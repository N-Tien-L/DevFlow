package io.devflow.auth.internal.security;

import io.devflow.auth.internal.TestAuthApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = {TestAuthApplication.class, SecurityConfigIntegrationTest.TestEndpointConfig.class})
@AutoConfigureMockMvc
@DisplayName("SecurityConfig Integration Tests")
class SecurityConfigIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    private final UUID testUserId = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private final String testEmail = "secured.user@devflow.io";

    @TestConfiguration
    static class TestEndpointConfig {
        @RestController
        static class TestSecuredController {
            @GetMapping("/api/v1/secure-test")
            public Map<String, String> secureTest(@AuthenticationPrincipal UserPrincipal principal) {
                return Map.of("userId", principal.getId().toString(), "email", principal.getEmail());
            }
        }
    }

    @Test
    @DisplayName("Public endpoint /api/v1/auth/health should be accessible anonymously")
    void publicHealthEndpoint_shouldBeAccessibleWithoutToken() throws Exception {
        mockMvc.perform(get("/api/v1/auth/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("UP")))
                .andExpect(jsonPath("$.module", is("auth")));
    }

    @Test
    @DisplayName("Protected endpoint without token should return 401 Unauthorized with JSON body")
    void protectedEndpoint_withoutToken_shouldReturn401Json() throws Exception {
        mockMvc.perform(get("/api/v1/secure-test"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status", is(401)))
                .andExpect(jsonPath("$.error", is("Unauthorized")))
                .andExpect(jsonPath("$.path", is("/api/v1/secure-test")));
    }

    @Test
    @DisplayName("Protected endpoint with invalid Bearer token should return 401 Unauthorized")
    void protectedEndpoint_withInvalidToken_shouldReturn401() throws Exception {
        mockMvc.perform(get("/api/v1/secure-test")
                        .header("Authorization", "Bearer invalid.jwt.token"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status", is(401)))
                .andExpect(jsonPath("$.error", is("Unauthorized")));
    }

    @Test
    @DisplayName("Protected endpoint with refresh token should be rejected with 401 Unauthorized")
    void protectedEndpoint_withRefreshToken_shouldReturn401() throws Exception {
        String refreshToken = tokenProvider.generateRefreshToken(testUserId, testEmail);

        mockMvc.perform(get("/api/v1/secure-test")
                        .header("Authorization", "Bearer " + refreshToken))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status", is(401)));
    }

    @Test
    @DisplayName("Protected endpoint with valid access token should succeed and resolve UserPrincipal")
    void protectedEndpoint_withValidAccessToken_shouldSucceed() throws Exception {
        String accessToken = tokenProvider.generateAccessToken(testUserId, testEmail);

        mockMvc.perform(get("/api/v1/secure-test")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId", is(testUserId.toString())))
                .andExpect(jsonPath("$.email", is(testEmail)));
    }

    @Test
    @DisplayName("Special auth endpoint /api/v1/auth/me without token should return 401 Unauthorized")
    void authMeEndpoint_withoutToken_shouldReturn401() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status", is(401)));
    }
}
