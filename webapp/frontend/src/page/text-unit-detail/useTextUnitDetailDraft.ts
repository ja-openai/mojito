import { type QueryClient, useQueryClient } from '@tanstack/react-query';
import { useCallback, useLayoutEffect, useRef, useState, useSyncExternalStore } from 'react';

import type { TextUnitFeedbackDraft } from '../../hooks/useTextUnitReviewFeedback';

type EditorStatus = 'Accepted' | 'To review' | 'To translate' | 'Rejected';
export type TextUnitDetailDraft = {
  seeded: boolean;
  source: string;
  messageFormat: string | null;
  seedKey: string | null;
  baselineTarget: string;
  baselineStatus: EditorStatus;
  baselineVariantId: number | null;
  baselineComment: string;
  draftTarget: string;
  draftStatus: EditorStatus;
  targetCommentDraft: string;
  isTargetCommentEditing: boolean;
  feedback: TextUnitFeedbackDraft;
  pendingOperation: string | null;
  operationError: string | null;
};

const emptyDraft = (): TextUnitDetailDraft => ({
  seeded: false,
  source: '',
  messageFormat: null,
  seedKey: null,
  baselineTarget: '',
  baselineStatus: 'To translate',
  baselineVariantId: null,
  baselineComment: '',
  draftTarget: '',
  draftStatus: 'To translate',
  targetCommentDraft: '',
  isTargetCommentEditing: false,
  feedback: { reason: '', note: '', chatUsed: false, aiSuggestionUsed: false },
  pendingOperation: null,
  operationError: null,
});

export function isTextUnitDetailDraftDirty(draft: TextUnitDetailDraft) {
  return (
    draft.draftTarget !== draft.baselineTarget ||
    draft.draftStatus !== draft.baselineStatus ||
    Boolean(draft.feedback.reason || draft.feedback.note) ||
    (draft.isTargetCommentEditing && draft.targetCommentDraft !== draft.baselineComment)
  );
}

const unloadGuards = new WeakMap<QueryClient, () => void>();
const activeEditors = new WeakMap<QueryClient, Map<string, number>>();
const prefix = ['text-unit-detail-draft'] as const;

function protectDrafts(client: QueryClient) {
  if (unloadGuards.has(client)) return;
  const hasDrafts = () =>
    client
      .getQueryCache()
      .findAll({ queryKey: prefix })
      .some((query) => {
        const draft = query.state.data as TextUnitDetailDraft | undefined;
        return draft && (isTextUnitDetailDraftDirty(draft) || draft.pendingOperation);
      });
  const preventUnload = (event: BeforeUnloadEvent) => {
    if (!hasDrafts()) return;
    event.preventDefault();
    event.returnValue = '';
  };
  const dispose = () => {
    window.removeEventListener('beforeunload', preventUnload);
    unsubscribe();
    unloadGuards.delete(client);
  };
  const unsubscribe = client.getQueryCache().subscribe(() => {
    if (!hasDrafts()) dispose();
  });
  unloadGuards.set(client, dispose);
  window.addEventListener('beforeunload', preventUnload);
}

/** Embedded editors share one in-memory session across close, selection and app navigation. */
export function useTextUnitDetailDraft(owner: string | null) {
  const client = useQueryClient();
  const initial = useRef(emptyDraft());
  const [local, setLocal] = useState(initial.current);
  const localRef = useRef(local);
  localRef.current = local;
  const read = useCallback(
    () =>
      owner
        ? (client.getQueryData<TextUnitDetailDraft>([...prefix, owner]) ?? initial.current)
        : localRef.current,
    [client, owner],
  );
  const subscribe = useCallback(
    (notify: () => void) =>
      owner
        ? client.getQueryCache().subscribe((event) => {
            const key = event.query.queryKey as readonly unknown[];
            // Unrelated queries can be created during render. Only our draft may
            // notify this external store, including when another editor owns the write.
            if (key.length === 2 && key[0] === prefix[0] && key[1] === owner) notify();
          })
        : () => undefined,
    [client, owner],
  );
  const retained = useSyncExternalStore(subscribe, read, read);
  const draft = owner ? retained : local;
  const update = useCallback(
    (change: (current: TextUnitDetailDraft) => TextUnitDetailDraft) => {
      if (!owner) {
        setLocal(change);
        return;
      }
      const next = change(read());
      client.setQueryDefaults(prefix, { gcTime: Infinity });
      client.setQueryData([...prefix, owner], next);
      if (isTextUnitDetailDraftDirty(next) || next.pendingOperation) protectDrafts(client);
      else if (!activeEditors.get(client)?.get(owner)) {
        client.removeQueries({ queryKey: [...prefix, owner], exact: true });
      }
    },
    [client, owner, read],
  );

  useLayoutEffect(() => {
    if (!owner) return;
    const owners = activeEditors.get(client) ?? new Map<string, number>();
    activeEditors.set(client, owners);
    owners.set(owner, (owners.get(owner) ?? 0) + 1);
    return () => {
      const count = (owners.get(owner) ?? 1) - 1;
      if (count) owners.set(owner, count);
      else owners.delete(owner);
      const current = read();
      if (!count && !isTextUnitDetailDraftDirty(current) && !current.pendingOperation) {
        client.removeQueries({ queryKey: [...prefix, owner], exact: true });
      }
    };
  }, [client, owner, read]);

  const setField = useCallback(
    <K extends keyof TextUnitDetailDraft>(field: K, value: TextUnitDetailDraft[K]) =>
      update((current) => ({ ...current, [field]: value })),
    [update],
  );
  const beginOperation = () => {
    const snapshot = read();
    const id = crypto.randomUUID();
    update((current) => ({ ...current, pendingOperation: id, operationError: null }));
    return { id, snapshot };
  };
  const finishOperation = (
    operation: ReturnType<typeof beginOperation>,
    change: (current: TextUnitDetailDraft) => TextUnitDetailDraft,
  ) => {
    update((current) => {
      if (current.pendingOperation !== operation.id) return current;
      return { ...change(current), pendingOperation: null };
    });
  };
  return { draft, update, setField, beginOperation, finishOperation };
}
