import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { type ReactNode, useState } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import type * as ContentApi from '../../api/content';
import {
  type ApiContentAssets,
  contentAssetsQueryKey,
  fetchContentAssets,
  fetchContentDirectories,
} from '../../api/content';
import type * as FileTreeComponents from '../../components/FileTree';
import { ContentDirectoryTree } from './ContentDirectoryTree';

vi.mock('../../api/content', async (importOriginal) => ({
  ...(await importOriginal<typeof ContentApi>()),
  fetchContentDirectories: vi.fn(),
  fetchContentAssets: vi.fn(),
}));
// Query bounds are tested independently from the shared tree's viewport virtualization.
vi.mock('../../components/FileTree', async (importOriginal) => ({
  ...(await importOriginal<typeof FileTreeComponents>()),
  FileTree: ({
    items,
    ariaLabel,
    revealKey,
  }: {
    items: { key: string; depth: number; content: ReactNode }[];
    ariaLabel: string;
    revealKey?: string | null;
  }) => (
    <nav aria-label={ariaLabel} data-reveal-key={revealKey}>
      {items.map((item) => (
        <div key={item.key} data-depth={item.depth}>
          {item.content}
        </div>
      ))}
    </nav>
  ),
}));
const selectDirectory = vi.fn();
const openAsset = vi.fn();
const assetOptions = (directory = '', branchId = 5) => ({
  branchId,
  query: '',
  searchMode: 'prefix' as const,
  directory,
  recursive: false,
  after: null,
  before: null,
  offset: 0,
});
const asset = (assetPath: string, assetId = 1, branchId = 5) => ({
  assetId,
  assetPath,
  branchId,
  branchName: 'main',
  sourceContentMd5: 'source-hash',
});
const assetPage = (assets = [asset('index.mdx')]): ApiContentAssets => ({
  repositoryId: 1,
  sourceLocaleTag: 'en',
  branchId: 5,
  branchName: 'main',
  branches: [{ id: 5, name: 'main' }],
  assets,
  offset: 0,
  hasMore: false,
  nextCursor: null,
  previousCursor: null,
  warnings: [],
});

function renderTree(
  directory = '',
  folderAfter: string | null = null,
  position: { after?: string | null; before?: string | null; offset?: number } = {},
) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  function Wrapper() {
    const [selected, setSelected] = useState(directory);
    const [after, setAfter] = useState(folderAfter);
    const [filePosition, setFilePosition] = useState(position);
    return (
      <ContentDirectoryTree
        repositoryId={1}
        branchId={5}
        directory={selected}
        folderAfter={after}
        {...filePosition}
        onAssetPage={setFilePosition}
        onOpenAsset={openAsset}
        onSelect={(path) => {
          setSelected(path);
          setAfter(null);
          setFilePosition({});
          selectDirectory(path);
        }}
        onFolderPage={setAfter}
      />
    );
  }
  const result = render(
    <QueryClientProvider client={client}>
      <Wrapper />
    </QueryClientProvider>,
  );
  return { ...result, client };
}

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(fetchContentAssets).mockImplementation((_repositoryId, options) =>
    Promise.resolve(assetPage([asset(`${options.directory ?? ''}index.mdx`)])),
  );
  vi.mocked(fetchContentDirectories).mockImplementation((repositoryId, options) =>
    Promise.resolve({
      repositoryId,
      branchId: options.branchId,
      directory: options.directory,
      directories:
        options.directory === ''
          ? ['content/', 'other/']
          : options.directory === 'content/'
            ? ['content/guides/']
            : [],
      nextCursor: null,
    }),
  );
});

describe('ContentDirectoryTree', () => {
  it('bounds requests and rendered rows for a logical repository with 100,000 files', async () => {
    const user = userEvent.setup();
    const totalFiles = 100_000;
    const folderCount = 50;
    const pageSize = 100;
    let generatedFiles = 0;
    // Half the files are at root; each folder holds 500 files plus 500 in details/.
    // Only the requested page is materialized, so this checks UI bounds, not database throughput.
    vi.mocked(fetchContentDirectories).mockImplementation((repositoryId, options) =>
      Promise.resolve({
        repositoryId,
        branchId: options.branchId,
        directory: options.directory,
        directories: !options.directory
          ? Array.from({ length: folderCount }, (_, index) => `folder-${index}/`)
          : options.directory.endsWith('details/')
            ? []
            : [`${options.directory}details/`],
        nextCursor: null,
      }),
    );
    vi.mocked(fetchContentAssets).mockImplementation((_repositoryId, options) => {
      const count = options.directory ? totalFiles / 2 / folderCount / 2 : totalFiles / 2;
      const start = Number(options.after ?? 0);
      const files = Array.from({ length: Math.min(pageSize, count - start) }, (_, index) =>
        asset(`${options.directory}file-${start + index}.mdx`, start + index + 1),
      );
      generatedFiles += files.length;
      return Promise.resolve({
        ...assetPage(files),
        hasMore: start + pageSize < count,
        nextCursor: start + pageSize < count ? String(start + pageSize) : null,
        previousCursor: start ? String(Math.max(0, start - pageSize)) : null,
      });
    });
    renderTree();
    await screen.findByRole('button', { name: 'Open file-99.mdx' });
    expect(fetchContentDirectories).toHaveBeenCalledExactlyOnceWith(1, {
      branchId: 5,
      directory: '',
      after: null,
    });
    expect(fetchContentAssets).toHaveBeenCalledExactlyOnceWith(1, assetOptions());
    expect(screen.getAllByRole('button', { name: /^Browse folder-/, hidden: true })).toHaveLength(
      folderCount,
    );
    expect(screen.getAllByRole('button', { name: /^Open /, hidden: true })).toHaveLength(pageSize);
    expect(generatedFiles).toBe(pageSize);

    await user.click(
      within(screen.getByRole('navigation', { name: 'Files in /' })).getByRole('button', {
        name: 'Next files',
      }),
    );
    await screen.findByRole('button', { name: 'Open file-199.mdx' });
    expect(screen.queryByRole('button', { name: 'Open file-0.mdx' })).not.toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: /^Open /, hidden: true })).toHaveLength(pageSize);
    expect(fetchContentDirectories).toHaveBeenCalledTimes(1);
    expect(fetchContentAssets).toHaveBeenCalledTimes(2);
    expect(fetchContentAssets).toHaveBeenLastCalledWith(1, { ...assetOptions(), after: '100' });

    await user.click(screen.getByRole('button', { name: 'Expand folder-0/' }));
    await screen.findByRole('button', { name: 'Open folder-0/file-99.mdx' });
    expect(screen.getAllByRole('button', { name: /^Open /, hidden: true })).toHaveLength(
      pageSize * 2,
    );
    expect(fetchContentAssets).toHaveBeenCalledTimes(3);
    expect(fetchContentAssets).toHaveBeenLastCalledWith(1, assetOptions('folder-0/'));
    await user.click(screen.getByRole('button', { name: 'Expand folder-0/details/' }));
    await screen.findByRole('button', { name: 'Open folder-0/details/file-99.mdx' });
    expect(screen.getAllByRole('button', { name: /^Open /, hidden: true })).toHaveLength(
      pageSize * 3,
    );
    expect(fetchContentAssets).toHaveBeenCalledTimes(4);
    expect(fetchContentAssets).toHaveBeenLastCalledWith(1, assetOptions('folder-0/details/'));

    await user.click(screen.getByRole('button', { name: 'Expand folder-1/' }));
    await screen.findByRole('button', { name: 'Open folder-1/file-99.mdx' });
    expect(
      screen.queryByRole('button', { name: 'Open folder-0/file-0.mdx' }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole('button', { name: 'Open folder-0/details/file-0.mdx' }),
    ).not.toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: /^Open /, hidden: true })).toHaveLength(
      pageSize * 2,
    );
    expect(fetchContentDirectories).toHaveBeenCalledTimes(4);
    expect(fetchContentAssets).toHaveBeenCalledTimes(5);
    expect(fetchContentAssets).toHaveBeenLastCalledWith(1, assetOptions('folder-1/'));
    expect(generatedFiles).toBe(pageSize * 5);
  });

  it('loads files only in expanded folders and opens their immediate children', async () => {
    const user = userEvent.setup();
    vi.mocked(fetchContentAssets).mockImplementation((_repositoryId, options) =>
      Promise.resolve(
        assetPage(
          options.directory
            ? [asset('content/intro.mdx'), asset('content/guides/deep.mdx', 2)]
            : [asset('index.mdx'), asset('content/intro.mdx', 2)],
        ),
      ),
    );
    renderTree();
    const rootFile = await screen.findByRole('button', { name: 'Open index.mdx' });
    expect(rootFile).toHaveTextContent('index.mdx');
    expect(rootFile).toHaveAttribute('title', 'index.mdx');
    expect(screen.getByRole('button', { name: 'Browse root directory' })).toHaveTextContent('/');
    expect(
      screen.queryByRole('button', { name: 'Open content/intro.mdx' }),
    ).not.toBeInTheDocument();
    expect(fetchContentAssets).toHaveBeenCalledExactlyOnceWith(1, assetOptions());
    expect(screen.queryByRole('navigation', { name: 'Files in /' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Expand content/' }));
    const child = await screen.findByRole('button', { name: 'Open content/intro.mdx' });
    expect(child).toHaveTextContent('intro.mdx');
    expect(fetchContentAssets).toHaveBeenLastCalledWith(1, assetOptions('content/'));
    expect(
      screen.queryByRole('button', { name: 'Open content/guides/deep.mdx' }),
    ).not.toBeInTheDocument();
    await user.click(child);
    expect(openAsset).toHaveBeenCalledExactlyOnceWith(asset('content/intro.mdx'), {
      directory: 'content/',
      after: null,
      before: null,
      offset: 0,
      folderAfter: null,
    });
    expect(selectDirectory).not.toHaveBeenCalled();
    await user.click(screen.getByRole('button', { name: 'Collapse content/' }));
    expect(
      screen.queryByRole('button', { name: 'Open content/intro.mdx' }),
    ).not.toBeInTheDocument();
    expect(rootFile).toBeVisible();
  });

  it('paginates files within each folder using bounded cursor requests', async () => {
    const user = userEvent.setup();
    vi.mocked(fetchContentAssets).mockImplementation((_repositoryId, options) => {
      const last = Boolean(options.after);
      return Promise.resolve({
        ...assetPage([asset(`${options.directory}${last ? 'last' : 'first'}.mdx`)]),
        nextCursor: last ? null : `${options.directory}next`,
        previousCursor: last ? `${options.directory}previous` : null,
      });
    });
    renderTree();
    await user.click(await screen.findByRole('button', { name: 'Expand content/' }));
    await screen.findByRole('button', { name: 'Open content/first.mdx' });
    const nestedPager = within(screen.getByRole('navigation', { name: 'Files in content/' }));
    await user.click(nestedPager.getByRole('button', { name: 'Next files' }));
    expect(await screen.findByRole('button', { name: 'Open content/last.mdx' })).toBeVisible();
    expect(
      screen.queryByRole('button', { name: 'Open content/first.mdx' }),
    ).not.toBeInTheDocument();
    expect(fetchContentAssets).toHaveBeenLastCalledWith(1, {
      ...assetOptions('content/'),
      after: 'content/next',
    });
    expect(screen.getByRole('button', { name: 'Open first.mdx' })).toBeVisible();
    await user.click(nestedPager.getByRole('button', { name: 'Previous files' }));
    expect(await screen.findByRole('button', { name: 'Open content/first.mdx' })).toBeVisible();
    expect(fetchContentAssets).toHaveBeenLastCalledWith(1, {
      ...assetOptions('content/'),
      before: 'content/previous',
    });
    await user.click(nestedPager.getByRole('button', { name: 'Next files' }));
    await screen.findByRole('button', { name: 'Open content/last.mdx' });
    const rootPager = within(screen.getByRole('navigation', { name: 'Files in /' }));
    await user.click(rootPager.getByRole('button', { name: 'Next files' }));
    expect(await screen.findByRole('button', { name: 'Open last.mdx' })).toBeVisible();
    expect(screen.getByRole('button', { name: 'Open content/last.mdx' })).toBeVisible();
    expect(fetchContentAssets).toHaveBeenLastCalledWith(1, {
      ...assetOptions(),
      after: 'next',
    });
    await user.click(rootPager.getByRole('button', { name: 'First files' }));
    expect(await screen.findByRole('button', { name: 'Open first.mdx' })).toBeVisible();
    expect(screen.getByRole('button', { name: 'Open content/last.mdx' })).toBeVisible();
  });

  it('preserves the expanded folder and both local cursors when opening and returning to a file', async () => {
    const user = userEvent.setup();
    vi.mocked(fetchContentDirectories).mockImplementation((repositoryId, options) =>
      Promise.resolve({
        repositoryId,
        branchId: options.branchId,
        directory: options.directory,
        directories: options.directory ? [] : ['content/'],
        nextCursor: options.directory && !options.after ? 'next-folders' : null,
      }),
    );
    vi.mocked(fetchContentAssets).mockImplementation((_repositoryId, options) =>
      Promise.resolve({
        ...assetPage([asset(`${options.directory}${options.after ? 'last' : 'first'}.mdx`)]),
        nextCursor: options.after ? null : 'next-files',
        previousCursor: options.after ? 'previous-files' : null,
      }),
    );
    const { unmount } = renderTree();
    await user.click(await screen.findByRole('button', { name: 'Expand content/' }));
    await screen.findByRole('button', { name: 'Open content/first.mdx' });
    await user.click(
      within(screen.getByRole('navigation', { name: 'Directory pages in content/' })).getByRole(
        'button',
        { name: 'Next folders' },
      ),
    );
    await user.click(
      within(screen.getByRole('navigation', { name: 'Files in content/' })).getByRole('button', {
        name: 'Next files',
      }),
    );
    await user.click(await screen.findByRole('button', { name: 'Open content/last.mdx' }));
    expect(selectDirectory).not.toHaveBeenCalled();
    const location = {
      directory: 'content/',
      after: 'next-files',
      before: null,
      offset: 0,
      folderAfter: 'next-folders',
    };
    expect(openAsset).toHaveBeenCalledExactlyOnceWith(asset('content/last.mdx'), location);
    unmount();
    vi.mocked(fetchContentDirectories).mockClear();
    vi.mocked(fetchContentAssets).mockClear();
    renderTree(location.directory, location.folderAfter, location);
    expect(await screen.findByRole('button', { name: 'Open content/last.mdx' })).toBeVisible();
    expect(screen.getByRole('button', { name: 'Browse content/' })).toHaveAttribute(
      'aria-current',
      'location',
    );
    expect(fetchContentDirectories).toHaveBeenCalledWith(1, {
      branchId: 5,
      directory: 'content/',
      after: 'next-folders',
    });
    expect(fetchContentAssets).toHaveBeenCalledWith(1, {
      ...assetOptions('content/'),
      after: 'next-files',
    });
  });

  it('restores file pagination only in the selected directory', async () => {
    renderTree('content/', null, { after: 'selected-files' });
    await screen.findByRole('button', { name: 'Open content/index.mdx' });
    expect(fetchContentAssets).toHaveBeenCalledWith(1, assetOptions());
    expect(fetchContentAssets).toHaveBeenCalledWith(1, {
      ...assetOptions('content/'),
      after: 'selected-files',
    });
    expect(screen.getByRole('navigation', { name: 'Files in content/' })).toBeVisible();
    expect(screen.queryByRole('navigation', { name: 'Files in /' })).not.toBeInTheDocument();
  });

  it('removes stale files after a failed refresh and retries only that folder', async () => {
    const user = userEvent.setup();
    const { client } = renderTree();
    await user.click(await screen.findByRole('button', { name: 'Expand content/' }));
    await screen.findByRole('button', { name: 'Open content/index.mdx' });
    vi.mocked(fetchContentAssets).mockRejectedValueOnce(new Error('Offline'));
    await act(async () => {
      await client.invalidateQueries({
        queryKey: contentAssetsQueryKey(1, assetOptions('content/')),
        exact: true,
      });
    });
    await screen.findByRole('alert');
    expect(
      screen.queryByRole('button', { name: 'Open content/index.mdx' }),
    ).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Open index.mdx' })).toBeVisible();
    await user.click(screen.getByRole('button', { name: 'Retry files' }));
    expect(await screen.findByRole('button', { name: 'Open content/index.mdx' })).toBeVisible();
    expect(fetchContentAssets).toHaveBeenLastCalledWith(1, assetOptions('content/'));
  });

  it('keeps the selected file reveal target when another folder hides its row', async () => {
    const user = userEvent.setup();
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    vi.mocked(fetchContentAssets).mockImplementation((_repositoryId, options) =>
      Promise.resolve(
        assetPage([
          asset(`${options.directory}index.mdx`, options.directory === 'content/' ? 77 : 1),
        ]),
      ),
    );
    render(
      <QueryClientProvider client={client}>
        <ContentDirectoryTree
          repositoryId={1}
          branchId={5}
          directory="content/"
          folderAfter={null}
          selectedAssetId={77}
          onSelect={selectDirectory}
          onFolderPage={vi.fn()}
          onAssetPage={vi.fn()}
          onOpenAsset={openAsset}
        />
      </QueryClientProvider>,
    );
    await screen.findByRole('button', { name: 'Open content/index.mdx' });
    const navigator = screen.getByRole('navigation', { name: 'Content directory' });
    const selectedFileKey = navigator.getAttribute('data-reveal-key');
    expect(selectedFileKey).toBe('file:5:77:content/');
    await user.click(screen.getByRole('button', { name: 'Expand other/' }));
    await screen.findByRole('button', { name: 'Open other/index.mdx' });
    expect(
      screen.queryByRole('button', { name: 'Open content/index.mdx' }),
    ).not.toBeInTheDocument();
    expect(navigator).toHaveAttribute('data-reveal-key', selectedFileKey);
  });

  it('shares cached files with the page and scopes requests by repository and branch', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    client.setQueryData(contentAssetsQueryKey(1, assetOptions()), assetPage());
    const tree = (repositoryId: number, branchId: number) => (
      <QueryClientProvider client={client}>
        <ContentDirectoryTree
          repositoryId={repositoryId}
          branchId={branchId}
          directory=""
          folderAfter={null}
          selectedAssetId={1}
          onSelect={selectDirectory}
          onFolderPage={vi.fn()}
          onAssetPage={vi.fn()}
          onOpenAsset={openAsset}
        />
      </QueryClientProvider>
    );
    const { rerender } = render(tree(1, 5));
    expect(await screen.findByRole('button', { name: 'Open index.mdx' })).toBeVisible();
    expect(fetchContentAssets).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: 'Open index.mdx' })).toHaveAttribute(
      'aria-current',
      'page',
    );
    rerender(tree(1, 6));
    await waitFor(() =>
      expect(fetchContentAssets).toHaveBeenCalledExactlyOnceWith(1, assetOptions('', 6)),
    );
    rerender(tree(2, 6));
    await waitFor(() =>
      expect(fetchContentAssets).toHaveBeenLastCalledWith(2, assetOptions('', 6)),
    );
    expect(fetchContentAssets).toHaveBeenCalledTimes(2);
    rerender(tree(1, 5));
    expect(await screen.findByRole('button', { name: 'Open index.mdx' })).toBeVisible();
    expect(fetchContentAssets).toHaveBeenCalledTimes(2);
  });

  it('loads only the root until a native keyboard disclosure expands one branch', async () => {
    const user = userEvent.setup();
    renderTree();
    const content = await screen.findByRole('button', { name: 'Expand content/' });
    expect(fetchContentDirectories).toHaveBeenCalledExactlyOnceWith(1, {
      branchId: 5,
      directory: '',
      after: null,
    });
    content.focus();
    await user.keyboard('{Enter}');
    expect(await screen.findByRole('button', { name: 'Browse content/guides/' })).toBeVisible();
    expect(screen.getByRole('button', { name: 'Collapse content/' })).toHaveAttribute(
      'aria-expanded',
      'true',
    );
    expect(fetchContentDirectories).toHaveBeenCalledTimes(2);
    expect(selectDirectory).not.toHaveBeenCalled();
    await user.click(screen.getByRole('button', { name: 'Expand other/' }));
    await waitFor(() =>
      expect(fetchContentDirectories).toHaveBeenLastCalledWith(1, {
        branchId: 5,
        directory: 'other/',
        after: null,
      }),
    );
    expect(
      screen.queryByRole('button', { name: 'Browse content/guides/' }),
    ).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Expand content/' })).toHaveAttribute(
      'aria-expanded',
      'false',
    );
    await user.click(screen.getByRole('button', { name: 'Collapse other/' }));
    expect(screen.getByRole('button', { name: 'Expand other/' })).toHaveAttribute(
      'aria-expanded',
      'false',
    );
    expect(fetchContentDirectories).toHaveBeenCalledTimes(3);
  });

  it('reveals selected ancestors outside the first fifty siblings without loading unrelated branches', async () => {
    const siblings = Array.from({ length: 50 }, (_, index) => `folder-${index}/`);
    vi.mocked(fetchContentDirectories).mockImplementation((repositoryId, options) =>
      Promise.resolve({
        repositoryId,
        branchId: 5,
        directory: options.directory,
        directories: options.directory === '' ? siblings : [],
        nextCursor: options.directory === '' ? 'root-next' : null,
      }),
    );
    renderTree('content/100%_real/guides/');
    expect(
      await screen.findByRole('button', { name: 'Browse content/100%_real/guides/' }),
    ).toHaveAttribute('aria-current', 'location');
    await waitFor(() => expect(fetchContentDirectories).toHaveBeenCalledTimes(4));
    expect(
      vi
        .mocked(fetchContentDirectories)
        .mock.calls.map(([, options]) => options.directory)
        .sort(),
    ).toEqual(['', 'content/', 'content/100%_real/', 'content/100%_real/guides/']);
    expect(screen.getByRole('button', { name: 'Browse folder-49/' })).toBeVisible();
    expect(screen.getByRole('button', { name: 'Collapse content/' })).toHaveAttribute(
      'aria-expanded',
      'true',
    );
  });

  it('keeps root and expanded-level folder pages separate and replaces siblings', async () => {
    const user = userEvent.setup();
    vi.mocked(fetchContentDirectories).mockImplementation((repositoryId, options) =>
      Promise.resolve({
        repositoryId,
        branchId: 5,
        directory: options.directory,
        directories:
          options.directory === ''
            ? options.after
              ? ['other/']
              : ['content/']
            : options.after
              ? ['content/last/']
              : ['content/first/'],
        nextCursor: options.after ? null : options.directory ? 'content-next' : 'root-next',
      }),
    );
    renderTree();
    await user.click(await screen.findByRole('button', { name: 'Expand content/' }));
    await screen.findByRole('button', { name: 'Browse content/first/' });
    const contentPager = within(
      screen.getByRole('navigation', { name: 'Directory pages in content/' }),
    );
    await user.click(contentPager.getByRole('button', { name: 'Next folders' }));
    expect(await screen.findByRole('button', { name: 'Browse content/last/' })).toBeVisible();
    expect(screen.queryByRole('button', { name: 'Browse content/first/' })).not.toBeInTheDocument();
    expect(fetchContentDirectories).toHaveBeenLastCalledWith(1, {
      branchId: 5,
      directory: 'content/',
      after: 'content-next',
    });
    await user.click(
      within(screen.getByRole('navigation', { name: 'Directory pages in /' })).getByRole('button', {
        name: 'Next folders',
      }),
    );
    expect(await screen.findByRole('button', { name: 'Browse other/' })).toBeVisible();
    // Retain only the expanded known ancestor alongside the new root page, not its old siblings.
    expect(screen.getByRole('button', { name: 'Collapse content/' })).toBeVisible();
    expect(screen.getByRole('button', { name: 'Browse content/last/' })).toBeVisible();
  });

  it('uses the saved folder cursor only at the selected level and allows explicit selection', async () => {
    const user = userEvent.setup();
    renderTree('content/', 'saved-next');
    await screen.findByRole('button', { name: 'Browse content/guides/' });
    expect(fetchContentDirectories).toHaveBeenCalledWith(1, {
      branchId: 5,
      directory: '',
      after: null,
    });
    expect(fetchContentDirectories).toHaveBeenCalledWith(1, {
      branchId: 5,
      directory: 'content/',
      after: 'saved-next',
    });
    await user.click(screen.getByRole('button', { name: 'Browse content/guides/' }));
    expect(selectDirectory).toHaveBeenLastCalledWith('content/guides/');
    expect(screen.getByRole('button', { name: 'Browse content/guides/' })).toHaveAttribute(
      'aria-current',
      'location',
    );
    await waitFor(() =>
      expect(fetchContentDirectories).toHaveBeenCalledWith(1, {
        branchId: 5,
        directory: 'content/guides/',
        after: null,
      }),
    );
  });

  it('keeps failed requests local to their expanded level and retries without stale children', async () => {
    const user = userEvent.setup();
    vi.mocked(fetchContentDirectories).mockImplementation((repositoryId, options) =>
      options.directory
        ? Promise.reject(new Error('Offline'))
        : Promise.resolve({
            repositoryId,
            branchId: 5,
            directory: '',
            directories: ['content/'],
            nextCursor: null,
          }),
    );
    renderTree();
    await user.click(await screen.findByRole('button', { name: 'Expand content/' }));
    await screen.findByRole('alert');
    expect(screen.getByRole('button', { name: 'Browse content/' })).toBeVisible();
    expect(
      screen.queryByRole('button', { name: 'Browse content/guides/' }),
    ).not.toBeInTheDocument();
    vi.mocked(fetchContentDirectories).mockResolvedValue({
      repositoryId: 1,
      branchId: 5,
      directory: 'content/',
      directories: ['content/recovered/'],
      nextCursor: null,
    });
    await user.click(screen.getByRole('button', { name: 'Retry directories' }));
    expect(await screen.findByRole('button', { name: 'Browse content/recovered/' })).toBeVisible();
  });

  it('loads at most eight levels for the deepest valid URL and moves the window without changing selection', async () => {
    const user = userEvent.setup();
    const selected = 'a/'.repeat(127);
    vi.mocked(fetchContentDirectories).mockImplementation((repositoryId, options) =>
      Promise.resolve({
        repositoryId,
        branchId: 5,
        directory: options.directory,
        directories: [],
        nextCursor: null,
      }),
    );
    renderTree(selected, 'selected-page');
    expect(await screen.findByRole('button', { name: `Browse ${selected}` })).toHaveAttribute(
      'aria-current',
      'location',
    );
    await waitFor(() => expect(fetchContentDirectories).toHaveBeenCalledTimes(8));
    const requested = vi
      .mocked(fetchContentDirectories)
      .mock.calls.map(([, options]) => options.directory);
    expect(requested.sort()).toEqual(
      Array.from({ length: 8 }, (_, index) => 'a/'.repeat(120 + index)).sort(),
    );
    expect(fetchContentDirectories).toHaveBeenCalledWith(1, {
      branchId: 5,
      directory: selected,
      after: 'selected-page',
    });
    await user.click(screen.getByRole('button', { name: 'Parent folders' }));
    await waitFor(() => expect(fetchContentDirectories).toHaveBeenCalledTimes(9));
    expect(fetchContentDirectories).toHaveBeenLastCalledWith(1, {
      branchId: 5,
      directory: 'a/'.repeat(119),
      after: null,
    });
    await user.click(screen.getByRole('button', { name: `Continue into ${selected}` }));
    expect(screen.getByRole('button', { name: `Browse ${selected}` })).toHaveAttribute(
      'aria-current',
      'location',
    );
    expect(selectDirectory).not.toHaveBeenCalled();
    expect(fetchContentDirectories).toHaveBeenCalledTimes(9);
    expect(screen.getByRole('button', { name: 'Browse root directory' })).toBeVisible();
  });

  it('requires an explicit window shift before loading a ninth expanded level', async () => {
    const user = userEvent.setup();
    vi.mocked(fetchContentDirectories).mockImplementation((repositoryId, options) =>
      Promise.resolve({
        repositoryId,
        branchId: 5,
        directory: options.directory,
        directories: [options.directory + 'a/'],
        nextCursor: null,
      }),
    );
    renderTree();
    for (let depth = 1; depth <= 8; depth++) {
      await user.click(await screen.findByRole('button', { name: `Expand ${'a/'.repeat(depth)}` }));
    }
    expect(fetchContentDirectories).toHaveBeenCalledTimes(8);
    expect(fetchContentAssets).toHaveBeenCalledTimes(8);
    const boundary = 'a/'.repeat(8);
    await user.click(screen.getByRole('button', { name: `Continue into ${boundary}` }));
    expect(await screen.findByRole('button', { name: `Browse ${boundary}a/` })).toBeVisible();
    expect(fetchContentDirectories).toHaveBeenCalledTimes(9);
    expect(selectDirectory).not.toHaveBeenCalled();
    await user.click(screen.getByRole('button', { name: 'Browse root directory' }));
    expect(selectDirectory).toHaveBeenLastCalledWith('');
    expect(screen.getByRole('button', { name: 'Browse root directory' })).toHaveAttribute(
      'aria-current',
      'location',
    );
  });

  it('restores the selected trail when reopening the root disclosure', async () => {
    const user = userEvent.setup();
    renderTree('content/guides/');
    await screen.findByRole('button', { name: 'Browse content/guides/' });
    await waitFor(() => expect(fetchContentDirectories).toHaveBeenCalledTimes(3));
    await user.click(screen.getByRole('button', { name: 'Collapse root directory' }));
    expect(
      screen.queryByRole('button', { name: 'Browse content/guides/' }),
    ).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Expand root directory' }));
    expect(await screen.findByRole('button', { name: 'Browse content/guides/' })).toHaveAttribute(
      'aria-current',
      'location',
    );
    expect(fetchContentDirectories).toHaveBeenCalledTimes(3);
  });

  it('collapses the root without making requests for its descendants', async () => {
    const user = userEvent.setup();
    renderTree();
    await screen.findByRole('button', { name: 'Browse content/' });
    await user.click(screen.getByRole('button', { name: 'Collapse root directory' }));
    expect(screen.queryByRole('button', { name: 'Browse content/' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Expand root directory' }));
    expect(await screen.findByRole('button', { name: 'Browse content/' })).toBeVisible();
    expect(fetchContentDirectories).toHaveBeenCalledOnce();
  });
});
