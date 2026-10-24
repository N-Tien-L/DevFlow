import type { PropsWithChildren, ReactNode, Ref } from 'react';

interface AuthLayoutProps {
  title: string;
  description?: ReactNode;
  footer?: ReactNode;
  /** Wider card for content such as the workspace list. */
  wide?: boolean;
  headingRef?: Ref<HTMLHeadingElement>;
}

export function AuthLayout({ title, description, footer, wide = false, headingRef, children }: PropsWithChildren<AuthLayoutProps>) {
  return (
    <main className="grid min-h-screen place-items-center bg-[radial-gradient(ellipse_at_top,_var(--tw-gradient-stops))] from-indigo-100 via-slate-50 to-white px-4 py-10 [color-scheme:light] sm:py-12 dark:from-indigo-950/60 dark:via-slate-950 dark:to-slate-950 dark:[color-scheme:dark]">
      <section className={`w-full ${wide ? 'max-w-2xl' : 'max-w-md'} rounded-3xl border border-slate-200 bg-white p-6 shadow-xl shadow-slate-200/50 sm:p-10 dark:border-slate-800 dark:bg-slate-900 dark:shadow-black/30`}>
        <div className="mb-7">
          <div className="mb-5 grid size-12 place-items-center rounded-2xl bg-indigo-600 text-lg font-black text-white" aria-hidden="true">D</div>
          <p className="text-sm font-semibold uppercase tracking-[0.18em] text-indigo-600 dark:text-indigo-400">DevFlow</p>
          <h1 ref={headingRef} tabIndex={-1} className="mt-2 text-2xl font-bold tracking-tight text-slate-950 outline-none sm:text-3xl dark:text-white">{title}</h1>
          {description && <div className="mt-2 text-sm leading-6 text-slate-500 dark:text-slate-400">{description}</div>}
        </div>
        {children}
        {footer && <div className="mt-6 border-t border-slate-100 pt-5 text-sm text-slate-500 dark:border-slate-800 dark:text-slate-400">{footer}</div>}
      </section>
    </main>
  );
}
