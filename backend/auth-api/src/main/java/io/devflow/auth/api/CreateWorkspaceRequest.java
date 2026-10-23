package io.devflow.auth.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request payload for creating a workspace. The caller becomes its {@code OWNER}.
 * The name is trimmed and stripped of HTML server-side; it must still hold 1-100 visible characters.
 */
public record CreateWorkspaceRequest(
        @NotBlank(message = "Workspace name is required")
        @Size(max = 100, message = "Workspace name must not exceed 100 characters")
        String name
) {}
