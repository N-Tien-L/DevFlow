package io.devflow.board.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class BoardRateLimiterTest {

    @Test
    void limitsEachActorIndependentlyAndReturnsRetryAfter() {
        BoardRateLimiter limiter = new BoardRateLimiter(1, 60);
        UUID firstActor = UUID.randomUUID();
        UUID secondActor = UUID.randomUUID();

        limiter.check(firstActor);
        assertThatThrownBy(() -> limiter.check(firstActor))
                .isInstanceOf(BoardApiException.class)
                .satisfies(error -> {
                    BoardApiException rateLimit = (BoardApiException) error;
                    assertThat(rateLimit.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(rateLimit.retryAfterSeconds()).isPositive();
                });

        limiter.check(secondActor);
    }

    @Test
    void rejectsInvalidLimitConfiguration() {
        assertThatThrownBy(() -> new BoardRateLimiter(0, 60))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BoardRateLimiter(1, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
