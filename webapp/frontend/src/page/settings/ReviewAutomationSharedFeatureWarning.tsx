import { formatLocalDateTime } from '../../utils/dateTime';
import type { SharedFeatureScheduleWarning } from './reviewAutomationSharedFeatureWarnings';

export function ReviewAutomationSharedFeatureWarning({
  warnings,
  checkedAutomationCount,
  totalAutomationCount,
}: {
  warnings: SharedFeatureScheduleWarning[];
  checkedAutomationCount: number;
  totalAutomationCount: number;
}) {
  if (!warnings.length) {
    return null;
  }

  const visibleWarnings = warnings.slice(0, 3);
  const hiddenWarningCount = warnings.length - visibleWarnings.length;
  const checkedCountText =
    totalAutomationCount > checkedAutomationCount
      ? ` Checked ${checkedAutomationCount} of ${totalAutomationCount} enabled automations.`
      : '';

  return (
    <p className="settings-hint is-warning">
      Shared feature warning: selected features are also used by{' '}
      {visibleWarnings
        .map((warning) => {
          const nextRunText = warning.nextRunAt
            ? `, next run ${formatLocalDateTime(warning.nextRunAt)}`
            : ', no next run scheduled';
          return `${warning.automationName} (${warning.featureNames.join(', ')}${nextRunText})`;
        })
        .join('; ')}
      {hiddenWarningCount > 0 ? ` and ${hiddenWarningCount} more` : ''}. Sequential runs skip text
      units already in open review projects; overlapping runs can still send the same text units
      more than once.
      {checkedCountText}
    </p>
  );
}
