import './settings-page.css';
import './admin-team-pools-page.css';
import './admin-user-detail-page.css';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useMemo, useState } from 'react';
import { Navigate, useNavigate, useParams } from 'react-router-dom';

import {
  type ApiTeam,
  type ApiTeamSlackChannelMembersResponse,
  type ApiSlackClientIdsResponse,
  type ApiTeamSlackSettings,
  type ApiTeamSlackUserMappingRow,
  deleteTeam,
  fetchTeam,
  fetchTeamProjectManagers,
  fetchSlackClientIds,
  fetchTeamSlackChannelMembers,
  fetchTeamSlackSettings,
  fetchTeamSlackUserMappings,
  fetchTeamTranslators,
  replaceTeamProjectManagers,
  replaceTeamSlackUserMappings,
  replaceTeamTranslators,
  sendTeamSlackChannelTest,
  sendTeamSlackMentionTest,
  updateTeam,
  updateTeamSlackSettings,
} from '../../api/teams';
import { type ApiUser, fetchAllUsersAdmin } from '../../api/users';
import { ConfirmModal } from '../../components/ConfirmModal';
import { Modal } from '../../components/Modal';
import { MultiSelectChip } from '../../components/MultiSelectChip';
import { useUser } from '../../components/RequireUser';

type StatusNotice = {
  kind: 'success' | 'error';
  message: string;
};

type SlackMappingDraftRow = {
  mojitoUserId: number;
  mojitoUsername: string;
  slackUserId: string;
  slackUsername: string;
  matchSource: string;
  lastVerifiedAt: string | null;
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
  const [slackStatusNotice, setSlackStatusNotice] = useState<StatusNotice | null>(null);
  const [showDeleteConfirm, setShowDeleteConfirm] = useState(false);
  const [draftSlackEnabled, setDraftSlackEnabled] = useState(false);
  const [draftSlackClientId, setDraftSlackClientId] = useState('');
  const [draftSlackChannelId, setDraftSlackChannelId] = useState('');
  const [slackMappingsStatusNotice, setSlackMappingsStatusNotice] = useState<StatusNotice | null>(
    null,
  );
  const [draftSlackMappings, setDraftSlackMappings] = useState<SlackMappingDraftRow[]>([]);
  const [isRefreshingSlackMappings, setIsRefreshingSlackMappings] = useState(false);
  const [isAutoMatchingSlackMappings, setIsAutoMatchingSlackMappings] = useState(false);
  const [testingSlackMentionRowUserId, setTestingSlackMentionRowUserId] = useState<number | null>(
    null,
  );
  const [isSlackChannelMembersModalOpen, setIsSlackChannelMembersModalOpen] = useState(false);

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

  const slackSettingsQuery = useQuery<ApiTeamSlackSettings>({
    queryKey: ['team-slack-settings', effectiveTeamId],
    queryFn: () => fetchTeamSlackSettings(effectiveTeamId as number),
    enabled: isAdmin && effectiveTeamId != null,
    staleTime: 30_000,
  });
  const slackClientIdsQuery = useQuery<ApiSlackClientIdsResponse>({
    queryKey: ['slack-client-ids'],
    queryFn: fetchSlackClientIds,
    enabled: isAdmin,
    staleTime: 60_000,
  });

  const slackMappingsQuery = useQuery({
    queryKey: ['team-slack-user-mappings', effectiveTeamId],
    queryFn: () => fetchTeamSlackUserMappings(effectiveTeamId as number),
    enabled: isAdmin && effectiveTeamId != null,
    staleTime: 30_000,
  });
  const slackChannelMembersQuery = useQuery<ApiTeamSlackChannelMembersResponse>({
    queryKey: ['team-slack-channel-members', effectiveTeamId],
    queryFn: () => fetchTeamSlackChannelMembers(effectiveTeamId as number),
    enabled: isAdmin && effectiveTeamId != null && isSlackChannelMembersModalOpen,
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

  useEffect(() => {
    const settings = slackSettingsQuery.data;
    if (!settings) {
      return;
    }
    setDraftSlackEnabled(Boolean(settings.enabled));
    setDraftSlackClientId(settings.slackClientId ?? '');
    setDraftSlackChannelId(settings.slackChannelId ?? '');
  }, [slackSettingsQuery.data]);

  useEffect(() => {
    if (!isAdmin) {
      return;
    }
    if (!slackSettingsQuery.data) {
      return;
    }
    if (slackSettingsQuery.data.slackClientId) {
      return;
    }
    if (draftSlackClientId.trim()) {
      return;
    }
    const ids = slackClientIdsQuery.data?.entries ?? [];
    if (ids.length === 1) {
      setDraftSlackClientId(ids[0] ?? '');
    }
  }, [isAdmin, slackSettingsQuery.data, slackClientIdsQuery.data?.entries, draftSlackClientId]);

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

  const teamRosterUsers = useMemo(() => {
    const byId = new Map<number, ApiUser>();
    for (const entry of allPmUsers) {
      byId.set(entry.id, entry);
    }
    for (const entry of allTranslatorUsers) {
      byId.set(entry.id, entry);
    }
    const teamUserIds = new Set<number>([...draftPmUserIds, ...draftTranslatorUserIds]);
    return Array.from(teamUserIds)
      .map((id) => byId.get(id))
      .filter((entry): entry is ApiUser => Boolean(entry))
      .sort((left, right) =>
        getUserLabel(left).localeCompare(getUserLabel(right), undefined, { sensitivity: 'base' }),
      );
  }, [allPmUsers, allTranslatorUsers, draftPmUserIds, draftTranslatorUserIds]);

  useEffect(() => {
    if (!isAdmin) {
      return;
    }

    const apiRows = slackMappingsQuery.data?.entries ?? [];
    const existingByUserId = new Map<number, ApiTeamSlackUserMappingRow>();
    apiRows.forEach((row) => existingByUserId.set(row.mojitoUserId, row));

    const nextRows: SlackMappingDraftRow[] = teamRosterUsers.map((user) => {
      const existing = existingByUserId.get(user.id);
      return {
        mojitoUserId: user.id,
        mojitoUsername: user.username,
        slackUserId: existing?.slackUserId ?? '',
        slackUsername: existing?.slackUsername ?? '',
        matchSource: existing?.matchSource ?? '',
        lastVerifiedAt: existing?.lastVerifiedAt ?? null,
      };
    });

    setDraftSlackMappings(nextRows);
  }, [isAdmin, slackMappingsQuery.data?.entries, teamRosterUsers]);

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

  const saveSlackSettingsMutation = useMutation({
    mutationFn: ({ teamId, payload }: { teamId: number; payload: ApiTeamSlackSettings }) =>
      updateTeamSlackSettings(teamId, payload),
    onSuccess: (updated) => {
      if (effectiveTeamId != null) {
        queryClient.setQueryData(['team-slack-settings', effectiveTeamId], updated);
      }
      setDraftSlackEnabled(Boolean(updated.enabled));
      setDraftSlackClientId(updated.slackClientId ?? '');
      setDraftSlackChannelId(updated.slackChannelId ?? '');
      setSlackStatusNotice({ kind: 'success', message: 'Saved.' });
    },
    onError: (error: Error) => {
      setSlackStatusNotice({
        kind: 'error',
        message: error.message || 'Failed to save Slack settings.',
      });
    },
  });

  const testSlackChannelMutation = useMutation({
    mutationFn: (teamId: number) => sendTeamSlackChannelTest(teamId),
    onSuccess: () => {
      setSlackStatusNotice({ kind: 'success', message: 'Test message sent.' });
    },
    onError: (error: Error) => {
      setSlackStatusNotice({
        kind: 'error',
        message: error.message || 'Failed to send Slack test message.',
      });
    },
  });

  const testSlackMentionMutation = useMutation({
    mutationFn: ({
      teamId,
      slackUserId,
      mojitoUsername,
    }: {
      teamId: number;
      slackUserId: string;
      mojitoUsername: string;
    }) => sendTeamSlackMentionTest(teamId, { slackUserId, mojitoUsername }),
    onSuccess: (_data, variables) => {
      setSlackMappingsStatusNotice({
        kind: 'success',
        message: `Test message sent for ${variables.mojitoUsername}.`,
      });
    },
    onError: (error: Error) => {
      setSlackMappingsStatusNotice({
        kind: 'error',
        message: error.message || 'Failed to send Slack test message.',
      });
    },
    onSettled: () => {
      setTestingSlackMentionRowUserId(null);
    },
  });

  const saveSlackMappingsMutation = useMutation({
    mutationFn: ({ teamId, entries }: { teamId: number; entries: ApiTeamSlackUserMappingRow[] }) =>
      replaceTeamSlackUserMappings(teamId, entries),
    onSuccess: async () => {
      if (effectiveTeamId != null) {
        await queryClient.invalidateQueries({
          queryKey: ['team-slack-user-mappings', effectiveTeamId],
        });
      }
      setSlackMappingsStatusNotice({ kind: 'success', message: 'Saved.' });
    },
    onError: (error: Error) => {
      setSlackMappingsStatusNotice({
        kind: 'error',
        message: error.message || 'Failed to save Slack user mappings.',
      });
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

  const handleSaveSlackSettings = () => {
    if (!isAdmin || effectiveTeamId == null) {
      return;
    }

    saveSlackSettingsMutation.mutate({
      teamId: effectiveTeamId,
      payload: {
        enabled: draftSlackEnabled,
        slackClientId: draftSlackClientId.trim() || null,
        slackChannelId: draftSlackChannelId.trim() || null,
      },
    });
  };

  const handleSaveSlackMappings = () => {
    if (!isAdmin || effectiveTeamId == null) {
      return;
    }

    const entries: ApiTeamSlackUserMappingRow[] = draftSlackMappings
      .map((row) => ({
        mojitoUserId: row.mojitoUserId,
        mojitoUsername: row.mojitoUsername,
        slackUserId: row.slackUserId.trim(),
        slackUsername: row.slackUsername.trim() || null,
        matchSource: row.matchSource.trim() || null,
        lastVerifiedAt: row.lastVerifiedAt,
      }))
      .filter((row) => row.slackUserId.length > 0);

    saveSlackMappingsMutation.mutate({ teamId: effectiveTeamId, entries });
  };

  const handleRefreshSlackMappings = async () => {
    if (!isAdmin || effectiveTeamId == null) {
      return;
    }

    setSlackMappingsStatusNotice(null);
    setIsRefreshingSlackMappings(true);
    try {
      await Promise.all([
        pmRosterQuery.refetch(),
        translatorRosterQuery.refetch(),
        slackMappingsQuery.refetch(),
      ]);
      setSlackMappingsStatusNotice({ kind: 'success', message: 'Refreshed.' });
    } catch (error) {
      setSlackMappingsStatusNotice({
        kind: 'error',
        message: error instanceof Error ? error.message : 'Failed to refresh Slack mappings.',
      });
    } finally {
      setIsRefreshingSlackMappings(false);
    }
  };

  const handleAutoMatchSlackMappings = async () => {
    if (!isAdmin || effectiveTeamId == null) {
      return;
    }

    const normalize = (value: string | null | undefined) => value?.trim().toLowerCase() ?? '';
    const addCandidate = <T extends { slackUserId: string }>(
      map: Map<string, T[]>,
      key: string,
      entry: T,
    ) => {
      if (!key) {
        return;
      }
      const list = map.get(key);
      if (list) {
        list.push(entry);
      } else {
        map.set(key, [entry]);
      }
    };

    setSlackMappingsStatusNotice(null);
    setIsAutoMatchingSlackMappings(true);

    try {
      const channelMembers = await fetchTeamSlackChannelMembers(effectiveTeamId);

      const byEmail = new Map<string, (typeof channelMembers.entries)[number][]>();
      const byUsername = new Map<string, (typeof channelMembers.entries)[number][]>();
      const byEmailLocalPart = new Map<string, (typeof channelMembers.entries)[number][]>();

      for (const member of channelMembers.entries) {
        const email = normalize(member.email);
        const username = normalize(member.slackUsername);
        addCandidate(byEmail, email, member);
        addCandidate(byUsername, username, member);
        if (email.includes('@')) {
          addCandidate(byEmailLocalPart, email.split('@')[0] ?? '', member);
        }
      }

      const alreadyAssignedSlackIds = new Set(
        draftSlackMappings
          .map((row) => row.slackUserId.trim())
          .filter((value) => value.length > 0)
          .map((value) => value.toLowerCase()),
      );

      let matchedCount = 0;
      let skippedAmbiguousCount = 0;

      const nextDraftSlackMappings = draftSlackMappings.map((row) => {
        if (row.slackUserId.trim()) {
          return row;
        }

        const mojitoKey = normalize(row.mojitoUsername);
        if (!mojitoKey) {
          return row;
        }

        const candidateGroups: Array<{
          source: string;
          candidates: (typeof channelMembers.entries)[number][];
        }> = [];

        if (mojitoKey.includes('@')) {
          candidateGroups.push({
            source: 'auto-email',
            candidates: byEmail.get(mojitoKey) ?? [],
          });
        }

        candidateGroups.push({
          source: 'auto-email-local',
          candidates: byEmailLocalPart.get(mojitoKey) ?? [],
        });
        candidateGroups.push({
          source: 'auto-username',
          candidates: byUsername.get(mojitoKey) ?? [],
        });

        let selectedMatch: (typeof channelMembers.entries)[number] | null = null;
        let selectedSource = '';

        for (const group of candidateGroups) {
          const uniqueCandidates = group.candidates.filter(
            (candidate, index, list) =>
              list.findIndex((entry) => entry.slackUserId === candidate.slackUserId) === index,
          );

          if (uniqueCandidates.length === 1) {
            selectedMatch = uniqueCandidates[0];
            selectedSource = group.source;
            break;
          }

          if (uniqueCandidates.length > 1) {
            skippedAmbiguousCount += 1;
            return row;
          }
        }

        if (!selectedMatch) {
          return row;
        }

        const normalizedSlackUserId = normalize(selectedMatch.slackUserId);
        if (!normalizedSlackUserId || alreadyAssignedSlackIds.has(normalizedSlackUserId)) {
          return row;
        }

        alreadyAssignedSlackIds.add(normalizedSlackUserId);
        matchedCount += 1;

        return {
          ...row,
          slackUserId: selectedMatch.slackUserId,
          slackUsername: selectedMatch.slackUsername ?? row.slackUsername,
          matchSource: selectedSource,
        };
      });

      setDraftSlackMappings(nextDraftSlackMappings);

      if (matchedCount > 0) {
        setSlackMappingsStatusNotice({
          kind: 'success',
          message:
            skippedAmbiguousCount > 0
              ? `Matched ${matchedCount} row(s); skipped ${skippedAmbiguousCount} ambiguous.`
              : `Matched ${matchedCount} row(s).`,
        });
      } else {
        setSlackMappingsStatusNotice({
          kind: 'error',
          message:
            skippedAmbiguousCount > 0
              ? `No matches applied; ${skippedAmbiguousCount} row(s) were ambiguous.`
              : 'No matches found.',
        });
      }
    } catch (error) {
      setSlackMappingsStatusNotice({
        kind: 'error',
        message: error instanceof Error ? error.message : 'Failed to auto-match Slack users.',
      });
    } finally {
      setIsAutoMatchingSlackMappings(false);
    }
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

        {isAdmin ? (
          <section className="user-detail-page__section">
            <div className="user-detail-page__field">
              <div className="user-detail-page__label">Slack Notifications</div>
              <label className="settings-hint" style={{ display: 'inline-flex', gap: '0.5rem' }}>
                <input
                  type="checkbox"
                  checked={draftSlackEnabled}
                  onChange={(event) => {
                    setDraftSlackEnabled(event.target.checked);
                    setSlackStatusNotice(null);
                  }}
                  disabled={slackSettingsQuery.isLoading || saveSlackSettingsMutation.isPending}
                />
                Enable team Slack notifications
              </label>
              <input
                type="text"
                className="settings-input"
                list="team-slack-client-ids"
                value={draftSlackClientId}
                placeholder="Slack client ID"
                onChange={(event) => {
                  setDraftSlackClientId(event.target.value);
                  setSlackStatusNotice(null);
                }}
              />
              {isAdmin && (slackClientIdsQuery.data?.entries?.length ?? 0) > 0 ? (
                <datalist id="team-slack-client-ids">
                  {(slackClientIdsQuery.data?.entries ?? []).map((id) => (
                    <option key={id} value={id} />
                  ))}
                </datalist>
              ) : null}
              <input
                type="text"
                className="settings-input"
                value={draftSlackChannelId}
                placeholder="Slack channel ID (e.g. C123...)"
                onChange={(event) => {
                  setDraftSlackChannelId(event.target.value);
                  setSlackStatusNotice(null);
                }}
              />
              <div className="user-detail-page__hint">
                One channel per team (v1). User-to-Slack-ID mappings are stored per team in the
                backend.
              </div>
              <div className="user-detail-page__actions">
                <button
                  type="button"
                  className="settings-button settings-button--ghost"
                  onClick={() => {
                    setIsSlackChannelMembersModalOpen(true);
                  }}
                  disabled={
                    slackSettingsQuery.isLoading ||
                    saveSlackSettingsMutation.isPending ||
                    testSlackChannelMutation.isPending
                  }
                >
                  Browse channel users
                </button>
                <button
                  type="button"
                  className="settings-button settings-button--ghost"
                  onClick={() => {
                    if (effectiveTeamId != null) {
                      testSlackChannelMutation.mutate(effectiveTeamId);
                    }
                  }}
                  disabled={
                    slackSettingsQuery.isLoading ||
                    saveSlackSettingsMutation.isPending ||
                    testSlackChannelMutation.isPending
                  }
                >
                  {testSlackChannelMutation.isPending ? 'Testing…' : 'Test channel'}
                </button>
                <button
                  type="button"
                  className="settings-button settings-button--primary"
                  onClick={handleSaveSlackSettings}
                  disabled={
                    slackSettingsQuery.isLoading ||
                    saveSlackSettingsMutation.isPending ||
                    testSlackChannelMutation.isPending
                  }
                >
                  Save
                </button>
                {slackSettingsQuery.isLoading ? (
                  <span className="user-detail-page__status">Loading…</span>
                ) : null}
                {slackStatusNotice ? (
                  <span
                    className={`user-detail-page__status${
                      slackStatusNotice.kind === 'error' ? ' team-detail-page__status--error' : ''
                    }`}
                  >
                    {slackStatusNotice.message}
                  </span>
                ) : null}
              </div>
            </div>
          </section>
        ) : null}

        {isAdmin ? (
          <section className="user-detail-page__section">
            <div className="user-detail-page__field">
              <div className="user-detail-page__label">Slack User ID Mappings</div>
              <div className="user-detail-page__hint">
                Map Mojito team users to Slack `U...` IDs for this team channel. Blank rows are
                ignored on save.
              </div>
              <div
                className="team-detail-page__slack-mapping-table"
                role="table"
                aria-label="Slack user mappings"
              >
                <div className="team-detail-page__slack-mapping-header" role="row">
                  <div role="columnheader">Mojito user</div>
                  <div role="columnheader">Slack user ID</div>
                  <div role="columnheader">Slack username</div>
                  <div role="columnheader">Source</div>
                  <div role="columnheader">Verified</div>
                  <div role="columnheader" className="team-detail-page__slack-mapping-actions-header">
                    Actions
                  </div>
                </div>
                {draftSlackMappings.map((row, index) => (
                  <div
                    className="team-detail-page__slack-mapping-row"
                    role="row"
                    key={row.mojitoUserId}
                  >
                    <div role="cell">
                      {row.mojitoUsername}{' '}
                      <span className="team-detail-page__slack-mapping-meta">
                        #{row.mojitoUserId}
                      </span>
                    </div>
                    <div role="cell">
                      <input
                        type="text"
                        className="settings-input"
                        value={row.slackUserId}
                        placeholder="U..."
                        onChange={(event) => {
                          const value = event.target.value;
                          setDraftSlackMappings((current) =>
                            current.map((entry, currentIndex) =>
                              currentIndex === index ? { ...entry, slackUserId: value } : entry,
                            ),
                          );
                          setSlackMappingsStatusNotice(null);
                        }}
                      />
                    </div>
                    <div role="cell">
                      <input
                        type="text"
                        className="settings-input"
                        value={row.slackUsername}
                        placeholder="optional"
                        onChange={(event) => {
                          const value = event.target.value;
                          setDraftSlackMappings((current) =>
                            current.map((entry, currentIndex) =>
                              currentIndex === index ? { ...entry, slackUsername: value } : entry,
                            ),
                          );
                          setSlackMappingsStatusNotice(null);
                        }}
                      />
                    </div>
                    <div role="cell">
                      <input
                        type="text"
                        className="settings-input"
                        value={row.matchSource}
                        placeholder="manual"
                        onChange={(event) => {
                          const value = event.target.value;
                          setDraftSlackMappings((current) =>
                            current.map((entry, currentIndex) =>
                              currentIndex === index ? { ...entry, matchSource: value } : entry,
                            ),
                          );
                          setSlackMappingsStatusNotice(null);
                        }}
                      />
                    </div>
                    <div role="cell" className="team-detail-page__slack-mapping-verified">
                      {row.lastVerifiedAt ? new Date(row.lastVerifiedAt).toLocaleString() : '—'}
                    </div>
                    <div role="cell" className="team-detail-page__slack-mapping-actions">
                      {row.slackUserId.trim() ? (
                        <button
                          type="button"
                          className="settings-button settings-button--ghost team-detail-page__slack-row-action"
                          disabled={
                            effectiveTeamId == null ||
                            testSlackMentionMutation.isPending ||
                            saveSlackMappingsMutation.isPending
                          }
                          onClick={() => {
                            if (effectiveTeamId == null) {
                              return;
                            }
                            setSlackMappingsStatusNotice(null);
                            setTestingSlackMentionRowUserId(row.mojitoUserId);
                            testSlackMentionMutation.mutate({
                              teamId: effectiveTeamId,
                              slackUserId: row.slackUserId.trim(),
                              mojitoUsername: row.mojitoUsername,
                            });
                          }}
                        >
                          {testingSlackMentionRowUserId === row.mojitoUserId &&
                          testSlackMentionMutation.isPending
                            ? 'Testing…'
                            : 'Test DM'}
                        </button>
                      ) : null}
                    </div>
                  </div>
                ))}
                {!draftSlackMappings.length ? (
                  <div className="team-detail-page__slack-mapping-empty">
                    No team PM/translator users yet.
                  </div>
                ) : null}
              </div>
              <div className="user-detail-page__actions">
                <button
                  type="button"
                  className="settings-button settings-button--ghost"
                  onClick={() => {
                    void handleAutoMatchSlackMappings();
                  }}
                  disabled={
                    isAutoMatchingSlackMappings ||
                    isRefreshingSlackMappings ||
                    slackMappingsQuery.isLoading ||
                    saveSlackMappingsMutation.isPending
                  }
                >
                  Auto-match
                </button>
                <button
                  type="button"
                  className="settings-button settings-button--ghost"
                  onClick={() => {
                    void handleRefreshSlackMappings();
                  }}
                  disabled={
                    isAutoMatchingSlackMappings ||
                    isRefreshingSlackMappings ||
                    slackMappingsQuery.isLoading ||
                    saveSlackMappingsMutation.isPending
                  }
                >
                  Refresh
                </button>
                <button
                  type="button"
                  className="settings-button settings-button--primary"
                  onClick={handleSaveSlackMappings}
                  disabled={slackMappingsQuery.isLoading || saveSlackMappingsMutation.isPending}
                >
                  Save
                </button>
                {slackMappingsQuery.isLoading ? (
                  <span className="user-detail-page__status">Loading…</span>
                ) : isAutoMatchingSlackMappings ? (
                  <span className="user-detail-page__status">Matching…</span>
                ) : isRefreshingSlackMappings ? (
                  <span className="user-detail-page__status">Refreshing…</span>
                ) : null}
                {slackMappingsStatusNotice ? (
                  <span
                    className={`user-detail-page__status${
                      slackMappingsStatusNotice.kind === 'error'
                        ? ' team-detail-page__status--error'
                        : ''
                    }`}
                  >
                    {slackMappingsStatusNotice.message}
                  </span>
                ) : null}
              </div>
            </div>
          </section>
        ) : null}

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

        <div className="user-detail-page__danger team-detail-page__danger">
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
      <Modal
        open={isSlackChannelMembersModalOpen}
        size="xl"
        ariaLabel="Slack channel users"
        onClose={() => setIsSlackChannelMembersModalOpen(false)}
        closeOnBackdrop
      >
        <div className="modal__title">Slack Channel Users</div>
        <div className="modal__body">
          {slackChannelMembersQuery.data
            ? `Client: ${slackChannelMembersQuery.data.slackClientId}\nChannel: ${
                slackChannelMembersQuery.data.slackChannelName
                  ? `#${slackChannelMembersQuery.data.slackChannelName} (${slackChannelMembersQuery.data.slackChannelId})`
                  : slackChannelMembersQuery.data.slackChannelId
              }`
            : 'List users in the configured team Slack channel to copy user IDs into mappings.'}
        </div>

        <div
          className="team-detail-page__slack-channel-members-table"
          role="table"
          aria-label="Slack channel users"
        >
          <div className="team-detail-page__slack-channel-members-header" role="row">
            <div role="columnheader">Slack user ID</div>
            <div role="columnheader">Username</div>
            <div role="columnheader">Display name</div>
            <div role="columnheader">Email</div>
          </div>
          {slackChannelMembersQuery.isLoading ? (
            <div className="team-detail-page__slack-channel-members-empty">
              Loading channel users…
            </div>
          ) : slackChannelMembersQuery.isError ? (
            <div className="team-detail-page__slack-channel-members-empty team-detail-page__status--error">
              {slackChannelMembersQuery.error instanceof Error
                ? slackChannelMembersQuery.error.message
                : 'Failed to load Slack channel users.'}
            </div>
          ) : (slackChannelMembersQuery.data?.entries ?? []).length === 0 ? (
            <div className="team-detail-page__slack-channel-members-empty">
              No channel users returned.
            </div>
          ) : (
            (slackChannelMembersQuery.data?.entries ?? []).map((entry) => (
              <div
                className="team-detail-page__slack-channel-members-row"
                role="row"
                key={entry.slackUserId}
              >
                <div role="cell">
                  <code>{entry.slackUserId}</code>
                </div>
                <div role="cell">{entry.slackUsername || '—'}</div>
                <div role="cell">{entry.displayName || '—'}</div>
                <div role="cell">{entry.email || '—'}</div>
              </div>
            ))
          )}
        </div>

        <div className="modal__actions">
          <button
            type="button"
            className="modal__button"
            onClick={() => {
              void slackChannelMembersQuery.refetch();
            }}
            disabled={slackChannelMembersQuery.isFetching}
          >
            {slackChannelMembersQuery.isFetching ? 'Refreshing…' : 'Refresh'}
          </button>
          <button
            type="button"
            className="modal__button modal__button--primary"
            onClick={() => setIsSlackChannelMembersModalOpen(false)}
          >
            Close
          </button>
        </div>
      </Modal>
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
