package io.devflow.auth.internal.exception;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.devflow.auth.internal.TestAuthApplication;
import io.devflow.auth.internal.entity.UserEntity;
import io.devflow.auth.internal.repository.UserRepository;
import io.devflow.auth.internal.security.JwtTokenProvider;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Framework request errors keep their HTTP status (RFC 7807) instead of becoming a 500.
 */
@SpringBootTest(classes = {TestAuthApplication.class, FrameworkErrorStatusIntegrationTest.ProbeConfig.class})
@AutoConfigureMockMvc
class FrameworkErrorStatusIntegrationTest {

    private static final String EMAIL = "framework-errors@devflow.io";

    @TestConfiguration
    static class ProbeConfig {
        @RestController
        static class ProbeController {
            @GetMapping("/api/v1/test/error-probe")
            Map<String, Object> probe(@RequestParam("page") int page) {
                return Map.of("page", page);
            }

            @GetMapping("/api/v1/test/header-probe")
            Map<String, Object> header(@RequestHeader("X-Probe") String probe) {
                return Map.of("probe", probe);
            }

            @GetMapping("/api/v1/test/status-probe")
            Map<String, Object> status() {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "internal reason not for clients");
            }
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private UserRepository userRepository;

    private String bearer;

    @BeforeEach
    void signIn() {
        UserEntity user = userRepository.findByEmail(EMAIL)
                .orElseGet(() -> userRepository.save(new UserEntity(EMAIL, "not-a-real-hash", "Framework Errors")));
        bearer = "Bearer " + tokenProvider.generateAccessToken(user.getId(), user.getEmail());
    }

    @Test
    void unknownRouteIs404WhenSignedInAnd401WhenNot() throws Exception {
        problem(mockMvc.perform(get("/api/v1/does-not-exist").header(HttpHeaders.AUTHORIZATION, bearer)), 404)
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.title").value("Not Found"))
                .andExpect(jsonPath("$.instance").value("/api/v1/does-not-exist"))
                .andExpect(jsonPath("$.detail").value(not(containsString("does-not-exist"))));
        // Public auth prefix: no token needed to learn it does not exist.
        problem(mockMvc.perform(get("/api/v1/auth/no-such-endpoint")), 404);
        // Elsewhere, the security filter answers first: anonymous callers cannot probe which routes exist.
        mockMvc.perform(get("/api/v1/does-not-exist")).andExpect(status().isUnauthorized());
    }

    @Test
    void wrongMethodIs405WithAllowHeader() throws Exception {
        problem(mockMvc.perform(put("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{}")), 405)
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
                .andExpect(header().string(HttpHeaders.ALLOW, containsString("POST")));
        problem(mockMvc.perform(delete("/api/v1/auth/workspaces").header(HttpHeaders.AUTHORIZATION, bearer)), 405);
    }

    @Test
    void unsupportedContentTypeIs415() throws Exception {
        problem(mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.TEXT_PLAIN).content("hello")), 415)
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"))
                .andExpect(header().exists(HttpHeaders.ACCEPT));
    }

    @Test
    void malformedJsonAndMissingParametersAre400() throws Exception {
        problem(mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": ")), 400);
        problem(mockMvc.perform(get("/api/v1/test/error-probe").header(HttpHeaders.AUTHORIZATION, bearer)), 400)
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.detail").value(not(containsString("page"))));
        problem(mockMvc.perform(get("/api/v1/test/error-probe").param("page", "abc")
                .header(HttpHeaders.AUTHORIZATION, bearer)), 400);
        problem(mockMvc.perform(get("/api/v1/test/header-probe").header(HttpHeaders.AUTHORIZATION, bearer)), 400);
    }

    @Test
    void responseStatusExceptionKeepsItsStatusButNotItsReason() throws Exception {
        problem(mockMvc.perform(get("/api/v1/test/status-probe").header(HttpHeaders.AUTHORIZATION, bearer)), 409)
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(content().string(not(containsString("internal reason"))));
    }

    /** Asserts an RFC 7807 body with the given status and nothing internal in it. */
    private static ResultActions problem(ResultActions result, int expectedStatus) throws Exception {
        return result.andExpect(status().is(expectedStatus))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(expectedStatus))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.trace").doesNotExist())
                .andExpect(jsonPath("$.exception").doesNotExist())
                .andExpect(content().string(not(containsString("springframework"))));
    }
}
