export type ApiTeam = {
  id: number;
  name: string;
  enabled: boolean;
};

export type ApiTeamUserIdsResponse = {
  userIds: number[];
};

export type ApiTeamUserSummary = {
  id: number;
  username: string;
  commonName?: string | null;
};

export type ApiTeamUsersResponse = {
  users: ApiTeamUserSummary[];
};

export type ApiReplaceTeamUsersResponse = {
  removedUsersCount: number;
  removedLocalePoolRows: number;
};

export type ApiTeamLocalePoolRow = {
  localeTag: string;
  translatorUserIds: number[];
};

export type ApiTeamLocalePoolsResponse = {
  entries: ApiTeamLocalePoolRow[];
};

export type ApiTeamSlackSettings = {
  enabled: boolean;
  slackClientId: string | null;
  slackChannelId: string | null;
};

export type ApiTeamSlackUserMappingRow = {
  mojitoUserId: number;
  mojitoUsername: string;
  slackUserId: string;
  slackUsername: string | null;
  matchSource: string | null;
  lastVerifiedAt: string | null;
};

export type ApiTeamSlackUserMappingsResponse = {
  entries: ApiTeamSlackUserMappingRow[];
};

export type ApiSlackChannelImportPreviewRow = {
  slackUserId: string;
  slackUsername: string | null;
  slackRealName: string | null;
  slackEmail: string | null;
  slackBot: boolean;
  slackDeleted: boolean;
  matchedMojitoUserId: number | null;
  matchedMojitoUsername: string | null;
  matchReason: string | null;
  alreadyMapped: boolean;
  alreadyPm: boolean;
  alreadyTranslator: boolean;
};

export type ApiSlackChannelImportPreviewResponse = {
  slackChannelId: string;
  slackChannelName: string | null;
  rows: ApiSlackChannelImportPreviewRow[];
};

export type ApiSlackChannelImportApplyResponse = {
  scannedRows: number;
  selectedRows: number;
  matchedRows: number;
  addedUsersCount: number;
  mappingsUpsertedCount: number;
};

export type ApiTeamSlackChannelMemberRow = {
  slackUserId: string;
  slackUsername: string | null;
  displayName: string | null;
  email: string | null;
};

export type ApiTeamSlackChannelMembersResponse = {
  slackClientId: string;
  slackChannelId: string;
  slackChannelName: string | null;
  entries: ApiTeamSlackChannelMemberRow[];
};

export type ApiSlackClientIdsResponse = {
  entries: string[];
};

const jsonHeaders = {
  'Content-Type': 'application/json',
};

export async function fetchTeams(): Promise<ApiTeam[]> {
  return fetchTeamsWithOptions();
}

export async function fetchTeamsWithOptions(options?: {
  includeDisabled?: boolean;
}): Promise<ApiTeam[]> {
  const params = new URLSearchParams();
  if (options?.includeDisabled) {
    params.set('includeDisabled', 'true');
  }
  const response = await fetch(`/api/teams${params.size > 0 ? `?${params.toString()}` : ''}`, {
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to load teams');
  }

  return (await response.json()) as ApiTeam[];
}

export async function fetchTeam(teamId: number): Promise<ApiTeam> {
  const response = await fetch(`/api/teams/${teamId}`, {
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to load team');
  }

  return (await response.json()) as ApiTeam;
}

export async function createTeam(name: string): Promise<ApiTeam> {
  const response = await fetch('/api/teams', {
    method: 'POST',
    credentials: 'same-origin',
    headers: jsonHeaders,
    body: JSON.stringify({ name }),
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to create team');
  }

  return (await response.json()) as ApiTeam;
}

export async function updateTeam(teamId: number, name: string): Promise<ApiTeam> {
  const response = await fetch(`/api/teams/${teamId}`, {
    method: 'PATCH',
    credentials: 'same-origin',
    headers: jsonHeaders,
    body: JSON.stringify({ name }),
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to update team');
  }

  return (await response.json()) as ApiTeam;
}

export async function setTeamEnabled(teamId: number, enabled: boolean): Promise<void> {
  const response = await fetch(`/api/teams/${teamId}/enabled`, {
    method: 'PATCH',
    credentials: 'same-origin',
    headers: jsonHeaders,
    body: JSON.stringify({ enabled }),
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to update team');
  }
}

export async function deleteTeam(teamId: number): Promise<void> {
  const response = await fetch(`/api/teams/${teamId}`, {
    method: 'DELETE',
    credentials: 'same-origin',
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to delete team');
  }
}

export async function fetchTeamProjectManagers(teamId: number): Promise<ApiTeamUserIdsResponse> {
  const response = await fetch(`/api/teams/${teamId}/project-managers`, {
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to load project managers');
  }

  return (await response.json()) as ApiTeamUserIdsResponse;
}

export async function fetchTeamUsersByRole(
  teamId: number,
  role: 'PM' | 'TRANSLATOR',
): Promise<ApiTeamUsersResponse> {
  const response = await fetch(
    `/api/teams/${teamId}/users?${new URLSearchParams({ role }).toString()}`,
    {
      credentials: 'same-origin',
      headers: { Accept: 'application/json' },
    },
  );

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to load team users');
  }

  return (await response.json()) as ApiTeamUsersResponse;
}

export async function replaceTeamProjectManagers(teamId: number, userIds: number[]): Promise<void> {
  const response = await fetch(`/api/teams/${teamId}/project-managers`, {
    method: 'PUT',
    credentials: 'same-origin',
    headers: jsonHeaders,
    body: JSON.stringify({ userIds }),
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to save project managers');
  }
}

export async function fetchTeamPmPool(teamId: number): Promise<ApiTeamUserIdsResponse> {
  const response = await fetch(`/api/teams/${teamId}/pm-pool`, {
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to load PM pool');
  }

  return (await response.json()) as ApiTeamUserIdsResponse;
}

export async function replaceTeamPmPool(teamId: number, userIds: number[]): Promise<void> {
  const response = await fetch(`/api/teams/${teamId}/pm-pool`, {
    method: 'PUT',
    credentials: 'same-origin',
    headers: jsonHeaders,
    body: JSON.stringify({ userIds }),
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to save PM pool');
  }
}

export async function fetchTeamTranslators(teamId: number): Promise<ApiTeamUserIdsResponse> {
  const response = await fetch(`/api/teams/${teamId}/translators`, {
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to load translators');
  }

  return (await response.json()) as ApiTeamUserIdsResponse;
}

export async function replaceTeamTranslators(
  teamId: number,
  userIds: number[],
): Promise<ApiReplaceTeamUsersResponse> {
  const response = await fetch(`/api/teams/${teamId}/translators`, {
    method: 'PUT',
    credentials: 'same-origin',
    headers: jsonHeaders,
    body: JSON.stringify({ userIds }),
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to save translators');
  }

  const body = await response.text().catch(() => '');
  if (!body) {
    return { removedUsersCount: 0, removedLocalePoolRows: 0 };
  }
  return JSON.parse(body) as ApiReplaceTeamUsersResponse;
}

export async function fetchTeamLocalePools(teamId: number): Promise<ApiTeamLocalePoolsResponse> {
  const response = await fetch(`/api/teams/${teamId}/locale-pools`, {
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to load locale pools');
  }

  return (await response.json()) as ApiTeamLocalePoolsResponse;
}

export async function replaceTeamLocalePools(
  teamId: number,
  entries: ApiTeamLocalePoolRow[],
): Promise<void> {
  const response = await fetch(`/api/teams/${teamId}/locale-pools`, {
    method: 'PUT',
    credentials: 'same-origin',
    headers: jsonHeaders,
    body: JSON.stringify({ entries }),
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to save locale pools');
  }
}

export async function updateUserTeamAssignment(
  userId: number,
  payload: { pmTeamId: number | null; translatorTeamId: number | null },
): Promise<void> {
  const response = await fetch(`/api/teams/users/${userId}/assignment`, {
    method: 'PUT',
    credentials: 'same-origin',
    headers: jsonHeaders,
    body: JSON.stringify(payload),
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to update team assignment');
  }
}

export async function fetchTeamSlackSettings(teamId: number): Promise<ApiTeamSlackSettings> {
  const response = await fetch(`/api/teams/${teamId}/slack-settings`, {
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to load team Slack settings');
  }

  return (await response.json()) as ApiTeamSlackSettings;
}

export async function fetchSlackClientIds(): Promise<ApiSlackClientIdsResponse> {
  const response = await fetch('/api/teams/slack-clients', {
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to load Slack client IDs');
  }

  return (await response.json()) as ApiSlackClientIdsResponse;
}

export async function updateTeamSlackSettings(
  teamId: number,
  payload: ApiTeamSlackSettings,
): Promise<ApiTeamSlackSettings> {
  const response = await fetch(`/api/teams/${teamId}/slack-settings`, {
    method: 'PUT',
    credentials: 'same-origin',
    headers: jsonHeaders,
    body: JSON.stringify(payload),
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to save team Slack settings');
  }

  return (await response.json()) as ApiTeamSlackSettings;
}

export async function fetchTeamSlackUserMappings(
  teamId: number,
): Promise<ApiTeamSlackUserMappingsResponse> {
  const response = await fetch(`/api/teams/${teamId}/slack-user-mappings`, {
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to load team Slack user mappings');
  }

  return (await response.json()) as ApiTeamSlackUserMappingsResponse;
}

export async function replaceTeamSlackUserMappings(
  teamId: number,
  entries: ApiTeamSlackUserMappingRow[],
): Promise<void> {
  const response = await fetch(`/api/teams/${teamId}/slack-user-mappings`, {
    method: 'PUT',
    credentials: 'same-origin',
    headers: jsonHeaders,
    body: JSON.stringify({ entries }),
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to save team Slack user mappings');
  }
}

export async function previewTeamSlackChannelImport(
  teamId: number,
): Promise<ApiSlackChannelImportPreviewResponse> {
  const response = await fetch(`/api/teams/${teamId}/slack-channel-import/preview`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to preview Slack channel import');
  }

  return (await response.json()) as ApiSlackChannelImportPreviewResponse;
}

export async function applyTeamSlackChannelImport(
  teamId: number,
  payload: { role: 'PM' | 'TRANSLATOR'; slackUserIds?: string[] },
): Promise<ApiSlackChannelImportApplyResponse> {
  const response = await fetch(`/api/teams/${teamId}/slack-channel-import/apply`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: jsonHeaders,
    body: JSON.stringify(payload),
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to apply Slack channel import');
  }

  return (await response.json()) as ApiSlackChannelImportApplyResponse;
}

export async function fetchTeamSlackChannelMembers(
  teamId: number,
): Promise<ApiTeamSlackChannelMembersResponse> {
  const response = await fetch(`/api/teams/${teamId}/slack-channel-members`, {
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to load Slack channel users');
  }

  return (await response.json()) as ApiTeamSlackChannelMembersResponse;
}

export async function sendTeamSlackChannelTest(teamId: number): Promise<void> {
  const response = await fetch(`/api/teams/${teamId}/slack-test-channel`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to send Slack test message');
  }
}

export async function sendTeamSlackMentionTest(
  teamId: number,
  payload: { slackUserId: string; mojitoUsername?: string | null },
): Promise<void> {
  const response = await fetch(`/api/teams/${teamId}/slack-test-mention`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: jsonHeaders,
    body: JSON.stringify(payload),
  });

  if (!response.ok) {
    const message = await response.text().catch(() => '');
    throw new Error(message || 'Failed to send Slack test message');
  }
}
