import { useRef, useState } from 'react';
import { useSortable } from '@dnd-kit/react/sortable';
import clsx from 'clsx';
import type { BoardColumn, TaskListItem, WorkspaceMember } from '../../types/board';
import type { TaskGitSummary } from '../../types/gitci';
import {
  ArrowRightLeftIcon,
  CalendarIcon,
  GripVerticalIcon,
  UserIcon,
  XIcon,
} from '../common/Icons';
import { Avatar, PriorityBadge } from '../common/UiPrimitives';
import { TaskGitBadges } from './TaskGitBadges';

export interface TaskCardProps {
  task: TaskListItem;
  column: BoardColumn;
  columns: BoardColumn[];
  member?: WorkspaceMember;
  gitSummary?: TaskGitSummary;
  isAssignedToMe: boolean;
  index: number;
  disabled: boolean;
  onMoveTask(taskId: string, destinationColumnId: string): void;
  onOpenTask(taskId: string, trigger?: HTMLElement | null): void;
}

export function TaskCard({
  task,
  column,
  columns,
  member,
  gitSummary,
  isAssignedToMe,
  index,
  disabled,
  onMoveTask,
  onOpenTask,
}: TaskCardProps) {
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

      {/* Row 3b: Git Commits, Pull Requests & CI Status Badges (T-026) */}
      <TaskGitBadges summary={gitSummary} />

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
