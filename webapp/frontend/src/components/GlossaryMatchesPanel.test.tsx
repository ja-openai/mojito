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

function renderPanel(
  overrides: Partial<ApiMatchedGlossaryTerm> = {},
  currentTarget = 'View translation',
) {
  return render(
    <MemoryRouter>
      <GlossaryMatchesPanel
        matches={[{ ...match, ...overrides }]}
        isLoading={false}
        currentTarget={currentTarget}
        showHeader={false}
      />
    </MemoryRouter>,
  );
}

describe('GlossaryMatchesPanel', () => {
  it('prioritizes enforcement while keeping source order and grouping repeated matches', () => {
    const makeMatch = (
      id: number,
      source: string,
      enforcement: string | null,
      startIndex: number,
    ) => ({
      ...match,
      tmTextUnitId: id,
      source,
      enforcement,
      startIndex,
      endIndex: startIndex + source.length,
      matchedText: source,
    });
    const matches = [
      makeMatch(1, 'Suggested', 'REVIEW_ONLY', 0),
      makeMatch(2, 'Soft later', 'SOFT', 35),
      makeMatch(3, 'Legacy', null, 10),
      makeMatch(4, 'Hard later', 'HARD', 50),
      makeMatch(5, 'Soft earlier', 'SOFT', 20),
      makeMatch(6, 'Hard earlier', 'HARD', 40),
      makeMatch(6, 'Hard earlier', 'HARD', 70),
      makeMatch(7, 'Unknown', 'FUTURE_VALUE', 15),
    ];
    const originalMatches = structuredClone(matches);
    render(
      <MemoryRouter>
        <GlossaryMatchesPanel matches={matches} />
      </MemoryRouter>,
    );

    const cards = screen.getAllByRole('article');
    expect(
      cards.map((card) => within(card).getByRole('button').getAttribute('aria-label')),
    ).toEqual([
      'Details for Hard earlier',
      'Details for Hard later',
      'Details for Soft earlier',
      'Details for Soft later',
      'Details for Suggested',
      'Details for Legacy',
      'Details for Unknown',
    ]);
    expect(cards.map((card) => card.getAttribute('data-enforcement'))).toEqual([
      'hard',
      'hard',
      'soft',
      'soft',
      'recommendation',
      'unspecified',
      'unspecified',
    ]);
    expect(within(cards[0]).getByText('Hard enforced')).toBeInTheDocument();
    expect(within(cards[2]).getByText('Soft enforced')).toBeInTheDocument();
    expect(within(cards[4]).getByText('Recommendation')).toBeInTheDocument();
    expect(within(cards[5]).getByText('Enforcement unspecified')).toBeInTheDocument();
    expect(matches).toEqual(originalMatches);

    fireEvent.click(within(cards[0]).getByRole('button'));
    expect(screen.getByText('Hard earlier [40-52], Hard earlier [70-82]')).toBeInTheDocument();
  });

  it('shows compact cards and opens term details in a modal', () => {
    renderPanel();

    const card = screen.getByText('View').closest('article');
    expect(card).not.toBeNull();
    expect(within(card!).getByText('View translation')).toBeInTheDocument();
    expect(within(card!).getByRole('button', { name: 'Details for View' })).toHaveTextContent(
      'Details',
    );
    expect(within(card!).queryByRole('link')).not.toBeInTheDocument();
    expect(within(card!).queryByText('Action label in mobile settings.')).not.toBeInTheDocument();
    expect(
      within(card!).queryByText('A command that opens a detail screen.'),
    ).not.toBeInTheDocument();
    expect(within(card!).queryByText('Settings screenshot reference.')).not.toBeInTheDocument();
    expect(screen.queryByText('Action label in mobile settings.')).not.toBeInTheDocument();
    expect(screen.queryByText('Settings screenshot reference.')).not.toBeInTheDocument();

    fireEvent.click(within(card!).getByRole('button', { name: 'Details for View' }));

    const dialog = screen.getByRole('dialog', { name: 'Glossary term details for View' });
    const summary = within(dialog).getByLabelText('Selected glossary term');
    expect(within(dialog).getByText('Glossary term details')).toBeInTheDocument();
    expect(within(summary).getByText('Source term')).toBeInTheDocument();
    expect(within(summary).getByText('View')).toBeInTheDocument();
    expect(within(summary).getByText('Glossary translation')).toBeInTheDocument();
    expect(within(summary).getByText('View translation')).toBeInTheDocument();
    expect(within(dialog).getByText('Term details')).toBeInTheDocument();
    expect(within(dialog).getByText('Technical match details')).toBeInTheDocument();
    expect(within(dialog).getByText('Glossary name')).toBeInTheDocument();
    expect(within(dialog).getByText('Product UI')).toBeInTheDocument();
    expect(within(dialog).getByText('Term description')).toBeInTheDocument();
    expect(within(dialog).getByText('Matched source ranges')).toBeInTheDocument();
    expect(within(dialog).getByText('View [0-4]')).toBeInTheDocument();
    expect(within(dialog).getByText('Match type')).toBeInTheDocument();
    expect(within(dialog).getByText('Action label in mobile settings.')).toBeInTheDocument();
    expect(within(dialog).getByText('A command that opens a detail screen.')).toBeInTheDocument();
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
    expect(
      within(dialog).getByRole('link', {
        name: 'Open evidence screenshot: Settings screenshot reference.',
      }),
    ).toHaveAttribute('href', '/api/images/settings-view.png');
    expect(within(dialog).getByRole('link', { name: 'Open term' })).toHaveAttribute(
      'href',
      '/glossaries/12/terms/34',
    );

    fireEvent.click(within(dialog).getByRole('button', { name: 'Close' }));

    expect(
      screen.queryByRole('dialog', { name: 'Glossary term details for View' }),
    ).not.toBeInTheDocument();
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
    fireEvent.click(within(card!).getByRole('button', { name: 'Details for View' }));

    const dialog = screen.getByRole('dialog', { name: 'Glossary term details for View' });
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
      source: 'ExampleApp',
      target: null,
      doNotTranslate: true,
      matchedText: 'ExampleApp',
    });

    const card = screen.getByText('ExampleApp').closest('article');
    expect(card).not.toBeNull();
    fireEvent.click(within(card!).getByRole('button', { name: 'Details for ExampleApp' }));

    const dialog = screen.getByRole('dialog', { name: 'Glossary term details for ExampleApp' });
    const summary = within(dialog).getByLabelText('Selected glossary term');
    expect(within(summary).getByText('Source term')).toBeInTheDocument();
    expect(within(summary).getByText('ExampleApp')).toBeInTheDocument();
    expect(within(summary).getByText('Glossary translation')).toBeInTheDocument();
    expect(within(summary).getByText('Do not translate')).toBeInTheDocument();
    expect(within(dialog).queryByText('ExampleApp to Do not translate')).not.toBeInTheDocument();
  });

  it.each([
    'Iniciar <link>prueba</link>',
    '<strong>Iniciar</strong> <link title="x > y">prueba</link>',
  ])('accepts the required translation across inline tags: %s', (currentTarget) => {
    renderPanel({ source: 'Start trial', target: 'Iniciar prueba' }, currentTarget);

    fireEvent.click(screen.getByRole('button', { name: 'Details for Start trial' }));

    expect(
      screen.queryByText('Current target does not contain the required glossary translation.'),
    ).not.toBeInTheDocument();
  });

  it('accepts a do-not-translate phrase across inline tags', () => {
    renderPanel(
      { source: 'Sample name', target: null, doNotTranslate: true },
      'Sample <strong>name</strong>',
    );

    fireEvent.click(screen.getByRole('button', { name: 'Details for Sample name' }));

    expect(
      screen.queryByText('Current target does not preserve this do-not-translate term.'),
    ).not.toBeInTheDocument();
  });

  it.each([
    '<link title="Iniciar prueba">Leer más</link>',
    '<link title="Iniciar prueba"></link>',
    '<strong></strong>',
    '<Iniciar prueba>Leer más</Iniciar>',
    'Iniciar <br>prueba',
    'Iniciar <placeholder/>prueba',
    '<p>Iniciar </p><p>prueba</p>',
    'Iniciar <link>demostración</link>',
    'iniciar <link>prueba</link>',
  ])('keeps the warning when the visible phrase is absent: %s', (currentTarget) => {
    renderPanel({ source: 'Start trial', target: 'Iniciar prueba' }, currentTarget);

    fireEvent.click(screen.getByRole('button', { name: 'Details for Start trial' }));

    expect(
      screen.getByText('Current target does not contain the required glossary translation.'),
    ).toBeInTheDocument();
  });

  it('preserves case-insensitive checks across inline tags', () => {
    renderPanel(
      { source: 'Start trial', target: 'Iniciar prueba', caseSensitive: false },
      'iniciar <link>PRUEBA</link>',
    );

    fireEvent.click(screen.getByRole('button', { name: 'Details for Start trial' }));

    expect(
      screen.queryByText('Current target does not contain the required glossary translation.'),
    ).not.toBeInTheDocument();
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
    fireEvent.click(within(card!).getByRole('button', { name: 'Details for View' }));
    expect(
      screen.getByRole('dialog', { name: 'Glossary term details for View' }),
    ).toBeInTheDocument();

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
        screen.queryByRole('dialog', { name: 'Glossary term details for View' }),
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
    fireEvent.click(within(card!).getByRole('button', { name: 'Details for View' }));
    expect(
      within(screen.getByLabelText('Selected glossary term')).getByText('View translation'),
    ).toBeInTheDocument();

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
      expect(
        within(screen.getByLabelText('Selected glossary term')).getByText(
          'Updated view translation',
        ),
      ).toBeInTheDocument();
    });
    expect(screen.getByText('Updated target note.')).toBeInTheDocument();
  });

  it('does not show duplicate source note when it matches the term description', () => {
    renderPanel({
      comment: 'Shared product meaning.',
      definition: 'Shared product meaning.',
    });

    const card = screen.getByText('View').closest('article');
    expect(card).not.toBeNull();
    fireEvent.click(within(card!).getByRole('button', { name: 'Details for View' }));

    const dialog = screen.getByRole('dialog', { name: 'Glossary term details for View' });
    expect(within(dialog).getByText('Term description')).toBeInTheDocument();
    expect(within(dialog).getByText('Shared product meaning.')).toBeInTheDocument();
    expect(within(dialog).queryByText('Source note')).not.toBeInTheDocument();
  });

  it('shows every matched range for a grouped glossary term', () => {
    renderPanel({
      ranges: [
        { matchType: 'EXACT', startIndex: 0, endIndex: 4, matchedText: 'View' },
        { matchType: 'CASE_INSENSITIVE', startIndex: 12, endIndex: 16, matchedText: 'view' },
      ],
    });

    const card = screen.getByText('View').closest('article');
    expect(card).not.toBeNull();
    fireEvent.click(within(card!).getByRole('button', { name: 'Details for View' }));

    const dialog = screen.getByRole('dialog', { name: 'Glossary term details for View' });
    expect(within(dialog).getByText('View [0-4], view [12-16]')).toBeInTheDocument();
    expect(within(dialog).getByText('EXACT, CASE_INSENSITIVE')).toBeInTheDocument();
  });

  it('groups repeated raw matches by glossary term id', () => {
    const { container } = render(
      <MemoryRouter>
        <GlossaryMatchesPanel
          matches={[
            match,
            {
              ...match,
              startIndex: 12,
              endIndex: 16,
              matchedText: 'view',
              matchType: 'CASE_INSENSITIVE',
            },
          ]}
          isLoading={false}
          currentTarget="View translation"
          showHeader={false}
        />
      </MemoryRouter>,
    );

    expect(container.querySelectorAll('article')).toHaveLength(1);
    fireEvent.click(screen.getByRole('button', { name: 'Details for View' }));

    const dialog = screen.getByRole('dialog', { name: 'Glossary term details for View' });
    expect(within(dialog).getByText('View [0-4], view [12-16]')).toBeInTheDocument();
  });
});
