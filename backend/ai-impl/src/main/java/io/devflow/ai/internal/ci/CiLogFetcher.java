package io.devflow.ai.internal.ci;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * Fetches raw CI failure logs referenced by {@code rawLogReference} (URL, classpath, file path,
 * or inline text) with explicit timeouts and size caps (T-031).
 */
@Component
public class CiLogFetcher {

    private static final Logger log = LoggerFactory.getLogger(CiLogFetcher.class);
    private static final int MAX_FETCH_BYTES = 512 * 1024; // 512 KB cap

    private final RestClient restClient;

    public CiLogFetcher() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(15));
        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }

    public CiLogFetcher(RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * Resolves the raw CI log content from a reference.
     */
    public String fetch(String rawLogReference) {
        if (!StringUtils.hasText(rawLogReference)) {
            return "";
        }

        String ref = rawLogReference.strip();

        // 1. HTTP / HTTPS URL
        if (ref.startsWith("http://") || ref.startsWith("https://")) {
            return fetchHttp(ref);
        }

        // 2. Classpath resource: classpath:path/to/log
        if (ref.startsWith("classpath:")) {
            return fetchClasspath(ref.substring("classpath:".length()));
        }

        // 3. File URI: file:///path or file:/path
        if (ref.startsWith("file:")) {
            try {
                return fetchFile(Paths.get(URI.create(ref)));
            } catch (Exception e) {
                log.warn("Failed to parse file URI {}: {}", ref, e.getMessage());
            }
        }

        // 4. File system path check
        try {
            Path path = Paths.get(ref);
            if (Files.isRegularFile(path)) {
                return fetchFile(path);
            }
        } catch (Exception ignored) {
            // Not a valid path string; proceed to inline text handling
        }

        // 5. Direct inline log content
        return ref;
    }

    private String fetchHttp(String url) {
        try {
            log.info("Fetching CI log via HTTP from {}", url);
            String body = restClient.get()
                    .uri(url)
                    .retrieve()
                    .body(String.class);

            if (body == null) {
                return "";
            }
            if (body.length() > MAX_FETCH_BYTES) {
                return body.substring(0, MAX_FETCH_BYTES);
            }
            return body;
        } catch (Exception e) {
            log.warn("Failed to fetch CI log from URL {}: {}", url, e.getMessage());
            return "";
        }
    }

    private String fetchClasspath(String resourcePath) {
        try {
            ClassPathResource resource = new ClassPathResource(resourcePath);
            try (InputStream in = resource.getInputStream()) {
                byte[] bytes = in.readNBytes(MAX_FETCH_BYTES);
                return new String(bytes, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            log.warn("Failed to load CI log from classpath {}: {}", resourcePath, e.getMessage());
            return "";
        }
    }

    private String fetchFile(Path path) {
        try {
            byte[] bytes = Files.readAllBytes(path);
            if (bytes.length > MAX_FETCH_BYTES) {
                return new String(bytes, 0, MAX_FETCH_BYTES, StandardCharsets.UTF_8);
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("Failed to read CI log from file {}: {}", path, e.getMessage());
            return "";
        }
    }
}
