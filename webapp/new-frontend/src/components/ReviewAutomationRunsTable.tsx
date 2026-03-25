import { Link } from 'react-router-dom';

import type { ApiReviewAutomationRun } from '../api/review-automations';
import {
  formatDateTime,
  formatRequestedBy,
  formatRunSource,
  formatRunStatus,
} from '../page/settings/reviewAutomationRunFormatting';

type ReviewAutomationRunsTableProps = {
  runs: ApiReviewAutomationRun[];
  showAutomationColumn?: boolean;
};

export function ReviewAutomationRunsTable({
  runs,
  showAutomationColumn = false,
}: ReviewAutomationRunsTableProps) {
  return (
    <div className="settings-table-wrapper">
      <table className="settings-table">
        <thead>
          <tr>
            <th>Status</th>
            {showAutomationColumn ? <th>Automation</th> : null}
            <th>Source</th>
            <th>Requests</th>
            <th>Projects</th>
            <th>Created locales</th>
            <th>Skipped</th>
            <th>Errors</th>
            <th>Features</th>
            <th>Started</th>
            <th>Finished</th>
            <th>Requested by</th>
          </tr>
        </thead>
        <tbody>
          {runs.map((run) => (
            <tr key={run.id}>
              <td title={run.errorMessage?.trim() || undefined}>{formatRunStatus(run)}</td>
              {showAutomationColumn ? (
                <td>
                  <Link
                    to={`/settings/system/review-automations/${run.automationId}`}
                    className="settings-table__link"
                  >
                    {run.automationName}
                  </Link>
                </td>
              ) : null}
              <td>{formatRunSource(run.triggerSource)}</td>
              <td>{run.createdProjectRequestCount}</td>
              <td>{run.createdProjectCount}</td>
              <td>{run.createdLocaleCount}</td>
              <td>{run.skippedLocaleCount}</td>
              <td>{run.erroredLocaleCount}</td>
              <td>{run.featureCount}</td>
              <td>{formatDateTime(run.startedAt ?? run.createdAt)}</td>
              <td>{formatDateTime(run.finishedAt)}</td>
              <td>{formatRequestedBy(run)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
