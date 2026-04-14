import './settings-page.css';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useMemo, useState } from 'react';
import { Link, Navigate, useParams } from 'react-router-dom';

import {
  type ApiReviewAutomationRun,
  fetchReviewAutomation,
  fetchReviewAutomationRuns,
  repairReviewAutomationTrigger,
  runReviewAutomationNow,
  updateReviewAutomation,
} from '../../api/review-automations';
import { fetchReviewFeatureOptions } from '../../api/review-features';
import { fetchTeams } from '../../api/teams';
import { NumericPresetDropdown } from '../../components/NumericPresetDropdown';
import { useUser } from '../../components/RequireUser';
import { ReviewAutomationRunsTable } from '../../components/ReviewAutomationRunsTable';
import { ReviewAutomationScheduleBuilderModal } from '../../components/ReviewAutomationScheduleBuilderModal';
import { ReviewFeatureMultiSelect } from '../../components/ReviewFeatureMultiSelect';
import { SingleSelectDropdown } from '../../components/SingleSelectDropdown';
import { getReviewAutomationTimeZoneOptions } from '../../utils/reviewAutomationSchedule';
import { formatDateTime } from './reviewAutomationRunFormatting';
import { SettingsSubpageHeader } from './SettingsSubpageHeader';

const normalizeAutomationName = (value: string) => value.trim().replace(/\s+/g, ' ');
const RUN_HISTORY_LIMIT_PRESETS = [20, 50, 100];

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

export function AdminReviewAutomationDetailPage() {
  const user = useUser();
  const isAdmin = user.role === 'ROLE_ADMIN';
  const queryClient = useQueryClient();
  const params = useParams<{ automationId?: string }>();

  const [nameDraft, setNameDraft] = useState('');
  const [enabledDraft, setEnabledDraft] = useState(true);
  const [isScheduleBuilderOpen, setIsScheduleBuilderOpen] = useState(false);
  const [timeZoneDraft, setTimeZoneDraft] = useState('UTC');
  const [cronExpressionDraft, setCronExpressionDraft] = useState('');
  const [teamIdDraft, setTeamIdDraft] = useState<number | null>(null);
  const [assignTranslatorDraft, setAssignTranslatorDraft] = useState(true);
  const [dueDateOffsetDaysDraft, setDueDateOffsetDaysDraft] = useState('');
  const [maxWordCountDraft, setMaxWordCountDraft] = useState('');
  const [featureIdsDraft, setFeatureIdsDraft] = useState<number[]>([]);
  const [runHistoryLimit, setRunHistoryLimit] = useState(20);
  const [statusNotice, setStatusNotice] = useState<{
    kind: 'success' | 'error';
    message: string;
  } | null>(null);

  const parsedAutomationId = useMemo(() => {
    const raw = params.automationId?.trim();
    if (!raw) {
      return null;
    }
    const next = Number(raw);
    return Number.isInteger(next) && next > 0 ? next : null;
  }, [params.automationId]);

  const reviewFeaturesQuery = useQuery({
    queryKey: ['review-features', 'options'],
    queryFn: fetchReviewFeatureOptions,
    enabled: isAdmin,
    staleTime: 30_000,
  });
  const teamsQuery = useQuery({
    queryKey: ['teams', 'review-automation-detail'],
    queryFn: fetchTeams,
    enabled: isAdmin,
    staleTime: 30_000,
  });

  const automationQuery = useQuery({
    queryKey: ['review-automation', parsedAutomationId],
    queryFn: () => fetchReviewAutomation(parsedAutomationId as number),
    enabled: isAdmin && parsedAutomationId != null,
    staleTime: 30_000,
  });
  const automationRunsQuery = useQuery<ApiReviewAutomationRun[]>({
    queryKey: ['review-automation-runs', parsedAutomationId, runHistoryLimit],
    queryFn: () =>
      fetchReviewAutomationRuns({
        automationIds: [parsedAutomationId as number],
        limit: runHistoryLimit,
      }),
    enabled: isAdmin && parsedAutomationId != null,
    staleTime: 10_000,
  });

  const updateMutation = useMutation({
    mutationFn: (payload: {
      name: string;
      enabled: boolean;
      cronExpression: string;
      timeZone: string;
      teamId: number;
      dueDateOffsetDays: number;
      maxWordCountPerProject: number;
      assignTranslator: boolean;
      featureIds: number[];
    }) => updateReviewAutomation(parsedAutomationId as number, payload),
    onSuccess: async (updated) => {
      queryClient.setQueryData(['review-automation', parsedAutomationId], updated);
      await queryClient.invalidateQueries({ queryKey: ['review-automation', parsedAutomationId] });
      await queryClient.invalidateQueries({ queryKey: ['review-automations'] });
      setStatusNotice({
        kind: 'success',
        message: `Saved review automation ${updated.name}.`,
      });
    },
    onError: (error: Error) => {
      setStatusNotice({
        kind: 'error',
        message: error.message || 'Failed to save review automation.',
      });
    },
  });
  const runNowMutation = useMutation({
    mutationFn: () => runReviewAutomationNow(parsedAutomationId as number),
    onMutate: () => {
      setStatusNotice({
        kind: 'success',
        message: 'Running review automation…',
      });
    },
    onSuccess: async (result) => {
      await queryClient.invalidateQueries({
        queryKey: ['review-automation-runs', parsedAutomationId],
      });
      setStatusNotice({
        kind: 'success',
        message: `Ran ${result.automationName}: created ${result.createdProjectRequestCount} request${result.createdProjectRequestCount === 1 ? '' : 's'}, ${result.createdProjectCount} project${result.createdProjectCount === 1 ? '' : 's'}, ${result.createdLocaleCount} locale${result.createdLocaleCount === 1 ? '' : 's'}, skipped ${result.skippedLocaleCount}, errors ${result.erroredLocaleCount}.`,
      });
    },
    onError: (error: Error) => {
      setStatusNotice({
        kind: 'error',
        message: error.message || 'Failed to run review automation.',
      });
    },
  });
  const repairTriggerMutation = useMutation({
    mutationFn: () => repairReviewAutomationTrigger(parsedAutomationId as number),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['review-automation', parsedAutomationId] }),
        queryClient.invalidateQueries({ queryKey: ['review-automations'] }),
      ]);
      setStatusNotice({
        kind: 'success',
        message: 'Repaired review automation trigger.',
      });
    },
    onError: (error: Error) => {
      setStatusNotice({
        kind: 'error',
        message: error.message || 'Failed to repair review automation trigger.',
      });
    },
  });

  useEffect(() => {
    const automation = automationQuery.data;
    if (!automation) {
      return;
    }
    setNameDraft(automation.name);
    setEnabledDraft(automation.enabled);
    setTimeZoneDraft(automation.timeZone);
    setCronExpressionDraft(automation.cronExpression);
    setTeamIdDraft(automation.team?.id ?? null);
    setAssignTranslatorDraft(automation.assignTranslator);
    setDueDateOffsetDaysDraft(String(automation.dueDateOffsetDays));
    setMaxWordCountDraft(String(automation.maxWordCountPerProject));
    setFeatureIdsDraft(automation.features.map((feature) => feature.id).sort((a, b) => a - b));
  }, [automationQuery.data]);

  const maxWordCount = useMemo(
    () => parsePositiveIntegerDraft(maxWordCountDraft),
    [maxWordCountDraft],
  );
  const dueDateOffsetDays = useMemo(
    () => parseNonNegativeIntegerDraft(dueDateOffsetDaysDraft),
    [dueDateOffsetDaysDraft],
  );
  const timeZoneOptions = useMemo(
    () => getReviewAutomationTimeZoneOptions(timeZoneDraft),
    [timeZoneDraft],
  );

  const isDirty = useMemo(() => {
    const automation = automationQuery.data;
    if (!automation) {
      return false;
    }
    const normalizedName = normalizeAutomationName(nameDraft);
    if (normalizedName !== automation.name) {
      return true;
    }
    if (enabledDraft !== automation.enabled) {
      return true;
    }
    if (cronExpressionDraft.trim() !== automation.cronExpression) {
      return true;
    }
    if (timeZoneDraft !== automation.timeZone) {
      return true;
    }
    if (teamIdDraft !== (automation.team?.id ?? null)) {
      return true;
    }
    if (assignTranslatorDraft !== automation.assignTranslator) {
      return true;
    }
    if (dueDateOffsetDays.valid && dueDateOffsetDays.value !== automation.dueDateOffsetDays) {
      return true;
    }
    if (maxWordCount.valid && maxWordCount.value !== automation.maxWordCountPerProject) {
      return true;
    }
    const existingIds = automation.features.map((feature) => feature.id).sort((a, b) => a - b);
    if (existingIds.length !== featureIdsDraft.length) {
      return true;
    }
    return existingIds.some((id, index) => id !== featureIdsDraft[index]);
  }, [
    automationQuery.data,
    cronExpressionDraft,
    enabledDraft,
    featureIdsDraft,
    assignTranslatorDraft,
    dueDateOffsetDays.valid,
    dueDateOffsetDays.value,
    maxWordCount.valid,
    maxWordCount.value,
    nameDraft,
    teamIdDraft,
    timeZoneDraft,
  ]);

  if (!isAdmin) {
    return <Navigate to="/repositories" replace />;
  }

  if (parsedAutomationId == null) {
    return <Navigate to="/settings/system/review-automations" replace />;
  }

  const handleSave = () => {
    const normalizedName = normalizeAutomationName(nameDraft);
    if (!normalizedName) {
      setStatusNotice({ kind: 'error', message: 'Review automation name is required.' });
      return;
    }
    if (!cronExpressionDraft.trim()) {
      setStatusNotice({ kind: 'error', message: 'Cron expression is required.' });
      return;
    }
    if (teamIdDraft == null) {
      setStatusNotice({ kind: 'error', message: 'Team is required.' });
      return;
    }
    if (!dueDateOffsetDays.valid) {
      setStatusNotice({
        kind: 'error',
        message: 'Due date offset days must be zero or a positive whole number.',
      });
      return;
    }
    if (!maxWordCount.valid) {
      setStatusNotice({
        kind: 'error',
        message: 'Max word count per project must be a positive whole number.',
      });
      return;
    }

    updateMutation.mutate({
      name: normalizedName,
      enabled: enabledDraft,
      cronExpression: cronExpressionDraft.trim(),
      timeZone: timeZoneDraft,
      teamId: teamIdDraft,
      dueDateOffsetDays: dueDateOffsetDays.value as number,
      maxWordCountPerProject: maxWordCount.value as number,
      assignTranslator: assignTranslatorDraft,
      featureIds: [...featureIdsDraft].sort((a, b) => a - b),
    });
  };

  return (
    <div className="settings-subpage">
      <SettingsSubpageHeader
        backTo="/settings/system/review-automations"
        backLabel="Back to review automations"
        context="Settings > Review automations > Review automation"
        title={automationQuery.data?.name || `Automation #${parsedAutomationId}`}
      />
      <div className="settings-page">
        <section className="settings-card">
          {automationQuery.isError ? (
            <p className="settings-hint is-error">
              {automationQuery.error instanceof Error
                ? automationQuery.error.message
                : 'Could not load review automation.'}
            </p>
          ) : automationQuery.isLoading ? (
            <p className="settings-hint">Loading review automation…</p>
          ) : (
            <>
              <div className="settings-field">
                <label className="settings-field__label" htmlFor="review-automation-name">
                  Name
                </label>
                <input
                  id="review-automation-name"
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
                <label className="settings-toggle">
                  <input
                    type="checkbox"
                    checked={enabledDraft}
                    onChange={(event) => {
                      setEnabledDraft(event.target.checked);
                      setStatusNotice(null);
                    }}
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
                      id="review-automation-cron"
                      type="text"
                      className="settings-input"
                      value={cronExpressionDraft}
                      onChange={(event) => {
                        setCronExpressionDraft(event.target.value);
                        setStatusNotice(null);
                      }}
                      placeholder="0 0 0 * * ?"
                    />
                  </label>
                  <label className="settings-field">
                    <span className="settings-field__label">Timezone</span>
                    <input
                      type="text"
                      className="settings-input"
                      value={timeZoneDraft}
                      list="review-automation-detail-time-zone-options"
                      onChange={(event) => {
                        setTimeZoneDraft(event.target.value);
                        setStatusNotice(null);
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
                    onClick={() => setIsScheduleBuilderOpen(true)}
                  >
                    Generate cron
                  </button>
                </div>
                <datalist id="review-automation-detail-time-zone-options">
                  {timeZoneOptions.map((timeZone) => (
                    <option key={timeZone} value={timeZone} />
                  ))}
                </datalist>
              </div>
              <div className="settings-card">
                <div className="settings-card__header">
                  <h2>Trigger health</h2>
                </div>
                <div className="settings-grid settings-grid--two-column">
                  <div className="settings-field">
                    <span className="settings-field__label">Trigger status</span>
                    <span>{formatTriggerStatus(automationQuery.data?.trigger?.status)}</span>
                  </div>
                  <div className="settings-field">
                    <span className="settings-field__label">Quartz state</span>
                    <span>{automationQuery.data?.trigger?.quartzState || 'NONE'}</span>
                  </div>
                  <div className="settings-field">
                    <span className="settings-field__label">Next run</span>
                    <span>{formatDateTime(automationQuery.data?.trigger?.nextRunAt)}</span>
                  </div>
                  <div className="settings-field">
                    <span className="settings-field__label">Previous fire</span>
                    <span>{formatDateTime(automationQuery.data?.trigger?.previousRunAt)}</span>
                  </div>
                  <div className="settings-field">
                    <span className="settings-field__label">Last run</span>
                    <span>{formatDateTime(automationQuery.data?.trigger?.lastRunAt)}</span>
                  </div>
                  <div className="settings-field">
                    <span className="settings-field__label">Last successful run</span>
                    <span>
                      {formatDateTime(automationQuery.data?.trigger?.lastSuccessfulRunAt)}
                    </span>
                  </div>
                </div>
                {automationQuery.data?.trigger?.repairRecommended ? (
                  <p className="settings-hint is-error">
                    This automation trigger needs repair before scheduled runs can resume.
                  </p>
                ) : (
                  <p className="settings-hint">
                    Scheduled runs use the saved automation configuration and Quartz trigger state.
                  </p>
                )}
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
                    value={teamIdDraft}
                    onChange={(value) => {
                      setTeamIdDraft(value);
                      setStatusNotice(null);
                    }}
                    noneLabel="Select team"
                    placeholder="Select team"
                    disabled={teamsQuery.isLoading}
                    buttonAriaLabel="Select review automation team"
                  />
                </div>
                <div className="settings-field">
                  <label className="settings-field__label" htmlFor="review-automation-due-offset">
                    Due date offset days
                  </label>
                  <input
                    id="review-automation-due-offset"
                    type="number"
                    min={0}
                    inputMode="numeric"
                    className="settings-input"
                    value={dueDateOffsetDaysDraft}
                    onChange={(event) => {
                      setDueDateOffsetDaysDraft(event.target.value);
                      setStatusNotice(null);
                    }}
                  />
                </div>
              </div>
              <div className="settings-field">
                <label className="settings-field__label" htmlFor="review-automation-max-word-count">
                  Max word count per project
                </label>
                <input
                  id="review-automation-max-word-count"
                  type="number"
                  min={1}
                  inputMode="numeric"
                  className="settings-input"
                  value={maxWordCountDraft}
                  onChange={(event) => {
                    setMaxWordCountDraft(event.target.value);
                    setStatusNotice(null);
                  }}
                />
              </div>
              <div className="settings-field">
                <label className="settings-toggle">
                  <input
                    type="checkbox"
                    checked={assignTranslatorDraft}
                    onChange={(event) => {
                      setAssignTranslatorDraft(event.target.checked);
                      setStatusNotice(null);
                    }}
                    disabled={teamIdDraft == null}
                  />
                  <span>Assign translator from locale pool</span>
                </label>
              </div>
              <div className="settings-field">
                <div className="settings-field__header">
                  <div className="settings-field__label">Review features</div>
                </div>
                <ReviewFeatureMultiSelect
                  label="Review features"
                  options={reviewFeaturesQuery.data ?? []}
                  selectedIds={featureIdsDraft}
                  onChange={(next) => {
                    setFeatureIdsDraft([...next].sort((a, b) => a - b));
                    setStatusNotice(null);
                  }}
                  className="settings-repository-select"
                  buttonAriaLabel="Select review features for review automation"
                  disabled={reviewFeaturesQuery.isLoading}
                  enabledOnlyByDefault
                />
                <p className="settings-hint">
                  Automations can span multiple repositories indirectly through review features.
                </p>
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
                    className="settings-button"
                    onClick={() => repairTriggerMutation.mutate()}
                    disabled={
                      updateMutation.isPending ||
                      runNowMutation.isPending ||
                      repairTriggerMutation.isPending ||
                      !automationQuery.data?.trigger?.repairRecommended
                    }
                  >
                    {repairTriggerMutation.isPending ? 'Repairing…' : 'Repair trigger'}
                  </button>
                  <button
                    type="button"
                    className="settings-button"
                    onClick={() => runNowMutation.mutate()}
                    disabled={
                      updateMutation.isPending ||
                      runNowMutation.isPending ||
                      repairTriggerMutation.isPending
                    }
                  >
                    {runNowMutation.isPending ? 'Running…' : 'Run now'}
                  </button>
                  <button
                    type="button"
                    className="settings-button settings-button--primary"
                    onClick={handleSave}
                    disabled={
                      updateMutation.isPending ||
                      runNowMutation.isPending ||
                      repairTriggerMutation.isPending ||
                      !isDirty
                    }
                  >
                    Save
                  </button>
                </div>
              </div>
            </>
          )}
        </section>

        <section className="settings-card">
          <div className="settings-card__header">
            <h2>Recent runs</h2>
            <div className="settings-actions">
              <Link to="/settings/system/review-automation-runs" className="settings-button">
                View all automation runs
              </Link>
              <NumericPresetDropdown
                value={runHistoryLimit}
                buttonLabel={`${runHistoryLimit} rows`}
                menuLabel="Run history size"
                presetOptions={RUN_HISTORY_LIMIT_PRESETS.map((size) => ({
                  value: size,
                  label: String(size),
                }))}
                onChange={setRunHistoryLimit}
                ariaLabel="Review automation run history size"
                pillsClassName="settings-pills"
                optionClassName="settings-pill"
                optionActiveClassName="is-active"
                customActiveClassName="is-active"
                customButtonClassName="settings-pill"
                customInitialValue={50}
              />
            </div>
          </div>
          <p className="settings-hint">
            Run history reflects the saved automation configuration. Manual runs use the current
            saved values, not unsaved edits on this page.
          </p>
          {automationRunsQuery.isError ? (
            <p className="settings-hint is-error">
              {automationRunsQuery.error instanceof Error
                ? automationRunsQuery.error.message
                : 'Could not load review automation runs.'}
            </p>
          ) : automationRunsQuery.isLoading ? (
            <p className="settings-hint">Loading review automation runs…</p>
          ) : (automationRunsQuery.data ?? []).length === 0 ? (
            <p className="settings-hint">No runs recorded yet.</p>
          ) : (
            <ReviewAutomationRunsTable runs={automationRunsQuery.data ?? []} />
          )}
        </section>

        <ReviewAutomationScheduleBuilderModal
          open={isScheduleBuilderOpen}
          title="Generate cron schedule"
          ariaLabel="Generate review automation cron schedule"
          initialCronExpression={cronExpressionDraft}
          initialTimeZone={timeZoneDraft}
          onClose={() => setIsScheduleBuilderOpen(false)}
          onApply={({ cronExpression, timeZone }) => {
            setCronExpressionDraft(cronExpression);
            setTimeZoneDraft(timeZone);
            setStatusNotice(null);
            setIsScheduleBuilderOpen(false);
          }}
        />
      </div>
    </div>
  );
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
