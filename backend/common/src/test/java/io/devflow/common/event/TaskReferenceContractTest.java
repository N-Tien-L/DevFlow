package io.devflow.common.event;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import io.devflow.common.event.TaskReferencesResolveRequestedEvent.Outcome;
import io.devflow.common.event.TaskReferencesResolveRequestedEvent.Resolution;

class TaskReferenceContractTest {
    @Test void exactlyOneImmutableCompleteResponse() {
        UUID workspace = UUID.randomUUID();
        var request = new TaskReferencesResolveRequestedEvent(workspace, Set.of("T-001"), UUID.randomUUID());
        assertThrows(IllegalStateException.class, request::requireResolutions);
        var response = Map.of("T-001", new Resolution(workspace, "T-001", UUID.randomUUID(), Outcome.ELIGIBLE));
        request.respond(response);
        assertEquals(response, request.requireResolutions());
        assertThrows(UnsupportedOperationException.class, () -> request.requireResolutions().clear());
        request.respond(response);
        assertThrows(IllegalStateException.class, request::requireResolutions);
    }
    @Test void rejectsForeignScopeIncompleteMappingAndInvalidEligibility() {
        UUID workspace = UUID.randomUUID();
        var request = new TaskReferencesResolveRequestedEvent(workspace, Set.of("T-001"), UUID.randomUUID());
        request.respond(Map.of("T-001", new Resolution(UUID.randomUUID(), "T-001", UUID.randomUUID(), Outcome.ELIGIBLE)));
        assertThrows(IllegalStateException.class, request::requireResolutions);
        var missing = new TaskReferencesResolveRequestedEvent(workspace, Set.of("T-001"), UUID.randomUUID());
        missing.respond(Map.of());
        assertThrows(IllegalStateException.class, missing::requireResolutions);
        assertThrows(IllegalArgumentException.class, () -> new Resolution(workspace,"T-001",null,Outcome.ELIGIBLE));
        assertThrows(IllegalArgumentException.class, () -> new TaskReferencesResolveRequestedEvent(workspace,Set.of("T-0003"),UUID.randomUUID()));
    }
}
