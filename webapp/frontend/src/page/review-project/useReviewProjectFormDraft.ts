import { useQueryClient } from '@tanstack/react-query';
import { useCallback, useLayoutEffect, useRef, useState } from 'react';

import { protectRetainedDrafts } from './useReviewProjectDraft';

type FormSession<T> = {
  owner: object;
  revision: number;
  base: T;
  remote: T;
  observed: T;
  values: T;
  operation: { id: number; values: T } | null;
};

function sameValues<T extends object>(left: T, right: T) {
  const keys = Object.keys(left) as Array<keyof T>;
  return keys.length === Object.keys(right).length && keys.every((key) => left[key] === right[key]);
}

/** Retains a flat terminology form and acknowledges only the operation that submitted it. */
export function useReviewProjectFormDraft<T extends object>(
  username: string,
  projectId: number,
  textUnitId: number,
  form: 'feedback' | 'resolution' | 'metadata',
  snapshot: T,
) {
  const queryClient = useQueryClient();
  const [stored, setStored] = useState<FormSession<T>>(() => {
    const initial: FormSession<T> = {
      owner: {},
      revision: 0,
      base: snapshot,
      remote: snapshot,
      observed: snapshot,
      values: snapshot,
      operation: null,
    };
    const retained = queryClient.getQueryData<Pick<FormSession<T>, 'base' | 'values'>>([
      'review-project-draft',
      username,
      projectId,
      textUnitId,
      form,
    ]);
    return retained && !sameValues(retained.base, retained.values)
      ? { ...initial, ...retained }
      : initial;
  });
  let session = stored;
  if (!sameValues(stored.observed, snapshot)) {
    const preserve = stored.operation != null || !sameValues(stored.base, stored.values);
    session = {
      ...stored,
      revision: stored.revision + 1,
      observed: snapshot,
      remote: snapshot,
      ...(preserve ? {} : { base: snapshot, values: snapshot }),
    };
    setStored(session);
  }
  const currentRef = useRef(session);
  const mountedRef = useRef(true);
  useLayoutEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);
  const retain = useCallback(
    (current: FormSession<T>) => {
      const queryKey = ['review-project-draft', username, projectId, textUnitId, form];
      if (current.operation || !sameValues(current.base, current.values)) {
        queryClient.setQueryDefaults(['review-project-draft'], { gcTime: Infinity });
        queryClient.setQueryData(queryKey, { base: current.base, values: current.values });
        protectRetainedDrafts(queryClient);
      } else {
        queryClient.removeQueries({ queryKey, exact: true });
      }
    },
    [form, projectId, queryClient, textUnitId, username],
  );
  useLayoutEffect(() => {
    if (currentRef.current.revision <= session.revision) currentRef.current = session;
    retain(currentRef.current);
  }, [retain, session]);
  const read = useCallback(
    () =>
      mountedRef.current && currentRef.current.owner === session.owner ? currentRef.current : null,
    [session.owner],
  );
  const update = useCallback(
    (transition: (current: FormSession<T>) => FormSession<T>) => {
      const current = read();
      if (!current) return;
      const next = transition(current);
      if (next === current) return;
      currentRef.current = next;
      retain(next);
      setStored(next);
    },
    [read, retain],
  );
  const updateValues = useCallback(
    (change: (values: T) => T) => {
      update((current) => ({
        ...current,
        revision: current.revision + 1,
        values: change(current.values),
      }));
    },
    [update],
  );
  const startOperation = useCallback(
    (id: number) => {
      update((current) => ({
        ...current,
        revision: current.revision + 1,
        operation: { id, values: current.values },
      }));
    },
    [update],
  );
  const finishOperation = useCallback(
    (id: number, saved: T) => {
      update((current) => {
        if (current.operation?.id !== id) return current;
        return {
          ...current,
          revision: current.revision + 1,
          base: saved,
          remote: saved,
          values: sameValues(current.values, current.operation.values) ? saved : current.values,
          operation: null,
        };
      });
    },
    [update],
  );
  const cancelOperation = useCallback(() => {
    update((current) =>
      current.operation == null
        ? current
        : {
            ...current,
            revision: current.revision + 1,
            ...(!sameValues(current.base, current.values)
              ? {}
              : { base: current.remote, values: current.remote }),
            operation: null,
          },
    );
  }, [update]);
  const reset = useCallback(() => {
    update((current) => ({
      ...current,
      revision: current.revision + 1,
      base: current.remote,
      values: current.remote,
      operation: null,
    }));
  }, [update]);
  const isDirty = useCallback(() => {
    const current = read();
    return current != null && !sameValues(current.base, current.values);
  }, [read]);
  return {
    values: session.values,
    base: session.base,
    dirty: !sameValues(session.base, session.values),
    read,
    updateValues,
    startOperation,
    finishOperation,
    cancelOperation,
    reset,
    isDirty,
  };
}
