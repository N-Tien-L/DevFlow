package io.devflow.ai.internal.ci;

import io.devflow.ai.internal.config.AiProperties;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * Resolves raw CI failure logs from {@code rawLogReference} (allowlisted HTTPS URL or inline log
 * text) with explicit timeouts, SSRF protections, and strict byte caps (T-031).
 *
 * <p>Local filesystem ({@code file:}, filesystem paths) and {@code classpath:} references are
 * explicitly rejected so untrusted webhook payloads cannot read local server files.
 */
@Component
public class CiLogFetcher {

    private static final Logger log = LoggerFactory.getLogger(CiLogFetcher.class);
    static final int MAX_FETCH_BYTES = 512 * 1024; // 512 KB cap

    private final RestClient restClient;
    private final List<String> allowedHosts;

    @Autowired
    public CiLogFetcher(AiProperties properties) {
        this(defaultRestClient(), properties != null ? properties.getCiSummary().getAllowedLogHosts() : List.of());
    }

    public CiLogFetcher(RestClient restClient, List<String> allowedHosts) {
        this.restClient = restClient;
        this.allowedHosts = allowedHosts == null
                ? List.of()
                : allowedHosts.stream()
                        .filter(StringUtils::hasText)
                        .map(host -> host.strip().toLowerCase(Locale.ROOT))
                        .toList();
    }

    private static RestClient defaultRestClient() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(15));
        return RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }

    /**
     * Resolves the raw CI log content from an allowlisted HTTPS URL or inline log text.
     */
    public String fetch(String rawLogReference) {
        if (!StringUtils.hasText(rawLogReference)) {
            return "";
        }

        String ref = rawLogReference.strip();
        String lower = ref.toLowerCase(Locale.ROOT);

        // Explicitly block local file and classpath references (LFI prevention)
        if (lower.startsWith("file:") || lower.startsWith("classpath:")) {
            log.warn("Rejected disallowed local CI log reference scheme");
            return "";
        }

        // Plain HTTP is rejected; external log fetches must use HTTPS to allowlisted hosts
        if (lower.startsWith("http://")) {
            log.warn("Rejected insecure HTTP CI log reference");
            return "";
        }

        if (lower.startsWith("https://")) {
            return fetchHttps(ref);
        }

        // Inline log text, capped to MAX_FETCH_BYTES characters
        return ref.length() > MAX_FETCH_BYTES ? ref.substring(0, MAX_FETCH_BYTES) : ref;
    }

    private String fetchHttps(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            log.warn("Rejected malformed CI log URL: {}", e.getMessage());
            return "";
        }

        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null) {
            log.warn("Rejected CI log URL with invalid scheme or userinfo");
            return "";
        }

        String host = uri.getHost();
        if (!StringUtils.hasText(host) || !isAllowedHost(host)) {
            log.warn("Rejected CI log URL from non-allowlisted host={}", host);
            return "";
        }

        if (!resolvesToPublicAddresses(host)) {
            log.warn("Rejected CI log URL resolving to non-public address host={}", host);
            return "";
        }

        String safeLogTarget = uri.getScheme() + "://" + host + (uri.getPath() != null ? uri.getPath() : "");
        try {
            log.info("Fetching CI log via HTTPS from {}", safeLogTarget);
            String body = restClient.get()
                    .uri(uri)
                    .exchange((request, response) -> {
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            log.warn("Failed to fetch CI log from {} status={}",
                                    safeLogTarget, response.getStatusCode().value());
                            return "";
                        }
                        try (InputStream in = response.getBody()) {
                            byte[] bytes = in.readNBytes(MAX_FETCH_BYTES);
                            return new String(bytes, StandardCharsets.UTF_8);
                        }
                    });
            return body != null ? body : "";
        } catch (Exception e) {
            log.warn("Failed to fetch CI log from {}: {}", safeLogTarget, e.getMessage());
            return "";
        }
    }

    private boolean isAllowedHost(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        for (String allowed : allowedHosts) {
            if (normalized.equals(allowed) || normalized.endsWith("." + allowed)) {
                return true;
            }
        }
        return false;
    }

    boolean resolvesToPublicAddresses(String host) {
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses.length == 0) {
                return false;
            }
            for (InetAddress address : addresses) {
                if (isNonPublicAddress(address)) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isNonPublicAddress(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int b0 = bytes[0] & 0xFF;
            int b1 = bytes[1] & 0xFF;
            // Carrier-grade NAT 100.64.0.0/10
            if (b0 == 100 && (b1 & 0xC0) == 64) {
                return true;
            }
        } else if (bytes.length == 16) {
            // IPv6 Unique Local Address fc00::/7
            if ((bytes[0] & 0xFE) == 0xFC) {
                return true;
            }
        }
        return false;
    }
}
