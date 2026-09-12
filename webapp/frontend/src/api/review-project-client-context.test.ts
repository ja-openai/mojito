import { afterEach, describe, expect, it, vi } from 'vitest';

import {
  createReviewProjectClientContext,
  ownReviewProjectAiSuggestions,
  recoverReviewProjectClientContext,
  reviewProjectAiSuggestionOrigin,
  type ReviewProjectClientOwner,
  reviewProjectContextForTransport,
  type ReviewProjectTargetOrigin,
} from './review-project-client-context';

const owner: ReviewProjectClientOwner = {
  projectId: 7,
  textUnitId: 101,
  tmTextUnitId: 201,
  reviewStateRevision: 'v2-original',
};
const origin: ReviewProjectTargetOrigin = {
  kind: 'ai_suggestion',
  owner,
  aiRequestId: 'request-a',
};

describe('content-free Review Project request attribution', () => {
  afterEach(() => vi.unstubAllGlobals());

  it.each([
    ['crypto is missing', undefined],
    ['randomUUID is missing', {}],
    [
      'randomUUID throws',
      {
        randomUUID: () => {
          throw new Error('UUID unavailable');
        },
      },
    ],
  ])('creates and transports a context when %s', async (_name, crypto) => {
    vi.stubGlobal('crypto', crypto);
    vi.resetModules();
    const diagnostic = await import('./review-project-client-context');
    const context = diagnostic.createReviewProjectClientContext('review_save', owner);
    expect(context).toMatchObject({ pageSessionId: 'unavailable', operationId: 'unavailable' });
    expect(diagnostic.createReviewProjectDiagnosticId()).toBe('unavailable');
    expect(diagnostic.reviewProjectContextForTransport(context)).toMatchObject({
      owner,
      requestSequence: 1,
    });
  });

  it('captures the suggestion owner before Use, independently of the selected row', () => {
    const original = structuredClone(origin);
    const [suggestion] = ownReviewProjectAiSuggestions([{ content: 'Suggestion A' }], original);
    original.owner.textUnitId = 102;
    const context = createReviewProjectClientContext(
      'review_save',
      { ...owner, textUnitId: 102 },
      reviewProjectAiSuggestionOrigin(suggestion),
    );
    expect(context.owner.textUnitId).toBe(102);
    expect(context.targetOrigin?.owner.textUnitId).toBe(101);
    expect(JSON.stringify(context)).not.toContain('Suggestion A');
    expect(context.loadedBundleId).not.toMatch(/[/?#]/);
  });

  it('keeps an immutable operation snapshot and sequences each transport attempt', () => {
    const original = structuredClone(origin);
    const context = createReviewProjectClientContext('review_save', owner, original);
    original.owner.textUnitId = 102;
    const first = reviewProjectContextForTransport(context)!;
    const second = reviewProjectContextForTransport(context)!;
    expect(first.operationId).toBe(second.operationId);
    expect(first.pageSessionId).toBe(second.pageSessionId);
    expect(second.requestSequence).toBe(first.requestSequence + 1);
    expect(second.targetOrigin?.owner.textUnitId).toBe(101);
    expect(context.requestSequence).toBeUndefined();
  });

  it('records explicit Use mine without rewriting the original suggestion revision', () => {
    const context = createReviewProjectClientContext('review_save', owner, origin);
    const recovered = recoverReviewProjectClientContext(context, 'v2-current', 'use_mine')!;
    expect(recovered.owner.reviewStateRevision).toBe('v2-current');
    expect(recovered.targetOrigin?.owner.reviewStateRevision).toBe('v2-original');
    expect(recovered.recovery).toBe('use_mine');
    expect(recovered.operationId).toBe(context.operationId);
    expect(context.owner.reviewStateRevision).toBe('v2-original');
    expect(
      recoverReviewProjectClientContext(context, 'v2-current', 'use_current')?.targetOrigin,
    ).toBeUndefined();
  });

  it('does not fabricate attribution for callers without metadata', () => {
    expect(reviewProjectContextForTransport()).toBeUndefined();
    expect(recoverReviewProjectClientContext(undefined, 'v2-current', 'use_mine')).toBeUndefined();
    expect(reviewProjectAiSuggestionOrigin({ content: 'Unowned suggestion' })).toBeUndefined();
  });
});
