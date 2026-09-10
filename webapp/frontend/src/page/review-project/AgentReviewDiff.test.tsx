import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { AgentReviewDiff } from './AgentReviewDiff';

describe('AgentReviewDiff', () => {
  it('keeps the original and proposal separately readable with semantic change marks', () => {
    const { container } = render(
      <AgentReviewDiff original="Un mauvais exemple." proposed="Un bon exemple." localeTag="fr" />,
    );
    expect(container.querySelector('del')).toHaveTextContent('mauvais');
    expect(container.querySelector('ins')).toHaveTextContent('bon');
    expect(container.querySelectorAll('[dir="auto"][lang="fr"]')).toHaveLength(2);
  });

  it('distinguishes no replacement from an intentionally empty replacement', () => {
    const { rerender, container } = render(
      <AgentReviewDiff original="Original" proposed={null} localeTag="fr" />,
    );
    expect(screen.getByText('No replacement proposed')).toBeInTheDocument();
    expect(container.querySelector('del')).toBeNull();
    rerender(<AgentReviewDiff original="Original" proposed="" localeTag="fr" />);
    expect(screen.getByText('Empty translation proposed')).toBeInTheDocument();
    expect(container.querySelector('del')).toHaveTextContent('Original');
  });

  it('renders untrusted evidence text as text and preserves RTL and whitespace', () => {
    const { container } = render(
      <AgentReviewDiff
        original={'مرحبا\n  {name}'}
        proposed={'<img src=x>\n  {name}'}
        localeTag="ar"
      />,
    );
    expect(container.querySelector('img')).toBeNull();
    expect(container.querySelectorAll('[lang="ar"]')).toHaveLength(2);
    expect(container.querySelectorAll('.agent-review-diff__text')[1].textContent).toBe(
      '<img src=x>\n  {name}',
    );
  });
});
