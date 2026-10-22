package io.devflow.board.api;

/** Part of a board view that a client must refetch after receiving a mutation. */
public enum BoardRefreshTarget {
    BOARD,
    COLUMNS,
    TASK_LISTS,
    TASK_DETAIL
}
