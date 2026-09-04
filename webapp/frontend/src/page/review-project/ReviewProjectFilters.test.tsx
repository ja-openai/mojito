import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { Profiler, useLayoutEffect } from 'react';
import {
  MemoryRouter,
  type NavigateFunction,
  Route,
  Routes,
  useLocation,
  useNavigate,
} from 'react-router-dom';
import { beforeAll, describe, expect, it, vi } from 'vitest';

import type * as GlossariesApi from '../../api/glossaries';
import { REVIEW_PROJECT_DETAIL_QUERY_KEY } from '../../hooks/useReviewProjectDetail';
import { UserContext } from '../../hooks/useUser';
import { userPreferencesQueryKey } from '../../hooks/useUserPreferences';
import { buildCarryoverProject, carryoverFixtures } from './review-project-carryover.fixtures';
import { ReviewProjectPage } from './ReviewProjectPage';

vi.mock('../../api/glossaries', async (importActual) => ({
  ...(await importActual<typeof GlossariesApi>()),
  matchGlossaryTerms: vi.fn().mockResolvedValue({ matchedTerms: [] }),
}));
vi.mock('../../api/ai-review', () => ({
  fetchPrecomputedAiReview: vi.fn().mockResolvedValue(null),
  formatAiReviewError: () => ({ message: 'Fixture AI error', detail: null }),
  requestAiReview: vi.fn(),
}));
// Keep filtering, selection, and URL synchronization real; JSDOM has no viewport.
vi.mock('../../components/virtual/useVirtualRows', () => ({
  useVirtualRows: ({
    count,
    getItemKey,
  }: {
    count: number;
    getItemKey: (index: number) => number;
  }) => ({
    scrollRef: { current: null },
    items: Array.from({ length: count }, (_, index) => ({
      index,
      key: getItemKey(index),
      start: index * 100,
      end: (index + 1) * 100,
      size: 100,
      lane: 0,
    })),
    totalSize: count * 100,
    scrollToIndex: vi.fn(),
    measureElement: vi.fn(),
  }),
}));

beforeAll(() => {
  Object.defineProperty(HTMLElement.prototype, 'scrollTo', { configurable: true, value: vi.fn() });
});

function mountFilters(initialState: 'all' | 'PENDING', { hasDecidedRow = true } = {}) {
  const project = buildCarryoverProject(carryoverFixtures[0]);
  const pending = project.reviewProjectTextUnits[0];
  pending.id = 101;
  pending.tmTextUnit = { ...pending.tmTextUnit!, id: 201 };
  const decided = project.reviewProjectTextUnits[1];
  decided.reviewProjectTextUnitDecision = {
    ...decided.reviewProjectTextUnitDecision!,
    decisionState: hasDecidedRow ? 'DECIDED' : 'PENDING',
    decisionTmTextUnitVariant: hasDecidedRow ? decided.currentTmTextUnitVariant : null,
  };
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, staleTime: Infinity } },
  });
  queryClient.setQueryData([...REVIEW_PROJECT_DETAIL_QUERY_KEY, project.id], project);
  queryClient.setQueryData(userPreferencesQueryKey('fixture-reviewer'), {
    initialized: true,
    worksetSize: null,
    preferredLocales: [],
    shortcutHelp: null,
    visibleTextEditorEnabled: false,
    reviewProjectSearchEnabled: false,
    defaultReviewTeamIds: [],
  });

  const locations: string[] = [];
  const visibleStates: string[] = [];
  let navigate!: NavigateFunction;
  function LocationProbe() {
    const location = useLocation();
    navigate = useNavigate();
    useLayoutEffect(() => {
      locations.push(location.search);
    }, [location]);
    return <output data-testid="filter-location">{location.search}</output>;
  }
  const result = render(
    <QueryClientProvider client={queryClient}>
      <UserContext.Provider
        value={{
          username: 'fixture-reviewer',
          role: 'ROLE_TRANSLATOR',
          canTranslateAllLocales: true,
          userLocales: [],
        }}
      >
        <MemoryRouter
          initialEntries={[
            `/review-projects/${project.id}?tu=201&marker=keep${initialState === 'all' ? '' : `&state=${initialState}`}`,
          ]}
        >
          <LocationProbe />
          <Profiler
            id="review-filter"
            onRender={() => {
              const activeState = document.querySelector(
                '[role="menu"] .filter-chip__section:first-child .is-active',
              )?.textContent;
              if (activeState && visibleStates[visibleStates.length - 1] !== activeState) {
                visibleStates.push(activeState);
              }
            }}
          >
            <Routes>
              <Route path="/review-projects/:projectId" element={<ReviewProjectPage />} />
            </Routes>
          </Profiler>
        </MemoryRouter>
      </UserContext.Provider>
    </QueryClientProvider>,
  );
  return {
    ...result,
    decided,
    project,
    locations,
    visibleStates,
    navigate: (to: string | number) => {
      if (typeof to === 'number') void navigate(to);
      else void navigate(to);
    },
  };
}

async function chooseState(menu: HTMLElement, name: string) {
  await act(() => Promise.resolve(fireEvent.click(within(menu).getByRole('button', { name }))));
}

describe('Review Project filters', () => {
  it.each(['all', 'PENDING'] as const)(
    'keeps Decided selected when changing from %s moves the selected row',
    async (initialState) => {
      const harness = mountFilters(initialState);
      fireEvent.click(screen.getByRole('button', { name: 'Filter text units' }));
      const menu = screen.getByRole('menu');
      harness.locations.length = 0;
      await chooseState(menu, 'Decided');

      await waitFor(() => {
        expect(within(menu).getByRole('button', { name: 'Decided' })).toHaveClass('is-active');
        expect(screen.getByTestId('filter-location')).toHaveTextContent('state=DECIDED');
        expect(screen.getByTestId('filter-location')).toHaveTextContent(
          `tu=${harness.decided.tmTextUnit!.id}`,
        );
      });
      expect(screen.getByRole('menu')).toBe(menu);
      expect(harness.container.querySelectorAll('.review-project-row')).toHaveLength(1);
      expect(harness.visibleStates).toEqual([
        initialState === 'all' ? 'All states' : 'Pending',
        'Decided',
      ]);
      expect(
        harness.locations.every((search) => new URLSearchParams(search).get('state') === 'DECIDED'),
      ).toBe(true);
      expect(screen.getByTestId('filter-location')).toHaveTextContent('marker=keep');
    },
  );

  it('retains an empty State filter and can clear it without closing the menu', async () => {
    const harness = mountFilters('all', { hasDecidedRow: false });
    fireEvent.click(screen.getByRole('button', { name: 'Filter text units' }));
    const menu = screen.getByRole('menu');
    await chooseState(menu, 'Decided');
    expect(within(menu).getByRole('button', { name: 'Decided' })).toHaveClass('is-active');
    expect(harness.container.querySelectorAll('.review-project-row')).toHaveLength(0);
    expect(screen.getByTestId('filter-location')).toHaveTextContent('state=DECIDED');

    await chooseState(menu, 'All states');
    expect(screen.getByRole('menu')).toBe(menu);
    expect(within(menu).getByRole('button', { name: 'All states' })).toHaveClass('is-active');
    expect(harness.container.querySelectorAll('.review-project-row')).toHaveLength(2);
    expect(screen.getByTestId('filter-location')).not.toHaveTextContent('state=');
    expect(screen.getByTestId('filter-location')).toHaveTextContent('marker=keep');
    expect(harness.visibleStates).toEqual(['All states', 'Decided', 'All states']);
  });

  it('follows State and row changes from browser back and forward without reverting them', async () => {
    const harness = mountFilters('PENDING');
    fireEvent.click(screen.getByRole('button', { name: 'Filter text units' }));
    const menu = screen.getByRole('menu');
    const decidedId = harness.decided.tmTextUnit!.id;
    await act(() =>
      Promise.resolve(
        harness.navigate(`/review-projects/${harness.project.id}?tu=${decidedId}&state=DECIDED`),
      ),
    );
    expect(within(menu).getByRole('button', { name: 'Decided' })).toHaveClass('is-active');
    expect(screen.getByTestId('filter-location')).toHaveTextContent(`tu=${decidedId}`);

    await act(() => Promise.resolve(harness.navigate(-1)));
    expect(within(menu).getByRole('button', { name: 'Pending' })).toHaveClass('is-active');
    expect(screen.getByTestId('filter-location')).toHaveTextContent('tu=201');
    expect(screen.getByTestId('filter-location')).toHaveTextContent('marker=keep');

    await act(() => Promise.resolve(harness.navigate(1)));
    expect(within(menu).getByRole('button', { name: 'Decided' })).toHaveClass('is-active');
    expect(screen.getByTestId('filter-location')).toHaveTextContent(`tu=${decidedId}`);
    expect(screen.getByRole('menu')).toBe(menu);
    expect(harness.visibleStates).toEqual(['Pending', 'Decided', 'Pending', 'Decided']);
  });
});
