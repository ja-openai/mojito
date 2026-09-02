import { onlineManager, QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, cleanup, renderHook, waitFor } from '@testing-library/react';
import { createElement, type ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type { ApiReviewProjectDetail, ApiReviewProjectTextUnit } from '../../api/review-projects';
import type { ApiUserProfile } from '../../api/users';
import { REVIEW_PROJECT_DETAIL_QUERY_KEY } from '../../hooks/useReviewProjectDetail';
import { UserContext } from '../../hooks/useUser';
import { type SaveDecisionRequest, useReviewProjectMutations } from './review-project-mutations';

const reviewer: ApiUserProfile = {
  username: 'confidence-translator',
  role: 'ROLE_TRANSLATOR',
  canTranslateAllLocales: true,
  userLocales: [],
};
const request: SaveDecisionRequest = {
  textUnitId: 8101,
  tmTextUnitId: 8201,
  target: 'Translation to save',
  status: 'APPROVED',
  includedInLocalizedFile: true,
  comment: 'Translator comment',
  decisionState: 'DECIDED',
  decisionNotes: 'Translator notes',
  expectedCurrentTmTextUnitVariantId: 8301,
  expectedReviewStateRevision: 'before',
};

function savedRow(): ApiReviewProjectTextUnit {
  const variant = {
    id: 8302,
    content: request.target,
    status: request.status,
    includedInLocalizedFile: request.includedInLocalizedFile,
    comment: request.comment,
  };
  return {
    id: request.textUnitId,
    reviewStateRevision: 'after',
    tmTextUnit: { id: request.tmTextUnitId!, content: 'Source text' },
    baselineTmTextUnitVariant: { ...variant, id: 8301, content: 'Previous translation' },
    currentTmTextUnitVariant: variant,
    reviewProjectTextUnitDecision: {
      decisionState: request.decisionState,
      notes: request.decisionNotes,
      lastModifiedByUsername: reviewer.username,
      decisionTmTextUnitVariant: variant,
    },
    reviewProjectTextUnitSuggestion: null,
    terminologyFeedbacks: [],
  };
}

function initialProject(): ApiReviewProjectDetail {
  const row = savedRow();
  row.reviewStateRevision = 'before';
  row.currentTmTextUnitVariant = row.baselineTmTextUnitVariant;
  row.reviewProjectTextUnitDecision = null;
  return { id: 8001, type: 'NORMAL', status: 'OPEN', reviewProjectTextUnits: [row] };
}

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
  const promise = new Promise<T>((resolvePromise) => {
    resolve = resolvePromise;
  });
  return { resolve, promise };
}

function setup() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retryDelay: 0 } },
  });
  clients.push(client);
  const project = initialProject();
  client.setQueryData([...REVIEW_PROJECT_DETAIL_QUERY_KEY, project.id], project);
  let user = reviewer;
  const wrapper = ({ children }: { children: ReactNode }) =>
    createElement(
      QueryClientProvider,
      { client },
      createElement(UserContext.Provider, { value: user }, children),
    );
  const hook = renderHook(({ projectId }) => useReviewProjectMutations(projectId), {
    initialProps: { projectId: project.id },
    wrapper,
  });
  return {
    ...hook,
    client,
    project,
    cached: () =>
      client.getQueryData<ApiReviewProjectDetail>([...REVIEW_PROJECT_DETAIL_QUERY_KEY, project.id]),
    changeOwner: (boundary: 'username' | 'project' | 'unmount') => {
      if (boundary === 'username') user = { ...reviewer, username: 'replacement-translator' };
      if (boundary === 'unmount') hook.unmount();
      else hook.rerender({ projectId: boundary === 'project' ? 8999 : project.id });
    },
  };
}

beforeEach(() => {
  onlineManager.setOnline(true);
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  cleanup();
  onlineManager.setOnline(true);
  clients.splice(0).forEach((client) => client.clear());
  vi.unstubAllGlobals();
});

describe('Review Project confidence audit: truthful acknowledgements', () => {
  it.each([
    'id-only',
    'original-row',
    'wrong-source',
    'wrong-target',
    'pending-decision',
    'wrong-status',
    'wrong-inclusion',
    'wrong-comment',
    'wrong-notes',
    'wrong-decision-variant',
    'missing-revision',
  ])('does not acknowledge a same-row HTTP 200 that contains %s', async (kind) => {
    let body: unknown = savedRow();
    if (kind === 'id-only') body = { id: request.textUnitId };
    if (kind === 'original-row') body = initialProject().reviewProjectTextUnits![0];
    if (kind === 'wrong-source') (body as ApiReviewProjectTextUnit).tmTextUnit!.id = 8299;
    if (kind === 'wrong-target')
      (body as ApiReviewProjectTextUnit).currentTmTextUnitVariant!.content =
        'Unexpected translation';
    if (kind === 'pending-decision')
      (body as ApiReviewProjectTextUnit).reviewProjectTextUnitDecision!.decisionState = 'PENDING';
    if (kind === 'wrong-status')
      (body as ApiReviewProjectTextUnit).currentTmTextUnitVariant!.status = 'REVIEW_NEEDED';
    if (kind === 'wrong-inclusion')
      (body as ApiReviewProjectTextUnit).currentTmTextUnitVariant!.includedInLocalizedFile = false;
    if (kind === 'wrong-comment')
      (body as ApiReviewProjectTextUnit).currentTmTextUnitVariant!.comment = 'Other comment';
    if (kind === 'wrong-notes')
      (body as ApiReviewProjectTextUnit).reviewProjectTextUnitDecision!.notes = 'Other notes';
    if (kind === 'wrong-decision-variant')
      (body as ApiReviewProjectTextUnit).reviewProjectTextUnitDecision!.decisionTmTextUnitVariant =
        {
          ...(body as ApiReviewProjectTextUnit).currentTmTextUnitVariant,
          id: 8399,
        };
    if (kind === 'missing-revision') delete (body as ApiReviewProjectTextUnit).reviewStateRevision;
    fetchMock.mockImplementation(() => Promise.resolve(response(body)));
    const hook = setup();
    act(() => {
      hook.result.current.onRequestSaveDecision(request);
    });
    await waitFor(() => expect(hook.result.current.isSaving).toBe(false));
    expect(hook.result.current.actionState.phase).toBe('failed');
    expect(hook.result.current.errorMessage).toBeTruthy();
    expect(hook.cached()).toEqual(hook.project);
    expect(fetchMock).toHaveBeenCalledOnce();
  });

  it('accepts the complete NFC-normalized result of the requested save', async () => {
    const normalized = savedRow();
    normalized.currentTmTextUnitVariant!.content = 'Café';
    fetchMock.mockImplementation(() => Promise.resolve(response(normalized)));
    const hook = setup();
    act(() => {
      hook.result.current.onRequestSaveDecision({ ...request, target: 'Cafe\u0301' });
    });
    await waitFor(() => expect(hook.result.current.actionState.phase).toBe('succeeded'));
    expect(hook.cached()?.reviewProjectTextUnits?.[0]).toEqual(normalized);
  });

  it.each(['PENDING', 'DECIDED'] as const)(
    'rejects a state-only HTTP 200 that did not apply requested %s',
    async (decisionState) => {
      const unchanged = savedRow();
      unchanged.currentTmTextUnitVariant!.id = request.expectedCurrentTmTextUnitVariantId;
      unchanged.reviewProjectTextUnitDecision!.decisionState =
        decisionState === 'DECIDED' ? 'PENDING' : 'DECIDED';
      fetchMock.mockImplementation(() => Promise.resolve(response(unchanged)));
      const hook = setup();
      act(() => {
        hook.result.current.onRequestDecisionState({ ...request, decisionState });
      });
      await waitFor(() => expect(hook.result.current.isSaving).toBe(false));
      expect(hook.result.current.actionState.phase).toBe('failed');
      expect(hook.cached()).toEqual(hook.project);
    },
  );

  it('accepts a state-only PENDING no-op without inventing a decision row', async () => {
    const unchanged = initialProject().reviewProjectTextUnits![0];
    fetchMock.mockImplementation(() => Promise.resolve(response(unchanged)));
    const hook = setup();
    act(() => {
      hook.result.current.onRequestDecisionState({ ...request, decisionState: 'PENDING' });
    });
    await waitFor(() => expect(hook.result.current.actionState.phase).toBe('succeeded'));
    expect(hook.cached()?.reviewProjectTextUnits?.[0].reviewProjectTextUnitDecision).toBeNull();
  });

  it('accepts state-only DECIDED against an untranslated row with no current or baseline variant', async () => {
    const decided = savedRow();
    decided.currentTmTextUnitVariant = null;
    decided.baselineTmTextUnitVariant = null;
    decided.reviewProjectTextUnitDecision!.decisionTmTextUnitVariant = null;
    fetchMock.mockImplementation(() => Promise.resolve(response(decided)));
    const hook = setup();
    act(() => {
      hook.result.current.onRequestDecisionState({
        textUnitId: request.textUnitId,
        decisionState: 'DECIDED',
        expectedCurrentTmTextUnitVariantId: null,
        expectedReviewStateRevision: 'before',
      });
    });
    await waitFor(() => expect(hook.result.current.actionState.phase).toBe('succeeded'));
  });

  it('does not submit an old-source draft through Use mine after the row is remapped to another source', async () => {
    const remapped = savedRow();
    remapped.tmTextUnit = { id: 8299, content: 'A different source string' };
    remapped.currentTmTextUnitVariant!.content = 'Translation of the different source';
    fetchMock.mockImplementation(() => Promise.resolve(response(remapped, 409)));
    const hook = setup();
    act(() => {
      hook.result.current.onRequestSaveDecision(request);
    });
    await waitFor(() => expect(hook.result.current.actionState.phase).toBe('conflict'));
    act(() => hook.result.current.onOverwriteConflict());
    await waitFor(() => expect(hook.result.current.isSaving).toBe(false));
    expect(fetchMock).toHaveBeenCalledOnce();
    expect(hook.result.current.actionState.phase).toBe('failed');
    expect(hook.result.current.errorMessage).toContain('different source');
  });

  it('does not replace a newer source mapping with the delayed acknowledgement of an old-source save', async () => {
    const oldReply = deferred<Response>();
    fetchMock.mockReturnValue(oldReply.promise);
    const hook = setup();
    act(() => {
      hook.result.current.onRequestSaveDecision(request);
    });
    await waitFor(() => expect(fetchMock).toHaveBeenCalledOnce());
    const remapped = savedRow();
    remapped.tmTextUnit = { id: 8299, content: 'A different source string' };
    const refreshed = { ...hook.project, reviewProjectTextUnits: [remapped] };
    act(() => {
      hook.client.setQueryData([...REVIEW_PROJECT_DETAIL_QUERY_KEY, hook.project.id], refreshed);
    });
    await act(async () => {
      oldReply.resolve(response(savedRow()));
      await oldReply.promise;
    });
    await waitFor(() => expect(hook.result.current.isSaving).toBe(false));
    expect(hook.result.current.actionState.phase).toBe('failed');
    expect(hook.cached()).toEqual(refreshed);
    expect(hook.result.current.errorMessage).toContain('different source');
  });

  it.each(['save-decision', 'decision-state'] as const)(
    'keeps an intervening observed revision when the delayed %s acknowledgement arrives',
    async (kind) => {
      const oldReply = deferred<Response>();
      fetchMock.mockReturnValue(oldReply.promise);
      const hook = setup();
      const acknowledgement = savedRow();
      act(() => {
        if (kind === 'save-decision') hook.result.current.onRequestSaveDecision(request);
        else {
          acknowledgement.currentTmTextUnitVariant!.id = request.expectedCurrentTmTextUnitVariantId;
          hook.result.current.onRequestDecisionState(request);
        }
      });
      await waitFor(() => expect(fetchMock).toHaveBeenCalledOnce());
      const newer = savedRow();
      newer.reviewStateRevision = 'another-writer-after-commit';
      newer.currentTmTextUnitVariant = {
        ...newer.currentTmTextUnitVariant,
        id: 8303,
        content: 'Subsequent translation',
      };
      newer.reviewProjectTextUnitDecision!.decisionTmTextUnitVariant =
        newer.currentTmTextUnitVariant;
      const refreshed = { ...hook.project, reviewProjectTextUnits: [newer] };
      act(() => {
        hook.client.setQueryData([...REVIEW_PROJECT_DETAIL_QUERY_KEY, hook.project.id], refreshed);
      });
      await act(async () => {
        oldReply.resolve(response(acknowledgement));
        await oldReply.promise;
      });
      await waitFor(() => expect(hook.result.current.isSaving).toBe(false));
      expect(hook.result.current.actionState.phase).toBe('failed');
      expect(hook.cached()).toEqual(refreshed);
      expect(hook.result.current.errorMessage).toContain('Another update');
    },
  );

  it.each(['before', 'after'])(
    'accepts a delayed save when the cache still has the %s revision',
    async (revision) => {
      const oldReply = deferred<Response>();
      fetchMock.mockReturnValue(oldReply.promise);
      const hook = setup();
      act(() => {
        hook.result.current.onRequestSaveDecision(request);
      });
      await waitFor(() => expect(fetchMock).toHaveBeenCalledOnce());
      if (revision === 'after') {
        act(() => {
          hook.client.setQueryData([...REVIEW_PROJECT_DETAIL_QUERY_KEY, hook.project.id], {
            ...hook.project,
            reviewProjectTextUnits: [savedRow()],
          });
        });
      }
      await act(async () => {
        oldReply.resolve(response(savedRow()));
        await oldReply.promise;
      });
      await waitFor(() => expect(hook.result.current.actionState.phase).toBe('succeeded'));
      expect(hook.cached()?.reviewProjectTextUnits?.[0]).toEqual(savedRow());
    },
  );
});

describe('Review Project confidence audit: transport ownership', () => {
  it.each(['status', 'request', 'due-date', 'assignment'] as const)(
    'does not send an offline queued project %s update in a replacement user session',
    async (kind) => {
      onlineManager.setOnline(false);
      fetchMock.mockImplementation(() => Promise.resolve(response(initialProject())));
      const hook = setup();
      act(() => {
        if (kind === 'status') hook.result.current.onRequestProjectStatus('CLOSED');
        if (kind === 'request')
          void hook.result.current
            .onRequestProjectRequestUpdate({ name: 'Old session title' })
            .catch(() => {});
        if (kind === 'due-date')
          void hook.result.current.onRequestProjectDueDateUpdate('2026-09-15').catch(() => {});
        if (kind === 'assignment')
          void hook.result.current
            .onRequestProjectAssignmentUpdate({ assignedTranslatorUserId: 15 })
            .catch(() => {});
      });
      await waitFor(() =>
        expect(hook.client.getMutationCache().getAll()[0].state.isPaused).toBe(true),
      );
      hook.changeOwner('username');
      await act(async () => {
        onlineManager.setOnline(true);
        await hook.client.resumePausedMutations();
      });
      await waitFor(() =>
        expect(hook.client.getMutationCache().getAll()[0].state.status).not.toBe('pending'),
      );
      expect(fetchMock).not.toHaveBeenCalled();
      expect(hook.cached()).toEqual(hook.project);
    },
  );

  it.each(['username', 'project', 'unmount'] as const)(
    'does not issue further writes after %s changes while the first attempt is pending',
    async (boundary) => {
      const firstResponse = deferred<Response>();
      fetchMock
        .mockReturnValueOnce(firstResponse.promise)
        .mockImplementation(() => Promise.resolve(response({ message: 'Unavailable' }, 500)));
      const hook = setup();
      act(() => {
        hook.result.current.onRequestSaveDecision(request);
      });
      await waitFor(() => expect(fetchMock).toHaveBeenCalledOnce());
      hook.changeOwner(boundary);
      await act(async () => {
        firstResponse.resolve(response({ message: 'First reply lost' }, 500));
        await firstResponse.promise;
      });
      await waitFor(() =>
        expect(hook.client.getMutationCache().getAll()[0].state.status).toBe('error'),
      );
      expect(fetchMock).toHaveBeenCalledOnce();
      expect(hook.cached()).toEqual(hook.project);
    },
  );

  it.each(['username', 'project', 'unmount'] as const)(
    'does not resume an offline queued write after its %s owner has gone',
    async (boundary) => {
      onlineManager.setOnline(false);
      fetchMock.mockImplementation(() => Promise.resolve(response(savedRow())));
      const hook = setup();
      act(() => {
        hook.result.current.onRequestSaveDecision(request);
      });
      await waitFor(() =>
        expect(hook.client.getMutationCache().getAll()[0].state.isPaused).toBe(true),
      );
      expect(fetchMock).not.toHaveBeenCalled();
      hook.changeOwner(boundary);
      await act(async () => {
        onlineManager.setOnline(true);
        // The app's QueryClientProvider outlives the page. Explicitly resume here because
        // renderHook's provider is also removed in the unmount variant of this test.
        await hook.client.resumePausedMutations();
      });
      await waitFor(() =>
        expect(hook.client.getMutationCache().getAll()[0].state.status).not.toBe('pending'),
      );
      expect(fetchMock).not.toHaveBeenCalled();
      expect(hook.cached()).toEqual(hook.project);
    },
  );
});
