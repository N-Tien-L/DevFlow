package io.devflow.board.api;

/** Board-scoped invalidation actions emitted after a committed Board mutation. */
public enum BoardMutationType {
    CARD_CREATED,
    CARD_UPDATED,
    CARD_DELETED,
    CARD_MOVED,
    CARD_REORDERED,
    COLUMN_CREATED,
    COLUMN_UPDATED,
    COLUMN_DELETED,
    COLUMN_REORDERED,
    BOARD_UPDATED,
    BOARD_DELETED
}
