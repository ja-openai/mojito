import type { ApiReviewAutomationRun } from '../../api/review-automations';

export function formatDateTime(value?: string | null) {
  if (!value) {
    return '-';
  }

  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) {
    return value;
  }

  return parsed.toLocaleString();
}

export function formatRequestedBy(run: ApiReviewAutomationRun) {
  if (run.requestedByUsername?.trim()) {
    return run.requestedByUsername;
  }

  if (typeof run.requestedByUserId === 'number') {
    return `User #${run.requestedByUserId}`;
  }

  return '-';
}

export function formatRunSource(triggerSource: string) {
  return triggerSource === 'CRON' ? 'Cron' : triggerSource === 'MANUAL' ? 'Manual' : triggerSource;
}

export function formatRunStatus(run: ApiReviewAutomationRun) {
  return run.status === 'COMPLETED'
    ? 'Completed'
    : run.status === 'COMPLETED_WITH_ERRORS'
      ? 'Completed with errors'
      : run.status === 'RUNNING'
        ? 'Running'
        : run.status === 'FAILED'
          ? 'Failed'
          : run.status;
}
