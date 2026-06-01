import './settings-page.css';
import './admin-ai-locale-prompt-suffix-page.css';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useMemo, useState } from 'react';
import { Navigate } from 'react-router-dom';

import {
  type ApiAiTranslateSourcePromptRule,
  deleteAiTranslateSourcePromptRule,
  fetchAiTranslateSourcePromptRules,
  testAiTranslateSourcePromptRule,
  upsertAiTranslateSourcePromptRule,
} from '../../api/ai-translate-source-prompt-rules';
import { AutoTextarea } from '../../components/AutoTextarea';
import { Modal } from '../../components/Modal';
import { SearchControl } from '../../components/SearchControl';
import { useUser } from '../../hooks/useUser';
import { SettingsSubpageHeader } from './SettingsSubpageHeader';

type StatusNotice = {
  kind: 'success' | 'error';
  message: string;
};

type EditorMode = 'create' | 'edit';

const SOURCE_PROMPT_RULES_QUERY_KEY = ['ai-translate-source-prompt-rules'] as const;

type SourceRuleTemplate = {
  id: string;
  title: string;
  description: string;
  name: string;
  sourceRegex: string;
  promptSuffix: string;
  testSource: string;
};

const SOURCE_RULE_TEMPLATES: SourceRuleTemplate[] = [
  {
    id: 'translate-bracketed-text',
    title: 'Translate bracketed text',
    description: 'For source text with translatable text inside square brackets.',
    name: 'bracketed text',
    sourceRegex: String.raw`\[[^\]\r\n]+\](?!\()`,
    promptSuffix:
      'Square-bracketed text in the source marks editable user-provided content or UI actions. Preserve the bracket structure and translate only the natural-language text inside the brackets when appropriate. Do not drop or invent bracketed slots.',
    testSource: 'Click [Download report] to continue.',
  },
];

type AdminAiSourcePromptRulesPageProps = {
  embedded?: boolean;
};

export function AdminAiSourcePromptRulesPage({
  embedded = false,
}: AdminAiSourcePromptRulesPageProps = {}) {
  const user = useUser();
  const isAdmin = user.role === 'ROLE_ADMIN';
  const queryClient = useQueryClient();

  const [searchQuery, setSearchQuery] = useState('');
  const [statusNotice, setStatusNotice] = useState<StatusNotice | null>(null);
  const [isEditorOpen, setIsEditorOpen] = useState(false);
  const [isTemplatePickerOpen, setIsTemplatePickerOpen] = useState(false);
  const [editorMode, setEditorMode] = useState<EditorMode>('create');
  const [editorId, setEditorId] = useState<number | null>(null);
  const [editorName, setEditorName] = useState('');
  const [editorDescription, setEditorDescription] = useState('');
  const [editorEnabled, setEditorEnabled] = useState(true);
  const [editorPriority, setEditorPriority] = useState('0');
  const [editorSourceRegex, setEditorSourceRegex] = useState('');
  const [editorPromptSuffix, setEditorPromptSuffix] = useState('');
  const [testSourceText, setTestSourceText] = useState('');

  const sourcePromptRulesQuery = useQuery<ApiAiTranslateSourcePromptRule[]>({
    queryKey: SOURCE_PROMPT_RULES_QUERY_KEY,
    queryFn: fetchAiTranslateSourcePromptRules,
    staleTime: 30_000,
    enabled: isAdmin,
  });

  const rows = useMemo(() => {
    const normalizedSearch = searchQuery.trim().toLowerCase();
    return (sourcePromptRulesQuery.data ?? [])
      .map((row) => ({
        ...row,
        searchText:
          `${row.name} ${row.description ?? ''} ${row.sourceRegex} ${row.promptSuffix}`.toLowerCase(),
      }))
      .filter((row) => !normalizedSearch || row.searchText.includes(normalizedSearch));
  }, [searchQuery, sourcePromptRulesQuery.data]);

  const saveMutation = useMutation({
    mutationFn: upsertAiTranslateSourcePromptRule,
    onSuccess: async (savedRow) => {
      queryClient.setQueryData<ApiAiTranslateSourcePromptRule[]>(
        SOURCE_PROMPT_RULES_QUERY_KEY,
        (previous) => {
          const next = (previous ?? []).filter((row) => row.id !== savedRow.id);
          next.push(savedRow);
          next.sort((first, second) => first.priority - second.priority || first.id - second.id);
          return next;
        },
      );
      await queryClient.invalidateQueries({ queryKey: SOURCE_PROMPT_RULES_QUERY_KEY });
      setIsEditorOpen(false);
      setStatusNotice({
        kind: 'success',
        message: editorMode === 'create' ? 'Rule added.' : 'Rule saved.',
      });
      resetEditor();
    },
    onError: (error: Error) => {
      setStatusNotice({ kind: 'error', message: error.message || 'Failed to save rule.' });
    },
  });

  const deleteMutation = useMutation({
    mutationFn: deleteAiTranslateSourcePromptRule,
    onSuccess: async (_, id) => {
      queryClient.setQueryData<ApiAiTranslateSourcePromptRule[]>(
        SOURCE_PROMPT_RULES_QUERY_KEY,
        (previous) => (previous ?? []).filter((row) => row.id !== id),
      );
      await queryClient.invalidateQueries({ queryKey: SOURCE_PROMPT_RULES_QUERY_KEY });
      setIsEditorOpen(false);
      setStatusNotice({ kind: 'success', message: 'Rule deleted.' });
      resetEditor();
    },
    onError: (error: Error) => {
      setStatusNotice({ kind: 'error', message: error.message || 'Failed to delete rule.' });
    },
  });

  const testMutation = useMutation({
    mutationFn: testAiTranslateSourcePromptRule,
  });

  if (!isAdmin) {
    return <Navigate to="/repositories" replace />;
  }

  const resetEditor = () => {
    setEditorMode('create');
    setEditorId(null);
    setEditorName('');
    setEditorDescription('');
    setEditorEnabled(true);
    setEditorPriority('0');
    setEditorSourceRegex('');
    setEditorPromptSuffix('');
    setTestSourceText('');
    saveMutation.reset();
    deleteMutation.reset();
    testMutation.reset();
  };

  const applyTemplate = (template: SourceRuleTemplate) => {
    if (!editorName.trim()) {
      setEditorName(template.name);
    }
    setEditorSourceRegex(template.sourceRegex);
    setEditorPromptSuffix(template.promptSuffix);
    setTestSourceText(template.testSource);
    testMutation.reset();
    setIsTemplatePickerOpen(false);
  };

  const openCreateEditor = () => {
    resetEditor();
    setStatusNotice(null);
    setIsEditorOpen(true);
  };

  const openEditEditor = (row: ApiAiTranslateSourcePromptRule) => {
    setEditorMode('edit');
    setEditorId(row.id);
    setEditorName(row.name);
    setEditorDescription(row.description ?? '');
    setEditorEnabled(row.enabled);
    setEditorPriority(String(row.priority));
    setEditorSourceRegex(row.sourceRegex);
    setEditorPromptSuffix(row.promptSuffix);
    setTestSourceText('');
    setStatusNotice(null);
    saveMutation.reset();
    deleteMutation.reset();
    testMutation.reset();
    setIsEditorOpen(true);
  };

  const handleCloseEditor = () => {
    if (saveMutation.isPending || deleteMutation.isPending) {
      return;
    }
    setIsEditorOpen(false);
    setIsTemplatePickerOpen(false);
    resetEditor();
  };

  const trimmedName = editorName.trim();
  const trimmedSourceRegex = editorSourceRegex.trim();
  const trimmedPromptSuffix = editorPromptSuffix.trim();
  const parsedPriority = Number(editorPriority);
  const canSave =
    trimmedName.length > 0 &&
    trimmedSourceRegex.length > 0 &&
    trimmedPromptSuffix.length > 0 &&
    Number.isInteger(parsedPriority) &&
    !saveMutation.isPending &&
    !deleteMutation.isPending;

  const handleSave = () => {
    if (!canSave) {
      return;
    }
    saveMutation.mutate({
      id: editorId,
      name: trimmedName,
      description: editorDescription.trim() || null,
      enabled: editorEnabled,
      priority: parsedPriority,
      matchType: 'REGEX',
      sourceRegex: trimmedSourceRegex,
      promptSuffix: trimmedPromptSuffix,
    });
  };

  const handleDelete = () => {
    if (editorId == null) {
      return;
    }
    deleteMutation.mutate(editorId);
  };

  const handleTest = () => {
    if (!trimmedSourceRegex) {
      return;
    }
    testMutation.mutate({
      sourceRegex: trimmedSourceRegex,
      sourceText: testSourceText,
    });
  };

  const pageContent = (
    <div className="settings-page settings-page--wide ai-locale-prompt-page ai-source-prompt-page">
      <section className="settings-card">
        <div className="settings-card__content ai-source-prompt-page__content">
          <div className="ai-locale-prompt-page__toolbar ai-source-prompt-page__toolbar">
            <SearchControl
              value={searchQuery}
              onChange={setSearchQuery}
              placeholder="Search rules"
              className="ai-locale-prompt-page__search"
            />
            <button
              type="button"
              className="settings-button settings-button--primary ai-locale-prompt-page__create"
              onClick={openCreateEditor}
            >
              Add rule
            </button>
          </div>

          <div className="ai-locale-prompt-page__count ai-source-prompt-page__count">
            <span
              className="ai-locale-prompt-page__count-text ai-source-prompt-page__count-badge"
              aria-label={`${rows.length} ${rows.length === 1 ? 'rule' : 'rules'}`}
              title={`${rows.length} ${rows.length === 1 ? 'rule' : 'rules'}`}
            >
              {rows.length}
            </span>
            {statusNotice ? (
              <span
                className={`settings-hint ai-locale-prompt-page__status ai-source-prompt-page__status${
                  statusNotice.kind === 'error' ? ' is-error' : ''
                }`}
              >
                {statusNotice.message}
              </span>
            ) : null}
          </div>

          {sourcePromptRulesQuery.isError ? (
            <p className="ai-locale-prompt-page__empty">Could not load rules.</p>
          ) : sourcePromptRulesQuery.isLoading ? (
            <p className="ai-locale-prompt-page__empty">Loading rules…</p>
          ) : (sourcePromptRulesQuery.data ?? []).length === 0 ? (
            <p className="ai-locale-prompt-page__empty">No source prompt rules yet.</p>
          ) : rows.length === 0 ? (
            <p className="ai-locale-prompt-page__empty">No rules match the current search.</p>
          ) : (
            <div className="ai-locale-prompt-page__table">
              <div className="ai-locale-prompt-page__table-header ai-source-prompt-page__table-row">
                <div className="ai-locale-prompt-page__cell">Rule</div>
                <div className="ai-locale-prompt-page__cell">Regex</div>
                <div className="ai-locale-prompt-page__cell">Prompt suffix</div>
                <div className="ai-locale-prompt-page__cell">Updated</div>
              </div>
              {rows.map((row) => (
                <div
                  key={row.id}
                  className="ai-locale-prompt-page__row ai-source-prompt-page__table-row"
                  onClick={() => openEditEditor(row)}
                  role="button"
                  tabIndex={0}
                  onKeyDown={(event) => {
                    if (event.key === 'Enter' || event.key === ' ') {
                      event.preventDefault();
                      openEditEditor(row);
                    }
                  }}
                >
                  <div className="ai-locale-prompt-page__cell">
                    <div className="ai-locale-prompt-page__locale-cell">
                      <span className="ai-locale-prompt-page__locale-group">
                        <span className="ai-locale-prompt-page__locale-name">{row.name}</span>
                        <span className="ai-locale-prompt-page__locale-tag">
                          {row.enabled ? 'Enabled' : 'Disabled'} · Priority {row.priority}
                        </span>
                      </span>
                      <button
                        type="button"
                        className="ai-locale-prompt-page__row-action"
                        onClick={(event) => {
                          event.stopPropagation();
                          openEditEditor(row);
                        }}
                      >
                        Edit
                      </button>
                    </div>
                  </div>
                  <div className="ai-locale-prompt-page__cell">{row.sourceRegex}</div>
                  <div className="ai-locale-prompt-page__cell ai-locale-prompt-page__cell--suffix">
                    {row.promptSuffix}
                  </div>
                  <div className="ai-locale-prompt-page__cell ai-locale-prompt-page__cell--muted">
                    {formatDateTime(row.updatedAt)}
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      </section>

      <Modal
        open={isEditorOpen}
        size="lg"
        ariaLabel={editorMode === 'create' ? 'Add source prompt rule' : 'Edit source prompt rule'}
        onClose={handleCloseEditor}
        closeOnBackdrop
      >
        <div className="ai-source-prompt-page__modal-header">
          <div className="modal__title">
            {editorMode === 'create' ? 'Add source prompt rule' : `Edit ${editorName}`}
          </div>
          {editorMode === 'create' ? (
            <button
              type="button"
              className="settings-button settings-button--ghost ai-source-prompt-page__prefill"
              onClick={() => setIsTemplatePickerOpen(true)}
            >
              Use template
            </button>
          ) : null}
        </div>
        <div className="ai-locale-prompt-page__modal-fields">
          <div className="settings-field">
            <label className="settings-field__label" htmlFor="ai-source-prompt-rule-name">
              Name
            </label>
            <input
              id="ai-source-prompt-rule-name"
              className="settings-input"
              value={editorName}
              onChange={(event) => setEditorName(event.target.value)}
            />
          </div>
          <div className="settings-field">
            <label className="settings-field__label" htmlFor="ai-source-prompt-rule-description">
              Description
            </label>
            <input
              id="ai-source-prompt-rule-description"
              className="settings-input"
              value={editorDescription}
              onChange={(event) => setEditorDescription(event.target.value)}
            />
          </div>
          <div className="ai-source-prompt-page__form-grid">
            <label className="settings-field ai-source-prompt-page__checkbox">
              <input
                type="checkbox"
                checked={editorEnabled}
                onChange={(event) => setEditorEnabled(event.target.checked)}
              />
              <span>Enabled</span>
            </label>
            <div className="settings-field">
              <label className="settings-field__label" htmlFor="ai-source-prompt-rule-priority">
                Priority
              </label>
              <input
                id="ai-source-prompt-rule-priority"
                className="settings-input"
                type="number"
                value={editorPriority}
                onChange={(event) => setEditorPriority(event.target.value)}
              />
            </div>
          </div>
          <div className="settings-field">
            <label className="settings-field__label" htmlFor="ai-source-prompt-rule-regex">
              Source regex
            </label>
            <AutoTextarea
              id="ai-source-prompt-rule-regex"
              className="settings-input ai-source-prompt-page__textarea"
              value={editorSourceRegex}
              onChange={(event) => {
                setEditorSourceRegex(event.target.value);
                testMutation.reset();
              }}
              minRows={2}
              maxRows={6}
            />
          </div>
          <div className="settings-field">
            <label className="settings-field__label" htmlFor="ai-source-prompt-rule-suffix">
              Prompt suffix
            </label>
            <AutoTextarea
              id="ai-source-prompt-rule-suffix"
              className="settings-input ai-locale-prompt-page__textarea"
              value={editorPromptSuffix}
              onChange={(event) => setEditorPromptSuffix(event.target.value)}
              minRows={5}
              maxRows={12}
            />
          </div>
          <div className="settings-field">
            <label className="settings-field__label" htmlFor="ai-source-prompt-rule-test">
              Test source
            </label>
            <AutoTextarea
              id="ai-source-prompt-rule-test"
              className="settings-input ai-source-prompt-page__textarea"
              value={testSourceText}
              onChange={(event) => setTestSourceText(event.target.value)}
              minRows={3}
              maxRows={8}
            />
            <button
              type="button"
              className="settings-button ai-source-prompt-page__test-button"
              onClick={handleTest}
              disabled={!trimmedSourceRegex || testMutation.isPending}
            >
              {testMutation.isPending ? 'Testing…' : 'Test regex'}
            </button>
            {testMutation.data ? (
              <p className="settings-hint">
                {testMutation.data.matches
                  ? `Matched: ${testMutation.data.matchesList
                      .map((match) => match.snippet)
                      .join(', ')}`
                  : 'No match.'}
              </p>
            ) : null}
            {testMutation.error ? (
              <p className="settings-hint is-error">
                {testMutation.error instanceof Error
                  ? testMutation.error.message
                  : 'Failed to test regex.'}
              </p>
            ) : null}
          </div>
          {saveMutation.error ? (
            <p className="settings-hint is-error">
              {saveMutation.error instanceof Error
                ? saveMutation.error.message
                : 'Failed to save rule.'}
            </p>
          ) : null}
          {deleteMutation.error ? (
            <p className="settings-hint is-error">
              {deleteMutation.error instanceof Error
                ? deleteMutation.error.message
                : 'Failed to delete rule.'}
            </p>
          ) : null}
        </div>
        <div className="modal__actions ai-locale-prompt-page__modal-actions">
          <button type="button" className="modal__button" onClick={handleCloseEditor}>
            Cancel
          </button>
          {editorMode === 'edit' ? (
            <button
              type="button"
              className="modal__button modal__button--danger"
              onClick={handleDelete}
              disabled={saveMutation.isPending || deleteMutation.isPending}
            >
              {deleteMutation.isPending ? 'Deleting…' : 'Delete'}
            </button>
          ) : null}
          <button
            type="button"
            className="modal__button modal__button--primary"
            onClick={handleSave}
            disabled={!canSave}
          >
            {saveMutation.isPending ? 'Saving…' : 'Save'}
          </button>
        </div>
      </Modal>
      <Modal
        open={isTemplatePickerOpen}
        size="sm"
        ariaLabel="Choose source prompt rule template"
        onClose={() => setIsTemplatePickerOpen(false)}
        closeOnBackdrop
      >
        <div className="modal__title">Choose a template</div>
        <div className="ai-source-prompt-page__template-list">
          {SOURCE_RULE_TEMPLATES.map((template) => (
            <button
              key={template.id}
              type="button"
              className="ai-source-prompt-page__template-option"
              onClick={() => applyTemplate(template)}
            >
              <span className="ai-source-prompt-page__template-title">{template.title}</span>
              <span className="ai-source-prompt-page__template-description">
                {template.description}
              </span>
            </button>
          ))}
        </div>
        <div className="modal__actions ai-locale-prompt-page__modal-actions">
          <button
            type="button"
            className="modal__button"
            onClick={() => setIsTemplatePickerOpen(false)}
          >
            Cancel
          </button>
        </div>
      </Modal>
    </div>
  );

  if (embedded) {
    return pageContent;
  }

  return (
    <div className="settings-subpage">
      <SettingsSubpageHeader
        backTo="/settings/system"
        backLabel="Back to settings"
        context="Settings"
        title="AI source prompt rules"
      />
      {pageContent}
    </div>
  );
}

function formatDateTime(value: string | null) {
  if (!value) {
    return '-';
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  return date.toLocaleString();
}
