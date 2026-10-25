export interface RateLimitResult {
  allowed: boolean;
  /** Timestamps (ms) vigentes tras la decisión; incluye `nowMs` solo si se permite. */
  sends: number[];
  retryAfterSeconds?: number;
}

/** Ventana deslizante pura: un envío con edad >= `windowMs` ya no cuenta. */
export function checkRateLimit(sends: number[], nowMs: number, max: number, windowMs: number): RateLimitResult {
  const live = sends.filter((t) => nowMs - t < windowMs).sort((a, b) => a - b);
  if (live.length < max) return { allowed: true, sends: [...live, nowMs] };
  return { allowed: false, sends: live, retryAfterSeconds: Math.ceil((live[0] + windowMs - nowMs) / 1000) };
}
