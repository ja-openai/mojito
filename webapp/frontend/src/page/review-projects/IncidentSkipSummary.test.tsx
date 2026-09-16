import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';

import { IncidentSkipSummary } from './IncidentSkipSummary';

describe('IncidentSkipSummary', () => {
  it('groups a full batch by reason and only reveals three examples per reason', () => {
    const skipped = Array.from({ length: 500 }, (_, index) => ({
      incidentId: index + 1,
      reason: index < 497 ? 'String match is unresolved' : 'Locale match is unresolved',
    }));
    render(
      <MemoryRouter>
        <IncidentSkipSummary skipped={skipped} skippedIncidentCount={500} />
      </MemoryRouter>,
    );

    expect(screen.getByText('500 incidents not included')).toBeInTheDocument();
    const stringSummary = screen.getByText('String match is unresolved · 497 incidents');
    expect(screen.getByText('Locale match is unresolved · 3 incidents')).toBeInTheDocument();
    expect(screen.getAllByRole('link')).toHaveLength(6);
    for (const link of screen.getAllByRole('link')) expect(link).not.toBeVisible();

    fireEvent.click(stringSummary);
    expect(screen.getByText('Examples (3 of 497):')).toBeVisible();
    const example = screen.getByRole('link', { name: 'Incident #1 (opens in a new tab)' });
    expect(example).toBeVisible();
    expect(
      screen.getByRole('link', { name: 'Incident #498 (opens in a new tab)' }),
    ).not.toBeVisible();
    expect(example).toHaveAttribute('href', '/translation-incidents?incidentId=1');
    expect(example).toHaveAttribute('target', '_blank');
    expect(screen.queryByText('#4')).not.toBeInTheDocument();
  });

  it('labels reason counts that only cover retained details after multiple batches', () => {
    render(
      <MemoryRouter>
        <IncidentSkipSummary
          skipped={[{ incidentId: 20, reason: 'Asset is deleted' }]}
          skippedIncidentCount={501}
        />
      </MemoryRouter>,
    );

    expect(screen.getByText('501 incidents not included')).toBeInTheDocument();
    expect(
      screen.getByText('Reasons below cover the most recent 1 of 501 incidents not included.'),
    ).toBeInTheDocument();
    expect(screen.getByText('Asset is deleted · 1 incident')).toBeInTheDocument();
  });
});
