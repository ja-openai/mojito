import '../review-project/review-project-page.css';
import './text-unit-detail-page.css';

import { type ReactNode, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';

import type { AiReviewSuggestion } from '../../api/ai-review';
import type { ApiGlossaryTerm, ApiMatchedGlossaryTerm } from '../../api/glossaries';
import { AiChatReview, type AiChatReviewMessage } from '../../components/AiChatReview';
import { AiReviewSpeedControl } from '../../components/AiReviewSpeedControl';
import { AutoTextarea } from '../../components/AutoTextarea';
import { ConfirmModal } from '../../components/ConfirmModal';
import { GlossaryMatchesPanel } from '../../components/GlossaryMatchesPanel';
import { GlossaryTermEvidenceThumbnails } from '../../components/GlossaryTermEvidenceThumbnails';
import { IcuPreviewSection } from '../../components/IcuPreviewSection';
import { IntegrityCheckAlertModal } from '../../components/IntegrityCheckAlertModal';
import { isMf2Message } from '../../components/mf2/messageFormat';
import { Mf2DocumentPreview } from '../../components/mf2/Mf2DocumentPreview';
import { Mf2TranslationEditor } from '../../components/mf2/Mf2TranslationEditor';
import { Pill } from '../../components/Pill';
import { PillDropdown } from '../../components/PillDropdown';
import {
  TextUnitHistoryTimeline,
  type TextUnitHistoryTimelineComment as TextUnitDetailHistoryComment,
  type TextUnitHistoryTimelineEntry as TextUnitDetailHistoryRow,
} from '../../components/TextUnitHistoryTimeline';
import { TranslationSearchPanel } from '../../components/TranslationSearchPanel';
import { TranslationTextEditor } from '../../components/TranslationTextEditor';
import type { VisibleTextMarksMode } from '../../components/VisibleTextEditor';
import type { AiReviewSettings } from '../../hooks/useAiReviewPreferences';
import { getGlossaryTermScreenshotEvidence } from '../../utils/glossaryTermEvidence';
import type { ProtectedTextDiagnostic, ProtectedTextToken } from '../../utils/protectedTextTokens';

export type TextUnitDetailMetaRow = {
  label: string;
  value: string;
  kind?: 'targetComment';
};

export type TextUnitDetailMetaSection = {
  title: string;
  rows: TextUnitDetailMetaRow[];
};

export type TextUnitDetailScreenshot = {
  id?: number | null;
  name?: string | null;
  src?: string | null;
};

type TargetCommentEditorProps = {
  draft: string;
  isEditing: boolean;
  canEdit: boolean;
  isSaving: boolean;
  isDisabled: boolean;
  disabledReason: string | null;
  onStart: () => void;
  onChange: (value: string) => void;
  onSave: () => void;
  onCancel: () => void;
};

export type { TextUnitDetailHistoryComment, TextUnitDetailHistoryRow };

export type TextUnitDetailAiMessage = AiChatReviewMessage;

const formatGlossaryMetadataValue = (value?: string | null) =>
  value?.trim() ? value.trim().toLowerCase().replace(/_/g, ' ') : null;

type TextUnitDetailPageViewProps = {
  tmTextUnitId: number;
  isSearchEnabled: boolean;
  onBack: () => void;
  openInWorkbench?: boolean;
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
    mf2ErrorCount: number;
    errorMessage: string | null;
    warningMessage: string | null;
  };
  visibleTextEditor: {
    enabled: boolean;
    marksMode: VisibleTextMarksMode;
    onChangeMarksMode: (mode: VisibleTextMarksMode) => void;
    protectedDiagnostics: ProtectedTextDiagnostic[];
    protectedTokens: ProtectedTextToken[];
    validateNextValue: (nextValue: string) => boolean;
    dir: 'ltr' | 'rtl' | 'auto';
  };
  keyInfo: {
    stringId: string;
    locale: string;
    source: string;
    messageFormat?: string | null;
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
  aiSettings?: AiReviewSettings;
  onReviewAi?: () => void;
  aiInput: string;
  onChangeAiInput: (value: string) => void;
  onSubmitAi: () => void;
  onRetryAi: () => void;
  onUseAiSuggestion: (suggestion: AiReviewSuggestion) => void;
  getAiSuggestionError?: (suggestion: AiReviewSuggestion) => string | null;
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
  sourceScreenshots: TextUnitDetailScreenshot[];
  isGlossaryCollapsed: boolean;
  onToggleGlossaryCollapsed: () => void;
  isMetaCollapsed: boolean;
  onToggleMetaCollapsed: () => void;
  isMetaLoading: boolean;
  metaErrorMessage: string | null;
  metaSections: TextUnitDetailMetaSection[];
  targetCommentEditor: TargetCommentEditorProps;
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
  isSearchEnabled,
  onBack,
  openInWorkbench = false,
  editorInfo,
  visibleTextEditor,
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
  aiSettings,
  onReviewAi,
  aiInput,
  onChangeAiInput,
  onSubmitAi,
  onRetryAi,
  onUseAiSuggestion,
  getAiSuggestionError,
  isAiResponding,
  glossaryMatches,
  isGlossaryLoading,
  glossaryErrorMessage,
  glossaryTermMetadata,
  sourceScreenshots,
  isGlossaryCollapsed,
  onToggleGlossaryCollapsed,
  isMetaCollapsed,
  onToggleMetaCollapsed,
  isMetaLoading,
  metaErrorMessage,
  metaSections,
  targetCommentEditor,
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
  const isMf2 = !editorInfo.isSourceOnly && isMf2Message(keyInfo);
  const canSaveEditor =
    editorInfo.canEdit &&
    editorInfo.isDirty &&
    !editorInfo.isSaving &&
    !editorInfo.isDeleting &&
    editorInfo.mf2ErrorCount === 0;
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
  const glossaryTermScreenshots = getGlossaryTermScreenshotEvidence(glossaryTerm?.evidence);
  const [isGlossaryScreenshotsCollapsed, setIsGlossaryScreenshotsCollapsed] = useState(false);
  const [isSourceScreenshotsCollapsed, setIsSourceScreenshotsCollapsed] = useState(false);
  const historyCount =
    historyRows.length + (showDeletedHistoryEntry ? 1 : 0) + (historyInitialDate ? 1 : 0);
  const historyTitle = isHistoryCountReady ? `History (${historyCount})` : 'History';

  useEffect(() => {
    setIsGlossaryScreenshotsCollapsed(false);
  }, [glossaryTerm?.tmTextUnitId, glossaryTermScreenshots.length]);

  useEffect(() => {
    setIsSourceScreenshotsCollapsed(false);
  }, [tmTextUnitId, sourceScreenshots.length]);

  return (
    <div className="review-project-page text-unit-detail-page">
      <header className="review-project-page__header">
        <div className="review-project-page__header-row">
          <div className="review-project-page__header-group review-project-page__header-group--left">
            <button
              type="button"
              className={`review-project-page__header-back-link${openInWorkbench ? ' text-unit-detail-page__open-workbench' : ''}`}
              onClick={onBack}
              aria-label={openInWorkbench ? 'Open in Workbench' : 'Back to workbench'}
              title={openInWorkbench ? 'Open in Workbench' : 'Back to workbench'}
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
              {openInWorkbench ? <span>Open in Workbench</span> : null}
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
              {isMf2 && visibleTextEditor.enabled ? (
                <Mf2TranslationEditor
                  documentKey={`${tmTextUnitId}:${previewLocale}`}
                  locale={previewLocale}
                  marksMode={visibleTextEditor.marksMode}
                  onChangeMarksMode={visibleTextEditor.onChangeMarksMode}
                  onSubmit={canSaveEditor ? onSaveEditor : undefined}
                  onTargetChange={onChangeTarget}
                  readOnly={!editorInfo.canEdit || editorInfo.isSaving || editorInfo.isDeleting}
                  showArgumentInputs={false}
                  showLocaleSelector={false}
                  showPreview={false}
                  showSource={false}
                  source={keyInfo.source}
                  target={editorInfo.target}
                />
              ) : (
                <TranslationTextEditor
                  assisted={visibleTextEditor.enabled && !isMf2}
                  ariaLabel={editorInfo.isSourceOnly ? 'Source text' : 'Translation'}
                  className="text-unit-detail-page__editor-textarea"
                  source={keyInfo.source}
                  value={editorInfo.target}
                  onChange={onChangeTarget}
                  controlBar={{
                    marksMode: visibleTextEditor.marksMode,
                    onChangeMarksMode: visibleTextEditor.onChangeMarksMode,
                    protectedTokenCount: visibleTextEditor.protectedTokens.length,
                  }}
                  dir={visibleTextEditor.dir}
                  disabled={!editorInfo.canEdit || editorInfo.isSaving}
                  lang={previewLocale}
                  minRows={1}
                  protectedDiagnostics={visibleTextEditor.protectedDiagnostics}
                  protectedTokens={visibleTextEditor.protectedTokens}
                  marksMode={visibleTextEditor.marksMode}
                  spellCheck={true}
                  style={{ resize: 'none' }}
                  validateNextValue={
                    visibleTextEditor.enabled && !isMf2
                      ? visibleTextEditor.validateNextValue
                      : undefined
                  }
                />
              )}
              {editorInfo.mf2ErrorCount > 0 ? (
                <div
                  className="text-unit-detail-page__state text-unit-detail-page__state--error"
                  role="alert"
                >
                  Fix {editorInfo.mf2ErrorCount} MF2 error
                  {editorInfo.mf2ErrorCount === 1 ? '' : 's'} before saving.
                </div>
              ) : null}
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
                    disabled={!canSaveEditor}
                  >
                    {editorInfo.isSaving ? 'Saving…' : 'Save'}
                  </button>
                </div>
              </div>
            ) : null}

            {!editorInfo.isSourceOnly && !isMf2 ? (
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
                  titleAction={
                    aiSettings ? (
                      <AiReviewSpeedControl
                        value={aiSettings.preset}
                        onChange={aiSettings.onChangePreset}
                        reviewStyle={aiSettings.reviewStyle}
                        onChangeReviewStyle={aiSettings.onChangeReviewStyle}
                        showScore={aiSettings.showScore}
                        onChangeShowScore={aiSettings.onChangeShowScore}
                        automaticDisabled={aiSettings.automaticDisabled}
                        onChangeAutomaticDisabled={aiSettings.onChangeAutomaticDisabled}
                        disabled={!aiSettings.ready || aiSettings.isSaving}
                        error={aiSettings.error}
                        onRetry={!aiSettings.ready ? aiSettings.onRetryLoad : undefined}
                      />
                    ) : undefined
                  }
                  expanded={!isAiCollapsed}
                  onToggle={onToggleAiCollapsed}
                />
                {!isAiCollapsed ? (
                  <AiChatReview
                    messages={aiMessages}
                    settings={aiSettings}
                    onReview={onReviewAi}
                    currentTarget={editorInfo.target}
                    input={aiInput}
                    onChangeInput={onChangeAiInput}
                    onSubmit={onSubmitAi}
                    onRetryError={onRetryAi}
                    onUseSuggestion={onUseAiSuggestion}
                    getSuggestionError={getAiSuggestionError}
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
                    {isMf2 && visibleTextEditor.enabled ? (
                      <Mf2DocumentPreview
                        marksMode={visibleTextEditor.marksMode}
                        value={keyInfo.source}
                      />
                    ) : (
                      <pre className="text-unit-detail-page__key-info-text text-unit-detail-page__key-info-text--primary">
                        {keyInfo.source}
                      </pre>
                    )}
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
                {sourceScreenshots.length > 0 ? (
                  <div className="text-unit-detail-page__key-info-row">
                    <dt className="text-unit-detail-page__key-info-label">
                      <span>Screenshots</span>
                      <button
                        type="button"
                        className="text-unit-detail-page__inline-toggle"
                        onClick={() => setIsSourceScreenshotsCollapsed((current) => !current)}
                        aria-expanded={!isSourceScreenshotsCollapsed}
                      >
                        {isSourceScreenshotsCollapsed ? 'Show' : 'Hide'}
                      </button>
                    </dt>
                    <dd>
                      {isSourceScreenshotsCollapsed ? null : (
                        <TextUnitScreenshotThumbnails screenshots={sourceScreenshots} />
                      )}
                    </dd>
                  </div>
                ) : null}
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
                {glossaryTermScreenshots.length > 0 ? (
                  <div className="text-unit-detail-page__key-info-row">
                    <dt className="text-unit-detail-page__key-info-label">
                      <span>Glossary screenshots</span>
                      <button
                        type="button"
                        className="text-unit-detail-page__inline-toggle"
                        onClick={() => setIsGlossaryScreenshotsCollapsed((current) => !current)}
                        aria-expanded={!isGlossaryScreenshotsCollapsed}
                      >
                        {isGlossaryScreenshotsCollapsed ? 'Show' : 'Hide'}
                      </button>
                    </dt>
                    <dd>
                      {isGlossaryScreenshotsCollapsed ? null : (
                        <GlossaryTermEvidenceThumbnails evidence={glossaryTermScreenshots} />
                      )}
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

            {isSearchEnabled && !editorInfo.isSourceOnly ? (
              <TextUnitSearchSection
                key={`${tmTextUnitId}:${keyInfo.locale}`}
                localeTag={keyInfo.locale}
              />
            ) : null}

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
                      <MetaSection
                        key={section.title}
                        title={section.title}
                        rows={section.rows}
                        targetCommentEditor={targetCommentEditor}
                      />
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

function TextUnitSearchSection({ localeTag }: { localeTag: string }) {
  const [expanded, setExpanded] = useState(false);
  const [hasOpened, setHasOpened] = useState(false);

  return (
    <section className="text-unit-detail-page__panel text-unit-detail-page__panel--section">
      <SectionHeader
        title="Search"
        expanded={expanded}
        onToggle={() => {
          setExpanded((current) => !current);
          setHasOpened(true);
        }}
      />
      {hasOpened ? (
        <div hidden={!expanded}>
          <TranslationSearchPanel localeTag={localeTag} active={expanded} />
        </div>
      ) : null}
    </section>
  );
}

function TextUnitScreenshotThumbnails({
  screenshots,
}: {
  screenshots: TextUnitDetailScreenshot[];
}) {
  const visibleScreenshots = screenshots.filter((screenshot) => screenshot.src?.trim());

  if (!visibleScreenshots.length) {
    return null;
  }

  return (
    <div className="text-unit-detail-page__screenshots">
      {visibleScreenshots.map((screenshot, index) => {
        const src = screenshot.src?.trim() ?? '';
        const label = screenshot.name?.trim() || `Screenshot ${index + 1}`;
        return (
          <a
            key={`${screenshot.id ?? index}:${src}`}
            className="text-unit-detail-page__screenshot"
            href={src}
            target="_blank"
            rel="noreferrer"
            title={label}
          >
            <img src={src} alt={label} />
          </a>
        );
      })}
    </div>
  );
}

function SectionHeader({
  title,
  titleAction,
  expanded,
  onToggle,
  summary,
  controls,
}: {
  title: string;
  titleAction?: ReactNode;
  expanded: boolean;
  onToggle: () => void;
  summary?: ReactNode;
  controls?: ReactNode;
}) {
  const heading = (
    <button
      type="button"
      className="text-unit-detail-page__section-header"
      onClick={onToggle}
      aria-expanded={expanded}
    >
      <span className="text-unit-detail-page__section-heading">
        <span className="text-unit-detail-page__section-title">{title}</span>
        {summary ? <span className="text-unit-detail-page__section-summary">{summary}</span> : null}
      </span>
    </button>
  );
  return (
    <div
      className={`text-unit-detail-page__section-header-row${titleAction ? ' text-unit-detail-page__section-header-row--with-title-actions' : ''}`}
    >
      {titleAction ? (
        <div className="text-unit-detail-page__section-title-actions">
          {heading}
          {titleAction}
        </div>
      ) : (
        heading
      )}
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

function MetaSection({
  title,
  rows,
  targetCommentEditor,
}: {
  title: string;
  rows: TextUnitDetailMetaRow[];
  targetCommentEditor: TargetCommentEditorProps;
}) {
  return (
    <section className="text-unit-detail-page__meta-section">
      <h3 className="text-unit-detail-page__meta-title">{title}</h3>
      <dl className="text-unit-detail-page__meta">
        {rows.map((row) => (
          <div key={row.label} className="text-unit-detail-page__meta-row">
            <dt>
              <span className="text-unit-detail-page__meta-label">{row.label}</span>
              {row.kind === 'targetComment' && targetCommentEditor.canEdit ? (
                <button
                  type="button"
                  className="text-unit-detail-page__meta-edit-button"
                  onClick={targetCommentEditor.onStart}
                  disabled={targetCommentEditor.isEditing || targetCommentEditor.isDisabled}
                  title={targetCommentEditor.disabledReason ?? undefined}
                >
                  Edit
                </button>
              ) : null}
            </dt>
            <dd>
              {row.kind === 'targetComment' && targetCommentEditor.isEditing ? (
                <div className="text-unit-detail-page__meta-editor">
                  <AutoTextarea
                    className="text-unit-detail-page__meta-textarea"
                    value={targetCommentEditor.draft}
                    onChange={(event) => targetCommentEditor.onChange(event.target.value)}
                    minRows={2}
                    disabled={targetCommentEditor.isSaving}
                    aria-label="Target comment"
                  />
                  <div className="text-unit-detail-page__meta-editor-actions">
                    <button
                      type="button"
                      className="text-unit-detail-page__button text-unit-detail-page__button--primary"
                      onClick={targetCommentEditor.onSave}
                      disabled={targetCommentEditor.isSaving}
                    >
                      {targetCommentEditor.isSaving ? 'Saving…' : 'Save'}
                    </button>
                    <button
                      type="button"
                      className="text-unit-detail-page__button"
                      onClick={targetCommentEditor.onCancel}
                      disabled={targetCommentEditor.isSaving}
                    >
                      Cancel
                    </button>
                  </div>
                </div>
              ) : (
                row.value
              )}
            </dd>
          </div>
        ))}
      </dl>
    </section>
  );
}
