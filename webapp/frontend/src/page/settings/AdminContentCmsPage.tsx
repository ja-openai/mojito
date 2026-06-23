import './settings-page.css';
import './admin-content-cms-page.css';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { type FormEvent, useCallback, useEffect, useRef, useState } from 'react';
import {
  type BlockerFunction,
  Navigate,
  useBeforeUnload,
  useBlocker,
  useNavigate,
} from 'react-router-dom';

import {
  type ApiCmsContentType,
  type ApiCmsEntry,
  type ApiCmsEntryStatus,
  type ApiCmsFieldType,
  type ApiCmsProjectCompleteness,
  type ApiCmsProjectDetail,
  type ApiCmsProjectsResponse,
  type ApiCmsProjectSummary,
  type ApiCmsPublishSnapshot,
  type ApiCmsVariantStatus,
  createCmsContentType,
  createCmsContentTypeField,
  createCmsEntry,
  createCmsProject,
  createCmsPublishRequestKey,
  createCmsVariant,
  fetchCmsProject,
  fetchCmsProjectCompleteness,
  fetchCmsProjects,
  fetchCmsPublishSnapshots,
  isCmsConflictError,
  publishCmsProject,
  unmapCmsFieldMapping,
  updateCmsContentType,
  updateCmsContentTypeField,
  updateCmsEntry,
  updateCmsProject,
  updateCmsVariant,
  upsertCmsFieldMapping,
} from '../../api/content-cms';
import { SearchControl } from '../../components/SearchControl';
import { useRepositories } from '../../hooks/useRepositories';
import { useUser } from '../../hooks/useUser';
import {
  ContentTypeEditForm,
  ContentTypeForm,
  ContentTypesTable,
  EntriesTable,
  EntryEditForm,
  EntryForm,
  FieldEditForm,
  FieldForm,
  MappingForm,
  ProjectCreateForm,
  ProjectEditForm,
  ProjectList,
  ProjectOverview,
  PublishForm,
  VariantEditForm,
  VariantForm,
} from './content-cms-admin-forms';
import {
  buildGeneratedCmsStringId,
  type ContentTypeDraft,
  type EntryDraft,
  type FieldDraft,
  findContentType,
  findEntry,
  findVariant,
  initialContentTypeDraft,
  initialEntryDraft,
  initialFieldDraft,
  initialMappingDraft,
  initialProjectDraft,
  initialProjectEditDraft,
  initialVariantDraft,
  type MappingDraft,
  normalizeSourceContent,
  normalizeText,
  parseOptionalNumber,
  parseRequiredNumber,
  type ProjectDraft,
  type ProjectEditDraft,
  type VariantDraft,
} from './content-cms-admin-types';
import {
  buildCmsPublishLocaleTagsKey,
  buildCmsPublishStateKey,
  clearCmsPublishIntentsForProject,
  type CmsPublishIntent,
  loadCmsPublishIntent,
  saveCmsPublishIntent,
} from './content-cms-publish-intent';
import { SettingsSubpageHeader } from './SettingsSubpageHeader';

type StatusNotice = {
  kind: 'success' | 'error';
  message: string;
};

type CmsDraftKey =
  | 'projectEdit'
  | 'contentTypeEdit'
  | 'fieldEdit'
  | 'entryEdit'
  | 'variantEdit'
  | 'mapping';

type CmsDraftSyncState = {
  dirty: boolean;
  expectedVersion: number | null;
};

type CmsDraftSyncStateByKey = Record<CmsDraftKey, CmsDraftSyncState>;

const CMS_QUERY_KEY = ['content-cms'] as const;
const EMPTY_PROJECTS: ApiCmsProjectSummary[] = [];
const EMPTY_CONTENT_TYPES: ApiCmsContentType[] = [];
const EMPTY_ENTRIES: ApiCmsEntry[] = [];
const CMS_PROJECT_SEARCH_LIMIT = 100;
const CMS_SNAPSHOT_HISTORY_PAGE_LIMIT = 10;
const CMS_DRAFT_KEYS: CmsDraftKey[] = [
  'projectEdit',
  'contentTypeEdit',
  'fieldEdit',
  'entryEdit',
  'variantEdit',
  'mapping',
];

function createCmsDraftSyncState(): CmsDraftSyncStateByKey {
  return {
    projectEdit: { dirty: false, expectedVersion: null },
    contentTypeEdit: { dirty: false, expectedVersion: null },
    fieldEdit: { dirty: false, expectedVersion: null },
    entryEdit: { dirty: false, expectedVersion: null },
    variantEdit: { dirty: false, expectedVersion: null },
    mapping: { dirty: false, expectedVersion: null },
  };
}

function resetCmsDraftSyncState(state: CmsDraftSyncStateByKey, ...draftKeys: CmsDraftKey[]) {
  (draftKeys.length === 0 ? CMS_DRAFT_KEYS : draftKeys).forEach((draftKey) => {
    state[draftKey] = { dirty: false, expectedVersion: null };
  });
}

function hydrateCmsDraftSyncState(
  state: CmsDraftSyncStateByKey,
  draftKey: CmsDraftKey,
  expectedVersion: number | null,
) {
  state[draftKey] = { dirty: false, expectedVersion };
}

function markCmsDraftDirty(state: CmsDraftSyncStateByKey, draftKey: CmsDraftKey) {
  state[draftKey].dirty = true;
}

function clearCmsDraftDirty(state: CmsDraftSyncStateByKey, draftKey: CmsDraftKey) {
  state[draftKey].dirty = false;
}

function cmsDraftExpectedVersion(
  state: CmsDraftSyncStateByKey,
  draftKey: CmsDraftKey,
  fallbackVersion: number,
) {
  return state[draftKey].expectedVersion ?? fallbackVersion;
}

function hasDirtyCmsDrafts(
  state: CmsDraftSyncStateByKey,
  draftKeys: readonly CmsDraftKey[] = CMS_DRAFT_KEYS,
) {
  return draftKeys.some((draftKey) => state[draftKey].dirty);
}

function hasCmsFormDraftChanges<T extends Record<string, unknown>>(draft: T, initialDraft: T) {
  return Object.keys(initialDraft).some((key) => draft[key] !== initialDraft[key]);
}

function projectMatchesSearch(project: ApiCmsProjectSummary, search: string) {
  const normalizedSearch = search.trim().toLowerCase();
  return (
    normalizedSearch.length === 0 ||
    project.name.toLowerCase().includes(normalizedSearch) ||
    project.projectKey.toLowerCase().includes(normalizedSearch)
  );
}

function sortProjectSummaries(projects: ApiCmsProjectSummary[]) {
  return [...projects].sort((left, right) => {
    const nameComparison = left.name.toLowerCase().localeCompare(right.name.toLowerCase());
    return nameComparison === 0 ? left.id - right.id : nameComparison;
  });
}

function updateCachedProjectSearch(
  current: ApiCmsProjectsResponse | undefined,
  search: string,
  previousProject: ApiCmsProjectSummary | null,
  nextProject: ApiCmsProjectSummary,
) {
  if (current == null) {
    return current;
  }

  const previousMatches = previousProject != null && projectMatchesSearch(previousProject, search);
  const nextMatches = projectMatchesSearch(nextProject, search);
  const visibleProjects = current.projects.filter((project) => project.id !== nextProject.id);
  const wasVisible = visibleProjects.length !== current.projects.length;
  let totalCountDelta = 0;
  if (previousProject == null) {
    totalCountDelta = nextMatches ? 1 : 0;
  } else if (previousMatches !== nextMatches) {
    totalCountDelta = nextMatches ? 1 : -1;
  }
  if (nextMatches) {
    visibleProjects.push(nextProject);
  }

  if (!wasVisible && totalCountDelta === 0 && visibleProjects.length === current.projects.length) {
    return current;
  }

  return {
    ...current,
    projects: sortProjectSummaries(visibleProjects).slice(0, CMS_PROJECT_SEARCH_LIMIT),
    totalCount: Math.max(0, current.totalCount + totalCountDelta),
  };
}

function upsertPublishSnapshot(
  snapshots: ApiCmsPublishSnapshot[],
  snapshot: ApiCmsPublishSnapshot,
) {
  return [
    snapshot,
    ...snapshots.filter((currentSnapshot) => currentSnapshot.id !== snapshot.id),
  ].sort((left, right) => right.snapshotVersion - left.snapshotVersion);
}

export function AdminContentCmsPage() {
  const user = useUser();
  const isAdmin = user.role === 'ROLE_ADMIN';
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const {
    data: repositories,
    isLoading: repositoriesLoading,
    isError: repositoriesError,
    refetch: refetchRepositories,
  } = useRepositories(isAdmin);

  const [projectSearch, setProjectSearch] = useState('');
  const [selectedProjectId, setSelectedProjectId] = useState<number | null>(null);
  const [notice, setNotice] = useState<StatusNotice | null>(null);
  const [projectDraft, setProjectDraft] = useState<ProjectDraft>(initialProjectDraft);
  const [contentTypeDraft, setContentTypeDraft] =
    useState<ContentTypeDraft>(initialContentTypeDraft);
  const [fieldDraft, setFieldDraft] = useState<FieldDraft>(initialFieldDraft);
  const [entryDraft, setEntryDraft] = useState<EntryDraft>(initialEntryDraft);
  const [variantDraft, setVariantDraft] = useState<VariantDraft>(initialVariantDraft);
  const [mappingDraft, setMappingDraft] = useState<MappingDraft>(initialMappingDraft);
  const [projectEditDraft, setProjectEditDraft] =
    useState<ProjectEditDraft>(initialProjectEditDraft);
  const [contentTypeEditId, setContentTypeEditId] = useState('');
  const [contentTypeEditDraft, setContentTypeEditDraft] =
    useState<ContentTypeDraft>(initialContentTypeDraft);
  const [fieldEditId, setFieldEditId] = useState('');
  const [fieldEditDraft, setFieldEditDraft] = useState<FieldDraft>(initialFieldDraft);
  const [entryEditId, setEntryEditId] = useState('');
  const [entryEditDraft, setEntryEditDraft] = useState<EntryDraft>(initialEntryDraft);
  const [variantEditId, setVariantEditId] = useState('');
  const [variantEditDraft, setVariantEditDraft] = useState<VariantDraft>(initialVariantDraft);
  const [completenessResult, setCompletenessResult] = useState<ApiCmsProjectCompleteness | null>(
    null,
  );
  const [publishLocales, setPublishLocales] = useState('');
  const [publishSnapshots, setPublishSnapshots] = useState<ApiCmsPublishSnapshot[]>([]);
  const [hasMorePublishSnapshots, setHasMorePublishSnapshots] = useState(false);
  const [nextBeforePublishSnapshotVersion, setNextBeforePublishSnapshotVersion] = useState<
    number | null
  >(null);
  const selectedProjectIdRef = useRef<number | null>(null);
  const publishIntentRef = useRef<CmsPublishIntent | null>(null);
  const draftSyncRef = useRef(createCmsDraftSyncState());

  const projectsQuery = useQuery({
    queryKey: [...CMS_QUERY_KEY, 'projects', projectSearch],
    queryFn: () =>
      fetchCmsProjects({ search: projectSearch, enabled: null, limit: CMS_PROJECT_SEARCH_LIMIT }),
    enabled: isAdmin,
    staleTime: 30_000,
  });

  const projects = projectsQuery.data?.projects ?? EMPTY_PROJECTS;
  const visibleRepositories = repositories ?? [];
  const canCreateProject =
    !repositoriesLoading && !repositoriesError && visibleRepositories.length > 0;

  useEffect(() => {
    if (selectedProjectId == null && projects.length > 0) {
      setSelectedProjectId(projects[0].id);
    }
  }, [projects, selectedProjectId]);

  useEffect(() => {
    selectedProjectIdRef.current = selectedProjectId;
  }, [selectedProjectId]);

  useEffect(() => {
    resetCmsDraftSyncState(draftSyncRef.current);
    setContentTypeDraft(initialContentTypeDraft);
    setFieldDraft(initialFieldDraft);
    setEntryDraft(initialEntryDraft);
    setVariantDraft(initialVariantDraft);
    setMappingDraft(initialMappingDraft);
    setCompletenessResult(null);
    setPublishLocales('');
    publishIntentRef.current = null;
  }, [selectedProjectId]);

  const projectQuery = useQuery({
    queryKey: [...CMS_QUERY_KEY, 'project', selectedProjectId],
    queryFn: () => fetchCmsProject(selectedProjectId ?? 0),
    enabled: isAdmin && selectedProjectId != null,
    staleTime: 30_000,
  });

  const projectDetail = projectQuery.data ?? null;
  const contentTypes = projectDetail?.contentTypes ?? EMPTY_CONTENT_TYPES;
  const entries = projectDetail?.entries ?? EMPTY_ENTRIES;
  const selectedMappingEntry = findEntry(entries, mappingDraft.entryId);
  const selectedMappingVariant = findVariant(selectedMappingEntry, mappingDraft.variantId);
  const selectedMappingContentType = selectedMappingEntry
    ? findContentType(contentTypes, selectedMappingEntry.contentTypeId)
    : null;
  const selectedMappingFields =
    selectedMappingContentType?.fields.filter((field) => field.localizable) ?? [];
  const selectedMappingField =
    selectedMappingFields.find((field) => String(field.id) === mappingDraft.fieldId) ?? null;
  const selectedMapping =
    selectedMappingVariant?.fieldMappings.find(
      (mapping) => mapping.fieldId === selectedMappingField?.id,
    ) ?? null;
  const selectedGeneratedMappingStringId = buildGeneratedCmsStringId(
    projectDetail?.project.projectKey,
    selectedMappingEntry?.entryKey,
    selectedMappingVariant?.variantKey,
    selectedMappingField?.fieldKey,
  );
  const selectedMappingSource =
    selectedMapping == null || selectedMapping.stringId === selectedGeneratedMappingStringId
      ? 'GENERATED'
      : 'TM_TEXT_UNIT_ID';
  const selectedEditContentType =
    contentTypes.find((contentType) => String(contentType.id) === contentTypeEditId) ?? null;
  const selectedEditField =
    selectedEditContentType?.fields.find((field) => String(field.id) === fieldEditId) ?? null;
  const selectedEditEntry = entries.find((entry) => String(entry.id) === entryEditId) ?? null;
  const selectedEditVariant =
    selectedEditEntry?.variants.find((variant) => String(variant.id) === variantEditId) ?? null;

  useEffect(() => {
    if (projectDetail == null) {
      setPublishSnapshots([]);
      setHasMorePublishSnapshots(false);
      setNextBeforePublishSnapshotVersion(null);
      resetCmsDraftSyncState(draftSyncRef.current);
      setProjectEditDraft(initialProjectEditDraft);
      setContentTypeEditId('');
      setFieldEditId('');
      setEntryEditId('');
      setVariantEditId('');
      return;
    }
    setPublishSnapshots(projectDetail.publishSnapshots);
    setHasMorePublishSnapshots(projectDetail.hasMorePublishSnapshots);
    setNextBeforePublishSnapshotVersion(projectDetail.nextBeforePublishSnapshotVersion ?? null);
    if (!draftSyncRef.current.projectEdit.dirty) {
      hydrateCmsDraftSyncState(
        draftSyncRef.current,
        'projectEdit',
        projectDetail.project.entityVersion,
      );
      setProjectEditDraft({
        name: projectDetail.project.name,
        description: projectDetail.project.description ?? '',
        deliveryHint: projectDetail.project.deliveryHint,
        enabled: projectDetail.project.enabled,
      });
    }
    setContentTypeEditId((current) => {
      if (contentTypes.some((contentType) => String(contentType.id) === current)) {
        return current;
      }
      resetCmsDraftSyncState(draftSyncRef.current, 'contentTypeEdit', 'fieldEdit');
      return contentTypes[0]?.id.toString() ?? '';
    });
    setEntryEditId((current) => {
      if (entries.some((entry) => String(entry.id) === current)) {
        return current;
      }
      resetCmsDraftSyncState(draftSyncRef.current, 'entryEdit', 'variantEdit');
      return entries[0]?.id.toString() ?? '';
    });
  }, [contentTypes, entries, projectDetail]);

  useEffect(() => {
    if (selectedEditContentType == null) {
      resetCmsDraftSyncState(draftSyncRef.current, 'contentTypeEdit', 'fieldEdit');
      setContentTypeEditDraft(initialContentTypeDraft);
      setFieldEditId('');
      return;
    }
    if (!draftSyncRef.current.contentTypeEdit.dirty) {
      hydrateCmsDraftSyncState(
        draftSyncRef.current,
        'contentTypeEdit',
        selectedEditContentType.entityVersion,
      );
      setContentTypeEditDraft({
        typeKey: selectedEditContentType.typeKey,
        name: selectedEditContentType.name,
        description: selectedEditContentType.description ?? '',
        schemaVersion: String(selectedEditContentType.schemaVersion),
        metadataSchemaJson: selectedEditContentType.metadataSchemaJson ?? '',
      });
    }
    setFieldEditId((current) => {
      if (selectedEditContentType.fields.some((field) => String(field.id) === current)) {
        return current;
      }
      resetCmsDraftSyncState(draftSyncRef.current, 'fieldEdit');
      return selectedEditContentType.fields[0]?.id.toString() ?? '';
    });
  }, [selectedEditContentType]);

  useEffect(() => {
    if (selectedEditField == null) {
      resetCmsDraftSyncState(draftSyncRef.current, 'fieldEdit');
      setFieldEditDraft(initialFieldDraft);
      return;
    }
    if (!draftSyncRef.current.fieldEdit.dirty) {
      hydrateCmsDraftSyncState(draftSyncRef.current, 'fieldEdit', selectedEditField.entityVersion);
      setFieldEditDraft({
        contentTypeId: String(selectedEditField.contentTypeId),
        fieldKey: selectedEditField.fieldKey,
        name: selectedEditField.name,
        description: selectedEditField.description ?? '',
        fieldType: selectedEditField.fieldType,
        required: selectedEditField.required,
        sortOrder: String(selectedEditField.sortOrder),
      });
    }
  }, [selectedEditField]);

  useEffect(() => {
    if (selectedEditEntry == null) {
      resetCmsDraftSyncState(draftSyncRef.current, 'entryEdit', 'variantEdit');
      setEntryEditDraft(initialEntryDraft);
      setVariantEditId('');
      return;
    }
    if (!draftSyncRef.current.entryEdit.dirty) {
      hydrateCmsDraftSyncState(draftSyncRef.current, 'entryEdit', selectedEditEntry.entityVersion);
      setEntryEditDraft({
        contentTypeId: String(selectedEditEntry.contentTypeId),
        entryKey: selectedEditEntry.entryKey,
        name: selectedEditEntry.name,
        description: selectedEditEntry.description ?? '',
        status: selectedEditEntry.status,
        metadataJson: selectedEditEntry.metadataJson ?? '',
      });
    }
    setVariantEditId((current) => {
      if (selectedEditEntry.variants.some((variant) => String(variant.id) === current)) {
        return current;
      }
      resetCmsDraftSyncState(draftSyncRef.current, 'variantEdit');
      return selectedEditEntry.variants[0]?.id.toString() ?? '';
    });
  }, [selectedEditEntry]);

  useEffect(() => {
    if (selectedEditVariant == null) {
      resetCmsDraftSyncState(draftSyncRef.current, 'variantEdit');
      setVariantEditDraft(initialVariantDraft);
      return;
    }
    if (!draftSyncRef.current.variantEdit.dirty) {
      hydrateCmsDraftSyncState(
        draftSyncRef.current,
        'variantEdit',
        selectedEditVariant.entityVersion,
      );
      setVariantEditDraft({
        entryId: String(selectedEditVariant.entryId),
        variantKey: selectedEditVariant.variantKey,
        name: selectedEditVariant.name,
        candidateGroupKey: selectedEditVariant.candidateGroupKey ?? '',
        status: selectedEditVariant.status,
        metadataJson: selectedEditVariant.metadataJson ?? '',
        sortOrder: String(selectedEditVariant.sortOrder),
      });
    }
  }, [selectedEditVariant]);

  useEffect(() => {
    if (draftSyncRef.current.mapping.dirty) {
      return;
    }
    hydrateCmsDraftSyncState(
      draftSyncRef.current,
      'mapping',
      selectedMapping?.entityVersion ?? null,
    );
    setMappingDraft((current) => ({
      ...current,
      mappingSource: selectedMappingSource,
      tmTextUnitId:
        selectedMappingSource === 'TM_TEXT_UNIT_ID'
          ? String(selectedMapping?.tmTextUnitId ?? '')
          : '',
      stringId: '',
      sourceContent: selectedMapping?.sourceContent ?? '',
      sourceComment: selectedMapping?.sourceComment ?? '',
    }));
  }, [
    selectedMapping,
    selectedMappingField?.id,
    selectedMappingSource,
    selectedMappingVariant?.id,
  ]);

  const invalidateCmsQueries = () => {
    void queryClient.invalidateQueries({ queryKey: CMS_QUERY_KEY });
  };

  const isSelectedProject = (projectId: number) => selectedProjectIdRef.current === projectId;

  const clearStaleDerivedState = (projectId: number | null) => {
    setCompletenessResult(null);
    if (projectId != null) {
      clearCmsPublishIntentsForProject(projectId);
    }
    publishIntentRef.current = null;
  };

  useEffect(() => {
    if (
      projectDetail == null ||
      completenessResult == null ||
      completenessResult.authoringSha256 === projectDetail.authoringSha256
    ) {
      return;
    }
    setCompletenessResult(null);
    clearCmsPublishIntentsForProject(projectDetail.project.id);
    publishIntentRef.current = null;
    setNotice({
      kind: 'error',
      message: 'Content changed since it was validated. Validate package again.',
    });
  }, [completenessResult, projectDetail]);

  const handleDetailSuccess = (
    detail: ApiCmsProjectDetail,
    message: string,
    afterSuccess?: () => void,
  ) => {
    const previousProject =
      projectDetail?.project.id === detail.project.id ? projectDetail.project : null;
    queryClient.setQueryData<ApiCmsProjectDetail>(
      [...CMS_QUERY_KEY, 'project', detail.project.id],
      detail,
    );
    queryClient
      .getQueryCache()
      .findAll({ queryKey: [...CMS_QUERY_KEY, 'projects'] })
      .forEach((query) => {
        const cachedSearch = typeof query.queryKey[2] === 'string' ? query.queryKey[2] : '';
        queryClient.setQueryData<ApiCmsProjectsResponse>(query.queryKey, (current) =>
          updateCachedProjectSearch(current, cachedSearch, previousProject, detail.project),
        );
      });
    setSelectedProjectId(detail.project.id);
    setNotice({ kind: 'success', message });
    clearStaleDerivedState(detail.project.id);
    afterSuccess?.();
    invalidateCmsQueries();
  };

  const handleMutationError = (
    error: Error,
    fallbackMessage: string,
    conflictingDraftKey?: CmsDraftKey,
  ) => {
    if (isCmsConflictError(error)) {
      if (conflictingDraftKey != null) {
        resetCmsDraftSyncState(draftSyncRef.current, conflictingDraftKey);
      }
      clearStaleDerivedState(selectedProjectId);
      invalidateCmsQueries();
      setNotice({
        kind: 'error',
        message:
          'Content changed since it was loaded. Refreshing current CMS data; review and save again.',
      });
      return;
    }
    setNotice({ kind: 'error', message: error.message || fallbackMessage });
  };

  const createProjectMutation = useMutation({
    mutationFn: createCmsProject,
    onSuccess: (detail) => {
      handleDetailSuccess(detail, `Created content project ${detail.project.name}.`, () => {
        setProjectDraft(initialProjectDraft);
      });
    },
    onError: (error: Error) => {
      handleMutationError(error, 'Failed to create content project.');
    },
  });

  const createContentTypeMutation = useMutation({
    mutationFn: ({
      projectId,
      payload,
    }: {
      projectId: number;
      payload: {
        typeKey: string;
        name: string;
        description?: string | null;
        metadataSchemaJson?: string | null;
      };
    }) => createCmsContentType(projectId, payload),
    onSuccess: (detail) => {
      handleDetailSuccess(detail, `Created content type in ${detail.project.name}.`, () => {
        setContentTypeDraft(initialContentTypeDraft);
      });
    },
    onError: (error: Error) => {
      handleMutationError(error, 'Failed to create content type.');
    },
  });

  const updateProjectMutation = useMutation({
    mutationFn: ({
      projectId,
      payload,
    }: {
      projectId: number;
      payload: {
        name: string;
        description?: string | null;
        enabled: boolean;
        deliveryHint?: string;
        expectedVersion: number;
      };
    }) => updateCmsProject(projectId, payload),
    onSuccess: (detail) => {
      clearCmsDraftDirty(draftSyncRef.current, 'projectEdit');
      handleDetailSuccess(detail, `Updated content project ${detail.project.name}.`);
    },
    onError: (error: Error) => {
      handleMutationError(error, 'Failed to update content project.', 'projectEdit');
    },
  });

  const updateContentTypeMutation = useMutation({
    mutationFn: ({
      contentTypeId,
      payload,
    }: {
      contentTypeId: number;
      payload: {
        name: string;
        description?: string | null;
        metadataSchemaJson?: string | null;
        expectedVersion: number;
      };
    }) => updateCmsContentType(contentTypeId, payload),
    onSuccess: (detail) => {
      clearCmsDraftDirty(draftSyncRef.current, 'contentTypeEdit');
      handleDetailSuccess(detail, `Updated content type in ${detail.project.name}.`);
    },
    onError: (error: Error) => {
      handleMutationError(error, 'Failed to update content type.', 'contentTypeEdit');
    },
  });

  const updateFieldMutation = useMutation({
    mutationFn: ({
      fieldId,
      payload,
    }: {
      fieldId: number;
      payload: {
        name: string;
        description?: string | null;
        fieldType: ApiCmsFieldType;
        localizable: boolean;
        required: boolean;
        sortOrder?: number;
        expectedVersion: number;
      };
    }) => updateCmsContentTypeField(fieldId, payload),
    onSuccess: (detail) => {
      clearCmsDraftDirty(draftSyncRef.current, 'fieldEdit');
      handleDetailSuccess(detail, `Updated field in ${detail.project.name}.`);
    },
    onError: (error: Error) => {
      handleMutationError(error, 'Failed to update field.', 'fieldEdit');
    },
  });

  const updateEntryMutation = useMutation({
    mutationFn: ({
      entryId,
      payload,
    }: {
      entryId: number;
      payload: {
        name: string;
        description?: string | null;
        status: ApiCmsEntryStatus;
        metadataJson?: string | null;
        expectedVersion: number;
      };
    }) => updateCmsEntry(entryId, payload),
    onSuccess: (detail) => {
      clearCmsDraftDirty(draftSyncRef.current, 'entryEdit');
      handleDetailSuccess(detail, `Updated entry in ${detail.project.name}.`);
    },
    onError: (error: Error) => {
      handleMutationError(error, 'Failed to update entry.', 'entryEdit');
    },
  });

  const updateVariantMutation = useMutation({
    mutationFn: ({
      variantId,
      payload,
    }: {
      variantId: number;
      payload: {
        name: string;
        candidateGroupKey?: string | null;
        status: ApiCmsVariantStatus;
        metadataJson?: string | null;
        sortOrder?: number;
        expectedVersion: number;
      };
    }) => updateCmsVariant(variantId, payload),
    onSuccess: (detail) => {
      clearCmsDraftDirty(draftSyncRef.current, 'variantEdit');
      handleDetailSuccess(detail, `Updated variant in ${detail.project.name}.`);
    },
    onError: (error: Error) => {
      handleMutationError(error, 'Failed to update variant.', 'variantEdit');
    },
  });

  const createFieldMutation = useMutation({
    mutationFn: ({
      contentTypeId,
      payload,
    }: {
      contentTypeId: number;
      payload: {
        fieldKey: string;
        name: string;
        description?: string | null;
        fieldType: ApiCmsFieldType;
        localizable: boolean;
        required: boolean;
        sortOrder?: number | null;
      };
    }) => createCmsContentTypeField(contentTypeId, payload),
    onSuccess: (detail) => {
      handleDetailSuccess(detail, `Created field in ${detail.project.name}.`, () => {
        setFieldDraft((current) => ({
          ...initialFieldDraft,
          contentTypeId: current.contentTypeId,
        }));
      });
    },
    onError: (error: Error) => {
      handleMutationError(error, 'Failed to create field.');
    },
  });

  const createEntryMutation = useMutation({
    mutationFn: ({
      projectId,
      payload,
    }: {
      projectId: number;
      payload: {
        contentTypeId: number;
        entryKey: string;
        name: string;
        description?: string | null;
        status: ApiCmsEntryStatus;
        metadataJson?: string | null;
      };
    }) => createCmsEntry(projectId, payload),
    onSuccess: (detail) => {
      handleDetailSuccess(detail, `Created entry in ${detail.project.name}.`, () => {
        setEntryDraft((current) => ({
          ...initialEntryDraft,
          contentTypeId: current.contentTypeId,
        }));
      });
    },
    onError: (error: Error) => {
      handleMutationError(error, 'Failed to create entry.');
    },
  });

  const createVariantMutation = useMutation({
    mutationFn: ({
      entryId,
      payload,
    }: {
      entryId: number;
      payload: {
        variantKey: string;
        name: string;
        candidateGroupKey?: string | null;
        status: ApiCmsVariantStatus;
        metadataJson?: string | null;
        sortOrder?: number | null;
      };
    }) => createCmsVariant(entryId, payload),
    onSuccess: (detail) => {
      handleDetailSuccess(detail, `Created variant in ${detail.project.name}.`, () => {
        setVariantDraft((current) => ({
          ...initialVariantDraft,
          entryId: current.entryId,
        }));
      });
    },
    onError: (error: Error) => {
      handleMutationError(error, 'Failed to create variant.');
    },
  });

  const upsertMappingMutation = useMutation({
    mutationFn: ({
      variantId,
      payload,
    }: {
      variantId: number;
      payload: {
        fieldId: number;
        tmTextUnitId?: number | null;
        stringId?: string | null;
        sourceContent?: string | null;
        sourceComment?: string | null;
        expectedVersion?: number | null;
      };
    }) => upsertCmsFieldMapping(variantId, payload),
    onSuccess: (detail) => {
      clearCmsDraftDirty(draftSyncRef.current, 'mapping');
      handleDetailSuccess(detail, `Mapped field in ${detail.project.name}.`, () => {
        setMappingDraft((current) => ({
          ...initialMappingDraft,
          entryId: current.entryId,
          variantId: current.variantId,
        }));
      });
    },
    onError: (error: Error) => {
      handleMutationError(error, 'Failed to map field.', 'mapping');
    },
  });

  const unmapMappingMutation = useMutation({
    mutationFn: ({ mappingId, expectedVersion }: { mappingId: number; expectedVersion: number }) =>
      unmapCmsFieldMapping(mappingId, expectedVersion),
    onSuccess: (detail) => {
      clearCmsDraftDirty(draftSyncRef.current, 'mapping');
      handleDetailSuccess(detail, `Unmapped field in ${detail.project.name}.`);
    },
    onError: (error: Error) => {
      handleMutationError(error, 'Failed to unmap field.', 'mapping');
    },
  });

  const completenessMutation = useMutation({
    mutationFn: ({ projectId, locales }: { projectId: number; locales: string }) =>
      fetchCmsProjectCompleteness(projectId, locales),
    onMutate: () => {
      setCompletenessResult(null);
    },
    onSuccess: (response, variables) => {
      if (!isSelectedProject(variables.projectId)) {
        return;
      }
      if (projectDetail != null && response.authoringSha256 !== projectDetail.authoringSha256) {
        clearStaleDerivedState(response.projectId);
        invalidateCmsQueries();
        setNotice({
          kind: 'error',
          message:
            'Content changed since it was loaded. Refreshing current CMS data; review and validate again.',
        });
        return;
      }
      setCompletenessResult(response);
      setNotice({
        kind: 'success',
        message: `Validated publish package for ${response.projectKey}.`,
      });
    },
    onError: (error: Error, variables) => {
      if (!isSelectedProject(variables.projectId)) {
        return;
      }
      setNotice({ kind: 'error', message: error.message || 'Completeness validation failed.' });
    },
  });

  const publishMutation = useMutation({
    mutationFn: ({
      projectId,
      localeTags,
      expectedAuthoringSha256,
      expectedPackageSha256,
      publishRequestKey,
    }: {
      projectId: number;
      localeTags: string[];
      expectedAuthoringSha256: string;
      expectedPackageSha256: string;
      publishRequestKey: string;
    }) =>
      publishCmsProject(
        projectId,
        localeTags,
        expectedAuthoringSha256,
        expectedPackageSha256,
        publishRequestKey,
      ),
    onSuccess: (snapshot, variables) => {
      if (!isSelectedProject(variables.projectId)) {
        clearCmsPublishIntentsForProject(variables.projectId);
        invalidateCmsQueries();
        return;
      }
      clearStaleDerivedState(variables.projectId);
      setPublishSnapshots((current) => upsertPublishSnapshot(current, snapshot));
      setNotice({
        kind: 'success',
        message: `Published snapshot v${snapshot.snapshotVersion}.`,
      });
      invalidateCmsQueries();
    },
    onError: (error: Error, variables) => {
      if (!isSelectedProject(variables.projectId)) {
        return;
      }
      if (isCmsConflictError(error)) {
        clearStaleDerivedState(variables.projectId);
        invalidateCmsQueries();
        setNotice({
          kind: 'error',
          message:
            'Publish intent no longer matches current content or locale scope. Refreshing current CMS data; review and publish again.',
        });
        return;
      }
      setNotice({ kind: 'error', message: error.message || 'Publish failed.' });
    },
  });

  const loadOlderSnapshotsMutation = useMutation({
    mutationFn: ({ projectId, beforeVersion }: { projectId: number; beforeVersion: number }) =>
      fetchCmsPublishSnapshots(projectId, {
        beforeVersion,
        limit: CMS_SNAPSHOT_HISTORY_PAGE_LIMIT,
      }),
    onSuccess: (history, variables) => {
      if (selectedProjectIdRef.current !== variables.projectId) {
        return;
      }
      setPublishSnapshots((current) => {
        const currentSnapshotIds = new Set(current.map((snapshot) => snapshot.id));
        return [
          ...current,
          ...history.snapshots.filter((snapshot) => !currentSnapshotIds.has(snapshot.id)),
        ];
      });
      setHasMorePublishSnapshots(history.hasMore);
      setNextBeforePublishSnapshotVersion(history.nextBeforeSnapshotVersion ?? null);
    },
    onError: (error: Error, variables) => {
      if (selectedProjectIdRef.current !== variables.projectId) {
        return;
      }
      setNotice({ kind: 'error', message: error.message || 'Failed to load older snapshots.' });
    },
  });

  const selectedProject = projectDetail?.project ?? null;
  const isSaving =
    createProjectMutation.isPending ||
    createContentTypeMutation.isPending ||
    createFieldMutation.isPending ||
    createEntryMutation.isPending ||
    createVariantMutation.isPending ||
    updateProjectMutation.isPending ||
    updateContentTypeMutation.isPending ||
    updateFieldMutation.isPending ||
    updateEntryMutation.isPending ||
    updateVariantMutation.isPending ||
    upsertMappingMutation.isPending ||
    unmapMappingMutation.isPending ||
    completenessMutation.isPending ||
    publishMutation.isPending;

  const hasProjectScopedDraftChanges =
    hasCmsFormDraftChanges(contentTypeDraft, initialContentTypeDraft) ||
    hasCmsFormDraftChanges(fieldDraft, initialFieldDraft) ||
    hasCmsFormDraftChanges(entryDraft, initialEntryDraft) ||
    hasCmsFormDraftChanges(variantDraft, initialVariantDraft) ||
    publishLocales.trim().length > 0 ||
    hasDirtyCmsDrafts(draftSyncRef.current);
  const hasUnsavedCmsDraftChanges =
    hasCmsFormDraftChanges(projectDraft, initialProjectDraft) || hasProjectScopedDraftChanges;
  const hasCmsExitRisk = isSaving || hasUnsavedCmsDraftChanges;

  const confirmDiscardProjectScopedDrafts = (action: string) => {
    if (!hasProjectScopedDraftChanges) {
      return true;
    }
    return window.confirm(
      `${action} Unsaved CMS drafts for ${
        selectedProject?.projectKey ?? 'the current project'
      } will be discarded.`,
    );
  };

  const confirmDiscardCmsExit = useCallback(
    (action: string) => {
      if (!hasCmsExitRisk) {
        return true;
      }
      const exitRiskMessage = isSaving
        ? hasUnsavedCmsDraftChanges
          ? 'CMS work is still saving and unsaved CMS drafts will be discarded.'
          : 'CMS work is still saving.'
        : 'Unsaved CMS drafts will be discarded.';
      return window.confirm(`${action} ${exitRiskMessage}`);
    },
    [hasCmsExitRisk, hasUnsavedCmsDraftChanges, isSaving],
  );
  const shouldBlockCmsExit = useCallback<BlockerFunction>(
    ({ currentLocation, nextLocation }) =>
      hasCmsExitRisk &&
      (currentLocation.pathname !== nextLocation.pathname ||
        currentLocation.search !== nextLocation.search ||
        currentLocation.hash !== nextLocation.hash),
    [hasCmsExitRisk],
  );
  const cmsExitBlocker = useBlocker(shouldBlockCmsExit);

  const confirmDiscardDirtyCmsDrafts = (action: string, draftKeys: readonly CmsDraftKey[]) => {
    if (!hasDirtyCmsDrafts(draftSyncRef.current, draftKeys)) {
      return true;
    }
    return window.confirm(
      `${action} Unsaved CMS drafts for ${
        selectedProject?.projectKey ?? 'the current project'
      } will be discarded.`,
    );
  };

  const handleCreateProject = (event: FormEvent) => {
    event.preventDefault();
    if (repositoriesLoading) {
      setNotice({ kind: 'error', message: 'Repositories are still loading.' });
      return;
    }
    if (repositoriesError) {
      setNotice({ kind: 'error', message: 'Failed to load repositories.' });
      return;
    }
    if (visibleRepositories.length === 0) {
      setNotice({ kind: 'error', message: 'No visible repositories are available.' });
      return;
    }
    const nextProjectName = projectDraft.name.trim() || projectDraft.projectKey.trim();
    if (
      !confirmDiscardProjectScopedDrafts(
        nextProjectName.length === 0
          ? 'Create and open this content project?'
          : `Create ${nextProjectName} and open it?`,
      )
    ) {
      return;
    }
    try {
      createProjectMutation.mutate({
        projectKey: projectDraft.projectKey,
        name: projectDraft.name,
        description: normalizeText(projectDraft.description),
        enabled: projectDraft.enabled,
        repositoryId: parseRequiredNumber(projectDraft.repositoryId, 'Repository'),
        assetPath: normalizeText(projectDraft.assetPath),
        deliveryHint: normalizeText(projectDraft.deliveryHint),
      });
    } catch (error) {
      setNotice({ kind: 'error', message: (error as Error).message });
    }
  };

  const handleRetryRepositories = () => {
    setNotice(null);
    void refetchRepositories();
  };

  const handleRetryProjects = () => {
    setNotice(null);
    void projectsQuery.refetch();
  };

  const handleRetrySelectedProject = () => {
    setNotice(null);
    void projectQuery.refetch();
  };

  const handleSelectProject = (projectId: number) => {
    if (projectId === selectedProjectId) {
      return;
    }
    const nextProject = projects.find((project) => project.id === projectId);
    if (
      !confirmDiscardProjectScopedDrafts(
        nextProject == null ? 'Switch content projects?' : `Switch to ${nextProject.name}?`,
      )
    ) {
      return;
    }
    setSelectedProjectId(projectId);
    setCompletenessResult(null);
    setNotice(null);
  };

  const handleBackToSettings = () => {
    void navigate('/settings/system');
  };

  useBeforeUnload((event) => {
    if (!hasCmsExitRisk) {
      return;
    }
    event.preventDefault();
    event.returnValue = '';
  });

  useEffect(() => {
    if (cmsExitBlocker.state !== 'blocked') {
      return;
    }
    if (confirmDiscardCmsExit('Leave Content CMS?')) {
      cmsExitBlocker.proceed();
      return;
    }
    cmsExitBlocker.reset();
  }, [cmsExitBlocker, confirmDiscardCmsExit]);

  if (!isAdmin) {
    return <Navigate to="/repositories" replace />;
  }

  const handleContentTypeEditSelectionChange = (contentTypeId: string) => {
    if (contentTypeId === contentTypeEditId) {
      return;
    }
    if (
      !confirmDiscardDirtyCmsDrafts('Switch edit content types?', ['contentTypeEdit', 'fieldEdit'])
    ) {
      return;
    }
    resetCmsDraftSyncState(draftSyncRef.current, 'contentTypeEdit', 'fieldEdit');
    setContentTypeEditId(contentTypeId);
  };

  const handleFieldEditSelectionChange = (fieldId: string) => {
    if (fieldId === fieldEditId) {
      return;
    }
    if (!confirmDiscardDirtyCmsDrafts('Switch edit fields?', ['fieldEdit'])) {
      return;
    }
    resetCmsDraftSyncState(draftSyncRef.current, 'fieldEdit');
    setFieldEditId(fieldId);
  };

  const handleEntryEditSelectionChange = (entryId: string) => {
    if (entryId === entryEditId) {
      return;
    }
    if (!confirmDiscardDirtyCmsDrafts('Switch edit entries?', ['entryEdit', 'variantEdit'])) {
      return;
    }
    resetCmsDraftSyncState(draftSyncRef.current, 'entryEdit', 'variantEdit');
    setEntryEditId(entryId);
  };

  const handleVariantEditSelectionChange = (variantId: string) => {
    if (variantId === variantEditId) {
      return;
    }
    if (!confirmDiscardDirtyCmsDrafts('Switch edit variants?', ['variantEdit'])) {
      return;
    }
    resetCmsDraftSyncState(draftSyncRef.current, 'variantEdit');
    setVariantEditId(variantId);
  };

  const handleCreateContentType = (event: FormEvent) => {
    event.preventDefault();
    if (selectedProjectId == null) {
      setNotice({ kind: 'error', message: 'Select a content project first.' });
      return;
    }
    createContentTypeMutation.mutate({
      projectId: selectedProjectId,
      payload: {
        typeKey: contentTypeDraft.typeKey,
        name: contentTypeDraft.name,
        description: normalizeText(contentTypeDraft.description),
        metadataSchemaJson: normalizeText(contentTypeDraft.metadataSchemaJson),
      },
    });
  };

  const handleUpdateProject = (event: FormEvent) => {
    event.preventDefault();
    if (selectedProjectId == null || selectedProject == null) {
      setNotice({ kind: 'error', message: 'Select a content project first.' });
      return;
    }
    if (
      selectedProject.enabled &&
      !projectEditDraft.enabled &&
      !window.confirm(
        `Disable ${selectedProject.name}? New publish and latest delivery discovery stop; exact snapshot exports remain available for rollback.`,
      )
    ) {
      return;
    }
    updateProjectMutation.mutate({
      projectId: selectedProjectId,
      payload: {
        name: projectEditDraft.name,
        description: normalizeText(projectEditDraft.description),
        enabled: projectEditDraft.enabled,
        deliveryHint: projectEditDraft.deliveryHint,
        expectedVersion: cmsDraftExpectedVersion(
          draftSyncRef.current,
          'projectEdit',
          selectedProject.entityVersion,
        ),
      },
    });
  };

  const handleUpdateContentType = (event: FormEvent) => {
    event.preventDefault();
    try {
      if (selectedEditContentType == null) {
        throw new Error('Select a content type before saving.');
      }
      updateContentTypeMutation.mutate({
        contentTypeId: selectedEditContentType.id,
        payload: {
          name: contentTypeEditDraft.name,
          description: normalizeText(contentTypeEditDraft.description),
          metadataSchemaJson: normalizeText(contentTypeEditDraft.metadataSchemaJson),
          expectedVersion: cmsDraftExpectedVersion(
            draftSyncRef.current,
            'contentTypeEdit',
            selectedEditContentType.entityVersion,
          ),
        },
      });
    } catch (error) {
      setNotice({ kind: 'error', message: (error as Error).message });
    }
  };

  const handleUpdateField = (event: FormEvent) => {
    event.preventDefault();
    try {
      if (selectedEditField == null) {
        throw new Error('Select a field before saving.');
      }
      updateFieldMutation.mutate({
        fieldId: selectedEditField.id,
        payload: {
          name: fieldEditDraft.name,
          description: normalizeText(fieldEditDraft.description),
          fieldType: fieldEditDraft.fieldType,
          localizable: true,
          required: fieldEditDraft.required,
          sortOrder: parseRequiredNumber(fieldEditDraft.sortOrder, 'Field sort order'),
          expectedVersion: cmsDraftExpectedVersion(
            draftSyncRef.current,
            'fieldEdit',
            selectedEditField.entityVersion,
          ),
        },
      });
    } catch (error) {
      setNotice({ kind: 'error', message: (error as Error).message });
    }
  };

  const handleUpdateEntry = (event: FormEvent) => {
    event.preventDefault();
    try {
      if (selectedEditEntry == null) {
        throw new Error('Select an entry before saving.');
      }
      if (
        selectedEditEntry.status === 'READY' &&
        entryEditDraft.status !== 'READY' &&
        !window.confirm(
          `Move ${selectedEditEntry.name} from READY to ${entryEditDraft.status}? It will be excluded from the next published package.`,
        )
      ) {
        return;
      }
      updateEntryMutation.mutate({
        entryId: selectedEditEntry.id,
        payload: {
          name: entryEditDraft.name,
          description: normalizeText(entryEditDraft.description),
          status: entryEditDraft.status,
          metadataJson: normalizeText(entryEditDraft.metadataJson),
          expectedVersion: cmsDraftExpectedVersion(
            draftSyncRef.current,
            'entryEdit',
            selectedEditEntry.entityVersion,
          ),
        },
      });
    } catch (error) {
      setNotice({ kind: 'error', message: (error as Error).message });
    }
  };

  const handleUpdateVariant = (event: FormEvent) => {
    event.preventDefault();
    try {
      if (selectedEditVariant == null) {
        throw new Error('Select a variant before saving.');
      }
      if (
        selectedEditVariant.status !== 'CONTROL' &&
        variantEditDraft.status === 'CONTROL' &&
        !window.confirm(
          `Promote ${selectedEditVariant.name} to control? This archives the current control variant for rollback.`,
        )
      ) {
        return;
      }
      if (
        selectedEditVariant.status !== 'ARCHIVED' &&
        variantEditDraft.status === 'ARCHIVED' &&
        !window.confirm(
          `Archive ${selectedEditVariant.name}? It will be excluded from the next published package.`,
        )
      ) {
        return;
      }
      updateVariantMutation.mutate({
        variantId: selectedEditVariant.id,
        payload: {
          name: variantEditDraft.name,
          candidateGroupKey: normalizeText(variantEditDraft.candidateGroupKey),
          status: variantEditDraft.status,
          metadataJson: normalizeText(variantEditDraft.metadataJson),
          sortOrder: parseRequiredNumber(variantEditDraft.sortOrder, 'Variant sort order'),
          expectedVersion: cmsDraftExpectedVersion(
            draftSyncRef.current,
            'variantEdit',
            selectedEditVariant.entityVersion,
          ),
        },
      });
    } catch (error) {
      setNotice({ kind: 'error', message: (error as Error).message });
    }
  };

  const handleCreateField = (event: FormEvent) => {
    event.preventDefault();
    try {
      createFieldMutation.mutate({
        contentTypeId: parseRequiredNumber(fieldDraft.contentTypeId, 'Content type'),
        payload: {
          fieldKey: fieldDraft.fieldKey,
          name: fieldDraft.name,
          description: normalizeText(fieldDraft.description),
          fieldType: fieldDraft.fieldType,
          localizable: true,
          required: fieldDraft.required,
          sortOrder: parseOptionalNumber(fieldDraft.sortOrder),
        },
      });
    } catch (error) {
      setNotice({ kind: 'error', message: (error as Error).message });
    }
  };

  const handleCreateEntry = (event: FormEvent) => {
    event.preventDefault();
    if (selectedProjectId == null) {
      setNotice({ kind: 'error', message: 'Select a content project first.' });
      return;
    }
    try {
      createEntryMutation.mutate({
        projectId: selectedProjectId,
        payload: {
          contentTypeId: parseRequiredNumber(entryDraft.contentTypeId, 'Content type'),
          entryKey: entryDraft.entryKey,
          name: entryDraft.name,
          description: normalizeText(entryDraft.description),
          status: entryDraft.status,
          metadataJson: normalizeText(entryDraft.metadataJson),
        },
      });
    } catch (error) {
      setNotice({ kind: 'error', message: (error as Error).message });
    }
  };

  const handleCreateVariant = (event: FormEvent) => {
    event.preventDefault();
    try {
      createVariantMutation.mutate({
        entryId: parseRequiredNumber(variantDraft.entryId, 'Entry'),
        payload: {
          variantKey: variantDraft.variantKey,
          name: variantDraft.name,
          candidateGroupKey: normalizeText(variantDraft.candidateGroupKey),
          status: variantDraft.status,
          metadataJson: normalizeText(variantDraft.metadataJson),
          sortOrder: parseOptionalNumber(variantDraft.sortOrder),
        },
      });
    } catch (error) {
      setNotice({ kind: 'error', message: (error as Error).message });
    }
  };

  const handleUpsertMapping = (event: FormEvent) => {
    event.preventDefault();
    try {
      const variantId = parseRequiredNumber(mappingDraft.variantId, 'Variant');
      const fieldId = parseRequiredNumber(mappingDraft.fieldId, 'Localizable field');
      const tmTextUnitId =
        mappingDraft.mappingSource === 'TM_TEXT_UNIT_ID'
          ? parseOptionalNumber(mappingDraft.tmTextUnitId)
          : null;
      const stringId =
        mappingDraft.mappingSource === 'STRING_ID' ? normalizeText(mappingDraft.stringId) : null;
      const generatedMapping = mappingDraft.mappingSource === 'GENERATED';
      const sourceContent = generatedMapping
        ? normalizeSourceContent(mappingDraft.sourceContent)
        : null;
      const sourceComment = generatedMapping ? normalizeText(mappingDraft.sourceComment) : null;
      if (mappingDraft.mappingSource === 'STRING_ID' && stringId == null) {
        setNotice({
          kind: 'error',
          message: 'Existing Mojito string ID is required.',
        });
        return;
      }
      if (mappingDraft.mappingSource === 'TM_TEXT_UNIT_ID' && tmTextUnitId == null) {
        setNotice({
          kind: 'error',
          message: 'Exact TM text unit ID is required.',
        });
        return;
      }
      if (mappingDraft.mappingSource === 'GENERATED' && sourceContent == null) {
        setNotice({
          kind: 'error',
          message: 'Source content is required when generating a CMS string ID.',
        });
        return;
      }
      if (mappingDraft.mappingSource === 'GENERATED' && sourceComment == null) {
        setNotice({
          kind: 'error',
          message: 'Translator context is required when generating a CMS string ID.',
        });
        return;
      }
      upsertMappingMutation.mutate({
        variantId,
        payload: {
          fieldId,
          tmTextUnitId,
          stringId,
          sourceContent,
          sourceComment,
          expectedVersion: draftSyncRef.current.mapping.expectedVersion,
        },
      });
    } catch (error) {
      setNotice({ kind: 'error', message: (error as Error).message });
    }
  };

  const handleCompleteness = (event: FormEvent) => {
    event.preventDefault();
    if (selectedProjectId == null) {
      setNotice({ kind: 'error', message: 'Select a content project first.' });
      return;
    }
    completenessMutation.mutate({
      projectId: selectedProjectId,
      locales: publishLocales,
    });
  };

  const handleUnmapMapping = () => {
    if (selectedMapping == null) {
      setNotice({ kind: 'error', message: 'Select a mapped field before unmapping.' });
      return;
    }
    if (
      !window.confirm(
        `Unmap ${selectedMapping.stringId}? It will be excluded from the next published package; the Mojito text unit remains.`,
      )
    ) {
      return;
    }
    unmapMappingMutation.mutate({
      mappingId: selectedMapping.id,
      expectedVersion: cmsDraftExpectedVersion(
        draftSyncRef.current,
        'mapping',
        selectedMapping.entityVersion,
      ),
    });
  };

  const getPublishRequestKey = (
    detail: ApiCmsProjectDetail,
    localeTags: string[],
    publishPackageSha256: string,
  ) => {
    const projectId = detail.project.id;
    const localeTagsKey = buildCmsPublishLocaleTagsKey(localeTags);
    const publishStateKey = buildCmsPublishStateKey(detail, publishPackageSha256);
    if (
      publishIntentRef.current?.projectId === projectId &&
      publishIntentRef.current.localeTagsKey === localeTagsKey &&
      publishIntentRef.current.publishStateKey === publishStateKey
    ) {
      return publishIntentRef.current.publishRequestKey;
    }
    const storedPublishIntent = loadCmsPublishIntent(projectId, localeTagsKey, publishStateKey);
    if (storedPublishIntent != null) {
      publishIntentRef.current = storedPublishIntent;
      return storedPublishIntent.publishRequestKey;
    }
    const publishRequestKey = createCmsPublishRequestKey();
    publishIntentRef.current = { projectId, localeTagsKey, publishStateKey, publishRequestKey };
    saveCmsPublishIntent(publishIntentRef.current);
    return publishRequestKey;
  };

  const handlePublish = () => {
    if (selectedProjectId == null || selectedProject == null || projectDetail == null) {
      setNotice({ kind: 'error', message: 'Select a content project first.' });
      return;
    }
    const publishProjectDetail = projectDetail;
    const validatedPackage = completenessResult;
    if (validatedPackage == null) {
      setNotice({ kind: 'error', message: 'Validate package before publishing.' });
      return;
    }
    if (!validatedPackage.complete) {
      setNotice({
        kind: 'error',
        message: 'Cannot publish until the validated package is complete.',
      });
      return;
    }
    const localeTags = publishLocales.trim()
      ? publishLocales.split(',').map((tag) => tag.trim())
      : [];
    const publishLocaleScope =
      localeTags.length === 0 ? 'configured target locales' : localeTags.join(', ');
    if (
      !window.confirm(
        `Publish immutable JSON snapshot for ${selectedProject.projectKey} using ${publishLocaleScope}?`,
      )
    ) {
      return;
    }
    try {
      publishMutation.mutate({
        projectId: selectedProjectId,
        localeTags,
        expectedAuthoringSha256: publishProjectDetail.authoringSha256,
        expectedPackageSha256: validatedPackage.publishPackageSha256,
        publishRequestKey: getPublishRequestKey(
          publishProjectDetail,
          localeTags,
          validatedPackage.publishPackageSha256,
        ),
      });
    } catch (error) {
      setNotice({ kind: 'error', message: (error as Error).message });
    }
  };

  const handleLoadOlderSnapshots = () => {
    if (selectedProjectId == null || nextBeforePublishSnapshotVersion == null) {
      setNotice({ kind: 'error', message: 'No older snapshot page is available.' });
      return;
    }
    loadOlderSnapshotsMutation.mutate({
      projectId: selectedProjectId,
      beforeVersion: nextBeforePublishSnapshotVersion,
    });
  };

  const handleMappingDraftChange = (nextDraft: MappingDraft) => {
    const selectionChanged =
      nextDraft.entryId !== mappingDraft.entryId ||
      nextDraft.variantId !== mappingDraft.variantId ||
      nextDraft.fieldId !== mappingDraft.fieldId;
    if (selectionChanged) {
      if (!confirmDiscardDirtyCmsDrafts('Change mapping target?', ['mapping'])) {
        return;
      }
      resetCmsDraftSyncState(draftSyncRef.current, 'mapping');
    } else {
      markCmsDraftDirty(draftSyncRef.current, 'mapping');
    }
    setMappingDraft(nextDraft);
  };

  return (
    <div className="settings-subpage">
      <SettingsSubpageHeader
        backTo="/settings/system"
        backLabel="Back to settings"
        onBack={hasCmsExitRisk ? handleBackToSettings : undefined}
        context="Settings"
        title="Content CMS"
        centerContent={selectedProject ? selectedProject.projectKey : 'No project selected'}
      />

      <div className="settings-page settings-page--wide content-cms-admin-page">
        {notice ? (
          <div
            className={`content-cms-admin-page__notice${
              notice.kind === 'error' ? ' is-error' : ''
            }`}
            role={notice.kind === 'error' ? 'alert' : 'status'}
            aria-live={notice.kind === 'error' ? 'assertive' : 'polite'}
          >
            {notice.message}
          </div>
        ) : null}

        <div className="content-cms-admin-page__layout">
          <section className="settings-card content-cms-admin-page__sidebar">
            <div className="settings-card__header">
              <h2>Projects</h2>
            </div>
            <SearchControl
              value={projectSearch}
              onChange={setProjectSearch}
              placeholder="Search projects"
              disabled={isSaving}
              className="content-cms-admin-page__search"
            />
            <ProjectList
              projects={projects}
              totalCount={projectsQuery.data?.totalCount ?? projects.length}
              selectedProjectId={selectedProjectId}
              hasSearchQuery={projectSearch.trim().length > 0}
              disabled={isSaving}
              isLoading={projectsQuery.isLoading}
              isError={projectsQuery.isError}
              onRetry={handleRetryProjects}
              onSelect={handleSelectProject}
            />

            <ProjectCreateForm
              repositories={visibleRepositories}
              repositoriesLoading={repositoriesLoading}
              repositoriesError={repositoriesError}
              onRetryRepositories={handleRetryRepositories}
              draft={projectDraft}
              disabled={isSaving || !canCreateProject}
              onChange={setProjectDraft}
              onSubmit={handleCreateProject}
            />
          </section>

          <main className="content-cms-admin-page__main" aria-busy={isSaving}>
            {projectQuery.isLoading ? (
              <section className="settings-card">
                <p className="settings-page__hint">Loading selected project.</p>
              </section>
            ) : projectQuery.isError ? (
              <section className="settings-card">
                <p className="settings-hint is-error">Failed to load selected project.</p>
                <div className="content-cms-admin-page__actions">
                  <button
                    type="button"
                    className="settings-button"
                    onClick={handleRetrySelectedProject}
                  >
                    Try again
                  </button>
                </div>
              </section>
            ) : projectDetail ? (
              <>
                <ProjectOverview detail={projectDetail} />

                <section className="settings-card">
                  <div className="settings-card__header">
                    <h2>Authoring</h2>
                  </div>
                  <div className="content-cms-admin-page__workflow-grid">
                    <ContentTypeForm
                      draft={contentTypeDraft}
                      disabled={isSaving}
                      onChange={setContentTypeDraft}
                      onSubmit={handleCreateContentType}
                    />
                    <FieldForm
                      contentTypes={contentTypes}
                      draft={fieldDraft}
                      disabled={isSaving}
                      onChange={setFieldDraft}
                      onSubmit={handleCreateField}
                    />
                    <EntryForm
                      contentTypes={contentTypes}
                      draft={entryDraft}
                      disabled={isSaving}
                      onChange={setEntryDraft}
                      onSubmit={handleCreateEntry}
                    />
                    <VariantForm
                      entries={entries}
                      draft={variantDraft}
                      disabled={isSaving}
                      onChange={setVariantDraft}
                      onSubmit={handleCreateVariant}
                    />
                    <MappingForm
                      entries={entries}
                      selectedEntry={selectedMappingEntry}
                      selectedVariant={selectedMappingVariant}
                      selectedMapping={selectedMapping}
                      generatedStringId={selectedGeneratedMappingStringId}
                      fields={selectedMappingFields}
                      draft={mappingDraft}
                      disabled={isSaving}
                      onChange={handleMappingDraftChange}
                      onSubmit={handleUpsertMapping}
                      onUnmap={handleUnmapMapping}
                    />
                    <PublishForm
                      snapshots={publishSnapshots}
                      hasMoreSnapshots={hasMorePublishSnapshots}
                      isLoadingOlderSnapshots={loadOlderSnapshotsMutation.isPending}
                      completenessResult={completenessResult}
                      publishLocales={publishLocales}
                      disabled={isSaving}
                      onPublishLocalesChange={(value) => {
                        setPublishLocales(value);
                        setCompletenessResult(null);
                      }}
                      onCompletenessSubmit={handleCompleteness}
                      onPublish={handlePublish}
                      onLoadOlderSnapshots={handleLoadOlderSnapshots}
                    />
                  </div>
                </section>

                <section className="settings-card">
                  <div className="settings-card__header">
                    <h2>Edit stable content</h2>
                  </div>
                  <p className="settings-page__hint">
                    Stable keys, repository, and virtual asset stay fixed after creation so Mojito
                    string IDs remain durable.
                  </p>
                  <div className="content-cms-admin-page__workflow-grid">
                    <ProjectEditForm
                      draft={projectEditDraft}
                      disabled={isSaving}
                      onChange={(draft) => {
                        markCmsDraftDirty(draftSyncRef.current, 'projectEdit');
                        setProjectEditDraft(draft);
                      }}
                      onSubmit={handleUpdateProject}
                    />
                    <ContentTypeEditForm
                      contentTypes={contentTypes}
                      selectedContentTypeId={contentTypeEditId}
                      draft={contentTypeEditDraft}
                      disabled={isSaving}
                      onContentTypeChange={handleContentTypeEditSelectionChange}
                      onChange={(draft) => {
                        markCmsDraftDirty(draftSyncRef.current, 'contentTypeEdit');
                        setContentTypeEditDraft(draft);
                      }}
                      onSubmit={handleUpdateContentType}
                    />
                    <FieldEditForm
                      contentType={selectedEditContentType}
                      selectedFieldId={fieldEditId}
                      draft={fieldEditDraft}
                      disabled={isSaving}
                      onFieldChange={handleFieldEditSelectionChange}
                      onChange={(draft) => {
                        markCmsDraftDirty(draftSyncRef.current, 'fieldEdit');
                        setFieldEditDraft(draft);
                      }}
                      onSubmit={handleUpdateField}
                    />
                    <EntryEditForm
                      entries={entries}
                      selectedEntryId={entryEditId}
                      draft={entryEditDraft}
                      disabled={isSaving}
                      onEntryChange={handleEntryEditSelectionChange}
                      onChange={(draft) => {
                        markCmsDraftDirty(draftSyncRef.current, 'entryEdit');
                        setEntryEditDraft(draft);
                      }}
                      onSubmit={handleUpdateEntry}
                    />
                    <VariantEditForm
                      entry={selectedEditEntry}
                      selectedVariantId={variantEditId}
                      draft={variantEditDraft}
                      disabled={isSaving}
                      onVariantChange={handleVariantEditSelectionChange}
                      onChange={(draft) => {
                        markCmsDraftDirty(draftSyncRef.current, 'variantEdit');
                        setVariantEditDraft(draft);
                      }}
                      onSubmit={handleUpdateVariant}
                    />
                  </div>
                </section>

                <ContentTypesTable contentTypes={contentTypes} />
                <EntriesTable entries={entries} contentTypes={contentTypes} />
              </>
            ) : (
              <section className="settings-card">
                <p className="settings-page__hint">
                  Create or select a content project to start modeling copy blocks.
                </p>
              </section>
            )}
          </main>
        </div>
      </div>
    </div>
  );
}
