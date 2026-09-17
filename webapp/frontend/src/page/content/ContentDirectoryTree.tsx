import { useQueries } from '@tanstack/react-query';
import { useRef, useState } from 'react';

import {
  type ApiContentAsset,
  contentAssetsQueryKey,
  fetchContentAssets,
  fetchContentDirectories,
  normalizedDirectory,
} from '../../api/content';
import { FileTree, type FileTreeItem, FileTreeRow } from '../../components/FileTree';

type Props = {
  repositoryId: number;
  branchId: number | null;
  directory: string;
  folderAfter: string | null;
  onSelect: (directory: string) => void;
  onFolderPage: (cursor: string | null) => void;
  after?: string | null;
  before?: string | null;
  offset?: number;
  selectedAssetId?: number | null;
  onAssetPage: (position: AssetPagePosition) => void;
  onOpenAsset: (asset: ApiContentAsset, context: ContentAssetLocation) => void;
};

export type ContentAssetLocation = {
  directory: string;
  after: string | null;
  before: string | null;
  offset: number;
  folderAfter: string | null;
};

type AssetPagePosition = { after?: string | null; before?: string | null; offset?: number };

const FIRST_ASSET_PAGE = { after: null, before: null, offset: 0 };
const PAGE_SIZE = 100;
const MAX_OFFSET = 2_147_483_647 - PAGE_SIZE;

function immediateChild(parent: string, descendant: string | null) {
  if (!descendant || !descendant.startsWith(parent) || descendant === parent) return null;
  return parent + descendant.slice(parent.length).split('/')[0] + '/';
}

const MAX_LEVELS = 8;

function nearbyBase(path: string) {
  const parts = path.split('/').filter(Boolean);
  const count = Math.max(0, parts.length - MAX_LEVELS + 1);
  return count ? parts.slice(0, count).join('/') + '/' : '';
}

function initialView(selection: string, scope: string) {
  return {
    selection,
    scope,
    base: nearbyBase(selection),
    expandedPath: selection as string | null,
  };
}

type DirectoryPage = Omit<ContentAssetLocation, 'directory'>;

export function ContentDirectoryTree(props: Props) {
  const { repositoryId, branchId } = props;
  const scope = `${repositoryId}:${branchId}`;
  const selectedPath = normalizedDirectory(props.directory) ?? '';
  const [savedView, setView] = useState(() => initialView(selectedPath, scope));
  const fileReveal = useRef<{ selection: string; key: string } | null>(null);
  const [savedPages, setPages] = useState<{
    scope: string;
    byDirectory: Record<string, DirectoryPage>;
  }>(() => ({ scope, byDirectory: {} }));
  // Resolve a new URL synchronously: never briefly fetch its entire ancestry from the old base.
  const view =
    savedView.selection === selectedPath && savedView.scope === scope
      ? savedView
      : initialView(selectedPath, scope);
  const { base, expandedPath } = view;
  const pages = savedPages.scope === scope ? savedPages.byDirectory : {};
  const trail: string[] = [];
  if (expandedPath !== null) {
    trail.push(base);
    let path = base;
    for (const part of expandedPath.slice(base.length).split('/').filter(Boolean)) {
      if (trail.length === MAX_LEVELS) break;
      path += `${part}/`;
      trail.push(path);
    }
  }
  const levels = trail.map((directory) => {
    const position =
      directory === selectedPath
        ? {
            after: props.after ?? null,
            before: props.before ?? null,
            offset: props.offset ?? 0,
            folderAfter: props.folderAfter,
          }
        : (pages[directory] ?? { ...FIRST_ASSET_PAGE, folderAfter: null });
    return {
      directory,
      position,
      assetOptions: {
        branchId,
        query: '',
        searchMode: 'prefix' as const,
        directory,
        recursive: false,
        after: position.after,
        before: position.before,
        offset: position.offset,
      },
    };
  });
  const assetsQueries = useQueries({
    queries: levels.map(({ assetOptions }) => ({
      queryKey: contentAssetsQueryKey(repositoryId, assetOptions),
      queryFn: () => fetchContentAssets(repositoryId, assetOptions),
      staleTime: 30_000,
      gcTime: 60_000,
      refetchOnWindowFocus: 'always' as const,
      refetchOnReconnect: 'always' as const,
    })),
  });
  const foldersQueries = useQueries({
    queries: levels.map(({ directory, position }) => ({
      queryKey: ['content-directories', repositoryId, branchId, directory, position.folderAfter],
      queryFn: () =>
        fetchContentDirectories(repositoryId, {
          branchId,
          directory,
          after: position.folderAfter,
        }),
      staleTime: 30_000,
      gcTime: 60_000,
      refetchOnWindowFocus: 'always' as const,
      refetchOnReconnect: 'always' as const,
    })),
  });
  const rememberPages = (directory?: string, position?: DirectoryPage) =>
    setPages((saved) => ({
      scope,
      byDirectory: {
        ...(saved.scope === scope ? saved.byDirectory : {}),
        ...Object.fromEntries(levels.map((level) => [level.directory, level.position])),
        ...(directory !== undefined && position ? { [directory]: position } : {}),
      },
    }));
  const onExpand = (path: string) => setView({ ...view, expandedPath: path });
  const moveBase = (path: string) =>
    setView({ ...view, base: path, expandedPath: expandedPath ?? path });
  const select = (path: string) => {
    rememberPages();
    const fits =
      path.startsWith(base) &&
      path.slice(base.length).split('/').filter(Boolean).length < MAX_LEVELS;
    setView({ selection: path, scope, base: fits ? base : nearbyBase(path), expandedPath: path });
    props.onSelect(path);
  };
  let revealedFile: string | undefined;
  let descendants: FileTreeItem[] = [];
  // Assemble one flat list from the bounded expanded trail, retaining folder-before-file order.
  for (let index = levels.length - 1; index >= 0; index--) {
    const { directory, position } = levels[index];
    const { after: assetAfter, before: assetBefore, offset: assetOffset, folderAfter } = position;
    const assetsQuery = assetsQueries[index];
    const foldersQuery = foldersQueries[index];
    const assetPage = assetsQuery.isError ? undefined : assetsQuery.data;
    const folderPage = foldersQuery.isError ? undefined : foldersQuery.data;
    const files = (assetPage?.assets ?? []).filter((asset) => {
      const filename = asset.assetPath.slice(directory.length);
      return asset.assetPath.startsWith(directory) && filename && !filename.includes('/');
    });
    const paths = (folderPage?.directories ?? []).filter(
      (path) => normalizedDirectory(path) === path && immediateChild(directory, path) === path,
    );
    // A URL-selected ancestor may lie outside this page of siblings. Keep that known path visible.
    const contextPaths = [
      immediateChild(directory, selectedPath),
      immediateChild(directory, expandedPath),
    ].filter((path): path is string => path != null && !paths.includes(path));
    const visiblePaths = [...new Set([...contextPaths, ...paths])];
    const pageAssets = (next: AssetPagePosition) => {
      rememberPages(directory, { ...position, ...next });
      if (directory === selectedPath) props.onAssetPage(next);
    };
    const pageFolders = (cursor: string | null) => {
      rememberPages(directory, { ...position, folderAfter: cursor });
      if (directory === selectedPath) props.onFolderPage(cursor);
    };
    const hasAssetPosition = Boolean(assetAfter || assetBefore || assetOffset);
    const hasPreviousFiles = Boolean(assetPage?.previousCursor || assetOffset > 0);
    const hasNextFiles = Boolean(
      assetPage?.nextCursor ||
      (assetOffset > 0 && assetPage?.hasMore && assetOffset + PAGE_SIZE <= MAX_OFFSET),
    );
    const items: FileTreeItem[] = [];
    const add = (key: string, content: FileTreeItem['content'], focusable = true) =>
      items.push({ key: `${directory}:${key}`, depth: index + 1, content, focusable });
    if (foldersQuery.isPending) {
      add(
        'folders-loading',
        <p role="status" className="content-directory-tree__state">
          Loading directories…
        </p>,
        false,
      );
    }
    if (foldersQuery.isError) {
      add(
        'folders-error',
        <div role="alert" className="content-directory-tree__state">
          Could not load directories.{' '}
          <button type="button" onClick={() => void foldersQuery.refetch()}>
            Retry directories
          </button>
        </div>,
      );
    }
    for (const path of visiblePaths) {
      const expanded = expandedPath?.startsWith(path) ?? false;
      items.push({
        key: `folder:${path}`,
        depth: index + 1,
        selected: selectedPath === path,
        content: (
          <FileTreeRow
            label={path.slice(directory.length, -1)}
            title={path}
            ariaLabel={`Browse ${path}`}
            selected={selectedPath === path}
            expanded={expanded}
            toggleLabel={`${expanded ? 'Collapse' : 'Expand'} ${path}`}
            onToggle={() => onExpand(expanded ? directory : path)}
            onSelect={() => select(path)}
          />
        ),
      });
      if (expanded) {
        if (index + 1 < MAX_LEVELS) items.push(...descendants);
        else
          items.push({
            key: `continue:${path}`,
            depth: index + 2,
            content: (
              <div className="content-directory-tree__pagination">
                <button
                  type="button"
                  aria-label={`Continue into ${path}`}
                  onClick={() => moveBase(path)}
                >
                  Continue into this folder
                </button>
              </div>
            ),
          });
      }
    }
    for (const asset of files) {
      const selected = props.selectedAssetId === asset.assetId;
      const key = `file:${asset.branchId}:${asset.assetId}:${directory}`;
      if (selected) revealedFile = key;
      items.push({
        key,
        depth: index + 1,
        selected,
        content: (
          <FileTreeRow
            label={asset.assetPath.slice(directory.length)}
            ariaLabel={`Open ${asset.assetPath}`}
            title={asset.assetPath}
            selected={selected}
            current="page"
            onSelect={() => {
              rememberPages();
              props.onOpenAsset(asset, { directory, ...position });
            }}
          />
        ),
      });
    }
    if (assetsQuery.isPending) {
      add(
        'files-loading',
        <p role="status" className="content-directory-tree__state">
          Loading files…
        </p>,
        false,
      );
    }
    if (assetsQuery.isError) {
      add(
        'files-error',
        <div role="alert" className="content-directory-tree__state">
          Could not load files.{' '}
          <button type="button" onClick={() => void assetsQuery.refetch()}>
            Retry files
          </button>
        </div>,
      );
    }
    if (folderPage && assetPage && visiblePaths.length === 0 && files.length === 0) {
      add(
        'empty',
        <p className="content-directory-tree__state">
          {hasAssetPosition || folderAfter ? 'No items on this page.' : 'Empty folder.'}
        </p>,
        false,
      );
    }
    if (hasAssetPosition || hasPreviousFiles || hasNextFiles) {
      add(
        'files-pagination',
        <nav
          className="content-directory-tree__pagination"
          aria-label={`Files in ${directory || '/'}`}
        >
          <button
            type="button"
            disabled={!hasAssetPosition || assetsQuery.isFetching}
            onClick={() => pageAssets(FIRST_ASSET_PAGE)}
          >
            First files
          </button>
          <button
            type="button"
            disabled={!hasPreviousFiles || assetsQuery.isFetching || assetsQuery.isError}
            onClick={() =>
              pageAssets(
                assetPage?.previousCursor
                  ? { ...FIRST_ASSET_PAGE, before: assetPage.previousCursor }
                  : { ...FIRST_ASSET_PAGE, offset: Math.max(0, assetOffset - PAGE_SIZE) },
              )
            }
          >
            Previous files
          </button>
          <button
            type="button"
            disabled={!hasNextFiles || assetsQuery.isFetching}
            onClick={() =>
              pageAssets(
                assetPage?.nextCursor
                  ? { ...FIRST_ASSET_PAGE, after: assetPage.nextCursor }
                  : { ...FIRST_ASSET_PAGE, offset: assetOffset + PAGE_SIZE },
              )
            }
          >
            Next files
          </button>
        </nav>,
      );
    }
    if (folderAfter || folderPage?.nextCursor) {
      add(
        'folders-pagination',
        <nav
          className="content-directory-tree__pagination"
          aria-label={`Directory pages in ${directory || '/'}`}
        >
          <button
            type="button"
            disabled={!folderAfter || foldersQuery.isFetching}
            onClick={() => pageFolders(null)}
          >
            First folders
          </button>
          <button
            type="button"
            disabled={!folderPage?.nextCursor || foldersQuery.isFetching}
            onClick={() => pageFolders(folderPage?.nextCursor ?? null)}
          >
            Next folders
          </button>
        </nav>,
      );
    }
    descendants = items;
  }
  const items: FileTreeItem[] = [];
  if (base) {
    items.push(
      {
        key: 'parent-navigation',
        depth: 0,
        content: (
          <div className="content-directory-tree__pagination">
            <button type="button" aria-label="Browse root directory" onClick={() => select('')}>
              /
            </button>
            <button
              type="button"
              onClick={() => moveBase(base.slice(0, base.slice(0, -1).lastIndexOf('/') + 1))}
            >
              Parent folders
            </button>
          </div>
        ),
      },
      {
        key: 'base-path',
        depth: 0,
        focusable: false,
        content: (
          <p className="content-directory-tree__state" title={base}>
            Folders in {base}
          </p>
        ),
      },
    );
  }
  items.push(
    {
      key: `folder:${base}`,
      depth: 0,
      selected: selectedPath === base,
      content: (
        <FileTreeRow
          label={base ? base.slice(0, -1).split('/').pop() : '/'}
          title={base || '/'}
          ariaLabel={base ? `Browse ${base}` : 'Browse root directory'}
          selected={selectedPath === base}
          expanded={expandedPath !== null}
          toggleLabel={`${expandedPath === null ? 'Expand' : 'Collapse'} ${base || 'root directory'}`}
          onToggle={() =>
            setView({
              ...view,
              expandedPath:
                expandedPath === null
                  ? selectedPath.startsWith(base)
                    ? selectedPath
                    : base
                  : null,
            })
          }
          onSelect={() => select(base)}
        />
      ),
    },
    ...descendants,
  );
  const fileSelection = props.selectedAssetId == null ? null : `${scope}:${props.selectedAssetId}`;
  if (fileSelection && revealedFile)
    fileReveal.current = { selection: fileSelection, key: revealedFile };
  // Browsing another branch must not replace the selected file target with its old folder.
  const revealKey = fileSelection
    ? fileReveal.current?.selection === fileSelection
      ? fileReveal.current.key
      : `file:${branchId}:${props.selectedAssetId}:${selectedPath}`
    : `folder:${selectedPath}`;
  return <FileTree items={items} ariaLabel="Content directory" revealKey={revealKey} />;
}
