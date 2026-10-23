import { useCallback, useEffect, useState } from "react";
import { request, type Reply } from "./api";

export function useRemote<T>(path: string | null) {
  const [tick, setTick] = useState(0);
  const [state, setState] = useState<{
    path: string | null;
    loading: boolean;
    reply?: Reply<T>;
    error?: unknown;
  }>({ path, loading: true });
  const reload = useCallback(() => setTick((value) => value + 1), []);
  useEffect(() => {
    if (!path) {
      setState({ path, loading: false });
      return;
    }
    const controller = new AbortController();
    setState({ path, loading: true });
    request<T>(path, { signal: controller.signal })
      .then((reply) => setState({ path, loading: false, reply }))
      .catch((error) => {
        if (!controller.signal.aborted)
          setState({ path, loading: false, error });
      });
    return () => controller.abort();
  }, [path, tick]);
  const current = state.path === path ? state : { path, loading: true };
  return {
    data: current.reply?.data,
    etag: current.reply?.etag,
    loading: current.loading,
    error: current.error,
    reload,
  };
}

export function useHashRoute() {
  const [path, setPath] = useState(location.hash.slice(1) || "/");
  useEffect(() => {
    const update = () => setPath(location.hash.slice(1) || "/");
    addEventListener("hashchange", update);
    return () => removeEventListener("hashchange", update);
  }, []);
  return path;
}
export const navigate = (path: string) => {
  location.hash = path;
};
