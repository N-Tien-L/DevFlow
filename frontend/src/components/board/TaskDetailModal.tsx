import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import clsx from 'clsx';
import { createTaskComment, getTaskDetail, listTaskComments, listWorkspaceMembers, updateTaskDetail } from '../../api/boardApi';
import { useAuth } from '../../auth/AuthProvider';
import { ApiError, type TaskComment, type TaskCommentPage, type TaskDetail, type TaskPriority, type WorkspaceMember } from '../../types/board';
import {
  AlertCircleIcon,
  AlertTriangleIcon,
  CalendarIcon,
  CheckCircleIcon,
  EditPencilIcon,
  EyeIcon,
  LockIcon,
  MessageSquareIcon,
  RefreshIcon,
  SearchIcon,
  SendIcon,
  XIcon,
} from '../common/Icons';
import {
  Avatar,
  Badge,
  LoadingSpinner,
  PRIORITY_LABEL,
  PriorityBadge,
  StatusCategoryBadge,
} from '../common/UiPrimitives';

type Draft = { title: string; description: string; priority: TaskPriority; assigneeId: string; dueLocal: string };

const PRIORITIES: TaskPriority[] = ['LOW', 'MEDIUM', 'HIGH', 'URGENT'];

function toLocalInput(value: string | null) {
  if (!value) return '';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '';
  const local = new Date(date.getTime() - date.getTimezoneOffset() * 60_000);
  return local.toISOString().slice(0, 16);
}

function draftFrom(task: TaskDetail): Draft {
  return { title: task.title, description: task.description ?? '', priority: task.priority, assigneeId: task.assigneeId ?? '', dueLocal: toLocalInput(task.dueDate) };
}

function sameDraft(left: Draft, right: Draft) {
  return left.title === right.title && left.description === right.description && left.priority === right.priority && left.assigneeId === right.assigneeId && left.dueLocal === right.dueLocal;
}

function errorText(reason: unknown) {
  if (reason instanceof ApiError) {
    if (reason.status === 401) return 'Phiên đăng nhập đã hết. Hãy đăng nhập lại rồi mở task.';
    if (reason.status === 403) return 'Bạn không có quyền thay đổi công việc này.';
    if (reason.status === 404) return 'Công việc không còn tồn tại hoặc bạn không có quyền xem.';
    if (reason.status === 409) return 'Board đang được cập nhật hoặc đã lưu trữ. Tải lại dữ liệu trước khi thử tiếp.';
    if (reason.status === 412) return 'Công việc đã được người khác cập nhật. Bản nháp của bạn vẫn được giữ an toàn.';
    if (reason.status === 413) return 'Nội dung vượt giới hạn máy chủ cho phép.';
    if (reason.status === 428) return 'Cần tải phiên bản mới nhất của công việc trước khi lưu.';
    if (reason.status === 429) return `Bạn thao tác quá nhanh. Thử lại sau ${reason.problem?.retryAfterSeconds ?? 5} giây.`;
    if (reason.status === 0 || reason.uncertain) return 'Chưa xác định được máy chủ đã lưu hay chưa. Hãy đối soát dữ liệu trước khi gửi lại.';
    return reason.message;
  }
  if (reason instanceof Error && reason.message) return reason.message;
  return 'Không kết nối được máy chủ. Nội dung đang sửa vẫn được giữ.';
}

function sameSavedValues(task: TaskDetail, draft: Draft) {
  const dueDate = draft.dueLocal === toLocalInput(task.dueDate) ? task.dueDate : draft.dueLocal ? new Date(draft.dueLocal).toISOString() : null;
  return task.title === draft.title.trim() && task.description === (draft.description || null) && task.priority === draft.priority && task.assigneeId === (draft.assigneeId || null) && task.dueDate === dueDate;
}

interface TaskDetailModalProps {
  taskId: string;
  canEdit: boolean;
  detailRefreshVersion?: number;
  commentsRefreshVersion?: number;
  remoteDeleted?: boolean;
  onClose(): void;
  onSaved(task: TaskDetail): void;
}

export function TaskDetailModal({ taskId, canEdit, detailRefreshVersion = 0, commentsRefreshVersion = 0, remoteDeleted = false, onClose, onSaved }: TaskDetailModalProps) {
  const { request } = useAuth();
  const dialogRef = useRef<HTMLDialogElement>(null);
  const commentInputRef = useRef<HTMLTextAreaElement>(null);
  const commentsListRef = useRef<HTMLUListElement>(null);
  const discardCancelBtnRef = useRef<HTMLButtonElement>(null);
  const [task, setTask] = useState<TaskDetail | null>(null);
  const [draft, setDraft] = useState<Draft | null>(null);
  const [remoteTask, setRemoteTask] = useState<TaskDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadingError, setLoadingError] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [preview, setPreview] = useState(false);
  const [confirmClose, setConfirmClose] = useState(false);
  const [memberQuery, setMemberQuery] = useState('');
  const [members, setMembers] = useState<WorkspaceMember[]>([]);
  const [memberPage, setMemberPage] = useState(0);
  const [memberTotalPages, setMemberTotalPages] = useState(0);
  const [membersLoading, setMembersLoading] = useState(false);
  const [membersError, setMembersError] = useState<string | null>(null);
  const [comments, setComments] = useState<TaskComment[]>([]);
  const [commentPage, setCommentPage] = useState(0);
  const [commentTotalPages, setCommentTotalPages] = useState(0);
  const [commentsLoading, setCommentsLoading] = useState(false);
  const [commentsError, setCommentsError] = useState<string | null>(null);
  const [commentDraft, setCommentDraft] = useState('');
  const [sendingComment, setSendingComment] = useState(false);
  const [commentNotice, setCommentNotice] = useState<string | null>(null);
  const [remoteUnavailable, setRemoteUnavailable] = useState(false);
  const [localDetailRefreshVersion, setLocalDetailRefreshVersion] = useState(0);
  const [localCommentsRefreshVersion, setLocalCommentsRefreshVersion] = useState(0);
  const taskRef = useRef(task);
  const draftRef = useRef(draft);
  const commentsRef = useRef(comments);
  const commentPageRef = useRef(commentPage);
  const savingRef = useRef(false);
  const sendingCommentRef = useRef(false);
  const pendingDetailRefreshRef = useRef(false);
  const pendingCommentsRefreshRef = useRef(false);
  const detailRequestSequenceRef = useRef(0);
  const commentsRequestSequenceRef = useRef(0);

  taskRef.current = task;
  draftRef.current = draft;
  commentsRef.current = comments;
  commentPageRef.current = commentPage;

  const dirty = Boolean(task && draft && !sameDraft(draft, draftFrom(task)));
  const commentDirty = Boolean(commentDraft.trim());
  const hasUnsaved = dirty || commentDirty;
  const isArchived = !canEdit || remoteDeleted || remoteUnavailable;
  const timezone = useMemo(() => Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC', []);
  const membersById = useMemo(() => new Map(members.map((member) => [member.userId, member])), [members]);
  const detailRefreshKey = detailRefreshVersion + localDetailRefreshVersion;
  const commentsRefreshKey = commentsRefreshVersion + localCommentsRefreshVersion;

  const loadTask = useCallback(async (signal?: AbortSignal) => {
    const sequence = ++detailRequestSequenceRef.current;
    setLoading(true);
    setLoadingError(null);
    try {
      const result = await getTaskDetail(request, taskId, signal);
      if (signal?.aborted || sequence !== detailRequestSequenceRef.current) return;
      setTask(result);
      setDraft(draftFrom(result));
      setRemoteTask(null);
      setRemoteUnavailable(false);
    } catch (reason) {
      if (!signal?.aborted && sequence === detailRequestSequenceRef.current) {
        setLoadingError(errorText(reason));
        if (reason instanceof ApiError && (reason.status === 403 || reason.status === 404)) setRemoteUnavailable(true);
      }
    } finally {
      if (!signal?.aborted && sequence === detailRequestSequenceRef.current) setLoading(false);
    }
  }, [request, taskId]);

  useEffect(() => {
    if (!remoteDeleted) return;
    setRemoteUnavailable(true);
    setError('Công việc đã bị xóa hoặc bạn không còn quyền truy cập. Bản nháp đang mở sẽ không được gửi.');
  }, [remoteDeleted]);

  useEffect(() => {
    const dialog = dialogRef.current;
    if (dialog && !dialog.open && typeof dialog.showModal === 'function') {
      dialog.showModal();
    }
  }, []);

  useEffect(() => {
    if (confirmClose) {
      requestAnimationFrame(() => discardCancelBtnRef.current?.focus());
    }
  }, [confirmClose]);

  useEffect(() => {
    const controller = new AbortController();
    void loadTask(controller.signal);
    setComments([]);
    setCommentPage(0);
    setCommentTotalPages(0);
    setCommentsError(null);
    return () => controller.abort();
  }, [loadTask]);

  useEffect(() => {
    if (!task?.workspaceId) return;
    let active = true;
    const timer = window.setTimeout(() => {
      setMembersLoading(true);
      setMembersError(null);
      void listWorkspaceMembers(request, task.workspaceId, memberQuery.trim(), 0).then((result) => {
        if (!active) return;
        setMembers(result.items);
        setMemberPage(0);
        setMemberTotalPages(result.totalPages);
      }).catch((reason: unknown) => {
        if (active) setMembersError(errorText(reason));
      }).finally(() => { if (active) setMembersLoading(false); });
    }, memberQuery ? 250 : 0);
    return () => { active = false; window.clearTimeout(timer); };
  }, [memberQuery, request, task?.workspaceId]);

  useEffect(() => {
    let active = true;
    const controller = new AbortController();
    if (remoteDeleted || remoteUnavailable) {
      commentsRequestSequenceRef.current += 1;
      setCommentsLoading(false);
      return () => { active = false; controller.abort(); };
    }
    const sequence = ++commentsRequestSequenceRef.current;
    setCommentsLoading(true);
    setCommentsError(null);
    void listTaskComments(request, taskId, 0, controller.signal).then((result: TaskCommentPage) => {
      if (!active || sequence !== commentsRequestSequenceRef.current) return;
      setComments(result.items);
      setCommentPage(0);
      setCommentTotalPages(result.totalPages);
    }).catch((reason: unknown) => { if (active && sequence === commentsRequestSequenceRef.current) setCommentsError(errorText(reason)); })
      .finally(() => { if (active && sequence === commentsRequestSequenceRef.current) setCommentsLoading(false); });
    return () => { active = false; controller.abort(); };
  }, [remoteDeleted, remoteUnavailable, request, taskId]);

  useEffect(() => {
    if (detailRefreshKey === 0 || remoteDeleted || remoteUnavailable) return;
    const controller = new AbortController();
    const base = taskRef.current;
    if (!base || base.id !== taskId) return undefined;
    const sequence = ++detailRequestSequenceRef.current;
    void getTaskDetail(request, taskId, controller.signal).then((latest) => {
      if (controller.signal.aborted || sequence !== detailRequestSequenceRef.current || latest.id !== taskId) return;
      const currentTask = taskRef.current;
      const currentDraft = draftRef.current;
      if (!currentTask || currentTask.id !== latest.id || !currentDraft) return;
      if (savingRef.current) {
        pendingDetailRefreshRef.current = true;
        return;
      }
      if (latest.version < currentTask.version) return;
      if (!sameDraft(currentDraft, draftFrom(currentTask))) {
        if (latest.version !== currentTask.version) {
          setRemoteTask(latest);
          setError('Công việc đã được cập nhật ở nơi khác. Bản nháp của bạn vẫn được giữ.');
        }
        return;
      }
      setTask(latest);
      setDraft(draftFrom(latest));
      setRemoteTask(null);
      setRemoteUnavailable(false);
    }).catch((reason: unknown) => {
      if (controller.signal.aborted || sequence !== detailRequestSequenceRef.current) return;
      if (reason instanceof ApiError && (reason.status === 403 || reason.status === 404)) {
        setRemoteUnavailable(true);
        setError('Công việc không còn tồn tại hoặc bạn không có quyền xem. Bản nháp đang mở sẽ không được gửi.');
      } else {
        setNotice('Không tải được phiên bản mới. Nội dung đang sửa được giữ.');
      }
    });
    return () => controller.abort();
  }, [detailRefreshKey, remoteDeleted, remoteUnavailable, request, task?.id, taskId]);

  useEffect(() => {
    if (!hasUnsaved) return;
    const warn = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = ''; };
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [hasUnsaved]);

  const requestClose = useCallback(() => {
    if (saving || sendingComment) return;
    if (confirmClose) {
      setConfirmClose(false);
      return;
    }
    if (hasUnsaved) { setConfirmClose(true); return; }
    dialogRef.current?.close?.();
    onClose();
  }, [confirmClose, hasUnsaved, onClose, saving, sendingComment]);

  const loadMoreMembers = async () => {
    if (!task || membersLoading || memberPage + 1 >= memberTotalPages) return;
    setMembersLoading(true);
    try {
      const nextPage = memberPage + 1;
      const result = await listWorkspaceMembers(request, task.workspaceId, memberQuery.trim(), nextPage);
      setMembers((current) => [...current, ...result.items.filter((item) => !current.some((entry) => entry.userId === item.userId))]);
      setMemberPage(nextPage);
      setMemberTotalPages(result.totalPages);
    } catch (reason) { setMembersError(errorText(reason)); }
    finally { setMembersLoading(false); }
  };

  const loadMoreComments = async () => {
    if (commentsLoading || remoteDeleted || remoteUnavailable || commentPage + 1 >= commentTotalPages) return;
    const sequence = ++commentsRequestSequenceRef.current;
    setCommentsLoading(true);
    try {
      const nextPage = commentPage + 1;
      const result = await listTaskComments(request, taskId, nextPage);
      if (sequence !== commentsRequestSequenceRef.current) return;
      setComments((current) => [...current, ...result.items.filter((item) => !current.some((entry) => entry.id === item.id))]);
      setCommentPage(nextPage);
      setCommentTotalPages(result.totalPages);
    } catch (reason) { if (sequence === commentsRequestSequenceRef.current) setCommentsError(errorText(reason)); }
    finally { if (sequence === commentsRequestSequenceRef.current) setCommentsLoading(false); }
  };

  const saveTask = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!task || !draft || !dirty || saving || !canEdit || remoteDeleted || remoteUnavailable) return;
    const title = draft.title.trim();
    if (!title || [...title].length > 255 || [...draft.description].length > 10_000) {
      setError('Tiêu đề phải có 1–255 ký tự; mô tả tối đa 10.000 ký tự.');
      return;
    }
    const originalDue = toLocalInput(task.dueDate);
    let dueDate: string | null;
    if (draft.dueLocal === originalDue) dueDate = task.dueDate;
    else if (!draft.dueLocal) dueDate = null;
    else {
      const parsed = new Date(draft.dueLocal);
      if (Number.isNaN(parsed.getTime())) { setError('Hạn hoàn thành không hợp lệ.'); return; }
      const roundTrip = toLocalInput(parsed.toISOString());
      if (roundTrip !== draft.dueLocal) { setError('Thời gian này không hợp lệ trong múi giờ hiện tại.'); return; }
      dueDate = parsed.toISOString();
    }
    savingRef.current = true;
    setSaving(true); setError(null); setNotice(null); setRemoteTask(null);
    try {
      const result = await updateTaskDetail(request, task, {
        title, description: draft.description || null, priority: draft.priority,
        assigneeId: draft.assigneeId || null, dueDate,
      });
      setTask(result); setDraft(draftFrom(result)); setNotice('Đã lưu thay đổi.'); onSaved(result);
    } catch (reason) {
      const message = errorText(reason);
      setError(message);
      const uncertain = reason instanceof ApiError && reason.uncertain;
      if (reason instanceof ApiError && (reason.status === 412 || uncertain)) {
        try {
          const latest = await getTaskDetail(request, task.id);
          if (uncertain && sameSavedValues(latest, draft)) {
            setTask(latest); setDraft(draftFrom(latest)); setNotice('Đã xác nhận thay đổi được lưu.'); onSaved(latest);
          } else {
            setRemoteTask(latest);
            if (uncertain) setError('Máy chủ có phản hồi khác với bản nháp. Nội dung đang sửa được giữ; hãy hợp nhất với phiên bản mới.');
          }
        } catch { setError('Chưa thể xác nhận kết quả lưu. Bản nháp được giữ; tải lại dữ liệu trước khi thử lại.'); }
      }
    } finally {
      savingRef.current = false;
      setSaving(false);
      if (pendingDetailRefreshRef.current) {
        pendingDetailRefreshRef.current = false;
        setLocalDetailRefreshVersion((version) => version + 1);
      }
    }
  };

  const mergeRemote = () => {
    if (!task || !draft || !remoteTask) return;
    const oldBase = draftFrom(task);
    const nextBase = draftFrom(remoteTask);
    const merged: Draft = {
      title: draft.title !== oldBase.title ? draft.title : nextBase.title,
      description: draft.description !== oldBase.description ? draft.description : nextBase.description,
      priority: draft.priority !== oldBase.priority ? draft.priority : nextBase.priority,
      assigneeId: draft.assigneeId !== oldBase.assigneeId ? draft.assigneeId : nextBase.assigneeId,
      dueLocal: draft.dueLocal !== oldBase.dueLocal ? draft.dueLocal : nextBase.dueLocal,
    };
    setTask(remoteTask); setDraft(merged); setRemoteTask(null);
    setError(null);
    setNotice('Đã nạp phiên bản mới nhất và giữ nguyên các trường bạn đang sửa. Kiểm tra lại rồi nhấn Lưu.');
  };

  const sendComment = async () => {
    const content = commentDraft.trim();
    if (!content || [...content].length > 5_000 || sendingComment || !canEdit || remoteDeleted || remoteUnavailable) return;
    sendingCommentRef.current = true;
    setSendingComment(true); setCommentsError(null); setCommentNotice(null);
    try {
      const saved = await createTaskComment(request, taskId, content);
      if (!remoteDeleted && !remoteUnavailable) setComments((current) => [...current.filter((comment) => comment.id !== saved.id), saved].sort((a, b) => a.createdAt.localeCompare(b.createdAt) || a.id.localeCompare(b.id)));
      setCommentDraft(''); setCommentNotice('Đã gửi bình luận.');
    } catch (reason) {
      setCommentsError(errorText(reason));
      if (reason instanceof ApiError && reason.uncertain) setCommentNotice('Kết quả gửi chưa xác định. Tải lại bình luận trước khi gửi tiếp.');
    } finally {
      sendingCommentRef.current = false;
      setSendingComment(false);
      if (pendingCommentsRefreshRef.current) {
        pendingCommentsRefreshRef.current = false;
        setLocalCommentsRefreshVersion((version) => version + 1);
      }
      if (pendingDetailRefreshRef.current) {
        pendingDetailRefreshRef.current = false;
        setLocalDetailRefreshVersion((version) => version + 1);
      }
    }
  };

  const refreshComments = useCallback(async (signal?: AbortSignal) => {
    if (remoteDeleted || remoteUnavailable) return;
    if (sendingCommentRef.current) {
      pendingCommentsRefreshRef.current = true;
      return;
    }
    const sequence = ++commentsRequestSequenceRef.current;
    const pageCount = Math.max(1, commentPageRef.current + 1);
    setCommentsLoading(true); setCommentsError(null);
    try {
      const results = await Promise.all(Array.from({ length: pageCount }, (_, page) => listTaskComments(request, taskId, page, signal)));
      if (signal?.aborted || sequence !== commentsRequestSequenceRef.current) return;
      const latestPages = results.flatMap((result) => result.items);
      const merged = [...new Map([...commentsRef.current, ...latestPages].map((comment) => [comment.id, comment])).values()]
        .sort((left, right) => left.createdAt.localeCompare(right.createdAt) || left.id.localeCompare(right.id));
      const scrollTop = commentsListRef.current?.scrollTop ?? 0;
      setComments(merged);
      setCommentPage(Math.min(pageCount - 1, Math.max(0, results[0]?.totalPages ?? 1) - 1));
      setCommentTotalPages(results[0]?.totalPages ?? 0);
      setCommentNotice(null);
      requestAnimationFrame(() => { if (commentsListRef.current) commentsListRef.current.scrollTop = scrollTop; });
    } catch (reason) { if (!signal?.aborted && sequence === commentsRequestSequenceRef.current) setCommentsError(errorText(reason)); }
    finally { if (!signal?.aborted && sequence === commentsRequestSequenceRef.current) setCommentsLoading(false); }
  }, [remoteDeleted, remoteUnavailable, request, taskId]);

  useEffect(() => {
    if (commentsRefreshKey === 0 || remoteDeleted || remoteUnavailable) return;
    if (sendingCommentRef.current) {
      pendingCommentsRefreshRef.current = true;
      return undefined;
    }
    const controller = new AbortController();
    void refreshComments(controller.signal);
    return () => controller.abort();
  }, [commentsRefreshKey, refreshComments, remoteDeleted, remoteUnavailable]);

  const handleCancel = (event: React.SyntheticEvent<HTMLDialogElement>) => {
    event.preventDefault();
    requestClose();
  };

  const selectedAssignee = draft?.assigneeId ? membersById.get(draft.assigneeId) : undefined;

  return (
    <dialog
      ref={dialogRef}
      aria-labelledby="task-detail-title"
      onCancel={handleCancel}
      onClick={(event) => { if (event.target === event.currentTarget) requestClose(); }}
      className="devflow-dialog m-auto max-h-[min(94dvh,900px)] w-[calc(100vw-16px)] max-w-none overflow-hidden rounded-2xl border border-slate-200/90 bg-white p-0 text-slate-900 shadow-2xl sm:w-[min(960px,calc(100vw-32px))] sm:rounded-3xl"
    >
      <div className="flex max-h-[min(94dvh,900px)] flex-col">
        {/* Sticky Modal Header */}
        <header className="flex shrink-0 items-start justify-between gap-3 border-b border-slate-200/85 bg-white px-4 py-3.5 sm:px-6 sm:py-4">
          <div className="min-w-0 flex-1">
            <div className="flex flex-wrap items-center gap-1.5">
              {task?.taskKey && (
                <span className="font-mono-code rounded-md bg-indigo-50 px-2 py-0.5 text-xs font-semibold text-indigo-700 ring-1 ring-inset ring-indigo-200/80">
                  {task.taskKey}
                </span>
              )}
              {task && <StatusCategoryBadge category={task.statusCategory} />}
              {draft && <PriorityBadge priority={draft.priority} />}
              {isArchived && (
                <Badge tone="warning">
                  <LockIcon className="size-3" />
                  <span>Chỉ xem</span>
                </Badge>
              )}
              {hasUnsaved && !saving && (
                <Badge tone="warning">
                  <span className="size-1.5 rounded-full bg-amber-500" aria-hidden="true" />
                  <span>Chưa lưu</span>
                </Badge>
              )}
            </div>
            <h2 id="task-detail-title" className="mt-1.5 break-words text-base font-bold leading-snug text-slate-950 sm:text-lg">
              {task?.title ?? 'Chi tiết công việc'}
            </h2>
          </div>

          <button
            type="button"
            onClick={requestClose}
            className="grid size-10 shrink-0 place-items-center rounded-xl text-slate-500 transition hover:bg-slate-100 hover:text-slate-900 focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500"
            aria-label="Đóng cửa sổ chi tiết"
          >
            <XIcon className="size-5" />
          </button>
        </header>

        {/* Loading State */}
        {loading && (
          <div className="grid min-h-72 place-items-center p-6" role="status">
            <div className="flex items-center gap-2.5 text-sm font-medium text-slate-600">
              <LoadingSpinner className="size-5 text-indigo-600" />
              <span>Đang tải chi tiết công việc…</span>
            </div>
          </div>
        )}

        {/* Load Error State */}
        {!loading && loadingError && (
          <div className="space-y-4 p-6">
            <div className="flex items-start gap-2.5 rounded-xl border border-rose-200 bg-rose-50 p-4 text-sm text-rose-900" role="alert">
              <AlertCircleIcon className="mt-0.5 size-4 shrink-0 text-rose-600" />
              <span>{loadingError}</span>
            </div>
            <button
              type="button"
              className="inline-flex min-h-10 items-center gap-2 rounded-xl bg-indigo-600 px-4 py-2 text-sm font-semibold text-white transition hover:bg-indigo-700 focus:outline-none focus-visible:ring-4 focus-visible:ring-indigo-500/30"
              onClick={() => void loadTask()}
            >
              <RefreshIcon className="size-4" />
              <span>Thử tải lại</span>
            </button>
          </div>
        )}

        {/* Main Detail Form */}
        {!loading && task && draft && (
          <form onSubmit={(event) => void saveTask(event)} className="flex min-h-0 flex-1 flex-col">
            <div className="devflow-scroll min-h-0 flex-1 overflow-y-auto">
              {/* Conflict / Error / Success Alert Strip at Top of Scroll Area */}
              {(error || notice) && (
                <div className="space-y-2.5 px-4 pt-4 sm:px-6">
                  {error && (
                    <div
                      className={clsx(
                        'rounded-xl border p-3.5 text-sm',
                        remoteTask
                          ? 'border-amber-300 bg-amber-50/95 text-amber-950'
                          : 'border-rose-200 bg-rose-50 text-rose-900',
                      )}
                      role="alert"
                    >
                      <div className="flex items-start gap-2.5">
                        {remoteTask ? (
                          <AlertTriangleIcon className="mt-0.5 size-4 shrink-0 text-amber-600" />
                        ) : (
                          <AlertCircleIcon className="mt-0.5 size-4 shrink-0 text-rose-600" />
                        )}
                        <div className="min-w-0 flex-1">
                          <p className="font-medium leading-5">{error}</p>
                          {remoteTask && (
                            <div className="mt-2.5 flex flex-wrap items-center gap-2.5">
                              <span className="text-xs text-amber-800">
                                Phiên bản mới <strong className="font-mono-code">v{remoteTask.version}</strong> đã sẵn sàng trên máy chủ.
                              </span>
                              <button
                                type="button"
                                onClick={mergeRemote}
                                className="inline-flex min-h-9 items-center gap-1.5 rounded-lg bg-amber-600 px-3 py-1.5 text-xs font-semibold text-white shadow-2xs transition hover:bg-amber-700 focus:outline-none focus-visible:ring-2 focus-visible:ring-amber-500"
                              >
                                <RefreshIcon className="size-3.5" />
                                <span>Nạp bản mới và giữ phần đang sửa</span>
                              </button>
                            </div>
                          )}
                        </div>
                      </div>
                    </div>
                  )}

                  {notice && (
                    <div className="flex items-center gap-2 rounded-xl border border-emerald-200 bg-emerald-50/90 px-3.5 py-2.5 text-sm font-medium text-emerald-900" role="status">
                      <CheckCircleIcon className="size-4 shrink-0 text-emerald-600" />
                      <span>{notice}</span>
                    </div>
                  )}
                </div>
              )}

              <div className="grid gap-5 p-4 sm:gap-6 sm:p-6 lg:grid-cols-[minmax(0,1fr)_288px]">
                {/* Left Column: Title, Markdown Description, Comments */}
                <div className="min-w-0 space-y-5">
                  <label className="block">
                    <span className="mb-1.5 block text-xs font-bold uppercase tracking-wider text-slate-500">
                      Tiêu đề công việc
                    </span>
                    <input
                      required
                      maxLength={255}
                      disabled={isArchived || saving}
                      value={draft.title}
                      onChange={(event) => setDraft({ ...draft, title: event.target.value })}
                      className="min-h-11 w-full rounded-xl border border-slate-300/90 bg-white px-3.5 py-2.5 text-base font-semibold text-slate-950 shadow-2xs outline-none transition focus:border-indigo-500 focus:ring-4 focus:ring-indigo-500/15 disabled:cursor-not-allowed disabled:bg-slate-50 disabled:text-slate-600"
                    />
                  </label>

                  {/* Markdown Description Card */}
                  <section className="rounded-2xl border border-slate-200/90 bg-white p-3.5 shadow-2xs sm:p-4">
                    <div className="mb-2.5 flex flex-wrap items-center justify-between gap-2">
                      <label htmlFor="task-description" className="text-xs font-bold uppercase tracking-wider text-slate-500">
                        Mô tả Markdown
                      </label>
                      <div className="inline-flex rounded-xl bg-slate-100 p-1" role="group" aria-label="Chế độ xem mô tả">
                        <button
                          type="button"
                          onClick={() => setPreview(false)}
                          aria-pressed={!preview}
                          className={clsx(
                            'inline-flex min-h-8 items-center gap-1.5 rounded-lg px-2.5 py-1 text-xs font-semibold transition focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500',
                            !preview ? 'bg-white text-slate-900 shadow-xs' : 'text-slate-600 hover:text-slate-900',
                          )}
                        >
                          <EditPencilIcon className="size-3.5" />
                          <span>Viết</span>
                        </button>
                        <button
                          type="button"
                          onClick={() => setPreview(true)}
                          aria-pressed={preview}
                          className={clsx(
                            'inline-flex min-h-8 items-center gap-1.5 rounded-lg px-2.5 py-1 text-xs font-semibold transition focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500',
                            preview ? 'bg-white text-slate-900 shadow-xs' : 'text-slate-600 hover:text-slate-900',
                          )}
                        >
                          <EyeIcon className="size-3.5" />
                          <span>Xem trước</span>
                        </button>
                      </div>
                    </div>

                    {!preview ? (
                      <textarea
                        id="task-description"
                        rows={9}
                        maxLength={10_000}
                        disabled={isArchived || saving}
                        value={draft.description}
                        onChange={(event) => setDraft({ ...draft, description: event.target.value })}
                        placeholder="Mô tả chi tiết yêu cầu, tiêu chí nghiệm thu hoặc ghi chú kỹ thuật… Hỗ trợ Markdown (GFM)."
                        className="font-mono-code w-full resize-y rounded-xl border border-slate-200/90 bg-slate-50/60 p-3 text-sm leading-6 text-slate-900 outline-none transition placeholder:font-sans placeholder:text-slate-400 focus:border-indigo-500 focus:bg-white focus:ring-4 focus:ring-indigo-500/15 disabled:cursor-not-allowed disabled:bg-slate-100/80 disabled:text-slate-600"
                      />
                    ) : (
                      <div
                        className="markdown-preview min-h-52 rounded-xl border border-slate-200/80 bg-slate-50/70 p-4"
                        aria-label="Xem trước mô tả Markdown"
                      >
                        <ReactMarkdown
                          remarkPlugins={[remarkGfm]}
                          components={{
                            img: ({ alt }) => (
                              <span className="rounded bg-amber-50 px-1.5 py-0.5 text-xs font-medium text-amber-800">
                                [Ảnh ngoài không được tải: {alt || 'không có mô tả'}]
                              </span>
                            ),
                            a: ({ href, children, ...props }) => (
                              <a {...props} href={href} target="_blank" rel="noopener noreferrer">{children}</a>
                            ),
                          }}
                        >
                          {draft.description || '*Chưa có mô tả cho công việc này.*'}
                        </ReactMarkdown>
                      </div>
                    )}

                    <div className="mt-1.5 flex items-center justify-between gap-2 text-[11px] text-slate-500">
                      <span>Hỗ trợ danh sách, bảng và khối mã Markdown</span>
                      <span className="tabular-nums">{[...draft.description].length.toLocaleString('vi-VN')} / 10.000</span>
                    </div>
                  </section>

                  {/* Comments Card */}
                  <section className="rounded-2xl border border-slate-200/90 bg-white p-3.5 shadow-2xs sm:p-4">
                    <div className="flex items-center justify-between gap-2">
                      <div className="flex items-center gap-2">
                        <MessageSquareIcon className="size-4 text-indigo-600" />
                        <h3 className="text-sm font-bold text-slate-900">Bình luận</h3>
                        <span className="tabular-nums rounded-full bg-slate-100 px-2 py-0.5 text-[11px] font-semibold text-slate-600">
                          {comments.length}
                        </span>
                      </div>
                      <button
                        type="button"
                        onClick={() => void refreshComments()}
                        className="inline-flex min-h-8 items-center gap-1 rounded-lg px-2 py-1 text-xs font-semibold text-indigo-700 transition hover:bg-indigo-50 focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500"
                      >
                        <RefreshIcon className={clsx('size-3.5', commentsLoading && 'animate-spin motion-reduce:animate-none')} />
                        <span>Tải lại</span>
                      </button>
                    </div>

                    {commentsLoading && comments.length === 0 && (
                      <div className="mt-3 flex items-center gap-2 text-xs text-slate-500" role="status">
                        <LoadingSpinner className="size-3.5 text-indigo-600" />
                        <span>Đang tải bình luận…</span>
                      </div>
                    )}
                    {commentsError && (
                      <p className="mt-3 flex items-center gap-1.5 rounded-lg bg-rose-50 px-3 py-2 text-xs font-medium text-rose-700" role="alert">
                        <AlertCircleIcon className="size-3.5 shrink-0" />
                        <span>{commentsError}</span>
                      </p>
                    )}
                    {!commentsLoading && !commentsError && comments.length === 0 && (
                      <p className="mt-3 rounded-xl border border-dashed border-slate-200 bg-slate-50/70 px-3.5 py-4 text-center text-xs text-slate-500">
                        Chưa có bình luận nào. Hãy trao đổi tiến độ hoặc ghi chú cùng nhóm bên dưới.
                      </p>
                    )}

                    {comments.length > 0 && (
                      <ul ref={commentsListRef} className="devflow-scroll mt-3 max-h-64 space-y-2.5 overflow-y-auto pr-0.5">
                        {comments.map((comment: TaskComment) => {
                          const author = membersById.get(comment.authorId);
                          const authorName = author?.fullName || `Thành viên ${comment.authorId.slice(0, 8)}`;
                          return (
                            <li key={comment.id} className="flex items-start gap-2.5 rounded-xl border border-slate-100 bg-slate-50/80 p-3">
                              <Avatar seed={comment.authorId} name={authorName} avatarUrl={author?.avatarUrl} size="sm" className="mt-0.5" />
                              <div className="min-w-0 flex-1">
                                <div className="flex flex-wrap items-baseline justify-between gap-2 text-xs">
                                  <span className="font-semibold text-slate-900">{authorName}</span>
                                  <time dateTime={comment.createdAt} className="tabular-nums shrink-0 text-[11px] text-slate-500">
                                    {new Intl.DateTimeFormat('vi-VN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(comment.createdAt))}
                                  </time>
                                </div>
                                <p className="mt-1.5 whitespace-pre-wrap break-words text-sm leading-5 text-slate-700">
                                  {comment.content}
                                </p>
                              </div>
                            </li>
                          );
                        })}
                      </ul>
                    )}

                    {commentPage + 1 < commentTotalPages && (
                      <button
                        type="button"
                        disabled={commentsLoading}
                        onClick={() => void loadMoreComments()}
                        className="mt-3 inline-flex min-h-8 items-center text-xs font-semibold text-indigo-700 hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500"
                      >
                        Tải bình luận cũ hơn
                      </button>
                    )}

                    {commentNotice && (
                      <p className="mt-2.5 text-xs font-medium text-indigo-700" role="status">{commentNotice}</p>
                    )}

                    <div className="mt-4 border-t border-slate-100 pt-3.5">
                      <label className="block">
                        <span className="mb-1.5 block text-xs font-semibold text-slate-700">Viết bình luận</span>
                        <textarea
                          ref={commentInputRef}
                          rows={3}
                          maxLength={5000}
                          disabled={isArchived || sendingComment}
                          value={commentDraft}
                          onChange={(event) => setCommentDraft(event.target.value)}
                          onKeyDown={(event) => {
                            if ((event.ctrlKey || event.metaKey) && event.key === 'Enter') {
                              event.preventDefault();
                              void sendComment();
                            }
                          }}
                          placeholder={isArchived ? 'Không thể bình luận ở chế độ chỉ xem' : 'Nhập bình luận… (Ctrl+Enter để gửi nhanh)'}
                          className="w-full rounded-xl border border-slate-300/90 bg-white p-3 text-sm text-slate-900 outline-none transition placeholder:text-slate-400 focus:border-indigo-500 focus:ring-4 focus:ring-indigo-500/15 disabled:cursor-not-allowed disabled:bg-slate-100"
                        />
                      </label>
                      <div className="mt-2 flex flex-wrap items-center justify-between gap-2">
                        <span className="tabular-nums text-[11px] text-slate-500">{[...commentDraft].length}/5.000</span>
                        <button
                          type="button"
                          onClick={() => void sendComment()}
                          disabled={isArchived || sendingComment || !commentDraft.trim() || [...commentDraft.trim()].length > 5000}
                          className="inline-flex min-h-9 items-center gap-1.5 rounded-xl bg-indigo-600 px-3.5 py-2 text-xs font-semibold text-white shadow-2xs transition hover:bg-indigo-700 focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500 disabled:cursor-not-allowed disabled:opacity-50"
                        >
                          {sendingComment ? <LoadingSpinner className="size-3.5 text-white" /> : <SendIcon className="size-3.5" />}
                          <span>{sendingComment ? 'Đang gửi…' : 'Gửi bình luận'}</span>
                        </button>
                      </div>
                    </div>
                  </section>
                </div>

                {/* Right Column: Structured Properties Panel */}
                <aside className="min-w-0 space-y-4">
                  <div className="space-y-4 rounded-2xl border border-slate-200/90 bg-slate-50/75 p-4">
                    <h3 className="text-xs font-bold uppercase tracking-wider text-slate-500">
                      Thuộc tính
                    </h3>

                    <label className="block">
                      <span className="mb-1.5 flex items-center justify-between text-xs font-semibold text-slate-700">
                        <span>Độ ưu tiên</span>
                      </span>
                      <select
                        disabled={isArchived || saving}
                        value={draft.priority}
                        onChange={(event) => setDraft({ ...draft, priority: event.target.value as TaskPriority })}
                        className="min-h-10 w-full rounded-xl border border-slate-300/90 bg-white px-3 py-2 text-sm font-medium text-slate-900 outline-none transition focus:border-indigo-500 focus:ring-4 focus:ring-indigo-500/15 disabled:cursor-not-allowed disabled:bg-slate-100"
                      >
                        {PRIORITIES.map((item) => (
                          <option key={item} value={item}>{PRIORITY_LABEL[item]}</option>
                        ))}
                      </select>
                    </label>

                    <div className="space-y-2">
                      <label className="block text-xs font-semibold text-slate-700" htmlFor="task-assignee-select">
                        Người phụ trách
                      </label>
                      <div className="relative">
                        <SearchIcon className="pointer-events-none absolute left-3 top-1/2 size-3.5 -translate-y-1/2 text-slate-400" />
                        <input
                          type="search"
                          aria-label="Tìm thành viên phụ trách"
                          disabled={isArchived || saving}
                          value={memberQuery}
                          onChange={(event) => setMemberQuery(event.target.value)}
                          placeholder="Tìm thành viên…"
                          className="min-h-10 w-full rounded-xl border border-slate-300/90 bg-white py-2 pl-8 pr-3 text-xs text-slate-900 outline-none transition focus:border-indigo-500 focus:ring-4 focus:ring-indigo-500/15 disabled:cursor-not-allowed disabled:bg-slate-100"
                        />
                      </div>
                      <select
                        id="task-assignee-select"
                        disabled={isArchived || saving || (membersLoading && members.length === 0)}
                        value={draft.assigneeId}
                        onChange={(event) => setDraft({ ...draft, assigneeId: event.target.value })}
                        className="min-h-10 w-full rounded-xl border border-slate-300/90 bg-white px-3 py-2 text-sm text-slate-900 outline-none transition focus:border-indigo-500 focus:ring-4 focus:ring-indigo-500/15 disabled:cursor-not-allowed disabled:bg-slate-100"
                      >
                        <option value="">Chưa giao</option>
                        {draft.assigneeId && !members.some((member) => member.userId === draft.assigneeId) && (
                          <option value={draft.assigneeId}>Thành viên {draft.assigneeId.slice(0, 8)} (đang được giao)</option>
                        )}
                        {members.map((member) => (
                          <option key={member.userId} value={member.userId}>
                            {member.fullName || `Thành viên ${member.userId.slice(0, 8)}`}
                          </option>
                        ))}
                      </select>
                      {selectedAssignee && (
                        <div className="flex items-center gap-2 rounded-xl bg-white px-2.5 py-1.5 text-xs text-slate-700 ring-1 ring-inset ring-slate-200/80">
                          <Avatar seed={selectedAssignee.userId} name={selectedAssignee.fullName} avatarUrl={selectedAssignee.avatarUrl} size="xs" />
                          <span className="truncate font-medium">{selectedAssignee.fullName}</span>
                        </div>
                      )}
                      {membersLoading && <span className="block text-xs text-slate-500" role="status">Đang tải thành viên…</span>}
                      {membersError && <span className="block text-xs text-rose-700" role="alert">{membersError}</span>}
                      {memberPage + 1 < memberTotalPages && (
                        <button
                          type="button"
                          disabled={membersLoading}
                          onClick={() => void loadMoreMembers()}
                          className="text-xs font-semibold text-indigo-700 hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500"
                        >
                          Tải thêm thành viên
                        </button>
                      )}
                    </div>

                    <div className="space-y-1.5">
                      <div className="flex items-center justify-between gap-2">
                        <label htmlFor="task-due-input" className="inline-flex items-center gap-1.5 text-xs font-semibold text-slate-700">
                          <CalendarIcon className="size-3.5 text-slate-500" />
                          <span>Hạn hoàn thành</span>
                        </label>
                        {!isArchived && draft.dueLocal && (
                          <button
                            type="button"
                            onClick={() => setDraft({ ...draft, dueLocal: '' })}
                            className="text-[11px] font-semibold text-slate-500 hover:text-rose-600 focus:outline-none focus-visible:underline"
                          >
                            Xóa hạn
                          </button>
                        )}
                      </div>
                      <input
                        id="task-due-input"
                        type="datetime-local"
                        disabled={isArchived || saving}
                        value={draft.dueLocal}
                        onChange={(event) => setDraft({ ...draft, dueLocal: event.target.value })}
                        className="min-h-10 w-full rounded-xl border border-slate-300/90 bg-white px-3 py-2 text-sm text-slate-900 outline-none transition focus:border-indigo-500 focus:ring-4 focus:ring-indigo-500/15 disabled:cursor-not-allowed disabled:bg-slate-100"
                      />
                      <span className="block text-[11px] text-slate-500">Múi giờ: {timezone}</span>
                    </div>

                    <dl className="space-y-2.5 border-t border-slate-200/80 pt-3.5 text-xs">
                      <div className="flex items-center justify-between gap-2">
                        <dt className="text-slate-500">Trạng thái</dt>
                        <dd><StatusCategoryBadge category={task.statusCategory} /></dd>
                      </div>
                      <div className="flex items-center justify-between gap-2">
                        <dt className="text-slate-500">Phiên bản</dt>
                        <dd className="font-mono-code font-semibold text-slate-700">v{task.version}</dd>
                      </div>
                      <div className="flex items-center justify-between gap-2">
                        <dt className="text-slate-500">Tạo lúc</dt>
                        <dd className="tabular-nums font-medium text-slate-700">
                          {new Intl.DateTimeFormat('vi-VN', { dateStyle: 'medium' }).format(new Date(task.createdAt))}
                        </dd>
                      </div>
                      <div className="flex items-center justify-between gap-2">
                        <dt className="text-slate-500">Cập nhật</dt>
                        <dd className="tabular-nums font-medium text-slate-700">
                          {new Intl.DateTimeFormat('vi-VN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(task.updatedAt))}
                        </dd>
                      </div>
                    </dl>
                  </div>

                  {isArchived && (
                    <div className="flex items-start gap-2.5 rounded-2xl border border-amber-200/90 bg-amber-50/90 p-3.5 text-xs leading-5 text-amber-950">
                      <LockIcon className="mt-0.5 size-4 shrink-0 text-amber-700" />
                      <p>
                        {remoteDeleted || remoteUnavailable
                          ? 'Công việc không còn khả dụng. Bản nháp hiện có được giữ tại máy và không thể gửi.'
                          : 'Board đang lưu trữ, đồng bộ hoặc mất kết nối. Bạn chỉ có thể xem chi tiết cho đến khi quyền sửa được xác nhận.'}
                      </p>
                    </div>
                  )}
                </aside>
              </div>
            </div>

            {/* Sticky Modal Action Footer */}
            <footer className="flex shrink-0 flex-wrap items-center justify-between gap-3 border-t border-slate-200/85 bg-slate-50/90 px-4 py-3 sm:px-6 sm:py-3.5">
              <div className="flex items-center gap-2 text-xs font-medium" role="status">
                {saving ? (
                  <>
                    <LoadingSpinner className="size-3.5 text-indigo-600" />
                    <span className="text-indigo-700">Đang lưu thay đổi…</span>
                  </>
                ) : hasUnsaved ? (
                  <>
                    <span className="size-2 rounded-full bg-amber-500" aria-hidden="true" />
                    <span className="text-amber-800">Có thay đổi chưa lưu</span>
                  </>
                ) : isArchived ? (
                  <>
                    <LockIcon className="size-3.5 text-slate-500" />
                    <span className="text-slate-600">Chế độ chỉ xem</span>
                  </>
                ) : (
                  <>
                    <CheckCircleIcon className="size-3.5 text-emerald-600" />
                    <span className="text-slate-600">Đã đồng bộ</span>
                  </>
                )}
              </div>

              <div className="flex items-center gap-2">
                <button
                  type="button"
                  onClick={requestClose}
                  className="inline-flex min-h-10 items-center justify-center rounded-xl border border-slate-300 bg-white px-4 py-2 text-sm font-semibold text-slate-700 transition hover:bg-slate-50 focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500"
                >
                  Đóng
                </button>
                <button
                  type="submit"
                  disabled={isArchived || !dirty || saving}
                  className="inline-flex min-h-10 items-center justify-center gap-2 rounded-xl bg-indigo-600 px-4 py-2 text-sm font-semibold text-white shadow-xs shadow-indigo-600/20 transition hover:bg-indigo-700 focus:outline-none focus-visible:ring-4 focus-visible:ring-indigo-500/30 disabled:cursor-not-allowed disabled:opacity-50"
                >
                  {saving && <LoadingSpinner className="size-4 text-white" />}
                  <span>{saving ? 'Đang lưu…' : 'Lưu thay đổi'}</span>
                </button>
              </div>
            </footer>
          </form>
        )}
      </div>

      {/* Unsaved Changes Confirmation Dialog */}
      {confirmClose && (
        <div
          className="fixed inset-0 z-[1001] grid place-items-center bg-slate-950/50 p-4 backdrop-blur-xs"
          role="presentation"
          onKeyDown={(event) => {
            if (event.key === 'Escape') {
              event.stopPropagation();
              setConfirmClose(false);
            }
          }}
        >
          <section
            role="alertdialog"
            aria-modal="true"
            aria-labelledby="discard-title"
            className="w-full max-w-sm rounded-2xl border border-slate-200 bg-white p-5 shadow-2xl"
          >
            <div className="flex items-start gap-3">
              <div className="grid size-10 shrink-0 place-items-center rounded-xl bg-amber-50 text-amber-600 ring-1 ring-inset ring-amber-200">
                <AlertTriangleIcon className="size-5" />
              </div>
              <div>
                <h3 id="discard-title" className="text-base font-bold text-slate-950">Bỏ nội dung chưa lưu?</h3>
                <p className="mt-1.5 text-sm leading-5 text-slate-600">
                  Các thay đổi của công việc hoặc bình luận bạn đang viết sẽ bị mất.
                </p>
              </div>
            </div>
            <div className="mt-5 flex justify-end gap-2">
              <button
                ref={discardCancelBtnRef}
                type="button"
                onClick={() => setConfirmClose(false)}
                className="inline-flex min-h-10 items-center rounded-xl border border-slate-300 bg-white px-3.5 py-2 text-sm font-semibold text-slate-700 transition hover:bg-slate-50 focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500"
              >
                Tiếp tục sửa
              </button>
              <button
                type="button"
                onClick={() => {
                  setConfirmClose(false);
                  setDraft(task ? draftFrom(task) : null);
                  setCommentDraft('');
                  dialogRef.current?.close?.();
                  onClose();
                }}
                className="inline-flex min-h-10 items-center rounded-xl bg-rose-600 px-3.5 py-2 text-sm font-semibold text-white transition hover:bg-rose-700 focus:outline-none focus-visible:ring-2 focus-visible:ring-rose-500"
              >
                Bỏ thay đổi
              </button>
            </div>
          </section>
        </div>
      )}
    </dialog>
  );
}
