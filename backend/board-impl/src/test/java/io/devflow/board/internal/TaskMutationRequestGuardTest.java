package io.devflow.board.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TaskMutationRequestGuardTest {

    private final UUID actorId = UUID.randomUUID();
    private AuthenticatedActorProvider actorProvider;
    private HttpClient httpClient;

    @BeforeEach
    void setUp() {
        actorProvider = mock(AuthenticatedActorProvider.class);
        when(actorProvider.requireUserId()).thenReturn(actorId);
        httpClient = mock(HttpClient.class);
    }

    @Test
    void verifiesSiteverifyTokenWithBoundedRequestAndResponseTimeouts() throws Exception {
        HttpResponse<java.io.InputStream> response = successfulResponse("{\"success\":true}");
        doReturn(response).when(httpClient).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        TaskMutationRequestGuard guard = guard(true, "test secret");

        guard.authorizeMutation("single-use-token", "192.0.2.10");

        var request = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(request.capture(), any(HttpResponse.BodyHandler.class));
        assertThat(request.getValue().uri()).isEqualTo(URI.create("https://turnstile.test/siteverify"));
        assertThat(request.getValue().timeout()).contains(Duration.ofSeconds(3));
        assertThat(request.getValue().headers().firstValue("Content-Type"))
                .contains("application/x-www-form-urlencoded");
        assertThat(request.getValue().method()).isEqualTo("POST");
        verify(actorProvider).requireUserId();
    }

    @Test
    void rejectsMissingOrRejectedChallengeWithoutCallingTaskService() throws Exception {
        TaskMutationRequestGuard guard = guard(true, "test secret");
        assertThatThrownBy(() -> guard.authorizeMutation(" ", "192.0.2.10"))
                .isInstanceOf(BoardApiException.class)
                .extracting(exception -> ((BoardApiException) exception).code())
                .isEqualTo("BOT_CHALLENGE_REQUIRED");
        verify(httpClient, never()).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));

        HttpResponse<java.io.InputStream> rejected = successfulResponse("{\"success\":false}");
        doReturn(rejected).when(httpClient).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        assertThatThrownBy(() -> guard.authorizeMutation("replayed-token", "192.0.2.10"))
                .isInstanceOf(BoardApiException.class)
                .extracting(exception -> ((BoardApiException) exception).code())
                .isEqualTo("BOT_CHALLENGE_REJECTED");
    }

    @Test
    void failsClosedForMissingSecretProviderErrorAndMalformedResponse() throws Exception {
        TaskMutationRequestGuard missingSecret = guard(true, "");
        assertThatThrownBy(() -> missingSecret.authorizeMutation("token", "192.0.2.10"))
                .isInstanceOf(BoardApiException.class)
                .extracting(exception -> ((BoardApiException) exception).code())
                .isEqualTo("BOT_PROTECTION_UNAVAILABLE");
        verify(httpClient, never()).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));

        TaskMutationRequestGuard guard = guard(true, "test secret");
        doThrow(new IOException("provider unreachable"))
                .when(httpClient).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        assertThatThrownBy(() -> guard.authorizeMutation("token", "192.0.2.10"))
                .isInstanceOf(BoardApiException.class)
                .extracting(exception -> ((BoardApiException) exception).code())
                .isEqualTo("BOT_PROTECTION_UNAVAILABLE");

        HttpResponse<java.io.InputStream> malformed = successfulResponse("not-json");
        doReturn(malformed).when(httpClient).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        assertThatThrownBy(() -> guard.authorizeMutation("token", "192.0.2.10"))
                .isInstanceOf(BoardApiException.class)
                .extracting(exception -> ((BoardApiException) exception).code())
                .isEqualTo("BOT_PROTECTION_UNAVAILABLE");
    }

    @Test
    void localDisabledModeStillRequiresAnAuthenticatedActorAndSkipsProviderCall() throws Exception {
        TaskMutationRequestGuard guard = guard(false, "");

        guard.authorizeMutation(null, "127.0.0.1");

        verify(actorProvider).requireUserId();
        verify(httpClient, never()).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    private TaskMutationRequestGuard guard(boolean enabled, String secret) {
        return new TaskMutationRequestGuard(
                actorProvider,
                new BoardRateLimiter(60, 60),
                new ObjectMapper(),
                enabled,
                secret,
                URI.create("https://turnstile.test/siteverify"),
                httpClient);
    }

    private HttpResponse<java.io.InputStream> successfulResponse(String json) throws Exception {
        @SuppressWarnings("unchecked")
        HttpResponse<java.io.InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(new ByteArrayInputStream(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return response;
    }
}
