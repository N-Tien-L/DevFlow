package io.devflow.board.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.devflow.board.internal.entity.BoardEntity;
import io.devflow.board.internal.entity.ColumnEntity;
import io.devflow.board.internal.entity.CommentEntity;
import io.devflow.board.internal.entity.StatusCategory;
import io.devflow.board.internal.entity.TaskEntity;
import io.devflow.board.internal.entity.TaskPriority;
import io.devflow.board.internal.repository.BoardRepository;
import io.devflow.board.internal.repository.ColumnRepository;
import io.devflow.board.internal.repository.CommentRepository;
import io.devflow.board.internal.repository.TaskRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Verifies Board entities and repository contracts against the production V1/V2 schema. */
@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = TestBoardApplication.class)
class BoardPersistenceIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("devflow")
            .withUsername("devflow")
            .withPassword("devflow");

    private static final UUID WORKSPACE_ONE = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID WORKSPACE_TWO = UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID USER_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.flyway.locations", () -> "filesystem:"
                + System.getProperty("devflow.test.migration-location"));
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.open-in-view", () -> false);
    }

    @Autowired
    private BoardRepository boardRepository;

    @Autowired
    private ColumnRepository columnRepository;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private CommentRepository commentRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbc;

    private BoardEntity board;
    private BoardEntity otherBoard;
    private ColumnEntity todo;
    private ColumnEntity doing;
    private TaskEntity firstTask;
    private TaskEntity secondTask;
    private CommentEntity firstComment;

    @BeforeEach
    void setUp() {
        board = boardRepository.saveAndFlush(new BoardEntity(WORKSPACE_ONE, "Board one"));
        otherBoard = boardRepository.saveAndFlush(new BoardEntity(WORKSPACE_TWO, "Board two"));
        todo = columnRepository.saveAndFlush(new ColumnEntity(board, "To Do", 0, StatusCategory.TODO));
        doing = columnRepository.saveAndFlush(new ColumnEntity(board, "Doing", 1, StatusCategory.IN_PROGRESS));
        ColumnEntity otherColumn = columnRepository.saveAndFlush(
                new ColumnEntity(otherBoard, "Other", 0, StatusCategory.TODO));
        firstTask = taskRepository.saveAndFlush(new TaskEntity(todo, "First", 1));
        secondTask = taskRepository.saveAndFlush(new TaskEntity(todo, "Second", 0));
        taskRepository.saveAndFlush(new TaskEntity(otherColumn, "Other board task", 0));
        firstComment = commentRepository.saveAndFlush(new CommentEntity(firstTask, USER_ID, "first"));
    }

    @Test
    void persistsAndReloadsTheCompleteEntityGraphWithUuidAndAuditFields() {
        BoardEntity graph = new BoardEntity(WORKSPACE_ONE, "Bảng tiếng Việt 🚀");
        graph.setDescription("## Mô tả\n- Kanban");
        ColumnEntity column = new ColumnEntity(graph, "Đang làm", 3, StatusCategory.IN_PROGRESS);
        TaskEntity task = new TaskEntity(column, "Tác vụ", 7);
        task.setDescription("**Markdown** 🚀");
        task.setAssigneeId(UUID.randomUUID());
        task.setDueDate(Instant.parse("2026-10-06T03:00:00.123456Z"));
        CommentEntity comment = new CommentEntity(task, USER_ID, "Bình luận `V2`");

        BoardEntity saved = boardRepository.saveAndFlush(graph);
        entityManager.clear();

        BoardEntity reloadedBoard = boardRepository.findById(saved.getId()).orElseThrow();
        ColumnEntity reloadedColumn = columnRepository.findByBoard_IdOrderByPositionAscIdAsc(saved.getId()).get(0);
        TaskEntity reloadedTask = taskRepository.findById(reloadedColumn.getTasks().get(0).getId()).orElseThrow();
        CommentEntity reloadedComment = commentRepository.findByTask_IdOrderByCreatedAtAscIdAsc(reloadedTask.getId())
                .get(0);

        assertThat(reloadedBoard.getWorkspaceId()).isEqualTo(WORKSPACE_ONE);
        assertThat(reloadedBoard.getName()).isEqualTo("Bảng tiếng Việt 🚀");
        assertThat(reloadedBoard.getDescription()).isEqualTo("## Mô tả\n- Kanban");
        assertThat(reloadedBoard.isArchived()).isFalse();
        assertThat(reloadedBoard.getCreatedAt()).isNotNull();
        assertThat(reloadedBoard.getUpdatedAt()).isNotNull();
        assertThat(reloadedColumn.getStatusCategory()).isEqualTo(StatusCategory.IN_PROGRESS);
        assertThat(reloadedTask.getPriority()).isEqualTo(TaskPriority.MEDIUM);
        assertThat(reloadedTask.getDescription()).isEqualTo("**Markdown** 🚀");
        assertThat(reloadedTask.getAssigneeId()).isEqualTo(task.getAssigneeId());
        assertThat(reloadedTask.getDueDate()).isEqualTo(task.getDueDate());
        assertThat(reloadedComment.getAuthorId()).isEqualTo(USER_ID);
        assertThat(reloadedComment.getContent()).isEqualTo("Bình luận `V2`");
        assertThat(comment.getId()).isNotNull();
    }

    @Test
    void storesNullableFieldsAndAllowsSoftReferencesToAuthIds() {
        TaskEntity task = taskRepository.saveAndFlush(new TaskEntity(todo, "No optional values", 2));
        entityManager.clear();

        TaskEntity reloaded = taskRepository.findById(task.getId()).orElseThrow();
        assertThat(reloaded.getDescription()).isNull();
        assertThat(reloaded.getAssigneeId()).isNull();
        assertThat(reloaded.getDueDate()).isNull();

        BoardEntity softReferenceBoard = boardRepository.saveAndFlush(
                new BoardEntity(UUID.randomUUID(), "Missing Auth workspace row"));
        assertThat(boardRepository.findById(softReferenceBoard.getId())).isPresent();
    }

    @Test
    void storesEverySupportedEnumValueAsString() {
        for (StatusCategory category : StatusCategory.values()) {
            columnRepository.saveAndFlush(new ColumnEntity(board, "Status " + category,
                    category.ordinal() + 2, category));
        }
        for (TaskPriority priority : TaskPriority.values()) {
            TaskEntity task = new TaskEntity(todo, "Priority " + priority, priority.ordinal() + 2);
            task.setPriority(priority);
            taskRepository.saveAndFlush(task);
        }

        assertThat(jdbc.queryForList("SELECT status_category FROM columns WHERE board_id = ?", String.class,
                board.getId())).contains("TODO", "IN_PROGRESS", "IN_REVIEW", "DONE");
        assertThat(jdbc.queryForList("SELECT priority FROM tasks WHERE column_id = ?", String.class,
                todo.getId())).contains("LOW", "MEDIUM", "HIGH", "URGENT");
    }

    @Test
    void listsBoardsInWorkspaceAndSupportsActiveOnlyAndScopedLookup() {
        BoardEntity archived = new BoardEntity(WORKSPACE_ONE, "Archived");
        archived.setArchived(true);
        archived = boardRepository.saveAndFlush(archived);
        BoardEntity archivedBoard = archived;

        assertThat(boardRepository.findByWorkspaceIdOrderByCreatedAtAscIdAsc(WORKSPACE_ONE))
                .extracting(BoardEntity::getId).contains(board.getId(), archivedBoard.getId()).doesNotContain(otherBoard.getId());
        assertThat(boardRepository.findByWorkspaceIdAndArchivedFalseOrderByCreatedAtAscIdAsc(WORKSPACE_ONE))
                .extracting(BoardEntity::getId).contains(board.getId()).doesNotContain(archivedBoard.getId());
        assertThat(boardRepository.findByIdAndWorkspaceId(board.getId(), WORKSPACE_ONE)).isPresent();
        assertThat(boardRepository.findByIdAndWorkspaceId(board.getId(), WORKSPACE_TWO)).isEmpty();
    }

    @Test
    void scopesColumnsTasksAndCommentsAndOrdersThemDeterministically() {
        List<ColumnEntity> columns = columnRepository.findByBoard_IdOrderByPositionAscIdAsc(board.getId());
        assertThat(columns).extracting(ColumnEntity::getName).containsExactly("To Do", "Doing");
        assertThat(columnRepository.findByBoard_IdOrderByPositionAscIdAsc(otherBoard.getId()))
                .extracting(ColumnEntity::getName).containsExactly("Other");
        assertThat(columnRepository.findByIdAndBoard_Id(todo.getId(), otherBoard.getId())).isEmpty();

        assertThat(taskRepository.findByColumn_IdOrderByPositionAscIdAsc(todo.getId()))
                .extracting(TaskEntity::getTitle).containsExactly("Second", "First");
        assertThat(taskRepository.findByBoardIdInDisplayOrder(board.getId()))
                .extracting(TaskEntity::getTitle).containsExactly("Second", "First");
        assertThat(taskRepository.findByBoardIdInDisplayOrder(otherBoard.getId()))
                .extracting(TaskEntity::getTitle).containsExactly("Other board task");
        assertThat(taskRepository.findByIdAndColumn_Board_Id(firstTask.getId(), otherBoard.getId())).isEmpty();
        assertThat(commentRepository.findByTask_IdOrderByCreatedAtAscIdAsc(firstTask.getId()))
                .extracting(CommentEntity::getId).containsExactly(firstComment.getId());
        assertThat(commentRepository.findByIdAndTask_Id(firstComment.getId(), secondTask.getId())).isEmpty();
        assertThat(commentRepository.findByTask_IdOrderByCreatedAtAscIdAsc(UUID.randomUUID())).isEmpty();
    }

    @Test
    void movesATaskWithoutDeletingItOrItsCommentsAndKeepsInverseCollectionsConsistent() {
        UUID taskId = firstTask.getId();
        UUID commentId = firstComment.getId();

        firstTask.moveTo(doing);
        taskRepository.flush();
        entityManager.clear();

        TaskEntity moved = taskRepository.findById(taskId).orElseThrow();
        assertThat(moved.getColumn().getId()).isEqualTo(doing.getId());
        assertThat(taskRepository.findByColumn_IdOrderByPositionAscIdAsc(todo.getId()))
                .extracting(TaskEntity::getId).doesNotContain(taskId);
        assertThat(commentRepository.findByIdAndTask_Id(commentId, taskId)).isPresent();

        moved.getColumn().getTasks().remove(moved);
        taskRepository.flush();
        entityManager.clear();
        assertThat(taskRepository.findById(taskId)).isPresent();
    }

    @Test
    void keepsManyToOneAssociationsLazy() {
        entityManager.flush();
        entityManager.clear();

        TaskEntity loaded = taskRepository.findById(firstTask.getId()).orElseThrow();
        assertThat(Hibernate.isInitialized(loaded.getColumn())).isFalse();
        assertThat(loaded.getColumn().getId()).isEqualTo(todo.getId());
    }

    @Test
    void updatesFieldsAndPreservesCreationTimestamp() {
        Instant createdAtInDatabase = jdbc.queryForObject("SELECT created_at FROM tasks WHERE id = ?",
                java.sql.Timestamp.class, firstTask.getId()).toInstant();
        firstTask.setTitle("Updated title");
        firstTask.setPosition(9);
        taskRepository.flush();
        entityManager.clear();

        TaskEntity updated = taskRepository.findById(firstTask.getId()).orElseThrow();
        assertThat(updated.getTitle()).isEqualTo("Updated title");
        assertThat(updated.getPosition()).isEqualTo(9);
        assertThat(updated.getCreatedAt()).isEqualTo(createdAtInDatabase);
        assertThat(updated.getUpdatedAt()).isNotNull();
    }

    @Test
    void rejectsValuesThatViolateV2NotNullAndCheckConstraints() {
        TaskEntity missingPriority = new TaskEntity(todo, "Invalid priority", 10);
        missingPriority.setPriority(null);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> taskRepository.saveAndFlush(missingPriority))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingBoardCascadesInTheDatabaseAndLeavesOtherBoardsAlone() {
        UUID boardId = board.getId();
        UUID otherBoardId = otherBoard.getId();
        UUID taskId = firstTask.getId();
        UUID commentId = firstComment.getId();
        boardRepository.deleteById(boardId);
        boardRepository.flush();
        entityManager.clear();

        assertThat(boardRepository.findById(boardId)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM columns WHERE board_id = ?", Long.class, boardId)).isZero();
        assertThat(taskRepository.findById(taskId)).isEmpty();
        assertThat(commentRepository.findById(commentId)).isEmpty();
        assertThat(boardRepository.findById(otherBoardId)).isPresent();
    }

    @Test
    void deletingTaskRemovesOnlyItsComments() {
        UUID taskId = firstTask.getId();
        UUID otherTaskId = secondTask.getId();
        todo.removeTask(firstTask);
        taskRepository.deleteById(taskId);
        taskRepository.flush();
        entityManager.clear();

        assertThat(taskRepository.findById(taskId)).isEmpty();
        assertThat(commentRepository.findByTask_IdOrderByCreatedAtAscIdAsc(taskId)).isEmpty();
        assertThat(taskRepository.findById(otherTaskId)).isPresent();
    }
}
