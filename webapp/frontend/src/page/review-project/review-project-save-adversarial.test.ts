import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, cleanup, renderHook, waitFor } from '@testing-library/react';
import { createElement, type ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type { ApiReviewProjectDetail, ApiReviewProjectTextUnit } from '../../api/review-projects';
import type { ApiUserProfile } from '../../api/users';
import { REVIEW_PROJECT_DETAIL_QUERY_KEY } from '../../hooks/useReviewProjectDetail';
import { UserContext } from '../../hooks/useUser';
import type * as IntegrityCheckModule from '../../utils/integrityCheck';
import { type SaveDecisionRequest, useReviewProjectMutations } from './review-project-mutations';

const integrityCheck = vi.hoisted(() => vi.fn());
vi.mock('../../utils/integrityCheck', async (importOriginal) => ({
  ...(await importOriginal<typeof IntegrityCheckModule>()),
  checkTextUnitIntegrityWithRetry: integrityCheck,
}));

const translator: ApiUserProfile = {
  username: 'protocol-reviewer',
  role: 'ROLE_TRANSLATOR',
  canTranslateAllLocales: true,
  userLocales: [],
};
const makeRequest = (): SaveDecisionRequest => ({
  textUnitId: 7101,
  tmTextUnitId: 7201,
  target: 'Saved translation',
  comment: 'Saved comment',
  status: 'APPROVED',
  includedInLocalizedFile: true,
  decisionState: 'DECIDED',
  decisionNotes: 'Saved notes',
  expectedCurrentTmTextUnitVariantId: 7301,
  expectedReviewStateRevision: 'revision-before',
});

function row(
  revision = 'revision-after',
  target = 'Saved translation',
  state: 'PENDING' | 'DECIDED' = 'DECIDED',
): ApiReviewProjectTextUnit {
  const variant = {
    id: revision === 'revision-before' ? 7301 : 7302,
    content: target,
    status: 'APPROVED',
    includedInLocalizedFile: true,
    comment: 'Saved comment',
  };
  return {
    id: 7101,
    reviewStateRevision: revision,
    tmTextUnit: { id: 7201, name: 'protocol.source', content: 'Original source' },
    currentTmTextUnitVariant: variant,
    baselineTmTextUnitVariant: variant,
    reviewProjectTextUnitDecision: {
      decisionState: state,
      decisionTmTextUnitVariant: variant,
      notes: 'Saved notes',
      lastModifiedByUsername: translator.username,
    },
    terminologyFeedbacks: [],
  };
}

const originalRow = row('revision-before', 'Original translation', 'PENDING');
const project: ApiReviewProjectDetail = {
  id: 7001,
  type: 'NORMAL',
  status: 'OPEN',
  textUnitCount: 1,
  wordCount: 2,
  locale: { id: 74, bcp47Tag: 'fr-FR' },
  reviewProjectRequest: { id: 7002, name: 'Protocol test', screenshotImageIds: [] },
  assignment: null,
  reviewProjectTextUnits: [originalRow],
};

const clients: QueryClient[] = [];
const fetchMock = vi.fn<typeof fetch>();

function response(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

function setup(initialUser = translator) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retryDelay: 0 } },
  });
  clients.push(client);
  client.setQueryData([...REVIEW_PROJECT_DETAIL_QUERY_KEY, project.id], project);
  let currentUser = initialUser;
  const wrapper = ({ children }: { children: ReactNode }) =>
    createElement(
      QueryClientProvider,
      { client },
      createElement(UserContext.Provider, { value: currentUser }, children),
    );
  const mounted = renderHook(({ projectId }) => useReviewProjectMutations(projectId), {
    initialProps: { projectId: project.id },
    wrapper,
  });
  return {
    ...mounted,
    client,
    cached: (projectId = project.id) =>
      client.getQueryData<ApiReviewProjectDetail>([...REVIEW_PROJECT_DETAIL_QUERY_KEY, projectId]),
    changeUser: (next: ApiUserProfile) => {
      currentUser = next;
      mounted.rerender({ projectId: project.id });
    },
  };
}

function requestBody(call = 0) {
  const init = fetchMock.mock.calls[call][1] as RequestInit;
  if (typeof init.body !== 'string') throw new Error('Expected JSON request body');
  return JSON.parse(init.body) as Record<string, unknown>;
}

beforeEach(() => {
  integrityCheck.mockReset().mockResolvedValue({ checkResult: true });
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  cleanup();
  clients.splice(0).forEach((client) => client.clear());
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('Review Project adversarial save protocol with real API serialization', () => {
  it.each(['HTTP 500', 'connection loss'])(
    'reconciles a committed save after %s without changing the retried payload',
    async (failure) => {
      fetchMock
        .mockImplementationOnce(() =>
          failure === 'HTTP 500'
            ? Promise.resolve(response({ message: 'Reply lost after commit' }, 500))
            : Promise.reject(new TypeError('Connection closed after commit')),
        )
        .mockImplementationOnce(() => Promise.resolve(response(row(), 409)));
      const { result, cached } = setup();
      const request = makeRequest();
      let operationId: number | void = undefined;
      act(() => {
        operationId = result.current.onRequestSaveDecision(request);
        request.target = 'Typed after save started';
        request.expectedReviewStateRevision = 'Must not reach retry';
      });
      await waitFor(() => expect(result.current.actionState.phase).toBe('succeeded'));
      expect(fetchMock).toHaveBeenCalledTimes(2);
      expect(requestBody(0)).toEqual(requestBody(1));
      expect(requestBody(1)).toMatchObject({
        target: 'Saved translation',
        expectedReviewStateRevision: 'revision-before',
      });
      expect(result.current.actionState).toMatchObject({ operationId: operationId! });
      expect(cached()?.reviewProjectTextUnits?.[0]).toEqual(row());
    },
  );

  it('reconciles the server NFC form after an ambiguously committed Unicode save', async () => {
    const decomposed = 'Cafe\u0301';
    fetchMock
      .mockImplementationOnce(() =>
        Promise.resolve(response({ message: 'Commit reply lost' }, 500)),
      )
      .mockImplementationOnce(() => Promise.resolve(response(row('revision-after', 'Café'), 409)));
    const { result } = setup();
    act(() => {
      result.current.onRequestSaveDecision({ ...makeRequest(), target: decomposed });
    });
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(result.current.isSaving).toBe(false));
    expect(result.current.actionState.phase).toBe('succeeded');
  });

  it.each(['notes', 'comment', 'status', 'inclusion', 'reviewer', 'decision-state', 'suggestion'])(
    'does not reconcile a same-target conflict when %s differs',
    async (changed) => {
      const conflictRow = row();
      if (changed === 'notes') conflictRow.reviewProjectTextUnitDecision!.notes = 'New notes';
      if (changed === 'comment') conflictRow.currentTmTextUnitVariant!.comment = 'New comment';
      if (changed === 'status') conflictRow.currentTmTextUnitVariant!.status = 'REVIEW_NEEDED';
      if (changed === 'inclusion')
        conflictRow.currentTmTextUnitVariant!.includedInLocalizedFile = false;
      if (changed === 'reviewer')
        conflictRow.reviewProjectTextUnitDecision!.lastModifiedByUsername = 'Another reviewer';
      if (changed === 'decision-state')
        conflictRow.reviewProjectTextUnitDecision!.decisionState = 'PENDING';
      if (changed === 'suggestion')
        conflictRow.reviewProjectTextUnitSuggestion = {
          id: 7401,
          target: 'New staged target',
          source: 'AI_REVIEW',
        };
      fetchMock.mockImplementation(() => Promise.resolve(response(conflictRow, 409)));
      const { result, cached } = setup();
      act(() => {
        result.current.onRequestSaveDecision(makeRequest());
      });
      await waitFor(() => expect(result.current.actionState.phase).toBe('conflict'));
      expect(fetchMock).toHaveBeenCalledOnce();
      expect(cached()?.reviewProjectTextUnits?.[0]).toEqual(conflictRow);
    },
  );

  it('fails visibly after exactly three 500 attempts without acknowledging the cache', async () => {
    fetchMock.mockImplementation(() =>
      Promise.resolve(response({ message: 'Storage unavailable' }, 500)),
    );
    const { result, cached } = setup();
    act(() => {
      result.current.onRequestSaveDecision(makeRequest());
    });
    await waitFor(() => expect(result.current.actionState.phase).toBe('failed'));
    expect(fetchMock).toHaveBeenCalledTimes(3);
    expect(result.current.errorMessage).toBe('Storage unavailable');
    expect(cached()?.reviewProjectTextUnits?.[0]).toEqual(originalRow);
  });

  it.each([400, 401, 403, 404, 408, 409, 429])(
    'does not blindly retry permanent HTTP %s',
    async (status) => {
      fetchMock.mockImplementation(() =>
        Promise.resolve(response({ message: 'Rejected' }, status)),
      );
      const { result, cached } = setup();
      act(() => {
        result.current.onRequestSaveDecision(makeRequest());
      });
      await waitFor(() => expect(result.current.actionState.phase).toBe('failed'));
      expect(fetchMock).toHaveBeenCalledOnce();
      expect(cached()?.reviewProjectTextUnits?.[0]).toEqual(originalRow);
    },
  );

  it.each([null, {}, { ...row(), id: 9999 }])(
    'rejects a malformed or wrong-row success %j',
    async (body) => {
      fetchMock.mockImplementation(() => Promise.resolve(response(body)));
      const { result, cached } = setup();
      act(() => {
        result.current.onRequestSaveDecision(makeRequest());
      });
      await waitFor(() => expect(result.current.actionState.phase).toBe('failed'));
      expect(fetchMock).toHaveBeenCalledOnce();
      expect(cached()?.reviewProjectTextUnits?.[0]).toEqual(originalRow);
    },
  );

  it('serializes same-tick double saves and holds the guard through asynchronous cache acknowledgement', async () => {
    fetchMock.mockImplementation(() => Promise.resolve(response(row())));
    const { result, client } = setup();
    const cancellation = deferred<void>();
    const cancelSpy = vi.spyOn(client, 'cancelQueries').mockReturnValue(cancellation.promise);
    act(() => {
      expect(result.current.onRequestSaveDecision(makeRequest())).toEqual(expect.any(Number));
      expect(result.current.onRequestSaveDecision(makeRequest())).toBeUndefined();
    });
    await waitFor(() => expect(cancelSpy).toHaveBeenCalledOnce());
    act(() => expect(result.current.onRequestSaveDecision(makeRequest())).toBeUndefined());
    expect(result.current.actionState.phase).toBe('pending');
    await act(async () => {
      cancellation.resolve();
      await cancellation.promise;
    });
    await waitFor(() => expect(result.current.actionState.phase).toBe('succeeded'));
    expect(fetchMock).toHaveBeenCalledOnce();
  });

  it('Use current rechecks a DECIDED conflict snapshot before adopting it after a newer refresh', async () => {
    const firstConflict = row('conflict-shown', 'First external translation');
    const newer = row('changed-again', 'Second external translation');
    fetchMock
      .mockImplementationOnce(() => Promise.resolve(response(firstConflict, 409)))
      .mockImplementationOnce(() => Promise.resolve(response(newer, 409)));
    const { result, client, cached } = setup();
    act(() => {
      result.current.onRequestSaveDecision(makeRequest());
    });
    await waitFor(() => expect(result.current.actionState.phase).toBe('conflict'));
    act(() => {
      client.setQueryData([...REVIEW_PROJECT_DETAIL_QUERY_KEY, project.id], {
        ...project,
        reviewProjectTextUnits: [newer],
      });
    });
    act(() => {
      result.current.onUseConflictCurrent();
    });
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));
    expect(requestBody(1)).toMatchObject({
      decisionState: 'DECIDED',
      expectedReviewStateRevision: 'conflict-shown',
    });
    await waitFor(() => expect(result.current.actionState.phase).toBe('conflict'));
    expect(result.current.conflictTextUnit?.reviewStateRevision).toBe('changed-again');
    expect(cached()?.reviewProjectTextUnits?.[0]).toEqual(newer);
  });

  it('a second Use mine conflict checks the newly displayed revision, never an unconditional override', async () => {
    fetchMock
      .mockImplementationOnce(() =>
        Promise.resolve(response(row('conflict-one', 'External one'), 409)),
      )
      .mockImplementationOnce(() =>
        Promise.resolve(response(row('conflict-two', 'External two'), 409)),
      )
      .mockImplementationOnce(() => Promise.resolve(response(row())));
    const { result } = setup();
    act(() => {
      result.current.onRequestSaveDecision(makeRequest());
    });
    await waitFor(() =>
      expect(result.current.conflictTextUnit?.reviewStateRevision).toBe('conflict-one'),
    );
    const oldRecovery = result.current.onOverwriteConflict;
    act(() => result.current.onOverwriteConflict());
    await waitFor(() =>
      expect(result.current.conflictTextUnit?.reviewStateRevision).toBe('conflict-two'),
    );
    act(() => oldRecovery());
    expect(fetchMock).toHaveBeenCalledTimes(2);
    act(() => result.current.onOverwriteConflict());
    await waitFor(() => expect(result.current.actionState.phase).toBe('succeeded'));
    expect(requestBody(1)).toMatchObject({
      target: 'Saved translation',
      expectedReviewStateRevision: 'conflict-one',
      overrideChangedCurrent: false,
    });
    expect(requestBody(2)).toMatchObject({
      target: 'Saved translation',
      expectedReviewStateRevision: 'conflict-two',
      overrideChangedCurrent: false,
    });
  });

  it('Use mine still saves the original draft after a guarded Use external attempt conflicts again', async () => {
    fetchMock
      .mockImplementationOnce(() =>
        Promise.resolve(response(row('conflict-one', 'External one'), 409)),
      )
      .mockImplementationOnce(() =>
        Promise.resolve(response(row('conflict-two', 'External two'), 409)),
      )
      .mockImplementationOnce(() => Promise.resolve(response(row())));
    const { result } = setup();
    let operationId: number | void;
    act(() => {
      operationId = result.current.onRequestSaveDecision(makeRequest());
    });
    await waitFor(() =>
      expect(result.current.conflictTextUnit?.reviewStateRevision).toBe('conflict-one'),
    );
    act(() => {
      result.current.onUseConflictCurrent();
    });
    await waitFor(() =>
      expect(result.current.conflictTextUnit?.reviewStateRevision).toBe('conflict-two'),
    );
    expect(requestBody(1)).not.toHaveProperty('target');
    act(() => result.current.onOverwriteConflict());
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(3));
    expect(requestBody(2)).toMatchObject({
      target: 'Saved translation',
      comment: 'Saved comment',
      decisionNotes: 'Saved notes',
      decisionState: 'DECIDED',
      expectedReviewStateRevision: 'conflict-two',
      overrideChangedCurrent: false,
    });
    await waitFor(() => expect(result.current.actionState.phase).toBe('succeeded'));
    expect(result.current.actionState).toMatchObject({ operationId: operationId! });
  });

  it.each(['username', 'project', 'unmount'])(
    'ignores a late save response after %s changes',
    async (boundary) => {
      const pending = deferred<Response>();
      fetchMock.mockReturnValue(pending.promise);
      const hook = setup();
      act(() => {
        hook.result.current.onRequestSaveDecision(makeRequest());
      });
      await waitFor(() => expect(fetchMock).toHaveBeenCalledOnce());
      if (boundary === 'username') hook.changeUser({ ...translator, username: 'next-reviewer' });
      if (boundary === 'project') hook.rerender({ projectId: 7999 });
      if (boundary === 'unmount') hook.unmount();
      await act(async () => {
        pending.resolve(response(row()));
        await pending.promise;
      });
      expect(hook.cached()?.reviewProjectTextUnits?.[0]).toEqual(originalRow);
      if (boundary !== 'unmount') expect(hook.result.current.actionState.phase).toBe('idle');
    },
  );

  it('old validation confirmation and retry handles cannot resurrect a dismissed request', async () => {
    integrityCheck.mockResolvedValue({ checkResult: false, failureDetail: 'Missing placeholder' });
    const { result } = setup({ ...translator, role: 'ROLE_PM' });
    act(() => {
      result.current.onRequestSaveDecision(makeRequest());
    });
    await waitFor(() => expect(result.current.actionState.phase).toBe('validation'));
    const confirm = result.current.onConfirmValidationSave;
    const retry = result.current.onRetryValidationSave;
    act(() => result.current.onDismissValidationSave());
    act(() => {
      confirm();
      retry();
    });
    expect(result.current.actionState.phase).toBe('idle');
    expect(fetchMock).not.toHaveBeenCalled();
    expect(integrityCheck).toHaveBeenCalledOnce();
  });

  it('does not publish a PM preflight result after the signed-in user changes', async () => {
    const check = deferred<{ checkResult: boolean }>();
    integrityCheck.mockReturnValue(check.promise);
    const hook = setup({ ...translator, role: 'ROLE_PM' });
    act(() => {
      hook.result.current.onRequestSaveDecision(makeRequest());
    });
    expect(integrityCheck).toHaveBeenCalledOnce();
    hook.changeUser({ ...translator, username: 'new-session' });
    await act(async () => {
      check.resolve({ checkResult: true });
      await check.promise;
    });
    expect(fetchMock).not.toHaveBeenCalled();
    expect(hook.result.current.actionState.phase).toBe('idle');
  });

  it.each(['username', 'project', 'unmount'])(
    'rejects a captured request callback from an obsolete %s session',
    async (boundary) => {
      fetchMock.mockImplementation(() => Promise.resolve(response(row())));
      const hook = setup();
      const oldRequest = hook.result.current.onRequestSaveDecision;
      if (boundary === 'username') hook.changeUser({ ...translator, username: 'next-reviewer' });
      if (boundary === 'project') hook.rerender({ projectId: 7999 });
      if (boundary === 'unmount') hook.unmount();
      let operationId: number | void = undefined;
      await act(async () => {
        operationId = oldRequest(makeRequest());
        await Promise.resolve();
      });
      expect(operationId).toBeUndefined();
      expect(fetchMock).not.toHaveBeenCalled();
      expect(hook.cached()?.reviewProjectTextUnits?.[0]).toEqual(originalRow);
    },
  );
});
