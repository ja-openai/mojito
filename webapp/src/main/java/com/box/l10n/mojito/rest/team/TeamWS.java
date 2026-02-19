package com.box.l10n.mojito.rest.team;

import com.box.l10n.mojito.entity.Team;
import com.box.l10n.mojito.entity.TeamUserRole;
import com.box.l10n.mojito.service.team.TeamService;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/teams")
public class TeamWS {

  private final TeamService teamService;

  public TeamWS(TeamService teamService) {
    this.teamService = teamService;
  }

  public record TeamResponse(Long id, String name) {}

  public record UpsertTeamRequest(String name) {}

  public record ReplaceUserIdsRequest(List<Long> userIds) {}

  public record ReplaceTeamUsersResponse(int removedUsersCount, int removedLocalePoolRows) {}

  public record TeamUserIdsResponse(List<Long> userIds) {}

  public record LocalePoolRow(String localeTag, List<Long> translatorUserIds) {}

  public record ReplaceLocalePoolsRequest(List<LocalePoolRow> entries) {}

  public record LocalePoolsResponse(List<LocalePoolRow> entries) {}

  public record UpdateUserTeamAssignmentsRequest(Long pmTeamId, Long translatorTeamId) {}

  @GetMapping
  public List<TeamResponse> getTeams() {
    List<Team> teams =
        teamService.isCurrentUserAdmin()
            ? teamService.findAll()
            : teamService.findCurrentUserTeams();
    return teams.stream().map(team -> new TeamResponse(team.getId(), team.getName())).toList();
  }

  @GetMapping("/{teamId}")
  public TeamResponse getTeam(@PathVariable Long teamId) {
    if (!teamService.isCurrentUserAdmin()) {
      teamService.assertCurrentUserCanAccessTeam(teamId);
    }
    Team team = getTeamOr404(teamId);
    return new TeamResponse(team.getId(), team.getName());
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public TeamResponse createTeam(@RequestBody UpsertTeamRequest request) {
    try {
      Team created = teamService.createTeam(request != null ? request.name() : null);
      return new TeamResponse(created.getId(), created.getName());
    } catch (IllegalArgumentException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
    }
  }

  @PatchMapping("/{teamId}")
  public TeamResponse updateTeam(
      @PathVariable Long teamId, @RequestBody UpsertTeamRequest request) {
    try {
      Team updated = teamService.updateTeam(teamId, request != null ? request.name() : null);
      return new TeamResponse(updated.getId(), updated.getName());
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
    }
  }

  @GetMapping("/{teamId}/project-managers")
  public TeamUserIdsResponse getProjectManagers(@PathVariable Long teamId) {
    teamService.assertCurrentUserCanAccessTeam(teamId);
    return new TeamUserIdsResponse(teamService.getTeamUserIdsByRole(teamId, TeamUserRole.PM));
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
    teamService.assertCurrentUserCanAccessTeam(teamId);
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
    teamService.assertCurrentUserCanAccessTeam(teamId);
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
    teamService.assertCurrentUserCanAccessTeam(teamId);
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
}
