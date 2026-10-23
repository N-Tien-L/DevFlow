import { useCallback, useEffect, useState } from 'react';

/** Whole seconds left until a server-imposed wait (Retry-After) or a client cooldown ends. */
export function useCountdown() {
  const [deadline, setDeadline] = useState<number | null>(null);
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    if (deadline === null) return;
    const timer = window.setInterval(() => {
      const current = Date.now();
      setNow(current);
      if (current >= deadline) setDeadline(null);
    }, 250);
    return () => window.clearInterval(timer);
  }, [deadline]);

  const start = useCallback((seconds: number) => {
    const current = Date.now();
    setNow(current);
    setDeadline(seconds > 0 ? current + seconds * 1000 : null);
  }, []);

  const remaining = deadline === null ? 0 : Math.max(0, Math.ceil((deadline - now) / 1000));
  return { remaining, active: remaining > 0, start };
}
