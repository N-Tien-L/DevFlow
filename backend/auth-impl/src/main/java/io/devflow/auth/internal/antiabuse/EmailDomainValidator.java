package io.devflow.auth.internal.antiabuse;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.devflow.auth.internal.exception.EmailDomainRejectedException;
import io.devflow.auth.internal.support.AuthMetrics;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.IDN;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Rejects registration emails whose domain is a known disposable-mail provider or cannot receive mail.
 *
 * <p>The MX check fails open: if DNS cannot answer, the registration continues, because the emailed
 * verification link still proves the address is reachable. Definite answers are cached.
 */
@Component
public class EmailDomainValidator {

    static final String DISPOSABLE_DOMAINS_RESOURCE = "anti-abuse/disposable-email-domains.txt";
    static final String DISPOSABLE_EMAIL = "DISPOSABLE_EMAIL";
    static final String EMAIL_DOMAIN_UNDELIVERABLE = "EMAIL_DOMAIN_UNDELIVERABLE";

    private static final Logger log = LoggerFactory.getLogger(EmailDomainValidator.class);
    private static final long MAX_CACHED_DOMAINS = 10_000;

    private final EmailDomainProperties properties;
    private final MxLookup mxLookup;
    private final AuthMetrics metrics;
    private final Set<String> blockedDomains;
    private final Cache<String, MxLookup.Result> mxCache;

    public EmailDomainValidator(EmailDomainProperties properties, MxLookup mxLookup, AuthMetrics metrics) {
        this.properties = properties;
        this.mxLookup = mxLookup;
        this.metrics = metrics;
        this.blockedDomains = loadBlockedDomains(properties);
        this.mxCache = Caffeine.newBuilder()
                .maximumSize(MAX_CACHED_DOMAINS)
                .expireAfterWrite(properties.getMxCacheTtl())
                .build();
    }

    /**
     * @param normalizedEmail trimmed, lower-cased address that already passed {@code @Email} validation
     * @throws EmailDomainRejectedException domain is disposable or has no mail exchanger
     */
    public void validate(String normalizedEmail) {
        String domain = asciiDomain(normalizedEmail);

        if (properties.isDisposableCheckEnabled() && isBlocked(domain)) {
            metrics.antiAbuse("disposable_email", "rejected");
            throw new EmailDomainRejectedException(DISPOSABLE_EMAIL,
                    "Disposable email addresses are not allowed. Please use a permanent email address.");
        }

        if (properties.isMxCheckEnabled()) {
            MxLookup.Result result = mxCache.getIfPresent(domain);
            if (result == null) {
                result = mxLookup.lookup(domain);
                if (result != MxLookup.Result.UNKNOWN) {
                    mxCache.put(domain, result);
                }
            }
            switch (result) {
                case HAS_MX -> metrics.antiAbuse("mx", "passed");
                case UNKNOWN -> metrics.antiAbuse("mx", "unknown");
                case NO_MX, NO_DOMAIN -> {
                    metrics.antiAbuse("mx", "rejected");
                    throw new EmailDomainRejectedException(EMAIL_DOMAIN_UNDELIVERABLE,
                            "This email domain cannot receive mail. Please check the address.");
                }
            }
        }
    }

    boolean isBlocked(String asciiDomain) {
        // Match the domain and every parent, so "x.mailinator.com" is caught by "mailinator.com".
        String candidate = asciiDomain;
        while (true) {
            if (blockedDomains.contains(candidate)) {
                return true;
            }
            int dot = candidate.indexOf('.');
            if (dot < 0) {
                return false;
            }
            candidate = candidate.substring(dot + 1);
        }
    }

    private static String asciiDomain(String email) {
        String domain = email.substring(email.lastIndexOf('@') + 1);
        if (domain.endsWith(".")) {
            domain = domain.substring(0, domain.length() - 1);
        }
        try {
            return IDN.toASCII(domain, IDN.ALLOW_UNASSIGNED).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            throw new EmailDomainRejectedException(EMAIL_DOMAIN_UNDELIVERABLE, "Invalid email domain.");
        }
    }

    private static Set<String> loadBlockedDomains(EmailDomainProperties properties) {
        Set<String> domains = new HashSet<>();
        try (InputStream in = new ClassPathResource(DISPOSABLE_DOMAINS_RESOURCE).getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            reader.lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .map(line -> line.toLowerCase(Locale.ROOT))
                    .forEach(domains::add);
        } catch (IOException e) {
            throw new UncheckedIOException("Missing disposable domain list: " + DISPOSABLE_DOMAINS_RESOURCE, e);
        }
        properties.getExtraBlockedDomains().stream()
                .map(domain -> domain.trim().toLowerCase(Locale.ROOT))
                .filter(domain -> !domain.isEmpty())
                .forEach(domains::add);
        log.info("Loaded {} blocked disposable email domains", domains.size());
        return Set.copyOf(domains);
    }
}
