import { useDroppable } from '@dnd-kit/react';
import { useSortable } from '@dnd-kit/react/sortable';
import clsx from 'clsx';
import type { BoardColumn, TaskListItem, WorkspaceMember } from '../../types/board';
import type { TaskGitSummary } from '../../types/gitci';
import {
  AlertCircleIcon,
  FilterIcon,
  GripVerticalIcon,
  KanbanIcon,
  RefreshIcon,
} from '../common/Icons';
import { LoadingSpinner, STATUS_CATEGORY_META } from '../common/UiPrimitives';
import { TaskCard } from './TaskCard';

export interface BoardColumnViewProps {
  column: BoardColumn;
  visibleTasks: TaskListItem[];
  availableColumns: BoardColumn[];
  membersById: ReadonlyMap<string, WorkspaceMember>;
  gitSummariesByTaskId?: ReadonlyMap<string, TaskGitSummary>;
  currentUserId?: string;
  index: number;
  activeFilters: boolean;
  canReorder: boolean;
  canMoveCards: boolean;
  onMoveTask(taskId: string, destinationColumnId: string): void;
  onOpenTask(taskId: string, trigger?: HTMLElement | null): void;
  onClearFilters(): void;
  onRetry(): void;
  onLoadMore(): void;
}

export function BoardColumnView({
  column,
  visibleTasks,
  availableColumns,
  membersById,
  gitSummariesByTaskId,
  currentUserId,
  index,
  activeFilters,
  canReorder,
  canMoveCards,
  onMoveTask,
  onOpenTask,
  onClearFilters,
  onRetry,
  onLoadMore,
}: BoardColumnViewProps) {
  const accepts = column.complete ? ['column', 'task'] : ['column'];
  const { ref, handleRef, isDragging, isDropTarget } = useSortable({
    id: `column:${column.id}`,
    index,
    type: 'column',
    accept: accepts,
    group: 'board-columns',
    disabled: !canReorder,
    data: { kind: 'column', columnId: column.id },
  });

  const meta = STATUS_CATEGORY_META[column.statusCategory] ?? STATUS_CATEGORY_META.TODO;
  const remaining = Math.max(0, column.totalElements - column.tasks.length);
  const countLabel = activeFilters
    ? `${visibleTasks.length}/${column.tasks.length}`
    : column.complete
      ? String(column.totalElements)
      : `${column.tasks.length}/${column.totalElements}`;

  return (
    <section
      ref={ref}
      className={clsx(
        'flex w-[min(86vw,324px)] shrink-0 snap-start flex-col overflow-hidden rounded-2xl border bg-[#eaedf4]/80 transition sm:w-[316px] xl:w-[328px]',
        isDragging && 'opacity-50',
        isDropTarget
          ? 'border-indigo-400 ring-2 ring-indigo-500/20'
          : 'border-slate-200/90',
      )}
      aria-label={`Cột ${column.name}`}
    >
      {/* Status accent hairline */}
      <div className={clsx('h-1 w-full', meta.bar)} aria-hidden="true" />

      <header className="flex items-center gap-2 border-b border-slate-200/75 bg-white/55 px-3 py-3">
        <button
          ref={handleRef}
          type="button"
          disabled={!canReorder}
          className="grid size-8 shrink-0 place-items-center rounded-lg text-slate-400 transition hover:bg-white hover:text-indigo-600 focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500 disabled:cursor-default disabled:opacity-40"
          aria-label={`Sắp xếp cột ${column.name}`}
          title={canReorder ? 'Kéo để sắp xếp cột' : 'Không thể sắp xếp cột lúc này'}
        >
          <GripVerticalIcon className="size-4" />
        </button>

        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2">
            <span className={clsx('size-2 shrink-0 rounded-full', meta.dot)} aria-hidden="true" />
            <h2 className="truncate text-sm font-bold tracking-tight text-slate-900">
              {column.name}
            </h2>
          </div>
          {!column.complete && !column.loading && !column.error && (
            <p className="mt-0.5 text-[11px] font-medium text-amber-800">
              Đã tải {column.tasks.length}/{column.totalElements} · tạm khóa kéo thả
            </p>
          )}
        </div>

        <span
          className="tabular-nums shrink-0 rounded-full bg-white px-2.5 py-0.5 text-xs font-bold text-slate-700 ring-1 ring-inset ring-slate-200/90"
          title={activeFilters ? `${visibleTasks.length} khớp lọc trên ${column.tasks.length} công việc đã tải` : `${column.totalElements} công việc`}
        >
          {countLabel}
        </span>
      </header>

      <div
        className="devflow-scroll max-h-[calc(100dvh-285px)] min-h-36 space-y-2.5 overflow-y-auto p-2.5 sm:max-h-[calc(100dvh-250px)]"
        aria-label={`Danh sách công việc ${column.name}`}
      >
        {column.loading && column.tasks.length === 0 && (
          <div className="space-y-2.5" aria-label="Đang tải công việc" role="status">
            {[0, 1].map((skeleton) => (
              <div
                key={skeleton}
                className="rounded-xl border border-slate-200/80 bg-white p-3.5 shadow-2xs"
                aria-hidden="true"
              >
                <div className="flex items-center gap-2">
                  <div className="h-4 w-12 animate-pulse rounded bg-slate-200/80 motion-reduce:animate-none" />
                  <div className="h-4 w-16 animate-pulse rounded bg-slate-100 motion-reduce:animate-none" />
                </div>
                <div className="mt-3 h-4 w-5/6 animate-pulse rounded bg-slate-200/75 motion-reduce:animate-none" />
                <div className="mt-1.5 h-4 w-2/3 animate-pulse rounded bg-slate-100 motion-reduce:animate-none" />
                <div className="mt-4 flex items-center justify-between">
                  <div className="h-5 w-24 animate-pulse rounded-full bg-slate-100 motion-reduce:animate-none" />
                  <div className="h-4 w-14 animate-pulse rounded bg-slate-100 motion-reduce:animate-none" />
                </div>
              </div>
            ))}
          </div>
        )}

        {column.error && (
          <div className="rounded-xl border border-rose-200 bg-rose-50/95 p-3.5 text-xs text-rose-800" role="alert">
            <div className="flex items-start gap-2">
              <AlertCircleIcon className="mt-0.5 size-4 shrink-0 text-rose-600" />
              <div className="min-w-0 flex-1">
                <p className="font-medium leading-5">{column.error}</p>
                <button
                  type="button"
                  className="mt-2 inline-flex min-h-8 items-center gap-1.5 rounded-lg bg-white px-2.5 py-1 text-xs font-semibold text-rose-700 ring-1 ring-inset ring-rose-200 transition hover:bg-rose-100/60 focus:outline-none focus-visible:ring-2 focus-visible:ring-rose-500"
                  onClick={onRetry}
                >
                  <RefreshIcon className="size-3.5" />
                  <span>Thử tải lại</span>
                </button>
              </div>
            </div>
          </div>
        )}

        {!column.loading && !column.error && visibleTasks.length === 0 && (
          <EmptyTaskTarget
            column={column}
            canMoveCards={canMoveCards}
            activeFilters={activeFilters}
            onClearFilters={onClearFilters}
          />
        )}

        {visibleTasks.map((task, taskIndex) => (
          <TaskCard
            key={task.id}
            task={task}
            column={column}
            columns={availableColumns}
            member={task.assigneeId ? membersById.get(task.assigneeId) : undefined}
            gitSummary={gitSummariesByTaskId?.get(task.id)}
            isAssignedToMe={Boolean(currentUserId && task.assigneeId === currentUserId)}
            index={taskIndex}
            disabled={!canMoveCards}
            onMoveTask={onMoveTask}
            onOpenTask={onOpenTask}
          />
        ))}

        {!column.error && remaining > 0 && (
          <button
            type="button"
            disabled={column.loading}
            onClick={onLoadMore}
            className={clsx(
              'inline-flex min-h-10 w-full items-center justify-center gap-2 rounded-xl border border-dashed px-3 py-2 text-xs font-semibold transition focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500 disabled:cursor-not-allowed disabled:opacity-60',
              column.complete
                ? 'border-slate-300 bg-white/75 text-slate-700 hover:border-indigo-300 hover:bg-white hover:text-indigo-700'
                : 'border-amber-300 bg-amber-50/90 text-amber-900 hover:bg-amber-100/80',
            )}
          >
            {column.loading ? (
              <>
                <LoadingSpinner className="size-3.5" />
                <span>Đang tải thêm…</span>
              </>
            ) : (
              <span>Tải tiếp {remaining} công việc</span>
            )}
          </button>
        )}
      </div>
    </section>
  );
}

function EmptyTaskTarget({
  column,
  canMoveCards,
  activeFilters,
  onClearFilters,
}: {
  column: BoardColumn;
  canMoveCards: boolean;
  activeFilters: boolean;
  onClearFilters(): void;
}) {
  const { ref, isDropTarget } = useDroppable({
    id: `empty:${column.id}`,
    type: 'task',
    accept: 'task',
    disabled: !canMoveCards,
    data: { kind: 'column', columnId: column.id },
  });

  const filteredOutExisting = activeFilters && column.tasks.length > 0;

  return (
    <div
      ref={ref}
      className={clsx(
        'flex min-h-28 flex-col items-center justify-center rounded-xl border border-dashed px-4 py-5 text-center transition',
        isDropTarget
          ? 'border-indigo-400 bg-indigo-50/90 text-indigo-800'
          : filteredOutExisting
            ? 'border-indigo-200/90 bg-white/80 text-slate-600'
            : 'border-slate-300/90 bg-white/60 text-slate-600',
      )}
    >
      <div
        className={clsx(
          'mb-2 grid size-8 place-items-center rounded-lg',
          filteredOutExisting ? 'bg-indigo-50 text-indigo-600' : 'bg-slate-100 text-slate-500',
        )}
      >
        {filteredOutExisting ? <FilterIcon className="size-4" /> : <KanbanIcon className="size-4" />}
      </div>
      {filteredOutExisting ? (
        <>
          <p className="text-xs font-semibold text-slate-800">Không khớp bộ lọc hiện tại</p>
          <p className="mt-0.5 text-[11px] text-slate-500">
            Đang ẩn {column.tasks.length} công việc trong cột này
          </p>
          <button
            type="button"
            onClick={onClearFilters}
            className="mt-2.5 inline-flex min-h-8 items-center rounded-lg bg-indigo-50 px-2.5 py-1 text-xs font-semibold text-indigo-700 transition hover:bg-indigo-100 focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500"
          >
            Xóa bộ lọc
          </button>
        </>
      ) : activeFilters ? (
        <>
          <p className="text-xs font-semibold text-slate-700">Cột chưa có công việc</p>
          <p className="mt-0.5 text-[11px] text-slate-500">Không có thẻ nào trong {column.name}</p>
        </>
      ) : (
        <>
          <p className="text-xs font-semibold text-slate-700">Chưa có công việc</p>
          <p className="mt-0.5 text-[11px] text-slate-500">
            {canMoveCards ? `Kéo thả thẻ vào cột ${column.name}` : 'Các công việc mới sẽ hiển thị tại đây'}
          </p>
        </>
      )}
    </div>
  );
}
