import './review-automation-schedule-builder.css';

import { useQuery } from '@tanstack/react-query';
import { useEffect, useMemo, useState } from 'react';

import { fetchReviewAutomationSchedulePreview } from '../api/review-automations';
import { useDebouncedValue } from '../hooks/useDebouncedValue';
import {
  buildReviewAutomationCronExpression,
  DEFAULT_REVIEW_AUTOMATION_TIME_OF_DAY,
  DEFAULT_REVIEW_AUTOMATION_TIME_ZONE,
  deriveReviewAutomationScheduleHelper,
  formatReviewAutomationSchedule,
  getReviewAutomationTimeZoneOptions,
  type ReviewAutomationScheduleMode,
} from '../utils/reviewAutomationSchedule';
import { REVIEW_AUTOMATION_SCHEDULE_PRESETS } from '../utils/reviewAutomationSchedulePresets';
import { Modal } from './Modal';

type Props = {
  open: boolean;
  title: string;
  ariaLabel: string;
  initialCronExpression: string;
  initialTimeZone: string;
  onClose: () => void;
  onApply: (params: { cronExpression: string; timeZone: string }) => void;
};

export function ReviewAutomationScheduleBuilderModal({
  open,
  title,
  ariaLabel,
  initialCronExpression,
  initialTimeZone,
  onClose,
  onApply,
}: Props) {
  const [scheduleModeDraft, setScheduleModeDraft] = useState<ReviewAutomationScheduleMode>('daily');
  const [timeOfDayDraft, setTimeOfDayDraft] = useState(DEFAULT_REVIEW_AUTOMATION_TIME_OF_DAY);
  const [timeZoneDraft, setTimeZoneDraft] = useState(DEFAULT_REVIEW_AUTOMATION_TIME_ZONE);
  const [cronExpressionDraft, setCronExpressionDraft] = useState('');

  useEffect(() => {
    if (!open) {
      return;
    }
    const helper = deriveReviewAutomationScheduleHelper(initialCronExpression);
    setScheduleModeDraft(helper.mode);
    setTimeOfDayDraft(helper.timeOfDay);
    setTimeZoneDraft(initialTimeZone);
    setCronExpressionDraft(initialCronExpression);
  }, [initialCronExpression, initialTimeZone, open]);

  const timeZoneOptions = useMemo(
    () => getReviewAutomationTimeZoneOptions(timeZoneDraft),
    [timeZoneDraft],
  );
  const debouncedCronExpression = useDebouncedValue(cronExpressionDraft.trim(), 250);
  const debouncedTimeZone = useDebouncedValue(timeZoneDraft.trim(), 250);

  const previewQuery = useQuery({
    queryKey: ['review-automation-schedule-preview', debouncedCronExpression, debouncedTimeZone],
    queryFn: () =>
      fetchReviewAutomationSchedulePreview({
        cronExpression: debouncedCronExpression,
        timeZone: debouncedTimeZone,
        count: 5,
      }),
    enabled: open && Boolean(debouncedCronExpression),
    staleTime: 15_000,
    retry: false,
  });

  const formattedPreviewRuns = useMemo(() => {
    const timeZone = previewQuery.data?.timeZone?.trim() || timeZoneDraft.trim();
    return (previewQuery.data?.nextRuns ?? []).map((nextRun) => ({
      raw: nextRun,
      label: new Intl.DateTimeFormat(undefined, {
        dateStyle: 'medium',
        timeStyle: 'medium',
        timeZone: timeZone || DEFAULT_REVIEW_AUTOMATION_TIME_ZONE,
      }).format(new Date(nextRun)),
    }));
  }, [previewQuery.data?.nextRuns, previewQuery.data?.timeZone, timeZoneDraft]);

  const applyPreset = (preset: (typeof REVIEW_AUTOMATION_SCHEDULE_PRESETS)[number]) => {
    setScheduleModeDraft(preset.mode);
    setTimeOfDayDraft(preset.timeOfDay);
    setTimeZoneDraft(preset.timeZone);
    const nextCron =
      preset.cronExpression ?? buildReviewAutomationCronExpression(preset.mode, preset.timeOfDay);
    if (nextCron) {
      setCronExpressionDraft(nextCron);
    }
  };

  return (
    <Modal open={open} size="lg" ariaLabel={ariaLabel} onClose={onClose} closeOnBackdrop>
      <div className="review-automation-schedule__builder">
        <div className="modal__title">{title}</div>
        <div className="settings-field">
          <span className="settings-field__label">Quick presets</span>
          <div className="settings-pills" role="group" aria-label="Schedule presets">
            {REVIEW_AUTOMATION_SCHEDULE_PRESETS.map((preset) => {
              const isActive =
                scheduleModeDraft === preset.mode &&
                timeOfDayDraft === preset.timeOfDay &&
                timeZoneDraft === preset.timeZone;
              return (
                <button
                  key={preset.label}
                  type="button"
                  className={`settings-pill${isActive ? ' is-active' : ''}`}
                  onClick={() => applyPreset(preset)}
                >
                  {preset.label}
                </button>
              );
            })}
          </div>
        </div>
        <div className="review-automation-schedule__builder-fields">
          <label className="settings-field">
            <span className="settings-field__label">Cadence</span>
            <select
              className="settings-input"
              value={scheduleModeDraft}
              onChange={(event) => {
                const nextMode = event.target.value as ReviewAutomationScheduleMode;
                setScheduleModeDraft(nextMode);
                if (nextMode !== 'custom') {
                  const nextCron = buildReviewAutomationCronExpression(nextMode, timeOfDayDraft);
                  if (nextCron) {
                    setCronExpressionDraft(nextCron);
                  }
                }
              }}
            >
              <option value="daily">Every day</option>
              <option value="weekdays">Weekdays</option>
              <option value="custom">Custom cron</option>
            </select>
          </label>
          <label className="settings-field">
            <span className="settings-field__label">Time of day</span>
            <input
              type="time"
              className="settings-input"
              value={timeOfDayDraft}
              onChange={(event) => {
                const nextTime = event.target.value;
                setTimeOfDayDraft(nextTime);
                if (scheduleModeDraft !== 'custom') {
                  const nextCron = buildReviewAutomationCronExpression(scheduleModeDraft, nextTime);
                  if (nextCron) {
                    setCronExpressionDraft(nextCron);
                  }
                }
              }}
              disabled={scheduleModeDraft === 'custom'}
            />
          </label>
          <label className="settings-field">
            <span className="settings-field__label">Timezone</span>
            <input
              type="text"
              className="settings-input"
              value={timeZoneDraft}
              list="review-automation-builder-time-zone-options"
              onChange={(event) => setTimeZoneDraft(event.target.value)}
              placeholder="America/Los_Angeles"
              spellCheck={false}
              autoCapitalize="off"
              autoCorrect="off"
            />
          </label>
        </div>
        <datalist id="review-automation-builder-time-zone-options">
          {timeZoneOptions.map((timeZone) => (
            <option key={timeZone} value={timeZone} />
          ))}
        </datalist>
        <label className="settings-field">
          <span className="settings-field__label">Cron expression</span>
          <input
            type="text"
            className="settings-input"
            value={cronExpressionDraft}
            onChange={(event) => setCronExpressionDraft(event.target.value)}
            placeholder="0 0 0 * * ?"
            readOnly={scheduleModeDraft !== 'custom'}
          />
        </label>
        <p className="settings-hint">
          Quartz timezone is stored next to the cron expression and applied when the trigger is
          built.
        </p>
        <div className="review-automation-schedule__preview">
          <div className="review-automation-schedule__preview-title">Next runs</div>
          {previewQuery.isFetching ? (
            <div className="settings-hint">Loading preview…</div>
          ) : previewQuery.isError ? (
            <div className="settings-hint is-error">
              {previewQuery.error instanceof Error
                ? previewQuery.error.message
                : 'Unable to preview next runs.'}
            </div>
          ) : formattedPreviewRuns.length > 0 ? (
            <ol className="review-automation-schedule__preview-list">
              {formattedPreviewRuns.map((nextRun) => (
                <li key={nextRun.raw}>{nextRun.label}</li>
              ))}
            </ol>
          ) : (
            <div className="settings-hint">No upcoming runs found for this schedule.</div>
          )}
        </div>
        <div className="review-automation-schedule__docs">
          <div className="review-automation-schedule__docs-title">Quartz cron reference</div>
          <ul className="review-automation-schedule__docs-list">
            <li>
              Format: <code>seconds minutes hours day-of-month month day-of-week</code>
            </li>
            <li>
              Use <code>?</code> in either day-of-month or day-of-week when the other one is in use.
            </li>
            <li>
              Every day at midnight: <code>0 0 0 * * ?</code>
            </li>
            <li>
              Weekdays at 8am: <code>0 0 8 ? * MON-FRI</code>
            </li>
            <li>
              First day of month at 6:30am: <code>0 30 6 1 * ?</code>
            </li>
            <li>
              Every minute: <code>0 * * * * ?</code>
            </li>
            <li>
              Every 30 seconds: <code>0/30 * * * * ?</code>
            </li>
          </ul>
        </div>
        <p className="settings-hint">
          Preview: <code>{formatReviewAutomationSchedule(cronExpressionDraft, timeZoneDraft)}</code>
        </p>
      </div>
      <div className="modal__actions">
        <button type="button" className="modal__button" onClick={onClose}>
          Cancel
        </button>
        <button
          type="button"
          className="modal__button modal__button--primary"
          onClick={() =>
            onApply({
              cronExpression: cronExpressionDraft.trim(),
              timeZone: timeZoneDraft.trim(),
            })
          }
          disabled={!cronExpressionDraft.trim()}
        >
          Use schedule
        </button>
      </div>
    </Modal>
  );
}
