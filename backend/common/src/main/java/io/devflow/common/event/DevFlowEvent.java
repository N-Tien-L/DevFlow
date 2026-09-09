package io.devflow.common.event;

import java.time.Instant;

/**
 * Base type for every event that crosses a module boundary on the in-process event bus
 * (Spring {@code ApplicationEventPublisher} / {@code @EventListener}).
 *
 * <p>The set of events and their payloads is the inter-module communication contract —
 * ARCHITECTURE.md Section 4 is the source of truth. To add a new cross-module interaction,
 * add a new event here following the {@code domain.event_past_tense} naming pattern
 * (ARCHITECTURE.md Section 10) instead of importing another module's internals.
 */
public interface DevFlowEvent {

    /** Wire name of the event, e.g. {@code "task.created"}. See {@link EventTypes}. */
    String eventType();

    /** When the event occurred (set at publication time). */
    Instant occurredAt();
}
