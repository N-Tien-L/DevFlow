package io.devflow.auth.internal.antiabuse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.devflow.auth.internal.exception.EmailDomainRejectedException;
import io.devflow.auth.internal.support.AuthMetrics;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("EmailDomainValidator Unit Tests")
class EmailDomainValidatorTest {

    private EmailDomainProperties properties;
    private MxLookup mxLookup;
    private AuthMetrics metrics;

    @BeforeEach
    void setUp() {
        properties = new EmailDomainProperties();
        mxLookup = mock(MxLookup.class);
        metrics = mock(AuthMetrics.class);
    }

    private EmailDomainValidator validator() {
        return new EmailDomainValidator(properties, mxLookup, metrics);
    }

    @Test
    @DisplayName("Rejects bundled disposable domains and their subdomains without a DNS lookup")
    void rejectsDisposableDomains() {
        EmailDomainValidator validator = validator();

        assertThatThrownBy(() -> validator.validate("bot@mailinator.com"))
                .isInstanceOf(EmailDomainRejectedException.class)
                .extracting("code").isEqualTo(EmailDomainValidator.DISPOSABLE_EMAIL);
        assertThatThrownBy(() -> validator.validate("bot@inbox.yopmail.com"))
                .isInstanceOf(EmailDomainRejectedException.class)
                .extracting("field").isEqualTo("email");

        verify(mxLookup, never()).lookup(org.mockito.ArgumentMatchers.anyString());
        verify(metrics, times(2)).antiAbuse("disposable_email", "rejected");
    }

    @Test
    @DisplayName("Does not treat a domain that merely ends with a blocked name as blocked")
    void matchesWholeLabelsOnly() {
        when(mxLookup.lookup("notmailinator.com")).thenReturn(MxLookup.Result.HAS_MX);

        assertThatCode(() -> validator().validate("dev@notmailinator.com")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Applies deployment-specific extra blocked domains")
    void rejectsExtraBlockedDomains() {
        properties.setExtraBlockedDomains(List.of(" Spam-Corp.example "));

        assertThatThrownBy(() -> validator().validate("x@spam-corp.example"))
                .isInstanceOf(EmailDomainRejectedException.class);
    }

    @Test
    @DisplayName("Accepts a domain with MX records and caches the answer")
    void acceptsAndCachesMx() {
        when(mxLookup.lookup("devflow.io")).thenReturn(MxLookup.Result.HAS_MX);
        EmailDomainValidator validator = validator();

        validator.validate("a@devflow.io");
        validator.validate("b@devflow.io");

        verify(mxLookup, times(1)).lookup("devflow.io");
    }

    @Test
    @DisplayName("Rejects domains with no MX, a null MX or that do not exist")
    void rejectsUndeliverableDomains() {
        when(mxLookup.lookup("no-mx.example")).thenReturn(MxLookup.Result.NO_MX);
        when(mxLookup.lookup("missing.example")).thenReturn(MxLookup.Result.NO_DOMAIN);
        EmailDomainValidator validator = validator();

        assertThatThrownBy(() -> validator.validate("a@no-mx.example"))
                .isInstanceOf(EmailDomainRejectedException.class)
                .extracting("code").isEqualTo(EmailDomainValidator.EMAIL_DOMAIN_UNDELIVERABLE);
        assertThatThrownBy(() -> validator.validate("a@missing.example"))
                .isInstanceOf(EmailDomainRejectedException.class);
    }

    @Test
    @DisplayName("Fails open when DNS cannot answer, and retries the lookup next time")
    void failsOpenOnDnsFailure() {
        when(mxLookup.lookup("slow-dns.example")).thenReturn(MxLookup.Result.UNKNOWN);
        EmailDomainValidator validator = validator();

        assertThatCode(() -> validator.validate("a@slow-dns.example")).doesNotThrowAnyException();
        validator.validate("b@slow-dns.example");

        verify(mxLookup, times(2)).lookup("slow-dns.example");
        verify(metrics, times(2)).antiAbuse("mx", "unknown");
    }

    @Test
    @DisplayName("Looks up internationalised domains in their ASCII (punycode) form")
    void convertsIdnToAscii() {
        when(mxLookup.lookup("xn--bcher-kva.example")).thenReturn(MxLookup.Result.HAS_MX);

        validator().validate("leser@bücher.example");

        verify(mxLookup).lookup("xn--bcher-kva.example");
    }

    @Test
    @DisplayName("Skips the MX lookup when disabled")
    void mxCheckCanBeDisabled() {
        properties.setMxCheckEnabled(false);

        validator().validate("a@devflow.io");

        verify(mxLookup, never()).lookup(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("Bundled list is loaded and non-trivial")
    void bundledListLoaded() {
        EmailDomainValidator validator = validator();

        assertThat(validator.isBlocked("guerrillamail.com")).isTrue();
        assertThat(validator.isBlocked("gmail.com")).isFalse();
    }
}
