package io.devflow.board.internal;

import io.devflow.board.api.CreateTaskCommentRequest;
import io.devflow.board.api.TaskCommentPageResponse;
import io.devflow.board.api.TaskCommentResponse;
import io.devflow.board.api.BoardMutationData;
import io.devflow.board.api.BoardMutationType;
import io.devflow.board.api.BoardRefreshTarget;
import io.devflow.board.internal.entity.BoardEntity;
import io.devflow.board.internal.entity.CommentEntity;
import io.devflow.board.internal.entity.TaskEntity;
import io.devflow.board.internal.repository.BoardRepository;
import io.devflow.board.internal.repository.CommentRepository;
import io.devflow.board.internal.repository.TaskRepository;
import java.util.List;
import java.util.UUID;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** Read and write access to Board-owned task comments. */
@Service
public class TaskCommentService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_CONTENT_CODE_POINTS = 5_000;
    private final CommentRepository commentRepository;
    private final TaskRepository taskRepository;
    private final BoardRepository boardRepository;
    private final AuthenticatedActorProvider actorProvider;
    private final WorkspaceMembershipPermissionService permissionService;
    private final BoardAuditService auditService;
    private final BoardRealtimePublisher realtimePublisher;

    public TaskCommentService(CommentRepository commentRepository, TaskRepository taskRepository,
            BoardRepository boardRepository, AuthenticatedActorProvider actorProvider,
            WorkspaceMembershipPermissionService permissionService, BoardAuditService auditService,
            BoardRealtimePublisher realtimePublisher) {
        this.commentRepository = commentRepository;
        this.taskRepository = taskRepository;
        this.boardRepository = boardRepository;
        this.actorProvider = actorProvider;
        this.permissionService = permissionService;
        this.auditService = auditService;
        this.realtimePublisher = realtimePublisher;
    }

    @Transactional(readOnly = true)
    public TaskCommentPageResponse list(UUID taskId, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw BoardApiException.badRequest("INVALID_PAGE", "page must be non-negative and size must be 1..100.");
        }
        TaskEntity task = taskRepository.findTaskWithBoard(taskId).orElseThrow(BoardApiException::notFound);
        BoardEntity board = task.getColumn().getBoard();
        requireMember(actorProvider.requireUserId(), board.getWorkspaceId());
        Page<CommentEntity> result = commentRepository.findAllByTask_Id(taskId,
                PageRequest.of(page, size, Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("id"))));
        return new TaskCommentPageResponse(result.getContent().stream().map(this::toResponse).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    @Transactional
    public TaskCommentResponse create(UUID taskId, CreateTaskCommentRequest request) {
        UUID actorId = actorProvider.requireUserId();
        UUID boardId = taskRepository.findBoardIdByTaskId(taskId).orElseThrow(BoardApiException::notFound);
        UUID workspaceId = boardRepository.findWorkspaceIdByBoardId(boardId).orElseThrow(BoardApiException::notFound);
        requireMember(actorId, workspaceId);
        BoardEntity board = boardRepository.findByIdForUpdate(boardId).orElseThrow(BoardApiException::notFound);
        requireMember(actorId, board.getWorkspaceId());
        if (board.isArchived()) throw BoardApiException.boardArchived();
        TaskEntity task = taskRepository.findTaskWithBoard(taskId).orElseThrow(BoardApiException::notFound);
        if (!task.getColumn().getBoard().getId().equals(boardId)) throw BoardApiException.notFound();
        if (request == null || request.content() == null) {
            throw BoardApiException.badRequest("COMMENT_REQUIRED", "Comment content is required.");
        }
        String content = Jsoup.clean(request.content(), Safelist.none()).strip();
        if (!StringUtils.hasText(content)) {
            throw BoardApiException.badRequest("COMMENT_REQUIRED", "Comment content must contain visible text.");
        }
        if (content.codePointCount(0, content.length()) > MAX_CONTENT_CODE_POINTS) {
            throw BoardApiException.badRequest("COMMENT_TOO_LONG", "Comment content exceeds 5000 characters.");
        }
        CommentEntity saved = commentRepository.saveAndFlush(new CommentEntity(task, actorId, content));
        auditService.record(actorId, "UPDATE", "TASK", taskId, board.getWorkspaceId(), List.of("comments"));
        realtimePublisher.publish(boardId, BoardMutationType.CARD_UPDATED,
                new BoardMutationData(taskId, task.getColumn().getId(), null, null, null, task.getPosition(),
                        List.of(task.getColumn().getId()), List.of("comments"), List.of(BoardRefreshTarget.TASK_DETAIL)),
                actorId);
        return toResponse(saved);
    }

    private void requireMember(UUID actorId, UUID workspaceId) {
        if (!permissionService.isMember(actorId, workspaceId)) throw BoardApiException.notFound();
    }

    private TaskCommentResponse toResponse(CommentEntity comment) {
        return new TaskCommentResponse(comment.getId(), comment.getTask().getId(), comment.getAuthorId(),
                comment.getContent(), comment.getCreatedAt(), comment.getUpdatedAt());
    }
}
