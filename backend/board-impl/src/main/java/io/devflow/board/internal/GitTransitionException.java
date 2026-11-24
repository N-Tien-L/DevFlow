package io.devflow.board.internal;

/** Finite, non-sensitive reason sent back to the existing GitCI retry worker. */
final class GitTransitionException extends RuntimeException {
    private final String reason;
    GitTransitionException(String reason) { super(reason); this.reason = reason; }
    String reason() { return reason; }
}
