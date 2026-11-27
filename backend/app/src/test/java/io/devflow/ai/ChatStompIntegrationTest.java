package io.devflow.ai;

import static org.assertj.core.api.Assertions.assertThat;

import io.devflow.auth.internal.security.JwtTokenProvider;
import java.lang.reflect.Type;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/**
 * T-034 over a real STOMP connection through the full application: JWT CONNECT authentication, the Auth
 * membership decision, Board data, per-user {@code /user/queue/chat} delivery and the chat destination guard.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "devflow.ai.chat.stream-delay-ms=0")
@DisplayName("Chat over STOMP: per-user delivery, membership and forged frames (full app)")
class ChatStompIntegrationTest {

    private static final String CHAT_QUEUE = "/user/queue/chat";
    private static final String QUESTION = "How many tasks are left?";
    private static final String EXPECTED_ANSWER =
            "2 of 3 tasks are not done yet (to do: 1, in progress: 1, in review: 0; done: 1).";

    @LocalServerPort
    private int port;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SimpUserRegistry userRegistry;

    private final List<WebSocketStompClient> clients = new ArrayList<>();
    private UUID aliceId;
    private UUID bobId;
    private UUID outsiderId;
    private UUID workspaceId;

    @BeforeEach
    void createProject() {
        aliceId = UUID.randomUUID();
        bobId = UUID.randomUUID();
        outsiderId = UUID.randomUUID();
        workspaceId = UUID.randomUUID();
        insertUser(aliceId, "Alice Nguyen");
        insertUser(bobId, "Bob Tran");
        insertUser(outsiderId, "Mallory");
        jdbc.update("INSERT INTO workspaces(id, name, slug, owner_id, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                workspaceId, "Chat workspace", "chat-workspace-" + workspaceId, aliceId);
        insertMember(aliceId, "OWNER");
        insertMember(bobId, "MEMBER");

        UUID boardId = UUID.randomUUID();
        jdbc.update("INSERT INTO boards(id, workspace_id, name, is_archived, created_at, updated_at) "
                + "VALUES (?, ?, 'Sprint board', FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", boardId, workspaceId);
        insertTask(insertColumn(boardId, "To do", 0, "TODO"), "Write release notes", null);
        insertTask(insertColumn(boardId, "Doing", 1, "IN_PROGRESS"), "Build login form", aliceId);
        insertTask(insertColumn(boardId, "Done", 2, "DONE"), "Set up repository", aliceId);
    }

    @AfterEach
    void stopClients() {
        clients.forEach(WebSocketStompClient::stop);
        clients.clear();
    }

    @Test
    void twoMembersChattingAtOnceEachReceiveOnlyTheirOwnOrderedAnswer() throws Exception {
        Inbox alice = connectAndSubscribe(aliceId);
        Inbox bob = connectAndSubscribe(bobId);
        UUID aliceConversation = UUID.randomUUID();
        UUID bobConversation = UUID.randomUUID();

        alice.session.send("/app/chat", chat(aliceConversation, QUESTION));
        bob.session.send("/app/chat", chat(bobConversation, QUESTION));

        alice.awaitTerminal(aliceConversation);
        bob.awaitTerminal(bobConversation);

        assertThat(alice.frames).allMatch(frame -> aliceConversation.toString().equals(frame.get("conversationId")));
        assertThat(bob.frames).allMatch(frame -> bobConversation.toString().equals(frame.get("conversationId")));
        assertOrderedAnswer(alice.frames);
        assertOrderedAnswer(bob.frames);
    }

    @Test
    void userWithSeveralSessionsGetsTheAnswerOnEverySession() throws Exception {
        // Real users hold several sockets at once (board sync, notifications, chat): the answer must fan out.
        Inbox chatTab = connectAndSubscribe(aliceId);
        Inbox otherTab = connectAndSubscribe(aliceId);
        Inbox notificationsOnly = connect(aliceId);
        notificationsOnly.session.subscribe("/user/queue/notifications", notificationsOnly);
        UUID conversation = UUID.randomUUID();

        chatTab.session.send("/app/chat", chat(conversation, QUESTION));

        chatTab.awaitTerminal(conversation);
        otherTab.awaitTerminal(conversation);
        assertOrderedAnswer(chatTab.frames);
        assertOrderedAnswer(otherTab.frames);
        assertThat(notificationsOnly.frames).isEmpty();
    }

    @Test
    void nonMemberReceivesOnlyAnAccessDeniedError() throws Exception {
        Inbox outsider = connectAndSubscribe(outsiderId);
        UUID conversation = UUID.randomUUID();

        outsider.session.send("/app/chat", chat(conversation, QUESTION));

        outsider.awaitTerminal(conversation);
        assertThat(outsider.frames).hasSize(1);
        Map<String, Object> error = outsider.frames.get(0);
        assertThat(error.get("type")).isEqualTo("ERROR");
        assertThat(error.get("metadata")).isEqualTo(Map.of("code", "WORKSPACE_ACCESS_DENIED"));
    }

    @Test
    void forgedFramesCannotReachAnotherUsersChatQueue() throws Exception {
        Inbox bob = connectAndSubscribe(bobId);
        Inbox eavesdropper = connect(outsiderId);
        eavesdropper.session.subscribe("/user/" + bobId + "/queue/chat", eavesdropper);
        Inbox forger = connect(outsiderId);
        UUID forgedConversation = UUID.randomUUID();

        forger.session.send("/user/" + bobId + "/queue/chat", Map.of(
                "conversationId", forgedConversation.toString(), "seq", 0, "type", "TASK_PROPOSAL",
                "content", "Click to create", "metadata", Map.of("proposals", List.of())));

        UUID bobConversation = UUID.randomUUID();
        bob.session.send("/app/chat", chat(bobConversation, QUESTION));
        bob.awaitTerminal(bobConversation);
        Thread.sleep(300);

        assertThat(bob.frames).noneMatch(frame -> forgedConversation.toString().equals(frame.get("conversationId")));
        assertThat(eavesdropper.frames).isEmpty();
    }

    /** Outbound order is not forced per session, so frames are put back in {@code seq} order like the client does. */
    private void assertOrderedAnswer(List<Map<String, Object>> frames) {
        List<Map<String, Object>> ends = frames.stream().filter(frame -> "END".equals(frame.get("type"))).toList();
        assertThat(ends).hasSize(1);
        List<Map<String, Object>> chunks = frames.stream().filter(frame -> "CHUNK".equals(frame.get("type")))
                .sorted(java.util.Comparator.comparingInt(frame -> (Integer) frame.get("seq"))).toList();
        assertThat(frames).hasSize(chunks.size() + 1);
        assertThat(ends.get(0).get("seq")).isEqualTo(chunks.size());
        StringBuilder answer = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            assertThat(chunks.get(i).get("seq")).isEqualTo(i);
            answer.append(chunks.get(i).get("content"));
        }
        assertThat(answer.toString()).isEqualTo(EXPECTED_ANSWER);
    }

    private Inbox connectAndSubscribe(UUID userId) throws Exception {
        Inbox inbox = connect(userId);
        inbox.session.subscribe(CHAT_QUEUE, inbox);
        await(Duration.ofSeconds(3), "chat subscription of " + userId, () -> {
            var user = userRegistry.getUser(userId.toString());
            return user != null && user.getSessions().stream()
                    .flatMap(session -> session.getSubscriptions().stream())
                    .anyMatch(subscription -> CHAT_QUEUE.equals(subscription.getDestination()));
        });
        return inbox;
    }

    private Inbox connect(UUID userId) throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new MappingJackson2MessageConverter());
        clients.add(client);
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + tokenProvider.generateAccessToken(userId, userId + "@devflow.test"));
        WebSocketHttpHeaders handshakeHeaders = new WebSocketHttpHeaders();
        handshakeHeaders.setOrigin("http://localhost:5173");
        Inbox inbox = new Inbox();
        inbox.session = client.connectAsync(URI.create("ws://localhost:" + port + "/ws"), handshakeHeaders,
                connectHeaders, inbox.sessionHandler).get(5, TimeUnit.SECONDS);
        return inbox;
    }

    private Map<String, Object> chat(UUID conversationId, String message) {
        return Map.of("conversationId", conversationId.toString(), "workspaceId", workspaceId.toString(),
                "message", message);
    }

    private void insertUser(UUID id, String fullName) {
        // H2 schema comes from the JPA entities (test profile), so audit columns have no defaults.
        jdbc.update("INSERT INTO users(id, email, password_hash, full_name, status, created_at, updated_at) "
                        + "VALUES (?, ?, 'test-hash', ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                id, "user-" + id + "@devflow.test", fullName);
    }

    private void insertMember(UUID userId, String role) {
        jdbc.update("INSERT INTO workspace_members(id, workspace_id, user_id, role, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                UUID.randomUUID(), workspaceId, userId, role);
    }

    private UUID insertColumn(UUID boardId, String name, int position, String category) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO columns(id, board_id, name, position, status_category, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", id, boardId, name, position, category);
        return id;
    }

    private void insertTask(UUID columnId, String title, UUID assigneeId) {
        jdbc.update("INSERT INTO tasks(id, column_id, title, position, priority, assignee_id, version, created_at, "
                        + "updated_at) VALUES (?, ?, ?, 0, 'MEDIUM', ?, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                UUID.randomUUID(), columnId, title, assigneeId);
    }

    private static void await(Duration timeout, String what, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Timed out waiting for " + what);
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted waiting for " + what, e);
            }
        }
    }

    /** Collects chat frames and STOMP ERROR frames of one connection. */
    private static final class Inbox implements StompFrameHandler {

        private final List<Map<String, Object>> frames = new CopyOnWriteArrayList<>();
        private final List<String> errors = new CopyOnWriteArrayList<>();
        private StompSession session;
        private final StompSessionHandlerAdapter sessionHandler = new StompSessionHandlerAdapter() {
            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                errors.add(String.valueOf(headers.getFirst("message")));
            }

            @Override
            public void handleException(StompSession session, StompCommand command, StompHeaders headers,
                    byte[] payload, Throwable exception) {
                errors.add(exception.getClass().getSimpleName());
            }

            @Override
            public void handleTransportError(StompSession session, Throwable exception) {
                errors.add(exception.getClass().getSimpleName());
            }
        };

        @Override
        public Type getPayloadType(StompHeaders headers) {
            return Map.class;
        }

        @Override
        @SuppressWarnings("unchecked")
        public void handleFrame(StompHeaders headers, Object payload) {
            frames.add((Map<String, Object>) payload);
        }

        /** Waits for ERROR, or for END plus every chunk it announces (END may overtake the last chunks). */
        void awaitTerminal(UUID conversationId) {
            await(Duration.ofSeconds(5), "END or ERROR for " + conversationId, () -> {
                List<Map<String, Object>> own = frames.stream()
                        .filter(frame -> conversationId.toString().equals(frame.get("conversationId"))).toList();
                if (own.stream().anyMatch(frame -> "ERROR".equals(frame.get("type")))) {
                    return true;
                }
                return own.stream().filter(frame -> "END".equals(frame.get("type"))).findFirst()
                        .map(end -> own.stream().filter(frame -> "CHUNK".equals(frame.get("type"))).count()
                                >= (Integer) end.get("seq"))
                        .orElse(false);
            });
        }
    }
}
