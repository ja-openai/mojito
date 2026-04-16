import './settings-page.css';
import './admin-translation-incidents-page.css';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useMemo, useState } from 'react';
import { Navigate } from 'react-router-dom';

import {
  type ApiTranslationIncidentDetail,
  type ApiTranslationIncidentStatus,
  fetchTranslationIncident,
  fetchTranslationIncidents,
  rejectTranslationIncident,
} from '../../api/translation-incidents';
import { useUser } from '../../components/RequireUser';
import { SettingsSubpageHeader } from './SettingsSubpageHeader';

const STATUS_FILTERS: Array<{ label: string; value: ApiTranslationIncidentStatus | null }> = [
  { label: 'All', value: null },
  { label: 'Ready', value: 'READY_TO_REJECT' },
  { label: 'Review', value: 'PENDING_REVIEW' },
  { label: 'Rejected', value: 'REJECTED' },
  { label: 'Failed', value: 'REJECT_FAILED' },
];

function formatDateTime(value: string | null) {
  if (!value) {
    return '—';
  }
  return new Intl.DateTimeFormat(undefined, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
  }).format(new Date(value));
}

function renderMaybeLink(value: string | null) {
  if (!value) {
    return '—';
  }
  if (value.startsWith('http://') || value.startsWith('https://')) {
    return (
      <a href={value} target="_blank" rel="noreferrer" className="settings-table__link">
        {value}
      </a>
    );
  }
  return value;
}

function StatusBadge({ status }: { status: ApiTranslationIncidentStatus }) {
  return (
    <span
      className={`translation-incidents-page__status translation-incidents-page__status--${status}`}
    >
      <span className="translation-incidents-page__status-dot" aria-hidden="true" />
      {status.split('_').join(' ')}
    </span>
  );
}

export function AdminTranslationIncidentsPage() {
  const user = useUser();
  const isAdmin = user.role === 'ROLE_ADMIN';
  const queryClient = useQueryClient();
  const [statusFilter, setStatusFilter] = useState<ApiTranslationIncidentStatus | null>(null);
  const [selectedIncidentId, setSelectedIncidentId] = useState<number | null>(null);
  const [notice, setNotice] = useState<{ kind: 'success' | 'error'; message: string } | null>(null);

  const incidentsQuery = useQuery({
    queryKey: ['translation-incidents', statusFilter],
    queryFn: () => fetchTranslationIncidents({ status: statusFilter, limit: 100 }),
  });

  useEffect(() => {
    const incidents = incidentsQuery.data ?? [];
    if (incidents.length === 0) {
      if (selectedIncidentId !== null) {
        setSelectedIncidentId(null);
      }
      return;
    }
    if (
      selectedIncidentId === null ||
      !incidents.some((incident) => incident.id === selectedIncidentId)
    ) {
      setSelectedIncidentId(incidents[0].id);
    }
  }, [incidentsQuery.data, selectedIncidentId]);

  const selectedIncidentSummary = useMemo(
    () => (incidentsQuery.data ?? []).find((incident) => incident.id === selectedIncidentId) ?? null,
    [incidentsQuery.data, selectedIncidentId],
  );

  const detailQuery = useQuery<ApiTranslationIncidentDetail>({
    queryKey: ['translation-incident', selectedIncidentId],
    queryFn: () => fetchTranslationIncident(selectedIncidentId!),
    enabled: selectedIncidentId !== null,
  });

  const rejectMutation = useMutation({
    mutationFn: ({ incidentId, comment }: { incidentId: number; comment: string | null }) =>
      rejectTranslationIncident(incidentId, comment),
    onSuccess: async (detail) => {
      setNotice({
        kind: 'success',
        message: `Rejected translation incident #${detail.id}.`,
      });
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['translation-incidents'] }),
        queryClient.invalidateQueries({ queryKey: ['translation-incident', detail.id] }),
      ]);
    },
    onError: (error: Error) => {
      setNotice({
        kind: 'error',
        message: error.message || 'Reject failed.',
      });
    },
  });

  if (!isAdmin) {
    return <Navigate to="/settings/me" replace />;
  }

  const detail = detailQuery.data ?? null;
  const isRejecting = rejectMutation.isPending;

  const handleReject = () => {
    if (!detail?.canReject) {
      return;
    }
    const operatorComment = window.prompt('Optional operator note for the rejection audit trail:', '');
    const confirmed = window.confirm(
      `Reject the current Mojito translation for ${detail.stringId} (${detail.resolvedLocale ?? detail.observedLocale})?`,
    );
    if (!confirmed) {
      return;
    }
    setNotice(null);
    rejectMutation.mutate({ incidentId: detail.id, comment: operatorComment?.trim() || null });
  };

  return (
    <div className="settings-subpage">
      <SettingsSubpageHeader
        backTo="/settings/system"
        backLabel="Back to system settings"
        context="System settings"
        title="Translation incidents"
      />

      <div className="settings-page settings-page--wide translation-incidents-page">
        {notice ? (
          <p className={`settings-hint${notice.kind === 'error' ? ' is-error' : ''}`}>
            {notice.message}
          </p>
        ) : null}

        <div className="translation-incidents-page__layout">
          <section className="settings-card">
            <div className="settings-card__header">
              <h2>Queue</h2>
              <div className="translation-incidents-page__toolbar">
                <div className="settings-pills" role="tablist" aria-label="Incident status filters">
                  {STATUS_FILTERS.map((filter) => (
                    <button
                      key={filter.label}
                      type="button"
                      className={`settings-pill${statusFilter === filter.value ? ' is-active' : ''}`}
                      onClick={() => {
                        setStatusFilter(filter.value);
                        setNotice(null);
                      }}
                    >
                      {filter.label}
                    </button>
                  ))}
                </div>
              </div>
            </div>

            {incidentsQuery.isLoading ? (
              <p className="settings-page__hint">Loading incidents…</p>
            ) : incidentsQuery.isError ? (
              <p className="settings-hint is-error">
                {(incidentsQuery.error).message || 'Failed to load incidents.'}
              </p>
            ) : (incidentsQuery.data?.length ?? 0) === 0 ? (
              <p className="settings-page__hint">No translation incidents in this view yet.</p>
            ) : (
              <div className="settings-table-wrapper">
                <table className="settings-table">
                  <thead>
                    <tr>
                      <th>String</th>
                      <th>Locale</th>
                      <th>Status</th>
                      <th>Review project</th>
                      <th>Updated</th>
                    </tr>
                  </thead>
                  <tbody>
                    {(incidentsQuery.data ?? []).map((incident) => (
                      <tr
                        key={incident.id}
                        className={`translation-incidents-page__row${incident.id === selectedIncidentId ? ' is-selected' : ''}`}
                      >
                        <td>
                          <button
                            type="button"
                            className="translation-incidents-page__row-button"
                            onClick={() => {
                              setSelectedIncidentId(incident.id);
                              setNotice(null);
                            }}
                          >
                            <div>{incident.stringId}</div>
                            <div className="settings-hint">
                              {incident.repositoryName ?? 'Unknown repo'}
                            </div>
                          </button>
                        </td>
                        <td>{incident.resolvedLocale ?? incident.observedLocale}</td>
                        <td>
                          <StatusBadge status={incident.status} />
                        </td>
                        <td>
                          <div>{incident.reviewProjectName ?? '—'}</div>
                          <div className="settings-hint">{incident.reviewProjectConfidence ?? '—'}</div>
                        </td>
                        <td>{formatDateTime(incident.lastModifiedDate)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>

          <section className="settings-card">
            <div className="settings-card__header">
              <h2>Detail</h2>
              {selectedIncidentSummary ? <StatusBadge status={selectedIncidentSummary.status} /> : null}
            </div>

            {detailQuery.isLoading ? (
              <p className="settings-page__hint">Loading incident detail…</p>
            ) : detailQuery.isError ? (
              <p className="settings-hint is-error">
                {(detailQuery.error).message || 'Failed to load incident detail.'}
              </p>
            ) : detail ? (
              <div className="translation-incidents-page__detail-grid">
                    <dl className="translation-incidents-page__detail-list">
                      <div className="translation-incidents-page__detail-item">
                        <dt>String ID</dt>
                        <dd>{detail.stringId}</dd>
                      </div>
                      <div className="translation-incidents-page__detail-item">
                        <dt>Repository</dt>
                        <dd>{detail.repositoryName ?? '—'}</dd>
                      </div>
                      <div className="translation-incidents-page__detail-item">
                        <dt>Observed locale</dt>
                        <dd>{detail.observedLocale}</dd>
                      </div>
                      <div className="translation-incidents-page__detail-item">
                        <dt>Resolved locale</dt>
                        <dd>{detail.resolvedLocale ?? '—'}</dd>
                      </div>
                      <div className="translation-incidents-page__detail-item">
                        <dt>Lookup resolution</dt>
                        <dd>
                          {detail.lookupResolutionStatus} · {detail.localeResolutionStrategy}
                          {detail.localeUsedFallback ? ' fallback' : ''}
                        </dd>
                      </div>
                      <div className="translation-incidents-page__detail-item">
                        <dt>Review project</dt>
                        <dd>
                          {detail.reviewProjectLink ? (
                            <a
                              href={detail.reviewProjectLink}
                              target="_blank"
                              rel="noreferrer"
                              className="settings-table__link"
                            >
                              {detail.reviewProjectName ?? detail.reviewProjectLink}
                            </a>
                          ) : (
                            detail.reviewProjectName ?? '—'
                          )}
                        </dd>
                      </div>
                      <div className="translation-incidents-page__detail-item">
                        <dt>People</dt>
                        <dd>
                          Author: {detail.translationAuthorUsername ?? '—'}
                          <br />
                          Reviewer: {detail.reviewerUsername ?? '—'}
                          <br />
                          Owner: {detail.ownerUsername ?? '—'}
                        </dd>
                      </div>
                      <div className="translation-incidents-page__detail-item">
                        <dt>Source reference</dt>
                        <dd>{renderMaybeLink(detail.sourceReference)}</dd>
                      </div>
                    </dl>

                    <div className="translation-incidents-page__section-copy">
                      <h3>Reason</h3>
                      <p className="translation-incidents-page__code">{detail.reason}</p>
                    </div>

                    <div className="translation-incidents-page__section-copy">
                      <h3>Current translation</h3>
                      <p className="translation-incidents-page__code">{detail.selectedTarget ?? '—'}</p>
                    </div>

                    <div className="translation-incidents-page__section-copy">
                      <h3>Source message</h3>
                      <p className="translation-incidents-page__code">{detail.selectedSource ?? '—'}</p>
                    </div>

                    <div className="translation-incidents-page__section-copy">
                      <h3>Slack draft</h3>
                      <p className="translation-incidents-page__pre">{detail.slackDraft ?? 'No draft yet.'}</p>
                    </div>

                    {detail.lookupCandidates.length > 0 ? (
                      <div className="translation-incidents-page__section-copy">
                        <h3>Lookup candidates</h3>
                        <div className="settings-table-wrapper">
                          <table className="settings-table">
                            <thead>
                              <tr>
                                <th>Repository</th>
                                <th>Asset</th>
                                <th>Status</th>
                                <th>Reject</th>
                              </tr>
                            </thead>
                            <tbody>
                              {detail.lookupCandidates.map((candidate) => (
                                <tr
                                  key={`${candidate.tmTextUnitId ?? 'missing'}:${candidate.tmTextUnitVariantId ?? 'missing'}`}
                                >
                                  <td>{candidate.repositoryName ?? '—'}</td>
                                  <td>{candidate.assetPath ?? '—'}</td>
                                  <td>{candidate.status ?? '—'}</td>
                                  <td>{candidate.canReject ? 'Yes' : 'No'}</td>
                                </tr>
                              ))}
                            </tbody>
                          </table>
                        </div>
                      </div>
                    ) : null}

                    {detail.reviewProjectCandidates.length > 0 ? (
                      <div className="translation-incidents-page__section-copy">
                        <h3>Review lineage</h3>
                        <div className="settings-table-wrapper">
                          <table className="settings-table">
                            <thead>
                              <tr>
                                <th>Project</th>
                                <th>Confidence</th>
                                <th>Reviewer</th>
                                <th>Owner</th>
                              </tr>
                            </thead>
                            <tbody>
                              {detail.reviewProjectCandidates.map((candidate) => (
                                <tr key={candidate.reviewProjectId ?? candidate.reviewProjectName ?? 'unknown'}>
                                  <td>
                                    {candidate.reviewProjectLink ? (
                                      <a
                                        href={candidate.reviewProjectLink}
                                        target="_blank"
                                        rel="noreferrer"
                                        className="settings-table__link"
                                      >
                                        {candidate.reviewProjectName ?? candidate.reviewProjectLink}
                                      </a>
                                    ) : (
                                      candidate.reviewProjectName ?? '—'
                                    )}
                                  </td>
                                  <td>{candidate.confidence ?? '—'}</td>
                                  <td>{candidate.reviewerUsername ?? '—'}</td>
                                  <td>{candidate.ownerUsername ?? '—'}</td>
                                </tr>
                              ))}
                            </tbody>
                          </table>
                        </div>
                      </div>
                    ) : null}

                    <div className="translation-incidents-page__actions">
                      <button
                        type="button"
                        className="settings-button settings-button--primary"
                        onClick={handleReject}
                        disabled={!detail.canReject || isRejecting || detail.status === 'REJECTED'}
                      >
                        {isRejecting ? 'Rejecting…' : 'Reject translation'}
                      </button>
                      <span className="settings-hint">
                        {detail.canReject
                          ? 'Reject writes TRANSLATION_NEEDED with an audit comment.'
                          : 'Reject is available only when the incident resolved to one clear candidate.'}
                      </span>
                    </div>

                    {detail.rejectAuditComment ? (
                      <div className="translation-incidents-page__section-copy">
                        <h3>Reject audit</h3>
                        <p className="translation-incidents-page__pre">{detail.rejectAuditComment}</p>
                      </div>
                    ) : null}
              </div>
            ) : (
              <p className="settings-page__hint">Select an incident to inspect the stored context.</p>
            )}
          </section>
        </div>
      </div>
    </div>
  );
}
