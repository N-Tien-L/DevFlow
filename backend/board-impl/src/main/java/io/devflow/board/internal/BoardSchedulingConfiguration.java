package io.devflow.board.internal;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Enables Board's scheduled jobs (account-deletion cleanup retries) without relying on another module's config. */
@Configuration
@EnableScheduling
class BoardSchedulingConfiguration {
}
