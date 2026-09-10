---
name: frontend-dev
description: Guidance and recipes for developing the DevFlow React 19, TypeScript, and Vite frontend, including routing, components, TailwindCSS v4 styling, and backend API integration.
---

# Frontend Development Skill — DevFlow

This skill outlines guidelines, project structure, component templates, and commands for developing the DevFlow frontend web application.

---

## 1. Quick Reference & System Stack

- **Framework:** React 19
- **Bundler / Dev Server:** Vite 6.3
- **Language:** TypeScript 5.8
- **Routing:** React Router 7 (`react-router-dom`)
- **Styling:** TailwindCSS 4 (`@tailwindcss/vite`) + `clsx`
- **Linting:** ESLint 9
- **Local Dev Server Port:** `5173` (Vite dev server)
- **Production Port:** `3000` (via Docker Nginx container)

---

## 2. Standard NPM Commands

Run these commands inside the `frontend/` directory:

```bash
# Install node dependencies
npm install

# Start Vite development server (proxies /api to localhost:8080)
npm run dev

# Run TypeScript type-checking and build production bundle
npm run build

# Run ESLint check
npm run lint

# Preview the production build locally
npm run preview
```

---

## 3. Directory Layout

```
frontend/src/
├── api/             # API request functions (fetch / axios clients)
├── components/      # UI components
│   ├── common/      # Generic UI: Button, Badge, Modal, Input, Spinner
│   ├── board/       # Board specific: BoardView, Column, TaskCard, TaskModal
│   └── layout/      # AppShell, Navbar, Sidebar
├── hooks/           # Custom reusable hooks (e.g. useBoard, useTasks)
├── pages/           # Route-level views (e.g. BoardPage.tsx, AnalyticsPage.tsx)
├── types/           # TypeScript interfaces matching backend DTOs & models
├── App.tsx          # Router and root component setup
├── main.tsx         # DOM render root
└── index.css        # Global CSS & TailwindCSS v4 entrypoint
```

---

## 4. Development Recipes

### Recipe 1: Defining Shared TypeScript Types

Define models matching backend API DTOs in `src/types/`:

```typescript
// frontend/src/types/board.ts
export type TaskStatus = 'TODO' | 'IN_PROGRESS' | 'IN_REVIEW' | 'DONE';

export interface Task {
  id: string;
  columnId: string;
  title: string;
  description?: string;
  assigneeId?: string;
  status: TaskStatus;
  createdAt: string;
  updatedAt: string;
}

export interface Column {
  id: string;
  title: string;
  order: number;
  tasks: Task[];
}

export interface Board {
  id: string;
  name: string;
  description?: string;
  columns: Column[];
}
```

---

### Recipe 2: API Client Integration

Always use relative paths (`/api/v1/...`) so Vite proxy or Nginx handles routing to the backend:

```typescript
// frontend/src/api/boardApi.ts
import { Board, Task } from '../types/board';

const API_BASE = '/api/v1';

export async function fetchBoard(boardId: string): Promise<Board> {
  const res = await fetch(`${API_BASE}/boards/${boardId}`);
  if (!res.ok) {
    throw new Error(`Failed to fetch board: ${res.statusText}`);
  }
  return res.json();
}

export async function createTask(taskData: {
  columnId: string;
  title: string;
  description?: string;
}): Promise<Task> {
  const res = await fetch(`${API_BASE}/tasks`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(taskData),
  });
  if (!res.ok) {
    throw new Error(`Failed to create task: ${res.statusText}`);
  }
  return res.json();
}
```

---

### Recipe 3: Creating a Reusable Component with TailwindCSS v4

```tsx
// frontend/src/components/common/Badge.tsx
import React from 'react';
import clsx from 'clsx';

interface BadgeProps {
  variant?: 'info' | 'success' | 'warning' | 'danger';
  children: React.ReactNode;
  className?: string;
}

export const Badge: React.FC<BadgeProps> = ({
  variant = 'info',
  children,
  className,
}) => {
  const variantStyles = {
    info: 'bg-blue-50 text-blue-700 border-blue-200',
    success: 'bg-emerald-50 text-emerald-700 border-emerald-200',
    warning: 'bg-amber-50 text-amber-700 border-amber-200',
    danger: 'bg-rose-50 text-rose-700 border-rose-200',
  };

  return (
    <span
      className={clsx(
        'inline-flex items-center rounded-full border px-2.5 py-0.5 text-xs font-medium',
        variantStyles[variant],
        className
      )}
    >
      {children}
    </span>
  );
};
```

---

### Recipe 4: Adding Routes in React Router 7

In `frontend/src/App.tsx`:

```tsx
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { BoardPage } from './pages/BoardPage';
import { DashboardPage } from './pages/DashboardPage';

export function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<Navigate to="/boards" replace />} />
        <Route path="/boards" element={<BoardPage />} />
        <Route path="/dashboard" element={<DashboardPage />} />
      </Routes>
    </BrowserRouter>
  );
}
```

---

## 5. Pre-Commit Checklist for Frontend

- [ ] Run `npm run build` to ensure `tsc` passes without type errors.
- [ ] Run `npm run lint` and resolve any ESLint errors or unused imports.
- [ ] Ensure all API endpoints point to relative `/api/v1/...` (never hardcode `localhost:8080`).
- [ ] Test UI responsiveness on both desktop and mobile viewports.
