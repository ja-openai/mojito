import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import type { ApiReviewProjectDocument } from '../../api/review-projects';
import type * as FileTreeModule from '../../components/FileTree';
import { buildDocumentNavigation, documentKey } from './review-project-document-navigation';
import { ReviewProjectDocumentNavigator } from './ReviewProjectDocumentNavigator';

// Navigation behavior is independent of viewport measurement; FileTree tests cover virtualization.
vi.mock('../../components/FileTree', async (importOriginal) => {
  const actual = await importOriginal<typeof FileTreeModule>();
  return {
    ...actual,
    FileTree: ({
      items,
      ariaLabel,
      revealKey,
    }: {
      items: FileTreeModule.FileTreeItem[];
      ariaLabel: string;
      revealKey?: string | null;
    }) => (
      <div role="list" aria-label={ariaLabel} data-reveal-key={revealKey}>
        {items.map((item) => (
          <div role="listitem" key={item.key} data-depth={item.depth}>
            {item.content}
          </div>
        ))}
      </div>
    ),
  };
});

function navigationEntries() {
  const document: ApiReviewProjectDocument = {
    assetId: 1,
    assetPath: 'index.mdx',
    repositoryId: 1,
    branchName: null,
    sourceContentMd5: 'source-revision',
    warnings: [],
    blocks: [
      {
        id: 'title',
        type: 'heading',
        depth: 1,
        source: 'Accueil',
        line: 1,
        translatable: true,
        reviewProjectTextUnitId: null,
        tmTextUnitId: null,
        mappingStatus: 'NOT_IN_PROJECT',
      },
    ],
  };
  const entries = buildDocumentNavigation(
    {
      documents: [
        document,
        {
          ...document,
          assetId: 2,
          assetPath: 'modules/tip.mdx',
          blocks: [{ ...document.blocks[0], source: 'Un petit rappel' }],
        },
      ],
      warnings: [],
    },
    [],
  );
  entries[0].searchText += '\ncuriosite tranquille';
  entries[1].isModule = true;
  entries[1].parentKeys = [entries[0].key];
  return entries;
}

describe('document navigator', () => {
  it('closes compact navigation after selecting a page or module', async () => {
    const onOpen = vi.fn();
    const entries = navigationEntries();
    render(
      <ReviewProjectDocumentNavigator
        entries={entries}
        activeKey={entries[0].key}
        onOpen={onOpen}
      />,
    );
    const browse = screen.getByLabelText('Browse pages and modules');
    expect(browse).toHaveAttribute('aria-expanded', 'false');
    fireEvent.click(browse);
    expect(browse).toHaveAttribute('aria-expanded', 'true');
    const search = screen.getByRole('searchbox', { name: 'Search pages and modules' });
    fireEvent.change(search, { target: { value: 'rappel' } });
    fireEvent.click(screen.getByRole('button', { name: 'Open Un petit rappel (modules/tip.mdx)' }));
    expect(onOpen).toHaveBeenCalledWith(entries[1].key);
    await waitFor(() => expect(browse).toHaveAttribute('aria-expanded', 'false'));
    fireEvent.click(browse);
    expect(screen.getByRole('searchbox')).toBe(search);
    expect(search).toHaveValue('rappel');
  });

  it('opens pages and modules with an accessible current-page indicator', () => {
    const entries = navigationEntries();
    const onOpen = vi.fn();
    render(
      <ReviewProjectDocumentNavigator
        entries={entries}
        activeKey={entries[0].key}
        onOpen={onOpen}
      />,
    );
    const tree = screen.getByRole('navigation', { name: 'Review pages and modules' });
    expect(within(tree).getByRole('button', { name: 'Review files' })).toHaveTextContent('/');
    expect(within(tree).getByRole('button', { name: 'Repository 1' })).toBeVisible();
    fireEvent.click(within(tree).getByRole('button', { name: 'Expand modules' }));
    const page = within(tree).getByRole('button', { name: 'Open Accueil (index.mdx)' });
    expect(page).toHaveAttribute('aria-current', 'page');
    expect(page).toHaveTextContent(/^index.mdx$/);
    expect(page).toHaveAttribute('title', 'index.mdx');
    fireEvent.click(
      within(tree).getByRole('button', { name: 'Open Un petit rappel (modules/tip.mdx)' }),
    );
    expect(onOpen).toHaveBeenCalledWith(entries[1].key);
  });

  it('searches saved body and paths, reports no results, and clears the search', () => {
    const entries = navigationEntries();
    render(
      <ReviewProjectDocumentNavigator
        entries={entries}
        activeKey={entries[0].key}
        onOpen={vi.fn()}
      />,
    );
    const search = screen.getByRole('searchbox', { name: 'Search pages and modules' });
    fireEvent.change(search, { target: { value: 'curiosité' } });
    expect(screen.getByRole('button', { name: 'Open Accueil (index.mdx)' })).toBeVisible();
    expect(screen.queryByRole('button', { name: /Open Un petit rappel/ })).not.toBeInTheDocument();
    fireEvent.change(search, { target: { value: 'modules/tip' } });
    expect(screen.getByRole('button', { name: /Open Un petit rappel/ })).toBeVisible();
    fireEvent.change(search, { target: { value: 'no such document' } });
    expect(screen.getByRole('status')).toHaveTextContent('No pages or modules match your search.');
    fireEvent.click(screen.getByRole('button', { name: 'Clear page search' }));
    expect(search).toHaveValue('');
    expect(screen.getByRole('button', { name: 'Open Accueil (index.mdx)' })).toBeVisible();
    fireEvent.click(screen.getByRole('button', { name: 'Expand modules' }));
    expect(screen.getByRole('button', { name: /Open Un petit rappel/ })).toBeVisible();
  });

  it('provides a route back to the page that uses the active module', () => {
    const entries = navigationEntries();
    const onOpen = vi.fn();
    render(
      <ReviewProjectDocumentNavigator
        entries={entries}
        activeKey={entries[1].key}
        onOpen={onOpen}
      />,
    );
    const parents = screen.getByRole('region', { name: 'Used in' });
    fireEvent.click(
      within(parents).getByRole('button', { name: 'Open parent Accueil (index.mdx)' }),
    );
    expect(onOpen).toHaveBeenCalledWith(entries[0].key);
  });

  it('keeps every module reachable when the graph has no root page', () => {
    const entries = navigationEntries();
    entries[0].isModule = true;
    entries[0].parentKeys = [entries[1].key];
    render(
      <ReviewProjectDocumentNavigator
        entries={entries}
        activeKey={entries[1].key}
        onOpen={vi.fn()}
      />,
    );
    const tree = screen.getByRole('navigation', { name: 'Review pages and modules' });
    expect(within(tree).getAllByRole('button', { name: /^Open / })).toHaveLength(2);
    expect(screen.getByRole('button', { name: 'Open parent Accueil (index.mdx)' })).toBeVisible();
  });

  it('disables navigation during a save without disabling search', () => {
    const entries = navigationEntries();
    const onOpen = vi.fn();
    render(
      <ReviewProjectDocumentNavigator
        entries={entries}
        activeKey={entries[1].key}
        onOpen={onOpen}
        disabled
      />,
    );
    const button = screen.getByRole('button', { name: 'Open Accueil (index.mdx)' });
    expect(button).toBeDisabled();
    fireEvent.click(button);
    expect(onOpen).not.toHaveBeenCalled();
    expect(screen.getByRole('searchbox')).not.toBeDisabled();
    expect(screen.getByRole('button', { name: 'Open parent Accueil (index.mdx)' })).toBeDisabled();
  });

  it('distinguishes a default branch from an empty branch for the same asset', () => {
    const entries = navigationEntries();
    const emptyBranch = {
      ...entries[0],
      key: documentKey({ ...entries[0].document, branchName: '' }),
      branchName: '',
      document: { ...entries[0].document, branchName: '' },
    };
    render(
      <ReviewProjectDocumentNavigator
        entries={[entries[0], emptyBranch]}
        activeKey={entries[0].key}
        onOpen={vi.fn()}
      />,
    );
    const defaultBranch = screen.getByRole('button', {
      name: 'Open Accueil (index.mdx) on Default branch',
    });
    const unnamedBranch = screen.getByRole('button', {
      name: 'Open Accueil (index.mdx) on Unnamed branch',
    });
    expect(defaultBranch).toHaveAttribute('aria-current', 'page');
    expect(screen.getByRole('button', { name: 'Default branch' })).toBeVisible();
    expect(defaultBranch).toHaveAttribute('title', 'index.mdx · Default branch');
    expect(defaultBranch).toHaveTextContent('index.mdx');
    expect(unnamedBranch).not.toHaveAttribute('aria-current');
    expect(screen.getByRole('button', { name: 'Unnamed branch' })).toBeVisible();
  });

  it('shows filenames in their folders while keeping translated titles searchable', () => {
    const entries = navigationEntries();
    const duplicate = {
      ...entries[0],
      key: 'another-home',
      assetPath: 'guide/index.mdx',
      searchText: `${entries[0].searchText} guide/index.mdx`,
      document: { ...entries[0].document, assetId: 3, assetPath: 'guide/index.mdx' },
    };
    render(
      <ReviewProjectDocumentNavigator
        entries={[...entries, duplicate]}
        activeKey={entries[0].key}
        onOpen={vi.fn()}
      />,
    );
    fireEvent.click(screen.getByRole('button', { name: 'Expand guide' }));
    const first = screen.getByRole('button', { name: 'Open Accueil (index.mdx)' });
    const second = screen.getByRole('button', { name: 'Open Accueil (guide/index.mdx)' });
    expect(first).toHaveTextContent(/^index.mdx$/);
    expect(second).toHaveTextContent(/^index.mdx$/);
    expect(second).toHaveAttribute('title', 'guide/index.mdx');
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'guide/index' } });
    expect(second).toBeVisible();
    expect(first).not.toBeInTheDocument();
  });

  it('keeps matching paths from different repositories separate', () => {
    const [first] = navigationEntries();
    first.repositoryName = 'Docs';
    first.searchText += ' docs';
    const second = {
      ...first,
      repositoryId: 2,
      repositoryName: 'Website',
      searchText: `${first.searchText} website`,
      document: { ...first.document, repositoryId: 2 },
    };
    second.key = documentKey(second.document);
    const onOpen = vi.fn();
    render(
      <ReviewProjectDocumentNavigator
        entries={[first, second]}
        activeKey={first.key}
        onOpen={onOpen}
      />,
    );
    const tree = screen.getByRole('list', { name: 'Review files tree' });
    expect(
      within(tree)
        .getAllByRole('listitem')
        .map((item) => item.dataset.depth),
    ).toEqual(['0', '1', '2', '1', '2']);
    const samePaths = screen.getAllByRole('button', { name: 'Open Accueil (index.mdx)' });
    expect(samePaths[0]).toHaveAttribute('aria-current', 'page');
    fireEvent.click(samePaths[1]);
    expect(onOpen).toHaveBeenCalledWith(second.key);
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'website' } });
    expect(screen.queryByRole('button', { name: 'Docs' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Website' })).toBeVisible();
  });

  it('reveals a newly selected file after review navigation, including a hidden search result', () => {
    const entries = navigationEntries();
    const { rerender } = render(
      <ReviewProjectDocumentNavigator
        entries={entries}
        activeKey={entries[0].key}
        onOpen={vi.fn()}
      />,
    );
    expect(screen.queryByRole('button', { name: /Open Un petit rappel/ })).not.toBeInTheDocument();
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'curiosité' } });
    fireEvent.click(screen.getByRole('button', { name: 'Collapse review files' }));
    rerender(
      <ReviewProjectDocumentNavigator
        entries={entries}
        activeKey={entries[1].key}
        onOpen={vi.fn()}
      />,
    );
    expect(screen.getByRole('searchbox')).toHaveValue('');
    expect(screen.getByRole('list', { name: 'Review files tree' })).toHaveAttribute(
      'data-reveal-key',
      entries[1].key,
    );
    expect(
      screen.getByRole('button', { name: 'Open Un petit rappel (modules/tip.mdx)' }),
    ).toHaveAttribute('aria-current', 'page');
    expect(screen.getByRole('button', { name: 'Collapse modules' })).toHaveAttribute(
      'aria-expanded',
      'true',
    );
    // Manual collapsing remains respected until the selected file changes again.
    fireEvent.click(screen.getByRole('button', { name: 'Collapse modules' }));
    rerender(
      <ReviewProjectDocumentNavigator
        entries={[...entries]}
        activeKey={entries[1].key}
        onOpen={vi.fn()}
      />,
    );
    expect(
      screen.queryByRole('button', { name: 'Open Un petit rappel (modules/tip.mdx)' }),
    ).not.toBeInTheDocument();
  });
});
