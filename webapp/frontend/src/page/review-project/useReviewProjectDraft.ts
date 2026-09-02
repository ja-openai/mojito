import { type QueryClient, useQueryClient } from '@tanstack/react-query';
import { useCallback, useLayoutEffect, useRef, useState } from 'react';

export type ReviewProjectDraftStatus =
  | 'ACCEPTED'
  | 'NEEDS_REVIEW'
  | 'NEEDS_TRANSLATION'
  | 'REJECTED';

export type ReviewProjectDecisionSnapshot = {
  tmTextUnitId: number | null;
  source: string | null;
  messageFormat: string | null | undefined;
  expectedCurrentVariantId: number | null;
  reviewStateRevision: string | null;
  target: string;
  comment: string | null;
  decisionNotes: string | null;
  statusChoice: ReviewProjectDraftStatus;
  decisionState: 'PENDING' | 'DECIDED';
  suggestionSourceLabel: string | null;
};

export type ReviewProjectDraftValues = {
  target: string;
  statusChoice: ReviewProjectDraftStatus;
  comment: string;
  decisionNotes: string;
};

type DraftSession = {
  owner: object;
  textUnitId: number;
  revision: number;
  base: ReviewProjectDecisionSnapshot;
  remote: ReviewProjectDecisionSnapshot;
  observed: ReviewProjectDecisionSnapshot;
  values: ReviewProjectDraftValues;
  operation: {
    id: number;
    values: ReviewProjectDraftValues;
    discardValues?: ReviewProjectDraftValues;
  } | null;
};

const retainedDraftUnloadGuards = new WeakMap<QueryClient, () => void>();

export function protectRetainedDrafts(queryClient: QueryClient) {
  if (retainedDraftUnloadGuards.has(queryClient)) return;
  const cache = queryClient.getQueryCache();
  const hasDrafts = () => cache.findAll({ queryKey: ['review-project-draft'] }).length > 0;
  const beforeUnload = (event: BeforeUnloadEvent) => {
    if (!hasDrafts()) return;
    event.preventDefault();
    event.returnValue = '';
  };
  const dispose = () => {
    window.removeEventListener('beforeunload', beforeUnload);
    unsubscribe();
    retainedDraftUnloadGuards.delete(queryClient);
  };
  const unsubscribe = cache.subscribe(() => {
    if (!hasDrafts()) dispose();
  });
  retainedDraftUnloadGuards.set(queryClient, dispose);
  window.addEventListener('beforeunload', beforeUnload);
}

function valuesFromSnapshot(snapshot: ReviewProjectDecisionSnapshot): ReviewProjectDraftValues {
  return {
    target: snapshot.target,
    statusChoice: snapshot.statusChoice,
    comment: snapshot.comment ?? '',
    decisionNotes: snapshot.decisionNotes ?? '',
  };
}

function sameValues(left: ReviewProjectDraftValues, right: ReviewProjectDraftValues) {
  return (
    left.target === right.target &&
    left.statusChoice === right.statusChoice &&
    left.comment === right.comment &&
    left.decisionNotes === right.decisionNotes
  );
}

function sameSnapshot(left: ReviewProjectDecisionSnapshot, right: ReviewProjectDecisionSnapshot) {
  return (
    sameReviewProjectSource(left, right) &&
    sameValues(valuesFromSnapshot(left), valuesFromSnapshot(right)) &&
    left.expectedCurrentVariantId === right.expectedCurrentVariantId &&
    left.reviewStateRevision === right.reviewStateRevision &&
    left.decisionState === right.decisionState &&
    left.suggestionSourceLabel === right.suggestionSourceLabel
  );
}

export function sameReviewProjectSource(
  left: ReviewProjectDecisionSnapshot,
  right: ReviewProjectDecisionSnapshot,
) {
  return (
    left.tmTextUnitId === right.tmTextUnitId &&
    left.source === right.source &&
    left.messageFormat === right.messageFormat
  );
}

function createSession(textUnitId: number, snapshot: ReviewProjectDecisionSnapshot): DraftSession {
  return {
    owner: {},
    textUnitId,
    revision: 0,
    base: snapshot,
    remote: snapshot,
    observed: snapshot,
    values: valuesFromSnapshot(snapshot),
    operation: null,
  };
}

function isDirty(session: DraftSession) {
  return !sameValues(session.values, valuesFromSnapshot(session.base));
}

function receiveSnapshot(
  session: DraftSession,
  textUnitId: number,
  snapshot: ReviewProjectDecisionSnapshot,
): DraftSession {
  if (session.textUnitId !== textUnitId) return createSession(textUnitId, snapshot);
  if (sameSnapshot(session.observed, snapshot)) return session;
  if (session.operation || isDirty(session)) {
    return { ...session, revision: session.revision + 1, remote: snapshot, observed: snapshot };
  }
  return {
    ...session,
    revision: session.revision + 1,
    base: snapshot,
    remote: snapshot,
    observed: snapshot,
    values: valuesFromSnapshot(snapshot),
  };
}

/** Owns the draft and the exact server version it was based on as one editing session. */
export function useReviewProjectDraft(
  username: string,
  projectId: number,
  textUnitId: number,
  snapshot: ReviewProjectDecisionSnapshot,
) {
  const queryClient = useQueryClient();
  const cacheKey = ['review-project-draft', username, projectId, textUnitId] as const;
  type RetainedDraft = Pick<DraftSession, 'base' | 'values'>;
  const [storedSession, setStoredSession] = useState(() => {
    const initial = createSession(textUnitId, snapshot);
    const retained = queryClient.getQueryData<RetainedDraft>(cacheKey);
    if (!retained) return initial;
    const restored = { ...initial, ...retained };
    // An untouched draft can be retained only because a request was pending when
    // the page unmounted. There are no local edits to protect on return: use the
    // currently loaded server row, which may include that save or a later edit.
    return isDirty(restored) ? restored : initial;
  });
  const session = receiveSnapshot(storedSession, textUnitId, snapshot);
  if (session !== storedSession) {
    // Adjust before committing children, never in a later effect that can erase edits
    // or expose a new row/version alongside an old draft.
    setStoredSession(session);
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
    (current: DraftSession) => {
      const key = ['review-project-draft', username, projectId, textUnitId] as const;
      if (isDirty(current) || current.operation) {
        // Keep unsaved work for this signed-in user across same-app navigation. This is
        // intentionally in-memory; a reload remains guarded by beforeunload.
        queryClient.setQueryDefaults(['review-project-draft'], { gcTime: Infinity });
        queryClient.setQueryData<RetainedDraft>(key, {
          base: current.base,
          values: current.values,
        });
        protectRetainedDrafts(queryClient);
      } else {
        queryClient.removeQueries({ queryKey: key, exact: true });
      }
    },
    [projectId, queryClient, textUnitId, username],
  );
  useLayoutEffect(() => {
    if (
      currentRef.current.owner !== session.owner ||
      currentRef.current.revision <= session.revision
    ) {
      currentRef.current = session;
    }
    retain(currentRef.current);
  }, [retain, session]);

  const update = useCallback(
    (transition: (current: DraftSession) => DraftSession) => {
      const current = currentRef.current;
      if (!mountedRef.current || current.owner !== session.owner) return;
      const next = transition(current);
      if (next === current) return;
      currentRef.current = next;
      retain(next);
      setStoredSession(next);
    },
    [retain, session.owner],
  );

  const read = useCallback(
    () =>
      mountedRef.current && currentRef.current.owner === session.owner ? currentRef.current : null,
    [session.owner],
  );
  const updateValues = useCallback(
    (change: (values: ReviewProjectDraftValues) => ReviewProjectDraftValues) => {
      update((current) => ({
        ...current,
        revision: current.revision + 1,
        values: change(current.values),
      }));
    },
    [update],
  );
  const startOperation = useCallback(
    (operationId: number) => {
      update((current) => ({
        ...current,
        revision: current.revision + 1,
        operation: { id: operationId, values: current.values },
      }));
    },
    [update],
  );
  const confirmOperationDiscard = useCallback(
    (operationId: number) => {
      update((current) => {
        if (current.operation?.id !== operationId) return current;
        return {
          ...current,
          revision: current.revision + 1,
          // Use external discards the values visible when it is requested. Keep
          // the original submitted values separately if recovery conflicts again
          // and the reviewer then chooses Use mine.
          operation: { ...current.operation, discardValues: current.values },
        };
      });
    },
    [update],
  );
  const finishOperation = useCallback(
    (
      operationId: number,
      saved: ReviewProjectDecisionSnapshot,
      discard: boolean,
      preserveValues: boolean,
    ) => {
      update((current) => {
        if (current.operation?.id !== operationId) return current;
        const unchanged = sameValues(current.values, current.operation.values);
        const savedValues = valuesFromSnapshot(saved);
        const discarded = current.operation.discardValues;
        // A choice to use the external row replaces only the fields that have
        // not been edited since that choice. A new note must not restore the old
        // translation, and a new translation must not lose its accompanying note.
        const values =
          discard && discarded
            ? {
                target:
                  current.values.target === discarded.target
                    ? savedValues.target
                    : current.values.target,
                statusChoice:
                  current.values.statusChoice === discarded.statusChoice
                    ? savedValues.statusChoice
                    : current.values.statusChoice,
                comment:
                  current.values.comment === discarded.comment
                    ? savedValues.comment
                    : current.values.comment,
                decisionNotes:
                  current.values.decisionNotes === discarded.decisionNotes
                    ? savedValues.decisionNotes
                    : current.values.decisionNotes,
              }
            : !discard && !preserveValues && unchanged
              ? savedValues
              : current.values;
        return {
          ...current,
          revision: current.revision + 1,
          base: saved,
          // The acknowledgement is immediately available to Reset. Keep the last
          // observed props separately so an older render cannot roll it back.
          remote: saved,
          values,
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
            base: isDirty(current) ? current.base : current.remote,
            values: isDirty(current) ? current.values : valuesFromSnapshot(current.remote),
            operation: null,
          },
    );
  }, [update]);
  const reset = useCallback(() => {
    update((current) => ({
      ...current,
      revision: current.revision + 1,
      base: current.remote,
      values: valuesFromSnapshot(current.remote),
      operation: null,
    }));
  }, [update]);

  return {
    session,
    dirty: isDirty(session),
    sourceChanged: !sameReviewProjectSource(session.base, session.remote),
    read,
    updateValues,
    startOperation,
    confirmOperationDiscard,
    finishOperation,
    cancelOperation,
    reset,
  };
}
