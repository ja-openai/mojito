import { focusManager, QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReactNode } from 'react';
import { Link, MemoryRouter, Route, Routes, useLocation, useNavigate } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type * as ContentApi from '../../api/content';
import {
  type ApiContentAsset,
  type ApiContentAssets,
  type ApiContentPreview,
  fetchContentAssets,
  fetchContentDirectories,
  fetchContentEmailParts,
  fetchContentPreview,
} from '../../api/content';
import { type ApiRepository, fetchRepositories } from '../../api/repositories';
import type * as FileTreeModule from '../../components/FileTree';
import { ContentPage } from './ContentPage';

const account = vi.hoisted(() => ({ role: 'ROLE_ADMIN' }));
vi.mock('../../hooks/useUser', () => ({ useUser: () => account }));
vi.mock('../../api/repositories', () => ({ fetchRepositories: vi.fn() }));
vi.mock('../../api/content', async (importOriginal) => ({
  ...(await importOriginal<typeof ContentApi>()),
  fetchContentAssets: vi.fn(),
  fetchContentDirectories: vi.fn(),
  fetchContentPreview: vi.fn(),
  fetchContentEmailParts: vi.fn(),
}));
// Exercise page navigation independently from the shared tree's viewport measurements.
vi.mock('../../components/FileTree', async (importOriginal) => ({
  ...(await importOriginal<typeof FileTreeModule>()),
  FileTree: ({
    items,
    ariaLabel,
  }: {
    items: { key: string; depth: number; content: ReactNode }[];
    ariaLabel: string;
  }) => (
    <div role="list" aria-label={ariaLabel}>
      {items.map((item) => (
        <div role="listitem" key={item.key} data-depth={item.depth}>
          {item.content}
        </div>
      ))}
    </div>
  ),
}));
vi.mock('../text-unit-detail/TextUnitDetailPage', () => ({
  TextUnitDetailPage: ({
    embedded,
  }: {
    embedded: {
      tmTextUnitId: number;
      localeTag: string;
      presentation: 'compact' | 'full';
      onClose: () => void;
      onSaved: () => void;
      onNext?: () => void;
      onShowDetails: () => void;
      onShowCompact: () => void;
    };
  }) => (
    <div data-testid="embedded-editor">
      <textarea aria-label="Draft translation" defaultValue="" />
      <p>
        Editing {embedded.tmTextUnitId} in {embedded.localeTag}
      </p>
      <button onClick={embedded.onClose}>Close editor</button>
      <button onClick={embedded.onSaved}>Save embedded translation</button>
      {embedded.onNext ? <button onClick={embedded.onNext}>Next passage</button> : null}
      <button
        onClick={
          embedded.presentation === 'compact' ? embedded.onShowDetails : embedded.onShowCompact
        }
      >
        {embedded.presentation === 'compact' ? 'Open side panel' : 'Edit inline'}
      </button>
    </div>
  ),
}));

const repositories: ApiRepository[] = [
  {
    id: 1,
    name: 'Marketing',
    sourceLocale: { id: 1, bcp47Tag: 'en' },
    repositoryLocales: [
      { locale: { id: 2, bcp47Tag: 'fr-FR' }, parentLocale: null, toBeFullyTranslated: true },
    ],
  },
  {
    id: 2,
    name: 'Help center',
    sourceLocale: { id: 3, bcp47Tag: 'es' },
    repositoryLocales: [
      { locale: { id: 4, bcp47Tag: 'de' }, parentLocale: null, toBeFullyTranslated: true },
    ],
  },
];

const asset: ApiContentAsset = {
  assetId: 11,
  assetPath: 'guide.mdx',
  branchId: 5,
  branchName: 'master',
  sourceContentMd5: 'source-revision',
};

function assetPage(
  assets: ApiContentAsset[] = [asset],
  overrides: Partial<ApiContentAssets> = {},
): ApiContentAssets {
  return {
    repositoryId: 1,
    sourceLocaleTag: 'en',
    branchId: 5,
    branchName: 'master',
    branches: [
      { id: 5, name: 'master' },
      { id: 6, name: 'release' },
    ],
    assets,
    offset: 0,
    hasMore: false,
    nextCursor: null,
    previousCursor: null,
    warnings: [],
    ...overrides,
  };
}

function preview(branchId = 5, localeTag = 'fr-FR'): ApiContentPreview {
  return {
    repositoryId: 1,
    branchId,
    branchName: branchId === 6 ? 'release' : 'master',
    sourceLocaleTag: 'en',
    localeTag,
    document: {
      ...asset,
      repositoryId: 1,
      branchId,
      branchName: branchId === 6 ? 'release' : 'master',
      blocks: [
        {
          id: 'guide.title',
          type: 'heading',
          depth: 1,
          source: 'A practical guide',
          line: 1,
          translatable: true,
          reviewProjectTextUnitId: null,
          tmTextUnitId: 901,
          mappingStatus: 'MATCHED',
          targetContent: 'Un guide pratique',
          tmTextUnitVariantId: 902,
          targetStatus: 'APPROVED',
        },
      ],
      warnings: [],
    },
    warnings: [],
  };
}

function LocationProbe() {
  const location = useLocation();
  const navigate = useNavigate();
  return (
    <>
      <output data-testid="location-url">{location.pathname + location.search}</output>
      <output data-testid="location-state">{JSON.stringify(location.state)}</output>
      <button onClick={() => void navigate(-1)}>Previous location</button>
    </>
  );
}

function renderPage(path: string | string[] = '/content') {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={typeof path === 'string' ? [path] : path}>
        <LocationProbe />
        <Routes>
          <Route path="/content" element={<ContentPage />} />
          <Route path="/repositories" element={<h1>Repositories destination</h1>} />
          <Route path="/text-units/:tmTextUnitId" element={<h1>Translation editor</h1>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('ContentPage', () => {
  afterEach(() => {
    focusManager.setFocused(undefined);
    vi.unstubAllGlobals();
    vi.useRealTimers();
  });

  beforeEach(() => {
    vi.clearAllMocks();
    account.role = 'ROLE_ADMIN';
    vi.mocked(fetchRepositories).mockResolvedValue(repositories);
    vi.mocked(fetchContentAssets).mockImplementation((repositoryId, options) => {
      const branchId = options.branchId ?? 5;
      return Promise.resolve(
        assetPage([{ ...asset, branchId }], { repositoryId, branchId, offset: options.offset }),
      );
    });
    vi.mocked(fetchContentDirectories).mockImplementation((repositoryId, options) =>
      Promise.resolve({
        repositoryId,
        branchId: options.branchId ?? 5,
        directory: options.directory,
        directories: [],
        nextCursor: null,
      }),
    );
    vi.mocked(fetchContentPreview).mockImplementation((_repositoryId, _assetId, branchId, locale) =>
      Promise.resolve(preview(branchId ?? 5, locale)),
    );
  });

  function linkedPreview() {
    const value = preview();
    value.document!.assetPath = 'index.mdx';
    value.document!.blocks[0] = {
      ...value.document!.blocks[0],
      type: 'paragraph',
      targetContent: 'Lisez le [guide](guide.html).',
    };
    return value;
  }

  it('keeps preview help optional while passages are always editable', async () => {
    const user = userEvent.setup();
    renderPage('/content?repoId=1&assetId=11&locale=fr-FR');
    const help = await screen.findByRole('button', { name: 'Preview help' });
    expect(help).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByRole('region', { name: 'Preview help' })).not.toBeInTheDocument();

    await user.click(help);
    const panel = screen.getByRole('region', { name: 'Preview help' });
    expect(panel).toHaveTextContent('Edit passageClick');
    expect(panel).toHaveTextContent(/Follow link(?:⌘|Ctrl) \+ click/);
    expect(panel).not.toHaveTextContent('Double-click');
    await user.keyboard('{Escape}');
    expect(help).toHaveAttribute('aria-expanded', 'false');
    expect(help).toHaveFocus();

    await user.click(help);
    await user.click(screen.getByRole('button', { name: 'Edit guide.title' }));
    expect(help).toHaveAttribute('aria-expanded', 'false');
    expect(screen.getByRole('dialog', { name: 'Translation editor' })).toBeVisible();
    await user.click(help);
    await user.tab();
    expect(help).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByRole('button', { name: 'Edit translations' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Done editing' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Close preview' })).not.toBeInTheDocument();
  });

  it('follows a modifier-clicked link outside the current asset page on the same branch', async () => {
    vi.mocked(fetchContentPreview).mockImplementation((_repo, id) =>
      Promise.resolve(id === 11 ? linkedPreview() : preview()),
    );
    vi.mocked(fetchContentAssets).mockImplementation((_repo, options) =>
      Promise.resolve(
        options.searchMode === 'exact'
          ? assetPage([{ ...asset, assetId: 12 }])
          : assetPage([{ ...asset, assetPath: 'index.mdx' }]),
      ),
    );
    renderPage('/content?repoId=1&assetId=11&locale=fr-FR&directory=modules%2F');
    await userEvent.click(await screen.findByRole('link', { name: 'guide' }));
    expect(await screen.findByText('Editing 901 in fr-FR')).toBeVisible();
    fireEvent.click(screen.getByRole('link', { name: 'guide' }), { metaKey: true, detail: 1 });
    await waitFor(() => expect(screen.getByTestId('location-url')).toHaveTextContent('assetId=12'));
    expect(fetchContentAssets).toHaveBeenCalledWith(1, {
      branchId: 5,
      query: 'guide.mdx',
      searchMode: 'exact',
    });
    expect(screen.getByTestId('location-url')).toHaveTextContent('locale=fr-FR');
    expect(screen.getByTestId('location-url')).toHaveTextContent('directory=modules%2F');
    expect(screen.queryByText('Editing 901 in fr-FR')).not.toBeInTheDocument();
  });

  it('keeps the current page when a linked asset cannot be found', async () => {
    vi.mocked(fetchContentPreview).mockResolvedValue(linkedPreview());
    vi.mocked(fetchContentAssets).mockImplementation((_repo, options) =>
      Promise.resolve(assetPage(options.searchMode === 'exact' ? [] : [asset])),
    );
    renderPage('/content?repoId=1&assetId=11&locale=fr-FR');
    fireEvent.click(await screen.findByRole('link', { name: 'guide' }), {
      ctrlKey: true,
      detail: 1,
    });
    expect(await screen.findByText(/Could not open “guide.mdx”/)).toBeVisible();
    expect(screen.getByTestId('location-url')).toHaveTextContent('assetId=11');
  });

  it('follows source-locale links with a normal click without opening an editor', async () => {
    const source = linkedPreview();
    source.document!.blocks[0].source = 'Read the [guide](guide.html).';
    vi.mocked(fetchContentPreview).mockImplementation((_repo, id) =>
      Promise.resolve(id === 11 ? source : preview(5, 'en')),
    );
    vi.mocked(fetchContentAssets).mockImplementation((_repo, options) =>
      Promise.resolve(
        options.searchMode === 'exact'
          ? assetPage([{ ...asset, assetId: 12 }])
          : assetPage([{ ...asset, assetPath: 'index.mdx' }]),
      ),
    );
    renderPage('/content?repoId=1&branchId=5&assetId=11&locale=en');

    await userEvent.click(await screen.findByRole('link', { name: 'guide' }));

    await waitFor(() => expect(screen.getByTestId('location-url')).toHaveTextContent('assetId=12'));
    expect(await screen.findByRole('heading', { name: 'A practical guide' })).toBeVisible();
    expect(screen.queryByRole('dialog', { name: 'Translation editor' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Edit guide.title' })).not.toBeInTheDocument();
  });

  it('ignores a pending link lookup after the locale changes', async () => {
    let resolveLink!: (value: ApiContentAssets) => void;
    vi.mocked(fetchContentPreview).mockResolvedValue(linkedPreview());
    vi.mocked(fetchContentAssets).mockImplementation((_repo, options) =>
      options.searchMode === 'exact'
        ? new Promise((resolve) => {
            resolveLink = resolve;
          })
        : Promise.resolve(assetPage()),
    );
    renderPage('/content?repoId=1&assetId=11&locale=fr-FR');
    fireEvent.click(await screen.findByRole('link', { name: 'guide' }), {
      altKey: true,
      detail: 1,
    });
    await screen.findByText('Opening linked page…');
    await userEvent.click(screen.getByRole('button', { name: 'Content language' }));
    await userEvent.click(screen.getByRole('radio', { name: /^English · Source/ }));
    await act(() => Promise.resolve(resolveLink(assetPage([{ ...asset, assetId: 12 }]))));
    await waitFor(() => expect(screen.getByTestId('location-url')).toHaveTextContent('locale=en'));
    expect(screen.getByTestId('location-url')).toHaveTextContent('assetId=11');
    expect(fetchContentPreview).not.toHaveBeenCalledWith(1, 12, 5, 'fr-FR');
  });

  it('does not navigate after leaving Content while a link lookup is pending', async () => {
    let resolveLink!: (value: ApiContentAssets) => void;
    vi.mocked(fetchContentPreview).mockResolvedValue(linkedPreview());
    vi.mocked(fetchContentAssets).mockImplementation((_repo, options) =>
      options.searchMode === 'exact'
        ? new Promise((resolve) => {
            resolveLink = resolve;
          })
        : Promise.resolve(assetPage()),
    );
    render(
      <QueryClientProvider
        client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
      >
        <MemoryRouter initialEntries={['/content?repoId=1&assetId=11&locale=fr-FR']}>
          <LocationProbe />
          <Link to="/repositories">Leave Content</Link>
          <Routes>
            <Route path="/content" element={<ContentPage />} />
            <Route path="/repositories" element={<h1>Repositories destination</h1>} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );
    fireEvent.click(await screen.findByRole('link', { name: 'guide' }), {
      metaKey: true,
      detail: 1,
    });
    await screen.findByText('Opening linked page…');
    await userEvent.click(screen.getByRole('link', { name: 'Leave Content' }));
    await screen.findByRole('heading', { name: 'Repositories destination' });
    await act(() => Promise.resolve(resolveLink(assetPage([{ ...asset, assetId: 12 }]))));
    expect(screen.getByTestId('location-url')).toHaveTextContent(/^\/repositories$/);
  });

  it('edits an email subject and refreshes the grouped preview after saving', async () => {
    const body = preview();
    body.document!.assetPath = 'welcome_body.mdx';
    const companions = ['subject', 'preheader'].map((part, index) => ({
      ...body.document!,
      assetId: index + 20,
      assetPath: `welcome_${part}.mdx`,
      blocks: [
        {
          ...body.document!.blocks[0],
          type: 'paragraph',
          id: `welcome.${part}`,
          tmTextUnitId: index + 1001,
          targetContent: `Email ${part}`,
        },
      ],
    }));
    vi.mocked(fetchContentPreview).mockResolvedValue(body);
    vi.mocked(fetchContentEmailParts).mockResolvedValue({ documents: companions, warnings: [] });
    renderPage('/content?repoId=1&branchId=5&assetId=11&locale=fr-FR');
    await userEvent.click(
      await screen.findByRole('button', { name: 'Edit welcome.subject in welcome_subject.mdx' }),
    );
    expect(screen.getByText('Editing 1001 in fr-FR')).toBeVisible();
    vi.mocked(fetchContentEmailParts).mockResolvedValue({
      documents: companions.map((document) => ({
        ...document,
        blocks: document.blocks.map((block) => ({
          ...block,
          targetContent: 'Updated email field',
        })),
      })),
      warnings: [],
    });
    await userEvent.click(screen.getByRole('button', { name: 'Save embedded translation' }));
    expect(await screen.findAllByText('Updated email field')).toHaveLength(2);
    expect(fetchContentEmailParts).toHaveBeenCalledWith(1, 5, 'fr-FR', 'welcome_body.mdx');
    await userEvent.click(screen.getByRole('button', { name: 'Next passage' }));
    expect(screen.getByText('Editing 1002 in fr-FR')).toBeVisible();
  });

  it('shows the repository selector before loading any content', async () => {
    renderPage();
    expect(
      await screen.findByText('Select a repository to browse its pages and modules.'),
    ).toBeVisible();
    expect(fetchContentAssets).not.toHaveBeenCalled();
    expect(fetchContentPreview).not.toHaveBeenCalled();

    await userEvent.click(screen.getByRole('button', { name: 'Select content repository' }));
    await userEvent.click(screen.getByRole('radio', { name: /^Marketing/ }));

    expect(await screen.findByRole('button', { name: 'Open guide.mdx' })).toBeVisible();
    expect(fetchContentAssets).toHaveBeenCalledWith(
      1,
      expect.objectContaining({ branchId: null, query: '', offset: 0, searchMode: 'prefix' }),
    );
    expect(fetchContentPreview).not.toHaveBeenCalled();
    expect(screen.getByTestId('location-url')).toHaveTextContent('/content?repoId=1');
  });

  it('allows admins to browse repository content using a direct link', async () => {
    renderPage('/content?repoId=1');
    expect(await screen.findByRole('button', { name: 'Open guide.mdx' })).toBeVisible();
    expect(fetchRepositories).toHaveBeenCalledOnce();
    expect(fetchContentAssets).toHaveBeenCalledOnce();
  });

  it.each(['ROLE_PM', 'ROLE_TRANSLATOR', 'ROLE_USER'])(
    'redirects %s before repository or content queries run',
    async (role) => {
      account.role = role;
      renderPage('/content?repoId=1&assetId=11&locale=fr-FR');
      expect(
        await screen.findByRole('heading', { name: 'Repositories destination' }),
      ).toBeVisible();
      expect(fetchRepositories).not.toHaveBeenCalled();
      expect(fetchContentAssets).not.toHaveBeenCalled();
      expect(fetchContentPreview).not.toHaveBeenCalled();
    },
  );

  it('replaces the current hundred rows when paging and retains the server path search', async () => {
    const firstMatches = Array.from({ length: 100 }, (_, index) => ({
      ...asset,
      assetId: index + 100,
      assetPath: `modules/module-${index}.mdx`,
    }));
    const secondMatch = { ...asset, assetId: 200, assetPath: 'modules/footer.mdx' };
    vi.mocked(fetchContentAssets).mockImplementation((_repositoryId, options) =>
      Promise.resolve(
        options.query
          ? assetPage(options.after ? [secondMatch] : firstMatches, {
              nextCursor: options.after ? null : 'asset-100',
              previousCursor: options.after ? 'asset-200' : null,
            })
          : assetPage([asset], { hasMore: true }),
      ),
    );
    renderPage('/content?repoId=1');
    await screen.findByRole('button', { name: 'Open guide.mdx' });
    fireEvent.change(screen.getByRole('searchbox', { name: 'Search content paths' }), {
      target: { value: 'modules/' },
    });
    expect(fetchContentAssets).toHaveBeenCalledOnce();
    await userEvent.type(
      screen.getByRole('searchbox', { name: 'Search content paths' }),
      '{Enter}',
    );

    expect(await screen.findByRole('button', { name: 'Open modules/module-0.mdx' })).toBeVisible();
    expect(screen.queryByRole('button', { name: 'Open guide.mdx' })).not.toBeInTheDocument();
    expect(
      within(screen.getByRole('list', { name: 'Matching files' })).getAllByRole('listitem'),
    ).toHaveLength(100);
    expect(screen.getByText('100 assets on this page')).toBeVisible();
    expect(fetchContentAssets).toHaveBeenLastCalledWith(
      1,
      expect.objectContaining({
        branchId: null,
        query: 'modules/',
        offset: 0,
        searchMode: 'prefix',
        after: null,
        before: null,
      }),
    );
    const catalogue = screen.getByRole('region', { name: 'Repository pages and modules' });
    catalogue.scrollTop = 750;
    await userEvent.click(screen.getByRole('button', { name: 'Next' }));

    expect(await screen.findByRole('button', { name: 'Open modules/footer.mdx' })).toBeVisible();
    expect(catalogue.scrollTop).toBe(0);
    expect(
      screen.queryByRole('button', { name: 'Open modules/module-0.mdx' }),
    ).not.toBeInTheDocument();
    expect(
      within(screen.getByRole('list', { name: 'Matching files' })).getAllByRole('listitem'),
    ).toHaveLength(1);
    expect(screen.getByText('1 asset on this page')).toBeVisible();
    expect(fetchContentAssets).toHaveBeenLastCalledWith(
      1,
      expect.objectContaining({
        branchId: null,
        query: 'modules/',
        offset: 0,
        searchMode: 'prefix',
        after: 'asset-100',
        before: null,
      }),
    );
    expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled();
    expect(screen.getByTestId('location-url')).toHaveTextContent('after=asset-100');
    expect(fetchContentPreview).not.toHaveBeenCalled();

    await userEvent.click(screen.getByRole('button', { name: 'Previous' }));
    expect(await screen.findByRole('button', { name: 'Open modules/module-0.mdx' })).toBeVisible();
    expect(
      screen.queryByRole('button', { name: 'Open modules/footer.mdx' }),
    ).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Previous' })).toBeDisabled();
    expect(screen.getByTestId('location-url')).not.toHaveTextContent('offset=');
  });

  it('debounces typing, flushes submitted search once, and replaces live-search history', async () => {
    renderPage(['/repositories', '/content?repoId=1']);
    await screen.findByRole('button', { name: 'Open guide.mdx' });
    const search = screen.getByRole('searchbox', { name: 'Search content paths' });
    vi.useFakeTimers();

    fireEvent.change(search, { target: { value: 'mod' } });
    await act(() => vi.advanceTimersByTimeAsync(150));
    fireEvent.change(search, { target: { value: 'modules/' } });
    await act(() => vi.advanceTimersByTimeAsync(299));
    expect(fetchContentAssets).toHaveBeenCalledOnce();
    await act(() => vi.advanceTimersByTimeAsync(1));
    expect(fetchContentAssets).toHaveBeenCalledTimes(2);
    expect(fetchContentAssets).toHaveBeenLastCalledWith(
      1,
      expect.objectContaining({ query: 'modules/', searchMode: 'prefix' }),
    );

    fireEvent.change(search, { target: { value: 'modules/footer' } });
    fireEvent.submit(search.closest('form')!);
    expect(fetchContentAssets).toHaveBeenCalledTimes(3);
    expect(fetchContentAssets).toHaveBeenLastCalledWith(
      1,
      expect.objectContaining({ query: 'modules/footer', searchMode: 'prefix' }),
    );
    await act(() => vi.advanceTimersByTimeAsync(600));
    expect(fetchContentAssets).toHaveBeenCalledTimes(3);
    vi.useRealTimers();

    await userEvent.click(screen.getByRole('button', { name: 'Previous location' }));
    expect(await screen.findByRole('heading', { name: 'Repositories destination' })).toBeVisible();
  });

  it('clears the current search immediately and cancels pending text', async () => {
    renderPage('/content?repoId=1&q=guide&after=next');
    await screen.findByRole('button', { name: 'Open guide.mdx' });
    const search = screen.getByRole('searchbox', { name: 'Search content paths' });
    vi.useFakeTimers();
    fireEvent.change(search, { target: { value: 'pending' } });
    fireEvent.click(screen.getByRole('button', { name: 'Clear search text' }));

    expect(search).toHaveValue('');
    expect(fetchContentAssets).toHaveBeenLastCalledWith(
      1,
      expect.objectContaining({ query: '', after: null, before: null, offset: 0 }),
    );
    await act(() => vi.advanceTimersByTimeAsync(600));
    expect(fetchContentAssets).toHaveBeenCalledTimes(2);
    expect(screen.getByTestId('location-url')).not.toHaveTextContent('q=');
    expect(screen.getByTestId('location-url')).not.toHaveTextContent('after=');
  });

  it.each(['branch', 'repository', 'Back'])(
    'cancels pending search and restores URL text on %s navigation',
    async (navigation) => {
      renderPage(['/content?repoId=2&q=earlier', '/content?repoId=1&branchId=5&q=guide']);
      await screen.findByRole('button', { name: 'Open guide.mdx' });
      const search = screen.getByRole('searchbox', { name: 'Search content paths' });
      vi.useFakeTimers();
      fireEvent.change(search, { target: { value: 'pending' } });

      if (navigation === 'branch') {
        fireEvent.click(screen.getByRole('button', { name: 'Content branch' }));
        fireEvent.click(screen.getByRole('button', { name: 'release' }));
      } else if (navigation === 'repository') {
        fireEvent.click(screen.getByRole('button', { name: 'Select content repository' }));
        fireEvent.click(screen.getByRole('radio', { name: /^Help center/ }));
      } else {
        fireEvent.click(screen.getByRole('button', { name: 'Previous location' }));
      }
      await act(() => vi.advanceTimersByTimeAsync(600));

      const query = navigation === 'branch' ? 'guide' : navigation === 'Back' ? 'earlier' : '';
      expect(search).toHaveValue(query);
      expect(fetchContentAssets).toHaveBeenLastCalledWith(
        navigation === 'branch' ? 1 : 2,
        expect.objectContaining({ query, branchId: navigation === 'branch' ? 6 : null }),
      );
      expect(fetchContentAssets).toHaveBeenCalledTimes(2);
      expect(screen.getByTestId('location-url')).not.toHaveTextContent('pending');
    },
  );

  it('keeps the explorer mounted when switching files and retains its search, page and resize state', async () => {
    const second = { ...asset, assetId: 12, assetPath: 'guide-next.mdx' };
    vi.mocked(fetchContentAssets).mockResolvedValue(assetPage([asset, second], { offset: 100 }));
    renderPage('/content?repoId=1&branchId=5&locale=fr-FR&q=guide&offset=100');
    await userEvent.click(await screen.findByRole('button', { name: 'Open guide.mdx' }));
    expect(await screen.findByRole('heading', { name: 'Un guide pratique' })).toBeVisible();
    expect(screen.getByRole('list', { name: 'Matching files' })).toBeVisible();
    const splitter = screen.getByRole('separator', { name: 'Resize file explorer' });
    fireEvent.keyDown(splitter, { key: 'ArrowRight' });
    expect(splitter).toHaveAttribute('aria-valuenow', '30');
    expect(screen.getByRole('navigation', { name: 'Content pages' })).toBeVisible();

    await userEvent.click(screen.getByRole('button', { name: 'Open guide-next.mdx' }));

    expect(await screen.findByRole('heading', { name: 'Un guide pratique' })).toBeVisible();
    expect(screen.getByRole('searchbox', { name: 'Search content paths' })).toHaveValue('guide');
    expect(screen.getByText('2 assets on this page')).toBeVisible();
    expect(screen.getByTestId('location-url')).toHaveTextContent(
      '/content?repoId=1&branchId=5&locale=fr-FR&q=guide&offset=100&assetId=12',
    );
    expect(screen.getByRole('region', { name: 'Content preview' })).toBeVisible();
    expect(screen.getByRole('separator', { name: 'Resize file explorer' })).toBe(splitter);
    expect(splitter).toHaveAttribute('aria-valuenow', '30');
    expect(fetchContentAssets).toHaveBeenCalledOnce();
    expect(fetchContentPreview).toHaveBeenCalledTimes(2);
    localStorage.removeItem('mojito.content.explorerWidth');
  });

  it.each(['-100', '50', '2147483647', 'invalid'])(
    'uses the first page for an invalid offset %s',
    async (offset) => {
      renderPage(`/content?repoId=1&offset=${offset}`);
      await screen.findByRole('button', { name: 'Open guide.mdx' });
      expect(fetchContentAssets).toHaveBeenCalledWith(
        1,
        expect.objectContaining({ branchId: null, query: '', offset: 0, searchMode: 'prefix' }),
      );
      expect(screen.queryByRole('button', { name: 'Previous files' })).not.toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Open guide.mdx' })).toBeVisible();
    },
  );

  it.each([
    ['search', 'modules/', 5],
    ['clear search', '', 5],
    ['switch branch', 'guide', 6],
  ] as const)('resets the catalogue page on %s', async (action, query, branchId) => {
    renderPage('/content?repoId=1&branchId=5&q=guide&offset=100');
    await screen.findByRole('button', { name: 'Open guide.mdx' });
    if (action === 'search') {
      fireEvent.change(screen.getByRole('searchbox', { name: 'Search content paths' }), {
        target: { value: query },
      });
      await userEvent.type(
        screen.getByRole('searchbox', { name: 'Search content paths' }),
        '{Enter}',
      );
    } else if (action === 'clear search') {
      await userEvent.click(screen.getByRole('button', { name: 'Clear search text' }));
    } else {
      await userEvent.click(screen.getByRole('button', { name: 'Content branch' }));
      await userEvent.click(screen.getByRole('button', { name: 'release' }));
    }
    await waitFor(() =>
      expect(fetchContentAssets).toHaveBeenLastCalledWith(
        1,
        expect.objectContaining({ branchId, query, offset: 0 }),
      ),
    );
    expect(screen.getByTestId('location-url')).not.toHaveTextContent('offset=');
    expect(fetchContentPreview).not.toHaveBeenCalled();
  });

  it('preserves both catalogue and folder cursors when switching documents', async () => {
    const browse =
      '/content?repoId=1&branchId=5&locale=fr-FR&directory=content%2F&q=content%2Fguide&searchMode=prefix&after=asset-next&folderAfter=folder-next';
    const second = { ...asset, assetId: 12, assetPath: 'guide-next.mdx' };
    vi.mocked(fetchContentAssets).mockResolvedValue(
      assetPage([asset, second], { previousCursor: 'asset-back' }),
    );
    renderPage(browse);
    await userEvent.click(await screen.findByRole('button', { name: 'Open guide.mdx' }));
    await screen.findByRole('heading', { name: 'Un guide pratique' });
    await userEvent.click(screen.getByRole('button', { name: 'Open guide-next.mdx' }));
    expect(await screen.findByRole('heading', { name: 'Un guide pratique' })).toBeVisible();
    expect(screen.getByTestId('location-url')).toHaveTextContent(`${browse}&assetId=12`);
    expect(fetchContentAssets).toHaveBeenCalledOnce();
    expect(fetchContentDirectories).not.toHaveBeenCalled();
  });

  it('loads the explorer alongside a direct document link and keeps it mounted while editing', async () => {
    renderPage('/content?repoId=1&branchId=5&assetId=11&locale=fr-FR');
    await screen.findByRole('heading', { name: 'Un guide pratique' });
    expect(fetchContentDirectories).toHaveBeenCalledOnce();
    const explorer = screen.getByRole('list', { name: 'Content directory' });
    expect(explorer).toBeVisible();
    await userEvent.click(screen.getByRole('button', { name: 'Edit guide.title' }));
    await userEvent.click(screen.getByRole('button', { name: 'Close editor' }));
    expect(screen.getByRole('list', { name: 'Content directory' })).toBe(explorer);
    expect(fetchContentDirectories).toHaveBeenCalledOnce();
  });

  it('replaces fifty lazy folders instead of accumulating or loading descendants', async () => {
    const directories = Array.from({ length: 50 }, (_, index) => `content-${index}/`);
    vi.mocked(fetchContentDirectories).mockImplementation((repositoryId, options) =>
      Promise.resolve({
        repositoryId,
        branchId: 5,
        directory: '',
        directories: options.after ? ['other/'] : directories,
        nextCursor: options.after ? null : 'folder-next',
      }),
    );
    renderPage('/content?repoId=1&branchId=5');
    await screen.findByRole('button', { name: 'Browse content-0/' });
    expect(screen.getAllByRole('button', { name: /^Browse (?!root directory)/ })).toHaveLength(50);
    expect(fetchContentDirectories).toHaveBeenCalledOnce();
    await userEvent.click(screen.getByRole('button', { name: 'Next folders' }));
    expect(await screen.findByRole('button', { name: 'Browse other/' })).toBeVisible();
    expect(screen.getAllByRole('button', { name: /^Browse (?!root directory)/ })).toHaveLength(1);
    expect(screen.queryByRole('button', { name: 'Browse content-0/' })).not.toBeInTheDocument();
    expect(fetchContentDirectories).toHaveBeenLastCalledWith(1, {
      branchId: 5,
      directory: '',
      after: 'folder-next',
    });
    expect(fetchContentAssets).toHaveBeenCalledOnce();
    await userEvent.click(screen.getByRole('button', { name: 'First folders' }));
    await screen.findByRole('button', { name: 'Browse content-0/' });
    expect(screen.getAllByRole('button', { name: /^Browse (?!root directory)/ })).toHaveLength(50);
    expect(screen.queryByRole('button', { name: 'Browse other/' })).not.toBeInTheDocument();
  });

  it('navigates literal directories in the tree and resets search and both cursors', async () => {
    vi.mocked(fetchContentDirectories).mockImplementation((repositoryId, options) =>
      Promise.resolve({
        repositoryId,
        branchId: 5,
        directory: options.directory,
        directories:
          options.directory === ''
            ? ['content/']
            : options.directory === 'content/'
              ? ['content/100%_real/']
              : [],
        nextCursor: null,
      }),
    );
    renderPage(
      '/content?repoId=1&branchId=5&q=old&searchMode=exact&after=old-asset&folderAfter=old-folder',
    );
    await userEvent.click(await screen.findByRole('button', { name: 'Clear search text' }));
    await userEvent.click(await screen.findByRole('button', { name: 'Expand content/' }));
    await userEvent.click(await screen.findByRole('button', { name: 'Browse content/100%_real/' }));
    await waitFor(() =>
      expect(fetchContentAssets).toHaveBeenCalledWith(
        1,
        expect.objectContaining({
          directory: 'content/100%_real/',
          query: '',
          searchMode: 'prefix',
          recursive: false,
          after: null,
          before: null,
          offset: 0,
        }),
      ),
    );
    expect(screen.getByTestId('location-url')).not.toHaveTextContent('folderAfter=');
    const breadcrumb = within(screen.getByRole('list', { name: 'Content directory' }));
    expect(breadcrumb.getByRole('button', { name: 'Browse content/100%_real/' })).toHaveAttribute(
      'aria-current',
      'location',
    );
    await userEvent.click(breadcrumb.getByRole('button', { name: 'Browse content/' }));
    await waitFor(() =>
      expect(fetchContentDirectories).toHaveBeenCalledWith(1, {
        branchId: 5,
        directory: 'content/',
        after: null,
      }),
    );
    await userEvent.click(breadcrumb.getByRole('button', { name: 'Browse root directory' }));
    await waitFor(() =>
      expect(fetchContentAssets).toHaveBeenCalledWith(
        1,
        expect.objectContaining({ directory: '' }),
      ),
    );
  });

  it.each(['prefix', 'exact'])('preserves literal path whitespace for %s search', async (mode) => {
    renderPage(`/content?repoId=1&searchMode=${mode}`);
    await screen.findByRole('button', { name: 'Open guide.mdx' });
    const path = ' content/100%_real/guide.mdx ';
    fireEvent.change(screen.getByRole('searchbox', { name: 'Search content paths' }), {
      target: { value: path },
    });
    await userEvent.type(
      screen.getByRole('searchbox', { name: 'Search content paths' }),
      '{Enter}',
    );
    await waitFor(() =>
      expect(fetchContentAssets).toHaveBeenLastCalledWith(
        1,
        expect.objectContaining({ query: path, searchMode: mode }),
      ),
    );
    expect(screen.getByRole('searchbox', { name: 'Search content paths' })).toHaveValue(path);
  });

  it('allows exact lookup of a full 255-character asset path', async () => {
    const path = `content/${'a'.repeat(243)}.mdx`;
    expect(path).toHaveLength(255);
    renderPage('/content?repoId=1&searchMode=exact');
    await screen.findByRole('button', { name: 'Open guide.mdx' });
    const search = screen.getByRole('searchbox', { name: 'Search content paths' });
    expect(search).toHaveAttribute('maxLength', '255');
    fireEvent.change(search, { target: { value: path } });
    await userEvent.type(
      screen.getByRole('searchbox', { name: 'Search content paths' }),
      '{Enter}',
    );
    await waitFor(() =>
      expect(fetchContentAssets).toHaveBeenLastCalledWith(
        1,
        expect.objectContaining({ query: path, searchMode: 'exact' }),
      ),
    );
  });

  it('keeps directory scope and legacy search mode while clearing asset page positions', async () => {
    renderPage(
      '/content?repoId=1&branchId=5&directory=content%2F&q=guide&after=next&before=previous&offset=100&folderAfter=folders',
    );
    await screen.findByRole('button', { name: 'Open guide.mdx' });
    expect(screen.queryByRole('button', { name: 'Path search mode' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Search' })).not.toBeInTheDocument();
    fireEvent.change(screen.getByRole('searchbox', { name: 'Search content paths' }), {
      target: { value: ' updated ' },
    });
    await waitFor(() =>
      expect(fetchContentAssets).toHaveBeenLastCalledWith(
        1,
        expect.objectContaining({
          query: 'updated',
          searchMode: 'contains',
          directory: 'content/',
          after: null,
          before: null,
          offset: 0,
        }),
      ),
    );
    expect(screen.getByTestId('location-url')).toHaveTextContent('folderAfter=folders');
    expect(screen.getByTestId('location-url')).not.toHaveTextContent('offset=');
    expect(screen.getByTestId('location-url')).not.toHaveTextContent('before=');
    expect(screen.getByTestId('location-url')).not.toHaveTextContent('after=');
  });

  it('clears directory and cursor scopes when switching branch or repository', async () => {
    renderPage('/content?repoId=1&branchId=5&directory=content%2F&after=next&folderAfter=folders');
    await screen.findByRole('button', { name: 'Open guide.mdx' });
    await userEvent.click(screen.getByRole('button', { name: 'Content branch' }));
    await userEvent.click(screen.getByRole('button', { name: 'release' }));
    await waitFor(() =>
      expect(fetchContentDirectories).toHaveBeenLastCalledWith(1, {
        branchId: 6,
        directory: '',
        after: null,
      }),
    );
    expect(fetchContentAssets).toHaveBeenLastCalledWith(
      1,
      expect.objectContaining({ branchId: 6, directory: '', after: null }),
    );
    await userEvent.click(screen.getByRole('button', { name: 'Select content repository' }));
    await userEvent.click(screen.getByRole('radio', { name: /^Help center/ }));
    await waitFor(() =>
      expect(fetchContentDirectories).toHaveBeenLastCalledWith(2, {
        branchId: null,
        directory: '',
        after: null,
      }),
    );
    expect(screen.getByTestId('location-url')).toHaveTextContent('/content?repoId=2');
  });

  it.each(['error', 'empty'])(
    'recovers the first page after a cursor %s without retaining previous rows',
    async (result) => {
      vi.mocked(fetchContentAssets).mockImplementation((_repositoryId, options) =>
        options.after
          ? result === 'error'
            ? Promise.reject(new Error('Invalid cursor'))
            : Promise.resolve(assetPage([]))
          : Promise.resolve(assetPage([asset], { nextCursor: 'next' })),
      );
      renderPage('/content?repoId=1&q=guide');
      await screen.findByRole('button', { name: 'Open guide.mdx' });
      await userEvent.click(screen.getByRole('button', { name: 'Next' }));
      await screen.findByText(
        result === 'error'
          ? 'Could not load pages and modules.'
          : 'No assets on this page. Return to the first page or search again.',
      );
      expect(screen.queryByRole('button', { name: 'Open guide.mdx' })).not.toBeInTheDocument();
      await userEvent.click(screen.getByRole('button', { name: 'First page' }));
      expect(await screen.findByRole('button', { name: 'Open guide.mdx' })).toBeVisible();
      expect(screen.getByTestId('location-url')).not.toHaveTextContent('after=');
    },
  );

  it('recovers folder cursor failures without stale folder choices', async () => {
    vi.mocked(fetchContentDirectories).mockImplementation((repositoryId, options) =>
      options.after
        ? Promise.reject(new Error('Invalid directory cursor'))
        : Promise.resolve({
            repositoryId,
            branchId: 5,
            directory: '',
            directories: ['content/'],
            nextCursor: 'next',
          }),
    );
    renderPage('/content?repoId=1');
    await screen.findByRole('button', { name: 'Browse content/' });
    await userEvent.click(screen.getByRole('button', { name: 'Next folders' }));
    await screen.findByText('Could not load directories.');
    expect(screen.queryByRole('button', { name: 'Browse content/' })).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'First folders' }));
    expect(await screen.findByRole('button', { name: 'Browse content/' })).toBeVisible();
  });

  it('moves from a legacy offset link to a server cursor on its next page', async () => {
    vi.mocked(fetchContentAssets).mockResolvedValue(
      assetPage([asset], { offset: 100, nextCursor: 'next', previousCursor: 'previous' }),
    );
    renderPage('/content?repoId=1&q=guide&offset=100');
    await screen.findByRole('button', { name: 'Open guide.mdx' });
    expect(fetchContentAssets).toHaveBeenCalledWith(
      1,
      expect.objectContaining({ query: 'guide', searchMode: 'contains', offset: 100 }),
    );
    await userEvent.click(screen.getByRole('button', { name: 'Next' }));
    await waitFor(() =>
      expect(fetchContentAssets).toHaveBeenLastCalledWith(
        1,
        expect.objectContaining({ offset: 0, after: 'next', before: null }),
      ),
    );
    expect(screen.getByTestId('location-url')).not.toHaveTextContent('offset=');
  });

  it('opens an anchored editor on a single click without shrinking or leaving the preview', async () => {
    const from = '/content?repoId=1&branchId=6&locale=fr-FR&q=guide&assetId=11';
    renderPage('/content?repoId=1&branchId=6&locale=fr-FR&q=guide');
    await userEvent.click(await screen.findByRole('button', { name: 'Open guide.mdx' }));
    expect(await screen.findByRole('heading', { name: 'Un guide pratique' })).toBeVisible();
    expect(fetchContentAssets).toHaveBeenCalledWith(
      1,
      expect.objectContaining({ branchId: 6, query: 'guide', offset: 0, searchMode: 'contains' }),
    );
    expect(fetchContentPreview).toHaveBeenCalledWith(1, 11, 6, 'fr-FR');
    await userEvent.click(screen.getByRole('heading', { name: 'Un guide pratique' }));

    expect(await screen.findByRole('dialog', { name: 'Translation editor' })).toBeVisible();
    const shortcuts = screen.getByRole('region', { name: 'Keyboard shortcuts' });
    expect(shortcuts).toHaveTextContent('Cmd/Ctrl Shift Enter');
    expect(shortcuts).toHaveTextContent('Save & next');
    expect(screen.getByRole('dialog', { name: 'Translation editor' })).not.toContainElement(
      shortcuts,
    );
    expect(screen.getByText('Editing 901 in fr-FR')).toBeVisible();
    expect(screen.getByTestId('location-url')).toHaveTextContent(from);
    expect(screen.getByRole('heading', { name: 'Un guide pratique' })).toBeVisible();
    expect(screen.getByRole('button', { name: 'Edit guide.title' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    expect(document.querySelector('.content-page__body')).not.toHaveClass(
      'content-page__body--editing',
    );
    expect(screen.getByRole('button', { name: 'Open side panel' })).toBeVisible();
    expect(screen.queryByRole('button', { name: 'Next passage' })).not.toBeInTheDocument();
    const editor = screen.getByTestId('embedded-editor');
    const field = screen.getByRole('textbox', { name: 'Draft translation' });
    await userEvent.type(field, 'Keep this draft');
    await userEvent.click(screen.getByRole('button', { name: 'Open side panel' }));
    expect(screen.queryByRole('dialog', { name: 'Translation editor' })).not.toBeInTheDocument();
    expect(screen.getByRole('region', { name: 'Translation editor' })).toContainElement(editor);
    expect(screen.getByRole('separator', { name: 'Resize document preview' })).toBeVisible();
    expect(screen.getByTestId('embedded-editor')).toBe(editor);
    expect(screen.getAllByTestId('embedded-editor')).toHaveLength(1);
    expect(screen.getByRole('textbox', { name: 'Draft translation' })).toBe(field);
    expect(field).toHaveValue('Keep this draft');
    expect(screen.getByTestId('location-url')).toHaveTextContent(from);
    await userEvent.click(screen.getByRole('button', { name: 'Edit inline' }));
    expect(screen.getByRole('dialog', { name: 'Translation editor' })).toContainElement(editor);
    expect(screen.getByTestId('embedded-editor')).toBe(editor);
    expect(screen.getAllByTestId('embedded-editor')).toHaveLength(1);
    expect(screen.getByRole('textbox', { name: 'Draft translation' })).toBe(field);
    expect(field).toHaveValue('Keep this draft');
    await userEvent.click(screen.getByRole('button', { name: 'Close editor' }));
    expect(screen.queryByRole('dialog', { name: 'Translation editor' })).not.toBeInTheDocument();
    expect(screen.queryByRole('region', { name: 'Keyboard shortcuts' })).not.toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Un guide pratique' })).toBeVisible();
    expect(screen.getByTestId('location-url')).toHaveTextContent(from);
    expect(screen.getByRole('button', { name: 'Edit guide.title' })).toHaveFocus();
  });

  it.each(['inline', 'side panel'])(
    'preserves the mounted draft and %s while filtering the explorer',
    async (placement) => {
      renderPage('/content?repoId=1&branchId=5&assetId=11&locale=fr-FR');
      await userEvent.click(await screen.findByRole('button', { name: 'Edit guide.title' }));
      const editor = screen.getByTestId('embedded-editor');
      const draft = screen.getByRole('textbox', { name: 'Draft translation' });
      await userEvent.type(draft, 'Keep this draft while searching');
      if (placement === 'side panel') {
        await userEvent.click(screen.getByRole('button', { name: 'Open side panel' }));
      }

      fireEvent.change(screen.getByRole('searchbox', { name: 'Search content paths' }), {
        target: { value: 'different-path' },
      });
      await waitFor(() =>
        expect(fetchContentAssets).toHaveBeenLastCalledWith(
          1,
          expect.objectContaining({ query: 'different-path', searchMode: 'prefix' }),
        ),
      );

      expect(screen.getByTestId('embedded-editor')).toBe(editor);
      expect(screen.getByRole('textbox', { name: 'Draft translation' })).toBe(draft);
      expect(draft).toHaveValue('Keep this draft while searching');
      expect(
        screen.getByRole(placement === 'inline' ? 'dialog' : 'region', {
          name: 'Translation editor',
        }),
      ).toContainElement(editor);
      expect(screen.getByRole('heading', { name: 'Un guide pratique' })).toBeVisible();
      expect(fetchContentPreview).toHaveBeenCalledOnce();
      expect(screen.getByTestId('location-url')).toHaveTextContent('assetId=11');
      expect(screen.getByTestId('location-url')).toHaveTextContent('locale=fr-FR');
    },
  );

  it('closes only the editor and keeps the preview ready for another passage', async () => {
    renderPage('/content?repoId=1&branchId=5&assetId=11&locale=fr-FR');
    const passage = await screen.findByRole('button', { name: 'Edit guide.title' });
    await userEvent.click(passage);
    expect(await screen.findByRole('dialog', { name: 'Translation editor' })).toBeVisible();
    await userEvent.click(screen.getByRole('button', { name: 'Open side panel' }));
    expect(screen.getByRole('region', { name: 'Translation editor' })).toBeVisible();
    await userEvent.click(screen.getByRole('button', { name: 'Close editor' }));
    expect(screen.queryByTestId('embedded-editor')).not.toBeInTheDocument();
    expect(passage).toBeVisible();
    expect(passage).toHaveFocus();
    await userEvent.click(passage);
    expect(screen.getByRole('dialog', { name: 'Translation editor' })).toBeVisible();
  });

  it('collapses the explorer for docked editing on narrow screens and allows reopening it', async () => {
    const matchMedia = vi.fn((query: string) => ({
      media: query,
      matches: query === '(max-width: 80rem)',
      onchange: null,
      addListener: vi.fn(),
      removeListener: vi.fn(),
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      dispatchEvent: vi.fn(),
    }));
    vi.stubGlobal('matchMedia', matchMedia);
    renderPage('/content?repoId=1&branchId=5&assetId=11&locale=fr-FR');
    const passage = await screen.findByRole('button', { name: 'Edit guide.title' });
    const explorer = screen.getByRole('complementary', { name: 'File explorer' });
    await userEvent.click(passage);
    expect(explorer).not.toHaveAttribute('hidden');
    await userEvent.click(screen.getByRole('button', { name: 'Open side panel' }));
    expect(matchMedia).toHaveBeenCalledWith('(max-width: 80rem)');
    expect(explorer).toHaveAttribute('hidden');
    expect(screen.getByRole('region', { name: 'Content preview' })).toBeVisible();
    expect(screen.getByRole('region', { name: 'Translation editor' })).toBeVisible();
    await userEvent.click(screen.getByRole('button', { name: 'Expand file explorer' }));
    expect(explorer).not.toHaveAttribute('hidden');
    expect(screen.getByRole('button', { name: 'Open guide.mdx' })).toBeVisible();
    await userEvent.click(screen.getByRole('button', { name: 'Edit inline' }));
    expect(explorer).not.toHaveAttribute('hidden');
    await userEvent.click(screen.getByRole('button', { name: 'Open side panel' }));
    expect(explorer).not.toHaveAttribute('hidden');
  });

  it('advances from the clicked occurrence to the next visible different string', async () => {
    const initial = preview();
    initial.document!.blocks.push(
      { ...initial.document!.blocks[0], occurrenceId: 'repeat' },
      {
        ...initial.document!.blocks[0],
        id: 'guide.hidden',
        tmTextUnitId: 903,
        occurrenceId: 'hidden',
        targetContent: 'Hidden passage',
      },
      {
        ...initial.document!.blocks[0],
        id: 'guide.next',
        tmTextUnitId: 904,
        occurrenceId: 'next',
        targetContent: 'Next passage text',
      },
    );
    vi.mocked(fetchContentPreview).mockResolvedValue(initial);
    renderPage('/content?repoId=1&branchId=5&assetId=11&locale=fr-FR');
    await screen.findByRole('heading', { name: 'Hidden passage' });
    screen.getByRole('button', { name: 'Edit guide.hidden' }).hidden = true;
    const passages = screen.getAllByRole('button', { name: 'Edit guide.title' });
    await userEvent.click(passages[1]);
    expect(passages[0]).toHaveAttribute('aria-pressed', 'false');
    expect(passages[1]).toHaveAttribute('aria-pressed', 'true');
    await userEvent.click(screen.getByRole('button', { name: 'Open side panel' }));
    await userEvent.click(screen.getByRole('button', { name: 'Next passage' }));
    expect(screen.getByRole('button', { name: 'Edit inline' })).toBeVisible();
    expect(screen.queryByRole('dialog', { name: 'Translation editor' })).not.toBeInTheDocument();
    expect(screen.getByText('Editing 904 in fr-FR')).toBeVisible();
    expect(screen.getByRole('button', { name: 'Edit guide.next' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    expect(screen.queryByRole('button', { name: 'Next passage' })).not.toBeInTheDocument();
    await userEvent.click(passages[0]);
    expect(screen.getByText('Editing 901 in fr-FR')).toBeVisible();
    expect(screen.getByRole('button', { name: 'Edit inline' })).toBeVisible();
    expect(screen.queryByRole('dialog', { name: 'Translation editor' })).not.toBeInTheDocument();
  });

  it('refreshes every occurrence of a shared passage after an embedded save', async () => {
    const initial = preview();
    initial.document!.blocks.push({ ...initial.document!.blocks[0], occurrenceId: 'second' });
    vi.mocked(fetchContentPreview).mockResolvedValue(initial);
    renderPage('/content?repoId=1&branchId=5&assetId=11&locale=fr-FR');
    const passages = await screen.findAllByRole('button', { name: 'Edit guide.title' });
    await userEvent.click(passages[0]);
    const updated = {
      ...initial,
      document: {
        ...initial.document!,
        blocks: initial.document!.blocks.map((block) => ({
          ...block,
          targetContent: 'Le guide actualisé',
        })),
      },
    };
    vi.mocked(fetchContentPreview).mockResolvedValue(updated);
    await userEvent.click(screen.getByRole('button', { name: 'Save embedded translation' }));
    expect(await screen.findAllByRole('heading', { name: 'Le guide actualisé' })).toHaveLength(2);
    expect(screen.getByRole('dialog', { name: 'Translation editor' })).toBeVisible();
  });

  it('reuses the asset list while switching files and refreshes on returning to the tab', async () => {
    const second = { ...asset, assetId: 12, assetPath: 'guide-next.mdx' };
    vi.mocked(fetchContentAssets).mockResolvedValue(assetPage([asset, second]));
    renderPage('/content?repoId=1&branchId=5&locale=fr-FR');
    await userEvent.click(await screen.findByRole('button', { name: 'Open guide.mdx' }));
    await screen.findByRole('heading', { name: 'Un guide pratique' });
    await userEvent.click(screen.getByRole('button', { name: 'Open guide-next.mdx' }));
    await screen.findByRole('heading', { name: 'Un guide pratique' });
    await userEvent.click(screen.getByRole('button', { name: 'Open guide.mdx' }));
    await screen.findByRole('heading', { name: 'Un guide pratique' });
    expect(fetchContentAssets).toHaveBeenCalledOnce();
    expect(fetchContentDirectories).toHaveBeenCalledOnce();
    expect(screen.queryByRole('button', { name: /^Refresh/ })).not.toBeInTheDocument();
    const previewRequests = vi.mocked(fetchContentPreview).mock.calls.length;
    const added = { ...asset, assetId: 13, assetPath: 'new-guide.mdx' };
    vi.mocked(fetchContentAssets).mockResolvedValue(assetPage([asset, second, added]));
    vi.mocked(fetchContentDirectories).mockResolvedValue({
      repositoryId: 1,
      branchId: 5,
      directory: '',
      directories: ['new/'],
      nextCursor: null,
    });
    const updated = preview();
    updated.document!.blocks[0].targetContent = 'Le guide actualisé';
    vi.mocked(fetchContentPreview).mockResolvedValue(updated);
    act(() => focusManager.setFocused(false));
    act(() => focusManager.setFocused(true));
    await waitFor(() => expect(fetchContentAssets).toHaveBeenCalledTimes(2));
    expect(await screen.findByRole('button', { name: 'Open new-guide.mdx' })).toBeVisible();
    expect(await screen.findByRole('button', { name: 'Browse new/' })).toBeVisible();
    expect(await screen.findByRole('heading', { name: 'Le guide actualisé' })).toBeVisible();
    expect(fetchContentDirectories).toHaveBeenCalledTimes(2);
    expect(fetchContentPreview).toHaveBeenCalledTimes(previewRequests + 1);
    expect(screen.getByRole('region', { name: 'Content preview' })).toBeVisible();
  });

  it('closes an anchored editor when refreshed document content removes its passage', async () => {
    renderPage('/content?repoId=1&branchId=5&assetId=11&locale=fr-FR');
    await userEvent.click(await screen.findByRole('button', { name: 'Edit guide.title' }));
    expect(screen.getByRole('dialog', { name: 'Translation editor' })).toBeVisible();
    const updated = preview();
    updated.document!.blocks = [];
    vi.mocked(fetchContentPreview).mockResolvedValue(updated);
    await userEvent.click(screen.getByRole('button', { name: 'Save embedded translation' }));
    await waitFor(() =>
      expect(screen.queryByRole('dialog', { name: 'Translation editor' })).not.toBeInTheDocument(),
    );
  });

  it('does not request a preview for an unavailable repository', async () => {
    renderPage('/content?repoId=999&assetId=11&locale=fr-FR');
    expect(
      await screen.findByText('This repository is unavailable. Select another repository.'),
    ).toBeVisible();
    expect(fetchContentAssets).not.toHaveBeenCalled();
    expect(fetchContentPreview).not.toHaveBeenCalled();
  });

  it('does not request or refresh a preview for a locale outside the repository', async () => {
    renderPage('/content?repoId=1&assetId=11&locale=ja');
    expect(
      await screen.findByText('Select a language configured for this repository.'),
    ).toBeVisible();
    expect(screen.queryByRole('list', { name: 'Matching files' })).not.toBeInTheDocument();
    const assetRequests = vi.mocked(fetchContentAssets).mock.calls.length;
    act(() => focusManager.setFocused(false));
    act(() => focusManager.setFocused(true));
    await waitFor(() => expect(fetchContentAssets).toHaveBeenCalledTimes(assetRequests + 1));
    expect(fetchContentPreview).not.toHaveBeenCalled();
  });

  it('clears stale asset, branch, locale and search when selecting another repository', async () => {
    renderPage('/content?repoId=1&branchId=6&locale=fr-FR&assetId=11&q=guide&offset=100');
    await screen.findByRole('heading', { name: 'Un guide pratique' });
    await userEvent.click(screen.getByRole('button', { name: 'Select content repository' }));
    await userEvent.click(screen.getByRole('radio', { name: /^Help center/ }));

    await waitFor(() =>
      expect(fetchContentAssets).toHaveBeenLastCalledWith(
        2,
        expect.objectContaining({ branchId: null, query: '', offset: 0, searchMode: 'prefix' }),
      ),
    );
    expect(screen.getByTestId('location-url')).toHaveTextContent('/content?repoId=2');
    expect(screen.getByRole('button', { name: 'Content branch' })).toHaveTextContent('master');
    expect(screen.getByRole('button', { name: 'Content language' })).toHaveTextContent(
      'Spanish · Source',
    );
    expect(screen.getByRole('searchbox', { name: 'Search content paths' })).toHaveValue('');
    expect(screen.queryByRole('heading', { name: 'Un guide pratique' })).not.toBeInTheDocument();
    expect(fetchContentPreview).toHaveBeenCalledOnce();
  });

  it('previews source content as read-only and requests a new locale when switched', async () => {
    renderPage('/content?repoId=1&branchId=6&assetId=11&locale=en');
    expect(await screen.findByRole('heading', { name: 'A practical guide' })).toBeVisible();
    expect(fetchContentPreview).toHaveBeenCalledWith(1, 11, 6, 'en');
    expect(screen.queryByRole('button', { name: 'Edit guide.title' })).not.toBeInTheDocument();
    expect(screen.queryByText('Source fallback — untranslated')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Edit translations' })).not.toBeInTheDocument();
    expect(screen.getByText('Source · read only')).toBeVisible();
    await userEvent.click(screen.getByRole('button', { name: 'Preview help' }));
    expect(screen.getByRole('region', { name: 'Preview help' })).toHaveTextContent(
      'Edit source content in Git.',
    );
    expect(screen.getByRole('region', { name: 'Preview help' })).not.toHaveTextContent(
      'Edit passage',
    );
    await userEvent.dblClick(screen.getByRole('heading', { name: 'A practical guide' }));
    expect(screen.queryByRole('dialog', { name: 'Translation editor' })).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Content language' }));
    await userEvent.type(screen.getByPlaceholderText('Filter locales'), 'fr-FR');
    expect(screen.queryByRole('radio', { name: /^English · Source/ })).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('radio', { name: /^French \(France\)/ }));
    expect(await screen.findByRole('heading', { name: 'Un guide pratique' })).toBeVisible();
    expect(screen.queryByText('Source · read only')).not.toBeInTheDocument();
    expect(screen.queryByRole('region', { name: 'Preview help' })).not.toBeInTheDocument();
    expect(fetchContentPreview).toHaveBeenLastCalledWith(1, 11, 6, 'fr-FR');
    expect(screen.getByRole('button', { name: 'Edit guide.title' })).toBeVisible();
  });
});
