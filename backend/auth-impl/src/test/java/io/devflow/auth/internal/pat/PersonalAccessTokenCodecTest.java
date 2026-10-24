package io.devflow.auth.internal.pat;

import static org.assertj.core.api.Assertions.assertThat;

import io.devflow.auth.internal.verification.VerificationTokenGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PersonalAccessTokenCodec Unit Tests")
class PersonalAccessTokenCodecTest {

    private final PersonalAccessTokenCodec codec = new PersonalAccessTokenCodec(new VerificationTokenGenerator());

    @Test
    @DisplayName("Generates dfp_ tokens whose public prefix is the first 16 chars and whose hash matches")
    void generatesRecognisableTokens() {
        PersonalAccessTokenCodec.GeneratedToken token = codec.generate();

        assertThat(token.raw()).matches("dfp_[0-9a-f]{12}[A-Za-z0-9_-]{43}")
                .hasSize(PersonalAccessTokenCodec.TOKEN_LENGTH);
        assertThat(token.publicPrefix()).isEqualTo(token.raw().substring(0, 16));
        assertThat(token.hash()).matches("[0-9a-f]{64}");
        assertThat(codec.matches(token.raw(), token.hash())).isTrue();
        assertThat(codec.publicPrefix(token.raw())).contains(token.publicPrefix());
        assertThat(codec.generate().raw()).isNotEqualTo(token.raw());
    }

    @Test
    @DisplayName("A token that differs in its secret does not match the stored hash")
    void rejectsAlteredSecret() {
        PersonalAccessTokenCodec.GeneratedToken token = codec.generate();
        String last = token.raw().substring(token.raw().length() - 1);
        String altered = token.raw().substring(0, token.raw().length() - 1) + ("A".equals(last) ? "B" : "A");

        assertThat(codec.matches(altered, token.hash())).isFalse();
    }

    @Test
    @DisplayName("Malformed values never reach a database lookup")
    void rejectsMalformedTokens() {
        String valid = codec.generate().raw();

        assertThat(codec.publicPrefix(null)).isEmpty();
        assertThat(codec.publicPrefix("dfp_")).isEmpty();
        assertThat(codec.publicPrefix(valid + "x")).isEmpty();
        assertThat(codec.publicPrefix(valid.replace("dfp_", "dfx_"))).isEmpty();
        assertThat(codec.publicPrefix("dfp_ZZZZZZZZZZZZ" + valid.substring(16))).isEmpty();
        assertThat(codec.publicPrefix(valid.substring(0, 20) + "!" + valid.substring(21))).isEmpty();
    }

    @Test
    @DisplayName("toString of a generated token never exposes the raw value")
    void toStringHidesRawToken() {
        PersonalAccessTokenCodec.GeneratedToken token = codec.generate();
        assertThat(token.toString()).doesNotContain(token.raw()).contains(token.publicPrefix());
    }
}
