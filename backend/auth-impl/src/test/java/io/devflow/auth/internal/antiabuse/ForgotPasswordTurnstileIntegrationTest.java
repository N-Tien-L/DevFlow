package io.devflow.auth.internal.antiabuse;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.devflow.auth.internal.TestAuthApplication;
import io.devflow.auth.internal.security.RateLimiterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Forgot-password is a public form: with bot protection on, a request without a Turnstile token is rejected
 * before any account lookup (and without calling Siteverify, which is unreachable here on purpose).
 */
@SpringBootTest(classes = TestAuthApplication.class, properties = {
        "devflow.turnstile.enabled=true",
        "devflow.turnstile.secret-key=test-secret",
        "devflow.turnstile.siteverify-url=http://127.0.0.1:9/siteverify"
})
@AutoConfigureMockMvc
@DisplayName("Forgot password Turnstile integration")
class ForgotPasswordTurnstileIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RateLimiterService rateLimiterService;

    @BeforeEach
    void reset() {
        rateLimiterService.clearAll();
    }

    @Test
    @DisplayName("Missing Turnstile token is rejected with 400 BOT_CHALLENGE_FAILED")
    void missingTokenIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"someone@devflow.io\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BOT_CHALLENGE_FAILED"));
    }
}
