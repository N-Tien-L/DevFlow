package io.devflow.ai.internal.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import io.devflow.ai.internal.AiHealthController;

class AiFrameworkErrorStatusTest {
    final String path = "/api/v1/ai/health";
    final AiHealthController controller = spy(new AiHealthController());
    MockMvc mvc;

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.standaloneSetup(controller, new ParameterProbe())
                .setControllerAdvice(new AiExceptionHandler(), new AiApiControllerAdvice()).build();
    }
    void throwFromController(Exception failure) {
        doAnswer(invocation -> { throw failure; }).when(controller).health();
    }

    @Test void unacceptableResponseMediaTypeIs406AfterControllerSelection() throws Exception {
        mvc.perform(get(path).accept(MediaType.TEXT_PLAIN))
                .andExpect(status().isNotAcceptable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(406));
    }

    static Stream<Arguments> frameworkErrors() {
        return Stream.of(
                Arguments.of(new HttpMediaTypeNotAcceptableException(List.of(MediaType.APPLICATION_JSON)), 406),
                Arguments.of(new HttpRequestMethodNotSupportedException("TRACE", List.of("GET")), 405),
                Arguments.of(new HttpMediaTypeNotSupportedException(MediaType.TEXT_PLAIN, List.of(MediaType.APPLICATION_JSON)), 415),
                Arguments.of(new MissingServletRequestParameterException("privateParameter", "privateType"), 400),
                Arguments.of(new NoResourceFoundException(HttpMethod.GET, "privateResource"), 404),
                Arguments.of(new ResponseStatusException(HttpStatus.CONFLICT, "privateReason"), 409),
                Arguments.of(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "privateReason"), 503));
    }

    @ParameterizedTest @MethodSource("frameworkErrors")
    void frameworkStatusAndHeadersSurviveScopedCatchAll(Exception failure, int expectedStatus) throws Exception {
        throwFromController(failure);
        var result = mvc.perform(get(path))
                .andExpect(status().is(expectedStatus))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(expectedStatus))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.trace").doesNotExist())
                .andExpect(jsonPath("$.exception").doesNotExist())
                .andReturn();
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("privateParameter", "privateType", "privateResource", "privateReason", "springframework");
        if (expectedStatus == 405) { assertThat(result.getResponse().getHeader(HttpHeaders.ALLOW)).contains("GET"); }
        if (expectedStatus == 415) { assertThat(result.getResponse().getHeader(HttpHeaders.ACCEPT)).contains("application/json"); }
    }

    @Test void unexpectedExceptionsStillReturnASanitized500() throws Exception {
        throwFromController(new IllegalStateException("privateReason"));
        var result = mvc.perform(get(path)).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500)).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("privateReason", "IllegalStateException");
    }

    @Test void malformedRequestParametersAre400() throws Exception {
        mvc.perform(get("/api/v1/ai/framework-parameter").param("page", "privateValue"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
    }

    @org.springframework.web.bind.annotation.RestController
    static class ParameterProbe {
        @org.springframework.web.bind.annotation.GetMapping("/api/v1/ai/framework-parameter")
        Map<String, Integer> parameter(@org.springframework.web.bind.annotation.RequestParam("page") int page) {
            return Map.of("page", page);
        }
    }

}
