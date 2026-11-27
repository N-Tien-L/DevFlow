import { useId, useState } from 'react';
import clsx from 'clsx';
import { createTask, type ApiRequest } from '../../api/boardApi';
import { problemMessage } from '../../api/problemDetail';
import { ApiError, type ColumnResponse } from '../../types/board';
import type { ProposedTask } from '../../types/chat';
import { CheckCircleIcon, ChevronDownIcon, PlusIcon } from '../common/Icons';
import { LoadingSpinner } from '../common/UiPrimitives';

interface TaskProposalCardProps {
  proposals: ProposedTask[];
  columns: ColumnResponse[];
  request: ApiRequest;
  boardName?: string | null;
  /** True while the target board is (re)loading: creation waits for the current columns. */
  columnsLoading?: boolean;
}

interface ItemState {
  title: string;
  expanded: boolean;
  status: 'idle' | 'creating' | 'created' | 'error';
  error?: string;
}

const MAX_TITLE_LENGTH = 255;

/** Description sent to the board: the proposal text followed by its checklist as Markdown steps. */
export function taskDescription(proposal: ProposedTask) {
  const steps = (proposal.checklist ?? []).map((step) => step.trim()).filter(Boolean);
  const description = proposal.description.trim();
  if (steps.length === 0) return description;
  const checklist = steps.map((step) => `- [ ] ${step}`).join('\n');
  return description ? `${description}\n\n${checklist}` : checklist;
}

function creationError(reason: unknown) {
  if (reason instanceof ApiError) {
    if (reason.problem) return problemMessage(reason.problem, 'Tạo task thất bại.');
    return reason.message || 'Tạo task thất bại.';
  }
  return 'Tạo task thất bại. Vui lòng thử lại.';
}

/**
 * AI-proposed subtasks (T-032 through T-034). The user picks the target column and may edit each title; the
 * task is created with the user's own JWT through Board's API, which checks permissions. It then appears on
 * every open board through the existing realtime sync (T-018).
 */
export function TaskProposalCard({ proposals, columns, request, boardName, columnsLoading = false }: TaskProposalCardProps) {
  const selectId = useId();
  const [selectedColumnId, setSelectedColumnId] = useState('');
  const [items, setItems] = useState<ItemState[]>(() =>
    proposals.map((proposal) => ({ title: proposal.title, expanded: false, status: 'idle' })),
  );
  // Columns arrive (or change) after the proposal renders: fall back to the first column, never to a stale id.
  const columnId = columns.some((column) => column.id === selectedColumnId) ? selectedColumnId : (columns[0]?.id ?? '');

  const update = (index: number, patch: Partial<ItemState>) =>
    setItems((previous) => previous.map((item, i) => (i === index ? { ...item, ...patch } : item)));

  const create = async (index: number) => {
    const item = items[index];
    const title = item.title.trim();
    if (!columnId || !title || item.status === 'creating' || item.status === 'created') return;
    update(index, { status: 'creating', error: undefined });
    try {
      await createTask(request, columnId, {
        title,
        description: taskDescription(proposals[index]) || null,
        priority: 'MEDIUM',
      });
      update(index, { status: 'created' });
    } catch (reason) {
      update(index, { status: 'error', error: creationError(reason) });
    }
  };

  return (
    <div className="mt-3 rounded-xl border border-indigo-200/80 bg-indigo-50/60 p-3 text-xs">
      <div className="mb-2.5 flex flex-wrap items-center justify-between gap-2">
        <p className="font-semibold text-indigo-950">Gợi ý {proposals.length} task</p>
        {columns.length > 0 ? (
          <div className="flex min-w-0 items-center gap-1.5">
            <label htmlFor={selectId} className="shrink-0 text-slate-600">
              Tạo vào cột
            </label>
            <select
              id={selectId}
              value={columnId}
              onChange={(event) => setSelectedColumnId(event.target.value)}
              disabled={columnsLoading}
              className="min-h-8 min-w-0 max-w-40 truncate rounded-lg border border-slate-300 bg-white px-2 py-1 text-xs text-slate-800 focus:border-indigo-500 focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500/30"
            >
              {columns.map((column) => (
                <option key={column.id} value={column.id}>
                  {column.name}
                </option>
              ))}
            </select>
          </div>
        ) : null}
      </div>

      {columns.length === 0 && !columnsLoading ? (
        <p className="mb-2 rounded-lg border border-amber-200 bg-amber-50 px-2.5 py-2 text-amber-900" role="status">
          Workspace này chưa có board đang hoạt động. Hãy tạo board để thêm các task này.
        </p>
      ) : boardName ? (
        <p className="mb-2 text-[11px] text-slate-500">
          Board: <span className="font-semibold text-slate-700">{boardName}</span>
        </p>
      ) : null}

      <ul className="space-y-2">
        {proposals.map((proposal, index) => {
          const item = items[index] ?? { title: proposal.title, expanded: false, status: 'idle' as const };
          const created = item.status === 'created';
          const creating = item.status === 'creating';
          const hasDetails = Boolean(proposal.description) || Boolean(proposal.checklist?.length);
          const detailsId = `${selectId}-details-${index}`;
          return (
            <li
              key={index}
              className={clsx(
                'rounded-lg border p-2.5 transition-colors',
                created ? 'border-emerald-200 bg-emerald-50/70' : 'border-slate-200 bg-white',
              )}
            >
              <label className="sr-only" htmlFor={`${selectId}-title-${index}`}>
                Tiêu đề task {index + 1}
              </label>
              <input
                id={`${selectId}-title-${index}`}
                type="text"
                value={item.title}
                maxLength={MAX_TITLE_LENGTH}
                disabled={created || creating}
                onChange={(event) => update(index, { title: event.target.value })}
                className="min-h-8 w-full rounded-md border border-slate-200 bg-white px-2 py-1 text-xs font-semibold text-slate-900 hover:border-slate-300 focus:border-indigo-500 focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500/30 disabled:border-transparent disabled:bg-transparent disabled:text-slate-700"
              />

              <div className="mt-1.5 flex items-center justify-between gap-2 pl-0.5">
                {hasDetails ? (
                  <button
                    type="button"
                    onClick={() => update(index, { expanded: !item.expanded })}
                    aria-expanded={item.expanded}
                    aria-controls={detailsId}
                    className="inline-flex items-center gap-1 rounded text-[11px] font-medium text-indigo-700 hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500"
                  >
                    <span>{item.expanded ? 'Ẩn chi tiết' : 'Xem chi tiết'}</span>
                    <ChevronDownIcon className={clsx('size-3 transition-transform', item.expanded && 'rotate-180')} />
                  </button>
                ) : (
                  <span />
                )}
                <button
                  type="button"
                  onClick={() => void create(index)}
                  disabled={created || creating || !columnId || columnsLoading || !item.title.trim()}
                  className={clsx(
                    'inline-flex min-h-8 shrink-0 items-center gap-1 rounded-lg px-2.5 text-xs font-semibold transition focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500',
                    created
                      ? 'bg-emerald-100 text-emerald-800'
                      : 'bg-indigo-600 text-white hover:bg-indigo-700 disabled:cursor-not-allowed disabled:bg-slate-200 disabled:text-slate-500',
                  )}
                >
                  {creating ? (
                    <>
                      <LoadingSpinner className="size-3.5" />
                      <span>Đang tạo…</span>
                    </>
                  ) : created ? (
                    <>
                      <CheckCircleIcon className="size-3.5" />
                      <span>Đã tạo</span>
                    </>
                  ) : (
                    <>
                      <PlusIcon className="size-3.5" />
                      <span>Tạo task</span>
                    </>
                  )}
                </button>
              </div>

              {hasDetails && item.expanded && (
                <div id={detailsId} className="mt-1.5 space-y-1.5 rounded-md bg-slate-50 p-2 text-slate-700">
                  {proposal.description && <p className="leading-5">{proposal.description}</p>}
                  {proposal.checklist && proposal.checklist.length > 0 && (
                    <ul className="list-disc space-y-0.5 pl-4 text-[11px]">
                      {proposal.checklist.map((step, stepIndex) => (
                        <li key={stepIndex}>{step}</li>
                      ))}
                    </ul>
                  )}
                </div>
              )}

              {item.error && (
                <p className="mt-1.5 px-1.5 text-[11px] text-rose-700" role="alert">
                  {item.error}
                </p>
              )}
            </li>
          );
        })}
      </ul>
    </div>
  );
}
