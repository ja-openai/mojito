export type ReviewAutomationScheduleMode = 'daily' | 'weekdays' | 'custom';

export const DEFAULT_REVIEW_AUTOMATION_CRON_EXPRESSION = '0 0 0 * * ?';
export const DEFAULT_REVIEW_AUTOMATION_TIME_OF_DAY = '00:00';
export const DEFAULT_REVIEW_AUTOMATION_TIME_ZONE = 'UTC';

const FALLBACK_TIME_ZONES = [
  'UTC',
  'America/Los_Angeles',
  'America/Denver',
  'America/Chicago',
  'America/New_York',
  'Europe/London',
  'Europe/Paris',
  'Asia/Tokyo',
  'Asia/Kolkata',
  'Australia/Sydney',
];

const DAILY_CRON_PATTERN = /^0\s+([0-5]?\d)\s+([01]?\d|2[0-3])\s+\*\s+\*\s+\?$/i;
const WEEKDAY_CRON_PATTERN = /^0\s+([0-5]?\d)\s+([01]?\d|2[0-3])\s+\?\s+\*\s+MON-FRI$/i;

const toTwoDigits = (value: number) => String(value).padStart(2, '0');

export function getDefaultReviewAutomationTimeZone() {
  const resolved = Intl.DateTimeFormat().resolvedOptions().timeZone;
  return resolved?.trim() || DEFAULT_REVIEW_AUTOMATION_TIME_ZONE;
}

export function getReviewAutomationTimeZoneOptions(selectedTimeZone?: string | null) {
  const zones = new Set(FALLBACK_TIME_ZONES);
  const selected = selectedTimeZone?.trim();
  if (selected) {
    zones.add(selected);
  }
  return Array.from(zones).sort((left, right) => left.localeCompare(right));
}

export function isValidReviewAutomationTimeOfDay(value: string) {
  return /^([01]\d|2[0-3]):[0-5]\d$/.test(value.trim());
}

export function buildReviewAutomationCronExpression(
  mode: ReviewAutomationScheduleMode,
  timeOfDay: string,
) {
  if (!isValidReviewAutomationTimeOfDay(timeOfDay)) {
    return null;
  }
  if (mode === 'custom') {
    return null;
  }
  const [hourRaw, minuteRaw] = timeOfDay.split(':');
  const hour = Number(hourRaw);
  const minute = Number(minuteRaw);
  if (mode === 'weekdays') {
    return `0 ${minute} ${hour} ? * MON-FRI`;
  }
  return `0 ${minute} ${hour} * * ?`;
}

export function deriveReviewAutomationScheduleHelper(cronExpression: string | null | undefined): {
  mode: ReviewAutomationScheduleMode;
  timeOfDay: string;
} {
  const trimmed = cronExpression?.trim() ?? '';
  const weekdayMatch = trimmed.match(WEEKDAY_CRON_PATTERN);
  if (weekdayMatch) {
    const minute = Number(weekdayMatch[1]);
    const hour = Number(weekdayMatch[2]);
    return {
      mode: 'weekdays',
      timeOfDay: `${toTwoDigits(hour)}:${toTwoDigits(minute)}`,
    };
  }

  const dailyMatch = trimmed.match(DAILY_CRON_PATTERN);
  if (dailyMatch) {
    const minute = Number(dailyMatch[1]);
    const hour = Number(dailyMatch[2]);
    return {
      mode: 'daily',
      timeOfDay: `${toTwoDigits(hour)}:${toTwoDigits(minute)}`,
    };
  }

  return {
    mode: 'custom',
    timeOfDay: DEFAULT_REVIEW_AUTOMATION_TIME_OF_DAY,
  };
}

export function formatReviewAutomationSchedule(cronExpression: string, timeZone: string) {
  const helper = deriveReviewAutomationScheduleHelper(cronExpression);
  const zone = timeZone?.trim() || DEFAULT_REVIEW_AUTOMATION_TIME_ZONE;
  if (helper.mode === 'weekdays') {
    return `Weekdays ${helper.timeOfDay} · ${zone}`;
  }
  if (helper.mode === 'daily') {
    return `Daily ${helper.timeOfDay} · ${zone}`;
  }
  return `${cronExpression} · ${zone}`;
}
