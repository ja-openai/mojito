import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation, useNavigate } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import type * as ReviewProjectsApi from '../../api/review-projects';
import type { ApiReviewProjectDetail, ApiReviewProjectTextUnit } from '../../api/review-projects';
import { UserContext } from '../../hooks/useUser';
import { ReviewProjectFindReplacePage } from './ReviewProjectFindReplacePage';

const detailMock = vi.hoisted(() => vi.fn());
const stageMock = vi.hoisted(() =>
  vi.fn<typeof ReviewProjectsApi.saveReviewProjectTextUnitSuggestion>(),
);
const clearMock = vi.hoisted(() =>
  vi.fn<typeof ReviewProjectsApi.deleteReviewProjectTextUnitSuggestion>(),
);
const saveMock = vi.hoisted(() =>
  vi.fn<typeof ReviewProjectsApi.saveReviewProjectTextUnitDecision>(),
);

vi.mock('../../api/review-projects', async (importActual) => ({
  ...(await importActual<typeof ReviewProjectsApi>()),
  saveReviewProjectTextUnitSuggestion: stageMock,
  deleteReviewProjectTextUnitSuggestion: clearMock,
  saveReviewProjectTextUnitDecision: saveMock,
}));
vi.mock('../../hooks/useReviewProjectDetail', () => ({
  REVIEW_PROJECT_DETAIL_QUERY_KEY: ['review-project'],
  useReviewProjectDetail: detailMock,
}));
vi.mock('../../hooks/useVisibleTextEditorEnabled', () => ({
  useVisibleTextEditorEnabled: () => false,
}));

function row(id: number, staged = false): ApiReviewProjectTextUnit {
  return {
    id,
    reviewStateRevision: `original-${id}`,
    tmTextUnit: { id: id + 100, name: `bulk.${id}`, content: 'Old source', asset: null },
    baselineTmTextUnitVariant: {
      id: id + 200,
      content: `Old target ${id}`,
      status: 'REVIEW_NEEDED',
      includedInLocalizedFile: true,
    },
    currentTmTextUnitVariant: null,
    reviewProjectTextUnitDecision: {
      decisionState: 'PENDING',
      notes: null,
      decisionTmTextUnitVariant: null,
    },
    reviewProjectTextUnitSuggestion: staged
      ? {
          id: id + 300,
          target: `New target ${id}`,
          previousTarget: `Old target ${id}`,
          source: 'FIND_REPLACE',
        }
      : null,
    terminologyFeedbacks: [],
  };
}

function project(rows = [row(101), row(102)], id = 7): ApiReviewProjectDetail {
  return {
    id,
    type: 'NORMAL',
    status: 'OPEN',
    locale: { id: 3, bcp47Tag: 'fr' },
    reviewProjectRequest: { id: id + 70, name: `Project ${id}`, screenshotImageIds: [] },
    reviewProjectTextUnits: rows,
  };
}

function mount(initial: ApiReviewProjectDetail) {
  let current = initial;
  detailMock.mockImplementation(() => ({
    data: current,
    isLoading: false,
    isError: false,
    error: null,
  }));
  function Location() {
    const location = useLocation();
    const navigate = useNavigate();
    return (
      <>
        <output data-testid="location">{location.pathname}</output>
        <button onClick={() => void navigate('/elsewhere')}>Leave test page</button>
        <button onClick={() => void navigate(-1)}>Return test page</button>
      </>
    );
  }
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  client.setQueryData(['review-project', initial.id], initial);
  const node = () => (
    <QueryClientProvider client={client}>
      <UserContext.Provider
        value={{
          username: 'bulk-reviewer',
          role: 'ROLE_PM',
          canTranslateAllLocales: true,
          userLocales: [],
        }}
      >
        <MemoryRouter initialEntries={['/review-projects/7/find-replace']}>
          <Location />
          <Routes>
            <Route
              path="/review-projects/:projectId/find-replace"
              element={<ReviewProjectFindReplacePage />}
            />
            <Route path="/review-projects/:projectId" element={<p>Review destination</p>} />
            <Route path="/elsewhere" element={<p>Other page</p>} />
          </Routes>
        </MemoryRouter>
      </UserContext.Provider>
    </QueryClientProvider>
  );
  const result = render(node());
  return {
    ...result,
    client,
    refresh: (next: ApiReviewProjectDetail) => {
      current = next;
      client.setQueryData(['review-project', initial.id], next);
      result.rerender(node());
    },
  };
}

function replaceAll() {
  fireEvent.change(screen.getByLabelText('Find'), { target: { value: 'Old' } });
  fireEvent.change(screen.getByLabelText('Replace'), { target: { value: 'New' } });
  fireEvent.click(screen.getByRole('button', { name: 'Replace all' }));
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

beforeEach(() => {
  detailMock.mockReset();
  stageMock.mockReset();
  clearMock.mockReset();
  saveMock.mockReset();
});

describe('Review Project bulk adversarial workflows', () => {
  it('explicit Reset adopts the latest observed server row after preserving a local edit during refresh', async () => {
    const initial = project([row(101)]);
    const view = mount(initial);
    replaceAll();
    view.refresh({
      ...initial,
      reviewProjectTextUnits: [
        {
          ...row(101),
          reviewStateRevision: 'external-101',
          currentTmTextUnitVariant: {
            id: 999,
            content: 'External target',
            status: 'APPROVED',
            includedInLocalizedFile: true,
          },
        },
      ],
    });
    expect(screen.getByDisplayValue('New target 101')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Reset' }));
    fireEvent.change(screen.getByLabelText('Find'), { target: { value: '' } });
    expect(screen.getByDisplayValue('External target')).toBeInTheDocument();
    fireEvent.change(screen.getByDisplayValue('External target'), {
      target: { value: 'Edited external target' },
    });
    stageMock.mockRejectedValue(new Error('Keep the new draft visible'));
    fireEvent.click(screen.getByRole('button', { name: 'Stage in project' }));
    await waitFor(() =>
      expect(stageMock).toHaveBeenCalledWith(
        expect.objectContaining({
          textUnitId: 101,
          target: 'Edited external target',
          expectedCurrentTmTextUnitVariantId: 999,
          expectedReviewStateRevision: 'external-101',
        }),
      ),
    );
    await screen.findByText('Keep the new draft visible');
  });

  it('preserves local replacements and their base revision when a background refresh arrives', async () => {
    const initial = project([row(101)]);
    const view = mount(initial);
    replaceAll();
    expect(screen.getByDisplayValue('New target 101')).toBeInTheDocument();
    view.refresh({
      ...initial,
      reviewProjectTextUnits: [
        {
          ...row(101),
          reviewStateRevision: 'external-101',
          currentTmTextUnitVariant: {
            id: 999,
            content: 'External target',
            status: 'APPROVED',
            includedInLocalizedFile: true,
          },
        },
      ],
    });
    expect(screen.getByDisplayValue('New target 101')).toBeInTheDocument();
    stageMock.mockRejectedValue(new Error('Conflict'));
    fireEvent.click(screen.getByRole('button', { name: 'Stage in project' }));
    await waitFor(() =>
      expect(stageMock).toHaveBeenCalledWith(
        expect.objectContaining({
          target: 'New target 101',
          expectedReviewStateRevision: 'original-101',
        }),
      ),
    );
  });

  it.each(['stage', 'accept'] as const)(
    'retains acknowledged progress after a partial %s failure so retry does not resend a stale row',
    async (mode) => {
      mount(project());
      const applyMock = mode === 'stage' ? stageMock : saveMock;
      applyMock
        .mockResolvedValueOnce({
          ...row(101, mode === 'stage'),
          reviewStateRevision: 'saved-101',
          ...(mode === 'accept'
            ? {
                currentTmTextUnitVariant: {
                  id: 999,
                  content: 'New target 101',
                  status: 'APPROVED',
                  includedInLocalizedFile: true,
                },
              }
            : {}),
        })
        .mockRejectedValueOnce(new Error('Second row temporarily unavailable'))
        .mockRejectedValue(new Error('Stale first row revision'));
      replaceAll();
      if (mode === 'accept')
        fireEvent.click(screen.getByRole('button', { name: 'Accept + decide' }));
      const name = mode === 'stage' ? 'Stage in project' : 'Accept changes';
      fireEvent.click(screen.getByRole('button', { name }));
      await screen.findByText('Second row temporarily unavailable');
      expect(applyMock.mock.calls.map(([request]) => request.textUnitId)).toEqual([101, 102]);
      fireEvent.click(screen.getByRole('button', { name }));
      await waitFor(() => expect(applyMock).toHaveBeenCalledTimes(3));
      expect(applyMock.mock.calls[2][0]).toEqual(
        expect.objectContaining({ textUnitId: 102, expectedReviewStateRevision: 'original-102' }),
      );
    },
  );

  it('retains acknowledged clears after a partial Reset failure', async () => {
    mount(project([row(101, true), row(102, true)]));
    clearMock
      .mockResolvedValueOnce({ ...row(101), reviewStateRevision: 'cleared-101' })
      .mockRejectedValueOnce(new Error('Second clear unavailable'))
      .mockRejectedValue(new Error('Stale first clear'));
    fireEvent.click(screen.getByRole('button', { name: 'Reset' }));
    await screen.findByText('Second clear unavailable');
    fireEvent.click(screen.getByRole('button', { name: 'Reset' }));
    await waitFor(() => expect(clearMock).toHaveBeenCalledTimes(3));
    expect(clearMock.mock.calls[2][0]).toEqual({
      textUnitId: 102,
      expectedReviewStateRevision: 'original-102',
    });
  });

  it('does not navigate away from another page when an old pending apply completes', async () => {
    const view = mount(project([row(101)]));
    const pending = deferred<ApiReviewProjectTextUnit>();
    stageMock.mockReturnValue(pending.promise);
    replaceAll();
    fireEvent.click(screen.getByRole('button', { name: 'Stage in project' }));
    await waitFor(() => expect(stageMock).toHaveBeenCalledTimes(1));
    fireEvent.click(screen.getByRole('button', { name: 'Leave test page' }));
    expect(screen.getByTestId('location')).toHaveTextContent('/elsewhere');
    const cancel = vi.spyOn(view.client, 'cancelQueries');
    await act(async () => {
      pending.resolve({ ...row(101, true), reviewStateRevision: 'saved-101' });
      await pending.promise;
    });
    expect(screen.getByTestId('location')).toHaveTextContent('/elsewhere');
    expect(cancel).not.toHaveBeenCalled();
  });

  it('prevents edits and replacement actions while an apply is pending', async () => {
    mount(project([row(101)]));
    const pending = deferred<ApiReviewProjectTextUnit>();
    stageMock.mockReturnValue(pending.promise);
    replaceAll();
    fireEvent.click(screen.getByRole('button', { name: 'Stage in project' }));
    await waitFor(() => expect(stageMock).toHaveBeenCalledTimes(1));
    expect(screen.getByDisplayValue('New target 101')).toHaveAttribute('readonly');
    expect(screen.getByRole('button', { name: 'Reset' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Undo' })).toBeDisabled();
    fireEvent.change(screen.getByLabelText('Find'), { target: { value: 'New' } });
    expect(screen.getByRole('button', { name: 'Replace all' })).toBeDisabled();
    fireEvent.keyDown(screen.getByLabelText('Find'), { key: 'Enter' });
    await act(async () => {
      pending.resolve({ ...row(101, true), reviewStateRevision: 'saved-101' });
      await pending.promise;
    });
    expect(stageMock.mock.calls[0][0].target).toBe('New target 101');
    expect(screen.getByTestId('location')).toHaveTextContent('/review-projects/7');
  });

  it('restores remaining edits and acknowledged rows after partial stage failure and route navigation', async () => {
    const view = mount(project());
    stageMock
      .mockResolvedValueOnce({ ...row(101, true), reviewStateRevision: 'saved-101' })
      .mockRejectedValueOnce(new Error('Second row unavailable'))
      .mockRejectedValue(new Error('Still unavailable'));
    replaceAll();
    fireEvent.click(screen.getByRole('button', { name: 'Stage in project' }));
    await screen.findByText('Second row unavailable');
    expect(
      view.client.getQueryData<ApiReviewProjectDetail>(['review-project', 7])
        ?.reviewProjectTextUnits?.[0].reviewStateRevision,
    ).toBe('saved-101');
    fireEvent.click(screen.getByRole('button', { name: 'Leave test page' }));
    const unload = new Event('beforeunload', { cancelable: true });
    window.dispatchEvent(unload);
    expect(unload.defaultPrevented).toBe(true);
    fireEvent.click(screen.getByRole('button', { name: 'Return test page' }));
    expect(screen.getByDisplayValue('New target 101')).toBeInTheDocument();
    expect(screen.getByDisplayValue('New target 102')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Stage in project' }));
    await waitFor(() => expect(stageMock).toHaveBeenCalledTimes(3));
    expect(stageMock.mock.calls[2][0]).toEqual(
      expect.objectContaining({ textUnitId: 102, expectedReviewStateRevision: 'original-102' }),
    );
    act(() => view.client.clear());
  });

  it('adopts current server state on remount when the retained session was only pending, with no local edit', async () => {
    const view = mount(project([row(101, true)]));
    const pending = deferred<ApiReviewProjectTextUnit>();
    clearMock.mockReturnValue(pending.promise);
    fireEvent.click(screen.getByRole('button', { name: 'Reset' }));
    await waitFor(() => expect(clearMock).toHaveBeenCalledTimes(1));
    fireEvent.click(screen.getByRole('button', { name: 'Leave test page' }));
    view.refresh(project([{ ...row(101), reviewStateRevision: 'cleared-101' }]));
    fireEvent.click(screen.getByRole('button', { name: 'Return test page' }));
    expect(screen.getByDisplayValue('Old target 101')).toBeInTheDocument();
    expect(screen.queryByDisplayValue('New target 101')).not.toBeInTheDocument();
    await act(async () => {
      pending.resolve({ ...row(101), reviewStateRevision: 'cleared-101' });
      await pending.promise;
    });
    expect(screen.getByDisplayValue('Old target 101')).toBeInTheDocument();
  });
});
