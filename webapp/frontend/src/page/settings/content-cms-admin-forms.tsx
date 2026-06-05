import { type FormEvent } from 'react';
import { Link } from 'react-router-dom';

import {
  type ApiCmsContentType,
  type ApiCmsEntry,
  type ApiCmsEntryStatus,
  type ApiCmsFieldMapping,
  type ApiCmsFieldType,
  type ApiCmsLocaleCompleteness,
  type ApiCmsProjectCompleteness,
  type ApiCmsProjectDetail,
  type ApiCmsProjectSummary,
  type ApiCmsVariant,
  type ApiCmsVariantStatus,
} from '../../api/content-cms';
import { type ApiRepository } from '../../api/repositories';
import { formatLocalDateTime as formatDateTime } from '../../utils/dateTime';
import {
  type ContentTypeDraft,
  type EntryDraft,
  type FieldDraft,
  findContentType,
  formatBool,
  type MappingDraft,
  type MappingSource,
  type ProjectDraft,
  type ProjectEditDraft,
  type VariantDraft,
} from './content-cms-admin-types';

export function ProjectList({
  projects,
  selectedProjectId,
  isLoading,
  isError,
  onSelect,
}: {
  projects: ApiCmsProjectSummary[];
  selectedProjectId: number | null;
  isLoading: boolean;
  isError: boolean;
  onSelect: (projectId: number) => void;
}) {
  if (isLoading) {
    return <p className="settings-page__hint">Loading projects.</p>;
  }
  if (isError) {
    return <p className="settings-hint is-error">Failed to load projects.</p>;
  }
  if (projects.length === 0) {
    return <p className="settings-page__hint">No content projects yet.</p>;
  }

  return (
    <div className="content-cms-admin-page__project-list">
      {projects.map((project) => (
        <button
          key={project.id}
          type="button"
          className={`content-cms-admin-page__project-row${
            selectedProjectId === project.id ? ' is-active' : ''
          }`}
          onClick={() => onSelect(project.id)}
        >
          <span className="content-cms-admin-page__project-row-name">{project.name}</span>
          <span>{project.projectKey}</span>
          <span>{project.repository.name}</span>
        </button>
      ))}
    </div>
  );
}

export function ProjectCreateForm({
  repositories,
  draft,
  disabled,
  onChange,
  onSubmit,
}: {
  repositories: ApiRepository[];
  draft: ProjectDraft;
  disabled: boolean;
  onChange: (draft: ProjectDraft) => void;
  onSubmit: (event: FormEvent) => void;
}) {
  return (
    <form className="content-cms-admin-page__form" onSubmit={onSubmit}>
      <div className="settings-card__header">
        <h2>Create project</h2>
      </div>
      <label className="settings-field">
        <span className="settings-field__label">Project key</span>
        <input
          className="settings-input"
          value={draft.projectKey}
          onChange={(event) => onChange({ ...draft, projectKey: event.target.value })}
          placeholder="growth-email"
          disabled={disabled}
        />
      </label>
      <label className="settings-field">
        <span className="settings-field__label">Name</span>
        <input
          className="settings-input"
          value={draft.name}
          onChange={(event) => onChange({ ...draft, name: event.target.value })}
          placeholder="Growth email copy"
          disabled={disabled}
        />
      </label>
      <label className="settings-field">
        <span className="settings-field__label">Repository</span>
        <select
          className="settings-input"
          value={draft.repositoryId}
          onChange={(event) => onChange({ ...draft, repositoryId: event.target.value })}
          disabled={disabled}
        >
          <option value="">Select repository</option>
          {repositories.map((repository) => (
            <option key={repository.id} value={repository.id}>
              {repository.name}
            </option>
          ))}
        </select>
      </label>
      <label className="settings-field">
        <span className="settings-field__label">Asset path</span>
        <input
          className="settings-input"
          value={draft.assetPath}
          onChange={(event) => onChange({ ...draft, assetPath: event.target.value })}
          placeholder="cms/growth-email"
          disabled={disabled}
        />
      </label>
      <label className="settings-field">
        <span className="settings-field__label">Delivery hint</span>
        <select
          className="settings-input"
          value={draft.deliveryHint}
          onChange={(event) => onChange({ ...draft, deliveryHint: event.target.value })}
          disabled={disabled}
        >
          <option value="BLOB_CDN">Blob/CDN</option>
          <option value="STATSIG_DYNAMIC_CONFIG">Statsig dynamic config</option>
          <option value="EXPERIENCE_FRAMEWORK">Experience framework</option>
        </select>
      </label>
      <label className="settings-field">
        <span className="settings-field__label">Description</span>
        <textarea
          className="settings-input content-cms-admin-page__textarea"
          value={draft.description}
          onChange={(event) => onChange({ ...draft, description: event.target.value })}
          disabled={disabled}
        />
      </label>
      <label className="settings-toggle">
        <input
          type="checkbox"
          checked={draft.enabled}
          onChange={(event) => onChange({ ...draft, enabled: event.target.checked })}
          disabled={disabled}
        />
        Enabled
      </label>
      <button
        type="submit"
        className="settings-button settings-button--primary"
        disabled={disabled}
      >
        Create project
      </button>
    </form>
  );
}

export function ProjectOverview({ detail }: { detail: ApiCmsProjectDetail }) {
  const { project } = detail;
  return (
    <section className="settings-card">
      <div className="settings-card__header">
        <h2>Project overview</h2>
      </div>
      <div className="content-cms-admin-page__overview">
        <Definition label="Key" value={project.projectKey} />
        <Definition
          label="Repository"
          value={`${project.repository.name} (#${project.repository.id})`}
        />
        <Definition label="Virtual asset" value={`${project.asset.path} (#${project.asset.id})`} />
        <Definition label="Source locale" value={project.sourceLocale} />
        <Definition label="Delivery hint" value={project.deliveryHint} />
        <Definition label="Enabled" value={formatBool(project.enabled)} />
        <Definition label="Created by" value={project.audit.createdByUsername} />
        <Definition label="Updated by" value={project.audit.lastModifiedByUsername} />
        <Definition label="Updated" value={formatDateTime(project.audit.lastModifiedDate)} />
      </div>
      {project.description ? <p className="settings-page__hint">{project.description}</p> : null}
    </section>
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
      <h3>Edit project</h3>
      <label className="settings-field">
        <span className="settings-field__label">Name</span>
        <input
          className="settings-input"
          value={draft.name}
          onChange={(event) => onChange({ ...draft, name: event.target.value })}
          disabled={disabled}
        />
      </label>
      <label className="settings-field">
        <span className="settings-field__label">Delivery hint</span>
        <select
          className="settings-input"
          value={draft.deliveryHint}
          onChange={(event) => onChange({ ...draft, deliveryHint: event.target.value })}
          disabled={disabled}
        >
          <option value="BLOB_CDN">Blob/CDN</option>
          <option value="STATSIG_DYNAMIC_CONFIG">Statsig dynamic config</option>
          <option value="EXPERIENCE_FRAMEWORK">Experience framework</option>
        </select>
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
      <label className="settings-toggle">
        <input
          type="checkbox"
          checked={draft.enabled}
          onChange={(event) => onChange({ ...draft, enabled: event.target.checked })}
          disabled={disabled}
        />
        Enabled
      </label>
      <button type="submit" className="settings-button" disabled={disabled}>
        Update project
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
      <h3>Edit content type</h3>
      <label className="settings-field">
        <span className="settings-field__label">Content type</span>
        <select
          className="settings-input"
          value={selectedContentTypeId}
          onChange={(event) => onContentTypeChange(event.target.value)}
          disabled={disabled}
        >
          <option value="">Select type</option>
          {contentTypes.map((contentType) => (
            <option key={contentType.id} value={contentType.id}>
              {contentType.name}
            </option>
          ))}
        </select>
      </label>
      <label className="settings-field">
        <span className="settings-field__label">Stable key</span>
        <input className="settings-input" value={draft.typeKey} disabled />
      </label>
      <label className="settings-field">
        <span className="settings-field__label">Name</span>
        <input
          className="settings-input"
          value={draft.name}
          onChange={(event) => onChange({ ...draft, name: event.target.value })}
          disabled={disabled}
        />
      </label>
      <label className="settings-field">
        <span className="settings-field__label">Schema version</span>
        <input className="settings-input" value={draft.schemaVersion} disabled />
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
      <label className="settings-field content-cms-admin-page__field-span">
        <span className="settings-field__label">Metadata schema JSON</span>
        <textarea
          className="settings-input content-cms-admin-page__textarea"
          value={draft.metadataSchemaJson}
          onChange={(event) => onChange({ ...draft, metadataSchemaJson: event.target.value })}
          disabled={disabled}
        />
      </label>
      <button type="submit" className="settings-button" disabled={disabled}>
        Update type
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
      <h3>Edit field</h3>
      <label className="settings-field">
        <span className="settings-field__label">Field</span>
        <select
          className="settings-input"
          value={selectedFieldId}
          onChange={(event) => onFieldChange(event.target.value)}
          disabled={disabled || contentType == null}
        >
          <option value="">Select field</option>
          {contentType?.fields.map((field) => (
            <option key={field.id} value={field.id}>
              {field.name}
            </option>
          ))}
        </select>
      </label>
      <label className="settings-field">
        <span className="settings-field__label">Stable key</span>
        <input className="settings-input" value={draft.fieldKey} disabled />
      </label>
      <label className="settings-field">
        <span className="settings-field__label">Name</span>
        <input
          className="settings-input"
          value={draft.name}
          onChange={(event) => onChange({ ...draft, name: event.target.value })}
          disabled={disabled}
        />
      </label>
      <label className="settings-field">
        <span className="settings-field__label">Type</span>
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
        <span className="settings-field__label">Sort order</span>
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
        <span className="settings-field__label">Description</span>
        <input
          className="settings-input"
          value={draft.description}
          onChange={(event) => onChange({ ...draft, description: event.target.value })}
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
          Required
        </label>
      </div>
      <button type="submit" className="settings-button" disabled={disabled}>
        Update field
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
      <h3>Edit entry</h3>
      <label className="settings-field">
        <span className="settings-field__label">Entry</span>
        <select
          className="settings-input"
          value={selectedEntryId}
          onChange={(event) => onEntryChange(event.target.value)}
          disabled={disabled}
        >
          <option value="">Select entry</option>
          {entries.map((entry) => (
            <option key={entry.id} value={entry.id}>
              {entry.name}
            </option>
          ))}
        </select>
      </label>
      <label className="settings-field">
        <span className="settings-field__label">Stable key</span>
        <input className="settings-input" value={draft.entryKey} disabled />
      </label>
      <label className="settings-field">
        <span className="settings-field__label">Name</span>
        <input
          className="settings-input"
          value={draft.name}
          onChange={(event) => onChange({ ...draft, name: event.target.value })}
          disabled={disabled}
        />
      </label>
      <label className="settings-field">
        <span className="settings-field__label">Status</span>
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
        <span className="settings-field__label">Description</span>
        <input
          className="settings-input"
          value={draft.description}
          onChange={(event) => onChange({ ...draft, description: event.target.value })}
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
      <button type="submit" className="settings-button" disabled={disabled}>
        Update entry
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
      <h3>Edit variant</h3>
      <label className="settings-field">
        <span className="settings-field__label">Variant</span>
        <select
          className="settings-input"
          value={selectedVariantId}
          onChange={(event) => onVariantChange(event.target.value)}
          disabled={disabled || entry == null}
        >
          <option value="">Select variant</option>
          {entry?.variants.map((variant) => (
            <option key={variant.id} value={variant.id}>
              {variant.name}
            </option>
          ))}
        </select>
      </label>
      <label className="settings-field">
        <span className="settings-field__label">Stable key</span>
        <input className="settings-input" value={draft.variantKey} disabled />
      </label>
      <label className="settings-field">
        <span className="settings-field__label">Name</span>
        <input
          className="settings-input"
          value={draft.name}
          onChange={(event) => onChange({ ...draft, name: event.target.value })}
          disabled={disabled}
        />
      </label>
      <label className="settings-field">
        <span className="settings-field__label">
          Candidate group{draft.status === 'CANDIDATE' ? ' (required)' : ''}
        </span>
        <input
          className="settings-input"
          value={draft.candidateGroupKey}
          onChange={(event) => onChange({ ...draft, candidateGroupKey: event.target.value })}
          disabled={disabled}
          required={draft.status === 'CANDIDATE'}
        />
      </label>
      <label className="settings-field">
        <span className="settings-field__label">Status</span>
        <select
          className="settings-input"
          value={draft.status}
          onChange={(event) =>
            onChange({ ...draft, status: event.target.value as ApiCmsVariantStatus })
          }
          disabled={disabled}
        >
          <option value="CONTROL">Control</option>
          <option value="CANDIDATE">Candidate</option>
          <option value="ARCHIVED">Archived</option>
        </select>
      </label>
      <label className="settings-field">
        <span className="settings-field__label">Sort order</span>
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
      <button type="submit" className="settings-button" disabled={disabled}>
        Update variant
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
      <h3>Create content type</h3>
      <label className="settings-field">
        <span className="settings-field__label">Type key</span>
        <input
          className="settings-input"
          value={draft.typeKey}
          onChange={(event) => onChange({ ...draft, typeKey: event.target.value })}
          placeholder="email"
          disabled={disabled}
        />
      </label>
      <label className="settings-field">
        <span className="settings-field__label">Name</span>
        <input
          className="settings-input"
          value={draft.name}
          onChange={(event) => onChange({ ...draft, name: event.target.value })}
          placeholder="Email"
          disabled={disabled}
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
      <label className="settings-field">
        <span className="settings-field__label">Metadata schema JSON</span>
        <textarea
          className="settings-input content-cms-admin-page__textarea"
          value={draft.metadataSchemaJson}
          onChange={(event) => onChange({ ...draft, metadataSchemaJson: event.target.value })}
          placeholder='{"type":"object","properties":{"owner":{"type":"string"}}}'
          disabled={disabled}
        />
      </label>
      <button type="submit" className="settings-button" disabled={disabled}>
        Create type
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
      <h3>Define localizable field</h3>
      <label className="settings-field">
        <span className="settings-field__label">Content type</span>
        <select
          className="settings-input"
          value={draft.contentTypeId}
          onChange={(event) => onChange({ ...draft, contentTypeId: event.target.value })}
          disabled={disabled}
        >
          <option value="">Select type</option>
          {contentTypes.map((contentType) => (
            <option key={contentType.id} value={contentType.id}>
              {contentType.name}
            </option>
          ))}
        </select>
      </label>
      <label className="settings-field">
        <span className="settings-field__label">Field key</span>
        <input
          className="settings-input"
          value={draft.fieldKey}
          onChange={(event) => onChange({ ...draft, fieldKey: event.target.value })}
          placeholder="header"
          disabled={disabled}
        />
      </label>
      <label className="settings-field">
        <span className="settings-field__label">Name</span>
        <input
          className="settings-input"
          value={draft.name}
          onChange={(event) => onChange({ ...draft, name: event.target.value })}
          placeholder="Header"
          disabled={disabled}
        />
      </label>
      <label className="settings-field">
        <span className="settings-field__label">Type</span>
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
        <span className="settings-field__label">Sort order</span>
        <input
          className="settings-input"
          type="number"
          min="0"
          value={draft.sortOrder}
          onChange={(event) => onChange({ ...draft, sortOrder: event.target.value })}
          disabled={disabled}
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
      <div className="content-cms-admin-page__toggles">
        <label className="settings-toggle">
          <input
            type="checkbox"
            checked={draft.required}
            onChange={(event) => onChange({ ...draft, required: event.target.checked })}
            disabled={disabled}
          />
          Required
        </label>
      </div>
      <button type="submit" className="settings-button" disabled={disabled}>
        Create field
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
      <h3>Create content entry</h3>
      <label className="settings-field">
        <span className="settings-field__label">Content type</span>
        <select
          className="settings-input"
          value={draft.contentTypeId}
          onChange={(event) => onChange({ ...draft, contentTypeId: event.target.value })}
          disabled={disabled}
        >
          <option value="">Select type</option>
          {contentTypes.map((contentType) => (
            <option key={contentType.id} value={contentType.id}>
              {contentType.name}
            </option>
          ))}
        </select>
      </label>
      <label className="settings-field">
        <span className="settings-field__label">Entry key</span>
        <input
          className="settings-input"
          value={draft.entryKey}
          onChange={(event) => onChange({ ...draft, entryKey: event.target.value })}
          placeholder="welcome-email"
          disabled={disabled}
        />
      </label>
      <label className="settings-field">
        <span className="settings-field__label">Name</span>
        <input
          className="settings-input"
          value={draft.name}
          onChange={(event) => onChange({ ...draft, name: event.target.value })}
          placeholder="Welcome email"
          disabled={disabled}
        />
      </label>
      <label className="settings-field">
        <span className="settings-field__label">Status</span>
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
      <label className="settings-field">
        <span className="settings-field__label">Description</span>
        <input
          className="settings-input"
          value={draft.description}
          onChange={(event) => onChange({ ...draft, description: event.target.value })}
          disabled={disabled}
        />
      </label>
      <label className="settings-field">
        <span className="settings-field__label">Metadata JSON</span>
        <textarea
          className="settings-input content-cms-admin-page__textarea"
          value={draft.metadataJson}
          onChange={(event) => onChange({ ...draft, metadataJson: event.target.value })}
          placeholder='{"surface":"email"}'
          disabled={disabled}
        />
      </label>
      <button type="submit" className="settings-button" disabled={disabled}>
        Create entry
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
      <h3>Add variant</h3>
      <label className="settings-field">
        <span className="settings-field__label">Entry</span>
        <select
          className="settings-input"
          value={draft.entryId}
          onChange={(event) => onChange({ ...draft, entryId: event.target.value })}
          disabled={disabled}
        >
          <option value="">Select entry</option>
          {entries.map((entry) => (
            <option key={entry.id} value={entry.id}>
              {entry.name}
            </option>
          ))}
        </select>
      </label>
      <label className="settings-field">
        <span className="settings-field__label">Variant key</span>
        <input
          className="settings-input"
          value={draft.variantKey}
          onChange={(event) => onChange({ ...draft, variantKey: event.target.value })}
          placeholder="subject-test-a"
          disabled={disabled}
        />
      </label>
      <label className="settings-field">
        <span className="settings-field__label">Name</span>
        <input
          className="settings-input"
          value={draft.name}
          onChange={(event) => onChange({ ...draft, name: event.target.value })}
          placeholder="Subject test A"
          disabled={disabled}
        />
      </label>
      <label className="settings-field">
        <span className="settings-field__label">
          Candidate group{draft.status === 'CANDIDATE' ? ' (required)' : ''}
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
      <label className="settings-field">
        <span className="settings-field__label">Status</span>
        <select
          className="settings-input"
          value={draft.status}
          onChange={(event) =>
            onChange({ ...draft, status: event.target.value as ApiCmsVariantStatus })
          }
          disabled={disabled}
        >
          <option value="CONTROL">Control</option>
          <option value="CANDIDATE">Candidate</option>
          <option value="ARCHIVED">Archived</option>
        </select>
      </label>
      <label className="settings-field">
        <span className="settings-field__label">Sort order</span>
        <input
          className="settings-input"
          type="number"
          min="0"
          value={draft.sortOrder}
          onChange={(event) => onChange({ ...draft, sortOrder: event.target.value })}
          disabled={disabled}
        />
      </label>
      <label className="settings-field">
        <span className="settings-field__label">Metadata JSON</span>
        <textarea
          className="settings-input content-cms-admin-page__textarea"
          value={draft.metadataJson}
          onChange={(event) => onChange({ ...draft, metadataJson: event.target.value })}
          disabled={disabled}
        />
      </label>
      <button type="submit" className="settings-button" disabled={disabled}>
        Add variant
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
}) {
  return (
    <form
      className="content-cms-admin-page__form content-cms-admin-page__form--wide"
      onSubmit={onSubmit}
    >
      <h3>Map field to Mojito text unit</h3>
      <label className="settings-field">
        <span className="settings-field__label">Entry</span>
        <select
          className="settings-input"
          value={draft.entryId}
          onChange={(event) =>
            onChange({ ...draft, entryId: event.target.value, variantId: '', fieldId: '' })
          }
          disabled={disabled}
        >
          <option value="">Select entry</option>
          {entries.map((entry) => (
            <option key={entry.id} value={entry.id}>
              {entry.name}
            </option>
          ))}
        </select>
      </label>
      <label className="settings-field">
        <span className="settings-field__label">Variant</span>
        <select
          className="settings-input"
          value={draft.variantId}
          onChange={(event) => onChange({ ...draft, variantId: event.target.value })}
          disabled={disabled || !selectedEntry}
        >
          <option value="">Select variant</option>
          {selectedEntry?.variants.map((variant) => (
            <option key={variant.id} value={variant.id}>
              {variant.name}
            </option>
          ))}
        </select>
      </label>
      <label className="settings-field">
        <span className="settings-field__label">Localizable field</span>
        <select
          className="settings-input"
          value={draft.fieldId}
          onChange={(event) => onChange({ ...draft, fieldId: event.target.value })}
          disabled={disabled || !selectedVariant}
        >
          <option value="">Select field</option>
          {fields.map((field) => (
            <option key={field.id} value={field.id}>
              {field.name}
            </option>
          ))}
        </select>
      </label>
      <label className="settings-field">
        <span className="settings-field__label">Mapping source</span>
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
          <option value="GENERATED">Generated CMS string</option>
          <option value="STRING_ID">Existing Mojito string ID</option>
          <option value="TM_TEXT_UNIT_ID">Exact TM text unit ID</option>
        </select>
      </label>
      <Definition
        label="Generated CMS string ID"
        value={generatedStringId ?? 'Select entry, variant, and field'}
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
      <label className="settings-field content-cms-admin-page__field-span">
        <span className="settings-field__label">Generated source content</span>
        <textarea
          className="settings-input content-cms-admin-page__textarea"
          value={draft.sourceContent}
          onChange={(event) => onChange({ ...draft, sourceContent: event.target.value })}
          placeholder="Required for generated CMS strings"
          disabled={disabled || draft.mappingSource !== 'GENERATED'}
        />
      </label>
      <label className="settings-field content-cms-admin-page__field-span">
        <span className="settings-field__label">Generated translator context</span>
        <textarea
          className="settings-input content-cms-admin-page__textarea"
          value={draft.sourceComment}
          onChange={(event) => onChange({ ...draft, sourceComment: event.target.value })}
          placeholder="Where this copy appears and any constraints"
          disabled={disabled || draft.mappingSource !== 'GENERATED'}
        />
      </label>
      <div className="content-cms-admin-page__actions">
        <button type="submit" className="settings-button" disabled={disabled}>
          {selectedMapping ? 'Save mapping' : 'Map field'}
        </button>
        <button
          type="button"
          className="settings-button"
          disabled={disabled || selectedMapping == null}
          onClick={onUnmap}
        >
          Unmap field
        </button>
      </div>
    </form>
  );
}

export function PublishForm({
  snapshots,
  completenessResult,
  publishLocales,
  disabled,
  onPublishLocalesChange,
  onCompletenessSubmit,
  onPublish,
}: {
  snapshots: ApiCmsProjectDetail['publishSnapshots'];
  completenessResult: ApiCmsProjectCompleteness | null;
  publishLocales: string;
  disabled: boolean;
  onPublishLocalesChange: (value: string) => void;
  onCompletenessSubmit: (event: FormEvent) => void;
  onPublish: () => void;
}) {
  return (
    <div className="content-cms-admin-page__form content-cms-admin-page__form--wide">
      <form className="content-cms-admin-page__inline-form" onSubmit={onCompletenessSubmit}>
        <h3>Validate and publish</h3>
        <label className="settings-field content-cms-admin-page__field-span">
          <span className="settings-field__label">Locale tags</span>
          <input
            className="settings-input"
            value={publishLocales}
            onChange={(event) => onPublishLocalesChange(event.target.value)}
            placeholder="Leave blank for configured target locales"
            disabled={disabled}
          />
        </label>
        <button type="submit" className="settings-button" disabled={disabled}>
          Validate package
        </button>
        <button
          type="button"
          className="settings-button settings-button--primary"
          disabled={disabled}
          onClick={onPublish}
        >
          Publish JSON
        </button>
      </form>

      {completenessResult ? <CompletenessTable result={completenessResult} /> : null}

      <div className="settings-table-wrapper">
        <table className="settings-table">
          <thead>
            <tr>
              <th>Snapshot</th>
              <th>Locales</th>
              <th>Size</th>
              <th>SHA-256</th>
              <th>Signing key</th>
              <th>Artifact signature</th>
              <th>Published by</th>
              <th>Published</th>
              <th>Artifact</th>
            </tr>
          </thead>
          <tbody>
            {snapshots.length === 0 ? (
              <tr>
                <td colSpan={9}>No published snapshots.</td>
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
    </div>
  );
}

function CompletenessTable({ result }: { result: ApiCmsProjectCompleteness }) {
  const scopeRows = [
    ...result.locales.map((locale) => ({ scope: 'Package', locale })),
    ...result.entries.flatMap((entry) =>
      entry.locales.map((locale) => ({ scope: entry.entryKey, locale })),
    ),
  ];
  return (
    <div>
      <p className="settings-page__hint">
        Package complete: {formatBool(result.complete)} for {result.localeTags.join(', ')}.
      </p>
      <p className="settings-page__hint">
        Package size: {result.publishPackageByteSize.toLocaleString()} B.
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
    <section className="settings-card">
      <div className="settings-card__header">
        <h2>Content types and fields</h2>
      </div>
      <div className="settings-table-wrapper">
        <table className="settings-table">
          <thead>
            <tr>
              <th>Type</th>
              <th>Version</th>
              <th>Field</th>
              <th>Field type</th>
              <th>Required</th>
            </tr>
          </thead>
          <tbody>
            {contentTypes.length === 0 ? (
              <tr>
                <td colSpan={5}>No content types.</td>
              </tr>
            ) : (
              contentTypes.flatMap((contentType) => {
                if (contentType.fields.length === 0) {
                  return [
                    <tr key={`${contentType.id}-empty`}>
                      <td>{contentType.name}</td>
                      <td>{contentType.schemaVersion}</td>
                      <td colSpan={3}>No fields.</td>
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
    <section className="settings-card">
      <div className="settings-card__header">
        <h2>Entries, variants, and text units</h2>
      </div>
      <div className="settings-table-wrapper">
        <table className="settings-table">
          <thead>
            <tr>
              <th>Entry</th>
              <th>Type</th>
              <th>Status</th>
              <th>Variant</th>
              <th>Candidate group</th>
              <th>Field mappings</th>
            </tr>
          </thead>
          <tbody>
            {entries.length === 0 ? (
              <tr>
                <td colSpan={6}>No entries.</td>
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
                        'No mappings.'
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
