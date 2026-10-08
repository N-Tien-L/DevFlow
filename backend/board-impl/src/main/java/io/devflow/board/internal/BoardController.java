package io.devflow.board.internal;

import io.devflow.board.api.BoardPageResponse;
import io.devflow.board.api.BoardResponse;
import io.devflow.board.api.CreateBoardRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import io.devflow.board.internal.dto.PatchBoardRequest;

/** Authenticated REST endpoints for Board lifecycle and workspace-scoped listing. */
@RestController
@RequestMapping("/api/v1")
public class BoardController {

    private final BoardManagementService boardService;

    public BoardController(BoardManagementService boardService) {
        this.boardService = boardService;
    }

    @PostMapping("/boards")
    public ResponseEntity<BoardResponse> createBoard(@Valid @RequestBody CreateBoardRequest request) {
        BoardResponse board = boardService.createBoard(request);
        return ResponseEntity.created(URI.create("/api/v1/boards/" + board.id())).body(board);
    }

    @GetMapping("/workspaces/{workspaceId}/boards")
    public BoardPageResponse listBoards(
            @PathVariable("workspaceId") UUID workspaceId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "includeArchived", defaultValue = "false") boolean includeArchived) {
        return boardService.listBoards(workspaceId, page, size, includeArchived);
    }

    @GetMapping("/boards/{boardId}")
    public BoardResponse getBoard(@PathVariable("boardId") UUID boardId) {
        return boardService.getBoard(boardId);
    }

    @PatchMapping("/boards/{boardId}")
    public BoardResponse updateBoard(
            @PathVariable("boardId") UUID boardId, @RequestBody PatchBoardRequest request) {
        return boardService.updateBoard(boardId, request);
    }

    @DeleteMapping("/boards/{boardId}")
    public ResponseEntity<Void> deleteBoard(@PathVariable("boardId") UUID boardId) {
        boardService.deleteBoard(boardId);
        return ResponseEntity.noContent().build();
    }
}
