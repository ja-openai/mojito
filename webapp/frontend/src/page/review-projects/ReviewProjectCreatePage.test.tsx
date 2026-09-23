import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { Link, MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import type {
  IncidentReviewProjectRequest,
  IncidentReviewProjectResult,
  IncidentReviewTaskStart,
  waitForIncidentReviewTask,
} from '../../api/incident-review-projects';
import type { ApiRepository } from '../../api/repositories';
import type * as ReviewProjectsApi from '../../api/review-projects';
import type { ReviewProjectSourceMode } from './ReviewProjectCreateForm';
import { ReviewProjectCreatePage } from './ReviewProjectCreatePage';

const createRequestMock = vi.hoisted(() => vi.fn());
const previewIncidentsMock = vi.hoisted(() =>
  vi.fn<(request: IncidentReviewProjectRequest) => Promise<IncidentReviewTaskStart>>(),
);
const createIncidentsMock = vi.hoisted(() =>
  vi.fn<(request: IncidentReviewProjectRequest) => Promise<IncidentReviewTaskStart>>(),
);

const waitForIncidentTaskMock = vi.hoisted(() => vi.fn<typeof waitForIncidentReviewTask>());

vi.mock('../../api/incident-review-projects', () => ({
  previewIncidentReviewProjects: previewIncidentsMock,
  createIncidentReviewProjects: createIncidentsMock,
  waitForIncidentReviewTask: waitForIncidentTaskMock,
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

function LocationProbe() {
  const location = useLocation();
  return (
    <>
      <output data-testid="location">
        {location.pathname}
        {location.search}
      </output>
      <Link to="/review-projects">Leave creation</Link>
    </>
  );
}

function renderPage(
  sourceMode: ReviewProjectSourceMode = 'TEXT_UNITS',
  selectLocale = true,
  availableRepositories = repositories,
  search = '',
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
            search,
            state: {
              sourceMode,
              tmTextUnitIds: [42],
              repositoryIds: [11],
              defaultDueDate: '2026-09-10T12:00',
            },
          },
        ]}
      >
        <LocationProbe />
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

const previewResult = incidentResult({
  eligibleIncidentCount: 3,
  projectCount: 1,
  localeTags: ['fr'],
});
const creationResult = incidentResult({
  eligibleIncidentCount: 3,
  projectCount: 1,
  localeTags: ['fr'],
  projectIds: [201],
  requestIds: [200],
});

function taskResult(
  id: number,
  result: IncidentReviewProjectResult | null,
  errorMessage: string | null = null,
) {
  return { task: { id, isAllFinished: true, message: 'Finished.', errorMessage }, result };
}

beforeEach(() => {
  vi.resetAllMocks();
  createRequestMock.mockResolvedValue(createdResponse);
  previewIncidentsMock.mockResolvedValue({ pollableTaskId: 1000 });
  createIncidentsMock.mockResolvedValue({ pollableTaskId: 2000 });
  waitForIncidentTaskMock.mockImplementation((id, onProgress) => {
    const result = taskResult(id, id === 1000 ? previewResult : creationResult);
    onProgress(result.task);
    return Promise.resolve(result);
  });
});

function selectIncidentSource() {
  fireEvent.click(screen.getByRole('button', { name: 'Incidents' }));
  fireEvent.click(screen.getByRole('button', { name: 'Select team for default assignment' }));
  fireEvent.click(screen.getByRole('button', { name: 'Review team (#31)' }));
}

describe('incident review project creation', () => {
  it('creates directly with one whole-scope request without previewing', async () => {
    renderPage('REPOSITORIES');
    selectIncidentSource();
    expect(
      screen.queryByRole('textbox', { name: 'Maximum incidents per project' }),
    ).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Create' })).toBeEnabled();
    fireEvent.click(screen.getByRole('button', { name: 'Create' }));
    await screen.findByRole('link', { name: 'Open review project #201' });
    expect(previewIncidentsMock).not.toHaveBeenCalled();
    expect(createIncidentsMock).toHaveBeenCalledTimes(1);
    expect(createIncidentsMock.mock.calls[0][0]).toEqual(
      expect.objectContaining({
        allRepositories: true,
        localeTags: ['fr'],
        teamId: 31,
        maxIncidentCount: null,
        maxWordCountPerProject: null,
      }),
    );
    expect(createIncidentsMock.mock.calls[0][0]).not.toHaveProperty('incidentIds');
    expect(createIncidentsMock.mock.calls[0][0]).not.toHaveProperty('maxIncidentsPerProject');
    expect(createRequestMock).not.toHaveBeenCalled();
    expect(screen.getByTestId('location')).toHaveTextContent('incidentTask=2000');
    expect(screen.getByTestId('location')).toHaveTextContent('incidentTaskMode=create');
  });

  it('requires explicit locales and supports Select all for both actions', async () => {
    renderPage('REPOSITORIES', false);
    selectIncidentSource();
    expect(screen.getByRole('button', { name: 'Preview incidents' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Create' })).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: 'Locales' }));
    fireEvent.click(screen.getByRole('button', { name: 'Select all' }));
    fireEvent.click(screen.getByRole('button', { name: 'Locales' }));
    expect(screen.getByRole('button', { name: 'Locales' })).toHaveTextContent('All locales');
    expect(screen.getByRole('button', { name: 'Create' })).toBeEnabled();
    fireEvent.click(screen.getByRole('button', { name: 'Preview incidents' }));
    await screen.findByText(/3 eligible incidents/);
    expect(previewIncidentsMock.mock.calls[0][0].localeTags).toEqual(['fr', 'de']);
    expect(createIncidentsMock).not.toHaveBeenCalled();
  });

  it.each<ReviewProjectSourceMode>(['REPOSITORIES', 'REVIEW_FEATURE'])(
    'requires locales for incident review scoped to %s',
    (sourceMode) => {
      renderPage(sourceMode, false);
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

  it('invalidates preview when settings change while keeping direct creation available', async () => {
    const wordLimit = renderPage('REPOSITORIES');
    selectIncidentSource();
    fireEvent.click(screen.getByRole('button', { name: 'Preview incidents' }));
    await screen.findByText(/3 eligible incidents/);
    fireEvent.change(wordLimit, { target: { value: '200' } });
    expect(screen.queryByText(/3 eligible incidents/)).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Create' })).toBeEnabled();
    fireEvent.click(screen.getByRole('button', { name: 'Create' }));
    await screen.findByRole('link', { name: 'Open review project #201' });
    expect(createIncidentsMock.mock.calls[0][0].maxWordCountPerProject).toBe(200);
  });

  it('clearing the final locale removes stale preview and disables both actions', async () => {
    renderPage('REPOSITORIES');
    selectIncidentSource();
    fireEvent.click(screen.getByRole('button', { name: 'Preview incidents' }));
    await screen.findByText(/3 eligible incidents/);
    fireEvent.click(screen.getByRole('button', { name: 'Locales' }));
    fireEvent.click(screen.getByRole('checkbox', { name: /French/ }));
    fireEvent.click(screen.getByRole('button', { name: 'Locales' }));
    expect(screen.queryByText(/3 eligible incidents/)).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Preview incidents' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Create' })).toBeDisabled();
  });

  it('creates selected features with a single request independent of translation status', async () => {
    renderPage('REVIEW_FEATURE');
    selectIncidentSource();
    fireEvent.click(screen.getByRole('button', { name: 'Review feature' }));
    expect(screen.queryByText('Status filter')).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Create' }));
    await screen.findByRole('link', { name: 'Open review project #201' });
    expect(createIncidentsMock).toHaveBeenCalledTimes(1);
    expect(createIncidentsMock.mock.calls[0][0]).toEqual(
      expect.objectContaining({
        reviewFeatureIds: [21, 22],
        repositoryIds: null,
        allRepositories: false,
      }),
    );
    expect(createIncidentsMock.mock.calls[0][0]).not.toHaveProperty('statusFilter');
  });

  it('shows progress and prevents duplicate starts while the server task runs', async () => {
    let finish!: (result: Awaited<ReturnType<typeof waitForIncidentReviewTask>>) => void;
    waitForIncidentTaskMock.mockImplementationOnce((id, onProgress) => {
      onProgress({ id, isAllFinished: false, message: 'Created 4 of 9 projects.' });
      return new Promise((resolve) => {
        finish = resolve;
      });
    });
    renderPage('REPOSITORIES');
    selectIncidentSource();
    fireEvent.click(screen.getByRole('button', { name: 'Create' }));
    await screen.findByText('Created 4 of 9 projects.');
    expect(screen.getByRole('button', { name: 'Create…' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Preview incidents' })).toBeDisabled();
    expect(
      screen.getByRole('textbox', { name: 'Max word count per project (optional)' }),
    ).toBeDisabled();
    expect(
      screen.getByText('You can leave this page and return to check progress.'),
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Leave page' })).toBeEnabled();
    expect(screen.queryByRole('button', { name: 'Cancel' })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Create…' }));
    expect(createIncidentsMock).toHaveBeenCalledTimes(1);
    await act(async () => {
      finish(taskResult(2000, creationResult));
      await Promise.resolve();
    });
    await screen.findByRole('link', { name: 'Open review project #201' });
  });

  it('does not duplicate the start request before its task ID arrives', async () => {
    let accept!: (result: IncidentReviewTaskStart) => void;
    createIncidentsMock.mockReturnValueOnce(
      new Promise((resolve) => {
        accept = resolve;
      }),
    );
    renderPage('REPOSITORIES');
    selectIncidentSource();
    fireEvent.click(screen.getByRole('button', { name: 'Create' }));
    fireEvent.click(screen.getByRole('button', { name: 'Create…' }));
    expect(createIncidentsMock).toHaveBeenCalledTimes(1);
    await act(async () => {
      accept({ pollableTaskId: 2000 });
      await Promise.resolve();
    });
    await screen.findByRole('link', { name: 'Open review project #201' });
  });

  it('keeps successful project links beside a later server-job failure', async () => {
    waitForIncidentTaskMock.mockResolvedValueOnce(
      taskResult(
        2000,
        {
          ...creationResult,
          hasMore: true,
        },
        'Project creation stopped after a database error.',
      ),
    );
    renderPage('REPOSITORIES');
    selectIncidentSource();
    fireEvent.click(screen.getByRole('button', { name: 'Create' }));
    await screen.findByRole('link', { name: 'Open review project #201' });
    expect(
      screen.getByText('Project creation stopped after a database error.'),
    ).toBeInTheDocument();
    expect(screen.getByText('Projects already created are preserved.')).toBeInTheDocument();
    expect(
      screen.queryByRole('button', { name: 'Resume remaining projects' }),
    ).not.toBeInTheDocument();
    expect(createIncidentsMock).toHaveBeenCalledTimes(1);
  });

  it('does not navigate back when admission finishes after leaving the page', async () => {
    let accept!: (result: IncidentReviewTaskStart) => void;
    createIncidentsMock.mockReturnValueOnce(
      new Promise((resolve) => {
        accept = resolve;
      }),
    );
    renderPage('REPOSITORIES');
    selectIncidentSource();
    fireEvent.click(screen.getByRole('button', { name: 'Create' }));
    expect(screen.queryByText(/return to this URL/)).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('link', { name: 'Leave creation' }));
    await screen.findByText('Review projects created');
    await act(async () => {
      accept({ pollableTaskId: 2000 });
      await Promise.resolve();
    });
    expect(screen.getByTestId('location')).toHaveTextContent(/^\/review-projects$/);
    expect(waitForIncidentTaskMock).not.toHaveBeenCalled();
    expect(createIncidentsMock).toHaveBeenCalledTimes(1);
  });

  it('shows the terminal job failure when its saved output cannot be read', async () => {
    waitForIncidentTaskMock.mockResolvedValueOnce({
      ...taskResult(2000, null, 'Creation failed before planning.'),
      outputError: 'Output unavailable (HTTP 500).',
    });
    renderPage('REPOSITORIES');
    selectIncidentSource();
    fireEvent.click(screen.getByRole('button', { name: 'Create' }));
    await screen.findByText('Creation failed before planning.');
    expect(screen.getByText('Output unavailable (HTTP 500).')).toBeInTheDocument();
    expect(
      screen.getByText(/task finished, but its saved result could not be loaded/),
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Create' })).toBeEnabled();
    expect(screen.getByRole('button', { name: 'Reconnect' })).toBeEnabled();
  });

  it('reconnects to an existing creation task after reload without starting another', async () => {
    renderPage(
      'REPOSITORIES',
      false,
      repositories,
      '?source=incidents&incidentTask=2000&incidentTaskMode=create',
    );
    await screen.findByRole('link', { name: 'Open review project #201' });
    expect(waitForIncidentTaskMock).toHaveBeenCalledWith(
      2000,
      expect.any(Function),
      expect.any(AbortSignal),
    );
    expect(createIncidentsMock).not.toHaveBeenCalled();
    expect(previewIncidentsMock).not.toHaveBeenCalled();
    expect(screen.getByText(/Form settings are not restored/)).toBeInTheDocument();
  });

  it('reconnects to an existing preview without submitting a new preview', async () => {
    renderPage(
      'REPOSITORIES',
      false,
      repositories,
      '?source=incidents&incidentTask=1000&incidentTaskMode=preview',
    );
    await screen.findByText(/3 eligible incidents/);
    expect(previewIncidentsMock).not.toHaveBeenCalled();
    expect(createIncidentsMock).not.toHaveBeenCalled();
  });

  it('stops observing when leaving and reconnects to the same task on return', async () => {
    waitForIncidentTaskMock.mockImplementationOnce(() => new Promise(() => {}));
    renderPage('REPOSITORIES');
    selectIncidentSource();
    fireEvent.click(screen.getByRole('button', { name: 'Create' }));
    await waitFor(() => expect(waitForIncidentTaskMock).toHaveBeenCalledTimes(1));
    const signal = waitForIncidentTaskMock.mock.calls[0][2];
    const savedSearch = screen.getByTestId('location').textContent!.split('?')[1];
    cleanup();
    expect(signal.aborted).toBe(true);
    renderPage('REPOSITORIES', false, repositories, `?${savedSearch}`);
    await screen.findByRole('link', { name: 'Open review project #201' });
    expect(createIncidentsMock).toHaveBeenCalledTimes(1);
    expect(waitForIncidentTaskMock).toHaveBeenCalledTimes(2);
  });

  it('reconnects after a polling error without starting a duplicate job', async () => {
    waitForIncidentTaskMock.mockRejectedValueOnce(new Error('Status unavailable (HTTP 401).'));
    renderPage('REPOSITORIES');
    selectIncidentSource();
    fireEvent.click(screen.getByRole('button', { name: 'Create' }));
    await screen.findByText('Status unavailable (HTTP 401).');
    expect(screen.getByRole('button', { name: 'Create…' })).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: 'Reconnect' }));
    await screen.findByRole('link', { name: 'Open review project #201' });
    expect(createIncidentsMock).toHaveBeenCalledTimes(1);
  });

  it('shows start failures with HTTP status and never begins polling', async () => {
    createIncidentsMock.mockRejectedValueOnce(
      new Error('Incident review request failed (HTTP 503).'),
    );
    renderPage('REPOSITORIES');
    selectIncidentSource();
    fireEvent.click(screen.getByRole('button', { name: 'Create' }));
    await screen.findByText('Incident review request failed (HTTP 503).');
    expect(waitForIncidentTaskMock).not.toHaveBeenCalled();
  });

  it('summarizes preview exclusions and handles empty scopes without creating', async () => {
    waitForIncidentTaskMock.mockResolvedValueOnce(
      taskResult(
        1000,
        incidentResult({
          skippedIncidentCount: 499,
          skipped: Array.from({ length: 100 }, (_, index) => ({
            incidentId: index + 1,
            reason: 'Locale match is unresolved',
          })),
        }),
      ),
    );
    renderPage('REPOSITORIES');
    selectIncidentSource();
    fireEvent.click(screen.getByRole('button', { name: 'Preview incidents' }));
    await screen.findByText('No eligible incidents found. No projects need to be created.');
    expect(screen.getByText('499 incidents not included')).toBeInTheDocument();
    expect(createIncidentsMock).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: 'Create' })).toBeEnabled();
  });

  it('handles an empty creation result', async () => {
    waitForIncidentTaskMock.mockResolvedValueOnce(taskResult(2000, incidentResult()));
    renderPage('REPOSITORIES');
    selectIncidentSource();
    fireEvent.click(screen.getByRole('button', { name: 'Create' }));
    await screen.findByText('Created 0 projects with 0 incidents.');
    expect(screen.queryByRole('link', { name: /Open review project/ })).not.toBeInTheDocument();
  });

  it('sends the optional overall incident limit and word split in one create request', async () => {
    renderPage('REPOSITORIES');
    selectIncidentSource();
    fireEvent.change(
      screen.getByRole('textbox', { name: 'Maximum incidents overall (optional)' }),
      { target: { value: '2000' } },
    );
    fireEvent.change(
      screen.getByRole('textbox', { name: 'Max word count per project (optional)' }),
      { target: { value: '100' } },
    );
    fireEvent.click(screen.getByRole('button', { name: 'Create' }));
    await screen.findByRole('link', { name: 'Open review project #201' });
    expect(createIncidentsMock.mock.calls[0][0]).toEqual(
      expect.objectContaining({ maxIncidentCount: 2000, maxWordCountPerProject: 100 }),
    );
    expect(createIncidentsMock.mock.calls[0][0]).not.toHaveProperty('maxIncidentsPerProject');
  });

  it.each([
    ['Maximum incidents overall (optional)', '0'],
    ['Maximum incidents overall (optional)', '2147483648'],
    ['Maximum incidents overall (optional)', '1.5'],
  ])('rejects %s set to %s', (label, value) => {
    renderPage('REPOSITORIES');
    selectIncidentSource();
    const field = screen.getByRole('textbox', { name: label });
    fireEvent.change(field, { target: { value } });
    expect(field).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByRole('button', { name: 'Preview incidents' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Create' })).toBeDisabled();
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
