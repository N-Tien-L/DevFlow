import type { PropsWithChildren, ReactNode, Ref } from 'react';
import clsx from 'clsx';
import { BrandMark } from '../common/Icons';

interface AuthLayoutProps {
  title: string;
  description?: ReactNode;
  footer?: ReactNode;
  /** Optional top-right action slot inside the card header (e.g. "Tạo workspace" CTA). */
  headerAction?: ReactNode;
  /** Wider card for content such as the workspace list. */
  wide?: boolean;
  headingRef?: Ref<HTMLHeadingElement>;
}

export function AuthLayout({
  title,
  description,
  footer,
  headerAction,
  wide = false,
  headingRef,
  children,
}: PropsWithChildren<AuthLayoutProps>) {
  return (
    <main className="bg-devflow-grid bg-devflow-grid-adaptive flex min-h-screen w-full flex-col items-center justify-center overflow-x-clip bg-slate-50 px-3.5 py-7 [color-scheme:light] sm:px-6 sm:py-12 dark:bg-slate-950 dark:[color-scheme:dark]">
      <div className={clsx('w-full min-w-0', wide ? 'max-w-3xl' : 'max-w-[448px]')}>
        <div className="mb-5 flex flex-wrap items-center justify-between gap-2 px-1">
          <div className="inline-flex items-center gap-2.5">
            <BrandMark size="md" />
            <div className="min-w-0">
              <span className="font-display block text-base font-extrabold tracking-tight text-slate-950 dark:text-white">
                DevFlow
              </span>
              <span className="block text-[11px] font-medium text-slate-500 dark:text-slate-400">
                Điều phối công việc cho đội phát triển
              </span>
            </div>
          </div>
          <span className="hidden items-center gap-1.5 rounded-full border border-indigo-200/80 bg-white/80 px-2.5 py-1 text-[11px] font-semibold text-indigo-700 shadow-2xs backdrop-blur-xs sm:inline-flex dark:border-indigo-500/30 dark:bg-slate-900/80 dark:text-indigo-300">
            <span className="size-1.5 rounded-full bg-indigo-500" aria-hidden="true" />
            Kanban · Git/CI · AI
          </span>
        </div>

        <section className="w-full min-w-0 rounded-2xl border border-slate-200/90 bg-white/95 p-5 shadow-[0_20px_50px_-24px_rgba(15,23,42,0.18)] backdrop-blur-sm sm:rounded-3xl sm:p-8 dark:border-slate-800 dark:bg-slate-900/95 dark:shadow-black/40">
          <header className="mb-6 flex flex-wrap items-start justify-between gap-4">
            <div className="min-w-0 flex-1">
              <h1
                ref={headingRef}
                tabIndex={-1}
                className="text-2xl font-bold tracking-tight text-slate-950 outline-none sm:text-[28px] sm:leading-9 dark:text-white"
              >
                {title}
              </h1>
              {description && (
                <div className="mt-2 text-sm leading-6 text-slate-600 dark:text-slate-400">
                  {description}
                </div>
              )}
            </div>
            {headerAction && <div className="shrink-0">{headerAction}</div>}
          </header>

          <div className="min-w-0">{children}</div>

          {footer && (
            <footer className="mt-6 border-t border-slate-200/80 pt-5 text-sm text-slate-600 dark:border-slate-800 dark:text-slate-400">
              {footer}
            </footer>
          )}
        </section>
      </div>
    </main>
  );
}
