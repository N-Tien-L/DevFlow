package io.devflow.auth.internal.workspace;

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
import java.util.Locale;
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
 * Renders and delivers the workspace invitation email over the same SMTP setup as account verification
 * (sender address and frontend URL come from {@link VerificationProperties}).
 *
 * <p>Wrapped by Resilience4j: transient SMTP failures are retried with exponential backoff, and a
 * circuit breaker stops hammering a provider that is down. Exhausted retries end in {@link #sendFallback}.
 * The subject is fixed so no user-controlled text reaches mail headers.
 */
@Component
public class InvitationEmailSender {

    static final String RESILIENCE_NAME = "invitationEmail";

    private static final Logger log = LoggerFactory.getLogger(InvitationEmailSender.class);
    private static final String SUBJECT = "You have been invited to a DevFlow workspace";

    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final VerificationProperties mailProperties;
    private final AuthMetrics metrics;
    private final Clock clock;
    private final String htmlTemplate;
    private final String textTemplate;

    public InvitationEmailSender(
            ObjectProvider<JavaMailSender> mailSenderProvider,
            VerificationProperties mailProperties,
            AuthMetrics metrics,
            Clock clock) {
        this.mailSenderProvider = mailSenderProvider;
        this.mailProperties = mailProperties;
        this.metrics = metrics;
        this.clock = clock;
        this.htmlTemplate = loadTemplate("templates/email/workspace-invitation.html");
        this.textTemplate = loadTemplate("templates/email/workspace-invitation.txt");
    }

    @Retry(name = RESILIENCE_NAME, fallbackMethod = "sendFallback")
    @CircuitBreaker(name = RESILIENCE_NAME)
    public void send(InvitationEmailRequestedEvent event) {
        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        if (mailSender == null) {
            metrics.workspaceInvitationEmail("failed");
            log.warn("No mail sender configured (spring.mail.host); invitation {} not emailed",
                    event.invitationId());
            return;
        }

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(mailProperties.getMailFrom());
            helper.setTo(event.email());
            helper.setSubject(SUBJECT);
            helper.setText(render(textTemplate, event, false), render(htmlTemplate, event, true));
            mailSender.send(message);
        } catch (MessagingException e) {
            // Malformed message (e.g. bad sender address config): not transient, so do not retry.
            metrics.workspaceInvitationEmail("failed");
            log.error("Could not build invitation email for invitation {}: {}",
                    event.invitationId(), e.getMessage());
            return;
        }

        metrics.workspaceInvitationEmail("sent");
        log.info("Invitation email sent to {} (invitation {})",
                LogMasking.maskEmail(event.email()), event.invitationId());
    }

    @SuppressWarnings("unused") // invoked reflectively by Resilience4j
    void sendFallback(InvitationEmailRequestedEvent event, Throwable cause) {
        metrics.workspaceInvitationEmail("failed");
        log.error("Invitation email delivery failed for invitation {} after retries: {}",
                event.invitationId(), cause.getClass().getSimpleName() + ": " + cause.getMessage());
    }

    String buildAcceptUrl(String rawToken) {
        return UriComponentsBuilder.fromUriString(mailProperties.getFrontendBaseUrl())
                .path("/invitations/accept")
                .queryParam("token", rawToken)
                .build()
                .toUriString();
    }

    private String render(String template, InvitationEmailRequestedEvent event, boolean html) {
        String url = buildAcceptUrl(event.rawToken());
        String role = event.role().toLowerCase(Locale.ROOT);
        long days = Math.max(1, Duration.between(clock.instant(), event.expiresAt()).toDays());
        return template
                .replace("{{inviterName}}", html ? HtmlUtils.htmlEscape(event.inviterName()) : event.inviterName())
                .replace("{{workspaceName}}",
                        html ? HtmlUtils.htmlEscape(event.workspaceName()) : event.workspaceName())
                .replace("{{role}}", role)
                .replace("{{acceptUrl}}", html ? HtmlUtils.htmlEscape(url) : url)
                .replace("{{expiresInDays}}", String.valueOf(days));
    }

    private static String loadTemplate(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Missing email template: " + path, e);
        }
    }
}
