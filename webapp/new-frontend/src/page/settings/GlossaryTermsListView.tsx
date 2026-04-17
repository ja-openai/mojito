import type { CSSProperties } from 'react';
import { Link } from 'react-router-dom';

import type { ApiGlossaryTerm } from '../../api/glossaries';
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
  selectedTermIds: number[];
  onToggleTermSelection: (tmTextUnitId: number, checked: boolean) => void;
  getWorkbenchHref: (tmTextUnitId: number) => string;
  getWorkbenchState: (tmTextUnitId: number) => unknown;
  statusOptions: string[];
  getStatusLabel: (status: string) => string;
  isChangingStatus: boolean;
  openStatusTermId: number | null;
  onOpenStatusTermIdChange: (tmTextUnitId: number, nextOpen: boolean) => void;
  onChangeTermStatus: (term: ApiGlossaryTerm, status: string) => void;
};

const getTranslation = (term: ApiGlossaryTerm, localeTag: string) =>
  term.translations.find((translation) => translation.localeTag === localeTag);

const AUTO_VISIBLE_LOCALE_COLUMNS_CAP = 5;

function getSourceColumnWidth(localeColumnCount: number) {
  if (localeColumnCount <= 0) {
    return 'auto';
  }
  if (localeColumnCount === 1) {
    return '34%';
  }
  if (localeColumnCount === 2) {
    return '28%';
  }
  if (localeColumnCount === 3) {
    return '23%';
  }
  return '18%';
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
  selectedTermIds,
  onToggleTermSelection,
  getWorkbenchHref,
  getWorkbenchState,
  statusOptions,
  getStatusLabel,
  isChangingStatus,
  openStatusTermId,
  onOpenStatusTermIdChange,
  onChangeTermStatus,
}: Props) {
  const emptyColSpan = displayedLocaleTags.length + (canManageTerms ? 3 : 2);
  const showLocaleColumnLimit = selectedLocaleTags.length > AUTO_VISIBLE_LOCALE_COLUMNS_CAP;
  const tableStyle = {
    '--glossary-term-admin-source-width': getSourceColumnWidth(displayedLocaleTags.length),
  } as CSSProperties;

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
              noneLabel="All statuses"
              placeholder="All statuses"
              noResultsLabel="No statuses found"
              searchable={false}
            />
          </div>
          <div className="glossary-term-admin__actions">
            {canManageTerms ? (
              <>
                <button
                  type="button"
                  className="settings-button settings-button--ghost"
                  onClick={onOpenExtract}
                >
                  Extract candidates
                </button>
                <button
                  type="button"
                  className="settings-button settings-button--primary"
                  onClick={onOpenCreate}
                >
                  Add term
                </button>
              </>
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
            {displayedLocaleTags.map((localeTag) => (
              <col key={localeTag} className="glossary-term-admin__translation-column" />
            ))}
            <col className="glossary-term-admin__status-column" />
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
              {displayedLocaleTags.map((localeTag) => (
                <th key={localeTag}>{localeTag}</th>
              ))}
              <th className="glossary-term-admin__status-header">Status</th>
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
              terms.map((term) => (
                <tr
                  key={term.tmTextUnitId}
                  className={`glossary-term-admin__row${
                    canManageTerms ? ' glossary-term-admin__row--interactive' : ''
                  }`}
                  onClick={canManageTerms ? () => onOpenEditTerm(term) : undefined}
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
                  <td>
                    <div className="glossary-term-admin__source-row">
                      <div className="glossary-term-admin__source-stack">
                        <div className="glossary-term-admin__source">{term.source}</div>
                        <div className="glossary-term-admin__term-meta">
                          <span>{getStatusLabel(term.termType ?? 'GENERAL')}</span>
                          <span aria-hidden="true">·</span>
                          <span>{getStatusLabel(term.enforcement ?? 'SOFT')}</span>
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
                  {displayedLocaleTags.map((localeTag) => {
                    const translation = getTranslation(term, localeTag);
                    return (
                      <td key={localeTag}>
                        <div
                          className={`glossary-term-admin__translation${
                            term.doNotTranslate ? ' glossary-term-admin__translation--muted' : ''
                          }`}
                        >
                          {term.doNotTranslate ? 'Do not translate' : translation?.target || '—'}
                        </div>
                        {translation?.targetComment ? (
                          <div className="glossary-term-admin__translation-note">
                            {translation.targetComment}
                          </div>
                        ) : null}
                      </td>
                    );
                  })}
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
                        />
                      </div>
                    ) : (
                      (term.status ?? '—')
                    )}
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>
    </>
  );
}
