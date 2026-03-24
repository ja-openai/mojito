import './settings-page.css';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useMemo, useState } from 'react';
import { Navigate, useParams } from 'react-router-dom';

import { fetchReviewAutomation, updateReviewAutomation } from '../../api/review-automations';
import { fetchReviewFeatureOptions } from '../../api/review-features';
import { fetchTeams } from '../../api/teams';
import { useUser } from '../../components/RequireUser';
import { ReviewAutomationScheduleBuilderModal } from '../../components/ReviewAutomationScheduleBuilderModal';
import { ReviewFeatureMultiSelect } from '../../components/ReviewFeatureMultiSelect';
import { SingleSelectDropdown } from '../../components/SingleSelectDropdown';
import { getReviewAutomationTimeZoneOptions } from '../../utils/reviewAutomationSchedule';
import { SettingsSubpageHeader } from './SettingsSubpageHeader';

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
  const [dueDateOffsetDaysDraft, setDueDateOffsetDaysDraft] = useState('');
  const [maxWordCountDraft, setMaxWordCountDraft] = useState('');
  const [featureIdsDraft, setFeatureIdsDraft] = useState<number[]>([]);
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

  const updateMutation = useMutation({
    mutationFn: (payload: {
      name: string;
      enabled: boolean;
      cronExpression: string;
      timeZone: string;
      teamId: number;
      dueDateOffsetDays: number;
      maxWordCountPerProject: number;
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
    setDueDateOffsetDaysDraft(String(automation.dueDateOffsetDays));
    setMaxWordCountDraft(String(automation.maxWordCountPerProject));
    setFeatureIdsDraft(automation.features.map((feature) => feature.id).sort((a, b) => a - b));
  }, [automationQuery.data]);

  const maxWordCount = useMemo(
    () => parsePositiveIntegerDraft(maxWordCountDraft),
    [maxWordCountDraft],
  );
  const dueDateOffsetDays = useMemo(
    () => parsePositiveIntegerDraft(dueDateOffsetDaysDraft),
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
                    className="settings-button settings-button--primary"
                    onClick={handleSave}
                    disabled={updateMutation.isPending || !isDirty}
                  >
                    Save
                  </button>
                </div>
              </div>
            </>
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
