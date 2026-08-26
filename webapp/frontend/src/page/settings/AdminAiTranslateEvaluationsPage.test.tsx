import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { AdminAiTranslateEvaluationsPage } from './AdminAiTranslateEvaluationsPage';

const mocks = vi.hoisted(() => ({
  fetchAiTranslateEvaluations: vi.fn(),
}));

vi.mock('../../api/ai-translate-evaluations', () => mocks);

vi.mock('../../hooks/useUser', () => ({
  useUser: () => ({ role: 'ROLE_ADMIN' }),
}));

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
    },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <AdminAiTranslateEvaluationsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('AdminAiTranslateEvaluationsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.fetchAiTranslateEvaluations.mockResolvedValue({
      summary: {
        reviewedCount: 2,
        exactAcceptedCount: 1,
        editedCount: 1,
        exactAcceptanceRate: 0.5,
        averageNormalizedEditDistance: 0.25,
      },
      cohorts: [
        {
          promptFingerprint: '0123456789abcdef',
          model: 'example-model',
          reasoningEffort: 'medium',
          textVerbosity: 'low',
          localeTag: 'fr-FR',
          summary: {
            reviewedCount: 2,
            exactAcceptedCount: 1,
            editedCount: 1,
            exactAcceptanceRate: 0.5,
            averageNormalizedEditDistance: 0.25,
          },
        },
      ],
      examples: [
        {
          attemptId: 1,
          reviewedAt: '2026-08-26T10:15:30Z',
          reviewProjectId: 10,
          repositoryId: 20,
          repositoryName: 'sample-repository',
          tmTextUnitId: 30,
          textUnitName: 'welcome',
          source: 'Hello',
          sourceDescription: 'Greeting',
          localeTag: 'fr-FR',
          model: 'example-model',
          promptFingerprint: '0123456789abcdef',
          reasoningEffort: 'medium',
          textVerbosity: 'low',
          aiTarget: 'Salut',
          acceptedTarget: 'Bonjour',
          decisionNotes: 'Use the standard greeting.',
          exactAccepted: false,
          normalizedEditDistance: 0.57,
        },
        {
          attemptId: 2,
          reviewedAt: '2026-08-26T10:16:30Z',
          reviewProjectId: 10,
          repositoryId: 20,
          repositoryName: 'sample-repository',
          tmTextUnitId: 31,
          textUnitName: 'thanks',
          source: 'Thanks',
          sourceDescription: null,
          localeTag: 'fr-FR',
          model: 'example-model',
          promptFingerprint: '0123456789abcdef',
          reasoningEffort: 'medium',
          textVerbosity: 'low',
          aiTarget: 'Merci',
          acceptedTarget: 'Merci',
          decisionNotes: null,
          exactAccepted: true,
          normalizedEditDistance: 0,
        },
      ],
    });
  });

  it('shows prompt cohorts and defaults to actionable edited examples', async () => {
    renderPage();

    expect(await screen.findByText('Learn from review edits')).toBeInTheDocument();
    expect((await screen.findAllByText('50%')).length).toBeGreaterThan(0);
    expect(screen.getByText('0123456789')).toBeInTheDocument();
    expect(screen.getByText('Salut')).toBeInTheDocument();
    expect(screen.getByText('Bonjour')).toBeInTheDocument();
    expect(screen.getByText('Use the standard greeting.')).toBeInTheDocument();
    expect(screen.queryByText('Merci')).not.toBeInTheDocument();
  });
});
