import { useRef, useState } from 'react';
import { useDroppable } from '@dnd-kit/react';
import { useSortable } from '@dnd-kit/react/sortable';
import clsx from 'clsx';
import type { BoardColumn, TaskListItem, WorkspaceMember } from '../../types/board';
import {
  AlertCircleIcon,
  ArrowRightLeftIcon,
  CalendarIcon,
  FilterIcon,
  GripVerticalIcon,
  KanbanIcon,
  RefreshIcon,
  UserIcon,
  XIcon,
} from '../common/Icons';
import {
  Avatar,
  LoadingSpinner,
  PriorityBadge,
  STATUS_CATEGORY_META,
} from '../common/UiPrimitives';

export interface BoardColumnViewProps {
  column: BoardColumn;
  visibleTasks: TaskListItem[];
  availableColumns: BoardColumn[];
  membersById: ReadonlyMap<string, WorkspaceMember>;
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

function TaskCard({
  task,
  column,
  columns,
  member,
  isAssignedToMe,
  index,
  disabled,
  onMoveTask,
  onOpenTask,
}: {
  task: TaskListItem;
  column: BoardColumn;
  columns: BoardColumn[];
  member?: WorkspaceMember;
  isAssignedToMe: boolean;
  index: number;
  disabled: boolean;
  onMoveTask(taskId: string, destinationColumnId: string): void;
  onOpenTask(taskId: string, trigger?: HTMLElement | null): void;
}) {
  const [showMoveMenu, setShowMoveMenu] = useState(false);
  const moveButtonRef = useRef<HTMLButtonElement>(null);

  const { ref, handleRef, isDragging, isDropTarget } = useSortable({
    id: `task:${task.id}`,
    index,
    type: 'task',
    accept: 'task',
    group: 'board-tasks',
    disabled,
    data: { kind: 'task', taskId: task.id, columnId: column.id },
  });

  const dueDate = task.dueDate ? new Date(task.dueDate) : null;
  const isDone = task.statusCategory === 'DONE' || column.statusCategory === 'DONE';
  const now = Date.now();
  const overdue = Boolean(dueDate && !isDone && dueDate.getTime() < now);
  const dueSoon = Boolean(dueDate && !isDone && !overdue && dueDate.getTime() - now <= 48 * 3600_000);

  const movableTargets = columns.filter((item) => item.id !== column.id && item.complete);
  const canQuickMove = !disabled && movableTargets.length > 0;

  const assigneeDisplay = member?.fullName
    ? isAssignedToMe
      ? `${member.fullName} (Tôi)`
      : member.fullName
    : isAssignedToMe
      ? 'Tôi'
      : task.assigneeId
        ? `#${task.assigneeId.slice(0, 8)}`
        : null;

  const closeMoveMenu = (restoreFocus = false) => {
    setShowMoveMenu(false);
    if (restoreFocus) {
      requestAnimationFrame(() => moveButtonRef.current?.focus());
    }
  };

  const priorityStripe =
    task.priority === 'URGENT'
      ? 'border-l-[3px] border-l-rose-500'
      : task.priority === 'HIGH'
        ? 'border-l-[3px] border-l-amber-500'
        : '';

  return (
    <article
      id={`task-drop-${task.id}`}
      ref={ref}
      className={clsx(
        'group rounded-xl border bg-white p-3.5 shadow-[0_1px_2px_rgba(15,23,42,0.04)] transition duration-150 hover:-translate-y-0.5 hover:border-indigo-300/90 hover:shadow-[0_8px_20px_-10px_rgba(79,70,229,0.22)] motion-reduce:hover:translate-y-0',
        priorityStripe,
        isDragging ? 'opacity-35 shadow-lg' : '',
        isDropTarget ? 'border-indigo-500 ring-2 ring-indigo-500/20' : 'border-slate-200/90',
      )}
    >
      {/* Row 1: Task Key + Priority + Drag Handle */}
      <div className="flex items-center justify-between gap-2">
        <div className="flex min-w-0 flex-wrap items-center gap-1.5">
          <span className="font-mono-code shrink-0 rounded-md bg-slate-100 px-1.5 py-0.5 text-[11px] font-semibold text-slate-700 ring-1 ring-inset ring-slate-200/80">
            {task.taskKey}
          </span>
          <PriorityBadge priority={task.priority} />
        </div>
        <button
          ref={handleRef}
          type="button"
          disabled={disabled}
          className="grid size-8 shrink-0 place-items-center rounded-lg text-slate-400 transition hover:bg-slate-100 hover:text-indigo-600 focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500 disabled:cursor-default disabled:opacity-35"
          aria-label={`Kéo để di chuyển ${task.title}`}
          title={disabled ? 'Xóa bộ lọc và tải đủ cột để kéo thả' : 'Kéo để di chuyển'}
        >
          <GripVerticalIcon className="size-4" />
        </button>
      </div>

      {/* Row 2: Task Title */}
      <h3 className="mt-2 min-w-0 break-words text-[13.5px] font-semibold leading-5 text-slate-900">
        <button
          type="button"
          onClick={(event) => onOpenTask(task.id, event.currentTarget)}
          className="w-full rounded text-left transition hover:text-indigo-700 focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500 focus-visible:ring-offset-1"
          aria-label={`Mở chi tiết ${task.taskKey} ${task.title}`}
        >
          {task.title}
        </button>
      </h3>

      {/* Row 3: Assignee + Due Date */}
      <div className="mt-3 flex flex-wrap items-center justify-between gap-2">
        {task.assigneeId && assigneeDisplay ? (
          <span
            className="inline-flex max-w-[175px] items-center gap-1.5 rounded-full bg-slate-50 py-0.5 pl-0.5 pr-2 text-[11px] font-medium text-slate-700 ring-1 ring-inset ring-slate-200/80"
            title={member?.fullName || task.assigneeId}
          >
            <Avatar
              seed={task.assigneeId}
              name={member?.fullName || task.assigneeId}
              avatarUrl={member?.avatarUrl}
              size="xs"
            />
            <span className="truncate">{assigneeDisplay}</span>
          </span>
        ) : (
          <span className="inline-flex items-center gap-1 text-[11px] font-medium text-slate-500">
            <UserIcon className="size-3 text-slate-400" />
            <span>Chưa giao</span>
          </span>
        )}

        {dueDate && (
          <span
            className={clsx(
              'tabular-nums inline-flex items-center gap-1 rounded-md px-1.5 py-0.5 text-[11px] font-semibold',
              overdue
                ? 'bg-rose-50 text-rose-700 ring-1 ring-inset ring-rose-200/90'
                : dueSoon
                  ? 'bg-amber-50 text-amber-800 ring-1 ring-inset ring-amber-200/90'
                  : isDone
                    ? 'bg-emerald-50/70 text-emerald-700'
                    : 'bg-slate-50 text-slate-600 ring-1 ring-inset ring-slate-200/70',
            )}
            title={overdue ? 'Đã quá hạn hoàn thành' : `Hạn hoàn thành: ${new Intl.DateTimeFormat('vi-VN', { dateStyle: 'medium', timeStyle: 'short' }).format(dueDate)}`}
          >
            <CalendarIcon className="size-3" />
            <span>
              {new Intl.DateTimeFormat('vi-VN', { day: '2-digit', month: 'short' }).format(dueDate)}
            </span>
            {overdue && <span className="font-bold">· Trễ</span>}
          </span>
        )}
      </div>

      {/* Row 4: Accessible Touch & Keyboard Column Move Control */}
      <div className="mt-3 border-t border-slate-100 pt-2.5">
        {showMoveMenu ? (
          <div
            className="flex items-center gap-1.5"
            onKeyDown={(event) => {
              if (event.key === 'Escape') {
                event.preventDefault();
                closeMoveMenu(true);
              }
            }}
          >
            <label className="sr-only" htmlFor={`move-task-${task.id}`}>
              Chuyển {task.taskKey} đến cột
            </label>
            <select
              id={`move-task-${task.id}`}
              autoFocus
              className="min-h-9 min-w-0 flex-1 rounded-lg border border-indigo-300 bg-indigo-50/40 px-2.5 py-1.5 text-xs font-medium text-slate-800 focus:border-indigo-500 focus:bg-white focus:outline-none focus:ring-2 focus:ring-indigo-500/20"
              value=""
              onChange={(event) => {
                if (event.target.value) onMoveTask(task.id, event.target.value);
                closeMoveMenu(true);
              }}
              onBlur={() => closeMoveMenu(false)}
            >
              <option value="" disabled>Chọn cột đích…</option>
              {movableTargets.map((item) => (
                <option key={item.id} value={item.id}>
                  {item.name}
                </option>
              ))}
            </select>
            <button
              type="button"
              onMouseDown={(event) => event.preventDefault()}
              onClick={() => closeMoveMenu(true)}
              aria-label="Hủy chuyển cột"
              className="grid size-9 shrink-0 place-items-center rounded-lg text-slate-500 transition hover:bg-slate-100 hover:text-slate-800 focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500"
            >
              <XIcon className="size-3.5" />
            </button>
          </div>
        ) : (
          <button
            ref={moveButtonRef}
            type="button"
            disabled={!canQuickMove}
            onClick={() => setShowMoveMenu(true)}
            className="inline-flex min-h-9 w-full items-center justify-between gap-2 rounded-lg bg-slate-50/80 px-2.5 py-1.5 text-xs font-semibold text-slate-600 transition hover:bg-indigo-50 hover:text-indigo-700 focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500 disabled:cursor-not-allowed disabled:opacity-45"
            aria-label={`Chuyển cột cho ${task.taskKey}`}
          >
            <span className="inline-flex items-center gap-1.5">
              <ArrowRightLeftIcon className="size-3.5" />
              <span>Chuyển cột</span>
            </span>
            <span className="text-[11px] font-medium text-slate-400 group-hover:text-indigo-500" aria-hidden="true">
              →
            </span>
          </button>
        )}
      </div>
    </article>
  );
}
