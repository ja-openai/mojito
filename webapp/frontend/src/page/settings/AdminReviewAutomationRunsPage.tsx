import './settings-page.css';

import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { Navigate } from 'react-router-dom';

import {
  fetchReviewAutomationOptions,
  fetchReviewAutomationRuns,
} from '../../api/review-automations';
import { NumericPresetDropdown } from '../../components/NumericPresetDropdown';
import { useUser } from '../../components/RequireUser';
import { ReviewAutomationRunsTable } from '../../components/ReviewAutomationRunsTable';
import { SingleSelectDropdown } from '../../components/SingleSelectDropdown';
import { SettingsSubpageHeader } from './SettingsSubpageHeader';

const RUN_HISTORY_LIMIT_PRESETS = [20, 50, 100];

export function AdminReviewAutomationRunsPage() {
  const user = useUser();
  const isAdmin = user.role === 'ROLE_ADMIN';
  const [runHistoryLimit, setRunHistoryLimit] = useState(50);
  const [selectedAutomationId, setSelectedAutomationId] = useState<number | null>(null);

  const automationOptionsQuery = useQuery({
    queryKey: ['review-automation-options'],
    queryFn: fetchReviewAutomationOptions,
    enabled: isAdmin,
    staleTime: 30_000,
  });

  const automationRunsQuery = useQuery({
    queryKey: ['review-automation-runs', 'all', selectedAutomationId, runHistoryLimit],
    queryFn: () =>
      fetchReviewAutomationRuns({
        automationIds: selectedAutomationId == null ? undefined : [selectedAutomationId],
        limit: runHistoryLimit,
      }),
    enabled: isAdmin,
    staleTime: 10_000,
  });

  if (!isAdmin) {
    return <Navigate to="/repositories" replace />;
  }

  return (
    <div className="settings-subpage">
      <SettingsSubpageHeader
        backTo="/settings/system"
        backLabel="Back to settings"
        context="Settings > Review automation"
        title="Automation runs"
      />
      <div className="settings-page settings-page--wide">
        <section className="settings-card">
          <div className="settings-card__header">
            <h2>Recent runs</h2>
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
          <p className="settings-hint">
            Use this page to review recent manual and scheduled automation activity across the
            system.
          </p>
          <div className="settings-filter-grid">
            <div className="settings-field">
              <label className="settings-field__label">Automation</label>
              <SingleSelectDropdown
                label="Automation"
                options={(automationOptionsQuery.data ?? []).map((automation) => ({
                  value: automation.id,
                  label: automation.name,
                }))}
                value={selectedAutomationId}
                onChange={setSelectedAutomationId}
                noneLabel="All automations"
                placeholder="All automations"
                disabled={automationOptionsQuery.isLoading}
                buttonAriaLabel="Filter automation runs by automation"
              />
            </div>
          </div>
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
            <ReviewAutomationRunsTable runs={automationRunsQuery.data ?? []} showAutomationColumn />
          )}
        </section>
      </div>
    </div>
  );
}
