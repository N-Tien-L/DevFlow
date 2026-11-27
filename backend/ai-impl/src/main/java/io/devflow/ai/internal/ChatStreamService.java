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
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

/**
 * Per-user streaming chat sessions over STOMP (T-034).
 *
 * <p>Checks workspace membership before routing a message to {@link TaskBreakdownService} (task proposal
 * intents) or {@link ProjectQueryService} (grounded project Q&amp;A); both apply the per-user AI rate limit.
 * Every frame goes only to the caller's own {@code /user/queue/chat}.
 *
 * <p>The answer is produced and validated in full (T-033 output checks) before it is split into chunks, so
 * no unvalidated model text reaches the client.
 *
 * <p>Streams are owned by {@code (userId, conversationId)}: a user can cancel or replace only their own
 * streams. A user runs at most {@code devflow.ai.chat.max-streams-per-user} streams at once and the instance
 * at most {@code devflow.ai.chat.max-concurrent-streams} (bounded worker pool without a queue).
 */
@Service
public class ChatStreamService implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ChatStreamService.class);
    private static final String CHAT_DESTINATION = "/queue/chat";
    private static final int DEFAULT_CHUNK_SIZE = 25;
    static final String PROPOSAL_INTRO = "Dưới đây là gợi ý các task cần làm:";
    static final String GREETING = "Xin chào! Tôi là trợ lý AI của DevFlow. "
            + "Tôi có thể giúp bạn giải đáp thắc mắc về tiến độ dự án, các task trên board, "
            + "hoặc hỗ trợ gợi ý chia nhỏ task. Bạn cần hỗ trợ gì hôm nay?";

    private final SimpMessagingTemplate messagingTemplate;
    private final WorkspaceMembershipChecker membershipChecker;
    private final AiRateLimiter rateLimiter;
    private final TaskBreakdownService taskBreakdownService;
    private final ProjectQueryService projectQueryService;
    private final AiProperties properties;
    private final ThreadPoolExecutor executor;
    private final ConcurrentHashMap<StreamKey, ActiveStream> activeStreams = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> streamsPerUser = new ConcurrentHashMap<>();

    public ChatStreamService(
            SimpMessagingTemplate messagingTemplate,
            WorkspaceMembershipChecker membershipChecker,
            AiRateLimiter rateLimiter,
            TaskBreakdownService taskBreakdownService,
            ProjectQueryService projectQueryService,
            AiProperties properties) {
        this.messagingTemplate = messagingTemplate;
        this.membershipChecker = membershipChecker;
        this.rateLimiter = rateLimiter;
        this.taskBreakdownService = taskBreakdownService;
        this.projectQueryService = projectQueryService;
        this.properties = properties;
        AtomicInteger threadIds = new AtomicInteger();
        // No queue: a stream either starts on a worker right away or is refused; it never waits unbounded.
        this.executor = new ThreadPoolExecutor(0, properties.getChat().getMaxConcurrentStreams(),
                60, TimeUnit.SECONDS, new SynchronousQueue<>(), runnable -> {
                    Thread thread = new Thread(runnable, "ai-chat-" + threadIds.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                });
    }

    /**
     * Validates the message and starts streaming its answer on a worker thread.
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
            sendError(userId, conversationId, "WORKSPACE_REQUIRED", "workspaceId is required.");
            return;
        }

        String rawMessage = request.message();
        if (rawMessage == null || rawMessage.isBlank()) {
            sendError(userId, conversationId, "AI_MESSAGE_REQUIRED", "Message is required.");
            return;
        }

        if (rawMessage.length() > ChatMessageRequest.MAX_MESSAGE_LENGTH) {
            sendError(userId, conversationId, "AI_MESSAGE_TOO_LONG", "Message must not exceed "
                    + ChatMessageRequest.MAX_MESSAGE_LENGTH + " characters.");
            return;
        }

        StreamKey key = new StreamKey(userId, conversationId);
        // A new message in the same conversation silently replaces the caller's own running stream.
        ActiveStream previous = activeStreams.remove(key);
        if (previous != null) {
            previous.abort();
        }

        if (!tryAcquire(userId)) {
            log.info("Chat stream refused: per-user limit reached user={}", userId);
            sendError(userId, conversationId, "AI_CHAT_STREAMS_LIMIT", "You already have "
                    + properties.getChat().getMaxStreamsPerUser()
                    + " answers in progress. Wait for one to finish or stop it.");
            return;
        }

        String message = rawMessage.strip();
        AtomicReference<ActiveStream> self = new AtomicReference<>();
        ActiveStream stream = new ActiveStream(key, () -> processMessage(self.get(), workspaceId, message));
        self.set(stream);
        ActiveStream displaced = activeStreams.put(key, stream);
        if (displaced != null) {
            // Two frames for the same conversation raced past the removal above: keep only the newest.
            displaced.abort();
        }
        try {
            executor.execute(stream);
        } catch (RejectedExecutionException busy) {
            stream.abort();
            log.warn("Chat stream refused: worker pool saturated user={}", userId);
            sendError(userId, conversationId, "AI_CHAT_BUSY",
                    "The AI assistant is busy. Please try again in a moment.");
        }
    }

    /**
     * Cancels the caller's own stream for {@code conversationId}; other users' streams are never touched.
     */
    public void cancel(UUID userId, UUID conversationId) {
        if (userId == null || conversationId == null) {
            return;
        }
        ActiveStream stream = activeStreams.remove(new StreamKey(userId, conversationId));
        if (stream != null) {
            stream.abort();
            log.info("Cancelled chat stream conversationId={} user={}", conversationId, userId);
            sendResponse(userId, ChatMessageResponse.end(conversationId, -1));
        }
    }

    /** Streams currently holding one of the user's slots; for tests and diagnostics. */
    int activeStreamCount(UUID userId) {
        return streamsPerUser.getOrDefault(userId, 0);
    }

    private void processMessage(ActiveStream stream, UUID workspaceId, String message) {
        UUID userId = stream.key.userId();
        UUID conversationId = stream.key.conversationId();
        try {
            // 1. Membership first (IDOR guard), so non-members get no greeting or proposal either.
            membershipChecker.requireMember(userId, workspaceId);

            // 2. Task breakdown proposal
            if (isTaskBreakdownRequest(message)) {
                TaskBreakdownProposal proposal = taskBreakdownService.propose(userId, workspaceId, message);
                Map<String, Object> metadata = Map.of("proposals", proposal.subtasks());
                stream.emit(ChatMessageResponse.proposal(conversationId, 0, PROPOSAL_INTRO, metadata));
                stream.emit(ChatMessageResponse.end(conversationId, 1));
                return;
            }

            // 3. Greeting: no model call, but it still costs a rate-limit token.
            if (isGreeting(message)) {
                rateLimiter.check(userId, AiFeature.CHAT.tag());
                streamChunks(stream, GREETING);
                return;
            }

            // 4. Grounded project Q&A
            ProjectQueryAnswer answer = projectQueryService.answer(userId, workspaceId, message);
            streamChunks(stream, answer.answer());
        } catch (AiApiException apiEx) {
            log.warn("AI API error during chat user={} conversationId={} status={} code={}",
                    userId, conversationId, apiEx.status(), apiEx.code());
            stream.emit(ChatMessageResponse.error(conversationId, apiEx.code(), apiEx.getMessage()));
        } catch (RuntimeException ex) {
            if (stream.isCancelled()) {
                log.debug("Chat stream ended after cancel conversationId={}", conversationId);
                return;
            }
            log.error("Unexpected error in chat stream user={} conversationId={}", userId, conversationId, ex);
            stream.emit(ChatMessageResponse.error(conversationId, "AI_CHAT_FAILED",
                    "An error occurred while processing your message."));
        }
    }

    private void streamChunks(ActiveStream stream, String fullText) {
        UUID conversationId = stream.key.conversationId();
        List<String> chunks = splitIntoChunks(fullText, DEFAULT_CHUNK_SIZE);
        long delay = properties.getChat().getStreamDelayMs();
        for (int seq = 0; seq < chunks.size(); seq++) {
            if (!stream.emit(ChatMessageResponse.chunk(conversationId, seq, chunks.get(seq)))) {
                log.debug("Stream aborted conversationId={}", conversationId);
                return;
            }
            if (delay > 0) {
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        stream.emit(ChatMessageResponse.end(conversationId, chunks.size()));
    }

    static List<String> splitIntoChunks(String text, int targetSize) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        List<String> chunks = new ArrayList<>();
        String[] words = text.split("(?<=\\s)");
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
        return chunks;
    }

    private boolean isTaskBreakdownRequest(String message) {
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("chia task")
                || lower.contains("tạo task")
                || lower.contains("phân rã task")
                || lower.contains("phân rã công việc")
                || lower.contains("gợi ý task")
                || lower.contains("gợi ý chia")
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

    private boolean tryAcquire(UUID userId) {
        int limit = properties.getChat().getMaxStreamsPerUser();
        boolean[] acquired = {false};
        streamsPerUser.compute(userId, (id, count) -> {
            int current = count == null ? 0 : count;
            if (current >= limit) {
                return count;
            }
            acquired[0] = true;
            return current + 1;
        });
        return acquired[0];
    }

    private void release(UUID userId) {
        streamsPerUser.computeIfPresent(userId, (id, count) -> count <= 1 ? null : count - 1);
    }

    private void sendError(UUID userId, UUID conversationId, String code, String errorMessage) {
        sendResponse(userId, ChatMessageResponse.error(conversationId, code, errorMessage));
    }

    private void sendResponse(UUID userId, ChatMessageResponse response) {
        messagingTemplate.convertAndSendToUser(userId.toString(), CHAT_DESTINATION, response);
    }

    @Override
    public void destroy() {
        activeStreams.values().forEach(ActiveStream::abort);
        activeStreams.clear();
        executor.shutdownNow();
    }

    private record StreamKey(UUID userId, UUID conversationId) {
    }

    /**
     * One running answer. {@link #done()} runs exactly once however the stream ends (completed, failed,
     * aborted while running, or aborted before it started), so the user's slot and the registry entry are
     * always released.
     */
    private final class ActiveStream extends FutureTask<Void> {

        private final StreamKey key;
        private boolean aborted;

        private ActiveStream(StreamKey key, Runnable work) {
            super(work, null);
            this.key = key;
        }

        /** Sends the frame unless the stream was aborted; an aborted stream never emits again. */
        synchronized boolean emit(ChatMessageResponse response) {
            if (aborted) {
                return false;
            }
            sendResponse(key.userId(), response);
            return true;
        }

        /** Stops emitting (waiting for an in-flight frame), then interrupts the worker. */
        void abort() {
            synchronized (this) {
                aborted = true;
            }
            cancel(true);
        }

        @Override
        protected void done() {
            activeStreams.remove(key, this);
            release(key.userId());
        }
    }
}
