import type { BoardColumn } from '../types/board';

export function cloneColumns(columns: BoardColumn[]): BoardColumn[] {
  return columns.map((column) => ({ ...column, tasks: [...column.tasks] }));
}

export function locateTask(columns: BoardColumn[], taskId: string) {
  for (const column of columns) {
    const index = column.tasks.findIndex((task) => task.id === taskId);
    if (index >= 0) return { columnId: column.id, index };
  }
  return null;
}

export function moveTaskBefore(
  columns: BoardColumn[],
  taskId: string,
  destinationColumnId: string,
  beforeTaskId?: string,
): BoardColumn[] {
  const source = locateTask(columns, taskId);
  if (beforeTaskId === taskId) return columns;
  const destination = columns.find((column) => column.id === destinationColumnId);
  if (!source || !destination || !destination.complete) return columns;
  if (source.columnId !== destinationColumnId && !columns.find((column) => column.id === source.columnId)?.complete) return columns;

  const next = cloneColumns(columns);
  const sourceColumn = next.find((column) => column.id === source.columnId);
  const targetColumn = next.find((column) => column.id === destinationColumnId);
  if (!sourceColumn || !targetColumn) return columns;
  const [task] = sourceColumn.tasks.splice(source.index, 1);
  if (!task) return columns;

  const requestedIndex = beforeTaskId ? targetColumn.tasks.findIndex((candidate) => candidate.id === beforeTaskId) : targetColumn.tasks.length;
  const index = requestedIndex < 0 ? targetColumn.tasks.length : requestedIndex;
  if (source.columnId === destinationColumnId && index === source.index) return columns;
  targetColumn.tasks.splice(index, 0, { ...task, columnId: destinationColumnId, statusCategory: targetColumn.statusCategory });
  sourceColumn.tasks = sourceColumn.tasks.map((item, position) => ({ ...item, position }));
  targetColumn.tasks = targetColumn.tasks.map((item, position) => ({ ...item, position }));
  return next;
}

export function reorderColumnById(columns: BoardColumn[], columnId: string, targetColumnId: string): BoardColumn[] {
  const from = columns.findIndex((column) => column.id === columnId);
  const to = columns.findIndex((column) => column.id === targetColumnId);
  if (from < 0 || to < 0 || from === to) return columns;
  const next = [...columns];
  const [column] = next.splice(from, 1);
  if (!column) return columns;
  next.splice(to, 0, column);
  return next.map((item, position) => ({ ...item, position }));
}

export function applyColumnOrder(columns: BoardColumn[], orderedColumns: { id: string; position: number }[]): BoardColumn[] {
  const positions = new Map(orderedColumns.map((column) => [column.id, column.position]));
  return [...columns]
    .map((column) => ({ ...column, position: positions.get(column.id) ?? column.position }))
    .sort((left, right) => left.position - right.position);
}
