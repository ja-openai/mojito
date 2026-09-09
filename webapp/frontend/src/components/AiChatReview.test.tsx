import { fireEvent, render, screen } from '@testing-library/react';
import { beforeAll, describe, expect, it, vi } from 'vitest';

import type { AiReviewSuggestion } from '../api/ai-review';
import { AiChatReview, type AiChatReviewMessage } from './AiChatReview';

beforeAll(() => {
  Object.defineProperty(HTMLElement.prototype, 'scrollTo', {
    configurable: true,
    value: vi.fn(),
  });
});

function renderReview(
  currentTarget: string,
  suggestions: AiReviewSuggestion[],
  messageOverrides: Partial<AiChatReviewMessage> = {},
) {
  const messages: AiChatReviewMessage[] = [
    {
      id: 'review',
      sender: 'assistant',
      content: 'The existing translation preserves the meaning.',
      review: { score: 2, explanation: 'No concrete defect was found.' },
      suggestions,
      ...messageOverrides,
    },
  ];
  const props = {
    messages,
    currentTarget,
    input: '',
    onChangeInput: vi.fn(),
    onSubmit: vi.fn(),
    onUseSuggestion: vi.fn(),
    onRetryError: vi.fn(),
    isResponding: false,
  };
  return { ...render(<AiChatReview {...props} />), props };
}

describe('AiChatReview', () => {
  it.each([
    { score: 2, target: 'Compte', status: 'No change suggested' },
    { score: 2, target: 'Votre compte', status: 'Change suggested' },
    { score: 0, target: 'Compte', status: 'Review needed' },
  ])('shows the same visible result structure for $status', ({ score, target, status }) => {
    renderReview(
      'Compte',
      [{ content: target, confidenceLevel: 94, explanation: 'Detailed review.' }],
      {
        content: 'Detailed review.',
        review: { score, explanation: 'Detailed review.' },
      },
    );
    expect(screen.getByText(status)).toBeVisible();
    expect(screen.getByText(status).closest('details')).toBeNull();
    expect(screen.getAllByText('Detailed review.')).toHaveLength(1);
    expect(screen.getByText('Detailed review.')).toBeVisible();
    expect(screen.getByText('Detailed review.').closest('details')).toBeNull();
    expect(screen.queryByText('Report')).not.toBeInTheDocument();
    expect(screen.queryByText('Confidence')).not.toBeInTheDocument();
    expect(screen.queryByText('94')).not.toBeInTheDocument();
  });

  it('keeps follow-up reasoning visible once without confidence scores', () => {
    const { props, rerender } = renderReview('Compte', [
      { content: 'Compte', confidenceLevel: 94 },
    ]);
    rerender(
      <AiChatReview
        {...props}
        messages={[
          ...props.messages,
          { id: 'question', sender: 'user', content: 'Can you make it clearer?' },
          {
            id: 'follow-up',
            sender: 'assistant',
            content: 'Makes the owner explicit.',
            review: { score: 2, explanation: 'A wording alternative.' },
            suggestions: [
              {
                content: 'Votre compte',
                confidenceLevel: 91,
                explanation: 'Makes the owner explicit.',
              },
            ],
          },
        ]}
      />,
    );
    expect(screen.queryByText('Report')).not.toBeInTheDocument();
    expect(screen.queryByText('Confidence')).not.toBeInTheDocument();
    expect(screen.getByText('Change suggested')).toBeVisible();
    expect(screen.getByText('No change suggested')).toBeVisible();
    expect(screen.getAllByText('Makes the owner explicit.')).toHaveLength(1);
    expect(screen.getByText('Makes the owner explicit.')).toBeVisible();
    expect(screen.queryByText('A wording alternative.')).not.toBeInTheDocument();
    expect(screen.getByText('Votre compte')).toBeVisible();
    expect(screen.queryByText('91')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Use' })).toBeEnabled();
  });

  it('shows a repeated change explanation once and omits confidence when no score exists', () => {
    renderReview('Compte', [{ content: 'Votre compte', explanation: 'Clarifies the owner.' }], {
      content: 'Clarifies the owner.',
      review: { score: 1, explanation: 'Clarifies the owner.' },
    });
    expect(screen.getAllByText('Clarifies the owner.')).toHaveLength(1);
    expect(screen.getByText('Clarifies the owner.')).toBeVisible();
    expect(screen.getByText('Votre compte')).toBeVisible();
    expect(screen.queryByText('Report')).not.toBeInTheDocument();
    expect(screen.queryByText('Confidence')).not.toBeInTheDocument();
  });

  it('keeps the original-target explanation visible without repeating identical translation', () => {
    renderReview('Compte', [
      {
        content: 'Compte',
        confidenceLevel: 94,
        explanation: 'The wording correctly preserves the account label.',
      },
    ]);

    expect(screen.getByText('No change suggested')).toBeVisible();
    expect(screen.getAllByText('The wording correctly preserves the account label.')).toHaveLength(
      1,
    );
    expect(screen.getByText('The wording correctly preserves the account label.')).toBeVisible();
    expect(screen.queryByText('No concrete defect was found.')).not.toBeInTheDocument();
    expect(
      screen.queryByText('The existing translation preserves the meaning.'),
    ).not.toBeInTheDocument();
    expect(screen.queryByText('Compte')).not.toBeInTheDocument();
    expect(screen.queryByText('94')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Use' })).not.toBeInTheDocument();
  });

  it('falls back to the message when no original-target explanation was returned', () => {
    renderReview('Compte', [{ content: 'Compte', confidenceLevel: 94 }]);

    expect(screen.queryByText('No concrete defect was found.')).not.toBeInTheDocument();
    expect(screen.getAllByText('The existing translation preserves the meaning.')).toHaveLength(1);
    expect(screen.getByText('The existing translation preserves the meaning.')).toBeVisible();
    expect(screen.queryByText('94')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Use' })).not.toBeInTheDocument();
  });

  it('falls back to review reasoning when no target explanation or message exists', () => {
    renderReview('Compte', [{ content: 'Compte' }], { content: ' ' });

    expect(screen.getByText('No concrete defect was found.')).toBeVisible();
    expect(screen.queryByText('Confidence')).not.toBeInTheDocument();
    expect(screen.queryByText('Compte')).not.toBeInTheDocument();
  });

  it('does not repeat the detailed explanation when another visible suggestion already shows it', () => {
    renderReview('Compte', [
      { content: 'Compte', confidenceLevel: 94 },
      {
        content: 'Votre compte',
        confidenceLevel: 91,
        explanation: 'The existing translation preserves the meaning.',
      },
    ]);

    expect(screen.getAllByText('The existing translation preserves the meaning.')).toHaveLength(1);
    expect(screen.getByText('The existing translation preserves the meaning.')).toBeVisible();
    expect(screen.getByText('Votre compte')).toBeVisible();
    expect(screen.getByRole('button', { name: 'Use' })).toBeEnabled();
    expect(screen.queryByText('No change suggested')).toBeNull();
  });

  it('retains an actual change and applies that suggestion when unchanged content is also returned', () => {
    const changed = {
      content: 'Votre compte',
      confidenceLevel: 91,
      explanation: 'Clarifies whose account is shown.',
    };
    const { props } = renderReview('Compte', [{ content: 'Compte', confidenceLevel: 94 }, changed]);

    expect(screen.queryByText('Compte')).not.toBeInTheDocument();
    expect(screen.queryByText('94')).not.toBeInTheDocument();
    expect(screen.queryByText('No change suggested')).toBeNull();
    expect(screen.getByText('Votre compte')).toBeVisible();
    expect(screen.getByText('Clarifies whose account is shown.')).toBeVisible();
    expect(screen.queryByText('91')).not.toBeInTheDocument();
    expect(screen.getByText('Change suggested')).toBeVisible();
    expect(screen.getAllByText('Clarifies whose account is shown.')).toHaveLength(1);
    const useButtons = screen.getAllByRole('button', { name: 'Use' });
    expect(useButtons).toHaveLength(1);
    fireEvent.click(useButtons[0]);
    expect(props.onUseSuggestion).toHaveBeenCalledExactlyOnceWith(changed);
  });

  it.each([
    { name: 'trailing space', currentTarget: 'Compte ', suggestedTarget: 'Compte' },
    { name: 'non-breaking space', currentTarget: '10 EUR', suggestedTarget: '10\u00a0EUR' },
  ])('preserves a $name change exactly', ({ currentTarget, suggestedTarget }) => {
    const suggestion = { content: suggestedTarget, confidenceLevel: 94 };
    const { props } = renderReview(currentTarget, [suggestion]);

    expect(screen.queryByText('No change suggested')).toBeNull();
    const useButton = screen.getByRole('button', { name: 'Use' });
    expect(useButton).toBeEnabled();
    expect(useButton.closest('details')).toBeNull();
    fireEvent.click(useButton);
    expect(props.onUseSuggestion).toHaveBeenCalledExactlyOnceWith(suggestion);
  });

  it('updates suggestion visibility when the current draft changes', () => {
    const suggestion = { content: 'Votre compte', confidenceLevel: 94 };
    const { rerender, props } = renderReview('Compte', [suggestion]);
    expect(screen.getByRole('button', { name: 'Use' })).toBeInTheDocument();

    rerender(<AiChatReview {...props} currentTarget="Votre compte" />);
    expect(screen.queryByRole('button', { name: 'Use' })).not.toBeInTheDocument();
    expect(screen.getByText('No change suggested')).toBeVisible();
    expect(screen.queryByText('94')).not.toBeInTheDocument();
    expect(screen.getByText('The existing translation preserves the meaning.')).toBeVisible();
    expect(screen.queryByText('Votre compte')).not.toBeInTheDocument();

    rerender(<AiChatReview {...props} currentTarget="Compte" />);
    expect(screen.queryByText('No change suggested')).toBeNull();
    expect(screen.getByRole('button', { name: 'Use' })).toBeEnabled();
  });

  it('keeps review failures and their retry action visible', () => {
    const { props } = renderReview('Compte', [], {
      review: undefined,
      content: 'AI review is taking too long. Please retry.',
      isError: true,
    });

    expect(screen.queryByText('No change suggested')).toBeNull();
    expect(screen.getByText('AI review is taking too long. Please retry.')).toBeVisible();
    const retry = screen.getByRole('button', { name: 'Retry' });
    expect(retry).toBeVisible();
    fireEvent.click(retry);
    expect(props.onRetryError).toHaveBeenCalledOnce();
  });
});
