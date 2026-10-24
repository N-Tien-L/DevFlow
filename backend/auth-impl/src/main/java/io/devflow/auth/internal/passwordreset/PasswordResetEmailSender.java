package io.devflow.auth.internal.passwordreset;

import io.devflow.auth.internal.support.AuthMetrics;
import io.devflow.auth.internal.support.LogMasking;
import io.devflow.auth.internal.verification.VerificationProperties;
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
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;
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
 * Renders and delivers the password reset link and the "password changed" notice over the same SMTP setup as
 * account verification (sender address and frontend URL come from {@link VerificationProperties}).
 *
 * <p>Wrapped by Resilience4j like the other auth mail: retries with exponential backoff behind a circuit breaker,
 * ending in a fallback that records the failure. Failures are logged by exception type and SMTP code only.
 */
@Component
public class PasswordResetEmailSender {

    static final String RESILIENCE_NAME = "passwordResetEmail";
    static final String KIND_RESET_LINK = "reset_link";
    static final String KIND_PASSWORD_CHANGED = "password_changed";

    private static final Logger log = LoggerFactory.getLogger(PasswordResetEmailSender.class);
    private static final String RESET_SUBJECT = "Reset your DevFlow password";
    private static final String CHANGED_SUBJECT = "Your DevFlow password was changed";
    private static final DateTimeFormatter CHANGED_AT_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final VerificationProperties mailProperties;
    private final AuthMetrics metrics;
    private final Clock clock;
    private final Map<String, String> templates;

    public PasswordResetEmailSender(
            ObjectProvider<JavaMailSender> mailSenderProvider,
            VerificationProperties mailProperties,
            AuthMetrics metrics,
            Clock clock) {
        this.mailSenderProvider = mailSenderProvider;
        this.mailProperties = mailProperties;
        this.metrics = metrics;
        this.clock = clock;
        this.templates = Map.of(
                "reset.html", loadTemplate("templates/email/reset-password.html"),
                "reset.txt", loadTemplate("templates/email/reset-password.txt"),
                "changed.html", loadTemplate("templates/email/password-changed.html"),
                "changed.txt", loadTemplate("templates/email/password-changed.txt"));
    }

    @Retry(name = RESILIENCE_NAME, fallbackMethod = "sendResetLinkFallback")
    @CircuitBreaker(name = RESILIENCE_NAME)
    public void sendResetLink(PasswordResetEmailRequestedEvent event) {
        long minutes = Math.max(1, Duration.between(clock.instant(), event.expiresAt()).toMinutes());
        Map<String, String> values = Map.of(
                "fullName", event.fullName(),
                "resetUrl", buildResetUrl(event.rawToken()),
                "expiresInMinutes", String.valueOf(minutes));
        send(KIND_RESET_LINK, event.userId(), event.email(), RESET_SUBJECT, "reset", values);
    }

    @Retry(name = RESILIENCE_NAME, fallbackMethod = "sendPasswordChangedFallback")
    @CircuitBreaker(name = RESILIENCE_NAME)
    public void sendPasswordChanged(PasswordChangedEmailRequestedEvent event) {
        Map<String, String> values = Map.of(
                "fullName", event.fullName(),
                "changedAt", CHANGED_AT_FORMAT.format(event.changedAt()),
                "forgotUrl", UriComponentsBuilder.fromUriString(mailProperties.getFrontendBaseUrl())
                        .path("/forgot-password").build().toUriString());
        send(KIND_PASSWORD_CHANGED, event.userId(), event.email(), CHANGED_SUBJECT, "changed", values);
    }

    @SuppressWarnings("unused") // invoked reflectively by Resilience4j
    void sendResetLinkFallback(PasswordResetEmailRequestedEvent event, Throwable cause) {
        fallback(KIND_RESET_LINK, event.userId(), cause);
    }

    @SuppressWarnings("unused") // invoked reflectively by Resilience4j
    void sendPasswordChangedFallback(PasswordChangedEmailRequestedEvent event, Throwable cause) {
        fallback(KIND_PASSWORD_CHANGED, event.userId(), cause);
    }

    String buildResetUrl(String rawToken) {
        return UriComponentsBuilder.fromUriString(mailProperties.getFrontendBaseUrl())
                .path("/reset-password")
                .queryParam("token", rawToken)
                .build()
                .toUriString();
    }

    private void send(String kind, UUID userId, String to, String subject, String template,
                      Map<String, String> values) {
        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        if (mailSender == null) {
            metrics.passwordResetEmail(kind, "failed");
            log.warn("No mail sender configured (spring.mail.host); {} email for user {} not sent", kind, userId);
            return;
        }

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(mailProperties.getMailFrom());
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(render(templates.get(template + ".txt"), values, false),
                    render(templates.get(template + ".html"), values, true));
            mailSender.send(message);
        } catch (MessagingException e) {
            // Malformed message (e.g. bad sender address config): not transient, so do not retry.
            metrics.passwordResetEmail(kind, "failed");
            log.error("Could not build {} email for user {}: {}", kind, userId, LogMasking.describeFailure(e));
            return;
        }

        metrics.passwordResetEmail(kind, "sent");
        log.info("{} email sent to {} (user {})", kind, LogMasking.maskEmail(to), userId);
    }

    private void fallback(String kind, UUID userId, Throwable cause) {
        metrics.passwordResetEmail(kind, "failed");
        log.error("{} email delivery failed for user {} after retries: {}",
                kind, userId, LogMasking.describeFailure(cause));
    }

    private static String render(String template, Map<String, String> values, boolean html) {
        String rendered = template;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String value = html ? HtmlUtils.htmlEscape(entry.getValue()) : entry.getValue();
            rendered = rendered.replace("{{" + entry.getKey() + "}}", value);
        }
        return rendered;
    }

    private static String loadTemplate(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Missing email template: " + path, e);
        }
    }
}
