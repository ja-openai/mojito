import './settings-page.css';
import './admin-content-cms-page.css';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { type FormEvent, useEffect, useRef, useState } from 'react';
import { Navigate } from 'react-router-dom';

import {
  type ApiCmsContentType,
  type ApiCmsEntry,
  type ApiCmsEntryStatus,
  type ApiCmsFieldType,
  type ApiCmsProjectCompleteness,
  type ApiCmsProjectDetail,
  type ApiCmsProjectSummary,
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

const CMS_QUERY_KEY = ['content-cms'] as const;
const EMPTY_PROJECTS: ApiCmsProjectSummary[] = [];
const EMPTY_CONTENT_TYPES: ApiCmsContentType[] = [];
const EMPTY_ENTRIES: ApiCmsEntry[] = [];

export function AdminContentCmsPage() {
  const user = useUser();
  const isAdmin = user.role === 'ROLE_ADMIN';
  const queryClient = useQueryClient();
  const { data: repositories } = useRepositories();

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
  const publishIntentRef = useRef<CmsPublishIntent | null>(null);

  const projectsQuery = useQuery({
    queryKey: [...CMS_QUERY_KEY, 'projects', projectSearch],
    queryFn: () => fetchCmsProjects({ search: projectSearch, enabled: null, limit: 100 }),
    enabled: isAdmin,
    staleTime: 30_000,
  });

  const projects = projectsQuery.data?.projects ?? EMPTY_PROJECTS;

  useEffect(() => {
    if (projects.length === 0) {
      setSelectedProjectId(null);
      return;
    }
    if (
      selectedProjectId == null ||
      !projects.some((project) => project.id === selectedProjectId)
    ) {
      setSelectedProjectId(projects[0].id);
    }
  }, [projects, selectedProjectId]);

  useEffect(() => {
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
  const publishSnapshots = projectDetail?.publishSnapshots ?? [];
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
      setProjectEditDraft(initialProjectEditDraft);
      setContentTypeEditId('');
      setFieldEditId('');
      setEntryEditId('');
      setVariantEditId('');
      return;
    }
    setProjectEditDraft({
      name: projectDetail.project.name,
      description: projectDetail.project.description ?? '',
      deliveryHint: projectDetail.project.deliveryHint,
      enabled: projectDetail.project.enabled,
    });
    setContentTypeEditId((current) =>
      contentTypes.some((contentType) => String(contentType.id) === current)
        ? current
        : (contentTypes[0]?.id.toString() ?? ''),
    );
    setEntryEditId((current) =>
      entries.some((entry) => String(entry.id) === current)
        ? current
        : (entries[0]?.id.toString() ?? ''),
    );
  }, [contentTypes, entries, projectDetail]);

  useEffect(() => {
    if (selectedEditContentType == null) {
      setContentTypeEditDraft(initialContentTypeDraft);
      setFieldEditId('');
      return;
    }
    setContentTypeEditDraft({
      typeKey: selectedEditContentType.typeKey,
      name: selectedEditContentType.name,
      description: selectedEditContentType.description ?? '',
      schemaVersion: String(selectedEditContentType.schemaVersion),
      metadataSchemaJson: selectedEditContentType.metadataSchemaJson ?? '',
    });
    setFieldEditId((current) =>
      selectedEditContentType.fields.some((field) => String(field.id) === current)
        ? current
        : (selectedEditContentType.fields[0]?.id.toString() ?? ''),
    );
  }, [selectedEditContentType]);

  useEffect(() => {
    if (selectedEditField == null) {
      setFieldEditDraft(initialFieldDraft);
      return;
    }
    setFieldEditDraft({
      contentTypeId: String(selectedEditField.contentTypeId),
      fieldKey: selectedEditField.fieldKey,
      name: selectedEditField.name,
      description: selectedEditField.description ?? '',
      fieldType: selectedEditField.fieldType,
      required: selectedEditField.required,
      sortOrder: String(selectedEditField.sortOrder),
    });
  }, [selectedEditField]);

  useEffect(() => {
    if (selectedEditEntry == null) {
      setEntryEditDraft(initialEntryDraft);
      setVariantEditId('');
      return;
    }
    setEntryEditDraft({
      contentTypeId: String(selectedEditEntry.contentTypeId),
      entryKey: selectedEditEntry.entryKey,
      name: selectedEditEntry.name,
      description: selectedEditEntry.description ?? '',
      status: selectedEditEntry.status,
      metadataJson: selectedEditEntry.metadataJson ?? '',
    });
    setVariantEditId((current) =>
      selectedEditEntry.variants.some((variant) => String(variant.id) === current)
        ? current
        : (selectedEditEntry.variants[0]?.id.toString() ?? ''),
    );
  }, [selectedEditEntry]);

  useEffect(() => {
    if (selectedEditVariant == null) {
      setVariantEditDraft(initialVariantDraft);
      return;
    }
    setVariantEditDraft({
      entryId: String(selectedEditVariant.entryId),
      variantKey: selectedEditVariant.variantKey,
      name: selectedEditVariant.name,
      candidateGroupKey: selectedEditVariant.candidateGroupKey ?? '',
      status: selectedEditVariant.status,
      metadataJson: selectedEditVariant.metadataJson ?? '',
      sortOrder: String(selectedEditVariant.sortOrder),
    });
  }, [selectedEditVariant]);

  useEffect(() => {
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

  const clearStaleDerivedState = (projectId: number | null) => {
    setCompletenessResult(null);
    if (projectId != null) {
      clearCmsPublishIntentsForProject(projectId);
    }
    publishIntentRef.current = null;
  };

  const handleDetailSuccess = (
    detail: ApiCmsProjectDetail,
    message: string,
    afterSuccess?: () => void,
  ) => {
    setSelectedProjectId(detail.project.id);
    setNotice({ kind: 'success', message });
    clearStaleDerivedState(detail.project.id);
    afterSuccess?.();
    invalidateCmsQueries();
  };

  const handleMutationError = (error: Error, fallbackMessage: string) => {
    if (isCmsConflictError(error)) {
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
        deliveryHint?: string | null;
        expectedVersion: number;
      };
    }) => updateCmsProject(projectId, payload),
    onSuccess: (detail) => {
      handleDetailSuccess(detail, `Updated content project ${detail.project.name}.`);
    },
    onError: (error: Error) => {
      handleMutationError(error, 'Failed to update content project.');
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
      handleDetailSuccess(detail, `Updated content type in ${detail.project.name}.`);
    },
    onError: (error: Error) => {
      handleMutationError(error, 'Failed to update content type.');
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
        sortOrder?: number | null;
        expectedVersion: number;
      };
    }) => updateCmsContentTypeField(fieldId, payload),
    onSuccess: (detail) => {
      handleDetailSuccess(detail, `Updated field in ${detail.project.name}.`);
    },
    onError: (error: Error) => {
      handleMutationError(error, 'Failed to update field.');
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
      handleDetailSuccess(detail, `Updated entry in ${detail.project.name}.`);
    },
    onError: (error: Error) => {
      handleMutationError(error, 'Failed to update entry.');
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
        sortOrder?: number | null;
        expectedVersion: number;
      };
    }) => updateCmsVariant(variantId, payload),
    onSuccess: (detail) => {
      handleDetailSuccess(detail, `Updated variant in ${detail.project.name}.`);
    },
    onError: (error: Error) => {
      handleMutationError(error, 'Failed to update variant.');
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
      handleDetailSuccess(detail, `Mapped field in ${detail.project.name}.`, () => {
        setMappingDraft((current) => ({
          ...initialMappingDraft,
          entryId: current.entryId,
          variantId: current.variantId,
        }));
      });
    },
    onError: (error: Error) => {
      handleMutationError(error, 'Failed to map field.');
    },
  });

  const unmapMappingMutation = useMutation({
    mutationFn: ({ mappingId, expectedVersion }: { mappingId: number; expectedVersion: number }) =>
      unmapCmsFieldMapping(mappingId, expectedVersion),
    onSuccess: (detail) => {
      handleDetailSuccess(detail, `Unmapped field in ${detail.project.name}.`);
    },
    onError: (error: Error) => {
      handleMutationError(error, 'Failed to unmap field.');
    },
  });

  const completenessMutation = useMutation({
    mutationFn: ({ projectId, locales }: { projectId: number; locales: string }) =>
      fetchCmsProjectCompleteness(projectId, locales),
    onMutate: () => {
      setCompletenessResult(null);
    },
    onSuccess: (response) => {
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
    onError: (error: Error) => {
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
      clearStaleDerivedState(variables.projectId);
      setNotice({
        kind: 'success',
        message: `Published snapshot v${snapshot.snapshotVersion}.`,
      });
      invalidateCmsQueries();
    },
    onError: (error: Error, variables) => {
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

  if (!isAdmin) {
    return <Navigate to="/repositories" replace />;
  }

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

  const handleCreateProject = (event: FormEvent) => {
    event.preventDefault();
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
        deliveryHint: normalizeText(projectEditDraft.deliveryHint),
        expectedVersion: selectedProject.entityVersion,
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
          expectedVersion: selectedEditContentType.entityVersion,
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
          sortOrder: parseOptionalNumber(fieldEditDraft.sortOrder),
          expectedVersion: selectedEditField.entityVersion,
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
          expectedVersion: selectedEditEntry.entityVersion,
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
          sortOrder: parseOptionalNumber(variantEditDraft.sortOrder),
          expectedVersion: selectedEditVariant.entityVersion,
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
          expectedVersion: selectedMapping?.entityVersion ?? null,
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
      expectedVersion: selectedMapping.entityVersion,
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

  return (
    <div className="settings-subpage">
      <SettingsSubpageHeader
        backTo="/settings/system"
        backLabel="Back to settings"
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
              className="content-cms-admin-page__search"
            />
            <ProjectList
              projects={projects}
              selectedProjectId={selectedProjectId}
              isLoading={projectsQuery.isLoading}
              isError={projectsQuery.isError}
              onSelect={(projectId) => {
                setSelectedProjectId(projectId);
                setCompletenessResult(null);
                setNotice(null);
              }}
            />

            <ProjectCreateForm
              repositories={repositories ?? []}
              draft={projectDraft}
              disabled={isSaving}
              onChange={setProjectDraft}
              onSubmit={handleCreateProject}
            />
          </section>

          <main className="content-cms-admin-page__main">
            {projectQuery.isLoading ? (
              <section className="settings-card">
                <p className="settings-page__hint">Loading selected project.</p>
              </section>
            ) : projectQuery.isError ? (
              <section className="settings-card">
                <p className="settings-hint is-error">Failed to load selected project.</p>
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
                      onChange={setMappingDraft}
                      onSubmit={handleUpsertMapping}
                      onUnmap={handleUnmapMapping}
                    />
                    <PublishForm
                      snapshots={publishSnapshots}
                      completenessResult={completenessResult}
                      publishLocales={publishLocales}
                      disabled={isSaving}
                      onPublishLocalesChange={(value) => {
                        setPublishLocales(value);
                        setCompletenessResult(null);
                      }}
                      onCompletenessSubmit={handleCompleteness}
                      onPublish={handlePublish}
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
                      onChange={setProjectEditDraft}
                      onSubmit={handleUpdateProject}
                    />
                    <ContentTypeEditForm
                      contentTypes={contentTypes}
                      selectedContentTypeId={contentTypeEditId}
                      draft={contentTypeEditDraft}
                      disabled={isSaving}
                      onContentTypeChange={setContentTypeEditId}
                      onChange={setContentTypeEditDraft}
                      onSubmit={handleUpdateContentType}
                    />
                    <FieldEditForm
                      contentType={selectedEditContentType}
                      selectedFieldId={fieldEditId}
                      draft={fieldEditDraft}
                      disabled={isSaving}
                      onFieldChange={setFieldEditId}
                      onChange={setFieldEditDraft}
                      onSubmit={handleUpdateField}
                    />
                    <EntryEditForm
                      entries={entries}
                      selectedEntryId={entryEditId}
                      draft={entryEditDraft}
                      disabled={isSaving}
                      onEntryChange={setEntryEditId}
                      onChange={setEntryEditDraft}
                      onSubmit={handleUpdateEntry}
                    />
                    <VariantEditForm
                      entry={selectedEditEntry}
                      selectedVariantId={variantEditId}
                      draft={variantEditDraft}
                      disabled={isSaving}
                      onVariantChange={setVariantEditId}
                      onChange={setVariantEditDraft}
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
