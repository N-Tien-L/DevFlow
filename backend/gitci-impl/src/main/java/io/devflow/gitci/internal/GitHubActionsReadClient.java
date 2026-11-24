package io.devflow.gitci.internal;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
class GitHubActionsReadClient {
    private static final String API = "api.github.com";
    private static final String API_VERSION = "2026-03-10";
    private static final int JSON_LIMIT = 2 * 1024 * 1024;
    private static final int PAGE_SIZE = 100;
    private final GitHubActionsProperties properties;
    private final CloseableHttpClient client;
    private final PublicDns dns;
    private final ScheduledThreadPoolExecutor deadlines = new ScheduledThreadPoolExecutor(1, task -> {
        Thread thread = new Thread(task, "ci-http-deadline");
        thread.setDaemon(true);
        return thread;
    });
    private final ObjectMapper mapper = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32).maxStringLength(65_536).build())
            .build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final CircuitBreaker breaker = CircuitBreaker.of("github-actions-read", CircuitBreakerConfig.custom()
            .slidingWindowSize(10).minimumNumberOfCalls(5).permittedNumberOfCallsInHalfOpenState(2)
            .waitDurationInOpenState(Duration.ofSeconds(30))
            .ignoreException(error -> error instanceof FetchFailure failure && failure.kind != Kind.TRANSIENT)
            .build());

    record Target(UUID repository, String remoteId, String url, String runId, int attempt, String sha) { }
    record Job(long id, String name, boolean failed, List<String> failedSteps) { }
    record Archive(byte[] bytes, List<Job> jobs, boolean truncated) { }
    enum Kind { TRANSIENT, NOT_FOUND, DENIED, UNAVAILABLE, CIRCUIT_OPEN }

    static final class FetchFailure extends RuntimeException {
        final Kind kind;
        final String code;
        final long delay;

        FetchFailure(Kind kind, String code) { this(kind, code, 0); }
        FetchFailure(Kind kind, String code, long delay) {
            super(code, null, false, false);
            this.kind = kind;
            this.code = code;
            this.delay = delay;
        }
    }

    @Autowired
    GitHubActionsReadClient(GitHubActionsProperties properties) {
        this(properties, null);
    }

    GitHubActionsReadClient(GitHubActionsProperties properties, CloseableHttpClient client) {
        this.properties = properties;
        dns = new PublicDns(properties.getSlots(), properties.getConnectSeconds());
        this.client = client == null ? httpClient(properties, dns) : client;
        deadlines.setRemoveOnCancelPolicy(true);
    }

    private static CloseableHttpClient httpClient(GitHubActionsProperties properties, PublicDns dns) {
        Timeout read = Timeout.ofSeconds(properties.getReadSeconds());
        var connections = PoolingHttpClientConnectionManagerBuilder.create().setDnsResolver(dns)
                .setMaxConnTotal(properties.getSlots()).setMaxConnPerRoute(properties.getSlots())
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.ofSeconds(properties.getConnectSeconds())).setSocketTimeout(read).build())
                .build();
        return HttpClients.custom().setConnectionManager(connections)
                .setDefaultRequestConfig(RequestConfig.custom().setResponseTimeout(read)
                        .setConnectionRequestTimeout(Timeout.ofSeconds(properties.getConnectSeconds()))
                        .setHardCancellationEnabled(true).build())
                .disableRedirectHandling().disableAutomaticRetries().disableCookieManagement()
                .disableAuthCaching().disableContentCompression().setUserAgent("DevFlow GitCI").build();
    }

    Archive fetch(Target target, long deadline) {
        String token = properties.token(target.repository(), target.remoteId());
        if (token == null) throw new FetchFailure(Kind.DENIED, "CREDENTIAL_NOT_CONFIGURED");
        try {
            return breaker.executeSupplier(() -> download(target, token, deadline));
        } catch (CallNotPermittedException ex) {
            throw new FetchFailure(Kind.CIRCUIT_OPEN, "CIRCUIT_OPEN", 30);
        }
    }

    private Archive download(Target target, String token, long deadline) {
        String endpoint = "https://" + API + "/repos/" + repositorySlug(target.url())
                + "/actions/runs/" + target.runId() + "/attempts/" + target.attempt();
        JsonNode run = json(endpoint, token, deadline, "RUN_NOT_FOUND");
        if (!sameInteger(run.path("id"), target.runId()) || !sameInteger(run.path("run_attempt"), target.attempt())
                || !sameInteger(run.path("repository").path("id"), target.remoteId())
                || !target.sha().equals(run.path("head_sha").textValue())
                || !"completed".equals(run.path("status").textValue()) || !"failure".equals(run.path("conclusion").textValue()))
            throw new FetchFailure(Kind.UNAVAILABLE, "RUN_IDENTITY_MISMATCH");

        var jobs = new ArrayList<Job>();
        var seen = new HashSet<Long>();
        boolean truncated = false;
        for (int page = 1; page <= properties.getMaxJobPages(); page++) {
            JsonNode response = json(endpoint + "/jobs?per_page=" + PAGE_SIZE + "&page=" + page, token, deadline, "JOBS_NOT_FOUND");
            JsonNode list = response.path("jobs");
            JsonNode total = response.path("total_count");
            if (!list.isArray() || list.size() > PAGE_SIZE || !total.isIntegralNumber() || total.bigIntegerValue().signum() < 0)
                throw new FetchFailure(Kind.UNAVAILABLE, "JOBS_FORMAT_INVALID");
            for (JsonNode job : list) {
                JsonNode id = job.path("id");
                JsonNode attempt = job.get("run_attempt");
                if (!sameInteger(job.path("run_id"), target.runId()) || !target.sha().equals(job.path("head_sha").textValue())
                        || (attempt != null && !sameInteger(attempt, target.attempt())) || !id.isIntegralNumber()
                        || !id.canConvertToLong() || id.longValue() <= 0 || !seen.add(id.longValue()))
                    throw new FetchFailure(Kind.UNAVAILABLE, "JOB_IDENTITY_MISMATCH");
                List<String> steps = new ArrayList<>();
                for (JsonNode step : job.path("steps")) {
                    String name = step.path("name").textValue();
                    if ("failure".equals(step.path("conclusion").textValue()) && name != null && name.length() <= 256)
                        steps.add(name);
                }
                String name = job.path("name").textValue();
                if (name == null || name.isBlank() || name.length() > 512)
                    throw new FetchFailure(Kind.UNAVAILABLE, "JOBS_FORMAT_INVALID");
                jobs.add(new Job(id.longValue(), name, "failure".equals(job.path("conclusion").textValue()), List.copyOf(steps)));
            }
            if (list.size() < PAGE_SIZE || page == properties.getMaxJobPages()) {
                truncated = total.bigIntegerValue().compareTo(java.math.BigInteger.valueOf(jobs.size())) > 0;
                break;
            }
        }
        String location = request(URI.create(endpoint + "/logs"), token, deadline, response -> {
            if (!isRedirect(response.getCode())) {
                check(response, "ARCHIVE_NOT_FOUND", false);
                throw new FetchFailure(Kind.UNAVAILABLE, "ARCHIVE_REDIRECT_REQUIRED");
            }
            return location(response);
        });
        var visited = new HashSet<URI>();
        for (int hop = 0; hop < properties.getMaxArchiveHops(); hop++) {
            URI uri = archiveUri(location, properties.getArchiveHosts());
            if (!visited.add(uri)) break;
            ArchiveResponse reply = request(uri, null, deadline, response -> {
                if (isRedirect(response.getCode())) return new ArchiveResponse(location(response), null);
                check(response, "ARCHIVE_NOT_FOUND", true);
                return new ArchiveResponse(null, body(response, properties.getMaxArchiveBytes()));
            });
            if (reply.bytes() != null) return new Archive(reply.bytes(), List.copyOf(jobs), truncated);
            location = reply.redirect();
        }
        throw new FetchFailure(Kind.UNAVAILABLE, "ARCHIVE_REDIRECT_LIMIT");
    }

    private JsonNode json(String url, String token, long deadline, String notFound) {
        return request(URI.create(url), token, deadline, response -> {
            check(response, notFound, false);
            try {
                JsonNode data = mapper.readTree(body(response, JSON_LIMIT));
                if (data == null || !data.isObject()) throw new FetchFailure(Kind.UNAVAILABLE, "GITHUB_FORMAT_INVALID");
                return data;
            }
            catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
                throw new FetchFailure(Kind.UNAVAILABLE, "GITHUB_FORMAT_INVALID");
            }
        });
    }

    private <T> T request(URI uri, String token, long deadline, org.apache.hc.core5.http.io.HttpClientResponseHandler<T> read) {
        HttpGet get = new HttpGet(uri);
        if (token != null) {
            get.setHeader("Authorization", "Bearer " + token);
            get.setHeader("Accept", "application/vnd.github+json");
            get.setHeader("X-GitHub-Api-Version", API_VERSION);
        }
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new FetchFailure(Kind.TRANSIENT, "CLAIM_TIMEOUT");
        var timer = deadlines.schedule(get::cancel, remaining, TimeUnit.NANOSECONDS);
        dns.deadline.set(deadline);
        try { return client.execute(get, read); }
        catch (UnknownHostException ex) { throw new FetchFailure(Kind.TRANSIENT, "GITHUB_DNS"); }
        catch (IOException ex) {
            throw new FetchFailure(Kind.TRANSIENT, get.isCancelled() ? "CLAIM_TIMEOUT" : "GITHUB_NETWORK");
        } finally {
            dns.deadline.remove();
            timer.cancel(false);
        }
    }

    private static byte[] body(ClassicHttpResponse response, int limit) throws IOException {
        if (response.getEntity() == null) throw new FetchFailure(Kind.UNAVAILABLE, "GITHUB_BODY_MISSING");
        if (response.getEntity().getContentLength() > limit) throw new FetchFailure(Kind.UNAVAILABLE, "LIMIT_EXCEEDED");
        try (var input = response.getEntity().getContent()) {
            byte[] data = input.readNBytes(limit + 1);
            if (data.length > limit) throw new FetchFailure(Kind.UNAVAILABLE, "LIMIT_EXCEEDED");
            return data;
        }
    }

    private static void check(ClassicHttpResponse response, String notFound, boolean archive) {
        int code = response.getCode();
        if (code == 200) return;
        String reset = header(response, "X-RateLimit-Reset");
        String retry = header(response, "Retry-After");
        if (code == 429 || (code == 403 && (!retry.isEmpty() || "0".equals(header(response, "X-RateLimit-Remaining")))))
            throw new FetchFailure(Kind.TRANSIENT, "GITHUB_THROTTLED", retryDelay(retry, reset, Instant.now()));
        if (code >= 500 || (archive && (code == 401 || code == 403)))
            throw new FetchFailure(Kind.TRANSIENT, archive && code < 500 ? "ARCHIVE_URL_EXPIRED" : "GITHUB_UPSTREAM");
        if (code == 401 || code == 403) throw new FetchFailure(Kind.DENIED, "ACTIONS_READ_DENIED");
        if (code == 404) throw new FetchFailure(Kind.NOT_FOUND, notFound);
        throw new FetchFailure(Kind.UNAVAILABLE, code == 410 ? "ARCHIVE_GONE" : "GITHUB_RESPONSE_INVALID");
    }

    static long retryDelay(String retry, String reset, Instant now) {
        long seconds = 0;
        try {
            seconds = retry.matches("[0-9]+") ? Long.parseLong(retry)
                    : Duration.between(now, ZonedDateTime.parse(retry, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).toSeconds();
        } catch (RuntimeException ignored) { /* Invalid upstream header. */ }
        try { seconds = Math.max(seconds, Duration.between(now, Instant.ofEpochSecond(Long.parseLong(reset))).toSeconds()); }
        catch (RuntimeException ignored) { /* Invalid upstream header. */ }
        return Math.max(0, Math.min(3600, seconds));
    }

    static URI archiveUri(String location, java.util.Set<String> hosts) {
        try {
            if (location.length() > 8192) throw new IllegalArgumentException();
            URI uri = URI.create(location);
            String host = uri.getHost();
            if (!"https".equals(uri.getScheme()) || host == null || !hosts.contains(host.toLowerCase(Locale.ROOT))
                    || uri.getRawUserInfo() != null || uri.getFragment() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)) throw new IllegalArgumentException();
            return uri;
        } catch (IllegalArgumentException ex) { throw new FetchFailure(Kind.UNAVAILABLE, "ARCHIVE_HOST_BLOCKED"); }
    }

    static String repositorySlug(String url) {
        try {
            URI repo = URI.create(url);
            if (!"https".equals(repo.getScheme()) || !"github.com".equalsIgnoreCase(repo.getHost())
                    || repo.getPort() != -1 || repo.getRawUserInfo() != null || repo.getRawQuery() != null || repo.getRawFragment() != null
                    || !repo.getRawPath().matches("/[A-Za-z0-9-]{1,100}/[A-Za-z0-9_.-]{1,100}/?"))
                throw new IllegalArgumentException();
            String slug = repo.getRawPath().substring(1).replaceFirst("/$", "").replaceFirst("\\.git$", "");
            String name = slug.substring(slug.indexOf('/') + 1);
            if (name.isEmpty() || name.equals(".") || name.equals("..")) throw new IllegalArgumentException();
            return slug;
        } catch (IllegalArgumentException ex) { throw new FetchFailure(Kind.UNAVAILABLE, "REPOSITORY_BINDING_INVALID"); }
    }

    private record ArchiveResponse(String redirect, byte[] bytes) { }
    private static boolean isRedirect(int code) { return code == 301 || code == 302 || code == 303 || code == 307 || code == 308; }
    private static boolean sameInteger(JsonNode node, String value) {
        return node.isIntegralNumber() && node.bigIntegerValue().toString().equals(value);
    }
    private static boolean sameInteger(JsonNode node, int value) { return sameInteger(node, Integer.toString(value)); }
    private static String location(ClassicHttpResponse response) {
        var locations = response.getHeaders("Location");
        if (locations.length != 1 || locations[0].getValue().isBlank())
            throw new FetchFailure(Kind.UNAVAILABLE, "ARCHIVE_REDIRECT_INVALID");
        return locations[0].getValue();
    }
    private static String header(ClassicHttpResponse response, String name) {
        var header = response.getFirstHeader(name);
        return header == null ? "" : header.getValue();
    }

    @PreDestroy
    void close() throws IOException {
        deadlines.shutdownNow();
        dns.pool.shutdownNow();
        client.close();
    }

    private static final class PublicDns implements DnsResolver {
        private final ThreadPoolExecutor pool;
        private final long timeoutNanos;
        private final ThreadLocal<Long> deadline = new ThreadLocal<>();

        PublicDns(int slots, int timeoutSeconds) {
            timeoutNanos = TimeUnit.SECONDS.toNanos(timeoutSeconds);
            pool = new ThreadPoolExecutor(0, slots, 30, TimeUnit.SECONDS, new SynchronousQueue<>(), task -> {
                Thread thread = new Thread(task, "ci-github-dns");
                thread.setDaemon(true);
                return thread;
            });
        }

        @Override public InetAddress[] resolve(String host) throws UnknownHostException {
            // Return the validated addresses directly to the connection manager. It does not resolve a second time.
            long remaining = deadline.get() == null ? timeoutNanos : Math.min(timeoutNanos, deadline.get() - System.nanoTime());
            if (remaining <= 0) throw new UnknownHostException();
            try {
                var lookup = pool.submit(() -> InetAddress.getAllByName(host));
                try {
                    InetAddress[] addresses = lookup.get(remaining, TimeUnit.NANOSECONDS);
                    for (InetAddress address : addresses) {
                        if (!publicAddress(address)) throw new FetchFailure(Kind.UNAVAILABLE, "GITHUB_DNS_BLOCKED");
                    }
                    return addresses;
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new UnknownHostException();
                } catch (ExecutionException | TimeoutException ex) {
                    throw new UnknownHostException();
                } finally { if (!lookup.isDone()) lookup.cancel(true); }
            } catch (RejectedExecutionException ex) {
                throw new UnknownHostException();
            }
        }
        @Override public String resolveCanonicalHostname(String host) { return host; }
    }

    static boolean publicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress();
        int first = Byte.toUnsignedInt(bytes[0]);
        int second = Byte.toUnsignedInt(bytes[1]);
        int third = Byte.toUnsignedInt(bytes[2]);
        if (bytes.length == 4) {
            return first > 0 && first < 224 && !(first == 100 && second >= 64 && second <= 127)
                    && !(first == 192 && second == 0) && !(first == 198 && (second == 18 || second == 19))
                    && !(first == 198 && second == 51 && third == 100) && !(first == 203 && second == 0 && third == 113);
        }
        return (first & 0xe0) == 0x20 && !(first == 0x20 && second == 2)
                && !(first == 0x20 && second == 1 && (third < 2 || (third == 0x0d && Byte.toUnsignedInt(bytes[3]) == 0xb8)));
    }
}
