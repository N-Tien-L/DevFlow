package io.devflow.auth.internal;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Per-module health check. Aggregated application health lives at /actuator/health. */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthHealthController {

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("module", "auth", "status", "UP");
    }
}
