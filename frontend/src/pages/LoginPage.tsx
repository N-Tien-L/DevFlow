import { useState } from 'react';
import { useAuth } from '../auth/AuthProvider';
import { ApiError } from '../types/board';

export default function LoginPage() {
  const { signIn } = useAuth();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setIsLoading(true);
    setError(null);
    try {
      await signIn(email, password);
    } catch (reason) {
      setError(reason instanceof ApiError ? reason.message : 'Đăng nhập thất bại. Vui lòng thử lại.');
    } finally {
      setIsLoading(false);
    }
  }

  return (
    <main className="grid min-h-screen place-items-center bg-[radial-gradient(ellipse_at_top,_var(--tw-gradient-stops))] from-indigo-100 via-slate-50 to-white px-4 py-12">
      <section className="w-full max-w-md rounded-3xl border border-slate-200 bg-white p-8 shadow-xl shadow-slate-200/50 sm:p-10">
        <div className="mb-8">
          <div className="mb-5 grid size-12 place-items-center rounded-2xl bg-indigo-600 text-lg font-black text-white">D</div>
          <p className="text-sm font-semibold uppercase tracking-[0.18em] text-indigo-600">DevFlow</p>
          <h1 className="mt-2 text-3xl font-bold tracking-tight text-slate-950">Chào mừng trở lại</h1>
          <p className="mt-2 text-sm leading-6 text-slate-500">Đăng nhập để tiếp tục quản lý board của nhóm.</p>
        </div>

        <form className="space-y-5" onSubmit={handleSubmit}>
          <label className="block text-sm font-medium text-slate-700">
            Email
            <input
              autoComplete="username"
              className="mt-2 w-full rounded-xl border border-slate-300 px-4 py-3 text-slate-900 outline-none transition focus:border-indigo-500 focus:ring-4 focus:ring-indigo-100"
              type="email"
              value={email}
              onChange={(event) => setEmail(event.target.value)}
              required
            />
          </label>
          <label className="block text-sm font-medium text-slate-700">
            Mật khẩu
            <input
              autoComplete="current-password"
              className="mt-2 w-full rounded-xl border border-slate-300 px-4 py-3 text-slate-900 outline-none transition focus:border-indigo-500 focus:ring-4 focus:ring-indigo-100"
              type="password"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              required
            />
          </label>
          {error && <p className="rounded-xl bg-rose-50 px-4 py-3 text-sm text-rose-700" role="alert">{error}</p>}
          <button
            className="w-full rounded-xl bg-indigo-600 px-4 py-3 font-semibold text-white shadow-lg shadow-indigo-600/20 transition hover:bg-indigo-700 focus:outline-none focus:ring-4 focus:ring-indigo-200 disabled:cursor-wait disabled:opacity-60"
            type="submit"
            disabled={isLoading}
          >
            {isLoading ? 'Đang đăng nhập…' : 'Đăng nhập'}
          </button>
        </form>
        <p className="mt-6 text-xs leading-5 text-slate-400">Phiên đăng nhập được lưu trong tab hiện tại và xóa khi đăng xuất.</p>
      </section>
    </main>
  );
}
