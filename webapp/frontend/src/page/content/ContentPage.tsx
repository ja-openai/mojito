import './content-page.css';

import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useMemo, useRef, useState } from 'react';
import { Navigate, useLocation, useSearchParams } from 'react-router-dom';

import {
  type ApiContentAsset,
  contentAssetsQueryKey,
  type ContentSearchMode,
  fetchContentAssets,
  fetchContentEmailParts,
  fetchContentPreview,
  normalizedDirectory,
} from '../../api/content';
import { FileTreeRow } from '../../components/FileTree';
import { LocaleMultiSelect } from '../../components/LocaleMultiSelect';
import { RepositoryMultiSelect } from '../../components/RepositoryMultiSelect';
import { ResizableMasterDetailLayout } from '../../components/ResizableMasterDetailLayout';
import { SearchControl } from '../../components/SearchControl';
import { ShortcutBar } from '../../components/ShortcutBar';
import { SingleSelectDropdown } from '../../components/SingleSelectDropdown';
import { useRepositories } from '../../hooks/useRepositories';
import { useUser } from '../../hooks/useUser';
import { useLocaleDisplayNameResolver } from '../../utils/localeDisplayNames';
import { useRepositorySelectionOptions } from '../../utils/repositorySelection';
import { ReviewProjectDocumentView } from '../review-project/ReviewProjectDocumentView';
import { TextUnitDetailPage } from '../text-unit-detail/TextUnitDetailPage';
import { type ContentAssetLocation, ContentDirectoryTree } from './ContentDirectoryTree';
import { ContentPassageEditor } from './ContentPassageEditor';
import { ContentPreviewHelp } from './ContentPreviewHelp';

const PAGE_SIZE = 100;
const MAX_OFFSET = 2_147_483_647 - PAGE_SIZE;

function positiveId(value: string | null) {
  const number = value == null ? NaN : Number(value);
  return Number.isSafeInteger(number) && number > 0 ? number : null;
}

export function ContentPage() {
  const user = useUser();
  return user.role === 'ROLE_ADMIN' ? (
    <RepositoryContentPage />
  ) : (
    <Navigate to="/repositories" replace />
  );
}

function RepositoryContentPage() {
  const [params, setParams] = useSearchParams();
  const location = useLocation();
  const editorScope = JSON.stringify([
    params.get('repoId'),
    params.get('branchId'),
    params.get('assetId'),
    params.get('locale'),
  ]);
  const currentLocation = useRef(location);
  currentLocation.current = location;
  const openLinkRequest = useRef<symbol | null>(null);
  useEffect(
    () => () => {
      openLinkRequest.current = null;
    },
    [],
  );
  const queryClient = useQueryClient();
  const user = useUser();
  const catalogueScrollRef = useRef<HTMLElement | null>(null);
  const shortcutBarRef = useRef<HTMLDivElement>(null);
  const [narrowWorkspace, setNarrowWorkspace] = useState(false);
  const [explorerCollapsed, setExplorerCollapsed] = useState(false);
  const [dockedExplorerCollapsed, setDockedExplorerCollapsed] = useState(true);
  useEffect(() => {
    const media = window.matchMedia?.('(max-width: 80rem)');
    if (!media) return;
    const update = () => setNarrowWorkspace(media.matches);
    update();
    media.addEventListener('change', update);
    return () => media.removeEventListener('change', update);
  }, []);
  const selectionVersion = useRef(0);
  const lastBrowsePosition = useRef('');
  const [editorDocked, setEditorDocked] = useState(false);
  const [editorSelection, setEditorSelection] = useState<{
    scope: string;
    textUnitId: number;
    anchor: HTMLElement;
    version: number;
  } | null>(null);
  const selectedTextUnitId =
    editorSelection?.scope === editorScope ? editorSelection.textUnitId : null;
  const closeEditor = () => {
    if (editorSelection?.anchor.isConnected) {
      editorSelection.anchor.focus({ preventScroll: true });
    }
    setEditorSelection(null);
    setEditorDocked(false);
  };
  const editPassage = (textUnitId: number, anchor: HTMLElement) => {
    setEditorSelection({
      scope: editorScope,
      textUnitId,
      anchor,
      version: ++selectionVersion.current,
    });
  };
  const nextPassage = () => {
    const anchor = editorSelection?.anchor;
    const article = anchor?.closest('article');
    if (!anchor || !article) return null;
    const passages = Array.from(
      article.querySelectorAll<HTMLElement>('[data-content-editable="true"]'),
    ).filter((element) => !element.closest('[hidden]'));
    const index = passages.indexOf(anchor);
    return index < 0
      ? null
      : (passages
          .slice(index + 1)
          .find((element) => element.dataset.tmTextUnitId !== anchor.dataset.tmTextUnitId) ?? null);
  };
  const repositoryId = positiveId(params.get('repoId'));
  const branchId = positiveId(params.get('branchId'));
  const assetId = positiveId(params.get('assetId'));
  const after = params.get('after');
  const before = params.get('before');
  const requestedOffset = Number(params.get('offset') ?? '0');
  const offset =
    !after &&
    !before &&
    Number.isInteger(requestedOffset) &&
    requestedOffset >= 0 &&
    requestedOffset <= MAX_OFFSET &&
    requestedOffset % PAGE_SIZE === 0
      ? requestedOffset
      : 0;
  const query = params.get('q') ?? '';
  const requestedSearchMode = params.get('searchMode');
  const searchMode: ContentSearchMode =
    requestedSearchMode === 'exact' ||
    requestedSearchMode === 'contains' ||
    requestedSearchMode === 'prefix'
      ? requestedSearchMode
      : query
        ? 'contains'
        : 'prefix';
  const directory = normalizedDirectory(params.get('directory') ?? '') ?? '';
  const folderAfter = params.get('folderAfter');
  const hasPagePosition = Boolean(after || before || offset);
  const [search, setSearch] = useState(query);
  const searchTimer = useRef<number | null>(null);
  const repositoriesQuery = useRepositories();
  const repositoryOptions = useRepositorySelectionOptions(repositoriesQuery.data);
  const resolveLocaleDisplayName = useLocaleDisplayNameResolver();
  const repository = repositoriesQuery.data?.find((item) => item.id === repositoryId);
  const localeOptions = useMemo(
    () =>
      [
        ...new Set([
          repository?.sourceLocale?.bcp47Tag,
          ...(repository?.repositoryLocales ?? []).map((item) => item.locale.bcp47Tag),
        ]),
      ].filter((tag): tag is string => Boolean(tag)),
    [repository],
  );
  const requestedLocale = params.get('locale');
  const locale = requestedLocale ?? repository?.sourceLocale?.bcp47Tag ?? localeOptions[0] ?? '';
  const validLocale = localeOptions.includes(locale);

  useEffect(() => {
    setSearch(query);
    return () => window.clearTimeout(searchTimer.current ?? undefined);
  }, [query, location]);
  useEffect(() => {
    setEditorSelection(null);
    setEditorDocked(false);
  }, [editorScope]);
  const browsePosition = JSON.stringify([
    repositoryId,
    branchId,
    after,
    before,
    offset,
    query,
    searchMode,
    directory,
    folderAfter,
  ]);
  useEffect(() => {
    if (
      lastBrowsePosition.current !== browsePosition &&
      assetId == null &&
      catalogueScrollRef.current
    ) {
      catalogueScrollRef.current.scrollTop = 0;
    }
    lastBrowsePosition.current = browsePosition;
  }, [browsePosition, assetId]);

  const updateParams = (values: Record<string, string | number | null>, replace = false) => {
    const next = new URLSearchParams(params);
    for (const [key, value] of Object.entries(values)) {
      if (value == null || value === '') next.delete(key);
      else next.set(key, String(value));
    }
    setParams(next, { replace });
  };

  const resetPage = { after: null, before: null, offset: null };
  const searchPaths = (value: string) => {
    window.clearTimeout(searchTimer.current ?? undefined);
    const next = searchMode === 'contains' ? value.trim() : value;
    if (next !== query) updateParams({ ...resetPage, q: next, searchMode }, true);
  };
  const changeSearch = (value: string) => {
    setSearch(value);
    window.clearTimeout(searchTimer.current ?? undefined);
    if (!value) {
      searchPaths(value);
    } else {
      searchTimer.current = window.setTimeout(() => {
        if (currentLocation.current === location) searchPaths(value);
      }, 300);
    }
  };
  const selectDirectory = (nextDirectory: string) =>
    updateParams({
      ...resetPage,
      directory: nextDirectory,
      folderAfter: null,
      q: null,
      searchMode,
    });
  const assetOptions = {
    branchId,
    query,
    searchMode: query ? searchMode : ('prefix' as const),
    directory,
    recursive: Boolean(query),
    after,
    before,
    offset,
  };
  const assetsQuery = useQuery({
    queryKey: contentAssetsQueryKey(repositoryId, assetOptions),
    queryFn: () => fetchContentAssets(repositoryId!, assetOptions),
    enabled: repository != null,
    staleTime: 30_000,
    gcTime: 60_000,
    refetchOnWindowFocus: 'always',
    refetchOnReconnect: 'always',
  });
  const assetPage = assetsQuery.isError ? undefined : assetsQuery.data;
  const assets = assetPage?.assets ?? [];
  const firstPage = () => updateParams(resetPage);
  const previousPage = () =>
    updateParams(
      assetPage?.previousCursor
        ? { ...resetPage, before: assetPage.previousCursor }
        : { ...resetPage, offset: Math.max(0, offset - PAGE_SIZE) || null },
    );
  const nextPage = () =>
    updateParams(
      assetPage?.nextCursor
        ? { ...resetPage, after: assetPage.nextCursor }
        : { ...resetPage, offset: offset + PAGE_SIZE },
    );
  const openAsset = (asset: ApiContentAsset, browse?: ContentAssetLocation) =>
    updateParams({
      ...(browse && !query ? browse : {}),
      assetId: asset.assetId,
      branchId: asset.branchId,
    });
  const previewQuery = useQuery({
    queryKey: ['content-preview', repositoryId, branchId, assetId, locale],
    queryFn: () => fetchContentPreview(repositoryId!, assetId!, branchId, locale),
    enabled: repository != null && assetId != null && validLocale,
    // Returning from the existing translation editor must show its latest save.
    staleTime: 0,
    refetchOnMount: 'always',
  });
  const preview = previewQuery.data;
  const emailPartsQuery = useQuery({
    queryKey: ['content-preview', repositoryId, 'email-parts', preview?.branchId, assetId, locale],
    queryFn: () =>
      fetchContentEmailParts(
        repositoryId!,
        preview!.branchId,
        locale,
        preview!.document!.assetPath,
      ),
    enabled: validLocale && preview?.document?.assetPath.endsWith('_body.mdx') === true,
    staleTime: 0,
  });
  const isEmail = preview?.document?.assetPath.endsWith('_body.mdx') === true;
  const editorOpen =
    selectedTextUnitId != null && editorSelection != null && assetId != null && validLocale;
  const docked = editorOpen && editorDocked;
  const collapseForEditing = docked && narrowWorkspace;

  return (
    <div className="content-page">
      <header className="content-page__header">
        <div className="content-page__selectors">
          <RepositoryMultiSelect
            selectionMode="single"
            options={repositoryOptions}
            selectedIds={repositoryId == null ? [] : [repositoryId]}
            buttonAriaLabel="Select content repository"
            disabled={repositoriesQuery.isPending}
            onChange={([next]) => setParams(next == null ? {} : { repoId: String(next) })}
          />
          {repository ? (
            <>
              <SingleSelectDropdown
                label="Branch"
                buttonAriaLabel="Content branch"
                placeholder="Select a branch"
                searchPlaceholder="Search branches"
                options={(assetPage?.branches ?? []).map((branch) => ({
                  value: branch.id,
                  label: branch.name == null ? 'Default branch' : branch.name || 'Unnamed branch',
                }))}
                value={branchId ?? assetPage?.branchId ?? null}
                disabled={assetsQuery.isPending}
                onChange={(next) => {
                  if (next != null) {
                    updateParams({
                      ...resetPage,
                      branchId: next,
                      assetId: null,
                      directory: null,
                      folderAfter: null,
                    });
                  }
                }}
              />
              <LocaleMultiSelect
                selectionMode="single"
                label={!validLocale && requestedLocale ? requestedLocale : undefined}
                buttonAriaLabel="Content language"
                options={localeOptions.map((tag) => {
                  const label = `${resolveLocaleDisplayName(tag)}${
                    tag === repository.sourceLocale?.bcp47Tag ? ' · Source' : ''
                  }`;
                  return { tag, label };
                })}
                selectedTags={validLocale ? [locale] : []}
                onChange={([next]) => {
                  if (next != null) updateParams({ locale: next });
                }}
              />
            </>
          ) : null}
        </div>
      </header>

      {repositoriesQuery.isPending ? (
        <p role="status" className="content-page__state">
          Loading repositories…
        </p>
      ) : null}
      {repositoriesQuery.isError ? (
        <div role="alert" className="content-page__state">
          Could not load repositories.{' '}
          <button onClick={() => void repositoriesQuery.refetch()}>Try again</button>
        </div>
      ) : null}
      {!repositoriesQuery.isPending && !repositoriesQuery.isError && !repository ? (
        <p className="content-page__state">
          {repositoryId != null
            ? 'This repository is unavailable. Select another repository.'
            : 'Select a repository to browse its pages and modules.'}
        </p>
      ) : null}

      {repository ? (
        <ResizableMasterDetailLayout
          className="content-page__body"
          storageKey="mojito.content.explorerWidth"
          sidebarLabel="File explorer"
          detailLabel="Document workspace"
          resizeLabel="Resize file explorer"
          sidebarClassName="content-page__explorer-pane"
          detailClassName="content-page__preview-pane"
          defaultSidebarWidthPercent={28}
          minSidebarWidthPercent={18}
          maxSidebarWidthPercent={45}
          collapsible
          collapsed={collapseForEditing ? dockedExplorerCollapsed : explorerCollapsed}
          onCollapsedChange={collapseForEditing ? setDockedExplorerCollapsed : setExplorerCollapsed}
          sidebar={
            <section
              ref={catalogueScrollRef}
              className="content-page__assets"
              aria-label="Repository pages and modules"
            >
              <div className="content-page__list-header">
                <SearchControl
                  value={search}
                  onChange={changeSearch}
                  onSubmit={() => searchPaths(search)}
                  inputAriaLabel="Search content paths"
                  placeholder="Search paths…"
                  maxLength={255}
                  className="content-page__search"
                  leading={
                    <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
                      <circle cx="10.5" cy="10.5" r="6.5" />
                      <path d="m16 16 4.5 4.5" />
                    </svg>
                  }
                />
              </div>
              {assetPage?.warnings.map((warning) => (
                <p key={warning} role="status" className="content-page__state">
                  {warning}
                </p>
              ))}
              {!query ? (
                <ContentDirectoryTree
                  key={`${repositoryId}:${branchId}`}
                  repositoryId={repositoryId!}
                  branchId={branchId}
                  directory={directory}
                  folderAfter={folderAfter}
                  after={after}
                  before={before}
                  offset={offset}
                  onSelect={selectDirectory}
                  onFolderPage={(cursor) => updateParams({ folderAfter: cursor })}
                  onAssetPage={(position) => updateParams({ ...resetPage, ...position })}
                  selectedAssetId={assetId}
                  onOpenAsset={openAsset}
                />
              ) : (
                <div className="content-page__search-results">
                  {directory ? (
                    <p className="content-page__directory-scope">
                      In <strong>{directory}</strong>
                    </p>
                  ) : null}
                  {assetsQuery.isPending ? (
                    <p role="status" className="content-page__state">
                      Loading pages and modules…
                    </p>
                  ) : null}
                  {assetsQuery.isError ? (
                    <div role="alert" className="content-page__state">
                      Could not load pages and modules.{' '}
                      <button onClick={() => void assetsQuery.refetch()}>Try again</button>
                    </div>
                  ) : null}
                  {!assetsQuery.isPending && !assetsQuery.isError && assets.length === 0 ? (
                    <p className="content-page__state">
                      {hasPagePosition
                        ? 'No assets on this page. Return to the first page or search again.'
                        : 'No matching MDX paths.'}
                    </p>
                  ) : null}
                  <nav className="content-page__pagination" aria-label="Content pages">
                    <button
                      type="button"
                      disabled={
                        (!assetPage?.previousCursor && offset === 0) ||
                        assetsQuery.isFetching ||
                        assetsQuery.isError
                      }
                      onClick={previousPage}
                    >
                      Previous
                    </button>
                    <span className="content-page__asset-count" aria-live="polite">
                      {assetPage && assets.length > 0
                        ? `${assets.length} ${assets.length === 1 ? 'asset' : 'assets'} on this page`
                        : assetsQuery.isPending
                          ? 'Loading…'
                          : 'No assets shown'}
                    </span>
                    <button
                      type="button"
                      disabled={
                        (!assetPage?.nextCursor &&
                          !(
                            offset > 0 &&
                            assetPage?.hasMore &&
                            offset + PAGE_SIZE <= MAX_OFFSET
                          )) ||
                        assetsQuery.isFetching
                      }
                      onClick={nextPage}
                    >
                      Next
                    </button>
                    {hasPagePosition ? (
                      <button type="button" onClick={firstPage}>
                        First page
                      </button>
                    ) : null}
                  </nav>
                  <ul className="content-page__file-results" aria-label="Matching files">
                    {assets.map((asset) => (
                      <li key={`${asset.branchId}:${asset.assetId}`}>
                        <FileTreeRow
                          label={asset.assetPath}
                          ariaLabel={`Open ${asset.assetPath}`}
                          title={asset.assetPath}
                          selected={asset.assetId === assetId}
                          current="page"
                          onSelect={() => openAsset(asset)}
                        />
                      </li>
                    ))}
                  </ul>
                </div>
              )}
            </section>
          }
          detail={
            <ResizableMasterDetailLayout
              className={`content-page__workspace${docked ? ' content-page__workspace--docked' : ' content-page__workspace--inline'}`}
              storageKey="mojito.content.previewWidth"
              sidebarLabel="Page preview"
              detailLabel="Translation editor"
              resizeLabel="Resize document preview"
              sidebarClassName="content-page__document-pane"
              detailClassName="content-page__translation-pane"
              defaultSidebarWidthPercent={52}
              minSidebarWidthPercent={30}
              maxSidebarWidthPercent={70}
              detailVisible={editorOpen}
              sidebar={
                assetId != null ? (
                  <section className="content-page__preview" aria-label="Content preview">
                    <div className="content-page__preview-header">
                      {validLocale && locale === repository.sourceLocale?.bcp47Tag ? (
                        <span
                          className="content-page__source-label"
                          title="Edit source content in Git."
                        >
                          Source · read only
                        </span>
                      ) : null}
                      {validLocale ? (
                        <ContentPreviewHelp
                          key={`${assetId}:${locale}`}
                          source={locale === repository.sourceLocale?.bcp47Tag}
                        />
                      ) : null}
                    </div>
                    {!validLocale ? (
                      <p role="alert" className="content-page__state">
                        Select a language configured for this repository.
                      </p>
                    ) : (
                      <ReviewProjectDocumentView
                        key={`${repositoryId}:${branchId}:${assetId}:${locale}`}
                        data={
                          preview
                            ? {
                                documents: preview.document
                                  ? [
                                      preview.document,
                                      ...(isEmail ? (emailPartsQuery.data?.documents ?? []) : []),
                                    ]
                                  : [],
                                warnings: [
                                  ...preview.warnings,
                                  ...(isEmail ? (emailPartsQuery.data?.warnings ?? []) : []),
                                  ...(isEmail &&
                                  !emailPartsQuery.isPending &&
                                  !emailPartsQuery.isError &&
                                  emailPartsQuery.data?.documents.length !== 2
                                    ? [
                                        'Email subject or preheader is missing. Showing the body on its own.',
                                      ]
                                    : []),
                                ],
                              }
                            : undefined
                        }
                        textUnits={[]}
                        selectedTextUnitId={selectedTextUnitId}
                        localeTag={locale}
                        loading={previewQuery.isPending || (isEmail && emailPartsQuery.isPending)}
                        error={previewQuery.isError || (isEmail && emailPartsQuery.isError)}
                        onRetry={() => {
                          void previewQuery.refetch();
                          if (isEmail) void emailPartsQuery.refetch();
                        }}
                        onSelect={() => undefined}
                        onNavigate={() => {
                          setEditorSelection(null);
                          return true;
                        }}
                        repositoryPreview={{
                          editing: true,
                          sourceLocaleTag:
                            preview?.sourceLocaleTag ?? repository.sourceLocale?.bcp47Tag ?? '',
                          onEdit: editPassage,
                          onOpenAsset: async (assetPath) => {
                            const request = Symbol();
                            openLinkRequest.current = request;
                            const scope = location;
                            const linkedAssets = await queryClient.fetchQuery({
                              queryKey: [
                                'content-linked-asset',
                                repositoryId,
                                preview!.branchId,
                                assetPath,
                              ],
                              queryFn: () =>
                                fetchContentAssets(repositoryId!, {
                                  branchId: preview!.branchId,
                                  query: assetPath,
                                  searchMode: 'exact',
                                }),
                              staleTime: 30_000,
                            });
                            if (
                              currentLocation.current !== scope ||
                              request !== openLinkRequest.current
                            )
                              return;
                            const linkedAsset = linkedAssets.assets.find(
                              (asset) =>
                                asset.assetPath === assetPath &&
                                asset.branchId === preview!.branchId,
                            );
                            if (!linkedAsset) throw new Error('Linked asset is not available.');
                            updateParams({
                              assetId: linkedAsset.assetId,
                              branchId: linkedAsset.branchId,
                            });
                            setEditorSelection(null);
                          },
                        }}
                      />
                    )}
                  </section>
                ) : (
                  <p className="content-page__empty-preview">
                    Select a file to preview and translate.
                  </p>
                )
              }
              detail={
                editorOpen && editorSelection ? (
                  <ContentPassageEditor
                    anchor={editorSelection.anchor}
                    docked={docked}
                    footerRef={shortcutBarRef}
                    onAnchorUnavailable={closeEditor}
                  >
                    {(ready) => (
                      <TextUnitDetailPage
                        key={`${user.username}:${selectedTextUnitId}:${locale}`}
                        embedded={{
                          tmTextUnitId: editorSelection.textUnitId,
                          localeTag: locale,
                          presentation: editorDocked ? 'full' : 'compact',
                          navigationKey: editorSelection.version,
                          autoFocus: ready,
                          onClose: closeEditor,
                          onShowDetails: () => setEditorDocked(true),
                          onShowCompact: () => setEditorDocked(false),
                          onNext: nextPassage()
                            ? () => {
                                const next = nextPassage();
                                const id = positiveId(next?.dataset.tmTextUnitId ?? null);
                                if (next && id != null) {
                                  next.scrollIntoView?.({ block: 'nearest' });
                                  next.click();
                                }
                              }
                            : undefined,
                          onSaved: () => {
                            void queryClient.invalidateQueries({
                              queryKey: ['content-preview', repositoryId],
                            });
                          },
                        }}
                      />
                    )}
                  </ContentPassageEditor>
                ) : null
              }
            />
          }
        />
      ) : null}
      {editorOpen ? (
        <div ref={shortcutBarRef} style={{ flexShrink: 0 }}>
          <ShortcutBar
            shortcuts={[
              { keys: ['Cmd/Ctrl Enter'], label: 'Save' },
              { keys: ['Cmd/Ctrl Shift Enter'], label: 'Save & next', disabled: !nextPassage() },
              { keys: ['Esc'], label: 'Close editor' },
            ]}
          />
        </div>
      ) : null}
    </div>
  );
}
