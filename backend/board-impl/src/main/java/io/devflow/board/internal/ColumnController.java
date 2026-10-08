package io.devflow.board.internal;

import io.devflow.board.api.ColumnResponse;
import io.devflow.board.api.CreateColumnRequest;
import io.devflow.board.api.ReorderColumnRequest;
import io.devflow.board.internal.dto.PatchColumnRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated REST endpoints for columns and persisted display order. */
@RestController
@RequestMapping("/api/v1")
public class ColumnController {

    private final BoardManagementService boardService;

    public ColumnController(BoardManagementService boardService) {
        this.boardService = boardService;
    }

    @PostMapping("/boards/{boardId}/columns")
    public ResponseEntity<ColumnResponse> createColumn(
            @PathVariable("boardId") UUID boardId, @Valid @RequestBody CreateColumnRequest request) {
        ColumnResponse column = boardService.createColumn(boardId, request);
        return ResponseEntity.created(URI.create("/api/v1/columns/" + column.id())).body(column);
    }

    @GetMapping("/boards/{boardId}/columns")
    public List<ColumnResponse> listColumns(@PathVariable("boardId") UUID boardId) {
        return boardService.listColumns(boardId);
    }

    @GetMapping("/columns/{columnId}")
    public ColumnResponse getColumn(@PathVariable("columnId") UUID columnId) {
        return boardService.getColumn(columnId);
    }

    @PatchMapping("/columns/{columnId}")
    public ColumnResponse updateColumn(
            @PathVariable("columnId") UUID columnId, @RequestBody PatchColumnRequest request) {
        return boardService.updateColumn(columnId, request);
    }

    @PatchMapping("/columns/{columnId}/reorder")
    public List<ColumnResponse> reorderColumn(
            @PathVariable("columnId") UUID columnId, @Valid @RequestBody ReorderColumnRequest request) {
        return boardService.reorderColumn(columnId, request.position());
    }

    @DeleteMapping("/columns/{columnId}")
    public ResponseEntity<Void> deleteColumn(@PathVariable("columnId") UUID columnId) {
        boardService.deleteColumn(columnId);
        return ResponseEntity.noContent().build();
    }
}
