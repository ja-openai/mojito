import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  type FormEvent,
  type ReactNode,
  type Ref,
  type SyntheticEvent,
  useCallback,
  useEffect,
  useId,
  useMemo,
  useRef,
  useState,
} from 'react';
import { Link } from 'react-router-dom';

import {
  type ApiCmsContentType,
  type ApiCmsContentTypeField,
  type ApiCmsEntry,
  type ApiCmsEntryCompleteness,
  type ApiCmsEntryStatus,
  type ApiCmsFieldCompleteness,
  type ApiCmsFieldMapping,
  type ApiCmsFieldType,
  type ApiCmsLocaleCompleteness,
  type ApiCmsProjectCompleteness,
  type ApiCmsProjectDetail,
  type ApiCmsProjectSummary,
  type ApiCmsReleaseChange,
  type ApiCmsReleaseChangeSummary,
  type ApiCmsVariant,
  type ApiCmsVariantStatus,
  fetchCmsFieldTranslation,
} from '../../api/content-cms';
import { saveTextUnit, type SaveTextUnitRequest } from '../../api/text-units';
import { AutoTextarea } from '../../components/AutoTextarea';
import { LocaleMultiSelect, type LocaleOption } from '../../components/LocaleMultiSelect';
import { useUser } from '../../hooks/useUser';
import { formatLocalDateTime as formatDateTime } from '../../utils/dateTime';
import { checkTextUnitIntegrityWithRetry } from '../../utils/integrityCheck';
import { useLocaleDisplayNameResolver } from '../../utils/localeDisplayNames';
import { canEditLocale as canEditLocaleForUser } from '../../utils/permissions';
import { buildCmsTextUnitDetailLink, type CmsTextUnitContext } from '../../utils/textUnitDetailUrl';
import { formatStatus, mapUiStatusToApi } from '../workbench/workbench-helpers';
import {
  cmsReleasePanelId,
  type CopyFieldFocusTarget,
  focusCmsReleasePanel,
  queueCopyBlockEditorScroll,
  queueCopyFieldScroll,
} from './content-cms-admin-scroll';
import {
  type AuthoringFieldDraftState,
  type AuthoringMappingDraftState,
  buildMappingDraft,
  type ContentTypeDraft,
  type EntryDraft,
  type FieldDraft,
  findContentType,
  type FirstCopyBlockDraft,
  formatBool,
  hasReadyCmsTargetLocales,
  initialEntryDraft,
  initialSourceCopyDraft,
  type MappingDraft,
  type MappingSource,
  onboardingProjectStarter,
  type ProjectDraft,
  type ProjectEditDraft,
  type SourceCopyDraft,
  updateSuggestedCmsKey,
  type VariantDraft,
  welcomeEmailCopyBlockStarter,
} from './content-cms-admin-types';

const deliveryHintOptions = [
  { value: 'BLOB_CDN', label: 'Blob/CDN' },
  { value: 'STATSIG_DYNAMIC_CONFIG', label: 'Statsig dynamic config' },
  { value: 'EXPERIENCE_FRAMEWORK', label: 'Experience framework' },
] as const;
const cmsAuthorTargetLocaleTags = [
  'fr-FR',
  'de-DE',
  'es-ES',
  'ja-JP',
  'ko-KR',
  'pt-BR',
  'zh-CN',
  'zh-TW',
] as const;
const cmsAuthorTargetLocalePreviewCount = 8;
const cmsBlockReleasePanelId = 'content-cms-block-release-panel';
const cmsFirstWritingSpaceOptionsId = 'content-cms-first-writing-space-options';
const cmsFirstCopyBlockOptionsId = 'content-cms-first-copy-block-options';
const cmsNewCopyBlockDetailsId = 'content-cms-new-copy-block-details';
const cmsAuthorReleaseDetailsId = 'content-cms-author-release-details';
const cmsAuthorReleaseChangeGroupsId = 'content-cms-author-release-change-groups';
const authorSourceCopyMinRows = 2;
const authorTranslatorNoteMinRows = 1;
const authorReleaseBlockerPreviewCount = 2;
const authorReleaseChangePreviewCount = 4;

type CmsActionConfirmation = {
  title: string;
  body: string;
  confirmLabel: string;
  cancelLabel?: string;
  confirmVariant?: 'danger' | 'primary';
};

function formatSourceCopyPlaceholder(sourceLocaleLabel: string) {
  return `Write source copy in ${sourceLocaleLabel}`;
}

function hasAuthoringDraftText(value: string) {
  return value.trim().length > 0;
}

function hasRequiredFirstCopyBlockDraft(draft: FirstCopyBlockDraft) {
  return hasAuthoringDraftText(draft.entryName) && hasRequiredSourceCopyDraft(draft);
}

function hasRequiredSourceCopyDraft(
  draft: Pick<SourceCopyDraft, 'sourceContent' | 'sourceComment'>,
) {
  return hasAuthoringDraftText(draft.sourceContent) && hasAuthoringDraftText(draft.sourceComment);
}

function hasRequiredSavedSourceCopy(
  mapping: Pick<ApiCmsFieldMapping, 'sourceContent' | 'sourceComment'>,
) {
  return hasRequiredSourceCopyDraft({
    sourceContent: mapping.sourceContent ?? '',
    sourceComment: mapping.sourceComment ?? '',
  });
}

function WelcomeEmailStarterButton({
  disabled,
  onClick,
}: {
  disabled: boolean;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      className="settings-button settings-button--ghost"
      onClick={onClick}
      disabled={disabled}
    >
      Use welcome email starter
    </button>
  );
}

function CmsFirstAuthoringOptions({
  optionsId,
  open,
  disabled,
  entryDescription,
  projectDescription,
  onOpenChange,
  onEntryDescriptionChange,
  onProjectDescriptionChange,
}: {
  optionsId: string;
  open: boolean;
  disabled: boolean;
  entryDescription: string;
  projectDescription?: string;
  onOpenChange: (open: boolean) => void;
  onEntryDescriptionChange: (entryDescription: string) => void;
  onProjectDescriptionChange?: (projectDescription: string) => void;
}) {
  return (
    <div className="content-cms-admin-page__authoring-options content-cms-admin-page__field-span">
      <div className="content-cms-admin-page__authoring-options-actions">
        <button
          type="button"
          className="content-cms-admin-page__authoring-options-trigger"
          aria-expanded={open}
          aria-controls={optionsId}
          onClick={() => onOpenChange(!open)}
        >
          Add details
        </button>
      </div>
      {open ? (
        <div
          id={optionsId}
          className="content-cms-admin-page__authoring-options-body"
          aria-label="Optional details"
        >
          <fieldset
            className="content-cms-admin-page__authoring-form-section"
            aria-label="Content item details"
          >
            <legend>Content item details</legend>
            <label className="settings-field">
              <span className="settings-field__label">Where this item appears</span>
              <input
                className="settings-input"
                value={entryDescription}
                onChange={(event) => onEntryDescriptionChange(event.target.value)}
                placeholder="Signup confirmation email"
                disabled={disabled}
              />
            </label>
          </fieldset>
          {projectDescription != null && onProjectDescriptionChange != null ? (
            <fieldset
              className="content-cms-admin-page__authoring-form-section"
              aria-label="Copy collection details"
            >
              <legend>Copy collection details</legend>
              <label className="settings-field">
                <span className="settings-field__label">Copy collection description</span>
                <textarea
                  className="settings-input content-cms-admin-page__textarea"
                  value={projectDescription}
                  onChange={(event) => onProjectDescriptionChange(event.target.value)}
                  disabled={disabled}
                />
              </label>
            </fieldset>
          ) : null}
        </div>
      ) : null}
    </div>
  );
}

export function ProjectList({
  projects,
  totalCount,
  selectedProjectId,
  hasSearchQuery,
  disabled,
  isLoading,
  isError,
  onRetry,
  onSelect,
}: {
  projects: ApiCmsProjectSummary[];
  totalCount: number;
  selectedProjectId: number | null;
  hasSearchQuery: boolean;
  disabled: boolean;
  isLoading: boolean;
  isError: boolean;
  onRetry: () => void;
  onSelect: (projectId: number) => void;
}) {
  if (isLoading) {
    return <p className="settings-page__hint">Loading copy collections.</p>;
  }
  if (isError) {
    return (
      <div className="content-cms-admin-page__query-state">
        <p className="settings-hint is-error">Failed to load copy collections.</p>
        <button type="button" className="settings-button" onClick={onRetry}>
          Try again
        </button>
      </div>
    );
  }
  if (projects.length === 0) {
    return (
      <p className="settings-page__hint">
        {hasSearchQuery ? 'No matching copy collections.' : 'No copy collections yet.'}
      </p>
    );
  }

  return (
    <>
      {totalCount > projects.length ? (
        <p className="settings-page__hint">
          Showing {projects.length} of {totalCount} copy collections. Refine search to find more.
        </p>
      ) : null}
      <div className="content-cms-admin-page__project-list">
        {projects.map((project) => (
          <button
            key={project.id}
            type="button"
            className={`content-cms-admin-page__project-row${
              selectedProjectId === project.id ? ' is-active' : ''
            }`}
            aria-label={`Open copy collection ${project.name}`}
            aria-current={selectedProjectId === project.id ? 'true' : undefined}
            disabled={disabled}
            onClick={() => onSelect(project.id)}
          >
            <span className="content-cms-admin-page__project-row-name">{project.name}</span>
            {project.description ? <span>{project.description}</span> : null}
          </button>
        ))}
      </div>
    </>
  );
}

export function StartWritingSpaceButton({
  buttonRef,
  disabled,
  onClick,
}: {
  buttonRef?: Ref<HTMLButtonElement>;
  disabled: boolean;
  onClick: () => void;
}) {
  return (
    <button
      ref={buttonRef}
      type="button"
      className="settings-button content-cms-admin-page__start-writing-space"
      disabled={disabled}
      onClick={onClick}
    >
      New copy collection
    </button>
  );
}

export function CopyCollectionsSidebarToggle({
  controlsId,
  open,
  onOpenChange,
}: {
  controlsId: string;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  return (
    <button
      type="button"
      className="content-cms-admin-page__sidebar-toggle"
      aria-expanded={open}
      aria-controls={controlsId}
      onClick={() => onOpenChange(!open)}
    >
      <span>Copy collections</span>
      <span aria-hidden="true" className="content-cms-admin-page__sidebar-toggle-chevron" />
    </button>
  );
}

export function FirstWritingSpaceForm({
  projectDraft,
  firstCopyBlockDraft,
  disabled,
  focusCopyCollectionName = false,
  authoringRecovery = null,
  onProjectDraftChange,
  onFirstCopyBlockDraftChange,
  onCancel,
  onSubmit,
}: {
  projectDraft: ProjectDraft;
  firstCopyBlockDraft: FirstCopyBlockDraft;
  disabled: boolean;
  focusCopyCollectionName?: boolean;
  authoringRecovery?: Extract<AuthoringRecovery, { kind: 'first-writing-space' }> | null;
  onProjectDraftChange: (draft: ProjectDraft) => void;
  onFirstCopyBlockDraftChange: (draft: FirstCopyBlockDraft) => void;
  onCancel?: () => void;
  onSubmit: (event: FormEvent) => void;
}) {
  const copyCollectionNameRef = useRef<HTMLInputElement>(null);
  const canUseStarter =
    projectDraft.name.trim().length === 0 &&
    projectDraft.description.trim().length === 0 &&
    firstCopyBlockDraft.entryName.trim().length === 0 &&
    firstCopyBlockDraft.entryDescription.trim().length === 0 &&
    firstCopyBlockDraft.sourceContent.trim().length === 0 &&
    firstCopyBlockDraft.sourceComment.trim().length === 0;
  const canStartWriting =
    hasAuthoringDraftText(projectDraft.name) && hasRequiredFirstCopyBlockDraft(firstCopyBlockDraft);
  const [optionsOpen, setOptionsOpen] = useState(
    () =>
      hasAuthoringDraftText(firstCopyBlockDraft.entryDescription) ||
      hasAuthoringDraftText(projectDraft.description),
  );

  useEffect(() => {
    if (
      hasAuthoringDraftText(firstCopyBlockDraft.entryDescription) ||
      hasAuthoringDraftText(projectDraft.description)
    ) {
      setOptionsOpen(true);
    }
  }, [firstCopyBlockDraft.entryDescription, projectDraft.description]);
  useEffect(() => {
    if (!focusCopyCollectionName) {
      return;
    }
    window.setTimeout(() => copyCollectionNameRef.current?.focus({ preventScroll: true }), 0);
  }, [focusCopyCollectionName]);

  return (
    <form
      className="content-cms-admin-page__form content-cms-admin-page__clean-start"
      onSubmit={onSubmit}
    >
      <div className="content-cms-admin-page__clean-start-header content-cms-admin-page__field-span">
        <h2>Start with product copy</h2>
        <p className="settings-page__hint">
          Name the copy collection and first content item, then write source copy for translation.
        </p>
      </div>
      <AuthoringRecoveryPanel
        authoringRecovery={authoringRecovery}
        className="content-cms-admin-page__field-span"
      />
      <fieldset className="content-cms-admin-page__authoring-form-section content-cms-admin-page__field-span">
        <legend>Name the first copy</legend>
        <div className="content-cms-admin-page__authoring-form-section-grid">
          <label className="settings-field">
            <span className="settings-field__label">Copy collection name</span>
            <input
              ref={copyCollectionNameRef}
              className="settings-input"
              value={projectDraft.name}
              onChange={(event) =>
                onProjectDraftChange({
                  ...projectDraft,
                  name: event.target.value,
                  projectKey: updateSuggestedCmsKey(
                    projectDraft.projectKey,
                    projectDraft.name,
                    event.target.value,
                    'project',
                  ),
                })
              }
              placeholder="Growth email copy"
              disabled={disabled}
              required
            />
          </label>
          <label className="settings-field">
            <span className="settings-field__label">Content item name</span>
            <input
              className="settings-input"
              value={firstCopyBlockDraft.entryName}
              onChange={(event) =>
                onFirstCopyBlockDraftChange({
                  ...firstCopyBlockDraft,
                  entryKey: updateSuggestedCmsKey(
                    firstCopyBlockDraft.entryKey,
                    firstCopyBlockDraft.entryName,
                    event.target.value,
                    'first-block',
                  ),
                  entryName: event.target.value,
                })
              }
              placeholder="Welcome email"
              disabled={disabled}
              required
            />
          </label>
        </div>
      </fieldset>
      <fieldset className="content-cms-admin-page__authoring-form-section content-cms-admin-page__field-span">
        <legend>Write the copy</legend>
        <div className="content-cms-admin-page__authoring-form-section-grid">
          <label className="settings-field content-cms-admin-page__field-span">
            <span className="settings-field__label">Source copy</span>
            <AutoTextarea
              className="settings-input content-cms-admin-page__textarea content-cms-admin-page__textarea--copy"
              value={firstCopyBlockDraft.sourceContent}
              minRows={authorSourceCopyMinRows}
              maxRows={18}
              onChange={(event) =>
                onFirstCopyBlockDraftChange({
                  ...firstCopyBlockDraft,
                  sourceContent: event.target.value,
                })
              }
              placeholder="Write source copy"
              disabled={disabled}
              required
            />
          </label>
          <label className="settings-field content-cms-admin-page__field-span">
            <span className="content-cms-admin-page__field-label-row">
              <span className="settings-field__label">Translator note</span>
              <span className="content-cms-admin-page__required-hint" aria-hidden="true">
                Required for translation
              </span>
            </span>
            <AutoTextarea
              aria-label="Translator note"
              className="settings-input content-cms-admin-page__textarea content-cms-admin-page__textarea--translator-note"
              value={firstCopyBlockDraft.sourceComment}
              minRows={authorTranslatorNoteMinRows}
              maxRows={8}
              onChange={(event) =>
                onFirstCopyBlockDraftChange({
                  ...firstCopyBlockDraft,
                  sourceComment: event.target.value,
                })
              }
              placeholder="Tone, limits, placeholders, or translator instructions"
              disabled={disabled}
              required
            />
          </label>
        </div>
      </fieldset>
      <div className="content-cms-admin-page__actions content-cms-admin-page__field-span">
        <button
          type="submit"
          className="settings-button settings-button--primary"
          disabled={disabled || !canStartWriting}
        >
          Start writing
        </button>
        {canUseStarter ? (
          <WelcomeEmailStarterButton
            disabled={disabled}
            onClick={() => {
              onProjectDraftChange({
                ...projectDraft,
                name: onboardingProjectStarter.name,
                description: onboardingProjectStarter.description,
                projectKey: updateSuggestedCmsKey(
                  projectDraft.projectKey,
                  projectDraft.name,
                  onboardingProjectStarter.name,
                  'project',
                ),
              });
              onFirstCopyBlockDraftChange({
                ...firstCopyBlockDraft,
                entryKey: updateSuggestedCmsKey(
                  firstCopyBlockDraft.entryKey,
                  firstCopyBlockDraft.entryName,
                  welcomeEmailCopyBlockStarter.entryName,
                  'first-block',
                ),
                ...welcomeEmailCopyBlockStarter,
              });
            }}
          />
        ) : null}
        {onCancel != null ? (
          <button type="button" className="settings-button" onClick={onCancel} disabled={disabled}>
            Cancel
          </button>
        ) : null}
      </div>
      <CmsFirstAuthoringOptions
        optionsId={cmsFirstWritingSpaceOptionsId}
        open={optionsOpen}
        disabled={disabled}
        entryDescription={firstCopyBlockDraft.entryDescription}
        projectDescription={projectDraft.description}
        onOpenChange={setOptionsOpen}
        onEntryDescriptionChange={(entryDescription) =>
          onFirstCopyBlockDraftChange({
            ...firstCopyBlockDraft,
            entryDescription,
          })
        }
        onProjectDescriptionChange={(description) =>
          onProjectDraftChange({ ...projectDraft, description })
        }
      />
    </form>
  );
}

export function CmsEmptyState({ children }: { children?: ReactNode }) {
  return (
    <section className="content-cms-admin-page__empty-state">
      {children ? (
        children
      ) : (
        <div className="content-cms-admin-page__next-action">
          <span>First action</span>
          <strong>Create or select a copy collection to start writing copy.</strong>
        </div>
      )}
    </section>
  );
}

export function FirstCopyBlockForm({
  draft,
  disabled,
  authoringRecovery = null,
  onChange,
  onSubmit,
}: {
  draft: FirstCopyBlockDraft;
  disabled: boolean;
  authoringRecovery?: Extract<AuthoringRecovery, { kind: 'first-copy-block' }> | null;
  onChange: (draft: FirstCopyBlockDraft) => void;
  onSubmit: (event: FormEvent) => void;
}) {
  const canUseStarter =
    draft.entryName.trim().length === 0 &&
    draft.entryDescription.trim().length === 0 &&
    draft.sourceContent.trim().length === 0 &&
    draft.sourceComment.trim().length === 0;
  const canSaveFirstCopyBlock = hasRequiredFirstCopyBlockDraft(draft);
  const [optionsOpen, setOptionsOpen] = useState(() =>
    hasAuthoringDraftText(draft.entryDescription),
  );

  useEffect(() => {
    if (hasAuthoringDraftText(draft.entryDescription)) {
      setOptionsOpen(true);
    }
  }, [draft.entryDescription]);

  return (
    <form className="content-cms-admin-page__new-block" onSubmit={onSubmit}>
      <div className="content-cms-admin-page__new-block-header content-cms-admin-page__field-span">
        <div>
          <h2>Write the first content item</h2>
          <p className="settings-page__hint">
            Name this content item, then write the source copy and translator note.
          </p>
        </div>
      </div>
      <fieldset className="content-cms-admin-page__authoring-form-section content-cms-admin-page__field-span">
        <legend>Name this content item</legend>
        <div className="content-cms-admin-page__authoring-form-section-grid">
          <label className="settings-field">
            <span className="settings-field__label">Content item name</span>
            <input
              className="settings-input"
              value={draft.entryName}
              onChange={(event) =>
                onChange({
                  ...draft,
                  entryKey: updateSuggestedCmsKey(
                    draft.entryKey,
                    draft.entryName,
                    event.target.value,
                    'first-block',
                  ),
                  entryName: event.target.value,
                })
              }
              placeholder="Welcome email"
              disabled={disabled}
              required
            />
          </label>
        </div>
      </fieldset>
      <fieldset className="content-cms-admin-page__authoring-form-section content-cms-admin-page__field-span">
        <legend>Write the copy</legend>
        <div className="content-cms-admin-page__authoring-form-section-grid">
          <label className="settings-field content-cms-admin-page__field-span">
            <span className="settings-field__label">Source copy</span>
            <AutoTextarea
              className="settings-input content-cms-admin-page__textarea content-cms-admin-page__textarea--copy"
              value={draft.sourceContent}
              minRows={authorSourceCopyMinRows}
              maxRows={18}
              onChange={(event) => onChange({ ...draft, sourceContent: event.target.value })}
              placeholder="Write source copy"
              disabled={disabled}
              required
            />
          </label>
          <label className="settings-field content-cms-admin-page__field-span">
            <span className="content-cms-admin-page__field-label-row">
              <span className="settings-field__label">Translator note</span>
              <span className="content-cms-admin-page__required-hint" aria-hidden="true">
                Required for translation
              </span>
            </span>
            <AutoTextarea
              aria-label="Translator note"
              className="settings-input content-cms-admin-page__textarea content-cms-admin-page__textarea--translator-note"
              value={draft.sourceComment}
              minRows={authorTranslatorNoteMinRows}
              maxRows={8}
              onChange={(event) => onChange({ ...draft, sourceComment: event.target.value })}
              placeholder="Tone, limits, placeholders, or translator instructions"
              disabled={disabled}
              required
            />
          </label>
        </div>
      </fieldset>
      <div className="content-cms-admin-page__actions content-cms-admin-page__field-span">
        <button
          type="submit"
          className="settings-button settings-button--primary"
          disabled={disabled || !canSaveFirstCopyBlock}
        >
          Save first content item
        </button>
        {canUseStarter ? (
          <WelcomeEmailStarterButton
            disabled={disabled}
            onClick={() =>
              onChange({
                ...draft,
                entryKey: updateSuggestedCmsKey(
                  draft.entryKey,
                  draft.entryName,
                  welcomeEmailCopyBlockStarter.entryName,
                  'first-block',
                ),
                ...welcomeEmailCopyBlockStarter,
              })
            }
          />
        ) : null}
      </div>
      <CmsFirstAuthoringOptions
        optionsId={cmsFirstCopyBlockOptionsId}
        open={optionsOpen}
        disabled={disabled}
        entryDescription={draft.entryDescription}
        onOpenChange={setOptionsOpen}
        onEntryDescriptionChange={(entryDescription) => onChange({ ...draft, entryDescription })}
      />
      <AuthoringRecoveryPanel
        authoringRecovery={authoringRecovery}
        className="content-cms-admin-page__field-span"
      />
    </form>
  );
}

export function FirstCopyBlockIdentifiers({
  draft,
  disabled,
  onChange,
}: {
  draft: FirstCopyBlockDraft;
  disabled: boolean;
  onChange: (draft: FirstCopyBlockDraft) => void;
}) {
  return (
    <section className="content-cms-admin-page__form">
      <h3>First copy identifiers</h3>
      <p className="settings-page__hint content-cms-admin-page__field-span">
        Override only when another system already depends on these IDs. The first content item
        starts with one default copy field; add named fields later only when the content item needs
        them.
      </p>
      <label className="settings-field">
        <span className="settings-field__label">Block key</span>
        <input
          className="settings-input"
          value={draft.entryKey}
          onChange={(event) => onChange({ ...draft, entryKey: event.target.value })}
          disabled={disabled}
          required
        />
      </label>
      <label className="settings-field">
        <span className="settings-field__label">Field key</span>
        <input
          className="settings-input"
          value={draft.fieldKey}
          onChange={(event) => onChange({ ...draft, fieldKey: event.target.value })}
          disabled={disabled}
          required
        />
      </label>
    </section>
  );
}

export function CmsEntryWorkspace({
  projectId,
  projectName,
  projectKey,
  sourceLocale,
  entries,
  contentTypes,
  selectedEntry,
  selectedEntryId,
  selectedVariant,
  selectedField,
  selectedEntryCompleteness,
  selectedEntryCompletenessLoading,
  selectedEntryCompletenessRefreshing,
  selectedEntryCompletenessError,
  targetLocaleOptions,
  targetLocaleTagsDraft,
  targetLocalesLoading,
  targetLocalesError,
  targetLocalesSaving,
  newEntryDraft,
  newEntrySourceDrafts,
  entryDraft,
  staleSavedEntryDraft,
  mappingDraft,
  authoringFieldDrafts,
  authoringMappingDrafts,
  authoringRecovery = null,
  entryContextDirty,
  hasDirtyFieldContextDrafts,
  hasDirtySourceCopyDrafts,
  hasDirtyInlineTranslation,
  pendingInlineTranslationFieldSwitch = null,
  authorReleasePanel = null,
  inlineTranslationReleaseBlockerTargets = [],
  disabled,
  reviewEntryRequestKey,
  reviewEntryId,
  reviewFieldId,
  reviewLocaleTag,
  reviewRepairTarget,
  reviewReason,
  reviewReturnMode,
  reviewLastReleasedSourceContent,
  reviewLastReleasedTranslationContent,
  reviewReturnToRelease,
  reviewRefreshTranslation,
  reviewTranslationRepairSaved,
  reviewTranslationReconnectErrorMessage,
  reviewTranslationReconnectRefreshFailed,
  reviewTranslationReconnectRefreshPending,
  sourceCopyConflictRefreshFailed,
  sourceCopyConflictRefreshPending,
  reviewRequestedLocaleSetupRefreshPending,
  onReturnToRelease,
  onEntryChange,
  onNewEntryOpenChange,
  onStartNewEntry,
  onCancelNewEntry,
  onNewEntryDraftChange,
  onNewEntrySourceDraftsChange,
  onNewEntrySubmit,
  onEntryDraftChange,
  onEntrySubmit,
  onResetEntryDraft,
  onEntryReleaseStatusChange,
  onFieldFocus,
  onFieldContextChange,
  onFieldContextSubmit,
  onResetFieldContextDraft,
  onMappingDraftChange,
  onResetMappingDraft,
  onMappingSubmit,
  onSaveAll,
  onInlineTranslationDirtyChange,
  onInlineTranslationReleaseBlockerChange,
  onInlineTranslationSavingChange,
  onInlineTranslationSaved,
  onRequestConfirmation,
  onRetryEntryCompleteness,
  onRefreshStaleTranslation,
  onRetrySourceCopyConflictRefresh,
  onTargetLocaleTagsChange,
  onAddTargetLocales,
  onAddRequestedTargetLocale,
  onRetryTargetLocales,
  onReconnectTranslation,
  onOpenAdvancedSetup,
}: {
  projectId: number;
  projectName: string;
  projectKey: string | null | undefined;
  sourceLocale: string;
  entries: ApiCmsEntry[];
  contentTypes: ApiCmsContentType[];
  selectedEntry: ApiCmsEntry | null;
  selectedEntryId: string;
  selectedVariant: ApiCmsVariant | null;
  selectedField: ApiCmsContentTypeField | null;
  selectedEntryCompleteness: ApiCmsEntryCompleteness | null;
  selectedEntryCompletenessLoading: boolean;
  selectedEntryCompletenessRefreshing: boolean;
  selectedEntryCompletenessError: boolean;
  targetLocaleOptions: LocaleOption[];
  targetLocaleTagsDraft: string[];
  targetLocalesLoading: boolean;
  targetLocalesError: boolean;
  targetLocalesSaving: boolean;
  newEntryDraft: EntryDraft;
  newEntrySourceDrafts: Record<string, SourceCopyDraft>;
  entryDraft: EntryDraft;
  staleSavedEntryDraft: EntryDraft | null;
  mappingDraft: MappingDraft;
  authoringFieldDrafts: Record<string, AuthoringFieldDraftState>;
  authoringMappingDrafts: Record<string, AuthoringMappingDraftState>;
  authoringRecovery?: AuthoringRecovery | null;
  entryContextDirty: boolean;
  hasDirtyFieldContextDrafts: boolean;
  hasDirtySourceCopyDrafts: boolean;
  hasDirtyInlineTranslation: boolean;
  pendingInlineTranslationFieldSwitch?: {
    sourceFieldId: string;
    targetFieldId: string;
    targetFieldName: string;
    reason: 'dirty-translation' | 'refresh-error';
  } | null;
  authorReleasePanel?: ReactNode;
  inlineTranslationReleaseBlockerTargets?: InlineTranslationReleaseBlockerTarget[];
  disabled: boolean;
  reviewEntryRequestKey: number;
  reviewEntryId: string | null;
  reviewFieldId: string | null;
  reviewLocaleTag: string | null;
  reviewRepairTarget: ReleaseRepairTarget | null;
  reviewReason: string | null;
  reviewReturnMode: ReleaseReviewReturnMode | null;
  reviewLastReleasedSourceContent: string | null;
  reviewLastReleasedTranslationContent: string | null;
  reviewReturnToRelease: boolean;
  reviewRefreshTranslation: boolean;
  reviewTranslationRepairSaved: boolean;
  reviewTranslationReconnectErrorMessage: string | null;
  reviewTranslationReconnectRefreshFailed: boolean;
  reviewTranslationReconnectRefreshPending: boolean;
  sourceCopyConflictRefreshFailed: boolean;
  sourceCopyConflictRefreshPending: boolean;
  reviewRequestedLocaleSetupRefreshPending: boolean;
  onReturnToRelease: () => void;
  onEntryChange: (entryId: string, afterChange?: () => void) => void;
  onNewEntryOpenChange: (open: boolean) => void;
  onStartNewEntry: (onReady: () => void) => void;
  onCancelNewEntry: (onCancel: () => void) => void;
  onNewEntryDraftChange: (draft: EntryDraft) => void;
  onNewEntrySourceDraftsChange: (drafts: Record<string, SourceCopyDraft>) => void;
  onNewEntrySubmit: (event: FormEvent) => void;
  onEntryDraftChange: (draft: EntryDraft) => void;
  onEntrySubmit: (event: FormEvent) => void;
  onResetEntryDraft: () => void;
  onEntryReleaseStatusChange: (status: ApiCmsEntryStatus) => void;
  onFieldFocus: (
    fieldId: string,
    options?: { cancelPendingFieldSwitch?: boolean; focusTarget?: CopyFieldFocusTarget },
  ) => boolean;
  onFieldContextChange: (fieldId: string, description: string) => void;
  onFieldContextSubmit: (fieldId: string, event: FormEvent) => void;
  onResetFieldContextDraft: (fieldId: string) => void;
  onMappingDraftChange: (fieldId: string, draft: MappingDraft) => void;
  onResetMappingDraft: (fieldId: string) => void;
  onMappingSubmit: (fieldId: string, event: FormEvent) => void;
  onSaveAll: () => void;
  onInlineTranslationDirtyChange: (dirty: boolean) => void;
  onInlineTranslationReleaseBlockerChange: (
    blockers: InlineTranslationReleaseBlockerTarget[],
  ) => void;
  onInlineTranslationSavingChange: (saving: boolean) => void;
  onInlineTranslationSaved: () => void;
  onRequestConfirmation: (
    confirmation: CmsActionConfirmation,
    onConfirm: () => void,
    onCancel?: (() => void) | null,
  ) => void;
  onRetryEntryCompleteness: () => void;
  onRefreshStaleTranslation: () => void;
  onRetrySourceCopyConflictRefresh: () => void;
  onTargetLocaleTagsChange: (localeTags: string[]) => void;
  onAddTargetLocales: (event: FormEvent) => void;
  onAddRequestedTargetLocale: (localeTag: string) => void;
  onRetryTargetLocales: () => void;
  onReconnectTranslation: (fieldId: string, localeTag: string) => void;
  onOpenAdvancedSetup: (localeTag?: string | null) => void;
}) {
  const resolveLocaleDisplayName = useLocaleDisplayNameResolver();
  const sourceLocaleLabel = resolveLocaleDisplayName(sourceLocale);
  const sourceCopyPlaceholder = formatSourceCopyPlaceholder(sourceLocaleLabel);
  const selectedContentType = selectedEntry
    ? findContentType(contentTypes, selectedEntry.contentTypeId)
    : null;
  const localizableFields = sortLocalizableFields(selectedContentType?.fields ?? []);
  const mappedFieldCount =
    selectedVariant == null
      ? 0
      : localizableFields.filter((field) =>
          selectedVariant.fieldMappings.some((mapping) => mapping.fieldId === field.id),
        ).length;
  const hasSavedRequiredSourceCopy =
    selectedVariant != null &&
    localizableFields
      .filter((field) => field.required)
      .every((field) => {
        const mapping = selectedVariant.fieldMappings.find((item) => item.fieldId === field.id);
        return mapping != null && hasRequiredSavedSourceCopy(mapping);
      });
  const selectedEntryNeedsSourceCopy =
    selectedEntry != null && localizableFields.length > 0 && mappedFieldCount === 0;
  const selectedEntryReviewActive =
    selectedEntry != null && reviewEntryId === String(selectedEntry.id);
  const activeReviewEntryRequestKey = selectedEntryReviewActive ? reviewEntryRequestKey : 0;
  const activeReviewFieldId = selectedEntryReviewActive ? reviewFieldId : null;
  const activeReviewLocaleTag = selectedEntryReviewActive ? reviewLocaleTag : null;
  const activeReviewRepairTarget = selectedEntryReviewActive ? reviewRepairTarget : null;
  const activeReviewReason = selectedEntryReviewActive ? reviewReason : null;
  const activeReviewReturnMode = selectedEntryReviewActive ? reviewReturnMode : null;
  const activeReviewLastReleasedSourceContent = selectedEntryReviewActive
    ? reviewLastReleasedSourceContent
    : null;
  const activeReviewLastReleasedTranslationContent = selectedEntryReviewActive
    ? reviewLastReleasedTranslationContent
    : null;
  const activeReviewReturnToRelease = selectedEntryReviewActive && reviewReturnToRelease;
  const activeReviewRefreshTranslation = selectedEntryReviewActive && reviewRefreshTranslation;
  const activeReviewTranslationRepairSaved =
    selectedEntryReviewActive && reviewTranslationRepairSaved;
  const activeReviewTranslationReconnectErrorMessage = selectedEntryReviewActive
    ? reviewTranslationReconnectErrorMessage
    : null;
  const activeReviewTranslationReconnectRefreshFailed =
    selectedEntryReviewActive && reviewTranslationReconnectRefreshFailed;
  const activeReviewTranslationReconnectRefreshPending =
    selectedEntryReviewActive && reviewTranslationReconnectRefreshPending;
  const activeReviewRequestedLocaleSetupRefreshPending =
    selectedEntryReviewActive && reviewRequestedLocaleSetupRefreshPending;
  const selectedEntryDetailsId =
    selectedEntry == null ? null : getCopyBlockDetailsId(String(selectedEntry.id));
  const selectedEntryOptionsId =
    selectedEntry == null ? null : getCopyBlockOptionsId(String(selectedEntry.id));
  const authoringSaveStatusId =
    selectedEntry == null ? null : `${getCopyBlockEditorAnchorId(String(selectedEntry.id))}-save`;
  const savedItemDetailsRecovery =
    authoringRecovery?.kind === 'block-details' &&
    selectedEntry != null &&
    authoringRecovery.entryId === String(selectedEntry.id)
      ? authoringRecovery
      : null;
  const showEntryList = entries.length !== 1;
  const showWorkspaceHeader = entries.length !== 1;
  const createEntryContentType = selectedContentType ?? contentTypes[0] ?? null;
  const previousEntryCountRef = useRef(entries.length);
  const previousReviewEntryRequestKeyRef = useRef(0);
  const newBlockTriggerRef = useRef<HTMLButtonElement>(null);
  const sourceCopyConflictRefreshActionRef = useRef<HTMLButtonElement>(null);
  const returnFocusToNewBlockTriggerRef = useRef(false);
  const returnFocusToTranslationFieldIdRef = useRef<number | null>(null);
  const translationOpenFocusRequestKeyRef = useRef(0);
  const reviewTranslationReconnectFocusOverrideRequestKeyRef = useRef(0);
  const pendingReviewTranslationFieldFocusRef = useRef<{
    fieldId: string;
    focusTarget: CopyFieldFocusTarget;
  } | null>(null);
  const [newBlockOpen, setNewBlockOpen] = useState(entries.length === 0);
  const [savedItemOptionsOpen, setSavedItemOptionsOpen] = useState(false);
  const [openFieldContextIds, setOpenFieldContextIds] = useState<Set<string>>(() => new Set());
  const [openTranslationFieldId, setOpenTranslationFieldId] = useState<string | null>(
    activeReviewRepairTarget === 'translation' ? activeReviewFieldId : null,
  );
  const [openTranslationLocaleTag, setOpenTranslationLocaleTag] = useState<string | null>(
    activeReviewRepairTarget === 'translation' ? activeReviewLocaleTag : null,
  );
  const [translationOpenFocusRequest, setTranslationOpenFocusRequest] = useState<{
    fieldId: string;
    requestKey: number;
  } | null>(null);
  const [reviewTranslationReconnectFocusOverride, setReviewTranslationReconnectFocusOverride] =
    useState<{ fieldId: string; requestKey: number } | null>(null);
  const [inlineTranslationReleaseHandoffBlockers, setInlineTranslationReleaseHandoffBlockers] =
    useState<Set<string>>(
      () =>
        new Set(
          inlineTranslationReleaseBlockerTargets.map((blockerTarget) =>
            getScopedInlineTranslationReleaseBlockerKey(projectId, blockerTarget),
          ),
        ),
    );
  const hasSelectedEntry = selectedEntry != null;
  const getScopedInlineTranslationKey = useCallback(
    (fieldId: string, localeTag: string) =>
      !hasSelectedEntry || localeTag.length === 0
        ? null
        : getScopedInlineTranslationReleaseBlockerKey(projectId, {
            entryId: selectedEntryId,
            fieldId,
            localeTag,
          }),
    [hasSelectedEntry, projectId, selectedEntryId],
  );
  const selectedEntryReleaseHandoffBlockerPrefix = !hasSelectedEntry
    ? null
    : `${projectId}:${selectedEntryId}:`;
  const selectedProjectReleaseHandoffBlockerPrefix = `${projectId}:`;
  const getScopedInlineTranslationReleaseBlockerTarget = useCallback(
    (blockerKey: string): InlineTranslationReleaseBlockerTarget | null => {
      const [blockerProjectId, entryId, fieldId, ...localeTagParts] = blockerKey.split(':');
      const localeTag = localeTagParts.join(':');
      return blockerProjectId !== String(projectId) ||
        entryId == null ||
        fieldId == null ||
        localeTag.length === 0
        ? null
        : { entryId, fieldId, localeTag };
    },
    [projectId],
  );
  const selectedEntryReleaseHandoffBlockerCount =
    selectedEntryReleaseHandoffBlockerPrefix == null
      ? 0
      : Array.from(inlineTranslationReleaseHandoffBlockers).filter((blockerKey) =>
          blockerKey.startsWith(selectedEntryReleaseHandoffBlockerPrefix),
        ).length;
  const hasSelectedEntryReleaseHandoffBlocker = selectedEntryReleaseHandoffBlockerCount > 0;
  const selectedProjectReleaseHandoffBlockerTargets = useMemo(
    () =>
      Array.from(inlineTranslationReleaseHandoffBlockers)
        .filter((blockerKey) => blockerKey.startsWith(selectedProjectReleaseHandoffBlockerPrefix))
        .map(getScopedInlineTranslationReleaseBlockerTarget)
        .filter((blocker): blocker is InlineTranslationReleaseBlockerTarget => blocker != null),
    [
      getScopedInlineTranslationReleaseBlockerTarget,
      inlineTranslationReleaseHandoffBlockers,
      selectedProjectReleaseHandoffBlockerPrefix,
    ],
  );
  useEffect(() => {
    onInlineTranslationReleaseBlockerChange(selectedProjectReleaseHandoffBlockerTargets);
  }, [onInlineTranslationReleaseBlockerChange, selectedProjectReleaseHandoffBlockerTargets]);
  const handleInlineTranslationReleaseHandoffBlockerChange = useCallback(
    (fieldId: string, localeTag: string, blocked: boolean) => {
      const blockerKey = getScopedInlineTranslationKey(fieldId, localeTag);
      if (blockerKey == null) {
        return;
      }
      setInlineTranslationReleaseHandoffBlockers((current) => {
        if (current.has(blockerKey) === blocked) {
          return current;
        }
        const next = new Set(current);
        if (blocked) {
          next.add(blockerKey);
        } else {
          next.delete(blockerKey);
        }
        return next;
      });
    },
    [getScopedInlineTranslationKey],
  );
  const getActiveReviewTranslationReconnectFocus = (): {
    fieldId: string;
    focusTarget: CopyFieldFocusTarget;
  } | null => {
    if (
      activeReviewRepairTarget !== 'translation' ||
      activeReviewFieldId == null ||
      activeReviewTranslationReconnectErrorMessage == null ||
      activeReviewTranslationReconnectRefreshPending
    ) {
      return null;
    }
    return {
      fieldId: activeReviewFieldId,
      focusTarget: activeReviewTranslationReconnectRefreshFailed
        ? 'translation-reconnect-refresh'
        : 'translation-reconnect',
    };
  };
  const restoreActiveReviewTranslationReconnectFocus = () => {
    const activeReviewTranslationReconnectFocus = getActiveReviewTranslationReconnectFocus();
    if (activeReviewTranslationReconnectFocus == null) {
      return;
    }
    queueCopyFieldScroll(
      activeReviewTranslationReconnectFocus.fieldId,
      activeReviewTranslationReconnectFocus.focusTarget,
    );
  };
  const hasIncompleteDirtyEntryDetails =
    entryContextDirty && !hasAuthoringDraftText(entryDraft.name);
  const hasIncompleteDirtySourceCopyDrafts =
    hasDirtySourceCopyDrafts &&
    Object.values(authoringMappingDrafts).some(
      (draftState) =>
        draftState.dirty &&
        draftState.draft.mappingSource === 'GENERATED' &&
        !hasRequiredSourceCopyDraft(draftState.draft),
    );
  const dirtyAuthoringSaveBlocker =
    hasIncompleteDirtyEntryDetails && hasIncompleteDirtySourceCopyDrafts
      ? {
          hint: 'Add a content item name, then fill source copy and translator note before saving this content item.',
          title: 'Finish required details',
        }
      : hasIncompleteDirtyEntryDetails
        ? {
            hint: 'Add a content item name before saving this content item.',
            title: 'Name this content item',
          }
        : hasIncompleteDirtySourceCopyDrafts
          ? {
              hint: 'Fill source copy and translator note before saving this content item.',
              title: 'Finish source copy',
            }
          : null;
  const hasDirtyBatchSaveDrafts =
    entryContextDirty || hasDirtyFieldContextDrafts || hasDirtySourceCopyDrafts;
  const savedItemOptionsForcedOpen =
    entryContextDirty || staleSavedEntryDraft != null || savedItemDetailsRecovery != null;
  const savedItemOptionsVisible = savedItemOptionsForcedOpen || savedItemOptionsOpen;
  const hasUnsavedAuthoringChanges = hasDirtyBatchSaveDrafts || hasDirtyInlineTranslation;
  const showReleaseReviewContentItemTarget =
    activeReviewReturnToRelease && activeReviewFieldId == null;
  const showReleaseReviewFallback =
    activeReviewReturnToRelease &&
    activeReviewFieldId != null &&
    String(selectedField?.id ?? '') !== activeReviewFieldId;
  const hasReadyReleaseHandoff =
    selectedEntry?.status === 'DRAFT' &&
    !hasUnsavedAuthoringChanges &&
    !selectedEntryCompletenessError &&
    selectedEntryCompleteness != null &&
    hasReadyCmsTargetLocales(selectedEntryCompleteness.locales, sourceLocale);
  const showReleaseHandoff = hasReadyReleaseHandoff && !hasSelectedEntryReleaseHandoffBlocker;
  const authoringFields =
    selectedEntry == null || selectedVariant == null
      ? []
      : localizableFields.map((field) => {
          const mapping =
            selectedVariant.fieldMappings.find((item) => item.fieldId === field.id) ?? null;
          const draftState = authoringMappingDrafts[String(field.id)] ?? null;
          const fieldDraftState = authoringFieldDrafts[String(field.id)] ?? null;
          const fieldMappingDraft =
            draftState?.draft ??
            (selectedField?.id === field.id
              ? mappingDraft
              : buildMappingDraft({
                  projectKey,
                  entry: selectedEntry,
                  variant: selectedVariant,
                  field,
                  mapping,
                }));
          const fieldDraft = fieldDraftState?.draft ?? {
            contentTypeId: String(field.contentTypeId),
            fieldKey: field.fieldKey,
            name: field.name,
            description: field.description ?? '',
            sourceContent: '',
            sourceComment: '',
            fieldType: field.fieldType,
            required: field.required,
            sortOrder: String(field.sortOrder),
          };
          const isActive = selectedField?.id === field.id;
          const fieldSourceCopyDirty = draftState?.dirty ?? false;
          const staleSavedDraft = draftState?.staleSavedDraft ?? null;
          const staleSavedFieldDraft = fieldDraftState?.staleSavedDraft ?? null;
          const fieldContextDirty = fieldDraftState?.dirty ?? false;
          const fieldDirty = fieldSourceCopyDirty || fieldContextDirty;
          const fieldCanEditGeneratedCopy = fieldMappingDraft.mappingSource === 'GENERATED';
          return {
            field,
            fieldCanEditGeneratedCopy,
            fieldCompleteness: getFieldCompleteness(selectedEntryCompleteness, field.id),
            fieldContextDirty,
            fieldDirty,
            fieldDraft,
            fieldMappingDraft,
            fieldSourceCopyDirty,
            isActive,
            mapping,
            staleSavedDraft,
            staleSavedFieldDraft,
            textUnitContext: buildCmsTextUnitContext({
              projectId,
              projectName,
              entry: selectedEntry,
              field,
            }),
          };
        });
  useEffect(() => {
    if (entries.length > previousEntryCountRef.current && newBlockOpen) {
      setNewBlockOpen(false);
    }
    previousEntryCountRef.current = entries.length;
  }, [entries.length, newBlockOpen]);
  useEffect(() => {
    onNewEntryOpenChange(newBlockOpen);
  }, [newBlockOpen, onNewEntryOpenChange]);
  useEffect(() => {
    if (newBlockOpen || !returnFocusToNewBlockTriggerRef.current) {
      return;
    }
    returnFocusToNewBlockTriggerRef.current = false;
    window.setTimeout(() => newBlockTriggerRef.current?.focus({ preventScroll: true }), 0);
  }, [newBlockOpen]);
  useEffect(() => {
    const fieldId = returnFocusToTranslationFieldIdRef.current;
    if (openTranslationFieldId != null || fieldId == null) {
      return;
    }
    returnFocusToTranslationFieldIdRef.current = null;
    window.setTimeout(() => focusClosedInlineTranslationAction(fieldId), 0);
  }, [openTranslationFieldId]);
  useEffect(() => {
    if (!sourceCopyConflictRefreshFailed) {
      return;
    }
    const timeoutId = window.setTimeout(
      () => sourceCopyConflictRefreshActionRef.current?.focus({ preventScroll: true }),
      0,
    );
    return () => window.clearTimeout(timeoutId);
  }, [sourceCopyConflictRefreshFailed]);

  useEffect(() => {
    setSavedItemOptionsOpen(false);
    setOpenFieldContextIds(new Set());
    if (activeReviewRepairTarget === 'translation' && activeReviewFieldId != null) {
      return;
    }
    pendingReviewTranslationFieldFocusRef.current = null;
    setReviewTranslationReconnectFocusOverride(null);
    setTranslationOpenFocusRequest(null);
    setOpenTranslationFieldId(null);
    setOpenTranslationLocaleTag(null);
  }, [activeReviewFieldId, activeReviewRepairTarget, selectedEntry?.id]);
  useEffect(() => {
    if (
      newBlockOpen ||
      activeReviewRepairTarget !== 'translation' ||
      activeReviewFieldId == null ||
      String(selectedField?.id ?? '') !== activeReviewFieldId
    ) {
      return;
    }
    setOpenTranslationFieldId(activeReviewFieldId);
    setOpenTranslationLocaleTag(activeReviewLocaleTag);
    const pendingReviewTranslationFieldFocus = pendingReviewTranslationFieldFocusRef.current;
    if (
      pendingReviewTranslationFieldFocus == null ||
      pendingReviewTranslationFieldFocus.fieldId !== activeReviewFieldId
    ) {
      return;
    }
    pendingReviewTranslationFieldFocusRef.current = null;
    const timeoutId = window.setTimeout(
      () =>
        queueCopyFieldScroll(
          pendingReviewTranslationFieldFocus.fieldId,
          pendingReviewTranslationFieldFocus.focusTarget,
        ),
      0,
    );
    return () => window.clearTimeout(timeoutId);
  }, [
    activeReviewFieldId,
    activeReviewLocaleTag,
    activeReviewRepairTarget,
    newBlockOpen,
    selectedField?.id,
  ]);

  useEffect(() => {
    if (activeReviewEntryRequestKey === 0) {
      previousReviewEntryRequestKeyRef.current = activeReviewEntryRequestKey;
      return;
    }
    if (
      selectedEntry == null ||
      activeReviewEntryRequestKey === previousReviewEntryRequestKeyRef.current
    ) {
      return;
    }
    previousReviewEntryRequestKeyRef.current = activeReviewEntryRequestKey;
    if (activeReviewFieldId != null) {
      if (activeReviewRepairTarget === 'translation') {
        setOpenTranslationLocaleTag(activeReviewLocaleTag);
        setOpenTranslationFieldId(activeReviewFieldId);
      } else {
        setOpenTranslationFieldId(null);
        setOpenTranslationLocaleTag(null);
      }
      queueCopyFieldScroll(
        activeReviewFieldId,
        activeReviewRepairTarget === 'source-copy'
          ? 'source-copy'
          : activeReviewRepairTarget === 'translation'
            ? 'translation'
            : 'field',
      );
      return;
    }
    queueCopyBlockEditorScroll(String(selectedEntry.id));
  }, [
    activeReviewEntryRequestKey,
    activeReviewFieldId,
    activeReviewLocaleTag,
    activeReviewRepairTarget,
    selectedEntry,
  ]);

  const toggleNewBlock = () => {
    if (newBlockOpen) {
      onCancelNewEntry(() => {
        const activeReviewTranslationReconnectFocus = getActiveReviewTranslationReconnectFocus();
        onNewEntryDraftChange(initialEntryDraft);
        onNewEntrySourceDraftsChange({});
        pendingReviewTranslationFieldFocusRef.current = activeReviewTranslationReconnectFocus;
        returnFocusToNewBlockTriggerRef.current = activeReviewTranslationReconnectFocus == null;
        setNewBlockOpen(false);
      });
      return;
    }
    onStartNewEntry(() => setNewBlockOpen(true));
  };

  const toggleSavedItemOptions = () => {
    const shouldRestoreReviewTranslationReconnectFocus =
      savedItemOptionsOpen && !savedItemOptionsForcedOpen;
    setSavedItemOptionsOpen((current) => !current);
    if (shouldRestoreReviewTranslationReconnectFocus) {
      restoreActiveReviewTranslationReconnectFocus();
    }
  };

  const onFieldContextOpenChange = (fieldId: string, open: boolean) => {
    setOpenFieldContextIds((currentFieldIds) => {
      if (currentFieldIds.has(fieldId) === open) {
        return currentFieldIds;
      }
      const nextFieldIds = new Set(currentFieldIds);
      if (open) {
        nextFieldIds.add(fieldId);
      } else {
        nextFieldIds.delete(fieldId);
      }
      return nextFieldIds;
    });
  };

  return (
    <section className="content-cms-admin-page__workspace" aria-label="Product copy workspace">
      {!newBlockOpen && showWorkspaceHeader ? (
        <div className="content-cms-admin-page__workspace-header">
          <div className="content-cms-admin-page__workspace-title">
            <h2>Content items</h2>
            <p className="settings-page__hint">
              {entries.length === 0
                ? `Write the first content item in ${sourceLocaleLabel}.`
                : entries.length === 1
                  ? `Edit this content item, or write the next one. Writing in ${sourceLocaleLabel}.`
                  : `Choose a content item to edit, or write the next one. Writing in ${sourceLocaleLabel}.`}
            </p>
          </div>
          <button
            ref={newBlockTriggerRef}
            type="button"
            className="settings-button settings-button--primary"
            disabled={disabled || createEntryContentType == null}
            onClick={toggleNewBlock}
          >
            {entries.length === 0 ? 'Write first content item' : 'Write new content item'}
          </button>
        </div>
      ) : null}
      {newBlockOpen ? (
        <NewCopyBlockForm
          contentType={createEntryContentType}
          draft={newEntryDraft}
          sourceDrafts={newEntrySourceDrafts}
          disabled={disabled}
          isFirstBlock={entries.length === 0}
          authoringRecovery={authoringRecovery?.kind === 'new-block' ? authoringRecovery : null}
          sourceCopyPlaceholder={sourceCopyPlaceholder}
          onOpenAdvancedSetup={onOpenAdvancedSetup}
          onChange={onNewEntryDraftChange}
          onCancel={toggleNewBlock}
          onSourceDraftsChange={onNewEntrySourceDraftsChange}
          onSubmit={onNewEntrySubmit}
        />
      ) : null}
      {!newBlockOpen ? (
        <div className="content-cms-admin-page__workspace-shell">
          {showEntryList ? (
            <nav className="content-cms-admin-page__entry-list" aria-label="Content items">
              {entries.length === 0 ? (
                <p className="content-cms-admin-page__entry-list-empty">No content items yet.</p>
              ) : null}
              {entries.map((entry) => {
                const contentType = findContentType(contentTypes, entry.contentTypeId);
                const localizableFieldCount = sortLocalizableFields(
                  contentType?.fields ?? [],
                ).length;
                const primaryVariant = findPrimaryVariant(entry);
                const entryMappedFieldCount =
                  primaryVariant == null
                    ? 0
                    : sortLocalizableFields(contentType?.fields ?? []).filter((field) =>
                        primaryVariant.fieldMappings.some(
                          (mapping) => mapping.fieldId === field.id,
                        ),
                      ).length;
                const sourceCopyPreview =
                  primaryVariant?.fieldMappings
                    .find((mapping) => mapping.sourceContent?.trim())
                    ?.sourceContent?.trim() ?? 'No source copy yet';
                const isActive = String(entry.id) === selectedEntryId;
                return (
                  <button
                    key={entry.id}
                    type="button"
                    className={`content-cms-admin-page__entry-row${isActive ? ' is-active' : ''}`}
                    aria-label={`Open content item ${entry.name}`}
                    aria-current={isActive ? 'true' : undefined}
                    disabled={disabled}
                    onClick={() => {
                      const entryId = String(entry.id);
                      onEntryChange(
                        entryId,
                        isActive
                          ? undefined
                          : () => {
                              queueCopyBlockEditorScroll(entryId);
                            },
                      );
                    }}
                  >
                    <span className="content-cms-admin-page__entry-row-name">{entry.name}</span>
                    <span className="content-cms-admin-page__entry-row-preview">
                      {sourceCopyPreview}
                    </span>
                    <span>
                      {formatCopyFieldProgress(entryMappedFieldCount, localizableFieldCount)}
                    </span>
                  </button>
                );
              })}
            </nav>
          ) : null}

          {selectedEntry == null ? (
            <div className="content-cms-admin-page__editor-empty">
              <p className="settings-page__hint">
                {entries.length === 0
                  ? 'Write the first content item above to start writing source copy.'
                  : 'Select a content item to edit it.'}
              </p>
            </div>
          ) : (
            <div
              id={getCopyBlockEditorAnchorId(String(selectedEntry.id))}
              className={`content-cms-admin-page__editor${
                showReleaseReviewContentItemTarget ? ' is-release-review-target' : ''
              }`}
              tabIndex={-1}
            >
              <header className="content-cms-admin-page__editor-header">
                <div>
                  <h2>{selectedEntry.name}</h2>
                  {selectedEntry.description ? (
                    <p className="settings-page__hint">{selectedEntry.description}</p>
                  ) : null}
                </div>
                <div className="content-cms-admin-page__status-stack">
                  {showReleaseReviewContentItemTarget ? (
                    <span className="content-cms-admin-page__release-review-target-label">
                      Review from release
                    </span>
                  ) : null}
                  {!showEntryList && selectedEntryNeedsSourceCopy ? (
                    <span className="content-cms-admin-page__editor-progress">
                      No source copy yet
                    </span>
                  ) : null}
                  {entryContextDirty || hasDirtyFieldContextDrafts || hasDirtySourceCopyDrafts ? (
                    <span className="content-cms-admin-page__status is-warning">
                      Unsaved changes
                    </span>
                  ) : null}
                  {!showEntryList ? (
                    <button
                      ref={newBlockTriggerRef}
                      type="button"
                      className="settings-button settings-button--ghost"
                      disabled={disabled || createEntryContentType == null}
                      onClick={toggleNewBlock}
                    >
                      Write new content item
                    </button>
                  ) : null}
                  <button
                    type="button"
                    className="content-cms-admin-page__authoring-options-trigger"
                    aria-expanded={savedItemOptionsVisible}
                    aria-controls={selectedEntryOptionsId ?? undefined}
                    onClick={toggleSavedItemOptions}
                  >
                    Item details
                  </button>
                </div>
              </header>

              {showReleaseReviewContentItemTarget ? (
                <ReleaseReviewReturnHandoff
                  disabled={disabled}
                  onReturnToRelease={onReturnToRelease}
                  readySubject="content item"
                  reviewReason={activeReviewReason}
                  reviewReturnMode={activeReviewReturnMode}
                />
              ) : null}
              {showReleaseReviewFallback ? (
                <ReleaseReviewReturnHandoff
                  disabled={disabled}
                  onReturnToRelease={onReturnToRelease}
                  readySubject="release change"
                  reviewReason={activeReviewReason}
                  reviewReturnMode={activeReviewReturnMode}
                />
              ) : null}

              {savedItemOptionsVisible ? (
                <div
                  id={selectedEntryOptionsId ?? undefined}
                  className="content-cms-admin-page__saved-item-options"
                  aria-label="Item details"
                >
                  <CopyBlockDetails
                    id={selectedEntryDetailsId ?? undefined}
                    entryDraft={entryDraft}
                    staleSavedDraft={staleSavedEntryDraft}
                    entryContextDirty={entryContextDirty}
                    disabled={disabled}
                    authoringRecovery={savedItemDetailsRecovery}
                    onEntryDraftChange={onEntryDraftChange}
                    onEntrySubmit={onEntrySubmit}
                    onUseSavedDetails={onResetEntryDraft}
                  />
                </div>
              ) : null}

              {hasDirtyBatchSaveDrafts ? (
                <section
                  className="content-cms-admin-page__authoring-save-bar"
                  aria-label="Save copy changes"
                >
                  <div id={authoringSaveStatusId ?? undefined} role="status" aria-live="polite">
                    <strong>{dirtyAuthoringSaveBlocker?.title ?? 'Ready to save'}</strong>
                    <span>
                      {dirtyAuthoringSaveBlocker?.hint ??
                        'Save this content item to keep these edits.'}
                    </span>
                  </div>
                  <button
                    type="button"
                    className="settings-button settings-button--primary"
                    disabled={disabled || dirtyAuthoringSaveBlocker != null}
                    aria-describedby={authoringSaveStatusId ?? undefined}
                    onClick={onSaveAll}
                  >
                    Save copy changes
                  </button>
                </section>
              ) : null}

              {selectedVariant != null && localizableFields.length > 0 ? (
                <div className="content-cms-admin-page__field-list-header">
                  <div>
                    <h3>Write the copy</h3>
                  </div>
                </div>
              ) : null}

              {selectedVariant == null ? (
                <div className="content-cms-admin-page__editor-empty">
                  <AuthoringRepairAction
                    actionLabel="Repair content item"
                    message="This content item needs repair before source copy can be edited."
                    onOpenAdvancedSetup={onOpenAdvancedSetup}
                  />
                </div>
              ) : localizableFields.length === 0 ? (
                <div className="content-cms-admin-page__editor-empty">
                  <AuthoringRepairAction
                    actionLabel="Repair content item"
                    message="This content item has no fields to write yet."
                    onOpenAdvancedSetup={onOpenAdvancedSetup}
                  />
                </div>
              ) : (
                <>
                  <section
                    className="content-cms-admin-page__field-outline"
                    aria-label="Write the copy"
                  >
                    {authoringFields.map(
                      ({
                        field,
                        fieldCanEditGeneratedCopy,
                        fieldCompleteness,
                        fieldContextDirty,
                        fieldDirty,
                        fieldDraft,
                        fieldMappingDraft,
                        fieldSourceCopyDirty,
                        isActive,
                        mapping,
                        staleSavedDraft,
                        staleSavedFieldDraft,
                        textUnitContext,
                      }) => {
                        const fieldId = String(field.id);
                        const hasMultipleFields = authoringFields.length > 1;
                        const requestFieldFocus = (
                          event: SyntheticEvent<HTMLElement>,
                          options?: {
                            cancelPendingFieldSwitch?: boolean;
                            focusTarget?: CopyFieldFocusTarget;
                          },
                        ) => {
                          const focusTarget = options?.focusTarget ?? 'field';
                          const shouldReturnToReviewTranslationField =
                            activeReviewRepairTarget === 'translation' &&
                            activeReviewFieldId === fieldId &&
                            focusTarget !== 'field';
                          if (!onFieldFocus(fieldId, options)) {
                            event.preventDefault();
                            return;
                          }
                          if (shouldReturnToReviewTranslationField) {
                            const requestKey =
                              reviewTranslationReconnectFocusOverrideRequestKeyRef.current + 1;
                            reviewTranslationReconnectFocusOverrideRequestKeyRef.current =
                              requestKey;
                            pendingReviewTranslationFieldFocusRef.current = {
                              fieldId,
                              focusTarget,
                            };
                            setReviewTranslationReconnectFocusOverride({
                              fieldId,
                              requestKey,
                            });
                            return;
                          }
                          if (fieldId !== activeReviewFieldId) {
                            pendingReviewTranslationFieldFocusRef.current = null;
                            setReviewTranslationReconnectFocusOverride(null);
                          }
                        };
                        const focusField = (event: SyntheticEvent<HTMLElement>) =>
                          requestFieldFocus(event, {
                            focusTarget: getRequestedCopyFieldFocusTarget(event.target),
                          });
                        const keepWritingField = (event: SyntheticEvent<HTMLElement>) =>
                          requestFieldFocus(event, {
                            cancelPendingFieldSwitch: true,
                            focusTarget: getRequestedCopyFieldFocusTarget(event.target),
                          });
                        const keepWritingSourceCopy = (event: SyntheticEvent<HTMLElement>) =>
                          requestFieldFocus(event, {
                            cancelPendingFieldSwitch: true,
                            focusTarget: 'source-copy',
                          });
                        const sourceCopyRecovery =
                          authoringRecovery?.kind === 'source-copy' &&
                          authoringRecovery.fieldId === fieldId
                            ? authoringRecovery
                            : null;
                        const fieldContextRecovery =
                          authoringRecovery?.kind === 'field-context' &&
                          authoringRecovery.fieldId === fieldId
                            ? authoringRecovery
                            : null;
                        const fieldContextForcedOpen =
                          fieldContextDirty ||
                          fieldContextRecovery != null ||
                          staleSavedFieldDraft != null;
                        const fieldContextOpen =
                          fieldContextForcedOpen || openFieldContextIds.has(fieldId);
                        const fieldAuthoringStatus =
                          fieldDirty || mapping == null
                            ? fieldDirty
                              ? {
                                  className: 'content-cms-admin-page__status is-warning',
                                  label: 'Unsaved changes',
                                }
                              : field.required
                                ? {
                                    className: 'content-cms-admin-page__status is-warning',
                                    label: 'Needs source copy',
                                  }
                                : {
                                    className: 'content-cms-admin-page__status is-muted',
                                    label: 'Optional copy can wait',
                                  }
                            : null;
                        const canSaveFieldSourceCopy =
                          fieldCanEditGeneratedCopy &&
                          fieldSourceCopyDirty &&
                          hasRequiredSourceCopyDraft(fieldMappingDraft);
                        const showFieldSourceCopySave =
                          fieldSourceCopyDirty || (mapping == null && field.required);
                        const fieldSourceCopyRequired =
                          fieldCanEditGeneratedCopy &&
                          (field.required || mapping != null || fieldSourceCopyDirty);
                        const showReleaseReviewReturn =
                          activeReviewReturnToRelease && activeReviewFieldId === fieldId;
                        const showReleaseReviewSourceCopyTarget =
                          isActive &&
                          showReleaseReviewReturn &&
                          activeReviewRepairTarget === 'source-copy';
                        const showReleaseReviewFieldTarget =
                          isActive && showReleaseReviewReturn && activeReviewRepairTarget == null;
                        return (
                          <section
                            key={field.id}
                            id={getCopyFieldAnchorId(String(field.id))}
                            className={`content-cms-admin-page__field-outline-card${
                              isActive ? ' is-active' : ''
                            }${showReleaseReviewFieldTarget ? ' is-release-review-target' : ''}`}
                            tabIndex={-1}
                            onFocusCapture={focusField}
                            onPointerDownCapture={focusField}
                          >
                            <section className="content-cms-admin-page__field-editor">
                              <div className="content-cms-admin-page__field-editor-header">
                                <div>
                                  <h3>{field.name}</h3>
                                  {fieldDraft.description ? (
                                    <p className="settings-page__hint">{fieldDraft.description}</p>
                                  ) : null}
                                </div>
                                {showReleaseReviewFieldTarget || fieldAuthoringStatus ? (
                                  <div className="content-cms-admin-page__status-stack">
                                    {showReleaseReviewFieldTarget ? (
                                      <span className="content-cms-admin-page__release-review-target-label">
                                        Review from release
                                      </span>
                                    ) : null}
                                    {fieldAuthoringStatus ? (
                                      <span className={fieldAuthoringStatus.className}>
                                        {fieldAuthoringStatus.label}
                                      </span>
                                    ) : null}
                                  </div>
                                ) : null}
                              </div>
                              <form
                                className="content-cms-admin-page__field-copy-form"
                                onSubmit={(event) => onMappingSubmit(String(field.id), event)}
                              >
                                <label
                                  className={`content-cms-admin-page__copy-field${
                                    showReleaseReviewSourceCopyTarget
                                      ? ' is-release-review-target'
                                      : ''
                                  }`}
                                >
                                  <span className="content-cms-admin-page__copy-field-label-row">
                                    <span className="content-cms-admin-page__copy-field-label">
                                      Source copy
                                    </span>
                                    {showReleaseReviewSourceCopyTarget ? (
                                      <span
                                        className="content-cms-admin-page__release-review-target-label"
                                        aria-hidden="true"
                                      >
                                        Review from release
                                      </span>
                                    ) : null}
                                  </span>
                                  <AutoTextarea
                                    aria-label={
                                      hasMultipleFields
                                        ? `${field.name} source copy`
                                        : 'Source copy'
                                    }
                                    className="settings-input content-cms-admin-page__textarea content-cms-admin-page__textarea--copy content-cms-admin-page__copy-input"
                                    value={fieldMappingDraft.sourceContent}
                                    minRows={authorSourceCopyMinRows}
                                    maxRows={12}
                                    onFocus={keepWritingSourceCopy}
                                    onPointerDown={keepWritingSourceCopy}
                                    onChange={(event) =>
                                      onMappingDraftChange(String(field.id), {
                                        ...fieldMappingDraft,
                                        sourceContent: event.target.value,
                                      })
                                    }
                                    disabled={disabled || !fieldCanEditGeneratedCopy}
                                    required={fieldSourceCopyRequired}
                                    placeholder={sourceCopyPlaceholder}
                                  />
                                </label>
                                <label className="settings-field content-cms-admin-page__translator-note">
                                  <span className="content-cms-admin-page__field-label-row">
                                    <span className="settings-field__label">Translator note</span>
                                    <span
                                      className="content-cms-admin-page__required-hint"
                                      aria-hidden="true"
                                    >
                                      {field.required || mapping || fieldSourceCopyDirty
                                        ? 'Required for translation'
                                        : 'Add when writing this copy'}
                                    </span>
                                  </span>
                                  <AutoTextarea
                                    aria-label={
                                      hasMultipleFields
                                        ? `${field.name} translator note`
                                        : 'Translator note'
                                    }
                                    className="settings-input content-cms-admin-page__textarea content-cms-admin-page__textarea--translator-note"
                                    value={fieldMappingDraft.sourceComment}
                                    minRows={authorTranslatorNoteMinRows}
                                    maxRows={5}
                                    onFocus={keepWritingField}
                                    onChange={(event) =>
                                      onMappingDraftChange(String(field.id), {
                                        ...fieldMappingDraft,
                                        sourceComment: event.target.value,
                                      })
                                    }
                                    disabled={disabled || !fieldCanEditGeneratedCopy}
                                    required={fieldSourceCopyRequired}
                                    placeholder="Tone, limits, placeholders, or translator instructions"
                                  />
                                </label>
                                {showFieldSourceCopySave ? (
                                  <div className="content-cms-admin-page__field-actions">
                                    <button
                                      type="submit"
                                      className="settings-button settings-button--primary"
                                      disabled={disabled || !canSaveFieldSourceCopy}
                                    >
                                      {mapping ? 'Save source changes' : 'Save source copy'}
                                    </button>
                                  </div>
                                ) : null}
                                <AuthoringRecoveryPanel authoringRecovery={sourceCopyRecovery} />
                                {sourceCopyRecovery != null &&
                                (sourceCopyConflictRefreshPending ||
                                  sourceCopyConflictRefreshFailed) ? (
                                  <div className="content-cms-admin-page__field-actions">
                                    <button
                                      ref={sourceCopyConflictRefreshActionRef}
                                      type="button"
                                      className="settings-button"
                                      disabled={sourceCopyConflictRefreshPending}
                                      onClick={onRetrySourceCopyConflictRefresh}
                                    >
                                      {sourceCopyConflictRefreshPending
                                        ? 'Refreshing copy...'
                                        : 'Try again'}
                                    </button>
                                  </div>
                                ) : null}
                                <SourceCopyRefreshNotice
                                  draft={fieldMappingDraft}
                                  savedDraft={staleSavedDraft}
                                  onUseSavedCopy={() => onResetMappingDraft(String(field.id))}
                                />
                                {showReleaseReviewSourceCopyTarget ? (
                                  <ReleaseSourceCopyReviewNotice
                                    currentSourceContent={fieldMappingDraft.sourceContent}
                                    lastReleasedSourceContent={
                                      activeReviewLastReleasedSourceContent
                                    }
                                  />
                                ) : null}
                              </form>
                              <details
                                className="content-cms-admin-page__field-context-details"
                                open={fieldContextOpen}
                                onToggle={(event) => {
                                  if (!event.currentTarget.open && fieldContextForcedOpen) {
                                    event.currentTarget.open = true;
                                    return;
                                  }
                                  onFieldContextOpenChange(fieldId, event.currentTarget.open);
                                  if (!event.currentTarget.open) {
                                    restoreActiveReviewTranslationReconnectFocus();
                                  }
                                }}
                              >
                                <summary>Where this copy appears</summary>
                                <form
                                  className="content-cms-admin-page__field-context-form"
                                  onSubmit={(event) =>
                                    onFieldContextSubmit(String(field.id), event)
                                  }
                                >
                                  <label className="settings-field">
                                    <span className="settings-field__label">Placement details</span>
                                    <input
                                      aria-label={
                                        hasMultipleFields
                                          ? `${field.name} placement details`
                                          : undefined
                                      }
                                      className="settings-input"
                                      value={fieldDraft.description}
                                      tabIndex={fieldContextOpen ? undefined : -1}
                                      onFocus={keepWritingField}
                                      onChange={(event) =>
                                        onFieldContextChange(String(field.id), event.target.value)
                                      }
                                      disabled={disabled}
                                      placeholder="Headline above the welcome body"
                                    />
                                  </label>
                                  {fieldContextDirty ? (
                                    <div className="content-cms-admin-page__field-actions">
                                      <span className="content-cms-admin-page__status is-warning">
                                        Unsaved placement
                                      </span>
                                      <button
                                        type="submit"
                                        className="settings-button"
                                        disabled={disabled}
                                      >
                                        {field.description
                                          ? 'Save placement details'
                                          : 'Add placement details'}
                                      </button>
                                    </div>
                                  ) : null}
                                  <AuthoringRecoveryPanel
                                    authoringRecovery={fieldContextRecovery}
                                  />
                                  <FieldContextRefreshNotice
                                    draft={fieldDraft}
                                    savedDraft={staleSavedFieldDraft}
                                    onUseSavedContext={() =>
                                      onResetFieldContextDraft(String(field.id))
                                    }
                                  />
                                </form>
                              </details>
                              <FieldLocalizationStatus
                                field={field}
                                mapping={mapping}
                                sourceCopyDirty={fieldSourceCopyDirty}
                                completeness={fieldCompleteness}
                                sourceLocale={sourceLocale}
                                textUnitContext={textUnitContext}
                                isError={selectedEntryCompletenessError}
                                translationEditorOpen={openTranslationFieldId === fieldId}
                                onOpenTranslation={(localeTag) => {
                                  if (onFieldFocus(fieldId)) {
                                    const requestKey =
                                      translationOpenFocusRequestKeyRef.current + 1;
                                    translationOpenFocusRequestKeyRef.current = requestKey;
                                    setTranslationOpenFocusRequest({ fieldId, requestKey });
                                    setOpenTranslationLocaleTag(localeTag);
                                    setOpenTranslationFieldId(fieldId);
                                  }
                                }}
                                onRetry={onRetryEntryCompleteness}
                              />
                              {isActive && showReleaseReviewReturn ? (
                                <ReleaseReviewReturnHandoff
                                  disabled={disabled}
                                  onReturnToRelease={onReturnToRelease}
                                  readySubject="field"
                                  reviewReason={activeReviewReason}
                                  reviewReturnMode={activeReviewReturnMode}
                                />
                              ) : null}
                              {isActive &&
                              showReleaseHandoff &&
                              openTranslationFieldId !== fieldId ? (
                                <InlineTranslationReleaseHandoff
                                  disabled={disabled}
                                  onIncludeInRelease={() => onEntryReleaseStatusChange('READY')}
                                />
                              ) : null}
                              {isActive ? (
                                <div className="content-cms-admin-page__field-localization-panel">
                                  {mapping ? (
                                    <>
                                      {openTranslationFieldId === fieldId ? (
                                        <InlineTranslationEditor
                                          field={field}
                                          mapping={mapping}
                                          sourceCopyDirty={fieldSourceCopyDirty}
                                          completeness={fieldCompleteness}
                                          sourceLocale={sourceLocale}
                                          requestedLocaleTag={openTranslationLocaleTag}
                                          requestedLocaleRequestKey={activeReviewEntryRequestKey}
                                          focusOnOpenRequestKey={
                                            translationOpenFocusRequest?.fieldId === fieldId
                                              ? translationOpenFocusRequest.requestKey
                                              : 0
                                          }
                                          refreshRequestedTranslation={
                                            activeReviewRefreshTranslation
                                          }
                                          translationRepairSaved={
                                            activeReviewTranslationRepairSaved
                                          }
                                          translationReconnectErrorMessage={
                                            activeReviewTranslationReconnectErrorMessage
                                          }
                                          translationReconnectRefreshFailed={
                                            activeReviewTranslationReconnectRefreshFailed
                                          }
                                          translationReconnectRefreshPending={
                                            activeReviewTranslationReconnectRefreshPending
                                          }
                                          suppressReconnectAutoFocusRequestKey={
                                            reviewTranslationReconnectFocusOverride?.fieldId ===
                                            fieldId
                                              ? reviewTranslationReconnectFocusOverride.requestKey
                                              : 0
                                          }
                                          requestedLocaleSetupRefreshPending={
                                            activeReviewRequestedLocaleSetupRefreshPending
                                          }
                                          returnToRelease={activeReviewReturnToRelease}
                                          reviewLastReleasedTranslationContent={
                                            activeReviewLastReleasedTranslationContent
                                          }
                                          textUnitContext={textUnitContext}
                                          isLoading={selectedEntryCompletenessLoading}
                                          isRefreshing={selectedEntryCompletenessRefreshing}
                                          isError={selectedEntryCompletenessError}
                                          targetLocaleOptions={targetLocaleOptions}
                                          targetLocalesLoading={targetLocalesLoading}
                                          targetLocalesError={targetLocalesError}
                                          disabled={disabled}
                                          authoringRecovery={
                                            authoringRecovery?.kind === 'target-locales'
                                              ? authoringRecovery
                                              : null
                                          }
                                          blockedPieceSwitchTargetName={
                                            pendingInlineTranslationFieldSwitch?.sourceFieldId ===
                                            fieldId
                                              ? pendingInlineTranslationFieldSwitch.targetFieldName
                                              : null
                                          }
                                          blockedPieceSwitchReason={
                                            pendingInlineTranslationFieldSwitch?.sourceFieldId ===
                                            fieldId
                                              ? pendingInlineTranslationFieldSwitch.reason
                                              : null
                                          }
                                          onDirtyChange={onInlineTranslationDirtyChange}
                                          onSavingChange={onInlineTranslationSavingChange}
                                          onReleaseHandoffBlockerChange={
                                            handleInlineTranslationReleaseHandoffBlockerChange
                                          }
                                          onTranslationSaved={onInlineTranslationSaved}
                                          onRequestConfirmation={onRequestConfirmation}
                                          showReleaseHandoff={showReleaseHandoff}
                                          onIncludeInRelease={() =>
                                            onEntryReleaseStatusChange('READY')
                                          }
                                          onAddRequestedTargetLocale={onAddRequestedTargetLocale}
                                          onReconnectTranslation={(localeTag) =>
                                            onReconnectTranslation(fieldId, localeTag)
                                          }
                                          onRetry={onRetryEntryCompleteness}
                                          onRetryTargetLocales={onRetryTargetLocales}
                                          onRefreshStaleTranslation={onRefreshStaleTranslation}
                                          onClose={() => {
                                            returnFocusToTranslationFieldIdRef.current = field.id;
                                            setTranslationOpenFocusRequest(null);
                                            setOpenTranslationFieldId(null);
                                            setOpenTranslationLocaleTag(null);
                                          }}
                                          onOpenFocusSettled={() =>
                                            setTranslationOpenFocusRequest((current) =>
                                              current?.fieldId === fieldId ? null : current,
                                            )
                                          }
                                        />
                                      ) : null}
                                    </>
                                  ) : null}
                                  {!fieldCanEditGeneratedCopy ? (
                                    <AuthoringRepairAction
                                      actionLabel="Repair source link"
                                      message="This field reuses saved source copy."
                                      onOpenAdvancedSetup={onOpenAdvancedSetup}
                                    />
                                  ) : null}
                                </div>
                              ) : null}
                            </section>
                          </section>
                        );
                      },
                    )}
                  </section>
                  {mappedFieldCount > 0 &&
                  hasSavedRequiredSourceCopy &&
                  openTranslationFieldId == null ? (
                    <TargetLocaleSetupCard
                      sourceCopyDirty={hasDirtySourceCopyDrafts}
                      completeness={selectedEntryCompleteness}
                      sourceLocale={sourceLocale}
                      isLoading={selectedEntryCompletenessLoading}
                      isError={selectedEntryCompletenessError}
                      targetLocaleOptions={targetLocaleOptions}
                      targetLocaleTagsDraft={targetLocaleTagsDraft}
                      targetLocalesLoading={targetLocalesLoading}
                      targetLocalesError={targetLocalesError}
                      targetLocalesSaving={targetLocalesSaving}
                      disabled={disabled}
                      authoringRecovery={
                        authoringRecovery?.kind === 'target-locales' ? authoringRecovery : null
                      }
                      onTargetLocaleTagsChange={onTargetLocaleTagsChange}
                      onAddTargetLocales={onAddTargetLocales}
                      onRetryTargetLocales={onRetryTargetLocales}
                    />
                  ) : null}
                </>
              )}

              <BlockReleaseState
                entry={selectedEntry}
                mappedFieldCount={mappedFieldCount}
                copyFieldCount={localizableFields.length}
                completeness={selectedEntryCompleteness}
                sourceLocale={sourceLocale}
                isLoading={selectedEntryCompletenessLoading}
                isError={selectedEntryCompletenessError}
                hasUnsavedAuthoringChanges={hasUnsavedAuthoringChanges}
                disabled={disabled || hasUnsavedAuthoringChanges}
                hasReleaseHandoffBlocker={hasSelectedEntryReleaseHandoffBlocker}
                releaseHandoffBlockerCount={selectedEntryReleaseHandoffBlockerCount}
                suppressReadyDraftState={hasReadyReleaseHandoff}
                authoringRecovery={
                  authoringRecovery?.kind === 'block-release' &&
                  authoringRecovery.entryId === String(selectedEntry.id)
                    ? authoringRecovery
                    : null
                }
                onStatusChange={onEntryReleaseStatusChange}
              />
              {authorReleasePanel}
            </div>
          )}
        </div>
      ) : null}
    </section>
  );
}

type CmsTranslationCompleteness = Pick<ApiCmsEntryCompleteness, 'locales'>;

function FieldLocalizationStatus({
  field,
  mapping,
  sourceCopyDirty,
  completeness,
  sourceLocale,
  textUnitContext,
  isError,
  translationEditorOpen,
  onOpenTranslation,
  onRetry,
}: {
  field: ApiCmsContentTypeField;
  mapping: ApiCmsFieldMapping | null;
  sourceCopyDirty: boolean;
  completeness: CmsTranslationCompleteness | null;
  sourceLocale: string;
  textUnitContext: CmsTextUnitContext;
  isError: boolean;
  translationEditorOpen: boolean;
  onOpenTranslation: (localeTag: string | null) => void;
  onRetry: () => void;
}) {
  const user = useUser();
  const resolveLocaleDisplayName = useLocaleDisplayNameResolver();
  const translationReadiness =
    completeness == null ? null : getTranslationReadiness(completeness.locales, sourceLocale);
  const translationWorkflowStep = getTranslationWorkflowStep(
    completeness?.locales ?? [],
    sourceLocale,
  );
  const mojitoLocaleTag = getMojitoLocaleTag(
    completeness?.locales ?? [],
    sourceLocale,
    translationWorkflowStep,
  );
  const preferredLocale =
    translationReadiness?.targetLocales.find((locale) => locale.localeTag === mojitoLocaleTag) ??
    translationReadiness?.targetLocales[0] ??
    null;
  const preferredLocaleLabel =
    preferredLocale == null ? null : resolveLocaleDisplayName(preferredLocale.localeTag);
  const canEditPreferredLocale =
    preferredLocale != null && canEditLocaleForUser(user, preferredLocale.localeTag);
  const translationSummary =
    preferredLocale == null || preferredLocaleLabel == null
      ? null
      : canEditPreferredLocale
        ? getTranslationStatusSummary(preferredLocale, preferredLocaleLabel)
        : {
            ariaLabel: `${preferredLocaleLabel} Language access needed`,
            statusLabel: 'Language access needed',
          };
  if (mapping == null) {
    return null;
  }
  const localizationStatus = sourceCopyDirty
    ? 'Save source changes before translating'
    : isError && completeness == null
      ? 'Translation status could not load for this copy.'
      : completeness == null
        ? 'Checking translation status'
        : null;
  const textUnitLink = buildCmsTextUnitDetailLink(
    mapping.tmTextUnitId,
    textUnitContext,
    mojitoLocaleTag,
  );
  const showTextUnitLink = sourceCopyDirty;
  const linkLabel = `Open saved ${field.name} translation`;
  const showTranslationAction =
    localizationStatus == null && translationSummary != null && !translationEditorOpen;
  const visibleTranslationSummary = showTranslationAction ? translationSummary : null;
  const visibleTranslationTriggerLabel =
    visibleTranslationSummary == null || preferredLocaleLabel == null
      ? null
      : canEditPreferredLocale
        ? getTranslationEditorTriggerLabel(preferredLocale, preferredLocaleLabel)
        : `Open ${preferredLocaleLabel} translation access`;
  const visibleTranslationTriggerAction =
    visibleTranslationSummary == null || preferredLocaleLabel == null
      ? null
      : canEditPreferredLocale
        ? getTranslationEditorTriggerVisibleLabel(preferredLocale, preferredLocaleLabel)
        : `Open ${preferredLocaleLabel}`;
  if (translationEditorOpen && localizationStatus != null) {
    return null;
  }
  if (localizationStatus == null && visibleTranslationSummary == null) {
    return null;
  }
  return (
    <section
      className="content-cms-admin-page__field-localization"
      aria-label={`${field.name} translation status`}
    >
      <div>
        {visibleTranslationSummary ? (
          <span
            className="content-cms-admin-page__field-localization-state"
            aria-label={visibleTranslationSummary.ariaLabel}
          >
            {visibleTranslationSummary.statusLabel}
          </span>
        ) : (
          <p className="settings-page__hint">{localizationStatus}</p>
        )}
      </div>
      {visibleTranslationSummary ? (
        <button
          type="button"
          className="settings-button"
          aria-label={visibleTranslationTriggerLabel ?? undefined}
          aria-controls={getInlineTranslationEditorId(field.id)}
          aria-expanded={translationEditorOpen}
          onClick={() => onOpenTranslation(preferredLocale?.localeTag ?? null)}
        >
          {visibleTranslationTriggerAction}
        </button>
      ) : isError && completeness == null ? (
        <button type="button" className="settings-button" onClick={onRetry}>
          Try again
        </button>
      ) : textUnitLink && showTextUnitLink ? (
        <Link
          className="content-cms-admin-page__text-unit-link"
          to={textUnitLink.to}
          state={textUnitLink.state}
        >
          {linkLabel}
        </Link>
      ) : null}
    </section>
  );
}

const inlineTranslationPrimarySaveActions = [
  { status: 'To translate', label: 'Keep as draft' },
  { status: 'To review', label: 'Send for review' },
  { status: 'Accepted', label: 'Approve for release' },
] as const;
const inlineTranslationAdvancedSaveActions = [
  { status: 'Rejected', label: 'Exclude from release' },
] as const;

function InlineTranslationEditor({
  field,
  mapping,
  sourceCopyDirty,
  completeness,
  sourceLocale,
  requestedLocaleTag,
  requestedLocaleRequestKey,
  focusOnOpenRequestKey,
  refreshRequestedTranslation,
  translationRepairSaved,
  translationReconnectErrorMessage,
  translationReconnectRefreshFailed,
  translationReconnectRefreshPending,
  suppressReconnectAutoFocusRequestKey,
  requestedLocaleSetupRefreshPending,
  returnToRelease,
  reviewLastReleasedTranslationContent,
  textUnitContext,
  isLoading,
  isRefreshing,
  isError,
  targetLocaleOptions,
  targetLocalesLoading,
  targetLocalesError,
  disabled,
  authoringRecovery = null,
  blockedPieceSwitchTargetName,
  blockedPieceSwitchReason,
  onDirtyChange,
  onSavingChange,
  onReleaseHandoffBlockerChange,
  onTranslationSaved,
  onRequestConfirmation,
  showReleaseHandoff,
  onIncludeInRelease,
  onAddRequestedTargetLocale,
  onReconnectTranslation,
  onRetry,
  onRetryTargetLocales,
  onRefreshStaleTranslation,
  onClose,
  onOpenFocusSettled,
}: {
  field: ApiCmsContentTypeField;
  mapping: ApiCmsFieldMapping;
  sourceCopyDirty: boolean;
  completeness: CmsTranslationCompleteness | null;
  sourceLocale: string;
  requestedLocaleTag: string | null;
  requestedLocaleRequestKey: number;
  focusOnOpenRequestKey: number;
  refreshRequestedTranslation: boolean;
  translationRepairSaved: boolean;
  translationReconnectErrorMessage: string | null;
  translationReconnectRefreshFailed: boolean;
  translationReconnectRefreshPending: boolean;
  suppressReconnectAutoFocusRequestKey: number;
  requestedLocaleSetupRefreshPending: boolean;
  returnToRelease: boolean;
  reviewLastReleasedTranslationContent: string | null;
  textUnitContext: CmsTextUnitContext;
  isLoading: boolean;
  isRefreshing: boolean;
  isError: boolean;
  targetLocaleOptions: LocaleOption[];
  targetLocalesLoading: boolean;
  targetLocalesError: boolean;
  disabled: boolean;
  authoringRecovery?: Extract<AuthoringRecovery, { kind: 'target-locales' }> | null;
  blockedPieceSwitchTargetName: string | null;
  blockedPieceSwitchReason: 'dirty-translation' | 'refresh-error' | null;
  onDirtyChange: (dirty: boolean) => void;
  onSavingChange: (saving: boolean) => void;
  onReleaseHandoffBlockerChange: (fieldId: string, localeTag: string, blocked: boolean) => void;
  onTranslationSaved: () => void;
  onRequestConfirmation: (
    confirmation: CmsActionConfirmation,
    onConfirm: () => void,
    onCancel?: (() => void) | null,
  ) => void;
  showReleaseHandoff: boolean;
  onIncludeInRelease: () => void;
  onAddRequestedTargetLocale: (localeTag: string) => void;
  onReconnectTranslation: (localeTag: string) => void;
  onRetry: () => void;
  onRetryTargetLocales: () => void;
  onRefreshStaleTranslation: () => void;
  onClose: () => void;
  onOpenFocusSettled: () => void;
}) {
  const user = useUser();
  const queryClient = useQueryClient();
  const resolveLocaleDisplayName = useLocaleDisplayNameResolver();
  const targetLocales = useMemo(
    () =>
      sortTargetLocalesByWork(
        getTranslationReadiness(completeness?.locales ?? [], sourceLocale).targetLocales,
      ),
    [completeness?.locales, sourceLocale],
  );
  const translationWorkflowStep = getTranslationWorkflowStep(
    completeness?.locales ?? [],
    sourceLocale,
  );
  const requestedAvailableLocaleTag =
    requestedLocaleTag != null &&
    targetLocales.some((locale) => locale.localeTag === requestedLocaleTag)
      ? requestedLocaleTag
      : null;
  const requestedUnavailableLocaleTag =
    requestedLocaleTag != null && requestedAvailableLocaleTag == null ? requestedLocaleTag : null;
  const requestedLocaleSetupRefreshPendingLabel =
    requestedLocaleSetupRefreshPending && requestedUnavailableLocaleTag != null
      ? resolveLocaleDisplayName(requestedUnavailableLocaleTag)
      : null;
  const [dismissedRequestedLocaleTag, setDismissedRequestedLocaleTag] = useState<string | null>(
    null,
  );
  const activeRequestedUnavailableLocaleTag =
    requestedUnavailableLocaleTag != null &&
    requestedLocaleSetupRefreshPendingLabel == null &&
    dismissedRequestedLocaleTag !== requestedUnavailableLocaleTag
      ? requestedUnavailableLocaleTag
      : null;
  const defaultLocaleTag =
    getMojitoLocaleTag(completeness?.locales ?? [], sourceLocale, translationWorkflowStep) ??
    targetLocales[0]?.localeTag ??
    null;
  const preferredLocaleTag =
    activeRequestedUnavailableLocaleTag == null
      ? (requestedAvailableLocaleTag ?? defaultLocaleTag)
      : null;
  const [selectedLocaleTag, setSelectedLocaleTag] = useState(preferredLocaleTag ?? '');
  const selectedLocale = targetLocales.find((locale) => locale.localeTag === selectedLocaleTag);
  const requestedUnavailableLocaleLabel =
    activeRequestedUnavailableLocaleTag == null
      ? null
      : resolveLocaleDisplayName(activeRequestedUnavailableLocaleTag);
  const requestedUnavailableLocaleCanBeAdded =
    activeRequestedUnavailableLocaleTag != null &&
    targetLocaleOptions.some(
      (locale) => locale.tag === activeRequestedUnavailableLocaleTag && locale.tag !== sourceLocale,
    );
  const requestedUnavailableLocaleCatalogState =
    requestedUnavailableLocaleLabel == null
      ? null
      : targetLocalesError
        ? 'error'
        : targetLocalesLoading
          ? 'loading'
          : requestedUnavailableLocaleCanBeAdded
            ? 'available'
            : 'unsupported';
  const selectedLocaleLabel =
    requestedLocaleSetupRefreshPendingLabel ??
    requestedUnavailableLocaleLabel ??
    (selectedLocaleTag ? resolveLocaleDisplayName(selectedLocaleTag) : 'Selected language');
  const selectedLocaleRepairSaved =
    translationRepairSaved &&
    requestedLocaleTag != null &&
    selectedLocaleTag === requestedLocaleTag;
  const selectedLocaleReconnectConflict =
    translationReconnectErrorMessage?.startsWith('Saved copy changed while reconnecting ') ===
      true &&
    requestedLocaleTag != null &&
    selectedLocaleTag === requestedLocaleTag;
  const selectedLocaleReconnectRefreshPending =
    translationReconnectRefreshPending && selectedLocaleReconnectConflict;
  const selectedLocaleReconnectRefreshFailed =
    translationReconnectRefreshFailed && selectedLocaleReconnectConflict;
  const selectedLocaleReconnectErrorMessage =
    translationReconnectErrorMessage != null &&
    requestedLocaleTag != null &&
    selectedLocaleTag === requestedLocaleTag
      ? selectedLocaleReconnectRefreshFailed
        ? `Saved copy changed while reconnecting ${selectedLocaleLabel}, but current copy could not refresh here. Select Try again to refresh current copy, then reconnect ${selectedLocaleLabel} again.`
        : selectedLocaleReconnectRefreshPending
          ? `Saved copy changed while reconnecting ${selectedLocaleLabel}. Current copy is refreshing; reconnect ${selectedLocaleLabel} again after it finishes.`
          : translationReconnectErrorMessage
      : null;
  const selectedLocaleReconnectFailed = selectedLocaleReconnectErrorMessage != null;
  const selectedLocaleReconnectAgain = selectedLocaleRepairSaved || selectedLocaleReconnectFailed;
  const completenessRefreshErrorHint =
    requestedUnavailableLocaleLabel != null && !returnToRelease
      ? `Try again before ${requestedUnavailableLocaleLabel} can be added again or you switch to another language or field.`
      : 'Saved translation is kept. Try again before release readiness can update or you switch to another language or field.';
  const [draftTarget, setDraftTarget] = useState('');
  const [baselineTarget, setBaselineTarget] = useState('');
  const [baselineStatus, setBaselineStatus] = useState('To translate');
  const [saveErrorMessage, setSaveErrorMessage] = useState<string | null>(null);
  const [saveSuccessMessage, setSaveSuccessMessage] = useState<string | null>(null);
  const [missingSavedTranslation, setMissingSavedTranslation] = useState(false);
  const [localePickerExpanded, setLocalePickerExpanded] = useState(false);
  const [saveOptionsOpen, setSaveOptionsOpen] = useState(false);
  const [advancedToolsOpen, setAdvancedToolsOpen] = useState(false);
  const preserveNextSaveSuccessRef = useRef(false);
  const onDirtyChangeRef = useRef(onDirtyChange);
  const onSavingChangeRef = useRef(onSavingChange);
  const targetCopyRef = useRef<HTMLTextAreaElement | null>(null);
  const inlineTranslationEditorRef = useRef<HTMLElement | null>(null);
  const blockedPieceSwitchRef = useRef<HTMLDivElement | null>(null);
  const secondaryConfirmationActionRef = useRef<HTMLButtonElement | null>(null);
  const translationRecoveryActionRef = useRef<HTMLButtonElement | null>(null);
  const translationStatusRefreshActionRef = useRef<HTMLButtonElement | null>(null);
  const translationStaleRefreshActionRef = useRef<HTMLButtonElement | null>(null);
  const translationReconnectActionRef = useRef<HTMLButtonElement | null>(null);
  const hadCompletenessRefreshErrorRef = useRef(false);
  const hadMissingSavedTranslationRef = useRef(false);
  const refreshedRequestedTranslationRef = useRef(0);
  const consumedReconnectAutoFocusSuppressionRequestKeyRef = useRef(0);
  const focusSelectedTranslationAfterLocaleSwitchRef = useRef(false);
  const focusLocalePickerAfterExpandRef = useRef(false);

  useEffect(() => {
    onDirtyChangeRef.current = onDirtyChange;
  }, [onDirtyChange]);
  useEffect(() => {
    onSavingChangeRef.current = onSavingChange;
  }, [onSavingChange]);
  useEffect(() => {
    if (blockedPieceSwitchTargetName == null) {
      return;
    }
    const timeoutId = window.setTimeout(() => {
      blockedPieceSwitchRef.current?.scrollIntoView?.({ block: 'nearest' });
    }, 0);
    return () => window.clearTimeout(timeoutId);
  }, [blockedPieceSwitchReason, blockedPieceSwitchTargetName]);

  useEffect(() => {
    setDismissedRequestedLocaleTag(null);
  }, [requestedLocaleRequestKey, requestedLocaleTag]);
  useEffect(() => {
    if (activeRequestedUnavailableLocaleTag != null) {
      setLocalePickerExpanded(false);
    }
  }, [activeRequestedUnavailableLocaleTag]);
  useEffect(() => {
    if (preferredLocaleTag == null) {
      setSelectedLocaleTag('');
      return;
    }

    setSelectedLocaleTag(
      (current) =>
        requestedAvailableLocaleTag ??
        (targetLocales.some((locale) => locale.localeTag === current)
          ? current
          : preferredLocaleTag),
    );
  }, [preferredLocaleTag, requestedAvailableLocaleTag, requestedLocaleRequestKey, targetLocales]);
  useEffect(() => {
    setLocalePickerExpanded(false);
    setSaveOptionsOpen(false);
    setAdvancedToolsOpen(false);
    setMissingSavedTranslation(false);
  }, [mapping.id, mapping.tmTextUnitId]);
  useEffect(() => {
    setAdvancedToolsOpen(false);
    setMissingSavedTranslation(false);
  }, [selectedLocaleTag]);

  const textUnitQuery = useQuery({
    queryKey: [
      'content-cms-inline-translation',
      mapping.id,
      mapping.tmTextUnitId,
      selectedLocaleTag,
    ],
    enabled: !sourceCopyDirty && !isLoading && !isError && selectedLocaleTag.length > 0,
    staleTime: 30_000,
    refetchOnWindowFocus: false,
    queryFn: () => fetchCmsFieldTranslation(mapping, selectedLocaleTag),
  });
  const activeTextUnit = textUnitQuery.data;
  const refetchTextUnit = textUnitQuery.refetch;
  const selectedTranslationOpening =
    textUnitQuery.isLoading || (textUnitQuery.isFetching && activeTextUnit == null);
  const selectedLocaleReleaseHandoffBlocked =
    selectedLocale?.complete === true &&
    (textUnitQuery.isError || activeTextUnit == null || missingSavedTranslation);
  const canEditSelectedLocale = selectedLocaleTag
    ? canEditLocaleForUser(user, selectedLocaleTag)
    : false;
  const canEdit = canEditSelectedLocale && !disabled;
  const editableAlternateLocales = targetLocales.filter(
    (locale) =>
      locale.localeTag !== selectedLocaleTag && canEditLocaleForUser(user, locale.localeTag),
  );
  const editableAlternateLocale = editableAlternateLocales[0] ?? null;
  const editableAlternateLocaleCount = editableAlternateLocales.length;
  const hasEditableAlternateLocale = editableAlternateLocale != null;
  const hasMultipleEditableAlternateLocales = editableAlternateLocaleCount > 1;
  const editableAlternateLocaleLabel =
    editableAlternateLocale == null
      ? null
      : resolveLocaleDisplayName(editableAlternateLocale.localeTag);
  const editableAlternateLocaleActionPhrase =
    editableAlternateLocaleLabel == null
      ? null
      : hasMultipleEditableAlternateLocales
        ? 'choose another translation language'
        : `continue with ${editableAlternateLocaleLabel}`;
  const editableAlternateLocaleActionSentence =
    editableAlternateLocaleLabel == null
      ? null
      : hasMultipleEditableAlternateLocales
        ? 'Choose another translation language'
        : `Continue with ${editableAlternateLocaleLabel}`;
  const continueWithEditableAlternateLocale = () => {
    if (editableAlternateLocale == null) {
      return;
    }
    if (activeRequestedUnavailableLocaleTag != null) {
      setDismissedRequestedLocaleTag(activeRequestedUnavailableLocaleTag);
    }
    focusSelectedTranslationAfterLocaleSwitchRef.current = true;
    setSelectedLocaleTag(editableAlternateLocale.localeTag);
  };
  const toggleEditableAlternateLocalePicker = () => {
    if (!localePickerExpanded) {
      focusLocalePickerAfterExpandRef.current = true;
    }
    setLocalePickerExpanded((current) => !current);
  };
  const selectedLocalePermissionHint = hasEditableAlternateLocale
    ? `Choose another translation language you can edit, or ask an admin to give you access to ${selectedLocaleLabel}.`
    : `Ask an admin to give you access to ${selectedLocaleLabel} before editing this translation.`;
  const requestedUnavailableLocaleActionHint =
    requestedUnavailableLocaleLabel == null
      ? null
      : requestedUnavailableLocaleCatalogState === 'error'
        ? hasEditableAlternateLocale
          ? `Translation languages could not load. Try again before adding this translation, or ${editableAlternateLocaleActionPhrase}.`
          : 'Translation languages could not load. Try again before editing this translation.'
        : requestedUnavailableLocaleCatalogState === 'loading'
          ? hasEditableAlternateLocale
            ? `Checking whether this translation language can be added again. ${editableAlternateLocaleActionSentence} while it loads.`
            : 'Checking whether this translation language can be added again.'
          : requestedUnavailableLocaleCatalogState === 'unsupported'
            ? hasEditableAlternateLocale
              ? `${editableAlternateLocaleActionSentence}, or ask an admin to add this one before editing this translation.`
              : 'Ask an admin to add this translation language before editing this translation.'
            : hasEditableAlternateLocale
              ? `Add this translation language again, or ${editableAlternateLocaleActionPhrase}.`
              : targetLocales.length > 0
                ? 'Ask an admin for another translation language or add this one again before editing this translation.'
                : 'Add this translation language again before editing this translation.';
  const requestedUnavailableLocaleRecoveryHint =
    requestedUnavailableLocaleLabel == null
      ? null
      : requestedUnavailableLocaleCatalogState === 'error'
        ? hasEditableAlternateLocale
          ? `Translation languages could not load. Try again before adding ${requestedUnavailableLocaleLabel}, or ${editableAlternateLocaleActionPhrase}.`
          : `Translation languages could not load. Try again before adding ${requestedUnavailableLocaleLabel}.`
        : requestedUnavailableLocaleCatalogState === 'loading'
          ? hasEditableAlternateLocale
            ? `Checking whether ${requestedUnavailableLocaleLabel} can be added again. ${editableAlternateLocaleActionSentence} while it loads.`
            : `Checking whether ${requestedUnavailableLocaleLabel} can be added again.`
          : requestedUnavailableLocaleCatalogState === 'unsupported'
            ? hasEditableAlternateLocale
              ? `${editableAlternateLocaleActionSentence}, or ask an admin to add ${requestedUnavailableLocaleLabel} to supported translation languages, then try again.`
              : `${requestedUnavailableLocaleLabel} is not available in supported translation languages. Ask an admin to add it, then try again.`
            : hasEditableAlternateLocale
              ? `Add ${requestedUnavailableLocaleLabel} again, or ${editableAlternateLocaleActionPhrase}.`
              : targetLocales.length > 0
                ? `Ask an admin to give you access to another translation language, or add ${requestedUnavailableLocaleLabel} again.`
                : `Add ${requestedUnavailableLocaleLabel} again before editing this translation.`;
  const requestedUnavailableLocaleRecoveryAction =
    requestedUnavailableLocaleLabel == null
      ? null
      : requestedUnavailableLocaleCatalogState === 'available'
        ? {
            label: `Add ${requestedUnavailableLocaleLabel}`,
            onClick: () => {
              if (activeRequestedUnavailableLocaleTag != null) {
                onAddRequestedTargetLocale(activeRequestedUnavailableLocaleTag);
              }
            },
          }
        : requestedUnavailableLocaleCatalogState === 'error' ||
            requestedUnavailableLocaleCatalogState === 'unsupported'
          ? {
              label: 'Try again',
              onClick: onRetryTargetLocales,
            }
          : null;
  const requestedUnavailableLocaleRecoveryActionLabel =
    requestedUnavailableLocaleRecoveryAction?.label ?? null;
  const hasCompletenessRefreshError = isError && completeness != null;
  const requestedUnavailableLocaleAlternateAction =
    editableAlternateLocaleLabel == null
      ? null
      : hasMultipleEditableAlternateLocales
        ? {
            label: localePickerExpanded ? 'Hide languages' : 'Choose another language',
            onClick: toggleEditableAlternateLocalePicker,
          }
        : {
            label: `Continue with ${editableAlternateLocaleLabel}`,
            onClick: continueWithEditableAlternateLocale,
          };
  const requestedUnavailableLocaleStateIsLoading =
    requestedUnavailableLocaleCatalogState === 'loading';
  const advancedTextUnitLink = buildCmsTextUnitDetailLink(
    mapping.tmTextUnitId,
    textUnitContext,
    selectedLocaleTag || preferredLocaleTag,
  );
  const editorSeedKey = useMemo(() => {
    if (!activeTextUnit) {
      return null;
    }
    return [
      activeTextUnit.tmTextUnitId,
      activeTextUnit.targetLocale,
      activeTextUnit.tmTextUnitVariantId ?? activeTextUnit.tmTextUnitCurrentVariantId ?? 'none',
      activeTextUnit.target ?? '',
      activeTextUnit.status ?? 'none',
      activeTextUnit.includedInLocalizedFile === false ? 'excluded' : 'included',
    ].join(':');
  }, [activeTextUnit]);

  useEffect(() => {
    if (!activeTextUnit || editorSeedKey == null) {
      return;
    }

    const nextTarget = activeTextUnit.target ?? '';
    const nextStatus = getInlineTranslationStatus(activeTextUnit);
    setDraftTarget(nextTarget);
    setBaselineTarget(nextTarget);
    setBaselineStatus(nextStatus);
    setSaveErrorMessage(null);
    setMissingSavedTranslation(false);
    if (preserveNextSaveSuccessRef.current) {
      preserveNextSaveSuccessRef.current = false;
    } else {
      setSaveSuccessMessage(null);
    }
  }, [activeTextUnit, editorSeedKey]);
  useEffect(() => {
    if (
      selectedLocaleTag.length === 0 ||
      sourceCopyDirty ||
      isLoading ||
      isError ||
      textUnitQuery.isFetching ||
      selectedTranslationOpening
    ) {
      return;
    }
    onReleaseHandoffBlockerChange(
      String(field.id),
      selectedLocaleTag,
      selectedLocaleReleaseHandoffBlocked,
    );
  }, [
    field.id,
    isError,
    isLoading,
    onReleaseHandoffBlockerChange,
    selectedLocaleReleaseHandoffBlocked,
    selectedLocaleTag,
    selectedTranslationOpening,
    sourceCopyDirty,
    textUnitQuery.isFetching,
  ]);
  useEffect(() => {
    if (
      requestedLocaleRequestKey === 0 ||
      (requestedAvailableLocaleTag != null && selectedLocaleTag !== requestedAvailableLocaleTag)
    ) {
      return;
    }
    window.setTimeout(
      () =>
        (
          targetCopyRef.current ??
          translationRecoveryActionRef.current ??
          inlineTranslationEditorRef.current
        )?.focus({ preventScroll: true }),
      0,
    );
  }, [
    activeTextUnit,
    completeness,
    isError,
    isLoading,
    requestedAvailableLocaleTag,
    requestedUnavailableLocaleCatalogState,
    requestedLocaleRequestKey,
    selectedLocaleTag,
    textUnitQuery.isError,
    selectedTranslationOpening,
  ]);
  useEffect(() => {
    if (focusOnOpenRequestKey === 0) {
      return;
    }
    let settledFocusAnimationFrameId: number | null = null;
    const focusTimeoutId = window.setTimeout(() => {
      const activeElement = document.activeElement;
      const activeElementControlsEditor =
        activeElement instanceof HTMLElement &&
        activeElement.getAttribute('aria-controls') === getInlineTranslationEditorId(field.id);
      if (
        activeElement !== document.body &&
        activeElement !== inlineTranslationEditorRef.current &&
        !activeElementControlsEditor
      ) {
        return;
      }
      const focusTarget = targetCopyRef.current ?? translationRecoveryActionRef.current;
      if (focusTarget == null) {
        return;
      }
      focusTarget.focus({ preventScroll: true });
      settledFocusAnimationFrameId = window.requestAnimationFrame(() => {
        if (document.activeElement === focusTarget) {
          onOpenFocusSettled();
        }
      });
    }, 0);
    return () => {
      window.clearTimeout(focusTimeoutId);
      if (settledFocusAnimationFrameId != null) {
        window.cancelAnimationFrame(settledFocusAnimationFrameId);
      }
    };
  }, [
    activeTextUnit,
    completeness,
    field.id,
    focusOnOpenRequestKey,
    isError,
    isLoading,
    onOpenFocusSettled,
    selectedTranslationOpening,
    textUnitQuery.isError,
  ]);
  useEffect(() => {
    if (
      authoringRecovery == null ||
      requestedUnavailableLocaleRecoveryActionLabel == null ||
      disabled ||
      hasCompletenessRefreshError
    ) {
      return;
    }
    // Let durable detail-return field focus settle before landing on this local recovery action.
    let settledFocusTimeoutId: number | null = null;
    const durableFocusTimeoutId = window.setTimeout(() => {
      settledFocusTimeoutId = window.setTimeout(
        () => translationRecoveryActionRef.current?.focus({ preventScroll: true }),
        0,
      );
    }, 0);
    return () => {
      window.clearTimeout(durableFocusTimeoutId);
      if (settledFocusTimeoutId != null) {
        window.clearTimeout(settledFocusTimeoutId);
      }
    };
  }, [
    authoringRecovery,
    disabled,
    hasCompletenessRefreshError,
    requestedUnavailableLocaleRecoveryActionLabel,
  ]);
  useEffect(() => {
    if (
      selectedLocaleReconnectErrorMessage == null ||
      disabled ||
      selectedLocaleReconnectRefreshPending
    ) {
      return;
    }
    if (
      suppressReconnectAutoFocusRequestKey !== 0 &&
      consumedReconnectAutoFocusSuppressionRequestKeyRef.current !==
        suppressReconnectAutoFocusRequestKey
    ) {
      consumedReconnectAutoFocusSuppressionRequestKeyRef.current =
        suppressReconnectAutoFocusRequestKey;
      return;
    }
    // Let durable detail-return field focus settle before landing on this local recovery action.
    const recoveryActionRef = selectedLocaleReconnectRefreshFailed
      ? translationRecoveryActionRef
      : translationReconnectActionRef;
    let settledFocusTimeoutId: number | null = null;
    const durableFocusTimeoutId = window.setTimeout(() => {
      settledFocusTimeoutId = window.setTimeout(
        () => recoveryActionRef.current?.focus({ preventScroll: true }),
        0,
      );
    }, 0);
    return () => {
      window.clearTimeout(durableFocusTimeoutId);
      if (settledFocusTimeoutId != null) {
        window.clearTimeout(settledFocusTimeoutId);
      }
    };
  }, [
    disabled,
    selectedLocaleReconnectErrorMessage,
    selectedLocaleReconnectRefreshFailed,
    selectedLocaleReconnectRefreshPending,
    suppressReconnectAutoFocusRequestKey,
  ]);
  useEffect(() => {
    if (!focusSelectedTranslationAfterLocaleSwitchRef.current) {
      return;
    }
    const focusTarget = targetCopyRef.current ?? translationRecoveryActionRef.current;
    if (focusTarget == null) {
      return;
    }
    focusSelectedTranslationAfterLocaleSwitchRef.current = false;
    window.setTimeout(() => focusTarget.focus({ preventScroll: true }), 0);
  }, [activeTextUnit, selectedLocaleTag, selectedTranslationOpening, textUnitQuery.isError]);
  useEffect(() => {
    if (
      !refreshRequestedTranslation ||
      requestedLocaleRequestKey === 0 ||
      refreshedRequestedTranslationRef.current === requestedLocaleRequestKey ||
      sourceCopyDirty ||
      isLoading ||
      isError ||
      selectedLocaleTag.length === 0 ||
      (requestedAvailableLocaleTag != null && selectedLocaleTag !== requestedAvailableLocaleTag)
    ) {
      return;
    }
    refreshedRequestedTranslationRef.current = requestedLocaleRequestKey;
    if (textUnitQuery.isFetching) {
      return;
    }
    void refetchTextUnit();
  }, [
    isError,
    isLoading,
    refreshRequestedTranslation,
    requestedAvailableLocaleTag,
    requestedLocaleRequestKey,
    selectedLocaleTag,
    sourceCopyDirty,
    refetchTextUnit,
    textUnitQuery.isFetching,
  ]);

  const saveMutation = useMutation({
    mutationFn: async (request: SaveTextUnitRequest) => {
      const integrityResult = await checkTextUnitIntegrityWithRetry({
        tmTextUnitId: request.tmTextUnitId,
        content: request.target,
      });
      if (integrityResult?.checkResult === false) {
        throw new Error(
          integrityResult.failureDetail?.trim() ||
            'Translation failed placeholder or ICU validation.',
        );
      }
      return saveTextUnit(request);
    },
    onSuccess: (saved, request) => {
      const nextTarget = saved.target ?? request.target;
      const nextStatus = getInlineTranslationStatus({
        target: nextTarget,
        status: saved.status ?? request.status,
        includedInLocalizedFile: saved.includedInLocalizedFile ?? request.includedInLocalizedFile,
      });
      setDraftTarget(nextTarget);
      setBaselineTarget(nextTarget);
      setBaselineStatus(nextStatus);
      setSaveErrorMessage(null);
      setMissingSavedTranslation(false);
      preserveNextSaveSuccessRef.current = true;
      setSaveSuccessMessage(getInlineTranslationSaveMessage(selectedLocaleLabel, nextStatus));
      queryClient.setQueryData(
        ['content-cms-inline-translation', mapping.id, mapping.tmTextUnitId, selectedLocaleTag],
        saved,
      );
      void queryClient.invalidateQueries({ queryKey: ['workbench-search'] });
      onTranslationSaved();
      onRetry();
    },
    onError: (error: unknown, request) => {
      const status = (error as { status?: number })?.status;
      if (status === 403) {
        setSaveErrorMessage('You cannot edit this translation language.');
        setSaveSuccessMessage(null);
        return;
      }
      if (status === 404) {
        setMissingSavedTranslation(true);
        setSaveErrorMessage(null);
        setSaveSuccessMessage(null);
        return;
      }
      setMissingSavedTranslation(false);
      setSaveErrorMessage(
        getInlineTranslationSaveErrorMessage(error, {
          baselineStatus,
          baselineTarget,
          localeLabel: selectedLocaleLabel,
          request,
        }),
      );
      setSaveSuccessMessage(null);
    },
  });
  const isDirty = draftTarget !== baselineTarget;
  useEffect(() => {
    onDirtyChangeRef.current(isDirty);
    return () => onDirtyChangeRef.current(false);
  }, [isDirty]);
  useEffect(() => {
    onSavingChangeRef.current(saveMutation.isPending);
  }, [saveMutation.isPending]);
  useEffect(
    () => () => {
      onSavingChangeRef.current(false);
    },
    [],
  );
  useEffect(() => {
    if (isDirty) {
      setAdvancedToolsOpen(false);
    }
  }, [isDirty]);
  useEffect(() => {
    const refreshErrorAppeared =
      hasCompletenessRefreshError && !hadCompletenessRefreshErrorRef.current;
    hadCompletenessRefreshErrorRef.current = hasCompletenessRefreshError;
    if (!refreshErrorAppeared) {
      return;
    }
    window.setTimeout(
      () => translationStatusRefreshActionRef.current?.focus({ preventScroll: true }),
      0,
    );
  }, [hasCompletenessRefreshError]);
  useEffect(() => {
    const missingTranslationAppeared =
      missingSavedTranslation && !hadMissingSavedTranslationRef.current;
    hadMissingSavedTranslationRef.current = missingSavedTranslation;
    if (!missingTranslationAppeared) {
      return;
    }
    window.setTimeout(
      () => translationStaleRefreshActionRef.current?.focus({ preventScroll: true }),
      0,
    );
  }, [missingSavedTranslation]);
  const selectedLocaleStatus =
    requestedUnavailableLocaleLabel != null
      ? 'Not set up'
      : hasCompletenessRefreshError
        ? getInlineTranslationStatusOptionLabel(baselineStatus)
        : getLocaleStatusLabel(selectedLocale);
  const nextStepStatus = getInlineTranslationStatusOptionLabel(baselineStatus);
  const showNextStepStatus = nextStepStatus !== selectedLocaleStatus;
  const selectedLocaleProgress =
    requestedUnavailableLocaleLabel != null
      ? requestedUnavailableLocaleActionHint
      : hasCompletenessRefreshError
        ? 'Translation status needs refresh before release readiness can update.'
        : getLocaleProgressSummary(selectedLocale);
  const selectedLocaleAction =
    requestedUnavailableLocaleLabel != null
      ? {
          title: `${requestedUnavailableLocaleLabel} is not set up`,
          hint: requestedUnavailableLocaleActionHint,
        }
      : hasCompletenessRefreshError
        ? getInlineTranslationRefreshErrorAction(selectedLocaleLabel, baselineStatus)
        : !canEditSelectedLocale
          ? {
              title: `You cannot edit ${selectedLocaleLabel}`,
              hint: selectedLocalePermissionHint,
            }
          : getLocaleAction(selectedLocale, selectedLocaleLabel);
  const selectedLocaleActionHint = selectedLocaleAction.hint;
  const targetLocalePickerCollapsible =
    hasEditableAlternateLocale && requestedUnavailableLocaleLabel == null;
  const primarySaveStatus = getPrimaryInlineTranslationSaveStatus(selectedLocale, baselineStatus);
  const inlineTranslationHelpLabel = getInlineTranslationHelpLabel(baselineStatus);
  const showReleaseReviewTranslationComparison =
    reviewLastReleasedTranslationContent != null &&
    requestedLocaleTag != null &&
    selectedLocaleTag === requestedLocaleTag;
  const blockLocaleSwitch =
    hasCompletenessRefreshError || missingSavedTranslation || isDirty || saveMutation.isPending;
  useEffect(() => {
    if (!localePickerExpanded || !focusLocalePickerAfterExpandRef.current) {
      return;
    }
    focusLocalePickerAfterExpandRef.current = false;
    inlineTranslationEditorRef.current
      ?.querySelector<HTMLButtonElement>(
        '.content-cms-admin-page__inline-translation-locales button:not(:disabled)',
      )
      ?.focus({ preventScroll: true });
  }, [localePickerExpanded]);
  const blockAdvancedTools = blockLocaleSwitch;
  const inlineTranslationEditorId = getInlineTranslationEditorId(field.id);
  const inlineTranslationLocalePickerId = `${inlineTranslationEditorId}-languages`;
  const inlineTranslationSaveOptionsId = `${inlineTranslationEditorId}-other-actions`;
  const inlineTranslationAdvancedToolsId = `${inlineTranslationEditorId}-tools`;
  const completenessRefreshErrorStatusId = `${inlineTranslationEditorId}-refresh-status`;
  const missingSavedTranslationStatusId = `${inlineTranslationEditorId}-stale-status`;
  const requestedUnavailableLocaleStatusId = `${inlineTranslationEditorId}-requested-locale-status`;
  const selectedLocaleAccessStatusId = `${inlineTranslationEditorId}-access-status`;
  const selectedLocaleLoadErrorStatusId = `${inlineTranslationEditorId}-load-status`;
  const selectedLocaleUnavailableStatusId = `${inlineTranslationEditorId}-unavailable-status`;
  const canSaveTranslationAs = (nextStatus: string) => {
    const needsTargetCopy = nextStatus === 'To review' || nextStatus === 'Accepted';
    return (
      canEdit &&
      !hasCompletenessRefreshError &&
      !missingSavedTranslation &&
      !saveMutation.isPending &&
      (isDirty || nextStatus !== baselineStatus) &&
      (!needsTargetCopy || draftTarget.trim().length > 0)
    );
  };
  const needsTranslationDraft =
    baselineStatus === 'To translate' && draftTarget.trim().length === 0 && !isDirty;
  const primarySaveAction =
    inlineTranslationPrimarySaveActions.find((action) => action.status === primarySaveStatus) ??
    inlineTranslationPrimarySaveActions[0];
  const secondarySaveActions = needsTranslationDraft
    ? []
    : inlineTranslationPrimarySaveActions.filter((action) => action.status !== primarySaveStatus);
  const saveTranslation = (nextStatus: string) => {
    if (!activeTextUnit || typeof activeTextUnit.localeId !== 'number') {
      setSaveErrorMessage('Translation is still loading.');
      return;
    }
    const statusUpdate = mapUiStatusToApi(nextStatus);
    if (!statusUpdate?.status) {
      setSaveErrorMessage('Choose a valid translation status.');
      return;
    }
    saveMutation.mutate({
      tmTextUnitId: activeTextUnit.tmTextUnitId,
      localeId: activeTextUnit.localeId,
      target: draftTarget,
      status: statusUpdate.status,
      includedInLocalizedFile: statusUpdate.includedInLocalizedFile,
    });
  };
  const saveSecondaryTranslationWithConfirmation = (nextStatus: string) => {
    if (nextStatus !== 'Rejected') {
      saveTranslation(nextStatus);
      return;
    }
    onRequestConfirmation(
      {
        title: `Exclude ${selectedLocaleLabel} from release?`,
        body: `Exclude ${selectedLocaleLabel} translation from the next release? The saved translation stays available, but it will need another save, review, or approval before apps can receive it again.`,
        confirmLabel: 'Exclude from release',
        confirmVariant: 'danger',
      },
      () => saveTranslation(nextStatus),
      () => {
        window.setTimeout(
          () => secondaryConfirmationActionRef.current?.focus({ preventScroll: true }),
          0,
        );
      },
    );
  };
  const resetTranslationDraft = () => {
    setDraftTarget(baselineTarget);
    setSaveErrorMessage(null);
    setSaveSuccessMessage(null);
    if (blockedPieceSwitchTargetName == null) {
      window.setTimeout(() => targetCopyRef.current?.focus({ preventScroll: true }), 0);
    }
  };
  const refreshStaleTranslation = () => {
    onRequestConfirmation(
      {
        title: `Refresh ${field.name}?`,
        body: `The saved ${selectedLocaleLabel} translation is no longer available. Refresh this copy to load the latest saved translation. Unsaved ${selectedLocaleLabel} edits will be discarded.`,
        confirmLabel: 'Refresh copy',
      },
      () => {
        setDraftTarget(baselineTarget);
        setSaveErrorMessage(null);
        setSaveSuccessMessage(null);
        setMissingSavedTranslation(false);
        void queryClient.invalidateQueries({
          queryKey: ['content-cms-inline-translation', mapping.id, mapping.tmTextUnitId],
        });
        onRefreshStaleTranslation();
      },
    );
  };

  if (sourceCopyDirty) {
    return (
      <section
        id={inlineTranslationEditorId}
        ref={inlineTranslationEditorRef}
        className="content-cms-admin-page__inline-translation"
        aria-label={`${field.name} translation editor`}
        tabIndex={-1}
      >
        <div>
          <h4>Save source changes before translating</h4>
          <p className="settings-page__hint">
            This translation still uses the last saved source. Save source changes before editing
            it.
          </p>
          <Link
            className="content-cms-admin-page__text-unit-link"
            to={advancedTextUnitLink.to}
            state={advancedTextUnitLink.state}
          >
            Open saved {field.name} translation
          </Link>
        </div>
        <button type="button" className="settings-button" onClick={onClose}>
          Hide translation editor
        </button>
      </section>
    );
  }
  if (requestedLocaleSetupRefreshPendingLabel != null) {
    return (
      <section
        id={inlineTranslationEditorId}
        ref={inlineTranslationEditorRef}
        className="content-cms-admin-page__inline-translation"
        aria-label={`${field.name} translation editor`}
        tabIndex={-1}
      >
        <div>
          <h4>
            {isError
              ? `${requestedLocaleSetupRefreshPendingLabel} translation could not open.`
              : `Opening ${requestedLocaleSetupRefreshPendingLabel} translation`}
          </h4>
          {isError ? <p className="settings-page__hint">Try again to open it.</p> : null}
        </div>
        <div className="content-cms-admin-page__inline-translation-actions">
          {isError ? (
            <button
              ref={translationRecoveryActionRef}
              type="button"
              className="settings-button"
              disabled={isRefreshing}
              onClick={onRetry}
            >
              {isRefreshing ? 'Opening...' : 'Try again'}
            </button>
          ) : null}
          <button type="button" className="settings-button" onClick={onClose}>
            Hide translation editor
          </button>
        </div>
      </section>
    );
  }
  if ((isError || isRefreshing) && completeness == null) {
    return (
      <section
        id={inlineTranslationEditorId}
        ref={inlineTranslationEditorRef}
        className="content-cms-admin-page__inline-translation"
        aria-label={`${field.name} translation editor`}
        tabIndex={-1}
      >
        <div>
          <h4>{selectedLocaleLabel} translation could not open.</h4>
          <p className="settings-page__hint">Try again to open it.</p>
        </div>
        <div className="content-cms-admin-page__inline-translation-actions">
          <button
            ref={translationRecoveryActionRef}
            type="button"
            className="settings-button"
            disabled={isRefreshing}
            onClick={onRetry}
          >
            {isRefreshing ? 'Opening...' : 'Try again'}
          </button>
          <button type="button" className="settings-button" onClick={onClose}>
            Hide translation editor
          </button>
        </div>
      </section>
    );
  }
  if (isLoading || completeness == null) {
    return (
      <section
        id={inlineTranslationEditorId}
        ref={inlineTranslationEditorRef}
        className="content-cms-admin-page__inline-translation"
        aria-label={`${field.name} translation editor`}
        tabIndex={-1}
      >
        <div>
          <h4>Opening {selectedLocaleLabel} translation</h4>
        </div>
        <button type="button" className="settings-button" onClick={onClose}>
          Hide translation editor
        </button>
      </section>
    );
  }
  if (targetLocales.length === 0 && requestedUnavailableLocaleLabel == null) {
    return null;
  }

  return (
    <section
      id={inlineTranslationEditorId}
      ref={inlineTranslationEditorRef}
      className="content-cms-admin-page__inline-translation"
      aria-label={`${field.name} translation editor`}
      tabIndex={-1}
    >
      <div className="content-cms-admin-page__inline-translation-header">
        <div>
          <h4>{selectedLocaleAction.title}</h4>
          {selectedLocaleActionHint ? (
            <p className="settings-page__hint">{selectedLocaleActionHint}</p>
          ) : null}
        </div>
        <span
          className="content-cms-admin-page__inline-translation-current-state"
          aria-label={`${selectedLocaleLabel} ${selectedLocaleStatus}`}
        >
          {selectedLocaleStatus}
        </span>
      </div>
      {targetLocalePickerCollapsible ? (
        <button
          type="button"
          className="content-cms-admin-page__locale-readiness-toggle"
          aria-expanded={localePickerExpanded}
          aria-controls={inlineTranslationLocalePickerId}
          disabled={blockLocaleSwitch && !localePickerExpanded}
          onClick={() => setLocalePickerExpanded((current) => !current)}
        >
          {localePickerExpanded ? 'Hide languages' : 'Switch language'}
        </button>
      ) : null}
      {localePickerExpanded ? (
        <div
          id={inlineTranslationLocalePickerId}
          className="content-cms-admin-page__inline-translation-locales"
          role="group"
          aria-label="Translation languages"
        >
          {targetLocales.map((locale) => {
            const canEditLocale = canEditLocaleForUser(user, locale.localeTag);
            const localeStatusLabel = canEditLocale
              ? hasCompletenessRefreshError
                ? getInlineTranslationRefreshLocaleStatusLabel(
                    locale.localeTag,
                    selectedLocaleTag,
                    baselineStatus,
                  )
                : getLocaleStatusLabel(locale)
              : 'No access';
            const localeActionLabel = canEditLocale
              ? hasCompletenessRefreshError
                ? getInlineTranslationRefreshLocaleActionLabel(
                    locale.localeTag,
                    selectedLocaleTag,
                    baselineStatus,
                  )
                : getInlineTranslationLocaleActionLabel(locale)
              : 'No access';
            return (
              <button
                key={locale.localeTag}
                type="button"
                className={`content-cms-admin-page__inline-translation-locale${
                  locale.localeTag === selectedLocaleTag ? ' is-active' : ''
                }`}
                aria-label={`${resolveLocaleDisplayName(locale.localeTag)} ${localeStatusLabel}`}
                aria-pressed={locale.localeTag === selectedLocaleTag}
                disabled={blockLocaleSwitch || !canEditLocale}
                onClick={() => {
                  if (blockLocaleSwitch || !canEditLocale) {
                    return;
                  }
                  if (activeRequestedUnavailableLocaleTag != null) {
                    setDismissedRequestedLocaleTag(activeRequestedUnavailableLocaleTag);
                  }
                  if (locale.localeTag !== selectedLocaleTag) {
                    focusSelectedTranslationAfterLocaleSwitchRef.current = true;
                  }
                  setSelectedLocaleTag(locale.localeTag);
                }}
              >
                <span>{resolveLocaleDisplayName(locale.localeTag)}</span>
                <span>{localeActionLabel}</span>
              </button>
            );
          })}
        </div>
      ) : null}
      {isDirty ? (
        <p className="settings-hint is-warning content-cms-admin-page__inline-translation-locale-hint">
          Save or reset translation edits before switching languages or opening translation tools.
        </p>
      ) : null}
      {hasCompletenessRefreshError ? (
        <div className="content-cms-admin-page__inline-translation-state is-error">
          <div id={completenessRefreshErrorStatusId} role="alert">
            <strong>Translation status could not refresh.</strong>
            <span>{completenessRefreshErrorHint}</span>
          </div>
          <button
            ref={translationStatusRefreshActionRef}
            type="button"
            className="settings-button"
            disabled={isRefreshing}
            aria-describedby={completenessRefreshErrorStatusId}
            onClick={onRetry}
          >
            {isRefreshing ? 'Refreshing status...' : 'Try again'}
          </button>
        </div>
      ) : null}
      {missingSavedTranslation ? (
        <div className="content-cms-admin-page__inline-translation-state is-error">
          <div id={missingSavedTranslationStatusId} role="alert">
            <strong>This saved translation is no longer available.</strong>
            <span>
              Refresh this copy before saving again. Your {selectedLocaleLabel} draft stays here
              until you refresh.
            </span>
          </div>
          <button
            ref={translationStaleRefreshActionRef}
            type="button"
            className="settings-button"
            aria-describedby={missingSavedTranslationStatusId}
            onClick={refreshStaleTranslation}
          >
            Refresh copy
          </button>
        </div>
      ) : null}
      {requestedUnavailableLocaleLabel != null ? (
        <div
          className={`content-cms-admin-page__inline-translation-state${
            requestedUnavailableLocaleStateIsLoading ? '' : ' is-error'
          }`}
        >
          <div
            id={requestedUnavailableLocaleStatusId}
            role={requestedUnavailableLocaleStateIsLoading ? 'status' : 'alert'}
            aria-live={requestedUnavailableLocaleStateIsLoading ? 'polite' : undefined}
          >
            <strong>{requestedUnavailableLocaleLabel} is not set up for this copy.</strong>
            <span>{requestedUnavailableLocaleRecoveryHint}</span>
          </div>
          <div className="content-cms-admin-page__inline-translation-actions">
            {requestedUnavailableLocaleRecoveryAction ? (
              <button
                ref={translationRecoveryActionRef}
                type="button"
                className="settings-button"
                disabled={disabled}
                aria-describedby={requestedUnavailableLocaleStatusId}
                onClick={requestedUnavailableLocaleRecoveryAction.onClick}
              >
                {requestedUnavailableLocaleRecoveryAction.label}
              </button>
            ) : null}
            {requestedUnavailableLocaleAlternateAction ? (
              <button
                type="button"
                className="settings-button settings-button--ghost"
                disabled={blockLocaleSwitch}
                aria-describedby={requestedUnavailableLocaleStatusId}
                onClick={requestedUnavailableLocaleAlternateAction.onClick}
              >
                {requestedUnavailableLocaleAlternateAction.label}
              </button>
            ) : (
              <button
                type="button"
                className="settings-button settings-button--ghost"
                aria-describedby={requestedUnavailableLocaleStatusId}
                onClick={onClose}
              >
                Hide translation editor
              </button>
            )}
          </div>
          <AuthoringRecoveryPanel authoringRecovery={authoringRecovery} />
        </div>
      ) : !canEditSelectedLocale ? (
        <div className="content-cms-admin-page__inline-translation-state is-error">
          <div id={selectedLocaleAccessStatusId} role="alert">
            <strong>Language access needed.</strong>
            <span>
              {hasEditableAlternateLocale
                ? 'Choose another language below to keep working here.'
                : 'Ask an admin for language access before editing this translation.'}
            </span>
          </div>
          <div className="content-cms-admin-page__inline-translation-actions">
            {hasEditableAlternateLocale ? (
              <button
                ref={translationRecoveryActionRef}
                type="button"
                className="settings-button"
                aria-describedby={selectedLocaleAccessStatusId}
                onClick={() => setLocalePickerExpanded(true)}
              >
                Choose another language
              </button>
            ) : null}
            <button
              type="button"
              className="settings-button"
              aria-describedby={selectedLocaleAccessStatusId}
              onClick={onClose}
            >
              Hide translation editor
            </button>
          </div>
        </div>
      ) : selectedTranslationOpening ? (
        <div
          className="content-cms-admin-page__inline-translation-state"
          role="status"
          aria-live="polite"
        >
          <div>
            <strong>Opening {selectedLocaleLabel} translation</strong>
            <span>Preparing this translation for editing.</span>
          </div>
        </div>
      ) : textUnitQuery.isError ? (
        <div className="content-cms-admin-page__inline-translation-state is-error">
          <div id={selectedLocaleLoadErrorStatusId} role="alert">
            <strong>{selectedLocaleLabel} translation could not load.</strong>
            <span>Try again to open it.</span>
          </div>
          <button
            ref={translationRecoveryActionRef}
            type="button"
            className="settings-button"
            aria-describedby={selectedLocaleLoadErrorStatusId}
            onClick={() => void refetchTextUnit()}
          >
            Try again
          </button>
        </div>
      ) : activeTextUnit == null ? (
        <div
          className={`content-cms-admin-page__inline-translation-state${
            selectedLocaleRepairSaved && !selectedLocaleReconnectFailed ? '' : ' is-error'
          }`}
        >
          <div
            id={selectedLocaleUnavailableStatusId}
            role={selectedLocaleRepairSaved && !selectedLocaleReconnectFailed ? 'status' : 'alert'}
            aria-live={
              selectedLocaleRepairSaved && !selectedLocaleReconnectFailed ? 'polite' : undefined
            }
          >
            {selectedLocaleReconnectErrorMessage != null ? (
              <>
                <strong>{selectedLocaleLabel} translation could not reconnect.</strong>
                <span>{selectedLocaleReconnectErrorMessage}</span>
              </>
            ) : selectedLocaleRepairSaved ? (
              <>
                <strong>{selectedLocaleLabel} translation still needs work.</strong>
                <span>
                  Try again to open it. Reconnect {selectedLocaleLabel} again only if it stays
                  unavailable.
                </span>
              </>
            ) : (
              <>
                <strong>{selectedLocaleLabel} translation is unavailable.</strong>
                <span>Try again, choose another language, or reconnect {selectedLocaleLabel}.</span>
              </>
            )}
          </div>
          <div className="content-cms-admin-page__inline-translation-actions">
            <button
              ref={translationRecoveryActionRef}
              type="button"
              className="settings-button content-cms-admin-page__translation-reconnect-refresh-action"
              disabled={selectedLocaleReconnectRefreshPending}
              aria-describedby={selectedLocaleUnavailableStatusId}
              onClick={() => {
                if (selectedLocaleReconnectRefreshFailed) {
                  onRefreshStaleTranslation();
                  return;
                }
                void refetchTextUnit();
              }}
            >
              {selectedLocaleReconnectRefreshPending ? 'Refreshing copy...' : 'Try again'}
            </button>
            <button
              ref={translationReconnectActionRef}
              type="button"
              className="settings-button content-cms-admin-page__translation-reconnect-action"
              disabled={disabled || selectedLocaleReconnectRefreshPending}
              aria-describedby={selectedLocaleUnavailableStatusId}
              onClick={() => onReconnectTranslation(selectedLocaleTag)}
            >
              {selectedLocaleReconnectAgain
                ? `Reconnect ${selectedLocaleLabel} again`
                : `Reconnect ${selectedLocaleLabel}`}
            </button>
          </div>
        </div>
      ) : (
        <form className="content-cms-admin-page__inline-translation-form">
          <label className="content-cms-admin-page__copy-field">
            <span className="content-cms-admin-page__copy-field-label">
              {selectedLocaleLabel} translation
            </span>
            <AutoTextarea
              ref={targetCopyRef}
              aria-label={`${selectedLocaleLabel} translation`}
              className="settings-input content-cms-admin-page__textarea content-cms-admin-page__textarea--copy content-cms-admin-page__target-copy-input"
              value={draftTarget}
              minRows={2}
              maxRows={10}
              onChange={(event) => {
                setDraftTarget(event.target.value);
                setSaveSuccessMessage(null);
              }}
              disabled={!canEdit || saveMutation.isPending}
              placeholder={`Write ${selectedLocaleLabel} translation`}
            />
          </label>
          {showReleaseReviewTranslationComparison ? (
            <ReleaseTranslationReviewNotice
              currentTranslationContent={activeTextUnit.target ?? ''}
              lastReleasedTranslationContent={reviewLastReleasedTranslationContent}
              localeLabel={selectedLocaleLabel}
            />
          ) : null}
          <section className="content-cms-admin-page__inline-translation-next-step">
            <div className="content-cms-admin-page__inline-translation-next-step-header">
              <div>
                <span className="content-cms-admin-page__eyebrow">Next step</span>
                <strong>
                  {needsTranslationDraft ? 'Start this translation' : 'Save this translation'}
                </strong>
              </div>
              {showNextStepStatus ? (
                <span className="content-cms-admin-page__inline-translation-current-state">
                  {nextStepStatus}
                </span>
              ) : null}
            </div>
            {needsTranslationDraft ? (
              <p className="settings-page__hint">
                Write the translation to unlock draft, review, and release actions.
              </p>
            ) : (
              <div
                className="content-cms-admin-page__inline-translation-save-actions"
                role="group"
                aria-label="Save this translation"
              >
                <button
                  type="button"
                  className="settings-button settings-button--primary"
                  disabled={!canSaveTranslationAs(primarySaveAction.status)}
                  onClick={() => saveTranslation(primarySaveAction.status)}
                >
                  {primarySaveAction.label}
                </button>
              </div>
            )}
            <button
              type="button"
              className="content-cms-admin-page__locale-readiness-toggle"
              aria-expanded={saveOptionsOpen}
              aria-controls={inlineTranslationSaveOptionsId}
              disabled={!canEdit || saveMutation.isPending}
              onClick={() => setSaveOptionsOpen((current) => !current)}
            >
              {saveOptionsOpen ? 'Hide other actions' : 'Other actions'}
            </button>
            {saveOptionsOpen ? (
              <div
                id={inlineTranslationSaveOptionsId}
                className="content-cms-admin-page__inline-translation-save-options"
                role="group"
                aria-label="Other actions"
              >
                {secondarySaveActions.map((action) => (
                  <button
                    key={action.status}
                    type="button"
                    className="settings-button settings-button--ghost"
                    disabled={!canSaveTranslationAs(action.status)}
                    onClick={() => saveTranslation(action.status)}
                  >
                    {action.label}
                  </button>
                ))}
                {inlineTranslationAdvancedSaveActions.map((action) => (
                  <button
                    key={action.status}
                    ref={secondaryConfirmationActionRef}
                    type="button"
                    className="settings-button settings-button--ghost"
                    disabled={!canSaveTranslationAs(action.status)}
                    onClick={() => saveSecondaryTranslationWithConfirmation(action.status)}
                  >
                    {action.label}
                  </button>
                ))}
              </div>
            ) : null}
          </section>
          {saveErrorMessage ? <p className="settings-hint is-error">{saveErrorMessage}</p> : null}
          {draftTarget.trim().length === 0 && !needsTranslationDraft ? (
            <p className="settings-page__hint">
              Write the translation before sending it for review or approving release.
            </p>
          ) : null}
          {saveSuccessMessage || isDirty ? (
            <div
              className={`content-cms-admin-page__inline-translation-feedback${
                saveSuccessMessage ? ' is-success' : ' is-warning'
              }`}
              role="status"
              aria-live="polite"
            >
              <span className="content-cms-admin-page__eyebrow">
                {saveSuccessMessage ? 'Saved' : 'Unsaved'}
              </span>
              <strong>{saveSuccessMessage ?? `${selectedLocaleLabel} translation edits`}</strong>
              <span>
                {isDirty
                  ? `Save or reset ${selectedLocaleLabel} translation edits before leaving this field.`
                  : selectedLocaleProgress}
              </span>
            </div>
          ) : null}
          {blockedPieceSwitchTargetName ? (
            <div
              ref={blockedPieceSwitchRef}
              className="content-cms-admin-page__inline-translation-state is-error"
            >
              <div role="alert">
                {blockedPieceSwitchReason === 'refresh-error' ? (
                  <>
                    <strong>
                      Refresh translation status to open {blockedPieceSwitchTargetName}.
                    </strong>
                    <span>
                      Try again; {blockedPieceSwitchTargetName} will open after status refresh
                      succeeds.
                    </span>
                  </>
                ) : (
                  <>
                    <strong>Finish this translation to open {blockedPieceSwitchTargetName}.</strong>
                    <span>
                      Save or reset {selectedLocaleLabel} translation edits;{' '}
                      {blockedPieceSwitchTargetName} will open next.
                    </span>
                  </>
                )}
              </div>
            </div>
          ) : null}
          {showReleaseHandoff ? (
            <InlineTranslationReleaseHandoff
              disabled={disabled}
              onIncludeInRelease={onIncludeInRelease}
            />
          ) : null}
          <div className="content-cms-admin-page__inline-translation-actions">
            <button
              type="button"
              className="settings-button"
              disabled={blockLocaleSwitch}
              onClick={onClose}
            >
              Hide translation editor
            </button>
            {isDirty ? (
              <button
                type="button"
                className="settings-button"
                disabled={saveMutation.isPending}
                onClick={resetTranslationDraft}
              >
                Reset
              </button>
            ) : null}
            <button
              type="button"
              className="content-cms-admin-page__locale-readiness-toggle content-cms-admin-page__inline-translation-tools-trigger"
              aria-expanded={advancedToolsOpen}
              aria-controls={inlineTranslationAdvancedToolsId}
              disabled={blockAdvancedTools}
              onClick={() => setAdvancedToolsOpen((current) => !current)}
            >
              {advancedToolsOpen
                ? `Hide ${inlineTranslationHelpLabel.toLocaleLowerCase()}`
                : `More ${inlineTranslationHelpLabel.toLocaleLowerCase()}`}
            </button>
          </div>
          {advancedToolsOpen ? (
            <aside
              id={inlineTranslationAdvancedToolsId}
              className="content-cms-admin-page__inline-translation-advanced-tools"
              aria-label={`Optional ${inlineTranslationHelpLabel.toLocaleLowerCase()}`}
            >
              <Link
                className="content-cms-admin-page__text-unit-link"
                to={advancedTextUnitLink.to}
                state={advancedTextUnitLink.state}
              >
                Open detailed translation review
              </Link>
            </aside>
          ) : null}
        </form>
      )}
    </section>
  );
}

function InlineTranslationReleaseHandoff({
  disabled,
  onIncludeInRelease,
}: {
  disabled: boolean;
  onIncludeInRelease: () => void;
}) {
  return (
    <aside
      className="content-cms-admin-page__inline-translation-release-handoff"
      aria-label="Ready for release"
    >
      <div>
        <span className="content-cms-admin-page__eyebrow">Next step</span>
        <strong>Ready for release</strong>
        <span>Every translation language is approved. Include this copy in the next release.</span>
      </div>
      <button
        type="button"
        className="settings-button settings-button--primary"
        disabled={disabled}
        onClick={onIncludeInRelease}
      >
        Include in next release
      </button>
    </aside>
  );
}

function SourceCopyRefreshNotice({
  draft,
  savedDraft,
  onUseSavedCopy,
}: {
  draft: MappingDraft;
  savedDraft: MappingDraft | null;
  onUseSavedCopy: () => void;
}) {
  if (savedDraft == null) {
    return null;
  }
  const refreshStatusId = 'source-copy-refresh-status';
  return (
    <aside className="content-cms-admin-page__source-copy-refresh">
      <div id={refreshStatusId} role="status" aria-live="polite">
        <span className="content-cms-admin-page__eyebrow">Saved elsewhere</span>
        <strong>Saved source copy changed while you were editing.</strong>
        <span>Review the saved copy before overwriting it with this draft.</span>
      </div>
      <div className="content-cms-admin-page__source-copy-refresh-compare">
        <SourceCopyRefreshValue label="Current saved copy" value={savedDraft.sourceContent} />
        <SourceCopyRefreshValue label="Your draft" value={draft.sourceContent} />
      </div>
      {savedDraft.sourceComment !== draft.sourceComment ? (
        <div className="content-cms-admin-page__source-copy-refresh-compare">
          <SourceCopyRefreshValue label="Current saved note" value={savedDraft.sourceComment} />
          <SourceCopyRefreshValue label="Your note" value={draft.sourceComment} />
        </div>
      ) : null}
      <button
        type="button"
        className="settings-button"
        aria-describedby={refreshStatusId}
        onClick={onUseSavedCopy}
      >
        Use saved copy
      </button>
    </aside>
  );
}

function ReleaseSourceCopyReviewNotice({
  currentSourceContent,
  lastReleasedSourceContent,
}: {
  currentSourceContent: string;
  lastReleasedSourceContent: string | null;
}) {
  if (lastReleasedSourceContent == null) {
    return null;
  }
  const releaseSourceCopyReviewStatusId = 'release-source-copy-review-status';
  return (
    <aside className="content-cms-admin-page__source-copy-refresh">
      <div id={releaseSourceCopyReviewStatusId} role="status" aria-live="polite">
        <span className="content-cms-admin-page__eyebrow">Release review</span>
        <strong>Compare with the last released copy.</strong>
        <span>Review what apps received before returning to release.</span>
      </div>
      <div className="content-cms-admin-page__source-copy-refresh-compare">
        <SourceCopyRefreshValue
          label="Last released source copy"
          value={lastReleasedSourceContent}
        />
        <SourceCopyRefreshValue label="Current source copy" value={currentSourceContent} />
      </div>
    </aside>
  );
}

function ReleaseTranslationReviewNotice({
  currentTranslationContent,
  lastReleasedTranslationContent,
  localeLabel,
}: {
  currentTranslationContent: string;
  lastReleasedTranslationContent: string;
  localeLabel: string;
}) {
  const releaseTranslationReviewStatusId = 'release-translation-review-status';
  return (
    <aside className="content-cms-admin-page__source-copy-refresh">
      <div id={releaseTranslationReviewStatusId} role="status" aria-live="polite">
        <span className="content-cms-admin-page__eyebrow">Release review</span>
        <strong>Compare with the last released translation.</strong>
        <span>Review what apps received before returning to release.</span>
      </div>
      <div className="content-cms-admin-page__source-copy-refresh-compare">
        <SourceCopyRefreshValue
          label={`Last released ${localeLabel} translation`}
          value={lastReleasedTranslationContent}
        />
        <SourceCopyRefreshValue
          label={`Current saved ${localeLabel} translation`}
          value={currentTranslationContent}
        />
      </div>
    </aside>
  );
}

function FieldContextRefreshNotice({
  draft,
  savedDraft,
  onUseSavedContext,
}: {
  draft: FieldDraft;
  savedDraft: FieldDraft | null;
  onUseSavedContext: () => void;
}) {
  if (savedDraft == null) {
    return null;
  }
  const refreshStatusId = 'field-context-refresh-status';
  return (
    <aside className="content-cms-admin-page__source-copy-refresh">
      <div id={refreshStatusId} role="status" aria-live="polite">
        <span className="content-cms-admin-page__eyebrow">Saved elsewhere</span>
        <strong>Saved placement details changed while you were editing.</strong>
        <span>Review the saved placement details before overwriting them with this draft.</span>
      </div>
      <div className="content-cms-admin-page__source-copy-refresh-compare">
        <SourceCopyRefreshValue
          label="Current saved placement details"
          value={savedDraft.description}
        />
        <SourceCopyRefreshValue label="Your placement details" value={draft.description} />
      </div>
      <button
        type="button"
        className="settings-button"
        aria-describedby={refreshStatusId}
        onClick={onUseSavedContext}
      >
        Use saved placement details
      </button>
    </aside>
  );
}

function SourceCopyRefreshValue({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <span>{label}</span>
      <p>{value || 'Empty'}</p>
    </div>
  );
}

function TargetLocaleSetupCard({
  sourceCopyDirty,
  completeness,
  sourceLocale,
  isLoading,
  isError,
  targetLocaleOptions,
  targetLocaleTagsDraft,
  targetLocalesLoading,
  targetLocalesError,
  targetLocalesSaving,
  disabled,
  authoringRecovery = null,
  onTargetLocaleTagsChange,
  onAddTargetLocales,
  onRetryTargetLocales,
}: {
  sourceCopyDirty: boolean;
  completeness: ApiCmsEntryCompleteness | null;
  sourceLocale: string;
  isLoading: boolean;
  isError: boolean;
  targetLocaleOptions: LocaleOption[];
  targetLocaleTagsDraft: string[];
  targetLocalesLoading: boolean;
  targetLocalesError: boolean;
  targetLocalesSaving: boolean;
  disabled: boolean;
  authoringRecovery?: Extract<AuthoringRecovery, { kind: 'target-locales' }> | null;
  onTargetLocaleTagsChange: (localeTags: string[]) => void;
  onAddTargetLocales: (event: FormEvent) => void;
  onRetryTargetLocales: () => void;
}) {
  const { hasTargetLocales } = getTranslationReadiness(completeness?.locales ?? [], sourceLocale);
  const availableTargetLocaleOptions = targetLocaleOptions.filter(
    (locale) => locale.tag !== sourceLocale,
  );
  const targetLocaleSetupId = useId().replace(/:/g, '');
  const targetLocaleSetupTitleId = `content-cms-target-locale-setup-title-${targetLocaleSetupId}`;
  const targetLocaleSetupDescriptionId = `content-cms-target-locale-setup-description-${targetLocaleSetupId}`;
  const showTargetLocaleSetup = !sourceCopyDirty && !hasTargetLocales && !isLoading && !isError;
  if (!showTargetLocaleSetup) {
    return null;
  }

  return (
    <section
      className="content-cms-admin-page__target-locale-setup-card"
      aria-labelledby={targetLocaleSetupTitleId}
      aria-describedby={targetLocaleSetupDescriptionId}
    >
      <div>
        <span className="content-cms-admin-page__eyebrow">Next step</span>
        <h3 id={targetLocaleSetupTitleId}>Choose translation languages</h3>
        <p id={targetLocaleSetupDescriptionId} className="settings-page__hint">
          Source copy is saved. Choose the languages this content item should be translated into
          next.
        </p>
      </div>
      <TargetLocaleSetup
        localeOptions={availableTargetLocaleOptions}
        selectedLocaleTags={targetLocaleTagsDraft}
        isLoading={targetLocalesLoading}
        isError={targetLocalesError}
        isSaving={targetLocalesSaving}
        disabled={disabled}
        authoringRecovery={authoringRecovery}
        onChange={onTargetLocaleTagsChange}
        onSubmit={onAddTargetLocales}
        onRetry={onRetryTargetLocales}
      />
    </section>
  );
}

function TargetLocaleSetup({
  localeOptions,
  selectedLocaleTags,
  isLoading,
  isError,
  isSaving,
  disabled,
  authoringRecovery = null,
  onChange,
  onSubmit,
  onRetry,
}: {
  localeOptions: LocaleOption[];
  selectedLocaleTags: string[];
  isLoading: boolean;
  isError: boolean;
  isSaving: boolean;
  disabled: boolean;
  authoringRecovery?: Extract<AuthoringRecovery, { kind: 'target-locales' }> | null;
  onChange: (localeTags: string[]) => void;
  onSubmit: (event: FormEvent) => void;
  onRetry: () => void;
}) {
  const [searchAllLocales, setSearchAllLocales] = useState(false);
  const formRef = useRef<HTMLFormElement>(null);
  const pendingRetryFocusRef = useRef(false);
  const targetLocaleStatusId = `content-cms-target-locale-status-${useId().replace(/:/g, '')}`;
  const targetLocaleCatalogId = `content-cms-target-locale-catalog-${useId().replace(/:/g, '')}`;
  const defaultLocaleOptions = useMemo(
    () => getCmsAuthorTargetLocaleOptions(localeOptions, selectedLocaleTags),
    [localeOptions, selectedLocaleTags],
  );
  const visibleLocaleOptions = searchAllLocales ? localeOptions : defaultLocaleOptions.options;
  const hasHiddenLocaleOptions = defaultLocaleOptions.options.length < localeOptions.length;
  useEffect(() => {
    if (!pendingRetryFocusRef.current || isError || isLoading) {
      return;
    }
    pendingRetryFocusRef.current = false;
    window.setTimeout(() => {
      const restoredLanguagePicker = formRef.current?.querySelector<HTMLElement>(
        'button[aria-label="Choose translation languages"]',
      );
      (restoredLanguagePicker ?? formRef.current)?.focus({ preventScroll: true });
    }, 0);
  }, [isError, isLoading]);

  return (
    <form
      ref={formRef}
      className="content-cms-admin-page__target-locale-setup"
      tabIndex={-1}
      onSubmit={onSubmit}
    >
      {isError ? (
        <div className="content-cms-admin-page__target-locale-state is-error">
          <div id={targetLocaleStatusId} role="alert">
            <strong>Translation languages could not load.</strong>
            <span>Try again before translation can start.</span>
          </div>
          <button
            type="button"
            className="settings-button"
            aria-describedby={targetLocaleStatusId}
            onClick={() => {
              pendingRetryFocusRef.current = true;
              onRetry();
            }}
          >
            Try again
          </button>
        </div>
      ) : isLoading ? (
        <div
          id={targetLocaleStatusId}
          className="content-cms-admin-page__target-locale-state"
          role="status"
          aria-live="polite"
        >
          <strong>Loading translation languages</strong>
          <span>Preparing language choices for this content item.</span>
        </div>
      ) : localeOptions.length === 0 ? (
        <div
          id={targetLocaleStatusId}
          className="content-cms-admin-page__target-locale-state"
          role="status"
        >
          <strong>No translation languages yet.</strong>
          <span>
            Ask an admin to add supported languages before this content item can be translated.
          </span>
        </div>
      ) : (
        <>
          <div id={targetLocaleCatalogId}>
            <LocaleMultiSelect
              label="Translation languages"
              options={visibleLocaleOptions}
              selectedTags={selectedLocaleTags}
              onChange={onChange}
              className="content-cms-admin-page__target-locale-select"
              buttonAriaLabel="Choose translation languages"
              disabled={disabled || isSaving}
              searchPlaceholder={searchAllLocales ? 'Search all languages' : undefined}
              emptyFilterLabel={
                searchAllLocales ? 'Type to search every available language.' : undefined
              }
              showBulkActions={false}
              showOptionsWhenFilterEmpty={!searchAllLocales}
              allSelectedSummary={null}
            />
          </div>
          {hasHiddenLocaleOptions ? (
            <button
              type="button"
              className="content-cms-admin-page__target-locale-catalog-toggle"
              aria-expanded={searchAllLocales}
              aria-controls={targetLocaleCatalogId}
              disabled={disabled || isSaving}
              onClick={() => setSearchAllLocales((previous) => !previous)}
            >
              {searchAllLocales ? 'Show common languages' : 'Search all languages'}
            </button>
          ) : null}
          <button
            type="submit"
            className="settings-button settings-button--primary"
            disabled={disabled || isSaving || selectedLocaleTags.length === 0}
          >
            {isSaving ? 'Adding languages' : 'Add languages'}
          </button>
          <AuthoringRecoveryPanel authoringRecovery={authoringRecovery} />
          {hasHiddenLocaleOptions ? (
            <p className="content-cms-admin-page__target-locale-catalog-hint">
              {searchAllLocales
                ? 'Search every available language.'
                : `Showing ${defaultLocaleOptions.options.length} ${
                    defaultLocaleOptions.usesFallback ? 'available' : 'common'
                  } language${defaultLocaleOptions.options.length === 1 ? '' : 's'}.`}
            </p>
          ) : null}
        </>
      )}
    </form>
  );
}

function getCmsAuthorTargetLocaleOptions(
  localeOptions: LocaleOption[],
  selectedLocaleTags: string[],
) {
  const localeOptionsByTag = new Map(
    localeOptions.map((option) => [option.tag.toLowerCase(), option]),
  );
  const selectedLocaleOptions = selectedLocaleTags
    .map((tag) => localeOptionsByTag.get(tag.toLowerCase()) ?? null)
    .filter((option): option is LocaleOption => option != null);
  const commonLocaleOptions = cmsAuthorTargetLocaleTags
    .map((tag) => localeOptionsByTag.get(tag.toLowerCase()) ?? null)
    .filter((option): option is LocaleOption => option != null);
  const preferredLocaleOptions = dedupeLocaleOptions([
    ...commonLocaleOptions,
    ...selectedLocaleOptions,
  ]);
  return preferredLocaleOptions.length > 0
    ? { options: preferredLocaleOptions, usesFallback: false }
    : { options: localeOptions.slice(0, cmsAuthorTargetLocalePreviewCount), usesFallback: true };
}

function dedupeLocaleOptions(localeOptions: LocaleOption[]) {
  const seenTags = new Set<string>();
  return localeOptions.filter((option) => {
    const normalizedTag = option.tag.toLowerCase();
    if (seenTags.has(normalizedTag)) {
      return false;
    }
    seenTags.add(normalizedTag);
    return true;
  });
}

function getTranslationReadiness(locales: ApiCmsLocaleCompleteness[], sourceLocale: string) {
  const targetLocales = locales.filter((locale) => locale.localeTag !== sourceLocale);
  const readyTargetLocaleCount = targetLocales.filter((locale) => locale.complete).length;
  const totalTargetLocaleCount = targetLocales.length;
  return {
    targetLocales,
    readyTargetLocaleCount,
    totalTargetLocaleCount,
    hasTargetLocales: totalTargetLocaleCount > 0,
    allTargetLocalesReady:
      totalTargetLocaleCount > 0 && readyTargetLocaleCount === totalTargetLocaleCount,
  };
}

export type ReleaseRepairTarget = 'source-copy' | 'translation';
export type ReleaseReviewReturnMode = 'repair' | 'repair-saved' | 'review';
export type InlineTranslationReleaseBlockerTarget = {
  entryId: string;
  fieldId: string;
  localeTag: string;
};

function getScopedInlineTranslationReleaseBlockerKey(
  projectId: number,
  blockerTarget: InlineTranslationReleaseBlockerTarget,
) {
  return `${projectId}:${blockerTarget.entryId}:${blockerTarget.fieldId}:${blockerTarget.localeTag}`;
}

type ReleaseRepairAction =
  | 'fix-source-copy'
  | 'open-translation'
  | 'write-translation'
  | 'review-translation';

export type CmsMappingRepairContext = {
  contentItemName: string;
  fieldName: string;
  localeLabel: string;
};

type ReleaseReadinessBlocker = {
  action: ReleaseRepairAction;
  hint: string;
  localeTag: string | null;
  repairTarget: ReleaseRepairTarget;
};

function getReleaseReadinessBlocker(
  locales: ApiCmsLocaleCompleteness[],
  sourceLocale: string,
  resolveLocaleDisplayName: (localeTag: string) => string = (localeTag) => localeTag,
): ReleaseReadinessBlocker | null {
  const sourceLocaleCompleteness =
    locales.find((locale) => locale.localeTag === sourceLocale) ?? null;
  if (sourceLocaleCompleteness != null && !sourceLocaleCompleteness.complete) {
    const pendingSourceCopyPieces = Math.max(
      sourceLocaleCompleteness.totalFields - sourceLocaleCompleteness.approvedFields,
      sourceLocaleCompleteness.missingFields +
        sourceLocaleCompleteness.translationNeededFields +
        sourceLocaleCompleteness.reviewNeededFields,
    );
    if (pendingSourceCopyPieces <= 0) {
      return {
        action: 'fix-source-copy',
        hint: 'Save required source copy before release.',
        localeTag: null,
        repairTarget: 'source-copy',
      };
    }
    return {
      action: 'fix-source-copy',
      hint:
        pendingSourceCopyPieces === 1
          ? 'Save 1 required field of source copy before release.'
          : `Save ${pendingSourceCopyPieces} required fields of source copy before release.`,
      localeTag: null,
      repairTarget: 'source-copy',
    };
  }

  const { targetLocales } = getTranslationReadiness(locales, sourceLocale);
  const translationBlockedLocales = targetLocales
    .filter((locale) => locale.missingFields > 0 || locale.translationNeededFields > 0)
    .map((locale) => locale.localeTag);
  const reviewBlockedLocales = targetLocales
    .filter((locale) => locale.reviewNeededFields > 0)
    .map((locale) => locale.localeTag);
  const blockers = [
    formatLocaleReleaseBlocker(translationBlockedLocales, 'translation', resolveLocaleDisplayName),
    formatLocaleReleaseBlocker(reviewBlockedLocales, 'review', resolveLocaleDisplayName),
  ].filter((blocker): blocker is string => blocker != null);

  return blockers.length > 0
    ? {
        action: translationBlockedLocales.length > 0 ? 'write-translation' : 'review-translation',
        hint: `${blockers.join(' and ')} before release.`,
        localeTag: translationBlockedLocales[0] ?? reviewBlockedLocales[0] ?? null,
        repairTarget: 'translation',
      }
    : null;
}

function getReleaseReadinessBlockerHint(
  locales: ApiCmsLocaleCompleteness[],
  sourceLocale: string,
  resolveLocaleDisplayName: (localeTag: string) => string = (localeTag) => localeTag,
) {
  return getReleaseReadinessBlocker(locales, sourceLocale, resolveLocaleDisplayName)?.hint ?? null;
}

function formatLocaleReleaseBlocker(
  localeTags: string[],
  blocker: 'translation' | 'review',
  resolveLocaleDisplayName: (localeTag: string) => string,
) {
  if (localeTags.length === 0) {
    return null;
  }
  return `${formatLocaleLabelList(localeTags, resolveLocaleDisplayName)} ${
    localeTags.length === 1 ? 'needs' : 'need'
  } ${blocker}`;
}

function formatLocaleLabelList(
  localeTags: string[],
  resolveLocaleDisplayName: (localeTag: string) => string,
) {
  const localeLabels = localeTags.map(resolveLocaleDisplayName);
  if (localeLabels.length === 0) {
    return 'No translation languages';
  }
  if (localeLabels.length === 1) {
    return localeLabels[0];
  }
  if (localeLabels.length === 2) {
    return `${localeLabels[0]} and ${localeLabels[1]}`;
  }
  return `${localeLabels.slice(0, -1).join(', ')}, and ${localeLabels[localeLabels.length - 1]}`;
}

function getFieldCompleteness(
  completeness: ApiCmsEntryCompleteness | null,
  fieldId: number,
): ApiCmsFieldCompleteness | null {
  return completeness?.fields.find((field) => field.fieldId === fieldId) ?? null;
}

function getRequestedCopyFieldFocusTarget(target: EventTarget | null): CopyFieldFocusTarget {
  if (!(target instanceof HTMLElement)) {
    return 'field';
  }
  if (target.closest('.content-cms-admin-page__copy-input') != null) {
    return 'source-copy';
  }
  if (target.closest('.content-cms-admin-page__field-context-form .settings-input') != null) {
    return 'field-context';
  }
  return 'field';
}

function getInlineTranslationStatus(textUnit: {
  target?: string | null;
  status?: string | null;
  includedInLocalizedFile?: boolean;
}) {
  return textUnit.target?.trim()
    ? formatStatus(textUnit.status, textUnit.includedInLocalizedFile)
    : 'To translate';
}

function getInlineTranslationSaveMessage(localeLabel: string, status: string) {
  const savedLocale = localeLabel || 'Selected language';
  switch (status) {
    case 'Accepted':
      return `Saved ${savedLocale} translation. Approved for release.`;
    case 'Rejected':
      return `Saved ${savedLocale} translation. Excluded from release.`;
    case 'To review':
      return `Saved ${savedLocale} translation. Marked for review.`;
    default:
      return `Saved ${savedLocale} translation. Translation still needs work.`;
  }
}

function getInlineTranslationSaveErrorMessage(
  error: unknown,
  {
    baselineStatus,
    baselineTarget,
    localeLabel,
    request,
  }: {
    baselineStatus: string;
    baselineTarget: string;
    localeLabel: string;
    request: SaveTextUnitRequest;
  },
) {
  const message = error instanceof Error ? error.message : '';
  if (/placeholder|ICU/i.test(message)) {
    return message;
  }
  const requestedStatus = getInlineTranslationStatus({
    target: request.target,
    status: request.status,
    includedInLocalizedFile: request.includedInLocalizedFile,
  });
  if (request.target === baselineTarget && requestedStatus !== baselineStatus) {
    return getInlineTranslationStatusOnlySaveErrorMessage(
      localeLabel,
      requestedStatus,
      baselineStatus,
    );
  }
  return 'Could not save this translation. Try again. If it keeps failing, ask an admin to check this translation language.';
}

function getInlineTranslationStatusOnlySaveErrorMessage(
  localeLabel: string,
  requestedStatus: string,
  baselineStatus: string,
) {
  const selectedLocaleLabel = localeLabel || 'Selected language';
  const savedState = getInlineTranslationSavedStateMessage(baselineStatus);
  const retryHint = 'If it keeps failing, ask an admin to check this translation language.';
  switch (requestedStatus) {
    case 'Accepted':
      return `Could not approve ${selectedLocaleLabel} for release. ${savedState} Try Approve for release again. ${retryHint}`;
    case 'Rejected':
      return `Could not exclude ${selectedLocaleLabel} from release. ${savedState} Try Exclude from release again. ${retryHint}`;
    case 'To review':
      return `Could not send ${selectedLocaleLabel} for review. ${savedState} Try Send for review again. ${retryHint}`;
    default:
      return `Could not keep ${selectedLocaleLabel} as draft. ${savedState} Try Keep as draft again. ${retryHint}`;
  }
}

function getInlineTranslationSavedStateMessage(status: string) {
  switch (status) {
    case 'Accepted':
      return 'It is still approved for release.';
    case 'Rejected':
      return 'It is still excluded from release.';
    case 'To review':
      return 'It is still ready for review.';
    default:
      return 'It still needs translation.';
  }
}

function getInlineTranslationStatusOptionLabel(status: string) {
  switch (status) {
    case 'Accepted':
      return 'Approved for release';
    case 'Rejected':
      return 'Excluded from release';
    case 'To review':
      return 'Ready for review';
    default:
      return 'Needs translation';
  }
}

function getPrimaryInlineTranslationSaveStatus(
  locale: ApiCmsLocaleCompleteness | undefined,
  baselineStatus: string,
) {
  if (locale?.complete || baselineStatus === 'Accepted') {
    return 'Accepted';
  }
  if (locale?.reviewNeededFields || baselineStatus === 'To review') {
    return 'Accepted';
  }
  return 'To review';
}

function getLocaleAction(locale: ApiCmsLocaleCompleteness | undefined, localeLabel: string) {
  const selectedLocaleLabel = localeLabel || 'Selected language';
  if (!locale) {
    return {
      title: `${selectedLocaleLabel} needs work`,
      hint: null,
    };
  }
  if (locale.complete) {
    return {
      title: `${selectedLocaleLabel} approved`,
      hint: null,
    };
  }
  if (locale.reviewNeededFields > 0) {
    return {
      title: `Review ${selectedLocaleLabel} translation`,
      hint: null,
    };
  }
  const translationNeededFields = locale.missingFields + locale.translationNeededFields;
  if (translationNeededFields > 0) {
    return {
      title: `Write ${selectedLocaleLabel} translation`,
      hint: null,
    };
  }
  return {
    title: `${selectedLocaleLabel} needs work`,
    hint: null,
  };
}

function getTranslationEditorTriggerLabel(
  locale: ApiCmsLocaleCompleteness | null,
  localeLabel: string | null,
) {
  const selectedLocaleLabel = localeLabel || 'Selected language';
  if (locale?.complete) {
    return `Open ${selectedLocaleLabel} translation`;
  }
  if (locale?.reviewNeededFields) {
    return `Review ${selectedLocaleLabel} translation`;
  }
  return `Write ${selectedLocaleLabel} translation`;
}

function getInlineTranslationHelpLabel(status: string) {
  return status === 'To review' || status === 'Accepted' ? 'Review help' : 'Translation help';
}

function getTranslationEditorTriggerAction(locale: ApiCmsLocaleCompleteness | null) {
  if (locale?.complete) {
    return 'Open';
  }
  if (locale?.reviewNeededFields) {
    return 'Review';
  }
  return 'Write';
}

function getTranslationEditorTriggerVisibleLabel(
  locale: ApiCmsLocaleCompleteness | null,
  localeLabel: string | null,
) {
  return `${getTranslationEditorTriggerAction(locale)} ${localeLabel || 'Selected language'}`;
}

function getTranslationStatusSummary(
  locale: ApiCmsLocaleCompleteness | null,
  localeLabel: string | null,
) {
  const selectedLocaleLabel = localeLabel || 'Selected language';
  if (locale?.complete) {
    return {
      ariaLabel: `${selectedLocaleLabel} Approved for release`,
      statusLabel: 'Approved for release',
    };
  }
  if (locale?.reviewNeededFields) {
    return {
      ariaLabel: `${selectedLocaleLabel} Ready for review`,
      statusLabel: 'Ready for review',
    };
  }
  const translationNeededFields =
    (locale?.missingFields ?? 0) + (locale?.translationNeededFields ?? 0);
  if (translationNeededFields > 0) {
    return {
      ariaLabel: `${selectedLocaleLabel} Needs translation`,
      statusLabel: 'Needs translation',
    };
  }
  return {
    ariaLabel: `${selectedLocaleLabel} Needs work`,
    statusLabel: 'Needs work',
  };
}

function getInlineTranslationEditorId(fieldId: number) {
  return `content-cms-inline-translation-${fieldId}`;
}

function focusClosedInlineTranslationAction(fieldId: number) {
  const field = document.getElementById(getCopyFieldAnchorId(String(fieldId)));
  const editor = field?.closest('.content-cms-admin-page__editor');
  const targetLocaleSetupAction = editor?.querySelector<HTMLElement>(
    '.content-cms-admin-page__target-locale-setup button[aria-label="Choose translation languages"]:not([disabled])',
  );
  const translationAction = field?.querySelector<HTMLElement>(
    `[aria-controls="${getInlineTranslationEditorId(fieldId)}"]`,
  );
  const localizationAction = field?.querySelector<HTMLElement>(
    '.content-cms-admin-page__field-localization button:not([disabled]), .content-cms-admin-page__field-localization a[href]',
  );
  const setupAction = field?.querySelector<HTMLElement>(
    [
      '.content-cms-admin-page__field-localization-panel button:not([disabled])',
      '.content-cms-admin-page__field-localization-panel a[href]',
      '.content-cms-admin-page__field-localization-panel input:not([disabled])',
      '.content-cms-admin-page__field-localization-panel textarea:not([disabled])',
      '.content-cms-admin-page__field-localization-panel select:not([disabled])',
      '.content-cms-admin-page__field-localization-panel [tabindex]:not([tabindex="-1"])',
    ].join(', '),
  );
  (
    targetLocaleSetupAction ??
    translationAction ??
    localizationAction ??
    setupAction ??
    field
  )?.focus({
    preventScroll: true,
  });
}

function getAuthorReleaseBlockerListId(entryId: string) {
  return `content-cms-release-blockers-${entryId}`;
}

function getInlineTranslationRefreshErrorAction(localeLabel: string, status: string) {
  const selectedLocaleLabel = localeLabel || 'Selected language';
  const refreshHint = 'Refresh translation status before release readiness can update.';
  switch (status) {
    case 'Accepted':
      return {
        title: `${selectedLocaleLabel} approved`,
        hint: `Saved translation is approved for release. ${refreshHint}`,
      };
    case 'Rejected':
      return {
        title: `${selectedLocaleLabel} excluded`,
        hint: `Saved translation is excluded from release. ${refreshHint}`,
      };
    case 'To review':
      return {
        title: `Review ${selectedLocaleLabel} translation`,
        hint: `Saved translation is ready for review. ${refreshHint}`,
      };
    default:
      return {
        title: `Write ${selectedLocaleLabel} translation`,
        hint: `Saved translation still needs work. ${refreshHint}`,
      };
  }
}

function getInlineTranslationRefreshLocaleStatusLabel(
  localeTag: string,
  selectedLocaleTag: string,
  baselineStatus: string,
) {
  return localeTag === selectedLocaleTag
    ? getInlineTranslationStatusOptionLabel(baselineStatus)
    : 'Status needs refresh';
}

function getInlineTranslationRefreshLocaleActionLabel(
  localeTag: string,
  selectedLocaleTag: string,
  baselineStatus: string,
) {
  if (localeTag !== selectedLocaleTag) {
    return 'Refresh';
  }
  switch (baselineStatus) {
    case 'Accepted':
      return 'Open';
    case 'Rejected':
      return 'Open';
    case 'To review':
      return 'Review';
    default:
      return 'Write';
  }
}

function sortTargetLocalesByWork(locales: ApiCmsLocaleCompleteness[]) {
  return [...locales].sort(
    (left, right) =>
      getLocaleWorkPriority(left) - getLocaleWorkPriority(right) ||
      left.localeTag.localeCompare(right.localeTag),
  );
}

function getLocaleWorkPriority(locale: ApiCmsLocaleCompleteness) {
  if (locale.missingFields > 0 || locale.translationNeededFields > 0) {
    return 0;
  }
  if (locale.reviewNeededFields > 0) {
    return 1;
  }
  return locale.complete ? 3 : 2;
}

function formatTranslationLanguageApprovalCount(readyCount: number, totalCount: number) {
  return `${readyCount}/${totalCount} translation language${
    totalCount === 1 ? ' is' : 's are'
  } approved`;
}

function getLocaleStatusLabel(locale: ApiCmsLocaleCompleteness | undefined) {
  if (!locale) {
    return 'Not ready';
  }
  if (locale.complete) {
    return 'Approved';
  }
  if (locale.reviewNeededFields > 0) {
    return 'Needs review';
  }
  if (locale.translationNeededFields > 0 || locale.missingFields > 0) {
    return 'Needs translation';
  }
  return 'Not ready';
}

function getInlineTranslationLocaleActionLabel(locale: ApiCmsLocaleCompleteness) {
  if (locale.complete) {
    return 'Open';
  }
  if (locale.reviewNeededFields > 0) {
    return 'Review';
  }
  if (locale.translationNeededFields > 0 || locale.missingFields > 0) {
    return 'Write';
  }
  return 'Open';
}

function getLocaleProgressSummary(locale: ApiCmsLocaleCompleteness | undefined) {
  if (!locale) {
    return 'Translation readiness will refresh after save.';
  }
  const pendingWork = [
    locale.missingFields > 0 ? `${locale.missingFields} missing` : null,
    locale.translationNeededFields > 0 ? `${locale.translationNeededFields} to translate` : null,
    locale.reviewNeededFields > 0 ? `${locale.reviewNeededFields} to review` : null,
  ].filter((summary): summary is string => summary != null);
  const approvedSummary = `${locale.approvedFields}/${locale.totalFields} approved`;
  return pendingWork.length > 0
    ? `${approvedSummary} · ${pendingWork.join(' · ')}`
    : approvedSummary;
}

type TranslationWorkflowStep = 'translate' | 'review' | 'open';

function getTranslationWorkflowStep(
  locales: ApiCmsLocaleCompleteness[],
  sourceLocale: string,
): TranslationWorkflowStep {
  const { targetLocales, allTargetLocalesReady } = getTranslationReadiness(locales, sourceLocale);
  if (allTargetLocalesReady) {
    return 'open';
  }
  if (
    targetLocales.some((locale) => locale.missingFields > 0 || locale.translationNeededFields > 0)
  ) {
    return 'translate';
  }
  return targetLocales.some((locale) => locale.reviewNeededFields > 0) ? 'review' : 'translate';
}

function getMojitoLocaleTag(
  locales: ApiCmsLocaleCompleteness[],
  sourceLocale: string,
  translationWorkflowStep: TranslationWorkflowStep,
) {
  const targetLocales = locales.filter((locale) => locale.localeTag !== sourceLocale);
  const targetLocale =
    translationWorkflowStep === 'translate'
      ? targetLocales.find(
          (locale) => locale.missingFields > 0 || locale.translationNeededFields > 0,
        )
      : translationWorkflowStep === 'review'
        ? targetLocales.find((locale) => locale.reviewNeededFields > 0)
        : targetLocales[0];
  return targetLocale?.localeTag ?? null;
}

function buildCmsTextUnitContext({
  projectId,
  projectName,
  entry,
  field,
}: {
  projectId: number;
  projectName: string;
  entry: ApiCmsEntry;
  field: ApiCmsContentTypeField;
}): CmsTextUnitContext {
  return {
    projectId,
    projectName,
    entryId: entry.id,
    entryName: entry.name,
    fieldId: field.id,
    fieldName: field.name,
  };
}

function BlockReleaseState({
  entry,
  mappedFieldCount,
  copyFieldCount,
  completeness,
  sourceLocale,
  isLoading,
  isError,
  hasUnsavedAuthoringChanges,
  disabled,
  hasReleaseHandoffBlocker = false,
  releaseHandoffBlockerCount = 0,
  suppressReadyDraftState = false,
  authoringRecovery = null,
  onStatusChange,
}: {
  entry: ApiCmsEntry;
  mappedFieldCount: number;
  copyFieldCount: number;
  completeness: ApiCmsEntryCompleteness | null;
  sourceLocale: string;
  isLoading: boolean;
  isError: boolean;
  hasUnsavedAuthoringChanges: boolean;
  disabled: boolean;
  hasReleaseHandoffBlocker?: boolean;
  releaseHandoffBlockerCount?: number;
  suppressReadyDraftState?: boolean;
  authoringRecovery?: Extract<AuthoringRecovery, { kind: 'block-release' }> | null;
  onStatusChange: (status: ApiCmsEntryStatus) => void;
}) {
  const resolveLocaleDisplayName = useLocaleDisplayNameResolver();
  const isIncluded = entry.status === 'READY';
  const isArchived = entry.status === 'ARCHIVED';
  const canInclude = mappedFieldCount > 0;
  const hasReadyTargetLocales =
    completeness == null ? true : hasReadyCmsTargetLocales(completeness.locales, sourceLocale);
  const draftReleaseReady = !isLoading && !isError && completeness != null && hasReadyTargetLocales;
  const statusLabel = isIncluded ? 'Included in release' : isArchived ? 'Archived' : 'Not included';
  const actionLabel = isIncluded
    ? 'Remove from release'
    : isArchived
      ? 'Restore for next release'
      : 'Include in next release';
  const nextStatus: ApiCmsEntryStatus = isIncluded ? 'DRAFT' : 'READY';
  const blockReleaseStatusId = `${cmsBlockReleasePanelId}-status`;
  if (!isIncluded && !isArchived && !draftReleaseReady) {
    return null;
  }
  if (!isIncluded && !isArchived && suppressReadyDraftState && authoringRecovery == null) {
    return null;
  }
  const hint = isIncluded
    ? getIncludedBlockReleaseHint({
        completeness,
        sourceLocale,
        isLoading,
        isError,
        hasUnsavedAuthoringChanges,
        hasReleaseHandoffBlocker,
        releaseHandoffBlockerCount,
        resolveLocaleDisplayName,
      })
    : isArchived
      ? 'Restore this content item when it should return to a future release.'
      : hasUnsavedAuthoringChanges
        ? 'Save visible changes before including this content item in a release.'
        : canInclude
          ? 'Include this content item when it should ship in the next release.'
          : copyFieldCount > 0
            ? 'Save source copy before including this content item in a release.'
            : 'Add source copy before including this content item in a release.';

  return (
    <section
      id={cmsBlockReleasePanelId}
      className="content-cms-admin-page__block-release"
      aria-label="Release this copy"
      tabIndex={-1}
    >
      <div id={blockReleaseStatusId} role="status" aria-live="polite">
        <span className="content-cms-admin-page__eyebrow">
          {isIncluded || isArchived ? 'Release' : 'Next step'}
        </span>
        <p className="settings-page__hint">{hint}</p>
      </div>
      <div className="content-cms-admin-page__block-release-actions">
        <span
          className={`content-cms-admin-page__status${
            isIncluded ? ' is-ready' : isArchived ? ' is-muted' : ' is-warning'
          }`}
        >
          {statusLabel}
        </span>
        <button
          type="button"
          className={isIncluded ? 'settings-button settings-button--ghost' : 'settings-button'}
          disabled={disabled || (!isIncluded && !canInclude)}
          aria-describedby={blockReleaseStatusId}
          onClick={() => onStatusChange(nextStatus)}
        >
          {actionLabel}
        </button>
        {isIncluded ? (
          <button
            type="button"
            className="settings-button"
            aria-controls={cmsReleasePanelId}
            aria-describedby={blockReleaseStatusId}
            onClick={focusCmsReleasePanel}
          >
            Go to release
          </button>
        ) : null}
      </div>
      <AuthoringRecoveryPanel authoringRecovery={authoringRecovery} />
    </section>
  );
}

function ReleaseReviewReturnHandoff({
  disabled,
  onReturnToRelease,
  readySubject,
  reviewReason,
  reviewReturnMode,
}: {
  disabled: boolean;
  onReturnToRelease: () => void;
  readySubject: 'content item' | 'field' | 'release change';
  reviewReason: string | null;
  reviewReturnMode: ReleaseReviewReturnMode | null;
}) {
  const isRepairReturn = reviewReturnMode === 'repair';
  const isSavedRepairReturn = reviewReturnMode === 'repair-saved';
  const releaseReviewReturnStatusId = `${cmsReleasePanelId}-return`;
  return (
    <section
      className={`content-cms-admin-page__release-review-return${
        isRepairReturn ? ' is-repair' : ''
      }`}
      aria-label="Return to release"
    >
      <div id={releaseReviewReturnStatusId} role="status" aria-live="polite">
        <span className="content-cms-admin-page__eyebrow">
          {isRepairReturn ? 'Fix before release' : isSavedRepairReturn ? 'Saved' : 'Release review'}
        </span>
        <strong>
          {isRepairReturn
            ? 'Fix this before release'
            : isSavedRepairReturn
              ? 'Repair saved'
              : 'Opened from release review'}
        </strong>
        {reviewReason ? <span>{reviewReason}</span> : null}
        <span>
          {isRepairReturn
            ? `Return when this ${readySubject} is ready.`
            : isSavedRepairReturn
              ? 'Return to release to see the updated check.'
              : `Return after reviewing this ${readySubject}.`}
        </span>
      </div>
      <button
        type="button"
        className="settings-button"
        aria-controls={cmsReleasePanelId}
        aria-describedby={releaseReviewReturnStatusId}
        disabled={disabled}
        onClick={() => {
          focusCmsReleasePanel();
          onReturnToRelease();
        }}
      >
        Return to release
      </button>
    </section>
  );
}

function getIncludedBlockReleaseHint({
  completeness,
  sourceLocale,
  isLoading,
  isError,
  hasUnsavedAuthoringChanges,
  hasReleaseHandoffBlocker,
  releaseHandoffBlockerCount,
  resolveLocaleDisplayName,
}: {
  completeness: ApiCmsEntryCompleteness | null;
  sourceLocale: string;
  isLoading: boolean;
  isError: boolean;
  hasUnsavedAuthoringChanges: boolean;
  hasReleaseHandoffBlocker: boolean;
  releaseHandoffBlockerCount: number;
  resolveLocaleDisplayName: (localeTag: string) => string;
}) {
  if (hasUnsavedAuthoringChanges) {
    return 'Save visible changes before releasing approved copy.';
  }
  if (hasReleaseHandoffBlocker) {
    return releaseHandoffBlockerCount === 1
      ? 'Refresh unavailable approved translation before releasing approved copy.'
      : `Refresh ${releaseHandoffBlockerCount} unavailable approved translations before releasing approved copy.`;
  }
  if (isError) {
    return 'Translation status unavailable. Retry translation status before release.';
  }
  if (isLoading || completeness == null) {
    return 'Checking this content item for release blockers.';
  }
  return (
    getReleaseReadinessBlockerHint(completeness.locales, sourceLocale, resolveLocaleDisplayName) ??
    'This content item is included. Release approved copy below when every included item is ready.'
  );
}

function CopyBlockDetails({
  id,
  entryDraft,
  staleSavedDraft,
  entryContextDirty,
  disabled,
  authoringRecovery = null,
  onEntryDraftChange,
  onEntrySubmit,
  onUseSavedDetails,
}: {
  id?: string;
  entryDraft: EntryDraft;
  staleSavedDraft: EntryDraft | null;
  entryContextDirty: boolean;
  disabled: boolean;
  authoringRecovery?: Extract<AuthoringRecovery, { kind: 'block-details' }> | null;
  onEntryDraftChange: (draft: EntryDraft) => void;
  onEntrySubmit: (event: FormEvent) => void;
  onUseSavedDetails: () => void;
}) {
  const entryNameMissing = entryContextDirty && !hasAuthoringDraftText(entryDraft.name);

  return (
    <section
      id={id}
      className="content-cms-admin-page__copy-block-details"
      aria-label="Content item details"
    >
      <div className="content-cms-admin-page__copy-block-details-header">
        <div>
          <h3>Name and placement</h3>
        </div>
        {entryContextDirty ? (
          <span className="content-cms-admin-page__status is-warning">Unsaved</span>
        ) : null}
      </div>
      <form className="content-cms-admin-page__block-form" onSubmit={onEntrySubmit}>
        <label className="settings-field">
          <span className="settings-field__label">Content item name</span>
          <input
            className="settings-input"
            value={entryDraft.name}
            onChange={(event) => onEntryDraftChange({ ...entryDraft, name: event.target.value })}
            disabled={disabled}
            aria-invalid={entryNameMissing || undefined}
            required
          />
        </label>
        <label className="settings-field">
          <span className="settings-field__label">Where this item appears</span>
          <input
            className="settings-input"
            value={entryDraft.description}
            onChange={(event) =>
              onEntryDraftChange({ ...entryDraft, description: event.target.value })
            }
            disabled={disabled}
            placeholder="Signup confirmation email"
          />
        </label>
        {entryNameMissing ? (
          <p className="settings-hint is-error content-cms-admin-page__field-span">
            Name this content item before saving.
          </p>
        ) : null}
        {entryContextDirty ? (
          <button
            type="submit"
            className="settings-button content-cms-admin-page__field-span"
            disabled={disabled || entryNameMissing}
          >
            Save copy details
          </button>
        ) : null}
        <AuthoringRecoveryPanel
          authoringRecovery={authoringRecovery}
          className="content-cms-admin-page__field-span"
        />
        <CopyBlockDetailsRefreshNotice
          draft={entryDraft}
          savedDraft={staleSavedDraft}
          onUseSavedDetails={onUseSavedDetails}
        />
      </form>
    </section>
  );
}

function CopyBlockDetailsRefreshNotice({
  draft,
  savedDraft,
  onUseSavedDetails,
}: {
  draft: EntryDraft;
  savedDraft: EntryDraft | null;
  onUseSavedDetails: () => void;
}) {
  if (savedDraft == null) {
    return null;
  }
  const refreshStatusId = 'copy-block-details-refresh-status';
  return (
    <aside className="content-cms-admin-page__source-copy-refresh content-cms-admin-page__field-span">
      <div id={refreshStatusId} role="status" aria-live="polite">
        <span className="content-cms-admin-page__eyebrow">Saved elsewhere</span>
        <strong>Saved copy details changed while you were editing.</strong>
        <span>Review the saved details before overwriting them with this draft.</span>
      </div>
      {savedDraft.name !== draft.name ? (
        <div className="content-cms-admin-page__source-copy-refresh-compare">
          <SourceCopyRefreshValue label="Current saved name" value={savedDraft.name} />
          <SourceCopyRefreshValue label="Your name" value={draft.name} />
        </div>
      ) : null}
      {savedDraft.description !== draft.description ? (
        <div className="content-cms-admin-page__source-copy-refresh-compare">
          <SourceCopyRefreshValue label="Current saved placement" value={savedDraft.description} />
          <SourceCopyRefreshValue label="Your placement" value={draft.description} />
        </div>
      ) : null}
      <button
        type="button"
        className="settings-button"
        aria-describedby={refreshStatusId}
        onClick={onUseSavedDetails}
      >
        Use saved details
      </button>
    </aside>
  );
}

function NewCopyBlockForm({
  contentType,
  draft,
  sourceDrafts,
  disabled,
  isFirstBlock = false,
  authoringRecovery = null,
  sourceCopyPlaceholder,
  onOpenAdvancedSetup,
  onChange,
  onCancel,
  onSourceDraftsChange,
  onSubmit,
}: {
  contentType: ApiCmsContentType | null;
  draft: EntryDraft;
  sourceDrafts: Record<string, SourceCopyDraft>;
  disabled: boolean;
  isFirstBlock?: boolean;
  authoringRecovery?: Extract<AuthoringRecovery, { kind: 'new-block' }> | null;
  sourceCopyPlaceholder: string;
  onOpenAdvancedSetup: () => void;
  onChange: (draft: EntryDraft) => void;
  onCancel: () => void;
  onSourceDraftsChange: (drafts: Record<string, SourceCopyDraft>) => void;
  onSubmit: (event: FormEvent) => void;
}) {
  const contentItemNameRef = useRef<HTMLInputElement>(null);
  const copyFields = sortLocalizableFields(contentType?.fields ?? []);
  const hasOptionalCopyFields = copyFields.some((field) => !field.required);
  const [contentItemDetailsOpen, setContentItemDetailsOpen] = useState(() =>
    hasAuthoringDraftText(draft.description),
  );
  const hasRequiredCopyDrafts = copyFields
    .filter((field) => field.required)
    .every((field) => {
      const sourceDraft = sourceDrafts[String(field.id)] ?? initialSourceCopyDraft;
      return hasRequiredSourceCopyDraft(sourceDraft);
    });
  const canSaveCopyBlock =
    contentType != null &&
    copyFields.length > 0 &&
    hasAuthoringDraftText(draft.name) &&
    hasRequiredCopyDrafts;
  const updateSourceDraft = (fieldId: number, draft: SourceCopyDraft) => {
    onSourceDraftsChange({
      ...sourceDrafts,
      [String(fieldId)]: draft,
    });
  };

  useEffect(() => {
    if (hasAuthoringDraftText(draft.description)) {
      setContentItemDetailsOpen(true);
    }
  }, [draft.description]);
  useEffect(() => {
    if (isFirstBlock) {
      return;
    }
    window.setTimeout(() => contentItemNameRef.current?.focus({ preventScroll: true }), 0);
  }, [isFirstBlock]);

  return (
    <form className="content-cms-admin-page__new-block" onSubmit={onSubmit}>
      <div className="content-cms-admin-page__new-block-header content-cms-admin-page__field-span">
        <div>
          <h2>{isFirstBlock ? 'Write the first content item' : 'Write a new content item'}</h2>
          <p className="settings-page__hint">
            Name this content item, then write the required source copy and translator notes.
          </p>
        </div>
      </div>
      <fieldset className="content-cms-admin-page__authoring-form-section content-cms-admin-page__field-span">
        <legend>Name this content item</legend>
        <div className="content-cms-admin-page__authoring-form-section-grid">
          <label className="settings-field">
            <span className="settings-field__label">Content item name</span>
            <input
              ref={contentItemNameRef}
              className="settings-input"
              value={draft.name}
              onChange={(event) =>
                onChange({
                  ...draft,
                  contentTypeId: draft.contentTypeId || String(contentType?.id ?? ''),
                  name: event.target.value,
                  entryKey: updateSuggestedCmsKey(
                    draft.entryKey,
                    draft.name,
                    event.target.value,
                    'copy-block',
                  ),
                })
              }
              placeholder="Follow-up email"
              disabled={disabled}
              required
            />
          </label>
        </div>
      </fieldset>
      <button
        type="button"
        className="content-cms-admin-page__block-details-trigger content-cms-admin-page__field-span"
        aria-expanded={contentItemDetailsOpen}
        aria-controls={cmsNewCopyBlockDetailsId}
        onClick={() => setContentItemDetailsOpen((current) => !current)}
      >
        Content item details
      </button>
      {contentItemDetailsOpen ? (
        <fieldset
          id={cmsNewCopyBlockDetailsId}
          className="content-cms-admin-page__authoring-form-section content-cms-admin-page__field-span"
          aria-label="Content item details"
        >
          <legend>Content item details</legend>
          <label className="settings-field">
            <span className="settings-field__label">Where this item appears</span>
            <input
              className="settings-input"
              value={draft.description}
              onChange={(event) => onChange({ ...draft, description: event.target.value })}
              placeholder="Signup confirmation email"
              disabled={disabled}
            />
          </label>
        </fieldset>
      ) : null}
      {copyFields.length === 0 ? (
        <AuthoringRepairAction
          actionLabel="Repair content item"
          className="content-cms-admin-page__field-span"
          message="This content item has no fields to write yet."
          onOpenAdvancedSetup={onOpenAdvancedSetup}
        />
      ) : (
        <section
          className="content-cms-admin-page__new-block-copy-list content-cms-admin-page__field-span"
          aria-label="Fields for this content item"
        >
          <div className="content-cms-admin-page__new-block-copy-list-header">
            <span className="content-cms-admin-page__eyebrow">Write the copy</span>
            {hasOptionalCopyFields ? (
              <p className="settings-page__hint">Optional fields can wait.</p>
            ) : null}
          </div>
          {copyFields.map((field) => {
            const sourceDraft = sourceDrafts[String(field.id)] ?? initialSourceCopyDraft;
            const hasMultipleFields = copyFields.length > 1;
            return (
              <section key={field.id} className="content-cms-admin-page__new-block-copy-piece">
                <div className="content-cms-admin-page__new-block-copy-label content-cms-admin-page__field-span">
                  <span className="content-cms-admin-page__eyebrow">
                    {field.required ? 'Required copy' : 'Optional copy'}
                  </span>
                  <strong>{field.name}</strong>
                </div>
                {field.description ? (
                  <p className="settings-page__hint content-cms-admin-page__field-span">
                    {field.description}
                  </p>
                ) : null}
                <label className="content-cms-admin-page__copy-field">
                  <span className="content-cms-admin-page__copy-field-label">Source copy</span>
                  <AutoTextarea
                    aria-label={hasMultipleFields ? `${field.name} source copy` : undefined}
                    className="settings-input content-cms-admin-page__textarea content-cms-admin-page__textarea--copy content-cms-admin-page__copy-input"
                    value={sourceDraft.sourceContent}
                    minRows={authorSourceCopyMinRows}
                    maxRows={10}
                    onChange={(event) =>
                      updateSourceDraft(field.id, {
                        ...sourceDraft,
                        sourceContent: event.target.value,
                      })
                    }
                    disabled={disabled}
                    required={field.required}
                    placeholder={sourceCopyPlaceholder}
                  />
                </label>
                <label className="settings-field content-cms-admin-page__translator-note">
                  <span className="content-cms-admin-page__field-label-row">
                    <span className="settings-field__label">Translator note</span>
                    <span className="content-cms-admin-page__required-hint" aria-hidden="true">
                      {field.required ? 'Required for translation' : 'Add when writing this copy'}
                    </span>
                  </span>
                  <AutoTextarea
                    aria-label={
                      hasMultipleFields ? `${field.name} translator note` : 'Translator note'
                    }
                    className="settings-input content-cms-admin-page__textarea content-cms-admin-page__textarea--translator-note"
                    value={sourceDraft.sourceComment}
                    minRows={authorTranslatorNoteMinRows}
                    maxRows={5}
                    onChange={(event) =>
                      updateSourceDraft(field.id, {
                        ...sourceDraft,
                        sourceComment: event.target.value,
                      })
                    }
                    disabled={disabled}
                    required={field.required}
                    placeholder="Tone, limits, placeholders, or translator instructions"
                  />
                </label>
              </section>
            );
          })}
        </section>
      )}
      <div className="content-cms-admin-page__actions content-cms-admin-page__field-span">
        <button
          type="submit"
          className="settings-button settings-button--primary"
          disabled={disabled || !canSaveCopyBlock}
        >
          {isFirstBlock ? 'Save first content item' : 'Save content item'}
        </button>
        <button type="button" className="settings-button" disabled={disabled} onClick={onCancel}>
          Cancel
        </button>
      </div>
      <AuthoringRecoveryPanel
        authoringRecovery={authoringRecovery}
        className="content-cms-admin-page__field-span"
      />
    </form>
  );
}

export function ProjectOverview({ detail }: { detail: ApiCmsProjectDetail }) {
  const { project } = detail;
  return (
    <div className="content-cms-admin-page__project-overview">
      <TechnicalFieldsDisclosure
        summary="Technical details"
        hint="Exact Mojito records and audit details stay here for debugging."
      >
        <Definition label="Stable key" value={project.projectKey} />
        <Definition
          label="Mojito repository"
          value={`${project.repository.name} (#${project.repository.id})`}
        />
        <Definition label="Virtual asset" value={`${project.asset.path} (#${project.asset.id})`} />
        <Definition label="Source locale" value={project.sourceLocale} />
        <Definition label="Copy collection enabled" value={formatBool(project.enabled)} />
        <Definition label="Created by" value={project.audit.createdByUsername} />
        <Definition label="Updated by" value={project.audit.lastModifiedByUsername} />
        <Definition label="Updated" value={formatDateTime(project.audit.lastModifiedDate)} />
      </TechnicalFieldsDisclosure>
    </div>
  );
}

function Definition({ label, value }: { label: string; value: string }) {
  return (
    <div className="content-cms-admin-page__definition">
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  );
}

function TechnicalFieldsDisclosure({
  children,
  hint = 'Stable IDs and raw metadata stay here for integrations and debugging.',
  summary = 'Technical fields',
}: {
  children: ReactNode;
  hint?: string;
  summary?: string;
}) {
  return (
    <details className="content-cms-admin-page__technical-fields content-cms-admin-page__field-span">
      <summary>{summary}</summary>
      <p className="settings-page__hint">{hint}</p>
      <div className="content-cms-admin-page__technical-fields-grid">{children}</div>
    </details>
  );
}

function sortLocalizableFields(fields: ApiCmsContentTypeField[]) {
  return fields
    .filter((field) => field.localizable)
    .sort((left, right) => left.sortOrder - right.sortOrder || left.id - right.id);
}

function formatCopyFieldProgress(mappedFieldCount: number, copyFieldCount: number) {
  return copyFieldCount === 0
    ? 'No copy yet'
    : copyFieldCount === 1
      ? `${mappedFieldCount}/1 field saved`
      : `${mappedFieldCount}/${copyFieldCount} fields saved`;
}

function getCopyFieldAnchorId(fieldId: string) {
  return `content-cms-copy-field-${fieldId}`;
}

function getCopyBlockEditorAnchorId(entryId: string) {
  return `content-cms-copy-block-${entryId}`;
}

function getCopyBlockDetailsId(entryId: string) {
  return `content-cms-copy-block-details-${entryId}`;
}

function getCopyBlockOptionsId(entryId: string) {
  return `content-cms-copy-block-options-${entryId}`;
}

function findPrimaryVariant(entry: ApiCmsEntry) {
  return (
    entry.variants.find((variant) => variant.status === 'CONTROL') ?? entry.variants[0] ?? null
  );
}

export function ProjectEditForm({
  draft,
  disabled,
  onChange,
  onSubmit,
}: {
  draft: ProjectEditDraft;
  disabled: boolean;
  onChange: (draft: ProjectEditDraft) => void;
  onSubmit: (event: FormEvent) => void;
}) {
  return (
    <form className="content-cms-admin-page__form" onSubmit={onSubmit}>
      <h3>Rename or disable copy collection</h3>
      <label className="settings-field">
        <span className="settings-field__label">Copy collection name</span>
        <input
          className="settings-input"
          value={draft.name}
          onChange={(event) => onChange({ ...draft, name: event.target.value })}
          disabled={disabled}
          required
        />
      </label>
      <label className="settings-field content-cms-admin-page__field-span">
        <span className="settings-field__label">Description</span>
        <textarea
          className="settings-input content-cms-admin-page__textarea"
          value={draft.description}
          onChange={(event) => onChange({ ...draft, description: event.target.value })}
          disabled={disabled}
        />
      </label>
      <TechnicalFieldsDisclosure hint="Delivery format affects emitted release files; change it only when delivery plumbing changed.">
        <label className="settings-field">
          <span className="settings-field__label">Delivery format</span>
          <select
            className="settings-input"
            value={draft.deliveryHint}
            onChange={(event) => onChange({ ...draft, deliveryHint: event.target.value })}
            disabled={disabled}
          >
            {deliveryHintOptions.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
        </label>
      </TechnicalFieldsDisclosure>
      <label className="settings-toggle">
        <input
          type="checkbox"
          checked={draft.enabled}
          onChange={(event) => onChange({ ...draft, enabled: event.target.checked })}
          disabled={disabled}
        />
        Copy collection enabled
      </label>
      <button type="submit" className="settings-button" disabled={disabled}>
        Save copy collection
      </button>
    </form>
  );
}

export function ContentTypeEditForm({
  contentTypes,
  selectedContentTypeId,
  draft,
  disabled,
  onContentTypeChange,
  onChange,
  onSubmit,
}: {
  contentTypes: ApiCmsContentType[];
  selectedContentTypeId: string;
  draft: ContentTypeDraft;
  disabled: boolean;
  onContentTypeChange: (value: string) => void;
  onChange: (draft: ContentTypeDraft) => void;
  onSubmit: (event: FormEvent) => void;
}) {
  return (
    <form className="content-cms-admin-page__form" onSubmit={onSubmit}>
      <h3>Edit block shape</h3>
      <label className="settings-field">
        <span className="settings-field__label">Block shape</span>
        <select
          className="settings-input"
          value={selectedContentTypeId}
          onChange={(event) => onContentTypeChange(event.target.value)}
          disabled={disabled}
          required
        >
          <option value="">Select block shape</option>
          {contentTypes.map((contentType) => (
            <option key={contentType.id} value={contentType.id}>
              {contentType.name}
            </option>
          ))}
        </select>
      </label>
      <label className="settings-field">
        <span className="settings-field__label">Shape name</span>
        <input
          className="settings-input"
          value={draft.name}
          onChange={(event) => onChange({ ...draft, name: event.target.value })}
          disabled={disabled}
          required
        />
      </label>
      <label className="settings-field content-cms-admin-page__field-span">
        <span className="settings-field__label">Description</span>
        <input
          className="settings-input"
          value={draft.description}
          onChange={(event) => onChange({ ...draft, description: event.target.value })}
          disabled={disabled}
        />
      </label>
      <TechnicalFieldsDisclosure>
        <label className="settings-field">
          <span className="settings-field__label">Stable key</span>
          <input className="settings-input" value={draft.typeKey} disabled />
        </label>
        <label className="settings-field">
          <span className="settings-field__label">Schema version</span>
          <input className="settings-input" value={draft.schemaVersion} disabled />
        </label>
        <label className="settings-field content-cms-admin-page__field-span">
          <span className="settings-field__label">Metadata schema JSON</span>
          <textarea
            className="settings-input content-cms-admin-page__textarea"
            value={draft.metadataSchemaJson}
            onChange={(event) => onChange({ ...draft, metadataSchemaJson: event.target.value })}
            disabled={disabled}
          />
        </label>
      </TechnicalFieldsDisclosure>
      <button type="submit" className="settings-button" disabled={disabled}>
        Save shape
      </button>
    </form>
  );
}

export function FieldEditForm({
  contentType,
  selectedFieldId,
  draft,
  disabled,
  onFieldChange,
  onChange,
  onSubmit,
}: {
  contentType: ApiCmsContentType | null;
  selectedFieldId: string;
  draft: FieldDraft;
  disabled: boolean;
  onFieldChange: (value: string) => void;
  onChange: (draft: FieldDraft) => void;
  onSubmit: (event: FormEvent) => void;
}) {
  return (
    <form className="content-cms-admin-page__form" onSubmit={onSubmit}>
      <h3>Edit copy piece</h3>
      <label className="settings-field">
        <span className="settings-field__label">Copy piece</span>
        <select
          className="settings-input"
          value={selectedFieldId}
          onChange={(event) => onFieldChange(event.target.value)}
          disabled={disabled || contentType == null}
          required
        >
          <option value="">Select copy piece</option>
          {contentType?.fields.map((field) => (
            <option key={field.id} value={field.id}>
              {field.name}
            </option>
          ))}
        </select>
      </label>
      <label className="settings-field">
        <span className="settings-field__label">Copy piece name</span>
        <input
          className="settings-input"
          value={draft.name}
          onChange={(event) => onChange({ ...draft, name: event.target.value })}
          disabled={disabled}
          required
        />
      </label>
      <label className="settings-field content-cms-admin-page__field-span">
        <span className="settings-field__label">Description</span>
        <input
          className="settings-input"
          value={draft.description}
          onChange={(event) => onChange({ ...draft, description: event.target.value })}
          disabled={disabled}
        />
      </label>
      <TechnicalFieldsDisclosure>
        <label className="settings-field">
          <span className="settings-field__label">Stable key</span>
          <input className="settings-input" value={draft.fieldKey} disabled />
        </label>
        <label className="settings-field">
          <span className="settings-field__label">Copy format</span>
          <select
            className="settings-input"
            value={draft.fieldType}
            onChange={(event) =>
              onChange({ ...draft, fieldType: event.target.value as ApiCmsFieldType })
            }
            disabled={disabled}
          >
            <option value="TEXT">Text</option>
            <option value="ICU_MESSAGE">ICU message</option>
          </select>
        </label>
        <label className="settings-field">
          <span className="settings-field__label">Display order</span>
          <input
            className="settings-input"
            type="number"
            min="0"
            value={draft.sortOrder}
            onChange={(event) => onChange({ ...draft, sortOrder: event.target.value })}
            disabled={disabled}
            required
          />
        </label>
        <div className="content-cms-admin-page__toggles">
          <label className="settings-toggle">
            <input
              type="checkbox"
              checked={draft.required}
              onChange={(event) => onChange({ ...draft, required: event.target.checked })}
              disabled={disabled}
            />
            Required for release
          </label>
        </div>
      </TechnicalFieldsDisclosure>
      <button type="submit" className="settings-button" disabled={disabled}>
        Save copy piece
      </button>
    </form>
  );
}

export function EntryEditForm({
  entries,
  selectedEntryId,
  draft,
  disabled,
  onEntryChange,
  onChange,
  onSubmit,
}: {
  entries: ApiCmsEntry[];
  selectedEntryId: string;
  draft: EntryDraft;
  disabled: boolean;
  onEntryChange: (value: string) => void;
  onChange: (draft: EntryDraft) => void;
  onSubmit: (event: FormEvent) => void;
}) {
  return (
    <form className="content-cms-admin-page__form" onSubmit={onSubmit}>
      <h3>Edit copy block</h3>
      <label className="settings-field">
        <span className="settings-field__label">Copy block</span>
        <select
          className="settings-input"
          value={selectedEntryId}
          onChange={(event) => onEntryChange(event.target.value)}
          disabled={disabled}
          required
        >
          <option value="">Select copy block</option>
          {entries.map((entry) => (
            <option key={entry.id} value={entry.id}>
              {entry.name}
            </option>
          ))}
        </select>
      </label>
      <label className="settings-field">
        <span className="settings-field__label">Copy block name</span>
        <input
          className="settings-input"
          value={draft.name}
          onChange={(event) => onChange({ ...draft, name: event.target.value })}
          disabled={disabled}
          required
        />
      </label>
      <label className="settings-field content-cms-admin-page__field-span">
        <span className="settings-field__label">Description</span>
        <input
          className="settings-input"
          value={draft.description}
          onChange={(event) => onChange({ ...draft, description: event.target.value })}
          disabled={disabled}
        />
      </label>
      <TechnicalFieldsDisclosure>
        <label className="settings-field">
          <span className="settings-field__label">Stable key</span>
          <input className="settings-input" value={draft.entryKey} disabled />
        </label>
        <label className="settings-field">
          <span className="settings-field__label">Release state</span>
          <select
            className="settings-input"
            value={draft.status}
            onChange={(event) =>
              onChange({ ...draft, status: event.target.value as ApiCmsEntryStatus })
            }
            disabled={disabled}
          >
            <option value="DRAFT">Draft</option>
            <option value="READY">Ready</option>
            <option value="ARCHIVED">Archived</option>
          </select>
        </label>
        <label className="settings-field content-cms-admin-page__field-span">
          <span className="settings-field__label">Metadata JSON</span>
          <textarea
            className="settings-input content-cms-admin-page__textarea"
            value={draft.metadataJson}
            onChange={(event) => onChange({ ...draft, metadataJson: event.target.value })}
            disabled={disabled}
          />
        </label>
      </TechnicalFieldsDisclosure>
      <button type="submit" className="settings-button" disabled={disabled}>
        Save copy block
      </button>
    </form>
  );
}

export function VariantEditForm({
  entry,
  selectedVariantId,
  draft,
  disabled,
  onVariantChange,
  onChange,
  onSubmit,
}: {
  entry: ApiCmsEntry | null;
  selectedVariantId: string;
  draft: VariantDraft;
  disabled: boolean;
  onVariantChange: (value: string) => void;
  onChange: (draft: VariantDraft) => void;
  onSubmit: (event: FormEvent) => void;
}) {
  return (
    <form className="content-cms-admin-page__form" onSubmit={onSubmit}>
      <h3>Edit experiment path</h3>
      <label className="settings-field">
        <span className="settings-field__label">Experiment path</span>
        <select
          className="settings-input"
          value={selectedVariantId}
          onChange={(event) => onVariantChange(event.target.value)}
          disabled={disabled || entry == null}
          required
        >
          <option value="">Select experiment path</option>
          {entry?.variants.map((variant) => (
            <option key={variant.id} value={variant.id}>
              {variant.name}
            </option>
          ))}
        </select>
      </label>
      <label className="settings-field">
        <span className="settings-field__label">Path name</span>
        <input
          className="settings-input"
          value={draft.name}
          onChange={(event) => onChange({ ...draft, name: event.target.value })}
          disabled={disabled}
          required
        />
      </label>
      <label className="settings-field">
        <span className="settings-field__label">
          Experiment group{draft.status === 'CANDIDATE' ? ' (required)' : ''}
        </span>
        <input
          className="settings-input"
          value={draft.candidateGroupKey}
          onChange={(event) => onChange({ ...draft, candidateGroupKey: event.target.value })}
          disabled={disabled}
          required={draft.status === 'CANDIDATE'}
        />
      </label>
      <TechnicalFieldsDisclosure>
        <label className="settings-field">
          <span className="settings-field__label">Stable key</span>
          <input className="settings-input" value={draft.variantKey} disabled />
        </label>
        <label className="settings-field">
          <span className="settings-field__label">Path role</span>
          <select
            className="settings-input"
            value={draft.status}
            onChange={(event) =>
              onChange({ ...draft, status: event.target.value as ApiCmsVariantStatus })
            }
            disabled={disabled}
          >
            <option value="CONTROL">Default</option>
            <option value="CANDIDATE">Candidate</option>
            <option value="ARCHIVED">Archived</option>
          </select>
        </label>
        <label className="settings-field">
          <span className="settings-field__label">Display order</span>
          <input
            className="settings-input"
            type="number"
            min="0"
            value={draft.sortOrder}
            onChange={(event) => onChange({ ...draft, sortOrder: event.target.value })}
            disabled={disabled}
            required
          />
        </label>
        <label className="settings-field content-cms-admin-page__field-span">
          <span className="settings-field__label">Metadata JSON</span>
          <textarea
            className="settings-input content-cms-admin-page__textarea"
            value={draft.metadataJson}
            onChange={(event) => onChange({ ...draft, metadataJson: event.target.value })}
            disabled={disabled}
          />
        </label>
      </TechnicalFieldsDisclosure>
      <button type="submit" className="settings-button" disabled={disabled}>
        Save experiment path
      </button>
    </form>
  );
}

export function ContentTypeForm({
  draft,
  disabled,
  onChange,
  onSubmit,
}: {
  draft: ContentTypeDraft;
  disabled: boolean;
  onChange: (draft: ContentTypeDraft) => void;
  onSubmit: (event: FormEvent) => void;
}) {
  return (
    <form className="content-cms-admin-page__form" onSubmit={onSubmit}>
      <h3>Create block shape</h3>
      <label className="settings-field">
        <span className="settings-field__label">Shape name</span>
        <input
          className="settings-input"
          value={draft.name}
          onChange={(event) =>
            onChange({
              ...draft,
              typeKey: updateSuggestedCmsKey(
                draft.typeKey,
                draft.name,
                event.target.value,
                'shape',
              ),
              name: event.target.value,
            })
          }
          placeholder="Email"
          disabled={disabled}
          required
        />
      </label>
      <label className="settings-field">
        <span className="settings-field__label">Description</span>
        <input
          className="settings-input"
          value={draft.description}
          onChange={(event) => onChange({ ...draft, description: event.target.value })}
          disabled={disabled}
        />
      </label>
      <TechnicalFieldsDisclosure>
        <label className="settings-field">
          <span className="settings-field__label">Shape key</span>
          <input
            className="settings-input"
            value={draft.typeKey}
            onChange={(event) => onChange({ ...draft, typeKey: event.target.value })}
            placeholder="email"
            disabled={disabled}
            required
          />
        </label>
        <label className="settings-field content-cms-admin-page__field-span">
          <span className="settings-field__label">Metadata schema JSON</span>
          <textarea
            className="settings-input content-cms-admin-page__textarea"
            value={draft.metadataSchemaJson}
            onChange={(event) => onChange({ ...draft, metadataSchemaJson: event.target.value })}
            placeholder='{"type":"object","properties":{"owner":{"type":"string"}}}'
            disabled={disabled}
          />
        </label>
      </TechnicalFieldsDisclosure>
      <button type="submit" className="settings-button" disabled={disabled}>
        Create shape
      </button>
    </form>
  );
}

export function FieldForm({
  contentTypes,
  draft,
  disabled,
  onChange,
  onSubmit,
}: {
  contentTypes: ApiCmsContentType[];
  draft: FieldDraft;
  disabled: boolean;
  onChange: (draft: FieldDraft) => void;
  onSubmit: (event: FormEvent) => void;
}) {
  return (
    <form className="content-cms-admin-page__form" onSubmit={onSubmit}>
      <h3>Add copy piece</h3>
      <label className="settings-field">
        <span className="settings-field__label">Block shape</span>
        <select
          className="settings-input"
          value={draft.contentTypeId}
          onChange={(event) => onChange({ ...draft, contentTypeId: event.target.value })}
          disabled={disabled}
          required
        >
          <option value="">Select shape</option>
          {contentTypes.map((contentType) => (
            <option key={contentType.id} value={contentType.id}>
              {contentType.name}
            </option>
          ))}
        </select>
      </label>
      <label className="settings-field">
        <span className="settings-field__label">Copy piece name</span>
        <input
          className="settings-input"
          value={draft.name}
          onChange={(event) =>
            onChange({
              ...draft,
              fieldKey: updateSuggestedCmsKey(
                draft.fieldKey,
                draft.name,
                event.target.value,
                'copy-piece',
              ),
              name: event.target.value,
            })
          }
          placeholder="Header"
          disabled={disabled}
          required
        />
      </label>
      <label className="settings-field">
        <span className="settings-field__label">Description</span>
        <input
          className="settings-input"
          value={draft.description}
          onChange={(event) => onChange({ ...draft, description: event.target.value })}
          disabled={disabled}
        />
      </label>
      <TechnicalFieldsDisclosure>
        <label className="settings-field">
          <span className="settings-field__label">Copy piece key</span>
          <input
            className="settings-input"
            value={draft.fieldKey}
            onChange={(event) => onChange({ ...draft, fieldKey: event.target.value })}
            placeholder="header"
            disabled={disabled}
            required
          />
        </label>
        <label className="settings-field">
          <span className="settings-field__label">Copy format</span>
          <select
            className="settings-input"
            value={draft.fieldType}
            onChange={(event) =>
              onChange({ ...draft, fieldType: event.target.value as ApiCmsFieldType })
            }
            disabled={disabled}
          >
            <option value="TEXT">Text</option>
            <option value="ICU_MESSAGE">ICU message</option>
          </select>
        </label>
        <label className="settings-field">
          <span className="settings-field__label">Display order</span>
          <input
            className="settings-input"
            type="number"
            min="0"
            value={draft.sortOrder}
            onChange={(event) => onChange({ ...draft, sortOrder: event.target.value })}
            disabled={disabled}
          />
        </label>
        <div className="content-cms-admin-page__toggles">
          <label className="settings-toggle">
            <input
              type="checkbox"
              checked={draft.required}
              onChange={(event) => onChange({ ...draft, required: event.target.checked })}
              disabled={disabled}
            />
            Required for release
          </label>
        </div>
      </TechnicalFieldsDisclosure>
      <button type="submit" className="settings-button" disabled={disabled}>
        Add copy piece
      </button>
    </form>
  );
}

export function EntryForm({
  contentTypes,
  draft,
  disabled,
  onChange,
  onSubmit,
}: {
  contentTypes: ApiCmsContentType[];
  draft: EntryDraft;
  disabled: boolean;
  onChange: (draft: EntryDraft) => void;
  onSubmit: (event: FormEvent) => void;
}) {
  return (
    <form className="content-cms-admin-page__form" onSubmit={onSubmit}>
      <h3>Create copy block</h3>
      <label className="settings-field">
        <span className="settings-field__label">Block shape</span>
        <select
          className="settings-input"
          value={draft.contentTypeId}
          onChange={(event) => onChange({ ...draft, contentTypeId: event.target.value })}
          disabled={disabled}
          required
        >
          <option value="">Select shape</option>
          {contentTypes.map((contentType) => (
            <option key={contentType.id} value={contentType.id}>
              {contentType.name}
            </option>
          ))}
        </select>
      </label>
      <label className="settings-field">
        <span className="settings-field__label">Copy block name</span>
        <input
          className="settings-input"
          value={draft.name}
          onChange={(event) =>
            onChange({
              ...draft,
              entryKey: updateSuggestedCmsKey(
                draft.entryKey,
                draft.name,
                event.target.value,
                'copy-block',
              ),
              name: event.target.value,
            })
          }
          placeholder="Welcome email"
          disabled={disabled}
          required
        />
      </label>
      <label className="settings-field">
        <span className="settings-field__label">Description</span>
        <input
          className="settings-input"
          value={draft.description}
          onChange={(event) => onChange({ ...draft, description: event.target.value })}
          disabled={disabled}
        />
      </label>
      <TechnicalFieldsDisclosure>
        <label className="settings-field">
          <span className="settings-field__label">Block key</span>
          <input
            className="settings-input"
            value={draft.entryKey}
            onChange={(event) => onChange({ ...draft, entryKey: event.target.value })}
            placeholder="welcome-email"
            disabled={disabled}
            required
          />
        </label>
        <label className="settings-field">
          <span className="settings-field__label">Release state</span>
          <select
            className="settings-input"
            value={draft.status}
            onChange={(event) =>
              onChange({ ...draft, status: event.target.value as ApiCmsEntryStatus })
            }
            disabled={disabled}
          >
            <option value="DRAFT">Draft</option>
            <option value="ARCHIVED">Archived</option>
          </select>
        </label>
        <label className="settings-field content-cms-admin-page__field-span">
          <span className="settings-field__label">Metadata JSON</span>
          <textarea
            className="settings-input content-cms-admin-page__textarea"
            value={draft.metadataJson}
            onChange={(event) => onChange({ ...draft, metadataJson: event.target.value })}
            placeholder='{"surface":"email"}'
            disabled={disabled}
          />
        </label>
      </TechnicalFieldsDisclosure>
      <button type="submit" className="settings-button" disabled={disabled}>
        Create copy block
      </button>
    </form>
  );
}

export function VariantForm({
  entries,
  draft,
  disabled,
  onChange,
  onSubmit,
}: {
  entries: ApiCmsEntry[];
  draft: VariantDraft;
  disabled: boolean;
  onChange: (draft: VariantDraft) => void;
  onSubmit: (event: FormEvent) => void;
}) {
  return (
    <form className="content-cms-admin-page__form" onSubmit={onSubmit}>
      <h3>Add experiment candidate</h3>
      <label className="settings-field">
        <span className="settings-field__label">Copy block</span>
        <select
          className="settings-input"
          value={draft.entryId}
          onChange={(event) => onChange({ ...draft, entryId: event.target.value })}
          disabled={disabled}
          required
        >
          <option value="">Select copy block</option>
          {entries.map((entry) => (
            <option key={entry.id} value={entry.id}>
              {entry.name}
            </option>
          ))}
        </select>
      </label>
      <label className="settings-field">
        <span className="settings-field__label">Candidate name</span>
        <input
          className="settings-input"
          value={draft.name}
          onChange={(event) =>
            onChange({
              ...draft,
              variantKey: updateSuggestedCmsKey(
                draft.variantKey,
                draft.name,
                event.target.value,
                'variant',
              ),
              name: event.target.value,
            })
          }
          placeholder="Subject test A"
          disabled={disabled}
          required
        />
      </label>
      <label className="settings-field">
        <span className="settings-field__label">
          Experiment group{draft.status === 'CANDIDATE' ? ' (required)' : ''}
        </span>
        <input
          className="settings-input"
          value={draft.candidateGroupKey}
          onChange={(event) => onChange({ ...draft, candidateGroupKey: event.target.value })}
          placeholder="welcome-subject"
          disabled={disabled}
          required={draft.status === 'CANDIDATE'}
        />
      </label>
      <TechnicalFieldsDisclosure>
        <label className="settings-field">
          <span className="settings-field__label">Stable key</span>
          <input
            className="settings-input"
            value={draft.variantKey}
            onChange={(event) => onChange({ ...draft, variantKey: event.target.value })}
            placeholder="subject-test-a"
            disabled={disabled}
            required
          />
        </label>
        <label className="settings-field">
          <span className="settings-field__label">Path role</span>
          <select
            className="settings-input"
            value={draft.status}
            onChange={(event) =>
              onChange({ ...draft, status: event.target.value as ApiCmsVariantStatus })
            }
            disabled={disabled}
          >
            <option value="CONTROL">Default</option>
            <option value="CANDIDATE">Candidate</option>
            <option value="ARCHIVED">Archived</option>
          </select>
        </label>
        <label className="settings-field">
          <span className="settings-field__label">Display order</span>
          <input
            className="settings-input"
            type="number"
            min="0"
            value={draft.sortOrder}
            onChange={(event) => onChange({ ...draft, sortOrder: event.target.value })}
            disabled={disabled}
          />
        </label>
        <label className="settings-field content-cms-admin-page__field-span">
          <span className="settings-field__label">Metadata JSON</span>
          <textarea
            className="settings-input content-cms-admin-page__textarea"
            value={draft.metadataJson}
            onChange={(event) => onChange({ ...draft, metadataJson: event.target.value })}
            disabled={disabled}
          />
        </label>
      </TechnicalFieldsDisclosure>
      <button type="submit" className="settings-button" disabled={disabled}>
        Create experiment candidate
      </button>
    </form>
  );
}

export function MappingForm({
  entries,
  selectedEntry,
  selectedVariant,
  selectedMapping,
  generatedStringId,
  fields,
  draft,
  disabled,
  onChange,
  onSubmit,
  onUnmap,
  repairContext = null,
  repairReadyToReturn = false,
  onReturnToProductCopy,
}: {
  entries: ApiCmsEntry[];
  selectedEntry: ApiCmsEntry | null;
  selectedVariant: ApiCmsVariant | null;
  selectedMapping: ApiCmsFieldMapping | null;
  generatedStringId: string | null;
  fields: ApiCmsContentType['fields'];
  draft: MappingDraft;
  disabled: boolean;
  onChange: (draft: MappingDraft) => void;
  onSubmit: (event: FormEvent) => void;
  onUnmap: () => void;
  repairContext?: CmsMappingRepairContext | null;
  repairReadyToReturn?: boolean;
  onReturnToProductCopy: () => void;
}) {
  const repairHeadingRef = useRef<HTMLHeadingElement | null>(null);

  useEffect(() => {
    if (repairContext == null) {
      return;
    }
    window.setTimeout(() => repairHeadingRef.current?.focus({ preventScroll: true }), 0);
  }, [repairContext]);

  return (
    <form
      className="content-cms-admin-page__form content-cms-admin-page__form--wide"
      aria-label={
        repairContext == null
          ? undefined
          : `${repairContext.fieldName} ${repairContext.localeLabel} Mojito link repair`
      }
      onSubmit={onSubmit}
    >
      {repairContext == null ? (
        <>
          <h3>Reconnect copy to Mojito</h3>
          <p className="settings-page__hint content-cms-admin-page__field-span">
            Fresh links use the source copy and translator note below. Open Technical fields only
            when another Mojito record already owns the source.
          </p>
          <label className="settings-field">
            <span className="settings-field__label">Copy block</span>
            <select
              className="settings-input"
              value={draft.entryId}
              onChange={(event) =>
                onChange({ ...draft, entryId: event.target.value, variantId: '', fieldId: '' })
              }
              disabled={disabled}
              required
            >
              <option value="">Select copy block</option>
              {entries.map((entry) => (
                <option key={entry.id} value={entry.id}>
                  {entry.name}
                </option>
              ))}
            </select>
          </label>
          <label className="settings-field">
            <span className="settings-field__label">Experiment path</span>
            <select
              className="settings-input"
              value={draft.variantId}
              onChange={(event) => onChange({ ...draft, variantId: event.target.value })}
              disabled={disabled || !selectedEntry}
              required
            >
              <option value="">Select experiment path</option>
              {selectedEntry?.variants.map((variant) => (
                <option key={variant.id} value={variant.id}>
                  {variant.name}
                </option>
              ))}
            </select>
          </label>
          <label className="settings-field">
            <span className="settings-field__label">Copy piece</span>
            <select
              className="settings-input"
              value={draft.fieldId}
              onChange={(event) => onChange({ ...draft, fieldId: event.target.value })}
              disabled={disabled || !selectedVariant}
              required
            >
              <option value="">Select copy piece</option>
              {fields.map((field) => (
                <option key={field.id} value={field.id}>
                  {field.name}
                </option>
              ))}
            </select>
          </label>
        </>
      ) : (
        <>
          <span className="content-cms-admin-page__eyebrow content-cms-admin-page__field-span">
            Translation repair
          </span>
          <h3
            ref={repairHeadingRef}
            className="content-cms-admin-page__mapping-repair-heading content-cms-admin-page__field-span"
            tabIndex={-1}
          >
            Reconnect {repairContext.fieldName} for {repairContext.localeLabel}
          </h3>
          <p className="settings-page__hint content-cms-admin-page__field-span">
            Product copy could not open the saved {repairContext.localeLabel} translation for{' '}
            {repairContext.contentItemName}. Repair this Mojito link here, then return to Product
            copy to reopen the same translation.
          </p>
          <div
            className="content-cms-admin-page__mapping-repair-context content-cms-admin-page__field-span"
            aria-label="Blocked translation repair target"
          >
            <Definition label="Content item" value={repairContext.contentItemName} />
            <Definition label="Field" value={repairContext.fieldName} />
            <Definition label="Blocked language" value={repairContext.localeLabel} />
          </div>
        </>
      )}
      <label className="settings-field content-cms-admin-page__field-span">
        <span className="settings-field__label">Source copy</span>
        <textarea
          className="settings-input content-cms-admin-page__textarea"
          value={draft.sourceContent}
          onChange={(event) => onChange({ ...draft, sourceContent: event.target.value })}
          placeholder="Required for fresh links"
          disabled={disabled || draft.mappingSource !== 'GENERATED'}
          required={draft.mappingSource === 'GENERATED'}
        />
      </label>
      <label className="settings-field content-cms-admin-page__field-span">
        <span className="settings-field__label">Translator note</span>
        <textarea
          className="settings-input content-cms-admin-page__textarea"
          value={draft.sourceComment}
          onChange={(event) => onChange({ ...draft, sourceComment: event.target.value })}
          placeholder="Where this copy appears and any constraints"
          disabled={disabled || draft.mappingSource !== 'GENERATED'}
          required={draft.mappingSource === 'GENERATED'}
        />
      </label>
      {draft.mappingSource !== 'GENERATED' ? (
        <p className="settings-page__hint content-cms-admin-page__field-span">
          This link follows an exact Mojito record. Open Technical fields to change it or switch
          back to saved source copy.
        </p>
      ) : null}
      <TechnicalFieldsDisclosure hint="Mojito IDs and alternate link sources stay here for recovery and debugging.">
        <label className="settings-field">
          <span className="settings-field__label">Mojito link source</span>
          <select
            className="settings-input"
            value={draft.mappingSource}
            onChange={(event) =>
              onChange({
                ...draft,
                mappingSource: event.target.value as MappingSource,
                tmTextUnitId: '',
                stringId: '',
              })
            }
            disabled={disabled}
          >
            <option value="GENERATED">Fresh Mojito string</option>
            <option value="STRING_ID">Existing Mojito string ID</option>
            <option value="TM_TEXT_UNIT_ID">Exact TM text unit ID</option>
          </select>
        </label>
        <Definition
          label="Suggested Mojito string ID"
          value={generatedStringId ?? 'Select copy block, experiment path, and copy piece'}
        />
        {selectedMapping ? (
          <Definition label="Current Mojito string ID" value={selectedMapping.stringId} />
        ) : null}
        <label className="settings-field">
          <span className="settings-field__label">Existing Mojito string ID</span>
          <input
            className="settings-input"
            value={draft.stringId}
            onChange={(event) => onChange({ ...draft, stringId: event.target.value })}
            placeholder="Active project-asset string ID"
            required={draft.mappingSource === 'STRING_ID'}
            disabled={disabled || draft.mappingSource !== 'STRING_ID'}
          />
        </label>
        <label className="settings-field">
          <span className="settings-field__label">Exact TM text unit ID</span>
          <input
            className="settings-input"
            type="number"
            value={draft.tmTextUnitId}
            onChange={(event) => onChange({ ...draft, tmTextUnitId: event.target.value })}
            placeholder="Precise active text unit"
            required={draft.mappingSource === 'TM_TEXT_UNIT_ID'}
            disabled={disabled || draft.mappingSource !== 'TM_TEXT_UNIT_ID'}
          />
        </label>
      </TechnicalFieldsDisclosure>
      <div className="content-cms-admin-page__actions">
        <button type="submit" className="settings-button" disabled={disabled}>
          {repairContext != null
            ? `Reconnect ${repairContext.fieldName}`
            : selectedMapping
              ? 'Save link'
              : 'Reconnect copy'}
        </button>
        <button
          type="button"
          className="settings-button"
          disabled={disabled || selectedMapping == null}
          onClick={onUnmap}
        >
          Remove link
        </button>
      </div>
      {repairContext != null && repairReadyToReturn ? (
        <div
          className="content-cms-admin-page__mapping-repair-success content-cms-admin-page__field-span"
          role="status"
          aria-live="polite"
        >
          <strong>Mojito link saved</strong>
          <p className="settings-page__hint">
            Return to Product copy to reopen {repairContext.localeLabel} for{' '}
            {repairContext.fieldName}.
          </p>
          <button
            type="button"
            className="settings-button settings-button--primary"
            disabled={disabled}
            onClick={onReturnToProductCopy}
          >
            Return to Product copy
          </button>
        </div>
      ) : null}
    </form>
  );
}

export function PublishForm({
  projectName,
  sourceLocale,
  entries,
  contentTypes,
  selectedEntryId,
  selectedFieldId,
  snapshots,
  hasMoreSnapshots,
  isLoadingOlderSnapshots,
  completenessResult,
  blockedCompletenessResult = null,
  publishLocales,
  disabled,
  compact = false,
  authorFacing = false,
  showTechnicalDetails = true,
  technicalDetailsOpenByDefault = false,
  blockedHint = null,
  blockedStatusLabel = null,
  inlineTranslationReleaseBlockerTargets = [],
  releaseRecovery = null,
  releaseCheckPending = false,
  releasePublishPending = false,
  releaseSuccess = null,
  releaseHistoryChange = null,
  onOpenIncludedEntry,
  onReviewBlockedEntry,
  onPublishLocalesChange,
  onCompletenessSubmit,
  onPublish,
  onLoadOlderSnapshots,
}: {
  projectName: string;
  sourceLocale: string;
  entries: ApiCmsEntry[];
  contentTypes: ApiCmsContentType[];
  selectedEntryId?: string;
  selectedFieldId?: string;
  snapshots: ApiCmsProjectDetail['publishSnapshots'];
  hasMoreSnapshots: boolean;
  isLoadingOlderSnapshots: boolean;
  completenessResult: ApiCmsProjectCompleteness | null;
  blockedCompletenessResult?: ApiCmsProjectCompleteness | null;
  publishLocales: string;
  disabled: boolean;
  compact?: boolean;
  authorFacing?: boolean;
  showTechnicalDetails?: boolean;
  technicalDetailsOpenByDefault?: boolean;
  blockedHint?: string | null;
  blockedStatusLabel?: string | null;
  inlineTranslationReleaseBlockerTargets?: InlineTranslationReleaseBlockerTarget[];
  releaseRecovery?: ReleaseRecovery | null;
  releaseCheckPending?: boolean;
  releasePublishPending?: boolean;
  releaseSuccess?: ReleaseSuccess | null;
  releaseHistoryChange?: ReleaseHistoryChange | null;
  onOpenIncludedEntry?: (entryId: string, afterChange?: () => void) => void;
  onReviewBlockedEntry?: (
    entryId: string,
    fieldId?: string | null,
    localeTag?: string | null,
    repairTarget?: ReleaseRepairTarget | null,
    reviewReason?: string | null,
    reviewReturnMode?: ReleaseReviewReturnMode | null,
    lastReleasedSourceContent?: string | null,
    lastReleasedTranslationContent?: string | null,
  ) => void;
  onPublishLocalesChange: (value: string) => void;
  onCompletenessSubmit: (event: FormEvent) => void;
  onPublish: () => void;
  onLoadOlderSnapshots: () => void;
}) {
  const resolveLocaleDisplayName = useLocaleDisplayNameResolver();
  const latestSnapshot = snapshots[0] ?? null;
  const [historyOpen, setHistoryOpen] = useState(false);
  const [technicalDetailsOpen, setTechnicalDetailsOpen] = useState(technicalDetailsOpenByDefault);
  const releaseReadiness =
    completenessResult == null
      ? null
      : getProjectReleaseReadiness(
          completenessResult,
          sourceLocale,
          entries,
          contentTypes,
          resolveLocaleDisplayName,
        );
  const blockedReleaseReadiness =
    blockedCompletenessResult == null
      ? null
      : getProjectReleaseReadiness(
          blockedCompletenessResult,
          sourceLocale,
          entries,
          contentTypes,
          resolveLocaleDisplayName,
        );
  const inlineTranslationReleaseBlockers = inlineTranslationReleaseBlockerTargets.map(
    (inlineTranslationReleaseBlockerTarget) =>
      getInlineTranslationReleaseBlocker(
        inlineTranslationReleaseBlockerTarget,
        entries,
        contentTypes,
        resolveLocaleDisplayName,
      ),
  );
  const hasIncludedEntries = entries.some((entry) => entry.status === 'READY');
  const includedEntries = entries.filter((entry) => entry.status === 'READY');
  const releaseAlreadyCompleted = authorFacing && releaseSuccess != null;
  const canUseReleaseAction = authorFacing ? hasIncludedEntries : releaseReadiness?.ready === true;
  const shouldEmphasizeReadiness = !authorFacing && releaseReadiness?.ready !== true;
  const shouldEmphasizeRelease = authorFacing
    ? hasIncludedEntries && !releaseAlreadyCompleted
    : releaseReadiness?.ready === true;
  const releaseButtonLabel = releasePublishPending
    ? 'Releasing...'
    : authorFacing && releaseCheckPending
      ? 'Checking release...'
      : releaseAlreadyCompleted
        ? 'Check release again'
        : 'Release approved copy';
  const releasePanelTitleId = `${cmsReleasePanelId}-title`;
  const releaseReadinessSummaryId = `${cmsReleasePanelId}-readiness`;
  const releaseRecoveryId = `${cmsReleasePanelId}-recovery`;
  const releaseActionDescriptionIds =
    releaseRecovery == null
      ? releaseReadinessSummaryId
      : `${releaseReadinessSummaryId} ${releaseRecoveryId}`;
  const latestReleaseSummary = latestSnapshot
    ? `Last released ${formatLocaleLabelList(latestSnapshot.localeTags, resolveLocaleDisplayName)} · ${formatDateTime(latestSnapshot.publishedAt)}`
    : 'No releases yet.';
  useEffect(() => {
    setHistoryOpen(false);
    setTechnicalDetailsOpen(technicalDetailsOpenByDefault);
  }, [projectName, technicalDetailsOpenByDefault]);

  return (
    <div
      className={`content-cms-admin-page__form content-cms-admin-page__form--wide${
        authorFacing ? ' content-cms-admin-page__release-flow--author' : ''
      }`}
    >
      <section
        id={cmsReleasePanelId}
        className={`content-cms-admin-page__release-panel${authorFacing ? ' is-author' : ''}`}
        aria-labelledby={releasePanelTitleId}
        tabIndex={-1}
      >
        <div className="content-cms-admin-page__release-copy">
          <span className="content-cms-admin-page__eyebrow">Release</span>
          <h3 id={releasePanelTitleId}>Release approved copy</h3>
          <p className="settings-page__hint">
            {authorFacing
              ? 'Release approved copy for apps to use. Included content items are checked first.'
              : 'Check readiness, then release the approved copy for apps to use.'}
          </p>
        </div>
        <form className="content-cms-admin-page__release-actions" onSubmit={onCompletenessSubmit}>
          {!authorFacing ? (
            <button
              type="submit"
              className={`settings-button${shouldEmphasizeReadiness ? ' settings-button--primary' : ''}`}
              disabled={disabled || releaseCheckPending}
              aria-describedby={releaseActionDescriptionIds}
            >
              {releaseCheckPending ? 'Checking readiness...' : 'Check readiness'}
            </button>
          ) : null}
          <button
            type="button"
            className={`settings-button${shouldEmphasizeRelease ? ' settings-button--primary' : ''}`}
            disabled={
              disabled || releaseCheckPending || releasePublishPending || !canUseReleaseAction
            }
            aria-describedby={releaseActionDescriptionIds}
            onClick={onPublish}
          >
            {releaseButtonLabel}
          </button>
        </form>
        <ReleaseReadinessSummary
          readiness={releaseReadiness}
          blockedReadiness={blockedReleaseReadiness}
          blockedHint={blockedHint}
          blockedStatusLabel={blockedStatusLabel}
          summaryId={releaseReadinessSummaryId}
          recoveryId={releaseRecoveryId}
          inlineTranslationReleaseBlockers={inlineTranslationReleaseBlockers}
          releaseRecovery={releaseRecovery}
          authorFacing={authorFacing}
          includedEntries={includedEntries}
          releaseCheckPending={releaseCheckPending}
          releasePublishPending={releasePublishPending}
          releaseSuccess={releaseSuccess}
          releaseHistoryChange={releaseHistoryChange}
          resolveLocaleDisplayName={resolveLocaleDisplayName}
          selectedEntryId={selectedEntryId}
          selectedFieldId={selectedFieldId}
          onOpenIncludedEntry={onOpenIncludedEntry}
          onReviewBlockedEntry={onReviewBlockedEntry}
        />
      </section>

      {compact ? (
        <>
          {authorFacing ? (
            <p className="content-cms-admin-page__release-last">{latestReleaseSummary}</p>
          ) : (
            <div className="content-cms-admin-page__release-summary">
              <Definition
                label="Latest release"
                value={latestSnapshot ? `v${latestSnapshot.snapshotVersion}` : 'No releases yet'}
              />
              <Definition
                label="Released locales"
                value={latestSnapshot ? latestSnapshot.localeTags.join(', ') : 'Checked at release'}
              />
              <Definition
                label="Released"
                value={
                  latestSnapshot ? formatDateTime(latestSnapshot.publishedAt) : 'Not released yet'
                }
              />
            </div>
          )}
          {showTechnicalDetails ? (
            <div className="content-cms-admin-page__release-history">
              <button
                type="button"
                className="content-cms-admin-page__release-history-trigger"
                aria-expanded={technicalDetailsOpen}
                onClick={() => setTechnicalDetailsOpen((current) => !current)}
              >
                Technical release details
              </button>
              {technicalDetailsOpen ? (
                <div className="content-cms-admin-page__release-technical-details">
                  <label className="settings-field">
                    <span className="settings-field__label">Release locales</span>
                    <input
                      className="settings-input"
                      value={publishLocales}
                      onChange={(event) => onPublishLocalesChange(event.target.value)}
                      placeholder="Leave blank for all configured locales"
                      disabled={disabled}
                    />
                  </label>
                  {completenessResult ? <CompletenessTable result={completenessResult} /> : null}
                  {latestSnapshot ? (
                    <a href={latestSnapshot.artifactExportPath} target="_blank" rel="noreferrer">
                      Released JSON
                    </a>
                  ) : null}
                  <button
                    type="button"
                    className="content-cms-admin-page__release-history-trigger"
                    aria-expanded={historyOpen}
                    onClick={() => setHistoryOpen((current) => !current)}
                  >
                    Release history
                  </button>
                  {historyOpen ? (
                    <PublishHistoryTable
                      snapshots={snapshots}
                      hasMoreSnapshots={hasMoreSnapshots}
                      isLoadingOlderSnapshots={isLoadingOlderSnapshots}
                      disabled={disabled}
                      onLoadOlderSnapshots={onLoadOlderSnapshots}
                    />
                  ) : null}
                </div>
              ) : null}
            </div>
          ) : null}
        </>
      ) : (
        <PublishHistoryTable
          snapshots={snapshots}
          hasMoreSnapshots={hasMoreSnapshots}
          isLoadingOlderSnapshots={isLoadingOlderSnapshots}
          disabled={disabled}
          onLoadOlderSnapshots={onLoadOlderSnapshots}
        />
      )}
    </div>
  );
}

type ReleaseBlockedEntry = {
  action: ReleaseRepairAction;
  entryId: number;
  entryName: string;
  fieldId: number | null;
  fieldName: string | null;
  hint: string;
  localeTag: string | null;
  repairTarget: ReleaseRepairTarget;
};

type ProjectReleaseReadiness = {
  ready: boolean;
  hint: string;
  blockedEntries: ReleaseBlockedEntry[];
};

export type ReleaseRecovery = {
  kind: 'retry' | 'recheck' | 'repair-refresh';
  message: string;
};

export type ReleaseSuccess = {
  message: string;
};

export type ReleaseHistoryChange = {
  message: string;
  changeSummary: ApiCmsReleaseChangeSummary;
  loadAllChanges?: () => void;
  loadAllChangesPending?: boolean;
  loadAllChangesError?: string | null;
};

export type AuthoringRecovery =
  | {
      kind: 'first-writing-space';
      message: string;
    }
  | {
      kind: 'first-copy-block';
      message: string;
    }
  | {
      kind: 'source-copy';
      fieldId: string;
      message: string;
    }
  | {
      kind: 'field-context';
      fieldId: string;
      message: string;
    }
  | {
      kind: 'target-locales';
      message: string;
    }
  | {
      kind: 'block-release';
      entryId: string;
      message: string;
    }
  | {
      kind: 'block-details';
      entryId: string;
      message: string;
    }
  | {
      kind: 'new-block';
      message: string;
    };

function ReleaseReadinessSummary({
  readiness,
  blockedReadiness = null,
  blockedHint,
  blockedStatusLabel = null,
  summaryId,
  recoveryId,
  inlineTranslationReleaseBlockers = [],
  releaseRecovery,
  authorFacing = false,
  includedEntries = [],
  releaseCheckPending = false,
  releasePublishPending = false,
  releaseSuccess = null,
  releaseHistoryChange = null,
  resolveLocaleDisplayName = (localeTag) => localeTag,
  selectedEntryId,
  selectedFieldId,
  onOpenIncludedEntry,
  onReviewBlockedEntry,
}: {
  readiness: ProjectReleaseReadiness | null;
  blockedReadiness?: ProjectReleaseReadiness | null;
  blockedHint?: string | null;
  blockedStatusLabel?: string | null;
  summaryId: string;
  recoveryId: string;
  inlineTranslationReleaseBlockers?: ReleaseBlockedEntry[];
  releaseRecovery?: ReleaseRecovery | null;
  authorFacing?: boolean;
  includedEntries?: ApiCmsEntry[];
  releaseCheckPending?: boolean;
  releasePublishPending?: boolean;
  releaseSuccess?: ReleaseSuccess | null;
  releaseHistoryChange?: ReleaseHistoryChange | null;
  resolveLocaleDisplayName?: (localeTag: string) => string;
  selectedEntryId?: string;
  selectedFieldId?: string;
  onOpenIncludedEntry?: (entryId: string, afterChange?: () => void) => void;
  onReviewBlockedEntry?: (
    entryId: string,
    fieldId?: string | null,
    localeTag?: string | null,
    repairTarget?: ReleaseRepairTarget | null,
    reviewReason?: string | null,
    reviewReturnMode?: ReleaseReviewReturnMode | null,
    lastReleasedSourceContent?: string | null,
    lastReleasedTranslationContent?: string | null,
  ) => void;
}) {
  const releaseRecoveryPanel = (
    <ReleaseRecoveryPanel
      recoveryId={recoveryId}
      releaseRecovery={releaseRecovery}
      authorFacing={authorFacing}
    />
  );
  if (blockedHint != null) {
    const releaseWork = addInlineTranslationReleaseBlockers(
      readiness ?? blockedReadiness,
      inlineTranslationReleaseBlockers,
    );
    const blockedReleaseWork =
      releaseWork != null && !releaseWork.ready && releaseWork.blockedEntries.length > 0 ? (
        <ReleaseBlockers
          blockedEntries={releaseWork.blockedEntries}
          selectedEntryId={selectedEntryId}
          selectedFieldId={selectedFieldId}
          onReviewBlockedEntry={onReviewBlockedEntry}
        />
      ) : null;
    return (
      <>
        <div
          id={summaryId}
          className="content-cms-admin-page__release-readiness"
          role="status"
          aria-live="polite"
        >
          <span className="content-cms-admin-page__status is-warning">
            {blockedStatusLabel ??
              (authorFacing ? 'Save before releasing' : 'Save before checking')}
          </span>
          <p className="settings-page__hint">{blockedHint}</p>
        </div>
        {releaseRecoveryPanel}
        {blockedReleaseWork}
      </>
    );
  }
  if (authorFacing && includedEntries.length === 0) {
    return (
      <>
        <div
          id={summaryId}
          className="content-cms-admin-page__release-readiness"
          role="status"
          aria-live="polite"
        >
          <span className="content-cms-admin-page__status is-warning">Nothing included yet</span>
          <p className="settings-page__hint">
            Include a ready content item above before releasing approved copy.
          </p>
        </div>
        {releaseRecoveryPanel}
      </>
    );
  }
  const authorReleaseDetails = authorFacing ? (
    <AuthorReleaseDetails
      includedEntries={includedEntries}
      releaseHistoryChange={releaseHistoryChange}
      resolveLocaleDisplayName={resolveLocaleDisplayName}
      selectedEntryId={selectedEntryId}
      selectedFieldId={selectedFieldId}
      onOpenIncludedEntry={onOpenIncludedEntry}
      onReviewChangedEntry={onReviewBlockedEntry}
    />
  ) : null;
  if (authorFacing && releaseCheckPending) {
    return (
      <>
        <div
          id={summaryId}
          className="content-cms-admin-page__release-readiness"
          role="status"
          aria-live="polite"
        >
          <span className="content-cms-admin-page__status is-warning">Checking release</span>
          <p className="settings-page__hint">
            Checking every included content item before confirmation.
          </p>
        </div>
        {authorReleaseDetails}
      </>
    );
  }
  if (authorFacing && releasePublishPending) {
    return (
      <>
        <div
          id={summaryId}
          className="content-cms-admin-page__release-readiness"
          role="status"
          aria-live="polite"
        >
          <span className="content-cms-admin-page__status is-ready">Releasing copy</span>
          <p className="settings-page__hint">
            Creating the release for every included content item.
          </p>
        </div>
        {authorReleaseDetails}
      </>
    );
  }
  if (authorFacing && releaseSuccess != null) {
    return (
      <>
        <div
          id={summaryId}
          className="content-cms-admin-page__release-readiness"
          role="status"
          aria-live="polite"
        >
          <span className="content-cms-admin-page__status is-ready">Released copy</span>
          <p className="settings-page__hint">{releaseSuccess.message}</p>
        </div>
        {releaseRecoveryPanel}
        {authorReleaseDetails}
      </>
    );
  }
  if (readiness == null) {
    return (
      <>
        <div
          id={summaryId}
          className="content-cms-admin-page__release-readiness"
          role={authorFacing ? 'status' : undefined}
          aria-live={authorFacing ? 'polite' : undefined}
        >
          <span className="content-cms-admin-page__status is-warning">
            {authorFacing ? 'Release not started' : 'Readiness not checked'}
          </span>
          <p className="settings-page__hint">
            {authorFacing
              ? 'Release approved copy checks every included content item before it goes live.'
              : 'Check readiness to confirm every included content item can release.'}
          </p>
        </div>
        {releaseRecoveryPanel}
        {authorReleaseDetails}
      </>
    );
  }

  return (
    <>
      <div
        id={summaryId}
        className="content-cms-admin-page__release-readiness"
        role={authorFacing ? 'status' : undefined}
        aria-live={authorFacing ? 'polite' : undefined}
      >
        <span
          className={`content-cms-admin-page__status${readiness.ready ? ' is-ready' : ' is-warning'}`}
        >
          {readiness.ready ? 'Ready to release' : 'Needs work before release'}
        </span>
        <p className="settings-page__hint">{readiness.hint}</p>
      </div>
      {releaseRecoveryPanel}
      {!readiness.ready && readiness.blockedEntries.length > 0 ? (
        <ReleaseBlockers
          blockedEntries={readiness.blockedEntries}
          selectedEntryId={selectedEntryId}
          selectedFieldId={selectedFieldId}
          onReviewBlockedEntry={onReviewBlockedEntry}
        />
      ) : null}
      {authorReleaseDetails}
    </>
  );
}

function AuthorReleaseDetails({
  includedEntries,
  releaseHistoryChange,
  resolveLocaleDisplayName,
  selectedEntryId,
  selectedFieldId,
  onOpenIncludedEntry,
  onReviewChangedEntry,
}: {
  includedEntries: ApiCmsEntry[];
  releaseHistoryChange?: ReleaseHistoryChange | null;
  resolveLocaleDisplayName: (localeTag: string) => string;
  selectedEntryId?: string;
  selectedFieldId?: string;
  onOpenIncludedEntry?: (entryId: string, afterChange?: () => void) => void;
  onReviewChangedEntry?: (
    entryId: string,
    fieldId?: string | null,
    localeTag?: string | null,
    repairTarget?: ReleaseRepairTarget | null,
    reviewReason?: string | null,
    reviewReturnMode?: ReleaseReviewReturnMode | null,
    lastReleasedSourceContent?: string | null,
    lastReleasedTranslationContent?: string | null,
  ) => void;
}) {
  const [releaseDetailsOpen, setReleaseDetailsOpen] = useState(false);
  const hasIncludedEntryDetails = includedEntries.some(
    (entry) => String(entry.id) !== selectedEntryId,
  );
  const hasReleaseDetails = hasIncludedEntryDetails || releaseHistoryChange != null;

  return (
    <div className="content-cms-admin-page__release-details">
      <div className="content-cms-admin-page__release-details-summary">
        {hasReleaseDetails ? (
          <button
            type="button"
            className="content-cms-admin-page__release-details-trigger"
            aria-expanded={releaseDetailsOpen}
            aria-controls={cmsAuthorReleaseDetailsId}
            onClick={() => setReleaseDetailsOpen((current) => !current)}
          >
            {releaseDetailsOpen ? 'Hide release details' : 'Show release details'}
          </button>
        ) : null}
        <span>{formatAuthorReleaseDetailsSummary(includedEntries, releaseHistoryChange)}</span>
      </div>
      {releaseDetailsOpen && hasReleaseDetails ? (
        <div
          id={cmsAuthorReleaseDetailsId}
          className="content-cms-admin-page__release-details-body"
        >
          {hasIncludedEntryDetails ? (
            <IncludedReleaseEntries
              entries={includedEntries}
              selectedEntryId={selectedEntryId}
              onOpenIncludedEntry={onOpenIncludedEntry}
            />
          ) : null}
          {releaseHistoryChange ? (
            <ReleaseHistoryChangeNotice
              releaseHistoryChange={releaseHistoryChange}
              resolveLocaleDisplayName={resolveLocaleDisplayName}
              selectedEntryId={selectedEntryId}
              selectedFieldId={selectedFieldId}
              onReviewChangedEntry={onReviewChangedEntry}
            />
          ) : null}
        </div>
      ) : null}
    </div>
  );
}

function ReleaseBlockers({
  blockedEntries,
  selectedEntryId,
  selectedFieldId,
  onReviewBlockedEntry,
}: {
  blockedEntries: ReleaseBlockedEntry[];
  selectedEntryId?: string;
  selectedFieldId?: string;
  onReviewBlockedEntry?: (
    entryId: string,
    fieldId?: string | null,
    localeTag?: string | null,
    repairTarget?: ReleaseRepairTarget | null,
    reviewReason?: string | null,
    reviewReturnMode?: ReleaseReviewReturnMode | null,
    lastReleasedSourceContent?: string | null,
    lastReleasedTranslationContent?: string | null,
  ) => void;
}) {
  return (
    <div
      className="content-cms-admin-page__release-blockers"
      role="group"
      aria-label="Content items needing work"
    >
      <span className="content-cms-admin-page__eyebrow">Fix before release</span>
      {groupReleaseBlockedEntries(blockedEntries, selectedEntryId).map((blockedEntryGroup) => (
        <ReleaseBlockerGroup
          key={blockedEntryGroup.key}
          blockedEntryGroup={blockedEntryGroup}
          selectedEntryId={selectedEntryId}
          selectedFieldId={selectedFieldId}
          onReviewBlockedEntry={onReviewBlockedEntry}
        />
      ))}
    </div>
  );
}

function ReleaseBlockerGroup({
  blockedEntryGroup,
  selectedEntryId,
  selectedFieldId,
  onReviewBlockedEntry,
}: {
  blockedEntryGroup: ReleaseBlockedEntryGroup;
  selectedEntryId?: string;
  selectedFieldId?: string;
  onReviewBlockedEntry?: (
    entryId: string,
    fieldId?: string | null,
    localeTag?: string | null,
    repairTarget?: ReleaseRepairTarget | null,
    reviewReason?: string | null,
    reviewReturnMode?: ReleaseReviewReturnMode | null,
    lastReleasedSourceContent?: string | null,
    lastReleasedTranslationContent?: string | null,
  ) => void;
}) {
  const orderedBlockedEntries = sortReleaseBlockedEntries(
    blockedEntryGroup.blockedEntries,
    selectedFieldId,
  );
  const blockedEntryStateKey = blockedEntryGroup.blockedEntries
    .map(
      (blockedEntry) =>
        `${blockedEntry.action}:${blockedEntry.fieldId ?? 'entry'}:${blockedEntry.localeTag ?? 'source'}:${blockedEntry.repairTarget ?? 'none'}`,
    )
    .join('|');
  const [showAllBlockers, setShowAllBlockers] = useState(false);
  useEffect(() => {
    setShowAllBlockers(false);
  }, [blockedEntryStateKey]);
  const visibleBlockedEntries = showAllBlockers
    ? orderedBlockedEntries
    : orderedBlockedEntries.slice(0, authorReleaseBlockerPreviewCount);
  const hiddenBlockedEntryCount = orderedBlockedEntries.length - visibleBlockedEntries.length;
  const releaseBlockerListId = getAuthorReleaseBlockerListId(blockedEntryGroup.entryId);

  return (
    <section
      className={`content-cms-admin-page__release-blocker-group${
        blockedEntryGroup.entryId === selectedEntryId ? ' is-selected' : ''
      }`}
      aria-label={`${blockedEntryGroup.entryName} release blockers`}
    >
      <div className="content-cms-admin-page__release-blocker-group-header">
        <div className="content-cms-admin-page__release-blocker-group-title">
          <strong>{blockedEntryGroup.entryName}</strong>
          {blockedEntryGroup.entryId === selectedEntryId ? (
            <span className="content-cms-admin-page__release-blocker-current">
              Current content item
            </span>
          ) : null}
        </div>
        <span>{formatReleaseBlockedEntryCount(blockedEntryGroup.blockedEntries)}</span>
      </div>
      <div id={releaseBlockerListId} className="content-cms-admin-page__release-blocker-list">
        {visibleBlockedEntries.map((blockedEntry) => (
          <ReleaseBlockerAction
            key={`${blockedEntry.entryId}:${blockedEntry.fieldId ?? 'entry'}`}
            blockedEntry={blockedEntry}
            selectedFieldId={selectedFieldId}
            onReviewBlockedEntry={onReviewBlockedEntry}
          />
        ))}
      </div>
      {hiddenBlockedEntryCount > 0 || showAllBlockers ? (
        <button
          type="button"
          className="content-cms-admin-page__release-blocker-toggle"
          aria-expanded={showAllBlockers}
          aria-controls={releaseBlockerListId}
          onClick={() => setShowAllBlockers((current) => !current)}
        >
          {showAllBlockers
            ? formatCollapsedReleaseBlockedEntryLabel(orderedBlockedEntries)
            : formatHiddenReleaseBlockedEntryCount(
                orderedBlockedEntries.slice(visibleBlockedEntries.length),
              )}
        </button>
      ) : null}
    </section>
  );
}

function ReleaseBlockerAction({
  blockedEntry,
  selectedFieldId,
  onReviewBlockedEntry,
}: {
  blockedEntry: ReleaseBlockedEntry;
  selectedFieldId?: string;
  onReviewBlockedEntry?: (
    entryId: string,
    fieldId?: string | null,
    localeTag?: string | null,
    repairTarget?: ReleaseRepairTarget | null,
    reviewReason?: string | null,
    reviewReturnMode?: ReleaseReviewReturnMode | null,
    lastReleasedSourceContent?: string | null,
    lastReleasedTranslationContent?: string | null,
  ) => void;
}) {
  const resolveLocaleDisplayName = useLocaleDisplayNameResolver();
  const blockerActionLabel = getReleaseBlockerActionLabel(blockedEntry.action);
  const blockerActionAriaLabel = getReleaseBlockerActionAriaLabel(
    blockedEntry,
    resolveLocaleDisplayName,
  );
  const isSelectedField =
    blockedEntry.fieldId != null && String(blockedEntry.fieldId) === selectedFieldId;
  const blockedEntryContent = (
    <>
      <span className="content-cms-admin-page__release-blocker-copy">
        <span>
          {blockedEntry.fieldName == null ? null : <strong>{blockedEntry.fieldName}: </strong>}
          {blockedEntry.hint}
        </span>
        {isSelectedField ? (
          <span className="content-cms-admin-page__release-blocker-current">Current field</span>
        ) : null}
      </span>
      {onReviewBlockedEntry ? (
        <span className="content-cms-admin-page__release-blocker-action">{blockerActionLabel}</span>
      ) : null}
    </>
  );
  const blockerClassName = `content-cms-admin-page__release-blocker${
    isSelectedField ? ' is-selected-field' : ''
  }`;
  return onReviewBlockedEntry ? (
    <button
      type="button"
      className={blockerClassName}
      aria-label={blockerActionAriaLabel}
      onClick={() =>
        onReviewBlockedEntry(
          String(blockedEntry.entryId),
          blockedEntry.fieldId == null ? null : String(blockedEntry.fieldId),
          blockedEntry.localeTag,
          blockedEntry.repairTarget,
          blockedEntry.hint,
          'repair',
        )
      }
    >
      {blockedEntryContent}
    </button>
  ) : (
    <div className={blockerClassName}>{blockedEntryContent}</div>
  );
}

function ReleaseHistoryChangeNotice({
  releaseHistoryChange,
  resolveLocaleDisplayName,
  selectedEntryId,
  selectedFieldId,
  onReviewChangedEntry,
}: {
  releaseHistoryChange?: ReleaseHistoryChange | null;
  resolveLocaleDisplayName: (localeTag: string) => string;
  selectedEntryId?: string;
  selectedFieldId?: string;
  onReviewChangedEntry?: (
    entryId: string,
    fieldId?: string | null,
    localeTag?: string | null,
    repairTarget?: ReleaseRepairTarget | null,
    reviewReason?: string | null,
    reviewReturnMode?: ReleaseReviewReturnMode | null,
    lastReleasedSourceContent?: string | null,
    lastReleasedTranslationContent?: string | null,
  ) => void;
}) {
  const releaseChangePreviewKey =
    releaseHistoryChange?.changeSummary.changes
      .slice(0, authorReleaseChangePreviewCount)
      .map(formatReleaseChangeKey)
      .concat(
        String(releaseHistoryChange?.changeSummary.actionNeededCount ?? 0),
        selectedEntryId ?? '',
        selectedFieldId ?? '',
      )
      .join('|') ?? '';
  const [showAllChanges, setShowAllChanges] = useState(false);
  useEffect(() => {
    setShowAllChanges(false);
  }, [releaseChangePreviewKey]);

  if (releaseHistoryChange == null) {
    return null;
  }
  const exactChanges = releaseHistoryChange.changeSummary.changes;
  const actionNeededChangeCount =
    releaseHistoryChange.changeSummary.actionNeededCount ??
    exactChanges.filter(isReleaseChangeActionNeeded).length;
  const compactPreviewChanges = getCompactReleaseChangePreview(
    exactChanges,
    selectedEntryId,
    selectedFieldId,
  );
  const hiddenExactChangeCount = Math.max(exactChanges.length - compactPreviewChanges.length, 0);
  const hiddenActionNeededChangeCount = Math.max(
    actionNeededChangeCount - exactChanges.filter(isReleaseChangeActionNeeded).length,
    0,
  );
  const visibleChanges = showAllChanges ? exactChanges : compactPreviewChanges;
  const visibleChangeGroups = groupReleaseChanges(visibleChanges, selectedEntryId);

  return (
    <div className="content-cms-admin-page__release-change">
      <span className="content-cms-admin-page__eyebrow">Changed since last release</span>
      {exactChanges.length > 0 ? (
        <>
          <div
            id={cmsAuthorReleaseChangeGroupsId}
            className="content-cms-admin-page__release-change-groups"
          >
            {visibleChangeGroups.map((releaseChangeGroup) => {
              const contentItemChangeGroups =
                releaseChangeGroup.kind === 'content-item'
                  ? groupContentItemReleaseChanges(
                      releaseChangeGroup.changes,
                      selectedEntryId,
                      selectedFieldId,
                    )
                  : [];
              const shouldGroupContentItemChanges =
                shouldGroupContentItemReleaseChanges(contentItemChangeGroups);
              return (
                <section
                  key={releaseChangeGroup.key}
                  className="content-cms-admin-page__release-change-group"
                  aria-label={releaseChangeGroup.label}
                >
                  <div className="content-cms-admin-page__release-change-group-header">
                    <div className="content-cms-admin-page__release-change-group-title">
                      <h4>{releaseChangeGroup.label}</h4>
                      {isSelectedReleaseChangeGroup(releaseChangeGroup, selectedEntryId) ? (
                        <span className="content-cms-admin-page__release-change-current">
                          Current content item
                        </span>
                      ) : null}
                    </div>
                    <span>{formatReleaseChangeSummary(releaseChangeGroup.changes)}</span>
                  </div>
                  {shouldGroupContentItemChanges ? (
                    <div className="content-cms-admin-page__release-change-subgroups">
                      {contentItemChangeGroups.map((contentItemChangeGroup) => (
                        <div
                          key={contentItemChangeGroup.key}
                          className="content-cms-admin-page__release-change-subgroup"
                          role="group"
                          aria-label={`${contentItemChangeGroup.label} changes`}
                        >
                          <div className="content-cms-admin-page__release-change-subgroup-header">
                            <div className="content-cms-admin-page__release-change-subgroup-title">
                              <strong>{contentItemChangeGroup.label}</strong>
                              {isSelectedContentItemReleaseChangeGroup(
                                contentItemChangeGroup,
                                selectedEntryId,
                                selectedFieldId,
                              ) ? (
                                <span className="content-cms-admin-page__release-change-current">
                                  Current field
                                </span>
                              ) : null}
                            </div>
                            <span>
                              {formatReleaseChangeSummary(contentItemChangeGroup.changes)}
                            </span>
                          </div>
                          <ReleaseChangeList
                            releaseChanges={contentItemChangeGroup.changes}
                            resolveLocaleDisplayName={resolveLocaleDisplayName}
                            onReviewChangedEntry={onReviewChangedEntry}
                            prioritizeActionNeeded={contentItemChangeGroup.kind === 'field'}
                            formatScope={{
                              omitEntryName: true,
                              omitFieldName: contentItemChangeGroup.kind === 'field',
                            }}
                          />
                        </div>
                      ))}
                    </div>
                  ) : (
                    <ReleaseChangeList
                      releaseChanges={releaseChangeGroup.changes}
                      resolveLocaleDisplayName={resolveLocaleDisplayName}
                      onReviewChangedEntry={onReviewChangedEntry}
                      formatScope={{
                        omitEntryName: releaseChangeGroup.kind === 'content-item',
                      }}
                    />
                  )}
                </section>
              );
            })}
          </div>
          {hiddenExactChangeCount > 0 ? (
            <button
              type="button"
              className="content-cms-admin-page__release-change-toggle"
              aria-expanded={showAllChanges}
              aria-controls={cmsAuthorReleaseChangeGroupsId}
              onClick={() => setShowAllChanges((current) => !current)}
            >
              {showAllChanges
                ? 'Show fewer changes'
                : `Show ${hiddenExactChangeCount} more ${
                    hiddenExactChangeCount === 1 ? 'change' : 'changes'
                  }`}
            </button>
          ) : null}
          {releaseHistoryChange.changeSummary.hasMore ? (
            <div className="content-cms-admin-page__release-change-overflow">
              <p className="settings-page__hint">
                {formatReleaseChangeOverflowHint(hiddenActionNeededChangeCount)}
              </p>
              {releaseHistoryChange.loadAllChanges ? (
                <button
                  type="button"
                  className="content-cms-admin-page__release-change-toggle"
                  aria-controls={cmsAuthorReleaseChangeGroupsId}
                  disabled={releaseHistoryChange.loadAllChangesPending}
                  onClick={() => {
                    setShowAllChanges(true);
                    releaseHistoryChange.loadAllChanges?.();
                  }}
                >
                  {releaseHistoryChange.loadAllChangesPending
                    ? 'Loading every change...'
                    : formatLoadEveryChangeLabel(hiddenActionNeededChangeCount)}
                </button>
              ) : null}
              {releaseHistoryChange.loadAllChangesError ? (
                <p className="settings-page__error" role="alert">
                  {releaseHistoryChange.loadAllChangesError}
                </p>
              ) : null}
            </div>
          ) : null}
        </>
      ) : (
        <p className="settings-page__hint">{releaseHistoryChange.message}</p>
      )}
    </div>
  );
}

type ReleaseChangeReviewTarget = {
  entryId: string;
  fieldId: string | null;
  localeTag: string | null;
  repairTarget: ReleaseRepairTarget | null;
  actionLabel: string;
  actionAriaLabel: string;
};

type ReleaseChangeGroup = {
  key: string;
  label: string;
  kind: 'content-item' | 'release-language' | 'release-details';
  changes: ApiCmsReleaseChange[];
};

type ContentItemReleaseChangeGroup = {
  key: string;
  label: string;
  kind: 'content-item' | 'field';
  changes: ApiCmsReleaseChange[];
};

type ReleaseBlockedEntryGroup = {
  key: string;
  entryId: string;
  entryName: string;
  blockedEntries: ReleaseBlockedEntry[];
};

type ReleaseChangeFormatScope = {
  omitEntryName?: boolean;
  omitFieldName?: boolean;
};

function ReleaseChangeList({
  releaseChanges,
  resolveLocaleDisplayName,
  onReviewChangedEntry,
  prioritizeActionNeeded = false,
  formatScope,
}: {
  releaseChanges: ApiCmsReleaseChange[];
  resolveLocaleDisplayName: (localeTag: string) => string;
  onReviewChangedEntry?: (
    entryId: string,
    fieldId?: string | null,
    localeTag?: string | null,
    repairTarget?: ReleaseRepairTarget | null,
    reviewReason?: string | null,
    reviewReturnMode?: ReleaseReviewReturnMode | null,
    lastReleasedSourceContent?: string | null,
    lastReleasedTranslationContent?: string | null,
  ) => void;
  prioritizeActionNeeded?: boolean;
  formatScope?: ReleaseChangeFormatScope;
}) {
  const orderedReleaseChanges = prioritizeActionNeeded
    ? sortReleaseChangesByAuthorPriority(releaseChanges)
    : releaseChanges;
  return (
    <ul className="content-cms-admin-page__release-change-list">
      {orderedReleaseChanges.map((releaseChange) => {
        const reviewTarget = getReleaseChangeReviewTarget(releaseChange, resolveLocaleDisplayName);
        const releaseChangeCopy = formatReleaseChange(
          releaseChange,
          resolveLocaleDisplayName,
          formatScope,
        );
        const releaseChangeContent = (
          <>
            <span className="content-cms-admin-page__release-change-copy">{releaseChangeCopy}</span>
            {reviewTarget && onReviewChangedEntry ? (
              <span className="content-cms-admin-page__release-change-action">
                {reviewTarget.actionLabel}
              </span>
            ) : null}
          </>
        );
        return (
          <li key={formatReleaseChangeKey(releaseChange)}>
            {reviewTarget && onReviewChangedEntry ? (
              <button
                type="button"
                className={getReleaseChangeItemClassName(releaseChange)}
                aria-label={reviewTarget.actionAriaLabel}
                onClick={() =>
                  onReviewChangedEntry(
                    reviewTarget.entryId,
                    reviewTarget.fieldId,
                    reviewTarget.localeTag,
                    reviewTarget.repairTarget,
                    releaseChangeCopy,
                    isReleaseChangeActionNeeded(releaseChange) ? 'repair' : 'review',
                    releaseChange.lastReleasedSourceContent ?? null,
                    releaseChange.lastReleasedTranslationContent ?? null,
                  )
                }
              >
                {releaseChangeContent}
              </button>
            ) : (
              <span className={getReleaseChangeItemClassName(releaseChange)}>
                {releaseChangeContent}
              </span>
            )}
          </li>
        );
      })}
    </ul>
  );
}

function getReleaseChangeReviewTarget(
  releaseChange: ApiCmsReleaseChange,
  resolveLocaleDisplayName: (localeTag: string) => string,
): ReleaseChangeReviewTarget | null {
  if (releaseChange.kind === 'CONTENT_ITEM_REMOVED' || releaseChange.kind === 'FIELD_REMOVED') {
    return null;
  }
  if (releaseChange.entryId == null) {
    return null;
  }
  const entryId = String(releaseChange.entryId);
  const entryName = releaseChange.entryName ?? 'this content item';
  if (releaseChange.fieldId == null) {
    return {
      entryId,
      fieldId: null,
      localeTag: null,
      repairTarget: null,
      actionLabel: 'Review',
      actionAriaLabel: `Review ${entryName}`,
    };
  }
  const fieldId = String(releaseChange.fieldId);
  const fieldName = releaseChange.fieldName ?? 'this field';
  if (
    releaseChange.kind === 'TRANSLATION_CHANGED' ||
    releaseChange.kind === 'TRANSLATION_NEEDED' ||
    releaseChange.kind === 'TRANSLATION_NEEDS_REVIEW'
  ) {
    const localeLabel =
      releaseChange.localeTag == null
        ? 'translation'
        : `${resolveLocaleDisplayName(releaseChange.localeTag)} translation`;
    return {
      entryId,
      fieldId,
      localeTag: releaseChange.localeTag ?? null,
      repairTarget: 'translation',
      actionLabel: releaseChange.kind === 'TRANSLATION_NEEDED' ? 'Write' : 'Review',
      actionAriaLabel:
        releaseChange.kind === 'TRANSLATION_NEEDED'
          ? `Write ${localeLabel} in ${fieldName} for ${entryName}`
          : `Review ${localeLabel} in ${fieldName} for ${entryName}`,
    };
  }
  return {
    entryId,
    fieldId,
    localeTag: null,
    repairTarget: releaseChange.kind === 'SOURCE_COPY_CHANGED' ? 'source-copy' : null,
    actionLabel: 'Review',
    actionAriaLabel:
      releaseChange.kind === 'SOURCE_COPY_CHANGED'
        ? `Review source copy in ${fieldName} for ${entryName}`
        : `Review ${fieldName} in ${entryName}`,
  };
}

function groupReleaseChanges(releaseChanges: ApiCmsReleaseChange[], selectedEntryId?: string) {
  const releaseChangeGroups = new Map<string, ReleaseChangeGroup>();
  releaseChanges.forEach((releaseChange) => {
    const releaseChangeGroup = getReleaseChangeGroup(releaseChange);
    const existingGroup = releaseChangeGroups.get(releaseChangeGroup.key);
    if (existingGroup) {
      existingGroup.changes.push(releaseChange);
      return;
    }
    releaseChangeGroups.set(releaseChangeGroup.key, {
      ...releaseChangeGroup,
      changes: [releaseChange],
    });
  });
  return [...releaseChangeGroups.values()].sort(
    (left, right) =>
      Number(isSelectedReleaseChangeGroup(right, selectedEntryId)) -
      Number(isSelectedReleaseChangeGroup(left, selectedEntryId)),
  );
}

function groupReleaseBlockedEntries(
  blockedEntries: ReleaseBlockedEntry[],
  selectedEntryId?: string,
) {
  const blockedEntryGroups = new Map<string, ReleaseBlockedEntryGroup>();
  blockedEntries.forEach((blockedEntry) => {
    const entryId = String(blockedEntry.entryId);
    const existingGroup = blockedEntryGroups.get(entryId);
    if (existingGroup) {
      existingGroup.blockedEntries.push(blockedEntry);
      return;
    }
    blockedEntryGroups.set(entryId, {
      key: entryId,
      entryId,
      entryName: blockedEntry.entryName,
      blockedEntries: [blockedEntry],
    });
  });
  return [...blockedEntryGroups.values()]
    .map((blockedEntryGroup, index) => ({ blockedEntryGroup, index }))
    .sort(
      (left, right) =>
        getReleaseBlockedEntryGroupPriority(left.blockedEntryGroup) -
          getReleaseBlockedEntryGroupPriority(right.blockedEntryGroup) ||
        Number(right.blockedEntryGroup.entryId === selectedEntryId) -
          Number(left.blockedEntryGroup.entryId === selectedEntryId) ||
        left.index - right.index,
    )
    .map(({ blockedEntryGroup }) => blockedEntryGroup);
}

function sortReleaseBlockedEntries(
  blockedEntries: ReleaseBlockedEntry[],
  selectedFieldId?: string,
) {
  return blockedEntries
    .map((blockedEntry, index) => ({ blockedEntry, index }))
    .sort(
      (left, right) =>
        getReleaseBlockedEntryPriority(left.blockedEntry) -
          getReleaseBlockedEntryPriority(right.blockedEntry) ||
        Number(
          right.blockedEntry.fieldId != null &&
            String(right.blockedEntry.fieldId) === selectedFieldId,
        ) -
          Number(
            left.blockedEntry.fieldId != null &&
              String(left.blockedEntry.fieldId) === selectedFieldId,
          ) ||
        left.index - right.index,
    )
    .map(({ blockedEntry }) => blockedEntry);
}

function getReleaseBlockedEntryGroupPriority(blockedEntryGroup: ReleaseBlockedEntryGroup) {
  return Math.min(...blockedEntryGroup.blockedEntries.map(getReleaseBlockedEntryPriority));
}

function getReleaseBlockedEntryPriority(blockedEntry: Pick<ReleaseBlockedEntry, 'action'>) {
  switch (blockedEntry.action) {
    case 'fix-source-copy':
      return 0;
    case 'open-translation':
    case 'write-translation':
      return 1;
    case 'review-translation':
      return 2;
  }
}

function formatReleaseBlockedEntryCount(blockedEntries: ReleaseBlockedEntry[]) {
  const fieldBlockerCount = blockedEntries.filter(
    (blockedEntry) => blockedEntry.fieldName != null,
  ).length;
  if (fieldBlockerCount === blockedEntries.length) {
    return fieldBlockerCount === 1 ? '1 field needs work' : `${fieldBlockerCount} fields need work`;
  }
  return blockedEntries.length === 1
    ? '1 issue needs work'
    : `${blockedEntries.length} issues need work`;
}

function getReleaseBlockerActionLabel(action: ReleaseRepairAction) {
  if (action === 'fix-source-copy') {
    return 'Fix source copy';
  }
  if (action === 'open-translation') {
    return 'Open translation';
  }
  return action === 'review-translation' ? 'Review translation' : 'Write translation';
}

function getReleaseBlockerActionAriaLabel(
  blockedEntry: ReleaseBlockedEntry,
  resolveLocaleDisplayName: (localeTag: string) => string,
) {
  const repairLocation =
    blockedEntry.fieldName == null
      ? blockedEntry.entryName
      : `${blockedEntry.fieldName} for ${blockedEntry.entryName}`;
  if (blockedEntry.action === 'fix-source-copy') {
    return `Fix source copy in ${repairLocation}`;
  }
  const localeLabel =
    blockedEntry.localeTag == null ? null : resolveLocaleDisplayName(blockedEntry.localeTag);
  const translationAction =
    blockedEntry.action === 'open-translation'
      ? 'Open'
      : blockedEntry.action === 'review-translation'
        ? 'Review'
        : 'Write';
  return `${translationAction}${localeLabel == null ? '' : ` ${localeLabel}`} translation in ${repairLocation}`;
}

function addInlineTranslationReleaseBlockers(
  readiness: ProjectReleaseReadiness | null,
  inlineTranslationReleaseBlockers: ReleaseBlockedEntry[],
) {
  if (inlineTranslationReleaseBlockers.length === 0) {
    return readiness;
  }
  const duplicateBlocker = (blockedEntry: ReleaseBlockedEntry) =>
    inlineTranslationReleaseBlockers.some(
      (inlineTranslationReleaseBlocker) =>
        blockedEntry.entryId === inlineTranslationReleaseBlocker.entryId &&
        blockedEntry.fieldId === inlineTranslationReleaseBlocker.fieldId &&
        blockedEntry.localeTag === inlineTranslationReleaseBlocker.localeTag,
    );
  return {
    ready: false,
    hint:
      inlineTranslationReleaseBlockers.length === 1
        ? inlineTranslationReleaseBlockers[0].hint
        : `${inlineTranslationReleaseBlockers.length} approved translations need refreshing before release.`,
    blockedEntries: [
      ...inlineTranslationReleaseBlockers,
      ...(readiness?.blockedEntries.filter((blockedEntry) => !duplicateBlocker(blockedEntry)) ??
        []),
    ],
  };
}

function getInlineTranslationReleaseBlocker(
  blockerTarget: InlineTranslationReleaseBlockerTarget,
  entries: ApiCmsEntry[],
  contentTypes: ApiCmsContentType[],
  resolveLocaleDisplayName: (localeTag: string) => string,
): ReleaseBlockedEntry {
  const entry =
    entries.find((currentEntry) => String(currentEntry.id) === blockerTarget.entryId) ?? null;
  const field =
    contentTypes
      .flatMap((contentType) => contentType.fields)
      .find((currentField) => String(currentField.id) === blockerTarget.fieldId) ?? null;
  const localeLabel = resolveLocaleDisplayName(blockerTarget.localeTag);
  return {
    action: 'open-translation',
    entryId: Number(blockerTarget.entryId),
    entryName: entry?.name ?? 'Current content item',
    fieldId: Number(blockerTarget.fieldId),
    fieldName: field?.name ?? null,
    hint: `${localeLabel} translation is unavailable. Refresh it before release.`,
    localeTag: blockerTarget.localeTag,
    repairTarget: 'translation',
  };
}

function formatHiddenReleaseBlockedEntryCount(blockedEntries: ReleaseBlockedEntry[]) {
  const fieldBlockerCount = blockedEntries.filter(
    (blockedEntry) => blockedEntry.fieldName != null,
  ).length;
  if (fieldBlockerCount === blockedEntries.length) {
    return fieldBlockerCount === 1 ? 'Show 1 more field' : `Show ${fieldBlockerCount} more fields`;
  }
  return blockedEntries.length === 1
    ? 'Show 1 more issue'
    : `Show ${blockedEntries.length} more issues`;
}

function formatCollapsedReleaseBlockedEntryLabel(blockedEntries: ReleaseBlockedEntry[]) {
  return blockedEntries.every((blockedEntry) => blockedEntry.fieldName != null)
    ? 'Show fewer fields'
    : 'Show fewer issues';
}

function groupContentItemReleaseChanges(
  releaseChanges: ApiCmsReleaseChange[],
  selectedEntryId?: string,
  selectedFieldId?: string,
) {
  const contentItemReleaseChangeGroups = new Map<string, ContentItemReleaseChangeGroup>();
  releaseChanges.forEach((releaseChange) => {
    const contentItemReleaseChangeGroup = getContentItemReleaseChangeGroup(releaseChange);
    const existingGroup = contentItemReleaseChangeGroups.get(contentItemReleaseChangeGroup.key);
    if (existingGroup) {
      existingGroup.changes.push(releaseChange);
      return;
    }
    contentItemReleaseChangeGroups.set(contentItemReleaseChangeGroup.key, {
      ...contentItemReleaseChangeGroup,
      changes: [releaseChange],
    });
  });
  return [...contentItemReleaseChangeGroups.values()].sort(
    (left, right) =>
      Number(isSelectedContentItemReleaseChangeGroup(right, selectedEntryId, selectedFieldId)) -
      Number(isSelectedContentItemReleaseChangeGroup(left, selectedEntryId, selectedFieldId)),
  );
}

function shouldGroupContentItemReleaseChanges(
  contentItemReleaseChangeGroups: ContentItemReleaseChangeGroup[],
) {
  return (
    contentItemReleaseChangeGroups.length > 1 ||
    contentItemReleaseChangeGroups.some(
      (contentItemReleaseChangeGroup) =>
        contentItemReleaseChangeGroup.kind === 'field' &&
        contentItemReleaseChangeGroup.changes.length > 1,
    )
  );
}

function sortReleaseChangesByAuthorPriority(releaseChanges: ApiCmsReleaseChange[]) {
  return releaseChanges
    .map((releaseChange, index) => ({ releaseChange, index }))
    .sort(
      (left, right) =>
        getReleaseChangeAuthorPriority(left.releaseChange) -
          getReleaseChangeAuthorPriority(right.releaseChange) || left.index - right.index,
    )
    .map(({ releaseChange }) => releaseChange);
}

function getCompactReleaseChangePreview(
  releaseChanges: ApiCmsReleaseChange[],
  selectedEntryId?: string,
  selectedFieldId?: string,
) {
  const authorOrderedReleaseChanges = sortReleaseChangesByAuthorContext(
    releaseChanges,
    selectedEntryId,
    selectedFieldId,
  );
  const actionNeededChanges = authorOrderedReleaseChanges.filter(isReleaseChangeActionNeeded);
  const compactPreviewChangeCount = Math.max(
    authorReleaseChangePreviewCount,
    actionNeededChanges.length,
  );
  const previewChanges = [...actionNeededChanges];
  const previewChangeKeys = new Set(previewChanges.map(formatReleaseChangeKey));
  authorOrderedReleaseChanges.forEach((releaseChange) => {
    if (previewChanges.length >= compactPreviewChangeCount) {
      return;
    }
    const releaseChangeKey = formatReleaseChangeKey(releaseChange);
    if (previewChangeKeys.has(releaseChangeKey)) {
      return;
    }
    previewChanges.push(releaseChange);
    previewChangeKeys.add(releaseChangeKey);
  });
  return previewChanges;
}

function sortReleaseChangesByAuthorContext(
  releaseChanges: ApiCmsReleaseChange[],
  selectedEntryId?: string,
  selectedFieldId?: string,
) {
  return releaseChanges
    .map((releaseChange, index) => ({ releaseChange, index }))
    .sort(
      (left, right) =>
        getReleaseChangeAuthorPriority(left.releaseChange) -
          getReleaseChangeAuthorPriority(right.releaseChange) ||
        getReleaseChangeSelectionPriority(left.releaseChange, selectedEntryId, selectedFieldId) -
          getReleaseChangeSelectionPriority(
            right.releaseChange,
            selectedEntryId,
            selectedFieldId,
          ) ||
        left.index - right.index,
    )
    .map(({ releaseChange }) => releaseChange);
}

function getReleaseChangeAuthorPriority(releaseChange: ApiCmsReleaseChange) {
  return isReleaseChangeActionNeeded(releaseChange) ? 0 : 1;
}

function getReleaseChangeSelectionPriority(
  releaseChange: ApiCmsReleaseChange,
  selectedEntryId?: string,
  selectedFieldId?: string,
) {
  if (
    selectedFieldId != null &&
    (selectedEntryId == null ||
      (releaseChange.entryId != null && String(releaseChange.entryId) === selectedEntryId)) &&
    releaseChange.fieldId != null &&
    String(releaseChange.fieldId) === selectedFieldId
  ) {
    return 0;
  }
  if (
    selectedEntryId != null &&
    releaseChange.entryId != null &&
    String(releaseChange.entryId) === selectedEntryId
  ) {
    return 1;
  }
  return 2;
}

function isReleaseChangeActionNeeded(releaseChange: ApiCmsReleaseChange) {
  return (
    releaseChange.kind === 'TRANSLATION_NEEDED' || releaseChange.kind === 'TRANSLATION_NEEDS_REVIEW'
  );
}

function isSelectedReleaseChangeGroup(
  releaseChangeGroup: ReleaseChangeGroup,
  selectedEntryId?: string,
) {
  return (
    selectedEntryId != null &&
    releaseChangeGroup.kind === 'content-item' &&
    releaseChangeGroup.changes.some(
      (releaseChange) =>
        releaseChange.entryId != null && String(releaseChange.entryId) === selectedEntryId,
    )
  );
}

function isSelectedContentItemReleaseChangeGroup(
  contentItemChangeGroup: ContentItemReleaseChangeGroup,
  selectedEntryId?: string,
  selectedFieldId?: string,
) {
  return (
    selectedEntryId != null &&
    selectedFieldId != null &&
    contentItemChangeGroup.kind === 'field' &&
    contentItemChangeGroup.changes.some(
      (releaseChange) =>
        releaseChange.entryId != null &&
        String(releaseChange.entryId) === selectedEntryId &&
        releaseChange.fieldId != null &&
        String(releaseChange.fieldId) === selectedFieldId,
    )
  );
}

function getReleaseChangeItemClassName(releaseChange: ApiCmsReleaseChange) {
  return [
    'content-cms-admin-page__release-change-item',
    isReleaseChangeActionNeeded(releaseChange) ? 'is-action-needed' : '',
  ]
    .filter(Boolean)
    .join(' ');
}

function formatReleaseChangeSummary(releaseChanges: ApiCmsReleaseChange[]) {
  const actionNeededChangeCount = releaseChanges.filter(isReleaseChangeActionNeeded).length;
  if (actionNeededChangeCount === 0) {
    return formatReleaseChangeCount(releaseChanges.length);
  }
  return `${formatReleaseChangeCount(releaseChanges.length)} · ${formatReleaseActionNeededCount(
    actionNeededChangeCount,
  )}`;
}

function formatReleaseActionNeededCount(changeCount: number) {
  return changeCount === 1 ? '1 needs action' : `${changeCount} need action`;
}

function formatReleaseChangeOverflowHint(hiddenActionNeededChangeCount: number) {
  if (hiddenActionNeededChangeCount === 0) {
    return 'More release changes are included. Load every change to review them here.';
  }
  return hiddenActionNeededChangeCount === 1
    ? '1 more change needs action. Load every change to review it here.'
    : `${hiddenActionNeededChangeCount} more changes need action. Load every change to review them here.`;
}

function formatLoadEveryChangeLabel(hiddenActionNeededChangeCount: number) {
  return hiddenActionNeededChangeCount === 0
    ? 'Load every change'
    : `Load every change · ${formatReleaseActionNeededCount(hiddenActionNeededChangeCount)}`;
}

function formatAuthorReleaseDetailsSummary(
  includedEntries: ApiCmsEntry[],
  releaseHistoryChange?: ReleaseHistoryChange | null,
) {
  const releaseDetailSegments = [
    includedEntries.length === 1
      ? '1 content item included'
      : `${includedEntries.length} content items included`,
  ];
  if (releaseHistoryChange == null) {
    return releaseDetailSegments.join(' · ');
  }
  const releaseChangeCount = releaseHistoryChange.changeSummary.changes.length;
  releaseDetailSegments.push(
    releaseHistoryChange.changeSummary.hasMore
      ? `${releaseChangeCount}+ changes since last release`
      : `${formatReleaseChangeCount(releaseChangeCount)} since last release`,
  );
  const actionNeededChangeCount =
    releaseHistoryChange.changeSummary.actionNeededCount ??
    releaseHistoryChange.changeSummary.changes.filter(isReleaseChangeActionNeeded).length;
  if (actionNeededChangeCount > 0) {
    releaseDetailSegments.push(formatReleaseActionNeededCount(actionNeededChangeCount));
  }
  return releaseDetailSegments.join(' · ');
}

function getReleaseChangeGroup(
  releaseChange: ApiCmsReleaseChange,
): Omit<ReleaseChangeGroup, 'changes'> {
  if (releaseChange.entryName != null) {
    return {
      key: `content-item:${releaseChange.entryId ?? releaseChange.entryName}`,
      label: releaseChange.entryName,
      kind: 'content-item',
    };
  }
  if (releaseChange.kind === 'LOCALE_ADDED' || releaseChange.kind === 'LOCALE_REMOVED') {
    return {
      key: 'release-languages',
      label: 'Release languages',
      kind: 'release-language',
    };
  }
  return {
    key: 'release-details',
    label: 'Release details',
    kind: 'release-details',
  };
}

function getContentItemReleaseChangeGroup(
  releaseChange: ApiCmsReleaseChange,
): Omit<ContentItemReleaseChangeGroup, 'changes'> {
  if (releaseChange.fieldName != null) {
    return {
      key: `field:${releaseChange.fieldName}`,
      label: releaseChange.fieldName,
      kind: 'field',
    };
  }
  return {
    key: 'content-item',
    label: 'Content item',
    kind: 'content-item',
  };
}

function formatReleaseChangeCount(changeCount: number) {
  return `${changeCount} ${changeCount === 1 ? 'change' : 'changes'}`;
}

function formatReleaseChange(
  releaseChange: ApiCmsReleaseChange,
  resolveLocaleDisplayName: (localeTag: string) => string,
  { omitEntryName = false, omitFieldName = false }: ReleaseChangeFormatScope = {},
) {
  const entryName = releaseChange.entryName ?? 'this content item';
  const fieldName = releaseChange.fieldName ?? 'this field';
  const localeLabel =
    releaseChange.localeTag == null
      ? 'This translation language'
      : resolveLocaleDisplayName(releaseChange.localeTag);

  switch (releaseChange.kind) {
    case 'LOCALE_ADDED':
      return `${localeLabel} was added to this release.`;
    case 'LOCALE_REMOVED':
      return `${localeLabel} was removed from this release.`;
    case 'CONTENT_ITEM_ADDED':
      return omitEntryName
        ? 'Included in this release.'
        : `${entryName} was included in this release.`;
    case 'CONTENT_ITEM_REMOVED':
      return omitEntryName
        ? 'Removed from this release.'
        : `${entryName} was removed from this release.`;
    case 'FIELD_ADDED':
      if (omitFieldName) {
        return 'Added to this content item.';
      }
      return omitEntryName ? `${fieldName} was added.` : `${fieldName} was added in ${entryName}.`;
    case 'FIELD_REMOVED':
      if (omitFieldName) {
        return 'Removed from this content item.';
      }
      return omitEntryName
        ? `${fieldName} was removed.`
        : `${fieldName} was removed from ${entryName}.`;
    case 'SOURCE_COPY_CHANGED':
      if (omitFieldName) {
        return 'Source copy changed.';
      }
      return omitEntryName
        ? `Source copy changed in ${fieldName}.`
        : `Source copy changed in ${fieldName} for ${entryName}.`;
    case 'TRANSLATION_CHANGED':
      if (omitFieldName) {
        return `${localeLabel} translation changed.`;
      }
      return omitEntryName
        ? `${localeLabel} translation changed in ${fieldName}.`
        : `${localeLabel} translation changed in ${fieldName} for ${entryName}.`;
    case 'TRANSLATION_NEEDED':
      if (omitFieldName) {
        return `${localeLabel} translation is needed.`;
      }
      return omitEntryName
        ? `${localeLabel} translation is needed in ${fieldName}.`
        : `${localeLabel} translation is needed in ${fieldName} for ${entryName}.`;
    case 'TRANSLATION_NEEDS_REVIEW':
      if (omitFieldName) {
        return `${localeLabel} needs review.`;
      }
      return omitEntryName
        ? `${localeLabel} needs review in ${fieldName}.`
        : `${localeLabel} needs review in ${fieldName} for ${entryName}.`;
    case 'RELEASE_SETUP_CHANGED':
      return 'Release setup changed.';
  }
}

function formatReleaseChangeKey(releaseChange: ApiCmsReleaseChange) {
  return [
    releaseChange.kind,
    releaseChange.entryId ?? '',
    releaseChange.entryName ?? '',
    releaseChange.fieldId ?? '',
    releaseChange.fieldName ?? '',
    releaseChange.localeTag ?? '',
  ].join(':');
}

function IncludedReleaseEntries({
  entries,
  selectedEntryId,
  onOpenIncludedEntry,
}: {
  entries: ApiCmsEntry[];
  selectedEntryId?: string;
  onOpenIncludedEntry?: (entryId: string, afterChange?: () => void) => void;
}) {
  const includedCount = entries.length;
  return (
    <div
      className="content-cms-admin-page__release-included"
      role="group"
      aria-label="Included content items"
    >
      <span className="content-cms-admin-page__eyebrow">Included in this release</span>
      <p className="settings-page__hint">
        {includedCount === 1
          ? '1 content item will release.'
          : `${includedCount} content items will release.`}
      </p>
      <div className="content-cms-admin-page__release-included-list">
        {entries.map((entry) => {
          const isSelected = String(entry.id) === selectedEntryId;
          const content = (
            <>
              <strong>{entry.name}</strong>
              <span className="content-cms-admin-page__release-included-state">
                {isSelected ? 'Current' : 'Open'}
              </span>
            </>
          );
          return onOpenIncludedEntry && !isSelected ? (
            <button
              key={entry.id}
              type="button"
              className="content-cms-admin-page__release-included-item"
              aria-label={`Open included content item ${entry.name}`}
              onClick={() => {
                const entryId = String(entry.id);
                onOpenIncludedEntry(entryId, () => {
                  queueCopyBlockEditorScroll(entryId);
                });
              }}
            >
              {content}
            </button>
          ) : (
            <div
              key={entry.id}
              className={`content-cms-admin-page__release-included-item${isSelected ? ' is-selected' : ''}`}
            >
              {content}
            </div>
          );
        })}
      </div>
    </div>
  );
}

function ReleaseRecoveryPanel({
  recoveryId,
  releaseRecovery,
  authorFacing = false,
}: {
  recoveryId: string;
  releaseRecovery?: ReleaseRecovery | null;
  authorFacing?: boolean;
}) {
  if (releaseRecovery == null) {
    return null;
  }

  return (
    <div
      id={recoveryId}
      className={`content-cms-admin-page__release-recovery is-${releaseRecovery.kind}`}
      role="alert"
    >
      <strong>
        {releaseRecovery.kind === 'retry'
          ? 'Release did not finish'
          : releaseRecovery.kind === 'repair-refresh'
            ? 'Saved repair needs another check'
            : authorFacing
              ? 'Release needs another check'
              : 'Check readiness again'}
      </strong>
      <p className="settings-page__hint">{releaseRecovery.message}</p>
    </div>
  );
}

function AuthoringRecoveryPanel({
  authoringRecovery,
  className = '',
}: {
  authoringRecovery?: AuthoringRecovery | null;
  className?: string;
}) {
  if (authoringRecovery == null) {
    return null;
  }

  const recoveryClassName = [
    'content-cms-admin-page__authoring-recovery',
    `is-${authoringRecovery.kind}`,
    className,
  ]
    .filter(Boolean)
    .join(' ');
  return (
    <div className={recoveryClassName} role="alert">
      <strong>
        {authoringRecovery.kind === 'source-copy'
          ? 'Source copy did not save'
          : authoringRecovery.kind === 'first-writing-space'
            ? 'Writing did not start'
            : authoringRecovery.kind === 'first-copy-block'
              ? 'First content item did not save'
              : authoringRecovery.kind === 'field-context'
                ? 'Placement details did not save'
                : authoringRecovery.kind === 'target-locales'
                  ? 'Translation languages did not save'
                  : authoringRecovery.kind === 'block-release'
                    ? 'Release step did not save'
                    : authoringRecovery.kind === 'block-details'
                      ? 'Copy details did not save'
                      : 'Content item did not save'}
      </strong>
      <p className="settings-page__hint">{authoringRecovery.message}</p>
    </div>
  );
}

function AuthoringRepairAction({
  actionLabel,
  message,
  onOpenAdvancedSetup,
  className = '',
}: {
  actionLabel: string;
  message: string;
  onOpenAdvancedSetup: () => void;
  className?: string;
}) {
  const recoveryClassName = ['content-cms-admin-page__setup-recovery', className]
    .filter(Boolean)
    .join(' ');

  return (
    <div className={recoveryClassName}>
      <p className="settings-page__hint">{message}</p>
      <button type="button" className="settings-button" onClick={() => onOpenAdvancedSetup()}>
        {actionLabel}
      </button>
    </div>
  );
}

function getProjectReleaseReadiness(
  result: ApiCmsProjectCompleteness,
  sourceLocale: string,
  entries: ApiCmsEntry[],
  contentTypes: ApiCmsContentType[],
  resolveLocaleDisplayName: (localeTag: string) => string,
): ProjectReleaseReadiness {
  const { readyTargetLocaleCount, totalTargetLocaleCount } = getTranslationReadiness(
    result.locales,
    sourceLocale,
  );
  if (result.complete) {
    return {
      ready: true,
      hint:
        totalTargetLocaleCount === 0
          ? 'Source copy is ready to release.'
          : `${formatTranslationLanguageApprovalCount(
              readyTargetLocaleCount,
              totalTargetLocaleCount,
            )}.`,
      blockedEntries: [],
    };
  }
  const blockedEntries = getReleaseBlockedEntries(
    result,
    sourceLocale,
    entries,
    contentTypes,
    resolveLocaleDisplayName,
  );
  return {
    ready: false,
    hint:
      (blockedEntries.length > 0 ? formatReleaseBlockedEntrySummary(blockedEntries) : null) ??
      getReleaseReadinessBlockerHint(result.locales, sourceLocale, resolveLocaleDisplayName) ??
      (totalTargetLocaleCount === 0
        ? 'Save required source copy before releasing.'
        : `${formatTranslationLanguageApprovalCount(
            readyTargetLocaleCount,
            totalTargetLocaleCount,
          )}; finish translation or review before release.`),
    blockedEntries,
  };
}

function getReleaseBlockedEntries(
  result: ApiCmsProjectCompleteness,
  sourceLocale: string,
  entries: ApiCmsEntry[],
  contentTypes: ApiCmsContentType[],
  resolveLocaleDisplayName: (localeTag: string) => string,
): ReleaseBlockedEntry[] {
  const includedEntriesById = new Map(
    entries.filter((entry) => entry.status === 'READY').map((entry) => [entry.id, entry]),
  );
  const contentTypesById = new Map(
    contentTypes.map((contentType) => [contentType.id, contentType]),
  );
  return result.entries.flatMap((entryCompleteness): ReleaseBlockedEntry[] => {
    const blocker = getReleaseReadinessBlocker(
      entryCompleteness.locales,
      sourceLocale,
      resolveLocaleDisplayName,
    );
    if (blocker == null) {
      return [];
    }
    const entry = includedEntriesById.get(entryCompleteness.entryId);
    if (entry == null) {
      return [];
    }
    const fieldBlockers = getReleaseBlockedFieldEntries(
      entryCompleteness,
      sourceLocale,
      contentTypesById.get(entry.contentTypeId)?.fields ?? [],
      resolveLocaleDisplayName,
    );
    if (fieldBlockers.length > 0) {
      return fieldBlockers.map((fieldBlocker) => ({
        entryId: entryCompleteness.entryId,
        entryName: entry.name,
        ...fieldBlocker,
      }));
    }
    return [
      {
        entryId: entryCompleteness.entryId,
        entryName: entry.name,
        fieldId: null,
        fieldName: null,
        ...blocker,
      },
    ];
  });
}

function getReleaseBlockedFieldEntries(
  entryCompleteness: ApiCmsEntryCompleteness,
  sourceLocale: string,
  fields: ApiCmsContentTypeField[],
  resolveLocaleDisplayName: (localeTag: string) => string,
): Pick<
  ReleaseBlockedEntry,
  'action' | 'fieldId' | 'fieldName' | 'hint' | 'localeTag' | 'repairTarget'
>[] {
  const localizableFieldsById = new Map(
    sortLocalizableFields(fields).map((field) => [field.id, field]),
  );
  return entryCompleteness.fields
    .map((fieldCompleteness) => {
      const field = localizableFieldsById.get(fieldCompleteness.fieldId);
      const blocker = getReleaseReadinessBlocker(
        fieldCompleteness.locales,
        sourceLocale,
        resolveLocaleDisplayName,
      );
      if (field == null || blocker == null) {
        return null;
      }
      return {
        fieldId: field.id,
        fieldName: field.name,
        ...blocker,
      };
    })
    .filter(
      (
        fieldBlocker,
      ): fieldBlocker is {
        fieldId: number;
        fieldName: string;
        action: ReleaseRepairAction;
        hint: string;
        localeTag: string | null;
        repairTarget: ReleaseRepairTarget;
      } => fieldBlocker != null,
    );
}

function formatReleaseBlockedEntrySummary(blockedEntries: ReleaseBlockedEntry[]) {
  const blockedEntryCount = new Set(blockedEntries.map((blockedEntry) => blockedEntry.entryId))
    .size;
  return blockedEntryCount === 1
    ? '1 included content item needs work before release.'
    : `${blockedEntryCount} included content items need work before release.`;
}

function PublishHistoryTable({
  snapshots,
  hasMoreSnapshots,
  isLoadingOlderSnapshots,
  disabled,
  onLoadOlderSnapshots,
}: {
  snapshots: ApiCmsProjectDetail['publishSnapshots'];
  hasMoreSnapshots: boolean;
  isLoadingOlderSnapshots: boolean;
  disabled: boolean;
  onLoadOlderSnapshots: () => void;
}) {
  return (
    <>
      <div className="settings-table-wrapper">
        <table className="settings-table">
          <thead>
            <tr>
              <th>Release</th>
              <th>Locales</th>
              <th>Size</th>
              <th>SHA-256</th>
              <th>Signing key</th>
              <th>Release signature</th>
              <th>Released by</th>
              <th>Released</th>
              <th>Release file</th>
            </tr>
          </thead>
          <tbody>
            {snapshots.length === 0 ? (
              <tr>
                <td colSpan={9}>No releases yet.</td>
              </tr>
            ) : (
              snapshots.map((snapshot) => (
                <tr key={snapshot.id}>
                  <td>v{snapshot.snapshotVersion}</td>
                  <td>{snapshot.localeTags.join(', ')}</td>
                  <td>{snapshot.artifactByteSize.toLocaleString()} B</td>
                  <td>
                    <code title={snapshot.artifactSha256}>
                      {snapshot.artifactSha256.slice(0, 12)}
                    </code>
                  </td>
                  <td>
                    <code>{snapshot.snapshotSigningKeyId}</code>
                  </td>
                  <td>
                    <code title={snapshot.artifactSignature}>
                      {snapshot.artifactSignature.slice(0, 12)}
                    </code>
                  </td>
                  <td>{snapshot.createdByUsername}</td>
                  <td>{formatDateTime(snapshot.publishedAt)}</td>
                  <td>
                    <a href={snapshot.artifactExportPath} target="_blank" rel="noreferrer">
                      JSON
                    </a>
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>
      {hasMoreSnapshots ? (
        <button
          type="button"
          className="settings-button"
          disabled={disabled || isLoadingOlderSnapshots}
          onClick={onLoadOlderSnapshots}
        >
          {isLoadingOlderSnapshots ? 'Loading older releases...' : 'Load older releases'}
        </button>
      ) : null}
    </>
  );
}

function CompletenessTable({ result }: { result: ApiCmsProjectCompleteness }) {
  const scopeRows = [
    ...result.locales.map((locale) => ({ scope: 'Release', locale })),
    ...result.entries.flatMap((entry) =>
      entry.locales.map((locale) => ({ scope: entry.entryKey, locale })),
    ),
  ];
  return (
    <div>
      <p className="settings-page__hint">
        Release ready: {formatBool(result.complete)} for {result.localeTags.join(', ')}.
      </p>
      <p className="settings-page__hint">
        Release size: {result.publishPackageByteSize.toLocaleString()} B.
      </p>
      <div className="settings-table-wrapper">
        <table className="settings-table settings-table--nested">
          <thead>
            <tr>
              <th>Scope</th>
              <th>Locale</th>
              <th>Approved</th>
              <th>Missing</th>
              <th>Review needed</th>
              <th>Translation needed</th>
              <th>Complete</th>
            </tr>
          </thead>
          <tbody>
            {scopeRows.map(({ scope, locale }) => (
              <CompletenessRow key={`${scope}-${locale.localeTag}`} scope={scope} locale={locale} />
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function CompletenessRow({ scope, locale }: { scope: string; locale: ApiCmsLocaleCompleteness }) {
  return (
    <tr>
      <td>{scope}</td>
      <td>{locale.localeTag}</td>
      <td>
        {locale.approvedFields}/{locale.totalFields}
      </td>
      <td>{locale.missingFields}</td>
      <td>{locale.reviewNeededFields}</td>
      <td>{locale.translationNeededFields}</td>
      <td>{formatBool(locale.complete)}</td>
    </tr>
  );
}

export function ContentTypesTable({ contentTypes }: { contentTypes: ApiCmsContentType[] }) {
  return (
    <section className="settings-card content-cms-admin-page__debug-record-card">
      <div className="content-cms-admin-page__debug-record-header">
        <span className="content-cms-admin-page__eyebrow">Read-only records</span>
        <h2>Block shape and copy piece records</h2>
        <p className="settings-page__hint">Exact saved structure for debugging.</p>
      </div>
      <div className="settings-table-wrapper">
        <table className="settings-table" aria-label="Read-only block shape and copy piece records">
          <thead>
            <tr>
              <th>Block shape</th>
              <th>Version</th>
              <th>Copy piece</th>
              <th>Copy format</th>
              <th>Required</th>
            </tr>
          </thead>
          <tbody>
            {contentTypes.length === 0 ? (
              <tr>
                <td colSpan={5}>No block shapes.</td>
              </tr>
            ) : (
              contentTypes.flatMap((contentType) => {
                if (contentType.fields.length === 0) {
                  return [
                    <tr key={`${contentType.id}-empty`}>
                      <td>{contentType.name}</td>
                      <td>{contentType.schemaVersion}</td>
                      <td colSpan={3}>No copy pieces.</td>
                    </tr>,
                  ];
                }
                return contentType.fields.map((field) => (
                  <tr key={field.id}>
                    <td>{contentType.name}</td>
                    <td>{contentType.schemaVersion}</td>
                    <td>
                      {field.name} <span className="settings-note">({field.fieldKey})</span>
                    </td>
                    <td>{field.fieldType}</td>
                    <td>{formatBool(field.required)}</td>
                  </tr>
                ));
              })
            )}
          </tbody>
        </table>
      </div>
    </section>
  );
}

export function EntriesTable({
  entries,
  contentTypes,
}: {
  entries: ApiCmsEntry[];
  contentTypes: ApiCmsContentType[];
}) {
  return (
    <section className="settings-card content-cms-admin-page__debug-record-card">
      <div className="content-cms-admin-page__debug-record-header">
        <span className="content-cms-admin-page__eyebrow">Read-only records</span>
        <h2>Copy block, experiment path, and Mojito link records</h2>
        <p className="settings-page__hint">Exact saved delivery links for debugging.</p>
      </div>
      <div className="settings-table-wrapper">
        <table
          className="settings-table"
          aria-label="Read-only copy block, experiment path, and Mojito link records"
        >
          <thead>
            <tr>
              <th>Copy block</th>
              <th>Block shape</th>
              <th>Release state</th>
              <th>Experiment path</th>
              <th>Experiment group</th>
              <th>Mojito links</th>
            </tr>
          </thead>
          <tbody>
            {entries.length === 0 ? (
              <tr>
                <td colSpan={6}>No copy blocks.</td>
              </tr>
            ) : (
              entries.flatMap((entry) => {
                const contentType = findContentType(contentTypes, entry.contentTypeId);
                if (entry.variants.length === 0) {
                  return [
                    <tr key={`${entry.id}-empty`}>
                      <td>{entry.name}</td>
                      <td>{contentType?.name ?? entry.contentTypeId}</td>
                      <td>{entry.status}</td>
                      <td colSpan={3}>No variants.</td>
                    </tr>,
                  ];
                }
                return entry.variants.map((variant) => (
                  <tr key={variant.id}>
                    <td>
                      {entry.name} <span className="settings-note">({entry.entryKey})</span>
                    </td>
                    <td>{contentType?.name ?? entry.contentTypeId}</td>
                    <td>{entry.status}</td>
                    <td>
                      {variant.name} <span className="settings-note">({variant.variantKey})</span>
                      <div className="settings-note">{variant.status}</div>
                    </td>
                    <td>{variant.candidateGroupKey ?? 'None'}</td>
                    <td>
                      {variant.fieldMappings.length === 0 ? (
                        'No links.'
                      ) : (
                        <ul className="content-cms-admin-page__mapping-list">
                          {variant.fieldMappings.map((mapping) => (
                            <li key={mapping.id}>
                              <strong>{mapping.fieldKey}</strong>
                              <span>{mapping.stringId}</span>
                              <Link
                                to={`/text-units/${encodeURIComponent(
                                  String(mapping.tmTextUnitId),
                                )}`}
                              >
                                Text unit #{mapping.tmTextUnitId}
                              </Link>
                            </li>
                          ))}
                        </ul>
                      )}
                    </td>
                  </tr>
                ));
              })
            )}
          </tbody>
        </table>
      </div>
    </section>
  );
}
