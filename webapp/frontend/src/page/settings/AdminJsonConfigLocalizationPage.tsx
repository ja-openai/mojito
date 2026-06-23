import '../../components/chip-dropdown.css';
import '../../components/filters/filter-chip.css';
import './settings-page.css';
import './admin-json-config-localization-page.css';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { VirtualItem } from '@tanstack/react-virtual';
import type { ReactNode } from 'react';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Navigate, useNavigate, useParams, useSearchParams } from 'react-router-dom';

import {
  fetchAiTranslateConfig,
  translateRepository,
  waitForPollableTaskToFinish,
} from '../../api/ai-translate';
import {
  type ApiJsonConfigExtractForRepositoryResult,
  type ApiJsonConfigLocalization,
  type ApiJsonConfigLocalizationRun,
  type ApiJsonConfigString,
  createJsonConfigLocalization,
  deleteJsonConfigLocalizationSetup,
  detectJsonConfigMapping,
  exportJsonConfigForRepository,
  exportJsonConfigForSetup,
  extractJsonConfigForRepository,
  extractJsonConfigForSetup,
  extractJsonConfigStrings,
  fetchJsonConfigLocalizationById,
  fetchJsonConfigLocalizationRuns,
  fetchJsonConfigLocalizations,
  fetchJsonConfigLocalizationSetupsByRepository,
  pullJsonConfigFromStatsig,
  pullJsonConfigFromStatsigForSetup,
  pushJsonConfigToStatsigForSetup,
  updateJsonConfigLocalization,
} from '../../api/json-config-localization';
import type { ApiRepository } from '../../api/repositories';
import { fetchReviewAutomationSchedulePreview } from '../../api/review-automations';
import { searchTextUnits } from '../../api/text-units';
import {
  type FilterSection,
  MultiSectionFilterChip,
} from '../../components/filters/MultiSectionFilterChip';
import { JsonCodeEditor, type JsonCodeEditorHandle } from '../../components/JsonCodeEditor';
import { Modal } from '../../components/Modal';
import { RepositoryMultiSelect } from '../../components/RepositoryMultiSelect';
import { ResizableMasterDetailLayout } from '../../components/ResizableMasterDetailLayout';
import { SearchControl } from '../../components/SearchControl';
import { getRowHeightPx } from '../../components/virtual/getRowHeightPx';
import { useVirtualRows } from '../../components/virtual/useVirtualRows';
import { VirtualList } from '../../components/virtual/VirtualList';
import { useDebouncedValue } from '../../hooks/useDebouncedValue';
import { useRepositories } from '../../hooks/useRepositories';
import { useUser } from '../../hooks/useUser';
import { getStandardDateQuickRanges } from '../../utils/dateQuickRanges';
import { getNonRootRepositoryLocaleTags } from '../../utils/repositoryLocales';
import { formatReviewAutomationSchedule } from '../../utils/reviewAutomationSchedule';
import { buildZipFile, downloadBlob } from '../workbench/workbench-import-export';
import {
  appendStatsigSourceConfigEntry,
  buildJsonConfigLocalizationExport,
  buildJsonConfigLocalizationFilename,
  buildJsonConfigLocalizationLocaleFileExport,
  buildJsonConfigLocalizationLocaleFilesFilename,
  buildJsonConfigLocalizationReadiness,
  buildStatsigSourceConfigExport,
  createEmptyDraftString,
  DEFAULT_JSON_CONFIG_LOCALIZATION_ASSET_PATH,
  DEFAULT_STATSIG_SOURCE_CONFIG_PROFILE,
  getStatsigSourceConfigStringIds,
  inferStatsigSourceConfigProfileFromText,
  type JsonConfigLocalizationDraftString,
  type JsonRecord,
  mergeExtractedDraftStrings,
  normalizeOutputLocaleMapping,
  normalizeSourceStringId,
  type OutputLocaleMapping,
  parseJsonRecord,
  parseOutputLocaleMappingSpec,
  sortJsonConfigLocalizationDraftStrings,
  type StatsigSourceConfigProfile,
  toJsonConfigLocalizationDraftStrings,
} from './jsonConfigLocalization';
import { SettingsSubpageHeader } from './SettingsSubpageHeader';

type StatusNotice = {
  kind: 'success' | 'error' | 'info';
  message: string;
  spinning?: boolean;
};

type UsedFilter = 'USED' | 'UNUSED' | 'ALL';
type RepositorySetupFilter = 'CONFIGURED' | 'NOT_CONFIGURED' | 'ALL';
type TranslateScope = 'selected' | 'active';
type ExportFormat = 'locale-map' | 'locale-files' | 'localized-config';
type JsonConfigProvider = 'GENERIC_JSON' | 'STATSIG';
type JsonConfigLocalizationTab =
  | 'SYNC'
  | 'CONFIG'
  | 'STRINGS'
  | 'TRANSLATIONS'
  | 'EXPORT'
  | 'SETUP';
type StatsigSyncOptions = {
  pull: boolean;
  extract: boolean;
  translate: boolean;
  merge: boolean;
  saveConfig: boolean;
  push: boolean;
};
type StatsigSyncStepId = 'PULL' | 'EXTRACT' | 'TRANSLATE' | 'MERGE' | 'SAVE_CONFIG' | 'PUSH';
type StatsigSyncStepStatus = 'PENDING' | 'RUNNING' | 'DONE' | 'SKIPPED' | 'ERROR';
type StatsigSyncProgress = {
  steps: Record<StatsigSyncStepId, StatsigSyncStepStatus>;
  message: string | null;
};
type SaveDraftStringsOptions = {
  assetPath?: string;
  providerConfigId?: string;
  schemaJson?: string;
  sourceConfigJson?: string;
  profile?: StatsigSourceConfigProfile;
  expectedLastModifiedDate?: string | null;
};
type ConfigEditorFocusTarget = {
  needle: string;
  offset: number;
};
type StoredStatsigDraft = {
  provider: JsonConfigProvider;
  providerConfigId: string;
  schemaText: string;
  sourceConfigText: string;
  profile: StatsigSourceConfigProfile;
  outputLocaleMapping: OutputLocaleMapping;
  automationEnabled: boolean;
  automationCronExpression: string;
  automationTimeZone: string;
  automationOptions: StatsigSyncOptions;
};
type RepositoryPickerRow = {
  repositoryId: number;
  repositoryName: string;
  sourceLocaleTag?: string | null;
  targetLocaleCount: number;
  assetPath: string;
  config: ApiJsonConfigLocalization | null;
};
type JsonConfigSchemaPreset = {
  id: string;
  label: string;
  description: string;
  schema: JsonRecord;
  profile: StatsigSourceConfigProfile;
};
type JsonConfigSchemaOption = {
  id: string;
  label: string;
  description: string;
  schemaText: string;
  profile: StatsigSourceConfigProfile;
};

const SOURCE_SEARCH_LIMIT = 500;
const TARGET_SEARCH_LIMIT = 5000;
const AI_TRANSLATE_TIMEOUT_MS = 5 * 60 * 1000;
const STRING_LIST_WIDTH_STORAGE_KEY = 'json-config-localization:string-list-width-percent';
const JSON_CONFIG_LOCALIZATION_TABS: Array<{ id: JsonConfigLocalizationTab; label: string }> = [
  { id: 'CONFIG', label: 'Config' },
  { id: 'STRINGS', label: 'Strings' },
  { id: 'TRANSLATIONS', label: 'Translations' },
  { id: 'EXPORT', label: 'Export' },
  { id: 'SYNC', label: 'Automation' },
  { id: 'SETUP', label: 'Setup' },
];
const DEFAULT_JSON_CONFIG_LOCALIZATION_TAB: JsonConfigLocalizationTab = 'CONFIG';
const JSON_CONFIG_LOCALIZATION_TAB_QUERY_VALUES: Record<JsonConfigLocalizationTab, string> = {
  CONFIG: 'config',
  STRINGS: 'strings',
  TRANSLATIONS: 'translations',
  EXPORT: 'export',
  SYNC: 'automation',
  SETUP: 'setup',
};
const JSON_CONFIG_LOCALIZATION_TAB_BY_QUERY_VALUE = new Map<string, JsonConfigLocalizationTab>(
  Object.entries(JSON_CONFIG_LOCALIZATION_TAB_QUERY_VALUES).map(([tabId, queryValue]) => [
    queryValue,
    tabId as JsonConfigLocalizationTab,
  ]),
);
const STATSIG_SYNC_STEP_LABELS: Record<StatsigSyncStepId, string> = {
  PULL: 'Pull config from Statsig',
  EXTRACT: 'Extract strings into Mojito',
  TRANSLATE: 'AI translate',
  MERGE: 'Merge translations into config',
  SAVE_CONFIG: 'Save config in Mojito',
  PUSH: 'Push config to Statsig',
};
const DEFAULT_JSON_CONFIG_AUTOMATION_OPTIONS: StatsigSyncOptions = {
  pull: false,
  extract: false,
  translate: false,
  merge: true,
  saveConfig: true,
  push: true,
};
const JSON_CONFIG_AUTOMATION_STEP_IDS: StatsigSyncStepId[] = [
  'PULL',
  'EXTRACT',
  'TRANSLATE',
  'MERGE',
  'SAVE_CONFIG',
  'PUSH',
];
const PUBLISH_CONFIG_STEP_IDS: StatsigSyncStepId[] = ['TRANSLATE', 'MERGE', 'SAVE_CONFIG', 'PUSH'];
const JSON_CONFIG_AUTOMATION_CRON_PRESETS = [
  { label: 'Hourly', value: '0 0 * * * ?' },
  { label: 'Daily 2 AM', value: '0 0 2 * * ?' },
  { label: 'Weekdays 2 AM', value: '0 0 2 ? * MON-FRI' },
];

function getJsonConfigLocalizationTab(tabParam: string | null): JsonConfigLocalizationTab {
  if (!tabParam) {
    return DEFAULT_JSON_CONFIG_LOCALIZATION_TAB;
  }

  return (
    JSON_CONFIG_LOCALIZATION_TAB_BY_QUERY_VALUE.get(tabParam) ??
    DEFAULT_JSON_CONFIG_LOCALIZATION_TAB
  );
}

const JSON_CONFIG_SCHEMA_PRESETS: JsonConfigSchemaPreset[] = [
  {
    id: 'multilingual-title-body',
    label: 'Multilingual title/body entries',
    description:
      'Array of objects with id and translations keyed by locale, where each locale has title and body.',
    profile: {
      format: 'EMBEDDED_TRANSLATIONS',
      collectionKey: 'items',
      itemIdField: 'id',
      translationsField: 'translations',
      sourceLocaleTag: 'en-US',
      translatableFields: ['title', 'body'],
    },
    schema: {
      $schema: 'https://json-schema.org/draft/2020-12/schema',
      type: 'object',
      properties: {
        items: {
          type: 'array',
          items: {
            type: 'object',
            properties: {
              id: { type: 'string', minLength: 1 },
              translations: {
                type: 'object',
                properties: {
                  'en-US': {
                    type: 'object',
                    properties: {
                      title: { type: 'string' },
                      body: { type: 'string' },
                    },
                    required: ['title', 'body'],
                    additionalProperties: false,
                  },
                },
                required: ['en-US'],
                additionalProperties: {
                  type: 'object',
                  properties: {
                    title: { type: 'string' },
                    body: { type: 'string' },
                  },
                  additionalProperties: false,
                },
              },
            },
            required: ['id', 'translations'],
            additionalProperties: true,
          },
        },
      },
      required: ['items'],
      additionalProperties: true,
    },
  },
  {
    id: 'multilingual-text',
    label: 'Multilingual text entries',
    description: 'Array of objects with translations keyed by locale, with one text field.',
    profile: {
      format: 'EMBEDDED_TRANSLATIONS',
      collectionKey: 'messages',
      itemIdField: 'id',
      translationsField: 'translations',
      sourceLocaleTag: 'en-US',
      translatableFields: ['text'],
    },
    schema: {
      $schema: 'https://json-schema.org/draft/2020-12/schema',
      type: 'object',
      properties: {
        messages: {
          type: 'array',
          items: {
            type: 'object',
            properties: {
              translations: {
                type: 'object',
                properties: {
                  'en-US': {
                    type: 'object',
                    properties: {
                      text: { type: 'string' },
                    },
                    required: ['text'],
                    additionalProperties: false,
                  },
                },
                required: ['en-US'],
                additionalProperties: {
                  type: 'object',
                  properties: {
                    text: { type: 'string' },
                  },
                  additionalProperties: false,
                },
              },
            },
            required: ['translations'],
            additionalProperties: true,
          },
        },
      },
      required: ['messages'],
      additionalProperties: true,
    },
  },
  {
    id: 'flat-source-array',
    label: 'Flat source array',
    description: 'Array of source messages with id, source text, and optional description.',
    profile: {
      format: 'FLAT_SOURCE_ARRAY',
      collectionKey: 'messages',
      itemIdField: 'id',
      translationsField: 'translations',
      sourceLocaleTag: 'en-US',
      translatableFields: [],
      sourceField: 'source',
      commentField: 'description',
    },
    schema: {
      $schema: 'https://json-schema.org/draft/2020-12/schema',
      type: 'object',
      properties: {
        messages: {
          type: 'array',
          items: {
            type: 'object',
            properties: {
              id: { type: 'string', minLength: 1 },
              source: { type: 'string' },
              description: { type: 'string' },
            },
            required: ['id', 'source'],
            additionalProperties: true,
          },
        },
      },
      required: ['messages'],
      additionalProperties: true,
    },
  },
  {
    id: 'formatjs-map',
    label: 'FormatJS message map',
    description: 'Object keyed by message id, with defaultMessage and optional description.',
    profile: {
      format: 'FORMATJS_MAP',
      collectionKey: '',
      itemIdField: 'id',
      translationsField: 'translations',
      sourceLocaleTag: 'en-US',
      translatableFields: [],
      sourceField: 'defaultMessage',
      commentField: 'description',
    },
    schema: {
      $schema: 'https://json-schema.org/draft/2020-12/schema',
      type: 'object',
      additionalProperties: {
        type: 'object',
        properties: {
          defaultMessage: { type: 'string' },
          description: { type: 'string' },
        },
        required: ['defaultMessage'],
        additionalProperties: true,
      },
    },
  },
  {
    id: 'formatjs-multilingual-map',
    label: 'Multilingual FormatJS message map',
    description:
      'Finds message maps named messages anywhere in the config, with defaultMessage, optional description, and locale translations.',
    profile: {
      format: 'FORMATJS_MULTILINGUAL_MAP',
      collectionKey: '$..messages',
      itemIdField: 'id',
      translationsField: 'translations',
      sourceLocaleTag: 'en-US',
      translatableFields: [],
      sourceField: 'defaultMessage',
      commentField: 'description',
    },
    schema: {
      $schema: 'https://json-schema.org/draft/2020-12/schema',
      type: 'object',
      properties: {
        messages: {
          type: 'object',
          additionalProperties: {
            type: 'object',
            properties: {
              defaultMessage: { type: 'string' },
              description: { type: 'string' },
              translations: {
                type: 'object',
                additionalProperties: { type: 'string' },
              },
            },
            required: ['defaultMessage'],
            additionalProperties: true,
          },
        },
      },
      additionalProperties: true,
    },
  },
];

export function AdminJsonConfigLocalizationPage() {
  const user = useUser();
  const params = useParams<{ repositoryId?: string }>();
  const [searchParams, setSearchParams] = useSearchParams();
  const repositoryId = parseRepositoryId(params.repositoryId);
  const setupId = parsePositiveInteger(searchParams.get('setupId'));
  const assetPathParam = searchParams.get('assetPath');
  const isAdminOrPm = user.role === 'ROLE_ADMIN' || user.role === 'ROLE_PM';

  if (!isAdminOrPm) {
    return <Navigate to="/settings/me" replace />;
  }

  if (repositoryId == null) {
    return <JsonConfigLocalizationRepositoryPicker />;
  }

  return (
    <JsonConfigLocalizationWorkspace
      repositoryId={repositoryId}
      setupIdFromUrl={setupId}
      assetPathFromUrl={assetPathParam || DEFAULT_JSON_CONFIG_LOCALIZATION_ASSET_PATH}
      hasAssetPathParam={assetPathParam != null}
      setAssetPath={(assetPath) => {
        const nextParams = new URLSearchParams(searchParams);
        if (assetPath === DEFAULT_JSON_CONFIG_LOCALIZATION_ASSET_PATH) {
          nextParams.delete('assetPath');
        } else {
          nextParams.set('assetPath', assetPath);
        }
        setSearchParams(nextParams, { replace: true });
      }}
    />
  );
}

function JsonConfigLocalizationRepositoryPicker() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const repositoriesQuery = useRepositories();
  const [assetPath, setAssetPath] = useState(DEFAULT_JSON_CONFIG_LOCALIZATION_ASSET_PATH);
  const [setupProvider, setSetupProvider] = useState<JsonConfigProvider>('STATSIG');
  const [setupName, setSetupName] = useState('');
  const [setupProviderConfigId, setSetupProviderConfigId] = useState('');
  const [setupFilter, setSetupFilter] = useState<RepositorySetupFilter>('CONFIGURED');
  const [selectedRepositoryIds, setSelectedRepositoryIds] = useState<number[]>([]);
  const [creatingRepositoryId, setCreatingRepositoryId] = useState<number | null>(null);
  const [deletingRepositoryId, setDeletingRepositoryId] = useState<number | null>(null);
  const [setupModalRow, setSetupModalRow] = useState<RepositoryPickerRow | null>(null);
  const [pickerError, setPickerError] = useState<string | null>(null);

  const configsQuery = useQuery({
    queryKey: ['json-config-localizations'],
    queryFn: fetchJsonConfigLocalizations,
    staleTime: 0,
  });

  const configsByRepositoryId = useMemo(() => {
    const map = new Map<number, ApiJsonConfigLocalization[]>();
    (configsQuery.data ?? []).forEach((config) => {
      const configs = map.get(config.repository.id) ?? [];
      configs.push(config);
      map.set(config.repository.id, configs);
    });
    return map;
  }, [configsQuery.data]);

  const rows = useMemo(() => {
    const defaultAssetPath = assetPath.trim() || DEFAULT_JSON_CONFIG_LOCALIZATION_ASSET_PATH;
    return (repositoriesQuery.data ?? []).flatMap((repository) => {
      const configs = configsByRepositoryId.get(repository.id) ?? [];
      if (configs.length) {
        return configs.map(toConfiguredRepositoryPickerRow);
      }
      return [{ ...toRepositoryPickerRow(repository), assetPath: defaultAssetPath }];
    });
  }, [assetPath, configsByRepositoryId, repositoriesQuery.data]);

  const filteredRows = useMemo(
    () => filterRepositoryPickerRows(rows, setupFilter),
    [rows, setupFilter],
  );

  const visibleRows = useMemo(() => {
    if (!selectedRepositoryIds.length) {
      return filteredRows;
    }
    const selectedRepositoryIdSet = new Set(selectedRepositoryIds);
    return filteredRows.filter((row) => selectedRepositoryIdSet.has(row.repositoryId));
  }, [filteredRows, selectedRepositoryIds]);

  const repositoryOptions = useMemo(() => {
    const optionsByRepositoryId = new Map<
      number,
      {
        id: number;
        name: string;
        secondaryLabel: string;
        searchText: string;
      }
    >();
    filteredRows.forEach((row) => {
      if (optionsByRepositoryId.has(row.repositoryId)) {
        return;
      }
      optionsByRepositoryId.set(row.repositoryId, {
        id: row.repositoryId,
        name: row.repositoryName,
        secondaryLabel: formatRepositoryOptionSecondaryLabel(row),
        searchText: buildRepositoryOptionSearchText(row),
      });
    });
    return Array.from(optionsByRepositoryId.values());
  }, [filteredRows]);
  const repositoryFilterActions = useMemo(
    () =>
      (['CONFIGURED', 'NOT_CONFIGURED', 'ALL'] as const).map((filter) => ({
        label: formatRepositorySetupFilter(filter),
        onClick: () => setSetupFilter(filter),
        active: setupFilter === filter,
        disabled: configsQuery.isLoading || repositoriesQuery.isLoading,
        ariaLabel: `Show ${formatRepositorySetupFilter(filter).toLowerCase()} repositories`,
      })),
    [configsQuery.isLoading, repositoriesQuery.isLoading, setupFilter],
  );

  const handleRepositorySelectionChange = (nextRepositoryIds: number[]) => {
    setSelectedRepositoryIds(nextRepositoryIds);
  };

  useEffect(() => {
    if (!configsQuery.data || configsQuery.data.length > 0 || setupFilter !== 'CONFIGURED') {
      return;
    }
    setSetupFilter('ALL');
  }, [configsQuery.data, setupFilter]);

  useEffect(() => {
    const availableRepositoryIds = new Set(filteredRows.map((row) => row.repositoryId));
    setSelectedRepositoryIds((current) =>
      current.filter((repositoryId) => availableRepositoryIds.has(repositoryId)),
    );
  }, [filteredRows]);

  const startCreateSetup = (row: RepositoryPickerRow) => {
    const existingSetups = configsByRepositoryId.get(row.repositoryId) ?? [];
    const nextSetupName = nextSetupNameForRepository(
      row.repositoryName,
      existingSetups.map((config) => config.name),
    );
    setSetupName(nextSetupName);
    setSetupProviderConfigId('');
    setSetupProvider(row.config?.provider === 'GENERIC_JSON' ? 'GENERIC_JSON' : 'STATSIG');
    setAssetPath(
      row.config
        ? defaultAssetPathForSetupName(nextSetupName)
        : row.assetPath || DEFAULT_JSON_CONFIG_LOCALIZATION_ASSET_PATH,
    );
    setSetupModalRow({ ...row, config: null });
  };

  const openRow = (row: RepositoryPickerRow) => {
    if (!row.config) {
      startCreateSetup(row);
      return;
    }
    openRepository(row.repositoryId, row.assetPath, row.config.id);
  };

  const createSetup = async () => {
    if (!setupModalRow) {
      return;
    }

    setPickerError(null);
    setCreatingRepositoryId(setupModalRow.repositoryId);
    try {
      const config = await createJsonConfigLocalization(setupModalRow.repositoryId, {
        name: setupName.trim() || setupModalRow.repositoryName,
        assetPath:
          setupProvider === 'GENERIC_JSON'
            ? assetPath.trim() || DEFAULT_JSON_CONFIG_LOCALIZATION_ASSET_PATH
            : null,
        provider: setupProvider,
        providerConfigId: setupProvider === 'STATSIG' ? setupProviderConfigId.trim() || null : null,
      });
      await queryClient.invalidateQueries({ queryKey: ['json-config-localizations'] });
      const trimmedAssetPath =
        config.assetPath.trim() ||
        setupModalRow.assetPath ||
        DEFAULT_JSON_CONFIG_LOCALIZATION_ASSET_PATH;
      setSetupModalRow(null);
      openRepository(setupModalRow.repositoryId, trimmedAssetPath, config.id);
    } catch (error) {
      setPickerError(error instanceof Error ? error.message : 'Unable to open repository setup.');
    } finally {
      setCreatingRepositoryId(null);
    }
  };

  const openRepository = (
    repositoryId: number,
    repositoryAssetPath: string,
    setupId?: number | null,
  ) => {
    const trimmedAssetPath =
      repositoryAssetPath.trim() || DEFAULT_JSON_CONFIG_LOCALIZATION_ASSET_PATH;
    const nextParams = new URLSearchParams();
    if (setupId) {
      nextParams.set('setupId', String(setupId));
    }
    if (trimmedAssetPath !== DEFAULT_JSON_CONFIG_LOCALIZATION_ASSET_PATH) {
      nextParams.set('assetPath', trimmedAssetPath);
    }
    const params = nextParams.toString() ? `?${nextParams.toString()}` : '';
    void navigate(`/settings/system/json-config-localization/${repositoryId}${params}`);
  };

  const removeRow = async (row: RepositoryPickerRow) => {
    if (!row.config) {
      return;
    }
    const confirmed = window.confirm(
      `Remove JSON config localization setup for "${row.repositoryName}"? The repository and text units will remain.`,
    );
    if (!confirmed) {
      return;
    }

    setPickerError(null);
    setDeletingRepositoryId(row.repositoryId);
    try {
      await deleteJsonConfigLocalizationSetup(row.config.id);
      await queryClient.invalidateQueries({ queryKey: ['json-config-localizations'] });
    } catch (error) {
      setPickerError(error instanceof Error ? error.message : 'Unable to remove repository setup.');
    } finally {
      setDeletingRepositoryId(null);
    }
  };

  return (
    <div className="settings-subpage json-config-localization-page">
      <SettingsSubpageHeader
        backTo="/settings/system"
        backLabel="Back to settings"
        title="JSON config localization"
      />
      <div className="json-config-localization-page__picker">
        <section
          className="settings-card"
          aria-labelledby="json-config-localization-repository-picker"
        >
          <div className="settings-card__header">
            <div>
              <h2 id="json-config-localization-repository-picker">Repository</h2>
              <p className="settings-hint">
                Filter repositories, then edit an existing JSON config setup or create one.
              </p>
            </div>
          </div>
          {pickerError ? (
            <div className="json-config-localization-page__empty is-error">{pickerError}</div>
          ) : null}
          <div className="json-config-localization-page__repository-picker-control">
            <RepositoryMultiSelect
              label="Repositories"
              options={repositoryOptions}
              selectedIds={selectedRepositoryIds}
              onChange={handleRepositorySelectionChange}
              className="json-config-localization-page__repository-select"
              disabled={configsQuery.isLoading || repositoriesQuery.isLoading}
              buttonAriaLabel="Filter JSON config localization repositories"
              quickActions={repositoryFilterActions}
              summaryFormatter={({ defaultSummary, selectedIds }) =>
                selectedIds.length ? defaultSummary : 'All matching repositories'
              }
            />
          </div>
          <div className="json-config-localization-page__repository-table-wrap">
            {configsQuery.isLoading || repositoriesQuery.isLoading ? (
              <div className="json-config-localization-page__empty">Loading repositories...</div>
            ) : visibleRows.length ? (
              <table className="json-config-localization-page__repository-table">
                <thead>
                  <tr>
                    <th scope="col">Repository</th>
                    <th scope="col">Setup</th>
                    <th scope="col" className="json-config-localization-page__repository-action">
                      Actions
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {visibleRows.map((row) => (
                    <tr key={row.config?.id ?? `repository-${row.repositoryId}`}>
                      <td>
                        <div className="json-config-localization-page__repository-name">
                          {row.repositoryName}
                        </div>
                      </td>
                      <td>
                        <span
                          className={`json-config-localization-page__setup-badge${
                            row.config ? ' is-configured' : ''
                          }`}
                        >
                          {formatRepositorySetupStatus(row)}
                        </span>
                      </td>
                      <td className="json-config-localization-page__repository-action">
                        <div className="json-config-localization-page__repository-actions">
                          <button
                            type="button"
                            className="settings-button settings-button--primary"
                            onClick={() => {
                              openRow(row);
                            }}
                            disabled={
                              creatingRepositoryId === row.repositoryId ||
                              deletingRepositoryId === row.repositoryId
                            }
                          >
                            {row.config ? 'Edit config' : 'Set up'}
                          </button>
                          {row.config ? (
                            <button
                              type="button"
                              className="settings-button"
                              onClick={() => {
                                startCreateSetup(row);
                              }}
                              disabled={
                                deletingRepositoryId === row.repositoryId ||
                                creatingRepositoryId === row.repositoryId
                              }
                            >
                              New setup
                            </button>
                          ) : null}
                          {row.config ? (
                            <button
                              type="button"
                              className="settings-button"
                              onClick={() => {
                                void removeRow(row);
                              }}
                              disabled={
                                deletingRepositoryId === row.repositoryId ||
                                creatingRepositoryId === row.repositoryId
                              }
                            >
                              Remove
                            </button>
                          ) : null}
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            ) : (
              <div className="json-config-localization-page__empty">
                No repositories match this filter.
              </div>
            )}
          </div>
        </section>
        <Modal
          open={setupModalRow != null}
          size="sm"
          ariaLabel="Create JSON config localization setup"
          onClose={() => setSetupModalRow(null)}
          closeOnBackdrop
        >
          <h2 className="modal__title">Create setup</h2>
          <p className="modal__body">
            Choose how Mojito should localize config for{' '}
            <strong>{setupModalRow?.repositoryName}</strong>.
          </p>
          <div className="json-config-localization-page__setup-modal-fields">
            <label className="settings-field">
              <span className="settings-field__label">Setup name</span>
              <input
                className="settings-input"
                value={setupName}
                onChange={(event) => setSetupName(event.target.value)}
                placeholder={setupModalRow?.repositoryName ?? 'Setup name'}
              />
            </label>
            <label className="settings-field">
              <span className="settings-field__label">Config type</span>
              <select
                className="settings-input"
                value={setupProvider}
                onChange={(event) => setSetupProvider(event.target.value as JsonConfigProvider)}
              >
                <option value="STATSIG">Statsig dynamic config</option>
                <option value="GENERIC_JSON">Generic JSON</option>
              </select>
            </label>
            {setupProvider === 'STATSIG' ? (
              <label className="settings-field">
                <span className="settings-field__label">Statsig config ID</span>
                <input
                  className="settings-input"
                  value={setupProviderConfigId}
                  onChange={(event) => setSetupProviderConfigId(event.target.value)}
                  placeholder="dynamic_config_id"
                />
              </label>
            ) : null}
            {setupProvider === 'GENERIC_JSON' ? (
              <label className="settings-field">
                <span className="settings-field__label">Asset path</span>
                <input
                  className="settings-input"
                  value={assetPath}
                  onChange={(event) => setAssetPath(event.target.value)}
                  placeholder={DEFAULT_JSON_CONFIG_LOCALIZATION_ASSET_PATH}
                />
              </label>
            ) : null}
          </div>
          <div className="modal__actions">
            <button
              type="button"
              className="modal__button"
              onClick={() => setSetupModalRow(null)}
              disabled={creatingRepositoryId != null}
            >
              Cancel
            </button>
            <button
              type="button"
              className="modal__button modal__button--primary"
              onClick={() => {
                void createSetup();
              }}
              disabled={creatingRepositoryId != null || !setupModalRow}
            >
              {creatingRepositoryId != null ? 'Creating...' : 'Create setup'}
            </button>
          </div>
        </Modal>
      </div>
    </div>
  );
}

function JsonConfigLocalizationWorkspace({
  repositoryId,
  setupIdFromUrl,
  assetPathFromUrl,
  hasAssetPathParam,
  setAssetPath,
}: {
  repositoryId: number;
  setupIdFromUrl: number | null;
  assetPathFromUrl: string;
  hasAssetPathParam: boolean;
  setAssetPath: (assetPath: string) => void;
}) {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const [workspaceSearchParams, setWorkspaceSearchParams] = useSearchParams();
  const repositoriesQuery = useRepositories();
  const repository =
    repositoriesQuery.data?.find((candidate) => candidate.id === repositoryId) ?? null;
  const sourceLocaleTag = repository?.sourceLocale?.bcp47Tag ?? 'en-US';
  const targetLocaleTags = useMemo(
    () =>
      repository
        ? getNonRootRepositoryLocaleTags(repository).filter(
            (localeTag) => localeTag !== sourceLocaleTag,
          )
        : [],
    [repository, sourceLocaleTag],
  );

  const [assetPathDraft, setAssetPathDraft] = useState(assetPathFromUrl);
  const [stringSearch, setStringSearch] = useState('');
  const [usedFilter, setUsedFilter] = useState<UsedFilter>('USED');
  const [createdAfter, setCreatedAfter] = useState<string | null>(null);
  const [createdBefore, setCreatedBefore] = useState<string | null>(null);
  const [draftStrings, setDraftStrings] = useState<JsonConfigLocalizationDraftString[]>([]);
  const draftStringsRef = useRef<JsonConfigLocalizationDraftString[]>([]);
  const [selectedClientId, setSelectedClientId] = useState<string | null>(null);
  const [statusNotice, setStatusNotice] = useState<StatusNotice | null>(null);
  const [provider, setProvider] = useState<JsonConfigProvider>('STATSIG');
  const [providerConfigId, setProviderConfigId] = useState('');
  const [schemaText, setSchemaText] = useState('');
  const [sourceConfigText, setSourceConfigText] = useState('');
  const [statsigProfile, setStatsigProfile] = useState<StatsigSourceConfigProfile>(
    DEFAULT_STATSIG_SOURCE_CONFIG_PROFILE,
  );
  const [selectedSchemaPresetId, setSelectedSchemaPresetId] = useState('');
  const [statsigWarnings, setStatsigWarnings] = useState<string[]>([]);
  const [mappingNotice, setMappingNotice] = useState<StatusNotice | null>(null);
  const [outputLocaleMapping, setOutputLocaleMapping] = useState<OutputLocaleMapping>({});
  const [outputLocaleMappingImport, setOutputLocaleMappingImport] = useState('');
  const [showAllOutputLocaleMappings, setShowAllOutputLocaleMappings] = useState(false);
  const [exportFormat, setExportFormat] = useState<ExportFormat>('locale-map');
  const [extractOnConfigSave, setExtractOnConfigSave] = useState(true);
  const [collapseLocalizedConfig, setCollapseLocalizedConfig] = useState(true);
  const [automationEnabled, setAutomationEnabled] = useState(false);
  const [automationCronExpression, setAutomationCronExpression] = useState('');
  const [automationTimeZone, setAutomationTimeZone] = useState(getDefaultTimeZone());
  const [statsigSyncOptions, setStatsigSyncOptions] = useState<StatsigSyncOptions>(
    DEFAULT_JSON_CONFIG_AUTOMATION_OPTIONS,
  );
  const [publishConfigOptions, setPublishConfigOptions] = useState<StatsigSyncOptions>({
    pull: false,
    extract: false,
    translate: false,
    merge: true,
    saveConfig: true,
    push: false,
  });
  const activeTab = getJsonConfigLocalizationTab(workspaceSearchParams.get('tab'));
  const setActiveTab = useCallback(
    (tab: JsonConfigLocalizationTab, options?: { replace?: boolean }) => {
      const nextParams = new URLSearchParams(workspaceSearchParams);
      const nextTabParam =
        tab === DEFAULT_JSON_CONFIG_LOCALIZATION_TAB
          ? null
          : JSON_CONFIG_LOCALIZATION_TAB_QUERY_VALUES[tab];

      if (nextTabParam == null) {
        nextParams.delete('tab');
      } else {
        nextParams.set('tab', nextTabParam);
      }

      if ((workspaceSearchParams.get('tab') ?? null) === nextTabParam) {
        return;
      }

      setWorkspaceSearchParams(nextParams, { replace: options?.replace ?? false });
    },
    [setWorkspaceSearchParams, workspaceSearchParams],
  );
  const [statsigSyncProgress, setStatsigSyncProgress] = useState<StatsigSyncProgress | null>(null);
  const [translateProgress, setTranslateProgress] = useState<StatsigSyncProgress | null>(null);
  const configEditorRef = useRef<JsonCodeEditorHandle | null>(null);
  const pendingSchemaPresetTextRef = useRef<string | null>(null);
  const [pendingConfigFocusTarget, setPendingConfigFocusTarget] =
    useState<ConfigEditorFocusTarget | null>(null);

  const jsonConfigLocalizationSetupsQuery = useQuery({
    queryKey: ['json-config-localizations', 'repository', repositoryId, 'setups'],
    queryFn: () => fetchJsonConfigLocalizationSetupsByRepository(repositoryId),
    enabled: Boolean(repository),
    staleTime: 0,
  });
  const selectedSetupId = setupIdFromUrl ?? jsonConfigLocalizationSetupsQuery.data?.[0]?.id ?? null;
  const selectedSetupFromList =
    jsonConfigLocalizationSetupsQuery.data?.find((setup) => setup.id === selectedSetupId) ?? null;
  const selectSetupInUrl = useCallback(
    (setup: ApiJsonConfigLocalization, options?: { replace?: boolean }) => {
      const nextParams = new URLSearchParams(workspaceSearchParams);
      nextParams.set('setupId', String(setup.id));
      const setupAssetPath =
        setup.assetPath?.trim() || assetPathFromUrl || DEFAULT_JSON_CONFIG_LOCALIZATION_ASSET_PATH;
      if (setupAssetPath === DEFAULT_JSON_CONFIG_LOCALIZATION_ASSET_PATH) {
        nextParams.delete('assetPath');
      } else {
        nextParams.set('assetPath', setupAssetPath);
      }
      setWorkspaceSearchParams(nextParams, { replace: options?.replace ?? true });
    },
    [assetPathFromUrl, setWorkspaceSearchParams, workspaceSearchParams],
  );
  const jsonConfigLocalizationQuery = useQuery({
    queryKey: ['json-config-localizations', 'setup', selectedSetupId],
    queryFn: () =>
      selectedSetupId == null
        ? Promise.resolve(null)
        : fetchJsonConfigLocalizationById(selectedSetupId),
    enabled: Boolean(repository) && !jsonConfigLocalizationSetupsQuery.isLoading,
    staleTime: 0,
  });
  const automationRunsQuery = useQuery({
    queryKey: ['json-config-localizations', 'setup', selectedSetupId, 'automation-runs'],
    queryFn: () =>
      selectedSetupId == null
        ? Promise.resolve([])
        : fetchJsonConfigLocalizationRuns(selectedSetupId),
    enabled: Boolean(selectedSetupId),
    staleTime: 0,
  });
  const debouncedAutomationCronExpression = useDebouncedValue(automationCronExpression.trim(), 250);
  const debouncedAutomationTimeZone = useDebouncedValue(automationTimeZone.trim(), 250);
  const automationScheduleTimeZone = debouncedAutomationTimeZone || getDefaultTimeZone();
  const automationSchedulePreviewQuery = useQuery({
    queryKey: [
      'json-config-localizations',
      'automation-schedule-preview',
      debouncedAutomationCronExpression,
      automationScheduleTimeZone,
    ],
    queryFn: () =>
      fetchReviewAutomationSchedulePreview({
        cronExpression: debouncedAutomationCronExpression,
        timeZone: automationScheduleTimeZone,
        count: 3,
      }),
    enabled:
      activeTab === 'SYNC' && automationEnabled && Boolean(debouncedAutomationCronExpression),
    staleTime: 15_000,
    retry: false,
  });

  const jsonConfigDraftStorageKey = useMemo(
    () =>
      `json-config-localization:config-draft:${repositoryId}:${selectedSetupId ?? 'new'}:${assetPathFromUrl}`,
    [assetPathFromUrl, repositoryId, selectedSetupId],
  );
  const legacyStatsigDraftStorageKey = useMemo(
    () => `json-config-localization:statsig-draft:${repositoryId}:${assetPathFromUrl}`,
    [assetPathFromUrl, repositoryId],
  );
  const [loadedStatsigDraftStorageKey, setLoadedStatsigDraftStorageKey] = useState<string | null>(
    null,
  );
  const effectiveStatsigProfile = useMemo(() => {
    if (!sourceConfigText.trim()) {
      return statsigProfile;
    }
    try {
      return inferStatsigSourceConfigProfileFromText(sourceConfigText, statsigProfile);
    } catch {
      return statsigProfile;
    }
  }, [sourceConfigText, statsigProfile]);
  const canExportLocalizedConfig =
    (effectiveStatsigProfile.format === 'EMBEDDED_TRANSLATIONS' &&
      Boolean(effectiveStatsigProfile.collectionKey.trim()) &&
      Boolean(effectiveStatsigProfile.translationsField.trim()) &&
      Boolean(effectiveStatsigProfile.sourceLocaleTag.trim()) &&
      effectiveStatsigProfile.translatableFields.length > 0) ||
    (effectiveStatsigProfile.format === 'FORMATJS_MULTILINGUAL_MAP' &&
      Boolean(effectiveStatsigProfile.sourceField?.trim()) &&
      Boolean(effectiveStatsigProfile.translationsField.trim()));
  const localizedConfigExportLabel =
    effectiveStatsigProfile.format === 'FORMATJS_MULTILINGUAL_MAP'
      ? 'Multilingual FormatJS config'
      : 'Embedded multilingual config';
  const exportFormatOptions = useMemo(
    () => [
      { value: 'locale-map' as const, label: 'Locale map' },
      { value: 'locale-files' as const, label: 'Locale files' },
      ...(canExportLocalizedConfig
        ? [{ value: 'localized-config' as const, label: localizedConfigExportLabel }]
        : []),
    ],
    [canExportLocalizedConfig, localizedConfigExportLabel],
  );

  const localizedConfigExportQuery = useQuery({
    queryKey: [
      'json-config-localization',
      'localized-config-export',
      repositoryId,
      selectedSetupId,
      jsonConfigLocalizationQuery.data?.lastModifiedDate,
    ],
    queryFn: () =>
      selectedSetupId == null
        ? exportJsonConfigForRepository(repositoryId)
        : exportJsonConfigForSetup(selectedSetupId),
    enabled:
      Boolean(repository) &&
      exportFormat === 'localized-config' &&
      canExportLocalizedConfig &&
      Boolean(jsonConfigLocalizationQuery.data?.sourceConfigJson),
    staleTime: 0,
  });

  const cacheJsonConfigLocalizationSetup = (setup?: ApiJsonConfigLocalization | null) => {
    if (!setup) {
      return;
    }
    queryClient.setQueryData<ApiJsonConfigLocalization | null>(
      ['json-config-localizations', 'setup', setup.id],
      setup,
    );
    queryClient.setQueryData<ApiJsonConfigLocalization | null>(
      ['json-config-localizations', 'repository', repositoryId],
      setup,
    );
    queryClient.setQueryData<ApiJsonConfigLocalization[] | undefined>(
      ['json-config-localizations', 'repository', repositoryId, 'setups'],
      (current) => upsertJsonConfigLocalizationList(current, setup),
    );
    queryClient.setQueryData<ApiJsonConfigLocalization[] | undefined>(
      ['json-config-localizations'],
      (current) => upsertJsonConfigLocalizationList(current, setup),
    );
  };

  useEffect(() => {
    setAssetPathDraft(assetPathFromUrl);
  }, [assetPathFromUrl]);

  useEffect(() => {
    if (setupIdFromUrl != null || !selectedSetupFromList) {
      return;
    }
    selectSetupInUrl(selectedSetupFromList, { replace: true });
  }, [selectSetupInUrl, selectedSetupFromList, setupIdFromUrl]);

  useEffect(() => {
    const configuredAssetPath = jsonConfigLocalizationQuery.data?.assetPath?.trim();
    if (hasAssetPathParam || !configuredAssetPath || configuredAssetPath === assetPathFromUrl) {
      return;
    }
    setAssetPath(configuredAssetPath);
  }, [
    assetPathFromUrl,
    hasAssetPathParam,
    jsonConfigLocalizationQuery.data?.assetPath,
    setAssetPath,
  ]);

  useEffect(() => {
    draftStringsRef.current = draftStrings;
  }, [draftStrings]);

  useEffect(() => {
    if (jsonConfigLocalizationQuery.isLoading) {
      return;
    }

    const persistedConfig = jsonConfigLocalizationQuery.data;
    const loadKey = persistedConfig
      ? `db:${persistedConfig.id}:${persistedConfig.lastModifiedDate ?? persistedConfig.createdDate ?? ''}:${assetPathFromUrl}`
      : `local:${jsonConfigDraftStorageKey}`;
    if (loadedStatsigDraftStorageKey === loadKey) {
      return;
    }

    const storedDraft = persistedConfig
      ? toStoredStatsigDraft(persistedConfig)
      : (readStoredStatsigDraft(jsonConfigDraftStorageKey) ??
        readStoredStatsigDraft(legacyStatsigDraftStorageKey));
    if (storedDraft) {
      setProvider(storedDraft.provider);
      setProviderConfigId(storedDraft.providerConfigId);
      setSchemaText(storedDraft.schemaText);
      setSourceConfigText(storedDraft.sourceConfigText);
      setStatsigProfile(storedDraft.profile);
      setOutputLocaleMapping(storedDraft.outputLocaleMapping);
      setAutomationEnabled(storedDraft.automationEnabled);
      setAutomationCronExpression(storedDraft.automationCronExpression);
      setAutomationTimeZone(storedDraft.automationTimeZone || getDefaultTimeZone());
      setStatsigSyncOptions(storedDraft.automationOptions);
      setStatsigWarnings([]);
    } else {
      setProvider('STATSIG');
      setProviderConfigId('');
      setSchemaText('');
      setSourceConfigText('');
      setStatsigProfile(DEFAULT_STATSIG_SOURCE_CONFIG_PROFILE);
      setOutputLocaleMapping({});
      setAutomationEnabled(false);
      setAutomationCronExpression('');
      setAutomationTimeZone(getDefaultTimeZone());
      setStatsigSyncOptions(DEFAULT_JSON_CONFIG_AUTOMATION_OPTIONS);
      setStatsigWarnings([]);
      setExportFormat('locale-map');
    }
    setLoadedStatsigDraftStorageKey(loadKey);
  }, [
    assetPathFromUrl,
    jsonConfigDraftStorageKey,
    jsonConfigLocalizationQuery.data,
    jsonConfigLocalizationQuery.isLoading,
    legacyStatsigDraftStorageKey,
    loadedStatsigDraftStorageKey,
  ]);

  useEffect(() => {
    if (!targetLocaleTags.length) {
      return;
    }

    setOutputLocaleMapping((current) => normalizeOutputLocaleMapping(targetLocaleTags, current));
  }, [targetLocaleTags]);

  useEffect(() => {
    if (exportFormat === 'localized-config' && !canExportLocalizedConfig) {
      setExportFormat('locale-map');
    }
  }, [canExportLocalizedConfig, exportFormat]);

  useEffect(() => {
    if (provider === 'STATSIG' && activeTab === 'EXPORT') {
      setActiveTab('CONFIG', { replace: true });
    }
  }, [activeTab, provider, setActiveTab]);

  useEffect(() => {
    if (activeTab !== 'CONFIG' || !pendingConfigFocusTarget) {
      return;
    }

    const timeout = window.setTimeout(() => {
      configEditorRef.current?.focusAtText(pendingConfigFocusTarget.needle, {
        offset: pendingConfigFocusTarget.offset,
        match: 'last',
      });
      setPendingConfigFocusTarget(null);
    }, 0);

    return () => window.clearTimeout(timeout);
  }, [activeTab, pendingConfigFocusTarget, sourceConfigText]);

  useEffect(() => {
    if (statusNotice?.kind !== 'success') {
      return;
    }

    const notice = statusNotice;
    const timeout = window.setTimeout(() => {
      setStatusNotice((current) => (current === notice ? null : current));
    }, 4500);

    return () => window.clearTimeout(timeout);
  }, [statusNotice]);

  const sourceRowsQuery = useQuery({
    queryKey: [
      'json-config-localization',
      'source',
      repositoryId,
      sourceLocaleTag,
      assetPathFromUrl,
      usedFilter,
      createdAfter,
      createdBefore,
    ],
    queryFn: () =>
      searchTextUnits({
        repositoryIds: [repositoryId],
        localeTags: [sourceLocaleTag],
        textSearch: buildAssetPathSearch(assetPathFromUrl),
        usedFilter: usedFilter === 'ALL' ? undefined : usedFilter,
        tmTextUnitCreatedAfter: createdAfter ?? undefined,
        tmTextUnitCreatedBefore: createdBefore ?? undefined,
        limit: SOURCE_SEARCH_LIMIT,
      }),
    enabled: Boolean(repository),
    staleTime: 0,
  });

  useEffect(() => {
    if (!sourceRowsQuery.data) {
      return;
    }
    const nextDraftStrings = toJsonConfigLocalizationDraftStrings(sourceRowsQuery.data);
    const savedStringIds = new Set(nextDraftStrings.map((sourceString) => sourceString.stringId));
    const unsavedStrings = draftStringsRef.current.filter(
      (sourceString) =>
        sourceString.tmTextUnitId == null && !savedStringIds.has(sourceString.stringId),
    );
    const nextStrings = sortJsonConfigLocalizationDraftStrings([
      ...nextDraftStrings,
      ...unsavedStrings,
    ]);
    setDraftStrings(nextStrings);
    setSelectedClientId((current) => {
      if (current && nextStrings.some((sourceString) => sourceString.clientId === current)) {
        return current;
      }

      const selectedStringBeforeRefresh = draftStringsRef.current.find(
        (sourceString) => sourceString.clientId === current,
      );
      if (selectedStringBeforeRefresh) {
        return (
          nextStrings.find(
            (sourceString) => sourceString.stringId === selectedStringBeforeRefresh.stringId,
          )?.clientId ?? null
        );
      }

      return nextStrings[0]?.clientId ?? null;
    });
  }, [sourceRowsQuery.data]);

  const sourceTmTextUnitIds = useMemo(
    () =>
      Array.from(
        new Set(
          draftStrings
            .map((sourceString) => sourceString.tmTextUnitId)
            .filter((id): id is number => typeof id === 'number' && id > 0),
        ),
      ),
    [draftStrings],
  );

  const targetRowsQuery = useQuery({
    queryKey: [
      'json-config-localization',
      'targets',
      repositoryId,
      sourceTmTextUnitIds,
      targetLocaleTags,
    ],
    queryFn: () =>
      searchTextUnits({
        repositoryIds: [repositoryId],
        localeTags: targetLocaleTags,
        tmTextUnitIds: sourceTmTextUnitIds,
        limit: Math.min(
          TARGET_SEARCH_LIMIT,
          Math.max(10, sourceTmTextUnitIds.length * Math.max(targetLocaleTags.length, 1)),
        ),
      }),
    enabled: sourceTmTextUnitIds.length > 0 && targetLocaleTags.length > 0,
    staleTime: 0,
  });

  const aiTranslateConfigQuery = useQuery({
    queryKey: ['json-config-localization', 'ai-translate-config'],
    queryFn: fetchAiTranslateConfig,
    enabled: Boolean(repository),
    staleTime: 60_000,
  });

  const readinessByString = useMemo(
    () =>
      buildJsonConfigLocalizationReadiness(
        draftStrings,
        targetRowsQuery.data ?? [],
        targetLocaleTags,
      ),
    [draftStrings, targetLocaleTags, targetRowsQuery.data],
  );

  const selectedString =
    draftStrings.find((sourceString) => sourceString.clientId === selectedClientId) ?? null;

  const visibleStrings = useMemo(
    () => filterDraftStrings(draftStrings, stringSearch),
    [draftStrings, stringSearch],
  );
  const estimateStringRowHeight = useCallback(
    () =>
      getRowHeightPx({
        cssVariable: '--json-config-localization-string-row-height',
        defaultRem: 6,
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
      (sourceString) => sourceString.clientId === selectedClientId,
    );
    if (selectedIndex < 0) {
      return;
    }
    scrollStringToIndex(selectedIndex, { align: 'auto' });
  }, [scrollStringToIndex, selectedClientId, visibleStrings]);
  const normalizedOutputLocaleMapping = useMemo(
    () => normalizeOutputLocaleMapping(targetLocaleTags, outputLocaleMapping),
    [outputLocaleMapping, targetLocaleTags],
  );
  const outputLocaleMappingRows = useMemo(
    () =>
      targetLocaleTags.map((mojitoLocaleTag) => {
        const outputLocaleTag = normalizedOutputLocaleMapping[mojitoLocaleTag] ?? mojitoLocaleTag;
        return {
          mojitoLocaleTag,
          outputLocaleTag,
          isCustom: outputLocaleTag !== mojitoLocaleTag,
        };
      }),
    [normalizedOutputLocaleMapping, targetLocaleTags],
  );
  const customOutputLocaleMappingRows = outputLocaleMappingRows.filter((row) => row.isCustom);
  const visibleOutputLocaleMappingRows = showAllOutputLocaleMappings
    ? outputLocaleMappingRows
    : customOutputLocaleMappingRows;
  const identityOutputLocaleMappingCount =
    outputLocaleMappingRows.length - customOutputLocaleMappingRows.length;
  const isStatsigProvider = provider === 'STATSIG';
  const statsigConsoleUrl =
    isStatsigProvider && jsonConfigLocalizationQuery.data?.statsigConsoleUrl
      ? jsonConfigLocalizationQuery.data.statsigConsoleUrl
      : null;
  const setupOptions = jsonConfigLocalizationSetupsQuery.data ?? [];
  const handleSetupSelectionChange = (setupId: number) => {
    const setup = setupOptions.find((candidate) => candidate.id === setupId);
    if (!setup) {
      return;
    }
    selectSetupInUrl(setup);
  };
  const jsonConfigLocalizationTabs = useMemo(
    () =>
      isStatsigProvider
        ? JSON_CONFIG_LOCALIZATION_TABS.filter((tab) => tab.id !== 'EXPORT')
        : JSON_CONFIG_LOCALIZATION_TABS,
    [isStatsigProvider],
  );
  const isSourceConfigBacked = isStatsigProvider || Boolean(sourceConfigText.trim());
  const sourceConfigStringIds = useMemo(() => {
    if (!isSourceConfigBacked) {
      return null;
    }
    try {
      return getStatsigSourceConfigStringIds(sourceConfigText, statsigProfile);
    } catch {
      return null;
    }
  }, [isSourceConfigBacked, sourceConfigText, statsigProfile]);

  const localeMapExportJson = useMemo(
    () =>
      JSON.stringify(
        buildJsonConfigLocalizationExport(
          statsigProfile.sourceLocaleTag || sourceLocaleTag,
          targetLocaleTags,
          draftStrings,
          readinessByString,
          normalizedOutputLocaleMapping,
        ),
        null,
        2,
      ),
    [
      draftStrings,
      normalizedOutputLocaleMapping,
      readinessByString,
      sourceLocaleTag,
      statsigProfile.sourceLocaleTag,
      targetLocaleTags,
    ],
  );

  const localeFileExportFiles = useMemo(
    () =>
      buildJsonConfigLocalizationLocaleFileExport(
        statsigProfile.sourceLocaleTag || sourceLocaleTag,
        targetLocaleTags,
        draftStrings,
        readinessByString,
        normalizedOutputLocaleMapping,
      ),
    [
      draftStrings,
      normalizedOutputLocaleMapping,
      readinessByString,
      sourceLocaleTag,
      statsigProfile.sourceLocaleTag,
      targetLocaleTags,
    ],
  );

  const localeFileExportJson = useMemo(
    () =>
      JSON.stringify(
        Object.fromEntries(localeFileExportFiles.map((file) => [file.filename, file.messages])),
        null,
        2,
      ),
    [localeFileExportFiles],
  );

  const exportPayload = useMemo(() => {
    if (exportFormat === 'localized-config') {
      if (!canExportLocalizedConfig) {
        return {
          json: localeMapExportJson,
          warnings: [
            'Localized config export requires an embedded translations or multilingual FormatJS mapping. Showing locale-map export instead.',
          ],
        };
      }

      if (!jsonConfigLocalizationQuery.data?.sourceConfigJson && !sourceConfigText.trim()) {
        return {
          json: localeMapExportJson,
          warnings: [
            'No Config JSON is saved yet. Showing locale-map export instead of embedded config export.',
          ],
        };
      }

      if (!jsonConfigLocalizationQuery.data?.sourceConfigJson && sourceConfigText.trim()) {
        try {
          const exportResult = buildStatsigSourceConfigExport(
            effectiveStatsigProfile,
            parseJsonRecord(sourceConfigText, 'config'),
            targetLocaleTags,
            draftStrings,
            readinessByString,
            normalizedOutputLocaleMapping,
          );
          return {
            json: JSON.stringify(exportResult.value, null, 2),
            warnings: [
              'Config JSON is not saved yet. Save it before pushing or downloading embedded config output.',
              ...exportResult.warnings,
            ],
          };
        } catch (error) {
          return {
            json: localeMapExportJson,
            warnings: [
              error instanceof Error ? error.message : 'Unable to build embedded config export.',
              'Showing locale-map export instead.',
            ],
          };
        }
      }

      if (localizedConfigExportQuery.isLoading || localizedConfigExportQuery.isFetching) {
        return {
          json: localeMapExportJson,
          warnings: ['Loading localized config export.'],
        };
      }

      if (localizedConfigExportQuery.error) {
        return {
          json: localeMapExportJson,
          warnings: [
            formatJsonConfigApiErrorMessage(
              localizedConfigExportQuery.error,
              'Unable to export localized config.',
            ),
          ],
        };
      }

      return {
        json: formatJsonText(localizedConfigExportQuery.data?.json ?? ''),
        warnings: localizedConfigExportQuery.data?.warnings ?? [],
      };
    }

    if (exportFormat === 'locale-files') {
      return {
        json: localeFileExportJson,
        warnings: [],
      };
    }

    return {
      json: localeMapExportJson,
      warnings: [],
    };
  }, [
    canExportLocalizedConfig,
    draftStrings,
    effectiveStatsigProfile,
    exportFormat,
    jsonConfigLocalizationQuery.data?.sourceConfigJson,
    localeFileExportJson,
    localeMapExportJson,
    localizedConfigExportQuery.data?.json,
    localizedConfigExportQuery.data?.warnings,
    localizedConfigExportQuery.error,
    localizedConfigExportQuery.isFetching,
    localizedConfigExportQuery.isLoading,
    normalizedOutputLocaleMapping,
    readinessByString,
    sourceConfigText,
    targetLocaleTags,
  ]);

  const exportJson = exportPayload.json;

  const saveJsonConfigLocalizationSetup = async (options: SaveDraftStringsOptions = {}) => {
    if (!repository) {
      throw new Error('Repository not found.');
    }
    const effectiveSetupName =
      jsonConfigLocalizationQuery.data?.name ??
      (provider === 'STATSIG' ? providerConfigId.trim() : '') ??
      repository.name;
    const effectiveAssetPath = options.assetPath ?? assetPathFromUrl;
    const effectiveSourceConfigJson = options.sourceConfigJson ?? sourceConfigText;
    const effectiveProfile = options.profile ?? statsigProfile;
    const input = {
      name: effectiveSetupName,
      assetPath: effectiveAssetPath,
      provider,
      providerConfigId: provider === 'STATSIG' ? providerConfigId : null,
      schemaJson: schemaText,
      sourceConfigJson: effectiveSourceConfigJson,
      extractionMappingJson: JSON.stringify(effectiveProfile),
      outputLocaleMappingJson: JSON.stringify(normalizedOutputLocaleMapping),
      automationEnabled,
      automationCronExpression: automationCronExpression.trim() || null,
      automationTimeZone: automationTimeZone.trim() || getDefaultTimeZone(),
      automationOptionsJson: JSON.stringify(
        normalizeJsonConfigAutomationOptions(statsigSyncOptions),
      ),
      expectedLastModifiedDate:
        options.expectedLastModifiedDate ?? jsonConfigLocalizationQuery.data?.lastModifiedDate,
    };
    const currentSetupId = jsonConfigLocalizationQuery.data?.id;
    const savedSetup =
      currentSetupId == null
        ? await createJsonConfigLocalization(repository.id, input)
        : await updateJsonConfigLocalization(currentSetupId, input);
    cacheJsonConfigLocalizationSetup(savedSetup);
    if (setupIdFromUrl !== savedSetup.id) {
      selectSetupInUrl(savedSetup, { replace: true });
    }
    return savedSetup;
  };

  const buildSourceConfigFromDraftStrings = (sourceStrings = draftStrings) => {
    const exportResult = buildStatsigSourceConfigExport(
      statsigProfile,
      parseJsonRecord(sourceConfigText, 'config'),
      [],
      sourceStrings,
      {},
      normalizedOutputLocaleMapping,
    );
    return {
      sourceConfigJson: formatJsonText(JSON.stringify(exportResult.value)),
      warnings: exportResult.warnings,
    };
  };

  const buildLocalizedConfigFromDraftStrings = (
    sourceStrings = draftStrings,
    options: {
      sourceConfigJson?: string;
      profile?: StatsigSourceConfigProfile;
    } = {},
  ) => {
    const effectiveSourceConfigJson = options.sourceConfigJson ?? sourceConfigText;
    const effectiveProfile = options.profile ?? statsigProfile;
    const effectiveReadinessByString =
      sourceStrings === draftStrings
        ? readinessByString
        : buildJsonConfigLocalizationReadiness(
            sourceStrings,
            targetRowsQuery.data ?? [],
            targetLocaleTags,
          );
    const exportResult = buildStatsigSourceConfigExport(
      effectiveProfile,
      parseJsonRecord(effectiveSourceConfigJson, 'config'),
      targetLocaleTags,
      sourceStrings,
      effectiveReadinessByString,
      normalizedOutputLocaleMapping,
    );
    return {
      sourceConfigJson: formatJsonText(JSON.stringify(exportResult.value)),
      warnings: exportResult.warnings,
    };
  };

  const syncSourceConfigFromDraftStrings = (sourceStrings = draftStrings) => {
    if (!isSourceConfigBacked || !sourceConfigText.trim()) {
      return sourceConfigText;
    }

    const syncedConfig = buildSourceConfigFromDraftStrings(sourceStrings);
    setSourceConfigText(syncedConfig.sourceConfigJson);
    setStatsigWarnings((current) => Array.from(new Set([...current, ...syncedConfig.warnings])));
    return syncedConfig.sourceConfigJson;
  };

  const pushSavedConfigToStatsig = async () => {
    if (!repository) {
      throw new Error('Repository not found.');
    }
    const trimmedConfigId = providerConfigId.trim();
    if (!trimmedConfigId) {
      throw new Error('Provide a Statsig config id.');
    }

    const setup = jsonConfigLocalizationQuery.data ?? (await saveJsonConfigLocalizationSetup());
    return pushJsonConfigToStatsigForSetup(setup.id, { configId: trimmedConfigId });
  };

  const saveDraftStringsToJsonConfig = async (
    sourceStrings = draftStrings,
    options: SaveDraftStringsOptions = {},
  ) => {
    if (!repository) {
      throw new Error('Repository not found.');
    }
    const effectiveSourceConfigJson = options.sourceConfigJson ?? sourceConfigText;
    const effectiveProfile = options.profile ?? statsigProfile;
    const validationError = validateDraftStrings(
      sourceStrings,
      assetPathFromUrl,
      isSourceConfigBacked
        ? getStatsigSourceConfigStringIds(effectiveSourceConfigJson, effectiveProfile)
        : null,
    );
    if (validationError) {
      throw new Error(validationError);
    }

    const effectiveSetupName =
      jsonConfigLocalizationQuery.data?.name ??
      (provider === 'STATSIG' ? providerConfigId.trim() : '') ??
      repository.name;
    const input = {
      name: effectiveSetupName,
      assetPath: assetPathFromUrl,
      provider,
      providerConfigId: provider === 'STATSIG' ? providerConfigId : null,
      schemaJson: schemaText,
      sourceConfigJson: effectiveSourceConfigJson,
      profile: effectiveProfile,
      strings: sourceStrings.filter((sourceString) => sourceString.used).map(toJsonConfigString),
      outputLocaleMappingJson: JSON.stringify(normalizedOutputLocaleMapping),
      expectedLastModifiedDate:
        options.expectedLastModifiedDate ?? jsonConfigLocalizationQuery.data?.lastModifiedDate,
    };
    const currentSetupId = jsonConfigLocalizationQuery.data?.id;
    const result =
      currentSetupId == null
        ? await extractJsonConfigForRepository(repository.id, input)
        : await extractJsonConfigForSetup(currentSetupId, input);
    cacheJsonConfigLocalizationSetup(result.setup);
    if (setupIdFromUrl !== result.setup.id) {
      selectSetupInUrl(result.setup, { replace: true });
    }
    return result;
  };

  const saveStringsFromStringsTab = async (sourceStrings = draftStrings) => {
    if (!isSourceConfigBacked) {
      await saveDraftStringsToJsonConfig(sourceStrings);
      return { syncedConfig: false };
    }

    const sourceConfigJson = syncSourceConfigFromDraftStrings();
    await saveDraftStringsToJsonConfig(sourceStrings, { sourceConfigJson });
    return { syncedConfig: true };
  };

  const getSavedActiveTextUnitIds = async () => {
    const savedRows = await searchTextUnits({
      repositoryIds: [repositoryId],
      localeTags: [sourceLocaleTag],
      textSearch: buildAssetPathSearch(assetPathFromUrl),
      usedFilter: 'USED',
      limit: SOURCE_SEARCH_LIMIT,
    });
    return savedRows.map((row) => row.tmTextUnitId);
  };

  const saveAndTranslateActiveStrings = async (
    sourceStrings = draftStrings,
    options: SaveDraftStringsOptions = {},
  ) => {
    await saveDraftStringsToJsonConfig(sourceStrings, options);
    const tmTextUnitIds = await getSavedActiveTextUnitIds();
    await translateTextUnitIds(tmTextUnitIds);
    return tmTextUnitIds.length;
  };

  const translateTextUnitIds = async (tmTextUnitIds: number[]) => {
    if (!repository) {
      throw new Error('Repository not found.');
    }
    if (!tmTextUnitIds.length) {
      throw new Error('Save at least one active source string before running AI translate.');
    }
    if (!targetLocaleTags.length) {
      throw new Error('Repository has no target locales.');
    }

    const config = aiTranslateConfigQuery.data ?? (await fetchAiTranslateConfig());
    const pollable = await translateRepository({
      repositoryName: repository.name,
      targetBcp47tags: targetLocaleTags,
      sourceTextMaxCountPerLocale: Math.max(tmTextUnitIds.length, 1),
      tmTextUnitIds,
      useBatch: false,
      useModel: config.modelName || null,
      promptSuffix: null,
      relatedStringsType: config.relatedStringsType,
      translateType: config.translateType,
      statusFilter: config.statusFilter,
      importStatus: config.importStatus,
      reasoningEffort: config.reasoningEffort || null,
      textVerbosity: config.textVerbosity || null,
      glossaryName: null,
      glossaryTermSource: null,
      glossaryTermSourceDescription: null,
      glossaryTermTarget: null,
      glossaryTermTargetDescription: null,
      glossaryTermDoNotTranslate: false,
      glossaryTermCaseSensitive: false,
      glossaryOnlyMatchedTextUnits: false,
      dryRun: false,
      timeoutSeconds: null,
    });
    const completedTask = await waitForPollableTaskToFinish(pollable.id, AI_TRANSLATE_TIMEOUT_MS);
    if (completedTask.errorMessage) {
      throw new Error(completedTask.errorMessage);
    }
  };

  const applyStatsigPullResult = (result: ApiJsonConfigExtractForRepositoryResult) => {
    cacheJsonConfigLocalizationSetup(result.setup);
    if (setupIdFromUrl !== result.setup.id) {
      selectSetupInUrl(result.setup, { replace: true });
    }
    const storedDraft = toStoredStatsigDraft(result.setup);
    const extractedStrings = toExtractedDraftStrings(result.strings ?? [], draftStrings.length);
    const nextDraftStrings = extractedStrings.length
      ? mergeExtractedDraftStrings(draftStrings, extractedStrings)
      : draftStrings;

    setProvider('STATSIG');
    setProviderConfigId(storedDraft.providerConfigId);
    setSchemaText(storedDraft.schemaText);
    setSourceConfigText(storedDraft.sourceConfigText);
    setStatsigProfile(storedDraft.profile);
    setOutputLocaleMapping(storedDraft.outputLocaleMapping);
    setStatsigWarnings(result.warnings ?? []);
    setDraftStrings(nextDraftStrings);
    setSelectedClientId(extractedStrings[0]?.clientId ?? nextDraftStrings[0]?.clientId ?? null);
    setExportFormat(
      isLocalizedConfigFormat(storedDraft.profile) ? 'localized-config' : 'locale-map',
    );

    return {
      extractedCount: result.strings?.length ?? 0,
      nextDraftStrings,
      profile: storedDraft.profile,
      sourceConfigText: storedDraft.sourceConfigText,
      setupLastModifiedDate: result.setup.lastModifiedDate ?? null,
    };
  };

  const saveSetupMutation = useMutation({
    mutationFn: () =>
      saveJsonConfigLocalizationSetup({
        assetPath: assetPathDraft.trim() || DEFAULT_JSON_CONFIG_LOCALIZATION_ASSET_PATH,
      }),
    onSuccess: async (savedSetup) => {
      const savedAssetPath =
        savedSetup.assetPath?.trim() || DEFAULT_JSON_CONFIG_LOCALIZATION_ASSET_PATH;
      setAssetPathDraft(savedAssetPath);
      if (savedAssetPath !== assetPathFromUrl) {
        setAssetPath(savedAssetPath);
      }
      setStatusNotice({ kind: 'success', message: 'Saved JSON config setup.' });
      cacheJsonConfigLocalizationSetup(savedSetup);
      await queryClient.invalidateQueries({ queryKey: ['json-config-localizations'] });
      await queryClient.invalidateQueries({
        queryKey: ['json-config-localizations', 'setup', savedSetup.id, 'automation-runs'],
      });
    },
    onError: (error: Error) => {
      setStatusNotice({
        kind: 'error',
        message: error.message || 'Failed to save JSON config setup.',
      });
    },
  });

  const saveMutation = useMutation({
    mutationFn: async () => {
      const stringsToSave =
        selectedString && selectedString.used
          ? draftStrings.filter(
              (sourceString) =>
                sourceString.tmTextUnitId != null ||
                sourceString.clientId === selectedString.clientId,
            )
          : draftStrings.filter((sourceString) => sourceString.tmTextUnitId != null);
      return saveStringsFromStringsTab(stringsToSave);
    },
    onSuccess: async ({ syncedConfig }) => {
      setStatusNotice({
        kind: 'success',
        message: syncedConfig ? 'Saved string and updated Config JSON.' : 'Saved source string.',
      });
      await queryClient.invalidateQueries({ queryKey: ['json-config-localization'] });
      await queryClient.invalidateQueries({ queryKey: ['json-config-localizations'] });
      await queryClient.invalidateQueries({
        queryKey: ['json-config-localization', 'localized-config-export'],
      });
      if (jsonConfigLocalizationQuery.data?.id != null) {
        await queryClient.invalidateQueries({
          queryKey: [
            'json-config-localizations',
            'setup',
            jsonConfigLocalizationQuery.data.id,
            'automation-runs',
          ],
        });
      }
    },
    onError: (error: Error) => {
      setStatusNotice({
        kind: 'error',
        message: error.message || 'Failed to save source strings.',
      });
    },
  });

  const translateMutation = useMutation({
    mutationFn: async (scope: TranslateScope) => {
      const label = scope === 'selected' ? 'selected string' : 'active strings';
      setTranslateProgress(
        markStatsigSyncStep(
          createStatsigSyncProgress({
            pull: false,
            extract: false,
            translate: true,
            merge: false,
            saveConfig: false,
            push: false,
          }),
          'TRANSLATE',
          'RUNNING',
          `Starting AI translation for ${label}.`,
        ),
      );
      const tmTextUnitIds = getTranslateTextUnitIds(scope, selectedString, draftStrings);
      await translateTextUnitIds(tmTextUnitIds);
      return { scope, count: tmTextUnitIds.length };
    },
    onSuccess: async ({ scope, count }) => {
      const label = scope === 'selected' ? 'selected string' : 'active strings';
      setStatusNotice({
        kind: 'success',
        message: scope === 'selected' ? 'AI translated selected string.' : 'AI translated strings.',
      });
      setTranslateProgress((current) =>
        markStatsigSyncStep(
          current,
          'TRANSLATE',
          'DONE',
          `Started AI translation for ${count} ${label}.`,
        ),
      );
      await queryClient.invalidateQueries({ queryKey: ['json-config-localization', 'targets'] });
    },
    onError: (error: Error) => {
      setStatusNotice({ kind: 'error', message: error.message || 'AI translate failed.' });
      setTranslateProgress((current) =>
        markStatsigSyncStep(current, 'TRANSLATE', 'ERROR', error.message || 'AI translate failed.'),
      );
    },
  });

  const saveAndTranslateMutation = useMutation({
    mutationFn: async () => {
      return saveAndTranslateActiveStrings();
    },
    onSuccess: async (translatedCount) => {
      setStatusNotice({
        kind: 'success',
        message: `Saved and started AI translation for ${translatedCount} strings.`,
      });
      await queryClient.invalidateQueries({ queryKey: ['json-config-localization'] });
      await queryClient.invalidateQueries({
        queryKey: ['json-config-localization', 'localized-config-export'],
      });
      if (jsonConfigLocalizationQuery.data?.id != null) {
        await queryClient.invalidateQueries({
          queryKey: [
            'json-config-localizations',
            'setup',
            jsonConfigLocalizationQuery.data.id,
            'automation-runs',
          ],
        });
      }
    },
    onError: (error: Error) => {
      setStatusNotice({ kind: 'error', message: error.message || 'Save and translate failed.' });
    },
  });

  const pullStatsigMutation = useMutation<
    ApiJsonConfigExtractForRepositoryResult,
    Error,
    boolean | undefined
  >({
    mutationFn: async (extract = true) => {
      if (!repository) {
        throw new Error('Repository not found.');
      }
      const trimmedConfigId = providerConfigId.trim();
      if (!trimmedConfigId) {
        throw new Error('Provide a Statsig config id.');
      }

      const input = {
        configId: trimmedConfigId,
        assetPath: assetPathFromUrl,
        profile: statsigProfile,
        outputLocaleMappingJson: JSON.stringify(normalizedOutputLocaleMapping),
        extract,
        expectedLastModifiedDate: jsonConfigLocalizationQuery.data?.lastModifiedDate,
      };
      const currentSetupId = jsonConfigLocalizationQuery.data?.id;
      return currentSetupId == null
        ? pullJsonConfigFromStatsig(repository.id, input)
        : pullJsonConfigFromStatsigForSetup(currentSetupId, input);
    },
    onSuccess: async (result) => {
      const { extractedCount } = applyStatsigPullResult(result);
      setStatusNotice({
        kind: 'success',
        message: extractedCount
          ? `Pulled Statsig config and extracted ${extractedCount} source strings.`
          : 'Pulled Statsig config.',
      });
      await queryClient.invalidateQueries({ queryKey: ['json-config-localization'] });
      await queryClient.invalidateQueries({ queryKey: ['json-config-localizations'] });
    },
    onError: (error: Error) => {
      setStatusNotice({
        kind: 'error',
        message: error.message || 'Failed to pull Statsig config.',
      });
    },
  });

  const runStatsigSyncMutation = useMutation({
    mutationFn: async (options: StatsigSyncOptions) => {
      if (!repository) {
        throw new Error('Repository not found.');
      }
      const trimmedConfigId = providerConfigId.trim();
      if ((options.pull || options.push) && !trimmedConfigId) {
        throw new Error('Provide a Statsig config id.');
      }
      if (
        !options.pull &&
        !options.extract &&
        !options.translate &&
        !options.merge &&
        !options.saveConfig &&
        !options.push
      ) {
        throw new Error('Select at least one sync step.');
      }

      setStatsigSyncProgress(createStatsigSyncProgress(options));
      let extractedCount = 0;
      let extractedStrings = draftStrings;
      let workingProfile = statsigProfile;
      let workingSourceConfigText = sourceConfigText;
      let workingExpectedLastModifiedDate =
        jsonConfigLocalizationQuery.data?.lastModifiedDate ?? null;

      if (options.pull) {
        setStatsigSyncProgress((current) =>
          markStatsigSyncStep(current, 'PULL', 'RUNNING', 'Pulling Statsig config.'),
        );
        const pullInput = {
          configId: trimmedConfigId,
          assetPath: assetPathFromUrl,
          profile: statsigProfile,
          outputLocaleMappingJson: JSON.stringify(normalizedOutputLocaleMapping),
          extract: options.extract,
          expectedLastModifiedDate: workingExpectedLastModifiedDate,
        };
        const pullProgress = {
          onPulled: () => {
            setStatsigSyncProgress((current) =>
              markStatsigSyncStep(current, 'PULL', 'DONE', 'Pulled Statsig dynamic config.'),
            );
            if (options.extract) {
              setStatsigSyncProgress((current) =>
                markStatsigSyncStep(current, 'EXTRACT', 'RUNNING', 'Extracting source strings.'),
              );
            }
          },
          onExtracting: () => {
            if (options.extract) {
              setStatsigSyncProgress((current) =>
                markStatsigSyncStep(
                  current,
                  'EXTRACT',
                  'RUNNING',
                  'Saving extracted strings in Mojito.',
                ),
              );
            }
          },
        };
        const currentSetupId = jsonConfigLocalizationQuery.data?.id;
        const pullResult =
          currentSetupId == null
            ? await pullJsonConfigFromStatsig(repository.id, pullInput, pullProgress)
            : await pullJsonConfigFromStatsigForSetup(currentSetupId, pullInput, pullProgress);
        const appliedPullResult = applyStatsigPullResult(pullResult);
        extractedCount = appliedPullResult.extractedCount;
        extractedStrings = appliedPullResult.nextDraftStrings;
        workingProfile = appliedPullResult.profile;
        workingSourceConfigText = appliedPullResult.sourceConfigText;
        workingExpectedLastModifiedDate = appliedPullResult.setupLastModifiedDate;
      } else {
        setStatsigSyncProgress((current) =>
          markStatsigSyncStep(current, 'PULL', 'SKIPPED', 'Statsig pull skipped.'),
        );
      }

      if (options.extract) {
        if (!options.pull) {
          setStatsigSyncProgress((current) =>
            markStatsigSyncStep(current, 'EXTRACT', 'RUNNING', 'Extracting source strings.'),
          );
          const extractionResult = await extractSourceConfigDraftStrings();
          extractedCount = extractionResult.extractedStrings.length;
          extractedStrings = extractionResult.nextDraftStrings;
          workingProfile = extractionResult.extraction.profile;
          workingSourceConfigText = formatJsonText(extractionResult.extraction.sourceConfigJson);
          const savedExtraction = await saveDraftStringsToJsonConfig(extractedStrings, {
            sourceConfigJson: extractionResult.extraction.sourceConfigJson,
            profile: extractionResult.extraction.profile,
            expectedLastModifiedDate: workingExpectedLastModifiedDate,
          });
          workingExpectedLastModifiedDate = savedExtraction.setup.lastModifiedDate ?? null;
        }
        setStatsigSyncProgress((current) =>
          markStatsigSyncStep(
            current,
            'EXTRACT',
            'DONE',
            `Extracted ${extractedCount} source strings.`,
          ),
        );
      } else {
        setStatsigSyncProgress((current) =>
          markStatsigSyncStep(current, 'EXTRACT', 'SKIPPED', 'String extraction skipped.'),
        );
      }

      let translatedCount = 0;
      if (options.translate) {
        setStatsigSyncProgress((current) =>
          markStatsigSyncStep(current, 'TRANSLATE', 'RUNNING', 'Starting AI translation.'),
        );
        const savedRows = await searchTextUnits({
          repositoryIds: [repositoryId],
          localeTags: [sourceLocaleTag],
          textSearch: buildAssetPathSearch(assetPathFromUrl),
          usedFilter: 'USED',
          limit: SOURCE_SEARCH_LIMIT,
        });
        const tmTextUnitIds = savedRows.map((row) => row.tmTextUnitId);
        await translateTextUnitIds(tmTextUnitIds);
        translatedCount = tmTextUnitIds.length;
        setStatsigSyncProgress((current) =>
          markStatsigSyncStep(
            current,
            'TRANSLATE',
            'DONE',
            `Started AI translation for ${translatedCount} strings.`,
          ),
        );
      } else {
        setStatsigSyncProgress((current) =>
          markStatsigSyncStep(current, 'TRANSLATE', 'SKIPPED', 'AI translation skipped.'),
        );
      }

      const warnings: string[] = [];
      let mergedConfigJson: string | null = null;
      if (options.merge) {
        setStatsigSyncProgress((current) =>
          markStatsigSyncStep(current, 'MERGE', 'RUNNING', 'Merging translations into config.'),
        );
        const mergedConfig = buildLocalizedConfigFromDraftStrings(extractedStrings, {
          sourceConfigJson: workingSourceConfigText,
          profile: workingProfile,
        });
        mergedConfigJson = mergedConfig.sourceConfigJson;
        workingSourceConfigText = mergedConfigJson;
        warnings.push(...mergedConfig.warnings);
        setSourceConfigText(mergedConfigJson);
        setStatsigWarnings((current) =>
          Array.from(new Set([...current, ...mergedConfig.warnings])),
        );
        setStatsigSyncProgress((current) =>
          markStatsigSyncStep(current, 'MERGE', 'DONE', 'Merged translations into config.'),
        );
      } else {
        setStatsigSyncProgress((current) =>
          markStatsigSyncStep(current, 'MERGE', 'SKIPPED', 'Config merge skipped.'),
        );
      }

      if (options.saveConfig) {
        setStatsigSyncProgress((current) =>
          markStatsigSyncStep(current, 'SAVE_CONFIG', 'RUNNING', 'Saving config in Mojito.'),
        );
        const savedSetup = await saveJsonConfigLocalizationSetup({
          sourceConfigJson: mergedConfigJson ?? workingSourceConfigText,
          expectedLastModifiedDate: workingExpectedLastModifiedDate,
        });
        workingExpectedLastModifiedDate = savedSetup.lastModifiedDate ?? null;
        setStatsigSyncProgress((current) =>
          markStatsigSyncStep(current, 'SAVE_CONFIG', 'DONE', 'Saved config in Mojito.'),
        );
      } else {
        setStatsigSyncProgress((current) =>
          markStatsigSyncStep(current, 'SAVE_CONFIG', 'SKIPPED', 'Config save skipped.'),
        );
      }

      const pushResult = options.push
        ? await (async () => {
            setStatsigSyncProgress((current) =>
              markStatsigSyncStep(current, 'PUSH', 'RUNNING', 'Pushing saved config to Statsig.'),
            );
            const result = await pushSavedConfigToStatsig();
            warnings.push(...result.warnings);
            setStatsigSyncProgress((current) =>
              markStatsigSyncStep(
                current,
                'PUSH',
                'DONE',
                result.skipped
                  ? `Statsig config ${result.configId} already matched; skipped push.`
                  : `Pushed Statsig config ${result.configId}.`,
              ),
            );
            return result;
          })()
        : null;
      if (!options.push) {
        setStatsigSyncProgress((current) =>
          markStatsigSyncStep(current, 'PUSH', 'SKIPPED', 'Statsig push skipped.'),
        );
      }

      return {
        pushResult,
        extractedCount,
        translatedCount,
        warnings: Array.from(new Set(warnings)),
        options,
      };
    },
    onSuccess: async ({ pushResult, extractedCount, translatedCount, warnings, options }) => {
      setStatsigWarnings((current) => Array.from(new Set([...current, ...warnings])));
      const completedSteps = [
        options.pull ? 'pulled Statsig config' : null,
        options.extract ? `extracted ${extractedCount} strings` : null,
        options.translate ? `started AI translation for ${translatedCount}` : null,
        options.merge ? 'merged translations into config' : null,
        options.saveConfig ? 'saved config in Mojito' : null,
        pushResult ? `pushed Statsig config ${pushResult.configId}` : null,
      ].filter(Boolean);
      setStatusNotice({
        kind: 'success',
        message: `${completedSteps.join(', ')}.`,
      });
      setStatsigSyncProgress((current) =>
        current ? { ...current, message: `${completedSteps.join(', ')}.` } : current,
      );
      await queryClient.invalidateQueries({ queryKey: ['json-config-localization'] });
      await queryClient.invalidateQueries({ queryKey: ['json-config-localizations'] });
      await queryClient.invalidateQueries({
        queryKey: ['json-config-localization', 'localized-config-export'],
      });
      if (jsonConfigLocalizationQuery.data?.id != null) {
        await queryClient.invalidateQueries({
          queryKey: [
            'json-config-localizations',
            'setup',
            jsonConfigLocalizationQuery.data.id,
            'automation-runs',
          ],
        });
      }
    },
    onError: (error: Error) => {
      setStatsigSyncProgress((current) =>
        markRunningStatsigSyncStepError(current, error.message || 'Automation failed.'),
      );
      setStatusNotice({
        kind: 'error',
        message: error.message || 'Automation failed.',
      });
    },
  });

  const updateSelectedString = (patch: Partial<JsonConfigLocalizationDraftString>) => {
    if (!selectedClientId) {
      return;
    }
    setDraftStrings((current) =>
      current.map((sourceString) =>
        sourceString.clientId === selectedClientId ? { ...sourceString, ...patch } : sourceString,
      ),
    );
  };

  const addString = () => {
    const nextString = createEmptyDraftString(draftStrings.length);
    setDraftStrings((current) => sortJsonConfigLocalizationDraftStrings([nextString, ...current]));
    setSelectedClientId(nextString.clientId);
    setStatusNotice(null);
  };

  const addConfigEntry = () => {
    try {
      const result = appendStatsigSourceConfigEntry(schemaText, sourceConfigText, statsigProfile);
      setSourceConfigText(result.sourceConfigJson);
      setStatsigProfile(result.profile);
      setStatsigWarnings(result.warnings);
      const focusField =
        result.profile.format === 'FORMATJS_MAP' ||
        result.profile.format === 'FORMATJS_MULTILINGUAL_MAP'
          ? result.profile.sourceField
          : result.profile.translatableFields[0];
      if (focusField) {
        const needle = `"${focusField}": ""`;
        setPendingConfigFocusTarget({
          needle,
          offset: needle.length - 1,
        });
      }
      setStatusNotice({
        kind: 'success',
        message: `Added config entry ${result.itemKey}. Add more if needed, then fill source text and publish with Merge and Save.`,
      });
      return result;
    } catch (error) {
      setStatusNotice({
        kind: 'error',
        message: error instanceof Error ? error.message : 'Unable to add config entry.',
      });
      return null;
    }
  };

  const addConfigString = () => {
    try {
      const draftBackedConfigText = sourceConfigText.trim()
        ? JSON.stringify(
            buildStatsigSourceConfigExport(
              statsigProfile,
              parseJsonRecord(sourceConfigText, 'config'),
              [],
              draftStrings,
              {},
              normalizedOutputLocaleMapping,
            ).value,
          )
        : sourceConfigText;
      const result = appendStatsigSourceConfigEntry(
        schemaText,
        draftBackedConfigText,
        statsigProfile,
      );
      setStatsigProfile(result.profile);
      setStatsigWarnings(result.warnings);
      const existingByStringId = new Map(
        draftStrings.map((sourceString) => [sourceString.stringId, sourceString]),
      );
      const newDraftStrings = result.stringIds
        .filter((stringId) => !existingByStringId.has(stringId))
        .map((stringId, index) => ({
          ...createEmptyDraftString(draftStrings.length + index),
          stringId,
        }));
      if (newDraftStrings.length) {
        setDraftStrings((current) =>
          sortJsonConfigLocalizationDraftStrings([...newDraftStrings, ...current]),
        );
      }
      const selectedExistingString = result.stringIds
        .map((stringId) => existingByStringId.get(stringId))
        .find((sourceString): sourceString is JsonConfigLocalizationDraftString =>
          Boolean(sourceString),
        );
      const selectedDraft = newDraftStrings[0] ?? selectedExistingString;
      setSelectedClientId(selectedDraft?.clientId ?? selectedClientId);
      setStatusNotice({
        kind: 'success',
        message: `Added draft config entry ${result.itemKey}. Add more if needed, then save the strings or publish with Merge translations and Save.`,
      });
    } catch (error) {
      setStatusNotice({
        kind: 'error',
        message: error instanceof Error ? error.message : 'Unable to add config entry.',
      });
      return;
    }
  };

  const detectStatsigMapping = async () => {
    try {
      const { profile, warnings } = await detectJsonConfigMapping(schemaText);
      setStatsigProfile(profile);
      setStatsigWarnings(warnings);
      setMappingNotice({
        kind: 'success',
        message: formatDetectedMappingMessage(profile),
      });
    } catch (error) {
      setMappingNotice({
        kind: 'error',
        message: error instanceof Error ? error.message : 'Unable to detect schema mapping.',
      });
    }
  };

  const extractSourceConfigDraftStrings = async () => {
    const extraction = await extractJsonConfigStrings({
      schemaJson: schemaText,
      sourceConfigJson: sourceConfigText,
      profile: statsigProfile,
    });
    const extractedStrings = toExtractedDraftStrings(extraction.strings, draftStrings.length);
    const nextDraftStrings = mergeExtractedDraftStrings(draftStrings, extractedStrings);
    setStatsigProfile(extraction.profile);
    setSourceConfigText(formatJsonText(extraction.sourceConfigJson));
    setStatsigWarnings(extraction.warnings);
    setDraftStrings(nextDraftStrings);
    setSelectedClientId(extractedStrings[0]?.clientId ?? nextDraftStrings[0]?.clientId ?? null);
    setExportFormat(
      isLocalizedConfigFormat(extraction.profile) ? 'localized-config' : 'locale-map',
    );
    return { extraction, extractedStrings, nextDraftStrings };
  };

  const saveConfigEditorMutation = useMutation({
    mutationFn: async ({ extract }: { extract: boolean }) => {
      if (!extract) {
        await saveJsonConfigLocalizationSetup({ sourceConfigJson: sourceConfigText });
        return { extract, extractedCount: 0 };
      }

      const { extraction, extractedStrings, nextDraftStrings } =
        await extractSourceConfigDraftStrings();
      await saveDraftStringsToJsonConfig(nextDraftStrings, {
        sourceConfigJson: extraction.sourceConfigJson,
        profile: extraction.profile,
      });
      return { extract, extractedCount: extractedStrings.length };
    },
    onSuccess: async ({ extract, extractedCount }) => {
      setStatusNotice({
        kind: 'success',
        message: extract
          ? `Saved config and extracted ${extractedCount} source strings.`
          : 'Saved config.',
      });
      await queryClient.invalidateQueries({ queryKey: ['json-config-localization'] });
      await queryClient.invalidateQueries({ queryKey: ['json-config-localizations'] });
      await queryClient.invalidateQueries({
        queryKey: ['json-config-localization', 'localized-config-export'],
      });
    },
    onError: (error: Error) => {
      setStatusNotice({
        kind: 'error',
        message: error.message || 'Save config failed.',
      });
    },
  });

  const updateStatsigProfile = (patch: Partial<StatsigSourceConfigProfile>) => {
    setSelectedSchemaPresetId('');
    if (patch.format) {
      setMappingNotice(null);
      setStatsigWarnings([]);
    }
    setStatsigProfile((current) => ({ ...current, ...patch }));
  };

  const updateOutputLocaleMapping = (mojitoLocaleTag: string, outputLocaleTag: string) => {
    setOutputLocaleMapping((current) => ({
      ...current,
      [mojitoLocaleTag]: outputLocaleTag,
    }));
  };

  const applyOutputLocaleMappingImport = () => {
    const { mapping, warnings } = parseOutputLocaleMappingSpec(
      outputLocaleMappingImport,
      targetLocaleTags,
    );
    const appliedCount = Object.keys(mapping).length;

    if (!appliedCount) {
      setStatsigWarnings(warnings);
      setStatusNotice({
        kind: 'error',
        message: warnings[0] ?? 'Paste output:mojito locale mapping pairs first.',
      });
      return;
    }

    setOutputLocaleMapping((current) =>
      normalizeOutputLocaleMapping(targetLocaleTags, {
        ...current,
        ...mapping,
      }),
    );
    setStatsigWarnings(warnings);
    setStatusNotice({
      kind: 'success',
      message: `Applied ${appliedCount} output locale mappings.`,
    });
  };

  const toggleSelectedBundleInclusion = () => {
    if (!selectedString) {
      return;
    }
    if (selectedString.tmTextUnitId == null) {
      const nextSelectedClientId =
        draftStrings.find((sourceString) => sourceString.clientId !== selectedString.clientId)
          ?.clientId ?? null;
      setDraftStrings((current) =>
        current.filter((sourceString) => sourceString.clientId !== selectedString.clientId),
      );
      setSelectedClientId(nextSelectedClientId);
      return;
    }
    updateSelectedString({ used: !selectedString.used });
  };

  const copyExport = async () => {
    try {
      await navigator.clipboard.writeText(exportJson);
      setStatusNotice({ kind: 'success', message: 'Copied export JSON.' });
    } catch {
      setStatusNotice({ kind: 'error', message: 'Unable to copy export JSON.' });
    }
  };

  const refreshExport = async () => {
    setStatusNotice({
      kind: 'info',
      message: 'Refreshing export.',
      spinning: true,
    });
    try {
      const refreshes: Promise<unknown>[] = [];
      if (sourceTmTextUnitIds.length > 0 && targetLocaleTags.length > 0) {
        refreshes.push(targetRowsQuery.refetch());
      }
      if (
        exportFormat === 'localized-config' &&
        jsonConfigLocalizationQuery.data?.sourceConfigJson
      ) {
        refreshes.push(localizedConfigExportQuery.refetch());
      }
      await Promise.all(refreshes);
      setStatusNotice({ kind: 'success', message: 'Refreshed export.' });
    } catch (error) {
      setStatusNotice({
        kind: 'error',
        message: error instanceof Error ? error.message : 'Unable to refresh export.',
      });
    }
  };

  const downloadExport = () => {
    if (!repository) {
      return;
    }

    if (exportFormat === 'locale-files') {
      const encoder = new TextEncoder();
      const zipBytes = buildZipFile(
        localeFileExportFiles.map((file) => ({
          name: file.filename,
          content: encoder.encode(`${JSON.stringify(file.messages, null, 2)}\n`),
        })),
      );
      downloadBlob(
        new Blob([zipBytes], { type: 'application/zip' }),
        buildJsonConfigLocalizationLocaleFilesFilename(repository.name),
      );
      return;
    }

    downloadBlob(
      new Blob([exportJson], { type: 'application/json' }),
      buildJsonConfigLocalizationFilename(repository.name),
    );
  };

  if (repositoriesQuery.isLoading) {
    return (
      <JsonConfigLocalizationShell title="JSON config localization">
        <div className="json-config-localization-page__empty">Loading repository...</div>
      </JsonConfigLocalizationShell>
    );
  }

  if (!repository) {
    return (
      <JsonConfigLocalizationShell title="JSON config localization">
        <div className="json-config-localization-page__empty is-error">Repository not found.</div>
      </JsonConfigLocalizationShell>
    );
  }

  const selectedStringIsMissingFromSourceConfig =
    isSourceConfigBacked &&
    selectedString != null &&
    sourceConfigStringIds != null &&
    !sourceConfigStringIds.has(selectedString.stringId.trim());
  const activeSavedStringIds = draftStrings.filter(
    (sourceString) => sourceString.used && sourceString.tmTextUnitId != null,
  );
  const hasUnsavedDraftStrings = draftStrings.some(
    (sourceString) => sourceString.used && sourceString.tmTextUnitId == null,
  );
  const activeSavedTextUnitIds = activeSavedStringIds.flatMap((sourceString) =>
    sourceString.tmTextUnitId == null ? [] : [sourceString.tmTextUnitId],
  );
  const usedFilterLabel = formatUsedFilter(usedFilter);
  const createdDateQuickRanges = getStandardDateQuickRanges();
  const createdDateFilterLabel = formatCreatedDateFilter(createdAfter, createdBefore);
  const stringFilterSections: FilterSection[] = [
    {
      kind: 'radio',
      label: 'Bundle status',
      options: [
        { value: 'USED', label: 'Active' },
        { value: 'UNUSED', label: 'Unused' },
        { value: 'ALL', label: 'All' },
      ],
      value: usedFilter,
      onChange: (value) => setUsedFilter(value as UsedFilter),
    },
    {
      kind: 'date',
      label: 'Created',
      after: createdAfter,
      before: createdBefore,
      afterLabel: 'Created after',
      beforeLabel: 'Created before',
      onChangeAfter: setCreatedAfter,
      onChangeBefore: setCreatedBefore,
      quickRanges: createdDateQuickRanges,
      onClear: () => {
        setCreatedAfter(null);
        setCreatedBefore(null);
      },
      clearLabel: 'Clear dates',
    },
  ];
  const stringFilterSummary = [usedFilterLabel, createdDateFilterLabel].filter(Boolean).join(' · ');
  const activeStringCount = draftStrings.filter((sourceString) => sourceString.used).length;
  const translationTargetCount = activeStringCount * targetLocaleTags.length;
  const translatedTargetCount = Object.values(readinessByString).reduce(
    (count, readiness) => count + readiness.translatedCount,
    0,
  );
  const reviewedTargetCount = Object.values(readinessByString).reduce(
    (count, readiness) => count + readiness.reviewedCount,
    0,
  );
  const automationRuns = automationRunsQuery.data ?? [];
  const automationSchedulePreviewRuns = automationSchedulePreviewQuery.data?.nextRuns ?? [];
  const automationSchedulePreviewTimeZone =
    automationSchedulePreviewQuery.data?.timeZone?.trim() || automationScheduleTimeZone;
  const automationScheduleDescription = debouncedAutomationCronExpression
    ? formatReviewAutomationSchedule(
        debouncedAutomationCronExpression,
        automationSchedulePreviewTimeZone,
      )
    : 'No schedule';
  const automationNextRunLabel = !automationEnabled
    ? 'Automation disabled'
    : !debouncedAutomationCronExpression
      ? 'No schedule'
      : automationSchedulePreviewQuery.isLoading
        ? 'Calculating next run...'
        : automationSchedulePreviewQuery.isError
          ? 'Unable to calculate next run'
          : automationSchedulePreviewRuns[0]
            ? formatDateTimeInTimeZone(
                automationSchedulePreviewRuns[0],
                automationSchedulePreviewTimeZone,
              )
            : 'No next run';
  const automationUpcomingRunLabels = automationSchedulePreviewRuns
    .slice(1, 3)
    .map((nextRun) => formatDateTimeInTimeZone(nextRun, automationSchedulePreviewTimeZone));
  const automationSchedulePreviewError =
    automationSchedulePreviewQuery.error instanceof Error
      ? automationSchedulePreviewQuery.error.message
      : 'Unable to calculate next run';
  const builtInSchemaOptions: JsonConfigSchemaOption[] = JSON_CONFIG_SCHEMA_PRESETS.map(
    (preset) => ({
      id: `preset:${preset.id}`,
      label: preset.label,
      description: preset.description,
      schemaText: JSON.stringify(preset.schema, null, 2),
      profile: preset.profile,
    }),
  );
  const selectedSchemaPreset =
    builtInSchemaOptions.find((preset) => preset.id === selectedSchemaPresetId) ?? null;
  const mappingFormat = statsigProfile.format ?? 'EMBEDDED_TRANSLATIONS';
  const isFormatJsMapping =
    mappingFormat === 'FORMATJS_MAP' || mappingFormat === 'FORMATJS_MULTILINGUAL_MAP';
  const usesTranslationsField =
    mappingFormat === 'EMBEDDED_TRANSLATIONS' || mappingFormat === 'FORMATJS_MULTILINGUAL_MAP';
  const mappingStatusNotice =
    mappingNotice ??
    (selectedSchemaPreset
      ? {
          kind: 'info' as const,
          message: `Using ${selectedSchemaPreset.label} preset mapping. Save setup to persist it.`,
        }
      : null);
  const applySchemaOption = (schemaOption: JsonConfigSchemaOption) => {
    pendingSchemaPresetTextRef.current = schemaOption.schemaText;
    setSelectedSchemaPresetId(schemaOption.id);
    setSchemaText(schemaOption.schemaText);
    setStatsigProfile(schemaOption.profile);
    setStatsigWarnings([]);
    setMappingNotice({
      kind: 'info',
      message: `Using ${schemaOption.label} preset mapping. Save setup to persist it.`,
    });
  };
  const handleSchemaPresetChange = (presetId: string) => {
    if (!presetId) {
      setSelectedSchemaPresetId('');
      return;
    }
    const schemaOption = builtInSchemaOptions.find((preset) => preset.id === presetId);
    if (schemaOption) {
      applySchemaOption(schemaOption);
    }
  };
  const isBusy =
    saveMutation.isPending ||
    saveSetupMutation.isPending ||
    saveAndTranslateMutation.isPending ||
    saveConfigEditorMutation.isPending ||
    translateMutation.isPending ||
    pullStatsigMutation.isPending ||
    runStatsigSyncMutation.isPending;
  const effectiveStatsigSyncOptions = statsigSyncOptions;
  const hasStatsigSyncStepSelected = Object.values(effectiveStatsigSyncOptions).some(Boolean);
  const statsigSyncRequiresSavedStrings =
    !effectiveStatsigSyncOptions.extract &&
    (effectiveStatsigSyncOptions.translate ||
      effectiveStatsigSyncOptions.merge ||
      effectiveStatsigSyncOptions.push);
  const canRunStatsigSync =
    isStatsigProvider &&
    hasStatsigSyncStepSelected &&
    (!statsigSyncRequiresSavedStrings || !hasUnsavedDraftStrings) &&
    (!effectiveStatsigSyncOptions.pull || Boolean(providerConfigId.trim())) &&
    (!effectiveStatsigSyncOptions.push || Boolean(providerConfigId.trim())) &&
    !isBusy;
  const canSaveAutomationSetup =
    isStatsigProvider &&
    (!automationEnabled || Boolean(automationCronExpression.trim())) &&
    !isBusy;
  const effectivePublishConfigOptions: StatsigSyncOptions = {
    pull: false,
    extract: false,
    translate: publishConfigOptions.translate && targetLocaleTags.length > 0,
    merge: publishConfigOptions.merge,
    saveConfig: publishConfigOptions.saveConfig,
    push: publishConfigOptions.push,
  };
  const hasPublishConfigStepSelected = Object.values(effectivePublishConfigOptions).some(Boolean);
  const canRunPublishConfig =
    isStatsigProvider &&
    hasPublishConfigStepSelected &&
    !hasUnsavedDraftStrings &&
    (!effectivePublishConfigOptions.push || Boolean(providerConfigId.trim())) &&
    !isBusy;
  const canSaveConfigEditor = Boolean(sourceConfigText.trim()) && !isBusy;
  const isStatsigSyncRunning =
    runStatsigSyncMutation.isPending &&
    statsigSyncProgress != null &&
    Object.values(statsigSyncProgress.steps).some(
      (status) => status === 'RUNNING' || status === 'PENDING',
    );
  const runConfigEditorSave = () => {
    if (!canSaveConfigEditor) {
      return;
    }
    saveConfigEditorMutation.mutate({ extract: extractOnConfigSave });
  };
  const openActiveStringsInWorkbench = () => {
    if (!repository || !activeSavedTextUnitIds.length || !targetLocaleTags.length) {
      return;
    }
    void navigate('/workbench', {
      state: {
        workbenchSearch: {
          repositoryIds: [repository.id],
          localeTags: targetLocaleTags,
          tmTextUnitIds: activeSavedTextUnitIds,
          usedFilter: 'USED',
          limit: Math.min(
            TARGET_SEARCH_LIMIT,
            Math.max(10, activeSavedTextUnitIds.length * Math.max(targetLocaleTags.length, 1)),
          ),
          offset: 0,
        },
      },
    });
  };
  const openTextUnitInWorkbench = (tmTextUnitId: number) => {
    if (!repository || !targetLocaleTags.length) {
      return;
    }
    const params = new URLSearchParams();
    params.set('tmTextUnitId', String(tmTextUnitId));
    params.set('repo', String(repository.id));
    targetLocaleTags.forEach((localeTag) => params.append('locale', localeTag));
    void navigate(`/workbench?${params.toString()}`);
  };
  const createActiveStringsReviewProject = () => {
    if (!repository || !activeSavedTextUnitIds.length) {
      return;
    }
    void navigate(`/review-projects/new?scope=repositories&repositoryIds=${repository.id}`, {
      state: {
        sourceMode: 'REPOSITORIES',
        repositoryIds: [repository.id],
        defaultName: `${repository.name} JSON config review`,
        statusFilter: 'REVIEW_NEEDED',
      },
    });
  };
  const extractionMappingSummary =
    mappingFormat === 'FORMATJS_MAP'
      ? [
          statsigProfile.collectionKey ? `map ${statsigProfile.collectionKey}` : 'root map',
          statsigProfile.sourceField,
          statsigProfile.commentField,
        ]
          .filter(Boolean)
          .join(' / ')
      : mappingFormat === 'FORMATJS_MULTILINGUAL_MAP'
        ? [
            statsigProfile.collectionKey ? `map ${statsigProfile.collectionKey}` : 'root map',
            statsigProfile.sourceField,
            statsigProfile.commentField,
            statsigProfile.translationsField,
          ]
            .filter(Boolean)
            .join(' / ')
        : [
            statsigProfile.collectionKey,
            statsigProfile.translationsField,
            statsigProfile.sourceLocaleTag,
            statsigProfile.translatableFields.join(', '),
          ]
            .filter(Boolean)
            .join(' / ');
  const activeStatusNotice: StatusNotice | null = saveConfigEditorMutation.isPending
    ? {
        kind: 'info',
        message: extractOnConfigSave ? 'Saving config and extracting strings.' : 'Saving config.',
        spinning: true,
      }
    : statusNotice;
  const hasStringFilters =
    Boolean(stringSearch.trim()) ||
    usedFilter !== 'USED' ||
    Boolean(createdAfter) ||
    Boolean(createdBefore);
  const clearStringFilters = () => {
    setStringSearch('');
    setUsedFilter('ALL');
    setCreatedAfter(null);
    setCreatedBefore(null);
  };
  const stringEmptyTitle = hasStringFilters ? 'No matching strings' : 'No strings yet';
  const stringEmptyMessage = hasStringFilters
    ? 'Try a different status, date range, or search term.'
    : isSourceConfigBacked
      ? 'Add an entry here, or use Config to paste, pull, and extract JSON.'
      : 'Add a source string to start authoring translations.';
  const showStringListEmpty = hasStringFilters || Boolean(selectedString);
  const publishConfigStrip = isStatsigProvider ? (
    <div className="json-config-localization-page__publish-strip">
      <strong>Publish config</strong>
      <label className="settings-toggle">
        <input
          type="checkbox"
          checked={publishConfigOptions.translate}
          disabled={isBusy || !targetLocaleTags.length}
          onChange={(event) =>
            setPublishConfigOptions((current) => ({
              ...current,
              translate: event.target.checked,
            }))
          }
        />
        AI translate
      </label>
      <label className="settings-toggle">
        <input
          type="checkbox"
          checked={publishConfigOptions.merge}
          disabled={isBusy}
          onChange={(event) =>
            setPublishConfigOptions((current) => ({
              ...current,
              merge: event.target.checked,
            }))
          }
        />
        Merge translations into config
      </label>
      <label className="settings-toggle">
        <input
          type="checkbox"
          checked={publishConfigOptions.saveConfig}
          disabled={isBusy}
          onChange={(event) =>
            setPublishConfigOptions((current) => ({
              ...current,
              saveConfig: event.target.checked,
            }))
          }
        />
        Save config in Mojito
      </label>
      <label className="settings-toggle">
        <input
          type="checkbox"
          checked={publishConfigOptions.push}
          disabled={isBusy}
          onChange={(event) =>
            setPublishConfigOptions((current) => ({
              ...current,
              push: event.target.checked,
            }))
          }
        />
        Push config to Statsig
      </label>
      <button
        type="button"
        className="settings-button"
        onClick={() => runStatsigSyncMutation.mutate(effectivePublishConfigOptions)}
        disabled={!canRunPublishConfig}
      >
        {runStatsigSyncMutation.isPending ? 'Running...' : 'Run selected'}
      </button>
      {hasUnsavedDraftStrings ? (
        <span className="settings-hint">Save strings before publishing config.</span>
      ) : null}
    </div>
  ) : null;
  const renderStatsigSyncProgress = (
    visibleStepIds: StatsigSyncStepId[] = PUBLISH_CONFIG_STEP_IDS,
    title = 'Publish progress',
  ) =>
    statsigSyncProgress ? (
      <StatsigSyncProgressPanel
        progress={statsigSyncProgress}
        title={title}
        visibleStepIds={visibleStepIds}
        onDismiss={() => setStatsigSyncProgress(null)}
      />
    ) : null;
  const statusToastElement = activeStatusNotice ? (
    <div
      className={`json-config-localization-page__notice is-${activeStatusNotice.kind}`}
      role={activeStatusNotice.kind === 'error' ? 'alert' : 'status'}
    >
      {activeStatusNotice.spinning ? (
        <span className="json-config-localization-page__notice-spinner" aria-hidden="true" />
      ) : null}
      <span>{activeStatusNotice.message}</span>
      {!activeStatusNotice.spinning ? (
        <button type="button" onClick={() => setStatusNotice(null)} aria-label="Dismiss message">
          x
        </button>
      ) : null}
    </div>
  ) : null;
  return (
    <div className="settings-subpage json-config-localization-page">
      <SettingsSubpageHeader
        backTo="/settings/system/json-config-localization"
        backLabel="Select repository"
        context="JSON config localization"
        title={repository.name}
        centerContent={
          <nav
            className="json-config-localization-page__topbar-tabs"
            aria-label="JSON config localization sections"
          >
            {jsonConfigLocalizationTabs.map((tab) => (
              <button
                key={tab.id}
                type="button"
                className={`json-config-localization-page__topbar-tab${
                  activeTab === tab.id ? ' is-active' : ''
                }`}
                onClick={() => {
                  if (tab.id === 'CONFIG') {
                    try {
                      syncSourceConfigFromDraftStrings();
                    } catch (error) {
                      setStatusNotice({
                        kind: 'error',
                        message:
                          error instanceof Error
                            ? error.message
                            : 'Unable to update Config JSON from strings.',
                      });
                    }
                  }
                  setActiveTab(tab.id);
                }}
              >
                {tab.label}
              </button>
            ))}
          </nav>
        }
        rightContent={
          setupOptions.length > 1 || statsigConsoleUrl ? (
            <div className="json-config-localization-page__topbar-actions">
              {setupOptions.length > 1 ? (
                <select
                  className="settings-input json-config-localization-page__setup-select"
                  value={selectedSetupId ?? ''}
                  aria-label="Select JSON config setup"
                  onChange={(event) => handleSetupSelectionChange(Number(event.target.value))}
                >
                  {setupOptions.map((setup) => (
                    <option key={setup.id} value={setup.id}>
                      {setup.name || setup.providerConfigId || setup.assetPath}
                    </option>
                  ))}
                </select>
              ) : null}
              {statsigConsoleUrl ? (
                <a
                  className="settings-button"
                  href={statsigConsoleUrl}
                  target="_blank"
                  rel="noreferrer"
                >
                  Open in Statsig
                </a>
              ) : null}
            </div>
          ) : null
        }
      />
      <div className="json-config-localization-page__workspace">
        {statusToastElement}
        {activeTab === 'SYNC' ? (
          <section
            className="json-config-localization-page__tab-panel"
            aria-labelledby="json-config-localization-sync"
          >
            <div className="json-config-localization-page__section-header">
              <div>
                <h2 id="json-config-localization-sync">Automation</h2>
                <p className="settings-hint">
                  Schedule publish steps from Mojito's saved config, or run them now.
                </p>
              </div>
            </div>
            {isStatsigProvider ? (
              <div className="json-config-localization-page__sync-grid">
                <div className="json-config-localization-page__sync-primary">
                  <div className="json-config-localization-page__automation-card">
                    <div className="json-config-localization-page__automation-card-header">
                      <div>
                        <strong>Schedule</strong>
                        <p className="settings-hint">
                          Runs the selected publish steps on a Quartz cron schedule.
                        </p>
                      </div>
                      <label className="settings-toggle">
                        <input
                          type="checkbox"
                          checked={automationEnabled}
                          disabled={isBusy}
                          onChange={(event) => setAutomationEnabled(event.target.checked)}
                        />
                        Enabled
                      </label>
                    </div>
                    <div className="json-config-localization-page__automation-schedule-grid">
                      <label className="settings-field">
                        <span className="settings-field__label">Cron expression</span>
                        <input
                          className="settings-input"
                          value={automationCronExpression}
                          placeholder="0 0 2 * * ?"
                          disabled={isBusy}
                          onChange={(event) => setAutomationCronExpression(event.target.value)}
                        />
                      </label>
                      <label className="settings-field">
                        <span className="settings-field__label">Time zone</span>
                        <input
                          className="settings-input"
                          value={automationTimeZone}
                          placeholder="UTC"
                          disabled={isBusy}
                          onChange={(event) => setAutomationTimeZone(event.target.value)}
                        />
                      </label>
                    </div>
                    <div className="json-config-localization-page__automation-presets">
                      {JSON_CONFIG_AUTOMATION_CRON_PRESETS.map((preset) => (
                        <button
                          key={preset.value}
                          type="button"
                          className={`settings-pill${
                            automationCronExpression === preset.value ? ' is-active' : ''
                          }`}
                          disabled={isBusy}
                          onClick={() => setAutomationCronExpression(preset.value)}
                        >
                          {preset.label}
                        </button>
                      ))}
                    </div>
                    <div
                      className="json-config-localization-page__automation-next-run"
                      title={
                        automationSchedulePreviewQuery.isError
                          ? automationSchedulePreviewError
                          : automationScheduleDescription
                      }
                      aria-live="polite"
                    >
                      <span className="settings-field__label">Next run</span>
                      <strong>{automationNextRunLabel}</strong>
                      <span className="settings-hint">{automationScheduleDescription}</span>
                      {automationUpcomingRunLabels.length > 0 ? (
                        <span className="settings-hint">
                          Then {automationUpcomingRunLabels.join(', ')}
                        </span>
                      ) : null}
                    </div>
                  </div>
                  <div className="json-config-localization-page__automation-card">
                    <div>
                      <strong>Steps</strong>
                      <p className="settings-hint">
                        Defaults publish Mojito's saved config. Enable pull/extract when Statsig
                        should be the source of truth.
                      </p>
                    </div>
                    <div className="json-config-localization-page__sync-options">
                      <label className="settings-toggle">
                        <input
                          type="checkbox"
                          checked={statsigSyncOptions.pull}
                          disabled={isBusy}
                          onChange={(event) =>
                            setStatsigSyncOptions((current) => ({
                              ...current,
                              pull: event.target.checked,
                            }))
                          }
                        />
                        Pull config from Statsig
                      </label>
                      <label className="settings-toggle">
                        <input
                          type="checkbox"
                          checked={statsigSyncOptions.extract}
                          disabled={isBusy}
                          onChange={(event) =>
                            setStatsigSyncOptions((current) => ({
                              ...current,
                              extract: event.target.checked,
                            }))
                          }
                        />
                        Extract strings into Mojito
                      </label>
                      <label className="settings-toggle">
                        <input
                          type="checkbox"
                          checked={statsigSyncOptions.translate}
                          disabled={isBusy}
                          onChange={(event) =>
                            setStatsigSyncOptions((current) => ({
                              ...current,
                              translate: event.target.checked,
                            }))
                          }
                        />
                        AI translate
                      </label>
                      <label className="settings-toggle">
                        <input
                          type="checkbox"
                          checked={statsigSyncOptions.merge}
                          disabled={isBusy}
                          onChange={(event) =>
                            setStatsigSyncOptions((current) => ({
                              ...current,
                              merge: event.target.checked,
                            }))
                          }
                        />
                        Merge translations into config
                      </label>
                      <label className="settings-toggle">
                        <input
                          type="checkbox"
                          checked={statsigSyncOptions.saveConfig}
                          disabled={isBusy}
                          onChange={(event) =>
                            setStatsigSyncOptions((current) => ({
                              ...current,
                              saveConfig: event.target.checked,
                            }))
                          }
                        />
                        Save config in Mojito
                      </label>
                      <label className="settings-toggle">
                        <input
                          type="checkbox"
                          checked={statsigSyncOptions.push}
                          disabled={isBusy}
                          onChange={(event) =>
                            setStatsigSyncOptions((current) => ({
                              ...current,
                              push: event.target.checked,
                            }))
                          }
                        />
                        Push config to Statsig
                      </label>
                    </div>
                  </div>
                  <div className="json-config-localization-page__actions">
                    <button
                      type="button"
                      className="settings-button"
                      onClick={() => saveSetupMutation.mutate()}
                      disabled={!canSaveAutomationSetup}
                    >
                      {saveSetupMutation.isPending ? 'Saving...' : 'Save automation'}
                    </button>
                    <button
                      type="button"
                      className="settings-button settings-button--primary"
                      onClick={() => runStatsigSyncMutation.mutate(effectiveStatsigSyncOptions)}
                      disabled={!canRunStatsigSync}
                    >
                      {runStatsigSyncMutation.isPending
                        ? 'Running automation...'
                        : 'Run automation'}
                    </button>
                  </div>
                  {automationEnabled && !automationCronExpression.trim() ? (
                    <p className="settings-hint">Add a cron expression before saving automation.</p>
                  ) : null}
                  {hasUnsavedDraftStrings ? (
                    <p className="settings-hint">Save strings before running publish steps.</p>
                  ) : null}
                </div>
                <div className="json-config-localization-page__summary-grid">
                  <div className="json-config-localization-page__summary-item">
                    <strong>{activeStringCount}</strong>
                    <span>Active strings</span>
                  </div>
                  <div className="json-config-localization-page__summary-item">
                    <strong>
                      {translatedTargetCount}/{translationTargetCount}
                    </strong>
                    <span>Translated targets</span>
                  </div>
                  <div className="json-config-localization-page__summary-item">
                    <strong>
                      {reviewedTargetCount}/{translationTargetCount}
                    </strong>
                    <span>Reviewed targets</span>
                  </div>
                </div>
              </div>
            ) : (
              <div className="json-config-localization-page__empty">
                Generic JSON has no provider to sync with. Use Export for manual downloads, or use
                the JSON config API/CLI from CI.
              </div>
            )}
            {renderStatsigSyncProgress(JSON_CONFIG_AUTOMATION_STEP_IDS, 'Automation progress')}
            {isStatsigProvider ? (
              <AutomationRunsTable
                runs={automationRuns}
                isLoading={automationRunsQuery.isLoading}
                isRefreshing={automationRunsQuery.isFetching && !automationRunsQuery.isLoading}
                onRefresh={() => {
                  void automationRunsQuery.refetch();
                }}
              />
            ) : null}
            <WarningCallout title="Config warning" warnings={statsigWarnings} />
          </section>
        ) : null}

        {activeTab === 'CONFIG' ? (
          <section className="json-config-localization-page__schema-panel" aria-label="Config">
            {publishConfigStrip}
            {renderStatsigSyncProgress(PUBLISH_CONFIG_STEP_IDS)}
            <div className="settings-field json-config-localization-page__config-editor-field">
              <JsonCodeEditor
                ref={configEditorRef}
                ariaLabel="Config JSON"
                className="json-config-localization-page__config-editor"
                collapseLocaleKeysExcept={
                  collapseLocalizedConfig ? statsigProfile.sourceLocaleTag || 'en-US' : null
                }
                height="clamp(24rem, 58vh, 44rem)"
                minHeight="24rem"
                value={sourceConfigText}
                onChange={(nextValue) => {
                  setSourceConfigText(nextValue);
                }}
                onSave={canSaveConfigEditor ? runConfigEditorSave : undefined}
                placeholder='{"items":[{"id":"welcome","translations":{"en-US":{"title":"...","body":"..."}}}]}'
                toolbarPosition="bottom"
                toolbarActions={
                  <div className="json-config-localization-page__config-editor-toolbar">
                    <div className="json-config-localization-page__config-editor-toolbar-group">
                      <button
                        type="button"
                        className="json-code-editor__history-button json-code-editor__history-button--secondary json-code-editor__history-button--pill"
                        onClick={addConfigEntry}
                        disabled={isBusy}
                      >
                        Add entry
                      </button>
                      {isStatsigProvider ? (
                        <button
                          type="button"
                          className="json-code-editor__history-button json-code-editor__history-button--secondary json-code-editor__history-button--pill"
                          onClick={() => pullStatsigMutation.mutate(false)}
                          disabled={isBusy || !providerConfigId.trim()}
                        >
                          {pullStatsigMutation.isPending
                            ? 'Pulling...'
                            : 'Pull config from Statsig'}
                        </button>
                      ) : null}
                      {statsigConsoleUrl ? (
                        <a
                          className="json-code-editor__history-button json-code-editor__history-button--secondary json-code-editor__history-button--pill"
                          href={statsigConsoleUrl}
                          target="_blank"
                          rel="noreferrer"
                        >
                          Open in Statsig
                        </a>
                      ) : null}
                    </div>
                    <div className="json-config-localization-page__config-editor-toolbar-group">
                      <label className="json-code-editor__toolbar-toggle">
                        <input
                          type="checkbox"
                          checked={collapseLocalizedConfig}
                          disabled={isBusy}
                          onChange={(event) => setCollapseLocalizedConfig(event.target.checked)}
                        />
                        Collapse localized
                      </label>
                      <label className="json-code-editor__toolbar-toggle">
                        <input
                          type="checkbox"
                          checked={extractOnConfigSave}
                          disabled={isBusy}
                          onChange={(event) => setExtractOnConfigSave(event.target.checked)}
                        />
                        Extract on save
                      </label>
                      <button
                        type="button"
                        className="json-code-editor__history-button json-code-editor__history-button--primary json-code-editor__history-button--pill"
                        onClick={runConfigEditorSave}
                        disabled={!canSaveConfigEditor}
                      >
                        Save
                      </button>
                    </div>
                  </div>
                }
              />
            </div>
            <details className="json-config-localization-page__config-details">
              <summary>
                <span className="json-config-localization-page__config-details-title">
                  Schema and mapping
                </span>
                <span className="json-config-localization-page__config-details-summary">
                  {extractionMappingSummary || 'No mapping configured'}
                </span>
              </summary>
              <div className="json-config-localization-page__schema-layout">
                <div className="json-config-localization-page__schema-controls">
                  <div className="json-config-localization-page__schema-preset-panel">
                    <div className="json-config-localization-page__schema-preset-row">
                      <label className="settings-field">
                        <span className="settings-field__label">Schema preset</span>
                        <select
                          className="settings-input"
                          value={selectedSchemaPresetId}
                          onChange={(event) => handleSchemaPresetChange(event.target.value)}
                          disabled={isBusy}
                        >
                          <option value="">Custom schema</option>
                          <optgroup label="Built-in">
                            {builtInSchemaOptions.map((preset) => (
                              <option key={preset.id} value={preset.id}>
                                {preset.label}
                              </option>
                            ))}
                          </optgroup>
                        </select>
                      </label>
                    </div>
                    {selectedSchemaPreset ? (
                      <p className="json-config-localization-page__schema-preset-description">
                        {selectedSchemaPreset.description}
                      </p>
                    ) : null}
                  </div>
                  <div className="json-config-localization-page__mapping-panel">
                    <div className="json-config-localization-page__mapping-header">
                      <h3>Extraction mapping</h3>
                      {selectedSchemaPreset ? (
                        <span className="json-config-localization-page__mapping-preset-pill">
                          Preset mapping
                        </span>
                      ) : mappingFormat === 'EMBEDDED_TRANSLATIONS' ? (
                        <button
                          type="button"
                          className="settings-button"
                          onClick={() => {
                            void detectStatsigMapping();
                          }}
                          disabled={isBusy || !schemaText.trim()}
                        >
                          Guess embedded mapping
                        </button>
                      ) : (
                        <span className="json-config-localization-page__mapping-preset-pill">
                          Manual mapping
                        </span>
                      )}
                    </div>
                    {mappingStatusNotice ? (
                      <div
                        className={`json-config-localization-page__status-notice is-${mappingStatusNotice.kind}`}
                        role={mappingStatusNotice.kind === 'error' ? 'alert' : 'status'}
                      >
                        {mappingStatusNotice.message}
                      </div>
                    ) : null}
                    <div className="json-config-localization-page__profile-grid">
                      <label className="settings-field">
                        <span className="settings-field__label">Mapping type</span>
                        <select
                          className="settings-input"
                          value={mappingFormat}
                          onChange={(event) =>
                            updateStatsigProfile({
                              format: event.target.value as StatsigSourceConfigProfile['format'],
                            })
                          }
                        >
                          <option value="EMBEDDED_TRANSLATIONS">Embedded translations</option>
                          <option value="FLAT_SOURCE_ARRAY">Flat source array</option>
                          <option value="FORMATJS_MAP">FormatJS message map</option>
                          <option value="FORMATJS_MULTILINGUAL_MAP">
                            Multilingual FormatJS message map
                          </option>
                        </select>
                      </label>
                      {isFormatJsMapping ? (
                        <label className="settings-field">
                          <span className="settings-field__label">Message map key</span>
                          <input
                            className="settings-input"
                            value={statsigProfile.collectionKey}
                            placeholder="Blank for root, e.g. $..messages or $.surface..messages"
                            onChange={(event) =>
                              updateStatsigProfile({ collectionKey: event.target.value })
                            }
                          />
                          <span className="json-config-localization-page__muted">
                            Supports this JSONPath subset: <code>$..messages</code> for recursive
                            object search, <code>$.surface..messages</code> to scope it, and{' '}
                            <code>*</code> for one object level. Arrays are not traversed.
                          </span>
                        </label>
                      ) : (
                        <label className="settings-field">
                          <span className="settings-field__label">Collection key</span>
                          <input
                            className="settings-input"
                            value={statsigProfile.collectionKey}
                            onChange={(event) =>
                              updateStatsigProfile({ collectionKey: event.target.value })
                            }
                          />
                        </label>
                      )}
                      {!isFormatJsMapping ? (
                        <label className="settings-field">
                          <span className="settings-field__label">Item id field</span>
                          <input
                            className="settings-input"
                            value={statsigProfile.itemIdField}
                            onChange={(event) =>
                              updateStatsigProfile({ itemIdField: event.target.value })
                            }
                          />
                        </label>
                      ) : null}
                      {usesTranslationsField ? (
                        <label className="settings-field">
                          <span className="settings-field__label">Translations field</span>
                          <input
                            className="settings-input"
                            value={statsigProfile.translationsField}
                            onChange={(event) =>
                              updateStatsigProfile({ translationsField: event.target.value })
                            }
                          />
                        </label>
                      ) : null}
                      {mappingFormat === 'EMBEDDED_TRANSLATIONS' ? (
                        <label className="settings-field">
                          <span className="settings-field__label">Output source locale</span>
                          <input
                            className="settings-input"
                            value={statsigProfile.sourceLocaleTag}
                            onChange={(event) =>
                              updateStatsigProfile({ sourceLocaleTag: event.target.value })
                            }
                          />
                        </label>
                      ) : null}
                      {mappingFormat !== 'EMBEDDED_TRANSLATIONS' ? (
                        <label className="settings-field">
                          <span className="settings-field__label">Source field</span>
                          <input
                            className="settings-input"
                            value={statsigProfile.sourceField ?? ''}
                            onChange={(event) =>
                              updateStatsigProfile({ sourceField: event.target.value })
                            }
                          />
                        </label>
                      ) : null}
                      {mappingFormat !== 'EMBEDDED_TRANSLATIONS' ? (
                        <label className="settings-field">
                          <span className="settings-field__label">Source comment field</span>
                          <input
                            className="settings-input"
                            value={statsigProfile.commentField ?? ''}
                            onChange={(event) =>
                              updateStatsigProfile({ commentField: event.target.value })
                            }
                          />
                        </label>
                      ) : null}
                      {mappingFormat === 'EMBEDDED_TRANSLATIONS' ? (
                        <label className="settings-field json-config-localization-page__profile-fields">
                          <span className="settings-field__label">Translatable fields</span>
                          <input
                            className="settings-input"
                            value={statsigProfile.translatableFields.join(', ')}
                            onChange={(event) =>
                              updateStatsigProfile({
                                translatableFields: parseTranslatableFields(event.target.value),
                              })
                            }
                          />
                        </label>
                      ) : null}
                    </div>
                    <MappingNoteCallout warnings={statsigWarnings} />
                  </div>
                </div>
                <div className="settings-field json-config-localization-page__schema-editor-column">
                  <span className="settings-field__label">JSON schema</span>
                  <JsonCodeEditor
                    ariaLabel="JSON schema"
                    className="json-config-localization-page__schema-editor"
                    height="clamp(26rem, 42vh, 36rem)"
                    minHeight="24rem"
                    showHistoryControls
                    value={schemaText}
                    onChange={(nextValue) => {
                      const isPresetApplyChange = pendingSchemaPresetTextRef.current === nextValue;
                      setSchemaText(nextValue);
                      if (isPresetApplyChange) {
                        pendingSchemaPresetTextRef.current = null;
                      } else {
                        pendingSchemaPresetTextRef.current = null;
                        setSelectedSchemaPresetId('');
                        setMappingNotice(null);
                      }
                    }}
                    placeholder='{"type":"object","properties":{"items":{"type":"array","items":...}}}'
                  />
                </div>
              </div>
            </details>
          </section>
        ) : null}

        {activeTab === 'STRINGS' ? (
          <section
            className="json-config-localization-page__tab-panel json-config-localization-page__tab-panel--strings"
            aria-labelledby="json-config-localization-strings"
          >
            <h2
              id="json-config-localization-strings"
              className="json-config-localization-page__sr-only"
            >
              Strings
            </h2>
            {publishConfigStrip}
            {!isStatsigSyncRunning
              ? renderStatsigSyncProgress(PUBLISH_CONFIG_STEP_IDS, 'Publish progress')
              : null}
            <div
              className={`json-config-localization-page__blocked-region ${
                isStatsigSyncRunning ? 'is-blocked' : ''
              }`}
              aria-busy={isStatsigSyncRunning}
            >
              {isStatsigSyncRunning && statsigSyncProgress ? (
                <div className="json-config-localization-page__blocking-progress">
                  <StatsigSyncProgressPanel
                    progress={statsigSyncProgress}
                    title="Publish progress"
                    visibleStepIds={PUBLISH_CONFIG_STEP_IDS}
                    onDismiss={() => setStatsigSyncProgress(null)}
                  />
                </div>
              ) : null}
              <ResizableMasterDetailLayout
                className="json-config-localization-page__string-layout"
                storageKey={STRING_LIST_WIDTH_STORAGE_KEY}
                sidebarLabel="Source strings"
                detailLabel="String editor"
                resizeLabel="Resize source string list"
                sidebarClassName="json-config-localization-page__list-pane"
                detailClassName="json-config-localization-page__detail-pane"
                sidebar={
                  <>
                    <div className="json-config-localization-page__list-toolbar">
                      <SearchControl
                        value={stringSearch}
                        onChange={setStringSearch}
                        placeholder="Search strings"
                        inputAriaLabel="Search source strings"
                        className="json-config-localization-page__list-search"
                      />
                      <MultiSectionFilterChip
                        sections={stringFilterSections}
                        align="left"
                        summary={stringFilterSummary}
                        ariaLabel="Filter source strings"
                        className="json-config-localization-page__filter-chip"
                        classNames={{
                          button: 'json-config-localization-page__filter-chip-button',
                          panel: 'json-config-localization-page__filter-chip-panel',
                        }}
                        disabled={isBusy}
                      />
                      {isSourceConfigBacked ? (
                        <button
                          type="button"
                          className="settings-button settings-button--primary"
                          onClick={addConfigString}
                          disabled={isBusy}
                        >
                          Add entry
                        </button>
                      ) : (
                        <button
                          type="button"
                          className="settings-button settings-button--primary"
                          onClick={addString}
                        >
                          Add string
                        </button>
                      )}
                    </div>
                    <div className="json-config-localization-page__string-list">
                      {sourceRowsQuery.isLoading ? (
                        <div className="json-config-localization-page__empty">
                          Loading strings...
                        </div>
                      ) : visibleStrings.length ? (
                        <VirtualList
                          scrollRef={stringListScrollRef}
                          items={stringVirtualItems}
                          totalSize={stringListTotalSize}
                          renderRow={(virtualItem: VirtualItem) => {
                            const sourceString = visibleStrings[virtualItem.index];
                            if (!sourceString) {
                              return null;
                            }
                            return {
                              key: virtualItem.key,
                              props: {
                                ref: measureStringRow,
                              },
                              content: (
                                <StringListRow
                                  sourceString={sourceString}
                                  readiness={readinessByString[sourceString.clientId]}
                                  isActive={sourceString.clientId === selectedClientId}
                                  isDraft={sourceString.tmTextUnitId == null}
                                  onSelect={() => setSelectedClientId(sourceString.clientId)}
                                />
                              ),
                            };
                          }}
                        />
                      ) : showStringListEmpty ? (
                        <div className="json-config-localization-page__list-empty">
                          <strong>{stringEmptyTitle}</strong>
                          <span>{stringEmptyMessage}</span>
                        </div>
                      ) : (
                        <div aria-hidden="true" />
                      )}
                    </div>
                  </>
                }
                detail={
                  <>
                    {selectedString ? (
                      <>
                        <div className="json-config-localization-page__editor-header">
                          <div>
                            <div className="json-config-localization-page__eyebrow">
                              {selectedString.tmTextUnitId ? (
                                <span className="json-config-localization-page__text-unit-meta">
                                  <span>Text unit {selectedString.tmTextUnitId}</span>
                                  <button
                                    type="button"
                                    className="json-config-localization-page__text-unit-action"
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
                            <h2>{selectedString.stringId || 'Untitled string'}</h2>
                            {selectedString.createdDate ? (
                              <div className="json-config-localization-page__detail-meta">
                                Created {formatDateTime(selectedString.createdDate)}
                              </div>
                            ) : null}
                          </div>
                          <label className="settings-toggle">
                            <input
                              type="checkbox"
                              checked={selectedString.used}
                              onChange={(event) =>
                                updateSelectedString({ used: event.target.checked })
                              }
                            />
                            Include in bundle
                          </label>
                        </div>
                        {selectedStringIsMissingFromSourceConfig ? (
                          <div className="json-config-localization-page__empty is-error">
                            This active string is from an older extraction and is not in the current
                            Config JSON. Extract again after the backend restarts, or remove it from
                            the bundle.
                          </div>
                        ) : null}

                        <div className="json-config-localization-page__editor-grid">
                          <label className="settings-field">
                            <span className="settings-field__label">String id</span>
                            <span className="settings-field__row">
                              <input
                                className="settings-input"
                                value={selectedString.stringId}
                                readOnly={isSourceConfigBacked}
                                onChange={(event) => {
                                  if (!isSourceConfigBacked) {
                                    updateSelectedString({ stringId: event.target.value });
                                  }
                                }}
                                onBlur={() =>
                                  !isSourceConfigBacked
                                    ? updateSelectedString({
                                        stringId: normalizeSourceStringId(selectedString.stringId),
                                      })
                                    : undefined
                                }
                              />
                              {isSourceConfigBacked ? null : (
                                <button
                                  type="button"
                                  className="settings-button"
                                  onClick={() =>
                                    updateSelectedString({
                                      stringId: normalizeSourceStringId(selectedString.stringId),
                                    })
                                  }
                                >
                                  Normalize
                                </button>
                              )}
                            </span>
                            {isSourceConfigBacked ? (
                              <span className="settings-hint">
                                Generated from Config JSON and extraction mapping.
                              </span>
                            ) : null}
                          </label>
                          <label className="settings-field">
                            <span className="settings-field__label">English source</span>
                            <textarea
                              className="settings-input json-config-localization-page__textarea"
                              value={selectedString.source}
                              readOnly={false}
                              onChange={(event) => {
                                updateSelectedString({ source: event.target.value });
                              }}
                            />
                            {isSourceConfigBacked ? (
                              <span className="settings-hint">
                                Save this string to create the Mojito text unit and update Config
                                JSON.
                              </span>
                            ) : null}
                          </label>
                          <label className="settings-field">
                            <span className="settings-field__label">Source comment</span>
                            <textarea
                              className="settings-input json-config-localization-page__textarea json-config-localization-page__textarea--compact"
                              value={selectedString.comment}
                              readOnly={false}
                              onChange={(event) => {
                                updateSelectedString({ comment: event.target.value });
                              }}
                            />
                          </label>
                          <label className="settings-toggle">
                            <input
                              type="checkbox"
                              checked={selectedString.doNotTranslate}
                              onChange={(event) =>
                                updateSelectedString({ doNotTranslate: event.target.checked })
                              }
                            />
                            Do not translate
                          </label>
                        </div>

                        <div className="json-config-localization-page__actions">
                          <button
                            type="button"
                            className="settings-button settings-button--primary"
                            onClick={() => saveMutation.mutate()}
                            disabled={isBusy || !draftStrings.length}
                          >
                            Save
                          </button>
                          <button
                            type="button"
                            className="settings-button"
                            onClick={toggleSelectedBundleInclusion}
                          >
                            {selectedString.tmTextUnitId == null
                              ? 'Discard draft'
                              : selectedString.used
                                ? 'Remove from bundle'
                                : 'Restore to bundle'}
                          </button>
                        </div>
                      </>
                    ) : (
                      <div className="json-config-localization-page__empty-state-card">
                        <div>
                          <h3>{stringEmptyTitle}</h3>
                          <p>{stringEmptyMessage}</p>
                        </div>
                        <div className="json-config-localization-page__empty-state-actions">
                          {hasStringFilters ? (
                            <button
                              type="button"
                              className="settings-button"
                              onClick={clearStringFilters}
                              disabled={isBusy}
                            >
                              Show all strings
                            </button>
                          ) : null}
                          {isSourceConfigBacked ? (
                            <>
                              <button
                                type="button"
                                className="settings-button settings-button--primary"
                                onClick={addConfigString}
                                disabled={isBusy}
                              >
                                Add entry
                              </button>
                              <button
                                type="button"
                                className="settings-button"
                                onClick={() => setActiveTab('CONFIG')}
                                disabled={isBusy}
                              >
                                Open Config
                              </button>
                              {isStatsigProvider ? (
                                <button
                                  type="button"
                                  className="settings-button"
                                  onClick={() => pullStatsigMutation.mutate(true)}
                                  disabled={isBusy || !providerConfigId.trim()}
                                >
                                  Pull from Statsig
                                </button>
                              ) : null}
                            </>
                          ) : (
                            <button
                              type="button"
                              className="settings-button settings-button--primary"
                              onClick={addString}
                              disabled={isBusy}
                            >
                              Add string
                            </button>
                          )}
                        </div>
                      </div>
                    )}
                  </>
                }
              />
            </div>
          </section>
        ) : null}

        {activeTab === 'TRANSLATIONS' ? (
          <section
            className="json-config-localization-page__tab-panel"
            aria-labelledby="json-config-localization-translations"
          >
            <h2
              id="json-config-localization-translations"
              className="json-config-localization-page__sr-only"
            >
              Translations
            </h2>
            <div className="json-config-localization-page__summary-grid">
              <div className="json-config-localization-page__summary-item">
                <strong>{activeStringCount}</strong>
                <span>Active strings</span>
                <div className="json-config-localization-page__summary-action">
                  <button
                    type="button"
                    className="settings-button"
                    onClick={openActiveStringsInWorkbench}
                    disabled={!activeSavedTextUnitIds.length || !targetLocaleTags.length}
                  >
                    Open in Workbench
                  </button>
                </div>
              </div>
              <div className="json-config-localization-page__summary-item">
                <strong>
                  {translatedTargetCount}/{translationTargetCount}
                </strong>
                <span>Translated targets</span>
                <div className="json-config-localization-page__summary-action">
                  <button
                    type="button"
                    className="settings-button settings-button--primary"
                    onClick={() => translateMutation.mutate('active')}
                    disabled={isBusy || !activeSavedStringIds.length || !targetLocaleTags.length}
                  >
                    AI translate
                  </button>
                </div>
              </div>
              <div className="json-config-localization-page__summary-item">
                <strong>
                  {reviewedTargetCount}/{translationTargetCount}
                </strong>
                <span>Reviewed targets</span>
                <div className="json-config-localization-page__summary-action">
                  <button
                    type="button"
                    className="settings-button"
                    onClick={createActiveStringsReviewProject}
                    disabled={!activeSavedTextUnitIds.length}
                  >
                    Create review project
                  </button>
                </div>
              </div>
            </div>
            {translateProgress ? (
              <StatsigSyncProgressPanel
                progress={translateProgress}
                title="AI translate progress"
                visibleStepIds={['TRANSLATE']}
                onDismiss={() => setTranslateProgress(null)}
              />
            ) : null}
          </section>
        ) : null}

        {activeTab === 'EXPORT' && !isStatsigProvider ? (
          <section
            className="json-config-localization-page__export"
            aria-labelledby="json-config-localization-export"
          >
            <div className="json-config-localization-page__section-header">
              <div>
                <h2 id="json-config-localization-export">Export</h2>
                <p className="settings-hint">
                  Exports active strings only. Missing target translations are omitted so clients
                  can fall back to source.
                </p>
              </div>
              <div className="settings-actions">
                <select
                  className="settings-input json-config-localization-page__export-format"
                  value={exportFormat}
                  onChange={(event) => setExportFormat(event.target.value as ExportFormat)}
                  aria-label="Export format"
                >
                  {exportFormatOptions.map((option) => (
                    <option key={option.value} value={option.value}>
                      {option.label}
                    </option>
                  ))}
                </select>
                <button
                  type="button"
                  className="settings-button"
                  onClick={() => {
                    void refreshExport();
                  }}
                  disabled={localizedConfigExportQuery.isFetching || targetRowsQuery.isFetching}
                >
                  Refresh
                </button>
                <button
                  type="button"
                  className="settings-button"
                  onClick={() => {
                    void copyExport();
                  }}
                >
                  Copy JSON
                </button>
                <button type="button" className="settings-button" onClick={downloadExport}>
                  Download
                </button>
              </div>
            </div>
            <WarningCallout title="Export warning" warnings={exportPayload.warnings} />
            <JsonCodeEditor
              ariaLabel="Export JSON"
              className="json-config-localization-page__export-output"
              minHeight="16rem"
              readOnly
              value={exportJson}
            />
          </section>
        ) : null}

        {activeTab === 'SETUP' ? (
          <section
            className="json-config-localization-page__schema-panel"
            aria-labelledby="json-config-localization-setup"
          >
            <div className="json-config-localization-page__section-header">
              <div>
                <h2 id="json-config-localization-setup">Setup</h2>
                <p className="settings-hint">
                  Configure the integration, storage defaults, and how Mojito locales map back to
                  output locale keys.
                </p>
              </div>
            </div>
            <div className="json-config-localization-page__provider-grid">
              <label className="settings-field">
                <span className="settings-field__label">Integration type</span>
                <select
                  className="settings-input"
                  value={provider}
                  onChange={(event) => setProvider(event.target.value as JsonConfigProvider)}
                >
                  <option value="STATSIG">Statsig dynamic config</option>
                  <option value="GENERIC_JSON">Generic JSON</option>
                </select>
              </label>
              {isStatsigProvider ? (
                <div className="settings-field">
                  <span className="settings-field__label">Statsig config ID</span>
                  <div className="json-config-localization-page__provider-config-row">
                    <input
                      className="settings-input"
                      value={providerConfigId}
                      onChange={(event) => setProviderConfigId(event.target.value)}
                      placeholder="dynamic_config_id"
                    />
                    {statsigConsoleUrl ? (
                      <a
                        className="settings-button"
                        href={statsigConsoleUrl}
                        target="_blank"
                        rel="noreferrer"
                      >
                        Open in Statsig
                      </a>
                    ) : null}
                  </div>
                </div>
              ) : null}
              <label className="settings-field json-config-localization-page__asset-path">
                <span className="settings-field__label">Asset path</span>
                <input
                  className="settings-input"
                  value={assetPathDraft}
                  onChange={(event) => setAssetPathDraft(event.target.value)}
                />
              </label>
            </div>
            {targetLocaleTags.length ? (
              <section className="json-config-localization-page__locale-mapping">
                <div className="json-config-localization-page__locale-mapping-header">
                  <div>
                    <h3>Output locale mapping</h3>
                    <p className="settings-hint">
                      Map Mojito locale tags to the keys written into localized config.
                    </p>
                  </div>
                  <div className="json-config-localization-page__locale-mapping-summary">
                    <strong>{customOutputLocaleMappingRows.length}</strong>
                    <span>custom</span>
                    <span>{identityOutputLocaleMappingCount} default</span>
                  </div>
                </div>
                <div className="json-config-localization-page__locale-mapping-import">
                  <label className="settings-field json-config-localization-page__locale-mapping-import-field">
                    <span className="settings-field__label">Bulk mapping</span>
                    <input
                      className="settings-input"
                      value={outputLocaleMappingImport}
                      onChange={(event) => setOutputLocaleMappingImport(event.target.value)}
                      placeholder="-lm 'bg-BG:bg,de-DE:de,fr-FR:fr,zh-TW:zh-Hant'"
                    />
                  </label>
                  <div className="json-config-localization-page__locale-mapping-actions">
                    <button
                      type="button"
                      className="settings-button"
                      onClick={applyOutputLocaleMappingImport}
                    >
                      Apply
                    </button>
                    <button
                      type="button"
                      className="settings-button"
                      onClick={() => setShowAllOutputLocaleMappings((current) => !current)}
                    >
                      {showAllOutputLocaleMappings ? 'Show custom' : 'Show all'}
                    </button>
                  </div>
                </div>
                {visibleOutputLocaleMappingRows.length ? (
                  <div className="json-config-localization-page__locale-mapping-grid">
                    {visibleOutputLocaleMappingRows.map(
                      ({ mojitoLocaleTag, outputLocaleTag, isCustom }) => (
                        <div
                          key={mojitoLocaleTag}
                          className="json-config-localization-page__locale-mapping-row"
                        >
                          <span title={`Mojito locale ${mojitoLocaleTag}`}>{mojitoLocaleTag}</span>
                          <input
                            className="settings-input"
                            aria-label={`Output locale key for ${mojitoLocaleTag}`}
                            value={outputLocaleTag}
                            onChange={(event) =>
                              updateOutputLocaleMapping(mojitoLocaleTag, event.target.value)
                            }
                          />
                          <button
                            type="button"
                            className="json-config-localization-page__locale-mapping-reset"
                            onClick={() =>
                              updateOutputLocaleMapping(mojitoLocaleTag, mojitoLocaleTag)
                            }
                            disabled={!isCustom}
                          >
                            Reset
                          </button>
                        </div>
                      ),
                    )}
                  </div>
                ) : (
                  <div className="json-config-localization-page__locale-mapping-empty">
                    All output keys currently match Mojito locale tags.
                  </div>
                )}
              </section>
            ) : null}
            <div className="json-config-localization-page__setup-actions">
              <button
                type="button"
                className="settings-button settings-button--primary"
                onClick={() => saveSetupMutation.mutate()}
                disabled={isBusy}
              >
                {saveSetupMutation.isPending ? 'Saving...' : 'Save'}
              </button>
            </div>
          </section>
        ) : null}
      </div>
    </div>
  );
}

function JsonConfigLocalizationShell({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div className="settings-subpage json-config-localization-page">
      <SettingsSubpageHeader
        backTo="/settings/system/json-config-localization"
        backLabel="Select repository"
        title={title}
      />
      <div className="json-config-localization-page__workspace">{children}</div>
    </div>
  );
}

function StringListRow({
  sourceString,
  readiness,
  isActive,
  isDraft,
  onSelect,
}: {
  sourceString: JsonConfigLocalizationDraftString;
  readiness?: {
    translatedCount: number;
    reviewedCount: number;
    totalTargetLocales: number;
  };
  isActive: boolean;
  isDraft: boolean;
  onSelect: () => void;
}) {
  const totalTargetLocales = readiness?.totalTargetLocales ?? 0;
  return (
    <button
      type="button"
      className={`json-config-localization-page__string-row${isActive ? ' is-active' : ''}${
        isDraft ? ' is-draft' : ''
      }`}
      onClick={onSelect}
    >
      <span className="json-config-localization-page__string-row-main">
        <span className="json-config-localization-page__string-id">
          {sourceString.stringId || 'Untitled string'}
        </span>
        <span className="json-config-localization-page__source-preview">
          {sourceString.source === '' ? 'Empty source' : sourceString.source}
        </span>
      </span>
      <span className="json-config-localization-page__string-row-meta">
        {!sourceString.used ? (
          <span className="json-config-localization-page__used-pill is-unused">unused</span>
        ) : null}
        {isDraft ? <span className="json-config-localization-page__draft-pill">new</span> : null}
        <span>
          T {readiness?.translatedCount ?? 0}/{totalTargetLocales}
        </span>
        <span>
          R {readiness?.reviewedCount ?? 0}/{totalTargetLocales}
        </span>
      </span>
    </button>
  );
}

function AutomationRunsTable({
  runs,
  isLoading,
  isRefreshing,
  onRefresh,
}: {
  runs: ApiJsonConfigLocalizationRun[];
  isLoading: boolean;
  isRefreshing: boolean;
  onRefresh: () => void;
}) {
  return (
    <section
      className="json-config-localization-page__automation-runs"
      aria-labelledby="json-config-localization-automation-runs"
    >
      <div className="json-config-localization-page__section-header">
        <div>
          <h3 id="json-config-localization-automation-runs">Recent scheduled runs</h3>
          <p className="settings-hint">Latest backend cron executions for this setup.</p>
        </div>
        <button type="button" className="settings-button" onClick={onRefresh} disabled={isLoading}>
          {isRefreshing ? 'Refreshing...' : 'Refresh'}
        </button>
      </div>
      {isLoading ? (
        <div className="json-config-localization-page__empty">Loading scheduled runs...</div>
      ) : runs.length ? (
        <div className="json-config-localization-page__automation-runs-table-wrap">
          <table className="json-config-localization-page__automation-runs-table">
            <thead>
              <tr>
                <th scope="col">Started</th>
                <th scope="col">Status</th>
                <th scope="col">Steps</th>
                <th scope="col">Result</th>
              </tr>
            </thead>
            <tbody>
              {runs.map((run) => {
                const enabledSteps = formatAutomationRunSteps(run);
                const result = run.errorMessage || run.summary || 'No result recorded.';
                return (
                  <tr key={run.id}>
                    <td>{formatAutomationRunDate(run.startedAt ?? run.createdDate)}</td>
                    <td>
                      <span
                        className={`json-config-localization-page__automation-run-status is-${run.status.toLowerCase()}`}
                      >
                        {formatAutomationRunStatus(run.status)}
                      </span>
                    </td>
                    <td>{enabledSteps || 'No steps selected'}</td>
                    <td>{result}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      ) : (
        <div className="json-config-localization-page__empty">
          No scheduled automation runs yet.
        </div>
      )}
    </section>
  );
}

function StatsigSyncProgressPanel({
  progress,
  title = 'Sync progress',
  visibleStepIds = Object.keys(STATSIG_SYNC_STEP_LABELS) as StatsigSyncStepId[],
  onDismiss,
}: {
  progress: StatsigSyncProgress;
  title?: string;
  visibleStepIds?: StatsigSyncStepId[];
  onDismiss?: () => void;
}) {
  const percent = getStatsigSyncProgressPercent(progress, visibleStepIds);
  const titleId = title.toLowerCase().replace(/\s+/g, '-');
  return (
    <section className="json-config-localization-page__sync-progress" aria-labelledby={titleId}>
      <div className="json-config-localization-page__section-header">
        <div>
          <h3 id={titleId}>{title}</h3>
          <p className="settings-hint">{progress.message ?? 'Ready to run selected steps.'}</p>
        </div>
        {onDismiss ? (
          <button
            type="button"
            className="json-config-localization-page__sync-progress-dismiss"
            onClick={onDismiss}
            aria-label="Dismiss progress"
          >
            x
          </button>
        ) : null}
      </div>
      <div
        className="json-config-localization-page__sync-progress-bar"
        role="progressbar"
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={percent}
      >
        <span style={{ width: `${percent}%` }} />
      </div>
      <div className="json-config-localization-page__sync-step-list">
        {visibleStepIds.map((stepId) => {
          const status = progress.steps[stepId];
          return (
            <div key={stepId} className="json-config-localization-page__sync-step">
              <span>{STATSIG_SYNC_STEP_LABELS[stepId]}</span>
              <span
                className={`json-config-localization-page__sync-step-status is-${status.toLowerCase()}`}
              >
                {formatStatsigSyncStepStatus(status)}
              </span>
            </div>
          );
        })}
      </div>
    </section>
  );
}

function WarningCallout({ title, warnings }: { title: string; warnings: string[] }) {
  if (!warnings.length) {
    return null;
  }
  const uniqueWarnings = Array.from(new Set(warnings));
  const resolvedTitle = uniqueWarnings.length > 1 ? `${title}s` : title;

  return (
    <aside className="json-config-localization-page__warning-callout" role="status">
      <h3>{resolvedTitle}</h3>
      {uniqueWarnings.length === 1 ? (
        <p>{uniqueWarnings[0]}</p>
      ) : (
        <ul>
          {uniqueWarnings.map((warning) => (
            <li key={warning}>{warning}</li>
          ))}
        </ul>
      )}
    </aside>
  );
}

function MappingNoteCallout({ warnings }: { warnings: string[] }) {
  const notes = formatMappingNotes(warnings);
  if (!notes.length) {
    return null;
  }

  return (
    <aside className="json-config-localization-page__mapping-note" role="status">
      <strong>{notes.length > 1 ? 'Mapping notes' : 'Mapping note'}</strong>
      {notes.length === 1 ? (
        <p>{notes[0]}</p>
      ) : (
        <ul>
          {notes.map((note) => (
            <li key={note}>{note}</li>
          ))}
        </ul>
      )}
    </aside>
  );
}

function formatMappingNotes(warnings: string[]): string[] {
  const notes = new Set<string>();
  let usesIndexIds = false;

  warnings.forEach((warning) => {
    if (isIndexBasedIdWarning(warning)) {
      usesIndexIds = true;
      return;
    }
    notes.add(warning);
  });

  if (usesIndexIds) {
    notes.add(
      'No stable item id field was found. Mojito can still extract strings, but ids will use array positions like item.0.title. Add an id field if entries may be reordered.',
    );
  }

  return Array.from(notes);
}

function isIndexBasedIdWarning(warning: string): boolean {
  return warning.includes('index-based string ids') || warning.includes('stable string id field');
}

function toConfiguredRepositoryPickerRow(config: ApiJsonConfigLocalization): RepositoryPickerRow {
  return {
    repositoryId: config.repository.id,
    repositoryName: config.repository.name,
    sourceLocaleTag: config.repository.sourceLocaleTag,
    targetLocaleCount: config.repository.targetLocaleCount,
    assetPath: config.assetPath || DEFAULT_JSON_CONFIG_LOCALIZATION_ASSET_PATH,
    config,
  };
}

function toRepositoryPickerRow(
  repository: ApiRepository,
  config?: ApiJsonConfigLocalization,
): RepositoryPickerRow {
  if (config) {
    return toConfiguredRepositoryPickerRow(config);
  }
  return {
    repositoryId: repository.id,
    repositoryName: repository.name,
    sourceLocaleTag: repository.sourceLocale?.bcp47Tag,
    targetLocaleCount: getNonRootRepositoryLocaleTags(repository).length,
    assetPath: DEFAULT_JSON_CONFIG_LOCALIZATION_ASSET_PATH,
    config: null,
  };
}

function filterRepositoryPickerRows(
  rows: RepositoryPickerRow[],
  filter: RepositorySetupFilter,
): RepositoryPickerRow[] {
  if (filter === 'CONFIGURED') {
    return rows.filter((row) => row.config);
  }
  if (filter === 'NOT_CONFIGURED') {
    return rows.filter((row) => !row.config);
  }
  return rows;
}

function formatRepositorySetupFilter(filter: RepositorySetupFilter): string {
  switch (filter) {
    case 'CONFIGURED':
      return 'Configured';
    case 'NOT_CONFIGURED':
      return 'Not set up';
    case 'ALL':
      return 'All';
  }
}

function formatUsedFilter(filter: UsedFilter): string {
  switch (filter) {
    case 'USED':
      return 'Active';
    case 'UNUSED':
      return 'Unused';
    case 'ALL':
      return 'All';
  }
}

function formatCreatedDateFilter(after: string | null, before: string | null): string | null {
  if (!after && !before) {
    return null;
  }
  if (after && before) {
    return `Created ${formatShortDate(after)} - ${formatShortDate(before)}`;
  }
  if (after) {
    return `Created after ${formatShortDate(after)}`;
  }
  return `Created before ${formatShortDate(before)}`;
}

function formatDateTime(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  return new Intl.DateTimeFormat(undefined, {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(date);
}

function formatDateTimeInTimeZone(value: string, timeZone?: string | null): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  try {
    return new Intl.DateTimeFormat(undefined, {
      dateStyle: 'medium',
      timeStyle: 'short',
      timeZone: timeZone?.trim() || undefined,
    }).format(date);
  } catch {
    return formatDateTime(value);
  }
}

function formatShortDate(value: string | null): string {
  if (!value) {
    return '';
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  return new Intl.DateTimeFormat(undefined, {
    month: 'short',
    day: 'numeric',
  }).format(date);
}

function formatAutomationRunDate(value?: string | null): string {
  if (!value) {
    return 'Unknown';
  }
  return formatDateTime(value);
}

function formatAutomationRunStatus(status: string): string {
  switch (status) {
    case 'RUNNING':
      return 'Running';
    case 'COMPLETED':
      return 'Completed';
    case 'FAILED':
      return 'Failed';
    default:
      return status;
  }
}

function formatAutomationRunSteps(run: ApiJsonConfigLocalizationRun): string {
  return [
    run.pullEnabled ? STATSIG_SYNC_STEP_LABELS.PULL : null,
    run.extractEnabled ? STATSIG_SYNC_STEP_LABELS.EXTRACT : null,
    run.translateEnabled ? STATSIG_SYNC_STEP_LABELS.TRANSLATE : null,
    run.mergeEnabled ? STATSIG_SYNC_STEP_LABELS.MERGE : null,
    run.saveConfigEnabled ? STATSIG_SYNC_STEP_LABELS.SAVE_CONFIG : null,
    run.pushEnabled ? STATSIG_SYNC_STEP_LABELS.PUSH : null,
  ]
    .filter(Boolean)
    .join(', ');
}

function formatRepositorySetupStatus(row: RepositoryPickerRow): string {
  if (!row.config) {
    return 'Not set up';
  }
  return row.config.provider === 'STATSIG' ? 'Statsig' : 'Generic JSON';
}

function formatRepositoryOptionSecondaryLabel(row: RepositoryPickerRow): string {
  if (!row.config) {
    return 'Not set up';
  }
  const setupName = row.config.name?.trim();
  return setupName
    ? `${formatRepositorySetupStatus(row)} setup: ${setupName}`
    : `${formatRepositorySetupStatus(row)} setup`;
}

function buildRepositoryOptionSearchText(row: RepositoryPickerRow): string {
  return [
    row.repositoryName,
    row.config?.name,
    formatRepositorySetupStatus(row),
    row.config ? 'configured setup json config localization' : 'not configured setup',
    row.assetPath,
    row.config?.providerConfigId,
  ]
    .filter(Boolean)
    .join(' ');
}

function nextSetupNameForRepository(repositoryName: string, existingNames: string[]): string {
  const trimmedRepositoryName = repositoryName.trim() || 'JSON config';
  const existingNameSet = new Set(existingNames.map((name) => name.trim()).filter(Boolean));
  if (!existingNameSet.has(trimmedRepositoryName)) {
    return trimmedRepositoryName;
  }

  let index = 2;
  while (existingNameSet.has(`${trimmedRepositoryName} ${index}`)) {
    index += 1;
  }
  return `${trimmedRepositoryName} ${index}`;
}

function defaultAssetPathForSetupName(setupName: string): string {
  const slug = setupName
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '');
  return slug
    ? `json-config-localization/${slug}/strings.json`
    : DEFAULT_JSON_CONFIG_LOCALIZATION_ASSET_PATH;
}

function upsertJsonConfigLocalizationList(
  current: ApiJsonConfigLocalization[] | undefined,
  setup: ApiJsonConfigLocalization,
): ApiJsonConfigLocalization[] {
  const next = [...(current ?? [])];
  const existingIndex = next.findIndex((candidate) => candidate.id === setup.id);
  if (existingIndex >= 0) {
    next[existingIndex] = setup;
  } else {
    next.push(setup);
  }
  return next.sort((left, right) => left.name.localeCompare(right.name) || left.id - right.id);
}

function filterDraftStrings(
  sourceStrings: JsonConfigLocalizationDraftString[],
  search: string,
): JsonConfigLocalizationDraftString[] {
  const query = search.trim().toLowerCase();
  if (!query) {
    return sourceStrings;
  }
  return sourceStrings.filter(
    (sourceString) =>
      sourceString.stringId.toLowerCase().includes(query) ||
      sourceString.source.toLowerCase().includes(query) ||
      sourceString.comment.toLowerCase().includes(query),
  );
}

function buildAssetPathSearch(assetPath: string) {
  return {
    operator: 'AND' as const,
    predicates: [
      {
        field: 'asset' as const,
        searchType: 'exact' as const,
        value: assetPath,
      },
    ],
  };
}

function validateDraftStrings(
  sourceStrings: JsonConfigLocalizationDraftString[],
  assetPath: string,
  sourceConfigStringIds: Set<string> | null = null,
): string | null {
  if (!assetPath.trim()) {
    return 'Provide a virtual asset path.';
  }
  const activeStrings = sourceStrings.filter((sourceString) => sourceString.used);
  const stringIds = activeStrings.map((sourceString) => sourceString.stringId.trim());
  if (stringIds.some((stringId) => !stringId)) {
    return 'Every active string needs a string id.';
  }
  if (new Set(stringIds).size !== stringIds.length) {
    return 'String ids must be unique in the active bundle.';
  }
  if (sourceConfigStringIds) {
    const unmatchedStringIds = stringIds.filter((stringId) => !sourceConfigStringIds.has(stringId));
    if (unmatchedStringIds.length) {
      return `Active string ids must exist in Config JSON. Edit Config and extract again, or remove from bundle: ${unmatchedStringIds
        .slice(0, 5)
        .join(', ')}${unmatchedStringIds.length > 5 ? ', ...' : ''}.`;
    }
  }
  return null;
}

function toJsonConfigString(sourceString: JsonConfigLocalizationDraftString): ApiJsonConfigString {
  return {
    stringId: sourceString.stringId.trim(),
    source: sourceString.source,
    comment: sourceString.comment || null,
    used: sourceString.used,
    doNotTranslate: sourceString.doNotTranslate,
  };
}

function toExtractedDraftStrings(
  strings: ApiJsonConfigString[],
  existingCount: number,
): JsonConfigLocalizationDraftString[] {
  return strings.map((sourceString, index) => ({
    clientId: `draft-${Date.now()}-${existingCount + index}`,
    tmTextUnitId: null,
    assetId: null,
    stringId: sourceString.stringId,
    source: sourceString.source,
    comment: sourceString.comment ?? '',
    used: sourceString.used,
    doNotTranslate: sourceString.doNotTranslate,
  }));
}

function getTranslateTextUnitIds(
  scope: TranslateScope,
  selectedString: JsonConfigLocalizationDraftString | null,
  sourceStrings: JsonConfigLocalizationDraftString[],
): number[] {
  if (scope === 'selected') {
    return selectedString?.tmTextUnitId ? [selectedString.tmTextUnitId] : [];
  }
  return sourceStrings
    .filter((sourceString) => sourceString.used && sourceString.tmTextUnitId != null)
    .map((sourceString) => sourceString.tmTextUnitId)
    .filter((id): id is number => typeof id === 'number');
}

function createStatsigSyncProgress(options: StatsigSyncOptions): StatsigSyncProgress {
  return {
    steps: {
      PULL: options.pull ? 'PENDING' : 'SKIPPED',
      EXTRACT: options.extract ? 'PENDING' : 'SKIPPED',
      TRANSLATE: options.translate ? 'PENDING' : 'SKIPPED',
      MERGE: options.merge ? 'PENDING' : 'SKIPPED',
      SAVE_CONFIG: options.saveConfig ? 'PENDING' : 'SKIPPED',
      PUSH: options.push ? 'PENDING' : 'SKIPPED',
    },
    message: 'Starting automation.',
  };
}

function markStatsigSyncStep(
  progress: StatsigSyncProgress | null,
  stepId: StatsigSyncStepId,
  status: StatsigSyncStepStatus,
  message: string,
): StatsigSyncProgress | null {
  if (!progress) {
    return progress;
  }
  return {
    steps: {
      ...progress.steps,
      [stepId]: status,
    },
    message,
  };
}

function markRunningStatsigSyncStepError(
  progress: StatsigSyncProgress | null,
  message: string,
): StatsigSyncProgress | null {
  if (!progress) {
    return progress;
  }

  const runningStepId = (Object.keys(progress.steps) as StatsigSyncStepId[]).find(
    (stepId) => progress.steps[stepId] === 'RUNNING',
  );
  if (!runningStepId) {
    return {
      ...progress,
      message,
    };
  }
  return markStatsigSyncStep(progress, runningStepId, 'ERROR', message);
}

function getStatsigSyncProgressPercent(
  progress: StatsigSyncProgress,
  visibleStepIds = Object.keys(STATSIG_SYNC_STEP_LABELS) as StatsigSyncStepId[],
): number {
  const statuses = visibleStepIds.map((stepId) => progress.steps[stepId]);
  const completedSteps = statuses.filter(
    (status) => status === 'DONE' || status === 'SKIPPED',
  ).length;
  const runningStep = statuses.some((status) => status === 'RUNNING') ? 0.5 : 0;
  return Math.round(((completedSteps + runningStep) / statuses.length) * 100);
}

function formatStatsigSyncStepStatus(status: StatsigSyncStepStatus): string {
  switch (status) {
    case 'PENDING':
      return 'Pending';
    case 'RUNNING':
      return 'Running';
    case 'DONE':
      return 'Done';
    case 'SKIPPED':
      return 'Skipped';
    case 'ERROR':
      return 'Failed';
  }
}

function formatJsonConfigApiErrorMessage(error: unknown, fallback: string): string {
  const message = error instanceof Error ? error.message : typeof error === 'string' ? error : '';
  if (!message.trim()) {
    return fallback;
  }

  const jsonBody = message.match(/\{.*\}\s*$/s)?.[0];
  if (jsonBody) {
    try {
      const parsed = JSON.parse(jsonBody) as { message?: unknown };
      if (typeof parsed.message === 'string' && parsed.message.trim()) {
        return parsed.message;
      }
    } catch {
      // Keep the original error message when it is not a JSON API error body.
    }
  }

  return message;
}

function formatDetectedMappingMessage(profile: StatsigSourceConfigProfile): string {
  if (profile.format === 'FORMATJS_MULTILINGUAL_MAP') {
    return `Detected multilingual FormatJS messages using "${profile.sourceField || 'defaultMessage'}". Save setup to persist it.`;
  }

  if (profile.format === 'FORMATJS_MAP') {
    return `Detected FormatJS messages using "${profile.sourceField || 'defaultMessage'}". Save setup to persist it.`;
  }

  if (profile.format === 'FLAT_SOURCE_ARRAY') {
    return `Detected ${profile.collectionKey} using "${profile.sourceField || 'source'}". Save setup to persist it.`;
  }

  const fields = profile.translatableFields.join(', ');
  return fields
    ? `Detected ${profile.collectionKey} with fields ${fields}. Save setup to persist it.`
    : `Detected ${profile.collectionKey}. Add translatable fields, then save setup.`;
}

function isLocalizedConfigFormat(profile: StatsigSourceConfigProfile): boolean {
  return (
    profile.format === 'EMBEDDED_TRANSLATIONS' || profile.format === 'FORMATJS_MULTILINGUAL_MAP'
  );
}

function parseTranslatableFields(value: string): string[] {
  return Array.from(
    new Set(
      value
        .split(/[,\n]+/)
        .map((field) => field.trim())
        .filter(Boolean),
    ),
  );
}

function getDefaultTimeZone(): string {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';
  } catch {
    return 'UTC';
  }
}

function normalizeJsonConfigAutomationOptions(
  value: Partial<StatsigSyncOptions> | null | undefined,
): StatsigSyncOptions {
  const normalized = {
    ...DEFAULT_JSON_CONFIG_AUTOMATION_OPTIONS,
    ...value,
  };
  if (!Object.values(normalized).some(Boolean)) {
    return DEFAULT_JSON_CONFIG_AUTOMATION_OPTIONS;
  }
  return normalized;
}

function toStoredStatsigDraft(config: ApiJsonConfigLocalization): StoredStatsigDraft {
  return {
    provider: normalizeJsonConfigProvider(config.provider),
    providerConfigId: config.providerConfigId ?? '',
    schemaText: formatJsonText(config.schemaJson ?? ''),
    sourceConfigText: formatJsonText(config.sourceConfigJson ?? ''),
    profile:
      parseJsonConfigValue(config.extractionMappingJson, isStatsigProfile) ??
      DEFAULT_STATSIG_SOURCE_CONFIG_PROFILE,
    outputLocaleMapping: parseJsonConfigValue(config.outputLocaleMappingJson, isStringRecord) ?? {},
    automationEnabled: Boolean(config.automationEnabled),
    automationCronExpression: config.automationCronExpression ?? '',
    automationTimeZone: config.automationTimeZone || getDefaultTimeZone(),
    automationOptions: normalizeJsonConfigAutomationOptions(
      parseJsonConfigValue(config.automationOptionsJson, isStatsigSyncOptions),
    ),
  };
}

function formatJsonText(value: string | null | undefined): string {
  const text = value ?? '';
  if (!text.trim()) {
    return '';
  }
  try {
    return JSON.stringify(JSON.parse(text), null, 2);
  } catch {
    return text;
  }
}

function parseJsonConfigValue<T>(
  value: string | null | undefined,
  isExpectedValue: (parsed: unknown) => parsed is T,
): T | null {
  if (!value?.trim()) {
    return null;
  }
  try {
    const parsed = JSON.parse(value) as unknown;
    return isExpectedValue(parsed) ? parsed : null;
  } catch {
    return null;
  }
}

function readStoredStatsigDraft(storageKey: string): StoredStatsigDraft | null {
  try {
    const storedValue = window.localStorage.getItem(storageKey);
    if (!storedValue) {
      return null;
    }

    const parsed = JSON.parse(storedValue) as Partial<StoredStatsigDraft>;
    if (
      typeof parsed.schemaText !== 'string' ||
      typeof parsed.sourceConfigText !== 'string' ||
      !isStatsigProfile(parsed.profile)
    ) {
      return null;
    }

    return {
      provider: normalizeJsonConfigProvider(parsed.provider),
      providerConfigId: typeof parsed.providerConfigId === 'string' ? parsed.providerConfigId : '',
      schemaText: formatJsonText(parsed.schemaText),
      sourceConfigText: formatJsonText(parsed.sourceConfigText),
      profile: parsed.profile,
      outputLocaleMapping: isStringRecord(parsed.outputLocaleMapping)
        ? parsed.outputLocaleMapping
        : {},
      automationEnabled: Boolean(parsed.automationEnabled),
      automationCronExpression:
        typeof parsed.automationCronExpression === 'string' ? parsed.automationCronExpression : '',
      automationTimeZone:
        typeof parsed.automationTimeZone === 'string'
          ? parsed.automationTimeZone
          : getDefaultTimeZone(),
      automationOptions: normalizeJsonConfigAutomationOptions(
        isStatsigSyncOptions(parsed.automationOptions) ? parsed.automationOptions : null,
      ),
    };
  } catch {
    return null;
  }
}

function isStatsigProfile(value: unknown): value is StatsigSourceConfigProfile {
  if (typeof value !== 'object' || value == null) {
    return false;
  }

  const candidate = value as Partial<StatsigSourceConfigProfile>;
  return (
    typeof candidate.collectionKey === 'string' &&
    typeof candidate.itemIdField === 'string' &&
    typeof candidate.translationsField === 'string' &&
    typeof candidate.sourceLocaleTag === 'string' &&
    Array.isArray(candidate.translatableFields) &&
    candidate.translatableFields.every((field) => typeof field === 'string')
  );
}

function normalizeJsonConfigProvider(value: unknown): JsonConfigProvider {
  return value === 'STATSIG' ? 'STATSIG' : 'GENERIC_JSON';
}

function isStringRecord(value: unknown): value is Record<string, string> {
  if (typeof value !== 'object' || value == null || Array.isArray(value)) {
    return false;
  }

  return Object.values(value).every((recordValue) => typeof recordValue === 'string');
}

function isStatsigSyncOptions(value: unknown): value is StatsigSyncOptions {
  if (typeof value !== 'object' || value == null || Array.isArray(value)) {
    return false;
  }

  const candidate = value as Partial<StatsigSyncOptions>;
  return ['pull', 'extract', 'translate', 'merge', 'saveConfig', 'push'].every(
    (key) => typeof candidate[key as keyof StatsigSyncOptions] === 'boolean',
  );
}

function parseRepositoryId(value: string | undefined): number | null {
  return parsePositiveInteger(value);
}

function parsePositiveInteger(value: string | null | undefined): number | null {
  if (!value) {
    return null;
  }
  const numericValue = Number(value);
  return Number.isInteger(numericValue) && numericValue > 0 ? numericValue : null;
}
