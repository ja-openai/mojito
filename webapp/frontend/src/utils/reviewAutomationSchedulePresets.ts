import type { ReviewAutomationScheduleMode } from './reviewAutomationSchedule';

export const REVIEW_AUTOMATION_SCHEDULE_PRESETS: Array<{
  label: string;
  mode: ReviewAutomationScheduleMode;
  timeOfDay: string;
  timeZone: string;
  cronExpression?: string;
}> = [
  {
    label: 'Weekdays 8am Los Angeles',
    mode: 'weekdays',
    timeOfDay: '08:00',
    timeZone: 'America/Los_Angeles',
  },
  {
    label: 'Daily midnight UTC',
    mode: 'daily',
    timeOfDay: '00:00',
    timeZone: 'UTC',
  },
  {
    label: 'Every minute UTC',
    mode: 'custom',
    timeOfDay: '00:00',
    timeZone: 'UTC',
    cronExpression: '0 * * * * ?',
  },
  {
    label: 'Every 30s UTC',
    mode: 'custom',
    timeOfDay: '00:00',
    timeZone: 'UTC',
    cronExpression: '0/30 * * * * ?',
  },
];
