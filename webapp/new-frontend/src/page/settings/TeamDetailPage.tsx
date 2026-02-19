import './settings-page.css';
import './admin-team-pools-page.css';
import './admin-user-detail-page.css';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useMemo, useState } from 'react';
import { Navigate, useNavigate, useParams } from 'react-router-dom';

import {
  type ApiTeam,
  deleteTeam,
  fetchTeam,
  fetchTeamProjectManagers,
  fetchTeamTranslators,
  replaceTeamProjectManagers,
  replaceTeamTranslators,
  updateTeam,
} from '../../api/teams';
import { type ApiUser, fetchAllUsersAdmin } from '../../api/users';
import { ConfirmModal } from '../../components/ConfirmModal';
import { MultiSelectChip } from '../../components/MultiSelectChip';
import { useUser } from '../../components/RequireUser';

type StatusNotice = {
  kind: 'success' | 'error';
  message: string;
};

const getUserRole = (entry: ApiUser) => entry.authorities?.[0]?.authority ?? 'ROLE_USER';

const getUserLabel = (entry: ApiUser) => {
  const fullName =
    entry.commonName || [entry.givenName, entry.surname].filter((part) => Boolean(part)).join(' ');
  if (fullName) {
    return `${fullName} (${entry.username})`;
  }
  return entry.username;
};

const normalizeTeamName = (value: string) => value.trim().replace(/\s+/g, ' ');

export function TeamDetailPage() {
  const user = useUser();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const isAdmin = user.role === 'ROLE_ADMIN';
  const isPm = user.role === 'ROLE_PM';
  const canAccess = isAdmin || isPm;
  const params = useParams<{ teamId?: string }>();

  const [draftName, setDraftName] = useState('');
  const [draftPmUserIds, setDraftPmUserIds] = useState<number[]>([]);
  const [draftTranslatorUserIds, setDraftTranslatorUserIds] = useState<number[]>([]);
  const [showAllPmUsers, setShowAllPmUsers] = useState(false);
  const [showAllTranslatorUsers, setShowAllTranslatorUsers] = useState(false);
  const [statusNotice, setStatusNotice] = useState<StatusNotice | null>(null);
  const [pmStatusNotice, setPmStatusNotice] = useState<StatusNotice | null>(null);
  const [translatorStatusNotice, setTranslatorStatusNotice] = useState<StatusNotice | null>(null);
  const [showDeleteConfirm, setShowDeleteConfirm] = useState(false);

  const parsedTeamId = useMemo(() => {
    const raw = params.teamId?.trim();
    if (!raw) {
      return null;
    }
    const id = Number(raw);
    if (!Number.isInteger(id) || id <= 0) {
      return null;
    }
    return id;
  }, [params.teamId]);

  const teamByIdQuery = useQuery<ApiTeam>({
    queryKey: ['team', parsedTeamId],
    queryFn: () => fetchTeam(parsedTeamId as number),
    enabled: parsedTeamId != null && canAccess,
    staleTime: 30_000,
  });

  const effectiveTeam = useMemo(() => teamByIdQuery.data ?? null, [teamByIdQuery.data]);

  const effectiveTeamId = effectiveTeam?.id ?? null;

  const pmUsersQuery = useQuery<ApiUser[]>({
    queryKey: ['users', 'admin', 'pm-roster'],
    queryFn: fetchAllUsersAdmin,
    enabled: isAdmin,
    staleTime: 30_000,
  });

  const translatorUsersQuery = useQuery<ApiUser[]>({
    queryKey: ['users', 'admin', 'translator-roster'],
    queryFn: fetchAllUsersAdmin,
    enabled: canAccess,
    staleTime: 30_000,
  });

  const pmRosterQuery = useQuery({
    queryKey: ['team-project-managers', effectiveTeamId],
    queryFn: () => fetchTeamProjectManagers(effectiveTeamId as number),
    enabled: isAdmin && effectiveTeamId != null,
    staleTime: 30_000,
  });

  const translatorRosterQuery = useQuery({
    queryKey: ['team-translators', effectiveTeamId],
    queryFn: () => fetchTeamTranslators(effectiveTeamId as number),
    enabled: effectiveTeamId != null,
    staleTime: 30_000,
  });

  useEffect(() => {
    setDraftName(effectiveTeam?.name ?? '');
  }, [effectiveTeam?.id, effectiveTeam?.name]);

  useEffect(() => {
    const fromApi = pmRosterQuery.data?.userIds ?? [];
    const normalized = Array.from(new Set(fromApi)).sort((left, right) => left - right);
    setDraftPmUserIds(normalized);
  }, [pmRosterQuery.data?.userIds]);

  useEffect(() => {
    const fromApi = translatorRosterQuery.data?.userIds ?? [];
    const normalized = Array.from(new Set(fromApi)).sort((left, right) => left - right);
    setDraftTranslatorUserIds(normalized);
  }, [translatorRosterQuery.data?.userIds]);

  useEffect(() => {
    setShowAllPmUsers(false);
    setShowAllTranslatorUsers(false);
  }, [effectiveTeamId]);

  const allPmUsers = useMemo(
    () => (pmUsersQuery.data ?? []).filter((entry) => entry.enabled !== false),
    [pmUsersQuery.data],
  );

  const allPmUsersById = useMemo(
    () => new Map(allPmUsers.map((entry) => [entry.id, entry])),
    [allPmUsers],
  );

  const pmOptions = useMemo(() => {
    const baseUsers = showAllPmUsers
      ? allPmUsers
      : allPmUsers.filter((entry) => getUserRole(entry) === 'ROLE_PM');
    const baseUserIds = new Set(baseUsers.map((entry) => entry.id));
    const selectedExtras = draftPmUserIds
      .map((id) => allPmUsersById.get(id))
      .filter((entry): entry is ApiUser => Boolean(entry))
      .filter((entry) => !baseUserIds.has(entry.id));
    return [...baseUsers, ...selectedExtras]
      .sort((left, right) =>
        getUserLabel(left).localeCompare(getUserLabel(right), undefined, { sensitivity: 'base' }),
      )
      .map((entry) => ({
        value: entry.id,
        label: getUserLabel(entry),
      }));
  }, [allPmUsers, allPmUsersById, draftPmUserIds, showAllPmUsers]);

  useEffect(() => {
    const validIds = new Set(allPmUsers.map((entry) => entry.id));
    setDraftPmUserIds((current) => current.filter((id) => validIds.has(id)));
  }, [allPmUsers]);

  const allTranslatorUsers = useMemo(
    () => (translatorUsersQuery.data ?? []).filter((entry) => entry.enabled !== false),
    [translatorUsersQuery.data],
  );

  const allTranslatorUsersById = useMemo(
    () => new Map(allTranslatorUsers.map((entry) => [entry.id, entry])),
    [allTranslatorUsers],
  );

  const translatorOptions = useMemo(() => {
    const baseUsers = showAllTranslatorUsers
      ? allTranslatorUsers
      : allTranslatorUsers.filter((entry) => getUserRole(entry) === 'ROLE_TRANSLATOR');
    const baseUserIds = new Set(baseUsers.map((entry) => entry.id));
    const selectedExtras = draftTranslatorUserIds
      .map((id) => allTranslatorUsersById.get(id))
      .filter((entry): entry is ApiUser => Boolean(entry))
      .filter((entry) => !baseUserIds.has(entry.id));
    return [...baseUsers, ...selectedExtras]
      .sort((left, right) =>
        getUserLabel(left).localeCompare(getUserLabel(right), undefined, { sensitivity: 'base' }),
      )
      .map((entry) => ({
        value: entry.id,
        label: getUserLabel(entry),
      }));
  }, [allTranslatorUsers, allTranslatorUsersById, draftTranslatorUserIds, showAllTranslatorUsers]);

  useEffect(() => {
    const validIds = new Set(allTranslatorUsers.map((entry) => entry.id));
    setDraftTranslatorUserIds((current) => current.filter((id) => validIds.has(id)));
  }, [allTranslatorUsers]);

  const saveTeamNameMutation = useMutation({
    mutationFn: ({ teamId, name }: { teamId: number; name: string }) => updateTeam(teamId, name),
    onSuccess: async (updated) => {
      if (parsedTeamId != null) {
        queryClient.setQueryData(['team', parsedTeamId], updated);
      }
      await queryClient.invalidateQueries({ queryKey: ['teams'] });
      setStatusNotice({ kind: 'success', message: 'Saved.' });
    },
    onError: (error: Error) => {
      setStatusNotice({ kind: 'error', message: error.message || 'Failed to save team.' });
    },
  });

  const savePmRosterMutation = useMutation({
    mutationFn: ({ teamId, userIds }: { teamId: number; userIds: number[] }) =>
      replaceTeamProjectManagers(teamId, userIds),
    onSuccess: async () => {
      if (effectiveTeamId != null) {
        await queryClient.invalidateQueries({
          queryKey: ['team-project-managers', effectiveTeamId],
        });
      }
      setPmStatusNotice({ kind: 'success', message: 'Saved.' });
    },
    onError: (error: Error) => {
      setPmStatusNotice({
        kind: 'error',
        message: error.message || 'Failed to save project managers.',
      });
    },
  });

  const saveTranslatorRosterMutation = useMutation({
    mutationFn: ({ teamId, userIds }: { teamId: number; userIds: number[] }) =>
      replaceTeamTranslators(teamId, userIds),
    onSuccess: async (result) => {
      if (effectiveTeamId != null) {
        await queryClient.invalidateQueries({ queryKey: ['team-translators', effectiveTeamId] });
      }
      setTranslatorStatusNotice({
        kind: 'success',
        message:
          result.removedLocalePoolRows > 0
            ? `Saved. Removed ${result.removedLocalePoolRows} locale assignment${result.removedLocalePoolRows === 1 ? '' : 's'} for removed translators.`
            : 'Saved.',
      });
    },
    onError: (error: Error) => {
      setTranslatorStatusNotice({
        kind: 'error',
        message: error.message || 'Failed to save translators.',
      });
    },
  });

  const deleteTeamMutation = useMutation({
    mutationFn: (teamId: number) => deleteTeam(teamId),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['teams'] });
      setShowDeleteConfirm(false);
      void navigate('/settings/admin/teams');
    },
    onError: (error: Error) => {
      setShowDeleteConfirm(false);
      setStatusNotice({ kind: 'error', message: error.message || 'Failed to delete team.' });
    },
  });

  if (!canAccess) {
    return <Navigate to="/repositories" replace />;
  }

  if (isPm) {
    return <Navigate to="/settings/teams" replace />;
  }

  if (teamByIdQuery.isLoading) {
    return (
      <div className="user-detail-page__state">
        <div className="spinner spinner--md" aria-hidden />
        <div>Loading team…</div>
      </div>
    );
  }

  if (teamByIdQuery.isError || !effectiveTeam) {
    return (
      <div className="user-detail-page__state user-detail-page__state--error">
        <div>
          {parsedTeamId != null ? `Team #${parsedTeamId} was not found.` : 'Team ID is required.'}
        </div>
        <button
          type="button"
          className="settings-button settings-button--ghost"
          onClick={() => {
            void navigate('/settings/admin/teams');
          }}
        >
          Back to teams
        </button>
      </div>
    );
  }

  const handleSaveName = () => {
    if (!isAdmin || effectiveTeamId == null) {
      return;
    }

    const normalized = normalizeTeamName(draftName);
    if (!normalized) {
      setStatusNotice({ kind: 'error', message: 'Team name is required.' });
      return;
    }

    saveTeamNameMutation.mutate({ teamId: effectiveTeamId, name: normalized });
  };

  const handleSavePmRoster = () => {
    if (!isAdmin || effectiveTeamId == null) {
      return;
    }
    const normalizedIds = Array.from(new Set(draftPmUserIds)).sort((left, right) => left - right);
    savePmRosterMutation.mutate({ teamId: effectiveTeamId, userIds: normalizedIds });
  };

  const handleSaveTranslatorRoster = () => {
    if (effectiveTeamId == null) {
      return;
    }
    const normalizedIds = Array.from(new Set(draftTranslatorUserIds)).sort(
      (left, right) => left - right,
    );
    saveTranslatorRosterMutation.mutate({ teamId: effectiveTeamId, userIds: normalizedIds });
  };

  const pageTitle = normalizeTeamName(draftName) || effectiveTeam.name;

  return (
    <div className="user-detail-page team-detail-page">
      <header className="user-detail-page__header">
        <div className="user-detail-page__header-row">
          <div className="user-detail-page__header-group user-detail-page__header-group--left">
            <button
              type="button"
              className="user-detail-page__header-back"
              onClick={() => {
                void navigate('/settings/admin/teams');
              }}
              aria-label="Back to teams"
              title="Back to teams"
            >
              <svg
                className="user-detail-page__header-back-icon"
                viewBox="0 0 24 24"
                aria-hidden="true"
                focusable="false"
              >
                <path
                  d="M20 12H6m0 0l5-5m-5 5l5 5"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="1.8"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                />
              </svg>
            </button>
            <span className="user-detail-page__header-name">{pageTitle}</span>
          </div>
          <div className="user-detail-page__header-group user-detail-page__header-group--center">
            <span className="user-detail-page__header-meta">ID</span>
            <span className="user-detail-page__header-dot">#</span>
            <span className="user-detail-page__header-meta">{effectiveTeam.id}</span>
          </div>
        </div>
      </header>

      <div className="user-detail-page__content team-detail-page__content">
        <section className="user-detail-page__section">
          <div className="user-detail-page__field">
            <div className="user-detail-page__label">Name</div>
            <input
              type="text"
              className="settings-input"
              value={draftName}
              onChange={(event) => {
                setDraftName(event.target.value);
                setStatusNotice(null);
              }}
            />
            <div className="user-detail-page__actions">
              <button
                type="button"
                className="settings-button settings-button--primary"
                onClick={handleSaveName}
                disabled={saveTeamNameMutation.isPending}
              >
                Save
              </button>
              {statusNotice ? (
                <span
                  className={`user-detail-page__status${
                    statusNotice.kind === 'error' ? ' team-detail-page__status--error' : ''
                  }`}
                >
                  {statusNotice.message}
                </span>
              ) : null}
            </div>
          </div>
        </section>

        <section className="user-detail-page__section">
          <div className="user-detail-page__field">
            <div className="user-detail-page__label">Translators</div>
            <MultiSelectChip
              label="Translators"
              options={translatorOptions}
              selectedValues={draftTranslatorUserIds}
              onChange={(next) => {
                setDraftTranslatorUserIds(next);
                setTranslatorStatusNotice(null);
              }}
              placeholder="Select translators"
              emptyOptionsLabel={
                translatorUsersQuery.isLoading
                  ? 'Loading translators…'
                  : showAllTranslatorUsers
                    ? 'No users available'
                    : 'No translators available'
              }
              className="user-detail-page__select team-detail-page__select"
              buttonAriaLabel="Select translators"
              customActions={[
                {
                  label: showAllTranslatorUsers ? 'Role only' : 'All team users',
                  onClick: () => {
                    setShowAllTranslatorUsers((current) => !current);
                    setTranslatorStatusNotice(null);
                  },
                  ariaLabel: showAllTranslatorUsers
                    ? 'Show role filtered users only'
                    : 'Show all team users',
                },
              ]}
            />
            <div className="user-detail-page__actions">
              <button
                type="button"
                className="settings-button settings-button--primary"
                onClick={handleSaveTranslatorRoster}
                disabled={translatorUsersQuery.isLoading || saveTranslatorRosterMutation.isPending}
              >
                Save
              </button>
              {translatorStatusNotice ? (
                <span
                  className={`user-detail-page__status${
                    translatorStatusNotice.kind === 'error'
                      ? ' team-detail-page__status--error'
                      : ''
                  }`}
                >
                  {translatorStatusNotice.message}
                </span>
              ) : null}
            </div>
          </div>
        </section>

        {isAdmin ? (
          <section className="user-detail-page__section">
            <div className="user-detail-page__field">
              <div className="user-detail-page__label">PMs</div>
              <MultiSelectChip
                label="Project managers"
                options={pmOptions}
                selectedValues={draftPmUserIds}
                onChange={(next) => {
                  setDraftPmUserIds(next);
                  setPmStatusNotice(null);
                }}
                placeholder="Select project managers"
                emptyOptionsLabel={
                  pmUsersQuery.isLoading
                    ? 'Loading project managers…'
                    : showAllPmUsers
                      ? 'No users available'
                      : 'No project managers available'
                }
                className="user-detail-page__select team-detail-page__select"
                buttonAriaLabel="Select project managers"
                customActions={[
                  {
                    label: showAllPmUsers ? 'Role only' : 'All team users',
                    onClick: () => {
                      setShowAllPmUsers((current) => !current);
                      setPmStatusNotice(null);
                    },
                    ariaLabel: showAllPmUsers
                      ? 'Show role filtered users only'
                      : 'Show all team users',
                  },
                ]}
                summaryFormatter={({ options, selectedValues }) => {
                  if (!options.length) {
                    return 'No project managers';
                  }
                  if (!selectedValues.length) {
                    return 'Select project managers';
                  }
                  if (selectedValues.length === options.length) {
                    return 'All selected';
                  }
                  if (selectedValues.length === 1) {
                    return '1 selected';
                  }
                  return `${selectedValues.length} selected`;
                }}
              />
              <div className="user-detail-page__actions">
                <button
                  type="button"
                  className="settings-button settings-button--primary"
                  onClick={handleSavePmRoster}
                  disabled={pmUsersQuery.isLoading || savePmRosterMutation.isPending}
                >
                  Save
                </button>
                {pmStatusNotice ? (
                  <span
                    className={`user-detail-page__status${
                      pmStatusNotice.kind === 'error' ? ' team-detail-page__status--error' : ''
                    }`}
                  >
                    {pmStatusNotice.message}
                  </span>
                ) : null}
              </div>
            </div>
          </section>
        ) : null}

        <div className="user-detail-page__danger">
          <button
            type="button"
            className="user-detail-page__delete"
            onClick={() => setShowDeleteConfirm(true)}
            disabled={deleteTeamMutation.isPending}
          >
            Delete team
          </button>
          {deleteTeamMutation.isError ? (
            <div className="user-detail-page__hint is-error">
              {deleteTeamMutation.error instanceof Error
                ? deleteTeamMutation.error.message
                : 'Failed to delete team.'}
            </div>
          ) : null}
        </div>
      </div>
      <ConfirmModal
        open={showDeleteConfirm}
        title="Delete team?"
        body="This will permanently remove the team and its roster/pool assignments."
        confirmLabel={deleteTeamMutation.isPending ? 'Deleting...' : 'Delete'}
        cancelLabel="Cancel"
        onCancel={() => setShowDeleteConfirm(false)}
        onConfirm={() => {
          if (effectiveTeamId == null || deleteTeamMutation.isPending) {
            return;
          }
          deleteTeamMutation.mutate(effectiveTeamId);
        }}
      />
    </div>
  );
}
