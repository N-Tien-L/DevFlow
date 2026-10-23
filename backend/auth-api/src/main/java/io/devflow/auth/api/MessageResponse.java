package io.devflow.auth.api;

/**
 * Generic human-readable acknowledgement for endpoints that return no resource.
 */
public record MessageResponse(String message) {}
