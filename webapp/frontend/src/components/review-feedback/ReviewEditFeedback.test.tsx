import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import { isMaterialReviewEdit } from './review-edit-diff';
import { ReviewEditFeedback } from './ReviewEditFeedback';

describe('ReviewEditFeedback', () => {
  it('does not ask for cosmetic edits but does ask for changed meaning or protected tokens', () => {
    for (const [a, b] of [
      ['Hello', ' hello '],
      ['“Hello”', '„Hello“'],
      ['Hello!', 'hello.'],
    ])
      expect(isMaterialReviewEdit(a, b)).toBe(false);
    expect(isMaterialReviewEdit('Enable', 'Disable')).toBe(true);
    expect(isMaterialReviewEdit('Hello {name}', 'Hello {user}')).toBe(true);
    expect(isMaterialReviewEdit('10.5', '105')).toBe(true);
  });
  it('allows optional, reversible reasons and a bounded note without saving', () => {
    const onReason = vi.fn(),
      onNote = vi.fn();
    const { rerender } = render(
      <ReviewEditFeedback reason="" note="" onReason={onReason} onNote={onNote} />,
    );
    fireEvent.click(screen.getByRole('button', { name: 'Terminology' }));
    expect(onReason).toHaveBeenCalledWith('TERMINOLOGY');
    rerender(
      <ReviewEditFeedback
        reason="TERMINOLOGY"
        note="Context"
        onReason={onReason}
        onNote={onNote}
      />,
    );
    fireEvent.click(screen.getByRole('button', { name: 'Terminology' }));
    expect(onReason).toHaveBeenLastCalledWith('');
    expect(screen.getByRole('textbox', { name: 'AI feedback note' })).toHaveAttribute(
      'maxLength',
      '500',
    );
    fireEvent.change(screen.getByRole('textbox', { name: 'AI feedback note' }), {
      target: { value: 'Use permission wording.' },
    });
    expect(onNote).toHaveBeenCalledWith('Use permission wording.');
    expect(screen.queryByRole('button', { name: 'Save' })).not.toBeInTheDocument();
  });
});
