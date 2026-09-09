package io.devflow.gitci.internal;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inbound webhooks from Git/CI providers — these enter through the REST API layer like
 * any other client (ARCHITECTURE.md Sections 2 and 5). All endpoints are stubs.
 *
 * <p>TODO(PRODUCT_SPEC Section 5.2a): verify provider signatures, parse push/PR/pipeline
 * payloads, and delegate to {@link GitCiService} which publishes the corresponding events
 * (ARCHITECTURE.md Section 4).
 */
@RestController
@RequestMapping("/api/v1/gitci/webhooks")
public class GitWebhookController {

    @PostMapping("/git")
    public ResponseEntity<Map<String, String>> gitWebhook() {
        // TODO(PRODUCT_SPEC 5.2a): handle push/PR events; link commits and PRs to tasks.
        return todo("gitWebhook");
    }

    @PostMapping("/ci")
    public ResponseEntity<Map<String, String>> ciWebhook() {
        // TODO(PRODUCT_SPEC 5.2a): handle pipeline status events; on failure, ingest the
        //   raw log and publish CiFailureDetectedEvent for the AI service to summarize.
        return todo("ciWebhook");
    }

    private ResponseEntity<Map<String, String>> todo(String endpoint) {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                .body(Map.of(
                        "todo", endpoint + " — not implemented yet",
                        "spec", "PRODUCT_SPEC.md Section 5.2a"));
    }
}
