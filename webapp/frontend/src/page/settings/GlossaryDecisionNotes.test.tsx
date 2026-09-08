import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import { GlossaryDecisionNotes } from './GlossaryDecisionNotes';

describe('GlossaryDecisionNotes', () => {
  it('opens document and Slack links while preserving note text and safe protocols', () => {
    const caption =
      'Changed after [review](https://docs.example.com/term_(v2)).\n' +
      'Discussion: https://example.slack.com/archives/C123/p456?thread_ts=123.456&cid=C123\n' +
      'See https://docs.example.com/term_(v2) again; javascript:alert(1), data:text/html,bad, ' +
      'https:// and <script>alert(1)</script> are plain text.';
    render(
      <GlossaryDecisionNotes
        notes={[{ id: 'note', caption }]}
        canEdit={false}
        onAdd={vi.fn()}
        onChange={vi.fn()}
        onRemove={vi.fn()}
      />,
    );

    const links = screen.getAllByRole('link');
    expect(links).toHaveLength(2);
    expect(links[0]).toHaveAttribute('href', 'https://docs.example.com/term_(v2)');
    expect(links[1]).toHaveAttribute(
      'href',
      'https://example.slack.com/archives/C123/p456?thread_ts=123.456&cid=C123',
    );
    for (const link of links) {
      expect(link).toHaveAttribute('target', '_blank');
      expect(link).toHaveAttribute('rel', 'noopener noreferrer');
    }
    expect(screen.getByText(/Changed after/)).toHaveTextContent('<script>alert(1)</script>');
    expect(document.querySelector('script')).toBeNull();
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });
});
