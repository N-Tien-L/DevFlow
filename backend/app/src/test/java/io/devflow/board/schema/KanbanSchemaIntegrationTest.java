package io.devflow.board.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Exercises the production Flyway migrations and Kanban schema against PostgreSQL 17. */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.ai.model.chat=none",
        "spring.ai.model.embedding=none",
        "spring.ai.model.image=none",
        "spring.ai.model.moderation=none",
        "spring.ai.model.audio.speech=none",
        "spring.ai.model.audio.transcription=none"
})
@Testcontainers
class KanbanSchemaIntegrationTest {

    private static final UUID WORKSPACE_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID ASSIGNEE_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID AUTHOR_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("devflow")
            .withUsername("devflow")
            .withPassword("devflow");

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private javax.sql.DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private UUID boardId;
    private UUID otherBoardId;
    private UUID columnId;
    private UUID otherColumnId;
    private UUID taskId;
    private UUID otherTaskId;
    private UUID tagId;
    private UUID otherTagId;
    private UUID archivedBoardId;

    @BeforeEach
    void createFixture() {
        boardId = insertBoard(WORKSPACE_ID);
        otherBoardId = insertBoard(UUID.randomUUID());
        columnId = insertColumn(boardId, "To Do", 0, "TODO");
        otherColumnId = insertColumn(otherBoardId, "In Progress", 0, "IN_PROGRESS");
        taskId = insertTask(columnId, "Kanban migration", 0);
        otherTaskId = insertTask(otherColumnId, "Independent board", 0);
        tagId = insertTag(boardId, "backend", "#123456");
        otherTagId = insertTag(otherBoardId, "other-board", "blue");
        jdbc.update("INSERT INTO task_tag_mappings(task_id, tag_id) VALUES (?, ?)", taskId, tagId);
    }

    @AfterEach
    void removeFixture() {
        if (jdbc != null) {
            jdbc.update("DELETE FROM boards WHERE id IN (?, ?) OR workspace_id = ?",
                    boardId, otherBoardId, WORKSPACE_ID);
            if (archivedBoardId != null) {
                jdbc.update("DELETE FROM boards WHERE id = ?", archivedBoardId);
            }
        }
    }

    @Test
    void createsExpectedTablesConstraintsAndIndexes() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertThat(tableNames(metadata)).contains("boards", "columns", "tasks", "task_comments",
                    "task_tags", "task_tag_mappings");
            assertThat(indexNames(metadata, "boards")).contains("idx_boards_workspace");
            assertThat(indexNames(metadata, "columns")).contains("idx_columns_board_position");
            assertThat(indexNames(metadata, "tasks")).contains("idx_tasks_column_position",
                    "idx_tasks_assignee", "idx_tasks_due_date");
            assertThat(indexNames(metadata, "task_comments")).contains("idx_task_comments_task_created");
            assertThat(indexNames(metadata, "task_tags")).contains("idx_task_tags_board");
            assertThat(indexNames(metadata, "task_tag_mappings")).contains("idx_tag_mappings_tag");
        }

        assertThat(scalarLong("SELECT COUNT(*) FROM flyway_schema_history WHERE version = '2' AND success"))
                .isEqualTo(1);
        assertThat(foreignKeyNames()).containsExactlyInAnyOrder(
                "fk_columns_board", "fk_tasks_column", "fk_task_comments_task", "fk_task_tags_board",
                "fk_task_tag_mappings_task", "fk_task_tag_mappings_tag");
    }

    @Test
    void storesTaskDefaultsSoftReferencesAndPersistsAcrossConnections() throws SQLException {
        UUID persistedTaskId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO tasks(id, column_id, title, position, assignee_id)
                VALUES (?, ?, ?, ?, ?)
                """, persistedTaskId, columnId, "Unicode / tiếng Việt 🚀", 3, ASSIGNEE_ID);

        try (Connection connection = dataSource.getConnection();
                var statement = connection.prepareStatement(
                        "SELECT title, description, priority, assignee_id, due_date, created_at, updated_at "
                                + "FROM tasks WHERE id = ?")) {
            statement.setObject(1, persistedTaskId);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString("title")).isEqualTo("Unicode / tiếng Việt 🚀");
                assertThat(result.getString("description")).isNull();
                assertThat(result.getString("priority")).isEqualTo("MEDIUM");
                assertThat(result.getObject("assignee_id", UUID.class)).isEqualTo(ASSIGNEE_ID);
                assertThat(result.getObject("due_date")).isNull();
                assertThat(result.getTimestamp("created_at")).isNotNull();
                assertThat(result.getTimestamp("updated_at")).isNotNull();
                assertThat(result.next()).isFalse();
            }
        }
    }

    @Test
    void storesMarkdownCommentsAndEquivalentTimezoneInstants() {
        Instant deadline = Instant.parse("2026-10-06T03:00:00Z");
        jdbc.update("UPDATE tasks SET description = ?, due_date = ? WHERE id = ?",
                "## Mô tả\n- **Unicode** 🚀", Timestamp.from(deadline), taskId);
        jdbc.update("INSERT INTO task_comments(task_id, author_id, content) VALUES (?, ?, ?)", taskId, AUTHOR_ID,
                "Xem `V2` và mô tả tiếng Việt.");

        assertThat(jdbc.queryForObject("SELECT description FROM tasks WHERE id = ?", String.class, taskId))
                .isEqualTo("## Mô tả\n- **Unicode** 🚀");
        assertThat(jdbc.queryForObject("SELECT content FROM task_comments WHERE task_id = ?", String.class, taskId))
                .contains("tiếng Việt");
        assertThat(jdbc.queryForObject("SELECT due_date FROM tasks WHERE id = ?", Timestamp.class, taskId).toInstant())
                .isEqualTo(deadline);
    }

    @ParameterizedTest
    @MethodSource("validCategories")
    void acceptsEverySupportedStatusCategory(String category) {
        assertThat(insertColumn(boardId, "status-" + category, 4, category)).isNotNull();
    }

    static Stream<String> validCategories() {
        return Stream.of("TODO", "IN_PROGRESS", "IN_REVIEW", "DONE");
    }

    @ParameterizedTest
    @MethodSource("validPriorities")
    void acceptsEverySupportedPriority(String priority) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO tasks(id, column_id, title, position, priority) VALUES (?, ?, ?, ?, ?)",
                id, columnId, "priority-" + priority, 5, priority);
        assertThat(jdbc.queryForObject("SELECT priority FROM tasks WHERE id = ?", String.class, id))
                .isEqualTo(priority);
    }

    static Stream<String> validPriorities() {
        return Stream.of("LOW", "MEDIUM", "HIGH", "URGENT");
    }

    @ParameterizedTest
    @MethodSource("requiredColumns")
    void rejectsExplicitNullForRequiredColumns(String column) {
        assertSqlState("23502", () -> {
            switch (column) {
                case "board.workspace" -> jdbc.update(
                        "INSERT INTO boards(workspace_id, name) VALUES (NULL, 'x')");
                case "board.name" -> jdbc.update(
                        "INSERT INTO boards(workspace_id, name) VALUES (?, NULL)", WORKSPACE_ID);
                case "column.board" -> jdbc.update(
                        "INSERT INTO columns(board_id, name, position, status_category) VALUES (NULL, 'x', 0, 'TODO')");
                case "column.name" -> jdbc.update(
                        "INSERT INTO columns(board_id, name, position, status_category) VALUES (?, NULL, 0, 'TODO')",
                        boardId);
                case "column.position" -> jdbc.update(
                        "INSERT INTO columns(board_id, name, position, status_category) VALUES (?, 'x', NULL, 'TODO')",
                        boardId);
                case "column.status" -> jdbc.update(
                        "INSERT INTO columns(board_id, name, position, status_category) VALUES (?, 'x', 0, NULL)",
                        boardId);
                case "task.column" -> jdbc.update(
                        "INSERT INTO tasks(column_id, title, position) VALUES (NULL, 'x', 0)");
                case "task.title" -> jdbc.update(
                        "INSERT INTO tasks(column_id, title, position) VALUES (?, NULL, 0)", columnId);
                case "task.position" -> jdbc.update(
                        "INSERT INTO tasks(column_id, title, position) VALUES (?, 'x', NULL)", columnId);
                case "task.priority" -> jdbc.update(
                        "INSERT INTO tasks(column_id, title, position, priority) VALUES (?, 'x', 0, NULL)", columnId);
                case "comment.task" -> jdbc.update(
                        "INSERT INTO task_comments(task_id, author_id, content) VALUES (NULL, ?, 'x')", AUTHOR_ID);
                case "comment.author" -> jdbc.update(
                        "INSERT INTO task_comments(task_id, author_id, content) VALUES (?, NULL, 'x')", taskId);
                case "comment.content" -> jdbc.update(
                        "INSERT INTO task_comments(task_id, author_id, content) VALUES (?, ?, NULL)", taskId,
                        AUTHOR_ID);
                case "tag.board" -> jdbc.update("INSERT INTO task_tags(board_id, name, color) VALUES (NULL, 'x', 'x')");
                case "tag.name" -> jdbc.update("INSERT INTO task_tags(board_id, name, color) VALUES (?, NULL, 'x')",
                        boardId);
                case "tag.color" -> jdbc.update("INSERT INTO task_tags(board_id, name, color) VALUES (?, 'x', NULL)",
                        boardId);
                case "mapping.task" -> jdbc.update(
                        "INSERT INTO task_tag_mappings(task_id, tag_id) VALUES (NULL, ?)", tagId);
                case "mapping.tag" -> jdbc.update(
                        "INSERT INTO task_tag_mappings(task_id, tag_id) VALUES (?, NULL)", taskId);
                default -> throw new IllegalArgumentException("Unknown required column: " + column);
            }
        });
    }

    static Stream<String> requiredColumns() {
        return Stream.of("board.workspace", "board.name", "column.board", "column.name", "column.position",
                "column.status", "task.column", "task.title", "task.position", "task.priority", "comment.task",
                "comment.author", "comment.content", "tag.board", "tag.name", "tag.color", "mapping.task",
                "mapping.tag");
    }

    @ParameterizedTest
    @MethodSource("boundedStrings")
    void acceptsMaximumStringLengthAndRejectsOneCharacterOver(String field, int maxLength) {
        String maximum = "x".repeat(maxLength);
        insertBoundedString(field, maximum);
        assertSqlState("22001", () -> insertBoundedString(field, maximum + "x"));
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> boundedStrings() {
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("board.name", 255),
                org.junit.jupiter.params.provider.Arguments.of("column.name", 100),
                org.junit.jupiter.params.provider.Arguments.of("task.title", 255),
                org.junit.jupiter.params.provider.Arguments.of("tag.name", 50),
                org.junit.jupiter.params.provider.Arguments.of("tag.color", 20));
    }

    private void insertBoundedString(String field, String value) {
        switch (field) {
            case "board.name" -> jdbc.update("INSERT INTO boards(workspace_id, name) VALUES (?, ?)",
                    WORKSPACE_ID, value);
            case "column.name" -> insertColumn(boardId, value, 8, "TODO");
            case "task.title" -> jdbc.update("INSERT INTO tasks(column_id, title, position) VALUES (?, ?, 8)",
                    columnId, value);
            case "tag.name" -> jdbc.update("INSERT INTO task_tags(board_id, name, color) VALUES (?, ?, 'x')",
                    boardId, value);
            case "tag.color" -> jdbc.update("INSERT INTO task_tags(board_id, name, color) VALUES (?, 'x', ?)",
                    boardId, value);
            default -> throw new IllegalArgumentException("Unknown bounded field: " + field);
        }
    }

    @ParameterizedTest
    @MethodSource("invalidCategoryAndPrioritySql")
    void rejectsValuesOutsideStatusAndPriorityDomains(String field, String value) {
        if ("status".equals(field)) {
            assertSqlState("23514", () -> insertColumn(boardId, "invalid-category", 8, value));
        } else {
            assertSqlState("23514", () -> jdbc.update(
                    "INSERT INTO tasks(column_id, title, position, priority) VALUES (?, 'invalid-priority', 8, ?)",
                    columnId, value));
        }
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> invalidCategoryAndPrioritySql() {
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("status", "BLOCKED"),
                org.junit.jupiter.params.provider.Arguments.of("status", "todo"),
                org.junit.jupiter.params.provider.Arguments.of("priority", "CRITICAL"),
                org.junit.jupiter.params.provider.Arguments.of("priority", "low"));
    }

    @ParameterizedTest
    @MethodSource("invalidPositions")
    void rejectsNegativePositions(String target) {
        if ("column".equals(target)) {
            assertSqlState("23514", () -> insertColumn(boardId, "negative-position", -1, "TODO"));
        } else {
            assertSqlState("23514", () -> jdbc.update(
                    "INSERT INTO tasks(column_id, title, position) VALUES (?, 'negative-position', -1)", columnId));
        }
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> invalidPositions() {
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("column"),
                org.junit.jupiter.params.provider.Arguments.of("task"));
    }

    @ParameterizedTest
    @MethodSource("invalidForeignKeys")
    void rejectsMissingInternalParents(String relation) {
        UUID absent = UUID.randomUUID();
        switch (relation) {
            case "column-board" -> assertSqlState("23503", () -> insertColumn(absent, "x", 0, "TODO"));
            case "task-column" -> assertSqlState("23503", () -> insertTask(absent, "x", 0));
            case "comment-task" -> assertSqlState("23503", () -> jdbc.update(
                    "INSERT INTO task_comments(task_id, author_id, content) VALUES (?, ?, 'x')", absent, AUTHOR_ID));
            case "tag-board" -> assertSqlState("23503", () -> insertTag(absent, "x", "blue"));
            case "mapping-tag" -> assertSqlState("23503", () -> jdbc.update(
                    "INSERT INTO task_tag_mappings(task_id, tag_id) VALUES (?, ?)", taskId, absent));
            case "mapping-task" -> assertSqlState("23503", () -> jdbc.update(
                    "INSERT INTO task_tag_mappings(task_id, tag_id) VALUES (?, ?)", absent, tagId));
            default -> throw new IllegalArgumentException("Unknown relation case: " + relation);
        }
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> invalidForeignKeys() {
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("column-board"),
                org.junit.jupiter.params.provider.Arguments.of("task-column"),
                org.junit.jupiter.params.provider.Arguments.of("comment-task"),
                org.junit.jupiter.params.provider.Arguments.of("tag-board"),
                org.junit.jupiter.params.provider.Arguments.of("mapping-tag"),
                org.junit.jupiter.params.provider.Arguments.of("mapping-task"));
    }

    @Test
    void acceptsCrossModuleSoftReferencesAndRecordsCrossBoardTagLimit() {
        UUID orphanWorkspaceId = UUID.randomUUID();
        UUID orphanAssigneeId = UUID.randomUUID();
        UUID orphanAuthorId = UUID.randomUUID();
        UUID boardWithNoWorkspace = insertBoard(orphanWorkspaceId);
        UUID taskWithUnresolvedAssignee = insertTask(columnId, "soft ref", 9);
        jdbc.update("UPDATE tasks SET assignee_id = ? WHERE id = ?", orphanAssigneeId, taskWithUnresolvedAssignee);
        jdbc.update("INSERT INTO task_comments(task_id, author_id, content) VALUES (?, ?, ?)",
                taskWithUnresolvedAssignee, orphanAuthorId, "author is a soft reference");

        jdbc.update("INSERT INTO task_tag_mappings(task_id, tag_id) VALUES (?, ?)", taskId, otherTagId);

        assertThat(jdbc.queryForObject("SELECT workspace_id FROM boards WHERE id = ?", UUID.class, boardWithNoWorkspace))
                .isEqualTo(orphanWorkspaceId);
        assertThat(jdbc.queryForObject("SELECT assignee_id FROM tasks WHERE id = ?", UUID.class,
                taskWithUnresolvedAssignee)).isEqualTo(orphanAssigneeId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_comments WHERE task_id = ? AND author_id = ?",
                Long.class, taskWithUnresolvedAssignee, orphanAuthorId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_tag_mappings WHERE task_id = ? AND tag_id = ?",
                Long.class, taskId, otherTagId)).isEqualTo(1);
        jdbc.update("DELETE FROM boards WHERE id = ?", boardWithNoWorkspace);
    }

    @Test
    void rejectsDuplicateIdsAndDuplicateTaskTagMappings() {
        assertSqlState("23505", () -> jdbc.update(
                "INSERT INTO tasks(id, column_id, title, position) VALUES (?, ?, 'duplicate id', 1)",
                taskId, columnId));
        assertSqlState("23505", () -> jdbc.update(
                "INSERT INTO task_tag_mappings(task_id, tag_id) VALUES (?, ?)", taskId, tagId));
    }

    @Test
    void rejectsMalformedUuidAndOutOfRangeIntegerValues() {
        assertSqlState("22P02", () -> jdbc.update(
                "INSERT INTO boards(workspace_id, name) VALUES ('not-a-uuid', 'bad uuid')"));
        assertSqlState("22003", () -> jdbc.update(
                "INSERT INTO columns(board_id, name, position, status_category) VALUES (?, 'too large', ?, 'TODO')",
                boardId, Long.MAX_VALUE));
    }

    @Test
    void persistsOrderingAndAllowsTemporaryDuplicatesAndGaps() {
        UUID secondColumnId = insertColumn(boardId, "Later", 8, "DONE");
        jdbc.update("UPDATE tasks SET position = 4 WHERE id = ?", taskId);
        jdbc.update("UPDATE tasks SET column_id = ?, position = 4 WHERE id = ?", secondColumnId, otherTaskId);
        jdbc.update("INSERT INTO tasks(column_id, title, position) VALUES (?, 'same position', 4)", columnId);

        assertThat(jdbc.queryForList("SELECT position FROM columns WHERE board_id = ? ORDER BY position, id",
                Integer.class, boardId)).containsExactly(0, 8);
        assertThat(jdbc.queryForList("SELECT position FROM tasks WHERE column_id = ? ORDER BY position, id",
                Integer.class, columnId)).containsExactly(4, 4);
        assertThat(jdbc.queryForObject("SELECT column_id FROM tasks WHERE id = ?", UUID.class, otherTaskId))
                .isEqualTo(secondColumnId);
    }

    @Test
    void rollsBackTaskUpdatesAndCascadedDeletesAsOneTransaction() {
        jdbc.update("INSERT INTO task_comments(task_id, author_id, content) VALUES (?, ?, 'keep me')", taskId,
                AUTHOR_ID);
        String originalTitle = jdbc.queryForObject("SELECT title FROM tasks WHERE id = ?", String.class, taskId);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.update("UPDATE tasks SET title = 'temporary' WHERE id = ?", taskId);
            jdbc.update("DELETE FROM boards WHERE id = ?", boardId);
            status.setRollbackOnly();
        });

        assertThat(jdbc.queryForObject("SELECT title FROM tasks WHERE id = ?", String.class, taskId))
                .isEqualTo(originalTitle);
        assertThat(scalarLong("SELECT COUNT(*) FROM task_comments WHERE task_id = ?", taskId)).isEqualTo(1);
        assertThat(scalarLong("SELECT COUNT(*) FROM task_tag_mappings WHERE task_id = ? AND tag_id = ?",
                taskId, tagId)).isEqualTo(1);
    }

    @Test
    void deletingTaskCascadesCommentsAndMappingsButKeepsSharedTags() {
        UUID sharedTaskId = insertTask(columnId, "shares a tag", 2);
        jdbc.update("INSERT INTO task_comments(task_id, author_id, content) VALUES (?, ?, 'comment')", taskId,
                AUTHOR_ID);
        jdbc.update("INSERT INTO task_tag_mappings(task_id, tag_id) VALUES (?, ?)", sharedTaskId, tagId);

        jdbc.update("DELETE FROM tasks WHERE id = ?", taskId);

        assertThat(scalarLong("SELECT COUNT(*) FROM task_comments WHERE task_id = ?", taskId)).isZero();
        assertThat(scalarLong("SELECT COUNT(*) FROM task_tag_mappings WHERE task_id = ?", taskId)).isZero();
        assertThat(scalarLong("SELECT COUNT(*) FROM task_tags WHERE id = ?", tagId)).isEqualTo(1);
        assertThat(scalarLong("SELECT COUNT(*) FROM task_tag_mappings WHERE task_id = ? AND tag_id = ?",
                sharedTaskId, tagId)).isEqualTo(1);
    }

    @Test
    void deletingColumnCascadesItsTasksAndDependents() {
        jdbc.update("INSERT INTO task_comments(task_id, author_id, content) VALUES (?, ?, 'comment')", taskId,
                AUTHOR_ID);

        jdbc.update("DELETE FROM columns WHERE id = ?", columnId);

        assertThat(scalarLong("SELECT COUNT(*) FROM tasks WHERE id = ?", taskId)).isZero();
        assertThat(scalarLong("SELECT COUNT(*) FROM tasks WHERE column_id = ?", columnId)).isZero();
        assertThat(scalarLong("SELECT COUNT(*) FROM task_comments WHERE task_id = ?", taskId)).isZero();
        assertThat(scalarLong("SELECT COUNT(*) FROM task_tag_mappings WHERE task_id = ?", taskId)).isZero();
        assertThat(scalarLong("SELECT COUNT(*) FROM task_tags WHERE id = ?", tagId)).isEqualTo(1);
        assertThat(scalarLong("SELECT COUNT(*) FROM boards WHERE id = ?", boardId)).isEqualTo(1);
    }

    @Test
    void deletingTagRemovesItsMappingsWithoutDeletingTasksOrOtherTags() {
        UUID secondTagId = insertTag(boardId, "bug", "red");
        jdbc.update("INSERT INTO task_tag_mappings(task_id, tag_id) VALUES (?, ?)", taskId, secondTagId);

        jdbc.update("DELETE FROM task_tags WHERE id = ?", tagId);

        assertThat(scalarLong("SELECT COUNT(*) FROM tasks WHERE id = ?", taskId)).isEqualTo(1);
        assertThat(scalarLong("SELECT COUNT(*) FROM task_tags WHERE id = ?", secondTagId)).isEqualTo(1);
        assertThat(scalarLong("SELECT COUNT(*) FROM task_tag_mappings WHERE task_id = ?", taskId)).isEqualTo(1);
        assertThat(scalarLong("SELECT COUNT(*) FROM task_tag_mappings WHERE task_id = ? AND tag_id = ?",
                taskId, secondTagId)).isEqualTo(1);
    }

    @Test
    void deletingBoardCascadesOnlyItsKanbanDataAndArchivingDoesNotCascade() {
        UUID commentId = jdbc.queryForObject(
                "INSERT INTO task_comments(task_id, author_id, content) VALUES (?, ?, 'comment') RETURNING id",
                UUID.class, taskId, AUTHOR_ID);
        UUID mappingTaskId = taskId;
        archivedBoardId = insertBoard(UUID.randomUUID());
        UUID archivedColumnId = insertColumn(archivedBoardId, "Archived", 0, "TODO");
        UUID archivedTaskId = insertTask(archivedColumnId, "Keep me", 0);
        jdbc.update("UPDATE boards SET is_archived = TRUE WHERE id = ?", archivedBoardId);

        jdbc.update("DELETE FROM boards WHERE id = ?", boardId);

        assertThat(scalarLong("SELECT COUNT(*) FROM boards WHERE id = ?", boardId)).isZero();
        assertThat(scalarLong("SELECT COUNT(*) FROM columns WHERE board_id = ?", boardId)).isZero();
        assertThat(scalarLong("SELECT COUNT(*) FROM tasks WHERE id = ?", mappingTaskId)).isZero();
        assertThat(scalarLong("SELECT COUNT(*) FROM task_comments WHERE id = ?", commentId)).isZero();
        assertThat(scalarLong("SELECT COUNT(*) FROM task_tags WHERE board_id = ?", boardId)).isZero();
        assertThat(scalarLong("SELECT COUNT(*) FROM task_tag_mappings WHERE task_id = ?", mappingTaskId)).isZero();
        assertThat(scalarLong("SELECT COUNT(*) FROM boards WHERE id = ?", otherBoardId)).isEqualTo(1);
        assertThat(scalarLong("SELECT COUNT(*) FROM tasks WHERE id = ?", otherTaskId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT is_archived FROM boards WHERE id = ?", Boolean.class, archivedBoardId))
                .isTrue();
        assertThat(scalarLong("SELECT COUNT(*) FROM tasks WHERE id = ?", archivedTaskId)).isEqualTo(1);
    }

    private UUID insertBoard(UUID workspaceId) {
        return jdbc.queryForObject("INSERT INTO boards(workspace_id, name) VALUES (?, ?) RETURNING id", UUID.class,
                workspaceId, "Board " + UUID.randomUUID());
    }

    private UUID insertColumn(UUID parentBoardId, String name, int position, String category) {
        return jdbc.queryForObject("""
                INSERT INTO columns(board_id, name, position, status_category)
                VALUES (?, ?, ?, ?) RETURNING id
                """, UUID.class, parentBoardId, name, position, category);
    }

    private UUID insertTask(UUID parentColumnId, String title, int position) {
        return jdbc.queryForObject("""
                INSERT INTO tasks(column_id, title, position)
                VALUES (?, ?, ?) RETURNING id
                """, UUID.class, parentColumnId, title, position);
    }

    private UUID insertTag(UUID parentBoardId, String name, String color) {
        return jdbc.queryForObject("""
                INSERT INTO task_tags(board_id, name, color)
                VALUES (?, ?, ?) RETURNING id
                """, UUID.class, parentBoardId, name, color);
    }

    private Set<String> tableNames(DatabaseMetaData metadata) throws SQLException {
        try (ResultSet result = metadata.getTables(null, "public", "%", new String[] {"TABLE"})) {
            var names = new java.util.HashSet<String>();
            while (result.next()) {
                names.add(result.getString("TABLE_NAME"));
            }
            return names;
        }
    }

    private Set<String> indexNames(DatabaseMetaData metadata, String table) throws SQLException {
        try (ResultSet result = metadata.getIndexInfo(null, "public", table, false, false)) {
            var names = new java.util.HashSet<String>();
            while (result.next()) {
                String name = result.getString("INDEX_NAME");
                if (name != null) {
                    names.add(name);
                }
            }
            return names;
        }
    }

    private List<String> foreignKeyNames() {
        return jdbc.queryForList("""
                SELECT c.conname
                FROM pg_constraint c
                JOIN pg_class r ON r.oid = c.conrelid
                WHERE c.connamespace = current_schema()::regnamespace
                  AND c.contype = 'f'
                  AND r.relname IN ('boards', 'columns', 'tasks', 'task_comments',
                                    'task_tags', 'task_tag_mappings')
                """, String.class);
    }

    private long scalarLong(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private void assertSqlState(String expectedState, ThrowingSqlAction action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(error -> assertThat(findSqlState(error)).isEqualTo(expectedState));
    }

    private String findSqlState(Throwable error) {
        Throwable cause = error;
        while (cause != null) {
            if (cause instanceof SQLException sqlException && sqlException.getSQLState() != null) {
                return sqlException.getSQLState();
            }
            cause = cause.getCause();
        }
        return null;
    }

    @FunctionalInterface
    private interface ThrowingSqlAction {
        void run() throws Exception;
    }
}
