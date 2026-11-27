import { useEffect, useState } from 'react';
import { matchPath } from 'react-router-dom';
import { getBoard, listBoards, listWorkspaces, type ApiRequest } from '../api/boardApi';
import type { ColumnResponse } from '../types/board';

export interface ChatBoardContext {
  workspaceId: string | null;
  workspaceName: string | null;
  boardId: string | null;
  boardName: string | null;
  /** Columns of the board new tasks go to, in board order. */
  columns: ColumnResponse[];
  loading: boolean;
  failed: boolean;
}

const EMPTY: ChatBoardContext = {
  workspaceId: null,
  workspaceName: null,
  boardId: null,
  boardName: null,
  columns: [],
  loading: false,
  failed: false,
};

/**
 * Workspace and board the chat talks about. The panel is mounted outside `<Routes>`, so the board is read
 * from the path: on `/boards/:boardId` it is that board (and its workspace); elsewhere it is the first
 * active board of `?workspace=` or of the first workspace. Reloaded each time the panel opens, so renamed
 * or added columns show up.
 */
export function useChatBoardContext(request: ApiRequest, active: boolean, pathname: string, search: string) {
  const [context, setContext] = useState<ChatBoardContext>(EMPTY);
  const routeBoardId = matchPath('/boards/:boardId', pathname)?.params.boardId ?? null;
  const requestedWorkspaceId = new URLSearchParams(search).get('workspace');

  useEffect(() => {
    if (!active) return undefined;
    const controller = new AbortController();
    let cancelled = false;
    setContext((previous) => ({ ...previous, loading: true, failed: false }));

    const load = async (): Promise<ChatBoardContext> => {
      const workspaces = await listWorkspaces(request);
      if (routeBoardId) {
        const board = await getBoard(request, routeBoardId, controller.signal);
        const workspace = workspaces.find((item) => item.id === board.workspaceId);
        return {
          ...EMPTY,
          workspaceId: board.workspaceId,
          workspaceName: workspace?.name ?? null,
          boardId: board.id,
          boardName: board.name,
          columns: [...board.columns].sort((a, b) => a.position - b.position),
        };
      }
      const workspace = workspaces.find((item) => item.id === requestedWorkspaceId) ?? workspaces[0];
      if (!workspace) return EMPTY;
      const boards = await listBoards(request, workspace.id);
      const firstBoard = boards.items.find((board) => !board.archived);
      if (!firstBoard) {
        return { ...EMPTY, workspaceId: workspace.id, workspaceName: workspace.name };
      }
      const board = await getBoard(request, firstBoard.id, controller.signal);
      return {
        ...EMPTY,
        workspaceId: workspace.id,
        workspaceName: workspace.name,
        boardId: board.id,
        boardName: board.name,
        columns: [...board.columns].sort((a, b) => a.position - b.position),
      };
    };

    load()
      .then((next) => {
        if (!cancelled) setContext(next);
      })
      .catch(() => {
        if (!cancelled) setContext((previous) => ({ ...previous, loading: false, failed: true }));
      });

    return () => {
      cancelled = true;
      controller.abort();
    };
  }, [active, request, routeBoardId, requestedWorkspaceId]);

  return context;
}
