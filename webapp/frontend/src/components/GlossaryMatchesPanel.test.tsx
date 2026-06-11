import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';

import type { ApiMatchedGlossaryTerm } from '../api/glossaries';
import { GlossaryMatchesPanel } from './GlossaryMatchesPanel';

const match: ApiMatchedGlossaryTerm = {
  glossaryId: 12,
  glossaryName: 'Product UI',
  tmTextUnitId: 34,
  source: 'View',
  comment: 'Action label in mobile settings.',
  definition: 'A command that opens a detail screen.',
  partOfSpeech: 'Verb',
  termType: 'UI label',
  enforcement: 'Required',
  status: 'Approved',
  provenance: 'Human curated',
  target: 'View translation',
  targetComment: 'Use the short noun form in Bulgarian UI.',
  doNotTranslate: false,
  caseSensitive: true,
  matchType: 'EXACT',
  startIndex: 0,
  endIndex: 4,
  matchedText: 'View',
  evidence: [
    {
      evidenceType: 'SCREENSHOT',
      caption: 'Settings screenshot reference.',
      imageKey: 'settings-view.png',
    },
    {
      evidenceType: 'NOTE',
      caption: 'This appears next to display preferences.',
    },
  ],
};

function renderPanel(overrides: Partial<ApiMatchedGlossaryTerm> = {}) {
  return render(
    <MemoryRouter>
      <GlossaryMatchesPanel
        matches={[{ ...match, ...overrides }]}
        isLoading={false}
        currentTarget="View translation"
        showHeader={false}
      />
    </MemoryRouter>,
  );
}

describe('GlossaryMatchesPanel', () => {
  it('shows compact cards and opens term details in a modal', () => {
    renderPanel();

    const card = screen.getByText('View').closest('article');
    expect(card).not.toBeNull();
    expect(within(card!).getByText('View translation')).toBeInTheDocument();
    expect(within(card!).getByRole('button', { name: 'Details' })).toBeInTheDocument();
    expect(within(card!).queryByRole('link')).not.toBeInTheDocument();
    expect(screen.queryByText('Action label in mobile settings.')).not.toBeInTheDocument();
    expect(screen.queryByText('Settings screenshot reference.')).not.toBeInTheDocument();

    fireEvent.click(within(card!).getByRole('button', { name: 'Details' }));

    const dialog = screen.getByRole('dialog', { name: 'Glossary term details' });
    expect(within(dialog).getByText('Glossary term details')).toBeInTheDocument();
    expect(within(dialog).getByText('Source term: View')).toBeInTheDocument();
    expect(within(dialog).getByText('Translation: View translation')).toBeInTheDocument();
    expect(within(dialog).getByText('Product UI')).toBeInTheDocument();
    expect(within(dialog).getByText('Action label in mobile settings.')).toBeInTheDocument();
    expect(within(dialog).getByText('Evidence screenshots')).toBeInTheDocument();
    expect(within(dialog).getByText('1 screenshot')).toBeInTheDocument();
    expect(
      within(dialog).getByText('Use the short noun form in Bulgarian UI.'),
    ).toBeInTheDocument();
    expect(
      within(dialog).getByText('This appears next to display preferences.'),
    ).toBeInTheDocument();
    expect(within(dialog).getByText('Settings screenshot reference.')).toBeInTheDocument();
    expect(
      within(dialog).getByRole('img', { name: 'Settings screenshot reference.' }),
    ).toHaveAttribute('src', '/api/images/settings-view.png');
    expect(within(dialog).getByRole('link', { name: 'Open term' })).toHaveAttribute(
      'href',
      '/glossaries/12/terms/34',
    );

    fireEvent.click(within(dialog).getByRole('button', { name: 'Close' }));

    expect(screen.queryByRole('dialog', { name: 'Glossary term details' })).not.toBeInTheDocument();
  });

  it('shows a screenshot empty state with the term link when no screenshots are attached', () => {
    renderPanel({
      evidence: [
        {
          evidenceType: 'NOTE',
          caption: 'No visual reference yet.',
        },
      ],
    });

    const card = screen.getByText('View').closest('article');
    expect(card).not.toBeNull();
    fireEvent.click(within(card!).getByRole('button', { name: 'Details' }));

    const dialog = screen.getByRole('dialog', { name: 'Glossary term details' });
    expect(
      within(dialog).getByText(
        'No screenshots attached yet; open the term to add screenshot evidence.',
      ),
    ).toBeInTheDocument();
    expect(within(dialog).getByRole('link', { name: 'Open term' })).toHaveAttribute(
      'href',
      '/glossaries/12/terms/34',
    );
  });

  it('labels do-not-translate detail requirements without directional copy', () => {
    renderPanel({
      source: 'ChatGPT',
      target: null,
      doNotTranslate: true,
      matchedText: 'ChatGPT',
    });

    const card = screen.getByText('ChatGPT').closest('article');
    expect(card).not.toBeNull();
    fireEvent.click(within(card!).getByRole('button', { name: 'Details' }));

    const dialog = screen.getByRole('dialog', { name: 'Glossary term details' });
    expect(within(dialog).getByText('Source term: ChatGPT')).toBeInTheDocument();
    expect(within(dialog).getByText('Translation: Do not translate')).toBeInTheDocument();
    expect(within(dialog).queryByText('ChatGPT to Do not translate')).not.toBeInTheDocument();
  });

  it('closes open details when the selected match disappears', async () => {
    const { rerender } = render(
      <MemoryRouter>
        <GlossaryMatchesPanel
          matches={[match]}
          isLoading={false}
          currentTarget="View translation"
          showHeader={false}
        />
      </MemoryRouter>,
    );

    const card = screen.getByText('View').closest('article');
    expect(card).not.toBeNull();
    fireEvent.click(within(card!).getByRole('button', { name: 'Details' }));
    expect(screen.getByRole('dialog', { name: 'Glossary term details' })).toBeInTheDocument();

    rerender(
      <MemoryRouter>
        <GlossaryMatchesPanel
          matches={[
            {
              ...match,
              tmTextUnitId: 99,
              source: 'Mobile preview',
              target: 'Mobile preview translation',
              matchedText: 'Mobile preview',
              endIndex: 14,
            },
          ]}
          isLoading={false}
          currentTarget="Mobile preview translation"
          showHeader={false}
        />
      </MemoryRouter>,
    );

    await waitFor(() => {
      expect(
        screen.queryByRole('dialog', { name: 'Glossary term details' }),
      ).not.toBeInTheDocument();
    });
  });

  it('updates open details when the selected match is refreshed', async () => {
    const { rerender } = render(
      <MemoryRouter>
        <GlossaryMatchesPanel
          matches={[match]}
          isLoading={false}
          currentTarget="View translation"
          showHeader={false}
        />
      </MemoryRouter>,
    );

    const card = screen.getByText('View').closest('article');
    expect(card).not.toBeNull();
    fireEvent.click(within(card!).getByRole('button', { name: 'Details' }));
    expect(screen.getByText('Translation: View translation')).toBeInTheDocument();

    rerender(
      <MemoryRouter>
        <GlossaryMatchesPanel
          matches={[
            {
              ...match,
              target: 'Updated view translation',
              targetComment: 'Updated target note.',
            },
          ]}
          isLoading={false}
          currentTarget="Updated view translation"
          showHeader={false}
        />
      </MemoryRouter>,
    );

    await waitFor(() => {
      expect(screen.getByText('Translation: Updated view translation')).toBeInTheDocument();
    });
    expect(screen.getByText('Updated target note.')).toBeInTheDocument();
  });
});
