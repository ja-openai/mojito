import './admin-string-authoring-page.css';
import '../../components/filters/filter-chip.css';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { VirtualItem } from '@tanstack/react-virtual';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Navigate, useNavigate, useSearchParams } from 'react-router-dom';

import {
  type ApiStringAuthoringBranch,
  type ApiStringAuthoringBranchScope,
  type ApiStringAuthoringString,
  type ApiStringAuthoringUsedFilter,
  deleteStringAuthoringBranch,
  fetchStringAuthoringAssets,
  fetchStringAuthoringBranches,
  fetchStringAuthoringStrings,
  saveStringAuthoring,
  waitForStringAuthoringTask,
} from '../../api/string-authoring';
import {
  type FilterSection,
  MultiSectionFilterChip,
} from '../../components/filters/MultiSectionFilterChip';
import { FloatingStatusMessage } from '../../components/FloatingStatusMessage';
import { RepositorySingleSelect } from '../../components/RepositorySingleSelect';
import { ResizableMasterDetailLayout } from '../../components/ResizableMasterDetailLayout';
import { SearchControl } from '../../components/SearchControl';
import { SingleSelectDropdown } from '../../components/SingleSelectDropdown';
import { getRowHeightPx } from '../../components/virtual/getRowHeightPx';
import { useVirtualRows } from '../../components/virtual/useVirtualRows';
import { VirtualList } from '../../components/virtual/VirtualList';
import { useRepositories } from '../../hooks/useRepositories';
import { useUser } from '../../hooks/useUser';
import { formatLocalDateTime } from '../../utils/dateTime';
import { md5HexUtf8 } from '../../utils/md5';
import { useRepositorySelectionOptions } from '../../utils/repositorySelection';
import { downloadBlob } from '../workbench/workbench-import-export';

const AUTHORING_BRANCH_PREFIX = 'authoring/';
const DEFAULT_CLEANUP_DAYS = 7;
const DEFAULT_BRANCH_OPTION_VALUE = '__mojito_default_branch__';
const STRING_AUTHORING_LIST_WIDTH_STORAGE_KEY = 'string-authoring:string-list-width-percent';

type DraftString = {
  clientId: string;
  tmTextUnitId?: number | null;
  name: string;
  source: string;
  comment: string;
  pluralForm: string;
  pluralFormOther: string;
  generateId: boolean;
  used: boolean;
  createdDate?: string | null;
};

let nextDraftId = 1;

export function AdminStringAuthoringPage() {
  const user = useUser();
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const searchParamsString = searchParams.toString();
  const queryClient = useQueryClient();
  const isAdmin = user.role === 'ROLE_ADMIN';
  const [repositoryId, setRepositoryId] = useState<number | null>(() =>
    parsePositiveInteger(searchParams.get('repositoryId')),
  );
  const [branchScope, setBranchScope] = useState<ApiStringAuthoringBranchScope>('AUTHORING');
  const [usedFilter, setUsedFilter] = useState<ApiStringAuthoringUsedFilter>('USED');
  const [branchNameDraft, setBranchNameDraft] = useState(() =>
    normalizeBranchName(searchParams.get('branchName') ?? AUTHORING_BRANCH_PREFIX),
  );
  const [assetPath, setAssetPath] = useState(() => searchParams.get('assetPath') ?? '');
  const [cleanupDateDraft, setCleanupDateDraft] = useState(getDefaultCleanupDateInput);
  const [stringSearch, setStringSearch] = useState('');
  const [draftStrings, setDraftStrings] = useState<DraftString[]>([]);
  const [selectedClientId, setSelectedClientId] = useState<string | null>(null);
  const [statusMessage, setStatusMessage] = useState<string | null>(null);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const latestSaveTaskIdRef = useRef<number | null>(null);

  const repositoriesQuery = useRepositories();
  const repositoryOptions = useRepositorySelectionOptions(repositoriesQuery.data);

  useEffect(() => {
    if (repositoryId != null || !repositoryOptions.length) {
      return;
    }
    const firstRepository = repositoryOptions[0];
    if (!firstRepository) {
      return;
    }
    setRepositoryId(firstRepository.id);
  }, [repositoryId, repositoryOptions]);

  const assetsQuery = useQuery({
    queryKey: ['string-authoring-assets', repositoryId],
    queryFn: () => fetchStringAuthoringAssets(repositoryId as number, { limit: 500 }),
    enabled: repositoryId != null,
  });
  const assetSelectOptions = useMemo(
    () =>
      (assetsQuery.data ?? []).map((asset) => ({
        value: asset.path,
        label: asset.path,
      })),
    [assetsQuery.data],
  );

  const branchesQuery = useQuery({
    queryKey: ['string-authoring-branches', repositoryId, branchScope],
    queryFn: () => fetchStringAuthoringBranches(repositoryId as number, branchScope),
    enabled: repositoryId != null,
  });
  const branchSelectOptions = useMemo(
    () =>
      (branchesQuery.data ?? []).map((branch) => ({
        value: branch.name ?? DEFAULT_BRANCH_OPTION_VALUE,
        label: formatBranchLabel(branch),
        helper: formatBranchHelper(branch),
        disabled: branch.name == null,
      })),
    [branchesQuery.data],
  );

  useEffect(() => {
    if (assetPath.trim() || !assetsQuery.data || assetsQuery.data.length !== 1) {
      return;
    }
    const onlyAsset = assetsQuery.data[0];
    if (!onlyAsset) {
      return;
    }
    setAssetPath(onlyAsset.path);
  }, [assetPath, assetsQuery.data]);

  const branchName = normalizeBranchName(branchNameDraft);
  const selectedBranch =
    branchesQuery.data?.find((branch) => branch.name === branchName && branch.authoring) ?? null;
  const isExistingBranch = branchSelectOptions.some((option) => option.value === branchName);
  const hasAuthoringBranchName = isAuthoringBranchName(branchName);
  const isPrefixOnlyBranchName = branchName === AUTHORING_BRANCH_PREFIX;
  const effectiveAssetPath = assetPath.trim() || defaultAssetPath(branchName);
  const canLoadStrings =
    repositoryId != null && hasAuthoringBranchName && Boolean(effectiveAssetPath);

  useEffect(() => {
    setCleanupDateDraft(
      selectedBranch?.cleanupDate
        ? toDateInputValue(selectedBranch.cleanupDate)
        : getDefaultCleanupDateInput(),
    );
  }, [branchName, selectedBranch?.cleanupDate, selectedBranch?.id]);

  useEffect(() => {
    const nextSearchParams = new URLSearchParams();
    if (repositoryId != null) {
      nextSearchParams.set('repositoryId', String(repositoryId));
    }
    if (hasAuthoringBranchName) {
      nextSearchParams.set('branchName', branchName);
    }
    if (assetPath.trim()) {
      nextSearchParams.set('assetPath', assetPath.trim());
    }
    const nextSearchParamsString = nextSearchParams.toString();
    if (searchParamsString !== nextSearchParamsString) {
      setSearchParams(nextSearchParams, { replace: true });
    }
  }, [
    assetPath,
    branchName,
    hasAuthoringBranchName,
    repositoryId,
    searchParamsString,
    setSearchParams,
  ]);

  const stringsQuery = useQuery({
    queryKey: ['string-authoring', repositoryId, branchName, effectiveAssetPath, usedFilter],
    queryFn: () =>
      fetchStringAuthoringStrings(repositoryId as number, {
        branchName,
        assetPath: effectiveAssetPath,
        usedFilter,
        limit: 500,
      }),
    enabled: Boolean(canLoadStrings),
  });

  useEffect(() => {
    if (!stringsQuery.data) {
      return;
    }
    const rows = stringsQuery.data.map(toDraftString);
    setDraftStrings(rows);
    setSelectedClientId(rows[0]?.clientId ?? null);
    setStatusMessage(null);
    setErrorMessage(null);
  }, [stringsQuery.data]);

  const selectedString = useMemo(
    () => draftStrings.find((string) => string.clientId === selectedClientId) ?? null,
    [draftStrings, selectedClientId],
  );
  const selectedStringGeneratedId = selectedString ? getGeneratedStringId(selectedString) : '';
  const savedTextUnitIds = useMemo(() => getActiveSavedTextUnitIds(draftStrings), [draftStrings]);

  const monitorSaveTask = useCallback(
    async ({
      pollableTaskId,
      repositoryId,
      branchName,
      assetPath,
      stringCount,
    }: {
      pollableTaskId: number;
      repositoryId: number;
      branchName: string;
      assetPath: string;
      stringCount: number;
    }) => {
      try {
        const completedTask = await waitForStringAuthoringTask(pollableTaskId);
        if (completedTask.errorMessage) {
          throw new Error(completedTask.errorMessage);
        }
        if (latestSaveTaskIdRef.current !== pollableTaskId) {
          return;
        }
        setErrorMessage(null);
        setStatusMessage(`Saved ${stringCount} string${stringCount === 1 ? '' : 's'}.`);
        void queryClient.invalidateQueries({
          queryKey: ['string-authoring-branches', repositoryId],
        });
        void queryClient.invalidateQueries({
          queryKey: ['string-authoring', repositoryId, branchName, assetPath],
        });
      } catch (error) {
        if (latestSaveTaskIdRef.current !== pollableTaskId) {
          return;
        }
        setStatusMessage(null);
        setErrorMessage(error instanceof Error ? error.message : 'Failed to save source strings.');
      }
    },
    [queryClient],
  );

  const visibleStrings = useMemo(() => {
    const query = stringSearch.trim().toLowerCase();
    if (!query) {
      return draftStrings;
    }
    return draftStrings.filter((string) =>
      [string.name, string.source, string.comment].some((value) =>
        value.toLowerCase().includes(query),
      ),
    );
  }, [draftStrings, stringSearch]);
  const usedFilterLabel = formatUsedFilter(usedFilter);
  const hasAnyStrings = draftStrings.length > 0;
  const stringFilterSections: FilterSection[] = [
    {
      kind: 'radio',
      label: 'Status',
      options: [
        { value: 'USED', label: 'Active' },
        { value: 'UNUSED', label: 'Unused' },
        { value: 'ALL', label: 'All' },
      ],
      value: usedFilter,
      onChange: (value) => setUsedFilter(value as ApiStringAuthoringUsedFilter),
    },
  ];
  const hasStringFilters = Boolean(stringSearch.trim()) || usedFilter !== 'USED';
  const stringEmptyTitle = hasStringFilters ? 'No strings match' : 'No active strings';
  const stringEmptyMessage = hasStringFilters
    ? 'Try a different status or search term.'
    : 'Add a string or switch the status filter to see unused strings.';
  const estimateStringRowHeight = useCallback(
    () =>
      getRowHeightPx({
        cssVariable: '--string-authoring-string-row-height',
        defaultRem: 4.5,
      }),
    [],
  );
  const getStringRowKey = useCallback(
    (index: number) => visibleStrings[index]?.clientId ?? index,
    [visibleStrings],
  );
  const {
    scrollRef: stringListScrollRef,
    items: stringVirtualItems,
    totalSize: stringListTotalSize,
    measureElement: measureStringRow,
    scrollToIndex: scrollStringToIndex,
  } = useVirtualRows<HTMLDivElement>({
    count: visibleStrings.length,
    estimateSize: estimateStringRowHeight,
    getItemKey: getStringRowKey,
  });

  useEffect(() => {
    if (!selectedClientId) {
      return;
    }
    const selectedIndex = visibleStrings.findIndex(
      (string) => string.clientId === selectedClientId,
    );
    if (selectedIndex < 0) {
      return;
    }
    scrollStringToIndex(selectedIndex, { align: 'auto' });
  }, [scrollStringToIndex, selectedClientId, visibleStrings]);

  const saveMutation = useMutation({
    mutationFn: async () => {
      if (repositoryId == null) {
        throw new Error('Select a repository.');
      }
      if (!hasAuthoringBranchName) {
        throw new Error(
          `Authoring project name must start with ${AUTHORING_BRANCH_PREFIX} and include a name.`,
        );
      }
      const draftStringsForSave =
        usedFilter === 'ALL'
          ? draftStrings
          : mergeDraftStringsForSave(
              (
                await fetchStringAuthoringStrings(repositoryId, {
                  branchName,
                  assetPath: effectiveAssetPath,
                  usedFilter: 'ALL',
                  limit: 500,
                })
              ).map(toDraftString),
              draftStrings,
            );
      const stringsToSave = draftStringsForSave
        .filter((string) => string.used && hasDraftStringContent(string))
        .map((string) => ({
          name: string.generateId ? null : string.name.trim() || null,
          source: string.source,
          comment: string.comment.trim() || null,
          pluralForm: string.pluralForm.trim() || null,
          pluralFormOther: string.pluralFormOther.trim() || null,
          generateId: string.generateId,
        }));
      if (stringsToSave.some((string) => !string.name && !string.generateId)) {
        throw new Error('Add a string id, or explicitly choose md5 generation.');
      }
      if (stringsToSave.some((string) => string.generateId && !string.source.trim())) {
        throw new Error('Add source text before generating an md5 id.');
      }
      const saveRepositoryId = repositoryId;
      const saveBranchName = branchName;
      const saveAssetPath = effectiveAssetPath;
      const result = await saveStringAuthoring(saveRepositoryId, {
        assetPath: saveAssetPath,
        branchName: saveBranchName,
        cleanupDate: dateInputToIso(cleanupDateDraft),
        strings: stringsToSave,
      });
      return {
        ...result,
        repositoryId: saveRepositoryId,
        branchName: saveBranchName,
        assetPath: saveAssetPath,
      };
    },
    onMutate: () => {
      setStatusMessage('Saving source strings...');
      setErrorMessage(null);
    },
    onSuccess: (result) => {
      const pollableTaskId = result.pollableTask.id;
      latestSaveTaskIdRef.current = pollableTaskId;
      setStatusMessage(
        `Save started for ${result.stringCount} string${result.stringCount === 1 ? '' : 's'}.`,
      );
      void queryClient.invalidateQueries({
        queryKey: ['string-authoring-branches', result.repositoryId],
      });
      void monitorSaveTask({
        pollableTaskId,
        repositoryId: result.repositoryId,
        branchName: result.branchName,
        assetPath: result.assetPath,
        stringCount: result.stringCount,
      });
    },
    onError: (error) => {
      latestSaveTaskIdRef.current = null;
      setStatusMessage(null);
      setErrorMessage(error instanceof Error ? error.message : 'Failed to save source strings.');
    },
  });

  const deleteBranchMutation = useMutation({
    mutationFn: async () => {
      if (repositoryId == null) {
        throw new Error('Select a repository.');
      }
      if (!selectedBranch) {
        throw new Error('Select an existing authoring project to delete.');
      }
      return deleteStringAuthoringBranch(repositoryId, selectedBranch.id, selectedBranch.name);
    },
    onMutate: () => {
      setStatusMessage('Deleting authoring project...');
      setErrorMessage(null);
    },
    onSuccess: async (result) => {
      setBranchNameDraft(AUTHORING_BRANCH_PREFIX);
      setAssetPath('');
      setDraftStrings([]);
      setSelectedClientId(null);
      setStatusMessage(`Deleted ${result.name}.`);
      await queryClient.invalidateQueries({
        queryKey: ['string-authoring-branches', repositoryId],
      });
      await queryClient.invalidateQueries({
        queryKey: ['string-authoring'],
      });
    },
    onError: (error) => {
      setStatusMessage(null);
      setErrorMessage(
        error instanceof Error ? error.message : 'Failed to delete authoring project.',
      );
    },
  });

  if (!isAdmin) {
    return <Navigate to="/settings/me" replace />;
  }

  const addString = () => {
    const nextString: DraftString = {
      clientId: createDraftClientId(),
      name: '',
      source: '',
      comment: '',
      pluralForm: '',
      pluralFormOther: '',
      generateId: false,
      used: true,
    };
    setDraftStrings((strings) => [...strings, nextString]);
    setSelectedClientId(nextString.clientId);
    setStatusMessage(null);
  };

  const deleteSelectedString = () => {
    if (!selectedString) {
      return;
    }
    setDraftStrings((strings) => {
      const nextStrings = strings.filter((string) => string.clientId !== selectedString.clientId);
      setSelectedClientId(nextStrings[0]?.clientId ?? null);
      return nextStrings;
    });
    setStatusMessage(null);
  };

  const toggleSelectedStringStatus = () => {
    if (!selectedString) {
      return;
    }
    if (selectedString.tmTextUnitId == null) {
      deleteSelectedString();
      return;
    }
    updateSelectedString({ used: !selectedString.used });
    setStatusMessage(null);
    setErrorMessage(null);
  };

  const updateSelectedString = (updates: Partial<DraftString>) => {
    if (!selectedString) {
      return;
    }
    setDraftStrings((strings) =>
      strings.map((string) =>
        string.clientId === selectedString.clientId ? { ...string, ...updates } : string,
      ),
    );
  };

  const selectedRepository = repositoriesQuery.data?.find((repo) => repo.id === repositoryId);

  const openTextUnitInWorkbench = (tmTextUnitId: number) => {
    if (repositoryId == null) {
      return;
    }
    const params = new URLSearchParams();
    params.set('tmTextUnitId', String(tmTextUnitId));
    params.set('repo', String(repositoryId));
    void navigate(`/workbench?${params.toString()}`);
  };

  const deleteSelectedBranch = () => {
    if (!selectedBranch) {
      return;
    }
    if (
      !globalThis.confirm(
        `Delete ${selectedBranch.name}? This removes the temporary authoring project and its assets.`,
      )
    ) {
      return;
    }
    deleteBranchMutation.mutate();
  };

  const sendForReview = () => {
    if (!savedTextUnitIds.length) {
      setStatusMessage(null);
      setErrorMessage('Save strings before sending them for review.');
      return;
    }
    const repositoryName = selectedRepository?.name ?? 'String authoring';
    const collectionName = [branchName, effectiveAssetPath].filter(Boolean).join(' · ');
    void navigate('/review-projects/new', {
      state: {
        sourceMode: 'TEXT_UNITS',
        tmTextUnitIds: savedTextUnitIds,
        collectionName: collectionName || 'Authored strings',
        defaultName: `${repositoryName} string authoring review`,
        statusFilter: 'ALL',
      },
    });
  };

  const downloadDeveloperJson = async () => {
    if (!hasAuthoringBranchName) {
      setStatusMessage(null);
      setErrorMessage(
        `Authoring project name must start with ${AUTHORING_BRANCH_PREFIX} and include a name.`,
      );
      return;
    }
    let stringsForExport = draftStrings;
    try {
      if (usedFilter !== 'ALL' && repositoryId != null) {
        stringsForExport = mergeDraftStringsForSave(
          (
            await fetchStringAuthoringStrings(repositoryId, {
              branchName,
              assetPath: effectiveAssetPath,
              usedFilter: 'ALL',
              limit: 500,
            })
          ).map(toDraftString),
          draftStrings,
        );
      }
    } catch (error) {
      setStatusMessage(null);
      setErrorMessage(error instanceof Error ? error.message : 'Failed to prepare JSON download.');
      return;
    }
    const strings = stringsForExport.filter(
      (string) => string.used && hasDraftStringContent(string),
    );
    if (!strings.length) {
      setStatusMessage(null);
      setErrorMessage('Add or restore at least one active string before downloading JSON.');
      return;
    }
    if (strings.some((string) => string.generateId && !getGeneratedStringId(string))) {
      setStatusMessage(null);
      setErrorMessage('Add source text before downloading JSON so generated ids are included.');
      return;
    }
    if (strings.some((string) => !getEffectiveStringId(string))) {
      setStatusMessage(null);
      setErrorMessage('Add a string id, or explicitly choose md5 generation.');
      return;
    }

    const payload = buildDeveloperJsonPayload({
      repositoryId,
      repositoryName: selectedRepository?.name ?? null,
      branchName,
      assetPath: effectiveAssetPath,
      strings,
    });
    downloadBlob(
      new Blob([`${JSON.stringify(payload, null, 2)}\n`], {
        type: 'application/json;charset=utf-8',
      }),
      buildDeveloperJsonFilename(selectedRepository?.name, branchName),
    );
    setStatusMessage(`Downloaded ${strings.length} string${strings.length === 1 ? '' : 's'}.`);
    setErrorMessage(null);
  };

  const copyDeveloperLink = async () => {
    if (repositoryId == null) {
      setStatusMessage(null);
      setErrorMessage('Select a repository before copying a link.');
      return;
    }
    if (!hasAuthoringBranchName) {
      setStatusMessage(null);
      setErrorMessage(
        `Authoring project name must start with ${AUTHORING_BRANCH_PREFIX} and include a name.`,
      );
      return;
    }

    const href = buildDeveloperDeepLink({
      repositoryId,
      branchName,
      assetPath: effectiveAssetPath,
    });

    try {
      if (!navigator.clipboard?.writeText) {
        throw new Error('Clipboard API is unavailable.');
      }
      await navigator.clipboard.writeText(href);
      setStatusMessage('Copied developer link.');
      setErrorMessage(null);
    } catch {
      setStatusMessage(null);
      setErrorMessage(`Copy failed. Developer link: ${href}`);
    }
  };

  const handleRepositoryChange = (nextRepositoryId: number | null) => {
    setRepositoryId(nextRepositoryId);
    setBranchNameDraft(AUTHORING_BRANCH_PREFIX);
    setAssetPath('');
    setCleanupDateDraft(getDefaultCleanupDateInput());
    setDraftStrings([]);
    setSelectedClientId(null);
    setStatusMessage(null);
    setErrorMessage(null);
  };

  return (
    <div className="string-authoring-page">
      <main className="string-authoring-page__workspace">
        <div className="string-authoring-page__topbar">
          <div className="string-authoring-page__header-actions" aria-label="String actions">
            <button
              type="button"
              className="settings-button"
              onClick={() => void downloadDeveloperJson()}
              disabled={saveMutation.isPending || !draftStrings.some(hasDraftStringContent)}
            >
              Download JSON
            </button>
            <button
              type="button"
              className="settings-button"
              onClick={() => void copyDeveloperLink()}
              disabled={saveMutation.isPending || repositoryId == null || !hasAuthoringBranchName}
            >
              Copy link
            </button>
            <button
              type="button"
              className="settings-button"
              onClick={sendForReview}
              disabled={
                saveMutation.isPending || stringsQuery.isFetching || !savedTextUnitIds.length
              }
            >
              Send for review
            </button>
          </div>
        </div>

        <section className="string-authoring-page__setup" aria-label="String authoring setup">
          <div className="settings-field">
            <span className="settings-field__label">Repository</span>
            <RepositorySingleSelect
              options={repositoryOptions}
              value={repositoryId}
              onChange={handleRepositoryChange}
              placeholder={
                repositoriesQuery.isLoading
                  ? 'Loading repositories...'
                  : repositoriesQuery.isError
                    ? 'Unable to load repositories'
                    : 'Select repository'
              }
              disabled={repositoriesQuery.isLoading}
              className="string-authoring-page__repository-select"
            />
            {repositoriesQuery.isError ? (
              <span className="settings-hint is-error">Unable to load repositories.</span>
            ) : null}
          </div>

          <div className="settings-field string-authoring-page__wide-field">
            <span className="settings-field__label">Authoring project</span>
            <span className="settings-field__row">
              <SingleSelectDropdown<string>
                label="Authoring project"
                options={branchSelectOptions}
                value={isExistingBranch ? branchName : null}
                onChange={(nextBranchName) => {
                  if (nextBranchName && nextBranchName !== DEFAULT_BRANCH_OPTION_VALUE) {
                    setBranchNameDraft(nextBranchName);
                  }
                }}
                buttonSummary={isExistingBranch ? undefined : branchName}
                placeholder={
                  branchesQuery.isLoading
                    ? 'Loading projects...'
                    : branchesQuery.isError
                      ? 'Unable to load projects'
                      : 'Select or create project'
                }
                disabled={repositoryId == null || branchesQuery.isLoading}
                className="string-authoring-page__branch-select"
                searchPlaceholder="Search or create project"
                noResultsLabel="No matching projects"
                footerAction={{
                  label:
                    branchScope === 'AUTHORING'
                      ? 'Show all projects'
                      : 'Show authoring projects only',
                  onClick: () =>
                    setBranchScope((currentScope) =>
                      currentScope === 'AUTHORING' ? 'ALL' : 'AUTHORING',
                    ),
                  closeOnClick: true,
                }}
                customValueLabel={(query) => `Use ${toAuthoringBranchName(query)}`}
                onCustomValue={(query) => setBranchNameDraft(toAuthoringBranchName(query))}
              />
              <button
                type="button"
                className="settings-button"
                onClick={deleteSelectedBranch}
                disabled={
                  saveMutation.isPending ||
                  deleteBranchMutation.isPending ||
                  stringsQuery.isFetching ||
                  !selectedBranch
                }
              >
                Delete project
              </button>
            </span>
            {branchNameDraft && !isPrefixOnlyBranchName && !hasAuthoringBranchName ? (
              <span className="settings-hint is-error">
                Use a {AUTHORING_BRANCH_PREFIX} project to save strings from this page.
              </span>
            ) : null}
          </div>

          <label className="settings-field string-authoring-page__cleanup-field">
            <span className="settings-field__label">Cleanup date</span>
            <input
              type="date"
              className="settings-input"
              value={cleanupDateDraft}
              disabled={!hasAuthoringBranchName}
              onChange={(event) => setCleanupDateDraft(event.target.value)}
            />
          </label>

          <div className="settings-field string-authoring-page__asset-field">
            <span className="settings-field__label">Asset path</span>
            <SingleSelectDropdown<string>
              label="Asset path"
              options={assetSelectOptions}
              value={
                assetSelectOptions.some((option) => option.value === assetPath.trim())
                  ? assetPath.trim()
                  : null
              }
              onChange={(nextAssetPath) => {
                if (nextAssetPath) {
                  setAssetPath(nextAssetPath);
                }
              }}
              buttonSummary={assetPath.trim() || effectiveAssetPath || undefined}
              placeholder={
                assetsQuery.isLoading
                  ? 'Loading assets...'
                  : assetsQuery.isError
                    ? 'Unable to load assets'
                    : 'Select existing asset'
              }
              disabled={repositoryId == null || assetsQuery.isLoading}
              className="string-authoring-page__asset-select"
              searchPlaceholder="Search or type asset path"
              noResultsLabel="No matching assets"
              footerAction={{
                label: `Use generated path${effectiveAssetPath ? `: ${effectiveAssetPath}` : ''}`,
                onClick: () => setAssetPath(''),
                disabled: !hasAuthoringBranchName,
                closeOnClick: true,
              }}
              customValueLabel={(query) => `Use ${query.trim()}`}
              onCustomValue={(query) => setAssetPath(query.trim())}
            />
            {assetsQuery.isError ? (
              <span className="settings-hint is-error">Unable to load assets.</span>
            ) : null}
          </div>
        </section>

        <FloatingStatusMessage
          kind={errorMessage ? 'error' : 'success'}
          message={errorMessage ?? statusMessage}
        />

        <ResizableMasterDetailLayout
          className="string-authoring-page__editor"
          storageKey={STRING_AUTHORING_LIST_WIDTH_STORAGE_KEY}
          sidebarLabel="Authored strings"
          detailLabel="Selected string"
          resizeLabel="Resize authored string list"
          sidebarClassName="string-authoring-page__list-pane"
          detailClassName="string-authoring-page__detail-pane"
          sidebar={
            <>
              <div className="string-authoring-page__list-toolbar">
                <SearchControl
                  value={stringSearch}
                  onChange={setStringSearch}
                  placeholder="Search strings"
                  inputAriaLabel="Search source strings"
                  className="string-authoring-page__list-search"
                />
                <MultiSectionFilterChip
                  sections={stringFilterSections}
                  align="left"
                  summary={usedFilterLabel}
                  ariaLabel="Filter source strings"
                  className="string-authoring-page__filter-chip"
                  classNames={{
                    button: 'string-authoring-page__filter-chip-button',
                    panel: 'string-authoring-page__filter-chip-panel',
                  }}
                  disabled={saveMutation.isPending || stringsQuery.isFetching}
                />
                {hasAnyStrings ? (
                  <button
                    type="button"
                    className="settings-button settings-button--primary"
                    onClick={addString}
                  >
                    Add string
                  </button>
                ) : null}
              </div>
              <div className="string-authoring-page__string-list">
                {stringsQuery.isLoading ? (
                  <div className="string-authoring-page__empty">Loading strings...</div>
                ) : visibleStrings.length ? (
                  <VirtualList
                    scrollRef={stringListScrollRef}
                    items={stringVirtualItems}
                    totalSize={stringListTotalSize}
                    renderRow={(virtualItem: VirtualItem) => {
                      const string = visibleStrings[virtualItem.index];
                      if (!string) {
                        return null;
                      }
                      return {
                        key: virtualItem.key,
                        props: {
                          ref: measureStringRow,
                        },
                        content: (
                          <StringAuthoringListRow
                            string={string}
                            isActive={string.clientId === selectedClientId}
                            onSelect={() => setSelectedClientId(string.clientId)}
                          />
                        ),
                      };
                    }}
                  />
                ) : (
                  <div className="string-authoring-page__list-empty">
                    <strong>{stringEmptyTitle}</strong>
                    <span>
                      {hasAuthoringBranchName
                        ? stringEmptyMessage
                        : 'Select or create an authoring project.'}
                    </span>
                  </div>
                )}
              </div>
            </>
          }
          detail={
            <>
              {selectedString ? (
                <>
                  <div className="string-authoring-page__detail-header">
                    <div>
                      <div className="string-authoring-page__eyebrow">
                        {selectedString.tmTextUnitId ? (
                          <span className="string-authoring-page__text-unit-meta">
                            <span>Text unit {selectedString.tmTextUnitId}</span>
                            <button
                              type="button"
                              className="string-authoring-page__text-unit-action"
                              onClick={() =>
                                selectedString.tmTextUnitId
                                  ? openTextUnitInWorkbench(selectedString.tmTextUnitId)
                                  : undefined
                              }
                            >
                              Show in Workbench
                            </button>
                          </span>
                        ) : (
                          'New string'
                        )}
                      </div>
                      <h2>{getDraftStringTitle(selectedString)}</h2>
                      <div className="string-authoring-page__detail-meta">
                        {selectedString.createdDate
                          ? `Created ${formatLocalDateTime(selectedString.createdDate)}`
                          : `${selectedRepository?.name ?? 'Repository'} · ${
                              branchName || 'No project'
                            }`}
                      </div>
                    </div>
                  </div>

                  <div className="string-authoring-page__editor-grid">
                    <div className="settings-field">
                      <span className="settings-field__label">String id</span>
                      <input
                        className="settings-input"
                        value={
                          selectedString.generateId
                            ? selectedStringGeneratedId
                            : selectedString.name
                        }
                        placeholder={
                          selectedString.generateId
                            ? 'Add source text to generate id'
                            : 'e.g. checkout.submit_label'
                        }
                        disabled={selectedString.generateId}
                        onChange={(event) =>
                          updateSelectedString({ name: event.target.value, generateId: false })
                        }
                      />
                      <label className="string-authoring-page__checkbox">
                        <input
                          type="checkbox"
                          checked={selectedString.generateId}
                          onChange={(event) =>
                            updateSelectedString({
                              generateId: event.target.checked,
                              ...(event.target.checked ? { name: '' } : {}),
                            })
                          }
                        />
                        <span>Generate md5 id from source + description</span>
                      </label>
                      <span className="settings-hint">
                        {selectedString.generateId
                          ? selectedStringGeneratedId
                            ? 'Generated from source + description. Updates as you edit.'
                            : 'Add source text to generate the md5 id.'
                          : 'Use a stable developer-owned id unless this string must match a source-derived md5 id.'}
                      </span>
                    </div>
                    <label className="settings-field">
                      <span className="settings-field__label">English source</span>
                      <textarea
                        className="settings-input string-authoring-page__textarea"
                        value={selectedString.source}
                        onChange={(event) => updateSelectedString({ source: event.target.value })}
                      />
                    </label>
                    <label className="settings-field">
                      <span className="settings-field__label">Description</span>
                      <textarea
                        className="settings-input string-authoring-page__textarea string-authoring-page__textarea--compact"
                        value={selectedString.comment}
                        onChange={(event) => updateSelectedString({ comment: event.target.value })}
                      />
                    </label>
                  </div>

                  <div className="string-authoring-page__actions">
                    <button
                      type="button"
                      className="settings-button settings-button--primary"
                      onClick={() => saveMutation.mutate()}
                      disabled={
                        saveMutation.isPending ||
                        repositoryId == null ||
                        !hasAuthoringBranchName ||
                        !draftStrings.length
                      }
                    >
                      {saveMutation.isPending ? 'Saving...' : 'Save'}
                    </button>
                    <button
                      type="button"
                      className="settings-button"
                      onClick={toggleSelectedStringStatus}
                    >
                      {selectedString.tmTextUnitId == null
                        ? 'Remove'
                        : selectedString.used
                          ? 'Remove'
                          : 'Restore'}
                    </button>
                  </div>
                </>
              ) : (
                <div className="string-authoring-page__empty-state">
                  <h2>{hasAnyStrings ? 'No string selected' : 'Create your first string'}</h2>
                  <p>
                    {hasAnyStrings
                      ? 'Select a string from the list.'
                      : 'Start authoring source text for this project.'}
                  </p>
                  {!hasAnyStrings ? (
                    <button
                      type="button"
                      className="settings-button settings-button--primary"
                      onClick={addString}
                    >
                      Create string
                    </button>
                  ) : null}
                </div>
              )}
            </>
          }
        />
      </main>
    </div>
  );
}

function StringAuthoringListRow({
  string,
  isActive,
  onSelect,
}: {
  string: DraftString;
  isActive: boolean;
  onSelect: () => void;
}) {
  return (
    <button
      type="button"
      className={`string-authoring-page__string-row${isActive ? ' is-active' : ''}`}
      onClick={onSelect}
    >
      <span className="string-authoring-page__string-row-main">
        <span className="string-authoring-page__string-id">{getDraftStringTitle(string)}</span>
        <span className="string-authoring-page__source-preview">
          {string.source || 'No source text'}
        </span>
      </span>
      <span className="string-authoring-page__string-row-meta">
        {!string.used ? (
          <span className="string-authoring-page__used-pill is-unused">unused</span>
        ) : null}
        {string.tmTextUnitId == null ? (
          <span className="string-authoring-page__draft-pill">new</span>
        ) : null}
      </span>
    </button>
  );
}

function toDraftString(row: ApiStringAuthoringString): DraftString {
  return {
    clientId: createDraftClientId(row.tmTextUnitId ?? undefined),
    tmTextUnitId: row.tmTextUnitId,
    name: row.name ?? '',
    source: row.source ?? '',
    comment: row.comment ?? '',
    pluralForm: row.pluralForm ?? '',
    pluralFormOther: row.pluralFormOther ?? '',
    generateId: false,
    used: row.used ?? true,
    createdDate: row.createdDate,
  };
}

function getDraftStringTitle(string: DraftString): string {
  if (string.name.trim()) {
    return string.name;
  }
  if (string.generateId) {
    return getGeneratedStringId(string) || 'Generated md5 id';
  }
  return 'Untitled string';
}

function createDraftClientId(stableId?: number) {
  return stableId ? `tu-${stableId}` : `draft-${nextDraftId++}`;
}

function mergeDraftStringsForSave(baseStrings: DraftString[], editedStrings: DraftString[]) {
  const merged = [...baseStrings];
  editedStrings.forEach((editedString) => {
    const existingIndex = findMatchingDraftStringIndex(merged, editedString);
    if (existingIndex >= 0) {
      merged[existingIndex] = editedString;
      return;
    }
    merged.push(editedString);
  });
  return merged;
}

function findMatchingDraftStringIndex(strings: DraftString[], string: DraftString) {
  if (string.tmTextUnitId != null) {
    return strings.findIndex((candidate) => candidate.tmTextUnitId === string.tmTextUnitId);
  }
  const trimmedName = string.name.trim();
  if (!trimmedName) {
    return -1;
  }
  return strings.findIndex(
    (candidate) => candidate.tmTextUnitId == null && candidate.name.trim() === trimmedName,
  );
}

function getActiveSavedTextUnitIds(strings: DraftString[]): number[] {
  return Array.from(
    new Set(
      strings
        .filter((string) => string.used)
        .map((string) => string.tmTextUnitId)
        .filter((id): id is number => typeof id === 'number' && Number.isSafeInteger(id) && id > 0),
    ),
  ).sort((left, right) => left - right);
}

function buildDeveloperJsonPayload({
  repositoryId,
  repositoryName,
  branchName,
  assetPath,
  strings,
}: {
  repositoryId: number | null;
  repositoryName: string | null;
  branchName: string;
  assetPath: string;
  strings: DraftString[];
}) {
  return {
    repository: {
      id: repositoryId,
      name: repositoryName,
    },
    branchName,
    assetPath,
    strings: strings.map((string) => ({
      id: getEffectiveStringId(string),
      source: string.source,
      description: string.comment.trim() || null,
      pluralForm: string.pluralForm.trim() || null,
      pluralFormOther: string.pluralFormOther.trim() || null,
      tmTextUnitId: string.tmTextUnitId ?? null,
    })),
  };
}

function getEffectiveStringId(string: DraftString): string {
  return string.generateId ? getGeneratedStringId(string) : string.name.trim();
}

function getGeneratedStringId(string: DraftString): string {
  if (!string.generateId || !string.source.trim()) {
    return '';
  }
  return md5HexUtf8(string.source.normalize('NFC') + string.comment.trim().normalize('NFC'));
}

function buildDeveloperJsonFilename(repositoryName: string | null | undefined, branchName: string) {
  const repositorySlug = slugify(repositoryName ?? 'string-authoring');
  const branchSlug = slugify(branchName.replace(AUTHORING_BRANCH_PREFIX, ''));
  return `${repositorySlug}-${branchSlug}-strings.json`;
}

function buildDeveloperDeepLink({
  repositoryId,
  branchName,
  assetPath,
}: {
  repositoryId: number;
  branchName: string;
  assetPath: string;
}): string {
  const url = new URL('/string-authoring', window.location.origin);
  url.searchParams.set('repositoryId', String(repositoryId));
  url.searchParams.set('branchName', branchName);
  if (assetPath) {
    url.searchParams.set('assetPath', assetPath);
  }
  return url.toString();
}

function formatUsedFilter(filter: ApiStringAuthoringUsedFilter): string {
  switch (filter) {
    case 'USED':
      return 'Active';
    case 'UNUSED':
      return 'Unused';
    case 'ALL':
      return 'All';
  }
}

function formatBranchLabel(branch: ApiStringAuthoringBranch): string {
  return branch.name ?? 'Default branch';
}

function formatBranchHelper(branch: ApiStringAuthoringBranch): string | undefined {
  const details = [];
  if (branch.name == null) {
    details.push('Primary branch');
  } else if (!branch.authoring) {
    details.push('Not authoring');
  }
  if (branch.createdDate) {
    details.push(`Created ${formatLocalDateTime(branch.createdDate)}`);
  }
  if (branch.cleanupDate) {
    details.push(`Cleanup ${formatCleanupDate(branch.cleanupDate)}`);
  }
  return details.length ? details.join(' · ') : undefined;
}

function toAuthoringBranchName(value: string): string {
  const trimmed = value.trim();
  if (!trimmed) {
    return AUTHORING_BRANCH_PREFIX;
  }
  if (trimmed.startsWith(AUTHORING_BRANCH_PREFIX)) {
    return trimmed;
  }
  return `${AUTHORING_BRANCH_PREFIX}${slugify(trimmed)}`;
}

function normalizeBranchName(value: string): string {
  const trimmed = value.trim();
  if (!trimmed) {
    return AUTHORING_BRANCH_PREFIX;
  }
  if (trimmed.startsWith(AUTHORING_BRANCH_PREFIX)) {
    return trimmed;
  }
  if (trimmed.includes('/')) {
    return trimmed;
  }
  return `${AUTHORING_BRANCH_PREFIX}${slugify(trimmed)}`;
}

function parsePositiveInteger(value: string | null): number | null {
  if (!value) {
    return null;
  }
  const parsed = Number(value);
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : null;
}

function getDefaultCleanupDateInput(): string {
  const date = new Date();
  date.setDate(date.getDate() + DEFAULT_CLEANUP_DAYS);
  return toDateInputValue(date);
}

function toDateInputValue(value: string | Date | null | undefined): string {
  if (!value) {
    return '';
  }
  if (typeof value === 'string' && /^\d{4}-\d{2}-\d{2}/.test(value)) {
    return value.slice(0, 10);
  }
  const date = value instanceof Date ? value : new Date(value);
  if (Number.isNaN(date.getTime())) {
    return '';
  }
  const pad = (number: number) => String(number).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

function dateInputToIso(value: string): string | null {
  if (!value) {
    return null;
  }
  return new Date(`${value}T00:00:00.000Z`).toISOString();
}

function formatCleanupDate(value: string | null | undefined): string {
  return toDateInputValue(value) || '—';
}

function defaultAssetPath(branchName: string): string {
  if (!isAuthoringBranchName(branchName)) {
    return '';
  }
  return `${branchName}/strings.json`;
}

function hasDraftStringContent(string: DraftString): boolean {
  const hasTextContent = [
    string.name,
    string.source,
    string.comment,
    string.pluralForm,
    string.pluralFormOther,
  ].some((value) => value.trim());
  return string.generateId || hasTextContent;
}

function isAuthoringBranchName(value: string): boolean {
  return value.startsWith(AUTHORING_BRANCH_PREFIX) && value.length > AUTHORING_BRANCH_PREFIX.length;
}

function slugify(value: string): string {
  return (
    value
      .trim()
      .toLowerCase()
      .replace(/[^a-z0-9._-]+/g, '-')
      .replace(/^-+|-+$/g, '') || 'strings'
  );
}
