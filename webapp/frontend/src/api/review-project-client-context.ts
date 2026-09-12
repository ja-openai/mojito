import type { AiReviewSuggestion } from './ai-review';

/** Content-free, untrusted diagnostics. Never use these claims as authorization. */
export type ReviewProjectClientOwner = {
  projectId: number;
  textUnitId: number;
  tmTextUnitId: number | null;
  reviewStateRevision: string | null;
};

export type ReviewProjectTargetOrigin = {
  kind:
    | 'server_snapshot'
    | 'staged_suggestion'
    | 'agent_proposal'
    | 'editor'
    | 'ai_suggestion'
    | 'unknown';
  owner: ReviewProjectClientOwner;
  suggestionId?: number;
  proposalId?: number;
  proposalRevision?: number;
  aiRequestId?: string;
};

export type ReviewProjectClientContext = {
  schemaVersion: 1;
  pageSessionId: string;
  operationId: string;
  requestSequence?: number;
  loadedBundleId: string;
  operationOrigin:
    | 'review_save'
    | 'review_accept'
    | 'review_status_change'
    | 'review_state_change'
    | 'agent_outcome';
  owner: ReviewProjectClientOwner;
  targetOrigin?: ReviewProjectTargetOrigin;
  recovery?: 'use_mine' | 'use_current';
};

let pageSessionId: string | undefined;
let requestSequence = 0;
// This identifies the executing artifact, including in a tab opened before a deployment.
// A request to /cli/version would incorrectly identify an old tab as the new server build.
const loadedBundleId = new URL(import.meta.url).pathname.split('/').pop() ?? 'unknown';

/** Diagnostic correlation only; unavailable browser support must not prevent a save. */
export function createReviewProjectDiagnosticId(): string {
  try {
    return globalThis.crypto?.randomUUID?.() ?? 'unavailable';
  } catch {
    return 'unavailable';
  }
}

export function createReviewProjectClientContext(
  operationOrigin: ReviewProjectClientContext['operationOrigin'],
  owner: ReviewProjectClientOwner,
  targetOrigin?: ReviewProjectTargetOrigin,
): ReviewProjectClientContext {
  pageSessionId ??= createReviewProjectDiagnosticId();
  return structuredClone({
    schemaVersion: 1,
    pageSessionId,
    operationId: createReviewProjectDiagnosticId(),
    loadedBundleId,
    operationOrigin,
    owner,
    ...(targetOrigin ? { targetOrigin } : {}),
  });
}

export function reviewProjectContextForTransport(context?: ReviewProjectClientContext) {
  return context ? { ...context, requestSequence: ++requestSequence } : undefined;
}

export function recoverReviewProjectClientContext(
  context: ReviewProjectClientContext | undefined,
  revision: string | null | undefined,
  recovery: 'use_mine' | 'use_current',
): ReviewProjectClientContext | undefined {
  if (!context) return undefined;
  return {
    ...context,
    owner: { ...context.owner, reviewStateRevision: revision ?? null },
    // Preserve the original suggestion revision when explicitly rebasing the draft.
    targetOrigin: recovery === 'use_current' ? undefined : context.targetOrigin,
    recovery,
  };
}

const suggestionOwners = new WeakMap<AiReviewSuggestion, ReviewProjectTargetOrigin>();

/** Bind ownership when a response is created, not from the row selected at Use time. */
export function ownReviewProjectAiSuggestions(
  suggestions: AiReviewSuggestion[],
  origin: ReviewProjectTargetOrigin,
) {
  return suggestions.map((suggestion) => {
    const owned = { ...suggestion };
    suggestionOwners.set(owned, structuredClone(origin));
    return owned;
  });
}

export function reviewProjectAiSuggestionOrigin(suggestion: AiReviewSuggestion) {
  return suggestionOwners.get(suggestion);
}
