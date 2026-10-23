package io.devflow.ai.internal.llm;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/**
 * Local stand-in for the OpenAI chat completions API: scripted status, body and delay; records every
 * request. Tests never reach the real provider.
 */
final class OpenAiStubServer {

    static final String COMPLETION_TEXT = "Stubbed answer from the fake provider.";
    static final String SUCCESS_BODY = """
            {"id":"chatcmpl-test","object":"chat.completion","created":1700000000,"model":"gpt-test-model",
             "choices":[{"index":0,"message":{"role":"assistant","content":"%s"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":42,"completion_tokens":7,"total_tokens":49}}
            """.formatted(COMPLETION_TEXT);

    record RecordedRequest(String method, String path, String authorization, String body) {
    }

    private final HttpServer server;
    private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String body = SUCCESS_BODY;
    private volatile long delayMs;

    OpenAiStubServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", this::handle);
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void respond(int status, String body, long delayMs) {
        this.status = status;
        this.body = body;
        this.delayMs = delayMs;
    }

    void reset() {
        requests.clear();
        respond(200, SUCCESS_BODY, 0);
    }

    List<RecordedRequest> requests() {
        return requests;
    }

    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(new RecordedRequest(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders().getFirst("Authorization"), requestBody));
        try {
            if (delayMs > 0) {
                Thread.sleep(delayMs);
            }
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // Client gave up (read timeout) before the delayed response was written.
        } finally {
            exchange.close();
        }
    }
}
