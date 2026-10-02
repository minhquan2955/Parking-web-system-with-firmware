"use client";
import { useCallback, useEffect, useRef, useState } from "react";
import { api, ApiError } from "./api";
export function useResource<T>(
  path: string | null,
  poll = false,
  continuePolling?: (value: T) => boolean,
) {
  const [data, setData] = useState<T | null>(null),
    [error, setError] = useState<ApiError | null>(null),
    [loading, setLoading] = useState(true),
    [version, setVersion] = useState(0);
  const predicate = useRef(continuePolling);
  predicate.current = continuePolling;
  const refresh = useCallback(() => setVersion((v) => v + 1), []);
  useEffect(() => {
    if (!path) {
      setLoading(false);
      return;
    }
    let stopped = false,
      busy = false,
      latest: T | null = null,
      timer: ReturnType<typeof setTimeout> | undefined;
    let controller: AbortController | null = null;
    const run = async () => {
      if (stopped || busy || document.hidden) return;
      busy = true;
      controller = new AbortController();
      try {
        const value = await api<T>(path, { signal: controller.signal });
        if (!stopped) {
          latest = value;
          setData(value);
          setError(null);
        }
      } catch (e) {
        if (!stopped && e instanceof Error && e.name !== "AbortError")
          setError(e as ApiError);
      } finally {
        busy = false;
        if (!stopped) {
          setLoading(false);
          if (
            poll &&
            (!latest || !predicate.current || predicate.current(latest))
          )
            timer = setTimeout(run, 3000);
        }
      }
    };
    const visible = () => {
      if (timer) clearTimeout(timer);
      if (!document.hidden) void run();
    };
    setLoading(true);
    void run();
    document.addEventListener("visibilitychange", visible);
    return () => {
      stopped = true;
      controller?.abort();
      if (timer) clearTimeout(timer);
      document.removeEventListener("visibilitychange", visible);
    };
  }, [path, poll, version]);
  return { data, error, loading, refresh };
}
