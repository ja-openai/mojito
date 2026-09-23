import { fireEvent, render, screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import type { ApiAgentReviewContext } from '../../api/agent-reviews';
import { AgentReviewReport } from './AgentReviewReport';

const proposal: ApiAgentReviewContext = {
  proposalId: 901,
  proposalRevision: 2,
  proposalVersion: 4,
  findingId: 'finding',
  runId: 51,
  reviewType: 'TRANSLATION_QUALITY',
  reviewedSource: 'Enable notifications',
  reviewedTarget: 'Désactiver les notifications',
  proposedTarget: 'Activer les notifications',
  rationale: 'The current translation reverses the action.',
  category: 'OBVIOUS_ERROR',
  verificationStatus: 'READY',
  disposition: 'ROUTED',
  stale: false,
};

function props() {
  return {
    proposal,
    localeTag: 'fr-FR',
    draftTarget: proposal.reviewedTarget!,
    originalDisabled: false,
    suggestionDisabled: false,
    onUseOriginal: vi.fn(),
    onUseSuggestion: vi.fn(),
    onOpenReport: vi.fn(),
  };
}

describe('AgentReviewReport', () => {
  it('keeps View report as the only header action without a separate note control', () => {
    const callbacks = props();
    const { container } = render(<AgentReviewReport {...callbacks} />);
    const header = within(container.querySelector('.agent-review-report__header') as HTMLElement);
    const viewReport = header.getByRole('button', { name: 'View report →' });
    expect(header.getAllByRole('button')).toEqual([viewReport]);
    expect(screen.queryByRole('button', { name: /decision note/i })).not.toBeInTheDocument();
    fireEvent.click(viewReport);
    expect(callbacks.onOpenReport).toHaveBeenCalledOnce();
  });

  it('hides the report link without hiding the finding or translation choices when report access is unavailable', () => {
    const callbacks = props();
    render(<AgentReviewReport {...callbacks} onOpenReport={undefined} />);

    expect(screen.queryByRole('button', { name: 'View report →' })).not.toBeInTheDocument();
    const report = screen.getByRole('region', { name: 'Reported issue' });
    expect(report).toHaveTextContent(proposal.rationale);
    expect(report).toHaveTextContent(proposal.reviewedTarget!);
    expect(report).toHaveTextContent(proposal.proposedTarget!);
    fireEvent.click(screen.getByRole('button', { name: 'Use suggestion' }));
    expect(callbacks.onUseSuggestion).toHaveBeenCalledOnce();
    expect(callbacks.onOpenReport).not.toHaveBeenCalled();
  });

  it('retains the original and proposal when the selected draft or review state changes', () => {
    const callbacks = props();
    const { rerender } = render(<AgentReviewReport {...callbacks} />);
    expect(screen.getByRole('status', { name: 'Selected original translation' })).toHaveTextContent(
      'Selected',
    );
    expect(screen.getByRole('button', { name: 'Use suggestion' })).toHaveTextContent('Use');
    fireEvent.click(screen.getByRole('button', { name: 'Use suggestion' }));
    expect(callbacks.onUseSuggestion).toHaveBeenCalledOnce();
    rerender(
      <AgentReviewReport
        {...callbacks}
        draftTarget={proposal.proposedTarget!}
        proposal={{ ...proposal, disposition: 'RESOLVED' }}
      />,
    );
    expect(screen.getByText('Automation · Reviewed')).toBeInTheDocument();
    expect(screen.getByRole('status', { name: 'Selected proposed correction' })).toHaveTextContent(
      'Selected',
    );
    expect(screen.getByRole('region', { name: 'Reported issue' })).toHaveTextContent(
      proposal.reviewedTarget!,
    );
    expect(screen.getByRole('region', { name: 'Reported issue' })).toHaveTextContent(
      proposal.proposedTarget!,
    );
    fireEvent.click(screen.getByRole('button', { name: 'Use original translation' }));
    expect(callbacks.onUseOriginal).toHaveBeenCalledOnce();
    expect(screen.queryByText('In editor')).not.toBeInTheDocument();
  });

  it('distinguishes no proposal from an intentionally empty correction and honors validation errors', () => {
    const callbacks = props();
    const { rerender } = render(
      <AgentReviewReport {...callbacks} proposal={{ ...proposal, proposedTarget: null }} />,
    );
    expect(screen.getByText('No correction proposed')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Use suggestion' })).not.toBeInTheDocument();
    rerender(<AgentReviewReport {...callbacks} proposal={{ ...proposal, proposedTarget: '' }} />);
    expect(screen.getByText('Empty translation')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Use suggestion' }));
    expect(callbacks.onUseSuggestion).toHaveBeenCalledOnce();
    rerender(
      <AgentReviewReport
        {...callbacks}
        originalError="The source changed."
        suggestionError="The source changed."
      />,
    );
    expect(
      screen.queryByRole('button', { name: 'Use original translation' }),
    ).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Use suggestion' })).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: 'View report →' }));
    expect(callbacks.onOpenReport).toHaveBeenCalledOnce();
  });

  it('selects only one identical version and neither version for a custom edit', () => {
    const callbacks = props();
    const { rerender } = render(
      <AgentReviewReport
        {...callbacks}
        proposal={{ ...proposal, proposedTarget: proposal.reviewedTarget }}
      />,
    );
    expect(screen.getAllByRole('status', { name: /^Selected/ })).toHaveLength(1);
    expect(
      screen.getByRole('status', { name: 'Selected original translation' }),
    ).toBeInTheDocument();
    rerender(<AgentReviewReport {...callbacks} draftTarget="My own translation" />);
    expect(screen.queryByRole('status', { name: /^Selected/ })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Use original translation' })).toHaveTextContent(
      'Use original',
    );
    expect(screen.getByRole('button', { name: 'Use suggestion' })).toHaveTextContent(
      'Use suggestion',
    );
    rerender(
      <AgentReviewReport
        {...callbacks}
        draftTarget=""
        proposal={{ ...proposal, proposedTarget: '' }}
      />,
    );
    expect(
      screen.getByRole('status', { name: 'Selected proposed correction' }),
    ).toBeInTheDocument();
  });

  it('renders report text as text, including markup-looking content and non-Latin changes', () => {
    const { container } = render(
      <AgentReviewReport
        {...props()}
        localeTag="ar"
        proposal={{
          ...proposal,
          reviewedTarget: '<img src=x onerror=alert(1)> مرحبا',
          proposedTarget: '<img src=x onerror=alert(1)> أهلا',
        }}
      />,
    );
    expect(container.querySelector('img')).toBeNull();
    const originals = container.querySelectorAll('[lang="ar"][dir="auto"]');
    expect(originals).toHaveLength(2);
    expect(originals[0]).toHaveTextContent('<img src=x onerror=alert(1)> مرحبا');
    expect(originals[1]).toHaveTextContent('<img src=x onerror=alert(1)> أهلا');
  });
});
