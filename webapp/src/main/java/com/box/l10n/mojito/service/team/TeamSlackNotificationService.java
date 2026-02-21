package com.box.l10n.mojito.service.team;

import com.box.l10n.mojito.entity.Team;
import com.box.l10n.mojito.entity.review.ReviewProject;
import com.box.l10n.mojito.entity.review.ReviewProjectAssignmentEventType;
import com.box.l10n.mojito.entity.security.user.User;
import com.box.l10n.mojito.slack.SlackClient;
import com.box.l10n.mojito.slack.SlackClientException;
import com.box.l10n.mojito.slack.SlackClients;
import com.box.l10n.mojito.slack.request.Message;
import com.box.l10n.mojito.utils.ServerConfig;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class TeamSlackNotificationService {

  private static final Logger logger = LoggerFactory.getLogger(TeamSlackNotificationService.class);

  private final TeamService teamService;
  private final SlackClients slackClients;
  private final ServerConfig serverConfig;

  public TeamSlackNotificationService(
      TeamService teamService, SlackClients slackClients, ServerConfig serverConfig) {
    this.teamService = teamService;
    this.slackClients = slackClients;
    this.serverConfig = serverConfig;
  }

  public void sendReviewProjectAssignmentNotification(
      ReviewProject reviewProject, ReviewProjectAssignmentEventType eventType, String note) {
    if (reviewProject == null) {
      return;
    }

    Team team = reviewProject.getTeam();
    if (team == null || team.getId() == null) {
      return;
    }

    TeamService.TeamSlackSettings settings = teamService.getTeamSlackSettings(team.getId());
    if (!settings.enabled()
        || isBlank(settings.slackClientId())
        || isBlank(settings.slackChannelId())) {
      return;
    }

    SlackClient slackClient = slackClients.getById(settings.slackClientId());
    if (slackClient == null) {
      logger.warn(
          "Skipping review project assignment Slack notification: unknown Slack client id {} for team {}",
          settings.slackClientId(),
          team.getId());
      return;
    }

    Map<Long, TeamService.TeamSlackUserMappingEntry> mappingsByUserId =
        teamService.getTeamSlackUserMappings(team.getId()).stream()
            .collect(Collectors.toMap(TeamService.TeamSlackUserMappingEntry::mojitoUserId, e -> e));
    mappingsByUserId =
        ensureMappingsForAssignedUsers(
            reviewProject, settings, slackClient, new LinkedHashMap<>(mappingsByUserId));

    String text = buildReviewProjectAssignmentMessage(reviewProject, eventType, note, mappingsByUserId);
    if (isBlank(text)) {
      return;
    }

    try {
      Message message = new Message();
      message.setChannel(settings.slackChannelId());
      message.setText(text);
      slackClient.sendInstantMessage(message);
    } catch (SlackClientException ex) {
      logger.warn(
          "Failed to send review project assignment Slack notification for project {} team {}: {}",
          reviewProject.getId(),
          team.getId(),
          ex.getMessage(),
          ex);
    }
  }

  private Map<Long, TeamService.TeamSlackUserMappingEntry> ensureMappingsForAssignedUsers(
      ReviewProject reviewProject,
      TeamService.TeamSlackSettings settings,
      SlackClient slackClient,
      Map<Long, TeamService.TeamSlackUserMappingEntry> mappingsByUserId) {
    Team team = reviewProject.getTeam();
    if (team == null || team.getId() == null) {
      return mappingsByUserId;
    }
    if (isBlank(settings.slackChannelId())) {
      return mappingsByUserId;
    }

    List<User> assignedUsers = new ArrayList<>();
    if (reviewProject.getAssignedPmUser() != null) {
      assignedUsers.add(reviewProject.getAssignedPmUser());
    }
    if (reviewProject.getAssignedTranslatorUser() != null) {
      assignedUsers.add(reviewProject.getAssignedTranslatorUser());
    }

    List<User> missingUsers =
        assignedUsers.stream()
            .filter(user -> user != null && user.getId() != null)
            .filter(
                user -> {
                  TeamService.TeamSlackUserMappingEntry mapping = mappingsByUserId.get(user.getId());
                  return mapping == null || isBlank(mapping.slackUserId());
                })
            .toList();
    if (missingUsers.isEmpty()) {
      return mappingsByUserId;
    }

    try {
      List<SlackUserCandidate> slackCandidates =
          slackClient.getConversationMemberIds(settings.slackChannelId()).stream()
              .distinct()
              .map(
                  memberId -> {
                    try {
                      com.box.l10n.mojito.slack.request.User slackUser = slackClient.getUserById(memberId);
                      return new SlackUserCandidate(
                          memberId,
                          slackUser != null ? slackUser.getName() : null,
                          normalizeKey(slackUser != null ? slackUser.getName() : null),
                          normalizeKey(slackUser != null ? slackUser.getEmail() : null));
                    } catch (SlackClientException ex) {
                      logger.debug("Skipping Slack member {} during auto-match: {}", memberId, ex.getMessage());
                      return null;
                    }
                  })
              .filter(candidate -> candidate != null)
              .toList();

      Map<String, List<SlackUserCandidate>> byUsername = new LinkedHashMap<>();
      Map<String, List<SlackUserCandidate>> byEmail = new LinkedHashMap<>();
      Map<String, List<SlackUserCandidate>> byEmailLocal = new LinkedHashMap<>();
      for (SlackUserCandidate candidate : slackCandidates) {
        addSlackCandidateIndex(byUsername, candidate.usernameKey(), candidate);
        addSlackCandidateIndex(byEmail, candidate.emailKey(), candidate);
        if (!isBlank(candidate.emailKey()) && candidate.emailKey().contains("@")) {
          addSlackCandidateIndex(
              byEmailLocal, candidate.emailKey().substring(0, candidate.emailKey().indexOf('@')), candidate);
        }
      }

      boolean changed = false;
      Set<String> usedSlackUserIdsLower =
          mappingsByUserId.values().stream()
              .map(TeamService.TeamSlackUserMappingEntry::slackUserId)
              .filter(value -> !isBlank(value))
              .map(this::normalizeKey)
              .collect(Collectors.toCollection(LinkedHashSet::new));

      for (User mojitoUser : missingUsers) {
        SlackUserCandidate match = findAutoMatchCandidate(mojitoUser, byEmail, byEmailLocal, byUsername);
        if (match == null || isBlank(match.slackUserId())) {
          continue;
        }
        String slackUserIdKey = normalizeKey(match.slackUserId());
        if (usedSlackUserIdsLower.contains(slackUserIdKey)) {
          continue;
        }
        usedSlackUserIdsLower.add(slackUserIdKey);
        TeamService.TeamSlackUserMappingEntry existing = mappingsByUserId.get(mojitoUser.getId());
        mappingsByUserId.put(
            mojitoUser.getId(),
            new TeamService.TeamSlackUserMappingEntry(
                mojitoUser.getId(),
                mojitoUser.getUsername(),
                match.slackUserId(),
                match.slackUsername(),
                "auto-notify",
                existing != null ? existing.lastVerifiedAt() : null));
        changed = true;
      }

      if (!changed) {
        return mappingsByUserId;
      }

      teamService.replaceTeamSlackUserMappings(
          team.getId(),
          mappingsByUserId.values().stream()
              .map(
                  entry ->
                      new TeamService.UpsertTeamSlackUserMappingEntry(
                          entry.mojitoUserId(),
                          entry.slackUserId(),
                          entry.slackUsername(),
                          entry.matchSource()))
              .toList());

      return teamService.getTeamSlackUserMappings(team.getId()).stream()
          .collect(Collectors.toMap(TeamService.TeamSlackUserMappingEntry::mojitoUserId, e -> e));
    } catch (SlackClientException | IllegalArgumentException ex) {
      logger.warn(
          "Failed Slack auto-match for team {} before review project notification: {}",
          team.getId(),
          ex.getMessage());
      return mappingsByUserId;
    }
  }

  private SlackUserCandidate findAutoMatchCandidate(
      User mojitoUser,
      Map<String, List<SlackUserCandidate>> byEmail,
      Map<String, List<SlackUserCandidate>> byEmailLocal,
      Map<String, List<SlackUserCandidate>> byUsername) {
    String username = normalizeKey(mojitoUser != null ? mojitoUser.getUsername() : null);
    if (isBlank(username)) {
      return null;
    }

    List<List<SlackUserCandidate>> candidateGroups = new ArrayList<>();
    if (username.contains("@")) {
      candidateGroups.add(byEmail.getOrDefault(username, List.of()));
    }
    candidateGroups.add(byEmailLocal.getOrDefault(username, List.of()));
    candidateGroups.add(byUsername.getOrDefault(username, List.of()));

    for (List<SlackUserCandidate> group : candidateGroups) {
      if (group == null || group.isEmpty()) {
        continue;
      }
      List<SlackUserCandidate> unique =
          group.stream()
              .filter(candidate -> candidate != null && !isBlank(candidate.slackUserId()))
              .collect(
                  Collectors.collectingAndThen(
                      Collectors.toMap(
                          candidate -> normalizeKey(candidate.slackUserId()),
                          candidate -> candidate,
                          (left, right) -> left,
                          LinkedHashMap::new),
                      map -> new ArrayList<>(map.values())));
      if (unique.size() == 1) {
        return unique.get(0);
      }
      if (unique.size() > 1) {
        return null;
      }
    }
    return null;
  }

  private void addSlackCandidateIndex(
      Map<String, List<SlackUserCandidate>> index, String key, SlackUserCandidate candidate) {
    if (isBlank(key) || candidate == null) {
      return;
    }
    index.computeIfAbsent(key, ignored -> new ArrayList<>()).add(candidate);
  }

  private String normalizeKey(String value) {
    return value == null ? "" : value.trim().toLowerCase();
  }

  private record SlackUserCandidate(
      String slackUserId, String slackUsername, String usernameKey, String emailKey) {}

  private String buildReviewProjectAssignmentMessage(
      ReviewProject reviewProject,
      ReviewProjectAssignmentEventType eventType,
      String note,
      Map<Long, TeamService.TeamSlackUserMappingEntry> mappingsByUserId) {
    String localeTag =
        reviewProject.getLocale() != null ? reviewProject.getLocale().getBcp47Tag() : null;
    String requestName =
        reviewProject.getReviewProjectRequest() != null ? reviewProject.getReviewProjectRequest().getName() : null;

    StringBuilder builder = new StringBuilder();
    builder.append("Mojito review project ");
    builder.append(eventTypeLabel(eventType));
    builder.append(": #").append(reviewProject.getId());
    if (!isBlank(requestName)) {
      builder.append(" — ").append(requestName.trim());
    }
    if (!isBlank(localeTag)) {
      builder.append(" [").append(localeTag.trim()).append("]");
    }
    String projectLink = buildReviewProjectLink(reviewProject.getId());
    if (!isBlank(projectLink)) {
      builder.append("\nOpen: ").append(projectLink);
    }
    String normalizedNote = note == null ? null : note.trim();
    if (!isBlank(normalizedNote)) {
      builder.append("\nNote: ").append(normalizedNote);
    }

    appendUserLine(builder, "PM", reviewProject.getAssignedPmUser(), mappingsByUserId);
    appendUserLine(builder, "Translator", reviewProject.getAssignedTranslatorUser(), mappingsByUserId);

    return builder.toString();
  }

  private void appendUserLine(
      StringBuilder builder,
      String label,
      User user,
      Map<Long, TeamService.TeamSlackUserMappingEntry> mappingsByUserId) {
    builder.append("\n").append(label).append(": ");
    if (user == null) {
      builder.append("—");
      return;
    }
    if (user.getId() != null) {
      TeamService.TeamSlackUserMappingEntry mapping = mappingsByUserId.get(user.getId());
      if (mapping != null && !isBlank(mapping.slackUserId())) {
        builder.append("<@").append(mapping.slackUserId().trim()).append(">");
        return;
      }
    }
    String username = user.getUsername();
    builder.append(!isBlank(username) ? username.trim() : ("user#" + user.getId()));
  }

  private String eventTypeLabel(ReviewProjectAssignmentEventType eventType) {
    if (eventType == null) {
      return "assignment updated";
    }
    return switch (eventType) {
      case ASSIGNED, CREATED_DEFAULT -> "assigned";
      case REASSIGNED -> "reassigned";
      case UNASSIGNED -> "unassigned";
    };
  }

  private boolean isBlank(String value) {
    return value == null || value.trim().isEmpty();
  }

  private String buildReviewProjectLink(Long projectId) {
    String configuredServerUrl = serverConfig != null ? serverConfig.getUrl() : null;
    if (projectId == null || isBlank(configuredServerUrl)) {
      return null;
    }
    String base = configuredServerUrl.trim();
    while (base.endsWith("/")) {
      base = base.substring(0, base.length() - 1);
    }
    if (base.isEmpty()) {
      return null;
    }
    String url = base + "/n/review-projects/" + projectId;
    return "<" + url + "|review project #" + projectId + ">";
  }
}
