import type { CSSProperties, KeyboardEvent } from 'react';
import { useCallback, useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { Link } from 'react-router-dom';

import type { ApiGlossaryTerm } from '../../api/glossaries';
import { ConfirmModal } from '../../components/ConfirmModal';
import { getAnchoredDropdownPanelStyle } from '../../components/dropdownPosition';
import { LocaleMultiSelect } from '../../components/LocaleMultiSelect';
import { NumericPresetDropdown } from '../../components/NumericPresetDropdown';
import { PillDropdown } from '../../components/PillDropdown';
import { SingleSelectDropdown } from '../../components/SingleSelectDropdown';

type SelectOption = {
  value: string;
  label: string;
};

type LocaleOption = {
  tag: string;
  label: string;
};

type Props = {
  canManageTerms: boolean;
  searchDraft: string;
  onChangeSearch: (value: string) => void;
  localeOptions: LocaleOption[];
  selectedLocaleTags: string[];
  onChangeSelectedLocaleTags: (next: string[]) => void;
  statusFilterOptions: SelectOption[];
  selectedStatusFilter: string | null;
  onChangeSelectedStatusFilter: (value: string | null) => void;
  onOpenExtract: () => void;
  onOpenCreate: () => void;
  canImport: boolean;
  onOpenImport: () => void;
  canExport: boolean;
  onOpenExport: () => void;
  termsTotalCount: number;
  visibleTermsCount: number;
  termsLimit: number;
  onChangeTermsLimit: (value: number) => void;
  termsLimitOptions: number[];
  visibleLocaleColumnLimit: number;
  onChangeVisibleLocaleColumnLimit: (value: number) => void;
  visibleLocaleColumnLimitOptions: number[];
  localeColumnSummary: string | null;
  selectedTermIdsCount: number;
  onOpenBatch: () => void;
  onClearSelection: () => void;
  statusNotice: {
    kind: 'success' | 'error';
    message: string;
  } | null;
  isLoading: boolean;
  errorMessage: string | null;
  terms: ApiGlossaryTerm[];
  displayedLocaleTags: string[];
  allVisibleSelected: boolean;
  onToggleSelectAll: () => void;
  onOpenEditTerm: (term: ApiGlossaryTerm) => void;
  onOpenInlineTranslationEdit: (term: ApiGlossaryTerm) => void;
  activeTermId: number | null;
  selectedTermIds: number[];
  onToggleTermSelection: (tmTextUnitId: number, checked: boolean) => void;
  getWorkbenchHref: (tmTextUnitId: number, localeTags?: string[]) => string;
  getWorkbenchState: (tmTextUnitId: number, localeTags?: string[]) => unknown;
  statusOptions: string[];
  getStatusLabel: (status: string) => string;
  getTermTypeLabel: (termType: string) => string;
  getEnforcementLabel: (enforcement: string) => string;
  isChangingStatus: boolean;
  openStatusTermId: number | null;
  onOpenStatusTermIdChange: (tmTextUnitId: number, nextOpen: boolean) => void;
  onChangeTermStatus: (term: ApiGlossaryTerm, status: string) => void;
  canEditTranslationLocale: (localeTag: string) => boolean;
  savingTranslationKey: string | null;
  onSaveTermTranslation: (
    term: ApiGlossaryTerm,
    localeTag: string,
    value: { target: string; targetComment: string },
  ) => Promise<void>;
};

const getTranslation = (term: ApiGlossaryTerm, localeTag: string) =>
  term.translations.find((translation) => translation.localeTag === localeTag);

const getTranslationKey = (tmTextUnitId: number, localeTag: string) =>
  `${tmTextUnitId}:${localeTag.toLowerCase()}`;

type PendingEditExit =
  | { type: 'term'; term: ApiGlossaryTerm }
  | { type: 'translation'; term: ApiGlossaryTerm; localeTag: string; target: string };

const AUTO_VISIBLE_LOCALE_COLUMNS_CAP = 5;

function getSourceColumnWidth(localeColumnCount: number) {
  if (localeColumnCount <= 0) {
    return 'minmax(18rem, 1fr)';
  }
  if (localeColumnCount === 1) {
    return 'minmax(16rem, 0.9fr)';
  }
  if (localeColumnCount === 2) {
    return 'minmax(15rem, 0.8fr)';
  }
  if (localeColumnCount === 3) {
    return 'minmax(14rem, 0.7fr)';
  }
  return 'minmax(13rem, 0.6fr)';
}

function getTableGridTemplate(canManageTerms: boolean, localeColumnCount: number) {
  const checkboxTrack = canManageTerms ? '2.75rem ' : '';
  const sourceTrack = getSourceColumnWidth(localeColumnCount);
  const statusTrack = '9.75rem';
  const localeTracks =
    localeColumnCount > 0 ? ` repeat(${localeColumnCount}, minmax(12rem, 1fr))` : '';
  return `${checkboxTrack}${sourceTrack} ${statusTrack}${localeTracks}`;
}

function GlossaryPrimaryActionMenu({
  onOpenCreate,
  onOpenExtract,
}: {
  onOpenCreate: () => void;
  onOpenExtract: () => void;
}) {
  const [isOpen, setIsOpen] = useState(false);
  const buttonRef = useRef<HTMLButtonElement | null>(null);
  const panelRef = useRef<HTMLDivElement | null>(null);
  const [panelStyle, setPanelStyle] = useState<CSSProperties>();

  const updatePanelPosition = useCallback(() => {
    if (!buttonRef.current) {
      return;
    }
    const rect = buttonRef.current.getBoundingClientRect();
    setPanelStyle(
      getAnchoredDropdownPanelStyle({
        rect,
        align: 'right',
        maxWidth: Math.min(260, window.innerWidth - 32),
      }),
    );
  }, []);

  useEffect(() => {
    if (!isOpen) {
      return;
    }
    const handlePointerDown = (event: PointerEvent) => {
      const target = event.target as Node;
      if (!buttonRef.current?.contains(target) && !panelRef.current?.contains(target)) {
        setIsOpen(false);
      }
    };
    const handleReposition = () => updatePanelPosition();

    updatePanelPosition();
    window.addEventListener('pointerdown', handlePointerDown);
    window.addEventListener('resize', handleReposition);
    window.addEventListener('scroll', handleReposition, true);
    return () => {
      window.removeEventListener('pointerdown', handlePointerDown);
      window.removeEventListener('resize', handleReposition);
      window.removeEventListener('scroll', handleReposition, true);
    };
  }, [isOpen, updatePanelPosition]);

  const runAction = (action: () => void) => {
    setIsOpen(false);
    action();
  };

  return (
    <div className="glossary-term-admin__primary-action">
      <button
        type="button"
        className="settings-button settings-button--primary glossary-term-admin__primary-action-button"
        onClick={() => {
          updatePanelPosition();
          setIsOpen((previous) => !previous);
        }}
        aria-expanded={isOpen}
        aria-haspopup="menu"
        ref={buttonRef}
      >
        <span>Add term</span>
        <span className="glossary-term-admin__primary-action-chevron" aria-hidden="true" />
      </button>
      {isOpen
        ? createPortal(
            <div
              className="chip-dropdown__panel glossary-term-admin__primary-action-panel"
              role="menu"
              ref={panelRef}
              style={panelStyle}
            >
              <button
                type="button"
                className="glossary-term-admin__primary-action-option is-primary"
                onClick={() => runAction(onOpenCreate)}
              >
                Add term
              </button>
              <button
                type="button"
                className="glossary-term-admin__primary-action-option"
                onClick={() => runAction(onOpenExtract)}
              >
                Extract candidates
              </button>
            </div>,
            document.body,
          )
        : null}
    </div>
  );
}

export function GlossaryTermsListView({
  canManageTerms,
  searchDraft,
  onChangeSearch,
  localeOptions,
  selectedLocaleTags,
  onChangeSelectedLocaleTags,
  statusFilterOptions,
  selectedStatusFilter,
  onChangeSelectedStatusFilter,
  onOpenExtract,
  onOpenCreate,
  canImport,
  onOpenImport,
  canExport,
  onOpenExport,
  termsTotalCount,
  visibleTermsCount,
  termsLimit,
  onChangeTermsLimit,
  termsLimitOptions,
  visibleLocaleColumnLimit,
  onChangeVisibleLocaleColumnLimit,
  visibleLocaleColumnLimitOptions,
  localeColumnSummary,
  selectedTermIdsCount,
  onOpenBatch,
  onClearSelection,
  statusNotice,
  isLoading,
  errorMessage,
  terms,
  displayedLocaleTags,
  allVisibleSelected,
  onToggleSelectAll,
  onOpenEditTerm,
  onOpenInlineTranslationEdit,
  activeTermId,
  selectedTermIds,
  onToggleTermSelection,
  getWorkbenchHref,
  getWorkbenchState,
  statusOptions,
  getStatusLabel,
  getTermTypeLabel,
  getEnforcementLabel,
  isChangingStatus,
  openStatusTermId,
  onOpenStatusTermIdChange,
  onChangeTermStatus,
  canEditTranslationLocale,
  savingTranslationKey,
  onSaveTermTranslation,
}: Props) {
  const [editingTranslation, setEditingTranslation] = useState<{
    tmTextUnitId: number;
    localeTag: string;
    target: string;
  } | null>(null);
  const [pendingEditExit, setPendingEditExit] = useState<PendingEditExit | null>(null);
  const termRowRefs = useRef(new Map<number, HTMLTableRowElement>());
  const emptyColSpan = displayedLocaleTags.length + (canManageTerms ? 3 : 2);
  const showLocaleColumnLimit = selectedLocaleTags.length > AUTO_VISIBLE_LOCALE_COLUMNS_CAP;
  const tableStyle = {
    '--glossary-term-admin-grid-template': getTableGridTemplate(
      canManageTerms,
      displayedLocaleTags.length,
    ),
  } as CSSProperties;
  const shouldGuardEditExit = (term: ApiGlossaryTerm, localeTag?: string) => {
    if (!editingTranslation) {
      return false;
    }
    if (editingTranslation.tmTextUnitId !== term.tmTextUnitId) {
      return true;
    }
    return Boolean(
      localeTag && editingTranslation.localeTag.toLowerCase() !== localeTag.toLowerCase(),
    );
  };
  const openTermEditor = (term: ApiGlossaryTerm) => {
    if (shouldGuardEditExit(term)) {
      setPendingEditExit({ type: 'term', term });
      return;
    }
    onOpenEditTerm(term);
  };
  const activeTermIndex = terms.findIndex((term) => term.tmTextUnitId === activeTermId);
  const focusTermRow = (tmTextUnitId: number) => {
    requestAnimationFrame(() => {
      termRowRefs.current.get(tmTextUnitId)?.focus();
    });
  };
  const openTermFromKeyboard = (term: ApiGlossaryTerm) => {
    openTermEditor(term);
    focusTermRow(term.tmTextUnitId);
  };
  const handleTermRowKeyDown = (
    term: ApiGlossaryTerm,
    index: number,
    event: KeyboardEvent<HTMLTableRowElement>,
  ) => {
    if (event.target !== event.currentTarget) {
      return;
    }

    let nextIndex: number | null = null;
    if (event.key === 'ArrowDown') {
      nextIndex = Math.min(index + 1, terms.length - 1);
    } else if (event.key === 'ArrowUp') {
      nextIndex = Math.max(index - 1, 0);
    } else if (event.key === 'Home') {
      nextIndex = 0;
    } else if (event.key === 'End') {
      nextIndex = terms.length - 1;
    } else if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      openTermFromKeyboard(term);
      return;
    }

    if (nextIndex == null) {
      return;
    }

    event.preventDefault();
    const nextTerm = terms[nextIndex];
    if (nextTerm) {
      openTermFromKeyboard(nextTerm);
    }
  };
  const openTranslationEditor = (
    term: ApiGlossaryTerm,
    localeTag: string,
    target: string,
    canEditTranslation: boolean,
  ) => {
    if (!canEditTranslation) {
      return;
    }
    if (shouldGuardEditExit(term, localeTag)) {
      setPendingEditExit({ type: 'translation', term, localeTag, target });
      return;
    }
    onOpenInlineTranslationEdit(term);
    setEditingTranslation({
      tmTextUnitId: term.tmTextUnitId,
      localeTag,
      target,
    });
  };
  const discardCurrentEdit = () => {
    const pending = pendingEditExit;
    setEditingTranslation(null);
    setPendingEditExit(null);
    if (!pending) {
      return;
    }
    if (pending.type === 'term') {
      onOpenEditTerm(pending.term);
      return;
    }
    onOpenInlineTranslationEdit(pending.term);
    setEditingTranslation({
      tmTextUnitId: pending.term.tmTextUnitId,
      localeTag: pending.localeTag,
      target: pending.target,
    });
  };

  return (
    <>
      <div className="glossary-term-admin__toolbar">
        <div className="glossary-term-admin__search">
          <input
            type="search"
            className="settings-input"
            placeholder="Search source, definition, target, or references"
            value={searchDraft}
            onChange={(event) => onChangeSearch(event.target.value)}
          />
        </div>
        <div className="glossary-term-admin__toolbar-row">
          <div className="glossary-term-admin__filters">
            <LocaleMultiSelect
              label="Locale columns"
              options={localeOptions}
              selectedTags={selectedLocaleTags}
              onChange={onChangeSelectedLocaleTags}
              className="glossary-term-admin__filter-dropdown glossary-term-admin__filter-dropdown--locale"
              buttonAriaLabel="Choose glossary locale columns"
              align="right"
            />
            <SingleSelectDropdown
              label="Status"
              options={statusFilterOptions}
              value={selectedStatusFilter}
              onChange={onChangeSelectedStatusFilter}
              className="glossary-term-admin__filter-dropdown glossary-term-admin__filter-dropdown--status"
              buttonAriaLabel="Filter glossary terms by status"
              placeholder="Status"
              noResultsLabel="No statuses found"
              searchable={false}
            />
          </div>
          <div className="glossary-term-admin__actions">
            {canManageTerms ? (
              <GlossaryPrimaryActionMenu
                onOpenCreate={onOpenCreate}
                onOpenExtract={onOpenExtract}
              />
            ) : (
              <button
                type="button"
                className="settings-button settings-button--primary"
                onClick={onOpenCreate}
              >
                Propose term
              </button>
            )}
          </div>
        </div>
      </div>

      <div className="glossary-term-admin__subbar">
        <div className="glossary-term-admin__subbar-meta">
          <span className="settings-hint">
            {termsTotalCount > visibleTermsCount
              ? `Showing first ${visibleTermsCount.toLocaleString()} of ${termsTotalCount.toLocaleString()} terms`
              : `${termsTotalCount.toLocaleString()} terms`}
          </span>
          {localeColumnSummary ? (
            <>
              <span className="glossary-term-admin__subbar-separator" aria-hidden="true">
                ·
              </span>
              <span className="settings-hint">{localeColumnSummary}</span>
            </>
          ) : null}
        </div>
        <div className="glossary-term-admin__subbar-actions">
          {showLocaleColumnLimit ? (
            <>
              <NumericPresetDropdown
                value={visibleLocaleColumnLimit}
                buttonLabel={`Columns: ${visibleLocaleColumnLimit}`}
                menuLabel="Visible locale columns"
                presetOptions={visibleLocaleColumnLimitOptions.map((count) => ({
                  value: count,
                  label: String(count),
                }))}
                onChange={onChangeVisibleLocaleColumnLimit}
                ariaLabel="Visible locale columns"
                customInitialValue={selectedLocaleTags.length}
                className="glossary-term-admin__subbar-dropdown"
                buttonClassName="glossary-term-admin__subbar-button glossary-term-admin__subbar-button--dropdown"
              />
              <span className="glossary-term-admin__subbar-separator" aria-hidden="true">
                ·
              </span>
            </>
          ) : null}
          <NumericPresetDropdown
            value={termsLimit}
            buttonLabel={`Limit: ${termsLimit}`}
            menuLabel="Result limit"
            presetOptions={termsLimitOptions.map((size) => ({
              value: size,
              label: String(size),
            }))}
            onChange={onChangeTermsLimit}
            ariaLabel="Result limit"
            className="glossary-term-admin__subbar-dropdown"
            buttonClassName="glossary-term-admin__subbar-button glossary-term-admin__subbar-button--dropdown"
          />
          {canImport || canExport || (canManageTerms && selectedTermIdsCount > 0) ? (
            <span className="glossary-term-admin__subbar-separator" aria-hidden="true">
              ·
            </span>
          ) : null}
          {canImport ? (
            <>
              <button
                type="button"
                className="glossary-term-admin__subbar-button"
                onClick={onOpenImport}
              >
                Import
              </button>
              {canExport || (canManageTerms && selectedTermIdsCount > 0) ? (
                <span className="glossary-term-admin__subbar-separator" aria-hidden="true">
                  ·
                </span>
              ) : null}
            </>
          ) : null}
          {canExport ? (
            <>
              <button
                type="button"
                className="glossary-term-admin__subbar-button"
                onClick={onOpenExport}
              >
                Export
              </button>
              {canManageTerms && selectedTermIdsCount > 0 ? (
                <span className="glossary-term-admin__subbar-separator" aria-hidden="true">
                  ·
                </span>
              ) : null}
            </>
          ) : null}
          {canManageTerms && selectedTermIdsCount > 0 ? (
            <>
              {canImport || canExport ? (
                <span className="glossary-term-admin__subbar-separator" aria-hidden="true">
                  ·
                </span>
              ) : null}
              <span className="settings-hint glossary-term-admin__selection-summary">
                {selectedTermIdsCount.toLocaleString()} selected
              </span>
              <span className="glossary-term-admin__subbar-separator" aria-hidden="true">
                ·
              </span>
              <button
                type="button"
                className="glossary-term-admin__subbar-button"
                onClick={onOpenBatch}
              >
                Batch edit
              </button>
              <span className="glossary-term-admin__subbar-separator" aria-hidden="true">
                ·
              </span>
              <button
                type="button"
                className="glossary-term-admin__subbar-button"
                onClick={onClearSelection}
              >
                Clear selection
              </button>
            </>
          ) : null}
        </div>
      </div>

      {statusNotice ? (
        <p className={`settings-hint${statusNotice.kind === 'error' ? ' is-error' : ''}`}>
          {statusNotice.message}
        </p>
      ) : null}

      <div className="settings-table-wrapper">
        <table
          className={`settings-table glossary-term-admin__table${
            displayedLocaleTags.length === 0 ? ' glossary-term-admin__table--no-locales' : ''
          }`}
          style={tableStyle}
        >
          <colgroup>
            {canManageTerms ? <col className="glossary-term-admin__checkbox-column" /> : null}
            <col className="glossary-term-admin__source-column" />
            <col className="glossary-term-admin__status-column" />
            {displayedLocaleTags.map((localeTag) => (
              <col key={localeTag} className="glossary-term-admin__translation-column" />
            ))}
          </colgroup>
          <thead>
            <tr>
              {canManageTerms ? (
                <th className="glossary-term-admin__checkbox-cell">
                  <input
                    type="checkbox"
                    checked={allVisibleSelected}
                    onChange={onToggleSelectAll}
                    aria-label="Select all visible glossary terms"
                  />
                </th>
              ) : null}
              <th>Source</th>
              <th className="glossary-term-admin__status-header">Status</th>
              {displayedLocaleTags.map((localeTag, index) => (
                <th
                  key={localeTag}
                  className={
                    index === 0 ? 'glossary-term-admin__translation-cell--first' : undefined
                  }
                >
                  {localeTag}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {isLoading ? (
              <tr>
                <td colSpan={emptyColSpan} className="glossary-term-admin__empty">
                  Loading glossary terms…
                </td>
              </tr>
            ) : errorMessage ? (
              <tr>
                <td colSpan={emptyColSpan} className="glossary-term-admin__empty">
                  {errorMessage}
                </td>
              </tr>
            ) : terms.length === 0 ? (
              <tr>
                <td colSpan={emptyColSpan} className="glossary-term-admin__empty">
                  No glossary terms yet.
                </td>
              </tr>
            ) : (
              terms.map((term, index) => (
                <tr
                  key={term.tmTextUnitId}
                  ref={(node) => {
                    if (node) {
                      termRowRefs.current.set(term.tmTextUnitId, node);
                    } else {
                      termRowRefs.current.delete(term.tmTextUnitId);
                    }
                  }}
                  className={`glossary-term-admin__row glossary-term-admin__row--interactive${
                    term.tmTextUnitId === activeTermId ? ' is-selected' : ''
                  }`}
                  tabIndex={
                    (activeTermIndex >= 0 ? term.tmTextUnitId === activeTermId : index === 0)
                      ? 0
                      : -1
                  }
                  aria-selected={term.tmTextUnitId === activeTermId}
                  onClick={() => openTermEditor(term)}
                  onKeyDown={(event) => handleTermRowKeyDown(term, index, event)}
                >
                  {canManageTerms ? (
                    <td
                      className="glossary-term-admin__checkbox-cell"
                      onClick={(event) => event.stopPropagation()}
                    >
                      <input
                        type="checkbox"
                        checked={selectedTermIds.includes(term.tmTextUnitId)}
                        onChange={(event) =>
                          onToggleTermSelection(term.tmTextUnitId, event.target.checked)
                        }
                        aria-label={`Select glossary term ${term.source}`}
                      />
                    </td>
                  ) : null}
                  <td className="glossary-term-admin__source-cell">
                    <div className="glossary-term-admin__source-row">
                      <div className="glossary-term-admin__source-stack">
                        <div className="glossary-term-admin__source">{term.source}</div>
                        <div className="glossary-term-admin__term-meta">
                          <span>{getTermTypeLabel(term.termType ?? 'GENERAL')}</span>
                          <span aria-hidden="true">·</span>
                          <span>{getEnforcementLabel(term.enforcement ?? 'SOFT')}</span>
                        </div>
                      </div>
                      <div className="glossary-term-admin__row-actions">
                        <Link
                          to={getWorkbenchHref(term.tmTextUnitId)}
                          state={getWorkbenchState(term.tmTextUnitId)}
                          className="glossary-term-admin__row-action-link"
                          onClick={(event) => event.stopPropagation()}
                        >
                          Workbench
                        </Link>
                      </div>
                    </div>
                  </td>
                  <td
                    className="glossary-term-admin__status-cell"
                    onClick={(event) => event.stopPropagation()}
                  >
                    {canManageTerms ? (
                      <div className="glossary-term-admin__status-stack">
                        <PillDropdown
                          value={term.status ?? 'CANDIDATE'}
                          options={statusOptions.map((status) => ({
                            value: status,
                            label: getStatusLabel(status),
                          }))}
                          onChange={(next) => onChangeTermStatus(term, next)}
                          disabled={isChangingStatus}
                          ariaLabel="Glossary term status"
                          isOpen={openStatusTermId === term.tmTextUnitId}
                          onOpenChange={(nextOpen) =>
                            onOpenStatusTermIdChange(term.tmTextUnitId, nextOpen)
                          }
                          className="glossary-term-admin__status-pill"
                        />
                      </div>
                    ) : (
                      getStatusLabel(term.status ?? 'CANDIDATE')
                    )}
                  </td>
                  {displayedLocaleTags.map((localeTag, index) => {
                    const translation = getTranslation(term, localeTag);
                    const translationKey = getTranslationKey(term.tmTextUnitId, localeTag);
                    const isEditingTranslation =
                      editingTranslation?.tmTextUnitId === term.tmTextUnitId &&
                      editingTranslation.localeTag.toLowerCase() === localeTag.toLowerCase();
                    const isSavingTranslation = savingTranslationKey === translationKey;
                    const canEditTranslation = canEditTranslationLocale(localeTag);
                    const openCurrentTranslationEditor = () =>
                      openTranslationEditor(
                        term,
                        localeTag,
                        translation?.target ?? '',
                        canEditTranslation,
                      );
                    const saveTranslation = async () => {
                      if (!editingTranslation?.target.trim()) {
                        return;
                      }
                      await onSaveTermTranslation(term, localeTag, {
                        target: editingTranslation.target,
                        targetComment: translation?.targetComment ?? '',
                      });
                      setEditingTranslation(null);
                    };
                    return (
                      <td
                        key={localeTag}
                        className={`glossary-term-admin__translation-cell${
                          index === 0 ? ' glossary-term-admin__translation-cell--first' : ''
                        }${
                          !isEditingTranslation && canEditTranslation
                            ? ' glossary-term-admin__translation-cell--interactive'
                            : ''
                        }`}
                        onClick={(event) => {
                          event.stopPropagation();
                          if (!isEditingTranslation) {
                            openCurrentTranslationEditor();
                          }
                        }}
                      >
                        {isEditingTranslation ? (
                          <div className="glossary-term-admin__translation-editor">
                            <textarea
                              className="settings-input glossary-term-admin__translation-editor-target"
                              value={editingTranslation.target}
                              placeholder="Target translation"
                              autoFocus
                              onChange={(event) =>
                                setEditingTranslation((current) =>
                                  current ? { ...current, target: event.target.value } : current,
                                )
                              }
                              onKeyDown={(event) => {
                                if (event.key === 'Escape') {
                                  setEditingTranslation(null);
                                }
                              }}
                            />
                            <div className="glossary-term-admin__translation-editor-actions">
                              <button
                                type="button"
                                className="glossary-term-admin__translation-editor-button glossary-term-admin__translation-editor-button--primary"
                                disabled={isSavingTranslation || !editingTranslation.target.trim()}
                                onClick={() => void saveTranslation()}
                              >
                                {isSavingTranslation ? 'Saving…' : 'Accept'}
                              </button>
                              <button
                                type="button"
                                className="glossary-term-admin__translation-editor-button"
                                disabled={isSavingTranslation}
                                onClick={() => setEditingTranslation(null)}
                              >
                                Cancel
                              </button>
                              <Link
                                to={getWorkbenchHref(term.tmTextUnitId, [localeTag])}
                                state={getWorkbenchState(term.tmTextUnitId, [localeTag])}
                                className="glossary-term-admin__translation-editor-button"
                                onClick={(event) => {
                                  event.stopPropagation();
                                  setEditingTranslation(null);
                                }}
                              >
                                Workbench
                              </Link>
                            </div>
                          </div>
                        ) : (
                          <button
                            type="button"
                            className="glossary-term-admin__translation-button"
                            disabled={!canEditTranslation}
                            onClick={(event) => {
                              event.stopPropagation();
                              openCurrentTranslationEditor();
                            }}
                          >
                            <span
                              className={`glossary-term-admin__translation${
                                term.doNotTranslate
                                  ? ' glossary-term-admin__translation--muted'
                                  : ''
                              }`}
                            >
                              {term.doNotTranslate
                                ? 'Do not translate'
                                : translation?.target || '—'}
                            </span>
                            {translation?.targetComment ? (
                              <span className="glossary-term-admin__translation-note">
                                {translation.targetComment}
                              </span>
                            ) : null}
                          </button>
                        )}
                      </td>
                    );
                  })}
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>

      <ConfirmModal
        open={pendingEditExit != null}
        title="Stop editing translation?"
        body="You have an inline translation edit in progress. Stop editing to switch to another term."
        confirmLabel="Stop editing"
        cancelLabel="Keep editing"
        confirmVariant="primary"
        onCancel={() => setPendingEditExit(null)}
        onConfirm={discardCurrentEdit}
      />
    </>
  );
}
