import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import type { ApiRepository } from '../../api/repositories';
import type * as ReviewProjectsApi from '../../api/review-projects';
import type { ReviewProjectSourceMode } from './ReviewProjectCreateForm';
import { ReviewProjectCreatePage } from './ReviewProjectCreatePage';

const createRequestMock = vi.hoisted(() => vi.fn());

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

function renderPage(sourceMode: ReviewProjectSourceMode = 'TEXT_UNITS') {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  queryClient.setQueryData(['repositories'], repositories);
  queryClient.setQueryData(['teams', 'review-project-create'], []);
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

  fireEvent.click(screen.getByRole('button', { name: 'Locales' }));
  fireEvent.click(screen.getByRole('checkbox', { name: /French/ }));
  fireEvent.click(screen.getByRole('button', { name: 'Locales' }));

  return screen.getByRole('textbox', { name: /^Max word count per project \(optional\)/ });
}

beforeEach(() => {
  vi.clearAllMocks();
  createRequestMock.mockResolvedValue(createdResponse);
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
