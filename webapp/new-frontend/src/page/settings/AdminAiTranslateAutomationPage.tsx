import './settings-page.css';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useMemo, useState } from 'react';
import { Navigate } from 'react-router-dom';

import {
  type ApiAiTranslateAutomationConfig,
  fetchAiTranslateAutomationConfig,
  runAiTranslateAutomationNow,
  updateAiTranslateAutomationConfig,
} from '../../api/ai-translate-automation';
import { RepositoryMultiSelect } from '../../components/RepositoryMultiSelect';
import { useUser } from '../../components/RequireUser';
import { useRepositories } from '../../hooks/useRepositories';
import { useRepositorySelectionOptions } from '../../utils/repositorySelection';

const CRON_PRESETS = [
  { label: 'Off', value: '' },
  { label: 'Every 5 min', value: '0 */5 * * * ?' },
  { label: 'Every 15 min', value: '0 */15 * * * ?' },
  { label: 'Hourly', value: '0 0 * * * ?' },
];

export function AdminAiTranslateAutomationPage() {
  const user = useUser();
  const isAdmin = user.role === 'ROLE_ADMIN';
  const queryClient = useQueryClient();
  const { data: repositories } = useRepositories();
  const repositorySelectionOptions = useRepositorySelectionOptions(repositories ?? []);
  const [automationEnabledDraft, setAutomationEnabledDraft] = useState(false);
  const [automationRepositoryIdsDraft, setAutomationRepositoryIdsDraft] = useState<number[]>([]);
  const [automationSourceTextMaxDraft, setAutomationSourceTextMaxDraft] = useState('100');
  const [automationCronExpressionDraft, setAutomationCronExpressionDraft] = useState('');

  const automationConfigQuery = useQuery<ApiAiTranslateAutomationConfig>({
    queryKey: ['ai-translate-automation-config'],
    queryFn: fetchAiTranslateAutomationConfig,
    staleTime: 30_000,
    enabled: isAdmin,
  });

  const saveAutomationMutation = useMutation({
    mutationFn: updateAiTranslateAutomationConfig,
    onSuccess: async (nextConfig) => {
      queryClient.setQueryData(['ai-translate-automation-config'], nextConfig);
      await queryClient.invalidateQueries({ queryKey: ['ai-translate-automation-config'] });
      setAutomationEnabledDraft(nextConfig.enabled);
      setAutomationRepositoryIdsDraft(nextConfig.repositoryIds);
      setAutomationSourceTextMaxDraft(String(nextConfig.sourceTextMaxCountPerLocale));
      setAutomationCronExpressionDraft(nextConfig.cronExpression ?? '');
    },
  });

  const runAutomationMutation = useMutation({
    mutationFn: runAiTranslateAutomationNow,
  });

  useEffect(() => {
    const config = automationConfigQuery.data;
    if (!config) {
      return;
    }
    setAutomationEnabledDraft(config.enabled);
    setAutomationRepositoryIdsDraft(config.repositoryIds);
    setAutomationSourceTextMaxDraft(String(config.sourceTextMaxCountPerLocale));
    setAutomationCronExpressionDraft(config.cronExpression ?? '');
  }, [automationConfigQuery.data]);

  const automationSourceTextMax = useMemo(() => {
    const trimmed = automationSourceTextMaxDraft.trim();
    if (!trimmed) {
      return { value: null as number | null, valid: false };
    }
    const parsed = Number(trimmed);
    if (!Number.isFinite(parsed) || !Number.isInteger(parsed) || parsed < 1) {
      return { value: null as number | null, valid: false };
    }
    return { value: parsed, valid: true };
  }, [automationSourceTextMaxDraft]);

  const automationSourceTextMaxError =
    automationSourceTextMaxDraft.trim() && !automationSourceTextMax.valid
      ? 'Enter a positive whole number'
      : null;

  const isAutomationDirty = useMemo(() => {
    const saved = automationConfigQuery.data;
    if (!saved) {
      return false;
    }
    if (automationEnabledDraft !== saved.enabled) {
      return true;
    }
    if (
      automationSourceTextMax.valid &&
      automationSourceTextMax.value !== saved.sourceTextMaxCountPerLocale
    ) {
      return true;
    }
    if (automationRepositoryIdsDraft.length !== saved.repositoryIds.length) {
      return true;
    }
    if (automationCronExpressionDraft !== (saved.cronExpression ?? '')) {
      return true;
    }
    return automationRepositoryIdsDraft.some(
      (value, index) => value !== saved.repositoryIds[index],
    );
  }, [
    automationConfigQuery.data,
    automationEnabledDraft,
    automationRepositoryIdsDraft,
    automationCronExpressionDraft,
    automationSourceTextMax.valid,
    automationSourceTextMax.value,
  ]);

  useEffect(() => {
    if (!isAutomationDirty) {
      return;
    }
    saveAutomationMutation.reset();
    runAutomationMutation.reset();
  }, [isAutomationDirty, runAutomationMutation, saveAutomationMutation]);

  if (!isAdmin) {
    return <Navigate to="/repositories" replace />;
  }

  const handleSaveAutomationConfig = () => {
    if (!automationSourceTextMax.valid) {
      return;
    }
    saveAutomationMutation.mutate({
      enabled: automationEnabledDraft,
      repositoryIds: automationRepositoryIdsDraft,
      sourceTextMaxCountPerLocale: automationSourceTextMax.value as number,
      cronExpression: automationCronExpressionDraft.trim() || null,
    });
  };

  return (
    <div className="settings-page">
      <div className="settings-page__header">
        <h1>AI translate automation</h1>
      </div>

      <section className="settings-card" aria-labelledby="settings-ai-translate-automation">
        <div className="settings-card__header">
          <h2 id="settings-ai-translate-automation">Automatic AI translate</h2>
        </div>
        <p className="settings-note">
          Manual runs use the saved repository list and per-locale limit immediately. Optional cron
          scheduling is configured below and only targets strings already marked for translation,
          importing them as review-needed.
        </p>
        {automationConfigQuery.error ? (
          <p className="settings-hint is-error">
            {automationConfigQuery.error instanceof Error
              ? automationConfigQuery.error.message
              : 'Failed to load automatic AI translate settings.'}
          </p>
        ) : null}
        <div className="settings-field">
          <label className="settings-toggle">
            <input
              type="checkbox"
              checked={automationEnabledDraft}
              onChange={(event) => setAutomationEnabledDraft(event.target.checked)}
              disabled={automationConfigQuery.isLoading || saveAutomationMutation.isPending}
            />
            <span>Enable automatic AI translate backlog draining</span>
          </label>
        </div>
        <div className="settings-field">
          <div className="settings-field__header">
            <div className="settings-field__label">Cron schedule</div>
          </div>
          <div className="settings-field__row">
            <input
              type="text"
              className="settings-input"
              value={automationCronExpressionDraft}
              onChange={(event) => setAutomationCronExpressionDraft(event.target.value)}
              disabled={automationConfigQuery.isLoading || saveAutomationMutation.isPending}
              placeholder="Leave blank to disable cron scheduling"
            />
          </div>
          <div className="settings-pills" role="group" aria-label="Cron schedule presets">
            {CRON_PRESETS.map((preset) => (
              <button
                key={preset.label}
                type="button"
                className={`settings-pill${
                  automationCronExpressionDraft === preset.value ? ' is-active' : ''
                }`}
                onClick={() => setAutomationCronExpressionDraft(preset.value)}
                disabled={automationConfigQuery.isLoading || saveAutomationMutation.isPending}
              >
                {preset.label}
              </button>
            ))}
          </div>
          <div className="settings-hint">
            Blank means no scheduled cron run. Manual <code>Run now</code> still works.
          </div>
        </div>
        <div className="settings-field">
          <div className="settings-field__header">
            <div className="settings-field__label">Repositories</div>
          </div>
          <RepositoryMultiSelect
            label="Repositories"
            options={repositorySelectionOptions}
            selectedIds={automationRepositoryIdsDraft}
            onChange={(next) => setAutomationRepositoryIdsDraft([...next].sort((a, b) => a - b))}
            className="settings-repository-select"
            buttonAriaLabel="Select repositories for automatic AI translate"
            disabled={automationConfigQuery.isLoading || saveAutomationMutation.isPending}
          />
          <p className="settings-hint">
            Leave the list empty to keep automation configured but idle.
          </p>
        </div>
        <div className="settings-field">
          <div className="settings-field__header">
            <div className="settings-field__label">Per-locale batch size</div>
          </div>
          <div className="settings-field__row">
            <input
              type="number"
              min={1}
              inputMode="numeric"
              className="settings-input"
              value={automationSourceTextMaxDraft}
              onChange={(event) => setAutomationSourceTextMaxDraft(event.target.value)}
              disabled={automationConfigQuery.isLoading || saveAutomationMutation.isPending}
            />
          </div>
          {automationSourceTextMaxError ? (
            <div className="settings-hint is-error">{automationSourceTextMaxError}</div>
          ) : (
            <div className="settings-hint">
              Limits how many source strings per locale each automation sweep schedules.
            </div>
          )}
        </div>
        <div className="settings-card__footer">
          <div className="settings-card__footer-group">
            <div className="settings-actions">
              <button
                type="button"
                className="settings-button"
                onClick={() => runAutomationMutation.mutate()}
                disabled={
                  automationConfigQuery.isLoading ||
                  saveAutomationMutation.isPending ||
                  runAutomationMutation.isPending
                }
              >
                Run now
              </button>
              <button
                type="button"
                className="settings-button settings-button--primary"
                onClick={handleSaveAutomationConfig}
                disabled={
                  automationConfigQuery.isLoading ||
                  saveAutomationMutation.isPending ||
                  runAutomationMutation.isPending ||
                  !automationSourceTextMax.valid ||
                  !isAutomationDirty
                }
              >
                Save
              </button>
            </div>
            {runAutomationMutation.error ? (
              <p className="settings-hint is-error">
                {runAutomationMutation.error instanceof Error
                  ? runAutomationMutation.error.message
                  : 'Failed to run automatic AI translate.'}
              </p>
            ) : null}
            {saveAutomationMutation.error ? (
              <p className="settings-hint is-error">
                {saveAutomationMutation.error instanceof Error
                  ? saveAutomationMutation.error.message
                  : 'Failed to save automatic AI translate settings.'}
              </p>
            ) : null}
            {runAutomationMutation.data ? (
              <p className="settings-hint">
                Scheduled {runAutomationMutation.data.scheduledRepositoryCount}{' '}
                {runAutomationMutation.data.scheduledRepositoryCount === 1
                  ? 'repository'
                  : 'repositories'}
                .
              </p>
            ) : null}
            {saveAutomationMutation.isSuccess && !isAutomationDirty ? (
              <p className="settings-hint">Settings saved.</p>
            ) : null}
          </div>
        </div>
      </section>
    </div>
  );
}
