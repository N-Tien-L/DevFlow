export const BOARD_MUTATION_TYPES = [
  'CARD_CREATED', 'CARD_UPDATED', 'CARD_DELETED', 'CARD_MOVED', 'CARD_REORDERED',
  'COLUMN_CREATED', 'COLUMN_UPDATED', 'COLUMN_DELETED', 'COLUMN_REORDERED',
  'BOARD_UPDATED', 'BOARD_DELETED',
] as const;

export const BOARD_REFRESH_TARGETS = ['BOARD', 'COLUMNS', 'TASK_LISTS', 'TASK_DETAIL'] as const;

export type BoardMutationType = typeof BOARD_MUTATION_TYPES[number];
export type BoardRefreshTarget = typeof BOARD_REFRESH_TARGETS[number];

export interface BoardMutationData {
  taskId: string | null;
  columnId: string | null;
  sourceColumnId: string | null;
  destinationColumnId: string | null;
  fromPosition: number | null;
  toPosition: number | null;
  affectedColumnIds: string[];
  changedFields: string[];
  refresh: BoardRefreshTarget[];
}

export interface BoardMutationMessage {
  schemaVersion: number;
  eventId: string;
  type: BoardMutationType;
  boardId: string;
  occurredAt: string;
  correlationId: string;
  actorId: string | null;
  data: BoardMutationData;
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const UUID_OR_NULL_KEYS = ['taskId', 'columnId', 'sourceColumnId', 'destinationColumnId'] as const;
const POSITION_KEYS = ['fromPosition', 'toPosition'] as const;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isUuidOrNull(value: unknown): value is string | null {
  return value === null || typeof value === 'string' && UUID.test(value);
}

/** Validate STOMP's untrusted JSON body against the board-api v1 contract. */
export function parseBoardMutationMessage(body: string, activeBoardId: string): BoardMutationMessage | null {
  if (new TextEncoder().encode(body).byteLength > 65_536) return null;

  let value: unknown;
  try {
    value = JSON.parse(body) as unknown;
  } catch {
    return null;
  }
  if (!isRecord(value) || value.schemaVersion !== 1 || value.boardId !== activeBoardId ||
    typeof value.eventId !== 'string' || !UUID.test(value.eventId) ||
    typeof value.correlationId !== 'string' || !UUID.test(value.correlationId) ||
    !(typeof value.actorId === 'string' && UUID.test(value.actorId) || value.actorId === null) ||
    typeof value.occurredAt !== 'string' || !Number.isFinite(Date.parse(value.occurredAt)) ||
    typeof value.type !== 'string' || !BOARD_MUTATION_TYPES.includes(value.type as BoardMutationType) ||
    !isRecord(value.data)) return null;

  const data = value.data;
  if (UUID_OR_NULL_KEYS.some((key) => !isUuidOrNull(data[key])) ||
    POSITION_KEYS.some((key) => data[key] !== null && (!Number.isInteger(data[key]) || (data[key] as number) < 0)) ||
    !Array.isArray(data.affectedColumnIds) || data.affectedColumnIds.length > 100 ||
    !data.affectedColumnIds.every((id) => typeof id === 'string' && UUID.test(id)) ||
    !Array.isArray(data.changedFields) || data.changedFields.length > 40 ||
    !data.changedFields.every((field) => typeof field === 'string' && /^[A-Za-z][A-Za-z0-9_]{0,39}$/.test(field)) ||
    !Array.isArray(data.refresh) || data.refresh.length > BOARD_REFRESH_TARGETS.length ||
    !data.refresh.every((target) => typeof target === 'string' && BOARD_REFRESH_TARGETS.includes(target as BoardRefreshTarget))) return null;

  return {
    schemaVersion: 1,
    eventId: value.eventId,
    type: value.type as BoardMutationType,
    boardId: value.boardId as string,
    occurredAt: value.occurredAt,
    correlationId: value.correlationId,
    actorId: value.actorId as string | null,
    data: {
      taskId: data.taskId as string | null,
      columnId: data.columnId as string | null,
      sourceColumnId: data.sourceColumnId as string | null,
      destinationColumnId: data.destinationColumnId as string | null,
      fromPosition: data.fromPosition as number | null,
      toPosition: data.toPosition as number | null,
      affectedColumnIds: [...data.affectedColumnIds] as string[],
      changedFields: [...data.changedFields] as string[],
      refresh: [...new Set(data.refresh)] as BoardRefreshTarget[],
    },
  };
}
