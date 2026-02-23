import './settings-page.css';
import './admin-teams-page.css';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useMemo, useState } from 'react';
import { Link, Navigate } from 'react-router-dom';

import { type ApiTeam, createTeam, fetchTeamsWithOptions } from '../../api/teams';
import { Modal } from '../../components/Modal';
import { useUser } from '../../components/RequireUser';
import { SearchControl } from '../../components/SearchControl';

type StatusNotice = {
  kind: 'success' | 'error';
  message: string;
};

type EnabledFilter = 'enabled' | 'disabled' | 'all';

const normalizeTeamName = (value: string) => value.trim().replace(/\s+/g, ' ');

const isSameTeamName = (left: string, right: string) =>
  normalizeTeamName(left).toLowerCase() === normalizeTeamName(right).toLowerCase();

const sortTeams = (teams: ApiTeam[]) =>
  [...teams].sort((left, right) => {
    const byName = left.name.localeCompare(right.name, undefined, { sensitivity: 'base' });
    if (byName !== 0) {
      return byName;
    }
    return left.id - right.id;
  });

export function AdminTeamsPage() {
  const user = useUser();
  const queryClient = useQueryClient();
  const isAdmin = user.role === 'ROLE_ADMIN';
  const isPm = user.role === 'ROLE_PM';
  const canAccess = isAdmin || isPm;

  const [searchQuery, setSearchQuery] = useState('');
  const [enabledFilter, setEnabledFilter] = useState<EnabledFilter>('enabled');
  const [isCreateModalOpen, setIsCreateModalOpen] = useState(false);
  const [newTeamDraft, setNewTeamDraft] = useState('');
  const [createModalError, setCreateModalError] = useState<string | null>(null);
  const [statusNotice, setStatusNotice] = useState<StatusNotice | null>(null);

  const teamsQuery = useQuery<ApiTeam[]>({
    queryKey: ['teams'],
    queryFn: () => fetchTeamsWithOptions({ includeDisabled: isAdmin }),
    enabled: canAccess,
    staleTime: 30_000,
  });

  const sortedTeams = useMemo(() => sortTeams(teamsQuery.data ?? []), [teamsQuery.data]);

  const createTeamMutation = useMutation({
    mutationFn: (name: string) => createTeam(name),
    onSuccess: async (created) => {
      await queryClient.invalidateQueries({ queryKey: ['teams'] });
      setIsCreateModalOpen(false);
      setNewTeamDraft('');
      setCreateModalError(null);
      setStatusNotice({
        kind: 'success',
        message: `Created team ${created.name} (#${created.id}).`,
      });
    },
    onError: (error: Error) => {
      setCreateModalError(error.message || 'Failed to create team.');
    },
  });

  const teamRows = useMemo(
    () =>
      sortedTeams.map((team) => {
        const searchText =
          `${team.id} ${team.name} ${team.enabled ? 'enabled' : 'disabled'}`.toLowerCase();
        return {
          team,
          searchText,
        };
      }),
    [sortedTeams],
  );

  const normalizedSearch = searchQuery.trim().toLowerCase();
  const filteredTeamRows = useMemo(() => {
    return teamRows.filter((row) => {
      if (normalizedSearch && !row.searchText.includes(normalizedSearch)) {
        return false;
      }
      if (enabledFilter === 'enabled' && row.team.enabled !== true) {
        return false;
      }
      if (enabledFilter === 'disabled' && row.team.enabled !== false) {
        return false;
      }
      return true;
    });
  }, [enabledFilter, normalizedSearch, teamRows]);

  if (!canAccess) {
    return <Navigate to="/repositories" replace />;
  }

  const handleCreateTeam = () => {
    const normalized = normalizeTeamName(newTeamDraft);
    if (!normalized) {
      setCreateModalError('Team name is required.');
      return;
    }

    const duplicate = sortedTeams.some((team) => isSameTeamName(team.name, normalized));
    if (duplicate) {
      setCreateModalError(`Team ${normalized} already exists.`);
      return;
    }

    createTeamMutation.mutate(normalized);
  };

  const isLoading = teamsQuery.isLoading;
  const hasLoadError = teamsQuery.isError;

  return (
    <div className="team-admin-page">
      <section className="settings-card">
        <div className="settings-card__content">
          <div className="team-admin-page__toolbar">
            <SearchControl
              value={searchQuery}
              onChange={setSearchQuery}
              placeholder="Search teams"
              className="team-admin-page__search"
            />
            {isAdmin ? (
              <label className="team-admin-page__filter">
                <span className="team-admin-page__filter-label">Status</span>
                <select
                  className="settings-input team-admin-page__filter-select"
                  value={enabledFilter}
                  onChange={(event) => setEnabledFilter(event.target.value as EnabledFilter)}
                >
                  <option value="enabled">Enabled</option>
                  <option value="disabled">Disabled</option>
                  <option value="all">All</option>
                </select>
              </label>
            ) : null}
            {isAdmin ? (
              <button
                type="button"
                className="settings-button settings-button--primary team-admin-page__create"
                onClick={() => {
                  setIsCreateModalOpen(true);
                  setCreateModalError(null);
                  setStatusNotice(null);
                }}
              >
                Create team
              </button>
            ) : null}
          </div>

          <div className="team-admin-page__count">
            <span className="team-admin-page__count-text">{filteredTeamRows.length} teams</span>
            {statusNotice ? (
              <span
                className={`settings-hint team-admin-page__status${
                  statusNotice.kind === 'error' ? ' is-error' : ''
                }`}
              >
                {statusNotice.message}
              </span>
            ) : null}
          </div>

          {hasLoadError ? (
            <p className="team-admin-page__empty">Could not load teams from backend.</p>
          ) : isLoading ? (
            <p className="team-admin-page__empty">Loading teams…</p>
          ) : teamRows.length === 0 ? (
            <p className="team-admin-page__empty">No teams yet.</p>
          ) : filteredTeamRows.length === 0 ? (
            <p className="team-admin-page__empty">No teams match the current search.</p>
          ) : (
            <div className="team-admin-page__table">
              <div className="team-admin-page__table-header">
                <div className="team-admin-page__cell">ID</div>
                <div className="team-admin-page__cell">Team</div>
                <div className="team-admin-page__cell">Status</div>
                <div className="team-admin-page__cell team-admin-page__cell--actions">Actions</div>
              </div>
              {filteredTeamRows.map(({ team }) => {
                const poolUrl = isAdmin
                  ? `/settings/admin/team-pools?teamId=${team.id}`
                  : `/settings/team?teamId=${team.id}`;
                return (
                  <div key={team.id} className="team-admin-page__row">
                    <div className="team-admin-page__cell team-admin-page__cell--muted">
                      {team.id}
                    </div>
                    <div className="team-admin-page__cell">
                      <div className="team-admin-page__name-cell">
                        <span className="team-admin-page__name-text">{team.name}</span>
                      </div>
                    </div>
                    <div className="team-admin-page__cell team-admin-page__cell--muted">
                      {team.enabled ? 'Enabled' : 'Disabled'}
                    </div>
                    <div className="team-admin-page__cell team-admin-page__cell--actions">
                      <div className="team-admin-page__actions">
                        {isAdmin && team.enabled ? (
                          <Link
                            className="team-admin-page__row-action-link"
                            to={`/settings/admin/teams/${team.id}`}
                          >
                            Edit team
                          </Link>
                        ) : null}
                        {team.enabled ? (
                          <Link className="team-admin-page__row-action-link" to={poolUrl}>
                            Edit pools
                          </Link>
                        ) : (
                          <span className="team-admin-page__row-action-muted">Disabled</span>
                        )}
                      </div>
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </div>
      </section>

      {isAdmin ? (
        <Modal
          open={isCreateModalOpen}
          size="sm"
          ariaLabel="Create team"
          onClose={() => setIsCreateModalOpen(false)}
          closeOnBackdrop
        >
          <div className="team-admin-page__create-modal">
            <div className="modal__title">Create team</div>
            <div className="settings-field">
              <label className="settings-field__label" htmlFor="create-team-name">
                Name
              </label>
              <input
                id="create-team-name"
                type="text"
                className="settings-input"
                value={newTeamDraft}
                onChange={(event) => {
                  setNewTeamDraft(event.target.value);
                  setCreateModalError(null);
                }}
                placeholder="Team name"
                onKeyDown={(event) => {
                  if (event.key !== 'Enter') {
                    return;
                  }
                  event.preventDefault();
                  handleCreateTeam();
                }}
              />
              {createModalError ? (
                <p className="settings-hint is-error">{createModalError}</p>
              ) : null}
            </div>
          </div>
          <div className="modal__actions">
            <button
              type="button"
              className="modal__button"
              onClick={() => {
                setIsCreateModalOpen(false);
                setCreateModalError(null);
              }}
            >
              Cancel
            </button>
            <button
              type="button"
              className="modal__button modal__button--primary"
              onClick={handleCreateTeam}
              disabled={createTeamMutation.isPending}
            >
              Create
            </button>
          </div>
        </Modal>
      ) : null}
    </div>
  );
}
