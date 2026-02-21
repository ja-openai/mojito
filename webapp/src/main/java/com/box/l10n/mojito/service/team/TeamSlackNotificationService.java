package com.box.l10n.mojito.service.team;

import com.box.l10n.mojito.entity.Team;
import com.box.l10n.mojito.entity.review.ReviewProject;
import com.box.l10n.mojito.entity.review.ReviewProjectAssignmentEventType;
import com.box.l10n.mojito.entity.security.user.User;
import com.box.l10n.mojito.slack.SlackClient;
import com.box.l10n.mojito.slack.SlackClientException;
import com.box.l10n.mojito.slack.SlackClients;
import com.box.l10n.mojito.slack.request.Message;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class TeamSlackNotificationService {

  private static final Logger logger = LoggerFactory.getLogger(TeamSlackNotificationService.class);

  private final TeamService teamService;
  private final SlackClients slackClients;

  public TeamSlackNotificationService(TeamService teamService, SlackClients slackClients) {
    this.teamService = teamService;
    this.slackClients = slackClients;
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

  private String buildReviewProjectAssignmentMessage(
      ReviewProject reviewProject,
      ReviewProjectAssignmentEventType eventType,
      String note,
      Map<Long, TeamService.TeamSlackUserMappingEntry> mappingsByUserId) {
    List<String> mentionTokens = new ArrayList<>();
    collectMentionToken(mentionTokens, reviewProject.getAssignedPmUser(), mappingsByUserId);
    collectMentionToken(mentionTokens, reviewProject.getAssignedTranslatorUser(), mappingsByUserId);

    String mentionPrefix =
        mentionTokens.isEmpty() ? "" : String.join(" ", new LinkedHashSet<>(mentionTokens)) + " ";

    String localeTag =
        reviewProject.getLocale() != null ? reviewProject.getLocale().getBcp47Tag() : null;
    String requestName =
        reviewProject.getReviewProjectRequest() != null ? reviewProject.getReviewProjectRequest().getName() : null;
    String teamName = reviewProject.getTeam() != null ? reviewProject.getTeam().getName() : null;

    StringBuilder builder = new StringBuilder();
    builder.append(mentionPrefix);
    builder.append("Mojito review project ");
    builder.append(eventTypeLabel(eventType));
    builder.append(": #").append(reviewProject.getId());
    if (!isBlank(requestName)) {
      builder.append(" — ").append(requestName.trim());
    }
    if (!isBlank(localeTag)) {
      builder.append(" [").append(localeTag.trim()).append("]");
    }
    if (!isBlank(teamName)) {
      builder.append("\nTeam: ").append(teamName.trim());
    }

    appendUserLine(builder, "PM", reviewProject.getAssignedPmUser());
    appendUserLine(builder, "Translator", reviewProject.getAssignedTranslatorUser());

    String normalizedNote = note == null ? null : note.trim();
    if (!isBlank(normalizedNote)) {
      builder.append("\nNote: ").append(normalizedNote);
    }

    return builder.toString();
  }

  private void collectMentionToken(
      List<String> mentionTokens,
      User user,
      Map<Long, TeamService.TeamSlackUserMappingEntry> mappingsByUserId) {
    if (user == null || user.getId() == null) {
      return;
    }
    TeamService.TeamSlackUserMappingEntry mapping = mappingsByUserId.get(user.getId());
    if (mapping == null || isBlank(mapping.slackUserId())) {
      return;
    }
    mentionTokens.add("<@" + mapping.slackUserId().trim() + ">");
  }

  private void appendUserLine(StringBuilder builder, String label, User user) {
    builder.append("\n").append(label).append(": ");
    if (user == null) {
      builder.append("—");
      return;
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
}
