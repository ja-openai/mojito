import { render, screen, waitFor } from '@testing-library/react';
import { fireEvent } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import type * as ReviewProjectsApi from '../../api/review-projects';
import type { ApiReviewProjectDetail, ApiReviewProjectTextUnit } from '../../api/review-projects';
import { ReviewProjectFindReplacePage } from './ReviewProjectFindReplacePage';

const useReviewProjectDetailMock = vi.hoisted(() => vi.fn());
const deleteReviewProjectTextUnitSuggestionMock = vi.hoisted(() => vi.fn());
const saveReviewProjectTextUnitDecisionMock = vi.hoisted(() => vi.fn());
const saveReviewProjectTextUnitSuggestionMock = vi.hoisted(() => vi.fn());

vi.mock('../../api/review-projects', async () => {
  const actual = await vi.importActual<typeof ReviewProjectsApi>('../../api/review-projects');
  return {
    ...actual,
    deleteReviewProjectTextUnitSuggestion: deleteReviewProjectTextUnitSuggestionMock,
    saveReviewProjectTextUnitDecision: saveReviewProjectTextUnitDecisionMock,
    saveReviewProjectTextUnitSuggestion: saveReviewProjectTextUnitSuggestionMock,
  };
});

vi.mock('../../hooks/useReviewProjectDetail', () => ({
  useReviewProjectDetail: useReviewProjectDetailMock,
}));

vi.mock('../../hooks/useVisibleTextEditorEnabled', () => ({
  useVisibleTextEditorEnabled: () => false,
}));

function buildProject(
  reviewProjectTextUnits: ApiReviewProjectTextUnit[] = [],
): ApiReviewProjectDetail {
  return {
    id: 7,
    type: 'NORMAL',
    status: 'OPEN',
    locale: { id: 3, bcp47Tag: 'fr' },
    reviewProjectRequest: {
      id: 70,
      name: 'Translation cleanup',
      notes: null,
      screenshotImageIds: [],
    },
    reviewProjectTextUnits,
  };
}

function renderPage(path = '/review-projects/7/find-replace') {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route
          path="/review-projects/:projectId/find-replace"
          element={<ReviewProjectFindReplacePage />}
        />
        <Route path="/review-projects/:projectId" element={<div>Review project destination</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('ReviewProjectFindReplacePage', () => {
  beforeEach(() => {
    useReviewProjectDetailMock.mockReset();
    deleteReviewProjectTextUnitSuggestionMock.mockReset();
    saveReviewProjectTextUnitDecisionMock.mockReset();
    saveReviewProjectTextUnitSuggestionMock.mockReset();
    useReviewProjectDetailMock.mockReturnValue({
      data: buildProject(),
      error: null,
      isError: false,
      isLoading: false,
    });
  });

  it('defaults literal matching to target-case-sensitive', () => {
    renderPage();

    expect(useReviewProjectDetailMock).toHaveBeenCalledWith(7);
    expect(screen.getByRole('checkbox', { name: 'Match case in target' })).toBeChecked();
    expect(screen.getByRole('checkbox', { name: 'Whole words' })).not.toBeChecked();
    expect(screen.getByRole('checkbox', { name: 'Regex' })).not.toBeChecked();
    expect(screen.getByRole('checkbox', { name: 'Preserve case' })).not.toBeChecked();
  });

  it('renders the project header as a single title with find-replace context', () => {
    renderPage();

    expect(screen.getByRole('heading', { name: 'Translation cleanup' })).toBeInTheDocument();
    expect(screen.getAllByText('Translation cleanup')).toHaveLength(1);
    expect(screen.getByText('Find and replace')).toBeInTheDocument();
  });

  it('shows current-to-working diffs after applying a replacement', () => {
    useReviewProjectDetailMock.mockReturnValue({
      data: buildProject([
        buildTextUnit({
          target:
            'Activé par défaut car Thinking n’est pas activé. Auto enverra vers Thinking Mini quand un raisonnement est demandé.',
        }),
      ]),
      error: null,
      isError: false,
      isLoading: false,
    });

    const { container } = renderPage();

    expect(screen.queryByText('From find/replace')).not.toBeInTheDocument();

    fireEvent.change(screen.getByLabelText('Find'), { target: { value: 'Thinking' } });
    fireEvent.change(screen.getByLabelText('Replace'), {
      target: { value: 'putsomethingweirdfortesting' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Replace all' }));

    expect(
      container.querySelector('.review-find-replace-row__diff-part.is-removed')?.textContent,
    ).toBe('Thinking');
    expect(
      container.querySelector('.review-find-replace-row__diff-part.is-added')?.textContent,
    ).toBe('putsomethingweirdfortesting');
    expect(screen.getByText('From find/replace')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Replace all' })).toHaveAttribute(
      'title',
      'No matches left in the working target. Undo or reset to search the original project targets again.',
    );
  });

  it('can replace one selected match before replacing all remaining matches', () => {
    useReviewProjectDetailMock.mockReturnValue({
      data: buildProject([
        buildTextUnit({
          target: 'Thinking Thinking',
        }),
      ]),
      error: null,
      isError: false,
      isLoading: false,
    });

    const { container } = renderPage();

    fireEvent.change(screen.getByLabelText('Find'), { target: { value: 'Thinking' } });
    fireEvent.change(screen.getByLabelText('Replace'), { target: { value: 'Super' } });

    fireEvent.click(screen.getByRole('button', { name: 'Replace' }));
    expect(container.textContent).toContain('Super Thinking');
    expect(container.textContent).not.toContain('Super Super');

    fireEvent.click(screen.getByRole('button', { name: 'Replace all' }));
    expect(container.textContent).toContain('Super Super');
  });

  it('stages changed working rows back to the review project as staged suggestions', async () => {
    const textUnit = buildTextUnit({ target: 'Réflexionbam...' });
    saveReviewProjectTextUnitSuggestionMock.mockImplementation(
      ({
        target,
        source,
        notes,
        previousTarget,
      }: Parameters<typeof ReviewProjectsApi.saveReviewProjectTextUnitSuggestion>[0]) =>
        Promise.resolve({
          ...textUnit,
          reviewProjectTextUnitSuggestion: {
            id: 901,
            target,
            source,
            notes,
            previousTarget,
          },
        }),
    );
    useReviewProjectDetailMock.mockReturnValue({
      data: buildProject([textUnit]),
      error: null,
      isError: false,
      isLoading: false,
    });

    renderPage();

    fireEvent.change(screen.getByLabelText('Find'), { target: { value: 'Réflexionbam' } });
    fireEvent.change(screen.getByLabelText('Replace'), { target: { value: 'Raisonnement' } });
    fireEvent.click(screen.getByRole('button', { name: 'Replace all' }));
    fireEvent.click(screen.getByRole('button', { name: 'Stage in project' }));

    await waitFor(() => expect(saveReviewProjectTextUnitSuggestionMock).toHaveBeenCalledTimes(1));
    expect(screen.getByText('Review project destination')).toBeInTheDocument();
    expect(saveReviewProjectTextUnitDecisionMock).not.toHaveBeenCalled();
    expect(saveReviewProjectTextUnitSuggestionMock).toHaveBeenCalledWith({
      textUnitId: 101,
      target: 'Raisonnement...',
      source: 'FIND_REPLACE',
      notes: null,
      previousTarget: 'Réflexionbam...',
      expectedCurrentTmTextUnitVariantId: null,
    });
  });

  it('clears persisted staged suggestions when resetting the project state', async () => {
    const textUnit = buildTextUnit({
      target: 'Réflexionbam...',
      suggestionTarget: 'Raisonnement...',
    });
    deleteReviewProjectTextUnitSuggestionMock.mockResolvedValue({
      ...textUnit,
      reviewProjectTextUnitSuggestion: null,
    });
    useReviewProjectDetailMock.mockReturnValue({
      data: buildProject([textUnit]),
      error: null,
      isError: false,
      isLoading: false,
    });

    renderPage();

    expect(screen.getByText('From find/replace')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Reset' }));

    await screen.findByText('Cleared 1 staged suggestion.');
    await waitFor(() => expect(deleteReviewProjectTextUnitSuggestionMock).toHaveBeenCalledTimes(1));
    expect(deleteReviewProjectTextUnitSuggestionMock).toHaveBeenCalledWith({ textUnitId: 101 });
    expect(screen.queryByText('From find/replace')).not.toBeInTheDocument();
    expect(screen.getByDisplayValue('Réflexionbam...')).toBeInTheDocument();
  });

  it('can accept and mark changed working rows decided when applying to the project', async () => {
    const textUnit = buildTextUnit({ target: 'Réflexionbam...' });
    saveReviewProjectTextUnitDecisionMock.mockResolvedValue({
      ...textUnit,
      currentTmTextUnitVariant: {
        id: 301,
        content: 'Raisonnement...',
        status: 'APPROVED',
        includedInLocalizedFile: true,
        comment: null,
      },
      reviewProjectTextUnitDecision: {
        decisionState: 'DECIDED',
        notes: null,
        decisionTmTextUnitVariant: {
          id: 301,
          content: 'Raisonnement...',
          status: 'APPROVED',
          includedInLocalizedFile: true,
          comment: null,
        },
      },
    });
    useReviewProjectDetailMock.mockReturnValue({
      data: buildProject([textUnit]),
      error: null,
      isError: false,
      isLoading: false,
    });

    renderPage();

    fireEvent.change(screen.getByLabelText('Find'), { target: { value: 'Réflexionbam' } });
    fireEvent.change(screen.getByLabelText('Replace'), { target: { value: 'Raisonnement' } });
    fireEvent.click(screen.getByRole('button', { name: 'Replace all' }));
    fireEvent.click(screen.getByRole('button', { name: 'Accept + decide' }));
    fireEvent.click(screen.getByRole('button', { name: 'Accept changes' }));

    await waitFor(() => expect(saveReviewProjectTextUnitDecisionMock).toHaveBeenCalledTimes(1));
    expect(screen.getByText('Review project destination')).toBeInTheDocument();
    expect(saveReviewProjectTextUnitDecisionMock).toHaveBeenCalledWith({
      textUnitId: 101,
      target: 'Raisonnement...',
      comment: null,
      status: 'APPROVED',
      includedInLocalizedFile: true,
      decisionState: 'DECIDED',
      expectedCurrentTmTextUnitVariantId: null,
      decisionNotes: null,
    });
  });
});

function buildTextUnit({
  target,
  suggestionTarget = null,
}: {
  target: string;
  suggestionTarget?: string | null;
}): ApiReviewProjectTextUnit {
  return {
    id: 101,
    tmTextUnit: {
      id: 86,
      name: 'message.default-on',
      content:
        'Default on because Thinking is not enabled. Auto will route to Thinking Mini when reasoning is requested.',
      comment: 'Reason for disabling GPT-5-Thinking-Mini Auto Router toggle.',
      asset: null,
      wordCount: 12,
    },
    baselineTmTextUnitVariant: {
      id: 201,
      content: target,
      status: 'REVIEW_NEEDED',
      includedInLocalizedFile: true,
      comment: null,
    },
    currentTmTextUnitVariant: null,
    reviewProjectTextUnitDecision: {
      decisionState: 'PENDING',
      notes: null,
      decisionTmTextUnitVariant: null,
    },
    reviewProjectTextUnitSuggestion:
      suggestionTarget == null
        ? null
        : {
            id: 901,
            target: suggestionTarget,
            source: 'FIND_REPLACE',
            notes: null,
            previousTarget: target,
          },
    terminologyFeedbacks: [],
  };
}
