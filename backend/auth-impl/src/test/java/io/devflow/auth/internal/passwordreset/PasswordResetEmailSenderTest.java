package io.devflow.auth.internal.passwordreset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.devflow.auth.internal.support.AuthMetrics;
import io.devflow.auth.internal.verification.VerificationProperties;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.SendFailedException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

@ExtendWith(OutputCaptureExtension.class)
@DisplayName("PasswordResetEmailSender Unit Tests")
class PasswordResetEmailSenderTest {

    private static final Instant NOW = Instant.parse("2026-10-24T10:00:00Z");

    private final JavaMailSender mailSender = mock(JavaMailSender.class);
    private final AuthMetrics metrics = mock(AuthMetrics.class);
    private final VerificationProperties properties = new VerificationProperties();

    @SuppressWarnings("unchecked")
    private PasswordResetEmailSender sender(JavaMailSender available) {
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(available);
        properties.setFrontendBaseUrl("https://app.devflow.io");
        return new PasswordResetEmailSender(provider, properties, metrics, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private PasswordResetEmailRequestedEvent resetEvent(String fullName) {
        return new PasswordResetEmailRequestedEvent(UUID.randomUUID(), "user@devflow.io", fullName, "rst_tok-123",
                NOW.plus(Duration.ofMinutes(30)));
    }

    @Test
    @DisplayName("Builds the reset link from the configured frontend URL")
    void buildsResetUrl() {
        assertThat(sender(mailSender).buildResetUrl("rst_tok-123"))
                .isEqualTo("https://app.devflow.io/reset-password?token=rst_tok-123");
    }

    @Test
    @DisplayName("Sends the reset link with the expiry in minutes and an HTML-escaped name")
    void sendsResetLink() throws Exception {
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage((Session) null));

        sender(mailSender).sendResetLink(resetEvent("<img src=x onerror=alert(1)>"));

        MimeMessage message = sentMessage();
        String html = part((Multipart) message.getContent(), "text/html");
        String text = part((Multipart) message.getContent(), "text/plain");
        assertThat(message.getSubject()).isEqualTo("Reset your DevFlow password");
        assertThat(message.getAllRecipients()[0].toString()).isEqualTo("user@devflow.io");
        assertThat(html).contains("https://app.devflow.io/reset-password?token=rst_tok-123")
                .contains("30 minutes").contains("&lt;img").doesNotContain("<img src=x");
        assertThat(text).contains("https://app.devflow.io/reset-password?token=rst_tok-123");
        verify(metrics).passwordResetEmail("reset_link", "sent");
    }

    @Test
    @DisplayName("Sends the password changed notice with the time and a link to reset again")
    void sendsPasswordChangedNotice() throws Exception {
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage((Session) null));

        sender(mailSender).sendPasswordChanged(
                new PasswordChangedEmailRequestedEvent(UUID.randomUUID(), "user@devflow.io", "Alex", NOW));

        MimeMessage message = sentMessage();
        String text = part((Multipart) message.getContent(), "text/plain");
        assertThat(message.getSubject()).isEqualTo("Your DevFlow password was changed");
        assertThat(text).contains("2026-10-24 10:00 UTC").contains("https://app.devflow.io/forgot-password")
                .contains("signed out on every device");
        verify(metrics).passwordResetEmail("password_changed", "sent");
    }

    @Test
    @DisplayName("Skips sending and records a failure when no mail sender is configured")
    void skipsWhenMailNotConfigured() {
        sender(null).sendResetLink(resetEvent("Alex"));

        verify(mailSender, never()).send(any(MimeMessage.class));
        verify(metrics).passwordResetEmail("reset_link", "failed");
    }

    @Test
    @DisplayName("A rejected recipient is logged by exception type and SMTP code, never by address or token")
    void fallbackLogNeverContainsRecipientAddress(CapturedOutput output) throws Exception {
        PasswordResetEmailRequestedEvent event = resetEvent("Alex");
        SMTPAddressFailedException rejected = new SMTPAddressFailedException(
                new InternetAddress("user@devflow.io"), "RCPT TO", 550,
                "5.1.1 <user@devflow.io>: Recipient address rejected");
        MailSendException failure = new MailSendException(
                Map.of(new Object(), new SendFailedException("Invalid Addresses", rejected)));

        sender(mailSender).sendResetLinkFallback(event, failure);

        assertThat(output).doesNotContain("user@devflow.io").doesNotContain("rst_tok-123")
                .contains("SMTPAddressFailedException(smtp=550)")
                .contains(event.userId().toString());
        verify(metrics).passwordResetEmail("reset_link", "failed");
    }

    @Test
    @DisplayName("Event toString never exposes the token or the email")
    void eventsHideSecrets() {
        assertThat(resetEvent("Alex").toString()).doesNotContain("rst_tok-123").doesNotContain("user@");
        assertThat(new PasswordChangedEmailRequestedEvent(UUID.randomUUID(), "user@devflow.io", "Alex", NOW)
                .toString()).doesNotContain("user@");
    }

    private MimeMessage sentMessage() throws Exception {
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        MimeMessage message = captor.getValue();
        message.saveChanges();
        return message;
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
