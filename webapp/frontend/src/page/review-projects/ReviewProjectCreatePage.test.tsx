import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import type { ApiRepository } from '../../api/repositories';
import type * as ReviewProjectsApi from '../../api/review-projects';
import type { ReviewProjectSourceMode } from './ReviewProjectCreateForm';
import { ReviewProjectCreatePage } from './ReviewProjectCreatePage';

const createRequestMock = vi.hoisted(() => vi.fn());
const previewIncidentsMock = vi.hoisted(() => vi.fn());
const createIncidentsMock = vi.hoisted(() => vi.fn());

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

function renderPage(sourceMode: ReviewProjectSourceMode = 'TEXT_UNITS', selectLocale = true) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  queryClient.setQueryData(['repositories'], repositories);
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

beforeEach(() => {
  vi.clearAllMocks();
  createRequestMock.mockResolvedValue(createdResponse);
  previewIncidentsMock.mockResolvedValue({
    eligibleIncidentCount: 3,
    skippedIncidentCount: 0,
    projectCount: 2,
    localeTags: ['fr'],
    projectIds: [],
    requestIds: [],
    skipped: [],
  });
  createIncidentsMock.mockResolvedValue({
    eligibleIncidentCount: 3,
    skippedIncidentCount: 0,
    projectCount: 2,
    localeTags: ['fr'],
    projectIds: [201, 202],
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
  it('defaults to the incident queue across repositories and locales, with optional narrowing', async () => {
    renderPage('TEXT_UNITS', false);
    selectIncidentSource();
    expect(screen.getByRole('button', { name: 'All eligible incidents' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    expect(screen.queryByRole('button', { name: 'Select repositories' })).not.toBeInTheDocument();
    expect(
      screen.queryByRole('button', { name: 'Select review features' }),
    ).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Preview incidents' }));
    await screen.findByText(/3 eligible incidents/);
    expect(previewIncidentsMock.mock.calls[0][0]).toEqual(
      expect.objectContaining({
        allRepositories: true,
        repositoryIds: null,
        reviewFeatureIds: null,
        localeTags: [],
        teamId: 31,
      }),
    );
    fireEvent.click(screen.getByRole('button', { name: 'Create' }));
    await screen.findByRole('link', { name: 'Open review project #201' });
    expect(createIncidentsMock.mock.calls[0][0]).toEqual(previewIncidentsMock.mock.calls[0][0]);
    expect(createRequestMock).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: 'Repositories' }));
    expect(screen.getByRole('button', { name: 'Select repositories' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Create' })).toBeDisabled();
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
    expect(createIncidentsMock.mock.calls[0][0]).toEqual(previewIncidentsMock.mock.calls[0][0]);
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

  it('finishes an empty saved pass so a fresh preview can discover new incidents', async () => {
    const emptyLastPage = {
      eligibleIncidentCount: 0,
      skippedIncidentCount: 0,
      projectCount: 0,
      localeTags: [],
      projectIds: [],
      requestIds: [],
      skipped: [],
      scannedIncidentCount: 0,
      hasMore: false,
    };
    previewIncidentsMock.mockResolvedValueOnce(emptyLastPage);
    createIncidentsMock.mockResolvedValueOnce(emptyLastPage);
    renderPage('REPOSITORIES');
    selectIncidentSource();
    fireEvent.click(screen.getByRole('button', { name: 'Preview incidents' }));
    await screen.findByText(/0 eligible incidents/);
    fireEvent.click(screen.getByRole('button', { name: 'Check for new incidents' }));
    await screen.findByText(/No eligible incidents remain in this pass/);
    expect(createIncidentsMock).toHaveBeenCalledTimes(1);
    expect(screen.getByRole('button', { name: 'Create' })).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: 'Preview incidents' }));
    await screen.findByText(/3 eligible incidents/);
    expect(screen.getByRole('button', { name: 'Create' })).toBeEnabled();
  });

  it('advances a skipped page and continues the same selection without losing created projects', async () => {
    const skippedPage = {
      eligibleIncidentCount: 0,
      skippedIncidentCount: 500,
      scannedIncidentCount: 500,
      hasMore: true,
      projectCount: 0,
      localeTags: [],
      projectIds: [],
      requestIds: [],
      skipped: [],
    };
    previewIncidentsMock.mockResolvedValueOnce(skippedPage);
    createIncidentsMock.mockResolvedValueOnce(skippedPage).mockResolvedValueOnce({
      ...skippedPage,
      eligibleIncidentCount: 2,
      skippedIncidentCount: 0,
      scannedIncidentCount: 2,
      projectCount: 1,
      projectIds: [203],
      requestIds: [204],
      localeTags: ['fr'],
      hasMore: false,
    });
    renderPage('REPOSITORIES');
    selectIncidentSource();
    fireEvent.click(screen.getByRole('button', { name: 'Preview incidents' }));
    await screen.findByText(/This preview covers the next batch/);
    fireEvent.click(screen.getByRole('button', { name: 'Continue to next batch' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Create next batch' }));
    await screen.findByRole('link', { name: 'Open review project #203' });
    expect(screen.getByText(/Created 1 project\. 500 incidents skipped/)).toBeInTheDocument();
    expect(createIncidentsMock.mock.calls[1][0]).toEqual(createIncidentsMock.mock.calls[0][0]);
    expect(screen.queryByRole('button', { name: 'Create next batch' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Create' })).toBeDisabled();
  });

  it('preserves committed project links when a continuation fails', async () => {
    createIncidentsMock
      .mockResolvedValueOnce({
        eligibleIncidentCount: 3,
        skippedIncidentCount: 0,
        projectCount: 1,
        localeTags: ['fr'],
        projectIds: [201],
        requestIds: [200],
        skipped: [],
        hasMore: true,
      })
      .mockRejectedValueOnce(new Error('Temporary failure'));
    renderPage('REPOSITORIES');
    selectIncidentSource();
    fireEvent.click(screen.getByRole('button', { name: 'Preview incidents' }));
    await screen.findByText(/3 eligible incidents/);
    fireEvent.click(screen.getByRole('button', { name: 'Create' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Create next batch' }));
    await screen.findByText('Temporary failure');
    expect(screen.getByRole('link', { name: 'Open review project #201' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Create next batch' })).toBeEnabled();
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
