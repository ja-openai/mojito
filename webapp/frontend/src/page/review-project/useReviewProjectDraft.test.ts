import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook } from '@testing-library/react';
import { createElement, type ReactNode } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { type ReviewProjectDecisionSnapshot, useReviewProjectDraft } from './useReviewProjectDraft';

const initial: ReviewProjectDecisionSnapshot = {
  tmTextUnitId: 201,
  source: 'Original source',
  messageFormat: null,
  target: 'Original translation',
  expectedCurrentVariantId: 30,
  reviewStateRevision: 'original-version',
  comment: null,
  decisionNotes: null,
  statusChoice: 'NEEDS_REVIEW',
  decisionState: 'PENDING',
  suggestionSourceLabel: null,
};
const saved: ReviewProjectDecisionSnapshot = {
  ...initial,
  target: 'Saved translation',
  expectedCurrentVariantId: 31,
  reviewStateRevision: 'saved-version',
  statusChoice: 'ACCEPTED',
  decisionState: 'DECIDED',
};

const clients = new Set<QueryClient>();
afterEach(() => {
  clients.forEach((client) => client.clear());
  clients.clear();
  vi.restoreAllMocks();
});

function mountDraft(client = new QueryClient()) {
  clients.add(client);
  const wrapper = ({ children }: { children: ReactNode }) =>
    createElement(QueryClientProvider, { client }, children);
  return {
    client,
    ...renderHook(({ snapshot }) => useReviewProjectDraft('translator', 7, 101, snapshot), {
      initialProps: { snapshot: initial },
      wrapper,
    }),
  };
}

describe('review project draft acknowledgements', () => {
  it('keeps an acknowledged save available for Reset before detail props catch up', () => {
    const { result, rerender } = mountDraft();
    act(() => {
      result.current.updateValues((values) => ({ ...values, target: saved.target }));
      result.current.startOperation(1);
      result.current.finishOperation(1, saved, false, false);
    });
    expect(result.current.session.base).toEqual(saved);
    act(() => {
      result.current.updateValues((values) => ({ ...values, comment: 'New unsaved comment' }));
      result.current.reset();
    });
    expect(result.current.session.values.target).toBe(saved.target);
    expect(result.current.session.base.reviewStateRevision).toBe(saved.reviewStateRevision);
    rerender({ snapshot: saved });
    expect(result.current.dirty).toBe(false);
  });

  it('keeps a dirty translation when only the decision state is acknowledged', () => {
    const { result } = mountDraft();
    const stateOnly = {
      ...initial,
      decisionState: 'DECIDED' as const,
      reviewStateRevision: 'state-version',
    };
    act(() => {
      result.current.updateValues((values) => ({ ...values, target: 'Unsaved translation' }));
      result.current.startOperation(1);
      result.current.finishOperation(1, stateOnly, false, true);
    });
    expect(result.current.session.values.target).toBe('Unsaved translation');
    expect(result.current.session.base.reviewStateRevision).toBe('state-version');
    expect(result.current.dirty).toBe(true);
  });

  it('ignores historical use-current success and late callbacks after a discarded session unmounts', () => {
    const first = mountDraft();
    const lateEdit = first.result.current.updateValues;
    act(() => {
      first.result.current.updateValues((values) => ({
        ...values,
        target: 'Discarded translation',
      }));
      first.result.current.reset();
    });
    first.unmount();
    act(() => lateEdit((values) => ({ ...values, target: 'Late old translation' })));
    const next = mountDraft(first.client);
    act(() => {
      next.result.current.updateValues((values) => ({ ...values, target: 'New draft' }));
      next.result.current.finishOperation(1, saved, true, false);
    });
    expect(next.result.current.session.values.target).toBe('New draft');
    expect(next.result.current.session.base).toEqual(initial);
  });

  it.each(['discard', 'save', 'clear'] as const)(
    'guards reload after leaving the page, and releases the warning after %s',
    (resolution) => {
      const add = vi.spyOn(window, 'addEventListener');
      const first = mountDraft();
      act(() => {
        first.result.current.updateValues((values) => ({ ...values, target: saved.target }));
        first.result.current.updateValues((values) => ({ ...values, comment: 'Another edit' }));
      });
      expect(add.mock.calls.filter(([name]) => name === 'beforeunload')).toHaveLength(1);
      first.unmount();
      const whileAway = new Event('beforeunload', { cancelable: true });
      window.dispatchEvent(whileAway);
      expect(whileAway.defaultPrevented).toBe(true);

      const next = mountDraft(first.client);
      act(() => {
        if (resolution === 'discard') next.result.current.reset();
        else if (resolution === 'clear') first.client.clear();
        else {
          next.result.current.startOperation(1);
          next.result.current.finishOperation(1, saved, false, false);
        }
      });
      const resolved = new Event('beforeunload', { cancelable: true });
      window.dispatchEvent(resolved);
      expect(resolved.defaultPrevented).toBe(false);
    },
  );
});
