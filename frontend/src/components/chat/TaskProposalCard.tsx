import { useState } from 'react';
import { createTask, type ApiRequest } from '../../api/boardApi';
import type { ColumnResponse } from '../../types/board';
import type { ProposedTask } from '../../types/chat';

interface TaskProposalCardProps {
  proposals: ProposedTask[];
  columns: ColumnResponse[];
  request: ApiRequest;
}

interface ProposalItemState {
  title: string;
  isExpanded: boolean;
  status: 'idle' | 'creating' | 'created' | 'error';
  errorMessage?: string;
}

export function TaskProposalCard({ proposals, columns, request }: TaskProposalCardProps) {
  const [selectedColumnId, setSelectedColumnId] = useState<string>(() => {
    // Default to the first column (usually 'TODO') or empty
    return columns.length > 0 ? columns[0].id : '';
  });

  const [items, setItems] = useState<ProposalItemState[]>(() =>
    proposals.map((p) => ({
      title: p.title,
      isExpanded: false,
      status: 'idle',
    }))
  );

  const handleTitleChange = (index: number, newTitle: string) => {
    setItems((prev) =>
      prev.map((item, i) => (i === index ? { ...item, title: newTitle } : item))
    );
  };

  const toggleExpand = (index: number) => {
    setItems((prev) =>
      prev.map((item, i) => (i === index ? { ...item, isExpanded: !item.isExpanded } : item))
    );
  };

  const handleCreateTask = async (index: number, proposal: ProposedTask) => {
    if (!selectedColumnId) return;

    const currentItem = items[index];
    if (currentItem.status === 'creating' || currentItem.status === 'created') return;

    setItems((prev) =>
      prev.map((it, i) => (i === index ? { ...it, status: 'creating', errorMessage: undefined } : it))
    );

    try {
      await createTask(request, selectedColumnId, {
        title: currentItem.title.trim() || proposal.title,
        description: proposal.description,
        priority: 'MEDIUM',
      });

      setItems((prev) =>
        prev.map((it, i) => (i === index ? { ...it, status: 'created' } : it))
      );
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : 'Tạo task thất bại.';
      setItems((prev) =>
        prev.map((it, i) => (i === index ? { ...it, status: 'error', errorMessage: msg } : it))
      );
    }
  };

  return (
    <div className="mt-3 rounded-lg border border-indigo-200 bg-indigo-50/70 p-3 text-xs dark:border-indigo-900/60 dark:bg-indigo-950/30">
      <div className="mb-2 flex items-center justify-between">
        <div className="flex items-center gap-1.5 font-semibold text-indigo-900 dark:text-indigo-200">
          <svg className="h-4 w-4 text-indigo-600 dark:text-indigo-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2" />
          </svg>
          <span>Gợi ý tạo công việc ({proposals.length})</span>
        </div>

        {columns.length > 0 && (
          <div className="flex items-center gap-1">
            <label htmlFor="target-column-select" className="text-slate-500 dark:text-slate-400">
              Vào cột:
            </label>
            <select
              id="target-column-select"
              value={selectedColumnId}
              onChange={(e) => setSelectedColumnId(e.target.value)}
              className="rounded border border-slate-300 bg-white px-2 py-0.5 text-xs text-slate-800 shadow-sm focus:border-indigo-500 focus:outline-none dark:border-slate-700 dark:bg-slate-800 dark:text-slate-200"
            >
              {columns.map((col) => (
                <option key={col.id} value={col.id}>
                  {col.name}
                </option>
              ))}
            </select>
          </div>
        )}
      </div>

      <div className="space-y-2">
        {proposals.map((proposal, idx) => {
          const itemState = items[idx] ?? { title: proposal.title, isExpanded: false, status: 'idle' };
          const isCreated = itemState.status === 'created';
          const isCreating = itemState.status === 'creating';

          return (
            <div
              key={idx}
              className={`rounded-md border p-2.5 transition-all ${
                isCreated
                  ? 'border-emerald-200 bg-emerald-50/50 dark:border-emerald-900/50 dark:bg-emerald-950/20'
                  : 'border-slate-200 bg-white dark:border-slate-800 dark:bg-slate-900'
              }`}
            >
              <div className="flex items-start justify-between gap-2">
                <input
                  type="text"
                  value={itemState.title}
                  disabled={isCreated || isCreating}
                  onChange={(e) => handleTitleChange(idx, e.target.value)}
                  className="flex-1 rounded border border-transparent px-1.5 py-0.5 font-medium text-slate-900 hover:border-slate-300 focus:border-indigo-500 focus:bg-white focus:outline-none dark:text-slate-100 dark:hover:border-slate-700 dark:focus:bg-slate-800"
                  placeholder="Tiêu đề task"
                />

                <button
                  type="button"
                  onClick={() => handleCreateTask(idx, proposal)}
                  disabled={isCreated || isCreating || !selectedColumnId}
                  className={`inline-flex shrink-0 items-center gap-1 rounded px-2.5 py-1 text-xs font-medium transition ${
                    isCreated
                      ? 'bg-emerald-100 text-emerald-800 dark:bg-emerald-900/40 dark:text-emerald-300'
                      : isCreating
                      ? 'bg-indigo-100 text-indigo-400 cursor-wait dark:bg-indigo-900/30'
                      : 'bg-indigo-600 text-white hover:bg-indigo-700 active:bg-indigo-800 dark:bg-indigo-500 dark:hover:bg-indigo-600'
                  }`}
                >
                  {isCreating ? (
                    <>
                      <svg className="h-3.5 w-3.5 animate-spin" viewBox="0 0 24 24" fill="none">
                        <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
                        <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v8H4z" />
                      </svg>
                      <span>Đang tạo…</span>
                    </>
                  ) : isCreated ? (
                    <>
                      <svg className="h-3.5 w-3.5 text-emerald-600 dark:text-emerald-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 13l4 4L19 7" />
                      </svg>
                      <span>Đã tạo</span>
                    </>
                  ) : (
                    <>
                      <svg className="h-3.5 w-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 4v16m8-8H4" />
                      </svg>
                      <span>Tạo task</span>
                    </>
                  )}
                </button>
              </div>

              {proposal.description && (
                <div className="mt-1">
                  <button
                    type="button"
                    onClick={() => toggleExpand(idx)}
                    className="text-2xs text-indigo-600 hover:underline dark:text-indigo-400"
                  >
                    {itemState.isExpanded ? 'Ẩn chi tiết ▲' : 'Xem chi tiết ▼'}
                  </button>

                  {itemState.isExpanded && (
                    <div className="mt-1 space-y-1 rounded bg-slate-50 p-2 text-slate-600 dark:bg-slate-800/60 dark:text-slate-300">
                      <p className="leading-relaxed">{proposal.description}</p>
                      {proposal.checklist && proposal.checklist.length > 0 && (
                        <div className="mt-1.5 border-t border-slate-200 pt-1 dark:border-slate-700">
                          <span className="font-semibold text-2xs text-slate-500 dark:text-slate-400">Các bước:</span>
                          <ul className="list-disc pl-3.5 space-y-0.5 mt-0.5 text-2xs">
                            {proposal.checklist.map((step, sIdx) => (
                              <li key={sIdx}>{step}</li>
                            ))}
                          </ul>
                        </div>
                      )}
                    </div>
                  )}
                </div>
              )}

              {itemState.errorMessage && (
                <p className="mt-1 text-2xs text-red-600 dark:text-red-400" role="alert">
                  {itemState.errorMessage}
                </p>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}
