import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ReviewAutomationExcludedLocalesField } from './ReviewAutomationExcludedLocalesField';

const mocks = vi.hoisted(() => ({
  fetchReviewFeatureLocales: vi.fn(),
  locales: [
    { bcp47Tag: 'ar' },
    { bcp47Tag: 'de' },
    { bcp47Tag: 'fr' },
    { bcp47Tag: 'he' },
    { bcp47Tag: 'ja' },
  ],
}));

vi.mock('../../api/review-features', () => ({
  fetchReviewFeatureLocales: mocks.fetchReviewFeatureLocales,
}));
vi.mock('../../hooks/useLocales', () => ({
  useLocales: () => ({ data: mocks.locales, isLoading: false, isError: false }),
}));

function renderField({ featureIds = [1, 2], selectedTags = [] as string[] } = {}) {
  const onChange = vi.fn();
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  const makeField = (nextFeatureIds: number[]) => (
    <QueryClientProvider client={queryClient}>
      <ReviewAutomationExcludedLocalesField
        featureIds={nextFeatureIds}
        selectedTags={selectedTags}
        onChange={onChange}
      />
    </QueryClientProvider>
  );
  const view = render(makeField(featureIds));
  return {
    onChange,
    rerenderFeatures: (nextFeatureIds: number[]) => view.rerender(makeField(nextFeatureIds)),
  };
}

async function openSelector() {
  const selector = screen.getByRole('button', { name: 'Select excluded locales' });
  await waitFor(() => expect(selector).toBeEnabled());
  await userEvent.click(selector);
}

describe('ReviewAutomationExcludedLocalesField locale scope', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.fetchReviewFeatureLocales.mockResolvedValue(['de', 'fr', 'fr']);
  });

  it('defaults to selected-feature locales and shows overlapping locales once', async () => {
    renderField({ featureIds: [2, 1, 2] });
    await openSelector();

    expect(mocks.fetchReviewFeatureLocales).toHaveBeenCalledWith([1, 2]);
    expect(await screen.findAllByRole('checkbox', { name: /French/ })).toHaveLength(1);
    expect(screen.getByRole('checkbox', { name: /German/ })).toBeVisible();
    expect(screen.queryByRole('checkbox', { name: /Japanese/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('checkbox', { name: /Hebrew/ })).not.toBeInTheDocument();
  });

  it('can show the global catalog and return to feature locales without losing an exclusion', async () => {
    const user = userEvent.setup();
    const { onChange } = renderField({ selectedTags: ['he'] });
    await openSelector();
    expect(screen.getByRole('checkbox', { name: /Hebrew/ })).toBeChecked();

    await user.click(screen.getByRole('button', { name: 'Show all available locales' }));
    expect(await screen.findByRole('checkbox', { name: /Japanese/ })).toBeVisible();
    expect(screen.getByRole('checkbox', { name: /Arabic/ })).toBeVisible();
    expect(screen.getByRole('checkbox', { name: /Hebrew/ })).toBeChecked();

    await user.click(
      screen.getByRole('button', { name: 'Show locales used by selected review features' }),
    );
    expect(screen.queryByRole('checkbox', { name: /Japanese/ })).not.toBeInTheDocument();
    expect(screen.getByRole('checkbox', { name: /French/ })).toBeVisible();
    expect(screen.getByRole('checkbox', { name: /Hebrew/ })).toBeChecked();
    expect(onChange).not.toHaveBeenCalled();
  });

  it('refreshes the scope when features change while retaining selected out-of-scope locales', async () => {
    mocks.fetchReviewFeatureLocales.mockImplementation((featureIds: number[]) =>
      Promise.resolve(featureIds.includes(3) ? ['ja'] : ['de', 'fr']),
    );
    const { rerenderFeatures, onChange } = renderField({ selectedTags: ['he'] });
    await openSelector();
    expect(await screen.findByRole('checkbox', { name: /German/ })).toBeVisible();

    rerenderFeatures([3]);
    await waitFor(() => expect(mocks.fetchReviewFeatureLocales).toHaveBeenCalledWith([3]));
    expect(await screen.findByRole('checkbox', { name: /Japanese/ })).toBeVisible();
    expect(screen.queryByRole('checkbox', { name: /German/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('checkbox', { name: /French/ })).not.toBeInTheDocument();
    expect(screen.getByRole('checkbox', { name: /Hebrew/ })).toBeChecked();
    expect(onChange).not.toHaveBeenCalled();
  });

  it('offers the global catalog when no features are selected without loading every feature', async () => {
    const user = userEvent.setup();
    renderField({ featureIds: [] });
    await openSelector();

    expect(
      screen.getByText('Select review features to see their locales, or choose Show all locales.'),
    ).toBeVisible();
    expect(mocks.fetchReviewFeatureLocales).not.toHaveBeenCalled();
    expect(screen.queryAllByRole('checkbox')).toHaveLength(0);
    await user.click(screen.getByRole('button', { name: 'Show all available locales' }));
    expect(await screen.findByRole('checkbox', { name: /Hebrew/ })).toBeVisible();
  });

  it('names a saved exclusion when no feature locales are available', () => {
    renderField({ featureIds: [], selectedTags: ['he'] });

    const selector = screen.getByRole('button', { name: 'Select excluded locales' });
    expect(selector).toHaveTextContent('Hebrew');
    expect(selector).not.toHaveTextContent('All locales');
    expect(mocks.fetchReviewFeatureLocales).not.toHaveBeenCalled();
  });

  it('keeps the global fallback available when feature locales fail to load', async () => {
    const user = userEvent.setup();
    mocks.fetchReviewFeatureLocales.mockRejectedValue(new Error('Feature locales unavailable'));
    const { onChange } = renderField({ selectedTags: ['he'] });
    await openSelector();

    expect(
      await screen.findByText(
        'Could not load feature locales. Choose Show all locales to use the full list.',
      ),
    ).toBeVisible();
    const selector = screen.getByRole('button', { name: 'Select excluded locales' });
    expect(selector).toHaveTextContent('Hebrew');
    expect(selector).not.toHaveTextContent('All locales');
    expect(screen.getByRole('checkbox', { name: /Hebrew/ })).toBeChecked();
    await user.click(screen.getByRole('button', { name: 'Show all available locales' }));
    expect(await screen.findByRole('checkbox', { name: /Japanese/ })).toBeVisible();
    expect(screen.getByRole('checkbox', { name: /Hebrew/ })).toBeChecked();
    expect(onChange).not.toHaveBeenCalled();
  });
});
