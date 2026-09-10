import { fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ComponentProps } from 'react';
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

function renderComposer(overrides: Partial<ComponentProps<typeof AiChatReview>> = {}) {
  const props: ComponentProps<typeof AiChatReview> = {
    messages: [],
    currentTarget: 'Compte',
    input: '',
    onChangeInput: vi.fn(),
    onSubmit: vi.fn(),
    onReview: vi.fn(),
    onUseSuggestion: vi.fn(),
    settings: {
      preset: 'fast',
      allowExtendedPresets: false,
      automaticDisabled: true,
      reviewStyle: 'corrections_and_alternatives',
      showScore: true,
      ready: true,
      isSaving: false,
      error: null,
      onChangePreset: vi.fn(),
      onChangeAutomaticDisabled: vi.fn(),
      onChangeReviewStyle: vi.fn(),
      onChangeShowScore: vi.fn(),
      onRetryLoad: vi.fn(),
    },
    isResponding: false,
    ...overrides,
  };
  return { ...render(<AiChatReview {...props} />), props };
}

describe('AiChatReview', () => {
  it('offers one Review CTA for the first empty round with automatic review off', async () => {
    const user = userEvent.setup();
    const { props } = renderComposer();
    const review = screen.getByRole('button', { name: 'Review' });
    expect(screen.getAllByRole('button')).toHaveLength(1);
    expect(review.closest('form')).toContainElement(screen.getByRole('textbox'));
    expect(review).toBeEnabled();
    await user.click(review);
    expect(props.onReview).toHaveBeenCalledOnce();
    expect(props.onSubmit).not.toHaveBeenCalled();
    expect(props.settings?.onChangeAutomaticDisabled).not.toHaveBeenCalled();
  });

  it('submits a first one-off review with Enter even if the input is whitespace', async () => {
    const user = userEvent.setup();
    const { props } = renderComposer({ input: '   ' });
    await user.click(screen.getByRole('textbox'));
    await user.keyboard('{Enter}');
    expect(props.onReview).toHaveBeenCalledOnce();
    expect(props.onSubmit).not.toHaveBeenCalled();
  });

  it('uses Ask for a typed first message and returns to Review when cleared', async () => {
    const user = userEvent.setup();
    const { props, rerender } = renderComposer();
    rerender(<AiChatReview {...props} input="Make it warmer" />);
    expect(screen.queryByRole('button', { name: 'Review' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Ask' }));
    expect(props.onSubmit).toHaveBeenCalledOnce();
    expect(props.onReview).not.toHaveBeenCalled();
    rerender(<AiChatReview {...props} input="" />);
    expect(screen.getByRole('button', { name: 'Review' })).toBeEnabled();
  });

  it('returns to Ask after the first result and requires a follow-up message', async () => {
    const user = userEvent.setup();
    const { props, rerender } = renderComposer({
      messages: [{ id: 'result', sender: 'assistant', content: 'No change suggested.' }],
    });
    expect(screen.queryByRole('button', { name: 'Review' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Ask' })).toBeDisabled();
    rerender(<AiChatReview {...props} input="Explain the terminology" />);
    await user.click(screen.getByRole('button', { name: 'Ask' }));
    expect(props.onSubmit).toHaveBeenCalledOnce();
    expect(props.onReview).not.toHaveBeenCalled();
  });

  it('keeps the empty CTA as Ask while automatic review is enabled', () => {
    const { props, rerender } = renderComposer();
    rerender(
      <AiChatReview {...props} settings={{ ...props.settings!, automaticDisabled: false }} />,
    );
    expect(screen.queryByRole('button', { name: 'Review' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Ask' })).toBeDisabled();
  });

  it.each(['loading', 'saving', 'responding'] as const)(
    'blocks one-off review while %s, including direct form submission',
    (state) => {
      const { props, rerender } = renderComposer();
      rerender(
        <AiChatReview
          {...props}
          isResponding={state === 'responding'}
          settings={{
            ...props.settings!,
            ready: state !== 'loading',
            isSaving: state === 'saving',
          }}
        />,
      );
      const review = screen.getByRole('button', { name: 'Review' });
      expect(review).toBeDisabled();
      fireEvent.submit(review.closest('form')!);
      expect(props.onReview).not.toHaveBeenCalled();
      expect(props.onSubmit).not.toHaveBeenCalled();
    },
  );

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
    expect(screen.getByLabelText('Model confidence: 94 out of 100')).toHaveTextContent('94');
  });

  it('keeps follow-up reasoning visible once with its own model confidence', () => {
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
    expect(screen.getByLabelText('Model confidence: 91 out of 100')).toHaveTextContent('91');
    expect(screen.getByLabelText('Model confidence: 91 out of 100')).toBeVisible();
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
    expect(screen.getByLabelText('Model confidence: 94 out of 100')).toHaveTextContent('94');
    expect(screen.queryByRole('button', { name: 'Use' })).not.toBeInTheDocument();
  });

  it('falls back to the message when no original-target explanation was returned', () => {
    renderReview('Compte', [{ content: 'Compte', confidenceLevel: 94 }]);

    expect(screen.queryByText('No concrete defect was found.')).not.toBeInTheDocument();
    expect(screen.getAllByText('The existing translation preserves the meaning.')).toHaveLength(1);
    expect(screen.getByText('The existing translation preserves the meaning.')).toBeVisible();
    expect(screen.getByLabelText('Model confidence: 94 out of 100')).toHaveTextContent('94');
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
    expect(screen.queryByLabelText('Model confidence: 94 out of 100')).not.toBeInTheDocument();
    expect(screen.queryByText('No change suggested')).toBeNull();
    expect(screen.getByText('Votre compte')).toBeVisible();
    expect(screen.getByText('Clarifies whose account is shown.')).toBeVisible();
    expect(screen.getByLabelText('Model confidence: 91 out of 100')).toHaveTextContent('91');
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
    expect(screen.getByLabelText('Model confidence: 94 out of 100')).toHaveTextContent('94');
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

  it('shows two optional alternatives without presenting them as corrections', () => {
    renderReview('Compte', [
      {
        content: 'Votre compte',
        kind: 'alternative',
        confidenceLevel: 94,
        explanation: 'Addresses the reader directly.',
      },
      {
        content: 'Mon compte',
        kind: 'alternative',
        confidenceLevel: 89,
        explanation: 'Uses the account owner’s perspective.',
      },
    ]);
    expect(screen.getByText('No correction suggested')).toBeVisible();
    expect(screen.getByText('No concrete defect was found.')).toBeVisible();
    expect(screen.getAllByText('Alternative wording')).toHaveLength(2);
    expect(screen.getAllByRole('button', { name: 'Use' })).toHaveLength(2);
    expect(screen.queryByText('Change suggested')).not.toBeInTheDocument();
    expect(screen.queryByText('Compte')).not.toBeInTheDocument();
    expect(screen.getByLabelText('Model confidence: 94 out of 100')).toBeVisible();
    expect(screen.getByLabelText('Model confidence: 89 out of 100')).toBeVisible();
  });

  it('distinguishes a correction from optional wording without hiding an identified issue', () => {
    renderReview(
      'Compte',
      [
        {
          content: 'Comptes',
          kind: 'correction',
          confidenceLevel: 97,
          explanation: 'Preserves the plural.',
        },
        {
          content: 'Vos comptes',
          kind: 'alternative',
          confidenceLevel: 93,
          explanation: 'Also addresses the reader.',
        },
      ],
      { review: { score: 0, explanation: 'The source refers to multiple accounts.' } },
    );
    expect(screen.getByText('Change suggested')).toBeVisible();
    expect(screen.getByText('Suggested correction')).toBeVisible();
    expect(screen.getByText('Alternative wording')).toBeVisible();
    expect(screen.queryByText('No correction suggested')).not.toBeInTheDocument();
  });

  it('keeps the defect assessment when only an alternative was returned', () => {
    renderReview('Compte', [{ content: 'Vos comptes', kind: 'alternative', confidenceLevel: 93 }], {
      review: { score: 0, explanation: 'Check the source plural.' },
    });
    expect(screen.getByText('Review needed')).toBeVisible();
    expect(screen.getByText('Check the source plural.')).toBeVisible();
  });

  it('does not imply a clean assessment when alternatives have no original review', () => {
    renderReview(
      'Compte',
      [{ content: 'Votre compte', kind: 'alternative', confidenceLevel: 93 }],
      {
        review: undefined,
        content: 'A useful alternative wording.',
      },
    );
    expect(screen.queryByText('No correction suggested')).not.toBeInTheDocument();
    expect(screen.queryByText('No change suggested')).not.toBeInTheDocument();
    expect(screen.getByText('Votre compte')).toBeVisible();
    expect(screen.getByRole('button', { name: 'Use' })).toBeEnabled();
  });

  it('shows only a compact score pill in the main result by default', () => {
    renderReview('Compte', [{ content: 'Compte', confidenceLevel: 94 }]);
    const score = screen.getByLabelText('Model confidence: 94 out of 100');
    expect(score).toBeVisible();
    expect(score).toHaveTextContent(/^94$/);
    expect(screen.queryByText(/Model confidence:/)).not.toBeInTheDocument();
    expect(screen.queryByText(/\/100/)).not.toBeInTheDocument();
    expect(screen.queryByText('Compte')).not.toBeInTheDocument();
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument();
    expect(
      screen.queryByRole('button', { name: 'About model confidence' }),
    ).not.toBeInTheDocument();
  });

  it('hides all confidence values without changing the result or requesting another review', () => {
    const { props, rerender } = renderComposer({
      messages: [
        {
          id: 'review',
          sender: 'assistant',
          content: 'A useful alternative.',
          review: { score: 2, explanation: 'No issue identified.' },
          suggestions: [{ content: 'Votre compte', kind: 'alternative', confidenceLevel: 91 }],
        },
      ],
    });
    expect(screen.getByLabelText('Model confidence: 91 out of 100')).toBeVisible();
    rerender(<AiChatReview {...props} settings={{ ...props.settings!, showScore: false }} />);
    expect(screen.queryByLabelText(/Model confidence:/)).not.toBeInTheDocument();
    expect(
      screen.queryByRole('button', { name: 'About model confidence' }),
    ).not.toBeInTheDocument();
    expect(screen.getByText('Votre compte')).toBeVisible();
    expect(screen.getByText('No correction suggested')).toBeVisible();
    expect(props.onReview).not.toHaveBeenCalled();
    expect(props.onSubmit).not.toHaveBeenCalled();
  });

  it.each([undefined, -1, 101, 94.5, Number.NaN])(
    'omits missing or invalid confidence %s',
    (confidenceLevel) => {
      renderReview('Compte', [{ content: 'Compte', confidenceLevel }], {
        review: { score: 2, explanation: 'No issue identified.' },
      });
      expect(screen.queryByLabelText(/Model confidence:/)).not.toBeInTheDocument();
      expect(
        screen.queryByRole('button', { name: 'About model confidence' }),
      ).not.toBeInTheDocument();
    },
  );
});
