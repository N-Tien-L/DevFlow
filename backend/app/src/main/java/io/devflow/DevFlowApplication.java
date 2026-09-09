package io.devflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * DevFlow backend — one deployable service, internally a modular monolith
 * (ARCHITECTURE.md Section 1). The io.devflow root package scans all module packages
 * (auth, board, gitci, ai, notification, common) in one application context.
 */
@SpringBootApplication
public class DevFlowApplication {

    public static void main(String[] args) {
        SpringApplication.run(DevFlowApplication.class, args);
    }
}
