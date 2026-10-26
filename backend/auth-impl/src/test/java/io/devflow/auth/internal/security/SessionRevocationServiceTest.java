package io.devflow.auth.internal.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.devflow.auth.internal.passwordreset.PasswordResetProperties;
import io.devflow.auth.internal.repository.UserRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SessionRevocationServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final SessionRevocationService service =
            new SessionRevocationService(userRepository, new PasswordResetProperties());

    @Test
    void tokenIsRevokedExactlyWhenItsVersionDiffersFromTheStoredOne() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findTokenVersion(userId)).thenReturn(Optional.of(1));

        // A version-0 token is revoked whatever its issue time, including the same second as the reset.
        assertThat(service.isRevoked(userId, 0)).isTrue();
        assertThat(service.isRevoked(userId, 1)).isFalse();
    }

    @Test
    void tokensOfADeletedAccountAreRevoked() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findTokenVersion(userId)).thenReturn(Optional.empty());

        assertThat(service.isRevoked(userId, 0)).isTrue();
    }

    @Test
    void cachesTheVersionUntilEvicted() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findTokenVersion(userId)).thenReturn(Optional.of(0), Optional.of(1));

        assertThat(service.isRevoked(userId, 0)).isFalse();
        assertThat(service.isRevoked(userId, 0)).isFalse();
        verify(userRepository, times(1)).findTokenVersion(userId);

        service.evict(userId);
        assertThat(service.isRevoked(userId, 0)).isTrue();
        verify(userRepository, times(2)).findTokenVersion(userId);
    }
}
