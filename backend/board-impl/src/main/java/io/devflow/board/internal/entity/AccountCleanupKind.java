package io.devflow.board.internal.entity;

/** What a Board account-deletion cleanup job erases (T-009). */
public enum AccountCleanupKind {
    /** Unassign a deleted user's tasks and anonymise their comments and audit rows. */
    USER,
    /** Delete a deleted workspace's boards and audit rows. */
    WORKSPACE
}
