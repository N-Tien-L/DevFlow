import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { createTaskComment, getTaskDetail, listTaskComments, listWorkspaceMembers, updateTaskDetail } from '../../api/boardApi';
import { useAuth } from '../../auth/AuthProvider';
import { useTurnstileToken } from '../../hooks/useTurnstileToken';
import { ApiError, type TaskComment, type TaskCommentPage, type TaskDetail, type TaskPriority, type WorkspaceMember } from '../../types/board';

type Draft = { title: string; description: string; priority: TaskPriority; assigneeId: string; dueLocal: string };

const PRIORITIES: TaskPriority[] = ['LOW', 'MEDIUM', 'HIGH', 'URGENT'];
const PRIORITY_LABEL: Record<TaskPriority, string> = { LOW: 'Thấp', MEDIUM: 'Trung bình', HIGH: 'Cao', URGENT: 'Khẩn cấp' };

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
    if (reason.status === 403) return 'Bạn không có quyền thay đổi task này.';
    if (reason.status === 404) return 'Task không còn tồn tại hoặc bạn không có quyền xem.';
    if (reason.status === 409) return 'Board đang được cập nhật hoặc đã lưu trữ. Tải lại dữ liệu trước khi thử tiếp.';
    if (reason.status === 412) return 'Task đã được người khác cập nhật. Bản nháp của bạn vẫn được giữ.';
    if (reason.status === 413) return 'Nội dung vượt giới hạn máy chủ cho phép.';
    if (reason.status === 428) return 'Cần tải phiên bản mới nhất của task trước khi lưu.';
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
  const turnstile = useTurnstileToken();
  const dialogRef = useRef<HTMLDialogElement>(null);
  const commentInputRef = useRef<HTMLTextAreaElement>(null);
  const commentsListRef = useRef<HTMLUListElement>(null);
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

  // Open once and never close from an effect cleanup: under StrictMode the cleanup's close() queues a
  // `close` event that would land after the remount reopened the dialog. Unmounting removes the element,
  // which takes it out of the top layer anyway.
  useEffect(() => {
    if (!remoteDeleted) return;
    setRemoteUnavailable(true);
    setError('Task đã bị xóa hoặc bạn không còn quyền truy cập. Bản nháp đang mở sẽ không được gửi.');
  }, [remoteDeleted]);

  useEffect(() => {
    const dialog = dialogRef.current;
    if (dialog && !dialog.open) dialog.showModal();
  }, []);

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
          setError('Task đã được cập nhật ở nơi khác. Bản nháp của bạn vẫn được giữ.');
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
        setError('Task không còn tồn tại hoặc bạn không có quyền xem. Bản nháp đang mở sẽ không được gửi.');
      } else {
        setNotice('Không tải được phiên bản task mới. Nội dung đang sửa được giữ.');
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
    if (hasUnsaved) { setConfirmClose(true); return; }
    dialogRef.current?.close();
    onClose();
  }, [hasUnsaved, onClose, saving, sendingComment]);

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
      const token = await turnstile.getToken();
      const result = await updateTaskDetail(request, task, {
        title, description: draft.description || null, priority: draft.priority,
        assigneeId: draft.assigneeId || null, dueDate,
      }, token);
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
    setError('Bản mới nhất đã được tải. Các trường bạn đang sửa được giữ lại; kiểm tra rồi lưu lại.');
  };

  const sendComment = async () => {
    const content = commentDraft.trim();
    if (!content || [...content].length > 5_000 || sendingComment || !canEdit || remoteDeleted || remoteUnavailable) return;
    sendingCommentRef.current = true;
    setSendingComment(true); setCommentsError(null); setCommentNotice(null);
    try {
      const token = await turnstile.getToken();
      const saved = await createTaskComment(request, taskId, content, token);
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

  const handleCancel = (event: React.SyntheticEvent<HTMLDialogElement>) => { event.preventDefault(); requestClose(); };

  return <dialog ref={dialogRef} aria-labelledby="task-detail-title" onCancel={handleCancel}
    onClick={(event) => { if (event.target === event.currentTarget) requestClose(); }}
    className="m-auto max-h-[min(92dvh,900px)] w-[min(900px,calc(100vw-24px))] max-w-none overflow-hidden rounded-2xl bg-white p-0 text-slate-900 shadow-2xl backdrop:bg-slate-950/55">
    <div className="flex max-h-[92dvh] flex-col">
      <header className="flex items-start justify-between gap-4 border-b border-slate-200 px-5 py-4 sm:px-7">
        <div><p className="text-xs font-semibold uppercase tracking-wide text-indigo-600">Chi tiết công việc</p><h2 id="task-detail-title" className="mt-1 text-lg font-bold">{task?.title ?? 'Task'}</h2></div>
        <button type="button" onClick={requestClose} className="grid size-9 shrink-0 place-items-center rounded-lg text-slate-500 hover:bg-slate-100 focus:outline-none focus:ring-2 focus:ring-indigo-500" aria-label="Đóng cửa sổ chi tiết">×</button>
      </header>

      {loading && <div className="grid min-h-64 place-items-center text-sm text-slate-500" role="status">Đang tải chi tiết task…</div>}
      {!loading && loadingError && <div className="p-7"><p className="text-sm text-rose-700" role="alert">{loadingError}</p><button className="mt-4 rounded-lg bg-indigo-600 px-4 py-2 text-sm font-semibold text-white" onClick={() => void loadTask()}>Thử tải lại</button></div>}
      {!loading && task && draft && <form onSubmit={(event) => void saveTask(event)} className="min-h-0 overflow-y-auto">
        <div className="grid gap-7 p-5 sm:p-7 lg:grid-cols-[minmax(0,1fr)_260px]">
          <section className="min-w-0 space-y-5">
            <label className="block"><span className="mb-1.5 block text-sm font-semibold">Tiêu đề</span><input required maxLength={255} disabled={isArchived || saving} value={draft.title} onChange={(event) => setDraft({ ...draft, title: event.target.value })} className="w-full rounded-xl border border-slate-300 px-3.5 py-3 text-base font-semibold outline-none focus:border-indigo-500 focus:ring-4 focus:ring-indigo-100 disabled:bg-slate-100" /></label>
            <section>
              <div className="mb-2 flex items-center justify-between gap-3"><label htmlFor="task-description" className="text-sm font-semibold">Mô tả Markdown</label><div className="flex rounded-lg bg-slate-100 p-1"><button type="button" onClick={() => setPreview(false)} aria-pressed={!preview} className={`rounded-md px-3 py-1.5 text-xs font-semibold ${!preview ? 'bg-white shadow-sm' : 'text-slate-500'}`}>Viết</button><button type="button" onClick={() => setPreview(true)} aria-pressed={preview} className={`rounded-md px-3 py-1.5 text-xs font-semibold ${preview ? 'bg-white shadow-sm' : 'text-slate-500'}`}>Xem trước</button></div></div>
              {!preview ? <textarea id="task-description" rows={11} maxLength={10_000} disabled={isArchived || saving} value={draft.description} onChange={(event) => setDraft({ ...draft, description: event.target.value })} placeholder="Mô tả công việc… Hỗ trợ Markdown." className="w-full resize-y rounded-xl border border-slate-300 p-3 font-mono text-sm leading-6 outline-none focus:border-indigo-500 focus:ring-4 focus:ring-indigo-100 disabled:bg-slate-100" /> : <div className="markdown-preview min-h-64 rounded-xl border border-slate-200 bg-slate-50 p-4" aria-label="Xem trước mô tả Markdown"><ReactMarkdown remarkPlugins={[remarkGfm]} components={{ img: ({ alt }) => <span className="rounded bg-amber-50 px-1 text-amber-800">[Ảnh ngoài không được tải: {alt || 'không có mô tả'}]</span>, a: ({ href, children, ...props }) => <a {...props} href={href} target="_blank" rel="noopener noreferrer">{children}</a> }}>{draft.description || '*Chưa có mô tả.*'}</ReactMarkdown></div>}
              <p className="mt-1 text-right text-xs text-slate-400">{[...draft.description].length.toLocaleString('vi-VN')} / 10.000 ký tự</p>
            </section>
            <div className="rounded-xl border border-slate-200 bg-white p-4">
              <div className="flex items-center justify-between"><h3 className="text-sm font-bold">Bình luận</h3><button type="button" onClick={() => void refreshComments()} className="text-xs font-semibold text-indigo-700 underline">Tải lại</button></div>
              {commentsLoading && comments.length === 0 && <p className="mt-3 text-sm text-slate-500" role="status">Đang tải bình luận…</p>}
              {commentsError && <p className="mt-3 text-sm text-rose-700" role="alert">{commentsError}</p>}
              {!commentsLoading && !commentsError && comments.length === 0 && <p className="mt-3 text-sm text-slate-500">Chưa có bình luận.</p>}
              <ul ref={commentsListRef} className="mt-3 max-h-64 space-y-3 overflow-y-auto">{comments.map((comment: TaskComment) => <li key={comment.id} className="rounded-lg bg-slate-50 p-3"><div className="flex justify-between gap-3 text-xs"><span className="font-semibold text-slate-700">Thành viên {comment.authorId.slice(0, 8)}</span><time dateTime={comment.createdAt} className="shrink-0 text-slate-400">{new Intl.DateTimeFormat('vi-VN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(comment.createdAt))}</time></div><p className="mt-2 whitespace-pre-wrap break-words text-sm text-slate-700">{comment.content}</p></li>)}</ul>
              {commentPage + 1 < commentTotalPages && <button type="button" disabled={commentsLoading} onClick={() => void loadMoreComments()} className="mt-3 text-xs font-semibold text-indigo-700 underline">Tải bình luận cũ hơn</button>}
              {commentNotice && <p className="mt-3 text-xs text-indigo-700" role="status">{commentNotice}</p>}
              <label className="mt-4 block"><span className="mb-1.5 block text-xs font-semibold">Viết bình luận</span><textarea ref={commentInputRef} rows={3} maxLength={5000} disabled={isArchived || sendingComment} value={commentDraft} onChange={(event) => setCommentDraft(event.target.value)} placeholder="Nhập nội dung…" className="w-full rounded-lg border border-slate-300 p-3 text-sm outline-none focus:border-indigo-500 focus:ring-4 focus:ring-indigo-100 disabled:bg-slate-100" /></label>
            <div className="mt-2 flex items-center justify-between gap-3"><span className="text-xs text-slate-400">{[...commentDraft].length}/5.000</span><button type="button" onClick={() => void sendComment()} disabled={isArchived || sendingComment || !commentDraft.trim() || [...commentDraft.trim()].length > 5000 || !turnstile.enabled || !turnstile.ready} className="rounded-lg bg-indigo-600 px-3.5 py-2 text-xs font-semibold text-white disabled:cursor-not-allowed disabled:opacity-50">{sendingComment ? 'Đang gửi…' : 'Gửi bình luận'}</button></div>
            </div>
          </section>

          <aside className="space-y-4">
            <label className="block"><span className="mb-1.5 block text-sm font-semibold">Độ ưu tiên</span><select disabled={isArchived || saving} value={draft.priority} onChange={(event) => setDraft({ ...draft, priority: event.target.value as TaskPriority })} className="w-full rounded-lg border border-slate-300 bg-white px-3 py-2.5 text-sm disabled:bg-slate-100">{PRIORITIES.map((item) => <option key={item} value={item}>{PRIORITY_LABEL[item]}</option>)}</select></label>
            <label className="block"><span className="mb-1.5 block text-sm font-semibold">Người phụ trách</span><input type="search" disabled={isArchived || saving} value={memberQuery} onChange={(event) => setMemberQuery(event.target.value)} placeholder="Tìm thành viên…" className="mb-2 w-full rounded-lg border border-slate-300 px-3 py-2 text-sm disabled:bg-slate-100" /><select disabled={isArchived || saving || membersLoading && members.length === 0} value={draft.assigneeId} onChange={(event) => setDraft({ ...draft, assigneeId: event.target.value })} className="w-full rounded-lg border border-slate-300 bg-white px-3 py-2.5 text-sm disabled:bg-slate-100"><option value="">Chưa giao</option>{draft.assigneeId && !members.some((member) => member.userId === draft.assigneeId) && <option value={draft.assigneeId}>Thành viên {draft.assigneeId.slice(0, 8)} (đang được giao)</option>}{members.map((member) => <option key={member.userId} value={member.userId}>{member.fullName || `Thành viên ${member.userId.slice(0, 8)}`}</option>)}</select>{membersLoading && <span className="mt-1 block text-xs text-slate-500" role="status">Đang tải thành viên…</span>}{membersError && <span className="mt-1 block text-xs text-rose-700" role="alert">{membersError}</span>}{memberPage + 1 < memberTotalPages && <button type="button" disabled={membersLoading} onClick={() => void loadMoreMembers()} className="mt-1 text-xs font-semibold text-indigo-700 underline">Tải thêm thành viên</button>}</label>
            <label className="block"><span className="mb-1.5 block text-sm font-semibold">Hạn hoàn thành</span><input type="datetime-local" disabled={isArchived || saving} value={draft.dueLocal} onChange={(event) => setDraft({ ...draft, dueLocal: event.target.value })} className="w-full rounded-lg border border-slate-300 px-3 py-2.5 text-sm disabled:bg-slate-100" /><span className="mt-1 block text-xs text-slate-500">Múi giờ: {timezone}</span></label>
            <dl className="space-y-2 border-t border-slate-200 pt-4 text-xs text-slate-500"><div className="flex justify-between gap-2"><dt>Trạng thái</dt><dd>{task.statusCategory}</dd></div><div className="flex justify-between gap-2"><dt>Tạo lúc</dt><dd>{new Intl.DateTimeFormat('vi-VN', { dateStyle: 'medium' }).format(new Date(task.createdAt))}</dd></div><div className="flex justify-between gap-2"><dt>Cập nhật</dt><dd>{new Intl.DateTimeFormat('vi-VN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(task.updatedAt))}</dd></div></dl>
            {isArchived && <p className="rounded-lg bg-amber-50 p-3 text-xs text-amber-800">{remoteDeleted || remoteUnavailable ? 'Task không còn khả dụng. Bản nháp hiện có được giữ và không thể gửi.' : 'Board đang lưu trữ, đồng bộ hoặc mất kết nối. Bạn chỉ có thể xem task cho đến khi quyền sửa được xác nhận.'}</p>}
          </aside>
        </div>

        {error && <div className="mx-5 mb-3 rounded-lg bg-rose-50 p-3 text-sm text-rose-800 sm:mx-7" role="alert">{error}{remoteTask && <div className="mt-2 flex flex-wrap items-center gap-3"><span>Phiên bản mới nhất đã sẵn sàng.</span><button type="button" onClick={mergeRemote} className="font-semibold underline">Nạp bản mới và giữ phần đang sửa</button></div>}</div>}
        {notice && <p className="mx-5 mb-3 text-sm text-emerald-700 sm:mx-7" role="status">{notice}</p>}
        {turnstile.error && <p className="mx-5 mb-3 text-sm text-rose-700 sm:mx-7" role="alert">{turnstile.error}</p>}
        <footer className="sticky bottom-0 flex flex-wrap items-center justify-between gap-3 border-t border-slate-200 bg-white px-5 py-4 sm:px-7"><span className="text-xs text-slate-500">{hasUnsaved ? 'Có bản nháp chưa lưu' : isArchived ? 'Chỉ xem' : 'Đã lưu'}</span><div className="flex gap-2"><button type="button" onClick={requestClose} className="rounded-lg border border-slate-300 px-4 py-2 text-sm font-semibold">Đóng</button><button disabled={isArchived || !dirty || saving || !turnstile.enabled || !turnstile.ready} className="rounded-lg bg-indigo-600 px-4 py-2 text-sm font-semibold text-white disabled:cursor-not-allowed disabled:opacity-50">{saving ? 'Đang lưu…' : 'Lưu thay đổi'}</button></div></footer>
      </form>}
    </div>
    <div ref={turnstile.hostRef} className="fixed bottom-3 left-3 z-[1000]" />

    {confirmClose && <div className="fixed inset-0 z-[1001] grid place-items-center bg-slate-950/45 p-4" role="presentation"><section role="alertdialog" aria-modal="true" aria-labelledby="discard-title" className="w-full max-w-sm rounded-xl bg-white p-5 shadow-xl"><h3 id="discard-title" className="font-bold">Bỏ nội dung chưa lưu?</h3><p className="mt-2 text-sm text-slate-600">Thay đổi task hoặc bình luận đang viết sẽ bị mất.</p><div className="mt-5 flex justify-end gap-2"><button type="button" onClick={() => setConfirmClose(false)} className="rounded-lg border border-slate-300 px-3 py-2 text-sm font-semibold">Tiếp tục sửa</button><button type="button" onClick={() => { setConfirmClose(false); setDraft(task ? draftFrom(task) : null); setCommentDraft(''); dialogRef.current?.close(); onClose(); }} className="rounded-lg bg-rose-600 px-3 py-2 text-sm font-semibold text-white">Bỏ thay đổi</button></div></section></div>}
  </dialog>;
}
