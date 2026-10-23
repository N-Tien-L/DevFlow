package io.devflow.auth.api;

import java.util.List;

/** A stable page of members visible to a workspace member. */
public record WorkspaceMemberPageResponse(
        List<WorkspaceMemberSummary> items,
        int page,
        int size,
        long totalElements,
        int totalPages) {
    public WorkspaceMemberPageResponse {
        items = List.copyOf(items);
    }
}
