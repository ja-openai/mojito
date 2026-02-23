package com.box.l10n.mojito.rest.team;

import com.box.l10n.mojito.entity.Team;
import com.box.l10n.mojito.entity.TeamUserRole;
import com.box.l10n.mojito.service.team.TeamService;
import com.box.l10n.mojito.slack.SlackClient;
import com.box.l10n.mojito.slack.SlackClientException;
import com.box.l10n.mojito.slack.SlackClients;
import com.box.l10n.mojito.slack.request.Channel;
import com.box.l10n.mojito.slack.request.Message;
import com.box.l10n.mojito.slack.request.User;
import java.time.ZonedDateTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/teams")
public class TeamWS {

  private final TeamService teamService;
  private final SlackClients slackClients;

  public TeamWS(TeamService teamService, SlackClients slackClients) {
    this.teamService = teamService;
    this.slackClients = slackClients;
  }

  public record TeamResponse(Long id, String name, boolean enabled) {}

  public record SlackClientIdsResponse(List<String> entries) {}

  public record UpsertTeamRequest(String name) {}

  public record UpdateTeamEnabledRequest(Boolean enabled) {}

  public record ReplaceUserIdsRequest(List<Long> userIds) {}

  public record ReplaceTeamUsersResponse(int removedUsersCount, int removedLocalePoolRows) {}

  public record TeamUserIdsResponse(List<Long> userIds) {}

  public record TeamUserSummaryRow(Long id, String username, String commonName) {}

  public record TeamUsersResponse(List<TeamUserSummaryRow> users) {}

  public record LocalePoolRow(String localeTag, List<Long> translatorUserIds) {}

  public record ReplaceLocalePoolsRequest(List<LocalePoolRow> entries) {}

  public record LocalePoolsResponse(List<LocalePoolRow> entries) {}

  public record UpdateUserTeamAssignmentsRequest(Long pmTeamId, Long translatorTeamId) {}

  public record TeamSlackSettingsResponse(
      boolean enabled, String slackClientId, String slackChannelId) {}

  public record UpdateTeamSlackSettingsRequest(
      Boolean enabled, String slackClientId, String slackChannelId) {}

  public record TeamSlackUserMappingRow(
      Long mojitoUserId,
      String mojitoUsername,
      String slackUserId,
      String slackUsername,
      String matchSource,
      ZonedDateTime lastVerifiedAt) {}

  public record ReplaceTeamSlackUserMappingsRequest(List<TeamSlackUserMappingRow> entries) {}

  public record TeamSlackUserMappingsResponse(List<TeamSlackUserMappingRow> entries) {}

  public record SlackChannelImportPreviewRow(
      String slackUserId,
      String slackUsername,
      String slackRealName,
      String slackEmail,
      boolean slackBot,
      boolean slackDeleted,
      Long matchedMojitoUserId,
      String matchedMojitoUsername,
      String matchReason,
      boolean alreadyMapped,
      boolean alreadyPm,
      boolean alreadyTranslator) {}

  public record SlackChannelImportPreviewResponse(
      String slackChannelId, String slackChannelName, List<SlackChannelImportPreviewRow> rows) {}

  public record ApplySlackChannelImportRequest(String role, List<String> slackUserIds) {}

  public record ApplySlackChannelImportResponse(
      int scannedRows,
      int selectedRows,
      int matchedRows,
      int addedUsersCount,
      int mappingsUpsertedCount) {}

  public record TeamSlackChannelMemberRow(
      String slackUserId, String slackUsername, String displayName, String email) {}

  public record TeamSlackChannelMembersResponse(
      String slackClientId,
      String slackChannelId,
      String slackChannelName,
      List<TeamSlackChannelMemberRow> entries) {}

  public record SendTeamSlackMentionTestRequest(String slackUserId, String mojitoUsername) {}

  @GetMapping
  public List<TeamResponse> getTeams(
      @RequestParam(name = "includeDisabled", required = false, defaultValue = "false")
          boolean includeDisabled) {
    List<Team> teams =
        teamService.isCurrentUserAdmin()
            ? teamService.findAll(includeDisabled)
            : teamService.findCurrentUserTeams();
    return teams.stream()
        .map(
            team ->
                new TeamResponse(
                    team.getId(), team.getName(), Boolean.TRUE.equals(team.getEnabled())))
        .toList();
  }

  @GetMapping("/slack-clients")
  public SlackClientIdsResponse getSlackClientIds() {
    assertCurrentUserIsAdmin();
    return new SlackClientIdsResponse(slackClients.getIds());
  }

  @GetMapping("/{teamId}")
  public TeamResponse getTeam(@PathVariable Long teamId) {
    if (!teamService.isCurrentUserAdmin()) {
      teamService.assertCurrentUserCanAccessTeam(teamId);
    }
    Team team = getTeamOr404(teamId);
    return new TeamResponse(team.getId(), team.getName(), Boolean.TRUE.equals(team.getEnabled()));
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public TeamResponse createTeam(@RequestBody UpsertTeamRequest request) {
    try {
      Team created = teamService.createTeam(request != null ? request.name() : null);
      return new TeamResponse(
          created.getId(), created.getName(), Boolean.TRUE.equals(created.getEnabled()));
    } catch (IllegalArgumentException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
    }
  }

  @PatchMapping("/{teamId}")
  public TeamResponse updateTeam(
      @PathVariable Long teamId, @RequestBody UpsertTeamRequest request) {
    try {
      Team updated = teamService.updateTeam(teamId, request != null ? request.name() : null);
      return new TeamResponse(
          updated.getId(), updated.getName(), Boolean.TRUE.equals(updated.getEnabled()));
    } catch (IllegalArgumentException ex) {
      throw toStatusException(ex);
    }
  }

  @PatchMapping("/{teamId}/enabled")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void updateTeamEnabled(
      @PathVariable Long teamId, @RequestBody UpdateTeamEnabledRequest request) {
    try {
      if (request == null || request.enabled() == null) {
        throw new IllegalArgumentException("enabled is required");
      }
      teamService.updateTeamEnabled(teamId, request.enabled());
    } catch (IllegalArgumentException ex) {
      throw toStatusException(ex);
    }
  }

  @DeleteMapping("/{teamId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteTeam(@PathVariable Long teamId) {
    try {
      teamService.deleteTeam(teamId);
    } catch (IllegalArgumentException ex) {
      throw toStatusException(ex);
    } catch (IllegalStateException ex) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage());
    }
  }

  @GetMapping("/{teamId}/project-managers")
  public TeamUserIdsResponse getProjectManagers(@PathVariable Long teamId) {
    teamService.assertCurrentUserCanReadTeam(teamId);
    return new TeamUserIdsResponse(teamService.getTeamUserIdsByRole(teamId, TeamUserRole.PM));
  }

  @GetMapping("/{teamId}/users")
  public TeamUsersResponse getTeamUsers(
      @PathVariable Long teamId, @RequestParam TeamUserRole role) {
    teamService.assertCurrentUserCanReadTeam(teamId);
    return new TeamUsersResponse(
        teamService.getTeamUsersByRole(teamId, role).stream()
            .map(user -> new TeamUserSummaryRow(user.id(), user.username(), user.commonName()))
            .toList());
  }

  @PutMapping("/{teamId}/project-managers")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void replaceProjectManagers(
      @PathVariable Long teamId, @RequestBody ReplaceUserIdsRequest request) {
    teamService.assertCurrentUserCanAccessTeam(teamId);
    try {
      teamService.replaceTeamUsersByRole(
          teamId, TeamUserRole.PM, request != null ? request.userIds() : List.of());
    } catch (IllegalArgumentException ex) {
      throw toStatusException(ex);
    }
  }

  @GetMapping("/{teamId}/pm-pool")
  public TeamUserIdsResponse getPmPool(@PathVariable Long teamId) {
    teamService.assertCurrentUserCanReadTeam(teamId);
    return new TeamUserIdsResponse(teamService.getPmPool(teamId));
  }

  @PutMapping("/{teamId}/pm-pool")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void replacePmPool(@PathVariable Long teamId, @RequestBody ReplaceUserIdsRequest request) {
    teamService.assertCurrentUserCanAccessTeam(teamId);
    try {
      teamService.replacePmPool(teamId, request != null ? request.userIds() : List.of());
    } catch (IllegalArgumentException ex) {
      throw toStatusException(ex);
    }
  }

  @GetMapping("/{teamId}/translators")
  public TeamUserIdsResponse getTranslators(@PathVariable Long teamId) {
    teamService.assertCurrentUserCanReadTeam(teamId);
    return new TeamUserIdsResponse(
        teamService.getTeamUserIdsByRole(teamId, TeamUserRole.TRANSLATOR));
  }

  @PutMapping("/{teamId}/translators")
  public ReplaceTeamUsersResponse replaceTranslators(
      @PathVariable Long teamId, @RequestBody ReplaceUserIdsRequest request) {
    teamService.assertCurrentUserCanAccessTeam(teamId);
    try {
      TeamService.ReplaceTeamUsersResult result =
          teamService.replaceTeamUsersByRole(
              teamId, TeamUserRole.TRANSLATOR, request != null ? request.userIds() : List.of());
      return new ReplaceTeamUsersResponse(
          result.removedUsersCount(), result.removedLocalePoolRows());
    } catch (IllegalArgumentException ex) {
      throw toStatusException(ex);
    }
  }

  @GetMapping("/{teamId}/locale-pools")
  public LocalePoolsResponse getLocalePools(@PathVariable Long teamId) {
    teamService.assertCurrentUserCanReadTeam(teamId);
    List<LocalePoolRow> entries =
        teamService.getLocalePools(teamId).stream()
            .map(entry -> new LocalePoolRow(entry.localeTag(), entry.translatorUserIds()))
            .toList();
    return new LocalePoolsResponse(entries);
  }

  @PutMapping("/{teamId}/locale-pools")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void replaceLocalePools(
      @PathVariable Long teamId, @RequestBody ReplaceLocalePoolsRequest request) {
    teamService.assertCurrentUserCanAccessTeam(teamId);
    try {
      List<TeamService.LocalePoolEntry> entries =
          (request == null || request.entries() == null
                  ? List.<LocalePoolRow>of()
                  : request.entries())
              .stream()
                  .map(
                      row ->
                          new TeamService.LocalePoolEntry(row.localeTag(), row.translatorUserIds()))
                  .toList();
      teamService.replaceLocalePools(teamId, entries);
    } catch (IllegalArgumentException ex) {
      throw toStatusException(ex);
    }
  }

  @GetMapping("/{teamId}/slack-settings")
  public TeamSlackSettingsResponse getTeamSlackSettings(@PathVariable Long teamId) {
    assertCurrentUserIsAdmin();
    TeamService.TeamSlackSettings settings = teamService.getTeamSlackSettings(teamId);
    return new TeamSlackSettingsResponse(
        settings.enabled(), settings.slackClientId(), settings.slackChannelId());
  }

  @PutMapping("/{teamId}/slack-settings")
  public TeamSlackSettingsResponse updateTeamSlackSettings(
      @PathVariable Long teamId, @RequestBody UpdateTeamSlackSettingsRequest request) {
    assertCurrentUserIsAdmin();
    try {
      TeamService.TeamSlackSettings settings =
          teamService.updateTeamSlackSettings(
              teamId,
              request != null ? request.enabled() : null,
              request != null ? request.slackClientId() : null,
              request != null ? request.slackChannelId() : null);
      return new TeamSlackSettingsResponse(
          settings.enabled(), settings.slackClientId(), settings.slackChannelId());
    } catch (IllegalArgumentException ex) {
      throw toStatusException(ex);
    }
  }

  @GetMapping("/{teamId}/slack-user-mappings")
  public TeamSlackUserMappingsResponse getTeamSlackUserMappings(@PathVariable Long teamId) {
    assertCurrentUserIsAdmin();
    List<TeamSlackUserMappingRow> entries =
        teamService.getTeamSlackUserMappings(teamId).stream()
            .map(
                entry ->
                    new TeamSlackUserMappingRow(
                        entry.mojitoUserId(),
                        entry.mojitoUsername(),
                        entry.slackUserId(),
                        entry.slackUsername(),
                        entry.matchSource(),
                        entry.lastVerifiedAt()))
            .toList();
    return new TeamSlackUserMappingsResponse(entries);
  }

  @PutMapping("/{teamId}/slack-user-mappings")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void replaceTeamSlackUserMappings(
      @PathVariable Long teamId, @RequestBody ReplaceTeamSlackUserMappingsRequest request) {
    assertCurrentUserIsAdmin();
    try {
      teamService.replaceTeamSlackUserMappings(
          teamId,
          (request == null || request.entries() == null
                  ? List.<TeamSlackUserMappingRow>of()
                  : request.entries())
              .stream()
                  .map(
                      row ->
                          new TeamService.UpsertTeamSlackUserMappingEntry(
                              row.mojitoUserId(),
                              row.slackUserId(),
                              row.slackUsername(),
                              row.matchSource()))
                  .toList());
    } catch (IllegalArgumentException ex) {
      throw toStatusException(ex);
    }
  }

  @PostMapping("/{teamId}/slack-channel-import/preview")
  public SlackChannelImportPreviewResponse previewSlackChannelImport(@PathVariable Long teamId) {
    assertCurrentUserIsAdmin();
    try {
      TeamService.SlackChannelImportPreview preview = teamService.previewSlackChannelImport(teamId);
      return new SlackChannelImportPreviewResponse(
          preview.slackChannelId(),
          preview.slackChannelName(),
          preview.rows().stream()
              .map(
                  row ->
                      new SlackChannelImportPreviewRow(
                          row.slackUserId(),
                          row.slackUsername(),
                          row.slackRealName(),
                          row.slackEmail(),
                          row.slackBot(),
                          row.slackDeleted(),
                          row.matchedMojitoUserId(),
                          row.matchedMojitoUsername(),
                          row.matchReason(),
                          row.alreadyMapped(),
                          row.alreadyPm(),
                          row.alreadyTranslator()))
              .toList());
    } catch (IllegalArgumentException ex) {
      throw toStatusException(ex);
    }
  }

  @PostMapping("/{teamId}/slack-channel-import/apply")
  public ApplySlackChannelImportResponse applySlackChannelImport(
      @PathVariable Long teamId, @RequestBody ApplySlackChannelImportRequest request) {
    assertCurrentUserIsAdmin();
    try {
      TeamUserRole role =
          request == null || request.role() == null || request.role().isBlank()
              ? null
              : TeamUserRole.valueOf(request.role().trim().toUpperCase());
      TeamService.SlackChannelImportApplyResult result =
          teamService.applySlackChannelImport(
              teamId, role, request != null ? request.slackUserIds() : List.of());
      return new ApplySlackChannelImportResponse(
          result.scannedRows(),
          result.selectedRows(),
          result.matchedRows(),
          result.addedUsersCount(),
          result.mappingsUpsertedCount());
    } catch (IllegalArgumentException ex) {
      throw toStatusException(ex);
    }
  }

  @GetMapping("/{teamId}/slack-channel-members")
  public TeamSlackChannelMembersResponse getTeamSlackChannelMembers(@PathVariable Long teamId) {
    assertCurrentUserIsAdmin();

    TeamService.TeamSlackSettings settings = teamService.getTeamSlackSettings(teamId);
    String slackClientId = settings.slackClientId();
    String channelId = settings.slackChannelId();
    if (slackClientId == null || slackClientId.isBlank()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Team Slack client ID is not configured");
    }
    if (channelId == null || channelId.isBlank()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Team Slack channel ID is not configured");
    }

    SlackClient slackClient = slackClients.getById(slackClientId);
    if (slackClient == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Unknown Slack client ID in team settings: " + slackClientId);
    }

    try {
      String channelName = null;
      try {
        Channel channel = slackClient.getConversationById(channelId);
        channelName = channel != null ? channel.getName() : null;
      } catch (SlackClientException ex) {
        // Keep member listing usable even if channel metadata lookup fails (e.g. missing scope).
      }

      List<TeamSlackChannelMemberRow> entries =
          slackClient.getConversationMemberIds(channelId).stream()
              .distinct()
              .sorted()
              .map(
                  memberId -> {
                    try {
                      User user = slackClient.getUserById(memberId);
                      String username = user != null ? user.getName() : null;
                      String displayName = user != null ? user.getReal_name() : null;
                      String email = user != null ? user.getEmail() : null;
                      return new TeamSlackChannelMemberRow(memberId, username, displayName, email);
                    } catch (SlackClientException ex) {
                      return new TeamSlackChannelMemberRow(memberId, null, null, null);
                    }
                  })
              .toList();

      return new TeamSlackChannelMembersResponse(slackClientId, channelId, channelName, entries);
    } catch (SlackClientException ex) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          ex.getMessage() != null ? ex.getMessage() : "Failed to load Slack channel members",
          ex);
    }
  }

  @PostMapping("/{teamId}/slack-test-channel")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void sendTeamSlackChannelTest(@PathVariable Long teamId) {
    assertCurrentUserIsAdmin();

    Team team = getTeamOr404(teamId);
    TeamService.TeamSlackSettings settings = teamService.getTeamSlackSettings(teamId);
    String slackClientId = settings.slackClientId();
    String channelId = settings.slackChannelId();

    if (slackClientId == null || slackClientId.isBlank()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Team Slack client ID is not configured");
    }
    if (channelId == null || channelId.isBlank()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Team Slack channel ID is not configured");
    }

    SlackClient slackClient = slackClients.getById(slackClientId);
    if (slackClient == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Unknown Slack client ID in team settings: " + slackClientId);
    }

    try {
      Message message = new Message();
      message.setChannel(channelId);
      message.setText(
          "Mojito test message for team "
              + team.getName()
              + " (#"
              + team.getId()
              + ") at "
              + ZonedDateTime.now());
      slackClient.sendInstantMessage(message);
    } catch (SlackClientException ex) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          ex.getMessage() != null ? ex.getMessage() : "Failed to send Slack test message",
          ex);
    }
  }

  @PostMapping("/{teamId}/slack-test-mention")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void sendTeamSlackMentionTest(
      @PathVariable Long teamId, @RequestBody SendTeamSlackMentionTestRequest request) {
    assertCurrentUserIsAdmin();

    Team team = getTeamOr404(teamId);
    TeamService.TeamSlackSettings settings = teamService.getTeamSlackSettings(teamId);
    String slackClientId = settings.slackClientId();
    String channelId = settings.slackChannelId();
    String slackUserId = request != null ? request.slackUserId() : null;
    String mojitoUsername = request != null ? request.mojitoUsername() : null;

    if (slackClientId == null || slackClientId.isBlank()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Team Slack client ID is not configured");
    }
    if (slackUserId == null || slackUserId.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Slack user ID is required");
    }
    if (channelId == null || channelId.isBlank()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Team Slack channel ID is not configured");
    }

    SlackClient slackClient = slackClients.getById(slackClientId);
    if (slackClient == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Unknown Slack client ID in team settings: " + slackClientId);
    }

    try {
      Message message = new Message();
      message.setChannel(channelId);
      message.setText(
          "<@"
              + slackUserId.trim()
              + "> "
              + "Mojito test mention for team "
              + team.getName()
              + " (#"
              + team.getId()
              + ")"
              + (mojitoUsername != null && !mojitoUsername.isBlank()
                  ? " [Mojito user: " + mojitoUsername.trim() + "]"
                  : "")
              + " at "
              + ZonedDateTime.now());
      slackClient.sendInstantMessage(message);
    } catch (SlackClientException ex) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          ex.getMessage() != null ? ex.getMessage() : "Failed to send Slack test mention",
          ex);
    }
  }

  @PutMapping("/users/{userId}/assignment")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void updateUserTeamAssignments(
      @PathVariable Long userId, @RequestBody UpdateUserTeamAssignmentsRequest request) {
    try {
      teamService.setUserTeamAssignments(
          userId,
          request != null ? request.pmTeamId() : null,
          request != null ? request.translatorTeamId() : null);
    } catch (IllegalArgumentException ex) {
      throw toStatusException(ex);
    }
  }

  private Team getTeamOr404(Long teamId) {
    try {
      return teamService.getTeam(teamId);
    } catch (IllegalArgumentException ex) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
    }
  }

  private ResponseStatusException toStatusException(IllegalArgumentException ex) {
    if (ex.getMessage() != null && ex.getMessage().startsWith("Team not found")) {
      return new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
    }
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
  }

  private void assertCurrentUserIsAdmin() {
    if (!teamService.isCurrentUserAdmin()) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Forbidden");
    }
  }
}
