package io.devflow.auth.internal.verification;

import io.devflow.auth.internal.support.AuthMetrics;
import io.devflow.auth.internal.support.LogMasking;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Renders and delivers the account verification email over SMTP.
 *
 * <p>Wrapped by Resilience4j: transient SMTP failures are retried with exponential backoff, and a
 * circuit breaker stops hammering a provider that is down. Exhausted retries end in
 * {@link #sendFallback}, which records the failure; the user can always request a resend.
 */
@Component
public class VerificationEmailSender {

    static final String RESILIENCE_NAME = "verificationEmail";

    private static final Logger log = LoggerFactory.getLogger(VerificationEmailSender.class);
    private static final String SUBJECT = "Verify your DevFlow account";

    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final VerificationProperties properties;
    private final AuthMetrics metrics;
    private final Clock clock;
    private final String htmlTemplate;
    private final String textTemplate;

    public VerificationEmailSender(
            ObjectProvider<JavaMailSender> mailSenderProvider,
            VerificationProperties properties,
            AuthMetrics metrics,
            Clock clock) {
        this.mailSenderProvider = mailSenderProvider;
        this.properties = properties;
        this.metrics = metrics;
        this.clock = clock;
        this.htmlTemplate = loadTemplate("templates/email/verify-email.html");
        this.textTemplate = loadTemplate("templates/email/verify-email.txt");
    }

    @Retry(name = RESILIENCE_NAME, fallbackMethod = "sendFallback")
    @CircuitBreaker(name = RESILIENCE_NAME)
    public void send(VerificationEmailRequestedEvent event) {
        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        if (mailSender == null) {
            metrics.verificationEmail("failed");
            log.warn("No mail sender configured (spring.mail.host); verification email for user {} not sent",
                    event.userId());
            return;
        }

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(properties.getMailFrom());
            helper.setTo(event.email());
            helper.setSubject(SUBJECT);
            helper.setText(render(textTemplate, event, false), render(htmlTemplate, event, true));
            mailSender.send(message);
        } catch (MessagingException e) {
            // Malformed message (e.g. bad sender address config): not transient, so do not retry.
            metrics.verificationEmail("failed");
            log.error("Could not build verification email for user {}: {}",
                    event.userId(), LogMasking.describeFailure(e));
            return;
        }

        metrics.verificationEmail("sent");
        log.info("Verification email sent to {} (user {})", LogMasking.maskEmail(event.email()), event.userId());
    }

    @SuppressWarnings("unused") // invoked reflectively by Resilience4j
    void sendFallback(VerificationEmailRequestedEvent event, Throwable cause) {
        metrics.verificationEmail("failed");
        log.error("Verification email delivery failed for user {} after retries: {}",
                event.userId(), LogMasking.describeFailure(cause));
    }

    String buildVerificationUrl(String rawToken) {
        return UriComponentsBuilder.fromUriString(properties.getFrontendBaseUrl())
                .path("/verify-email")
                .queryParam("token", rawToken)
                .build()
                .toUriString();
    }

    private String render(String template, VerificationEmailRequestedEvent event, boolean html) {
        String url = buildVerificationUrl(event.rawToken());
        String name = event.fullName();
        long hours = Math.max(1, Duration.between(clock.instant(), event.expiresAt()).toHours());
        return template
                .replace("{{fullName}}", html ? HtmlUtils.htmlEscape(name) : name)
                .replace("{{verifyUrl}}", html ? HtmlUtils.htmlEscape(url) : url)
                .replace("{{expiresInHours}}", String.valueOf(hours));
    }

    private static String loadTemplate(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Missing email template: " + path, e);
        }
    }
}
