import { useCallback, useEffect, useRef, useState } from 'react';
import { AdminApiError, adminGet } from '../../lib/admin';
import { activeRun, completedSince, type Run } from './runStatus';
import { hubFailure, type HubFailure } from './hubError';

export function useHubRuns(connectionId: string, refresh: number) {
  const [runs, setRuns] = useState<Run[] | null>(null);
  const [error, setError] = useState<HubFailure | null>(null);
  const [revision, setRevision] = useState(0);
  const [completed, setCompleted] = useState(0);
  const [observedAt, setObservedAt] = useState(0);
  const previous = useRef<Map<string, string> | null>(null);
  const reload = useCallback(() => setRevision(value => value + 1), []);
  const accept = useCallback((run: Run) => {
    previous.current = new Map(previous.current).set(run.id, run.status);
    setRuns(values => [run, ...(values || []).filter(value => value.id !== run.id)]);
    setRevision(value => value + 1);
  }, []);
  useEffect(() => {
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout>;
    async function poll() {
      let delay = 10000;
      try {
        const { data } = await adminGet<Run[]>(`/connections/${connectionId}/sync-runs`, controller.signal);
        if (controller.signal.aborted) return;
        if (completedSince(previous.current, data)) setCompleted(value => value + 1);
        previous.current = new Map(data.map(run => [run.id, run.status]));
        setRuns(data); setError(null); setObservedAt(Date.now());
        if (data.some(activeRun)) delay = 2000;
      } catch (reason) {
        if (controller.signal.aborted) return;
        setError(hubFailure(reason));
        if (reason instanceof AdminApiError && [401, 403].includes(reason.status)) return;
      }
      if (!controller.signal.aborted) timer = setTimeout(() => void poll(), delay);
    }
    void poll();
    return () => { controller.abort(); clearTimeout(timer); };
  }, [connectionId, refresh, revision]);
  return { runs, error, reload, accept, completed, observedAt };
}
