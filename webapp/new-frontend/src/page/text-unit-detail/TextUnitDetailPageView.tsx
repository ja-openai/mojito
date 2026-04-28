import '../review-project/review-project-page.css';
import './text-unit-detail-page.css';

import { type ReactNode } from 'react';
import { Link } from 'react-router-dom';

import type { AiReviewSuggestion } from '../../api/ai-review';
import type { ApiGlossaryTerm, ApiMatchedGlossaryTerm } from '../../api/glossaries';
import { AiChatReview, type AiChatReviewMessage } from '../../components/AiChatReview';
import { AutoTextarea } from '../../components/AutoTextarea';
import { ConfirmModal } from '../../components/ConfirmModal';
import { GlossaryMatchesPanel } from '../../components/GlossaryMatchesPanel';
import { IcuPreviewSection } from '../../components/IcuPreviewSection';
import { IntegrityCheckAlertModal } from '../../components/IntegrityCheckAlertModal';
import { Pill } from '../../components/Pill';
import { PillDropdown } from '../../components/PillDropdown';
import {
  TextUnitHistoryTimeline,
  type TextUnitHistoryTimelineComment as TextUnitDetailHistoryComment,
  type TextUnitHistoryTimelineEntry as TextUnitDetailHistoryRow,
} from '../../components/TextUnitHistoryTimeline';

export type TextUnitDetailMetaRow = {
  label: string;
  value: string;
};

export type TextUnitDetailMetaSection = {
  title: string;
  rows: TextUnitDetailMetaRow[];
};

export type { TextUnitDetailHistoryComment, TextUnitDetailHistoryRow };

export type TextUnitDetailAiMessage = AiChatReviewMessage;

const formatGlossaryMetadataValue = (value?: string | null) =>
  value?.trim() ? value.trim().toLowerCase().replace(/_/g, ' ') : null;

type TextUnitDetailPageViewProps = {
  tmTextUnitId: number;
  onBack: () => void;
  editorInfo: {
    target: string;
    status: string;
    isSourceOnly: boolean;
    statusOptions: string[];
    canEdit: boolean;
    canDelete: boolean;
    isDirty: boolean;
    isSaving: boolean;
    isDeleting: boolean;
    errorMessage: string | null;
    warningMessage: string | null;
  };
  keyInfo: {
    stringId: string;
    locale: string;
    source: string;
    comment: string;
    repositoryName: string;
  };
  onChangeTarget: (value: string) => void;
  onChangeStatus: (value: string) => void;
  onSaveEditor: () => void;
  onResetEditor: () => void;
  onRequestDeleteEditor: () => void;
  previewLocale: string;
  isIcuPreviewCollapsed: boolean;
  onToggleIcuPreviewCollapsed: () => void;
  icuPreviewMode: 'source' | 'target';
  onChangeIcuPreviewMode: (mode: 'source' | 'target') => void;
  isAiCollapsed: boolean;
  onToggleAiCollapsed: () => void;
  aiMessages: TextUnitDetailAiMessage[];
  aiInput: string;
  onChangeAiInput: (value: string) => void;
  onSubmitAi: () => void;
  onRetryAi: () => void;
  onUseAiSuggestion: (suggestion: AiReviewSuggestion) => void;
  isAiResponding: boolean;
  glossaryMatches: ApiMatchedGlossaryTerm[];
  isGlossaryLoading: boolean;
  glossaryErrorMessage: string | null;
  glossaryTermMetadata: {
    glossaryId: number;
    glossaryName: string;
    term: ApiGlossaryTerm | null;
    isLoading: boolean;
    errorMessage: string | null;
  } | null;
  isGlossaryCollapsed: boolean;
  onToggleGlossaryCollapsed: () => void;
  isMetaCollapsed: boolean;
  onToggleMetaCollapsed: () => void;
  isMetaLoading: boolean;
  metaErrorMessage: string | null;
  metaSections: TextUnitDetailMetaSection[];
  metaWarningMessage: string | null;
  isHistoryCollapsed: boolean;
  onToggleHistoryCollapsed: () => void;
  isHistoryLoading: boolean;
  historyErrorMessage: string | null;
  historyMissingLocale: boolean;
  historyRows: TextUnitDetailHistoryRow[];
  historyInitialDate: string;
  isHistoryCountReady: boolean;
  showDeletedHistoryEntry: boolean;
  showValidationDialog: boolean;
  validationDialogTitle: string;
  validationDialogBody: string;
  validationDialogFailureDetail: string | null;
  validationDialogReportMessage: string | null;
  validationDialogReportHtml: string | null;
  validationDialogCanBypass: boolean;
  validationDialogCanRetry: boolean;
  onConfirmValidationSave: () => void;
  onRetryValidationSave: () => void;
  onDismissValidationDialog: () => void;
  showDeleteDialog: boolean;
  deleteDialogBody: string;
  onConfirmDeleteEditor: () => void;
  onDismissDeleteDialog: () => void;
};

export function TextUnitDetailPageView({
  tmTextUnitId,
  onBack,
  editorInfo,
  keyInfo,
  onChangeTarget,
  onChangeStatus,
  onSaveEditor,
  onResetEditor,
  onRequestDeleteEditor,
  previewLocale,
  isIcuPreviewCollapsed,
  onToggleIcuPreviewCollapsed,
  icuPreviewMode,
  onChangeIcuPreviewMode,
  isAiCollapsed,
  onToggleAiCollapsed,
  aiMessages,
  aiInput,
  onChangeAiInput,
  onSubmitAi,
  onRetryAi,
  onUseAiSuggestion,
  isAiResponding,
  glossaryMatches,
  isGlossaryLoading,
  glossaryErrorMessage,
  glossaryTermMetadata,
  isGlossaryCollapsed,
  onToggleGlossaryCollapsed,
  isMetaCollapsed,
  onToggleMetaCollapsed,
  isMetaLoading,
  metaErrorMessage,
  metaSections,
  metaWarningMessage,
  isHistoryCollapsed,
  onToggleHistoryCollapsed,
  isHistoryLoading,
  historyErrorMessage,
  historyMissingLocale,
  historyRows,
  historyInitialDate,
  isHistoryCountReady,
  showDeletedHistoryEntry,
  showValidationDialog,
  validationDialogTitle,
  validationDialogBody,
  validationDialogFailureDetail,
  validationDialogReportMessage,
  validationDialogReportHtml,
  validationDialogCanBypass,
  validationDialogCanRetry,
  onConfirmValidationSave,
  onRetryValidationSave,
  onDismissValidationDialog,
  showDeleteDialog,
  deleteDialogBody,
  onConfirmDeleteEditor,
  onDismissDeleteDialog,
}: TextUnitDetailPageViewProps) {
  const glossaryTerm = glossaryTermMetadata?.term ?? null;
  const glossaryTermHref = glossaryTermMetadata
    ? `/glossaries/${glossaryTermMetadata.glossaryId}${
        glossaryTerm ? `?termId=${glossaryTerm.tmTextUnitId}` : ''
      }`
    : null;
  const glossaryTermComment =
    glossaryTerm?.definition?.trim() || glossaryTerm?.sourceComment?.trim() || keyInfo.comment;
  const glossaryTermType = formatGlossaryMetadataValue(glossaryTerm?.termType);
  const glossaryPartOfSpeech = formatGlossaryMetadataValue(glossaryTerm?.partOfSpeech);
  const historyCount =
    historyRows.length + (showDeletedHistoryEntry ? 1 : 0) + (historyInitialDate ? 1 : 0);
  const historyTitle = isHistoryCountReady ? `History (${historyCount})` : 'History';

  return (
    <div className="review-project-page text-unit-detail-page">
      <header className="review-project-page__header">
        <div className="review-project-page__header-row">
          <div className="review-project-page__header-group review-project-page__header-group--left">
            <button
              type="button"
              className="review-project-page__header-back-link"
              onClick={onBack}
              aria-label="Back to workbench"
              title="Back to workbench"
            >
              <svg
                className="review-project-page__header-back-icon"
                viewBox="0 0 24 24"
                aria-hidden="true"
                focusable="false"
              >
                <path
                  d="M20 12H6m0 0l5-5m-5 5l5 5"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="1.8"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                />
              </svg>
            </button>
            <span className="review-project-page__header-name">Text unit #{tmTextUnitId}</span>
            <div className="text-unit-detail-page__header-context">
              <Pill>{keyInfo.locale}</Pill>
              <span
                className="text-unit-detail-page__header-repository"
                title={keyInfo.repositoryName}
              >
                {keyInfo.repositoryName}
              </span>
            </div>
          </div>
          <div className="review-project-page__header-group review-project-page__header-group--stats" />
          <div className="review-project-page__header-group review-project-page__header-group--meta" />
        </div>
      </header>

      <div className="text-unit-detail-page__content">
        <div className="text-unit-detail-page__layout">
          <section className="text-unit-detail-page__panel text-unit-detail-page__panel--editor">
            <h1 className="text-unit-detail-page__title">
              {editorInfo.isSourceOnly ? 'Source' : 'Translation'}
            </h1>

            <div className="text-unit-detail-page__editor-field">
              <AutoTextarea
                className="text-unit-detail-page__editor-textarea"
                value={editorInfo.target}
                onChange={(event) => onChangeTarget(event.target.value)}
                disabled={!editorInfo.canEdit || editorInfo.isSaving}
                placeholder="Add translated copy"
                rows={1}
                style={{ resize: 'none' }}
              />
            </div>

            {!editorInfo.isSourceOnly ? (
              <div className="text-unit-detail-page__editor-controls">
                <div className="text-unit-detail-page__editor-status">
                  <PillDropdown
                    value={editorInfo.status}
                    options={editorInfo.statusOptions.map((option) => ({
                      value: option,
                      label: option,
                    }))}
                    onChange={onChangeStatus}
                    disabled={!editorInfo.canEdit || editorInfo.isSaving}
                    ariaLabel="Translation status"
                  />
                </div>
                <div className="text-unit-detail-page__editor-actions">
                  <button
                    type="button"
                    className="text-unit-detail-page__button"
                    onClick={onRequestDeleteEditor}
                    disabled={
                      !editorInfo.canDelete ||
                      editorInfo.isSaving ||
                      editorInfo.isDeleting ||
                      !editorInfo.canEdit
                    }
                  >
                    {editorInfo.isDeleting ? 'Deleting…' : 'Delete'}
                  </button>
                  <button
                    type="button"
                    className="text-unit-detail-page__button"
                    onClick={onResetEditor}
                    disabled={!editorInfo.isDirty || editorInfo.isSaving || editorInfo.isDeleting}
                  >
                    Reset
                  </button>
                  <button
                    type="button"
                    className="text-unit-detail-page__button text-unit-detail-page__button--primary"
                    onClick={onSaveEditor}
                    disabled={
                      !editorInfo.canEdit ||
                      !editorInfo.isDirty ||
                      editorInfo.isSaving ||
                      editorInfo.isDeleting
                    }
                  >
                    {editorInfo.isSaving ? 'Saving…' : 'Save'}
                  </button>
                </div>
              </div>
            ) : null}

            {!editorInfo.isSourceOnly ? (
              <IcuPreviewSection
                sourceMessage={keyInfo.source}
                targetMessage={editorInfo.target}
                targetLocale={previewLocale}
                mode={icuPreviewMode}
                isCollapsed={isIcuPreviewCollapsed}
                onToggleCollapsed={onToggleIcuPreviewCollapsed}
                onChangeMode={onChangeIcuPreviewMode}
                className="text-unit-detail-page__panel text-unit-detail-page__panel--section text-unit-detail-page__panel--icu-inline"
                titleClassName="text-unit-detail-page__section-title"
              />
            ) : null}

            {!editorInfo.isSourceOnly ? (
              <section className="text-unit-detail-page__panel text-unit-detail-page__panel--section text-unit-detail-page__panel--ai-inline">
                <SectionHeader
                  title="AI Chat Review"
                  expanded={!isAiCollapsed}
                  onToggle={onToggleAiCollapsed}
                />
                {!isAiCollapsed ? (
                  <AiChatReview
                    messages={aiMessages}
                    input={aiInput}
                    onChangeInput={onChangeAiInput}
                    onSubmit={onSubmitAi}
                    onRetryError={onRetryAi}
                    onUseSuggestion={onUseAiSuggestion}
                    isResponding={isAiResponding}
                  />
                ) : null}
              </section>
            ) : null}

            {editorInfo.warningMessage ? (
              <div className="text-unit-detail-page__state text-unit-detail-page__state--warning">
                {editorInfo.warningMessage}
              </div>
            ) : null}
            {editorInfo.errorMessage ? (
              <div className="text-unit-detail-page__state text-unit-detail-page__state--error">
                {editorInfo.errorMessage}
              </div>
            ) : null}
          </section>

          <div className="text-unit-detail-page__side">
            <section className="text-unit-detail-page__panel text-unit-detail-page__panel--section">
              <dl className="text-unit-detail-page__key-info">
                <div className="text-unit-detail-page__key-info-row">
                  <dt className="text-unit-detail-page__key-info-label">
                    <span>Source</span>
                    {glossaryTermHref ? (
                      <Link
                        className="text-unit-detail-page__source-affordance"
                        to={glossaryTermHref}
                      >
                        <Pill>Glossary term</Pill>
                      </Link>
                    ) : null}
                  </dt>
                  <dd>
                    <pre className="text-unit-detail-page__key-info-text text-unit-detail-page__key-info-text--primary">
                      {keyInfo.source}
                    </pre>
                  </dd>
                </div>
                <div className="text-unit-detail-page__key-info-row">
                  <dt>Comment</dt>
                  <dd>
                    <pre className="text-unit-detail-page__key-info-text text-unit-detail-page__key-info-text--primary">
                      {glossaryTermComment}
                    </pre>
                  </dd>
                </div>
                {glossaryPartOfSpeech ? (
                  <div className="text-unit-detail-page__key-info-row">
                    <dt>POS</dt>
                    <dd>
                      <pre className="text-unit-detail-page__key-info-text">
                        {glossaryPartOfSpeech}
                      </pre>
                    </dd>
                  </div>
                ) : null}
                {glossaryTermType ? (
                  <div className="text-unit-detail-page__key-info-row">
                    <dt>Type</dt>
                    <dd>
                      <pre className="text-unit-detail-page__key-info-text">{glossaryTermType}</pre>
                    </dd>
                  </div>
                ) : null}
                {!glossaryTermMetadata ? (
                  <div className="text-unit-detail-page__key-info-row">
                    <dt>Id</dt>
                    <dd>
                      <pre className="text-unit-detail-page__key-info-text">{keyInfo.stringId}</pre>
                    </dd>
                  </div>
                ) : null}
              </dl>
            </section>

            <section className="text-unit-detail-page__panel text-unit-detail-page__panel--section">
              <SectionHeader
                title="Glossary"
                expanded={!isGlossaryCollapsed}
                onToggle={onToggleGlossaryCollapsed}
                summary={isGlossaryLoading ? 'Loading…' : null}
              />
              {!isGlossaryCollapsed ? (
                <GlossaryMatchesPanel
                  matches={glossaryMatches}
                  isLoading={isGlossaryLoading}
                  errorMessage={glossaryErrorMessage}
                  currentTarget={editorInfo.target}
                  showHeader={false}
                />
              ) : null}
            </section>

            <section className="text-unit-detail-page__panel text-unit-detail-page__panel--section">
              <SectionHeader
                title={historyTitle}
                expanded={!isHistoryCollapsed}
                onToggle={onToggleHistoryCollapsed}
              />
              {!isHistoryCollapsed ? (
                <TextUnitHistoryTimeline
                  isLoading={isHistoryLoading}
                  errorMessage={historyErrorMessage}
                  missingLocale={historyMissingLocale}
                  entries={historyRows}
                  showDeletedEntry={showDeletedHistoryEntry}
                  initialDate={historyInitialDate}
                />
              ) : null}
            </section>

            <section className="text-unit-detail-page__panel text-unit-detail-page__panel--section">
              <SectionHeader
                title="Metadata"
                expanded={!isMetaCollapsed}
                onToggle={onToggleMetaCollapsed}
              />

              {!isMetaCollapsed ? (
                isMetaLoading ? (
                  <div className="text-unit-detail-page__state">
                    <span className="spinner spinner--md" aria-hidden />
                    <span>Loading text unit details…</span>
                  </div>
                ) : metaErrorMessage ? (
                  <div className="text-unit-detail-page__state text-unit-detail-page__state--error">
                    {metaErrorMessage}
                  </div>
                ) : (
                  <div className="text-unit-detail-page__sections">
                    {metaSections.map((section) => (
                      <MetaSection key={section.title} title={section.title} rows={section.rows} />
                    ))}

                    {metaWarningMessage ? (
                      <div className="text-unit-detail-page__state text-unit-detail-page__state--warning">
                        {metaWarningMessage}
                      </div>
                    ) : null}
                  </div>
                )
              ) : null}
            </section>
          </div>
        </div>
      </div>

      <IntegrityCheckAlertModal
        open={showValidationDialog}
        title={validationDialogTitle}
        body={validationDialogBody}
        failureDetail={validationDialogFailureDetail}
        reportMessage={validationDialogReportMessage}
        reportHtml={validationDialogReportHtml}
        primaryLabel={
          validationDialogCanRetry ? 'Try again' : validationDialogCanBypass ? 'Save anyway' : 'OK'
        }
        primaryVariant={validationDialogCanBypass ? 'danger' : 'primary'}
        onPrimary={
          validationDialogCanRetry
            ? onRetryValidationSave
            : validationDialogCanBypass
              ? onConfirmValidationSave
              : onDismissValidationDialog
        }
        secondaryLabel={
          validationDialogCanRetry
            ? 'Close'
            : validationDialogCanBypass
              ? 'Keep editing'
              : undefined
        }
        onSecondary={onDismissValidationDialog}
        onClose={onDismissValidationDialog}
      />
      <ConfirmModal
        open={showDeleteDialog}
        title="Delete?"
        body={deleteDialogBody}
        confirmLabel="Delete"
        cancelLabel="Cancel"
        onConfirm={onConfirmDeleteEditor}
        onCancel={onDismissDeleteDialog}
      />
    </div>
  );
}

function SectionHeader({
  title,
  expanded,
  onToggle,
  summary,
  controls,
}: {
  title: string;
  expanded: boolean;
  onToggle: () => void;
  summary?: ReactNode;
  controls?: ReactNode;
}) {
  return (
    <div className="text-unit-detail-page__section-header-row">
      <button
        type="button"
        className="text-unit-detail-page__section-header"
        onClick={onToggle}
        aria-expanded={expanded}
      >
        <span className="text-unit-detail-page__section-heading">
          <span className="text-unit-detail-page__section-title">{title}</span>
          {summary ? (
            <span className="text-unit-detail-page__section-summary">{summary}</span>
          ) : null}
        </span>
      </button>
      {controls ? <div className="text-unit-detail-page__section-controls">{controls}</div> : null}
      <button
        type="button"
        className="text-unit-detail-page__section-action"
        onClick={onToggle}
        aria-expanded={expanded}
      >
        {expanded ? 'Hide' : 'Show'}
      </button>
    </div>
  );
}

function MetaSection({ title, rows }: { title: string; rows: TextUnitDetailMetaRow[] }) {
  return (
    <section className="text-unit-detail-page__meta-section">
      <h3 className="text-unit-detail-page__meta-title">{title}</h3>
      <dl className="text-unit-detail-page__meta">
        {rows.map((row) => (
          <div key={row.label} className="text-unit-detail-page__meta-row">
            <dt>{row.label}</dt>
            <dd>{row.value}</dd>
          </div>
        ))}
      </dl>
    </section>
  );
}
