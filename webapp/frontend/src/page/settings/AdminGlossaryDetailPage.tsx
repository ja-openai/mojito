import './settings-page.css';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useMemo, useRef, useState } from 'react';
import { Link, Navigate, useNavigate, useParams } from 'react-router-dom';

import {
  type ApiCompiledInflectionProfilePackPreview,
  type ApiInflectionProfile,
  type ApiInflectionProfileStatus,
  deleteGlossary,
  exportInflectionProfilePack,
  fetchCompiledInflectionProfilePack,
  fetchGlossary,
  fetchInflectionProfiles,
  importInflectionProfiles,
  reviewInflectionProfile,
  updateGlossary,
  upsertInflectionProfile,
} from '../../api/glossaries';
import { ConfirmModal } from '../../components/ConfirmModal';
import { LocaleMultiSelect } from '../../components/LocaleMultiSelect';
import { Modal } from '../../components/Modal';
import { RepositoryMultiSelect } from '../../components/RepositoryMultiSelect';
import { useLocales } from '../../hooks/useLocales';
import { REPOSITORIES_QUERY_KEY, useRepositories } from '../../hooks/useRepositories';
import { useUser } from '../../hooks/useUser';
import { buildScopedGlossaryLocaleOptions } from '../../utils/glossaryLocaleScope';
import { buildGlossaryWorkbenchState } from '../../utils/glossaryWorkbench';
import {
  createEmptyInflectionFormRow,
  formatInflectionFormRowsAsJson,
  type InflectionFormEditorRow,
  parseInflectionFormRows,
  serializeInflectionFormRows,
  validateInflectionFormRows,
} from '../../utils/inflectionProfileForms';
import { useLocaleDisplayNameResolver } from '../../utils/localeDisplayNames';
import { renderMf2TermMessage } from '../../utils/mf2TermRenderer';
import { useRepositorySelectionOptions } from '../../utils/repositorySelection';
import { downloadBlob } from '../workbench/workbench-import-export';
import { SettingsSubpageHeader } from './SettingsSubpageHeader';

const normalizeGlossaryName = (value: string) => value.trim().replace(/\s+/g, ' ');
const normalizeRepositoryName = (value: string) => value.trim().replace(/\s+/g, ' ');
type GlossaryScopeMode = 'GLOBAL' | 'SELECTED_REPOSITORIES';
type InflectionProfileAction = 'APPROVED' | 'DISABLED';
const INFLECTION_PROFILE_STATUSES: ApiInflectionProfileStatus[] = [
  'GENERATED',
  'REVIEW_NEEDED',
  'APPROVED',
  'DISABLED',
];

type ParsedInflectionProfile = ApiInflectionProfile & {
  formCount: number;
  diagnostics: unknown[];
  missingFormKeys: string[];
};

type InflectionProfileEditDraft = {
  status: ApiInflectionProfileStatus;
  morphologyJson: string;
  formRows: InflectionFormEditorRow[];
  formsJson: string;
  diagnosticsJson: string;
  provenanceJson: string;
};

type SerializedInflectionProfileEditDraft = {
  morphologyJson: string;
  formsJson: string;
  diagnosticsJson: string;
  provenanceJson: string;
};

type InflectionRenderResult = {
  kind: 'success' | 'error';
  message: string;
};

function parseJsonObjectKeyCount(json: string): number {
  try {
    const parsed = JSON.parse(json) as unknown;
    if (parsed == null || typeof parsed !== 'object' || Array.isArray(parsed)) {
      return 0;
    }
    return Object.keys(parsed).length;
  } catch {
    return 0;
  }
}

function parseJsonArray(json: string): unknown[] {
  try {
    const parsed = JSON.parse(json) as unknown;
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}

function inflectionMissingFormKeys(
  profile: ApiInflectionProfile,
  diagnostics: unknown[],
): string[] {
  const backendKeys = normalizedMissingFormKeys(profile.missingFormKeys);
  return backendKeys.length ? backendKeys : derivedMissingFormKeys(diagnostics);
}

function normalizedMissingFormKeys(values: unknown): string[] {
  if (!Array.isArray(values)) {
    return [];
  }
  return Array.from(
    new Set(values.filter((value): value is string => typeof value === 'string' && !!value.trim())),
  ).sort();
}

function derivedMissingFormKeys(diagnostics: unknown[]): string[] {
  const formKeys = new Set<string>();
  diagnostics.forEach((diagnostic) => {
    if (diagnostic == null || typeof diagnostic !== 'object' || Array.isArray(diagnostic)) {
      return;
    }
    const fields = diagnostic as Record<string, unknown>;
    if (
      (fields.code === 'missing-form-cell' || fields.reason === 'missing-form-cell') &&
      typeof fields.formKey === 'string' &&
      fields.formKey.trim()
    ) {
      formKeys.add(fields.formKey.trim());
    }
  });
  return Array.from(formKeys).sort();
}

function formatDiagnostic(diagnostic: unknown): string {
  if (diagnostic == null || typeof diagnostic !== 'object' || Array.isArray(diagnostic)) {
    return String(diagnostic);
  }
  const fields = diagnostic as Record<string, unknown>;
  const parts = [
    textField(fields.message),
    textField(fields.reason),
    textField(fields.code),
    textField(fields.formKey),
  ].filter(Boolean);
  return parts.length ? parts.join(' · ') : JSON.stringify(diagnostic);
}

function textField(value: unknown): string | null {
  return typeof value === 'string' && value.trim() ? value.trim() : null;
}

function formatJsonForEdit(json: string): string {
  try {
    return JSON.stringify(JSON.parse(json) as unknown, null, 2);
  } catch {
    return json;
  }
}

function canonicalJsonObject(json: string, label: string): string {
  const parsed = parseJsonText(json, label);
  if (parsed == null || typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw new Error(`${label} must be a JSON object.`);
  }
  return JSON.stringify(parsed);
}

function canonicalJsonArray(json: string, label: string): string {
  const parsed = parseJsonText(json, label);
  if (!Array.isArray(parsed)) {
    throw new Error(`${label} must be a JSON array.`);
  }
  return JSON.stringify(parsed);
}

function canonicalStringRecord(json: string, label: string): Record<string, string> {
  const parsed = parseJsonText(json, label);
  if (parsed == null || typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw new Error(`${label} must be a JSON object.`);
  }

  const values: Record<string, string> = {};
  for (const [key, value] of Object.entries(parsed)) {
    if (typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean') {
      values[key] = String(value);
      continue;
    }
    throw new Error(`${label}.${key} must be a string, number, or boolean.`);
  }
  return values;
}

function parseJsonText(json: string, label: string): unknown {
  try {
    return JSON.parse(json) as unknown;
  } catch {
    throw new Error(`${label} must be valid JSON.`);
  }
}

function editDraftFromProfile(profile: ApiInflectionProfile): InflectionProfileEditDraft {
  return {
    status: profile.status,
    morphologyJson: formatJsonForEdit(profile.morphologyJson),
    formRows: parseInflectionFormRows(profile.formsJson),
    formsJson: formatJsonForEdit(profile.formsJson),
    diagnosticsJson: formatJsonForEdit(profile.diagnosticsJson),
    provenanceJson: formatJsonForEdit(profile.provenanceJson),
  };
}

function serializeInflectionEditDraft(
  draft: InflectionProfileEditDraft,
): SerializedInflectionProfileEditDraft {
  return {
    morphologyJson: canonicalJsonObject(draft.morphologyJson, 'Morphology JSON'),
    formsJson: serializeInflectionFormRows(draft.formRows),
    diagnosticsJson: canonicalJsonArray(draft.diagnosticsJson, 'Diagnostics JSON'),
    provenanceJson: canonicalJsonObject(draft.provenanceJson, 'Provenance JSON'),
  };
}

function inflectionProfileStatusOptions(
  status: ApiInflectionProfileStatus,
): ApiInflectionProfileStatus[] {
  return INFLECTION_PROFILE_STATUSES.includes(status)
    ? INFLECTION_PROFILE_STATUSES
    : [status, ...INFLECTION_PROFILE_STATUSES];
}

async function readFileAsText(file: File): Promise<string> {
  if (typeof file.text === 'function') {
    return file.text();
  }

  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onerror = () => reject(reader.error ?? new Error('Failed to read file.'));
    reader.onload = () => resolve(typeof reader.result === 'string' ? reader.result : '');
    reader.readAsText(file);
  });
}

export function AdminGlossaryDetailPage() {
  const user = useUser();
  const isAdmin = user.role === 'ROLE_ADMIN';
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const params = useParams<{ glossaryId?: string }>();
  const { data: repositories } = useRepositories();
  const { data: locales, isLoading: localesLoading, isError: localesError } = useLocales();
  const resolveLocaleDisplayName = useLocaleDisplayNameResolver();
  const repositoryOptions = useRepositorySelectionOptions(repositories ?? []);

  const [nameDraft, setNameDraft] = useState('');
  const [backingRepositoryNameDraft, setBackingRepositoryNameDraft] = useState('');
  const [descriptionDraft, setDescriptionDraft] = useState('');
  const [enabledDraft, setEnabledDraft] = useState(true);
  const [priorityDraft, setPriorityDraft] = useState('0');
  const [scopeModeDraft, setScopeModeDraft] = useState<GlossaryScopeMode>('GLOBAL');
  const [localeTagsDraft, setLocaleTagsDraft] = useState<string[]>([]);
  const [repositoryIdsDraft, setRepositoryIdsDraft] = useState<number[]>([]);
  const [excludedRepositoryIdsDraft, setExcludedRepositoryIdsDraft] = useState<number[]>([]);
  const [showAllLocaleOptions, setShowAllLocaleOptions] = useState(false);
  const [selectedInflectionLocale, setSelectedInflectionLocale] = useState('');
  const inflectionProfilePackInputRef = useRef<HTMLInputElement | null>(null);
  const [includeApprovedInflectionProfiles, setIncludeApprovedInflectionProfiles] = useState(false);
  const [compiledInflectionPreview, setCompiledInflectionPreview] =
    useState<ApiCompiledInflectionProfilePackPreview | null>(null);
  const [inflectionRenderMessage, setInflectionRenderMessage] = useState(
    'Preview: {$item :term count=$count}.',
  );
  const [inflectionRenderTermId, setInflectionRenderTermId] = useState('');
  const [inflectionRenderVariablesJson, setInflectionRenderVariablesJson] =
    useState('{\n  "count": "1"\n}');
  const [inflectionRenderResult, setInflectionRenderResult] =
    useState<InflectionRenderResult | null>(null);
  const [editingInflectionProfile, setEditingInflectionProfile] =
    useState<ApiInflectionProfile | null>(null);
  const [inflectionEditDraft, setInflectionEditDraft] = useState<InflectionProfileEditDraft | null>(
    null,
  );
  const [inflectionEditError, setInflectionEditError] = useState<string | null>(null);
  const [statusNotice, setStatusNotice] = useState<{
    kind: 'success' | 'error';
    message: string;
  } | null>(null);
  const [inflectionNotice, setInflectionNotice] = useState<{
    kind: 'success' | 'error';
    message: string;
  } | null>(null);
  const [showDeleteConfirm, setShowDeleteConfirm] = useState(false);

  const parsedGlossaryId = useMemo(() => {
    const raw = params.glossaryId?.trim();
    if (!raw) {
      return null;
    }
    const next = Number(raw);
    return Number.isInteger(next) && next > 0 ? next : null;
  }, [params.glossaryId]);

  const glossaryQuery = useQuery({
    queryKey: ['glossary', parsedGlossaryId],
    queryFn: () => fetchGlossary(parsedGlossaryId as number),
    enabled: isAdmin && parsedGlossaryId != null,
    staleTime: 30_000,
  });

  const inflectionProfilesQuery = useQuery({
    queryKey: ['glossary-inflection-profiles', parsedGlossaryId, selectedInflectionLocale],
    queryFn: () =>
      fetchInflectionProfiles(parsedGlossaryId as number, selectedInflectionLocale.trim()),
    enabled: isAdmin && parsedGlossaryId != null && selectedInflectionLocale.trim().length > 0,
    staleTime: 10_000,
  });

  const updateMutation = useMutation({
    mutationFn: (payload: {
      name: string;
      backingRepositoryName?: string | null;
      description?: string | null;
      enabled: boolean;
      priority: number;
      scopeMode: GlossaryScopeMode;
      localeTags: string[];
      repositoryIds: number[];
      excludedRepositoryIds: number[];
    }) => updateGlossary(parsedGlossaryId as number, payload),
    onSuccess: async (updated) => {
      queryClient.setQueryData(['glossary', parsedGlossaryId], updated);
      await queryClient.invalidateQueries({ queryKey: ['glossaries'] });
      await queryClient.invalidateQueries({ queryKey: ['glossary', parsedGlossaryId] });
      await queryClient.invalidateQueries({ queryKey: REPOSITORIES_QUERY_KEY });
      setStatusNotice({
        kind: 'success',
        message: `Saved glossary ${updated.name}.`,
      });
    },
    onError: (error: Error) => {
      setStatusNotice({
        kind: 'error',
        message: error.message || 'Failed to save glossary.',
      });
    },
  });

  const deleteMutation = useMutation({
    mutationFn: () => deleteGlossary(parsedGlossaryId as number),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['glossaries'] });
      setShowDeleteConfirm(false);
      void navigate('/glossaries', { replace: true });
    },
    onError: (error: Error) => {
      setShowDeleteConfirm(false);
      setStatusNotice({
        kind: 'error',
        message: error.message || 'Failed to delete glossary.',
      });
    },
  });

  const reviewInflectionProfileMutation = useMutation({
    mutationFn: ({
      profile,
      status,
      morphologyJson,
      formsJson,
      diagnosticsJson,
      provenanceJson,
    }: {
      profile: ApiInflectionProfile;
      status: InflectionProfileAction;
      morphologyJson?: string | null;
      formsJson?: string | null;
      diagnosticsJson?: string | null;
      provenanceJson?: string | null;
      closeEditor?: boolean;
    }) =>
      reviewInflectionProfile(parsedGlossaryId as number, profile.tmTextUnitId, profile.localeTag, {
        status,
        morphologyJson,
        formsJson,
        diagnosticsJson: diagnosticsJson ?? (status === 'APPROVED' ? '[]' : null),
        provenanceJson,
      }),
    onSuccess: async (profile, variables) => {
      setCompiledInflectionPreview(null);
      setInflectionRenderResult(null);
      if (variables.closeEditor) {
        setEditingInflectionProfile(null);
        setInflectionEditDraft(null);
        setInflectionEditError(null);
      }
      await queryClient.invalidateQueries({
        queryKey: ['glossary-inflection-profiles', parsedGlossaryId, profile.localeTag],
      });
      setInflectionNotice({
        kind: 'success',
        message: `${profile.termId} is now ${profile.status}.`,
      });
    },
    onError: (error: Error) => {
      setInflectionNotice({
        kind: 'error',
        message: error.message || 'Failed to update inflection profile.',
      });
    },
  });

  const compiledInflectionPreviewMutation = useMutation({
    mutationFn: () =>
      fetchCompiledInflectionProfilePack(
        parsedGlossaryId as number,
        selectedInflectionLocale.trim(),
      ),
    onSuccess: (preview) => {
      setCompiledInflectionPreview(preview);
      setInflectionRenderResult(null);
      setInflectionNotice({
        kind: 'success',
        message: `Compiled pack preview is ready${
          preview.compositionMode ? ` (${preview.compositionMode})` : ''
        }: ${preview.approvedProfileCount} approved, ${preview.skippedProfileCount} skipped.`,
      });
    },
    onError: (error: Error) => {
      setCompiledInflectionPreview(null);
      setInflectionRenderResult(null);
      setInflectionNotice({
        kind: 'error',
        message: error.message || 'Compiled export preview failed.',
      });
    },
  });

  const exportInflectionProfilePackMutation = useMutation({
    mutationFn: (localeTag: string) =>
      exportInflectionProfilePack(parsedGlossaryId as number, localeTag),
    onSuccess: (pack, localeTag) => {
      downloadBlob(
        new Blob([pack.content], { type: 'application/json;charset=utf-8' }),
        pack.filename ?? `glossary-${parsedGlossaryId}-inflection-${localeTag}.json`,
      );
      setInflectionNotice({
        kind: 'success',
        message: `Exported ${pack.profileCount} inflection profiles for ${localeTag}.`,
      });
    },
    onError: (error: Error) => {
      setInflectionNotice({
        kind: 'error',
        message: error.message || 'Failed to export inflection profile pack.',
      });
    },
  });

  const importInflectionProfilesMutation = useMutation({
    mutationFn: ({ content }: { content: string; fileName: string }) =>
      importInflectionProfiles(parsedGlossaryId as number, content),
    onSuccess: async (result, { fileName }) => {
      setSelectedInflectionLocale(result.localeTag);
      setCompiledInflectionPreview(null);
      setInflectionRenderResult(null);
      await queryClient.invalidateQueries({
        queryKey: ['glossary-inflection-profiles', parsedGlossaryId, result.localeTag],
      });
      setInflectionNotice({
        kind: 'success',
        message: `Imported ${result.profileCount} inflection profiles from ${fileName}: ${result.createdProfileCount} created, ${result.updatedProfileCount} updated.`,
      });
    },
    onError: (error: Error) => {
      setInflectionNotice({
        kind: 'error',
        message: error.message || 'Failed to import inflection profiles.',
      });
    },
  });

  const upsertInflectionProfileMutation = useMutation({
    mutationFn: ({
      profile,
      draft,
    }: {
      profile: ApiInflectionProfile;
      draft: InflectionProfileEditDraft;
    }) =>
      upsertInflectionProfile(parsedGlossaryId as number, profile.tmTextUnitId, profile.localeTag, {
        status: draft.status,
        ...serializeInflectionEditDraft(draft),
      }),
    onSuccess: async (profile) => {
      setCompiledInflectionPreview(null);
      setInflectionRenderResult(null);
      setEditingInflectionProfile(null);
      setInflectionEditDraft(null);
      setInflectionEditError(null);
      await queryClient.invalidateQueries({
        queryKey: ['glossary-inflection-profiles', parsedGlossaryId, profile.localeTag],
      });
      setInflectionNotice({
        kind: 'success',
        message: `${profile.termId} profile saved as ${profile.status}.`,
      });
    },
    onError: (error: Error) => {
      setInflectionEditError(error.message || 'Failed to save inflection profile.');
    },
  });

  useEffect(() => {
    const glossary = glossaryQuery.data;
    if (!glossary) {
      return;
    }
    setNameDraft(glossary.name);
    setBackingRepositoryNameDraft(glossary.backingRepository.name);
    setDescriptionDraft(glossary.description ?? '');
    setEnabledDraft(glossary.enabled);
    setPriorityDraft(String(glossary.priority));
    setScopeModeDraft(glossary.scopeMode);
    setLocaleTagsDraft(glossary.localeTags);
    setRepositoryIdsDraft(
      glossary.repositories.map((repository) => repository.id).sort((a, b) => a - b),
    );
    setExcludedRepositoryIdsDraft(
      glossary.excludedRepositories.map((repository) => repository.id).sort((a, b) => a - b),
    );
    setShowAllLocaleOptions(false);
    setSelectedInflectionLocale((current) => {
      if (current && glossary.localeTags.includes(current)) {
        return current;
      }
      return glossary.localeTags[0] ?? '';
    });
  }, [glossaryQuery.data]);

  const isDirty = useMemo(() => {
    const glossary = glossaryQuery.data;
    if (!glossary) {
      return false;
    }
    if (normalizeGlossaryName(nameDraft) !== glossary.name) {
      return true;
    }
    if (normalizeRepositoryName(backingRepositoryNameDraft) !== glossary.backingRepository.name) {
      return true;
    }
    if ((descriptionDraft.trim() || null) !== (glossary.description ?? null)) {
      return true;
    }
    if (enabledDraft !== glossary.enabled) {
      return true;
    }
    if (priorityDraft.trim() !== String(glossary.priority)) {
      return true;
    }
    if (scopeModeDraft !== glossary.scopeMode) {
      return true;
    }
    const currentLocaleTags = [...glossary.localeTags].sort((a, b) =>
      a.localeCompare(b, undefined, { sensitivity: 'base' }),
    );
    const nextLocaleTags = [...localeTagsDraft].sort((a, b) =>
      a.localeCompare(b, undefined, { sensitivity: 'base' }),
    );
    if (
      currentLocaleTags.length !== nextLocaleTags.length ||
      currentLocaleTags.some(
        (tag, index) => tag.toLowerCase() !== nextLocaleTags[index]?.toLowerCase(),
      )
    ) {
      return true;
    }

    const currentRepositoryIds = glossary.repositories
      .map((repository) => repository.id)
      .sort((a, b) => a - b);
    if (
      currentRepositoryIds.length !== repositoryIdsDraft.length ||
      currentRepositoryIds.some((id, index) => id !== repositoryIdsDraft[index])
    ) {
      return true;
    }

    const currentExcludedIds = glossary.excludedRepositories
      .map((repository) => repository.id)
      .sort((a, b) => a - b);
    if (currentExcludedIds.length !== excludedRepositoryIdsDraft.length) {
      return true;
    }
    return currentExcludedIds.some((id, index) => id !== excludedRepositoryIdsDraft[index]);
  }, [
    descriptionDraft,
    enabledDraft,
    excludedRepositoryIdsDraft,
    glossaryQuery.data,
    backingRepositoryNameDraft,
    localeTagsDraft,
    nameDraft,
    priorityDraft,
    repositoryIdsDraft,
    scopeModeDraft,
  ]);

  const sortedRepositoryOptions = useMemo(
    () =>
      [...repositoryOptions].sort((first, second) =>
        first.name.localeCompare(second.name, undefined, { sensitivity: 'base' }),
      ),
    [repositoryOptions],
  );

  const localeOptions = useMemo(
    () =>
      (locales ?? [])
        .map((locale) => ({
          tag: locale.bcp47Tag,
          label: resolveLocaleDisplayName(locale.bcp47Tag),
        }))
        .sort((first, second) =>
          first.label.localeCompare(second.label, undefined, { sensitivity: 'base' }),
        ),
    [locales, resolveLocaleDisplayName],
  );

  const scopedLocaleOptionsState = useMemo(
    () =>
      buildScopedGlossaryLocaleOptions({
        allOptions: localeOptions,
        repositories: repositories ?? [],
        scopeMode: scopeModeDraft,
        repositoryIds: repositoryIdsDraft,
        excludedRepositoryIds: excludedRepositoryIdsDraft,
        selectedTags: localeTagsDraft,
        backingRepositoryId: glossaryQuery.data?.backingRepository.id ?? null,
      }),
    [
      excludedRepositoryIdsDraft,
      glossaryQuery.data?.backingRepository.id,
      localeOptions,
      localeTagsDraft,
      repositories,
      repositoryIdsDraft,
      scopeModeDraft,
    ],
  );

  const visibleLocaleOptions =
    showAllLocaleOptions || !scopedLocaleOptionsState.isFiltered
      ? localeOptions
      : scopedLocaleOptionsState.options;

  const localeCustomActions = useMemo(() => {
    if (!scopedLocaleOptionsState.isFiltered) {
      return undefined;
    }
    return [
      {
        label: showAllLocaleOptions ? 'Show scoped locales' : 'Show all locales',
        onClick: () => setShowAllLocaleOptions((previous) => !previous),
        ariaLabel: showAllLocaleOptions
          ? 'Show locales used by repositories in scope'
          : 'Show all available locales',
      },
    ];
  }, [scopedLocaleOptionsState.isFiltered, showAllLocaleOptions]);

  const parsedInflectionProfiles = useMemo<ParsedInflectionProfile[]>(() => {
    const profiles = inflectionProfilesQuery.data?.profiles ?? [];
    return profiles
      .map((profile) => {
        const diagnostics = parseJsonArray(profile.diagnosticsJson);
        return {
          ...profile,
          formCount: parseJsonObjectKeyCount(profile.formsJson),
          diagnostics,
          missingFormKeys: inflectionMissingFormKeys(profile, diagnostics),
        };
      })
      .sort((first, second) => first.termId.localeCompare(second.termId));
  }, [inflectionProfilesQuery.data]);

  const visibleInflectionProfiles = useMemo(
    () =>
      parsedInflectionProfiles.filter(
        (profile) =>
          includeApprovedInflectionProfiles ||
          profile.status !== 'APPROVED' ||
          profile.diagnostics.length > 0,
      ),
    [includeApprovedInflectionProfiles, parsedInflectionProfiles],
  );

  const compiledInflectionSkippedProfiles = useMemo(
    () => parsedInflectionProfiles.filter((profile) => profile.status === 'DISABLED'),
    [parsedInflectionProfiles],
  );

  useEffect(() => {
    if (!inflectionRenderTermId && parsedInflectionProfiles.length > 0) {
      setInflectionRenderTermId(parsedInflectionProfiles[0].termId);
    }
  }, [inflectionRenderTermId, parsedInflectionProfiles]);

  if (!isAdmin) {
    return <Navigate to="/repositories" replace />;
  }

  if (parsedGlossaryId == null) {
    return <Navigate to="/glossaries" replace />;
  }

  const handleSave = () => {
    const normalizedName = normalizeGlossaryName(nameDraft);
    if (!normalizedName) {
      setStatusNotice({ kind: 'error', message: 'Glossary name is required.' });
      return;
    }
    const normalizedBackingRepositoryName = normalizeRepositoryName(backingRepositoryNameDraft);
    if (!normalizedBackingRepositoryName) {
      setStatusNotice({ kind: 'error', message: 'Backing repository name is required.' });
      return;
    }
    const priority = Number.parseInt(priorityDraft.trim() || '0', 10);
    if (!Number.isFinite(priority)) {
      setStatusNotice({ kind: 'error', message: 'Priority must be a whole number.' });
      return;
    }
    updateMutation.mutate({
      name: normalizedName,
      backingRepositoryName: normalizedBackingRepositoryName,
      description: descriptionDraft.trim() || null,
      enabled: enabledDraft,
      priority,
      scopeMode: scopeModeDraft,
      localeTags: localeTagsDraft,
      repositoryIds: [...repositoryIdsDraft].sort((a, b) => a - b),
      excludedRepositoryIds: [...excludedRepositoryIdsDraft].sort((a, b) => a - b),
    });
  };

  const openInflectionProfileEditor = (profile: ApiInflectionProfile) => {
    setEditingInflectionProfile(profile);
    setInflectionEditDraft(editDraftFromProfile(profile));
    setInflectionEditError(null);
  };

  const closeInflectionProfileEditor = () => {
    if (upsertInflectionProfileMutation.isPending || reviewInflectionProfileMutation.isPending) {
      return;
    }
    setEditingInflectionProfile(null);
    setInflectionEditDraft(null);
    setInflectionEditError(null);
  };

  const handleSaveInflectionProfile = () => {
    if (!editingInflectionProfile || !inflectionEditDraft) {
      return;
    }
    setInflectionEditError(null);

    let serializedDraft: SerializedInflectionProfileEditDraft;
    try {
      serializedDraft = serializeInflectionEditDraft(inflectionEditDraft);
    } catch (error) {
      setInflectionEditError(
        error instanceof Error ? error.message : 'Inflection profile fields are invalid.',
      );
      return;
    }

    upsertInflectionProfileMutation.mutate({
      profile: editingInflectionProfile,
      draft: {
        ...inflectionEditDraft,
        formsJson: serializedDraft.formsJson,
      },
    });
  };

  const handleSaveAndApproveInflectionProfile = () => {
    if (!editingInflectionProfile || !inflectionEditDraft) {
      return;
    }
    setInflectionEditError(null);

    let serializedDraft: SerializedInflectionProfileEditDraft;
    try {
      serializedDraft = serializeInflectionEditDraft(inflectionEditDraft);
    } catch (error) {
      setInflectionEditError(
        error instanceof Error ? error.message : 'Inflection profile fields are invalid.',
      );
      return;
    }

    reviewInflectionProfileMutation.mutate({
      profile: editingInflectionProfile,
      status: 'APPROVED',
      morphologyJson: serializedDraft.morphologyJson,
      formsJson: serializedDraft.formsJson,
      diagnosticsJson: '[]',
      provenanceJson: serializedDraft.provenanceJson,
      closeEditor: true,
    });
  };

  const handleRenderInflectionPreview = () => {
    if (!compiledInflectionPreview) {
      setInflectionRenderResult({
        kind: 'error',
        message: 'Preview the compiled pack before rendering a term.',
      });
      return;
    }
    const termId = inflectionRenderTermId.trim();
    if (!termId) {
      setInflectionRenderResult({ kind: 'error', message: 'Term id is required.' });
      return;
    }

    try {
      const variables = canonicalStringRecord(inflectionRenderVariablesJson, 'Variables JSON');
      setInflectionRenderResult({
        kind: 'success',
        message: renderMf2TermMessage(
          compiledInflectionPreview.pack,
          inflectionRenderMessage,
          { item: termId },
          variables,
        ),
      });
    } catch (error) {
      setInflectionRenderResult({
        kind: 'error',
        message: error instanceof Error ? error.message : 'Render preview failed.',
      });
    }
  };

  const handleImportInflectionProfilePackFile = async (file: File) => {
    setInflectionNotice(null);
    setCompiledInflectionPreview(null);
    setInflectionRenderResult(null);

    try {
      const content = await readFileAsText(file);
      importInflectionProfilesMutation.mutate({ content, fileName: file.name });
    } catch (error) {
      setInflectionNotice({
        kind: 'error',
        message:
          error instanceof Error ? error.message : 'Failed to read inflection profile pack file.',
      });
    }
  };

  const updateInflectionEditDraft = (
    update: (current: InflectionProfileEditDraft) => InflectionProfileEditDraft,
  ) => {
    setInflectionEditError(null);
    setInflectionEditDraft((current) => (current ? update(current) : current));
  };

  const updateInflectionFormRows = (
    update: (current: InflectionFormEditorRow[]) => InflectionFormEditorRow[],
  ) => {
    updateInflectionEditDraft((current) => {
      const formRows = update(current.formRows);
      return {
        ...current,
        formRows,
        formsJson: formatInflectionFormRowsAsJson(formRows),
      };
    });
  };

  const inflectionFormRowsError = inflectionEditDraft
    ? validateInflectionFormRows(inflectionEditDraft.formRows)
    : null;

  return (
    <div className="settings-subpage">
      <SettingsSubpageHeader
        backTo="/glossaries"
        backLabel="Back to glossaries"
        context="Glossaries"
        title={
          glossaryQuery.data?.name
            ? `${glossaryQuery.data.name} settings`
            : `Glossary #${parsedGlossaryId} settings`
        }
      />
      <div className="settings-page settings-page--wide">
        <section className="settings-card">
          {glossaryQuery.isError ? (
            <p className="settings-hint is-error">
              {glossaryQuery.error instanceof Error
                ? glossaryQuery.error.message
                : 'Could not load glossary.'}
            </p>
          ) : glossaryQuery.isLoading ? (
            <p className="settings-hint">Loading glossary…</p>
          ) : (
            <>
              <div className="settings-field">
                <label className="settings-field__label" htmlFor="glossary-name">
                  Name
                </label>
                <input
                  id="glossary-name"
                  type="text"
                  className="settings-input"
                  value={nameDraft}
                  onChange={(event) => {
                    setNameDraft(event.target.value);
                    setStatusNotice(null);
                  }}
                />
              </div>
              <div className="settings-field">
                <label className="settings-field__label" htmlFor="glossary-description">
                  Description
                </label>
                <textarea
                  id="glossary-description"
                  className="settings-input"
                  value={descriptionDraft}
                  onChange={(event) => {
                    setDescriptionDraft(event.target.value);
                    setStatusNotice(null);
                  }}
                />
              </div>
              <div className="settings-grid settings-grid--two-column">
                <div className="settings-field">
                  <label className="settings-field__label" htmlFor="glossary-priority">
                    Priority
                  </label>
                  <input
                    id="glossary-priority"
                    type="number"
                    className="settings-input"
                    value={priorityDraft}
                    onChange={(event) => {
                      setPriorityDraft(event.target.value);
                      setStatusNotice(null);
                    }}
                  />
                </div>
                <div className="settings-field">
                  <label className="settings-field__label" htmlFor="glossary-scope-mode">
                    Scope
                  </label>
                  <select
                    id="glossary-scope-mode"
                    className="settings-input"
                    value={scopeModeDraft}
                    onChange={(event) => {
                      setScopeModeDraft(event.target.value as GlossaryScopeMode);
                      setStatusNotice(null);
                    }}
                  >
                    <option value="GLOBAL">Global</option>
                    <option value="SELECTED_REPOSITORIES">Selected repositories</option>
                  </select>
                </div>
              </div>
              <div className="settings-field">
                <label className="settings-toggle">
                  <input
                    type="checkbox"
                    checked={enabledDraft}
                    onChange={(event) => {
                      setEnabledDraft(event.target.checked);
                      setStatusNotice(null);
                    }}
                  />
                  <span>Enable this glossary</span>
                </label>
              </div>
              <div className="settings-field">
                <div className="settings-field__header">
                  <div className="settings-field__label">
                    {scopeModeDraft === 'GLOBAL'
                      ? 'Excluded repositories'
                      : 'Selected repositories'}
                  </div>
                </div>
                <RepositoryMultiSelect
                  label={
                    scopeModeDraft === 'GLOBAL' ? 'Excluded repositories' : 'Selected repositories'
                  }
                  options={sortedRepositoryOptions}
                  selectedIds={
                    scopeModeDraft === 'GLOBAL' ? excludedRepositoryIdsDraft : repositoryIdsDraft
                  }
                  onChange={(next) => {
                    if (scopeModeDraft === 'GLOBAL') {
                      setExcludedRepositoryIdsDraft([...next].sort((a, b) => a - b));
                    } else {
                      setRepositoryIdsDraft([...next].sort((a, b) => a - b));
                    }
                    setStatusNotice(null);
                  }}
                  className="settings-repository-select"
                  buttonAriaLabel="Select repositories for glossary"
                />
                <p className="settings-hint">
                  {scopeModeDraft === 'GLOBAL'
                    ? 'Global glossaries apply everywhere unless a repository is excluded.'
                    : 'Selected glossaries apply only to the repositories listed here.'}
                </p>
              </div>
              <div className="settings-field">
                <div className="settings-field__header">
                  <div className="settings-field__label">Locales</div>
                </div>
                <LocaleMultiSelect
                  label="Locales"
                  options={visibleLocaleOptions}
                  selectedTags={localeTagsDraft}
                  onChange={(next) => {
                    setLocaleTagsDraft(next);
                    setStatusNotice(null);
                  }}
                  className="settings-locale-select"
                  align="right"
                  buttonAriaLabel="Select glossary locales"
                  disabled={localesLoading || localesError}
                  customActions={localeCustomActions}
                />
                <p className="settings-hint">
                  Pick the glossary target locales explicitly. The source locale is managed by the
                  backing repository.
                </p>
                {localesLoading ? <p className="settings-hint">Loading locales…</p> : null}
                {localesError ? (
                  <p className="settings-hint is-error">Could not load locales.</p>
                ) : null}
              </div>
              <div className="settings-field">
                <div className="settings-field__header">
                  <label
                    className="settings-field__label"
                    htmlFor="glossary-backing-repository-name"
                  >
                    Backing repository
                  </label>
                </div>
                <input
                  id="glossary-backing-repository-name"
                  type="text"
                  className="settings-input"
                  value={backingRepositoryNameDraft}
                  onChange={(event) => {
                    setBackingRepositoryNameDraft(event.target.value);
                    setStatusNotice(null);
                  }}
                />
                <Link
                  to="/workbench"
                  state={buildGlossaryWorkbenchState({
                    glossaryId: glossaryQuery.data?.id,
                    glossaryName: glossaryQuery.data?.name,
                    backingRepositoryId: glossaryQuery.data?.backingRepository.id ?? 0,
                    backingRepositoryName: glossaryQuery.data?.backingRepository.name,
                    assetPath: glossaryQuery.data?.assetPath,
                    localeTags: glossaryQuery.data?.localeTags,
                  })}
                  className="settings-table__link"
                >
                  {glossaryQuery.data?.backingRepository.name}
                </Link>
              </div>
              {statusNotice ? (
                <p className={`settings-hint${statusNotice.kind === 'error' ? ' is-error' : ''}`}>
                  {statusNotice.message}
                </p>
              ) : null}
              <div className="settings-card__footer">
                <div className="settings-actions">
                  <button
                    type="button"
                    className="settings-button settings-button--primary"
                    onClick={handleSave}
                    disabled={updateMutation.isPending || !isDirty}
                  >
                    Save
                  </button>
                  <button
                    type="button"
                    className="settings-button settings-button--ghost"
                    onClick={() => setShowDeleteConfirm(true)}
                    disabled={updateMutation.isPending || deleteMutation.isPending}
                  >
                    Delete
                  </button>
                </div>
              </div>
            </>
          )}
        </section>
        <section className="settings-card" aria-labelledby="inflection-review-heading">
          <div className="settings-card__header">
            <div>
              <h2 id="inflection-review-heading">Inflection review</h2>
              <p className="settings-hint">
                Check generated term-form diagnostics before checked V0 compiled export.
              </p>
            </div>
            <div className="settings-actions settings-actions--wrap">
              <label className="settings-toggle">
                <input
                  type="checkbox"
                  checked={includeApprovedInflectionProfiles}
                  onChange={(event) => setIncludeApprovedInflectionProfiles(event.target.checked)}
                />
                <span>Show approved</span>
              </label>
              <button
                type="button"
                className="settings-button settings-button--ghost"
                onClick={() => void inflectionProfilesQuery.refetch()}
                disabled={!selectedInflectionLocale || inflectionProfilesQuery.isFetching}
              >
                Refresh
              </button>
            </div>
          </div>
          {glossaryQuery.isLoading ? (
            <p className="settings-hint">Loading glossary…</p>
          ) : glossaryQuery.data?.localeTags.length ? (
            <>
              <div className="settings-field">
                <label className="settings-field__label" htmlFor="inflection-review-locale">
                  Locale
                </label>
                <select
                  id="inflection-review-locale"
                  className="settings-input"
                  value={selectedInflectionLocale}
                  onChange={(event) => {
                    setSelectedInflectionLocale(event.target.value);
                    setInflectionNotice(null);
                    setCompiledInflectionPreview(null);
                    setInflectionRenderTermId('');
                    setInflectionRenderResult(null);
                  }}
                >
                  {glossaryQuery.data.localeTags.map((localeTag) => (
                    <option key={localeTag} value={localeTag}>
                      {resolveLocaleDisplayName(localeTag)}
                    </option>
                  ))}
                </select>
              </div>
              {inflectionNotice ? (
                <p
                  className={`settings-hint${inflectionNotice.kind === 'error' ? ' is-error' : ''}`}
                >
                  {inflectionNotice.message}
                </p>
              ) : null}
              <div className="settings-actions settings-actions--wrap">
                <button
                  type="button"
                  className="settings-button settings-button--ghost"
                  onClick={() => {
                    setInflectionNotice(null);
                    setInflectionRenderResult(null);
                    compiledInflectionPreviewMutation.mutate();
                  }}
                  disabled={
                    !selectedInflectionLocale ||
                    compiledInflectionPreviewMutation.isPending ||
                    inflectionProfilesQuery.isLoading
                  }
                >
                  {compiledInflectionPreviewMutation.isPending
                    ? 'Previewing compiled pack…'
                    : 'Preview compiled pack'}
                </button>
                <button
                  type="button"
                  className="settings-button settings-button--ghost"
                  onClick={() => {
                    setInflectionNotice(null);
                    exportInflectionProfilePackMutation.mutate(selectedInflectionLocale.trim());
                  }}
                  disabled={
                    !selectedInflectionLocale ||
                    exportInflectionProfilePackMutation.isPending ||
                    inflectionProfilesQuery.isLoading
                  }
                >
                  {exportInflectionProfilePackMutation.isPending
                    ? 'Exporting profile pack…'
                    : 'Export profile pack'}
                </button>
                <input
                  ref={inflectionProfilePackInputRef}
                  type="file"
                  accept=".json,application/json"
                  hidden
                  disabled={importInflectionProfilesMutation.isPending}
                  onChange={(event) => {
                    const file = event.currentTarget.files?.[0];
                    if (file) {
                      void handleImportInflectionProfilePackFile(file);
                    }
                    event.currentTarget.value = '';
                  }}
                />
                <button
                  type="button"
                  className="settings-button settings-button--ghost"
                  onClick={() => inflectionProfilePackInputRef.current?.click()}
                  disabled={importInflectionProfilesMutation.isPending}
                >
                  {importInflectionProfilesMutation.isPending
                    ? 'Importing profile pack…'
                    : 'Import profile pack'}
                </button>
                {compiledInflectionPreview ? (
                  <span className="settings-hint">
                    {compiledInflectionPreview.profileCount} runtime profiles,{' '}
                    {compiledInflectionPreview.formCount} forms,{' '}
                    {compiledInflectionPreview.content.length} bytes;{' '}
                    {compiledInflectionPreview.approvedProfileCount} approved,{' '}
                    {compiledInflectionPreview.skippedProfileCount} skipped
                    {compiledInflectionPreview.runtimeExport
                      ? `; runtime export: ${compiledInflectionPreview.runtimeExport}`
                      : ''}
                    {compiledInflectionPreview.compositionMode
                      ? `; composition: ${compiledInflectionPreview.compositionMode}`
                      : ''}
                    .
                  </span>
                ) : null}
              </div>
              {compiledInflectionPreview && compiledInflectionPreview.skippedProfileCount > 0 ? (
                <div className="settings-field">
                  <div className="settings-field__label">Skipped profile diagnostics</div>
                  {compiledInflectionSkippedProfiles.length === 0 ? (
                    <p className="settings-hint">
                      {compiledInflectionPreview.skippedProfileCount} profile
                      {compiledInflectionPreview.skippedProfileCount === 1 ? '' : 's'} skipped by
                      compiled export. Refresh inflection profiles for row-level diagnostics.
                    </p>
                  ) : (
                    <ul className="settings-note">
                      {compiledInflectionSkippedProfiles.map((profile) => (
                        <li key={`${profile.localeTag}:${profile.tmTextUnitId}:skipped`}>
                          <strong>{profile.termId}</strong>
                          {profile.missingFormKeys.length > 0 ? (
                            <> · Missing forms: {profile.missingFormKeys.join(', ')}</>
                          ) : null}
                          {profile.diagnostics.length > 0 ? (
                            <>
                              {' '}
                              · {profile.diagnostics.slice(0, 2).map(formatDiagnostic).join('; ')}
                            </>
                          ) : null}
                        </li>
                      ))}
                    </ul>
                  )}
                </div>
              ) : null}
              {compiledInflectionPreview ? (
                <div className="settings-field">
                  <div className="settings-field__header">
                    <label className="settings-field__label" htmlFor="inflection-render-message">
                      Render probe
                    </label>
                    <button
                      type="button"
                      className="settings-button settings-button--ghost"
                      onClick={handleRenderInflectionPreview}
                    >
                      Render
                    </button>
                  </div>
                  <textarea
                    id="inflection-render-message"
                    className="settings-input"
                    rows={2}
                    spellCheck={false}
                    value={inflectionRenderMessage}
                    onChange={(event) => {
                      setInflectionRenderMessage(event.target.value);
                      setInflectionRenderResult(null);
                    }}
                  />
                  <div className="settings-grid settings-grid--two-column">
                    <div className="settings-field">
                      <label className="settings-field__label" htmlFor="inflection-render-term-id">
                        Term id
                      </label>
                      <input
                        id="inflection-render-term-id"
                        type="text"
                        className="settings-input"
                        value={inflectionRenderTermId}
                        onChange={(event) => {
                          setInflectionRenderTermId(event.target.value);
                          setInflectionRenderResult(null);
                        }}
                      />
                    </div>
                    <div className="settings-field">
                      <label
                        className="settings-field__label"
                        htmlFor="inflection-render-variables"
                      >
                        Variables JSON
                      </label>
                      <textarea
                        id="inflection-render-variables"
                        className="settings-input"
                        rows={3}
                        spellCheck={false}
                        value={inflectionRenderVariablesJson}
                        onChange={(event) => {
                          setInflectionRenderVariablesJson(event.target.value);
                          setInflectionRenderResult(null);
                        }}
                      />
                    </div>
                  </div>
                  {inflectionRenderResult ? (
                    <p
                      className={`settings-hint${
                        inflectionRenderResult.kind === 'error' ? ' is-error' : ''
                      }`}
                    >
                      {inflectionRenderResult.message}
                    </p>
                  ) : (
                    <p className="settings-hint">
                      Uses the compiled pack preview, with {'{$item}'} bound to the selected term
                      id.
                    </p>
                  )}
                </div>
              ) : null}
              {inflectionProfilesQuery.isError ? (
                <p className="settings-hint is-error">
                  {inflectionProfilesQuery.error instanceof Error
                    ? inflectionProfilesQuery.error.message
                    : 'Could not load inflection profiles.'}
                </p>
              ) : inflectionProfilesQuery.isLoading ? (
                <p className="settings-hint">Loading inflection profiles…</p>
              ) : visibleInflectionProfiles.length === 0 ? (
                <p className="settings-hint">
                  {parsedInflectionProfiles.length === 0
                    ? 'No inflection profiles found for this locale.'
                    : 'No profiles need review for this locale.'}
                </p>
              ) : (
                <div className="settings-table-wrapper">
                  <table className="settings-table">
                    <thead>
                      <tr>
                        <th>Term</th>
                        <th>Status</th>
                        <th>Forms</th>
                        <th>Diagnostics</th>
                        <th>Actions</th>
                      </tr>
                    </thead>
                    <tbody>
                      {visibleInflectionProfiles.map((profile) => (
                        <tr key={`${profile.localeTag}:${profile.tmTextUnitId}`}>
                          <td>
                            <div>{profile.termId}</div>
                            <div className="settings-hint">{profile.source}</div>
                          </td>
                          <td>{profile.status}</td>
                          <td>{profile.formCount}</td>
                          <td>
                            {profile.diagnostics.length === 0 &&
                            profile.missingFormKeys.length === 0 ? (
                              <span className="settings-hint">None</span>
                            ) : (
                              <>
                                {profile.missingFormKeys.length > 0 ? (
                                  <div className="settings-hint">
                                    Missing forms: {profile.missingFormKeys.join(', ')}
                                  </div>
                                ) : null}
                                {profile.diagnostics.length > 0 ? (
                                  <ul className="settings-note">
                                    {profile.diagnostics.slice(0, 4).map((diagnostic, index) => (
                                      <li key={index}>{formatDiagnostic(diagnostic)}</li>
                                    ))}
                                    {profile.diagnostics.length > 4 ? (
                                      <li>{profile.diagnostics.length - 4} more</li>
                                    ) : null}
                                  </ul>
                                ) : null}
                              </>
                            )}
                          </td>
                          <td>
                            <div className="settings-actions settings-actions--wrap">
                              <button
                                type="button"
                                className="settings-button settings-button--ghost"
                                onClick={() => openInflectionProfileEditor(profile)}
                                disabled={
                                  upsertInflectionProfileMutation.isPending ||
                                  reviewInflectionProfileMutation.isPending
                                }
                              >
                                Edit profile
                              </button>
                              <button
                                type="button"
                                className="settings-button settings-button--primary"
                                onClick={() => {
                                  setInflectionNotice(null);
                                  reviewInflectionProfileMutation.mutate({
                                    profile,
                                    status: 'APPROVED',
                                  });
                                }}
                                disabled={reviewInflectionProfileMutation.isPending}
                              >
                                Approve
                              </button>
                              <button
                                type="button"
                                className="settings-button settings-button--ghost"
                                onClick={() => {
                                  setInflectionNotice(null);
                                  reviewInflectionProfileMutation.mutate({
                                    profile,
                                    status: 'DISABLED',
                                  });
                                }}
                                disabled={
                                  reviewInflectionProfileMutation.isPending ||
                                  profile.status === 'DISABLED'
                                }
                              >
                                Disable
                              </button>
                            </div>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </>
          ) : (
            <p className="settings-hint">
              Add at least one glossary locale before reviewing inflection profiles.
            </p>
          )}
        </section>
      </div>
      {editingInflectionProfile && inflectionEditDraft ? (
        <Modal
          open
          size="xl"
          ariaLabel={`Edit inflection profile for ${editingInflectionProfile.termId}`}
          onClose={closeInflectionProfileEditor}
        >
          <div className="modal__header">
            <div>
              <div className="modal__title">Edit inflection profile</div>
              <p className="settings-hint">
                {editingInflectionProfile.termId} · {editingInflectionProfile.localeTag}
              </p>
            </div>
          </div>
          <div className="modal__body">
            <p className="settings-hint">
              Edit the sidecar fields, then save through the validating profile service.
            </p>
            <div className="settings-field">
              <label className="settings-field__label" htmlFor="inflection-profile-status">
                Status
              </label>
              <select
                id="inflection-profile-status"
                className="settings-input"
                value={inflectionEditDraft.status}
                onChange={(event) =>
                  updateInflectionEditDraft((current) => ({
                    ...current,
                    status: event.target.value,
                  }))
                }
              >
                {inflectionProfileStatusOptions(inflectionEditDraft.status).map((status) => (
                  <option key={status} value={status}>
                    {status}
                  </option>
                ))}
              </select>
            </div>
            <div className="settings-grid settings-grid--two-column">
              <div className="settings-field">
                <label className="settings-field__label" htmlFor="inflection-profile-morphology">
                  Morphology JSON
                </label>
                <textarea
                  id="inflection-profile-morphology"
                  className="settings-input"
                  rows={10}
                  spellCheck={false}
                  value={inflectionEditDraft.morphologyJson}
                  onChange={(event) =>
                    updateInflectionEditDraft((current) => ({
                      ...current,
                      morphologyJson: event.target.value,
                    }))
                  }
                />
              </div>
              <div className="settings-field">
                <div className="settings-field__header">
                  <label className="settings-field__label" htmlFor="inflection-profile-forms-json">
                    Forms
                  </label>
                  <button
                    type="button"
                    className="settings-button settings-button--ghost"
                    onClick={() =>
                      updateInflectionFormRows((rows) => [
                        ...rows,
                        createEmptyInflectionFormRow(rows),
                      ])
                    }
                  >
                    Add form
                  </button>
                </div>
                {inflectionEditDraft.formRows.length === 0 ? (
                  <p className="settings-hint">No explicit forms yet.</p>
                ) : (
                  <div className="settings-table-wrapper">
                    <table className="settings-table">
                      <thead>
                        <tr>
                          <th>Key</th>
                          <th>Text</th>
                          <th>Actions</th>
                        </tr>
                      </thead>
                      <tbody>
                        {inflectionEditDraft.formRows.map((formRow) => (
                          <tr key={formRow.id}>
                            <td>
                              <input
                                type="text"
                                className="settings-input"
                                value={formRow.key}
                                onChange={(event) =>
                                  updateInflectionFormRows((rows) =>
                                    rows.map((row) =>
                                      row.id === formRow.id
                                        ? { ...row, key: event.target.value }
                                        : row,
                                    ),
                                  )
                                }
                                aria-label={`Form key for ${formRow.key || 'new form'}`}
                              />
                            </td>
                            <td>
                              <input
                                type="text"
                                className="settings-input"
                                value={formRow.value}
                                onChange={(event) =>
                                  updateInflectionFormRows((rows) =>
                                    rows.map((row) =>
                                      row.id === formRow.id
                                        ? { ...row, value: event.target.value }
                                        : row,
                                    ),
                                  )
                                }
                                aria-label={`Form text for ${formRow.key || 'new form'}`}
                              />
                            </td>
                            <td>
                              <button
                                type="button"
                                className="settings-button settings-button--ghost"
                                onClick={() =>
                                  updateInflectionFormRows((rows) =>
                                    rows.filter((row) => row.id !== formRow.id),
                                  )
                                }
                              >
                                Remove
                              </button>
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
                {inflectionFormRowsError ? (
                  <p className="settings-hint is-error">{inflectionFormRowsError}</p>
                ) : (
                  <p className="settings-hint">
                    Form keys use the compiled renderer shape, for example bare.singular or
                    count.other.
                  </p>
                )}
                <details>
                  <summary className="settings-hint">Forms JSON preview</summary>
                  <textarea
                    id="inflection-profile-forms-json"
                    className="settings-input"
                    rows={6}
                    spellCheck={false}
                    readOnly
                    value={inflectionEditDraft.formsJson}
                  />
                </details>
              </div>
              <div className="settings-field">
                <label className="settings-field__label" htmlFor="inflection-profile-diagnostics">
                  Diagnostics JSON
                </label>
                <textarea
                  id="inflection-profile-diagnostics"
                  className="settings-input"
                  rows={8}
                  spellCheck={false}
                  value={inflectionEditDraft.diagnosticsJson}
                  onChange={(event) =>
                    updateInflectionEditDraft((current) => ({
                      ...current,
                      diagnosticsJson: event.target.value,
                    }))
                  }
                />
              </div>
              <div className="settings-field">
                <label className="settings-field__label" htmlFor="inflection-profile-provenance">
                  Provenance JSON
                </label>
                <textarea
                  id="inflection-profile-provenance"
                  className="settings-input"
                  rows={8}
                  spellCheck={false}
                  value={inflectionEditDraft.provenanceJson}
                  onChange={(event) =>
                    updateInflectionEditDraft((current) => ({
                      ...current,
                      provenanceJson: event.target.value,
                    }))
                  }
                />
              </div>
            </div>
            {inflectionEditError ? (
              <p className="settings-hint is-error">{inflectionEditError}</p>
            ) : null}
          </div>
          <div className="modal__actions">
            <button
              type="button"
              className="modal__button"
              onClick={closeInflectionProfileEditor}
              disabled={
                upsertInflectionProfileMutation.isPending ||
                reviewInflectionProfileMutation.isPending
              }
            >
              Cancel
            </button>
            <button
              type="button"
              className="modal__button"
              onClick={handleSaveAndApproveInflectionProfile}
              disabled={
                upsertInflectionProfileMutation.isPending ||
                reviewInflectionProfileMutation.isPending ||
                !!inflectionFormRowsError
              }
            >
              {reviewInflectionProfileMutation.isPending ? 'Approving…' : 'Save and approve'}
            </button>
            <button
              type="button"
              className="modal__button modal__button--primary"
              onClick={handleSaveInflectionProfile}
              disabled={
                upsertInflectionProfileMutation.isPending ||
                reviewInflectionProfileMutation.isPending ||
                !!inflectionFormRowsError
              }
            >
              {upsertInflectionProfileMutation.isPending ? 'Saving…' : 'Save profile'}
            </button>
          </div>
        </Modal>
      ) : null}
      <ConfirmModal
        open={showDeleteConfirm}
        title="Delete glossary"
        body={
          glossaryQuery.data
            ? `Delete ${glossaryQuery.data.name}? This soft-deletes the glossary and its backing repository.`
            : ''
        }
        confirmLabel={deleteMutation.isPending ? 'Deleting…' : 'Delete'}
        cancelLabel="Cancel"
        onCancel={() => {
          if (!deleteMutation.isPending) {
            setShowDeleteConfirm(false);
          }
        }}
        onConfirm={() => deleteMutation.mutate()}
        requireText={glossaryQuery.data?.name}
      />
    </div>
  );
}
