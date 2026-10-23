package io.devflow.ai.internal.llm;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;

/** The retry and circuit breaker guarding provider calls, wrapped so they are not injectable by bare type. */
public record AiResilience(Retry retry, CircuitBreaker circuitBreaker) {
}
