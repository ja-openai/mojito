import './settings-page.css';
import './admin-content-cms-page.css';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { type FormEvent, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  type BlockerFunction,
  Navigate,
  useBeforeUnload,
  useBlocker,
  useLocation,
  useNavigate,
  useSearchParams,
} from 'react-router-dom';

import {
  addCmsProjectTargetLocales,
  type ApiCmsContentType,
  type ApiCmsEntry,
  type ApiCmsEntryCompleteness,
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
  createCmsFirstCopyBlock,
  createCmsProject,
  createCmsPublishRequestKey,
  createCmsVariant,
  fetchCmsEntryCompleteness,
  fetchCmsProject,
  fetchCmsProjectCompleteness,
  fetchCmsProjectReleaseChanges,
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
import { ConfirmModal } from '../../components/ConfirmModal';
import type { LocaleOption } from '../../components/LocaleMultiSelect';
import { SearchControl } from '../../components/SearchControl';
import { useLocales } from '../../hooks/useLocales';
import { REPOSITORIES_QUERY_KEY } from '../../hooks/useRepositories';
import { useUser } from '../../hooks/useUser';
import { useLocaleDisplayNameResolver } from '../../utils/localeDisplayNames';
import {
  buildCmsSelectionPath,
  CMS_ADMIN_PATH,
  CMS_AUTHORING_PATH,
  getCmsAuthoringSelectionFromSearchParams,
} from '../../utils/textUnitDetailUrl';
import {
  type AuthoringRecovery,
  CmsEmptyState,
  CmsEntryWorkspace,
  type CmsMappingRepairContext,
  ContentTypeEditForm,
  ContentTypeForm,
  ContentTypesTable,
  CopyCollectionsSidebarToggle,
  EntriesTable,
  EntryEditForm,
  EntryForm,
  FieldEditForm,
  FieldForm,
  FirstCopyBlockForm,
  FirstCopyBlockIdentifiers,
  FirstWritingSpaceForm,
  type InlineTranslationReleaseBlockerTarget,
  MappingForm,
  ProjectEditForm,
  ProjectList,
  ProjectOverview,
  PublishForm,
  type ReleaseHistoryChange,
  type ReleaseRecovery,
  type ReleaseRepairTarget,
  type ReleaseReviewReturnMode,
  type ReleaseSuccess,
  StartWritingSpaceButton,
  VariantEditForm,
  VariantForm,
} from './content-cms-admin-forms';
import {
  type CopyFieldFocusTarget,
  focusCmsReleasePanel,
  queueCmsReleasePanelFocus,
  queueCopyBlockDetailsFocus,
  queueCopyBlockEditorScroll,
  queueCopyFieldScroll,
} from './content-cms-admin-scroll';
import {
  type AuthoringFieldDraftState,
  type AuthoringMappingDraftState,
  buildGeneratedCmsStringId,
  buildMappingDraft,
  type ContentTypeDraft,
  type EntryDraft,
  type FieldDraft,
  findContentType,
  findEntry,
  findVariant,
  type FirstCopyBlockDraft,
  initialContentTypeDraft,
  initialEntryDraft,
  initialFieldDraft,
  initialFirstCopyBlockDraft,
  initialMappingDraft,
  initialProjectDraft,
  initialProjectEditDraft,
  initialSourceCopyDraft,
  initialVariantDraft,
  type MappingDraft,
  normalizeSourceContent,
  normalizeText,
  parseOptionalNumber,
  parseRequiredNumber,
  type ProjectDraft,
  type ProjectEditDraft,
  type SourceCopyDraft,
  type VariantDraft,
} from './content-cms-admin-types';
import {
  buildCmsPublishLocaleTagsKey,
  buildCmsPublishStateKey,
  clearCmsPublishIntentsForProject,
  type CmsPublishIntent,
  loadCmsPublishIntent,
  loadLatestCmsPublishIntentForProject,
  saveCmsPublishIntent,
} from './content-cms-publish-intent';
import { SettingsSubpageHeader } from './SettingsSubpageHeader';

const cmsCopyCollectionsSidebarBodyId = 'content-cms-copy-collections-sidebar-body';

type StatusNotice = {
  kind: 'success' | 'error';
  message: string;
};

type CmsMutationErrorNoticeOptions = {
  conflictingDraftKey?: CmsDraftKey;
  authorFacingMessage?: string;
  authorConflictMessage?: string;
  isConflict?: boolean;
  suppressNotice?: boolean;
};

type CmsConfirmation = {
  title: string;
  body: string;
  confirmLabel: string;
  cancelLabel: string;
  confirmVariant?: 'danger' | 'primary';
  confirmDisabled?: boolean;
};

type AddTargetLocalesVariables = {
  projectId: number;
  localeTags: string[];
  requestedLocaleTag?: string | null;
  returnToRelease?: boolean;
};

type PendingRequestedTargetLocaleConflict = {
  localeTag: string;
  refreshStarted: boolean;
  returnToRelease: boolean;
};

type PendingInlineTranslationFieldSwitch = {
  sourceFieldId: string;
  targetFieldId: string;
  targetFieldName: string;
  focusTarget: CopyFieldFocusTarget;
  reason: 'dirty-translation' | 'refresh-error';
};

type PendingSavedEntryFocus = {
  projectId: number;
  entryId: string;
};

type PendingSavedFieldFocus = PendingSavedEntryFocus & {
  fieldId: string;
  focusTarget: CopyFieldFocusTarget;
};

type PendingTargetLocaleSetupFocus = PendingSavedEntryFocus & {
  fieldId: string;
  refreshStarted: boolean;
};

type AuthoringReviewTarget = {
  requestKey: number;
  projectId: number | null;
  entryId: string | null;
  fieldId: string | null;
  localeTag: string | null;
  repairTarget: ReleaseRepairTarget | null;
  reviewReason: string | null;
  reviewReturnMode: ReleaseReviewReturnMode | null;
  lastReleasedSourceContent: string | null;
  lastReleasedTranslationContent: string | null;
  returnToRelease: boolean;
  refreshTranslation: boolean;
  translationRepairSaved: boolean;
  translationReconnectErrorMessage: string | null;
  requestedLocaleSetupRefreshPending: boolean;
};

type AuthoringTranslationReconnectTarget = {
  entryId: string;
  fieldId: string;
  localeTag: string;
};

type PendingAuthoringSourceCopyConflictRefresh = {
  fieldId: string;
  refreshStarted: boolean;
};

const AUTHORING_SOURCE_COPY_REFRESH_PENDING_MESSAGE =
  'Copy changed while you were editing. Current copy is refreshing; review and save again.';
const AUTHORING_SOURCE_COPY_REFRESH_FAILED_MESSAGE =
  'Copy changed while you were editing, but current copy could not refresh here. Select Try again to refresh current copy, then review and save again.';

const initialAuthoringReviewTarget: AuthoringReviewTarget = {
  requestKey: 0,
  projectId: null,
  entryId: null,
  fieldId: null,
  localeTag: null,
  repairTarget: null,
  reviewReason: null,
  reviewReturnMode: null,
  lastReleasedSourceContent: null,
  lastReleasedTranslationContent: null,
  returnToRelease: false,
  refreshTranslation: false,
  translationRepairSaved: false,
  translationReconnectErrorMessage: null,
  requestedLocaleSetupRefreshPending: false,
};

function getRequestedTargetLocaleConflictSettledRecoveryMessage({
  requestedLocaleLabel,
  refreshFailed,
  returnToRelease,
}: {
  requestedLocaleLabel: string;
  refreshFailed: boolean;
  returnToRelease: boolean;
}) {
  if (refreshFailed) {
    return returnToRelease
      ? `Current copy could not refresh after ${requestedLocaleLabel} changed. Try Add ${requestedLocaleLabel} again, refresh translation status, or return to release to check current translation languages.`
      : `Current copy could not refresh after ${requestedLocaleLabel} changed. Try Add ${requestedLocaleLabel} again, or select Try again to refresh translation status here.`;
  }
  return returnToRelease
    ? `Current copy refreshed and ${requestedLocaleLabel} is still not set up. Try Add ${requestedLocaleLabel} again, or return to release to work from current translation languages.`
    : `Current copy refreshed and ${requestedLocaleLabel} is still not set up. Try Add ${requestedLocaleLabel} again to keep working here.`;
}

type CmsRouteState = {
  cmsTranslationRepairSaved?: boolean;
};

function hasSavedTranslationRepairRouteState(state: unknown) {
  return (
    typeof state === 'object' &&
    state != null &&
    (state as CmsRouteState).cmsTranslationRepairSaved === true
  );
}

type GeneratedSourceMappingPayload = {
  fieldId: number;
  sourceContent: string;
  sourceComment: string;
};

class CmsFirstWritingSpaceError extends Error {
  constructor(
    message: string,
    readonly projectDetail: ApiCmsProjectDetail,
  ) {
    super(message);
    this.name = 'CmsFirstWritingSpaceError';
  }
}

function getAuthoringReleaseRecoveryMessage({
  status,
  conflict,
}: {
  status: ApiCmsEntryStatus;
  conflict: boolean;
}) {
  if (conflict) {
    return 'Release step changed while you were updating it. Current copy is refreshing; review this item and try again.';
  }
  return status === 'READY'
    ? 'Could not include this content item in release. Try again. If it keeps failing, ask an admin to check this release.'
    : 'Could not remove this content item from release. Try again. If it keeps failing, ask an admin to check this release.';
}

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

type StructureRepairAction = 'block-shape' | 'copy-piece' | 'copy-block' | null;

type CopyStructureMaintenanceAction =
  | 'block-shape'
  | 'copy-piece'
  | 'copy-block'
  | 'variant'
  | null;

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

function hasDirtyAuthoringMappingDrafts(
  authoringMappingDrafts: Record<string, AuthoringMappingDraftState>,
) {
  return Object.values(authoringMappingDrafts).some((draftState) => draftState.dirty);
}

function mappingDraftsMatch(left: MappingDraft, right: MappingDraft) {
  return (
    left.entryId === right.entryId &&
    left.variantId === right.variantId &&
    left.fieldId === right.fieldId &&
    left.mappingSource === right.mappingSource &&
    left.tmTextUnitId === right.tmTextUnitId &&
    left.stringId === right.stringId &&
    left.sourceContent === right.sourceContent &&
    left.sourceComment === right.sourceComment
  );
}

function fieldDraftsMatch(left: FieldDraft, right: FieldDraft) {
  return (
    left.contentTypeId === right.contentTypeId &&
    left.fieldKey === right.fieldKey &&
    left.name === right.name &&
    left.description === right.description &&
    left.sourceContent === right.sourceContent &&
    left.sourceComment === right.sourceComment &&
    left.fieldType === right.fieldType &&
    left.required === right.required &&
    left.sortOrder === right.sortOrder
  );
}

function entryDraftsMatch(left: EntryDraft, right: EntryDraft) {
  return (
    left.contentTypeId === right.contentTypeId &&
    left.entryKey === right.entryKey &&
    left.name === right.name &&
    left.description === right.description &&
    left.status === right.status &&
    left.metadataJson === right.metadataJson
  );
}

function hasDirtyAuthoringFieldDrafts(
  authoringFieldDrafts: Record<string, AuthoringFieldDraftState>,
) {
  return Object.values(authoringFieldDrafts).some((draftState) => draftState.dirty);
}

function hasSourceCopyDraftChanges(sourceDrafts: Record<string, SourceCopyDraft>) {
  return Object.values(sourceDrafts).some(
    (draft) => draft.sourceContent.length > 0 || draft.sourceComment.length > 0,
  );
}

function buildFieldDraft(field: ApiCmsContentType['fields'][number]): FieldDraft {
  return {
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
}

function buildEntryDraft(entry: ApiCmsEntry): EntryDraft {
  return {
    contentTypeId: String(entry.contentTypeId),
    entryKey: entry.entryKey,
    name: entry.name,
    description: entry.description ?? '',
    status: entry.status,
    metadataJson: entry.metadataJson ?? '',
  };
}

function buildProjectEditDraft(project: ApiCmsProjectDetail['project']): ProjectEditDraft {
  return {
    name: project.name,
    description: project.description ?? '',
    deliveryHint: project.deliveryHint,
    enabled: project.enabled,
  };
}

function buildContentTypeDraft(contentType: ApiCmsContentType): ContentTypeDraft {
  return {
    typeKey: contentType.typeKey,
    name: contentType.name,
    description: contentType.description ?? '',
    schemaVersion: String(contentType.schemaVersion),
    metadataSchemaJson: contentType.metadataSchemaJson ?? '',
  };
}

function buildVariantDraft(variant: ApiCmsEntry['variants'][number]): VariantDraft {
  return {
    entryId: String(variant.entryId),
    variantKey: variant.variantKey,
    name: variant.name,
    candidateGroupKey: variant.candidateGroupKey ?? '',
    status: variant.status,
    metadataJson: variant.metadataJson ?? '',
    sortOrder: String(variant.sortOrder),
  };
}

function hasCmsFormDraftChanges<T extends Record<string, unknown>>(draft: T, initialDraft: T) {
  return Object.keys(initialDraft).some((key) => draft[key] !== initialDraft[key]);
}

function buildCreateProjectPayload(draft: ProjectDraft) {
  return {
    projectKey: draft.projectKey,
    name: draft.name,
    description: normalizeText(draft.description),
    enabled: draft.enabled,
    repositoryId:
      draft.repositoryId.trim().length === 0
        ? null
        : parseRequiredNumber(draft.repositoryId, 'Repository'),
    assetPath: normalizeText(draft.assetPath),
    deliveryHint: normalizeText(draft.deliveryHint),
  };
}

function buildFirstCopyBlockPayload(draft: FirstCopyBlockDraft) {
  const entryName = draft.entryName.trim();
  const sourceContent = normalizeSourceContent(draft.sourceContent);
  const sourceComment = normalizeText(draft.sourceComment);
  if (!entryName) {
    throw new Error('Content item name is required.');
  }
  if (sourceContent == null) {
    throw new Error('Source copy is required.');
  }
  if (sourceComment == null) {
    throw new Error('Translator note is required.');
  }

  return {
    entryKey: draft.entryKey,
    entryName,
    entryDescription: normalizeText(draft.entryDescription),
    fieldKey: draft.fieldKey,
    sourceContent,
    sourceComment,
  };
}

function hasScopedCmsFormDraftChanges<T extends Record<string, unknown>, TScopeKey extends keyof T>(
  draft: T,
  initialDraft: T,
  scopeKeys: TScopeKey[],
) {
  const scopedInitialDraft = { ...initialDraft };
  scopeKeys.forEach((scopeKey) => {
    scopedInitialDraft[scopeKey] = draft[scopeKey];
  });
  return hasCmsFormDraftChanges(draft, scopedInitialDraft);
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

function mergePublishSnapshots(
  snapshots: ApiCmsPublishSnapshot[],
  nextSnapshots: ApiCmsPublishSnapshot[],
) {
  return nextSnapshots.reduce(upsertPublishSnapshot, snapshots);
}

function parsePublishLocaleTags(publishLocales: string) {
  return publishLocales.trim() ? publishLocales.split(',').map((tag) => tag.trim()) : [];
}

function formatIncludedReleaseEntryNames(includedEntryNames: string[]) {
  if (includedEntryNames.length === 0) {
    return 'approved copy';
  }
  if (includedEntryNames.length === 1) {
    return includedEntryNames[0];
  }
  if (includedEntryNames.length === 2) {
    return `${includedEntryNames[0]} and ${includedEntryNames[1]}`;
  }
  return `${includedEntryNames.slice(0, -1).join(', ')}, and ${
    includedEntryNames[includedEntryNames.length - 1]
  }`;
}

function getIncludedReleaseEntryNames(detail: ApiCmsProjectDetail) {
  return detail.entries.filter((entry) => entry.status === 'READY').map((entry) => entry.name);
}

function publishLocaleScopesMatch(left: string[], right: string[]) {
  return buildCmsPublishLocaleTagsKey(left) === buildCmsPublishLocaleTagsKey(right);
}

function getReleaseHistoryChange(
  latestSnapshot: ApiCmsPublishSnapshot | undefined,
  validatedPackage: ApiCmsProjectCompleteness | null,
  requestedLocaleTags: string[],
): ReleaseHistoryChange | null {
  if (latestSnapshot == null || validatedPackage == null) {
    return null;
  }
  const changeSummary = validatedPackage.releaseChangeSummary ?? {
    changes: [],
    hasMore: false,
    actionNeededCount: 0,
  };
  if (!publishLocaleScopesMatch(latestSnapshot.publishRequestLocaleTags, requestedLocaleTags)) {
    return {
      message: 'Translation languages changed since the last release.',
      changeSummary,
    };
  }
  if (latestSnapshot.publishRequestAuthoringSha256 !== validatedPackage.authoringSha256) {
    return {
      message: 'Copy or included content changed since the last release.',
      changeSummary,
    };
  }
  if (latestSnapshot.publishRequestPackageSha256 !== validatedPackage.publishPackageSha256) {
    return {
      message: 'Translation or approval status changed since the last release.',
      changeSummary,
    };
  }
  return null;
}

type CmsPageMode = 'author' | 'admin';

export function AdminContentCmsPage({ mode = 'author' }: { mode?: CmsPageMode } = {}) {
  const user = useUser();
  const isAdmin = user.role === 'ROLE_ADMIN';
  const isAdminToolsRoute = mode === 'admin';
  const location = useLocation();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const cmsAuthoringSelection = getCmsAuthoringSelectionFromSearchParams(searchParams);
  const queryClient = useQueryClient();
  const {
    data: locales,
    isLoading: localesLoading,
    isError: localesError,
    refetch: refetchLocales,
  } = useLocales(isAdmin);
  const resolveLocaleDisplayName = useLocaleDisplayNameResolver();

  const [projectSearch, setProjectSearch] = useState('');
  const [selectedProjectId, setSelectedProjectId] = useState<number | null>(
    cmsAuthoringSelection.projectId,
  );
  const [mobileCopyCollectionsOpen, setMobileCopyCollectionsOpen] = useState(false);
  const [isStartingWritingSpace, setIsStartingWritingSpace] = useState(false);
  const [notice, setNotice] = useState<StatusNotice | null>(null);
  const [projectDraft, setProjectDraft] = useState<ProjectDraft>(initialProjectDraft);
  const [contentTypeDraft, setContentTypeDraft] =
    useState<ContentTypeDraft>(initialContentTypeDraft);
  const [fieldDraft, setFieldDraft] = useState<FieldDraft>(initialFieldDraft);
  const [entryDraft, setEntryDraft] = useState<EntryDraft>(initialEntryDraft);
  const [newEntrySourceDrafts, setNewEntrySourceDrafts] = useState<Record<string, SourceCopyDraft>>(
    {},
  );
  const [firstCopyBlockDraft, setFirstCopyBlockDraft] = useState<FirstCopyBlockDraft>(
    initialFirstCopyBlockDraft,
  );
  const [variantDraft, setVariantDraft] = useState<VariantDraft>(initialVariantDraft);
  const [mappingDraft, setMappingDraft] = useState<MappingDraft>(initialMappingDraft);
  const [adminTranslationRepairReadyToReturn, setAdminTranslationRepairReadyToReturn] =
    useState(false);
  const [authoringFieldDrafts, setAuthoringFieldDrafts] = useState<
    Record<string, AuthoringFieldDraftState>
  >({});
  const [authoringEntrySavedDraft, setAuthoringEntrySavedDraft] =
    useState<EntryDraft>(initialEntryDraft);
  const [staleAuthoringEntrySavedDraft, setStaleAuthoringEntrySavedDraft] =
    useState<EntryDraft | null>(null);
  const [authoringMappingDrafts, setAuthoringMappingDrafts] = useState<
    Record<string, AuthoringMappingDraftState>
  >({});
  const [activeAuthoringFieldId, setActiveAuthoringFieldId] = useState(
    cmsAuthoringSelection.fieldId?.toString() ?? '',
  );
  const [projectEditDraft, setProjectEditDraft] =
    useState<ProjectEditDraft>(initialProjectEditDraft);
  const [contentTypeEditId, setContentTypeEditId] = useState('');
  const [contentTypeEditDraft, setContentTypeEditDraft] =
    useState<ContentTypeDraft>(initialContentTypeDraft);
  const [fieldEditId, setFieldEditId] = useState('');
  const [fieldEditDraft, setFieldEditDraft] = useState<FieldDraft>(initialFieldDraft);
  const [entryEditId, setEntryEditId] = useState(cmsAuthoringSelection.entryId?.toString() ?? '');
  const [entryEditDraft, setEntryEditDraft] = useState<EntryDraft>(initialEntryDraft);
  const [variantEditId, setVariantEditId] = useState('');
  const [variantEditDraft, setVariantEditDraft] = useState<VariantDraft>(initialVariantDraft);
  const [authoringToolsOpen, setAuthoringToolsOpen] = useState(false);
  const [structureRepairOpen, setStructureRepairOpen] = useState(false);
  const [structureRepairAction, setStructureRepairAction] = useState<StructureRepairAction>(null);
  const [variantMaintenanceOpen, setVariantMaintenanceOpen] = useState(false);
  const [mappingRepairOpen, setMappingRepairOpen] = useState(false);
  const [advancedMaintenanceOpen, setAdvancedMaintenanceOpen] = useState(false);
  const [writingSpaceMaintenanceOpen, setWritingSpaceMaintenanceOpen] = useState(false);
  const [copyStructureMaintenanceOpen, setCopyStructureMaintenanceOpen] = useState(false);
  const [copyStructureMaintenanceAction, setCopyStructureMaintenanceAction] =
    useState<CopyStructureMaintenanceAction>(null);
  const [rawRecordsOpen, setRawRecordsOpen] = useState(false);
  const [pendingCmsConfirmation, setPendingCmsConfirmation] = useState<CmsConfirmation | null>(
    null,
  );
  const [hasDirtyInlineTranslation, setHasDirtyInlineTranslation] = useState(false);
  const [inlineTranslationReleaseBlockerTargets, setInlineTranslationReleaseBlockerTargets] =
    useState<InlineTranslationReleaseBlockerTarget[]>([]);
  const hasInlineTranslationReleaseBlocker = inlineTranslationReleaseBlockerTargets.length > 0;
  const [isSavingInlineTranslation, setIsSavingInlineTranslation] = useState(false);
  const [pendingInlineTranslationFieldSwitch, setPendingInlineTranslationFieldSwitch] =
    useState<PendingInlineTranslationFieldSwitch | null>(null);
  const [authoringReviewTarget, setAuthoringReviewTarget] = useState<AuthoringReviewTarget>(
    initialAuthoringReviewTarget,
  );
  const selectedAuthoringReviewTarget =
    authoringReviewTarget.projectId === selectedProjectId
      ? authoringReviewTarget
      : initialAuthoringReviewTarget;
  const shouldRefocusAuthoringTranslationReconnect = (fieldId: string) =>
    !isAdminToolsRoute &&
    selectedAuthoringReviewTarget.fieldId === fieldId &&
    selectedAuthoringReviewTarget.translationReconnectErrorMessage != null;
  const [isWritingNewCopyBlock, setIsWritingNewCopyBlock] = useState(false);
  const [completenessResult, setCompletenessResult] = useState<ApiCmsProjectCompleteness | null>(
    null,
  );
  const [lastCheckedCompletenessResult, setLastCheckedCompletenessResult] =
    useState<ApiCmsProjectCompleteness | null>(null);
  const [authoringRecovery, setAuthoringRecovery] = useState<AuthoringRecovery | null>(null);
  const [recoveringRequestedTargetLocaleConflict, setRecoveringRequestedTargetLocaleConflict] =
    useState(false);
  const [releaseRecovery, setReleaseRecovery] = useState<ReleaseRecovery | null>(null);
  const [releaseSuccess, setReleaseSuccess] = useState<
    (ReleaseSuccess & { projectId: number }) | null
  >(null);
  const [releaseChangeSummaryError, setReleaseChangeSummaryError] = useState<string | null>(null);
  const [publishLocales, setPublishLocales] = useState('');
  const [targetLocaleTagsDraft, setTargetLocaleTagsDraft] = useState<string[]>([]);
  const [publishSnapshots, setPublishSnapshots] = useState<ApiCmsPublishSnapshot[]>([]);
  const [hasMorePublishSnapshots, setHasMorePublishSnapshots] = useState(false);
  const [nextBeforePublishSnapshotVersion, setNextBeforePublishSnapshotVersion] = useState<
    number | null
  >(null);
  const selectedProjectIdRef = useRef<number | null>(null);
  const authoringMappingDraftsRef = useRef(authoringMappingDrafts);
  const pendingFirstCopyBlockDraftRef = useRef<FirstCopyBlockDraft | null>(null);
  const pendingFirstCopyBlockRecoveryRef = useRef<Extract<
    AuthoringRecovery,
    { kind: 'first-copy-block' }
  > | null>(null);
  const pendingCreatedEntryIdRef = useRef<string | null>(null);
  const pendingCreatedFieldIdRef = useRef<string | null>(null);
  const publishIntentRef = useRef<CmsPublishIntent | null>(null);
  const autoReleaseCheckKeyRef = useRef('');
  const pendingReleaseRepairReadinessRefreshRef = useRef(false);
  const pendingRequestedTargetLocaleConflictRef =
    useRef<PendingRequestedTargetLocaleConflict | null>(null);
  const pendingAuthoringSourceCopyConflictRefreshRef =
    useRef<PendingAuthoringSourceCopyConflictRefresh | null>(null);
  const authoringReviewTargetsByProjectIdRef = useRef(new Map<number, AuthoringReviewTarget>());
  const requestedTargetLocaleSetupRefreshStartedRef = useRef(false);
  const pendingCmsConfirmationActionRef = useRef<(() => void) | null>(null);
  const pendingCmsConfirmationCancelRef = useRef<(() => void) | null>(null);
  const resolvingCmsExitConfirmationRef = useRef(false);
  const startWritingSpaceButtonRef = useRef<HTMLButtonElement>(null);
  const returnFocusToStartWritingSpaceButtonRef = useRef(false);
  const pendingStartingWritingSpaceCancelFieldFocusRef = useRef<Pick<
    PendingSavedFieldFocus,
    'fieldId' | 'focusTarget'
  > | null>(null);
  const reopenMobileCopyCollectionsAfterWritingSpaceCancelRef = useRef(false);
  const pendingProjectsRetryFocusRef = useRef(false);
  const pendingProjectSwitchFocusProjectIdRef = useRef<number | null>(null);
  const pendingSelectedProjectRetryFocusProjectIdRef = useRef<number | null>(null);
  const pendingSavedEntryFocusRef = useRef<PendingSavedEntryFocus | null>(null);
  const pendingSavedEntryDetailsFocusRef = useRef<PendingSavedEntryFocus | null>(null);
  const pendingSavedFieldFocusRef = useRef<PendingSavedFieldFocus | null>(null);
  const pendingTargetLocaleSetupFocusRef = useRef<PendingTargetLocaleSetupFocus | null>(null);
  const updateAuthoringReviewTarget = (
    update: AuthoringReviewTarget | ((current: AuthoringReviewTarget) => AuthoringReviewTarget),
  ) => {
    setAuthoringReviewTarget((current) => {
      const next = typeof update === 'function' ? update(current) : update;
      if (next.projectId != null) {
        authoringReviewTargetsByProjectIdRef.current.set(next.projectId, next);
      } else if (current.projectId != null) {
        authoringReviewTargetsByProjectIdRef.current.delete(current.projectId);
      }
      return next;
    });
  };
  const preserveNoticeOnSavedFieldFocusRef = useRef<Pick<
    PendingSavedFieldFocus,
    'fieldId' | 'focusTarget'
  > | null>(null);
  const queueSavedFieldFocus = useCallback(
    (savedFieldFocus: Pick<PendingSavedFieldFocus, 'fieldId' | 'focusTarget'>) => {
      preserveNoticeOnSavedFieldFocusRef.current = savedFieldFocus;
      queueCopyFieldScroll(savedFieldFocus.fieldId, savedFieldFocus.focusTarget);
      window.setTimeout(() => {
        if (
          preserveNoticeOnSavedFieldFocusRef.current?.fieldId === savedFieldFocus.fieldId &&
          preserveNoticeOnSavedFieldFocusRef.current.focusTarget === savedFieldFocus.focusTarget
        ) {
          preserveNoticeOnSavedFieldFocusRef.current = null;
        }
      }, 0);
    },
    [],
  );
  const draftSyncRef = useRef(createCmsDraftSyncState());
  const entryEditDraftRef = useRef(entryEditDraft);
  const pendingInlineTranslationFieldSwitchRef = useRef<PendingInlineTranslationFieldSwitch | null>(
    null,
  );
  authoringMappingDraftsRef.current = authoringMappingDrafts;
  entryEditDraftRef.current = entryEditDraft;

  const projectsQuery = useQuery({
    queryKey: [...CMS_QUERY_KEY, 'projects', projectSearch],
    queryFn: () =>
      fetchCmsProjects({ search: projectSearch, enabled: null, limit: CMS_PROJECT_SEARCH_LIMIT }),
    enabled: isAdmin,
    staleTime: 30_000,
  });

  const projects = projectsQuery.data?.projects ?? EMPTY_PROJECTS;
  const hasNoProjects =
    projectsQuery.isSuccess &&
    selectedProjectId == null &&
    projectSearch.trim().length === 0 &&
    projects.length === 0;
  const localeOptions = useMemo<LocaleOption[]>(
    () =>
      (locales ?? [])
        .map((locale) => ({
          tag: locale.bcp47Tag,
          label: resolveLocaleDisplayName(locale.bcp47Tag),
        }))
        .sort((left, right) => {
          const labelComparison = left.label.localeCompare(right.label, undefined, {
            sensitivity: 'base',
          });
          return labelComparison === 0
            ? left.tag.localeCompare(right.tag, undefined, { sensitivity: 'base' })
            : labelComparison;
        }),
    [locales, resolveLocaleDisplayName],
  );

  useEffect(() => {
    if (selectedProjectId == null && projects.length > 0) {
      setSelectedProjectId(projects[0].id);
    }
  }, [projects, selectedProjectId]);

  useEffect(() => {
    selectedProjectIdRef.current = selectedProjectId;
  }, [selectedProjectId]);

  useEffect(() => {
    if (!pendingProjectsRetryFocusRef.current || !projectsQuery.isSuccess) {
      return;
    }
    pendingProjectsRetryFocusRef.current = false;
    queueCopyCollectionRailRecoveryFocus();
  }, [projectsQuery.isSuccess]);

  useEffect(() => {
    const pendingFirstCopyBlockDraft = pendingFirstCopyBlockDraftRef.current;
    const pendingFirstCopyBlockRecovery = pendingFirstCopyBlockRecoveryRef.current;
    pendingFirstCopyBlockDraftRef.current = null;
    pendingFirstCopyBlockRecoveryRef.current = null;
    resetCmsDraftSyncState(draftSyncRef.current);
    setContentTypeDraft(initialContentTypeDraft);
    setFieldDraft(initialFieldDraft);
    setEntryDraft(initialEntryDraft);
    setNewEntrySourceDrafts({});
    setFirstCopyBlockDraft(pendingFirstCopyBlockDraft ?? initialFirstCopyBlockDraft);
    setVariantDraft(initialVariantDraft);
    setMappingDraft(initialMappingDraft);
    setAuthoringMappingDrafts({});
    setActiveAuthoringFieldId('');
    setIsSavingInlineTranslation(false);
    setInlineTranslationReleaseBlockerTargets([]);
    pendingInlineTranslationFieldSwitchRef.current = null;
    setPendingInlineTranslationFieldSwitch(null);
    setCompletenessResult(null);
    setLastCheckedCompletenessResult(null);
    setAuthoringRecovery(pendingFirstCopyBlockRecovery);
    setRecoveringRequestedTargetLocaleConflict(false);
    setReleaseRecovery(null);
    setReleaseSuccess(null);
    setPublishLocales('');
    setTargetLocaleTagsDraft([]);
    setAuthoringToolsOpen(false);
    setAdvancedMaintenanceOpen(false);
    setPendingCmsConfirmation(null);
    setAuthoringReviewTarget(
      selectedProjectId == null
        ? initialAuthoringReviewTarget
        : (authoringReviewTargetsByProjectIdRef.current.get(selectedProjectId) ??
            initialAuthoringReviewTarget),
    );
    pendingStartingWritingSpaceCancelFieldFocusRef.current = null;
    setIsWritingNewCopyBlock(false);
    pendingCmsConfirmationActionRef.current = null;
    pendingCmsConfirmationCancelRef.current = null;
    pendingCreatedEntryIdRef.current = null;
    pendingCreatedFieldIdRef.current = null;
    pendingTargetLocaleSetupFocusRef.current = null;
    publishIntentRef.current = null;
    pendingReleaseRepairReadinessRefreshRef.current = false;
    pendingRequestedTargetLocaleConflictRef.current = null;
    requestedTargetLocaleSetupRefreshStartedRef.current = false;
    reopenMobileCopyCollectionsAfterWritingSpaceCancelRef.current = false;
    setIsStartingWritingSpace(false);
  }, [selectedProjectId]);
  useEffect(() => {
    if (isStartingWritingSpace) {
      return;
    }
    const pendingStartingWritingSpaceCancelFieldFocus =
      pendingStartingWritingSpaceCancelFieldFocusRef.current;
    if (pendingStartingWritingSpaceCancelFieldFocus != null) {
      pendingStartingWritingSpaceCancelFieldFocusRef.current = null;
      returnFocusToStartWritingSpaceButtonRef.current = false;
      queueCopyFieldScroll(
        pendingStartingWritingSpaceCancelFieldFocus.fieldId,
        pendingStartingWritingSpaceCancelFieldFocus.focusTarget,
      );
      return;
    }
    if (!returnFocusToStartWritingSpaceButtonRef.current) {
      return;
    }
    returnFocusToStartWritingSpaceButtonRef.current = false;
    window.setTimeout(() => startWritingSpaceButtonRef.current?.focus({ preventScroll: true }), 0);
  }, [isStartingWritingSpace]);
  const projectQuery = useQuery({
    queryKey: [...CMS_QUERY_KEY, 'project', selectedProjectId],
    queryFn: () => fetchCmsProject(selectedProjectId ?? 0),
    enabled: isAdmin && selectedProjectId != null,
    staleTime: 30_000,
  });

  const projectDetail = projectQuery.data ?? null;
  useEffect(() => {
    if (projectDetail == null || releaseRecovery != null || publishIntentRef.current != null) {
      return;
    }
    const storedPublishIntent = loadLatestCmsPublishIntentForProject(
      projectDetail.project.id,
      projectDetail.authoringSha256,
    );
    if (storedPublishIntent == null) {
      return;
    }
    publishIntentRef.current = storedPublishIntent;
    setReleaseRecovery({
      kind: 'retry',
      message:
        'A previous release request may not have finished. Try Release approved copy again. If it keeps failing, ask an admin to check this release.',
    });
  }, [projectDetail, releaseRecovery]);

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
  const selectedAuthoringContentType = selectedEditEntry
    ? findContentType(contentTypes, selectedEditEntry.contentTypeId)
    : null;
  const selectedAuthoringFields = useMemo(
    () =>
      selectedAuthoringContentType?.fields
        .filter((field) => field.localizable)
        .sort((left, right) => left.sortOrder - right.sortOrder || left.id - right.id) ?? [],
    [selectedAuthoringContentType],
  );
  const selectedAuthoringVariant =
    selectedEditEntry?.variants.find((variant) => variant.status === 'CONTROL') ??
    selectedEditEntry?.variants[0] ??
    null;
  const selectedAuthoringField =
    selectedAuthoringFields.find((field) => String(field.id) === activeAuthoringFieldId) ??
    selectedAuthoringFields[0] ??
    null;
  useEffect(() => {
    const pendingSavedEntryFocus = pendingSavedEntryFocusRef.current;
    if (pendingSavedEntryFocus == null) {
      return;
    }
    if (isAdminToolsRoute) {
      pendingSavedEntryFocusRef.current = null;
      return;
    }
    if (
      projectDetail?.project.id !== pendingSavedEntryFocus.projectId ||
      selectedEditEntry?.id.toString() !== pendingSavedEntryFocus.entryId
    ) {
      return;
    }
    pendingSavedEntryFocusRef.current = null;
    queueCopyBlockEditorScroll(pendingSavedEntryFocus.entryId);
  }, [isAdminToolsRoute, projectDetail?.project.id, selectedEditEntry]);
  useEffect(() => {
    const pendingSavedEntryDetailsFocus = pendingSavedEntryDetailsFocusRef.current;
    if (pendingSavedEntryDetailsFocus == null) {
      return;
    }
    if (isAdminToolsRoute) {
      pendingSavedEntryDetailsFocusRef.current = null;
      return;
    }
    if (
      projectDetail?.project.id !== pendingSavedEntryDetailsFocus.projectId ||
      selectedEditEntry?.id.toString() !== pendingSavedEntryDetailsFocus.entryId
    ) {
      return;
    }
    pendingSavedEntryDetailsFocusRef.current = null;
    queueCopyBlockDetailsFocus(pendingSavedEntryDetailsFocus.entryId);
  }, [isAdminToolsRoute, projectDetail, selectedEditEntry]);
  useEffect(() => {
    const pendingSavedFieldFocus = pendingSavedFieldFocusRef.current;
    if (pendingSavedFieldFocus == null) {
      return;
    }
    if (isAdminToolsRoute) {
      pendingSavedFieldFocusRef.current = null;
      return;
    }
    if (
      projectDetail?.project.id !== pendingSavedFieldFocus.projectId ||
      selectedEditEntry?.id.toString() !== pendingSavedFieldFocus.entryId
    ) {
      return;
    }
    pendingSavedFieldFocusRef.current = null;
    queueSavedFieldFocus(pendingSavedFieldFocus);
  }, [isAdminToolsRoute, projectDetail, queueSavedFieldFocus, selectedEditEntry]);
  useEffect(() => {
    const shouldFocusAuthoringWorkspace =
      selectedProjectId != null &&
      (pendingProjectSwitchFocusProjectIdRef.current === selectedProjectId ||
        pendingSelectedProjectRetryFocusProjectIdRef.current === selectedProjectId);
    if (shouldFocusAuthoringWorkspace && isAdminToolsRoute) {
      pendingProjectSwitchFocusProjectIdRef.current = null;
      pendingSelectedProjectRetryFocusProjectIdRef.current = null;
      return;
    }
    if (
      selectedProjectId == null ||
      !shouldFocusAuthoringWorkspace ||
      projectDetail?.project.id !== selectedProjectId ||
      (selectedEditEntry == null && entries.length > 0)
    ) {
      return;
    }
    pendingProjectSwitchFocusProjectIdRef.current = null;
    pendingSelectedProjectRetryFocusProjectIdRef.current = null;
    queueAuthoringWorkspaceFocus(selectedEditEntry?.id.toString() ?? null);
  }, [
    entries.length,
    isAdminToolsRoute,
    projectDetail?.project.id,
    selectedEditEntry,
    selectedProjectId,
  ]);
  const selectedCmsPathContext = {
    projectId: selectedProjectId ?? cmsAuthoringSelection.projectId ?? undefined,
    entryId: selectedEditEntry?.id ?? cmsAuthoringSelection.entryId ?? undefined,
    fieldId: selectedAuthoringField?.id ?? cmsAuthoringSelection.fieldId ?? undefined,
  };
  const selectedCmsTranslationPathContext = {
    ...selectedCmsPathContext,
    localeTag: cmsAuthoringSelection.localeTag,
  };
  const adminTranslationRepairContext = useMemo<CmsMappingRepairContext | null>(
    () =>
      isAdminToolsRoute &&
      cmsAuthoringSelection.localeTag != null &&
      selectedEditEntry != null &&
      selectedAuthoringField != null
        ? {
            contentItemName: selectedEditEntry.name,
            fieldName: selectedAuthoringField.name,
            localeLabel: resolveLocaleDisplayName(cmsAuthoringSelection.localeTag),
          }
        : null,
    [
      cmsAuthoringSelection.localeTag,
      isAdminToolsRoute,
      resolveLocaleDisplayName,
      selectedAuthoringField,
      selectedEditEntry,
    ],
  );
  const cleanAuthoringPath = buildCmsSelectionPath(CMS_AUTHORING_PATH, selectedCmsPathContext);
  const authoringPath = buildCmsSelectionPath(
    CMS_AUTHORING_PATH,
    selectedCmsTranslationPathContext,
  );
  const savedTranslationRepairReturn = hasSavedTranslationRepairRouteState(location.state);
  const openAdvancedSetup = (localeTag: string | null = null) => {
    const targetLocaleTag = typeof localeTag === 'string' ? localeTag : null;
    void navigate(
      buildCmsSelectionPath(CMS_ADMIN_PATH, {
        ...selectedCmsPathContext,
        localeTag: targetLocaleTag,
      }),
    );
  };
  const appliedCmsTranslationReturnTargetKeyRef = useRef<string | null>(null);
  useEffect(() => {
    const translationReturnTargetKey =
      cmsAuthoringSelection.fieldId == null || cmsAuthoringSelection.localeTag == null
        ? null
        : `${cmsAuthoringSelection.fieldId}:${cmsAuthoringSelection.localeTag}`;
    if (isAdminToolsRoute) {
      if (translationReturnTargetKey != null) {
        appliedCmsTranslationReturnTargetKeyRef.current = null;
      }
      return;
    }
    if (
      translationReturnTargetKey == null ||
      appliedCmsTranslationReturnTargetKeyRef.current === translationReturnTargetKey
    ) {
      return;
    }
    appliedCmsTranslationReturnTargetKeyRef.current = translationReturnTargetKey;
    const fieldId = String(cmsAuthoringSelection.fieldId);
    setActiveAuthoringFieldId(fieldId);
    updateAuthoringReviewTarget((current) => ({
      requestKey: current.requestKey + 1,
      projectId: cmsAuthoringSelection.projectId,
      entryId: cmsAuthoringSelection.entryId == null ? null : String(cmsAuthoringSelection.entryId),
      fieldId,
      localeTag: cmsAuthoringSelection.localeTag,
      repairTarget: 'translation',
      reviewReason: null,
      reviewReturnMode: null,
      lastReleasedSourceContent: null,
      lastReleasedTranslationContent: null,
      returnToRelease: false,
      refreshTranslation: true,
      translationRepairSaved: savedTranslationRepairReturn,
      translationReconnectErrorMessage: null,
      requestedLocaleSetupRefreshPending: false,
    }));
    void navigate(cleanAuthoringPath, { replace: true });
  }, [
    cleanAuthoringPath,
    cmsAuthoringSelection.entryId,
    cmsAuthoringSelection.fieldId,
    cmsAuthoringSelection.localeTag,
    cmsAuthoringSelection.projectId,
    isAdminToolsRoute,
    navigate,
    savedTranslationRepairReturn,
  ]);
  const selectedAuthoringMapping =
    selectedAuthoringVariant?.fieldMappings.find(
      (mapping) => mapping.fieldId === selectedAuthoringField?.id,
    ) ?? null;
  const selectedAuthoringMappingDraftState =
    selectedAuthoringField == null
      ? null
      : (authoringMappingDrafts[String(selectedAuthoringField.id)] ?? null);
  const selectedAuthoringMappingDraft =
    selectedAuthoringMappingDraftState?.draft ??
    (selectedEditEntry != null && selectedAuthoringVariant != null && selectedAuthoringField != null
      ? buildMappingDraft({
          projectKey: projectDetail?.project.projectKey,
          entry: selectedEditEntry,
          variant: selectedAuthoringVariant,
          field: selectedAuthoringField,
          mapping: selectedAuthoringMapping,
        })
      : initialMappingDraft);
  const hasDirtyAuthoringFields = hasDirtyAuthoringFieldDrafts(authoringFieldDrafts);
  const hasDirtyAuthoringMappings = hasDirtyAuthoringMappingDrafts(authoringMappingDrafts);
  const pendingAuthoringSourceCopyConflictRefresh =
    pendingAuthoringSourceCopyConflictRefreshRef.current;
  const selectedAuthoringSourceCopyConflictRefreshPending =
    pendingAuthoringSourceCopyConflictRefresh?.fieldId ===
      String(selectedAuthoringField?.id ?? '') &&
    pendingAuthoringSourceCopyConflictRefresh.refreshStarted &&
    projectQuery.isFetching;
  const selectedAuthoringSourceCopyConflictRefreshFailed =
    pendingAuthoringSourceCopyConflictRefresh?.fieldId ===
      String(selectedAuthoringField?.id ?? '') &&
    pendingAuthoringSourceCopyConflictRefresh.refreshStarted &&
    projectQuery.isError &&
    projectDetail != null;
  const selectedEntryCompletenessQuery = useQuery<ApiCmsEntryCompleteness>({
    queryKey: [
      ...CMS_QUERY_KEY,
      'entry-completeness',
      selectedEditEntry?.id ?? null,
      projectDetail?.authoringSha256 ?? null,
    ],
    queryFn: () => fetchCmsEntryCompleteness(selectedEditEntry?.id ?? 0),
    enabled: isAdmin && selectedEditEntry != null,
    staleTime: 30_000,
  });
  useEffect(() => {
    const pendingTargetLocaleSetupFocus = pendingTargetLocaleSetupFocusRef.current;
    if (pendingTargetLocaleSetupFocus == null) {
      return;
    }
    if (isAdminToolsRoute) {
      pendingTargetLocaleSetupFocusRef.current = null;
      return;
    }
    if (
      projectDetail?.project.id !== pendingTargetLocaleSetupFocus.projectId ||
      selectedEditEntry?.id.toString() !== pendingTargetLocaleSetupFocus.entryId
    ) {
      return;
    }
    if (selectedEntryCompletenessQuery.isFetching) {
      pendingTargetLocaleSetupFocus.refreshStarted = true;
      return;
    }
    if (!pendingTargetLocaleSetupFocus.refreshStarted || selectedEntryCompletenessQuery.isError) {
      return;
    }
    pendingTargetLocaleSetupFocusRef.current = null;
    queueCopyFieldScroll(pendingTargetLocaleSetupFocus.fieldId, 'translation');
  }, [
    isAdminToolsRoute,
    projectDetail?.project.id,
    selectedEditEntry,
    selectedEntryCompletenessQuery.data,
    selectedEntryCompletenessQuery.isError,
    selectedEntryCompletenessQuery.isFetching,
  ]);

  useEffect(() => {
    if (selectedEditEntry == null || selectedAuthoringVariant == null) {
      setAuthoringFieldDrafts({});
      setAuthoringMappingDrafts({});
      setActiveAuthoringFieldId('');
      return;
    }

    setAuthoringFieldDrafts((current) => {
      const nextDrafts: Record<string, AuthoringFieldDraftState> = {};
      selectedAuthoringFields.forEach((field) => {
        const fieldId = String(field.id);
        const currentDraft = current[fieldId];
        const savedDraft = buildFieldDraft(field);
        if (
          currentDraft?.dirty &&
          currentDraft.draft.contentTypeId === String(field.contentTypeId)
        ) {
          if (fieldDraftsMatch(currentDraft.draft, savedDraft)) {
            nextDrafts[fieldId] = {
              ...currentDraft,
              dirty: false,
              expectedVersion: field.entityVersion,
              savedDraft,
              staleSavedDraft: null,
            };
            return;
          }
          const savedContextChanged = !fieldDraftsMatch(currentDraft.savedDraft, savedDraft);
          nextDrafts[fieldId] = {
            ...currentDraft,
            expectedVersion: field.entityVersion,
            savedDraft,
            staleSavedDraft: savedContextChanged ? savedDraft : currentDraft.staleSavedDraft,
          };
          return;
        }
        nextDrafts[fieldId] = {
          draft: savedDraft,
          dirty: false,
          expectedVersion: field.entityVersion,
          savedDraft,
          staleSavedDraft: null,
        };
      });
      return nextDrafts;
    });
    setAuthoringMappingDrafts((current) => {
      const nextDrafts: Record<string, AuthoringMappingDraftState> = {};
      selectedAuthoringFields.forEach((field) => {
        const fieldId = String(field.id);
        const currentDraft = current[fieldId];
        const mapping =
          selectedAuthoringVariant.fieldMappings.find((item) => item.fieldId === field.id) ?? null;
        const savedDraft = buildMappingDraft({
          projectKey: projectDetail?.project.projectKey,
          entry: selectedEditEntry,
          variant: selectedAuthoringVariant,
          field,
          mapping,
        });
        const expectedVersion = mapping?.entityVersion ?? null;
        if (
          currentDraft?.dirty &&
          currentDraft.draft.entryId === String(selectedEditEntry.id) &&
          currentDraft.draft.variantId === String(selectedAuthoringVariant.id)
        ) {
          if (mappingDraftsMatch(currentDraft.draft, savedDraft)) {
            nextDrafts[fieldId] = {
              ...currentDraft,
              dirty: false,
              expectedVersion,
              savedDraft,
              staleSavedDraft: null,
            };
            return;
          }
          const savedCopyChanged = !mappingDraftsMatch(currentDraft.savedDraft, savedDraft);
          nextDrafts[fieldId] = {
            ...currentDraft,
            expectedVersion,
            savedDraft,
            staleSavedDraft: savedCopyChanged ? savedDraft : currentDraft.staleSavedDraft,
          };
          return;
        }
        nextDrafts[fieldId] = {
          draft: savedDraft,
          dirty: false,
          expectedVersion,
          savedDraft,
          staleSavedDraft: null,
        };
      });
      return nextDrafts;
    });
    setActiveAuthoringFieldId((current) => {
      const pendingCreatedFieldId = pendingCreatedFieldIdRef.current;
      if (
        pendingCreatedFieldId != null &&
        selectedAuthoringFields.some((field) => String(field.id) === pendingCreatedFieldId)
      ) {
        return pendingCreatedFieldId;
      }
      return selectedAuthoringFields.some((field) => String(field.id) === current)
        ? current
        : (selectedAuthoringFields[0]?.id.toString() ?? '');
    });
  }, [
    projectDetail?.project.projectKey,
    selectedAuthoringFields,
    selectedAuthoringVariant,
    selectedEditEntry,
  ]);

  useEffect(() => {
    if (projectDetail == null) {
      setPublishSnapshots([]);
      setHasMorePublishSnapshots(false);
      setNextBeforePublishSnapshotVersion(null);
      setReleaseSuccess(null);
      resetCmsDraftSyncState(draftSyncRef.current);
      setAuthoringFieldDrafts({});
      setAuthoringMappingDrafts({});
      setActiveAuthoringFieldId('');
      setProjectEditDraft(initialProjectEditDraft);
      setContentTypeEditId('');
      setFieldEditId('');
      setEntryEditId('');
      setVariantEditId('');
      return;
    }
    setPublishSnapshots((current) =>
      mergePublishSnapshots(
        current.filter((snapshot) => snapshot.projectId === projectDetail.project.id),
        projectDetail.publishSnapshots,
      ),
    );
    setHasMorePublishSnapshots(projectDetail.hasMorePublishSnapshots);
    setNextBeforePublishSnapshotVersion(projectDetail.nextBeforePublishSnapshotVersion ?? null);
    if (!draftSyncRef.current.projectEdit.dirty) {
      hydrateCmsDraftSyncState(
        draftSyncRef.current,
        'projectEdit',
        projectDetail.project.entityVersion,
      );
      setProjectEditDraft(buildProjectEditDraft(projectDetail.project));
    }
    setContentTypeEditId((current) => {
      if (contentTypes.some((contentType) => String(contentType.id) === current)) {
        return current;
      }
      resetCmsDraftSyncState(draftSyncRef.current, 'contentTypeEdit', 'fieldEdit');
      return contentTypes[0]?.id.toString() ?? '';
    });
    setEntryEditId((current) => {
      const pendingCreatedEntryId = pendingCreatedEntryIdRef.current;
      if (
        pendingCreatedEntryId != null &&
        entries.some((entry) => String(entry.id) === pendingCreatedEntryId)
      ) {
        pendingCreatedEntryIdRef.current = null;
        resetCmsDraftSyncState(draftSyncRef.current, 'entryEdit', 'variantEdit');
        return pendingCreatedEntryId;
      }
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
      setContentTypeEditDraft(buildContentTypeDraft(selectedEditContentType));
    }
    setFieldEditId((current) => {
      const pendingCreatedFieldId = pendingCreatedFieldIdRef.current;
      if (
        pendingCreatedFieldId != null &&
        selectedEditContentType.fields.some((field) => String(field.id) === pendingCreatedFieldId)
      ) {
        resetCmsDraftSyncState(draftSyncRef.current, 'fieldEdit');
        return pendingCreatedFieldId;
      }
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
      setFieldEditDraft(buildFieldDraft(selectedEditField));
    }
  }, [selectedEditField]);

  useEffect(() => {
    if (selectedEditEntry == null) {
      resetCmsDraftSyncState(draftSyncRef.current, 'entryEdit', 'variantEdit');
      setEntryEditDraft(initialEntryDraft);
      setAuthoringEntrySavedDraft(initialEntryDraft);
      setStaleAuthoringEntrySavedDraft(null);
      setVariantEditId('');
      return;
    }
    const savedDraft = buildEntryDraft(selectedEditEntry);
    if (!draftSyncRef.current.entryEdit.dirty) {
      hydrateCmsDraftSyncState(draftSyncRef.current, 'entryEdit', selectedEditEntry.entityVersion);
      setEntryEditDraft(savedDraft);
      setAuthoringEntrySavedDraft(savedDraft);
      setStaleAuthoringEntrySavedDraft(null);
    } else if (!isAdminToolsRoute) {
      draftSyncRef.current.entryEdit.expectedVersion = selectedEditEntry.entityVersion;
      setAuthoringEntrySavedDraft((currentSavedDraft) => {
        if (entryDraftsMatch(entryEditDraftRef.current, savedDraft)) {
          clearCmsDraftDirty(draftSyncRef.current, 'entryEdit');
          setEntryEditDraft(savedDraft);
          setStaleAuthoringEntrySavedDraft(null);
          return savedDraft;
        }
        const savedDetailsChanged = !entryDraftsMatch(currentSavedDraft, savedDraft);
        setStaleAuthoringEntrySavedDraft((current) => (savedDetailsChanged ? savedDraft : current));
        return savedDraft;
      });
    }
    setVariantEditId((current) => {
      if (selectedEditEntry.variants.some((variant) => String(variant.id) === current)) {
        return current;
      }
      resetCmsDraftSyncState(draftSyncRef.current, 'variantEdit');
      return selectedEditEntry.variants[0]?.id.toString() ?? '';
    });
  }, [isAdminToolsRoute, selectedEditEntry]);

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
      setVariantEditDraft(buildVariantDraft(selectedEditVariant));
    }
  }, [selectedEditVariant]);

  useEffect(() => {
    if (draftSyncRef.current.mapping.dirty || selectedEditEntry == null) {
      return;
    }
    const nextEntryId = String(selectedEditEntry.id);
    const nextVariantId =
      selectedEditEntry.variants
        .find((variant) => String(variant.id) === mappingDraft.variantId)
        ?.id.toString() ??
      selectedAuthoringVariant?.id.toString() ??
      '';
    const pendingCreatedField =
      pendingCreatedFieldIdRef.current == null
        ? null
        : (selectedAuthoringFields.find(
            (field) => String(field.id) === pendingCreatedFieldIdRef.current,
          ) ?? null);
    const nextFieldId =
      pendingCreatedField?.id ??
      selectedAuthoringFields.find((field) => String(field.id) === mappingDraft.fieldId)?.id ??
      selectedAuthoringFields[0]?.id ??
      null;
    const nextFieldValue = nextFieldId == null ? '' : String(nextFieldId);
    if (pendingCreatedField != null) {
      pendingCreatedFieldIdRef.current = null;
    }
    if (
      mappingDraft.entryId === nextEntryId &&
      mappingDraft.variantId === nextVariantId &&
      mappingDraft.fieldId === nextFieldValue
    ) {
      return;
    }
    resetCmsDraftSyncState(draftSyncRef.current, 'mapping');
    setMappingDraft((current) => ({
      ...current,
      entryId: nextEntryId,
      variantId: nextVariantId,
      fieldId: nextFieldValue,
    }));
  }, [
    mappingDraft.entryId,
    mappingDraft.fieldId,
    mappingDraft.variantId,
    selectedAuthoringFields,
    selectedAuthoringVariant,
    selectedEditEntry,
  ]);

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

  const clearStaleDerivedState = useCallback(
    (projectId: number | null) => {
      if (completenessResult != null) {
        setLastCheckedCompletenessResult(completenessResult);
      }
      setCompletenessResult(null);
      setAuthoringRecovery(null);
      setRecoveringRequestedTargetLocaleConflict(false);
      setReleaseRecovery(null);
      setReleaseSuccess(null);
      setReleaseChangeSummaryError(null);
      if (projectId != null) {
        clearCmsPublishIntentsForProject(projectId);
      }
      publishIntentRef.current = null;
      pendingRequestedTargetLocaleConflictRef.current = null;
    },
    [completenessResult],
  );
  useEffect(() => {
    if (!hasInlineTranslationReleaseBlocker) {
      return;
    }
    clearStaleDerivedState(selectedProjectId);
  }, [clearStaleDerivedState, hasInlineTranslationReleaseBlocker, selectedProjectId]);

  useEffect(() => {
    const pendingRequestedTargetLocaleConflict = pendingRequestedTargetLocaleConflictRef.current;
    const requestedLocaleTag = pendingRequestedTargetLocaleConflict?.localeTag ?? null;
    if (
      requestedLocaleTag == null ||
      selectedEntryCompletenessQuery.data?.locales.some(
        (locale) => locale.localeTag === requestedLocaleTag,
      ) !== true
    ) {
      if (pendingRequestedTargetLocaleConflict == null) {
        return;
      }
      if (selectedEntryCompletenessQuery.isFetching) {
        pendingRequestedTargetLocaleConflict.refreshStarted = true;
        setRecoveringRequestedTargetLocaleConflict(true);
        return;
      }
      if (pendingRequestedTargetLocaleConflict.refreshStarted) {
        const requestedLocaleLabel = resolveLocaleDisplayName(
          pendingRequestedTargetLocaleConflict.localeTag,
        );
        const settledRecoveryMessage = getRequestedTargetLocaleConflictSettledRecoveryMessage({
          requestedLocaleLabel,
          refreshFailed: selectedEntryCompletenessQuery.isError,
          returnToRelease: pendingRequestedTargetLocaleConflict.returnToRelease,
        });
        if (selectedEntryCompletenessQuery.isError) {
          pendingRequestedTargetLocaleConflict.refreshStarted = false;
        } else {
          pendingRequestedTargetLocaleConflictRef.current = null;
        }
        setRecoveringRequestedTargetLocaleConflict(false);
        setAuthoringRecovery((current) =>
          current?.kind === 'target-locales'
            ? {
                ...current,
                message: settledRecoveryMessage,
              }
            : current,
        );
        if (isAdminToolsRoute) {
          setNotice({ kind: 'error', message: settledRecoveryMessage });
        } else {
          setNotice(null);
        }
      }
      return;
    }
    pendingRequestedTargetLocaleConflictRef.current = null;
    setRecoveringRequestedTargetLocaleConflict(false);
    setAuthoringRecovery((current) => (current?.kind === 'target-locales' ? null : current));
    setNotice(null);
  }, [
    isAdminToolsRoute,
    resolveLocaleDisplayName,
    selectedEntryCompletenessQuery.data,
    selectedEntryCompletenessQuery.isError,
    selectedEntryCompletenessQuery.isFetching,
  ]);
  useEffect(() => {
    if (!selectedAuthoringReviewTarget.requestedLocaleSetupRefreshPending) {
      requestedTargetLocaleSetupRefreshStartedRef.current = false;
      return;
    }
    if (selectedEntryCompletenessQuery.isFetching) {
      requestedTargetLocaleSetupRefreshStartedRef.current = true;
      return;
    }
    if (
      !requestedTargetLocaleSetupRefreshStartedRef.current ||
      selectedEntryCompletenessQuery.isError
    ) {
      return;
    }
    updateAuthoringReviewTarget((current) =>
      current.requestedLocaleSetupRefreshPending
        ? {
            ...current,
            requestedLocaleSetupRefreshPending: false,
          }
        : current,
    );
  }, [
    selectedAuthoringReviewTarget.requestedLocaleSetupRefreshPending,
    selectedEntryCompletenessQuery.data,
    selectedEntryCompletenessQuery.isError,
    selectedEntryCompletenessQuery.isFetching,
  ]);
  useEffect(() => {
    const pendingSourceCopyConflictRefresh = pendingAuthoringSourceCopyConflictRefreshRef.current;
    if (
      pendingSourceCopyConflictRefresh == null ||
      !pendingSourceCopyConflictRefresh.refreshStarted ||
      projectQuery.isFetching ||
      projectQuery.isError
    ) {
      return;
    }
    if (!isAdminToolsRoute) {
      queueCopyFieldScroll(pendingSourceCopyConflictRefresh.fieldId, 'source-copy');
    }
    pendingAuthoringSourceCopyConflictRefreshRef.current = null;
  }, [isAdminToolsRoute, projectQuery.isError, projectQuery.isFetching, projectQuery.data]);
  useEffect(() => {
    const pendingSourceCopyConflictRefresh = pendingAuthoringSourceCopyConflictRefreshRef.current;
    if (
      pendingSourceCopyConflictRefresh == null ||
      !pendingSourceCopyConflictRefresh.refreshStarted ||
      !projectQuery.isError ||
      projectDetail == null
    ) {
      return;
    }
    setAuthoringRecovery((current) =>
      current?.kind === 'source-copy' &&
      current.fieldId === pendingSourceCopyConflictRefresh.fieldId
        ? {
            ...current,
            message: AUTHORING_SOURCE_COPY_REFRESH_FAILED_MESSAGE,
          }
        : current,
    );
  }, [projectDetail, projectQuery.isError]);

  const scheduleReleaseReadinessRefreshAfterRepair = ({
    markRepairSaved = true,
  }: {
    markRepairSaved?: boolean;
  } = {}) => {
    if (
      selectedProjectId == null ||
      !selectedAuthoringReviewTarget.returnToRelease ||
      selectedAuthoringReviewTarget.reviewReturnMode !== 'repair'
    ) {
      return;
    }
    pendingReleaseRepairReadinessRefreshRef.current = true;
    if (!markRepairSaved) {
      return;
    }
    updateAuthoringReviewTarget((current) =>
      current.returnToRelease && current.reviewReturnMode === 'repair'
        ? {
            ...current,
            reviewReason: null,
            reviewReturnMode: 'repair-saved',
          }
        : current,
    );
  };
  const isCurrentReleaseSourceCopyRepairBlocked = (
    response: ApiCmsProjectCompleteness,
    current: AuthoringReviewTarget,
  ) => {
    if (
      projectDetail == null ||
      !current.returnToRelease ||
      current.reviewReturnMode !== 'repair' ||
      current.repairTarget !== 'source-copy' ||
      current.entryId == null ||
      current.fieldId == null
    ) {
      return false;
    }
    const currentFieldCompleteness = response.entries
      .find((entry) => String(entry.entryId) === current.entryId)
      ?.fields.find((field) => String(field.fieldId) === current.fieldId);
    const sourceLocaleCompleteness =
      currentFieldCompleteness?.locales.find(
        (locale) => locale.localeTag === projectDetail.project.sourceLocale,
      ) ?? null;
    return sourceLocaleCompleteness?.complete === false;
  };
  const settleReleaseSourceCopyRepairFromReadiness = (
    response: ApiCmsProjectCompleteness,
    adoptedSavedCopyFieldId: string | null = null,
  ) => {
    updateAuthoringReviewTarget((current) =>
      projectDetail == null ||
      current.fieldId == null ||
      (current.fieldId !== adoptedSavedCopyFieldId &&
        authoringMappingDraftsRef.current[current.fieldId]?.dirty === true) ||
      isCurrentReleaseSourceCopyRepairBlocked(response, current)
        ? current
        : current.returnToRelease &&
            current.reviewReturnMode === 'repair' &&
            current.repairTarget === 'source-copy'
          ? {
              ...current,
              reviewReason: null,
              reviewReturnMode: 'repair-saved',
            }
          : current,
    );
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
    setAuthoringRecovery(null);
    setReleaseRecovery(null);
    setReleaseSuccess(null);
    clearCmsPublishIntentsForProject(projectDetail.project.id);
    publishIntentRef.current = null;
    setNotice({
      kind: 'error',
      message: isAdminToolsRoute
        ? 'Content changed since the release check. Check readiness again.'
        : 'Copy changed before release. Review it, then release approved copy again.',
    });
  }, [completenessResult, isAdminToolsRoute, projectDetail]);

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

  const rememberSavedEntryFocus = (detail: ApiCmsProjectDetail, entry: ApiCmsEntry | null) => {
    if (isAdminToolsRoute || entry == null) {
      return;
    }
    pendingSavedEntryFocusRef.current = {
      projectId: detail.project.id,
      entryId: String(entry.id),
    };
  };

  const rememberSavedEntryDetailsFocus = (
    detail: ApiCmsProjectDetail,
    entryDetailsFocus: { entryId: string } | null,
  ) => {
    if (isAdminToolsRoute || entryDetailsFocus == null) {
      return;
    }
    pendingSavedEntryDetailsFocusRef.current = {
      projectId: detail.project.id,
      entryId: entryDetailsFocus.entryId,
    };
  };

  const rememberSavedFieldFocus = (
    detail: ApiCmsProjectDetail,
    savedFieldFocus: Omit<PendingSavedFieldFocus, 'projectId'> | null,
  ) => {
    if (isAdminToolsRoute || savedFieldFocus == null) {
      return;
    }
    pendingSavedFieldFocusRef.current = {
      projectId: detail.project.id,
      ...savedFieldFocus,
    };
  };

  const handleMutationError = (
    error: Error,
    fallbackMessage: string,
    {
      conflictingDraftKey,
      authorFacingMessage,
      authorConflictMessage = 'Copy changed while you were editing. Current copy is refreshing; review and save again.',
      isConflict = isCmsConflictError(error),
      suppressNotice = false,
    }: CmsMutationErrorNoticeOptions = {},
  ) => {
    if (isConflict) {
      if (conflictingDraftKey != null) {
        resetCmsDraftSyncState(draftSyncRef.current, conflictingDraftKey);
      }
      clearStaleDerivedState(selectedProjectId);
      invalidateCmsQueries();
      if (!suppressNotice) {
        setNotice({
          kind: 'error',
          message: isAdminToolsRoute
            ? 'Content changed since it was loaded. Refreshing current CMS data; review and save again.'
            : authorConflictMessage,
        });
      }
      return;
    }
    if (!suppressNotice) {
      setNotice({
        kind: 'error',
        message: isAdminToolsRoute
          ? error.message || fallbackMessage
          : (authorFacingMessage ?? fallbackMessage),
      });
    }
  };

  const createFirstWritingSpaceMutation = useMutation({
    mutationFn: async ({
      projectDraft,
      firstCopyBlockDraft,
    }: {
      projectDraft: ProjectDraft;
      firstCopyBlockDraft: FirstCopyBlockDraft;
    }) => {
      const projectDetail = await createCmsProject(buildCreateProjectPayload(projectDraft));
      try {
        return await createCmsFirstCopyBlock(
          projectDetail.project.id,
          buildFirstCopyBlockPayload(firstCopyBlockDraft),
        );
      } catch (error) {
        throw new CmsFirstWritingSpaceError(
          (error as Error).message || 'Failed to create first content item.',
          projectDetail,
        );
      }
    },
    onSuccess: (detail, variables) => {
      rememberSavedEntryFocus(
        detail,
        detail.entries.find((entry) => entry.entryKey === variables.firstCopyBlockDraft.entryKey) ??
          detail.entries[0] ??
          null,
      );
      handleDetailSuccess(detail, `Created first content item in ${detail.project.name}.`, () => {
        setIsStartingWritingSpace(false);
        setProjectDraft(initialProjectDraft);
        setFirstCopyBlockDraft(initialFirstCopyBlockDraft);
      });
    },
    onError: (error: Error, variables) => {
      if (error instanceof CmsFirstWritingSpaceError) {
        const authoringRecoveryMessage =
          'The copy collection was created, but the first content item did not save. Finish the first content item here.';
        pendingFirstCopyBlockDraftRef.current = variables.firstCopyBlockDraft;
        pendingFirstCopyBlockRecoveryRef.current = {
          kind: 'first-copy-block',
          message: authoringRecoveryMessage,
        };
        handleDetailSuccess(
          error.projectDetail,
          `Created copy collection ${error.projectDetail.project.name}.`,
          () => {
            setIsStartingWritingSpace(false);
            setProjectDraft(initialProjectDraft);
            queueAuthoringWorkspaceFocus(null);
          },
        );
        if (isAdminToolsRoute) {
          setNotice({
            kind: 'error',
            message: `${error.message} The copy collection was created; finish the first content item here.`,
          });
        } else {
          setNotice(null);
        }
        return;
      }
      const authoringRecoveryMessage =
        'Could not start writing. Try again. If it keeps failing, ask an admin to check this copy collection.';
      handleMutationError(error, 'Failed to start writing.', {
        authorFacingMessage: authoringRecoveryMessage,
        suppressNotice: !isAdminToolsRoute,
      });
      setAuthoringRecovery({
        kind: 'first-writing-space',
        message: authoringRecoveryMessage,
      });
    },
  });

  const createContentTypeMutation = useMutation({
    mutationFn: async ({
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
      handleDetailSuccess(detail, `Created block shape in ${detail.project.name}.`, () => {
        setContentTypeDraft(initialContentTypeDraft);
      });
    },
    onError: (error: Error) => {
      handleMutationError(error, 'Failed to create block shape.');
    },
  });

  const createFirstCopyBlockMutation = useMutation({
    mutationFn: async ({ projectId, draft }: { projectId: number; draft: FirstCopyBlockDraft }) =>
      createCmsFirstCopyBlock(projectId, buildFirstCopyBlockPayload(draft)),
    onSuccess: (detail, variables) => {
      rememberSavedEntryFocus(
        detail,
        detail.entries.find((entry) => entry.entryKey === variables.draft.entryKey) ??
          detail.entries[0] ??
          null,
      );
      handleDetailSuccess(detail, `Created first content item in ${detail.project.name}.`, () => {
        setFirstCopyBlockDraft(initialFirstCopyBlockDraft);
      });
    },
    onError: (error: Error) => {
      const isConflict = isCmsConflictError(error);
      const authoringRecoveryMessage = isConflict
        ? 'This copy collection changed before the first content item was saved. Current copy is refreshing; review and try again.'
        : 'Could not save the first content item. Try again. If it keeps failing, ask an admin to check this copy collection.';
      handleMutationError(error, 'Failed to create first content item.', {
        authorFacingMessage: authoringRecoveryMessage,
        authorConflictMessage: authoringRecoveryMessage,
        isConflict,
        suppressNotice: !isAdminToolsRoute,
      });
      setAuthoringRecovery({
        kind: 'first-copy-block',
        message: authoringRecoveryMessage,
      });
    },
  });

  const updateProjectMutation = useMutation({
    mutationFn: async ({
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
      handleDetailSuccess(detail, `Updated copy collection ${detail.project.name}.`);
    },
    onError: (error: Error) => {
      handleMutationError(error, 'Failed to update copy collection.', {
        conflictingDraftKey: 'projectEdit',
      });
    },
  });

  const addTargetLocalesMutation = useMutation({
    mutationFn: async ({ projectId, localeTags }: AddTargetLocalesVariables) =>
      addCmsProjectTargetLocales(projectId, localeTags),
    onSuccess: (detail, variables) => {
      pendingRequestedTargetLocaleConflictRef.current = null;
      setRecoveringRequestedTargetLocaleConflict(false);
      if (variables.requestedLocaleTag != null) {
        requestedTargetLocaleSetupRefreshStartedRef.current = false;
        updateAuthoringReviewTarget((current) =>
          current.localeTag === variables.requestedLocaleTag
            ? {
                ...current,
                requestedLocaleSetupRefreshPending: true,
              }
            : current,
        );
      }
      if (
        !isAdminToolsRoute &&
        variables.requestedLocaleTag == null &&
        selectedEditEntry != null &&
        selectedAuthoringFields[0] != null
      ) {
        pendingTargetLocaleSetupFocusRef.current = {
          projectId: detail.project.id,
          entryId: String(selectedEditEntry.id),
          fieldId: String(selectedAuthoringFields[0].id),
          refreshStarted: false,
        };
      }
      handleDetailSuccess(
        detail,
        `Added ${variables.localeTags.length} translation language${
          variables.localeTags.length === 1 ? '' : 's'
        } to ${detail.project.name}.`,
        () => {
          setTargetLocaleTagsDraft([]);
          void queryClient.invalidateQueries({ queryKey: REPOSITORIES_QUERY_KEY });
          void selectedEntryCompletenessQuery.refetch();
        },
      );
    },
    onError: (error: Error, variables) => {
      const isConflict = isCmsConflictError(error);
      const requestedLocaleLabel =
        variables.requestedLocaleTag == null
          ? null
          : resolveLocaleDisplayName(variables.requestedLocaleTag);
      const authoringRecoveryMessage = isConflict
        ? requestedLocaleLabel == null
          ? 'Translation languages changed while you were choosing them. Current copy is refreshing; choose languages again.'
          : `Translation languages changed before ${requestedLocaleLabel} was added. Current copy is refreshing; try Add ${requestedLocaleLabel} again if it is still not set up.`
        : 'Could not add translation languages. Try again. If it keeps failing, ask an admin to check language setup.';
      handleMutationError(error, 'Failed to add translation languages.', {
        authorFacingMessage:
          'Could not add translation languages. Try again. If it keeps failing, ask an admin to check language setup.',
        authorConflictMessage: authoringRecoveryMessage,
        isConflict,
        suppressNotice: !isAdminToolsRoute,
      });
      if (!isAdminToolsRoute && isConflict && variables.requestedLocaleTag != null) {
        pendingRequestedTargetLocaleConflictRef.current = {
          localeTag: variables.requestedLocaleTag,
          refreshStarted: false,
          returnToRelease: variables.returnToRelease ?? false,
        };
        setRecoveringRequestedTargetLocaleConflict(true);
      }
      if (variables.requestedLocaleTag != null) {
        updateAuthoringReviewTarget((current) =>
          current.localeTag === variables.requestedLocaleTag
            ? {
                ...current,
                requestedLocaleSetupRefreshPending: false,
              }
            : current,
        );
      }
      if (!isAdminToolsRoute) {
        setAuthoringRecovery({
          kind: 'target-locales',
          message: authoringRecoveryMessage,
        });
      }
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
      handleDetailSuccess(detail, `Updated block shape in ${detail.project.name}.`);
    },
    onError: (error: Error) => {
      handleMutationError(error, 'Failed to update block shape.', {
        conflictingDraftKey: 'contentTypeEdit',
      });
    },
  });

  const updateFieldMutation = useMutation({
    mutationFn: ({
      fieldId,
      payload,
    }: {
      fieldId: number;
      authoringFieldContextSave?: boolean;
      authoringFieldContextFocus?: { entryId: string; fieldId: string } | null;
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
    onSuccess: (detail, variables) => {
      const isAuthoringFieldContextSave =
        !isAdminToolsRoute && variables.authoringFieldContextSave === true;
      clearCmsDraftDirty(draftSyncRef.current, 'fieldEdit');
      setAuthoringFieldDrafts((current) => {
        const fieldId = String(variables.fieldId);
        const draftState = current[fieldId];
        if (draftState == null || !draftState.dirty) {
          return current;
        }
        return {
          ...current,
          [fieldId]: {
            ...draftState,
            dirty: false,
          },
        };
      });
      rememberSavedFieldFocus(
        detail,
        variables.authoringFieldContextFocus == null
          ? null
          : {
              ...variables.authoringFieldContextFocus,
              focusTarget: 'field-context',
            },
      );
      handleDetailSuccess(
        detail,
        isAuthoringFieldContextSave
          ? `Saved placement details in ${detail.project.name}.`
          : `Updated field in ${detail.project.name}.`,
      );
    },
    onError: (error: Error, variables) => {
      const isAuthoringFieldContextSave =
        !isAdminToolsRoute && variables.authoringFieldContextSave === true;
      const isConflict = isCmsConflictError(error);
      const authoringFieldContextRecoveryMessage = isConflict
        ? 'Placement details changed while you were editing. Current copy is refreshing; review and save again.'
        : 'Could not save placement details. Try again. If it keeps failing, ask an admin to check this field.';
      handleMutationError(error, 'Failed to update field.', {
        conflictingDraftKey: 'fieldEdit',
        authorFacingMessage:
          'Could not save placement details. Try again. If it keeps failing, ask an admin to check this field.',
        authorConflictMessage: isAuthoringFieldContextSave
          ? authoringFieldContextRecoveryMessage
          : undefined,
        isConflict,
        suppressNotice: isAuthoringFieldContextSave,
      });
      if (isAuthoringFieldContextSave) {
        setAuthoringRecovery({
          kind: 'field-context',
          fieldId: String(variables.fieldId),
          message: authoringFieldContextRecoveryMessage,
        });
      }
    },
  });

  const updateEntryMutation = useMutation({
    mutationFn: ({
      entryId,
      payload,
    }: {
      entryId: number;
      authoringBlockDetailsSave?: boolean;
      authoringBlockDetailsFocus?: { entryId: string } | null;
      authoringReleaseStatusChange?: boolean;
      payload: {
        name: string;
        description?: string | null;
        status: ApiCmsEntryStatus;
        metadataJson?: string | null;
        expectedVersion: number;
      };
    }) => updateCmsEntry(entryId, payload),
    onSuccess: (detail, variables) => {
      const isAuthoringBlockDetailsSave =
        !isAdminToolsRoute && variables.authoringBlockDetailsSave === true;
      clearCmsDraftDirty(draftSyncRef.current, 'entryEdit');
      rememberSavedEntryDetailsFocus(detail, variables.authoringBlockDetailsFocus ?? null);
      handleDetailSuccess(
        detail,
        isAuthoringBlockDetailsSave
          ? `Saved copy details in ${detail.project.name}.`
          : `Updated content item in ${detail.project.name}.`,
      );
      if (
        !isAdminToolsRoute &&
        variables.authoringReleaseStatusChange === true &&
        variables.payload.status === 'READY'
      ) {
        requestAnimationFrame(() => {
          focusCmsReleasePanel();
        });
      }
    },
    onError: (error: Error, variables) => {
      const isAuthoringReleaseStatusChange =
        !isAdminToolsRoute && variables.authoringReleaseStatusChange === true;
      const isAuthoringBlockDetailsSave =
        !isAdminToolsRoute && variables.authoringBlockDetailsSave === true;
      const isConflict = isCmsConflictError(error);
      const authoringReleaseRecoveryMessage = getAuthoringReleaseRecoveryMessage({
        status: variables.payload.status,
        conflict: isConflict,
      });
      const authoringBlockDetailsRecoveryMessage = isConflict
        ? 'Copy details changed while you were editing. Current copy is refreshing; review and save again.'
        : 'Could not save content details. Try again. If it keeps failing, ask an admin to check this content item.';
      handleMutationError(error, 'Failed to update content item.', {
        conflictingDraftKey: isAuthoringBlockDetailsSave ? undefined : 'entryEdit',
        authorFacingMessage: isAuthoringReleaseStatusChange
          ? authoringReleaseRecoveryMessage
          : isAuthoringBlockDetailsSave
            ? authoringBlockDetailsRecoveryMessage
            : 'Could not save this content item. Try again. If it keeps failing, ask an admin to check this content item.',
        authorConflictMessage: isAuthoringReleaseStatusChange
          ? authoringReleaseRecoveryMessage
          : isAuthoringBlockDetailsSave
            ? authoringBlockDetailsRecoveryMessage
            : undefined,
        isConflict,
        suppressNotice: isAuthoringReleaseStatusChange || isAuthoringBlockDetailsSave,
      });
      if (isAuthoringReleaseStatusChange) {
        setAuthoringRecovery({
          kind: 'block-release',
          entryId: String(variables.entryId),
          message: authoringReleaseRecoveryMessage,
        });
      } else if (isAuthoringBlockDetailsSave) {
        setAuthoringRecovery({
          kind: 'block-details',
          entryId: String(variables.entryId),
          message: authoringBlockDetailsRecoveryMessage,
        });
      }
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
      handleDetailSuccess(detail, `Updated experiment path in ${detail.project.name}.`);
    },
    onError: (error: Error) => {
      handleMutationError(error, 'Failed to update experiment path.', {
        conflictingDraftKey: 'variantEdit',
      });
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
        initialFieldSource?: {
          variantId: number;
          sourceContent: string;
          sourceComment: string;
        };
      };
    }) => createCmsContentTypeField(contentTypeId, payload),
    onSuccess: (detail, variables) => {
      const createdField =
        detail.contentTypes
          .find((contentType) => contentType.id === variables.contentTypeId)
          ?.fields.find((field) => field.fieldKey === variables.payload.fieldKey) ?? null;
      pendingCreatedFieldIdRef.current = createdField ? String(createdField.id) : null;
      handleDetailSuccess(detail, `Added field to ${detail.project.name}.`, () => {
        setFieldDraft((current) => ({
          ...initialFieldDraft,
          contentTypeId: current.contentTypeId,
        }));
      });
    },
    onError: (error: Error) => {
      const isConflict = isCmsConflictError(error);
      handleMutationError(error, 'Failed to add field.', { isConflict });
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
        initialFieldMappings?: GeneratedSourceMappingPayload[];
      };
    }) => createCmsEntry(projectId, payload),
    onSuccess: (detail, variables) => {
      const createdEntry =
        detail.entries.find((entry) => entry.entryKey === variables.payload.entryKey) ?? null;
      pendingCreatedEntryIdRef.current = createdEntry ? String(createdEntry.id) : null;
      rememberSavedEntryFocus(detail, createdEntry);
      if (createdEntry != null) {
        setEntryEditId(String(createdEntry.id));
      }
      handleDetailSuccess(
        detail,
        (variables.payload.initialFieldMappings?.length ?? 0) === 0
          ? `Created content item in ${detail.project.name}.`
          : `Saved content item in ${detail.project.name}.`,
        () => {
          setEntryDraft((current) => ({
            ...initialEntryDraft,
            contentTypeId: current.contentTypeId,
          }));
          setNewEntrySourceDrafts({});
        },
      );
    },
    onError: (error: Error, variables) => {
      const isConflict = isCmsConflictError(error);
      const authoringRecoveryMessage = isConflict
        ? 'This copy collection changed while you were writing this content item. Current copy is refreshing; review and try again.'
        : 'Could not save this content item. Try again. If it keeps failing, ask an admin to check this copy collection.';
      handleMutationError(
        error,
        (variables.payload.initialFieldMappings?.length ?? 0) === 0
          ? 'Failed to create content item.'
          : 'Failed to save content item.',
        {
          authorFacingMessage:
            'Could not save this content item. Try again. If it keeps failing, ask an admin to check this copy collection.',
          authorConflictMessage:
            'This copy collection changed while you were writing this content item. Current copy is refreshing; review and try again.',
          isConflict,
          suppressNotice: !isAdminToolsRoute,
        },
      );
      if (!isAdminToolsRoute) {
        setAuthoringRecovery({
          kind: 'new-block',
          message: authoringRecoveryMessage,
        });
      }
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
      handleDetailSuccess(detail, `Created experiment candidate in ${detail.project.name}.`, () => {
        setVariantDraft((current) => ({
          ...initialVariantDraft,
          entryId: current.entryId,
        }));
      });
    },
    onError: (error: Error) => {
      handleMutationError(error, 'Failed to create experiment candidate.');
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
      authoringTranslationReconnect?: AuthoringTranslationReconnectTarget | null;
      authoringSourceCopySave?: { entryId: string; fieldId: string } | null;
    }) => upsertCmsFieldMapping(variantId, payload),
    onSuccess: (detail, variables) => {
      clearCmsDraftDirty(draftSyncRef.current, 'mapping');
      setAdminTranslationRepairReadyToReturn(adminTranslationRepairContext != null);
      const authoringTranslationReconnect = variables.authoringTranslationReconnect ?? null;
      const authoringSourceCopySave = variables.authoringSourceCopySave ?? null;
      if (authoringTranslationReconnect != null) {
        updateAuthoringReviewTarget((current) => ({
          ...current,
          requestKey: current.requestKey + 1,
          entryId: authoringTranslationReconnect.entryId,
          fieldId: authoringTranslationReconnect.fieldId,
          localeTag: authoringTranslationReconnect.localeTag,
          repairTarget: 'translation',
          refreshTranslation: true,
          translationRepairSaved: true,
          translationReconnectErrorMessage: null,
          requestedLocaleSetupRefreshPending: false,
        }));
      }
      setAuthoringMappingDrafts((current) => {
        const fieldId = String(variables.payload.fieldId);
        const draftState = current[fieldId];
        if (draftState == null || !draftState.dirty) {
          return current;
        }
        return {
          ...current,
          [fieldId]: {
            ...draftState,
            dirty: false,
          },
        };
      });
      rememberSavedFieldFocus(
        detail,
        authoringSourceCopySave == null
          ? null
          : {
              ...authoringSourceCopySave,
              focusTarget: shouldRefocusAuthoringTranslationReconnect(
                authoringSourceCopySave.fieldId,
              )
                ? 'translation-reconnect'
                : 'source-copy',
            },
      );
      handleDetailSuccess(
        detail,
        authoringTranslationReconnect != null
          ? `Reconnected ${resolveLocaleDisplayName(
              authoringTranslationReconnect.localeTag,
            )} translation.`
          : isAdminToolsRoute
            ? `Saved Mojito link in ${detail.project.name}.`
            : `Saved source copy in ${detail.project.name}.`,
        () => {
          setMappingDraft((current) => ({
            ...initialMappingDraft,
            entryId: current.entryId,
            variantId: current.variantId,
            fieldId: current.fieldId,
          }));
          scheduleReleaseReadinessRefreshAfterRepair({
            markRepairSaved:
              authoringTranslationReconnect != null ||
              selectedAuthoringReviewTarget.repairTarget === 'source-copy',
          });
        },
      );
    },
    onError: (error: Error, variables) => {
      setAdminTranslationRepairReadyToReturn(false);
      const isConflict = isCmsConflictError(error);
      const authoringTranslationReconnect = variables.authoringTranslationReconnect ?? null;
      if (authoringTranslationReconnect != null) {
        const localeLabel = resolveLocaleDisplayName(authoringTranslationReconnect.localeTag);
        const translationReconnectErrorMessage = isConflict
          ? `Saved copy changed while reconnecting ${localeLabel}. Review the refreshed source copy, then reconnect ${localeLabel} again.`
          : `Reconnect ${localeLabel} again. If it keeps failing, ask an admin to check this field.`;
        setNotice(null);
        if (isConflict) {
          resetCmsDraftSyncState(draftSyncRef.current, 'mapping');
          clearStaleDerivedState(selectedProjectId);
          invalidateCmsQueries();
        }
        updateAuthoringReviewTarget((current) => ({
          ...current,
          entryId: authoringTranslationReconnect.entryId,
          fieldId: authoringTranslationReconnect.fieldId,
          localeTag: authoringTranslationReconnect.localeTag,
          repairTarget: 'translation',
          refreshTranslation: false,
          translationReconnectErrorMessage,
          requestedLocaleSetupRefreshPending: false,
        }));
        setActiveAuthoringFieldId(authoringTranslationReconnect.fieldId);
        return;
      }
      const authoringRecoveryMessage = isConflict
        ? AUTHORING_SOURCE_COPY_REFRESH_PENDING_MESSAGE
        : 'Could not save source copy. Try again. If it keeps failing, ask an admin to check this field.';
      if (isConflict && !isAdminToolsRoute) {
        pendingAuthoringSourceCopyConflictRefreshRef.current = {
          fieldId: String(variables.payload.fieldId),
          refreshStarted: true,
        };
      }
      handleMutationError(error, 'Failed to save link.', {
        conflictingDraftKey: 'mapping',
        authorFacingMessage:
          'Could not save source copy. Try again. If it keeps failing, ask an admin to check this field.',
        isConflict,
        suppressNotice: !isAdminToolsRoute,
      });
      if (!isAdminToolsRoute) {
        setAuthoringRecovery({
          kind: 'source-copy',
          fieldId: String(variables.payload.fieldId),
          message: authoringRecoveryMessage,
        });
        queueCopyFieldScroll(String(variables.payload.fieldId), 'source-copy');
      }
    },
  });

  const unmapMappingMutation = useMutation({
    mutationFn: ({ mappingId, expectedVersion }: { mappingId: number; expectedVersion: number }) =>
      unmapCmsFieldMapping(mappingId, expectedVersion),
    onSuccess: (detail) => {
      clearCmsDraftDirty(draftSyncRef.current, 'mapping');
      setAdminTranslationRepairReadyToReturn(false);
      handleDetailSuccess(detail, `Removed Mojito link in ${detail.project.name}.`);
    },
    onError: (error: Error) => {
      setAdminTranslationRepairReadyToReturn(false);
      handleMutationError(error, 'Failed to remove link.', {
        conflictingDraftKey: 'mapping',
      });
    },
  });

  const clearAuthoringReleaseReview = () => {
    setAdminTranslationRepairReadyToReturn(false);
    updateAuthoringReviewTarget((current) =>
      current.returnToRelease
        ? {
            ...initialAuthoringReviewTarget,
            requestKey: current.requestKey,
          }
        : current,
    );
  };

  const restoreExactReleasedSnapshot = (
    validatedPackage: ApiCmsProjectCompleteness,
    requestedLocaleTags: string[],
  ) => {
    if (isAdminToolsRoute || projectDetail == null) {
      return false;
    }
    const latestSnapshot = publishSnapshots[0];
    if (
      latestSnapshot == null ||
      latestSnapshot.publishRequestAuthoringSha256 !== validatedPackage.authoringSha256 ||
      latestSnapshot.publishRequestPackageSha256 !== validatedPackage.publishPackageSha256 ||
      !publishLocaleScopesMatch(latestSnapshot.publishRequestLocaleTags, requestedLocaleTags)
    ) {
      return false;
    }
    const releaseMessage = `Released ${formatIncludedReleaseEntryNames(
      getIncludedReleaseEntryNames(projectDetail),
    )} in version ${latestSnapshot.snapshotVersion}.`;
    clearAuthoringReleaseReview();
    setReleaseRecovery(null);
    setReleaseSuccess({
      projectId: validatedPackage.projectId,
      message: releaseMessage,
    });
    setNotice({
      kind: 'success',
      message: releaseMessage,
    });
    return true;
  };

  const completenessMutation = useMutation({
    mutationFn: ({
      projectId,
      locales,
    }: {
      projectId: number;
      locales: string;
      silent?: boolean;
      errorRecoveryKind?: ReleaseRecovery['kind'];
      preserveReleasedHandoff?: boolean;
    }) => fetchCmsProjectCompleteness(projectId, locales),
    onMutate: (variables) => {
      if (!variables.silent) {
        setCompletenessResult(null);
      }
      setReleaseRecovery(null);
      if (!variables.preserveReleasedHandoff) {
        setReleaseSuccess(null);
      }
      setReleaseChangeSummaryError(null);
    },
    onSuccess: (response, variables) => {
      if (variables.errorRecoveryKind === 'repair-refresh') {
        pendingReleaseRepairReadinessRefreshRef.current = false;
      }
      if (!isSelectedProject(variables.projectId)) {
        return;
      }
      if (projectDetail != null && response.authoringSha256 !== projectDetail.authoringSha256) {
        clearStaleDerivedState(response.projectId);
        invalidateCmsQueries();
        setNotice({
          kind: 'error',
          message: isAdminToolsRoute
            ? 'Content changed since it was loaded. Refreshing current CMS data; review and check readiness again.'
            : 'Copy or release details changed since they were loaded. Current copy is refreshing; review and release approved copy again.',
        });
        return;
      }
      settleReleaseSourceCopyRepairFromReadiness(response);
      setCompletenessResult(response);
      setLastCheckedCompletenessResult(response);
      setReleaseRecovery(null);
      if (
        variables.errorRecoveryKind !== 'repair-refresh' &&
        restoreExactReleasedSnapshot(response, parsePublishLocaleTags(variables.locales))
      ) {
        return;
      }
      setReleaseSuccess(null);
      if (!variables.silent && isAdminToolsRoute) {
        setNotice({
          kind: 'success',
          message: `Release readiness checked for ${selectedProject?.name ?? response.projectKey}.`,
        });
      }
    },
    onError: (error: Error, variables) => {
      if (variables.errorRecoveryKind === 'repair-refresh') {
        pendingReleaseRepairReadinessRefreshRef.current = false;
      }
      if (!isSelectedProject(variables.projectId)) {
        return;
      }
      if (variables.silent && variables.errorRecoveryKind == null) {
        return;
      }
      const errorRecoveryKind = variables.errorRecoveryKind ?? 'recheck';
      const repairRefreshFailed = errorRecoveryKind === 'repair-refresh';
      const releasedRecheckFailed = variables.preserveReleasedHandoff === true;
      setReleaseRecovery({
        kind: errorRecoveryKind,
        message: isAdminToolsRoute
          ? error.message || 'Readiness check failed. Check readiness again.'
          : repairRefreshFailed
            ? 'Your repair saved, but this release could not refresh. Try Release approved copy again. If it keeps failing, ask an admin to check this release.'
            : releasedRecheckFailed
              ? 'Could not check current release. Try Check release again. If it keeps failing, ask an admin to check this release.'
              : 'Could not check this release. Try Release approved copy again. If it keeps failing, ask an admin to check this release.',
      });
      setNotice({
        kind: 'error',
        message: isAdminToolsRoute
          ? error.message || 'Readiness check failed.'
          : repairRefreshFailed
            ? 'Your repair saved, but this release could not refresh. Try again. If it keeps failing, ask an admin to check this release.'
            : 'Could not check this release. Try again. If it keeps failing, ask an admin to check this release.',
      });
      if (!isAdminToolsRoute) {
        queueCmsReleasePanelFocus();
      }
    },
  });

  useEffect(() => {
    if (
      !pendingReleaseRepairReadinessRefreshRef.current ||
      isAdminToolsRoute ||
      selectedProjectId == null ||
      projectDetail == null ||
      completenessMutation.isPending
    ) {
      return;
    }
    completenessMutation.mutate({
      projectId: selectedProjectId,
      locales: publishLocales,
      silent: true,
      errorRecoveryKind: 'repair-refresh',
    });
  }, [completenessMutation, isAdminToolsRoute, projectDetail, publishLocales, selectedProjectId]);

  const releaseChangeSummaryMutation = useMutation({
    mutationFn: ({
      projectId,
      locales,
    }: {
      projectId: number;
      locales: string;
      expectedAuthoringSha256: string;
      expectedPackageSha256: string;
    }) => fetchCmsProjectReleaseChanges(projectId, locales),
    onMutate: () => {
      setReleaseChangeSummaryError(null);
    },
    onSuccess: (releaseChangeSummary, variables) => {
      if (!isSelectedProject(variables.projectId)) {
        return;
      }
      setCompletenessResult((current) => {
        if (
          current == null ||
          current.projectId !== variables.projectId ||
          current.authoringSha256 !== variables.expectedAuthoringSha256 ||
          current.publishPackageSha256 !== variables.expectedPackageSha256
        ) {
          return current;
        }
        return {
          ...current,
          releaseChangeSummary,
        };
      });
    },
    onError: () => {
      setReleaseChangeSummaryError(
        'Could not load every release change. Check release again and try once more.',
      );
    },
  });

  const handleLoadAllReleaseChanges = () => {
    if (
      selectedProjectId == null ||
      completenessResult == null ||
      !completenessResult.releaseChangeSummary.hasMore
    ) {
      return;
    }
    releaseChangeSummaryMutation.mutate({
      projectId: selectedProjectId,
      locales: publishLocales,
      expectedAuthoringSha256: completenessResult.authoringSha256,
      expectedPackageSha256: completenessResult.publishPackageSha256,
    });
  };

  useEffect(() => {
    const latestSnapshot = publishSnapshots[0];
    if (pendingReleaseRepairReadinessRefreshRef.current || completenessMutation.isPending) {
      return;
    }
    if (
      isAdminToolsRoute ||
      selectedProjectId == null ||
      projectDetail == null ||
      latestSnapshot == null ||
      completenessResult != null ||
      releaseSuccess != null ||
      releaseRecovery != null
    ) {
      autoReleaseCheckKeyRef.current = '';
      return;
    }
    const autoReleaseCheckKey = [
      projectDetail.project.id,
      projectDetail.authoringSha256,
      buildCmsPublishLocaleTagsKey(parsePublishLocaleTags(publishLocales)),
      latestSnapshot.id,
    ].join(':');
    if (autoReleaseCheckKeyRef.current === autoReleaseCheckKey) {
      return;
    }
    autoReleaseCheckKeyRef.current = autoReleaseCheckKey;
    completenessMutation.mutate({
      projectId: selectedProjectId,
      locales: publishLocales,
      silent: true,
      errorRecoveryKind:
        selectedAuthoringReviewTarget.returnToRelease &&
        selectedAuthoringReviewTarget.reviewReturnMode === 'repair'
          ? 'recheck'
          : undefined,
    });
  }, [
    completenessMutation,
    completenessResult,
    isAdminToolsRoute,
    projectDetail,
    publishLocales,
    publishSnapshots,
    releaseRecovery,
    releaseSuccess,
    selectedAuthoringReviewTarget.returnToRelease,
    selectedAuthoringReviewTarget.reviewReturnMode,
    selectedProjectId,
  ]);

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
      includedEntryNames: string[];
    }) =>
      publishCmsProject(
        projectId,
        localeTags,
        expectedAuthoringSha256,
        expectedPackageSha256,
        publishRequestKey,
      ),
    onMutate: () => {
      setReleaseRecovery(null);
      setReleaseSuccess(null);
    },
    onSuccess: (snapshot, variables) => {
      if (!isSelectedProject(variables.projectId)) {
        clearCmsPublishIntentsForProject(variables.projectId);
        invalidateCmsQueries();
        return;
      }
      clearStaleDerivedState(variables.projectId);
      setPublishSnapshots((current) => upsertPublishSnapshot(current, snapshot));
      const releaseMessage = isAdminToolsRoute
        ? `Released version ${snapshot.snapshotVersion}.`
        : `Released ${formatIncludedReleaseEntryNames(
            variables.includedEntryNames,
          )} in version ${snapshot.snapshotVersion}.`;
      if (!isAdminToolsRoute) {
        clearAuthoringReleaseReview();
        setReleaseSuccess({
          projectId: variables.projectId,
          message: releaseMessage,
        });
      }
      setNotice({
        kind: 'success',
        message: releaseMessage,
      });
      if (!isAdminToolsRoute) {
        queueCmsReleasePanelFocus();
      }
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
          message: isAdminToolsRoute
            ? 'Release details no longer match current content or locale scope. Refreshing current CMS data; review and release again.'
            : 'Copy or release details changed before release. Review the latest copy, then release approved copy again.',
        });
        setReleaseRecovery({
          kind: 'recheck',
          message: isAdminToolsRoute
            ? 'Copy or release details changed after the readiness check. Check readiness again, then release approved copy.'
            : 'Copy or release details changed before release. Review the latest copy, then release approved copy again.',
        });
        return;
      }
      setReleaseRecovery({
        kind: 'retry',
        message:
          'The release request did not finish. Try Release approved copy again. If it keeps failing, ask an admin to check this release.',
      });
      setNotice({
        kind: 'error',
        message: isAdminToolsRoute
          ? error.message || 'Release failed.'
          : 'Release did not finish. Try again from the release card.',
      });
      if (!isAdminToolsRoute) {
        queueCmsReleasePanelFocus();
      }
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
      setNotice({ kind: 'error', message: error.message || 'Failed to load older releases.' });
    },
  });

  const selectedProject = projectDetail?.project ?? null;
  const hasIncludedAuthoringEntry = entries.some((entry) => entry.status === 'READY');
  const shouldShowAuthorRelease =
    entries.length > 0 && !isWritingNewCopyBlock && hasIncludedAuthoringEntry;
  const releaseCheckPending =
    completenessMutation.isPending && completenessMutation.variables?.silent !== true;
  const selectedProjectReleaseSuccess =
    releaseSuccess?.projectId === projectDetail?.project.id ? releaseSuccess : null;
  const releaseHistoryChange =
    selectedProjectReleaseSuccess == null
      ? getReleaseHistoryChange(
          publishSnapshots[0],
          completenessResult,
          parsePublishLocaleTags(publishLocales),
        )
      : null;
  const releaseHistoryChangeWithActions =
    releaseHistoryChange == null
      ? null
      : {
          ...releaseHistoryChange,
          loadAllChanges:
            releaseHistoryChange.changeSummary.hasMore && !isAdminToolsRoute
              ? handleLoadAllReleaseChanges
              : undefined,
          loadAllChangesPending: releaseChangeSummaryMutation.isPending,
          loadAllChangesError: releaseChangeSummaryError,
        };
  const isSaving =
    createFirstWritingSpaceMutation.isPending ||
    createContentTypeMutation.isPending ||
    createFirstCopyBlockMutation.isPending ||
    createFieldMutation.isPending ||
    createEntryMutation.isPending ||
    createVariantMutation.isPending ||
    addTargetLocalesMutation.isPending ||
    recoveringRequestedTargetLocaleConflict ||
    updateProjectMutation.isPending ||
    updateContentTypeMutation.isPending ||
    updateFieldMutation.isPending ||
    updateEntryMutation.isPending ||
    updateVariantMutation.isPending ||
    upsertMappingMutation.isPending ||
    unmapMappingMutation.isPending ||
    releaseCheckPending ||
    publishMutation.isPending ||
    isSavingInlineTranslation;

  const hasProjectScopedNonTranslationDraftChanges =
    hasCmsFormDraftChanges(contentTypeDraft, initialContentTypeDraft) ||
    hasScopedCmsFormDraftChanges(fieldDraft, initialFieldDraft, ['contentTypeId']) ||
    hasScopedCmsFormDraftChanges(entryDraft, initialEntryDraft, ['contentTypeId']) ||
    hasSourceCopyDraftChanges(newEntrySourceDrafts) ||
    hasCmsFormDraftChanges(firstCopyBlockDraft, initialFirstCopyBlockDraft) ||
    hasScopedCmsFormDraftChanges(variantDraft, initialVariantDraft, ['entryId']) ||
    publishLocales.trim().length > 0 ||
    targetLocaleTagsDraft.length > 0 ||
    hasDirtyCmsDrafts(draftSyncRef.current) ||
    hasDirtyAuthoringFields ||
    hasDirtyAuthoringMappings;
  const hasProjectScopedDraftChanges =
    hasProjectScopedNonTranslationDraftChanges || hasDirtyInlineTranslation;
  const inlineTranslationReleaseBlockerCount = inlineTranslationReleaseBlockerTargets.length;
  const authorReleaseBlockedStatusLabel = hasInlineTranslationReleaseBlocker
    ? inlineTranslationReleaseBlockerCount === 1
      ? 'Refresh translation'
      : 'Refresh translations'
    : null;
  const authorReleaseBlockedHint = hasInlineTranslationReleaseBlocker
    ? inlineTranslationReleaseBlockerCount === 1
      ? 'Refresh unavailable approved translation before releasing approved copy.'
      : `Refresh ${inlineTranslationReleaseBlockerCount} unavailable approved translations before releasing approved copy.`
    : hasDirtyInlineTranslation
      ? 'Save translation edits before releasing approved copy.'
      : hasProjectScopedNonTranslationDraftChanges
        ? 'Save visible changes before releasing approved copy.'
        : null;
  const hasNonTranslationCmsDraftChanges =
    hasCmsFormDraftChanges(projectDraft, initialProjectDraft) ||
    hasProjectScopedNonTranslationDraftChanges;
  const hasUnsavedCmsDraftChanges =
    hasCmsFormDraftChanges(projectDraft, initialProjectDraft) || hasProjectScopedDraftChanges;
  const hasNewWritingSpaceDraftChanges =
    hasCmsFormDraftChanges(projectDraft, initialProjectDraft) ||
    hasCmsFormDraftChanges(firstCopyBlockDraft, initialFirstCopyBlockDraft);
  const hasNewCopyBlockDraftChanges =
    hasScopedCmsFormDraftChanges(entryDraft, initialEntryDraft, ['contentTypeId']) ||
    hasSourceCopyDraftChanges(newEntrySourceDrafts);
  const hasCmsExitRisk = isSaving || hasUnsavedCmsDraftChanges;

  const closeCmsConfirmation = useCallback(() => {
    setPendingCmsConfirmation(null);
    pendingCmsConfirmationActionRef.current = null;
    pendingCmsConfirmationCancelRef.current = null;
  }, []);
  const markCmsExitConfirmationResolving = useCallback(() => {
    if (pendingCmsConfirmation?.title === 'Leave content editor?') {
      resolvingCmsExitConfirmationRef.current = true;
    }
  }, [pendingCmsConfirmation?.title]);

  const requestCmsConfirmation = useCallback(
    (
      confirmation: CmsConfirmation,
      onConfirm: () => void,
      onCancel: (() => void) | null = null,
    ) => {
      pendingCmsConfirmationActionRef.current = onConfirm;
      pendingCmsConfirmationCancelRef.current = onCancel;
      setPendingCmsConfirmation(confirmation);
    },
    [],
  );

  const handleConfirmCmsConfirmation = useCallback(() => {
    const onConfirm = pendingCmsConfirmationActionRef.current;
    markCmsExitConfirmationResolving();
    onConfirm?.();
    closeCmsConfirmation();
  }, [closeCmsConfirmation, markCmsExitConfirmationResolving]);

  const handleCancelCmsConfirmation = useCallback(() => {
    const onCancel = pendingCmsConfirmationCancelRef.current;
    markCmsExitConfirmationResolving();
    onCancel?.();
    closeCmsConfirmation();
  }, [closeCmsConfirmation, markCmsExitConfirmationResolving]);

  const buildUnsavedDraftDiscardMessage = useCallback(
    ({
      includeWritingSpace,
      hasOtherUnsavedContentEdits,
    }: {
      includeWritingSpace: boolean;
      hasOtherUnsavedContentEdits: boolean;
    }) => {
      const writingSpaceScope = includeWritingSpace
        ? ` for ${selectedProject?.name ?? 'the current copy collection'}`
        : '';
      if (!hasDirtyInlineTranslation) {
        return `Unsaved content edits${writingSpaceScope} will be discarded.`;
      }
      const inlineTranslationDraft = `Unsaved translation edits for ${
        selectedAuthoringField?.name ?? 'this field'
      }`;
      if (!hasOtherUnsavedContentEdits) {
        return `${inlineTranslationDraft} will be discarded.`;
      }
      return `${inlineTranslationDraft} and other unsaved content edits${writingSpaceScope} will be discarded.`;
    },
    [hasDirtyInlineTranslation, selectedAuthoringField?.name, selectedProject?.name],
  );
  const projectScopedDiscardBody = (action: string) =>
    `${action} ${buildUnsavedDraftDiscardMessage({
      includeWritingSpace: true,
      hasOtherUnsavedContentEdits: hasProjectScopedNonTranslationDraftChanges,
    })}`;

  const requestDiscardProjectScopedDrafts = (
    action: string,
    confirmLabel: string,
    onConfirm: () => void,
  ) => {
    if (!hasProjectScopedDraftChanges) {
      onConfirm();
      return;
    }
    requestCmsConfirmation(
      {
        title: 'Discard unsaved changes?',
        body: projectScopedDiscardBody(action),
        confirmLabel,
        cancelLabel: 'Keep editing',
      },
      onConfirm,
    );
  };

  const resetNewWritingSpaceDraft = () => {
    setProjectDraft(initialProjectDraft);
    setFirstCopyBlockDraft(initialFirstCopyBlockDraft);
  };

  const discardSelectedProjectDrafts = () => {
    resetCmsDraftSyncState(draftSyncRef.current);
    setContentTypeDraft(initialContentTypeDraft);
    setFieldDraft(initialFieldDraft);
    setEntryDraft(initialEntryDraft);
    setNewEntrySourceDrafts({});
    setFirstCopyBlockDraft(initialFirstCopyBlockDraft);
    setVariantDraft(initialVariantDraft);
    setMappingDraft(
      selectedEditEntry == null ||
        selectedAuthoringVariant == null ||
        selectedAuthoringField == null
        ? initialMappingDraft
        : buildMappingDraft({
            projectKey: projectDetail?.project.projectKey,
            entry: selectedEditEntry,
            variant: selectedAuthoringVariant,
            field: selectedAuthoringField,
            mapping: selectedAuthoringMapping,
          }),
    );
    setAuthoringFieldDrafts(
      Object.fromEntries(
        selectedAuthoringFields.map((field) => [
          String(field.id),
          {
            draft: buildFieldDraft(field),
            dirty: false,
            expectedVersion: field.entityVersion,
            savedDraft: buildFieldDraft(field),
            staleSavedDraft: null,
          },
        ]),
      ),
    );
    setAuthoringMappingDrafts(
      selectedEditEntry == null || selectedAuthoringVariant == null
        ? {}
        : Object.fromEntries(
            selectedAuthoringFields.map((field) => {
              const mapping =
                selectedAuthoringVariant.fieldMappings.find((item) => item.fieldId === field.id) ??
                null;
              return [
                String(field.id),
                {
                  draft: buildMappingDraft({
                    projectKey: projectDetail?.project.projectKey,
                    entry: selectedEditEntry,
                    variant: selectedAuthoringVariant,
                    field,
                    mapping,
                  }),
                  dirty: false,
                  expectedVersion: mapping?.entityVersion ?? null,
                  savedDraft: buildMappingDraft({
                    projectKey: projectDetail?.project.projectKey,
                    entry: selectedEditEntry,
                    variant: selectedAuthoringVariant,
                    field,
                    mapping,
                  }),
                  staleSavedDraft: null,
                },
              ];
            }),
          ),
    );
    setActiveAuthoringFieldId((current) =>
      selectedAuthoringFields.some((field) => String(field.id) === current)
        ? current
        : (selectedAuthoringFields[0]?.id.toString() ?? ''),
    );
    setProjectEditDraft(
      projectDetail == null
        ? initialProjectEditDraft
        : buildProjectEditDraft(projectDetail.project),
    );
    setContentTypeEditDraft(
      selectedEditContentType == null
        ? initialContentTypeDraft
        : buildContentTypeDraft(selectedEditContentType),
    );
    setFieldEditDraft(
      selectedEditField == null ? initialFieldDraft : buildFieldDraft(selectedEditField),
    );
    setEntryEditDraft(
      selectedEditEntry == null ? initialEntryDraft : buildEntryDraft(selectedEditEntry),
    );
    setAuthoringEntrySavedDraft(
      selectedEditEntry == null ? initialEntryDraft : buildEntryDraft(selectedEditEntry),
    );
    setStaleAuthoringEntrySavedDraft(null);
    setVariantEditDraft(
      selectedEditVariant == null ? initialVariantDraft : buildVariantDraft(selectedEditVariant),
    );
    setCompletenessResult(null);
    setLastCheckedCompletenessResult(null);
    setAuthoringRecovery(null);
    setReleaseRecovery(null);
    setPublishLocales('');
    setTargetLocaleTagsDraft([]);
    setHasDirtyInlineTranslation(false);
    setIsSavingInlineTranslation(false);
    pendingInlineTranslationFieldSwitchRef.current = null;
    setPendingInlineTranslationFieldSwitch(null);
    setAuthoringToolsOpen(false);
    setAdvancedMaintenanceOpen(false);
    pendingCreatedEntryIdRef.current = null;
    pendingCreatedFieldIdRef.current = null;
    publishIntentRef.current = null;
    if (projectDetail != null) {
      hydrateCmsDraftSyncState(
        draftSyncRef.current,
        'projectEdit',
        projectDetail.project.entityVersion,
      );
    }
    if (selectedEditContentType != null) {
      hydrateCmsDraftSyncState(
        draftSyncRef.current,
        'contentTypeEdit',
        selectedEditContentType.entityVersion,
      );
    }
    if (selectedEditField != null) {
      hydrateCmsDraftSyncState(draftSyncRef.current, 'fieldEdit', selectedEditField.entityVersion);
    }
    if (selectedEditEntry != null) {
      hydrateCmsDraftSyncState(draftSyncRef.current, 'entryEdit', selectedEditEntry.entityVersion);
    }
    if (selectedEditVariant != null) {
      hydrateCmsDraftSyncState(
        draftSyncRef.current,
        'variantEdit',
        selectedEditVariant.entityVersion,
      );
    }
    hydrateCmsDraftSyncState(
      draftSyncRef.current,
      'mapping',
      selectedAuthoringMapping?.entityVersion ?? null,
    );
  };

  const requestDiscardNewWritingSpaceDrafts = (
    action: string,
    confirmLabel: string,
    onConfirm: () => void,
  ) => {
    if (!isStartingWritingSpace || !hasNewWritingSpaceDraftChanges) {
      onConfirm();
      return;
    }
    requestCmsConfirmation(
      {
        title: 'Discard unsaved changes?',
        body: `${action} Unsaved first content item copy for the new copy collection will be discarded.`,
        confirmLabel,
        cancelLabel: 'Keep writing',
      },
      onConfirm,
    );
  };

  const resetNewCopyBlockDraft = () => {
    setEntryDraft(initialEntryDraft);
    setNewEntrySourceDrafts({});
  };

  const requestDiscardNewCopyBlockDrafts = (
    action: string,
    confirmLabel: string,
    onConfirm: () => void,
  ) => {
    if (!hasNewCopyBlockDraftChanges) {
      onConfirm();
      return;
    }
    requestCmsConfirmation(
      {
        title: 'Discard unsaved changes?',
        body: `${action} Unsaved copy for the new content item will be discarded.`,
        confirmLabel,
        cancelLabel: 'Keep writing',
      },
      onConfirm,
    );
  };

  const buildCmsExitConfirmation = useCallback((): CmsConfirmation => {
    const unsavedDraftDiscardMessage = buildUnsavedDraftDiscardMessage({
      includeWritingSpace: false,
      hasOtherUnsavedContentEdits: hasNonTranslationCmsDraftChanges,
    });
    const exitRiskMessage = isSavingInlineTranslation
      ? hasUnsavedCmsDraftChanges
        ? `Translation changes are still saving. Stay here until the save finishes. ${unsavedDraftDiscardMessage}`
        : 'Translation changes are still saving. Stay here until the save finishes.'
      : isSaving
        ? hasUnsavedCmsDraftChanges
          ? `Content changes are still saving. ${unsavedDraftDiscardMessage}`
          : 'Content changes are still saving.'
        : unsavedDraftDiscardMessage;
    return {
      title: 'Leave content editor?',
      body: exitRiskMessage,
      confirmLabel: 'Leave editor',
      cancelLabel: 'Stay here',
      confirmDisabled: isSavingInlineTranslation,
    };
  }, [
    buildUnsavedDraftDiscardMessage,
    hasNonTranslationCmsDraftChanges,
    hasUnsavedCmsDraftChanges,
    isSaving,
    isSavingInlineTranslation,
  ]);
  const shouldBlockCmsExit = useCallback<BlockerFunction>(
    ({ currentLocation, nextLocation }) =>
      hasCmsExitRisk &&
      (currentLocation.pathname !== nextLocation.pathname ||
        currentLocation.search !== nextLocation.search ||
        currentLocation.hash !== nextLocation.hash),
    [hasCmsExitRisk],
  );
  const cmsExitBlocker = useBlocker(shouldBlockCmsExit);

  const requestDiscardDirtyCmsDrafts = (
    action: string,
    draftKeys: readonly CmsDraftKey[],
    onConfirm: () => void,
    {
      authoringFields = false,
      authoringMappings = false,
      confirmLabel = 'Discard changes',
    }: {
      authoringFields?: boolean;
      authoringMappings?: boolean;
      confirmLabel?: string;
    } = {},
  ) => {
    if (
      !hasDirtyCmsDrafts(draftSyncRef.current, draftKeys) &&
      (!authoringFields || !hasDirtyAuthoringFields) &&
      (!authoringMappings || !hasDirtyAuthoringMappings) &&
      !hasDirtyInlineTranslation
    ) {
      onConfirm();
      return;
    }
    requestCmsConfirmation(
      {
        title: 'Discard unsaved changes?',
        body: projectScopedDiscardBody(action),
        confirmLabel,
        cancelLabel: 'Keep editing',
      },
      onConfirm,
    );
  };

  const requestOperatorConfirmation = (
    confirmation: Omit<CmsConfirmation, 'cancelLabel'> & { cancelLabel?: string },
    onConfirm: () => void,
    onCancel: (() => void) | null = null,
  ) => {
    requestCmsConfirmation(
      {
        cancelLabel: 'Cancel',
        ...confirmation,
      },
      onConfirm,
      onCancel,
    );
  };

  const handleStartWritingSpace = () => {
    requestDiscardProjectScopedDrafts('New copy collection?', 'Discard and start', () => {
      discardSelectedProjectDrafts();
      resetNewWritingSpaceDraft();
      pendingStartingWritingSpaceCancelFieldFocusRef.current = null;
      setAuthoringRecovery((current) => (current?.kind === 'first-writing-space' ? null : current));
      reopenMobileCopyCollectionsAfterWritingSpaceCancelRef.current = mobileCopyCollectionsOpen;
      setMobileCopyCollectionsOpen(false);
      setIsStartingWritingSpace(true);
      setNotice(null);
    });
  };

  const handleCancelStartingWritingSpace = () => {
    requestDiscardNewWritingSpaceDrafts(
      'Return to the current copy collection?',
      'Discard and return',
      () => {
        const translationReconnectErrorMessage =
          selectedAuthoringReviewTarget.translationReconnectErrorMessage;
        const pendingStartingWritingSpaceCancelFieldFocus: Pick<
          PendingSavedFieldFocus,
          'fieldId' | 'focusTarget'
        > | null =
          !isAdminToolsRoute &&
          selectedAuthoringReviewTarget.fieldId != null &&
          selectedAuthoringReviewTarget.repairTarget === 'translation' &&
          translationReconnectErrorMessage != null &&
          !(
            translationReconnectErrorMessage.startsWith('Saved copy changed while reconnecting ') &&
            projectQuery.isFetching
          )
            ? {
                fieldId: selectedAuthoringReviewTarget.fieldId,
                focusTarget:
                  translationReconnectErrorMessage.startsWith(
                    'Saved copy changed while reconnecting ',
                  ) &&
                  projectQuery.isError &&
                  projectDetail != null
                    ? 'translation-reconnect-refresh'
                    : 'translation-reconnect',
              }
            : null;
        resetNewWritingSpaceDraft();
        setAuthoringRecovery((current) =>
          current?.kind === 'first-writing-space' ? null : current,
        );
        setMobileCopyCollectionsOpen(reopenMobileCopyCollectionsAfterWritingSpaceCancelRef.current);
        reopenMobileCopyCollectionsAfterWritingSpaceCancelRef.current = false;
        pendingStartingWritingSpaceCancelFieldFocusRef.current =
          pendingStartingWritingSpaceCancelFieldFocus;
        returnFocusToStartWritingSpaceButtonRef.current =
          pendingStartingWritingSpaceCancelFieldFocus == null;
        setIsStartingWritingSpace(false);
        setNotice(null);
      },
    );
  };

  const handleStartAuthoringEntry = (onReady: () => void) => {
    requestDiscardProjectScopedDrafts('Write a new content item?', 'Discard and write', () => {
      discardSelectedProjectDrafts();
      resetNewCopyBlockDraft();
      setAuthoringRecovery((current) => (current?.kind === 'new-block' ? null : current));
      setNotice(null);
      onReady();
    });
  };

  const handleCancelAuthoringEntry = (onCancel: () => void) => {
    requestDiscardNewCopyBlockDrafts(
      'Return to this copy collection?',
      'Discard and return',
      () => {
        resetNewCopyBlockDraft();
        setAuthoringRecovery((current) => (current?.kind === 'new-block' ? null : current));
        setNotice(null);
        onCancel();
      },
    );
  };

  const handleCreateFirstWritingSpace = (event: FormEvent) => {
    event.preventDefault();
    try {
      setNotice(null);
      createFirstWritingSpaceMutation.mutate({
        projectDraft,
        firstCopyBlockDraft,
      });
    } catch (error) {
      setNotice({ kind: 'error', message: (error as Error).message });
    }
  };

  const handleRetryProjects = () => {
    pendingProjectsRetryFocusRef.current = true;
    setNotice(null);
    void projectsQuery.refetch();
  };

  const handleRetrySelectedProject = () => {
    pendingSelectedProjectRetryFocusProjectIdRef.current = isAdminToolsRoute
      ? null
      : selectedProjectId;
    setNotice(null);
    void projectQuery.refetch();
  };

  const handleRefreshSelectedTranslation = () => {
    setNotice(null);
    clearStaleDerivedState(selectedProjectId);
    void Promise.all([projectQuery.refetch(), selectedEntryCompletenessQuery.refetch()]);
  };
  const handleRetryAuthoringSourceCopyConflictRefresh = () => {
    const pendingSourceCopyConflictRefresh = pendingAuthoringSourceCopyConflictRefreshRef.current;
    if (pendingSourceCopyConflictRefresh == null) {
      return;
    }
    pendingSourceCopyConflictRefresh.refreshStarted = true;
    setNotice(null);
    setAuthoringRecovery((current) =>
      current?.kind === 'source-copy' &&
      current.fieldId === pendingSourceCopyConflictRefresh.fieldId
        ? {
            ...current,
            message: AUTHORING_SOURCE_COPY_REFRESH_PENDING_MESSAGE,
          }
        : current,
    );
    void projectQuery.refetch();
  };
  const handleRetryEntryCompleteness = () => {
    const pendingRequestedTargetLocaleConflict = pendingRequestedTargetLocaleConflictRef.current;
    if (pendingRequestedTargetLocaleConflict != null) {
      pendingRequestedTargetLocaleConflict.refreshStarted = true;
      setRecoveringRequestedTargetLocaleConflict(true);
    }
    void selectedEntryCompletenessQuery.refetch();
  };

  const handleSelectProject = (projectId: number) => {
    if (projectId === selectedProjectId) {
      if (isStartingWritingSpace) {
        handleCancelStartingWritingSpace();
      }
      return;
    }
    const nextProject = projects.find((project) => project.id === projectId);
    const selectProject = () => {
      pendingProjectSwitchFocusProjectIdRef.current = isAdminToolsRoute ? null : projectId;
      pendingSelectedProjectRetryFocusProjectIdRef.current = null;
      setMobileCopyCollectionsOpen(false);
      setIsStartingWritingSpace(false);
      resetNewWritingSpaceDraft();
      setSelectedProjectId(projectId);
      setCompletenessResult(null);
      setLastCheckedCompletenessResult(null);
      setAuthoringRecovery(null);
      setReleaseRecovery(null);
      setReleaseSuccess(null);
      setNotice(null);
    };
    if (isStartingWritingSpace) {
      requestDiscardNewWritingSpaceDrafts(
        nextProject == null ? 'Switch copy collections?' : `Switch to ${nextProject.name}?`,
        'Discard and switch',
        selectProject,
      );
      return;
    }
    requestDiscardProjectScopedDrafts(
      nextProject == null ? 'Switch copy collections?' : `Switch to ${nextProject.name}?`,
      'Discard and switch',
      selectProject,
    );
  };

  const handleBack = () => {
    void navigate(isAdminToolsRoute ? authoringPath : '/settings/system');
  };
  const handleReturnToProductCopyAfterRepair = () => {
    void navigate(authoringPath, { state: { cmsTranslationRepairSaved: true } });
  };
  const clearPendingInlineTranslationFieldSwitch = useCallback(() => {
    pendingInlineTranslationFieldSwitchRef.current = null;
    setPendingInlineTranslationFieldSwitch(null);
  }, []);
  const completePendingInlineTranslationFieldSwitch = useCallback(() => {
    const pendingFieldSwitch = pendingInlineTranslationFieldSwitchRef.current;
    clearPendingInlineTranslationFieldSwitch();
    if (pendingFieldSwitch == null) {
      return false;
    }
    setActiveAuthoringFieldId(pendingFieldSwitch.targetFieldId);
    queueCopyFieldScroll(pendingFieldSwitch.targetFieldId, pendingFieldSwitch.focusTarget);
    setNotice(null);
    return true;
  }, [clearPendingInlineTranslationFieldSwitch]);

  useBeforeUnload((event) => {
    if (!hasCmsExitRisk) {
      return;
    }
    event.preventDefault();
    event.returnValue = '';
  });

  useEffect(() => {
    if (cmsExitBlocker.state !== 'blocked') {
      resolvingCmsExitConfirmationRef.current = false;
      return;
    }
    if (resolvingCmsExitConfirmationRef.current || pendingCmsConfirmation != null) {
      return;
    }
    requestCmsConfirmation(
      buildCmsExitConfirmation(),
      () => cmsExitBlocker.proceed(),
      () => cmsExitBlocker.reset(),
    );
  }, [buildCmsExitConfirmation, cmsExitBlocker, pendingCmsConfirmation, requestCmsConfirmation]);
  useEffect(() => {
    if (
      cmsExitBlocker.state !== 'blocked' ||
      pendingCmsConfirmation?.title !== 'Leave content editor?'
    ) {
      return;
    }
    if (!hasCmsExitRisk) {
      cmsExitBlocker.reset();
      closeCmsConfirmation();
      return;
    }
    const nextCmsExitConfirmation = buildCmsExitConfirmation();
    setPendingCmsConfirmation((current) =>
      current != null &&
      current.title === nextCmsExitConfirmation.title &&
      current.body === nextCmsExitConfirmation.body &&
      current.confirmLabel === nextCmsExitConfirmation.confirmLabel &&
      current.cancelLabel === nextCmsExitConfirmation.cancelLabel &&
      current.confirmVariant === nextCmsExitConfirmation.confirmVariant &&
      current.confirmDisabled === nextCmsExitConfirmation.confirmDisabled
        ? current
        : nextCmsExitConfirmation,
    );
  }, [
    buildCmsExitConfirmation,
    closeCmsConfirmation,
    cmsExitBlocker,
    hasCmsExitRisk,
    pendingCmsConfirmation?.title,
  ]);
  useEffect(() => {
    if (
      selectedEntryCompletenessQuery.isError ||
      pendingInlineTranslationFieldSwitchRef.current?.reason !== 'refresh-error'
    ) {
      return;
    }
    completePendingInlineTranslationFieldSwitch();
  }, [completePendingInlineTranslationFieldSwitch, selectedEntryCompletenessQuery.isError]);
  const appliedAdminTranslationRepairKeyRef = useRef<string | null>(null);
  useEffect(() => {
    const adminTranslationRepairKey =
      adminTranslationRepairContext == null ||
      selectedEditEntry == null ||
      selectedAuthoringVariant == null ||
      selectedAuthoringField == null
        ? null
        : [
            selectedEditEntry.id,
            selectedAuthoringVariant.id,
            selectedAuthoringField.id,
            cmsAuthoringSelection.localeTag,
          ].join(':');
    if (adminTranslationRepairKey == null) {
      appliedAdminTranslationRepairKeyRef.current = null;
      setAdminTranslationRepairReadyToReturn(false);
      return;
    }
    if (appliedAdminTranslationRepairKeyRef.current === adminTranslationRepairKey) {
      return;
    }
    if (
      selectedEditEntry == null ||
      selectedAuthoringVariant == null ||
      selectedAuthoringField == null
    ) {
      return;
    }
    appliedAdminTranslationRepairKeyRef.current = adminTranslationRepairKey;
    setAdminTranslationRepairReadyToReturn(false);
    setAuthoringToolsOpen(true);
    setAdvancedMaintenanceOpen(false);
    setWritingSpaceMaintenanceOpen(false);
    setCopyStructureMaintenanceOpen(false);
    setCopyStructureMaintenanceAction(null);
    setRawRecordsOpen(false);
    setStructureRepairOpen(false);
    setStructureRepairAction(null);
    setVariantMaintenanceOpen(false);
    setMappingRepairOpen(true);
    if (draftSyncRef.current.mapping.dirty) {
      return;
    }
    resetCmsDraftSyncState(draftSyncRef.current, 'mapping');
    setMappingDraft((current) => ({
      ...current,
      entryId: String(selectedEditEntry.id),
      variantId: String(selectedAuthoringVariant.id),
      fieldId: String(selectedAuthoringField.id),
    }));
  }, [
    adminTranslationRepairContext,
    cmsAuthoringSelection.localeTag,
    selectedAuthoringField,
    selectedAuthoringVariant,
    selectedEditEntry,
  ]);

  if (!isAdmin) {
    return <Navigate to="/repositories" replace />;
  }

  const handleContentTypeEditSelectionChange = (contentTypeId: string) => {
    if (contentTypeId === contentTypeEditId) {
      return;
    }
    requestDiscardDirtyCmsDrafts(
      'Switch edit content types?',
      ['contentTypeEdit', 'fieldEdit'],
      () => {
        resetCmsDraftSyncState(draftSyncRef.current, 'contentTypeEdit', 'fieldEdit');
        setContentTypeEditId(contentTypeId);
      },
    );
  };

  const handleFieldEditSelectionChange = (fieldId: string) => {
    if (fieldId === fieldEditId) {
      return;
    }
    requestDiscardDirtyCmsDrafts('Switch fields?', ['fieldEdit'], () => {
      resetCmsDraftSyncState(draftSyncRef.current, 'fieldEdit');
      setFieldEditId(fieldId);
    });
  };

  const handleEntryEditSelectionChange = (entryId: string) => {
    if (entryId === entryEditId) {
      return;
    }
    requestDiscardDirtyCmsDrafts('Switch content items?', ['entryEdit', 'variantEdit'], () => {
      resetCmsDraftSyncState(draftSyncRef.current, 'entryEdit', 'variantEdit');
      setEntryEditId(entryId);
    });
  };

  const handleAuthoringEntryChange = (entryId: string, afterChange?: () => void) => {
    if (entryId === entryEditId) {
      afterChange?.();
      return;
    }
    requestDiscardProjectScopedDrafts('Switch content items?', 'Discard and switch', () => {
      discardSelectedProjectDrafts();
      setEntryEditId(entryId);
      afterChange?.();
    });
  };
  const handleReviewBlockedAuthoringEntry = (
    entryId: string,
    fieldId: string | null = null,
    localeTag: string | null = null,
    repairTarget: ReleaseRepairTarget | null = null,
    reviewReason: string | null = null,
    reviewReturnMode: ReleaseReviewReturnMode | null = null,
    lastReleasedSourceContent: string | null = null,
    lastReleasedTranslationContent: string | null = null,
  ) => {
    handleAuthoringEntryChange(entryId, () => {
      const currentReviewEntryCompleteness =
        lastCheckedCompletenessResult?.entries.find((entry) => String(entry.entryId) === entryId) ??
        completenessResult?.entries.find((entry) => String(entry.entryId) === entryId) ??
        null;
      const currentReviewLocaleCompleteness = currentReviewEntryCompleteness?.fields
        .find((field) => String(field.fieldId) === fieldId)
        ?.locales.find((locale) => locale.localeTag === localeTag);
      const currentReviewMapping =
        projectDetail?.entries
          .find((entry) => String(entry.id) === entryId)
          ?.variants.flatMap((variant) => variant.fieldMappings)
          .find((mapping) => String(mapping.fieldId) === fieldId) ?? null;
      const cachedReviewTranslation =
        currentReviewMapping == null || localeTag == null
          ? null
          : queryClient.getQueryData<{ status?: string | null }>([
              'content-cms-inline-translation',
              currentReviewMapping.id,
              currentReviewMapping.tmTextUnitId,
              localeTag,
            ]);
      const refreshStaleApprovedReviewTranslation =
        cachedReviewTranslation?.status === 'APPROVED' &&
        (currentReviewLocaleCompleteness?.reviewNeededFields ?? 0) > 0;
      if (
        projectDetail != null &&
        currentReviewEntryCompleteness != null &&
        (currentReviewLocaleCompleteness?.reviewNeededFields ?? 0) > 0
      ) {
        queryClient.setQueryData(
          [
            ...CMS_QUERY_KEY,
            'entry-completeness',
            currentReviewEntryCompleteness.entryId,
            projectDetail.authoringSha256,
          ],
          currentReviewEntryCompleteness,
        );
      }
      updateAuthoringReviewTarget((current) => ({
        requestKey: current.requestKey + 1,
        projectId: selectedProjectId,
        entryId,
        fieldId,
        localeTag,
        repairTarget,
        reviewReason,
        reviewReturnMode,
        lastReleasedSourceContent,
        lastReleasedTranslationContent,
        returnToRelease: true,
        refreshTranslation: refreshStaleApprovedReviewTranslation,
        translationRepairSaved: false,
        translationReconnectErrorMessage: null,
        requestedLocaleSetupRefreshPending: false,
      }));
      if (fieldId != null) {
        setActiveAuthoringFieldId(fieldId);
      }
    });
  };
  const handleReturnFromReleaseReview = () => {
    pendingRequestedTargetLocaleConflictRef.current = null;
    setRecoveringRequestedTargetLocaleConflict(false);
    setAuthoringRecovery((current) => (current?.kind === 'target-locales' ? null : current));
    setNotice(null);
    updateAuthoringReviewTarget((current) => ({
      ...initialAuthoringReviewTarget,
      requestKey: current.requestKey,
    }));
    if (
      isAdminToolsRoute ||
      selectedProjectId == null ||
      projectDetail == null ||
      completenessMutation.isPending
    ) {
      return;
    }
    completenessMutation.mutate({
      projectId: selectedProjectId,
      locales: publishLocales,
      silent: true,
      errorRecoveryKind: 'recheck',
    });
  };
  const handleInlineTranslationDirtyChange = (dirty: boolean) => {
    setHasDirtyInlineTranslation(dirty);
    if (dirty) {
      clearStaleDerivedState(selectedProjectId);
      return;
    }
    completePendingInlineTranslationFieldSwitch();
  };
  const handleInlineTranslationSaved = () => {
    completePendingInlineTranslationFieldSwitch();
    clearStaleDerivedState(selectedProjectId);
    scheduleReleaseReadinessRefreshAfterRepair();
  };
  const handleInlineTranslationSavingChange = (saving: boolean) => {
    setIsSavingInlineTranslation(saving);
  };

  const handleVariantEditSelectionChange = (variantId: string) => {
    if (variantId === variantEditId) {
      return;
    }
    requestDiscardDirtyCmsDrafts('Switch experiment paths?', ['variantEdit'], () => {
      resetCmsDraftSyncState(draftSyncRef.current, 'variantEdit');
      setVariantEditId(variantId);
    });
  };

  const handleCreateContentType = (event: FormEvent) => {
    event.preventDefault();
    if (selectedProjectId == null) {
      setNotice({ kind: 'error', message: 'Select a copy collection first.' });
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

  const handleCreateFirstCopyBlock = (event: FormEvent) => {
    event.preventDefault();
    if (selectedProjectId == null) {
      setNotice({ kind: 'error', message: 'Select a copy collection first.' });
      return;
    }
    setNotice(null);
    createFirstCopyBlockMutation.mutate({
      projectId: selectedProjectId,
      draft: firstCopyBlockDraft,
    });
  };

  const handleUpdateProject = (event: FormEvent) => {
    event.preventDefault();
    if (selectedProjectId == null || selectedProject == null) {
      setNotice({ kind: 'error', message: 'Select a copy collection first.' });
      return;
    }
    const updateProject = () => {
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
    if (selectedProject.enabled && !projectEditDraft.enabled) {
      requestOperatorConfirmation(
        {
          title: 'Disable copy collection?',
          body: `Disable ${selectedProject.name}? New releases stop, and apps following the latest release stop discovering this copy collection. Exact release exports remain available for rollback.`,
          confirmLabel: 'Disable copy collection',
          confirmVariant: 'danger',
        },
        updateProject,
      );
      return;
    }
    updateProject();
  };

  const addTargetLocales = (
    localeTags: string[],
    requestedLocaleTag: string | null = null,
    returnToRelease = false,
  ) => {
    if (selectedProjectId == null) {
      setNotice({
        kind: 'error',
        message: 'Select a copy collection before adding translation languages.',
      });
      return;
    }
    if (localeTags.length === 0) {
      setNotice({ kind: 'error', message: 'Select at least one translation language.' });
      return;
    }
    pendingRequestedTargetLocaleConflictRef.current = null;
    setRecoveringRequestedTargetLocaleConflict(false);
    setNotice(null);
    addTargetLocalesMutation.mutate({
      projectId: selectedProjectId,
      localeTags,
      requestedLocaleTag,
      returnToRelease,
    });
  };

  const handleAddTargetLocales = (event: FormEvent) => {
    event.preventDefault();
    addTargetLocales(targetLocaleTagsDraft);
  };

  const handleTargetLocaleTagsChange = (localeTags: string[]) => {
    pendingRequestedTargetLocaleConflictRef.current = null;
    setRecoveringRequestedTargetLocaleConflict(false);
    setAuthoringRecovery((current) => (current?.kind === 'target-locales' ? null : current));
    setTargetLocaleTagsDraft(localeTags);
  };

  const handleAddRequestedTargetLocale = (localeTag: string) => {
    setAuthoringRecovery((current) => (current?.kind === 'target-locales' ? null : current));
    setNotice(null);
    updateAuthoringReviewTarget((current) =>
      current.localeTag === localeTag
        ? {
            ...current,
            requestedLocaleSetupRefreshPending: false,
          }
        : current,
    );
    addTargetLocales([localeTag], localeTag, selectedAuthoringReviewTarget.returnToRelease);
  };

  const renderCopyCollectionRail = () => (
    <>
      <SearchControl
        value={projectSearch}
        onChange={setProjectSearch}
        placeholder="Search copy collections"
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

      {!isStartingWritingSpace ? (
        <StartWritingSpaceButton
          buttonRef={startWritingSpaceButtonRef}
          disabled={isSaving}
          onClick={handleStartWritingSpace}
        />
      ) : null}
    </>
  );

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
    const request = getUpdateFieldRequest();
    if (request != null) {
      updateFieldMutation.mutate(request);
    }
  };

  const getUpdateFieldRequest = (
    field: ApiCmsContentType['fields'][number] | null = selectedEditField,
    draft: FieldDraft = fieldEditDraft,
    expectedVersion = field == null
      ? null
      : cmsDraftExpectedVersion(draftSyncRef.current, 'fieldEdit', field.entityVersion),
  ) => {
    try {
      if (field == null || expectedVersion == null) {
        throw new Error('Select a field before saving.');
      }
      return {
        fieldId: field.id,
        payload: {
          name: draft.name,
          description: normalizeText(draft.description),
          fieldType: draft.fieldType,
          localizable: true,
          required: draft.required,
          sortOrder: parseRequiredNumber(draft.sortOrder, 'Copy piece display order'),
          expectedVersion,
        },
      };
    } catch (error) {
      setNotice({ kind: 'error', message: (error as Error).message });
      return null;
    }
  };

  const submitEntryUpdate = (
    draft: EntryDraft,
    {
      authoringBlockDetailsSave = false,
      authoringBlockDetailsFocus = null,
      authoringReleaseStatusChange = false,
    }: {
      authoringBlockDetailsSave?: boolean;
      authoringBlockDetailsFocus?: { entryId: string } | null;
      authoringReleaseStatusChange?: boolean;
    } = {},
  ) => {
    const request = getUpdateEntryRequest(draft);
    if (request == null) {
      return;
    }
    const updateEntry = () => {
      setNotice(null);
      updateEntryMutation.mutate({
        ...request,
        authoringBlockDetailsSave,
        authoringBlockDetailsFocus,
        authoringReleaseStatusChange,
      });
    };
    if (selectedEditEntry?.status === 'READY' && draft.status !== 'READY') {
      requestOperatorConfirmation(
        {
          title: 'Remove block from release?',
          body: `${selectedEditEntry.name} will be left out of the next release.`,
          confirmLabel: 'Remove from release',
          confirmVariant: 'danger',
        },
        updateEntry,
      );
      return;
    }
    updateEntry();
  };

  const handleUpdateEntry = (event: FormEvent) => {
    event.preventDefault();
    submitEntryUpdate(entryEditDraft, {
      authoringBlockDetailsSave: !isAdminToolsRoute,
      authoringBlockDetailsFocus:
        !isAdminToolsRoute && selectedEditEntry != null
          ? {
              entryId: String(selectedEditEntry.id),
            }
          : null,
    });
  };

  const handleAuthoringEntryReleaseStatusChange = (status: ApiCmsEntryStatus) => {
    const nextDraft = { ...entryEditDraft, status };
    setAuthoringRecovery((current) =>
      current?.kind === 'block-release' && current.entryId === entryEditId ? null : current,
    );
    setEntryEditDraft(nextDraft);
    submitEntryUpdate(nextDraft, { authoringReleaseStatusChange: true });
  };

  const getUpdateEntryRequest = (draft = entryEditDraft) => {
    try {
      if (selectedEditEntry == null) {
        throw new Error('Select a content item before saving.');
      }
      return {
        entryId: selectedEditEntry.id,
        payload: {
          name: draft.name,
          description: normalizeText(draft.description),
          status: draft.status,
          metadataJson: normalizeText(draft.metadataJson),
          expectedVersion: cmsDraftExpectedVersion(
            draftSyncRef.current,
            'entryEdit',
            selectedEditEntry.entityVersion,
          ),
        },
      };
    } catch (error) {
      setNotice({ kind: 'error', message: (error as Error).message });
      return null;
    }
  };

  const handleUpdateVariant = (event: FormEvent) => {
    event.preventDefault();
    try {
      if (selectedEditVariant == null) {
        throw new Error('Select an experiment path before saving.');
      }
      const updateVariant = () =>
        updateVariantMutation.mutate({
          variantId: selectedEditVariant.id,
          payload: {
            name: variantEditDraft.name,
            candidateGroupKey: normalizeText(variantEditDraft.candidateGroupKey),
            status: variantEditDraft.status,
            metadataJson: normalizeText(variantEditDraft.metadataJson),
            sortOrder: parseRequiredNumber(variantEditDraft.sortOrder, 'Path display order'),
            expectedVersion: cmsDraftExpectedVersion(
              draftSyncRef.current,
              'variantEdit',
              selectedEditVariant.entityVersion,
            ),
          },
        });
      if (selectedEditVariant.status !== 'CONTROL' && variantEditDraft.status === 'CONTROL') {
        requestOperatorConfirmation(
          {
            title: 'Make path default?',
            body: `Make ${selectedEditVariant.name} the default copy path? This archives the current default path for rollback.`,
            confirmLabel: 'Make default',
            confirmVariant: 'primary',
          },
          updateVariant,
        );
        return;
      }
      if (selectedEditVariant.status !== 'ARCHIVED' && variantEditDraft.status === 'ARCHIVED') {
        requestOperatorConfirmation(
          {
            title: 'Archive experiment path?',
            body: `Archive ${selectedEditVariant.name}? It will be excluded from the next release.`,
            confirmLabel: 'Archive path',
            confirmVariant: 'danger',
          },
          updateVariant,
        );
        return;
      }
      updateVariant();
    } catch (error) {
      setNotice({ kind: 'error', message: (error as Error).message });
    }
  };

  const handleCreateField = (event: FormEvent) => {
    event.preventDefault();
    requestDiscardDirtyCmsDrafts('Add another field?', ['mapping'], () => {
      resetCmsDraftSyncState(draftSyncRef.current, 'mapping');
      const fieldContentTypeId =
        fieldDraft.contentTypeId ||
        (selectedAuthoringContentType ? String(selectedAuthoringContentType.id) : '');
      try {
        createFieldMutation.mutate({
          contentTypeId: parseRequiredNumber(fieldContentTypeId, 'Block shape'),
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
    });
  };

  const handleCreateEntry = (event: FormEvent) => {
    event.preventDefault();
    if (selectedProjectId == null) {
      setNotice({ kind: 'error', message: 'Select a copy collection first.' });
      return;
    }
    requestDiscardDirtyCmsDrafts(
      'Create content item?',
      ['entryEdit', 'variantEdit', 'mapping'],
      () => {
        resetCmsDraftSyncState(draftSyncRef.current, 'entryEdit', 'variantEdit', 'mapping');
        setAuthoringFieldDrafts({});
        setAuthoringMappingDrafts({});
        const entryContentTypeId =
          entryDraft.contentTypeId ||
          (selectedAuthoringContentType ? String(selectedAuthoringContentType.id) : '');
        try {
          createEntryMutation.mutate({
            projectId: selectedProjectId,
            payload: {
              contentTypeId: parseRequiredNumber(entryContentTypeId, 'Block shape'),
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
      },
      { authoringFields: true, authoringMappings: true, confirmLabel: 'Discard and create' },
    );
  };

  const handleCreateAuthoringEntry = (event: FormEvent) => {
    event.preventDefault();
    if (selectedProjectId == null) {
      setNotice({ kind: 'error', message: 'Select a copy collection first.' });
      return;
    }
    requestDiscardDirtyCmsDrafts(
      'Save content item?',
      ['entryEdit', 'variantEdit', 'mapping'],
      () => {
        resetCmsDraftSyncState(draftSyncRef.current, 'entryEdit', 'variantEdit', 'mapping');
        setAuthoringFieldDrafts({});
        setAuthoringMappingDrafts({});
        const entryContentTypeId =
          entryDraft.contentTypeId ||
          (selectedAuthoringContentType ? String(selectedAuthoringContentType.id) : '');
        try {
          const contentTypeId = parseRequiredNumber(entryContentTypeId, 'Block shape');
          const contentType = findContentType(contentTypes, contentTypeId);
          const localizableFields =
            contentType?.fields
              .filter((field) => field.localizable)
              .sort((left, right) => left.sortOrder - right.sortOrder || left.id - right.id) ?? [];
          if (localizableFields.length === 0) {
            throw new Error('Add a field before saving a content item.');
          }
          const sourceMappings = localizableFields.flatMap((field) => {
            const sourceDraft = newEntrySourceDrafts[String(field.id)] ?? initialSourceCopyDraft;
            const sourceContent = normalizeSourceContent(sourceDraft.sourceContent);
            const sourceComment = normalizeText(sourceDraft.sourceComment);
            if (!field.required && sourceContent == null && sourceComment == null) {
              return [];
            }
            if (sourceContent == null) {
              throw new Error(`${field.name} source copy is required.`);
            }
            if (sourceComment == null) {
              throw new Error(`${field.name} translator note is required.`);
            }
            return [
              {
                fieldId: field.id,
                sourceContent,
                sourceComment,
              },
            ];
          });
          setNotice(null);
          createEntryMutation.mutate({
            projectId: selectedProjectId,
            payload: {
              contentTypeId,
              entryKey: entryDraft.entryKey,
              name: entryDraft.name,
              description: normalizeText(entryDraft.description),
              status: entryDraft.status,
              metadataJson: normalizeText(entryDraft.metadataJson),
              initialFieldMappings: sourceMappings,
            },
          });
        } catch (error) {
          setNotice({ kind: 'error', message: (error as Error).message });
        }
      },
      { authoringFields: true, authoringMappings: true, confirmLabel: 'Discard and save' },
    );
  };

  const handleCreateVariant = (event: FormEvent) => {
    event.preventDefault();
    try {
      createVariantMutation.mutate({
        entryId: parseRequiredNumber(variantDraft.entryId, 'Content item'),
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
    const request = getUpsertMappingRequest();
    if (request != null) {
      upsertMappingMutation.mutate(request);
    }
  };

  const getUpsertMappingRequest = (
    draft: MappingDraft = mappingDraft,
    expectedVersion = draftSyncRef.current.mapping.expectedVersion,
  ) => {
    try {
      const variantId = parseRequiredNumber(draft.variantId, 'Experiment path');
      const fieldId = parseRequiredNumber(draft.fieldId, 'Copy piece');
      const tmTextUnitId =
        draft.mappingSource === 'TM_TEXT_UNIT_ID' ? parseOptionalNumber(draft.tmTextUnitId) : null;
      const stringId = draft.mappingSource === 'STRING_ID' ? normalizeText(draft.stringId) : null;
      const generatedMapping = draft.mappingSource === 'GENERATED';
      const sourceContent = generatedMapping ? normalizeSourceContent(draft.sourceContent) : null;
      const sourceComment = generatedMapping ? normalizeText(draft.sourceComment) : null;
      if (draft.mappingSource === 'STRING_ID' && stringId == null) {
        setNotice({
          kind: 'error',
          message: 'Existing Mojito string ID is required.',
        });
        return null;
      }
      if (draft.mappingSource === 'TM_TEXT_UNIT_ID' && tmTextUnitId == null) {
        setNotice({
          kind: 'error',
          message: 'Exact TM text unit ID is required.',
        });
        return null;
      }
      if (draft.mappingSource === 'GENERATED' && sourceContent == null) {
        setNotice({
          kind: 'error',
          message: 'Source copy is required when reconnecting with a fresh Mojito link.',
        });
        return null;
      }
      if (draft.mappingSource === 'GENERATED' && sourceComment == null) {
        setNotice({
          kind: 'error',
          message: 'Translator note is required when reconnecting with a fresh Mojito link.',
        });
        return null;
      }
      return {
        variantId,
        payload: {
          fieldId,
          tmTextUnitId,
          stringId,
          sourceContent,
          sourceComment,
          expectedVersion,
        },
      };
    } catch (error) {
      setNotice({ kind: 'error', message: (error as Error).message });
      return null;
    }
  };

  const handleSaveAuthoringChanges = async () => {
    const entryRequest = draftSyncRef.current.entryEdit.dirty ? getUpdateEntryRequest() : null;
    if (draftSyncRef.current.entryEdit.dirty && entryRequest == null) {
      return;
    }
    const fieldRequest = draftSyncRef.current.fieldEdit.dirty ? getUpdateFieldRequest() : null;
    if (draftSyncRef.current.fieldEdit.dirty && fieldRequest == null) {
      return;
    }
    const authoringFieldRequests = Object.entries(authoringFieldDrafts)
      .filter(([, draftState]) => draftState.dirty)
      .map(([fieldId, draftState]) => {
        const field =
          selectedAuthoringFields.find((currentField) => String(currentField.id) === fieldId) ??
          null;
        return {
          fieldId,
          request: getUpdateFieldRequest(field, draftState.draft, draftState.expectedVersion),
        };
      });
    if (authoringFieldRequests.some(({ request }) => request == null)) {
      return;
    }
    const mappingRequests = Object.entries(authoringMappingDrafts)
      .filter(([, draftState]) => draftState.dirty)
      .map(([fieldId, draftState]) => ({
        fieldId,
        request: getUpsertMappingRequest(draftState.draft, draftState.expectedVersion),
      }));
    if (mappingRequests.some(({ request }) => request == null)) {
      return;
    }
    const batchSavedFieldFocus =
      !isAdminToolsRoute && mappingRequests.length > 0
        ? {
            fieldId:
              mappingRequests.find(({ fieldId }) => fieldId === activeAuthoringFieldId)?.fieldId ??
              mappingRequests[0]?.fieldId ??
              '',
            focusTarget: 'source-copy' as const,
          }
        : !isAdminToolsRoute && authoringFieldRequests.length > 0
          ? {
              fieldId:
                authoringFieldRequests.find(({ fieldId }) => fieldId === activeAuthoringFieldId)
                  ?.fieldId ??
                authoringFieldRequests[0]?.fieldId ??
                '',
              focusTarget: 'field-context' as const,
            }
          : null;
    const shouldFocusSavedEntryDetails =
      !isAdminToolsRoute && batchSavedFieldFocus == null && entryRequest != null;

    try {
      setNotice(null);
      if (entryRequest != null) {
        await updateEntryMutation.mutateAsync({
          ...entryRequest,
          authoringBlockDetailsSave: !isAdminToolsRoute,
        });
      }
      if (fieldRequest != null) {
        await updateFieldMutation.mutateAsync(fieldRequest);
      }
      for (const authoringFieldRequest of authoringFieldRequests) {
        if (authoringFieldRequest.request != null) {
          await updateFieldMutation.mutateAsync({
            ...authoringFieldRequest.request,
            authoringFieldContextSave: !isAdminToolsRoute,
          });
        }
      }
      for (const mappingRequest of mappingRequests) {
        if (mappingRequest.request != null) {
          await upsertMappingMutation.mutateAsync(mappingRequest.request);
        }
      }
      if (batchSavedFieldFocus != null && batchSavedFieldFocus.fieldId !== '') {
        queueSavedFieldFocus(batchSavedFieldFocus);
      } else if (shouldFocusSavedEntryDetails && entryRequest != null) {
        queueCopyBlockDetailsFocus(String(entryRequest.entryId));
      }
    } catch {
      // Individual mutation handlers surface the actionable error notice.
    }
  };

  const handleCompleteness = (event: FormEvent) => {
    event.preventDefault();
    if (selectedProjectId == null) {
      setNotice({ kind: 'error', message: 'Select a copy collection first.' });
      return;
    }
    completenessMutation.mutate({
      projectId: selectedProjectId,
      locales: publishLocales,
    });
  };

  const handleUnmapMapping = () => {
    if (selectedMapping == null) {
      setNotice({ kind: 'error', message: 'Select a linked field before removing its link.' });
      return;
    }
    requestOperatorConfirmation(
      {
        title: 'Remove Mojito link?',
        body: `Remove the link for ${selectedMapping.stringId}? It will be excluded from the next release; the Mojito text unit remains.`,
        confirmLabel: 'Remove link',
        confirmVariant: 'danger',
      },
      () =>
        unmapMappingMutation.mutate({
          mappingId: selectedMapping.id,
          expectedVersion: cmsDraftExpectedVersion(
            draftSyncRef.current,
            'mapping',
            selectedMapping.entityVersion,
          ),
        }),
    );
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

  const confirmPublish = (validatedPackage: ApiCmsProjectCompleteness) => {
    if (selectedProjectId == null || selectedProject == null || projectDetail == null) {
      setNotice({ kind: 'error', message: 'Select a copy collection first.' });
      return;
    }
    const publishProjectDetail = projectDetail;
    const localeTags = parsePublishLocaleTags(publishLocales);
    const publishLocaleScope =
      localeTags.length === 0
        ? isAdminToolsRoute
          ? 'configured translation languages'
          : 'every translation language'
        : localeTags.map(resolveLocaleDisplayName).join(', ');
    const includedEntryNames = publishProjectDetail.entries
      .filter((entry) => entry.status === 'READY')
      .map((entry) => entry.name);
    const publishConfirmationBody = isAdminToolsRoute
      ? `Release approved copy for ${selectedProject.name} using ${publishLocaleScope}?`
      : `Release ${formatIncludedReleaseEntryNames(
          includedEntryNames,
        )} from ${selectedProject.name} using ${publishLocaleScope}?`;
    requestOperatorConfirmation(
      {
        title: 'Release approved copy?',
        body: publishConfirmationBody,
        confirmLabel: 'Release copy',
        confirmVariant: 'primary',
      },
      () => {
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
            includedEntryNames,
          });
        } catch (error) {
          setNotice({ kind: 'error', message: (error as Error).message });
        }
      },
    );
  };

  const checkReleaseBeforePublishing = async ({
    confirmIfReady = true,
    preserveReleasedHandoff = false,
  } = {}) => {
    if (selectedProjectId == null || projectDetail == null) {
      setNotice({ kind: 'error', message: 'Select a copy collection first.' });
      return;
    }
    const requestedLocaleTags = parsePublishLocaleTags(publishLocales);
    try {
      const validatedPackage = await completenessMutation.mutateAsync({
        projectId: selectedProjectId,
        locales: publishLocales,
        preserveReleasedHandoff,
      });
      if (validatedPackage.authoringSha256 !== projectDetail.authoringSha256) {
        return;
      }
      if (!validatedPackage.complete) {
        return;
      }
      if (restoreExactReleasedSnapshot(validatedPackage, requestedLocaleTags)) {
        return;
      }
      if (!confirmIfReady) {
        return;
      }
      confirmPublish(validatedPackage);
    } catch {
      // Mutation callbacks surface the author-facing recovery notice.
    }
  };

  const handlePublish = () => {
    if (selectedProjectReleaseSuccess != null) {
      void checkReleaseBeforePublishing({
        confirmIfReady: false,
        preserveReleasedHandoff: true,
      });
      return;
    }
    if (releaseRecovery != null && releaseRecovery.kind !== 'retry') {
      void checkReleaseBeforePublishing();
      return;
    }
    const validatedPackage = completenessResult;
    if (validatedPackage == null || !validatedPackage.complete) {
      if (isAdminToolsRoute) {
        setNotice({
          kind: 'error',
          message:
            validatedPackage == null
              ? 'Check readiness before releasing.'
              : 'Cannot release until the readiness check passes.',
        });
        return;
      }
      void checkReleaseBeforePublishing();
      return;
    }
    if (restoreExactReleasedSnapshot(validatedPackage, parsePublishLocaleTags(publishLocales))) {
      return;
    }
    confirmPublish(validatedPackage);
  };

  const handleLoadOlderSnapshots = () => {
    if (selectedProjectId == null || nextBeforePublishSnapshotVersion == null) {
      setNotice({ kind: 'error', message: 'No older release page is available.' });
      return;
    }
    loadOlderSnapshotsMutation.mutate({
      projectId: selectedProjectId,
      beforeVersion: nextBeforePublishSnapshotVersion,
    });
  };

  const handleMappingDraftChange = (nextDraft: MappingDraft) => {
    setAdminTranslationRepairReadyToReturn(false);
    const selectionChanged =
      nextDraft.entryId !== mappingDraft.entryId ||
      nextDraft.variantId !== mappingDraft.variantId ||
      nextDraft.fieldId !== mappingDraft.fieldId;
    if (selectionChanged) {
      requestDiscardDirtyCmsDrafts('Change mapping target?', ['mapping'], () => {
        resetCmsDraftSyncState(draftSyncRef.current, 'mapping');
        setMappingDraft(nextDraft);
      });
      return;
    }
    markCmsDraftDirty(draftSyncRef.current, 'mapping');
    setMappingDraft(nextDraft);
  };

  const handleAuthoringFieldFocus = (
    fieldId: string,
    options?: { cancelPendingFieldSwitch?: boolean; focusTarget?: CopyFieldFocusTarget },
  ) => {
    if (selectedEditEntry == null || selectedAuthoringVariant == null) {
      return false;
    }
    const selectedAuthoringFieldId = selectedAuthoringField?.id.toString() ?? '';
    if (fieldId === selectedAuthoringFieldId) {
      if (options?.cancelPendingFieldSwitch) {
        const focusTarget = options.focusTarget ?? 'field';
        const preserveNoticeOnSavedFieldFocus =
          preserveNoticeOnSavedFieldFocusRef.current?.fieldId === fieldId &&
          preserveNoticeOnSavedFieldFocusRef.current.focusTarget === focusTarget;
        preserveNoticeOnSavedFieldFocusRef.current = null;
        clearPendingInlineTranslationFieldSwitch();
        if (!preserveNoticeOnSavedFieldFocus) {
          setNotice(null);
        }
      }
      return true;
    }
    if (selectedEntryCompletenessQuery.isError) {
      const nextAuthoringField =
        selectedAuthoringFields.find((field) => String(field.id) === fieldId) ?? null;
      const pendingFieldSwitch = {
        sourceFieldId: selectedAuthoringFieldId,
        targetFieldId: fieldId,
        targetFieldName: nextAuthoringField?.name ?? 'another field',
        focusTarget: options?.focusTarget ?? 'field',
        reason: 'refresh-error' as const,
      };
      pendingInlineTranslationFieldSwitchRef.current = pendingFieldSwitch;
      setPendingInlineTranslationFieldSwitch(pendingFieldSwitch);
      setNotice({
        kind: 'error',
        message: 'Refresh translation status before switching fields.',
      });
      return false;
    }
    if (hasDirtyInlineTranslation) {
      const nextAuthoringField =
        selectedAuthoringFields.find((field) => String(field.id) === fieldId) ?? null;
      const pendingFieldSwitch = {
        sourceFieldId: selectedAuthoringFieldId,
        targetFieldId: fieldId,
        targetFieldName: nextAuthoringField?.name ?? 'another field',
        focusTarget: options?.focusTarget ?? 'field',
        reason: 'dirty-translation' as const,
      };
      pendingInlineTranslationFieldSwitchRef.current = pendingFieldSwitch;
      setPendingInlineTranslationFieldSwitch(pendingFieldSwitch);
      setNotice({
        kind: 'error',
        message: 'Save or reset target translation before switching fields.',
      });
      return false;
    }
    setActiveAuthoringFieldId(fieldId);
    clearPendingInlineTranslationFieldSwitch();
    setNotice(null);
    return true;
  };

  const handleAuthoringFieldContextChange = (fieldId: string, description: string) => {
    if (!handleAuthoringFieldFocus(fieldId)) {
      return;
    }
    setAuthoringRecovery((current) =>
      current?.kind === 'field-context' && current.fieldId === fieldId ? null : current,
    );
    setAuthoringFieldDrafts((current) => {
      const draftState = current[fieldId];
      if (draftState == null) {
        return current;
      }
      return {
        ...current,
        [fieldId]: {
          ...draftState,
          draft: {
            ...draftState.draft,
            description,
          },
          dirty: true,
        },
      };
    });
  };

  const handleAuthoringFieldContextSubmit = (fieldId: string, event: FormEvent) => {
    event.preventDefault();
    const draftState = authoringFieldDrafts[fieldId];
    const field =
      selectedAuthoringFields.find((currentField) => String(currentField.id) === fieldId) ?? null;
    const request =
      draftState == null
        ? null
        : getUpdateFieldRequest(field, draftState.draft, draftState.expectedVersion);
    if (request != null) {
      setNotice(null);
      updateFieldMutation.mutate({
        ...request,
        authoringFieldContextSave: true,
        authoringFieldContextFocus:
          !isAdminToolsRoute && selectedEditEntry != null
            ? {
                entryId: String(selectedEditEntry.id),
                fieldId,
              }
            : null,
      });
    }
  };

  const handleResetAuthoringFieldDraft = (fieldId: string) => {
    if (!handleAuthoringFieldFocus(fieldId)) {
      return;
    }
    setAuthoringRecovery((current) =>
      current?.kind === 'field-context' && current.fieldId === fieldId ? null : current,
    );
    setAuthoringFieldDrafts((current) => {
      const draftState = current[fieldId];
      if (draftState == null) {
        return current;
      }
      return {
        ...current,
        [fieldId]: {
          ...draftState,
          draft: draftState.savedDraft,
          dirty: false,
          staleSavedDraft: null,
        },
      };
    });
    queueCopyFieldScroll(fieldId, 'field-context');
  };

  const handleResetAuthoringEntryDraft = () => {
    setAuthoringRecovery((current) =>
      current?.kind === 'block-details' && current.entryId === entryEditId ? null : current,
    );
    clearCmsDraftDirty(draftSyncRef.current, 'entryEdit');
    setEntryEditDraft(authoringEntrySavedDraft);
    setStaleAuthoringEntrySavedDraft(null);
    if (entryEditId !== '') {
      queueCopyBlockDetailsFocus(entryEditId);
    }
  };

  const handleAuthoringMappingDraftChange = (fieldId: string, nextDraft: MappingDraft) => {
    if (!handleAuthoringFieldFocus(fieldId)) {
      return;
    }
    setAuthoringRecovery((current) =>
      current?.kind === 'source-copy' && current.fieldId === fieldId ? null : current,
    );
    setAuthoringMappingDrafts((current) => {
      const draftState = current[fieldId];
      if (draftState == null) {
        return current;
      }
      return {
        ...current,
        [fieldId]: {
          ...draftState,
          draft: nextDraft,
          dirty: true,
        },
      };
    });
  };

  const handleResetAuthoringMappingDraft = (fieldId: string) => {
    if (!handleAuthoringFieldFocus(fieldId)) {
      return;
    }
    const shouldRefocusTranslationReconnect = shouldRefocusAuthoringTranslationReconnect(fieldId);
    setAuthoringRecovery((current) =>
      current?.kind === 'source-copy' && current.fieldId === fieldId ? null : current,
    );
    setAuthoringMappingDrafts((current) => {
      const draftState = current[fieldId];
      if (draftState == null) {
        return current;
      }
      return {
        ...current,
        [fieldId]: {
          ...draftState,
          draft: draftState.savedDraft,
          dirty: false,
          staleSavedDraft: null,
        },
      };
    });
    queueCopyFieldScroll(
      fieldId,
      shouldRefocusTranslationReconnect ? 'translation-reconnect' : 'source-copy',
    );
    if (completenessResult != null) {
      settleReleaseSourceCopyRepairFromReadiness(completenessResult, fieldId);
    }
  };

  const handleAuthoringMappingSubmit = (fieldId: string, event: FormEvent) => {
    event.preventDefault();
    const draftState = authoringMappingDrafts[fieldId];
    if (draftState == null) {
      setNotice({ kind: 'error', message: 'Select a field before saving.' });
      return;
    }
    const request = getUpsertMappingRequest(draftState.draft, draftState.expectedVersion);
    if (request != null) {
      setNotice(null);
      upsertMappingMutation.mutate({
        ...request,
        authoringSourceCopySave:
          !isAdminToolsRoute && selectedEditEntry != null
            ? {
                entryId: String(selectedEditEntry.id),
                fieldId,
              }
            : null,
      });
    }
  };
  const handleReconnectAuthoringTranslation = (fieldId: string, localeTag: string) => {
    if (!handleAuthoringFieldFocus(fieldId)) {
      return;
    }
    const draftState = authoringMappingDrafts[fieldId];
    if (draftState == null || selectedEditEntry == null) {
      setNotice({ kind: 'error', message: 'Select a field before reconnecting.' });
      return;
    }
    const request = getUpsertMappingRequest(
      {
        ...draftState.draft,
        mappingSource: 'GENERATED',
        tmTextUnitId: '',
        stringId: '',
      },
      draftState.expectedVersion,
    );
    if (request != null) {
      setNotice(null);
      updateAuthoringReviewTarget((current) => ({
        ...current,
        projectId: selectedProjectId,
        entryId: String(selectedEditEntry.id),
        fieldId,
        localeTag,
        repairTarget: 'translation',
        translationReconnectErrorMessage: null,
        requestedLocaleSetupRefreshPending: false,
      }));
      upsertMappingMutation.mutate({
        ...request,
        authoringTranslationReconnect: {
          entryId: String(selectedEditEntry.id),
          fieldId,
          localeTag,
        },
      });
    }
  };

  const setupForm =
    contentTypes.length === 0 ? (
      <FirstCopyBlockForm
        draft={firstCopyBlockDraft}
        disabled={isSaving}
        authoringRecovery={
          authoringRecovery?.kind === 'first-copy-block' ? authoringRecovery : null
        }
        onChange={(draft) => {
          setAuthoringRecovery((current) =>
            current?.kind === 'first-copy-block' ? null : current,
          );
          setFirstCopyBlockDraft(draft);
        }}
        onSubmit={handleCreateFirstCopyBlock}
      />
    ) : null;
  const closeStructureAndMappingTasks = () => {
    setStructureRepairOpen(false);
    setStructureRepairAction(null);
    setVariantMaintenanceOpen(false);
    setMappingRepairOpen(false);
  };
  const closeAdvancedMaintenanceTasks = () => {
    setWritingSpaceMaintenanceOpen(false);
    setCopyStructureMaintenanceOpen(false);
    setCopyStructureMaintenanceAction(null);
    setRawRecordsOpen(false);
  };
  const handleAuthoringToolsToggle = () => {
    if (authoringToolsOpen) {
      closeStructureAndMappingTasks();
    }
    setAuthoringToolsOpen(!authoringToolsOpen);
    setAdvancedMaintenanceOpen(false);
    closeAdvancedMaintenanceTasks();
  };
  const handleAdvancedMaintenanceToggle = () => {
    if (advancedMaintenanceOpen) {
      closeAdvancedMaintenanceTasks();
    }
    setAdvancedMaintenanceOpen(!advancedMaintenanceOpen);
    setAuthoringToolsOpen(false);
    closeStructureAndMappingTasks();
  };
  const handleStructureRepairToggle = () => {
    const nextOpen = !structureRepairOpen;
    setStructureRepairOpen(nextOpen);
    if (!nextOpen) {
      setStructureRepairAction(null);
    }
    if (nextOpen) {
      setVariantMaintenanceOpen(false);
      setMappingRepairOpen(false);
    }
  };
  const handleStructureRepairAction = (action: Exclude<StructureRepairAction, null>) => {
    setStructureRepairAction((current) => (current === action ? null : action));
  };
  const handleVariantMaintenanceToggle = () => {
    const nextOpen = !variantMaintenanceOpen;
    setVariantMaintenanceOpen(nextOpen);
    if (nextOpen) {
      setStructureRepairOpen(false);
      setStructureRepairAction(null);
      setMappingRepairOpen(false);
    }
  };
  const handleMappingRepairToggle = () => {
    const nextOpen = !mappingRepairOpen;
    setMappingRepairOpen(nextOpen);
    if (nextOpen) {
      setStructureRepairOpen(false);
      setStructureRepairAction(null);
      setVariantMaintenanceOpen(false);
    }
  };
  const handleWritingSpaceMaintenanceToggle = () => {
    const nextOpen = !writingSpaceMaintenanceOpen;
    setWritingSpaceMaintenanceOpen(nextOpen);
    if (nextOpen) {
      setCopyStructureMaintenanceOpen(false);
      setCopyStructureMaintenanceAction(null);
      setRawRecordsOpen(false);
    }
  };
  const handleCopyStructureMaintenanceToggle = () => {
    const nextOpen = !copyStructureMaintenanceOpen;
    setCopyStructureMaintenanceOpen(nextOpen);
    if (!nextOpen) {
      setCopyStructureMaintenanceAction(null);
    }
    if (nextOpen) {
      setWritingSpaceMaintenanceOpen(false);
      setRawRecordsOpen(false);
    }
  };
  const handleCopyStructureMaintenanceAction = (
    action: Exclude<CopyStructureMaintenanceAction, null>,
  ) => {
    setCopyStructureMaintenanceAction((current) => (current === action ? null : action));
  };
  const handleRawRecordsToggle = () => {
    const nextOpen = !rawRecordsOpen;
    setRawRecordsOpen(nextOpen);
    if (nextOpen) {
      setWritingSpaceMaintenanceOpen(false);
      setCopyStructureMaintenanceOpen(false);
      setCopyStructureMaintenanceAction(null);
    }
  };
  const adminToolsContent =
    projectDetail == null ? null : (
      <>
        {adminTranslationRepairContext == null ? (
          <section
            className="content-cms-admin-page__admin-route-overview"
            aria-label="Maintenance areas"
          >
            <span className="content-cms-admin-page__eyebrow">Use when needed</span>
            <p className="settings-page__hint">
              Product copy owns normal writing and release. Open one maintenance area only when the
              author route cannot express the repair.
            </p>
          </section>
        ) : (
          <section
            className="content-cms-admin-page__admin-route-overview content-cms-admin-page__admin-route-overview--repair"
            aria-label="Blocked translation repair"
          >
            <span className="content-cms-admin-page__eyebrow">Opened from Product copy</span>
            <h3>
              Reconnect {adminTranslationRepairContext.fieldName} for{' '}
              {adminTranslationRepairContext.localeLabel}
            </h3>
            <p className="settings-page__hint">
              The repair form below is already scoped to{' '}
              {adminTranslationRepairContext.contentItemName}. Save that Mojito link, then return to
              Product copy to reopen the same translation.
            </p>
          </section>
        )}
        <section className="content-cms-admin-page__toolbox content-cms-admin-page__toolbox--nested">
          <button
            type="button"
            className="content-cms-admin-page__toolbox-trigger"
            aria-expanded={authoringToolsOpen}
            onClick={handleAuthoringToolsToggle}
          >
            {adminTranslationRepairContext == null
              ? 'Structure and mappings'
              : 'Translation repair'}
          </button>
          <p className="settings-page__hint">
            {adminTranslationRepairContext == null
              ? 'Add or repair block shapes, fields, content items, experiment candidates, and Mojito links.'
              : 'Repair the saved Mojito link for this blocked translation.'}
          </p>
          {authoringToolsOpen ? (
            <div className="content-cms-admin-page__maintenance-task-list">
              {adminTranslationRepairContext == null ? (
                <>
                  <section className="content-cms-admin-page__maintenance-task">
                    <button
                      type="button"
                      className="content-cms-admin-page__toolbox-trigger"
                      aria-expanded={structureRepairOpen}
                      onClick={handleStructureRepairToggle}
                    >
                      Repair copy structure
                    </button>
                    <p className="settings-page__hint">
                      Create a missing block shape, field, or content item only when Product copy
                      cannot.
                    </p>
                    {structureRepairOpen ? (
                      <>
                        <div
                          className="content-cms-admin-page__maintenance-action-group"
                          role="group"
                          aria-label="Choose one copy-structure repair"
                        >
                          <button
                            type="button"
                            className="settings-button settings-button--ghost content-cms-admin-page__maintenance-action"
                            aria-pressed={structureRepairAction === 'block-shape'}
                            onClick={() => handleStructureRepairAction('block-shape')}
                          >
                            Block shape
                          </button>
                          <button
                            type="button"
                            className="settings-button settings-button--ghost content-cms-admin-page__maintenance-action"
                            aria-pressed={structureRepairAction === 'copy-piece'}
                            disabled={contentTypes.length === 0}
                            onClick={() => handleStructureRepairAction('copy-piece')}
                          >
                            Copy piece
                          </button>
                          <button
                            type="button"
                            className="settings-button settings-button--ghost content-cms-admin-page__maintenance-action"
                            aria-pressed={structureRepairAction === 'copy-block'}
                            disabled={contentTypes.length === 0}
                            onClick={() => handleStructureRepairAction('copy-block')}
                          >
                            Copy block
                          </button>
                        </div>
                        {structureRepairAction == null ? (
                          <p className="settings-page__hint">
                            Choose one repair action; Product copy still owns normal writing.
                          </p>
                        ) : (
                          <div className="content-cms-admin-page__workflow-grid">
                            {structureRepairAction === 'block-shape' ? (
                              <>
                                {contentTypes.length === 0 ? (
                                  <FirstCopyBlockIdentifiers
                                    draft={firstCopyBlockDraft}
                                    disabled={isSaving}
                                    onChange={setFirstCopyBlockDraft}
                                  />
                                ) : null}
                                <ContentTypeForm
                                  draft={contentTypeDraft}
                                  disabled={isSaving}
                                  onChange={setContentTypeDraft}
                                  onSubmit={handleCreateContentType}
                                />
                              </>
                            ) : null}
                            {structureRepairAction === 'copy-piece' ? (
                              <FieldForm
                                contentTypes={contentTypes}
                                draft={fieldDraft}
                                disabled={isSaving}
                                onChange={setFieldDraft}
                                onSubmit={handleCreateField}
                              />
                            ) : null}
                            {structureRepairAction === 'copy-block' ? (
                              <EntryForm
                                contentTypes={contentTypes}
                                draft={entryDraft}
                                disabled={isSaving}
                                onChange={setEntryDraft}
                                onSubmit={handleCreateEntry}
                              />
                            ) : null}
                          </div>
                        )}
                      </>
                    ) : null}
                  </section>
                  <section className="content-cms-admin-page__maintenance-task">
                    <button
                      type="button"
                      className="content-cms-admin-page__toolbox-trigger"
                      aria-expanded={variantMaintenanceOpen}
                      onClick={handleVariantMaintenanceToggle}
                    >
                      Add experiment candidate
                    </button>
                    <p className="settings-page__hint">
                      Create one alternate copy path only when a product experiment needs it.
                    </p>
                    {variantMaintenanceOpen ? (
                      <div className="content-cms-admin-page__workflow-grid">
                        <VariantForm
                          entries={entries}
                          draft={variantDraft}
                          disabled={isSaving}
                          onChange={setVariantDraft}
                          onSubmit={handleCreateVariant}
                        />
                      </div>
                    ) : null}
                  </section>
                </>
              ) : null}
              <section className="content-cms-admin-page__maintenance-task">
                <button
                  type="button"
                  className="content-cms-admin-page__toolbox-trigger"
                  aria-expanded={mappingRepairOpen}
                  onClick={handleMappingRepairToggle}
                >
                  Repair Mojito links
                </button>
                <p className="settings-page__hint">
                  Reconnect one saved field; exact Mojito records stay behind technical fields.
                </p>
                {mappingRepairOpen ? (
                  <div className="content-cms-admin-page__workflow-grid">
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
                      repairContext={adminTranslationRepairContext}
                      repairReadyToReturn={adminTranslationRepairReadyToReturn}
                      onReturnToProductCopy={handleReturnToProductCopyAfterRepair}
                    />
                  </div>
                ) : null}
              </section>
            </div>
          ) : null}
        </section>

        {adminTranslationRepairContext == null ? (
          <section className="content-cms-admin-page__toolbox content-cms-admin-page__toolbox--nested">
            <button
              type="button"
              className="content-cms-admin-page__toolbox-trigger"
              aria-expanded={advancedMaintenanceOpen}
              onClick={handleAdvancedMaintenanceToggle}
            >
              Advanced maintenance
            </button>
            <p className="settings-page__hint">
              Rename or disable the copy collection, edit saved copy structure, or debug exact
              records.
            </p>
            {advancedMaintenanceOpen ? (
              <div className="content-cms-admin-page__maintenance-task-list">
                <section className="content-cms-admin-page__maintenance-task">
                  <button
                    type="button"
                    className="content-cms-admin-page__toolbox-trigger"
                    aria-expanded={writingSpaceMaintenanceOpen}
                    onClick={handleWritingSpaceMaintenanceToggle}
                  >
                    Rename or disable copy collection
                  </button>
                  <p className="settings-page__hint">
                    Rename or disable this copy collection; fixed delivery settings stay behind
                    technical fields.
                  </p>
                  {writingSpaceMaintenanceOpen ? (
                    <>
                      <ProjectOverview detail={projectDetail} />
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
                      </div>
                    </>
                  ) : null}
                </section>
                <section className="content-cms-admin-page__maintenance-task">
                  <button
                    type="button"
                    className="content-cms-admin-page__toolbox-trigger"
                    aria-expanded={copyStructureMaintenanceOpen}
                    onClick={handleCopyStructureMaintenanceToggle}
                  >
                    Edit copy structure
                  </button>
                  <p className="settings-page__hint">
                    Adjust saved block shapes, copy pieces, copy blocks, or experiment paths only
                    when Product copy cannot.
                  </p>
                  {copyStructureMaintenanceOpen ? (
                    <>
                      <div
                        className="content-cms-admin-page__maintenance-action-group"
                        role="group"
                        aria-label="Choose one saved copy-structure edit"
                      >
                        <button
                          type="button"
                          className="settings-button settings-button--ghost content-cms-admin-page__maintenance-action"
                          aria-pressed={copyStructureMaintenanceAction === 'block-shape'}
                          onClick={() => handleCopyStructureMaintenanceAction('block-shape')}
                        >
                          Block shape
                        </button>
                        <button
                          type="button"
                          className="settings-button settings-button--ghost content-cms-admin-page__maintenance-action"
                          aria-pressed={copyStructureMaintenanceAction === 'copy-piece'}
                          onClick={() => handleCopyStructureMaintenanceAction('copy-piece')}
                        >
                          Copy piece
                        </button>
                        <button
                          type="button"
                          className="settings-button settings-button--ghost content-cms-admin-page__maintenance-action"
                          aria-pressed={copyStructureMaintenanceAction === 'copy-block'}
                          onClick={() => handleCopyStructureMaintenanceAction('copy-block')}
                        >
                          Copy block
                        </button>
                        <button
                          type="button"
                          className="settings-button settings-button--ghost content-cms-admin-page__maintenance-action"
                          aria-pressed={copyStructureMaintenanceAction === 'variant'}
                          onClick={() => handleCopyStructureMaintenanceAction('variant')}
                        >
                          Experiment path
                        </button>
                      </div>
                      {copyStructureMaintenanceAction == null ? (
                        <p className="settings-page__hint">
                          Choose one saved-structure edit; Product copy still owns normal writing.
                        </p>
                      ) : (
                        <div className="content-cms-admin-page__workflow-grid">
                          {copyStructureMaintenanceAction === 'block-shape' ? (
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
                          ) : null}
                          {copyStructureMaintenanceAction === 'copy-piece' ? (
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
                          ) : null}
                          {copyStructureMaintenanceAction === 'copy-block' ? (
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
                          ) : null}
                          {copyStructureMaintenanceAction === 'variant' ? (
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
                          ) : null}
                        </div>
                      )}
                    </>
                  ) : null}
                </section>
                <section className="content-cms-admin-page__maintenance-task">
                  <button
                    type="button"
                    className="content-cms-admin-page__toolbox-trigger"
                    aria-expanded={rawRecordsOpen}
                    onClick={handleRawRecordsToggle}
                  >
                    Debug raw records
                  </button>
                  <p className="settings-page__hint">
                    Inspect read-only block-shape, copy-piece, experiment-path, and Mojito-link
                    records only when debugging.
                  </p>
                  {rawRecordsOpen ? (
                    <>
                      <p className="content-cms-admin-page__maintenance-readonly-note">
                        Read-only debug view. Use Product copy or a focused repair task to change
                        content.
                      </p>
                      <ContentTypesTable contentTypes={contentTypes} />
                      <EntriesTable entries={entries} contentTypes={contentTypes} />
                    </>
                  ) : null}
                </section>
              </div>
            ) : null}
          </section>
        ) : null}
      </>
    );

  return (
    <div className="settings-subpage">
      <SettingsSubpageHeader
        backTo={isAdminToolsRoute ? authoringPath : '/settings/system'}
        backLabel={isAdminToolsRoute ? 'Back to Product copy' : 'Back to settings'}
        onBack={hasCmsExitRisk ? handleBack : undefined}
        title={isAdminToolsRoute ? 'Admin tools' : 'Product copy'}
        centerContent={
          selectedProject?.name ?? (hasNoProjects ? undefined : 'No copy collection selected')
        }
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

        <div className={`content-cms-admin-page__layout${hasNoProjects ? ' is-clean-start' : ''}`}>
          {!hasNoProjects ? (
            <aside
              className={`content-cms-admin-page__sidebar${
                mobileCopyCollectionsOpen ? ' is-mobile-open' : ''
              }`}
              aria-label="Copy collections"
            >
              <CopyCollectionsSidebarToggle
                controlsId={cmsCopyCollectionsSidebarBodyId}
                open={mobileCopyCollectionsOpen}
                onOpenChange={setMobileCopyCollectionsOpen}
              />
              <div
                id={cmsCopyCollectionsSidebarBodyId}
                className="content-cms-admin-page__sidebar-body"
              >
                <div className="content-cms-admin-page__sidebar-header">
                  <h2>Copy collections</h2>
                </div>
                {renderCopyCollectionRail()}
              </div>
            </aside>
          ) : null}
          <section
            className="content-cms-admin-page__main"
            aria-label="Product copy editor"
            aria-busy={isSaving}
          >
            {isStartingWritingSpace || (hasNoProjects && !isAdminToolsRoute) ? (
              <CmsEmptyState>
                <FirstWritingSpaceForm
                  projectDraft={projectDraft}
                  firstCopyBlockDraft={firstCopyBlockDraft}
                  disabled={isSaving}
                  focusCopyCollectionName={isStartingWritingSpace}
                  authoringRecovery={
                    authoringRecovery?.kind === 'first-writing-space' ? authoringRecovery : null
                  }
                  onProjectDraftChange={(draft) => {
                    setAuthoringRecovery((current) =>
                      current?.kind === 'first-writing-space' ? null : current,
                    );
                    setProjectDraft(draft);
                  }}
                  onFirstCopyBlockDraftChange={(draft) => {
                    setAuthoringRecovery((current) =>
                      current?.kind === 'first-writing-space' ? null : current,
                    );
                    setFirstCopyBlockDraft(draft);
                  }}
                  onCancel={isStartingWritingSpace ? handleCancelStartingWritingSpace : undefined}
                  onSubmit={handleCreateFirstWritingSpace}
                />
              </CmsEmptyState>
            ) : hasNoProjects ? (
              <CmsEmptyState>
                <section className="settings-card content-cms-admin-page__admin-tools-empty">
                  <span className="content-cms-admin-page__eyebrow">Admin</span>
                  <h2>No copy collection yet</h2>
                  <p className="settings-page__hint">
                    Start in Product copy before opening technical controls.
                  </p>
                  <div className="content-cms-admin-page__actions">
                    <button
                      type="button"
                      className="settings-button settings-button--primary"
                      onClick={() => {
                        void navigate(authoringPath);
                      }}
                    >
                      Back to Product copy
                    </button>
                  </div>
                </section>
              </CmsEmptyState>
            ) : projectQuery.isLoading ? (
              <section
                className="content-cms-admin-page__route-state"
                role="status"
                aria-live="polite"
              >
                <p className="settings-page__hint">Opening selected copy collection.</p>
              </section>
            ) : projectQuery.isError && projectDetail == null ? (
              <section
                className="content-cms-admin-page__route-state content-cms-admin-page__route-state--error"
                role="alert"
              >
                <p className="settings-hint is-error">Selected copy collection could not open.</p>
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
                {isAdminToolsRoute ? (
                  <>
                    <section className="settings-card content-cms-admin-page__toolbox content-cms-admin-page__toolbox--admin-route">
                      <div className="settings-card__header content-cms-admin-page__admin-tools-header">
                        <div>
                          <span className="content-cms-admin-page__eyebrow">Admin</span>
                          <h2>Maintenance tools</h2>
                          <p className="settings-page__hint">
                            Keep normal copy work in Product copy; use this screen for repairs and
                            fixed delivery settings.
                          </p>
                        </div>
                      </div>
                      <div className="content-cms-admin-page__project-tools-body">
                        {adminToolsContent}
                      </div>
                    </section>

                    {entries.length > 0 ? (
                      <section className="settings-card content-cms-admin-page__release-card">
                        <PublishForm
                          projectName={projectDetail.project.name}
                          sourceLocale={projectDetail.project.sourceLocale}
                          entries={entries}
                          contentTypes={contentTypes}
                          snapshots={publishSnapshots}
                          hasMoreSnapshots={hasMorePublishSnapshots}
                          isLoadingOlderSnapshots={loadOlderSnapshotsMutation.isPending}
                          completenessResult={completenessResult}
                          blockedCompletenessResult={lastCheckedCompletenessResult}
                          publishLocales={publishLocales}
                          disabled={isSaving}
                          compact
                          releaseRecovery={releaseRecovery}
                          releaseCheckPending={releaseCheckPending}
                          releasePublishPending={publishMutation.isPending}
                          releaseSuccess={selectedProjectReleaseSuccess}
                          releaseHistoryChange={releaseHistoryChangeWithActions}
                          onPublishLocalesChange={(value) => {
                            setPublishLocales(value);
                            setCompletenessResult(null);
                            setLastCheckedCompletenessResult(null);
                            setReleaseRecovery(null);
                            setReleaseSuccess(null);
                            setReleaseChangeSummaryError(null);
                          }}
                          onCompletenessSubmit={handleCompleteness}
                          onPublish={handlePublish}
                          onLoadOlderSnapshots={handleLoadOlderSnapshots}
                        />
                      </section>
                    ) : null}
                  </>
                ) : (
                  <>
                    {setupForm ? (
                      setupForm
                    ) : (
                      <CmsEntryWorkspace
                        projectId={projectDetail.project.id}
                        projectName={projectDetail.project.name}
                        projectKey={projectDetail.project.projectKey}
                        sourceLocale={projectDetail.project.sourceLocale}
                        entries={entries}
                        contentTypes={contentTypes}
                        selectedEntry={selectedEditEntry}
                        selectedEntryId={entryEditId}
                        selectedVariant={selectedAuthoringVariant}
                        selectedField={selectedAuthoringField}
                        selectedEntryCompleteness={selectedEntryCompletenessQuery.data ?? null}
                        selectedEntryCompletenessLoading={selectedEntryCompletenessQuery.isLoading}
                        selectedEntryCompletenessRefreshing={
                          selectedEntryCompletenessQuery.isFetching
                        }
                        selectedEntryCompletenessError={selectedEntryCompletenessQuery.isError}
                        targetLocaleOptions={localeOptions}
                        targetLocaleTagsDraft={targetLocaleTagsDraft}
                        targetLocalesLoading={localesLoading}
                        targetLocalesError={localesError}
                        targetLocalesSaving={addTargetLocalesMutation.isPending}
                        newEntryDraft={entryDraft}
                        newEntrySourceDrafts={newEntrySourceDrafts}
                        entryDraft={entryEditDraft}
                        staleSavedEntryDraft={staleAuthoringEntrySavedDraft}
                        mappingDraft={selectedAuthoringMappingDraft}
                        authoringFieldDrafts={authoringFieldDrafts}
                        authoringMappingDrafts={authoringMappingDrafts}
                        authoringRecovery={authoringRecovery}
                        entryContextDirty={draftSyncRef.current.entryEdit.dirty}
                        hasDirtyFieldContextDrafts={hasDirtyAuthoringFields}
                        hasDirtySourceCopyDrafts={hasDirtyAuthoringMappings}
                        hasDirtyInlineTranslation={hasDirtyInlineTranslation}
                        pendingInlineTranslationFieldSwitch={pendingInlineTranslationFieldSwitch}
                        authorReleasePanel={
                          shouldShowAuthorRelease ? (
                            <section className="content-cms-admin-page__release-card content-cms-admin-page__release-card--author">
                              <PublishForm
                                projectName={projectDetail.project.name}
                                sourceLocale={projectDetail.project.sourceLocale}
                                entries={entries}
                                contentTypes={contentTypes}
                                selectedEntryId={entryEditId}
                                selectedFieldId={activeAuthoringFieldId}
                                snapshots={publishSnapshots}
                                hasMoreSnapshots={hasMorePublishSnapshots}
                                isLoadingOlderSnapshots={loadOlderSnapshotsMutation.isPending}
                                completenessResult={completenessResult}
                                blockedCompletenessResult={lastCheckedCompletenessResult}
                                publishLocales={publishLocales}
                                disabled={isSaving || authorReleaseBlockedHint != null}
                                compact
                                authorFacing
                                showTechnicalDetails={false}
                                blockedHint={authorReleaseBlockedHint}
                                blockedStatusLabel={authorReleaseBlockedStatusLabel}
                                inlineTranslationReleaseBlockerTargets={
                                  inlineTranslationReleaseBlockerTargets
                                }
                                releaseRecovery={releaseRecovery}
                                releaseCheckPending={releaseCheckPending}
                                releasePublishPending={publishMutation.isPending}
                                releaseSuccess={selectedProjectReleaseSuccess}
                                releaseHistoryChange={releaseHistoryChangeWithActions}
                                onOpenIncludedEntry={handleAuthoringEntryChange}
                                onReviewBlockedEntry={handleReviewBlockedAuthoringEntry}
                                onPublishLocalesChange={(value) => {
                                  setPublishLocales(value);
                                  setCompletenessResult(null);
                                  setLastCheckedCompletenessResult(null);
                                  setReleaseRecovery(null);
                                  setReleaseSuccess(null);
                                  setReleaseChangeSummaryError(null);
                                }}
                                onCompletenessSubmit={handleCompleteness}
                                onPublish={handlePublish}
                                onLoadOlderSnapshots={handleLoadOlderSnapshots}
                              />
                            </section>
                          ) : null
                        }
                        inlineTranslationReleaseBlockerTargets={
                          inlineTranslationReleaseBlockerTargets
                        }
                        disabled={isSaving}
                        reviewEntryRequestKey={selectedAuthoringReviewTarget.requestKey}
                        reviewEntryId={selectedAuthoringReviewTarget.entryId}
                        reviewFieldId={selectedAuthoringReviewTarget.fieldId}
                        reviewLocaleTag={selectedAuthoringReviewTarget.localeTag}
                        reviewRepairTarget={selectedAuthoringReviewTarget.repairTarget}
                        reviewReason={selectedAuthoringReviewTarget.reviewReason}
                        reviewReturnMode={selectedAuthoringReviewTarget.reviewReturnMode}
                        reviewLastReleasedSourceContent={
                          selectedAuthoringReviewTarget.lastReleasedSourceContent
                        }
                        reviewLastReleasedTranslationContent={
                          selectedAuthoringReviewTarget.lastReleasedTranslationContent
                        }
                        reviewReturnToRelease={selectedAuthoringReviewTarget.returnToRelease}
                        reviewRefreshTranslation={selectedAuthoringReviewTarget.refreshTranslation}
                        reviewTranslationRepairSaved={
                          selectedAuthoringReviewTarget.translationRepairSaved
                        }
                        reviewTranslationReconnectErrorMessage={
                          selectedAuthoringReviewTarget.translationReconnectErrorMessage
                        }
                        reviewTranslationReconnectRefreshFailed={
                          projectQuery.isError && projectDetail != null
                        }
                        reviewTranslationReconnectRefreshPending={
                          projectQuery.isFetching && projectDetail != null
                        }
                        sourceCopyConflictRefreshFailed={
                          selectedAuthoringSourceCopyConflictRefreshFailed
                        }
                        sourceCopyConflictRefreshPending={
                          selectedAuthoringSourceCopyConflictRefreshPending
                        }
                        reviewRequestedLocaleSetupRefreshPending={
                          selectedAuthoringReviewTarget.requestedLocaleSetupRefreshPending
                        }
                        onReturnToRelease={handleReturnFromReleaseReview}
                        onEntryChange={handleAuthoringEntryChange}
                        onNewEntryOpenChange={setIsWritingNewCopyBlock}
                        onStartNewEntry={handleStartAuthoringEntry}
                        onCancelNewEntry={handleCancelAuthoringEntry}
                        onNewEntryDraftChange={(draft) => {
                          setAuthoringRecovery((current) =>
                            current?.kind === 'new-block' ? null : current,
                          );
                          setEntryDraft(draft);
                        }}
                        onNewEntrySourceDraftsChange={(drafts) => {
                          setAuthoringRecovery((current) =>
                            current?.kind === 'new-block' ? null : current,
                          );
                          setNewEntrySourceDrafts(drafts);
                        }}
                        onNewEntrySubmit={handleCreateAuthoringEntry}
                        onEntryDraftChange={(draft) => {
                          setAuthoringRecovery((current) =>
                            current?.kind === 'block-details' && current.entryId === entryEditId
                              ? null
                              : current,
                          );
                          markCmsDraftDirty(draftSyncRef.current, 'entryEdit');
                          setEntryEditDraft(draft);
                        }}
                        onEntrySubmit={handleUpdateEntry}
                        onResetEntryDraft={handleResetAuthoringEntryDraft}
                        onEntryReleaseStatusChange={handleAuthoringEntryReleaseStatusChange}
                        onFieldFocus={handleAuthoringFieldFocus}
                        onFieldContextChange={handleAuthoringFieldContextChange}
                        onFieldContextSubmit={handleAuthoringFieldContextSubmit}
                        onResetFieldContextDraft={handleResetAuthoringFieldDraft}
                        onMappingDraftChange={handleAuthoringMappingDraftChange}
                        onResetMappingDraft={handleResetAuthoringMappingDraft}
                        onMappingSubmit={handleAuthoringMappingSubmit}
                        onSaveAll={() => {
                          void handleSaveAuthoringChanges();
                        }}
                        onInlineTranslationDirtyChange={handleInlineTranslationDirtyChange}
                        onInlineTranslationReleaseBlockerChange={
                          setInlineTranslationReleaseBlockerTargets
                        }
                        onInlineTranslationSavingChange={handleInlineTranslationSavingChange}
                        onInlineTranslationSaved={handleInlineTranslationSaved}
                        onRequestConfirmation={requestOperatorConfirmation}
                        onRetryEntryCompleteness={handleRetryEntryCompleteness}
                        onRefreshStaleTranslation={handleRefreshSelectedTranslation}
                        onRetrySourceCopyConflictRefresh={
                          handleRetryAuthoringSourceCopyConflictRefresh
                        }
                        onTargetLocaleTagsChange={handleTargetLocaleTagsChange}
                        onAddTargetLocales={handleAddTargetLocales}
                        onAddRequestedTargetLocale={handleAddRequestedTargetLocale}
                        onRetryTargetLocales={() => {
                          void refetchLocales();
                        }}
                        onReconnectTranslation={handleReconnectAuthoringTranslation}
                        onOpenAdvancedSetup={openAdvancedSetup}
                      />
                    )}
                  </>
                )}
              </>
            ) : (
              <CmsEmptyState />
            )}
          </section>
        </div>
      </div>
      <ConfirmModal
        open={pendingCmsConfirmation != null}
        title={pendingCmsConfirmation?.title ?? ''}
        body={pendingCmsConfirmation?.body ?? ''}
        confirmLabel={pendingCmsConfirmation?.confirmLabel ?? 'Continue'}
        cancelLabel={pendingCmsConfirmation?.cancelLabel ?? 'Cancel'}
        confirmVariant={pendingCmsConfirmation?.confirmVariant}
        confirmDisabled={pendingCmsConfirmation?.confirmDisabled}
        onCancel={handleCancelCmsConfirmation}
        onConfirm={handleConfirmCmsConfirmation}
      />
    </div>
  );
}

function queueAuthoringWorkspaceFocus(entryId: string | null) {
  if (entryId != null) {
    queueCopyBlockEditorScroll(entryId);
    return;
  }
  window.setTimeout(() => {
    const firstContentItemAction = document.querySelector<HTMLElement>(
      '.content-cms-admin-page__new-block .settings-input, .content-cms-admin-page__workspace-header .settings-button',
    );
    firstContentItemAction?.focus({ preventScroll: true });
  }, 0);
}

function queueCopyCollectionRailRecoveryFocus() {
  window.setTimeout(() => {
    const recoveredRailAction =
      document.querySelector<HTMLElement>(
        '.content-cms-admin-page__project-row[aria-current="true"]',
      ) ??
      document.querySelector<HTMLElement>('.content-cms-admin-page__project-row') ??
      document.querySelector<HTMLElement>('.content-cms-admin-page__clean-start .settings-input') ??
      document.querySelector<HTMLElement>('.content-cms-admin-page__search input') ??
      document.querySelector<HTMLElement>('.content-cms-admin-page__start-writing-space');
    recoveredRailAction?.focus({ preventScroll: true });
  }, 0);
}
