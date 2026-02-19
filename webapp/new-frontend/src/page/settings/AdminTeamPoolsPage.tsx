import './settings-page.css';
import './admin-team-pools-page.css';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useMemo, useState } from 'react';
import { Link, Navigate, useSearchParams } from 'react-router-dom';

import {
  type ApiTeam,
  type ApiTeamLocalePoolRow,
  fetchTeam,
  fetchTeamLocalePools,
  fetchTeamPmPool,
  fetchTeamTranslators,
  replaceTeamLocalePools,
  replaceTeamPmPool,
} from '../../api/teams';
import type { ApiUser } from '../../api/users';
import { LocaleMultiSelect } from '../../components/LocaleMultiSelect';
import { MultiSelectChip } from '../../components/MultiSelectChip';
import { useUser } from '../../components/RequireUser';
import { useLocales } from '../../hooks/useLocales';
import { useRepositories } from '../../hooks/useRepositories';
import { useUsers } from '../../hooks/useUsers';
import { useLocaleDisplayNameResolver } from '../../utils/localeDisplayNames';
import { buildLocaleOptionsFromRepositories } from '../../utils/localeSelection';

type StatusNotice = {
  kind: 'success' | 'error';
  message: string;
};

type TeamPoolEntry = {
  localeTag: string;
  translatorUserIds: number[];
  updatedAt?: string;
};

type ParsedTeamPoolCsvRow = {
  lineNumber: number;
  localeTag: string;
  translatorUserIds: number[];
  errors: string[];
};

type TeamPoolEditorMode = 'row' | 'batch';
type BatchApplyMode = 'merge' | 'replace';

const CSV_BATCH_HEADERS = ['locale_tag', 'translator_usernames'] as const;
const CSV_PLACEHOLDER = `locale_tag,translator_usernames
fr-FR,jane|max
de-DE,alex|kim`;

const getUserRole = (user: ApiUser) => user.authorities?.[0]?.authority ?? 'ROLE_USER';

const getUserLabel = (user: ApiUser) => {
  const fullName =
    user.commonName || [user.givenName, user.surname].filter((part) => Boolean(part)).join(' ');
  if (fullName) {
    return `${fullName} (${user.username})`;
  }
  return user.username;
};

const normalizeLocaleTag = (value: string | null | undefined): string | null => {
  if (!value) {
    return null;
  }
  const normalized = value.trim().toLowerCase();
  return normalized.length > 0 ? normalized : null;
};

const getUserLocaleTagSet = (user: ApiUser): Set<string> => {
  const entries = Array.isArray(user.userLocales) ? (user.userLocales as unknown[]) : [];
  const tags = entries
    .map((entry) => {
      if (typeof entry === 'string') {
        return normalizeLocaleTag(entry);
      }
      if (!entry || typeof entry !== 'object') {
        return null;
      }
      const localeEntry = entry as {
        locale?: { bcp47Tag?: string | null } | null;
        bcp47Tag?: string | null;
        tag?: string | null;
      };
      return normalizeLocaleTag(
        localeEntry.locale?.bcp47Tag ?? localeEntry.bcp47Tag ?? localeEntry.tag,
      );
    })
    .filter((tag): tag is string => Boolean(tag));

  return new Set(tags);
};

const makePoolKey = (localeTag: string) => localeTag.toLowerCase();

const formatUpdatedAt = (value?: string) => {
  if (!value) {
    return '—';
  }
  const parsed = Date.parse(value);
  if (Number.isNaN(parsed)) {
    return value;
  }
  return new Date(parsed).toLocaleString();
};

const escapeCsvValue = (value: string | number) => {
  const text = String(value ?? '');
  if (/[",\n]/.test(text)) {
    return `"${text.replace(/"/g, '""')}"`;
  }
  return text;
};

const parseCsvRow = (line: string): string[] => {
  const values: string[] = [];
  let current = '';
  let inQuotes = false;

  for (let index = 0; index < line.length; index += 1) {
    const char = line[index];
    if (char === '"') {
      if (inQuotes && line[index + 1] === '"') {
        current += '"';
        index += 1;
      } else {
        inQuotes = !inQuotes;
      }
      continue;
    }
    if (char === ',' && !inQuotes) {
      values.push(current);
      current = '';
      continue;
    }
    current += char;
  }

  values.push(current);
  return values.map((value) => value.trim());
};

const parseCsv = (text: string): string[][] => {
  return text
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => line.length > 0)
    .map(parseCsvRow);
};

export function AdminTeamPoolsPage() {
  const user = useUser();
  const queryClient = useQueryClient();
  const isAdmin = user.role === 'ROLE_ADMIN';
  const isPm = user.role === 'ROLE_PM';
  const canAccess = isAdmin || isPm;
  const resolveLocaleName = useLocaleDisplayNameResolver();
  const [searchParams] = useSearchParams();

  const { data: repositories } = useRepositories();
  const { data: locales } = useLocales();
  const { data: users } = useUsers();

  const [useRepositoryLocalesOnly, setUseRepositoryLocalesOnly] = useState(true);
  const [selectedLocaleTags, setSelectedLocaleTags] = useState<string[]>([]);
  const [selectedTranslatorIds, setSelectedTranslatorIds] = useState<number[]>([]);
  const [selectedPmIds, setSelectedPmIds] = useState<number[]>([]);
  const [showAllTeamUsers, setShowAllTeamUsers] = useState(false);
  const [editorMode, setEditorMode] = useState<TeamPoolEditorMode>('row');
  const [batchApplyMode, setBatchApplyMode] = useState<BatchApplyMode>('merge');
  const [csvInput, setCsvInput] = useState('');
  const [statusNotice, setStatusNotice] = useState<StatusNotice | null>(null);
  const [pmStatusNotice, setPmStatusNotice] = useState<StatusNotice | null>(null);

  const requestedTeamId = useMemo(() => {
    const raw = searchParams.get('teamId');
    if (!raw) {
      return null;
    }
    const parsed = Number(raw);
    return Number.isInteger(parsed) && parsed > 0 ? parsed : null;
  }, [searchParams]);

  const teamByIdQuery = useQuery<ApiTeam>({
    queryKey: ['team', requestedTeamId],
    queryFn: () => fetchTeam(requestedTeamId as number),
    enabled: canAccess && requestedTeamId != null,
    staleTime: 30_000,
  });

  const selectedTeamRecord = useMemo(() => {
    if (!canAccess) {
      return null;
    }

    if (requestedTeamId != null) {
      return teamByIdQuery.data ?? null;
    }

    return null;
  }, [canAccess, requestedTeamId, teamByIdQuery.data]);

  const selectedTeamId = selectedTeamRecord?.id ?? null;
  const selectedTeamName = selectedTeamRecord?.name ?? null;

  useEffect(() => {
    setSelectedLocaleTags([]);
    setSelectedTranslatorIds([]);
    setSelectedPmIds([]);
    setShowAllTeamUsers(false);
    setEditorMode('row');
    setBatchApplyMode('merge');
    setCsvInput('');
    setStatusNotice(null);
    setPmStatusNotice(null);
  }, [selectedTeamId]);

  const localePoolsQuery = useQuery({
    queryKey: ['team-locale-pools', selectedTeamId],
    queryFn: () => fetchTeamLocalePools(selectedTeamId as number),
    enabled: selectedTeamId != null,
    staleTime: 30_000,
  });

  const teamTranslatorsQuery = useQuery({
    queryKey: ['team-translators', selectedTeamId],
    queryFn: () => fetchTeamTranslators(selectedTeamId as number),
    enabled: selectedTeamId != null,
    staleTime: 30_000,
  });

  const teamPmPoolQuery = useQuery({
    queryKey: ['team-pm-pool', selectedTeamId],
    queryFn: () => fetchTeamPmPool(selectedTeamId as number),
    enabled: selectedTeamId != null,
    staleTime: 30_000,
  });

  const activeTeamEntries = useMemo<TeamPoolEntry[]>(() => {
    return [...(localePoolsQuery.data?.entries ?? [])]
      .map((entry) => ({
        localeTag: entry.localeTag,
        translatorUserIds: entry.translatorUserIds,
      }))
      .sort((left, right) =>
        left.localeTag.localeCompare(right.localeTag, undefined, { sensitivity: 'base' }),
      );
  }, [localePoolsQuery.data?.entries]);

  const repositoryLocaleOptions = useMemo(
    () => buildLocaleOptionsFromRepositories(repositories ?? [], resolveLocaleName),
    [repositories, resolveLocaleName],
  );

  const allLocaleOptions = useMemo(() => {
    const byTag = new Map<string, string>();
    repositoryLocaleOptions.forEach((option) => {
      byTag.set(option.tag.toLowerCase(), option.tag);
    });
    (locales ?? []).forEach((locale) => {
      const tag = locale.bcp47Tag?.trim();
      if (!tag) {
        return;
      }
      const lowerTag = tag.toLowerCase();
      if (!byTag.has(lowerTag)) {
        byTag.set(lowerTag, tag);
      }
    });
    return Array.from(byTag.values())
      .sort((left, right) => left.localeCompare(right, undefined, { sensitivity: 'base' }))
      .map((tag) => ({ tag, label: resolveLocaleName(tag) }));
  }, [locales, repositoryLocaleOptions, resolveLocaleName]);

  const activeLocaleOptions = useMemo(() => {
    if (useRepositoryLocalesOnly && repositoryLocaleOptions.length > 0) {
      return repositoryLocaleOptions;
    }
    return allLocaleOptions;
  }, [allLocaleOptions, repositoryLocaleOptions, useRepositoryLocalesOnly]);

  useEffect(() => {
    const availableTags = new Set(activeLocaleOptions.map((option) => option.tag.toLowerCase()));
    setSelectedLocaleTags((current) =>
      current.filter((tag) => availableTags.has(tag.toLowerCase())),
    );
  }, [activeLocaleOptions]);

  const teamTranslatorUsers = useMemo(() => {
    const teamTranslatorIdSet = new Set(teamTranslatorsQuery.data?.userIds ?? []);
    return (users ?? [])
      .filter((entry) => {
        if (entry.enabled === false) {
          return false;
        }
        return teamTranslatorIdSet.has(entry.id);
      })
      .sort((left, right) =>
        getUserLabel(left).localeCompare(getUserLabel(right), undefined, { sensitivity: 'base' }),
      );
  }, [users, teamTranslatorsQuery.data?.userIds]);

  const teamUsers = useMemo(() => {
    if (selectedTeamId == null) {
      return [] as ApiUser[];
    }
    return (users ?? [])
      .filter((entry) => {
        if (entry.enabled === false) {
          return false;
        }
        return (entry.teamIds ?? []).includes(selectedTeamId);
      })
      .sort((left, right) =>
        getUserLabel(left).localeCompare(getUserLabel(right), undefined, { sensitivity: 'base' }),
      );
  }, [selectedTeamId, users]);

  const teamTranslatorUserById = useMemo(
    () => new Map(teamTranslatorUsers.map((entry) => [entry.id, entry])),
    [teamTranslatorUsers],
  );

  const teamUserById = useMemo(
    () => new Map(teamUsers.map((entry) => [entry.id, entry])),
    [teamUsers],
  );

  const translatorUsers = useMemo(() => {
    const baseUsers = showAllTeamUsers
      ? teamTranslatorUsers
      : teamTranslatorUsers.filter((entry) => getUserRole(entry) === 'ROLE_TRANSLATOR');
    const baseUserIds = new Set(baseUsers.map((entry) => entry.id));
    const selectedExtras = selectedTranslatorIds
      .map((id) => teamTranslatorUserById.get(id))
      .filter((entry): entry is ApiUser => Boolean(entry))
      .filter((entry) => !baseUserIds.has(entry.id));
    return [...baseUsers, ...selectedExtras];
  }, [selectedTranslatorIds, showAllTeamUsers, teamTranslatorUserById, teamTranslatorUsers]);

  const teamPmRosterUsers = useMemo(
    () => teamUsers.filter((entry) => getUserRole(entry) === 'ROLE_PM'),
    [teamUsers],
  );

  const pmUsersForSelection = useMemo(() => {
    const baseUserIds = new Set(teamPmRosterUsers.map((entry) => entry.id));
    const selectedExtras = selectedPmIds
      .map((id) => teamUserById.get(id))
      .filter((entry): entry is ApiUser => Boolean(entry))
      .filter((entry) => !baseUserIds.has(entry.id));
    return [...teamPmRosterUsers, ...selectedExtras]
      .sort((left, right) =>
        getUserLabel(left).localeCompare(getUserLabel(right), undefined, { sensitivity: 'base' }),
      )
      .map((entry) => ({
        value: entry.id,
        label: getUserLabel(entry),
      }));
  }, [selectedPmIds, teamPmRosterUsers, teamUserById]);

  const teamTranslatorLocaleTagSetById = useMemo(
    () => new Map(teamTranslatorUsers.map((entry) => [entry.id, getUserLocaleTagSet(entry)])),
    [teamTranslatorUsers],
  );

  const selectedLocaleTagSet = useMemo(
    () => new Set(selectedLocaleTags.map((tag) => tag.toLowerCase())),
    [selectedLocaleTags],
  );

  const translatorOptionsForSelection = useMemo(() => {
    const candidates =
      selectedLocaleTagSet.size === 0
        ? translatorUsers
        : translatorUsers.filter((translator) => {
            if (translator.canTranslateAllLocales === true) {
              return true;
            }
            const allowedLocaleTags =
              teamTranslatorLocaleTagSetById.get(translator.id) ?? new Set<string>();
            return Array.from(selectedLocaleTagSet).every((tag) => allowedLocaleTags.has(tag));
          });
    return candidates.map((translator) => ({
      value: translator.id,
      label: getUserLabel(translator),
    }));
  }, [selectedLocaleTagSet, teamTranslatorLocaleTagSetById, translatorUsers]);

  useEffect(() => {
    const teamTranslatorIds = new Set(teamTranslatorUsers.map((entry) => entry.id));
    setSelectedTranslatorIds((current) => current.filter((id) => teamTranslatorIds.has(id)));
  }, [teamTranslatorUsers]);

  useEffect(() => {
    setSelectedTranslatorIds((current) =>
      current.filter((id) => {
        const translator = teamTranslatorUserById.get(id);
        if (!translator) {
          return false;
        }
        if (selectedLocaleTagSet.size === 0 || translator.canTranslateAllLocales === true) {
          return true;
        }
        const allowedLocaleTags = teamTranslatorLocaleTagSetById.get(id) ?? new Set<string>();
        return Array.from(selectedLocaleTagSet).every((tag) => allowedLocaleTags.has(tag));
      }),
    );
  }, [selectedLocaleTagSet, teamTranslatorLocaleTagSetById, teamTranslatorUserById]);

  useEffect(() => {
    const fromApi = teamPmPoolQuery.data?.userIds ?? [];
    const normalized = Array.from(new Set(fromApi)).sort((left, right) => left - right);
    setSelectedPmIds(normalized);
  }, [teamPmPoolQuery.data?.userIds]);

  useEffect(() => {
    const teamPmIds = new Set(teamPmRosterUsers.map((entry) => entry.id));
    setSelectedPmIds((current) => current.filter((id) => teamPmIds.has(id)));
  }, [teamPmRosterUsers]);

  const translatorNameById = useMemo(
    () => new Map(teamTranslatorUsers.map((entry) => [entry.id, getUserLabel(entry)])),
    [teamTranslatorUsers],
  );

  const pmNameById = useMemo(
    () => new Map(teamPmRosterUsers.map((entry) => [entry.id, getUserLabel(entry)])),
    [teamPmRosterUsers],
  );

  const translatorUsernameById = useMemo(
    () => new Map(teamTranslatorUsers.map((entry) => [entry.id, entry.username])),
    [teamTranslatorUsers],
  );

  const translatorIdByUsername = useMemo(
    () => new Map(teamTranslatorUsers.map((entry) => [entry.username.toLowerCase(), entry.id])),
    [teamTranslatorUsers],
  );

  const csvParseResult = useMemo(() => {
    const rows = parseCsv(csvInput);
    if (rows.length === 0) {
      return { headerError: null as string | null, rows: [] as ParsedTeamPoolCsvRow[] };
    }

    if (selectedTeamId == null) {
      return {
        headerError: 'Select a team before parsing batch rows.',
        rows: [] as ParsedTeamPoolCsvRow[],
      };
    }

    const header = rows[0].map((value) => value.toLowerCase());
    const indexByHeader = new Map(header.map((value, index) => [value, index]));
    const localeTagIndex = indexByHeader.get('locale_tag');
    const translatorUsernamesIndex = indexByHeader.get('translator_usernames');

    if (localeTagIndex == null) {
      return {
        headerError: 'Input must include locale_tag column.',
        rows: [] as ParsedTeamPoolCsvRow[],
      };
    }

    if (translatorUsernamesIndex == null) {
      return {
        headerError: 'Input must include translator_usernames.',
        rows: [] as ParsedTeamPoolCsvRow[],
      };
    }

    const availableTranslatorIds = new Set(teamTranslatorUsers.map((entry) => entry.id));

    const parsedRows = rows.slice(1).map((row, index) => {
      const lineNumber = index + 2;
      const localeTag = (row[localeTagIndex] ?? '').trim();
      const errors: string[] = [];

      if (!localeTag) {
        errors.push('Missing locale_tag');
      }

      const usernamesFromCsv =
        translatorUsernamesIndex != null
          ? (row[translatorUsernamesIndex] ?? '')
              .split(/[|;]+/)
              .map((value) => value.trim())
              .filter((value) => value.length > 0)
          : [];

      const unknownUsernames: string[] = [];
      const idsFromUsernames: number[] = [];
      usernamesFromCsv.forEach((username) => {
        const mappedId = translatorIdByUsername.get(username.toLowerCase());
        if (mappedId == null) {
          unknownUsernames.push(username);
          return;
        }
        idsFromUsernames.push(mappedId);
      });
      if (unknownUsernames.length > 0) {
        errors.push(`Unknown translator usernames: ${unknownUsernames.join('|')}`);
      }

      const translatorUserIds = Array.from(
        new Set(idsFromUsernames.filter((id) => availableTranslatorIds.has(id))),
      ).sort((left, right) => left - right);

      if (translatorUserIds.length === 0) {
        errors.push('No valid translators');
      }

      if (localeTag) {
        const localeTagLower = localeTag.toLowerCase();
        const disallowedTranslators = translatorUserIds.filter((id) => {
          const translator = teamTranslatorUserById.get(id);
          if (!translator) {
            return false;
          }
          if (translator.canTranslateAllLocales === true) {
            return false;
          }
          return !teamTranslatorLocaleTagSetById.get(id)?.has(localeTagLower);
        });
        if (disallowedTranslators.length > 0) {
          const disallowedLabels = disallowedTranslators
            .map((id) => teamTranslatorUserById.get(id)?.username ?? `#${id}`)
            .join('|');
          errors.push(`Locale not allowed for: ${disallowedLabels}`);
        }
      }

      return {
        lineNumber,
        localeTag,
        translatorUserIds,
        errors,
      };
    });

    return {
      headerError: null as string | null,
      rows: parsedRows,
    };
  }, [
    csvInput,
    translatorIdByUsername,
    teamTranslatorLocaleTagSetById,
    teamTranslatorUserById,
    selectedTeamId,
    teamTranslatorUsers,
  ]);

  const csvErrorRows = useMemo(
    () => csvParseResult.rows.filter((row) => row.errors.length > 0),
    [csvParseResult.rows],
  );

  const csvValidRows = useMemo(
    () => csvParseResult.rows.filter((row) => row.errors.length === 0),
    [csvParseResult.rows],
  );

  const replaceLocalePoolsMutation = useMutation({
    mutationFn: ({ teamId, entries }: { teamId: number; entries: ApiTeamLocalePoolRow[] }) =>
      replaceTeamLocalePools(teamId, entries),
  });

  const replaceTeamPmPoolMutation = useMutation({
    mutationFn: ({ teamId, userIds }: { teamId: number; userIds: number[] }) =>
      replaceTeamPmPool(teamId, userIds),
  });

  if (!canAccess) {
    return <Navigate to="/repositories" replace />;
  }

  const isTeamLoading = requestedTeamId != null ? teamByIdQuery.isLoading : false;

  const canWriteAssignments =
    selectedTeamId != null &&
    !replaceLocalePoolsMutation.isPending &&
    !localePoolsQuery.isLoading &&
    !localePoolsQuery.isError;

  const canWritePmPool =
    selectedTeamId != null &&
    !replaceTeamPmPoolMutation.isPending &&
    !teamPmPoolQuery.isLoading &&
    !teamPmPoolQuery.isError;

  const persistEntries = async (
    nextEntries: TeamPoolEntry[],
    successMessage: string,
    fallbackErrorMessage: string,
  ) => {
    if (selectedTeamId == null) {
      return;
    }

    const payload = nextEntries
      .map((entry) => ({
        localeTag: entry.localeTag,
        translatorUserIds: Array.from(new Set(entry.translatorUserIds)).sort(
          (left, right) => left - right,
        ),
      }))
      .sort((left, right) =>
        left.localeTag.localeCompare(right.localeTag, undefined, { sensitivity: 'base' }),
      );

    try {
      await replaceLocalePoolsMutation.mutateAsync({
        teamId: selectedTeamId,
        entries: payload,
      });
      await queryClient.invalidateQueries({ queryKey: ['team-locale-pools', selectedTeamId] });
      await queryClient.invalidateQueries({ queryKey: ['teams'] });
      setStatusNotice({ kind: 'success', message: successMessage });
    } catch (error) {
      const message = error instanceof Error ? error.message : fallbackErrorMessage;
      setStatusNotice({ kind: 'error', message: message || fallbackErrorMessage });
    }
  };

  const handleSavePoolSelection = () => {
    if (selectedTeamId == null) {
      setStatusNotice({ kind: 'error', message: 'Select a team.' });
      return;
    }

    if (selectedLocaleTags.length === 0) {
      setStatusNotice({ kind: 'error', message: 'Select at least one locale.' });
      return;
    }

    if (selectedTranslatorIds.length === 0) {
      setStatusNotice({ kind: 'error', message: 'Select at least one translator.' });
      return;
    }

    const normalizedTranslatorIds = Array.from(new Set(selectedTranslatorIds)).sort(
      (a, b) => a - b,
    );
    const nextByKey =
      batchApplyMode === 'merge'
        ? new Map(activeTeamEntries.map((entry) => [makePoolKey(entry.localeTag), entry]))
        : new Map<string, TeamPoolEntry>();

    selectedLocaleTags.forEach((localeTag) => {
      nextByKey.set(makePoolKey(localeTag), {
        localeTag,
        translatorUserIds: normalizedTranslatorIds,
      });
    });

    const savedCount = selectedLocaleTags.length;
    void persistEntries(
      Array.from(nextByKey.values()),
      savedCount === 1 ? 'Saved.' : `Saved ${savedCount} assignments.`,
      'Failed to save assignments.',
    );
  };

  const handleDeletePoolEntry = (entry: TeamPoolEntry) => {
    const nextEntries = activeTeamEntries.filter(
      (candidate) => makePoolKey(candidate.localeTag) !== makePoolKey(entry.localeTag),
    );

    void persistEntries(nextEntries, `Removed ${entry.localeTag}.`, 'Failed to remove assignment.');
  };

  const handleSavePmPool = async () => {
    if (selectedTeamId == null) {
      setPmStatusNotice({ kind: 'error', message: 'Select a team.' });
      return;
    }

    const normalizedIds = Array.from(new Set(selectedPmIds)).sort((left, right) => left - right);

    try {
      await replaceTeamPmPoolMutation.mutateAsync({
        teamId: selectedTeamId,
        userIds: normalizedIds,
      });
      await queryClient.invalidateQueries({ queryKey: ['team-pm-pool', selectedTeamId] });
      await queryClient.invalidateQueries({ queryKey: ['teams'] });
      setPmStatusNotice({ kind: 'success', message: 'Saved.' });
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Failed to save PM pool.';
      setPmStatusNotice({ kind: 'error', message });
    }
  };

  const handleEditPoolEntry = (entry: TeamPoolEntry) => {
    setSelectedLocaleTags([entry.localeTag]);
    setSelectedTranslatorIds(entry.translatorUserIds);
    setEditorMode('row');
    setStatusNotice(null);
  };

  const handleApplyParsedCsv = () => {
    if (selectedTeamId == null) {
      setStatusNotice({ kind: 'error', message: 'Select a team.' });
      return;
    }

    if (!csvInput.trim()) {
      setStatusNotice({ kind: 'error', message: 'Paste batch rows first.' });
      return;
    }

    if (csvParseResult.headerError) {
      setStatusNotice({ kind: 'error', message: csvParseResult.headerError });
      return;
    }

    if (csvErrorRows.length > 0) {
      setStatusNotice({ kind: 'error', message: 'Fix batch input errors before applying.' });
      return;
    }

    if (csvValidRows.length === 0) {
      setStatusNotice({ kind: 'error', message: 'No valid rows found to apply.' });
      return;
    }

    const nextByKey =
      batchApplyMode === 'merge'
        ? new Map(activeTeamEntries.map((entry) => [makePoolKey(entry.localeTag), entry]))
        : new Map<string, TeamPoolEntry>();

    csvValidRows.forEach((row) => {
      const key = makePoolKey(row.localeTag);
      nextByKey.set(key, {
        localeTag: row.localeTag,
        translatorUserIds: row.translatorUserIds,
      });
    });

    void persistEntries(
      Array.from(nextByKey.values()),
      batchApplyMode === 'replace'
        ? `Replaced with ${csvValidRows.length} assignment${csvValidRows.length === 1 ? '' : 's'}.`
        : `Applied ${csvValidRows.length} assignment${csvValidRows.length === 1 ? '' : 's'}.`,
      'Failed to apply batch updates.',
    );
  };

  const handlePrefillCsvFromActiveTeam = () => {
    if (selectedTeamId == null) {
      setStatusNotice({ kind: 'error', message: 'Select a team.' });
      return;
    }

    const teamEntries = [...activeTeamEntries].sort((left, right) =>
      left.localeTag.localeCompare(right.localeTag, undefined, { sensitivity: 'base' }),
    );

    const rows = teamEntries.map((entry) => {
      const usernames = entry.translatorUserIds
        .map((id) => translatorUsernameById.get(id))
        .filter((value): value is string => Boolean(value))
        .join('|');
      return [entry.localeTag, usernames];
    });

    const csvText = [
      CSV_BATCH_HEADERS.join(','),
      ...rows.map((row) => row.map(escapeCsvValue).join(',')),
    ].join('\n');

    setCsvInput(csvText);
    setStatusNotice({
      kind: 'success',
      message: `Prefilled ${teamEntries.length} row${teamEntries.length === 1 ? '' : 's'}.`,
    });
  };

  const csvHasContent = csvInput.trim().length > 0;
  const canApplyParsedCsv =
    selectedTeamId != null &&
    csvHasContent &&
    !csvParseResult.headerError &&
    csvValidRows.length > 0 &&
    csvErrorRows.length === 0 &&
    canWriteAssignments;

  return (
    <div className="team-pools-page">
      {isAdmin ? (
        <div className="team-pools-page__topbar">
          <Link
            to="/settings/admin/teams"
            className="team-pools-page__topbar-back"
            aria-label="Back to teams"
          >
            <svg
              className="team-pools-page__topbar-back-icon"
              viewBox="0 0 24 24"
              aria-hidden="true"
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
          </Link>
          <h1 className="team-pools-page__topbar-title">
            {selectedTeamName ? `${selectedTeamName} · Team pools` : 'Team pools'}
          </h1>
        </div>
      ) : null}

      <div className="team-pools-page__content">
        <section className="settings-card" aria-label="Team pools editor">
          <div className="settings-card__header team-pools-page__card-header">
            <h2>Translator Pool</h2>
            <div className="team-pools-page__mode-toggle" role="group" aria-label="Edit mode">
              <button
                type="button"
                className={`team-pools-page__mode-option${editorMode === 'row' ? ' is-active' : ''}`}
                onClick={() => {
                  setEditorMode('row');
                  setStatusNotice(null);
                }}
              >
                Form
              </button>
              <button
                type="button"
                className={`team-pools-page__mode-option${editorMode === 'batch' ? ' is-active' : ''}`}
                onClick={() => {
                  setEditorMode('batch');
                  setStatusNotice(null);
                }}
              >
                Batch
              </button>
            </div>
          </div>

          <div className="settings-card__content team-pools-page__editor">
            {requestedTeamId == null ? (
              <p className="settings-hint is-error">
                Select a team from{' '}
                <Link to={isAdmin ? '/settings/admin/teams' : '/settings/teams'}>Teams</Link>.
              </p>
            ) : null}
            {requestedTeamId != null && !selectedTeamName && !isTeamLoading ? (
              <p className="settings-hint is-error">Team from URL was not found.</p>
            ) : null}
            {selectedTeamId != null && localePoolsQuery.isLoading ? (
              <p className="settings-hint">Loading assignments…</p>
            ) : null}
            {selectedTeamId != null && localePoolsQuery.isError ? (
              <p className="settings-hint is-error">Could not load assignments.</p>
            ) : null}
            {selectedTeamId != null && teamTranslatorsQuery.isLoading ? (
              <p className="settings-hint">Loading team translators…</p>
            ) : null}
            {selectedTeamId != null && teamTranslatorsQuery.isError ? (
              <p className="settings-hint is-error">Could not load team translators.</p>
            ) : null}

            {editorMode === 'row' ? (
              <div className="team-pools-page__selectors">
                <div className="settings-field">
                  <div className="settings-field__header">
                    <div className="settings-field__label">Locales</div>
                  </div>
                  <LocaleMultiSelect
                    label="Locales"
                    options={activeLocaleOptions}
                    selectedTags={selectedLocaleTags}
                    onChange={(next) => {
                      setSelectedLocaleTags(next);
                      setStatusNotice(null);
                    }}
                    className="settings-locale-select"
                    buttonAriaLabel="Select assignment locales"
                    customActions={[
                      {
                        label: useRepositoryLocalesOnly ? 'All locales' : 'Repo locales',
                        onClick: () => {
                          setUseRepositoryLocalesOnly((current) => !current);
                          setStatusNotice(null);
                        },
                        disabled: !useRepositoryLocalesOnly && repositoryLocaleOptions.length === 0,
                        ariaLabel: useRepositoryLocalesOnly
                          ? 'Show all locales'
                          : 'Use repository locales only',
                      },
                    ]}
                  />
                </div>

                <div className="settings-field">
                  <div className="settings-field__header">
                    <div className="settings-field__label">Translators</div>
                  </div>
                  <MultiSelectChip
                    label="Translators"
                    options={translatorOptionsForSelection}
                    selectedValues={selectedTranslatorIds}
                    onChange={(next) => {
                      setSelectedTranslatorIds(next);
                      setStatusNotice(null);
                    }}
                    placeholder="Select translators"
                    emptyOptionsLabel={
                      teamTranslatorsQuery.isLoading
                        ? 'Loading team translators…'
                        : selectedLocaleTags.length > 0
                          ? 'No translators match selected locales'
                          : 'No translators available'
                    }
                    className="team-pools-page__translator-select"
                    buttonAriaLabel="Select translators for assignments"
                    customActions={[
                      {
                        label: showAllTeamUsers ? 'Role only' : 'All team users',
                        onClick: () => {
                          setShowAllTeamUsers((current) => !current);
                          setStatusNotice(null);
                        },
                        ariaLabel: showAllTeamUsers
                          ? 'Show role filtered users only'
                          : 'Show all team users',
                      },
                    ]}
                  />
                </div>
              </div>
            ) : (
              <div className="settings-field">
                <div className="settings-field__header team-pools-page__batch-controls">
                  <div
                    className="team-pools-page__mode-toggle"
                    role="group"
                    aria-label="Batch apply mode"
                  >
                    <button
                      type="button"
                      className={`team-pools-page__mode-option${
                        batchApplyMode === 'merge' ? ' is-active' : ''
                      }`}
                      onClick={() => {
                        setBatchApplyMode('merge');
                        setStatusNotice(null);
                      }}
                    >
                      Merge
                    </button>
                    <button
                      type="button"
                      className={`team-pools-page__mode-option${
                        batchApplyMode === 'replace' ? ' is-active' : ''
                      }`}
                      onClick={() => {
                        setBatchApplyMode('replace');
                        setStatusNotice(null);
                      }}
                    >
                      Replace
                    </button>
                  </div>
                  <button
                    type="button"
                    className="settings-button settings-button--ghost team-pools-page__prefill-button"
                    onClick={handlePrefillCsvFromActiveTeam}
                    disabled={selectedTeamId == null || localePoolsQuery.isLoading}
                  >
                    Prefill from active team
                  </button>
                </div>
                <div className="team-pools-page__csv-input-wrap">
                  <textarea
                    className="team-pools-page__csv-input"
                    value={csvInput}
                    onChange={(event) => {
                      setCsvInput(event.target.value);
                      setStatusNotice(null);
                    }}
                    placeholder={CSV_PLACEHOLDER}
                    aria-label="Assignments batch input"
                  />
                </div>
                <p className="team-pools-page__csv-summary">
                  {!csvHasContent
                    ? 'Paste rows to preview.'
                    : csvParseResult.headerError
                      ? csvParseResult.headerError
                      : `Parsed ${csvParseResult.rows.length} row${
                          csvParseResult.rows.length === 1 ? '' : 's'
                        }. ${csvValidRows.length} ready.${
                          csvErrorRows.length > 0
                            ? ` ${csvErrorRows.length} row${csvErrorRows.length === 1 ? '' : 's'} need attention.`
                            : ''
                        }`}
                </p>
                <div className="team-pools-page__csv-preview">
                  <div className="team-pools-page__csv-preview-header">
                    <div>Line</div>
                    <div>Locale</div>
                    <div>Translators</div>
                    <div>Status</div>
                  </div>
                  {csvParseResult.rows.length > 0 ? (
                    csvParseResult.rows.map((row) => (
                      <div key={row.lineNumber} className="team-pools-page__csv-preview-row">
                        <div className="team-pools-page__csv-cell-muted">{row.lineNumber}</div>
                        <div className="team-pools-page__csv-cell-muted">
                          {row.localeTag || '—'}
                        </div>
                        <div className="team-pools-page__csv-cell-muted">
                          {row.translatorUserIds.length
                            ? row.translatorUserIds
                                .map((id) => {
                                  const username = translatorUsernameById.get(id);
                                  return username ? `${username} (#${id})` : `#${id}`;
                                })
                                .join('|')
                            : '—'}
                        </div>
                        <div>
                          {row.errors.length ? (
                            <span className="team-pools-page__csv-error">
                              {row.errors.join(', ')}
                            </span>
                          ) : (
                            'Ready'
                          )}
                        </div>
                      </div>
                    ))
                  ) : (
                    <div className="team-pools-page__csv-empty">No parsed rows yet.</div>
                  )}
                </div>
              </div>
            )}
          </div>

          <div className="settings-card__footer">
            <div className="settings-actions">
              {editorMode === 'row' ? (
                <button
                  type="button"
                  className="settings-button settings-button--primary"
                  onClick={handleSavePoolSelection}
                  disabled={!canWriteAssignments || !selectedTeamName}
                >
                  Save
                </button>
              ) : (
                <button
                  type="button"
                  className="settings-button settings-button--primary"
                  onClick={handleApplyParsedCsv}
                  disabled={!canApplyParsedCsv}
                >
                  Apply batch
                </button>
              )}
            </div>
            {statusNotice ? (
              <p
                className={`settings-hint team-pools-page__notice${
                  statusNotice.kind === 'error' ? ' is-error' : ''
                }`}
              >
                {statusNotice.message}
              </p>
            ) : null}
          </div>
        </section>

        <section className="team-pools-page__list" aria-label="Team pools list">
          {!selectedTeamName ? (
            <p className="team-pools-page__hint">Select a team to view assignments.</p>
          ) : localePoolsQuery.isLoading ? (
            <p className="team-pools-page__hint">Loading assignments…</p>
          ) : localePoolsQuery.isError ? (
            <p className="team-pools-page__hint is-error">Could not load assignments.</p>
          ) : activeTeamEntries.length === 0 ? (
            <p className="team-pools-page__hint">No pools yet for {selectedTeamName}.</p>
          ) : (
            <div className="team-pools-page__table">
              <div className="team-pools-page__header">
                <div>Locale</div>
                <div>Translators</div>
                <div>Updated</div>
                <div>Actions</div>
              </div>
              {activeTeamEntries.map((entry) => (
                <div key={entry.localeTag} className="team-pools-page__row">
                  <div>{resolveLocaleName(entry.localeTag)}</div>
                  <div className="team-pools-page__translator-list">
                    {entry.translatorUserIds
                      .map(
                        (id) =>
                          translatorNameById.get(id) ??
                          (isPm ? 'Unavailable translator' : `User #${id}`),
                      )
                      .join(', ')}
                  </div>
                  <div>{formatUpdatedAt(entry.updatedAt)}</div>
                  <div className="team-pools-page__actions">
                    <button
                      type="button"
                      className="settings-button settings-button--ghost"
                      onClick={() => handleEditPoolEntry(entry)}
                    >
                      Edit
                    </button>
                    <button
                      type="button"
                      className="settings-button settings-button--ghost"
                      onClick={() => handleDeletePoolEntry(entry)}
                      disabled={!canWriteAssignments}
                    >
                      Remove
                    </button>
                  </div>
                </div>
              ))}
            </div>
          )}
        </section>

        <section className="settings-card team-pools-page__pm-section" aria-label="PM pool">
          <div className="settings-card__header">
            <h2>PM Pool</h2>
          </div>
          <div className="settings-card__content">
            {!selectedTeamName ? (
              <p className="team-pools-page__hint">Select a team to edit PM pool.</p>
            ) : teamPmPoolQuery.isLoading ? (
              <p className="team-pools-page__hint">Loading PM pool…</p>
            ) : teamPmPoolQuery.isError ? (
              <p className="team-pools-page__hint is-error">Could not load PM pool.</p>
            ) : (
              <div className="settings-field">
                <MultiSelectChip
                  label="PMs"
                  options={pmUsersForSelection}
                  selectedValues={selectedPmIds}
                  onChange={(next) => {
                    setSelectedPmIds(next);
                    setPmStatusNotice(null);
                  }}
                  placeholder="Select PMs"
                  emptyOptionsLabel={
                    teamPmPoolQuery.isLoading
                      ? 'Loading PMs…'
                      : selectedTeamId == null
                        ? 'Select a team first'
                        : 'No PMs in team roster'
                  }
                  className="team-pools-page__pm-select"
                  buttonAriaLabel="Select PMs for this team"
                />
                {selectedPmIds.length > 0 ? (
                  <p className="settings-hint">
                    {selectedPmIds.map((id) => pmNameById.get(id) ?? `User #${id}`).join(', ')}
                  </p>
                ) : null}
              </div>
            )}
          </div>
          <div className="settings-card__footer">
            <div className="settings-actions">
              <button
                type="button"
                className="settings-button settings-button--primary"
                onClick={() => {
                  void handleSavePmPool();
                }}
                disabled={!canWritePmPool || !selectedTeamName}
              >
                Save
              </button>
            </div>
            {pmStatusNotice ? (
              <p
                className={`settings-hint team-pools-page__notice${
                  pmStatusNotice.kind === 'error' ? ' is-error' : ''
                }`}
              >
                {pmStatusNotice.message}
              </p>
            ) : null}
          </div>
        </section>
      </div>
    </div>
  );
}
