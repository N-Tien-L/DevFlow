package io.devflow.auth.internal.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

import io.devflow.auth.internal.support.AuthMetrics;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;

@DisplayName("VerificationEmailSender Unit Tests")
class VerificationEmailSenderTest {

    private static final Instant NOW = Instant.parse("2026-10-06T10:00:00Z");

    private final JavaMailSender mailSender = mock(JavaMailSender.class);
    private final AuthMetrics metrics = mock(AuthMetrics.class);
    private final VerificationProperties properties = new VerificationProperties();

    @SuppressWarnings("unchecked")
    private VerificationEmailSender sender(JavaMailSender available) {
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(available);
        properties.setFrontendBaseUrl("https://app.devflow.io");
        return new VerificationEmailSender(provider, properties, metrics, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private VerificationEmailRequestedEvent event(String fullName) {
        return new VerificationEmailRequestedEvent(UUID.randomUUID(), "user@devflow.io", fullName,
                "tok_en-123", NOW.plus(Duration.ofHours(24)));
    }

    @Test
    @DisplayName("Builds the verification link from the configured frontend URL")
    void buildsVerificationUrl() {
        assertThat(sender(mailSender).buildVerificationUrl("tok_en-123"))
                .isEqualTo("https://app.devflow.io/verify-email?token=tok_en-123");
    }

    @Test
    @DisplayName("Sends a multipart email with the link and HTML-escaped name")
    void sendsEscapedMultipartEmail() throws Exception {
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage((Session) null));

        sender(mailSender).send(event("<img src=x onerror=alert(1)>"));

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        MimeMessage message = captor.getValue();
        message.saveChanges();
        Multipart content = (Multipart) message.getContent();
        String html = part(content, "text/html");
        String text = part(content, "text/plain");

        assertThat(message.getAllRecipients()[0].toString()).isEqualTo("user@devflow.io");
        assertThat(html).contains("https://app.devflow.io/verify-email?token=tok_en-123")
                .contains("&lt;img").doesNotContain("<img src=x")
                .contains("24 hours");
        assertThat(text).contains("https://app.devflow.io/verify-email?token=tok_en-123");
        verify(metrics).verificationEmail("sent");
    }

    @Test
    @DisplayName("Skips sending and records a failure when no mail sender is configured")
    void skipsWhenMailNotConfigured() {
        sender(null).send(event("Alex"));

        verify(mailSender, never()).send(any(MimeMessage.class));
        verify(metrics).verificationEmail("failed");
    }

    private static String part(Multipart multipart, String mimeType) throws Exception {
        for (int i = 0; i < multipart.getCount(); i++) {
            Part part = multipart.getBodyPart(i);
            if (part.isMimeType(mimeType)) {
                return (String) part.getContent();
            }
            if (part.getContent() instanceof Multipart nested) {
                String found = part(nested, mimeType);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
