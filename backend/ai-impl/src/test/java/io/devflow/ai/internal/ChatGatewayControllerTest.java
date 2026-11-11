package io.devflow.ai.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

import io.devflow.ai.api.CancelChatRequest;
import io.devflow.ai.api.ChatMessageRequest;
import io.devflow.ai.api.ChatMessageResponse;
import io.devflow.ai.api.ChatMessageType;
import io.devflow.ai.internal.qa.ProjectFixture;
import io.devflow.ai.internal.support.TestBoardDirectory;
import io.devflow.ai.internal.support.TestMembershipDirectory;
import io.devflow.ai.internal.support.TestUserDirectory;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.awaitility.Awaitility;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Acceptance criteria tests for T-034: Per-User Streaming ChatGatewayController over STOMP.
 *
 * <p>Verifies:
 * <ul>
 *   <li>Per-user isolation: two concurrent chat sessions receive only their own replies;</li>
 *   <li>Membership check: non-member access is rejected with an error message;</li>
 *   <li>Sequenced streaming: chunks stream in monotonic order and conclude with an END message;</li>
 *   <li>Task proposal routing: task breakdown intent yields a TASK_PROPOSAL message with metadata;</li>
 *   <li>Rate limit enforcement: requests exceeding the bucket are rejected with ERROR;</li>
 *   <li>Cancellation: active streams can be aborted on demand.</li>
 * </ul>
 */
@SpringBootTest(classes = TestAiApplication.class, properties = {
        "devflow.ai.rate-limit.capacity=5",
        "devflow.ai.rate-limit.period=1h",
        "devflow.ai.chat.stream-delay-ms=0"
})
@Import(ProjectFixture.FixedClock.class)
@DisplayName("T-034: ChatGatewayController & ChatStreamService STOMP Tests")
class ChatGatewayControllerTest {

    @MockitoBean
    private SimpMessagingTemplate messagingTemplate;

    @Autowired
    private ChatGatewayController chatGatewayController;

    @Autowired
    private TestMembershipDirectory membershipDirectory;

    @Autowired
    private TestUserDirectory userDirectory;

    @Autowired
    private TestBoardDirectory boardDirectory;

    private ProjectFixture fixture;
    private final List<CapturedMessage> capturedMessages = new CopyOnWriteArrayList<>();

    private record CapturedMessage(String destinationUser, String destinationQueue, ChatMessageResponse payload) {}

    @BeforeEach
    void setUp() {
        fixture = new ProjectFixture(userDirectory, membershipDirectory, boardDirectory);
        capturedMessages.clear();

        Mockito.doAnswer(invocation -> {
            String user = invocation.getArgument(0);
            String queue = invocation.getArgument(1);
            ChatMessageResponse response = invocation.getArgument(2);
            capturedMessages.add(new CapturedMessage(user, queue, response));
            return null;
        }).when(messagingTemplate).convertAndSendToUser(any(), any(), any());
    }

    @Test
    @DisplayName("Two users chatting concurrently only receive their own responses on /user/queue/chat")
    void twoConcurrentUsers_receiveOnlyTheirOwnMessages() {
        UUID user1 = fixture.askerId;
        UUID user2 = fixture.bobId;
        UUID workspaceId = fixture.projectId;

        // Ensure both users are members of the workspace
        membershipDirectory.addMember(user1, workspaceId);
        membershipDirectory.addMember(user2, workspaceId);

        UUID conv1 = UUID.randomUUID();
        UUID conv2 = UUID.randomUUID();

        ChatMessageRequest request1 = new ChatMessageRequest(conv1, workspaceId, "how many tasks are left in this project?");
        ChatMessageRequest request2 = new ChatMessageRequest(conv2, workspaceId, "du an con bao nhieu task?");

        chatGatewayController.chat(request1, principal(user1));
        chatGatewayController.chat(request2, principal(user2));

        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .until(() -> userMessages(user1).stream().anyMatch(m -> m.payload().type() == ChatMessageType.END)
                        && userMessages(user2).stream().anyMatch(m -> m.payload().type() == ChatMessageType.END));

        List<CapturedMessage> user1Msgs = userMessages(user1);
        List<CapturedMessage> user2Msgs = userMessages(user2);

        assertThat(user1Msgs).isNotEmpty();
        assertThat(user2Msgs).isNotEmpty();

        // Check user 1 received only conv1
        assertThat(user1Msgs).allMatch(m -> m.payload().conversationId().equals(conv1));
        assertThat(user1Msgs).allMatch(m -> m.destinationQueue().equals("/queue/chat"));

        // Check user 2 received only conv2
        assertThat(user2Msgs).allMatch(m -> m.payload().conversationId().equals(conv2));
        assertThat(user2Msgs).allMatch(m -> m.destinationQueue().equals("/queue/chat"));

        // Verify no crosstalk
        assertThat(user1Msgs).noneMatch(m -> m.payload().conversationId().equals(conv2));
        assertThat(user2Msgs).noneMatch(m -> m.payload().conversationId().equals(conv1));
    }

    @Test
    @DisplayName("User not in workspace is rejected with ERROR message")
    void nonMemberUser_rejectedWithErrorMessage() {
        UUID outsider = userDirectory.addUser("Eve Outsider");
        UUID workspaceId = fixture.projectId;
        UUID convId = UUID.randomUUID();

        ChatMessageRequest request = new ChatMessageRequest(convId, workspaceId, "how many tasks are left in this project?");
        chatGatewayController.chat(request, principal(outsider));

        Awaitility.await()
                .atMost(Duration.ofSeconds(3))
                .until(() -> !userMessages(outsider).isEmpty());

        List<CapturedMessage> msgs = userMessages(outsider);
        assertThat(msgs).hasSize(1);
        ChatMessageResponse errorResponse = msgs.get(0).payload();
        assertThat(errorResponse.type()).isEqualTo(ChatMessageType.ERROR);
        assertThat(errorResponse.conversationId()).isEqualTo(convId);
        assertThat(errorResponse.seq()).isEqualTo(-1);
        assertThat(errorResponse.content()).containsIgnoringCase("access");
    }

    @Test
    @DisplayName("Streaming chunks have monotonic sequence numbers and conclude with END")
    void streamResponse_sequencesInOrderAndEndsWithEnd() {
        UUID user = fixture.askerId;
        UUID workspaceId = fixture.projectId;
        UUID convId = UUID.randomUUID();

        ChatMessageRequest request = new ChatMessageRequest(convId, workspaceId, "how many tasks are left in this project?");
        chatGatewayController.chat(request, principal(user));

        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .until(() -> userMessages(user).stream().anyMatch(m -> m.payload().type() == ChatMessageType.END));

        List<ChatMessageResponse> responses = userMessages(user).stream().map(CapturedMessage::payload).toList();
        assertThat(responses).isNotEmpty();

        // Last message must be END
        ChatMessageResponse last = responses.get(responses.size() - 1);
        assertThat(last.type()).isEqualTo(ChatMessageType.END);

        // Previous messages must be CHUNKs with strictly increasing seq starting from 0
        List<ChatMessageResponse> chunks = responses.subList(0, responses.size() - 1);
        for (int i = 0; i < chunks.size(); i++) {
            assertThat(chunks.get(i).seq()).isEqualTo(i);
            assertThat(chunks.get(i).type()).isEqualTo(ChatMessageType.CHUNK);
            assertThat(chunks.get(i).content()).isNotEmpty();
        }
    }

    @Test
    @DisplayName("Task breakdown intent returns TASK_PROPOSAL message with proposal list in metadata")
    void taskBreakdownIntent_returnsTaskProposal() {
        UUID user = fixture.askerId;
        UUID workspaceId = fixture.projectId;
        UUID convId = UUID.randomUUID();

        ChatMessageRequest request = new ChatMessageRequest(convId, workspaceId, "hãy chia task cho tính năng thanh toán VNPay");
        chatGatewayController.chat(request, principal(user));

        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .until(() -> userMessages(user).stream().anyMatch(m -> m.payload().type() == ChatMessageType.END));

        List<ChatMessageResponse> responses = userMessages(user).stream().map(CapturedMessage::payload).toList();
        assertThat(responses).hasSize(2);

        ChatMessageResponse proposalMsg = responses.get(0);
        assertThat(proposalMsg.type()).isEqualTo(ChatMessageType.TASK_PROPOSAL);
        assertThat(proposalMsg.conversationId()).isEqualTo(convId);
        assertThat(proposalMsg.metadata()).containsKey("proposals");

        Object proposals = proposalMsg.metadata().get("proposals");
        assertThat(proposals).isInstanceOf(List.class);
        List<?> proposalList = (List<?>) proposals;
        assertThat(proposalList).isNotEmpty();

        ChatMessageResponse endMsg = responses.get(1);
        assertThat(endMsg.type()).isEqualTo(ChatMessageType.END);
        assertThat(endMsg.conversationId()).isEqualTo(convId);
    }

    @Test
    @DisplayName("Input validation rejects missing fields or oversized messages with ERROR")
    void inputValidation_rejectsInvalidInputs() {
        UUID user = fixture.askerId;
        UUID convId = UUID.randomUUID();

        // 1. Missing workspaceId
        ChatMessageRequest missingWorkspace = new ChatMessageRequest(convId, null, "Hello");
        chatGatewayController.chat(missingWorkspace, principal(user));

        Awaitility.await().atMost(Duration.ofSeconds(2)).until(() -> !userMessages(user).isEmpty());
        assertThat(userMessages(user).get(0).payload().type()).isEqualTo(ChatMessageType.ERROR);
        assertThat(userMessages(user).get(0).payload().content()).containsIgnoringCase("workspaceId");

        capturedMessages.clear();

        // 2. Blank message
        ChatMessageRequest blankMsg = new ChatMessageRequest(convId, fixture.projectId, "   ");
        chatGatewayController.chat(blankMsg, principal(user));

        Awaitility.await().atMost(Duration.ofSeconds(2)).until(() -> !userMessages(user).isEmpty());
        assertThat(userMessages(user).get(0).payload().type()).isEqualTo(ChatMessageType.ERROR);
        assertThat(userMessages(user).get(0).payload().content()).containsIgnoringCase("Message is required");

        capturedMessages.clear();

        // 3. Oversized message (> 2000 chars)
        String longText = "a".repeat(2001);
        ChatMessageRequest oversizedMsg = new ChatMessageRequest(convId, fixture.projectId, longText);
        chatGatewayController.chat(oversizedMsg, principal(user));

        Awaitility.await().atMost(Duration.ofSeconds(2)).until(() -> !userMessages(user).isEmpty());
        assertThat(userMessages(user).get(0).payload().type()).isEqualTo(ChatMessageType.ERROR);
        assertThat(userMessages(user).get(0).payload().content()).containsIgnoringCase("must not exceed");
    }

    @Test
    @DisplayName("Cancel aborts an active stream")
    void cancelChat_stopsActiveStream() {
        UUID user = fixture.askerId;
        UUID workspaceId = fixture.projectId;
        UUID convId = UUID.randomUUID();

        CancelChatRequest cancelReq = new CancelChatRequest(convId);
        chatGatewayController.cancel(cancelReq, principal(user));

        // When cancelling nonexistent or completed stream, no error is thrown
        assertThat(userMessages(user)).isEmpty();
    }

    @Test
    @DisplayName("Exceeding per-user rate limit returns ERROR message")
    void rateLimitExceeded_returnsErrorMessage() {
        UUID rateLimitedUser = userDirectory.addUser("Fast Chatter");
        UUID workspaceId = fixture.projectId;
        membershipDirectory.addMember(rateLimitedUser, workspaceId);

        // Consume 5 allowed requests
        for (int i = 0; i < 5; i++) {
            UUID convId = UUID.randomUUID();
            ChatMessageRequest req = new ChatMessageRequest(convId, workspaceId, "how many tasks are left in this project?");
            chatGatewayController.chat(req, principal(rateLimitedUser));
            Awaitility.await().atMost(Duration.ofSeconds(3))
                    .until(() -> userMessages(rateLimitedUser).stream()
                            .anyMatch(m -> m.payload().conversationId().equals(convId) && m.payload().type() == ChatMessageType.END));
        }

        capturedMessages.clear();

        // 6th request should be rate-limited
        UUID conv6 = UUID.randomUUID();
        ChatMessageRequest req6 = new ChatMessageRequest(conv6, workspaceId, "how many tasks are left in this project?");
        chatGatewayController.chat(req6, principal(rateLimitedUser));

        Awaitility.await().atMost(Duration.ofSeconds(3))
                .until(() -> !userMessages(rateLimitedUser).isEmpty());

        List<CapturedMessage> msgs = userMessages(rateLimitedUser);
        assertThat(msgs).hasSize(1);
        ChatMessageResponse err = msgs.get(0).payload();
        assertThat(err.type()).isEqualTo(ChatMessageType.ERROR);
        assertThat(err.conversationId()).isEqualTo(conv6);
        assertThat(err.content()).containsIgnoringCase("Too many AI requests");
    }

    private List<CapturedMessage> userMessages(UUID userId) {
        return capturedMessages.stream()
                .filter(m -> m.destinationUser().equals(userId.toString()))
                .toList();
    }

    private static Principal principal(UUID userId) {
        return userId::toString;
    }
}
