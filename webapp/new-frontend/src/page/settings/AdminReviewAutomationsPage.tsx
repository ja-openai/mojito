import './settings-page.css';
import './admin-review-automations-page.css';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useMemo, useState } from 'react';
import { Link, Navigate, useNavigate } from 'react-router-dom';

import {
  type ApiReviewAutomationSummary,
  createReviewAutomation,
  deleteReviewAutomation,
  fetchReviewAutomations,
} from '../../api/review-automations';
import { fetchReviewFeatureOptions } from '../../api/review-features';
import { fetchTeams } from '../../api/teams';
import { ConfirmModal } from '../../components/ConfirmModal';
import { Modal } from '../../components/Modal';
import { NumericPresetDropdown } from '../../components/NumericPresetDropdown';
import { useUser } from '../../components/RequireUser';
import { ReviewAutomationScheduleBuilderModal } from '../../components/ReviewAutomationScheduleBuilderModal';
import { ReviewFeatureMultiSelect } from '../../components/ReviewFeatureMultiSelect';
import { SearchControl } from '../../components/SearchControl';
import { SingleSelectDropdown } from '../../components/SingleSelectDropdown';
import {
  DEFAULT_REVIEW_AUTOMATION_CRON_EXPRESSION,
  formatReviewAutomationSchedule,
  getDefaultReviewAutomationTimeZone,
  getReviewAutomationTimeZoneOptions,
} from '../../utils/reviewAutomationSchedule';
import { SettingsSubpageHeader } from './SettingsSubpageHeader';

type StatusNotice = {
  kind: 'success' | 'error';
  message: string;
};

type EnabledFilter = 'enabled' | 'disabled' | 'all';

const LIMIT_PRESETS = [25, 50, 100];
const DEFAULT_MAX_WORD_COUNT = 2000;
const DEFAULT_DUE_DATE_OFFSET_DAYS = 1;

const normalizeAutomationName = (value: string) => value.trim().replace(/\s+/g, ' ');

const parsePositiveIntegerDraft = (value: string) => {
  const trimmed = value.trim();
  if (!trimmed) {
    return { value: null as number | null, valid: false };
  }
  const parsed = Number(trimmed);
  if (!Number.isFinite(parsed) || !Number.isInteger(parsed) || parsed < 1) {
    return { value: null as number | null, valid: false };
  }
  return { value: parsed, valid: true };
};

const parseNonNegativeIntegerDraft = (value: string) => {
  const trimmed = value.trim();
  if (!trimmed) {
    return { value: null as number | null, valid: false };
  }
  const parsed = Number(trimmed);
  if (!Number.isFinite(parsed) || !Number.isInteger(parsed) || parsed < 0) {
    return { value: null as number | null, valid: false };
  }
  return { value: parsed, valid: true };
};

export function AdminReviewAutomationsPage() {
  const user = useUser();
  const isAdmin = user.role === 'ROLE_ADMIN';
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const [searchQuery, setSearchQuery] = useState('');
  const [enabledFilter, setEnabledFilter] = useState<EnabledFilter>('enabled');
  const [limit, setLimit] = useState(50);
  const [isCreateModalOpen, setIsCreateModalOpen] = useState(false);
  const [isCreateScheduleBuilderOpen, setIsCreateScheduleBuilderOpen] = useState(false);
  const [newNameDraft, setNewNameDraft] = useState('');
  const [newEnabledDraft, setNewEnabledDraft] = useState(true);
  const [newTimeZoneDraft, setNewTimeZoneDraft] = useState(getDefaultReviewAutomationTimeZone());
  const [newCronExpressionDraft, setNewCronExpressionDraft] = useState(
    DEFAULT_REVIEW_AUTOMATION_CRON_EXPRESSION,
  );
  const [newTeamIdDraft, setNewTeamIdDraft] = useState<number | null>(null);
  const [newDueDateOffsetDaysDraft, setNewDueDateOffsetDaysDraft] = useState(
    String(DEFAULT_DUE_DATE_OFFSET_DAYS),
  );
  const [newMaxWordCountDraft, setNewMaxWordCountDraft] = useState(String(DEFAULT_MAX_WORD_COUNT));
  const [newFeatureIdsDraft, setNewFeatureIdsDraft] = useState<number[]>([]);
  const [createModalError, setCreateModalError] = useState<string | null>(null);
  const [statusNotice, setStatusNotice] = useState<StatusNotice | null>(null);
  const [automationPendingDelete, setAutomationPendingDelete] =
    useState<ApiReviewAutomationSummary | null>(null);

  const reviewFeaturesQuery = useQuery({
    queryKey: ['review-features', 'options'],
    queryFn: fetchReviewFeatureOptions,
    enabled: isAdmin,
    staleTime: 30_000,
  });
  const teamsQuery = useQuery({
    queryKey: ['teams', 'review-automation-create'],
    queryFn: fetchTeams,
    enabled: isAdmin,
    staleTime: 30_000,
  });

  const reviewAutomationsQuery = useQuery({
    queryKey: ['review-automations', searchQuery, enabledFilter, limit],
    queryFn: () =>
      fetchReviewAutomations({
        search: searchQuery,
        enabled: enabledFilter === 'all' ? null : enabledFilter === 'enabled',
        limit,
      }),
    enabled: isAdmin,
    staleTime: 30_000,
  });

  const createAutomationMutation = useMutation({
    mutationFn: createReviewAutomation,
    onSuccess: async (created) => {
      await queryClient.invalidateQueries({ queryKey: ['review-automations'] });
      setIsCreateModalOpen(false);
      resetCreateDrafts();
      setStatusNotice({
        kind: 'success',
        message: `Created review automation ${created.name} (#${created.id}).`,
      });
    },
    onError: (error: Error) => {
      setCreateModalError(error.message || 'Failed to create review automation.');
    },
  });

  const deleteAutomationMutation = useMutation({
    mutationFn: (automationId: number) => deleteReviewAutomation(automationId),
    onSuccess: async () => {
      const deletedName = automationPendingDelete?.name;
      await queryClient.invalidateQueries({ queryKey: ['review-automations'] });
      setAutomationPendingDelete(null);
      setStatusNotice({
        kind: 'success',
        message:
          deletedName != null
            ? `Deleted review automation ${deletedName}.`
            : 'Deleted review automation.',
      });
    },
    onError: (error: Error) => {
      setStatusNotice({
        kind: 'error',
        message: error.message || 'Failed to delete review automation.',
      });
    },
  });

  const automations = reviewAutomationsQuery.data?.reviewAutomations ?? [];
  const totalCount = reviewAutomationsQuery.data?.totalCount ?? 0;
  const maxWordCountDraft = useMemo(
    () => parsePositiveIntegerDraft(newMaxWordCountDraft),
    [newMaxWordCountDraft],
  );
  const dueDateOffsetDaysDraft = useMemo(
    () => parseNonNegativeIntegerDraft(newDueDateOffsetDaysDraft),
    [newDueDateOffsetDaysDraft],
  );

  const visibleCountLabel = useMemo(() => {
    if (automations.length === totalCount) {
      return `${automations.length} review automations`;
    }
    return `${automations.length} of ${totalCount} review automations`;
  }, [automations.length, totalCount]);

  const timeZoneOptions = useMemo(
    () => getReviewAutomationTimeZoneOptions(newTimeZoneDraft),
    [newTimeZoneDraft],
  );

  const resetCreateDrafts = () => {
    setNewNameDraft('');
    setNewEnabledDraft(true);
    setNewTimeZoneDraft(getDefaultReviewAutomationTimeZone());
    setNewCronExpressionDraft(DEFAULT_REVIEW_AUTOMATION_CRON_EXPRESSION);
    setNewTeamIdDraft(null);
    setNewDueDateOffsetDaysDraft(String(DEFAULT_DUE_DATE_OFFSET_DAYS));
    setNewMaxWordCountDraft(String(DEFAULT_MAX_WORD_COUNT));
    setNewFeatureIdsDraft([]);
    setCreateModalError(null);
  };

  if (!isAdmin) {
    return <Navigate to="/repositories" replace />;
  }

  const handleCreateAutomation = () => {
    const normalizedName = normalizeAutomationName(newNameDraft);
    if (!normalizedName) {
      setCreateModalError('Review automation name is required.');
      return;
    }
    if (!newCronExpressionDraft.trim()) {
      setCreateModalError('Cron expression is required.');
      return;
    }
    if (newTeamIdDraft == null) {
      setCreateModalError('Team is required.');
      return;
    }
    if (!dueDateOffsetDaysDraft.valid) {
      setCreateModalError('Due date offset days must be zero or a positive whole number.');
      return;
    }
    if (!maxWordCountDraft.valid) {
      setCreateModalError('Max word count per project must be a positive whole number.');
      return;
    }
    createAutomationMutation.mutate({
      name: normalizedName,
      enabled: newEnabledDraft,
      cronExpression: newCronExpressionDraft.trim(),
      timeZone: newTimeZoneDraft,
      teamId: newTeamIdDraft,
      dueDateOffsetDays: dueDateOffsetDaysDraft.value as number,
      maxWordCountPerProject: maxWordCountDraft.value as number,
      featureIds: [...newFeatureIdsDraft].sort((a, b) => a - b),
    });
  };

  return (
    <div className="settings-subpage">
      <SettingsSubpageHeader
        backTo="/settings/system"
        backLabel="Back to settings"
        context="Settings"
        title="Review automations"
      />
      <div className="settings-page settings-page--wide review-automation-admin-page">
        <section className="settings-card">
          <div className="settings-card__content">
            <div className="review-automation-admin-page__toolbar">
              <SearchControl
                value={searchQuery}
                onChange={setSearchQuery}
                placeholder="Search review automations"
                className="review-automation-admin-page__search"
              />
              <label className="review-automation-admin-page__filter">
                <span className="review-automation-admin-page__filter-label">Status</span>
                <select
                  className="settings-input review-automation-admin-page__filter-select"
                  value={enabledFilter}
                  onChange={(event) => setEnabledFilter(event.target.value as EnabledFilter)}
                >
                  <option value="enabled">Enabled</option>
                  <option value="disabled">Disabled</option>
                  <option value="all">All</option>
                </select>
              </label>
              <NumericPresetDropdown
                value={limit}
                buttonLabel={`${limit} rows`}
                menuLabel="Result size"
                presetOptions={LIMIT_PRESETS.map((size) => ({
                  value: size,
                  label: String(size),
                }))}
                onChange={setLimit}
                ariaLabel="Review automation result size"
                pillsClassName="settings-pills"
                optionClassName="settings-pill"
                optionActiveClassName="is-active"
                customActiveClassName="is-active"
                customButtonClassName="settings-pill"
                customInitialValue={50}
              />
              <div className="review-automation-admin-page__toolbar-actions">
                <button
                  type="button"
                  className="settings-button"
                  onClick={() => void navigate('/settings/system/review-automations/batch')}
                >
                  Batch update
                </button>
                <button
                  type="button"
                  className="settings-button settings-button--primary"
                  onClick={() => {
                    setIsCreateModalOpen(true);
                    setIsCreateScheduleBuilderOpen(false);
                    resetCreateDrafts();
                    setStatusNotice(null);
                  }}
                >
                  Create automation
                </button>
              </div>
            </div>

            <div className="review-automation-admin-page__count">
              <span className="review-automation-admin-page__count-text">{visibleCountLabel}</span>
              {statusNotice ? (
                <span
                  className={`settings-hint review-automation-admin-page__status${
                    statusNotice.kind === 'error' ? ' is-error' : ''
                  }`}
                >
                  {statusNotice.message}
                </span>
              ) : null}
            </div>

            {reviewAutomationsQuery.isError ? (
              <p className="review-automation-admin-page__empty">
                {reviewAutomationsQuery.error instanceof Error
                  ? reviewAutomationsQuery.error.message
                  : 'Could not load review automations.'}
              </p>
            ) : reviewAutomationsQuery.isLoading ? (
              <p className="review-automation-admin-page__empty">Loading review automations…</p>
            ) : automations.length === 0 ? (
              <p className="review-automation-admin-page__empty">
                No review automations match the current filters.
              </p>
            ) : (
              <div className="review-automation-admin-page__table">
                <div className="review-automation-admin-page__table-header">
                  <div className="review-automation-admin-page__cell">ID</div>
                  <div className="review-automation-admin-page__cell">Automation</div>
                  <div className="review-automation-admin-page__cell">Enabled</div>
                  <div className="review-automation-admin-page__cell">Trigger</div>
                  <div className="review-automation-admin-page__cell">Next run</div>
                  <div className="review-automation-admin-page__cell">Schedule</div>
                  <div className="review-automation-admin-page__cell">Team</div>
                  <div className="review-automation-admin-page__cell">Due in</div>
                  <div className="review-automation-admin-page__cell">Max size</div>
                  <div className="review-automation-admin-page__cell">Features</div>
                  <div className="review-automation-admin-page__cell">Updated</div>
                  <div className="review-automation-admin-page__cell review-automation-admin-page__cell--actions">
                    Actions
                  </div>
                </div>
                {automations.map((automation) => (
                  <AutomationRow
                    key={automation.id}
                    automation={automation}
                    onDelete={() => {
                      setAutomationPendingDelete(automation);
                      setStatusNotice(null);
                    }}
                  />
                ))}
              </div>
            )}
          </div>
        </section>

        <Modal
          open={isCreateModalOpen}
          size="lg"
          ariaLabel="Create review automation"
          onClose={() => setIsCreateModalOpen(false)}
          closeOnBackdrop
          className="review-automation-admin-page__create-dialog"
        >
          <div className="review-automation-admin-page__create-modal">
            <div className="modal__title">Create review automation</div>
            <div className="settings-field">
              <label className="settings-field__label" htmlFor="create-review-automation-name">
                Name
              </label>
              <input
                id="create-review-automation-name"
                type="text"
                className="settings-input"
                value={newNameDraft}
                onChange={(event) => {
                  setNewNameDraft(event.target.value);
                  setCreateModalError(null);
                }}
                placeholder="Review automation name"
              />
            </div>
            <div className="settings-field">
              <label className="settings-toggle">
                <input
                  type="checkbox"
                  checked={newEnabledDraft}
                  onChange={(event) => setNewEnabledDraft(event.target.checked)}
                />
                <span>Enable this review automation</span>
              </label>
            </div>
            <div className="settings-field">
              <div className="settings-field__header">
                <div className="settings-field__label">Schedule</div>
              </div>
              <div className="review-automation-schedule__row">
                <label className="settings-field">
                  <span className="settings-field__label">Cron expression</span>
                  <input
                    id="create-review-automation-cron"
                    type="text"
                    className="settings-input"
                    value={newCronExpressionDraft}
                    onChange={(event) => {
                      setNewCronExpressionDraft(event.target.value);
                      setCreateModalError(null);
                    }}
                    placeholder="0 0 0 * * ?"
                  />
                </label>
                <label className="settings-field">
                  <span className="settings-field__label">Timezone</span>
                  <input
                    type="text"
                    className="settings-input"
                    value={newTimeZoneDraft}
                    list="review-automation-time-zone-options"
                    onChange={(event) => {
                      setNewTimeZoneDraft(event.target.value);
                      setCreateModalError(null);
                    }}
                    placeholder="America/Los_Angeles"
                    spellCheck={false}
                    autoCapitalize="off"
                    autoCorrect="off"
                  />
                </label>
                <button
                  type="button"
                  className="settings-button review-automation-schedule__row-action"
                  onClick={() => setIsCreateScheduleBuilderOpen(true)}
                >
                  Generate cron
                </button>
              </div>
              <datalist id="review-automation-time-zone-options">
                {timeZoneOptions.map((timeZone) => (
                  <option key={timeZone} value={timeZone} />
                ))}
              </datalist>
            </div>
            <div className="settings-grid settings-grid--two-column">
              <div className="settings-field">
                <label className="settings-field__label">Team</label>
                <SingleSelectDropdown
                  label="Team"
                  options={(teamsQuery.data ?? []).map((team) => ({
                    value: team.id,
                    label: team.name,
                  }))}
                  value={newTeamIdDraft}
                  onChange={(value) => {
                    setNewTeamIdDraft(value);
                    setCreateModalError(null);
                  }}
                  noneLabel="Select team"
                  placeholder="Select team"
                  disabled={teamsQuery.isLoading}
                  buttonAriaLabel="Select review automation team"
                />
              </div>
              <div className="settings-field">
                <label
                  className="settings-field__label"
                  htmlFor="create-review-automation-due-offset"
                >
                  Due date offset days
                </label>
                <input
                  id="create-review-automation-due-offset"
                  type="number"
                  min={0}
                  inputMode="numeric"
                  className="settings-input"
                  value={newDueDateOffsetDaysDraft}
                  onChange={(event) => {
                    setNewDueDateOffsetDaysDraft(event.target.value);
                    setCreateModalError(null);
                  }}
                />
              </div>
            </div>
            <div className="settings-field">
              <label
                className="settings-field__label"
                htmlFor="create-review-automation-max-word-count"
              >
                Max word count per project
              </label>
              <input
                id="create-review-automation-max-word-count"
                type="number"
                min={1}
                inputMode="numeric"
                className="settings-input"
                value={newMaxWordCountDraft}
                onChange={(event) => {
                  setNewMaxWordCountDraft(event.target.value);
                  setCreateModalError(null);
                }}
                placeholder={String(DEFAULT_MAX_WORD_COUNT)}
              />
            </div>
            <div className="settings-field">
              <div className="settings-field__header">
                <div className="settings-field__label">Review features</div>
              </div>
              <ReviewFeatureMultiSelect
                label="Review features"
                options={reviewFeaturesQuery.data ?? []}
                selectedIds={newFeatureIdsDraft}
                onChange={(next) => setNewFeatureIdsDraft([...next].sort((a, b) => a - b))}
                className="settings-repository-select"
                buttonAriaLabel="Select review features for new review automation"
                disabled={reviewFeaturesQuery.isLoading}
                enabledOnlyByDefault
              />
              <p className="settings-hint">
                Features stay exclusive across enabled automations to avoid duplicate project
                creation.
              </p>
              {createModalError ? (
                <p className="settings-hint is-error">{createModalError}</p>
              ) : null}
            </div>
          </div>
          <div className="modal__actions">
            <button
              type="button"
              className="modal__button"
              onClick={() => {
                setIsCreateModalOpen(false);
                setCreateModalError(null);
              }}
            >
              Cancel
            </button>
            <button
              type="button"
              className="modal__button modal__button--primary"
              onClick={handleCreateAutomation}
              disabled={createAutomationMutation.isPending}
            >
              Create
            </button>
          </div>
        </Modal>

        <ReviewAutomationScheduleBuilderModal
          open={isCreateScheduleBuilderOpen}
          title="Generate cron schedule"
          ariaLabel="Generate review automation cron schedule"
          initialCronExpression={newCronExpressionDraft}
          initialTimeZone={newTimeZoneDraft}
          onClose={() => setIsCreateScheduleBuilderOpen(false)}
          onApply={({ cronExpression, timeZone }) => {
            setNewCronExpressionDraft(cronExpression);
            setNewTimeZoneDraft(timeZone);
            setCreateModalError(null);
            setIsCreateScheduleBuilderOpen(false);
          }}
        />

        <ConfirmModal
          open={automationPendingDelete != null}
          title="Delete review automation"
          body={
            automationPendingDelete == null
              ? ''
              : `Delete ${automationPendingDelete.name}? This only removes the automation configuration.`
          }
          confirmLabel="Delete"
          cancelLabel="Cancel"
          onConfirm={() => {
            if (automationPendingDelete == null) {
              return;
            }
            deleteAutomationMutation.mutate(automationPendingDelete.id);
          }}
          onCancel={() => {
            if (!deleteAutomationMutation.isPending) {
              setAutomationPendingDelete(null);
            }
          }}
          requireText={automationPendingDelete?.name}
          requireTextLabel="Type the automation name to confirm deletion."
        />
      </div>
    </div>
  );
}

function AutomationRow({
  automation,
  onDelete,
}: {
  automation: ApiReviewAutomationSummary;
  onDelete: () => void;
}) {
  const featureSummary =
    automation.featureCount === 0
      ? 'No review features'
      : automation.featureCount === 1
        ? (automation.features[0]?.name ?? '1 review feature')
        : automation.featureCount === 2
          ? automation.features.map((feature) => feature.name).join(', ')
          : `${automation.features[0]?.name ?? 'Review feature'} +${automation.featureCount - 1}`;

  return (
    <div className="review-automation-admin-page__row">
      <div className="review-automation-admin-page__cell review-automation-admin-page__cell--muted">
        {automation.id}
      </div>
      <div className="review-automation-admin-page__cell">
        <div className="review-automation-admin-page__name-cell">
          <span className="review-automation-admin-page__name-text">{automation.name}</span>
        </div>
      </div>
      <div className="review-automation-admin-page__cell review-automation-admin-page__cell--muted">
        {automation.enabled ? 'Enabled' : 'Disabled'}
      </div>
      <div className="review-automation-admin-page__cell">
        <span
          className={`review-automation-admin-page__trigger-badge ${getTriggerStatusClassName(automation.trigger?.status)}`}
          title={automation.trigger?.quartzState ?? 'No trigger'}
        >
          {formatTriggerStatus(automation.trigger?.status)}
        </span>
      </div>
      <div className="review-automation-admin-page__cell review-automation-admin-page__cell--muted">
        {formatDateTime(automation.trigger?.nextRunAt ?? null)}
      </div>
      <div
        className="review-automation-admin-page__cell review-automation-admin-page__cell--muted"
        title={`${automation.cronExpression} · ${automation.timeZone}`}
      >
        {formatReviewAutomationSchedule(automation.cronExpression, automation.timeZone)}
      </div>
      <div className="review-automation-admin-page__cell review-automation-admin-page__cell--muted">
        {automation.team?.name ?? '—'}
      </div>
      <div className="review-automation-admin-page__cell review-automation-admin-page__cell--muted">
        {formatDueDateOffsetDays(automation.dueDateOffsetDays)}
      </div>
      <div className="review-automation-admin-page__cell review-automation-admin-page__cell--muted">
        {automation.maxWordCountPerProject}
      </div>
      <div
        className="review-automation-admin-page__cell review-automation-admin-page__cell--muted"
        title={automation.features.map((feature) => feature.name).join(', ')}
      >
        {featureSummary}
      </div>
      <div className="review-automation-admin-page__cell review-automation-admin-page__cell--muted">
        {formatDateTime(automation.lastModifiedDate)}
      </div>
      <div className="review-automation-admin-page__cell review-automation-admin-page__cell--actions">
        <div className="review-automation-admin-page__actions">
          <Link
            className="review-automation-admin-page__row-action-link"
            to={`/settings/system/review-automations/${automation.id}`}
          >
            Edit
          </Link>
          <button
            type="button"
            className="review-automation-admin-page__row-action-link review-automation-admin-page__row-action-button"
            onClick={onDelete}
          >
            Delete
          </button>
        </div>
      </div>
    </div>
  );
}

function formatDateTime(value?: string | null) {
  if (!value) {
    return '—';
  }
  const parsed = Date.parse(value);
  if (Number.isNaN(parsed)) {
    return value;
  }
  return new Date(parsed).toLocaleString();
}

function formatDueDateOffsetDays(value: number) {
  return `${value}d`;
}

function formatTriggerStatus(value?: string | null) {
  switch (value) {
    case 'HEALTHY':
      return 'Healthy';
    case 'ERROR':
      return 'Error';
    case 'PAUSED':
      return 'Paused';
    case 'MISSING':
      return 'Missing';
    default:
      return 'Missing';
  }
}

function getTriggerStatusClassName(value?: string | null) {
  switch (value) {
    case 'HEALTHY':
      return 'is-healthy';
    case 'ERROR':
      return 'is-error';
    case 'PAUSED':
      return 'is-paused';
    case 'MISSING':
      return 'is-missing';
    default:
      return 'is-missing';
  }
}
