import '../review-project/review-project-page.css';
import './text-unit-detail-page.css';

import { type ReactNode, useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';

import type { AiReviewSuggestion } from '../../api/ai-review';
import type { ApiGlossaryTerm, ApiMatchedGlossaryTerm } from '../../api/glossaries';
import { AiChatReview, type AiChatReviewMessage } from '../../components/AiChatReview';
import { AutoTextarea } from '../../components/AutoTextarea';
import { ConfirmModal } from '../../components/ConfirmModal';
import { GlossaryMatchesPanel } from '../../components/GlossaryMatchesPanel';
import { GlossaryTermEvidenceThumbnails } from '../../components/GlossaryTermEvidenceThumbnails';
import { IcuPreviewSection } from '../../components/IcuPreviewSection';
import { IntegrityCheckAlertModal } from '../../components/IntegrityCheckAlertModal';
import { Pill } from '../../components/Pill';
import { PillDropdown } from '../../components/PillDropdown';
import {
  TextUnitHistoryTimeline,
  type TextUnitHistoryTimelineComment as TextUnitDetailHistoryComment,
  type TextUnitHistoryTimelineEntry as TextUnitDetailHistoryRow,
} from '../../components/TextUnitHistoryTimeline';
import { TranslationTextEditor } from '../../components/TranslationTextEditor';
import type {
  VisibleTextEditorHandle,
  VisibleTextMarksMode,
} from '../../components/VisibleTextEditor';
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
  onBack: () => void;
  backLabel: string;
  isCmsHandoff: boolean;
  originContext: string | null;
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
  onBack,
  backLabel,
  isCmsHandoff,
  originContext,
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
  const [isCmsToolsExpanded, setIsCmsToolsExpanded] = useState(false);
  const translationEditorRef = useRef<VisibleTextEditorHandle | null>(null);
  const historyCount =
    historyRows.length + (showDeletedHistoryEntry ? 1 : 0) + (historyInitialDate ? 1 : 0);
  const historyTitle = isHistoryCountReady ? `History (${historyCount})` : 'History';
  const detailTitle = getDetailTitle({ editorInfo, isCmsHandoff });
  const showCmsTools = isCmsHandoff && !editorInfo.isSourceOnly;
  const cmsToolsTitle = getCmsToolsTitle(editorInfo);
  const detailHeaderName = isCmsHandoff ? detailTitle : `Text unit #${tmTextUnitId}`;

  useEffect(() => {
    setIsGlossaryScreenshotsCollapsed(false);
  }, [glossaryTerm?.tmTextUnitId, glossaryTermScreenshots.length]);

  useEffect(() => {
    setIsSourceScreenshotsCollapsed(false);
  }, [tmTextUnitId, sourceScreenshots.length]);

  useEffect(() => {
    setIsCmsToolsExpanded(false);
  }, [isCmsHandoff, tmTextUnitId]);

  const focusTranslationEditor = () => {
    translationEditorRef.current?.focus();
  };

  const icuPreviewPanel = !editorInfo.isSourceOnly ? (
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
  ) : null;
  const aiReviewPanel = !editorInfo.isSourceOnly ? (
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
  ) : null;
  const glossaryPanel = (
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
  );
  const historyPanel = (
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
  );
  const metadataPanel = (
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
  );

  return (
    <div className="review-project-page text-unit-detail-page">
      <header className="review-project-page__header">
        <div className="review-project-page__header-row">
          <div className="review-project-page__header-group review-project-page__header-group--left">
            <button
              type="button"
              className={`review-project-page__header-back-link${
                isCmsHandoff ? ' text-unit-detail-page__header-back-link--cms-handoff' : ''
              }`}
              onClick={onBack}
              aria-label={backLabel}
              title={backLabel}
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
              {isCmsHandoff ? (
                <span className="text-unit-detail-page__header-back-label">{backLabel}</span>
              ) : null}
            </button>
            <span className="review-project-page__header-name">{detailHeaderName}</span>
            <div className="text-unit-detail-page__header-context">
              <Pill>{keyInfo.locale}</Pill>
              {!isCmsHandoff ? (
                <span
                  className="text-unit-detail-page__header-repository"
                  title={keyInfo.repositoryName}
                >
                  {keyInfo.repositoryName}
                </span>
              ) : null}
              {originContext ? (
                <span className="text-unit-detail-page__header-origin" title={originContext}>
                  {originContext}
                </span>
              ) : null}
            </div>
          </div>
          <div className="review-project-page__header-group review-project-page__header-group--stats" />
          <div className="review-project-page__header-group review-project-page__header-group--meta" />
        </div>
      </header>

      <div className="text-unit-detail-page__content">
        <div
          className={`text-unit-detail-page__layout${
            isCmsHandoff ? ' text-unit-detail-page__layout--cms-handoff' : ''
          }`}
        >
          <section className="text-unit-detail-page__panel text-unit-detail-page__panel--editor">
            {isCmsHandoff ? <p className="text-unit-detail-page__eyebrow">Product copy</p> : null}
            <h1 className="text-unit-detail-page__title">{detailTitle}</h1>

            <div className="text-unit-detail-page__editor-field">
              <TranslationTextEditor
                ref={translationEditorRef}
                assisted={visibleTextEditor.enabled}
                ariaLabel={editorInfo.isSourceOnly ? 'Source text' : 'Translation'}
                className="text-unit-detail-page__editor-textarea"
                value={editorInfo.target}
                onChange={onChangeTarget}
                controlBar={
                  visibleTextEditor.enabled
                    ? {
                        marksMode: visibleTextEditor.marksMode,
                        onChangeMarksMode: visibleTextEditor.onChangeMarksMode,
                        protectedTokenCount: visibleTextEditor.protectedTokens.length,
                      }
                    : undefined
                }
                dir={visibleTextEditor.dir}
                disabled={!editorInfo.canEdit || editorInfo.isSaving}
                lang={previewLocale}
                minRows={1}
                placeholder="Add translated copy"
                protectedDiagnostics={visibleTextEditor.protectedDiagnostics}
                protectedTokens={visibleTextEditor.protectedTokens}
                marksMode={visibleTextEditor.marksMode}
                spellCheck={true}
                style={{ resize: 'none' }}
                validateNextValue={
                  visibleTextEditor.enabled ? visibleTextEditor.validateNextValue : undefined
                }
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

            {!isCmsHandoff ? icuPreviewPanel : null}

            {!isCmsHandoff ? aiReviewPanel : null}

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
            <section className="text-unit-detail-page__panel text-unit-detail-page__panel--section text-unit-detail-page__panel--source-context">
              {isCmsHandoff ? (
                <h2 className="text-unit-detail-page__source-context-title">Source context</h2>
              ) : null}
              <dl className="text-unit-detail-page__key-info">
                <div className="text-unit-detail-page__key-info-row">
                  <dt className="text-unit-detail-page__key-info-label">
                    <span>{isCmsHandoff ? 'Source copy' : 'Source'}</span>
                    {!isCmsHandoff && glossaryTermHref ? (
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
                  <dt>{isCmsHandoff ? 'Translator note' : 'Comment'}</dt>
                  <dd>
                    <pre className="text-unit-detail-page__key-info-text text-unit-detail-page__key-info-text--primary">
                      {glossaryTermComment}
                    </pre>
                  </dd>
                </div>
                {!isCmsHandoff && sourceScreenshots.length > 0 ? (
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
                {!isCmsHandoff && glossaryPartOfSpeech ? (
                  <div className="text-unit-detail-page__key-info-row">
                    <dt>POS</dt>
                    <dd>
                      <pre className="text-unit-detail-page__key-info-text">
                        {glossaryPartOfSpeech}
                      </pre>
                    </dd>
                  </div>
                ) : null}
                {!isCmsHandoff && glossaryTermType ? (
                  <div className="text-unit-detail-page__key-info-row">
                    <dt>Type</dt>
                    <dd>
                      <pre className="text-unit-detail-page__key-info-text">{glossaryTermType}</pre>
                    </dd>
                  </div>
                ) : null}
                {!isCmsHandoff && glossaryTermScreenshots.length > 0 ? (
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
                {!isCmsHandoff && !glossaryTermMetadata ? (
                  <div className="text-unit-detail-page__key-info-row">
                    <dt>Id</dt>
                    <dd>
                      <pre className="text-unit-detail-page__key-info-text">{keyInfo.stringId}</pre>
                    </dd>
                  </div>
                ) : null}
              </dl>
            </section>

            {showCmsTools ? (
              <section className="text-unit-detail-page__panel text-unit-detail-page__panel--section text-unit-detail-page__panel--cms-tools">
                <SectionHeader
                  title={cmsToolsTitle}
                  expanded={isCmsToolsExpanded}
                  onToggle={() => setIsCmsToolsExpanded((current) => !current)}
                  summary={
                    <span className="text-unit-detail-page__cms-tools-summary">Optional</span>
                  }
                />
                {isCmsToolsExpanded ? (
                  <div className="text-unit-detail-page__cms-tools-body">
                    {icuPreviewPanel}
                    {aiReviewPanel}
                    {glossaryPanel}
                    {historyPanel}
                    {metadataPanel}
                    <div className="text-unit-detail-page__cms-tools-return">
                      <button
                        type="button"
                        className="text-unit-detail-page__button"
                        onClick={focusTranslationEditor}
                      >
                        Back to translation
                      </button>
                    </div>
                  </div>
                ) : null}
              </section>
            ) : !isCmsHandoff ? (
              <>
                {glossaryPanel}
                {historyPanel}
                {metadataPanel}
              </>
            ) : null}
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

function getDetailTitle({
  editorInfo,
  isCmsHandoff,
}: {
  editorInfo: TextUnitDetailPageViewProps['editorInfo'];
  isCmsHandoff: boolean;
}) {
  if (editorInfo.isSourceOnly) {
    return isCmsHandoff ? 'Source copy' : 'Source';
  }

  if (!isCmsHandoff) {
    return 'Translation';
  }

  return editorInfo.status === 'To review' || editorInfo.status === 'Accepted'
    ? 'Review translation'
    : 'Translate copy';
}

function getCmsToolsTitle(editorInfo: TextUnitDetailPageViewProps['editorInfo']) {
  return editorInfo.status === 'To review' || editorInfo.status === 'Accepted'
    ? 'Review help'
    : 'Translation help';
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
