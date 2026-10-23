package io.devflow.auth.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;

import io.devflow.auth.internal.entity.UserEntity;
import io.devflow.auth.internal.entity.UserStatus;
import io.devflow.auth.internal.entity.VerificationTokenEntity;
import io.devflow.auth.internal.entity.VerificationTokenType;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class VerificationTokenRepositoryTest {

    @Autowired
    private VerificationTokenRepository tokenRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TestEntityManager entityManager;

    private UserEntity persistUser(String email, boolean active) {
        UserEntity user = new UserEntity(email, "hash", "User");
        if (active) {
            user.activate(Instant.now());
        }
        return entityManager.persistAndFlush(user);
    }

    private VerificationTokenEntity persistToken(UserEntity user, String hash, Instant expiresAt) {
        return entityManager.persistAndFlush(
                new VerificationTokenEntity(user, hash, VerificationTokenType.EMAIL_VERIFICATION, expiresAt));
    }

    @Test
    @DisplayName("findByTokenHash fetches the owning user")
    void findByTokenHash_fetchesUser() {
        UserEntity user = persistUser("a@devflow.io", false);
        persistToken(user, "a".repeat(64), Instant.now().plus(Duration.ofHours(1)));
        entityManager.clear();

        VerificationTokenEntity found = tokenRepository.findByTokenHash("a".repeat(64)).orElseThrow();

        assertThat(found.getUser().getEmail()).isEqualTo("a@devflow.io");
        assertThat(tokenRepository.findByTokenHash("b".repeat(64))).isEmpty();
    }

    @Test
    @DisplayName("markUsed consumes a token exactly once")
    void markUsed_isSingleUse() {
        UserEntity user = persistUser("b@devflow.io", false);
        VerificationTokenEntity token = persistToken(user, "c".repeat(64), Instant.now().plus(Duration.ofHours(1)));
        Instant now = Instant.now();

        assertThat(tokenRepository.markUsed(token.getId(), now)).isEqualTo(1);
        assertThat(tokenRepository.markUsed(token.getId(), now)).isZero();
        assertThat(tokenRepository.findById(token.getId()).orElseThrow().isUsed()).isTrue();
    }

    @Test
    @DisplayName("invalidateActiveTokens only touches the user's unused tokens")
    void invalidateActiveTokens_scopedToUser() {
        UserEntity user = persistUser("c@devflow.io", false);
        UserEntity other = persistUser("d@devflow.io", false);
        Instant expiry = Instant.now().plus(Duration.ofHours(1));
        persistToken(user, "d".repeat(64), expiry);
        persistToken(user, "e".repeat(64), expiry);
        VerificationTokenEntity otherToken = persistToken(other, "f".repeat(64), expiry);

        int invalidated = tokenRepository.invalidateActiveTokens(
                user.getId(), VerificationTokenType.EMAIL_VERIFICATION, Instant.now());

        assertThat(invalidated).isEqualTo(2);
        assertThat(tokenRepository.findById(otherToken.getId()).orElseThrow().isUsed()).isFalse();
    }

    @Test
    @DisplayName("deleteStaleTokens removes expired and long-used tokens, keeps live ones")
    void deleteStaleTokens_keepsLiveTokens() {
        UserEntity user = persistUser("e@devflow.io", false);
        Instant now = Instant.now();
        persistToken(user, "1".repeat(64), now.minus(Duration.ofDays(10)));
        VerificationTokenEntity live = persistToken(user, "2".repeat(64), now.plus(Duration.ofHours(1)));

        int deleted = tokenRepository.deleteStaleTokens(now.minus(Duration.ofDays(7)));
        entityManager.clear();

        assertThat(deleted).isEqualTo(1);
        assertThat(tokenRepository.findAll()).extracting(VerificationTokenEntity::getId).containsExactly(live.getId());
    }

    @Test
    @DisplayName("Deleting abandoned pending users cascades to their tokens and spares active users")
    void deleteUnverifiedUsers_cascadesTokens() {
        UserEntity pending = persistUser("pending@devflow.io", false);
        UserEntity active = persistUser("active@devflow.io", true);
        persistToken(pending, "3".repeat(64), Instant.now().plus(Duration.ofHours(1)));
        entityManager.clear();

        int deleted = userRepository.deleteByStatusAndCreatedAtBefore(
                UserStatus.PENDING_VERIFICATION, Instant.now().plus(Duration.ofMinutes(1)));
        entityManager.clear();

        assertThat(deleted).isEqualTo(1);
        assertThat(userRepository.findById(pending.getId())).isEmpty();
        assertThat(userRepository.findById(active.getId())).isPresent();
        assertThat(tokenRepository.count()).isZero();
    }
}
