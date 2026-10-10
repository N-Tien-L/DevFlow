package io.devflow.board.internal;

import io.devflow.board.api.BoardPageResponse;
import io.devflow.board.api.BoardMutationData;
import io.devflow.board.api.BoardMutationType;
import io.devflow.board.api.BoardResponse;
import io.devflow.board.api.BoardSummary;
import io.devflow.board.api.BoardRefreshTarget;
import io.devflow.board.api.ColumnResponse;
import io.devflow.board.api.ColumnStatusCategory;
import io.devflow.board.api.CreateBoardRequest;
import io.devflow.board.api.CreateColumnRequest;
import io.devflow.board.internal.dto.PatchBoardRequest;
import io.devflow.board.internal.dto.PatchColumnRequest;
import io.devflow.board.internal.entity.BoardEntity;
import io.devflow.board.internal.entity.ColumnEntity;
import io.devflow.board.internal.entity.StatusCategory;
import io.devflow.board.internal.repository.BoardRepository;
import io.devflow.board.internal.repository.ColumnRepository;
import io.devflow.board.internal.repository.TaskRepository;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** Board and column use cases. All authorization decisions are delegated to Auth by typed event. */
@Service
public class BoardManagementService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_COLUMNS_PER_BOARD = 100;
    private static final int MAX_DESCRIPTION_CODE_POINTS = 10_000;

    private final BoardRepository boardRepository;
    private final ColumnRepository columnRepository;
    private final TaskRepository taskRepository;
    private final AuthenticatedActorProvider actorProvider;
    private final WorkspaceMembershipPermissionService permissionService;
    private final BoardRateLimiter rateLimiter;
    private final BoardAuditService auditService;
    private final BoardRealtimePublisher realtimePublisher;
    private final JdbcTemplate jdbcTemplate;
    private final EntityManager entityManager;

    public BoardManagementService(
            BoardRepository boardRepository,
            ColumnRepository columnRepository,
            TaskRepository taskRepository,
            AuthenticatedActorProvider actorProvider,
            WorkspaceMembershipPermissionService permissionService,
            BoardRateLimiter rateLimiter,
            BoardAuditService auditService,
            BoardRealtimePublisher realtimePublisher,
            JdbcTemplate jdbcTemplate,
            EntityManager entityManager) {
        this.boardRepository = boardRepository;
        this.columnRepository = columnRepository;
        this.taskRepository = taskRepository;
        this.actorProvider = actorProvider;
        this.permissionService = permissionService;
        this.rateLimiter = rateLimiter;
        this.auditService = auditService;
        this.realtimePublisher = realtimePublisher;
        this.jdbcTemplate = jdbcTemplate;
        this.entityManager = entityManager;
    }

    @Transactional
    public BoardResponse createBoard(CreateBoardRequest request) {
        UUID actorId = actorProvider.requireUserId();
        if (request == null || request.workspaceId() == null) {
            throw BoardApiException.badRequest("WORKSPACE_ID_REQUIRED", "workspaceId is required.");
        }
        if (!permissionService.isMember(actorId, request.workspaceId())) {
            throw BoardApiException.forbidden();
        }
        rateLimiter.check(actorId);

        String name = cleanRequiredName(request.name(), 255, "name");
        String description = cleanDescription(request.description());
        BoardEntity board = new BoardEntity(request.workspaceId(), name);
        board.setDescription(description);
        board = boardRepository.saveAndFlush(board);
        auditService.record(actorId, "CREATE", "BOARD", board.getId(), board.getWorkspaceId(),
                List.of("workspaceId", "name", "description"));
        return toResponse(board, List.of());
    }

    @Transactional(readOnly = true)
    public BoardPageResponse listBoards(UUID workspaceId, int page, int size, boolean includeArchived) {
        UUID actorId = actorProvider.requireUserId();
        if (workspaceId == null) {
            throw BoardApiException.badRequest("WORKSPACE_ID_REQUIRED", "workspaceId is required.");
        }
        validatePage(page, size);
        if (!permissionService.isMember(actorId, workspaceId)) {
            throw BoardApiException.forbidden();
        }

        Sort sort = Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("id"));
        PageRequest pageable = PageRequest.of(page, size, sort);
        Page<BoardEntity> boards = includeArchived
                ? boardRepository.findByWorkspaceId(workspaceId, pageable)
                : boardRepository.findByWorkspaceIdAndArchivedFalse(workspaceId, pageable);
        List<BoardSummary> items = boards.getContent().stream().map(BoardManagementService::toSummary).toList();
        return new BoardPageResponse(items, page, size, boards.getTotalElements(), boards.getTotalPages());
    }

    @Transactional(readOnly = true)
    public BoardResponse getBoard(UUID boardId) {
        UUID actorId = actorProvider.requireUserId();
        BoardEntity board = requireAccessibleBoard(boardId, actorId);
        return toResponse(board, columnRepository.findAllByBoard_IdOrderByPositionAscIdAsc(boardId)
                .stream().map(BoardManagementService::toResponse).toList());
    }

    @Transactional
    public BoardResponse updateBoard(UUID boardId, PatchBoardRequest request) {
        UUID actorId = actorProvider.requireUserId();
        BoardEntity board = requireLockedAccessibleBoard(boardId, actorId);
        validatePatchFields(request == null ? Set.of() : request.unknownFields());
        if (request == null || request.suppliedFields().isEmpty()) {
            throw BoardApiException.badRequest("EMPTY_PATCH", "At least one supported field is required.");
        }
        rateLimiter.check(actorId);

        if (board.isArchived()) {
            boolean restoreOnly = request.hasArchived() && Boolean.FALSE.equals(request.archived())
                    && request.suppliedFields().size() == 1;
            if (!restoreOnly) {
                throw BoardApiException.conflict("BOARD_ARCHIVED", "Restore the board before changing it.");
            }
        }

        List<String> changedFields = new ArrayList<>();
        if (request.hasName()) {
            String name = cleanRequiredName(request.name(), 255, "name");
            if (!name.equals(board.getName())) {
                board.setName(name);
                changedFields.add("name");
            }
        }
        if (request.hasDescription()) {
            String description = cleanDescription(request.description());
            if (!java.util.Objects.equals(description, board.getDescription())) {
                board.setDescription(description);
                changedFields.add("description");
            }
        }
        if (request.hasArchived()) {
            if (request.archived() == null) {
                throw BoardApiException.badRequest("ARCHIVED_REQUIRED", "archived must be true or false.");
            }
            if (board.isArchived() != request.archived()) {
                board.setArchived(request.archived());
                changedFields.add("archived");
            }
        }

        if (!changedFields.isEmpty()) {
            String action = changedFields.contains("archived")
                    ? (board.isArchived() ? "ARCHIVE" : "RESTORE") : "UPDATE";
            auditService.record(actorId, action, "BOARD", board.getId(), board.getWorkspaceId(), changedFields);
            realtimePublisher.publish(board.getId(), BoardMutationType.BOARD_UPDATED,
                    new BoardMutationData(null, null, null, null, null, null, List.of(), changedFields,
                            List.of(BoardRefreshTarget.BOARD)), actorId);
        }
        return toResponse(board, columnRepository.findAllByBoard_IdOrderByPositionAscIdAsc(boardId)
                .stream().map(BoardManagementService::toResponse).toList());
    }

    @Transactional
    public void deleteBoard(UUID boardId) {
        UUID actorId = actorProvider.requireUserId();
        BoardEntity board = requireLockedAccessibleBoard(boardId, actorId);
        rateLimiter.check(actorId);
        UUID workspaceId = board.getWorkspaceId();
        auditService.record(actorId, "DELETE", "BOARD", boardId, workspaceId,
                List.of("board", "columns", "tasks", "comments", "tags"));
        realtimePublisher.publish(boardId, BoardMutationType.BOARD_DELETED,
                new BoardMutationData(null, null, null, null, null, null, List.of(), List.of("deleted"),
                        List.of(BoardRefreshTarget.BOARD)), actorId);
        int deleted = jdbcTemplate.update("DELETE FROM boards WHERE id = ?", boardId);
        if (deleted != 1) {
            throw BoardApiException.notFound();
        }
        entityManager.clear();
    }

    @Transactional
    public ColumnResponse createColumn(UUID boardId, CreateColumnRequest request) {
        UUID actorId = actorProvider.requireUserId();
        BoardEntity board = requireLockedAccessibleBoard(boardId, actorId);
        requireActive(board);
        if (request == null) {
            throw BoardApiException.badRequest("INVALID_REQUEST", "Column data is required.");
        }
        String name = cleanRequiredName(request.name(), 100, "name");
        if (request.statusCategory() == null) {
            throw BoardApiException.badRequest("STATUS_CATEGORY_REQUIRED", "statusCategory is required.");
        }
        rateLimiter.check(actorId);

        List<ColumnEntity> columns = columnRepository.findAllByBoard_IdOrderByPositionAscIdAsc(boardId);
        if (columns.size() >= MAX_COLUMNS_PER_BOARD) {
            throw BoardApiException.conflict("COLUMN_LIMIT_REACHED", "A board may contain at most 100 columns.");
        }
        normalizePositions(columns);
        ColumnEntity column = new ColumnEntity(board, name, columns.size(), toEntityCategory(request.statusCategory()));
        column = columnRepository.saveAndFlush(column);
        auditService.record(actorId, "CREATE", "COLUMN", column.getId(), board.getWorkspaceId(),
                List.of("boardId", "name", "position", "statusCategory"));
        realtimePublisher.publish(boardId, BoardMutationType.COLUMN_CREATED,
                new BoardMutationData(null, column.getId(), null, null, null, column.getPosition(),
                        List.of(column.getId()), List.of("name", "position", "statusCategory"),
                        List.of(BoardRefreshTarget.COLUMNS)), actorId);
        return toResponse(column);
    }

    @Transactional(readOnly = true)
    public List<ColumnResponse> listColumns(UUID boardId) {
        UUID actorId = actorProvider.requireUserId();
        requireAccessibleBoard(boardId, actorId);
        return columnRepository.findAllByBoard_IdOrderByPositionAscIdAsc(boardId).stream()
                .map(BoardManagementService::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public ColumnResponse getColumn(UUID columnId) {
        UUID actorId = actorProvider.requireUserId();
        ColumnEntity column = columnRepository.findById(columnId).orElseThrow(BoardApiException::notFound);
        UUID boardId = column.getBoard().getId();
        requireAccessibleBoard(boardId, actorId);
        return toResponse(column);
    }

    @Transactional
    public ColumnResponse updateColumn(UUID columnId, PatchColumnRequest request) {
        UUID actorId = actorProvider.requireUserId();
        ColumnEntity initial = columnRepository.findById(columnId).orElseThrow(BoardApiException::notFound);
        UUID boardId = initial.getBoard().getId();
        BoardEntity board = requireLockedAccessibleBoard(boardId, actorId);
        requireActive(board);
        rateLimiter.check(actorId);
        ColumnEntity column = columnRepository.findByIdAndBoard_Id(columnId, boardId)
                .orElseThrow(BoardApiException::notFound);
        validatePatchFields(request == null ? Set.of() : request.unknownFields());
        if (request == null || request.suppliedFields().isEmpty()) {
            throw BoardApiException.badRequest("EMPTY_PATCH", "At least one supported field is required.");
        }
        List<String> changedFields = new ArrayList<>();
        if (request.hasName()) {
            String name = cleanRequiredName(request.name(), 100, "name");
            if (!name.equals(column.getName())) {
                changedFields.add("name");
                column.setName(name);
            }
        }
        if (request.hasStatusCategory()) {
            if (request.statusCategory() == null) {
                throw BoardApiException.badRequest("STATUS_CATEGORY_REQUIRED", "statusCategory cannot be null.");
            }
            StatusCategory category = toEntityCategory(request.statusCategory());
            if (category != column.getStatusCategory()) {
                if (taskRepository.countByColumn_Id(columnId) > 0) {
                    throw BoardApiException.conflict("COLUMN_NOT_EMPTY",
                            "A column with tasks cannot change its status category.");
                }
                changedFields.add("statusCategory");
                column.setStatusCategory(category);
            }
        }

        if (!changedFields.isEmpty()) {
            auditService.record(actorId, "UPDATE", "COLUMN", columnId, board.getWorkspaceId(), changedFields);
            realtimePublisher.publish(boardId, BoardMutationType.COLUMN_UPDATED,
                    new BoardMutationData(null, columnId, null, null, null, null, List.of(columnId), changedFields,
                            List.of(BoardRefreshTarget.COLUMNS, BoardRefreshTarget.TASK_DETAIL)), actorId);
        }
        return toResponse(column);
    }

    @Transactional
    public void deleteColumn(UUID columnId) {
        UUID actorId = actorProvider.requireUserId();
        ColumnEntity initial = columnRepository.findById(columnId).orElseThrow(BoardApiException::notFound);
        UUID boardId = initial.getBoard().getId();
        BoardEntity board = requireLockedAccessibleBoard(boardId, actorId);
        requireActive(board);
        rateLimiter.check(actorId);
        ColumnEntity column = columnRepository.findByIdAndBoard_Id(columnId, boardId)
                .orElseThrow(BoardApiException::notFound);
        if (taskRepository.countByColumn_Id(columnId) > 0) {
            throw BoardApiException.conflict("COLUMN_NOT_EMPTY", "Move or delete the tasks before deleting this column.");
        }
        int fromPosition = column.getPosition();
        List<ColumnEntity> columns = columnRepository.findAllByBoard_IdOrderByPositionAscIdAsc(boardId);
        columns.removeIf(candidate -> candidate.getId().equals(columnId));
        columnRepository.delete(column);
        for (int index = 0; index < columns.size(); index++) {
            columns.get(index).setPosition(index);
        }
        auditService.record(actorId, "DELETE", "COLUMN", columnId, board.getWorkspaceId(), List.of("column"));
        realtimePublisher.publish(boardId, BoardMutationType.COLUMN_DELETED,
                new BoardMutationData(null, columnId, null, null, fromPosition, null, List.of(columnId),
                        List.of("deleted", "position"), List.of(BoardRefreshTarget.COLUMNS)), actorId);
    }

    @Transactional
    public List<ColumnResponse> reorderColumn(UUID columnId, int targetPosition) {
        UUID actorId = actorProvider.requireUserId();
        ColumnEntity initial = columnRepository.findById(columnId).orElseThrow(BoardApiException::notFound);
        UUID boardId = initial.getBoard().getId();
        BoardEntity board = requireLockedAccessibleBoard(boardId, actorId);
        requireActive(board);
        rateLimiter.check(actorId);

        List<ColumnEntity> columns = columnRepository.findAllByBoard_IdOrderByPositionAscIdAsc(boardId);
        int oldIndex = indexOf(columns, columnId);
        int oldPosition = columns.get(oldIndex).getPosition();
        if (targetPosition < 0 || targetPosition >= columns.size()) {
            throw BoardApiException.badRequest("INVALID_POSITION",
                    "position must be between 0 and " + (columns.size() - 1) + ".");
        }
        List<ColumnEntity> reordered = new ArrayList<>(columns);
        ColumnEntity moving = reordered.remove(oldIndex);
        reordered.add(targetPosition, moving);

        boolean changed = false;
        for (int index = 0; index < reordered.size(); index++) {
            ColumnEntity column = reordered.get(index);
            if (!column.getId().equals(columns.get(index).getId()) || column.getPosition() != index) {
                changed = true;
            }
            column.setPosition(index);
        }
        if (changed) {
            auditService.record(actorId, "REORDER", "COLUMN", columnId, board.getWorkspaceId(),
                    List.of("position"));
            int newPosition = moving.getPosition();
            realtimePublisher.publish(boardId, BoardMutationType.COLUMN_REORDERED,
                    new BoardMutationData(null, columnId, null, null, oldPosition, newPosition,
                            reordered.stream().map(ColumnEntity::getId).toList(), List.of("position"),
                            List.of(BoardRefreshTarget.COLUMNS)), actorId);
        }
        return reordered.stream().map(BoardManagementService::toResponse).toList();
    }

    private BoardEntity requireAccessibleBoard(UUID boardId, UUID actorId) {
        BoardEntity board = boardRepository.findById(boardId).orElseThrow(BoardApiException::notFound);
        if (!permissionService.isMember(actorId, board.getWorkspaceId())) {
            throw BoardApiException.notFound();
        }
        return board;
    }

    private BoardEntity requireLockedAccessibleBoard(UUID boardId, UUID actorId) {
        requireAccessibleBoard(boardId, actorId);
        BoardEntity locked = boardRepository.findByIdForUpdate(boardId).orElseThrow(BoardApiException::notFound);
        // The first read may have happened before waiting for another mutation's board lock.
        // Refresh the managed row so archive/workspace decisions use the just-committed state.
        entityManager.refresh(locked);
        if (!permissionService.isMember(actorId, locked.getWorkspaceId())) {
            throw BoardApiException.notFound();
        }
        return locked;
    }

    private static void requireActive(BoardEntity board) {
        if (board.isArchived()) {
            throw BoardApiException.conflict("BOARD_ARCHIVED", "Restore the board before changing its columns.");
        }
    }

    private static void validatePage(int page, int size) {
        if (page < 0) {
            throw BoardApiException.badRequest("INVALID_PAGE", "page must not be negative.");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw BoardApiException.badRequest("INVALID_PAGE_SIZE", "size must be between 1 and 100.");
        }
    }

    private static void validatePatchFields(Set<String> unknownFields) {
        if (!unknownFields.isEmpty()) {
            throw BoardApiException.badRequest("UNKNOWN_FIELDS", "The request contains unsupported fields.");
        }
    }

    private static String cleanRequiredName(String value, int maxCodePoints, String field) {
        if (value == null) {
            throw BoardApiException.badRequest("FIELD_REQUIRED", field + " is required.");
        }
        String cleaned = sanitizeMarkup(value).strip();
        if (!StringUtils.hasText(cleaned)) {
            throw BoardApiException.badRequest("FIELD_REQUIRED", field + " must contain visible text.");
        }
        if (cleaned.codePointCount(0, cleaned.length()) > maxCodePoints) {
            throw BoardApiException.badRequest("FIELD_TOO_LONG", field + " exceeds the maximum length.");
        }
        return cleaned;
    }

    private static String cleanDescription(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = sanitizeMarkup(value);
        if (cleaned.codePointCount(0, cleaned.length()) > MAX_DESCRIPTION_CODE_POINTS) {
            throw BoardApiException.badRequest("DESCRIPTION_TOO_LONG", "description exceeds 10000 characters.");
        }
        return cleaned;
    }

    private static String sanitizeMarkup(String value) {
        return Jsoup.clean(value, Safelist.none());
    }

    private static int indexOf(List<ColumnEntity> columns, UUID columnId) {
        for (int index = 0; index < columns.size(); index++) {
            if (columns.get(index).getId().equals(columnId)) {
                return index;
            }
        }
        throw BoardApiException.notFound();
    }

    private static void normalizePositions(List<ColumnEntity> columns) {
        for (int index = 0; index < columns.size(); index++) {
            columns.get(index).setPosition(index);
        }
    }

    private static StatusCategory toEntityCategory(ColumnStatusCategory category) {
        return StatusCategory.valueOf(category.name());
    }

    private static BoardSummary toSummary(BoardEntity board) {
        return new BoardSummary(board.getId(), board.getWorkspaceId(), board.getName(), board.getDescription(),
                board.isArchived(), board.getCreatedAt(), board.getUpdatedAt());
    }

    private BoardResponse toResponse(BoardEntity board, List<ColumnResponse> columns) {
        return new BoardResponse(board.getId(), board.getWorkspaceId(), board.getName(), board.getDescription(),
                board.isArchived(), board.getCreatedAt(), board.getUpdatedAt(), columns);
    }

    private static ColumnResponse toResponse(ColumnEntity column) {
        return new ColumnResponse(column.getId(), column.getBoard().getId(), column.getName(), column.getPosition(),
                ColumnStatusCategory.valueOf(column.getStatusCategory().name()),
                column.getCreatedAt(), column.getUpdatedAt());
    }
}
