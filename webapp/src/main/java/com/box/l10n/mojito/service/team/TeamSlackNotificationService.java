package com.box.l10n.mojito.service.team;

import com.box.l10n.mojito.entity.Team;
import com.box.l10n.mojito.entity.review.ReviewAutomation;
import com.box.l10n.mojito.entity.review.ReviewProject;
import com.box.l10n.mojito.entity.review.ReviewProjectAssignmentEventType;
import com.box.l10n.mojito.entity.review.ReviewProjectRequest;
import com.box.l10n.mojito.entity.review.ReviewProjectRequestSlackThread;
import com.box.l10n.mojito.entity.review.ReviewProjectType;
import com.box.l10n.mojito.entity.security.user.User;
import com.box.l10n.mojito.service.review.ReviewAutomationCronSchedulerService;
import com.box.l10n.mojito.service.review.ReviewProjectRepository;
import com.box.l10n.mojito.service.review.ReviewProjectRequestSlackThreadRepository;
import com.box.l10n.mojito.slack.SlackClient;
import com.box.l10n.mojito.slack.SlackClientException;
import com.box.l10n.mojito.slack.SlackClients;
import com.box.l10n.mojito.slack.request.Message;
import com.box.l10n.mojito.slack.response.ChatPostMessageResponse;
import com.box.l10n.mojito.utils.ServerConfig;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

@Service
public class TeamSlackNotificationService {

  private static final Logger logger = LoggerFactory.getLogger(TeamSlackNotificationService.class);
  private static final String DEFAULT_DUE_DATE_TIME_ZONE_ID = "America/Los_Angeles";
  private static final String AUTOMATION_NOTES_PREFIX = "Created by review automation ";
  private static final int MAX_REQUEST_DESCRIPTION_LENGTH = 3000;
  private static final DateTimeFormatter SLACK_DUE_DATE_FORMATTER =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z");

  private final TeamService teamService;
  private final SlackClients slackClients;
  private final ReviewProjectRequestSlackThreadRepository reviewProjectRequestSlackThreadRepository;
  private final ReviewProjectRepository reviewProjectRepository;
  private final ReviewProjectSlackAttachmentService attachmentService;
  private final ServerConfig serverConfig;
  private final ZoneId reviewProjectDueDateTimeZone;

  public TeamSlackNotificationService(
      TeamService teamService,
      SlackClients slackClients,
      ReviewProjectRequestSlackThreadRepository reviewProjectRequestSlackThreadRepository,
      ReviewProjectRepository reviewProjectRepository,
      ReviewProjectSlackAttachmentService attachmentService,
      ServerConfig serverConfig,
      @Value(
              "${l10n.review-project.notifications.due-date-timezone:"
                  + DEFAULT_DUE_DATE_TIME_ZONE_ID
                  + "}")
          String reviewProjectDueDateTimeZoneId) {
    this.teamService = teamService;
    this.slackClients = slackClients;
    this.reviewProjectRequestSlackThreadRepository = reviewProjectRequestSlackThreadRepository;
    this.reviewProjectRepository = reviewProjectRepository;
    this.attachmentService = attachmentService;
    this.serverConfig = serverConfig;
    this.reviewProjectDueDateTimeZone = toDueDateTimeZoneOrDefault(reviewProjectDueDateTimeZoneId);
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
        getMappingsByUserId(team.getId());

    String text =
        buildReviewProjectAssignmentMessage(reviewProject, eventType, note, mappingsByUserId);
    if (isBlank(text)) {
      return;
    }

    try {
      ReviewProjectRequest request = reviewProject.getReviewProjectRequest();
      sendRequestSlackMessage(
          slackClient,
          settings,
          () -> {
            List<ReviewProject> projects =
                reviewProjectRepository.findByRequestIdWithAssignment(request.getId());
            return buildReviewProjectRequestHeader(
                request, projects.isEmpty() ? List.of(reviewProject) : projects);
          },
          text,
          request);
    } catch (SlackClientException | RestClientException ex) {
      logger.warn(
          "Failed to send review project assignment Slack notification for project {} team {}: {}",
          reviewProject.getId(),
          team.getId(),
          ex.getMessage(),
          ex);
    }
  }

  public void sendReviewProjectCreateRequestNotification(
      ReviewProjectRequest reviewProjectRequest, List<ReviewProject> createdProjects) {
    sendReviewProjectRequestSummaryNotification(reviewProjectRequest, createdProjects, "create");
  }

  public boolean sendReviewAutomationTriggerAlert(
      ReviewAutomation reviewAutomation,
      ReviewAutomationCronSchedulerService.TriggerHealth triggerHealth,
      String failureReason) {
    if (reviewAutomation == null || reviewAutomation.getId() == null) {
      return false;
    }

    Team team = reviewAutomation.getTeam();
    if (team == null || team.getId() == null) {
      return false;
    }

    TeamService.TeamSlackSettings settings = teamService.getTeamSlackSettings(team.getId());
    if (!settings.enabled()
        || isBlank(settings.slackClientId())
        || isBlank(settings.slackChannelId())) {
      return false;
    }

    SlackClient slackClient = slackClients.getById(settings.slackClientId());
    if (slackClient == null) {
      logger.warn(
          "Skipping review automation Slack alert: unknown Slack client id {} for team {}",
          settings.slackClientId(),
          team.getId());
      return false;
    }

    String text =
        buildReviewAutomationTriggerAlertMessage(reviewAutomation, triggerHealth, failureReason);
    if (isBlank(text)) {
      return false;
    }

    try {
      Message message = new Message();
      message.setChannel(settings.slackChannelId());
      message.setText(text);
      slackClient.sendInstantMessage(message);
      return true;
    } catch (SlackClientException ex) {
      logger.warn(
          "Failed to send review automation Slack alert for automation {} team {}: {}",
          reviewAutomation.getId(),
          team.getId(),
          ex.getMessage(),
          ex);
      return false;
    }
  }

  public void sendReviewProjectRequestAssignmentNotification(
      ReviewProjectRequest reviewProjectRequest, List<ReviewProject> requestProjects) {
    sendReviewProjectRequestSummaryNotification(
        reviewProjectRequest, requestProjects, "assignment");
  }

  private void sendReviewProjectRequestSummaryNotification(
      ReviewProjectRequest reviewProjectRequest,
      List<ReviewProject> projectsInput,
      String context) {
    if (reviewProjectRequest == null || projectsInput == null || projectsInput.isEmpty()) {
      return;
    }

    List<ReviewProject> projects =
        projectsInput.stream()
            .filter(project -> project != null && project.getId() != null)
            .toList();
    if (projects.isEmpty()) {
      return;
    }

    Team team =
        projects.stream()
            .map(ReviewProject::getTeam)
            .filter(t -> t != null && t.getId() != null)
            .findFirst()
            .orElse(null);
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
          "Skipping review project create Slack notification: unknown Slack client id {} for team {}",
          settings.slackClientId(),
          team.getId());
      return;
    }

    Map<Long, TeamService.TeamSlackUserMappingEntry> mappingsByUserId =
        getMappingsByUserId(team.getId());

    String text =
        buildReviewProjectRequestDetails(reviewProjectRequest, projects, mappingsByUserId);

    try {
      sendRequestSlackMessage(
          slackClient,
          settings,
          () -> buildReviewProjectRequestHeader(reviewProjectRequest, projects),
          text,
          reviewProjectRequest);
    } catch (SlackClientException | RestClientException ex) {
      logger.warn(
          "Failed to send review project {} Slack notification for request {} team {}: {}",
          context,
          reviewProjectRequest.getId(),
          team.getId(),
          ex.getMessage(),
          ex);
    }
  }

  private Map<Long, TeamService.TeamSlackUserMappingEntry> getMappingsByUserId(Long teamId) {
    if (teamId == null) {
      return new LinkedHashMap<>();
    }
    return teamService.getTeamSlackUserMappings(teamId).stream()
        .collect(Collectors.toMap(TeamService.TeamSlackUserMappingEntry::mojitoUserId, e -> e));
  }

  private String buildReviewProjectAssignmentMessage(
      ReviewProject reviewProject,
      ReviewProjectAssignmentEventType eventType,
      String note,
      Map<Long, TeamService.TeamSlackUserMappingEntry> mappingsByUserId) {
    String localeTag =
        reviewProject.getLocale() != null ? reviewProject.getLocale().getBcp47Tag() : null;
    String requestName =
        reviewProject.getReviewProjectRequest() != null
            ? reviewProject.getReviewProjectRequest().getName()
            : null;

    StringBuilder builder = new StringBuilder();
    if (isEmergencyType(reviewProject.getType())) {
      builder.append("[EMERGENCY] ");
    }
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
      builder.append("\nView project: ").append(projectLink);
    }
    String normalizedNote = note == null ? null : note.trim();
    if (!isBlank(normalizedNote)) {
      builder.append("\nNote: ").append(normalizedNote);
    }

    appendUserLine(builder, "PM", reviewProject.getAssignedPmUser(), mappingsByUserId);
    appendUserLine(
        builder, "Translator", reviewProject.getAssignedTranslatorUser(), mappingsByUserId);

    return builder.toString();
  }

  private String buildReviewAutomationTriggerAlertMessage(
      ReviewAutomation reviewAutomation,
      ReviewAutomationCronSchedulerService.TriggerHealth triggerHealth,
      String failureReason) {
    if (reviewAutomation == null || triggerHealth == null) {
      return null;
    }

    StringBuilder builder = new StringBuilder();
    builder.append(":warning: Mojito review automation trigger needs attention");
    builder.append("\nAutomation: #").append(reviewAutomation.getId());
    if (!isBlank(reviewAutomation.getName())) {
      builder.append(" — ").append(reviewAutomation.getName().trim());
    }
    builder.append("\nStatus: ").append(triggerHealth.status());
    if (!isBlank(triggerHealth.quartzState())) {
      builder.append(" (Quartz: ").append(triggerHealth.quartzState().trim()).append(")");
    }
    if (triggerHealth.nextRunAt() != null) {
      builder
          .append("\nNext run: ")
          .append(triggerHealth.nextRunAt().format(SLACK_DUE_DATE_FORMATTER));
    }
    if (!isBlank(failureReason)) {
      builder.append("\nReason: ").append(failureReason.trim());
    }
    String settingsLink = buildReviewAutomationLink(reviewAutomation.getId());
    if (!isBlank(settingsLink)) {
      builder.append("\nOpen automation: ").append(settingsLink);
    }
    return builder.toString();
  }

  private String buildReviewProjectRequestHeader(
      ReviewProjectRequest reviewProjectRequest, List<ReviewProject> projects) {
    String requestName = reviewProjectRequest != null ? reviewProjectRequest.getName() : null;
    Long requestId = reviewProjectRequest != null ? reviewProjectRequest.getId() : null;

    boolean emergency = projects.stream().anyMatch(project -> isEmergencyType(project.getType()));
    String title =
        !isBlank(requestName) ? normalizeWhitespace(requestName) : "Review request #" + requestId;
    String label = escapeSlackText(title).replace("|", " · ");
    String requestLink = buildReviewProjectRequestLink(requestId, label);
    String linkedTitle = requestLink == null ? label : requestLink;
    StringBuilder builder =
        new StringBuilder(emergency ? "🚨 " : "").append("*").append(linkedTitle).append("*");
    List<ZonedDateTime> dueDates =
        projects.stream()
            .map(ReviewProject::getDueDate)
            .filter(java.util.Objects::nonNull)
            .sorted(java.util.Comparator.comparing(ZonedDateTime::toInstant))
            .toList();
    if (!dueDates.isEmpty()) {
      boolean differentDates =
          dueDates.stream().map(ZonedDateTime::toInstant).distinct().count() > 1;
      builder
          .append(differentDates ? " — Earliest due: " : " — Due: ")
          .append(formatDueDate(dueDates.get(0)));
    }
    return builder.toString();
  }

  private String buildReviewProjectRequestDetails(
      ReviewProjectRequest reviewProjectRequest,
      List<ReviewProject> projects,
      Map<Long, TeamService.TeamSlackUserMappingEntry> mappingsByUserId) {
    StringBuilder builder = new StringBuilder("*Review request details*");
    String link = buildReviewProjectRequestLink(reviewProjectRequest.getId());
    builder.append("\nRequest: ").append(link == null ? "#" + reviewProjectRequest.getId() : link);
    appendWordCountLine(builder, projects);
    appendReviewProjectTypesSummaryLine(builder, projects);
    appendReviewProjectDueDatesSummaryLine(builder, projects);
    appendRequestContextSummaryLine(builder, reviewProjectRequest.getNotes());
    builder.append(attachmentService.buildAttachmentSummary(reviewProjectRequest.getId()));
    List<String> localeTags = collectDistinctLocaleTags(projects);
    builder.append("\nLocales");
    if (!localeTags.isEmpty()) {
      builder.append(" (").append(localeTags.size()).append(")");
    }
    builder.append(": ");
    builder.append(localeTags.isEmpty() ? "—" : String.join(", ", localeTags));

    appendDistinctUserSummaryLine(
        builder,
        "Assigned PMs",
        projects.stream().map(ReviewProject::getAssignedPmUser).toList(),
        mappingsByUserId);
    appendTranslatorSummaryLine(builder, projects, mappingsByUserId);

    return builder.toString();
  }

  private String escapeSlackText(String value) {
    return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }

  private void appendWordCountLine(StringBuilder builder, List<ReviewProject> projects) {
    Map<String, Long> wordsByLocale = new LinkedHashMap<>();
    for (ReviewProject project : projects) {
      if (project.getLocale() != null && !isBlank(project.getLocale().getBcp47Tag())) {
        wordsByLocale.merge(
            project.getLocale().getBcp47Tag(),
            project.getWordCount() == null ? 0L : Math.max(0L, project.getWordCount()),
            Long::sum);
      }
    }
    if (wordsByLocale.isEmpty()) {
      return;
    }
    long min = wordsByLocale.values().stream().mapToLong(Long::longValue).min().orElse(0);
    long max = wordsByLocale.values().stream().mapToLong(Long::longValue).max().orElse(0);
    builder.append("\nReview words: ").append(formatWordCount(min));
    if (min != max) {
      builder.append("–").append(formatWordCount(max));
    }
    if (wordsByLocale.size() > 1) {
      builder
          .append(" per locale · ")
          .append(formatWordCount(wordsByLocale.values().stream().mapToLong(Long::longValue).sum()))
          .append(" total");
    }
  }

  private String formatWordCount(long count) {
    return String.format(java.util.Locale.ROOT, "%,d", count);
  }

  private void appendReviewProjectTypesSummaryLine(
      StringBuilder builder, List<ReviewProject> projects) {
    Set<String> typeLabels = new LinkedHashSet<>();
    for (ReviewProject project : projects) {
      ReviewProjectType type = project != null ? project.getType() : null;
      if (type != null) {
        typeLabels.add(formatReviewProjectType(type));
      }
    }
    if (typeLabels.isEmpty()) {
      return;
    }
    builder.append("\n");
    builder.append(typeLabels.size() == 1 ? "Type: " : "Types: ");
    builder.append(String.join(", ", typeLabels));
  }

  private void appendReviewProjectDueDatesSummaryLine(
      StringBuilder builder, List<ReviewProject> projects) {
    Set<String> dueLabels = new LinkedHashSet<>();
    for (ReviewProject project : projects) {
      ZonedDateTime dueDate = project != null ? project.getDueDate() : null;
      if (dueDate != null) {
        dueLabels.add(formatDueDate(dueDate));
      }
    }
    if (dueLabels.isEmpty()) {
      return;
    }
    builder.append("\n");
    builder.append(dueLabels.size() == 1 ? "Due: " : "Due dates: ");
    builder.append(String.join(", ", dueLabels));
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

  private void appendDistinctUserSummaryLine(
      StringBuilder builder,
      String label,
      List<User> users,
      Map<Long, TeamService.TeamSlackUserMappingEntry> mappingsByUserId) {
    builder.append("\n").append(label).append(": ");
    if (users == null || users.isEmpty()) {
      builder.append("—");
      return;
    }

    Set<String> rendered = new LinkedHashSet<>();
    for (User user : users) {
      if (user == null) {
        continue;
      }
      rendered.add(renderUser(user, mappingsByUserId));
    }
    rendered.removeIf(this::isBlank);

    if (rendered.isEmpty()) {
      builder.append("—");
      return;
    }
    builder.append(String.join(", ", rendered));
  }

  private void appendTranslatorSummaryLine(
      StringBuilder builder,
      List<ReviewProject> projects,
      Map<Long, TeamService.TeamSlackUserMappingEntry> mappingsByUserId) {
    builder.append("\nAssigned Translators: ");
    if (projects == null || projects.isEmpty()) {
      builder.append("—");
      return;
    }

    Map<String, Set<String>> localeTagsByTranslator = new LinkedHashMap<>();
    for (ReviewProject project : projects) {
      if (project == null) {
        continue;
      }
      String translatorLabel =
          project.getAssignedTranslatorUser() == null
              ? "Unassigned"
              : renderUser(project.getAssignedTranslatorUser(), mappingsByUserId);
      localeTagsByTranslator.computeIfAbsent(translatorLabel, ignored -> new LinkedHashSet<>());

      String localeTag = project.getLocale() != null ? project.getLocale().getBcp47Tag() : null;
      if (!isBlank(localeTag)) {
        localeTagsByTranslator.get(translatorLabel).add(localeTag.trim());
      }
    }

    if (localeTagsByTranslator.isEmpty()) {
      builder.append("—");
      return;
    }

    List<String> rendered = new ArrayList<>();
    for (Map.Entry<String, Set<String>> entry : localeTagsByTranslator.entrySet()) {
      List<String> localeTags = sortLocaleTags(entry.getValue());
      if (localeTags.isEmpty()) {
        rendered.add(entry.getKey());
      } else {
        rendered.add(entry.getKey() + " (" + String.join(", ", localeTags) + ")");
      }
    }
    builder.append(String.join(", ", rendered));
  }

  private String renderUser(
      User user, Map<Long, TeamService.TeamSlackUserMappingEntry> mappingsByUserId) {
    if (user == null) {
      return "—";
    }
    if (user.getId() != null && mappingsByUserId != null) {
      TeamService.TeamSlackUserMappingEntry mapping = mappingsByUserId.get(user.getId());
      if (mapping != null && !isBlank(mapping.slackUserId())) {
        return "<@" + mapping.slackUserId().trim() + ">";
      }
    }
    String username = user.getUsername();
    if (!isBlank(username)) {
      return username.trim();
    }
    return user.getId() != null ? ("user#" + user.getId()) : "—";
  }

  private void appendRequestContextSummaryLine(StringBuilder builder, String requestNotes) {
    String automationSource = extractAutomationSource(requestNotes);
    if (!isBlank(automationSource)) {
      builder.append("\nSource: Automation");
      builder.append(" — ").append(automationSource);
      return;
    }

    if (!isBlank(requestNotes)) {
      builder
          .append("\nDescription: ")
          .append(escapeSlackText(abbreviate(requestNotes.trim(), MAX_REQUEST_DESCRIPTION_LENGTH)));
    }
  }

  private String extractAutomationSource(String requestNotes) {
    String normalized = normalizeWhitespace(requestNotes);
    if (isBlank(normalized) || !normalized.startsWith(AUTOMATION_NOTES_PREFIX)) {
      return null;
    }
    String source = normalized.substring(AUTOMATION_NOTES_PREFIX.length()).trim();
    return source.isEmpty() ? null : source;
  }

  private String normalizeWhitespace(String value) {
    return value == null ? null : value.trim().replaceAll("\\s+", " ");
  }

  private String abbreviate(String value, int maxLength) {
    if (isBlank(value) || value.length() <= maxLength) {
      return value;
    }
    if (maxLength <= 1) {
      return value.substring(0, maxLength);
    }
    return value.substring(0, maxLength - 1).trim() + "…";
  }

  private List<String> collectDistinctLocaleTags(List<ReviewProject> projects) {
    if (projects == null || projects.isEmpty()) {
      return List.of();
    }
    Set<String> localeTags = new LinkedHashSet<>();
    for (ReviewProject project : projects) {
      String localeTag =
          project != null && project.getLocale() != null ? project.getLocale().getBcp47Tag() : null;
      if (!isBlank(localeTag)) {
        localeTags.add(localeTag.trim());
      }
    }
    return sortLocaleTags(localeTags);
  }

  private List<String> sortLocaleTags(Set<String> localeTags) {
    return localeTags.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
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

  private boolean isEmergencyType(ReviewProjectType type) {
    return ReviewProjectType.EMERGENCY.equals(type);
  }

  private String formatReviewProjectType(ReviewProjectType type) {
    if (type == null) {
      return "Unknown";
    }
    String[] parts = type.name().split("_");
    List<String> words = new ArrayList<>();
    for (String part : parts) {
      if (part == null || part.isBlank()) {
        continue;
      }
      String lower = part.toLowerCase();
      words.add(Character.toUpperCase(lower.charAt(0)) + lower.substring(1));
    }
    return words.isEmpty() ? "Unknown" : String.join(" ", words);
  }

  private String formatDueDate(ZonedDateTime dueDate) {
    if (dueDate == null) {
      return "";
    }
    return SLACK_DUE_DATE_FORMATTER.format(
        dueDate.withZoneSameInstant(reviewProjectDueDateTimeZone));
  }

  private ZoneId toDueDateTimeZoneOrDefault(String zoneId) {
    String candidate = zoneId == null ? "" : zoneId.trim();
    if (candidate.isEmpty()) {
      return ZoneId.of(DEFAULT_DUE_DATE_TIME_ZONE_ID);
    }
    try {
      return ZoneId.of(candidate);
    } catch (DateTimeException ex) {
      logger.warn(
          "Invalid review project due date timezone '{}', falling back to {}",
          candidate,
          DEFAULT_DUE_DATE_TIME_ZONE_ID);
      return ZoneId.of(DEFAULT_DUE_DATE_TIME_ZONE_ID);
    }
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
    String url = base + "/review-projects/" + projectId;
    return "<" + url + "|review project #" + projectId + ">";
  }

  private String buildReviewProjectRequestLink(Long requestId) {
    return buildReviewProjectRequestLink(requestId, "request #" + requestId);
  }

  private String buildReviewProjectRequestLink(Long requestId, String label) {
    String configuredServerUrl = serverConfig != null ? serverConfig.getUrl() : null;
    if (requestId == null || isBlank(configuredServerUrl)) {
      return null;
    }
    String base = configuredServerUrl.trim();
    while (base.endsWith("/")) {
      base = base.substring(0, base.length() - 1);
    }
    if (base.isEmpty()) {
      return null;
    }
    String url = base + "/review-projects?requestId=" + requestId;
    return "<" + url + "|" + label + ">";
  }

  private String buildReviewAutomationLink(Long automationId) {
    String configuredServerUrl = serverConfig != null ? serverConfig.getUrl() : null;
    if (automationId == null || isBlank(configuredServerUrl)) {
      return null;
    }
    String base = configuredServerUrl.trim();
    while (base.endsWith("/")) {
      base = base.substring(0, base.length() - 1);
    }
    if (base.isEmpty()) {
      return null;
    }
    String url = base + "/settings/system/review-automations/" + automationId;
    return "<" + url + "|automation #" + automationId + ">";
  }

  private void sendRequestSlackMessage(
      SlackClient slackClient,
      TeamService.TeamSlackSettings settings,
      Supplier<String> header,
      String details,
      ReviewProjectRequest reviewProjectRequest)
      throws SlackClientException {
    Message message = new Message();
    message.setChannel(settings.slackChannelId());
    message.setText(details);

    if (reviewProjectRequest != null && reviewProjectRequest.getId() != null) {
      String threadTs =
          reviewProjectRequestSlackThreadRepository
              .findByReviewProjectRequest_Id(reviewProjectRequest.getId())
              .filter(thread -> matchesSlackDestination(thread, settings))
              .map(ReviewProjectRequestSlackThread::getThreadTs)
              .filter(ts -> !isBlank(ts))
              .orElse(null);
      if (isBlank(threadTs)) {
        Message root = new Message();
        root.setChannel(settings.slackChannelId());
        root.setText(header.get());
        root.setUnfurlLinks(false);
        root.setUnfurlMedia(false);
        ChatPostMessageResponse response = slackClient.sendInstantMessage(root);
        threadTs = response != null ? response.getTs() : null;
        if (isBlank(threadTs)) {
          throw new SlackClientException(
              "Slack did not return a timestamp for the request message");
        }
        // Keep the parent available for later updates even if posting the details fails.
        saveRequestSlackThread(reviewProjectRequest, settings, threadTs);
      }
      message.setThreadTs(threadTs);
    }

    slackClient.sendInstantMessage(message);
  }

  private boolean matchesSlackDestination(
      ReviewProjectRequestSlackThread thread, TeamService.TeamSlackSettings settings) {
    return thread != null
        && settings != null
        && !isBlank(thread.getSlackClientId())
        && !isBlank(thread.getSlackChannelId())
        && thread.getSlackClientId().trim().equals(settings.slackClientId().trim())
        && thread.getSlackChannelId().trim().equals(settings.slackChannelId().trim());
  }

  private void saveRequestSlackThread(
      ReviewProjectRequest reviewProjectRequest,
      TeamService.TeamSlackSettings settings,
      String threadTs) {
    if (reviewProjectRequest == null
        || reviewProjectRequest.getId() == null
        || settings == null
        || isBlank(settings.slackClientId())
        || isBlank(settings.slackChannelId())
        || isBlank(threadTs)) {
      return;
    }

    ReviewProjectRequestSlackThread thread =
        reviewProjectRequestSlackThreadRepository
            .findByReviewProjectRequest_Id(reviewProjectRequest.getId())
            .orElseGet(ReviewProjectRequestSlackThread::new);
    thread.setReviewProjectRequest(reviewProjectRequest);
    thread.setSlackClientId(settings.slackClientId().trim());
    thread.setSlackChannelId(settings.slackChannelId().trim());
    thread.setThreadTs(threadTs.trim());
    reviewProjectRequestSlackThreadRepository.save(thread);
  }
}
