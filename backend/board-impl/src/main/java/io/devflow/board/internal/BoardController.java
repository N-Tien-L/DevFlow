package io.devflow.board.internal;

import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API for the Board module (ARCHITECTURE.md Section 5). All endpoints are stubs —
 * no business logic yet.
 *
 * <p>TODO(PRODUCT_SPEC Section 5.1): Trello-style board — status columns, card
 * create/edit/delete, drag-and-drop between columns, task details (assignee, due date,
 * labels/priority, description, comments), search &amp; filter. Real-time sync of board
 * changes to other viewers goes over the WebSocket gateway (Section 5).
 */
@RestController
@RequestMapping("/api/v1/boards")
public class BoardController {

    @GetMapping("/{boardId}")
    public ResponseEntity<Map<String, String>> getBoard(@PathVariable UUID boardId) {
        // TODO(PRODUCT_SPEC 5.1): return the board with its columns and cards.
        return todo("getBoard");
    }

    @PostMapping("/{boardId}/cards")
    public ResponseEntity<Map<String, String>> createCard(@PathVariable UUID boardId) {
        // TODO(PRODUCT_SPEC 5.1): create a card in the given column; publish TaskCreatedEvent.
        return todo("createCard");
    }

    @PatchMapping("/cards/{cardId}")
    public ResponseEntity<Map<String, String>> updateCard(@PathVariable UUID cardId) {
        // TODO(PRODUCT_SPEC 5.1): edit title/description/assignee/due date/labels.
        return todo("updateCard");
    }

    @PatchMapping("/cards/{cardId}/move")
    public ResponseEntity<Map<String, String>> moveCard(@PathVariable UUID cardId) {
        // TODO(PRODUCT_SPEC 5.1): drag-and-drop between columns;
        //   publish TaskStatusChangedEvent with cause MANUAL.
        return todo("moveCard");
    }

    private ResponseEntity<Map<String, String>> todo(String endpoint) {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                .body(Map.of(
                        "todo", endpoint + " — not implemented yet",
                        "spec", "PRODUCT_SPEC.md Section 5.1"));
    }
}
