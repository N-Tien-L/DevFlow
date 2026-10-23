package io.devflow.auth.internal.workspace;

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

@DisplayName("InvitationEmailSender Unit Tests")
class InvitationEmailSenderTest {

    private static final Instant NOW = Instant.parse("2026-10-23T10:00:00Z");

    private final JavaMailSender mailSender = mock(JavaMailSender.class);
    private final AuthMetrics metrics = mock(AuthMetrics.class);
    private final VerificationProperties properties = new VerificationProperties();

    @SuppressWarnings("unchecked")
    private InvitationEmailSender sender(JavaMailSender available) {
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(available);
        properties.setFrontendBaseUrl("https://app.devflow.io");
        return new InvitationEmailSender(provider, properties, metrics, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private InvitationEmailRequestedEvent event(String inviterName, String workspaceName) {
        return new InvitationEmailRequestedEvent(UUID.randomUUID(), "invitee@devflow.io", inviterName,
                workspaceName, "MEMBER", "inv_tok-123", NOW.plus(Duration.ofDays(7)));
    }

    @Test
    @DisplayName("Builds the accept link from the configured frontend URL")
    void buildsAcceptUrl() {
        assertThat(sender(mailSender).buildAcceptUrl("inv_tok-123"))
                .isEqualTo("https://app.devflow.io/invitations/accept?token=inv_tok-123");
    }

    @Test
    @DisplayName("Sends a multipart email with the link, a fixed subject and HTML-escaped names")
    void sendsEscapedMultipartEmail() throws Exception {
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage((Session) null));

        sender(mailSender).send(event("<img src=x onerror=alert(1)>", "Team <script>"));

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        MimeMessage message = captor.getValue();
        message.saveChanges();
        Multipart content = (Multipart) message.getContent();
        String html = part(content, "text/html");
        String text = part(content, "text/plain");

        assertThat(message.getAllRecipients()[0].toString()).isEqualTo("invitee@devflow.io");
        assertThat(message.getSubject()).isEqualTo("You have been invited to a DevFlow workspace");
        assertThat(html).contains("https://app.devflow.io/invitations/accept?token=inv_tok-123")
                .contains("&lt;img").doesNotContain("<img src=x")
                .contains("Team &lt;script&gt;").doesNotContain("<script>")
                .contains("as a member")
                .contains("7 days");
        assertThat(text).contains("https://app.devflow.io/invitations/accept?token=inv_tok-123");
        verify(metrics).workspaceInvitationEmail("sent");
    }

    @Test
    @DisplayName("Skips sending and records a failure when no mail sender is configured")
    void skipsWhenMailNotConfigured() {
        sender(null).send(event("Alex", "Team"));

        verify(mailSender, never()).send(any(MimeMessage.class));
        verify(metrics).workspaceInvitationEmail("failed");
    }

    @Test
    @DisplayName("The event's toString never exposes the token or the email")
    void eventToStringHidesSecrets() {
        assertThat(event("Alex", "Team").toString()).doesNotContain("inv_tok-123").doesNotContain("invitee@");
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
