import { useQuery } from '@tanstack/react-query';
import { type RefObject, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';

import {
  type ApiInflectionBindingRenderResponse,
  type ApiInflectionBindingReport,
  type ApiMatchedGlossaryTerm,
  fetchGlossaries,
  matchGlossaryTerms,
  renderInflectionBindingManifest,
  reportInflectionBindingManifest,
} from '../../api/glossaries';
import type { ApiRepository } from '../../api/repositories';
import type { TextUnitSearchRequest } from '../../api/text-units';
import { LocalePill } from '../../components/LocalePill';
import { Modal } from '../../components/Modal';
import { PillDropdown } from '../../components/PillDropdown';
import {
  TranslationTextEditor,
  type TranslationTextEditorKeyDownEvent,
} from '../../components/TranslationTextEditor';
import { getRowHeightPx } from '../../components/virtual/getRowHeightPx';
import { useMeasuredRowRefs } from '../../components/virtual/useMeasuredRowRefs';
import { useVirtualRows } from '../../components/virtual/useVirtualRows';
import { VirtualList } from '../../components/virtual/VirtualList';
import type {
  VisibleTextEditorHandle,
  VisibleTextMarksMode,
} from '../../components/VisibleTextEditor';
import { VisibleTextRenderer } from '../../components/VisibleTextRenderer';
import { useProtectedTextTokenGuard } from '../../hooks/useProtectedTextTokenGuard';
import { formatLocalDateTime, getLocalAndUtcDateTimeTooltip } from '../../utils/dateTime';
import { filterSelfGlossaryMatches, sortGlossaryMatches } from '../../utils/glossary-matches';
import type { GlossaryWorkbenchContext } from '../../utils/glossaryWorkbench';
import { isPrimaryActionShortcut } from '../../utils/keyboardShortcuts';
import { isRtlLocale } from '../../utils/localeDirection';
import {
  buildMf2TermBindingManifest,
  extractMf2TermRequirements,
  mf2RuntimeVariableNamesForUsage,
  type Mf2TermBindingManifestGroup,
  type Mf2TermUsageRequirement,
  uniqueMf2TermArguments,
} from '../../utils/mf2TermRequirements';
import { getNonRootRepositoryLocaleTags } from '../../utils/repositoryLocales';
import { saveWorkbenchSessionSearch, WORKBENCH_SESSION_QUERY_KEY } from './workbench-session-state';
import type { WorkbenchDiffModalData, WorkbenchRow } from './workbench-types';

type WorkbenchBodyProps = {
  rows: WorkbenchRow[];
  editingRowId: string | null;
  editingValue: string;
  editedRowIds: Set<string>;
  statusSavingRowIds: Set<string>;
  onShowDiff: (rowId: string) => void;
  onStartEditing: (rowId: string, translation: string | null) => void;
  onCancelEditing: () => void;
  onSaveEditing: () => void;
  canSaveEditing: boolean;
  onChangeEditingValue: (value: string) => void;
  onChangeStatus: (rowId: string, status: string) => void;
  statusOptions: string[];
  translationInputRef: RefObject<VisibleTextEditorHandle>;
  registerRowRef: (rowId: string, element: HTMLDivElement | null) => void;
  isSaving: boolean;
  saveErrorMessage: string | null;
  isRepositoryLoading: boolean;
  repositoryErrorMessage: string | null;
  canSearch: boolean;
  isSearchLoading: boolean;
  searchErrorMessage: string | null;
  onRetrySearch: () => void;
  hasSearched: boolean;
  activeSearchRequest: TextUnitSearchRequest | null;
  repositories: ApiRepository[];
  onAddToCollection: (tmTextUnitId: number, repositoryId: number | null) => void;
  onRemoveFromCollection: (tmTextUnitId: number) => void;
  activeCollectionIds: Set<number>;
  activeCollectionName: string | null;
  glossaryContext: GlossaryWorkbenchContext | null;
  restoreScrollTop: number | null;
  restoreRowId: string | null;
  onRestoreScrollConsumed: () => void;
  isVisibleTextEditorEnabled: boolean;
  translationMarksMode: VisibleTextMarksMode;
  onChangeTranslationMarksMode: (mode: VisibleTextMarksMode) => void;
  showProtectedTokens: boolean;
  showDateMetadata: boolean;
};

type GlossaryWorkbenchTarget = {
  glossaryId: number;
  glossaryName: string;
};

const getGlossaryWorkbenchKey = (repositoryName: string, assetPath: string | null | undefined) =>
  `${repositoryName.trim().toLowerCase()}\u0000${(assetPath ?? '').trim().toLowerCase()}`;

const normalizeWorkbenchToken = (value: string | null | undefined) =>
  (value ?? '').trim().toLowerCase();

const buildTextUnitDetailPath = (row: WorkbenchRow) => {
  const params = new URLSearchParams();
  params.set('locale', row.locale);
  return `/text-units/${row.tmTextUnitId}?${params.toString()}`;
};

export function WorkbenchBody({
  rows,
  editingRowId,
  editingValue,
  editedRowIds,
  statusSavingRowIds,
  onShowDiff,
  onStartEditing,
  onCancelEditing,
  onSaveEditing,
  canSaveEditing,
  onChangeEditingValue,
  onChangeStatus,
  statusOptions,
  translationInputRef,
  registerRowRef,
  isSaving,
  saveErrorMessage,
  isRepositoryLoading,
  repositoryErrorMessage,
  canSearch,
  isSearchLoading,
  searchErrorMessage,
  onRetrySearch,
  hasSearched,
  activeSearchRequest,
  repositories,
  onAddToCollection,
  onRemoveFromCollection,
  activeCollectionIds,
  activeCollectionName,
  glossaryContext,
  restoreScrollTop,
  restoreRowId,
  onRestoreScrollConsumed,
  isVisibleTextEditorEnabled,
  translationMarksMode,
  onChangeTranslationMarksMode,
  showProtectedTokens,
  showDateMetadata,
}: WorkbenchBodyProps) {
  const navigate = useNavigate();
  const editingTextTokenGuard = useProtectedTextTokenGuard(
    editingValue,
    isVisibleTextEditorEnabled && editingRowId ? 'icu-html' : 'none',
  );
  const registerRowRefRef = useRef(registerRowRef);

  // Keep latest callback without changing ref callback identities.
  registerRowRefRef.current = registerRowRef;

  const hasRows = rows.length > 0;
  const glossaryQuery = useQuery({
    queryKey: ['workbench-glossary-affordances'],
    queryFn: () => fetchGlossaries({ limit: 200 }),
    enabled: hasRows,
    staleTime: 60_000,
  });
  const glossaryTargetByWorkbenchKey = useMemo(() => {
    const glossaries = glossaryQuery.data?.glossaries ?? [];
    const targets = new Map<string, GlossaryWorkbenchTarget>();
    if (glossaryContext) {
      targets.set(
        getGlossaryWorkbenchKey(glossaryContext.backingRepositoryName, glossaryContext.assetPath),
        {
          glossaryId: glossaryContext.glossaryId,
          glossaryName: glossaryContext.glossaryName,
        },
      );
    }
    glossaries
      .filter((glossary) => Boolean(glossary.backingRepository.name && glossary.assetPath))
      .forEach((glossary) => {
        const key = getGlossaryWorkbenchKey(glossary.backingRepository.name, glossary.assetPath);
        if (!targets.has(key)) {
          targets.set(key, {
            glossaryId: glossary.id,
            glossaryName: glossary.name,
          });
        }
      });
    return targets;
  }, [glossaryContext, glossaryQuery.data?.glossaries]);
  const getGlossaryTargetForRow = useCallback(
    (row: WorkbenchRow) => {
      if (
        glossaryContext &&
        normalizeWorkbenchToken(row.repositoryName) ===
          normalizeWorkbenchToken(glossaryContext.backingRepositoryName) &&
        (!row.assetPath ||
          normalizeWorkbenchToken(row.assetPath) ===
            normalizeWorkbenchToken(glossaryContext.assetPath))
      ) {
        return {
          glossaryId: glossaryContext.glossaryId,
          glossaryName: glossaryContext.glossaryName,
        };
      }
      return glossaryTargetByWorkbenchKey.get(
        getGlossaryWorkbenchKey(row.repositoryName, row.assetPath),
      );
    },
    [glossaryContext, glossaryTargetByWorkbenchKey],
  );

  const showNoResults =
    hasSearched &&
    canSearch &&
    !isRepositoryLoading &&
    !isSearchLoading &&
    !repositoryErrorMessage &&
    !searchErrorMessage &&
    !saveErrorMessage &&
    rows.length === 0;

  const showRepositoryLoading =
    isRepositoryLoading &&
    !repositoryErrorMessage &&
    !searchErrorMessage &&
    !saveErrorMessage &&
    rows.length === 0;

  const showEmptyPrompt =
    !hasSearched &&
    !isRepositoryLoading &&
    !repositoryErrorMessage &&
    !searchErrorMessage &&
    !saveErrorMessage;

  const showSearchLoading =
    isSearchLoading &&
    !isRepositoryLoading &&
    !repositoryErrorMessage &&
    !searchErrorMessage &&
    !saveErrorMessage &&
    rows.length === 0;

  const [showSearchOverlay, setShowSearchOverlay] = useState(false);
  const [openStatusRowId, setOpenStatusRowId] = useState<string | null>(null);
  useEffect(() => {
    if (!isSearchLoading) {
      setShowSearchOverlay(false);
      return;
    }
    // Avoid flicker for fast searches.
    const timeout = window.setTimeout(() => setShowSearchOverlay(true), 180);
    return () => window.clearTimeout(timeout);
  }, [isSearchLoading]);

  const isShowingStaleResults = showSearchOverlay && isSearchLoading && rows.length > 0;
  const showInlineErrorNotice =
    hasRows &&
    (Boolean(repositoryErrorMessage) || Boolean(saveErrorMessage) || Boolean(searchErrorMessage));

  const getRepositoryScope = useCallback(
    (row: WorkbenchRow): { repoId: number; localeTags: string[] } | null => {
      const repository = repositories.find((repo) => repo.name === row.repositoryName);
      const repoId =
        repository?.id ??
        (activeSearchRequest?.repositoryIds?.length === 1
          ? activeSearchRequest.repositoryIds[0]
          : null);
      if (!repoId) {
        return null;
      }

      const repoLocales = repository ? getNonRootRepositoryLocaleTags(repository) : [];
      const localeTags =
        repoLocales.length > 0
          ? repoLocales
          : activeSearchRequest?.localeTags?.length
            ? activeSearchRequest.localeTags
            : [row.locale];

      if (!localeTags.length) {
        return null;
      }

      return { repoId, localeTags };
    },
    [activeSearchRequest, repositories],
  );

  const openTextUnitLink = useCallback(
    (row: WorkbenchRow) => {
      const scope = getRepositoryScope(row);
      if (!scope) {
        return;
      }

      const request: TextUnitSearchRequest = {
        repositoryIds: [scope.repoId],
        localeTags: scope.localeTags,
        searchAttribute: 'tmTextUnitIds',
        searchType: 'exact',
        searchText: String(row.tmTextUnitId),
        offset: 0,
      };

      const sessionKey = saveWorkbenchSessionSearch(request);
      const params = new URLSearchParams();
      params.set(WORKBENCH_SESSION_QUERY_KEY, sessionKey);
      void navigate(`/workbench?${params.toString()}`);
    },
    [getRepositoryScope, navigate],
  );

  const openAssetLink = useCallback(
    (row: WorkbenchRow) => {
      if (!row.assetPath) {
        return;
      }
      const scope = getRepositoryScope(row);
      if (!scope) {
        return;
      }

      const request: TextUnitSearchRequest = {
        repositoryIds: [scope.repoId],
        localeTags: scope.localeTags,
        searchAttribute: 'asset',
        searchType: 'exact',
        searchText: row.assetPath,
        offset: 0,
      };

      const sessionKey = saveWorkbenchSessionSearch(request);
      const params = new URLSearchParams();
      params.set(WORKBENCH_SESSION_QUERY_KEY, sessionKey);
      void navigate(`/workbench?${params.toString()}`);
    },
    [getRepositoryScope, navigate],
  );

  const openTextUnitDetailInNewWindow = useCallback((row: WorkbenchRow) => {
    window.open(buildTextUnitDetailPath(row), '_blank', 'noopener,noreferrer');
  }, []);

  const openGlossaryTerm = useCallback(
    (row: WorkbenchRow, glossaryTarget: GlossaryWorkbenchTarget) => {
      const params = new URLSearchParams();
      params.set('termId', String(row.tmTextUnitId));
      void navigate(`/glossaries/${glossaryTarget.glossaryId}?${params.toString()}`);
    },
    [navigate],
  );

  const scrollElementRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (restoreScrollTop === null && restoreRowId === null) {
      return;
    }
    if (isSearchLoading || rows.length === 0) {
      return;
    }
    const frame = window.requestAnimationFrame(() => {
      const element = scrollElementRef.current;
      if (!element) {
        return;
      }

      if (restoreScrollTop !== null) {
        element.scrollTop = restoreScrollTop;
      }

      if (restoreRowId) {
        const escapedRowId =
          typeof CSS !== 'undefined' && typeof CSS.escape === 'function'
            ? CSS.escape(restoreRowId)
            : restoreRowId.replace(/"/g, '\\"');
        const rowElement = element.querySelector('[data-workbench-row-id="' + escapedRowId + '"]');
        if (rowElement instanceof HTMLElement) {
          rowElement.scrollIntoView({ block: 'nearest' });
        }
      }

      onRestoreScrollConsumed();
    });
    return () => window.cancelAnimationFrame(frame);
  }, [isSearchLoading, onRestoreScrollConsumed, restoreRowId, restoreScrollTop, rows.length]);

  const estimateSize = useCallback(
    () =>
      getRowHeightPx({
        element: scrollElementRef.current,
        cssVariable: '--workbench-row-height',
        defaultRem: 11.875,
      }),
    [],
  );
  const getItemKey = useCallback((index: number) => rows[index]?.id ?? index, [rows]);

  const {
    items: virtualItems,
    totalSize,
    measureElement,
  } = useVirtualRows<HTMLDivElement>({
    count: rows.length,
    getItemKey,
    estimateSize,
    getScrollElement: () => scrollElementRef.current,
    overscan: 6,
  });

  const { getRowRef } = useMeasuredRowRefs<string, HTMLDivElement>({
    measureElement,
    onAssign: (rowId, element) => {
      registerRowRefRef.current(rowId, element);
    },
  });

  return (
    <div className="workbench-page__body">
      <div className="workbench-page__table-header">
        <div className="workbench-page__table-header-cell">Text unit (id, asset, repository)</div>
        <div className="workbench-page__table-header-cell workbench-page__table-header-cell--source">
          Source &amp; comment
        </div>
        <div className="workbench-page__table-header-cell workbench-page__table-header-cell--translation">
          Translation
        </div>
        <div className="workbench-page__table-header-cell workbench-page__table-header-cell--locale">
          Locale
        </div>
        <div className="workbench-page__table-header-cell workbench-page__table-header-cell--status">
          Status
        </div>
      </div>
      <div
        className="workbench-page__rows-frame"
        data-stale={isShowingStaleResults ? 'true' : undefined}
      >
        {showInlineErrorNotice ? (
          <div className="workbench-page__notice">
            <div className="workbench-page__notice-text">
              {repositoryErrorMessage ?? saveErrorMessage ?? searchErrorMessage}
            </div>
            {searchErrorMessage ? (
              <button type="button" className="workbench-status__retry" onClick={onRetrySearch}>
                Retry
              </button>
            ) : null}
          </div>
        ) : null}
        {isShowingStaleResults ? (
          <div className="workbench-page__search-overlay" aria-hidden="true">
            <span className="spinner spinner--md" />
          </div>
        ) : null}
        {showNoResults ||
        showEmptyPrompt ||
        showSearchLoading ||
        showRepositoryLoading ||
        (!hasRows && (repositoryErrorMessage || saveErrorMessage || searchErrorMessage)) ? (
          <div className="workbench-page__rows">
            <div className="workbench-page__empty">
              <div className="workbench-page__empty-text hint">
                {repositoryErrorMessage ? (
                  repositoryErrorMessage
                ) : saveErrorMessage ? (
                  saveErrorMessage
                ) : searchErrorMessage ? (
                  <>
                    <span>{searchErrorMessage}</span>
                    <div className="workbench-page__empty-action">
                      <button
                        type="button"
                        className="workbench-status__retry"
                        onClick={onRetrySearch}
                      >
                        Retry
                      </button>
                    </div>
                  </>
                ) : showRepositoryLoading ? (
                  <>
                    <span className="workbench-page__empty-spinner" aria-hidden="true">
                      <span className="spinner" />
                    </span>
                    <span>Loading repositories…</span>
                  </>
                ) : showSearchLoading ? (
                  <>
                    <span className="workbench-page__empty-spinner" aria-hidden="true">
                      <span className="spinner" />
                    </span>
                    <span>Searching…</span>
                  </>
                ) : showNoResults ? (
                  'No results. Try a different search term, broaden your filters, or switch repositories/locales.'
                ) : canSearch ? (
                  'Searching…'
                ) : (
                  'Select repositories & locales. Pick at least one of each to get started.'
                )}
              </div>
            </div>
          </div>
        ) : (
          <VirtualList
            scrollRef={scrollElementRef}
            items={virtualItems}
            totalSize={totalSize}
            renderRow={(virtualRow) => {
              const row = rows[virtualRow.index];
              if (!row) {
                return null;
              }

              const isEditing = editingRowId === row.id;
              const useAssistedTranslationEditor = isVisibleTextEditorEnabled && isEditing;
              const useAssistedTranslationPreview = isVisibleTextEditorEnabled && !isEditing;
              const useAssistedSourcePreview = isVisibleTextEditorEnabled;
              const translationValue = isEditing ? editingValue : (row.translation ?? '');
              const mf2TermRequirementPreview = buildMf2TermRequirementPreview(
                row.source ?? '',
                translationValue,
                row.locale,
              );
              const translationDirection = isRtlLocale(row.locale) ? 'rtl' : 'ltr';
              const translationLocale = row.locale;
              const translationProtectedTokens = useAssistedTranslationEditor
                ? editingTextTokenGuard.protectedTokens
                : [];
              const translationProtectedDiagnostics = useAssistedTranslationEditor
                ? editingTextTokenGuard.diagnostics
                : [];
              const validateTranslationValue = useAssistedTranslationEditor
                ? editingTextTokenGuard.validateNextValue
                : undefined;
              const isStatusSaving = statusSavingRowIds.has(row.id);
              const isEdited = editedRowIds.has(row.id);
              const isStatusOpen = openStatusRowId === row.id;
              const isUnused = !row.isUsed;
              const hasActiveCollection = Boolean(activeCollectionName);
              const isInCollection = hasActiveCollection
                ? activeCollectionIds.has(row.tmTextUnitId)
                : false;
              const glossaryTarget = getGlossaryTargetForRow(row);
              const collectionButtonLabel =
                hasActiveCollection && isInCollection ? 'In collection' : 'Add to collection';
              const handleTranslationKeyDown = (event: TranslationTextEditorKeyDownEvent) => {
                if (!isEditing || !row.canEdit || isSaving) {
                  return;
                }
                if (isPrimaryActionShortcut(event)) {
                  event.preventDefault();
                  onSaveEditing();
                }
              };

              return {
                key: row.id,
                className: 'workbench-page__row',
                props: {
                  'data-index': virtualRow.index,
                  'data-workbench-row-id': row.id,
                  'data-status-open': isStatusOpen ? 'true' : undefined,
                  ref: getRowRef(row.id),
                },
                content: (
                  <>
                    <div className="workbench-page__cell workbench-page__cell--meta">
                      <div className="workbench-page__meta-link">
                        <span className="workbench-page__meta-id">{row.textUnitName}</span>
                        <button
                          type="button"
                          className="workbench-link-button"
                          onClick={(event) => {
                            event.stopPropagation();
                            openTextUnitLink(row);
                          }}
                          aria-label={`Open ${row.textUnitName} in workbench`}
                          title="Open in workbench"
                        >
                          ↗
                        </button>
                        {hasActiveCollection ? (
                          <button
                            type="button"
                            className={`workbench-collection__inline${isInCollection ? ' is-active' : ''}`}
                            onClick={(event) => {
                              event.stopPropagation();
                              if (isInCollection) {
                                onRemoveFromCollection(row.tmTextUnitId);
                              } else {
                                const scope = getRepositoryScope(row);
                                onAddToCollection(row.tmTextUnitId, scope?.repoId ?? null);
                              }
                            }}
                            title={`Add to ${activeCollectionName}`}
                          >
                            {collectionButtonLabel}
                          </button>
                        ) : null}
                      </div>
                      <div className="workbench-page__meta-link">
                        <span className="workbench-page__meta-asset">{row.assetPath ?? ''}</span>
                        {row.assetPath ? (
                          <button
                            type="button"
                            className="workbench-link-button"
                            onClick={(event) => {
                              event.stopPropagation();
                              openAssetLink(row);
                            }}
                            aria-label={`Open asset ${row.assetPath} in workbench`}
                            title="Open asset in workbench"
                          >
                            ↗
                          </button>
                        ) : null}
                        {glossaryTarget ? (
                          <button
                            type="button"
                            className="workbench-glossary-pill"
                            onClick={(event) => {
                              event.stopPropagation();
                              openGlossaryTerm(row, glossaryTarget);
                            }}
                            title={`Open ${row.textUnitName} in glossary ${glossaryTarget.glossaryName}`}
                          >
                            Glossary term
                          </button>
                        ) : null}
                      </div>
                      <div className="workbench-page__meta-link workbench-page__meta-link--stack">
                        <span className="workbench-page__repo-name">{row.repositoryName}</span>
                        {row.locations.length > 0 ? (
                          <span className="workbench-page__meta-location">
                            {row.locations.join(', ')}
                          </span>
                        ) : null}
                      </div>
                      {showDateMetadata ? <WorkbenchDateMetadata row={row} /> : null}
                    </div>
                    <div className="workbench-page__cell workbench-page__cell--source">
                      {useAssistedSourcePreview ? (
                        <VisibleTextRenderer
                          className="workbench-page__text-block workbench-page__source-text"
                          value={row.source ?? ''}
                          marksMode={translationMarksMode}
                          showProtectedTokens={showProtectedTokens}
                          spellCheck={false}
                          tokenMode="icu-html"
                        />
                      ) : (
                        <div className="workbench-page__text-block workbench-page__source-text">
                          {row.source ?? ''}
                        </div>
                      )}
                      <div className="workbench-page__text-block workbench-page__source-comment">
                        {row.comment ?? ''}
                      </div>
                    </div>
                    <div
                      className="workbench-page__cell workbench-page__cell--translation"
                      data-editing={isEditing ? 'true' : undefined}
                    >
                      {useAssistedTranslationPreview ? (
                        <VisibleTextRenderer
                          ariaLabel="Text editor"
                          className="workbench-page__translation-input"
                          value={translationValue}
                          disabled={!row.canEdit || isSaving}
                          lang={translationLocale}
                          dir={translationDirection}
                          marksMode={translationMarksMode}
                          showProtectedTokens={showProtectedTokens}
                          onFocus={
                            row.canEdit && !isSaving
                              ? () => onStartEditing(row.id, row.translation)
                              : undefined
                          }
                          spellCheck={true}
                          tokenMode="icu-html"
                        />
                      ) : (
                        <TranslationTextEditor
                          assisted={useAssistedTranslationEditor}
                          className="workbench-page__translation-input"
                          value={translationValue}
                          onFocus={() => {
                            if (!isEditing && row.canEdit) {
                              onStartEditing(row.id, row.translation);
                            }
                          }}
                          onChange={(nextValue) => {
                            if (isEditing) {
                              onChangeEditingValue(nextValue);
                            }
                          }}
                          onKeyDown={isEditing ? handleTranslationKeyDown : undefined}
                          controlBar={
                            useAssistedTranslationEditor
                              ? {
                                  marksMode: translationMarksMode,
                                  onChangeMarksMode: onChangeTranslationMarksMode,
                                  protectedTokenCount: translationProtectedTokens.length,
                                }
                              : undefined
                          }
                          disabled={!row.canEdit || isSaving}
                          readOnly={!isEditing || !row.canEdit || isSaving}
                          ref={isEditing ? translationInputRef : undefined}
                          marksMode={translationMarksMode}
                          protectedDiagnostics={translationProtectedDiagnostics}
                          protectedTokens={translationProtectedTokens}
                          spellCheck={true}
                          lang={translationLocale}
                          dir={translationDirection}
                          validateNextValue={validateTranslationValue}
                        />
                      )}
                      <WorkbenchMf2TermRequirementPreview
                        glossaryTarget={glossaryTarget}
                        locale={row.locale}
                        messageId={row.textUnitName}
                        preview={mf2TermRequirementPreview}
                        repositoryName={row.repositoryName}
                        sourceText={row.source}
                        tmTextUnitId={row.tmTextUnitId}
                      />
                      <div className="workbench-page__translation-footer">
                        {isEdited ? (
                          <button
                            type="button"
                            className="workbench-page__edited-indicator"
                            onClick={() => onShowDiff(row.id)}
                          >
                            <span className="workbench-page__edited-dot" aria-hidden="true" />
                            <span className="workbench-page__edited-text">Edited</span>
                          </button>
                        ) : null}
                        <div className="workbench-page__translation-actions">
                          {isEditing ? (
                            <>
                              <button
                                type="button"
                                className="workbench-page__translation-button workbench-page__translation-button--primary"
                                onClick={(event) => {
                                  event.stopPropagation();
                                  onSaveEditing();
                                }}
                                disabled={!canSaveEditing || isSaving}
                                title={
                                  !canSaveEditing
                                    ? 'Already accepted. Edit the translation to save again.'
                                    : undefined
                                }
                              >
                                <span>Accept</span>
                              </button>
                              <button
                                type="button"
                                className="workbench-page__translation-button"
                                onClick={(event) => {
                                  event.stopPropagation();
                                  onCancelEditing();
                                }}
                                disabled={isSaving}
                              >
                                Cancel
                              </button>
                            </>
                          ) : null}
                          <button
                            type="button"
                            className="workbench-page__translation-button"
                            onClick={(event) => {
                              event.stopPropagation();
                              openTextUnitDetailInNewWindow(row);
                            }}
                          >
                            Details
                          </button>
                        </div>
                      </div>
                    </div>
                    <div className="workbench-page__cell workbench-page__cell--locale">
                      <LocalePill bcp47Tag={row.locale} labelMode="tag" />
                    </div>
                    <div className="workbench-page__cell workbench-page__cell--status">
                      <div className="workbench-page__status-stack">
                        {row.translation === null ? (
                          <span className="workbench-page__status-empty">—</span>
                        ) : (
                          <PillDropdown
                            value={row.status}
                            options={statusOptions.map((status) => ({
                              value: status,
                              label: status,
                            }))}
                            onChange={(next) => onChangeStatus(row.id, next)}
                            disabled={Boolean(editingRowId) || isStatusSaving || !row.canEdit}
                            aria-label="Translation status"
                            isOpen={isStatusOpen}
                            onOpenChange={(nextOpen) => {
                              setOpenStatusRowId((current) => {
                                if (nextOpen) {
                                  return row.id;
                                }
                                return current === row.id ? null : current;
                              });
                            }}
                          />
                        )}
                        {isUnused ? (
                          <span
                            className="workbench-page__unused-pill"
                            aria-label="Unused text unit"
                          >
                            Unused
                          </span>
                        ) : null}
                      </div>
                    </div>
                  </>
                ),
              };
            }}
          />
        )}
      </div>
    </div>
  );
}

type Mf2TermRequirementPreviewGroup = Mf2TermBindingManifestGroup;

type Mf2TermRequirementPreview =
  | {
      groups: Mf2TermRequirementPreviewGroup[];
      kind: 'success';
    }
  | {
      error: string;
      kind: 'error';
    }
  | null;

function buildMf2TermRequirementPreview(
  source: string,
  target: string,
  locale: string,
): Mf2TermRequirementPreview {
  try {
    const sourceUsages = extractMf2TermRequirements(source, locale);
    const targetUsages = extractMf2TermRequirements(target, locale);
    const possibleGroups: Array<Mf2TermRequirementPreviewGroup | null> = [
      sourceUsages.length > 0 ? { label: 'Source', message: source, usages: sourceUsages } : null,
      targetUsages.length > 0 ? { label: 'Target', message: target, usages: targetUsages } : null,
    ];
    const groups = possibleGroups.filter(
      (group): group is Mf2TermRequirementPreviewGroup => group !== null,
    );

    return groups.length > 0 ? { kind: 'success', groups } : null;
  } catch (error) {
    return {
      error: error instanceof Error ? error.message : 'Could not inspect MF2 term requirements.',
      kind: 'error',
    };
  }
}

function WorkbenchMf2TermRequirementPreview({
  glossaryTarget,
  locale,
  messageId,
  preview,
  repositoryName,
  sourceText,
  tmTextUnitId,
}: {
  glossaryTarget: GlossaryWorkbenchTarget | undefined;
  locale: string;
  messageId: string;
  preview: Mf2TermRequirementPreview;
  repositoryName: string;
  sourceText: string;
  tmTextUnitId: number;
}) {
  const [isOpen, setIsOpen] = useState(false);
  const [selectedTermIdsByArgument, setSelectedTermIdsByArgument] = useState<
    Record<string, string>
  >({});
  const [touchedTermBindingArguments, setTouchedTermBindingArguments] = useState<
    Record<string, true>
  >({});
  const [runtimeVariables, setRuntimeVariables] = useState<Record<string, string>>({});
  useEffect(() => {
    setSelectedTermIdsByArgument((current) => (Object.keys(current).length === 0 ? current : {}));
    setTouchedTermBindingArguments((current) => (Object.keys(current).length === 0 ? current : {}));
    setRuntimeVariables((current) => (Object.keys(current).length === 0 ? current : {}));
  }, [glossaryTarget?.glossaryId, locale, messageId, repositoryName, tmTextUnitId]);
  const argumentNames = useMemo(
    () => (preview?.kind === 'success' ? uniqueMf2TermArgumentsForGroups(preview.groups) : []),
    [preview],
  );
  const runtimeVariableNames = useMemo(
    () =>
      preview?.kind === 'success' ? uniqueMf2TermRuntimeVariablesForGroups(preview.groups) : [],
    [preview],
  );
  useEffect(() => {
    setSelectedTermIdsByArgument((current) => pruneRecordToKeys(current, argumentNames));
  }, [argumentNames]);
  useEffect(() => {
    setTouchedTermBindingArguments((current) => pruneRecordToKeys(current, argumentNames));
  }, [argumentNames]);
  useEffect(() => {
    setRuntimeVariables((current) => pruneRecordToKeys(current, runtimeVariableNames));
  }, [runtimeVariableNames]);
  const manifestTermIdsByArgument = useMemo(() => {
    const termIdsByArgument: Record<string, string[]> = {};
    for (const argument of argumentNames) {
      const termId = selectedTermIdsByArgument[argument];
      termIdsByArgument[argument] = termId ? [termId] : [];
    }
    return termIdsByArgument;
  }, [argumentNames, selectedTermIdsByArgument]);
  const bindingManifest = useMemo(
    () =>
      preview?.kind === 'success'
        ? buildMf2TermBindingManifest(messageId, locale, preview.groups, {
            termIdsByArgument: manifestTermIdsByArgument,
          })
        : null,
    [locale, manifestTermIdsByArgument, messageId, preview],
  );
  const glossaryMatchesQuery = useQuery({
    queryKey: [
      'workbench-mf2-term-binding-candidates',
      glossaryTarget?.glossaryId,
      repositoryName,
      locale,
      sourceText,
      tmTextUnitId,
    ],
    queryFn: async () => {
      const response = await matchGlossaryTerms({
        repositoryName,
        localeTag: locale,
        sourceText,
        excludeTmTextUnitId: tmTextUnitId,
      });
      return sortGlossaryMatches(filterSelfGlossaryMatches(response.matchedTerms, tmTextUnitId));
    },
    enabled:
      isOpen &&
      Boolean(glossaryTarget) &&
      preview?.kind === 'success' &&
      Boolean(repositoryName.trim()) &&
      Boolean(sourceText.trim()),
    staleTime: 30_000,
  });
  const bindingCandidates = useMemo(
    () => mf2TermBindingCandidates(glossaryMatchesQuery.data ?? []),
    [glossaryMatchesQuery.data],
  );
  useEffect(() => {
    setSelectedTermIdsByArgument((current) =>
      applyDefaultMf2TermBindings(
        argumentNames,
        bindingCandidates,
        current,
        touchedTermBindingArguments,
      ),
    );
  }, [argumentNames, bindingCandidates, touchedTermBindingArguments]);
  const handleTermIdChange = useCallback((argument: string, termId: string) => {
    setTouchedTermBindingArguments((current) =>
      current[argument] ? current : { ...current, [argument]: true },
    );
    setSelectedTermIdsByArgument((current) => {
      const next = { ...current };
      if (termId) {
        next[argument] = termId;
      } else {
        delete next[argument];
      }
      return next;
    });
  }, []);
  const handleRuntimeVariableChange = useCallback((name: string, value: string) => {
    setRuntimeVariables((current) => ({
      ...current,
      [name]: value,
    }));
  }, []);
  const bindingReportQuery = useQuery({
    queryKey: [
      'workbench-mf2-term-binding-report',
      glossaryTarget?.glossaryId,
      locale,
      bindingManifest,
    ],
    queryFn: () => {
      if (!glossaryTarget || !bindingManifest) {
        throw new Error('Missing glossary binding report input.');
      }
      return reportInflectionBindingManifest(glossaryTarget.glossaryId, locale, bindingManifest);
    },
    enabled: Boolean(glossaryTarget && bindingManifest),
    staleTime: 30_000,
  });
  const missingRuntimeVariableNames = useMemo(
    () => missingMf2RuntimeVariables(runtimeVariableNames, runtimeVariables),
    [runtimeVariableNames, runtimeVariables],
  );
  const renderVariables = useMemo(
    () => buildMf2RenderVariables(runtimeVariableNames, runtimeVariables),
    [runtimeVariableNames, runtimeVariables],
  );
  const hasBoundAllArguments = areMf2TermArgumentsBound(argumentNames, selectedTermIdsByArgument);
  const renderPreviewQuery = useQuery({
    queryKey: [
      'workbench-mf2-term-binding-render',
      glossaryTarget?.glossaryId,
      locale,
      bindingManifest,
      renderVariables,
    ],
    queryFn: () => {
      if (!glossaryTarget || !bindingManifest) {
        throw new Error('Missing glossary binding render input.');
      }
      return renderInflectionBindingManifest(
        glossaryTarget.glossaryId,
        locale,
        bindingManifest,
        renderVariables,
      );
    },
    enabled:
      isOpen &&
      Boolean(glossaryTarget && bindingManifest) &&
      hasBoundAllArguments &&
      bindingReportQuery.data?.summary.diagnostics === 0 &&
      missingRuntimeVariableNames.length === 0,
    staleTime: 30_000,
  });

  if (!preview) {
    return null;
  }

  if (preview.kind === 'error') {
    return (
      <div className="workbench-page__mf2-term-preview is-error" role="status">
        MF2 term requirements: {preview.error}
      </div>
    );
  }

  return (
    <details
      className="workbench-page__mf2-term-preview"
      onToggle={(event) => setIsOpen(event.currentTarget.open)}
    >
      <summary>{formatMf2TermRequirementPreviewSummary(preview.groups)}</summary>
      <div className="workbench-page__mf2-term-preview-body">
        {preview.groups.map((group) => (
          <div key={group.label} className="workbench-page__mf2-term-preview-group">
            <div className="workbench-page__mf2-term-preview-label">{group.label}</div>
            <ul className="workbench-page__mf2-term-preview-list">
              {group.usages.map((usage) => (
                <li key={`${group.label}:${usage.start}:${usage.end}`}>
                  <span className="workbench-page__mf2-term-preview-argument">
                    ${usage.argument}
                  </span>
                  {formatMf2TermOptions(usage.options) ? (
                    <span> {formatMf2TermOptions(usage.options)}</span>
                  ) : null}
                  <span>: {usage.requirements.join(', ')}</span>
                </li>
              ))}
            </ul>
          </div>
        ))}
      </div>
      {glossaryTarget ? (
        <>
          <WorkbenchMf2TermBindingResolver
            argumentNames={argumentNames}
            candidates={bindingCandidates}
            error={glossaryMatchesQuery.error}
            isLoading={glossaryMatchesQuery.isLoading}
            selectedTermIdsByArgument={selectedTermIdsByArgument}
            onChange={handleTermIdChange}
          />
          <WorkbenchMf2TermBindingReportStatus
            glossaryName={glossaryTarget.glossaryName}
            report={bindingReportQuery.data}
            isLoading={bindingReportQuery.isLoading}
            error={bindingReportQuery.error}
          />
          <WorkbenchMf2TermRuntimeVariables
            onChange={handleRuntimeVariableChange}
            runtimeVariables={runtimeVariables}
            variableNames={runtimeVariableNames}
          />
          <WorkbenchMf2TermRenderPreview
            error={renderPreviewQuery.error}
            isLoading={renderPreviewQuery.isLoading}
            missingVariableNames={missingRuntimeVariableNames}
            render={renderPreviewQuery.data}
            report={bindingReportQuery.data}
          />
        </>
      ) : null}
    </details>
  );
}

type Mf2TermBindingCandidate = {
  label: string;
  termId: string;
};

function WorkbenchMf2TermBindingResolver({
  argumentNames,
  candidates,
  error,
  isLoading,
  onChange,
  selectedTermIdsByArgument,
}: {
  argumentNames: string[];
  candidates: Mf2TermBindingCandidate[];
  error: Error | null;
  isLoading: boolean;
  onChange: (argument: string, termId: string) => void;
  selectedTermIdsByArgument: Record<string, string>;
}) {
  if (argumentNames.length === 0) {
    return null;
  }

  return (
    <div className="workbench-page__mf2-term-binding-resolver">
      <div className="workbench-page__mf2-term-binding-resolver-title">
        Bind MF2 arguments to glossary term IDs
      </div>
      {isLoading ? (
        <div className="workbench-page__mf2-term-binding-resolver-state">
          Loading glossary term candidates…
        </div>
      ) : error ? (
        <div className="workbench-page__mf2-term-binding-resolver-state is-error">
          {error.message}
        </div>
      ) : candidates.length === 0 ? (
        <div className="workbench-page__mf2-term-binding-resolver-state">
          No matched glossary term IDs found for this source.
        </div>
      ) : null}
      <div className="workbench-page__mf2-term-binding-rows">
        {argumentNames.map((argument) => (
          <label key={argument} className="workbench-page__mf2-term-binding-row">
            <span>${argument}</span>
            <select
              aria-label={`Glossary term ID for ${argument}`}
              className="workbench-page__mf2-term-binding-select"
              value={selectedTermIdsByArgument[argument] ?? ''}
              onChange={(event) => onChange(argument, event.target.value)}
            >
              <option value="">Unbound</option>
              {candidates.map((candidate) => (
                <option key={candidate.termId} value={candidate.termId}>
                  {candidate.label}
                </option>
              ))}
            </select>
          </label>
        ))}
      </div>
    </div>
  );
}

function WorkbenchMf2TermRuntimeVariables({
  onChange,
  runtimeVariables,
  variableNames,
}: {
  onChange: (name: string, value: string) => void;
  runtimeVariables: Record<string, string>;
  variableNames: string[];
}) {
  if (variableNames.length === 0) {
    return null;
  }

  return (
    <div className="workbench-page__mf2-term-runtime">
      <div className="workbench-page__mf2-term-runtime-title">Runtime variables</div>
      <div className="workbench-page__mf2-term-runtime-rows">
        {variableNames.map((name) => (
          <label key={name} className="workbench-page__mf2-term-runtime-row">
            <span>${name}</span>
            <input
              aria-label={`Runtime value for ${name}`}
              className="workbench-page__mf2-term-runtime-input"
              value={runtimeVariables[name] ?? ''}
              onChange={(event) => onChange(name, event.target.value)}
              placeholder="value"
            />
          </label>
        ))}
      </div>
    </div>
  );
}

function WorkbenchMf2TermRenderPreview({
  error,
  isLoading,
  missingVariableNames,
  render,
  report,
}: {
  error: Error | null;
  isLoading: boolean;
  missingVariableNames: string[];
  render: ApiInflectionBindingRenderResponse | undefined;
  report: ApiInflectionBindingReport | undefined;
}) {
  if (!report || report.summary.diagnostics > 0) {
    return null;
  }
  if (missingVariableNames.length > 0) {
    return (
      <div className="workbench-page__mf2-term-render-preview">
        MF2 render preview: enter runtime values for{' '}
        {formatMf2RuntimeVariableNames(missingVariableNames)}.
      </div>
    );
  }
  if (isLoading) {
    return (
      <div className="workbench-page__mf2-term-render-preview">MF2 render preview: rendering…</div>
    );
  }
  if (error) {
    return (
      <div className="workbench-page__mf2-term-render-preview is-error">
        MF2 render preview: {error.message}
      </div>
    );
  }
  if (!render) {
    return null;
  }

  return (
    <div className="workbench-page__mf2-term-render-preview">
      <div className="workbench-page__mf2-term-render-preview-title">MF2 render preview</div>
      <div className="workbench-page__mf2-term-render-preview-messages">
        {Object.entries(render.messages).map(([messageId, value]) => (
          <div key={messageId} className="workbench-page__mf2-term-render-preview-message">
            <span className="workbench-page__mf2-term-render-preview-label">
              {formatMf2RenderMessageLabel(messageId)}
            </span>
            <span>{value}</span>
          </div>
        ))}
      </div>
    </div>
  );
}

function uniqueMf2TermArgumentsForGroups(groups: readonly Mf2TermRequirementPreviewGroup[]) {
  const argumentsInOrder: string[] = [];
  const seen = new Set<string>();
  for (const group of groups) {
    for (const argument of uniqueMf2TermArguments(group.usages)) {
      if (!seen.has(argument)) {
        seen.add(argument);
        argumentsInOrder.push(argument);
      }
    }
  }
  return argumentsInOrder;
}

function uniqueMf2TermRuntimeVariablesForGroups(groups: readonly Mf2TermRequirementPreviewGroup[]) {
  const variablesInOrder: string[] = [];
  const seen = new Set<string>();
  for (const group of groups) {
    for (const usage of group.usages) {
      for (const variableName of mf2RuntimeVariableNamesForUsage(usage)) {
        if (!seen.has(variableName)) {
          seen.add(variableName);
          variablesInOrder.push(variableName);
        }
      }
    }
  }
  return variablesInOrder;
}

function missingMf2RuntimeVariables(
  variableNames: string[],
  runtimeVariables: Record<string, string>,
) {
  return variableNames.filter((name) => !(runtimeVariables[name] ?? '').trim());
}

function buildMf2RenderVariables(
  variableNames: string[],
  runtimeVariables: Record<string, string>,
) {
  const variables: Record<string, string> = {};
  for (const name of variableNames) {
    const value = (runtimeVariables[name] ?? '').trim();
    if (value) {
      variables[name] = value;
    }
  }
  return variables;
}

function areMf2TermArgumentsBound(
  argumentNames: string[],
  selectedTermIdsByArgument: Record<string, string>,
) {
  return argumentNames.every((argument) => Boolean(selectedTermIdsByArgument[argument]?.trim()));
}

function pruneRecordToKeys<T>(current: Record<string, T>, keysToKeep: readonly string[]) {
  const allowedKeys = new Set(keysToKeep);
  let changed = false;
  const next: Record<string, T> = {};
  for (const [key, value] of Object.entries(current)) {
    if (allowedKeys.has(key)) {
      next[key] = value;
    } else {
      changed = true;
    }
  }
  return changed ? next : current;
}

function mf2TermBindingCandidates(matches: readonly ApiMatchedGlossaryTerm[]) {
  const candidates: Mf2TermBindingCandidate[] = [];
  const seen = new Set<string>();
  for (const match of matches) {
    const termId = match.termKey?.trim();
    if (!termId || seen.has(termId)) {
      continue;
    }
    seen.add(termId);
    candidates.push({
      termId,
      label: formatMf2TermBindingCandidateLabel(termId, match),
    });
  }
  return candidates;
}

function applyDefaultMf2TermBindings(
  argumentNames: readonly string[],
  candidates: readonly Mf2TermBindingCandidate[],
  selectedTermIdsByArgument: Record<string, string>,
  touchedTermBindingArguments: Record<string, true>,
) {
  if (argumentNames.length !== 1 || candidates.length !== 1) {
    return selectedTermIdsByArgument;
  }

  const argument = argumentNames[0];
  if (
    !argument ||
    selectedTermIdsByArgument[argument]?.trim() ||
    touchedTermBindingArguments[argument]
  ) {
    return selectedTermIdsByArgument;
  }

  return {
    ...selectedTermIdsByArgument,
    [argument]: candidates[0].termId,
  };
}

function formatMf2TermBindingCandidateLabel(termId: string, match: ApiMatchedGlossaryTerm) {
  const source = match.source.trim();
  const target = match.target?.trim();
  if (source && target) {
    return `${termId} — ${source} → ${target}`;
  }
  if (source) {
    return `${termId} — ${source}`;
  }
  return termId;
}

function WorkbenchMf2TermBindingReportStatus({
  error,
  glossaryName,
  isLoading,
  report,
}: {
  error: Error | null;
  glossaryName: string;
  isLoading: boolean;
  report: ApiInflectionBindingReport | undefined;
}) {
  if (isLoading) {
    return (
      <div className="workbench-page__mf2-term-binding-report">
        MF2 term bindings: checking {glossaryName}…
      </div>
    );
  }
  if (error) {
    return (
      <div className="workbench-page__mf2-term-binding-report is-error">
        MF2 term bindings: {error.message}
      </div>
    );
  }
  if (!report) {
    return null;
  }

  if (report.summary.diagnostics === 0) {
    return (
      <div className="workbench-page__mf2-term-binding-report">
        MF2 term bindings: renderable for {glossaryName}.
      </div>
    );
  }

  return (
    <div className="workbench-page__mf2-term-binding-report is-warning">
      MF2 term bindings: {formatMf2BindingDiagnostics(report)}.
    </div>
  );
}

function formatMf2TermRequirementPreviewSummary(groups: Mf2TermRequirementPreviewGroup[]) {
  return `MF2 term requirements: ${groups
    .map((group) => `${group.label.toLowerCase()} ${formatMf2TermArguments(group.usages)}`)
    .join(' · ')}`;
}

function formatMf2TermArguments(usages: readonly Mf2TermUsageRequirement[]) {
  return uniqueMf2TermArguments(usages)
    .map((argument) => `$${argument}`)
    .join(', ');
}

function formatMf2TermOptions(options: Record<string, string>) {
  const entries = Object.entries(options);
  if (entries.length === 0) {
    return '';
  }
  return `(${entries.map(([key, value]) => `${key}=${value}`).join(', ')})`;
}

function formatMf2BindingDiagnostics(report: ApiInflectionBindingReport) {
  const counts = report.diagnostics.reduce(
    (acc, diagnostic) => {
      acc[diagnostic.status] = (acc[diagnostic.status] ?? 0) + 1;
      return acc;
    },
    {} as Record<string, number>,
  );
  const summary = Object.entries(counts)
    .map(([status, count]) => `${count} ${formatMf2BindingStatus(status)}`)
    .join(', ');
  const unsupportedRuntimeArguments = uniqueMf2BindingDiagnosticArguments(report, [
    'unsupported-locale-runtime-term-inflection',
  ]);
  const unboundArguments = uniqueMf2BindingDiagnosticArguments(report, [
    'missing',
    'ambiguous',
    'unknown',
  ]);
  if (unsupportedRuntimeArguments.length > 0) {
    const details = [
      `remove MF2 :term options for ${formatMf2BindingArgumentNames(
        unsupportedRuntimeArguments,
      )} or use a locale with a checked V0 runtime term-form pack`,
    ];
    if (unboundArguments.length > 0) {
      details.push(
        `bind MF2 arguments ${formatMf2BindingArgumentNames(
          unboundArguments,
        )} to valid glossary term IDs before rendering`,
      );
    }
    return `${summary}; ${details.join('; ')}`;
  }
  if (unboundArguments.length === 0) {
    return summary;
  }
  return `${summary} for ${formatMf2BindingArgumentNames(unboundArguments)}; bind MF2 arguments to valid glossary term IDs before rendering`;
}

function formatMf2BindingStatus(status: string) {
  if (status === 'unsupported-locale-runtime-term-inflection') {
    return 'unsupported by current V0 locale runtime';
  }
  return status;
}

function uniqueMf2BindingDiagnosticArguments(
  report: ApiInflectionBindingReport,
  statuses: ApiInflectionBindingReport['diagnostics'][number]['status'][],
) {
  const wantedStatuses = new Set(statuses);
  const argumentsInOrder: string[] = [];
  const seen = new Set<string>();
  for (const diagnostic of report.diagnostics) {
    if (!wantedStatuses.has(diagnostic.status) || seen.has(diagnostic.argument)) {
      continue;
    }
    seen.add(diagnostic.argument);
    argumentsInOrder.push(diagnostic.argument);
  }
  return argumentsInOrder;
}

function formatMf2BindingArgumentNames(argumentsInOrder: string[]) {
  return argumentsInOrder.map((argument) => `$${argument}`).join(', ');
}

function formatMf2RuntimeVariableNames(variableNames: string[]) {
  return variableNames.map((name) => `$${name}`).join(', ');
}

function formatMf2RenderMessageLabel(messageId: string) {
  if (messageId.endsWith('.source')) {
    return 'Source';
  }
  if (messageId.endsWith('.target')) {
    return 'Target';
  }
  return messageId;
}

function WorkbenchDateMetadata({ row }: { row: WorkbenchRow }) {
  const dates = [
    row.sourceCreatedDate ? { label: 'Created', value: row.sourceCreatedDate } : null,
    row.translationCreatedDate ? { label: 'Translated', value: row.translationCreatedDate } : null,
  ].filter((item): item is { label: string; value: string } => item !== null);

  if (dates.length === 0) {
    return null;
  }

  return (
    <div className="workbench-page__date-meta" aria-label="Text unit dates">
      {dates.map((date) => {
        const displayValue = formatLocalDateTime(date.value, date.value);
        const tooltip = getLocalAndUtcDateTimeTooltip(date.value) ?? displayValue;
        return (
          <span key={date.label} className="workbench-page__date-item">
            <span className="workbench-page__date-label">{date.label}</span>
            <time dateTime={date.value} title={tooltip} className="workbench-page__date-value">
              {displayValue}
            </time>
          </span>
        );
      })}
    </div>
  );
}

type DiffModalProps = {
  data: WorkbenchDiffModalData | null;
  onClose: () => void;
};

export function DiffModal({ data, onClose }: DiffModalProps) {
  if (!data) {
    return null;
  }

  const beforeTarget = data.baselineTarget ?? '';
  const afterTarget = data.latestTarget ?? '';
  const beforeStatus = data.baselineStatusLabel;
  const afterStatus = data.latestStatusLabel;

  return (
    <Modal open size="xl" ariaLabel="Edit diff" onClose={onClose} closeOnBackdrop>
      <div className="modal__header workbench-diff-modal__header">
        <div className="modal__title workbench-diff-modal__title">Changes</div>
        <button type="button" className="modal__button" onClick={onClose}>
          Close
        </button>
      </div>

      <div className="workbench-diff-modal__meta">
        <div className="workbench-diff-modal__meta-line">
          <span className="workbench-diff-modal__meta-key">String</span>
          <span className="workbench-diff-modal__meta-value">{data.textUnitName}</span>
        </div>
        <div className="workbench-diff-modal__meta-line">
          <span className="workbench-diff-modal__meta-key">Locale</span>
          <span className="workbench-diff-modal__meta-value">{data.locale}</span>
        </div>
        <div className="workbench-diff-modal__meta-line">
          <span className="workbench-diff-modal__meta-key">Repo</span>
          <span className="workbench-diff-modal__meta-value">{data.repositoryName}</span>
        </div>
        {data.assetPath ? (
          <div className="workbench-diff-modal__meta-line">
            <span className="workbench-diff-modal__meta-key">Asset</span>
            <span className="workbench-diff-modal__meta-value">{data.assetPath}</span>
          </div>
        ) : null}
      </div>

      <div className="workbench-diff-modal__grid">
        <div className="workbench-diff-modal__column">
          <div className="workbench-diff-modal__column-title">Before</div>
          <div className="workbench-diff-modal__field">
            <div className="workbench-diff-modal__field-label">Variant</div>
            <div className="workbench-diff-modal__field-value">{data.baselineVariantId ?? '—'}</div>
          </div>
          <div className="workbench-diff-modal__field">
            <div className="workbench-diff-modal__field-label">Status</div>
            <div className="workbench-diff-modal__field-value">{beforeStatus}</div>
          </div>
          <div className="workbench-diff-modal__field">
            <div className="workbench-diff-modal__field-label">Translation</div>
            <pre className="workbench-diff-modal__code">{beforeTarget}</pre>
          </div>
        </div>

        <div className="workbench-diff-modal__column">
          <div className="workbench-diff-modal__column-title">After</div>
          <div className="workbench-diff-modal__field">
            <div className="workbench-diff-modal__field-label">Variant</div>
            <div className="workbench-diff-modal__field-value">{data.savedVariantId ?? '—'}</div>
          </div>
          <div className="workbench-diff-modal__field">
            <div className="workbench-diff-modal__field-label">Status</div>
            <div className="workbench-diff-modal__field-value">{afterStatus}</div>
          </div>
          <div className="workbench-diff-modal__field">
            <div className="workbench-diff-modal__field-label">Translation</div>
            <pre className="workbench-diff-modal__code">{afterTarget}</pre>
          </div>
        </div>
      </div>
    </Modal>
  );
}
