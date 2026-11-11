package io.devflow.ai.internal;

import io.devflow.ai.api.ChatMessageRequest;
import io.devflow.ai.api.ChatMessageResponse;
import io.devflow.ai.api.ProjectQueryAnswer;
import io.devflow.ai.api.TaskBreakdownProposal;
import io.devflow.ai.internal.breakdown.TaskBreakdownService;
import io.devflow.ai.internal.config.AiProperties;
import io.devflow.ai.internal.exception.AiApiException;
import io.devflow.ai.internal.llm.AiFeature;
import io.devflow.ai.internal.qa.ProjectQueryService;
import io.devflow.ai.internal.security.AiRateLimiter;
import io.devflow.ai.internal.security.WorkspaceMembershipChecker;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.task.TaskExecutor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

/**
 * Service managing per-user streaming chat sessions over STOMP (T-034).
 *
 * <p>Enforces workspace membership and per-user rate limiting before routing requests
 * to either {@link TaskBreakdownService} (for task proposal intents) or
 * {@link ProjectQueryService} (for grounded project Q&amp;A). Chunks are pushed
 * exclusively to the caller's private destination {@code /user/queue/chat}.
 */
@Service
public class ChatStreamService implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ChatStreamService.class);
    private static final String CHAT_DESTINATION = "/queue/chat";
    private static final int DEFAULT_CHUNK_SIZE = 25;

    private final SimpMessagingTemplate messagingTemplate;
    private final WorkspaceMembershipChecker membershipChecker;
    private final AiRateLimiter rateLimiter;
    private final TaskBreakdownService taskBreakdownService;
    private final ProjectQueryService projectQueryService;
    private final AiProperties properties;
    private final ExecutorService executorService;
    private final ConcurrentHashMap<UUID, Future<?>> activeStreams = new ConcurrentHashMap<>();

    public ChatStreamService(
            SimpMessagingTemplate messagingTemplate,
            WorkspaceMembershipChecker membershipChecker,
            AiRateLimiter rateLimiter,
            TaskBreakdownService taskBreakdownService,
            ProjectQueryService projectQueryService,
            AiProperties properties,
            ObjectProvider<TaskExecutor> taskExecutorProvider) {
        this.messagingTemplate = messagingTemplate;
        this.membershipChecker = membershipChecker;
        this.rateLimiter = rateLimiter;
        this.taskBreakdownService = taskBreakdownService;
        this.projectQueryService = projectQueryService;
        this.properties = properties;
        this.executorService = Executors.newCachedThreadPool();
    }

    /**
     * Entrypoint for processing an incoming chat message asynchronously.
     */
    public void handleChat(UUID userId, ChatMessageRequest request) {
        if (userId == null) {
            log.warn("Unauthenticated chat message received");
            return;
        }
        if (request == null || request.conversationId() == null) {
            log.warn("Invalid chat request received from user={}", userId);
            return;
        }

        UUID conversationId = request.conversationId();
        UUID workspaceId = request.targetWorkspaceId();

        if (workspaceId == null) {
            sendError(userId, conversationId, "workspaceId is required.");
            return;
        }

        String rawMessage = request.message();
        if (rawMessage == null || rawMessage.isBlank()) {
            sendError(userId, conversationId, "Message is required.");
            return;
        }

        if (rawMessage.length() > ChatMessageRequest.MAX_MESSAGE_LENGTH) {
            sendError(userId, conversationId, "Message must not exceed "
                    + ChatMessageRequest.MAX_MESSAGE_LENGTH + " characters.");
            return;
        }

        // Cancel any active stream for the same conversation
        cancelActiveStream(conversationId);

        Future<?> future = executorService.submit(() -> {
            try {
                processMessage(userId, workspaceId, conversationId, rawMessage.strip());
            } catch (AiApiException apiEx) {
                log.warn("AI API error during chat user={} conversationId={} status={} code={}",
                        userId, conversationId, apiEx.status(), apiEx.code());
                sendError(userId, conversationId, apiEx.getMessage());
            } catch (Exception ex) {
                log.error("Unexpected error in chat stream user={} conversationId={}", userId, conversationId, ex);
                sendError(userId, conversationId, "An error occurred while processing your message.");
            } finally {
                activeStreams.remove(conversationId);
            }
        });

        activeStreams.put(conversationId, future);
    }

    /**
     * Cancels an ongoing stream if requested by the client.
     */
    public void cancel(UUID userId, UUID conversationId) {
        if (conversationId == null) {
            return;
        }
        Future<?> future = activeStreams.remove(conversationId);
        if (future != null) {
            future.cancel(true);
            log.info("Cancelled chat stream conversationId={} for user={}", conversationId, userId);
            sendResponse(userId, ChatMessageResponse.end(conversationId, -1));
        }
    }

    private void cancelActiveStream(UUID conversationId) {
        Future<?> existing = activeStreams.remove(conversationId);
        if (existing != null) {
            existing.cancel(true);
        }
    }

    private void processMessage(UUID userId, UUID workspaceId, UUID conversationId, String message) {
        // 1. Membership check first (IDOR prevention)
        membershipChecker.requireMember(userId, workspaceId);

        // 2. Intent routing: Task Breakdown proposal
        if (isTaskBreakdownRequest(message)) {
            TaskBreakdownProposal proposal = taskBreakdownService.propose(userId, workspaceId, message);
            Map<String, Object> metadata = Map.of("proposals", proposal.subtasks());
            sendResponse(userId, ChatMessageResponse.proposal(
                    conversationId, 0, "Dưới đây là gợi ý các task cần làm:", metadata));
            sendResponse(userId, ChatMessageResponse.end(conversationId, 1));
            return;
        }

        // 3. Intent routing: Friendly Greeting
        if (isGreeting(message)) {
            rateLimiter.check(userId, AiFeature.CHAT.tag());
            String greeting = "Xin chào! Tôi là trợ lý AI của DevFlow. "
                    + "Tôi có thể giúp bạn giải đáp thắc mắc về tiến độ dự án, các task trên board, "
                    + "hoặc hỗ trợ gợi ý chia nhỏ task. Bạn cần hỗ trợ gì hôm nay?";
            streamChunks(userId, conversationId, greeting);
            return;
        }

        // 4. Grounded Project Q&A
        ProjectQueryAnswer answer = projectQueryService.answer(userId, workspaceId, message);
        streamChunks(userId, conversationId, answer.answer());
    }

    private void streamChunks(UUID userId, UUID conversationId, String fullText) {
        List<String> chunks = splitIntoChunks(fullText, DEFAULT_CHUNK_SIZE);
        int seq = 0;
        for (; seq < chunks.size(); seq++) {
            if (Thread.currentThread().isInterrupted()) {
                log.debug("Stream aborted by interruption conversationId={}", conversationId);
                return;
            }
            sendResponse(userId, ChatMessageResponse.chunk(conversationId, seq, chunks.get(seq)));

            long delay = properties.getChat().getStreamDelayMs();
            if (delay > 0) {
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        if (!Thread.currentThread().isInterrupted()) {
            sendResponse(userId, ChatMessageResponse.end(conversationId, seq));
        }
    }

    static List<String> splitIntoChunks(String text, int targetSize) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        List<String> chunks = new ArrayList<>();
        String[] words = text.split("(?<=\\s+)");
        StringBuilder current = new StringBuilder();
        for (String word : words) {
            if (current.length() + word.length() > targetSize && current.length() > 0) {
                chunks.add(current.toString());
                current.setLength(0);
            }
            current.append(word);
        }
        if (current.length() > 0) {
            chunks.add(current.toString());
        }
        return chunks.isEmpty() ? List.of(text) : chunks;
    }

    private boolean isTaskBreakdownRequest(String message) {
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("chia task")
                || lower.contains("tạo task")
                || lower.contains("phân rã task")
                || lower.contains("phân rã công việc")
                || lower.contains("gợi ý task")
                || lower.contains("break down")
                || lower.contains("breakdown")
                || lower.contains("subtask");
    }

    private boolean isGreeting(String message) {
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.equals("hi") || lower.equals("hello") || lower.equals("hey")
                || lower.equals("xin chào") || lower.equals("chào") || lower.equals("chào bạn")
                || lower.startsWith("xin chào") || lower.startsWith("chào bot");
    }

    private void sendError(UUID userId, UUID conversationId, String errorMessage) {
        sendResponse(userId, ChatMessageResponse.error(conversationId, errorMessage));
    }

    private void sendResponse(UUID userId, ChatMessageResponse response) {
        messagingTemplate.convertAndSendToUser(userId.toString(), CHAT_DESTINATION, response);
    }

    @Override
    public void destroy() {
        activeStreams.values().forEach(future -> future.cancel(true));
        activeStreams.clear();
        executorService.shutdownNow();
    }
}
