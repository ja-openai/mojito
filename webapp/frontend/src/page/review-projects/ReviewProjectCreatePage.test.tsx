import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import type {
  IncidentReviewProjectRequest,
  IncidentReviewProjectResult,
} from '../../api/incident-review-projects';
import type { ApiRepository } from '../../api/repositories';
import type * as ReviewProjectsApi from '../../api/review-projects';
import type { ReviewProjectSourceMode } from './ReviewProjectCreateForm';
import { ReviewProjectCreatePage } from './ReviewProjectCreatePage';

const createRequestMock = vi.hoisted(() => vi.fn());
const previewIncidentsMock = vi.hoisted(() =>
  vi.fn<(request: IncidentReviewProjectRequest) => Promise<IncidentReviewProjectResult>>(),
);
const createIncidentsMock = vi.hoisted(() =>
  vi.fn<(request: IncidentReviewProjectRequest) => Promise<IncidentReviewProjectResult>>(),
);

vi.mock('../../api/incident-review-projects', () => ({
  previewIncidentReviewProjects: previewIncidentsMock,
  createIncidentReviewProjects: createIncidentsMock,
}));

vi.mock('../../api/review-projects', async (importActual) => ({
  ...(await importActual<typeof ReviewProjectsApi>()),
  createReviewProjectRequest: createRequestMock,
}));

const repositories: ApiRepository[] = [
  {
    id: 11,
    name: 'Product',
    sourceLocale: { bcp47Tag: 'en' },
    repositoryLocales: [
      {
        locale: { bcp47Tag: 'fr' },
        parentLocale: { bcp47Tag: 'en' },
        toBeFullyTranslated: true,
      },
      {
        locale: { bcp47Tag: 'de' },
        parentLocale: { bcp47Tag: 'en' },
        toBeFullyTranslated: true,
      },
    ],
  },
];

const createdResponse: ReviewProjectsApi.ReviewProjectCreateResponse = {
  requestId: 100,
  requestName: 'Review project',
  localeTags: ['fr'],
  dueDate: '2026-09-10T12:00:00Z',
  projectIds: [101, 102],
  requestedLocaleCount: 1,
  createdLocaleCount: 1,
  skippedLocaleCount: 0,
  erroredLocaleCount: 0,
  localeResults: [{ localeTag: 'fr', status: 'CREATED', textUnitCount: 2, projectCount: 2 }],
};

function renderPage(
  sourceMode: ReviewProjectSourceMode = 'TEXT_UNITS',
  selectLocale = true,
  availableRepositories = repositories,
) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  queryClient.setQueryData(['repositories'], availableRepositories);
  queryClient.setQueryData(['teams', 'review-project-create'], [{ id: 31, name: 'Review team' }]);
  queryClient.setQueryData(
    ['review-feature-options', 'review-project-create'],
    [
      { id: 21, name: 'Feature A', enabled: true },
      { id: 22, name: 'Feature B', enabled: true },
    ],
  );
  queryClient.setQueryData(['workbench-collections'], {
    collections: [],
    activeCollectionId: null,
  });

  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter
        initialEntries={[
          {
            pathname: '/review-projects/create',
            state: {
              sourceMode,
              tmTextUnitIds: [42],
              repositoryIds: [11],
              defaultDueDate: '2026-09-10T12:00',
            },
          },
        ]}
      >
        <Routes>
          <Route path="/review-projects/create" element={<ReviewProjectCreatePage />} />
          <Route path="/review-projects" element={<div>Review projects created</div>} />
          <Route path="/review-projects/:id" element={<div>Review project details</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );

  if (sourceMode === 'REVIEW_FEATURE') {
    fireEvent.click(screen.getByRole('button', { name: 'Select review features' }));
    fireEvent.click(screen.getByRole('checkbox', { name: /Feature A/ }));
    fireEvent.click(screen.getByRole('checkbox', { name: /Feature B/ }));
    fireEvent.click(screen.getByRole('button', { name: 'Select review features' }));
  }

  if (selectLocale) {
    fireEvent.click(screen.getByRole('button', { name: 'Locales' }));
    fireEvent.click(screen.getByRole('checkbox', { name: /French/ }));
    fireEvent.click(screen.getByRole('button', { name: 'Locales' }));
  }

  return screen.getByRole('textbox', { name: /^Max word count per project \(optional\)/ });
}

function incidentResult(
  overrides: Partial<IncidentReviewProjectResult> = {},
): IncidentReviewProjectResult {
  return {
    eligibleIncidentCount: 0,
    skippedIncidentCount: 0,
    projectCount: 0,
    localeTags: [],
    projectIds: [],
    requestIds: [],
    skipped: [],
    hasMore: false,
    limitReached: false,
    ...overrides,
  };
}

beforeEach(() => {
  vi.clearAllMocks();
  createRequestMock.mockResolvedValue(createdResponse);
  previewIncidentsMock.mockResolvedValue({
    eligibleIncidentCount: 3,
    skippedIncidentCount: 0,
    projectCount: 1,
    incidentBatches: [[101, 102, 103]],
    limitReached: false,
    localeTags: ['fr'],
    projectIds: [],
    requestIds: [],
    skipped: [],
  });
  createIncidentsMock.mockResolvedValue({
    eligibleIncidentCount: 3,
    skippedIncidentCount: 0,
    projectCount: 1,
    localeTags: ['fr'],
    projectIds: [201],
    requestIds: [200],
    skipped: [],
  });
});

function selectIncidentSource() {
  fireEvent.click(screen.getByRole('button', { name: 'Incidents' }));
  fireEvent.click(screen.getByRole('button', { name: 'Select team for default assignment' }));
  fireEvent.click(screen.getByRole('button', { name: 'Review team (#31)' }));
}

describe('incident review project creation', () => {
  it('summarizes exclusions in the preview without listing every incident', async () => {
    previewIncidentsMock.mockResolvedValueOnce({
      eligibleIncidentCount: 1,
      incidentBatches: [[501]],
      skippedIncidentCount: 499,
      projectCount: 1,
      localeTags: ['fr'],
      projectIds: [],
      requestIds: [],
      skipped: Array.from({ length: 499 }, (_, index) => ({
        incidentId: index + 1,
        reason: 'Locale match is unresolved',
      })),
    });
    renderPage('REPOSITORIES');
    selectIncidentSource();
    fireEvent.click(screen.getByRole('button', { name: 'Preview incidents' }));

    await screen.findByText('499 incidents not included');
    expect(screen.getByText('Locale match is unresolved · 499 incidents')).toBeInTheDocument();
    expect(screen.queryByText('#4')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Create' })).toBeEnabled();
  });

  it('requires explicit locale selection for the incident queue across repositories', async () => {
    renderPage('TEXT_UNITS', false);
    expect(screen.getByRole('button', { name: 'Create' })).toBeDisabled();
    selectIncidentSource();
    expect(screen.getByRole('button', { name: 'All eligible incidents' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    expect(screen.queryByRole('button', { name: 'Select repositories' })).not.toBeInTheDocument();
    expect(
      screen.queryByRole('button', { name: 'Select review features' }),
    ).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Preview incidents' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Create' })).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: 'Preview incidents' }));
    fireEvent.click(screen.getByRole('button', { name: 'Create' }));
    expect(previewIncidentsMock).not.toHaveBeenCalled();
    expect(createIncidentsMock).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole('button', { name: 'Locales' }));
    fireEvent.click(screen.getByRole('button', { name: 'Select all' }));
    fireEvent.click(screen.getByRole('button', { name: 'Locales' }));
    expect(screen.getByRole('button', { name: 'Locales' })).toHaveTextContent('All locales');
    expect(screen.getByRole('button', { name: 'Preview incidents' })).toBeEnabled();
    expect(screen.getByRole('button', { name: 'Create' })).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: 'Preview incidents' }));
    await screen.findByText(/3 eligible incidents/);
    expect(previewIncidentsMock.mock.calls[0][0]).toEqual(
      expect.objectContaining({
        allRepositories: true,
        repositoryIds: null,
        reviewFeatureIds: null,
        localeTags: ['fr', 'de'],
        teamId: 31,
      }),
    );
    fireEvent.click(screen.getByRole('button', { name: 'Create' }));
    await screen.findByRole('link', { name: 'Open review project #201' });
    expect(createIncidentsMock.mock.calls[0][0]).toEqual({
      ...previewIncidentsMock.mock.calls[0][0],
      incidentIds: [101, 102, 103],
    });
    expect(createRequestMock).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: 'Repositories' }));
    expect(screen.getByRole('button', { name: 'Select repositories' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Create' })).toBeDisabled();
  });

  it.each<ReviewProjectSourceMode>(['REPOSITORIES', 'REVIEW_FEATURE'])(
    'requires locales for incident review scoped to %s',
    (sourceMode) => {
      renderPage(sourceMode, false);
      expect(screen.getByRole('button', { name: 'Create' })).toBeDisabled();
      selectIncidentSource();
      fireEvent.click(
        screen.getByRole('button', {
          name: sourceMode === 'REPOSITORIES' ? 'Repositories' : 'Review feature',
        }),
      );
      expect(screen.getByRole('button', { name: 'Preview incidents' })).toBeDisabled();
      expect(screen.getByRole('button', { name: 'Create' })).toBeDisabled();
    },
  );

  it('disables preview and creation when the last locale is cleared after previewing', async () => {
    renderPage('REPOSITORIES');
    selectIncidentSource();
    fireEvent.click(screen.getByRole('button', { name: 'Preview incidents' }));
    await screen.findByText(/3 eligible incidents/);
    expect(screen.getByRole('button', { name: 'Create' })).toBeEnabled();

    fireEvent.click(screen.getByRole('button', { name: 'Locales' }));
    fireEvent.click(screen.getByRole('checkbox', { name: /French/ }));
    fireEvent.click(screen.getByRole('button', { name: 'Locales' }));

    expect(screen.queryByText(/3 eligible incidents/)).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Preview incidents' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Create' })).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: 'Preview incidents' }));
    fireEvent.click(screen.getByRole('button', { name: 'Create' }));
    expect(previewIncidentsMock).toHaveBeenCalledTimes(1);
    expect(createIncidentsMock).not.toHaveBeenCalled();
  });

  it('previews and creates one batch of incidents independently of translation status', async () => {
    renderPage('REPOSITORIES');
    selectIncidentSource();
    fireEvent.click(screen.getByRole('button', { name: 'Repositories' }));
    expect(screen.getByRole('group', { name: 'Incident scope' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Selected text units' })).not.toBeInTheDocument();
    expect(screen.queryByText('Status filter')).not.toBeInTheDocument();
    expect(
      screen.queryByText('Skip text units already in active review projects'),
    ).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Create' })).toBeDisabled();

    fireEvent.click(screen.getByRole('button', { name: 'Preview incidents' }));
    await screen.findByText(/3 eligible incidents/);
    expect(previewIncidentsMock).toHaveBeenCalledWith(
      expect.objectContaining({
        allRepositories: false,
        repositoryIds: [11],
        reviewFeatureIds: null,
        localeTags: ['fr'],
        teamId: 31,
        reviewType: null,
      }),
    );
    expect(previewIncidentsMock.mock.calls[0][0]).not.toHaveProperty('statusFilter');
    fireEvent.click(screen.getByRole('button', { name: 'Create' }));
    expect(await screen.findByRole('link', { name: 'Open review project #201' })).toHaveAttribute(
      'href',
      '/review-projects/201',
    );
    expect(createIncidentsMock.mock.calls[0][0]).toEqual({
      ...previewIncidentsMock.mock.calls[0][0],
      incidentIds: [101, 102, 103],
    });
    expect(createRequestMock).not.toHaveBeenCalled();
  });

  it('requires a fresh preview when selection or batch limits change', async () => {
    const wordLimit = renderPage('REPOSITORIES');
    selectIncidentSource();
    fireEvent.click(screen.getByRole('button', { name: 'Preview incidents' }));
    await screen.findByText(/3 eligible incidents/);
    expect(screen.getByRole('button', { name: 'Create' })).toBeEnabled();
    fireEvent.change(wordLimit, { target: { value: '200' } });
    expect(screen.getByRole('button', { name: 'Create' })).toBeDisabled();
    expect(screen.queryByText(/3 eligible incidents/)).not.toBeInTheDocument();
  });

  it('collects selected features in one incident batch instead of separate overlapping requests', async () => {
    renderPage('REVIEW_FEATURE');
    selectIncidentSource();
    fireEvent.click(screen.getByRole('button', { name: 'Review feature' }));
    fireEvent.click(screen.getByRole('button', { name: 'Preview incidents' }));
    await screen.findByText(/3 eligible incidents/);
    fireEvent.click(screen.getByRole('button', { name: 'Create' }));
    await screen.findByRole('link', { name: 'Open review project #201' });
    expect(createIncidentsMock).toHaveBeenCalledTimes(1);
    expect(createIncidentsMock.mock.calls[0][0]).toEqual(
      expect.objectContaining({ reviewFeatureIds: [21, 22], repositoryIds: null }),
    );
  });

  it('does not create anything for an empty preview and can preview again', async () => {
    previewIncidentsMock.mockResolvedValueOnce(incidentResult({ incidentBatches: [] }));
    renderPage('REPOSITORIES');
    selectIncidentSource();
    fireEvent.click(screen.getByRole('button', { name: 'Preview incidents' }));
    await screen.findByText('No eligible incidents found. No projects need to be created.');
    expect(screen.getByRole('button', { name: 'Create' })).toBeDisabled();
    expect(createIncidentsMock).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: 'Preview incidents' }));
    await screen.findByText(/3 eligible incidents/);
    expect(screen.getByRole('button', { name: 'Create' })).toBeEnabled();
  });

  it('creates every planned project beyond the old 500 incident limit', async () => {
    const batches = [
      Array.from({ length: 500 }, (_, index) => index + 1),
      Array.from({ length: 250 }, (_, index) => index + 501),
    ];
    previewIncidentsMock.mockResolvedValueOnce(
      incidentResult({
        eligibleIncidentCount: 750,
        projectCount: 2,
        incidentBatches: batches,
        localeTags: ['fr'],
        scannedIncidentCount: 1000,
      }),
    );
    let resolveSecond!: (result: IncidentReviewProjectResult) => void;
    const pendingSecond = new Promise<IncidentReviewProjectResult>((resolve) => {
      resolveSecond = resolve;
    });
    createIncidentsMock
      .mockResolvedValueOnce(
        incidentResult({
          eligibleIncidentCount: 500,
          projectCount: 1,
          projectIds: [201],
          localeTags: ['fr'],
        }),
      )
      .mockReturnValueOnce(pendingSecond);
    renderPage('REPOSITORIES');
    selectIncidentSource();
    fireEvent.click(screen.getByRole('button', { name: 'Preview incidents' }));
    await screen.findByText(/750 eligible incidents/);
    expect(screen.getByText(/This preview covers all matching incidents/)).toBeInTheDocument();
    expect(previewIncidentsMock.mock.calls[0][0]).toEqual(
      expect.objectContaining({
        maxIncidentCount: null,
        maxIncidentsPerProject: 500,
      }),
    );
    fireEvent.click(screen.getByRole('button', { name: 'Create' }));
    await screen.findByText(/1 of 2 planned projects checked/);
    expect(screen.getByText('Created 1 project with 500 incidents.')).toBeInTheDocument();
    expect(screen.getByRole('textbox', { name: 'Maximum incidents per project' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Create…' })).toBeDisabled();
    await act(async () => {
      resolveSecond(
        incidentResult({
          eligibleIncidentCount: 250,
          projectCount: 1,
          projectIds: [202],
          localeTags: ['fr'],
        }),
      );
      await pendingSecond;
    });
    await screen.findByText(/2 of 2 planned projects checked/);
    expect(screen.getByText('Created 2 projects with 750 incidents.')).toBeInTheDocument();
    expect(createIncidentsMock).toHaveBeenCalledTimes(2);
    batches.forEach((incidentIds, index) => {
      expect(createIncidentsMock.mock.calls[index][0]).toEqual({
        ...previewIncidentsMock.mock.calls[0][0],
        incidentIds,
      });
    });
    expect(screen.getByRole('button', { name: 'Create' })).toBeDisabled();
  });

  it('creates the entire plan across 64 selected locales without a project cap', async () => {
    const localeTags =
      'af am ar az be bg bn ca cs cy da de el es et eu fa fi fil fr ga gl gu he hi hr hu hy id is it ja ka kk km kn ko lo lt lv mk ml mn mr ms my nb ne nl nn pa pl pt ro ru sk sl sq sr sv sw ta te th'.split(
        ' ',
      );
    expect(localeTags).toHaveLength(64);
    const incidentBatches = localeTags.map((_, index) =>
      Array.from({ length: 10 }, (_, offset) => index * 10 + offset + 1),
    );
    previewIncidentsMock.mockResolvedValueOnce(
      incidentResult({
        eligibleIncidentCount: 640,
        projectCount: 64,
        incidentBatches,
        localeTags,
      }),
    );
    createIncidentsMock.mockImplementation((request: IncidentReviewProjectRequest) => {
      const localeIndex = Math.floor((request.incidentIds![0] - 1) / 10);
      return Promise.resolve(
        incidentResult({
          eligibleIncidentCount: 10,
          projectCount: 1,
          projectIds: [201 + localeIndex],
          localeTags: [localeTags[localeIndex]],
        }),
      );
    });
    renderPage('REPOSITORIES', false, [
      {
        ...repositories[0],
        repositoryLocales: localeTags.map((bcp47Tag) => ({
          locale: { bcp47Tag },
          parentLocale: { bcp47Tag: 'en' },
          toBeFullyTranslated: true,
        })),
      },
    ]);
    selectIncidentSource();
    fireEvent.click(screen.getByRole('button', { name: 'Locales' }));
    fireEvent.click(screen.getByRole('button', { name: 'Select all' }));
    fireEvent.click(screen.getByRole('button', { name: 'Locales' }));
    fireEvent.click(screen.getByRole('button', { name: 'Preview incidents' }));
    await screen.findByText(/640 eligible incidents.*64 projects.*64 locales/);
    expect(previewIncidentsMock.mock.calls[0][0].localeTags).toHaveLength(64);
    fireEvent.click(screen.getByRole('button', { name: 'Create' }));
    await screen.findByText(/64 of 64 planned projects checked/);
    expect(screen.getByText('Created 64 projects with 640 incidents.')).toBeInTheDocument();
    expect(createIncidentsMock).toHaveBeenCalledTimes(64);
    expect(createIncidentsMock.mock.calls.map(([request]) => request.incidentIds)).toEqual(
      incidentBatches,
    );
  });

  it('stops after Cancel even when there is no previous page to navigate to', async () => {
    previewIncidentsMock.mockResolvedValueOnce(
      incidentResult({
        eligibleIncidentCount: 2,
        projectCount: 2,
        incidentBatches: [[1], [2]],
      }),
    );
    let resolveFirst!: (result: IncidentReviewProjectResult) => void;
    const pendingFirst = new Promise<IncidentReviewProjectResult>((resolve) => {
      resolveFirst = resolve;
    });
    createIncidentsMock.mockReturnValueOnce(pendingFirst);
    renderPage('REPOSITORIES');
    selectIncidentSource();
    fireEvent.click(screen.getByRole('button', { name: 'Preview incidents' }));
    await screen.findByText(/2 eligible incidents/);
    fireEvent.click(screen.getByRole('button', { name: 'Create' }));
    await waitFor(() => expect(createIncidentsMock).toHaveBeenCalledTimes(1));
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    await act(async () => {
      resolveFirst(
        incidentResult({ eligibleIncidentCount: 1, projectCount: 1, projectIds: [201] }),
      );
      await pendingFirst;
    });
    await screen.findByRole('link', { name: 'Open review project #201' });
    expect(screen.getByText(/1 of 2 planned projects checked/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Resume remaining projects' })).toBeEnabled();
    expect(createIncidentsMock).toHaveBeenCalledTimes(1);
  });

  it('stops creating subsequent projects when leaving the page', async () => {
    previewIncidentsMock.mockResolvedValueOnce(
      incidentResult({
        eligibleIncidentCount: 3,
        projectCount: 3,
        incidentBatches: [[1], [2], [3]],
      }),
    );
    let resolveSecond!: (result: IncidentReviewProjectResult) => void;
    const pendingSecond = new Promise<IncidentReviewProjectResult>((resolve) => {
      resolveSecond = resolve;
    });
    createIncidentsMock
      .mockResolvedValueOnce(
        incidentResult({
          eligibleIncidentCount: 1,
          projectCount: 1,
          projectIds: [201],
        }),
      )
      .mockReturnValueOnce(pendingSecond);
    renderPage('REPOSITORIES');
    selectIncidentSource();
    fireEvent.click(screen.getByRole('button', { name: 'Preview incidents' }));
    await screen.findByText(/3 eligible incidents/);
    fireEvent.click(screen.getByRole('button', { name: 'Create' }));
    fireEvent.click(await screen.findByRole('link', { name: 'Open review project #201' }));
    await screen.findByText('Review project details');
    await act(async () => {
      resolveSecond(
        incidentResult({ eligibleIncidentCount: 1, projectCount: 1, projectIds: [202] }),
      );
      await pendingSecond;
    });
    expect(createIncidentsMock.mock.calls.map(([request]) => request.incidentIds)).toEqual([
      [1],
      [2],
    ]);
  });

  it('preserves successful projects and resumes at the failed planned project', async () => {
    previewIncidentsMock.mockResolvedValueOnce(
      incidentResult({
        eligibleIncidentCount: 3,
        projectCount: 3,
        incidentBatches: [[1], [2], [3]],
        localeTags: ['fr'],
      }),
    );
    createIncidentsMock
      .mockResolvedValueOnce(
        incidentResult({
          eligibleIncidentCount: 1,
          projectCount: 1,
          projectIds: [201],
        }),
      )
      .mockRejectedValueOnce(new Error('Temporary failure'))
      .mockResolvedValueOnce(
        incidentResult({
          eligibleIncidentCount: 1,
          projectCount: 1,
          projectIds: [202],
        }),
      )
      .mockResolvedValueOnce(
        incidentResult({
          eligibleIncidentCount: 1,
          projectCount: 1,
          projectIds: [203],
        }),
      );
    renderPage('REPOSITORIES');
    selectIncidentSource();
    fireEvent.click(screen.getByRole('button', { name: 'Preview incidents' }));
    await screen.findByText(/3 eligible incidents/);
    fireEvent.click(screen.getByRole('button', { name: 'Create' }));
    await screen.findByText('Temporary failure');
    expect(screen.getByText(/Counts and links show confirmed creations/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Open review project #201' })).toBeInTheDocument();
    expect(screen.getByText(/1 of 3 planned projects checked/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Resume remaining projects' }));
    await screen.findByText(/3 of 3 planned projects checked/);
    expect(screen.getByText('Created 3 projects with 3 incidents.')).toBeInTheDocument();
    expect(createIncidentsMock.mock.calls.map(([request]) => request.incidentIds)).toEqual([
      [1],
      [2],
      [2],
      [3],
    ]);
    expect(screen.getByRole('link', { name: 'Open review project #201' })).toBeInTheDocument();
    expect(
      screen.queryByRole('button', { name: 'Resume remaining projects' }),
    ).not.toBeInTheDocument();
  });

  it('invalidates a failed plan when settings change while keeping created project links', async () => {
    previewIncidentsMock.mockResolvedValueOnce(
      incidentResult({
        eligibleIncidentCount: 2,
        projectCount: 2,
        incidentBatches: [[1], [2]],
      }),
    );
    createIncidentsMock
      .mockResolvedValueOnce(
        incidentResult({
          eligibleIncidentCount: 1,
          projectCount: 1,
          projectIds: [201],
        }),
      )
      .mockRejectedValueOnce(new Error('Temporary failure'));
    renderPage('REPOSITORIES');
    selectIncidentSource();
    fireEvent.click(screen.getByRole('button', { name: 'Preview incidents' }));
    await screen.findByText(/2 eligible incidents/);
    fireEvent.click(screen.getByRole('button', { name: 'Create' }));
    await screen.findByRole('button', { name: 'Resume remaining projects' });
    fireEvent.change(screen.getByRole('textbox', { name: 'Maximum incidents per project' }), {
      target: { value: '100' },
    });
    expect(
      screen.queryByRole('button', { name: 'Resume remaining projects' }),
    ).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Open review project #201' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Create' })).toBeDisabled();
  });

  it('sends the visible limits and invalidates the preview when either changes', async () => {
    renderPage('REPOSITORIES');
    selectIncidentSource();
    const overallLimit = screen.getByRole('textbox', {
      name: 'Maximum incidents overall (optional)',
    });
    const projectLimit = screen.getByRole('textbox', { name: 'Maximum incidents per project' });
    expect(overallLimit).toHaveValue('');
    expect(projectLimit).toHaveValue('500');
    fireEvent.change(overallLimit, { target: { value: '2000' } });
    fireEvent.change(projectLimit, { target: { value: '100' } });
    previewIncidentsMock.mockResolvedValueOnce(
      incidentResult({
        eligibleIncidentCount: 3,
        projectCount: 1,
        incidentBatches: [[1, 2, 3]],
        limitReached: true,
      }),
    );
    fireEvent.click(screen.getByRole('button', { name: 'Preview incidents' }));
    await screen.findByText(/The overall incident limit was reached/);
    expect(previewIncidentsMock.mock.calls[0][0]).toEqual(
      expect.objectContaining({
        maxIncidentCount: 2000,
        maxIncidentsPerProject: 100,
      }),
    );
    fireEvent.change(overallLimit, { target: { value: '' } });
    expect(screen.getByRole('button', { name: 'Create' })).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: 'Preview incidents' }));
    await screen.findByText(/3 eligible incidents/);
    expect(screen.getByRole('button', { name: 'Create' })).toBeEnabled();
    fireEvent.change(projectLimit, { target: { value: '200' } });
    expect(screen.getByRole('button', { name: 'Create' })).toBeDisabled();
  });

  it.each([
    ['Maximum incidents overall (optional)', '0'],
    ['Maximum incidents overall (optional)', '2147483648'],
    ['Maximum incidents overall (optional)', '1.5'],
    ['Maximum incidents per project', ''],
    ['Maximum incidents per project', '0'],
    ['Maximum incidents per project', '5001'],
  ])('rejects %s set to %s', (label, value) => {
    renderPage('REPOSITORIES');
    selectIncidentSource();
    const field = screen.getByRole('textbox', { name: label });
    fireEvent.change(field, { target: { value } });
    expect(field).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByRole('button', { name: 'Preview incidents' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Create' })).toBeDisabled();
  });

  it('does not silently create an unplanned batch for an old preview response', async () => {
    previewIncidentsMock.mockResolvedValueOnce(incidentResult({ eligibleIncidentCount: 1000 }));
    renderPage('REPOSITORIES');
    selectIncidentSource();
    fireEvent.click(screen.getByRole('button', { name: 'Preview incidents' }));
    await screen.findByText(/The preview did not include a creation plan/);
    expect(screen.getByRole('button', { name: 'Create' })).toBeDisabled();
    expect(createIncidentsMock).not.toHaveBeenCalled();
  });
});

describe('manual review project word limit', () => {
  it('leaves existing creation uncapped by default', async () => {
    const wordLimit = renderPage();

    expect(wordLimit).toHaveValue('');
    fireEvent.click(screen.getByRole('button', { name: 'Create' }));

    await screen.findByText('Review projects created');
    expect(createRequestMock).toHaveBeenCalledWith(
      expect.objectContaining({ tmTextUnitIds: [42], maxWordCountPerProject: null }),
    );
  });

  it.each<ReviewProjectSourceMode>(['TEXT_UNITS', 'REPOSITORIES', 'REVIEW_FEATURE'])(
    'sends the requested word limit through the %s creation flow',
    async (sourceMode) => {
      const wordLimit = renderPage(sourceMode);
      fireEvent.change(wordLimit, { target: { value: '2000' } });
      fireEvent.click(screen.getByRole('button', { name: 'Create' }));

      await screen.findByText('Review projects created');
      expect(createRequestMock).toHaveBeenCalledTimes(sourceMode === 'REVIEW_FEATURE' ? 2 : 1);
      const expectedScopes =
        sourceMode === 'TEXT_UNITS'
          ? [{ tmTextUnitIds: [42], repositoryIds: null, reviewFeatureId: null }]
          : sourceMode === 'REPOSITORIES'
            ? [{ tmTextUnitIds: null, repositoryIds: [11], reviewFeatureId: null }]
            : [
                { tmTextUnitIds: null, repositoryIds: null, reviewFeatureId: 21 },
                { tmTextUnitIds: null, repositoryIds: null, reviewFeatureId: 22 },
              ];
      expectedScopes.forEach((scope, index) => {
        expect(createRequestMock).toHaveBeenNthCalledWith(
          index + 1,
          expect.objectContaining({ ...scope, localeTags: ['fr'], maxWordCountPerProject: 2000 }),
        );
      });
    },
  );

  it.each(['0', '-1', '1.5', '2147483648', 'words'])(
    'prevents creation with an invalid word limit of %s',
    (value) => {
      const wordLimit = renderPage();
      expect(screen.getByRole('button', { name: 'Create' })).toBeEnabled();

      fireEvent.change(wordLimit, { target: { value } });

      expect(wordLimit).toHaveAttribute('aria-invalid', 'true');
      expect(
        screen.getByText('Enter a whole number from 1 to 2,147,483,647, or leave blank.'),
      ).toBeVisible();
      expect(screen.getByRole('button', { name: 'Create' })).toBeDisabled();
      fireEvent.click(screen.getByRole('button', { name: 'Create' }));
      expect(createRequestMock).not.toHaveBeenCalled();
    },
  );

  it('accepts the largest supported integer', async () => {
    const wordLimit = renderPage();
    fireEvent.change(wordLimit, { target: { value: '2147483647' } });
    fireEvent.click(screen.getByRole('button', { name: 'Create' }));

    await screen.findByText('Review projects created');
    expect(createRequestMock).toHaveBeenCalledWith(
      expect.objectContaining({ maxWordCountPerProject: 2147483647 }),
    );
  });

  it('clears an invalid limit to restore uncapped creation', async () => {
    const wordLimit = renderPage();
    fireEvent.change(wordLimit, { target: { value: '0' } });
    expect(screen.getByRole('button', { name: 'Create' })).toBeDisabled();

    fireEvent.change(wordLimit, { target: { value: '' } });
    expect(wordLimit).not.toHaveAttribute('aria-invalid', 'true');
    expect(
      screen.queryByText('Enter a whole number from 1 to 2,147,483,647, or leave blank.'),
    ).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Create' }));

    await screen.findByText('Review projects created');
    expect(createRequestMock).toHaveBeenCalledWith(
      expect.objectContaining({ maxWordCountPerProject: null }),
    );
  });

  it('locks the word limit while creation is pending', async () => {
    let resolveCreation!: (response: ReviewProjectsApi.ReviewProjectCreateResponse) => void;
    const pendingCreation = new Promise<ReviewProjectsApi.ReviewProjectCreateResponse>(
      (resolve) => {
        resolveCreation = resolve;
      },
    );
    createRequestMock.mockReturnValueOnce(pendingCreation);
    const wordLimit = renderPage();
    fireEvent.change(wordLimit, { target: { value: '2000' } });
    fireEvent.click(screen.getByRole('button', { name: 'Create' }));

    await waitFor(() => expect(wordLimit).toBeDisabled());
    expect(screen.getByRole('button', { name: 'Create…' })).toBeDisabled();

    await act(async () => {
      resolveCreation(createdResponse);
      await pendingCreation;
    });
    await screen.findByText('Review projects created');
  });
});
