import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, expect, it, vi } from 'vitest';

import type * as AgentReviewsApi from '../../api/agent-reviews';
import { type ApiAgentReviewContext, fetchAgentReviewFeedback } from '../../api/agent-reviews';
import { AgentReviewContext } from './AgentReviewContext';

vi.mock('../../api/agent-reviews', async () => {
  const actual = await vi.importActual<typeof AgentReviewsApi>('../../api/agent-reviews');
  return { ...actual, fetchAgentReviewFeedback: vi.fn() };
});

const proposal: ApiAgentReviewContext = {
  proposalId: 901,
  proposalRevision: 2,
  proposalVersion: 3,
  findingId: 'checkout-title',
  runId: 12,
  reviewType: 'TRANSLATION_QUALITY',
  reviewedSource: 'Pay now',
  reviewedTarget: 'Before',
  proposedTarget: 'Suggested',
  rationale: 'Review this wording.',
  category: 'OBVIOUS_ERROR',
  verificationStatus: 'READY',
  disposition: 'RESOLVED',
  stale: false,
};

function renderContext(
  view: 'details' | 'history',
  overrides: Partial<ApiAgentReviewContext> = {},
) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <AgentReviewContext
          projectId={7}
          proposal={{ ...proposal, ...overrides }}
          localeTag="fr"
          view={view}
        />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.mocked(fetchAgentReviewFeedback).mockReset();
  vi.mocked(fetchAgentReviewFeedback).mockResolvedValue([]);
});

it('keeps safe evidence links while rendering unsafe URLs as text', () => {
  const artifact = `/api/agent-reviews/projects/7/proposals/901/artifacts/${'a'.repeat(64)}`;
  renderContext('details', {
    evidence: [
      { label: 'Reference', url: 'https://example.com/reference' },
      { label: 'Screenshot', url: artifact },
      { label: 'Unsafe', url: 'javascript:alert(1)' },
      { label: 'Other relative path', url: '/admin' },
    ],
  });
  expect(screen.getByRole('link', { name: 'Reference' })).toHaveAttribute(
    'rel',
    'noopener noreferrer',
  );
  expect(screen.getByRole('link', { name: 'Screenshot' })).toHaveAttribute('href', artifact);
  expect(screen.getByText('Unsafe')).toBeVisible();
  expect(screen.queryByRole('link', { name: 'Unsafe' })).not.toBeInTheDocument();
  expect(screen.queryByRole('link', { name: 'Other relative path' })).not.toBeInTheDocument();
  expect(fetchAgentReviewFeedback).not.toHaveBeenCalled();
});

it('preserves recorded translations and agent evidence across review revisions', async () => {
  vi.mocked(fetchAgentReviewFeedback).mockResolvedValue([
    {
      id: 1,
      proposalId: 900,
      proposalRevision: 1,
      actorType: 'HUMAN',
      actorIdentity: 'reviewer',
      createdDate: '2026-09-14T12:00:00Z',
      action: 'EDIT_ACCEPT',
      finalTarget: 'Recorded decision',
    },
    {
      id: 2,
      proposalId: 901,
      proposalRevision: 2,
      actorType: 'AGENT',
      actorIdentity: 'agent',
      createdDate: '2026-09-14T12:01:00Z',
      action: 'REVISED_PROPOSAL',
      evidenceJson: JSON.stringify([{ label: 'Unsafe response', url: 'javascript:alert(1)' }]),
      finalTarget: null,
    },
  ]);
  renderContext('history', { nextReviewProjectId: 8 });
  expect(await screen.findByText('Recorded decision')).toBeVisible();
  expect(screen.getByText('Edited and accepted')).toBeVisible();
  expect(screen.getByText('Revised proposal')).toBeVisible();
  expect(screen.queryByRole('link', { name: 'Unsafe response' })).not.toBeInTheDocument();
  expect(screen.getByRole('link', { name: 'Open new review' })).toHaveAttribute(
    'href',
    '/review-projects/8',
  );
});
