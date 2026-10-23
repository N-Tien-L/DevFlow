package io.devflow.auth.api;

import java.util.UUID;

/** Minimal workspace member identity for assignment pickers. */
public record WorkspaceMemberSummary(UUID userId, String fullName, String avatarUrl) {}
