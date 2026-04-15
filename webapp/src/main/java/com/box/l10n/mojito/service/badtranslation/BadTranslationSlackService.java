package com.box.l10n.mojito.service.badtranslation;

import com.box.l10n.mojito.entity.review.ReviewProjectRequestSlackThread;
import com.box.l10n.mojito.service.review.ReviewProjectRequestRepository;
import com.box.l10n.mojito.service.review.ReviewProjectRequestSlackThreadRepository;
import com.box.l10n.mojito.service.team.TeamService;
import com.box.l10n.mojito.slack.SlackClient;
import com.box.l10n.mojito.slack.SlackClientException;
import com.box.l10n.mojito.slack.SlackClients;
import com.box.l10n.mojito.slack.request.Message;
import com.box.l10n.mojito.slack.response.ChatPostMessageResponse;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class BadTranslationSlackService {

  private static final String SOURCE_REVIEW_PROJECT_REQUEST_THREAD =
      "REVIEW_PROJECT_REQUEST_THREAD";
  private static final String SOURCE_TEAM_CHANNEL = "TEAM_CHANNEL";

  record SlackContext(
      BadTranslationSlackDestination destination,
      BadTranslationPersonRef translationAuthor,
      BadTranslationPersonRef reviewer,
      BadTranslationPersonRef owner,
      Long reviewProjectRequestId) {}

  private final TeamService teamService;
  private final SlackClients slackClients;
  private final ReviewProjectRequestSlackThreadRepository reviewProjectRequestSlackThreadRepository;
  private final ReviewProjectRequestRepository reviewProjectRequestRepository;

  public BadTranslationSlackService(
      TeamService teamService,
      SlackClients slackClients,
      ReviewProjectRequestSlackThreadRepository reviewProjectRequestSlackThreadRepository,
      ReviewProjectRequestRepository reviewProjectRequestRepository) {
    this.teamService = Objects.requireNonNull(teamService);
    this.slackClients = Objects.requireNonNull(slackClients);
    this.reviewProjectRequestSlackThreadRepository =
        Objects.requireNonNull(reviewProjectRequestSlackThreadRepository);
    this.reviewProjectRequestRepository = Objects.requireNonNull(reviewProjectRequestRepository);
  }

  public SlackContext buildSlackContext(
      BadTranslationPersonRef translationAuthor,
      BadTranslationReviewProjectCandidate reviewProjectCandidate) {
    if (reviewProjectCandidate == null || reviewProjectCandidate.teamId() == null) {
      return new SlackContext(
          unavailableDestination("No matching review project with team ownership was found"),
          translationAuthor,
          null,
          null,
          null);
    }

    Map<Long, TeamService.TeamSlackUserMappingEntry> mappingsByUserId = new HashMap<>();
    teamService
        .getTeamSlackUserMappings(reviewProjectCandidate.teamId())
        .forEach(mapping -> mappingsByUserId.put(mapping.mojitoUserId(), mapping));

    BadTranslationPersonRef enrichedAuthor = enrichPerson(translationAuthor, mappingsByUserId);
    BadTranslationPersonRef reviewer =
        enrichPerson(
            reviewProjectCandidate.reviewer() != null
                ? reviewProjectCandidate.reviewer()
                : reviewProjectCandidate.assignedTranslator(),
            mappingsByUserId);
    BadTranslationPersonRef owner =
        enrichPerson(
            reviewProjectCandidate.assignedPm() != null
                ? reviewProjectCandidate.assignedPm()
                : reviewProjectCandidate.requestCreator(),
            mappingsByUserId);

    BadTranslationSlackDestination destination =
        resolveDestination(
            reviewProjectCandidate.reviewProjectRequestId(), reviewProjectCandidate.teamId());

    return new SlackContext(
        destination,
        enrichedAuthor,
        reviewer,
        owner,
        reviewProjectCandidate.reviewProjectRequestId());
  }

  public BadTranslationSlackDispatch sendMessage(SlackContext slackContext, String text) {
    if (slackContext == null) {
      return new BadTranslationSlackDispatch(false, false, null, null, "Slack context is missing");
    }

    BadTranslationSlackDestination destination = slackContext.destination();
    if (destination == null || !destination.canSend()) {
      return new BadTranslationSlackDispatch(
          true,
          false,
          null,
          destination == null ? null : destination.threadTs(),
          destination == null ? "Slack destination is missing" : destination.note());
    }

    SlackClient slackClient = slackClients.getById(destination.slackClientId());
    if (slackClient == null) {
      return new BadTranslationSlackDispatch(
          true,
          false,
          null,
          destination.threadTs(),
          "Unknown Slack client: " + destination.slackClientId());
    }

    try {
      Message message = new Message();
      message.setChannel(destination.slackChannelId());
      message.setText(text);
      if (destination.threadTs() != null && !destination.threadTs().isBlank()) {
        message.setThreadTs(destination.threadTs());
      }

      ChatPostMessageResponse response = slackClient.sendInstantMessage(message);
      String messageTs = response == null ? null : response.getTs();
      if ((message.getThreadTs() == null || message.getThreadTs().isBlank())
          && slackContext.reviewProjectRequestId() != null
          && messageTs != null
          && !messageTs.isBlank()) {
        saveRequestSlackThread(
            slackContext.reviewProjectRequestId(),
            destination.slackClientId(),
            destination.slackChannelId(),
            messageTs);
      }

      return new BadTranslationSlackDispatch(
          true,
          true,
          messageTs,
          message.getThreadTs() == null || message.getThreadTs().isBlank()
              ? messageTs
              : message.getThreadTs(),
          message.getThreadTs() == null || message.getThreadTs().isBlank()
              ? "Slack message posted to channel"
              : "Slack message posted to existing thread");
    } catch (SlackClientException exception) {
      return new BadTranslationSlackDispatch(
          true,
          false,
          null,
          destination.threadTs(),
          exception.getMessage() == null ? "Failed to send Slack message" : exception.getMessage());
    }
  }

  private BadTranslationSlackDestination resolveDestination(Long requestId, Long teamId) {
    if (requestId != null) {
      ReviewProjectRequestSlackThread existingThread =
          reviewProjectRequestSlackThreadRepository
              .findByReviewProjectRequest_Id(requestId)
              .orElse(null);
      if (existingThread != null
          && !isBlank(existingThread.getSlackClientId())
          && !isBlank(existingThread.getSlackChannelId())
          && !isBlank(existingThread.getThreadTs())
          && slackClients.getById(existingThread.getSlackClientId().trim()) != null) {
        return new BadTranslationSlackDestination(
            SOURCE_REVIEW_PROJECT_REQUEST_THREAD,
            existingThread.getSlackClientId().trim(),
            existingThread.getSlackChannelId().trim(),
            existingThread.getThreadTs().trim(),
            true,
            "Will reply in the existing review project request Slack thread");
      }
    }

    TeamService.TeamSlackSettings teamSlackSettings = teamService.getTeamSlackSettings(teamId);
    if (!teamSlackSettings.enabled()) {
      return unavailableDestination("Team Slack notifications are disabled");
    }

    if (isBlank(teamSlackSettings.slackClientId()) || isBlank(teamSlackSettings.slackChannelId())) {
      return unavailableDestination("Team Slack client/channel is not configured");
    }

    if (slackClients.getById(teamSlackSettings.slackClientId()) == null) {
      return unavailableDestination("Configured team Slack client is unknown to Mojito");
    }

    return new BadTranslationSlackDestination(
        SOURCE_TEAM_CHANNEL,
        teamSlackSettings.slackClientId(),
        teamSlackSettings.slackChannelId(),
        null,
        true,
        "Will post into the team's configured Slack channel");
  }

  private BadTranslationPersonRef enrichPerson(
      BadTranslationPersonRef person,
      Map<Long, TeamService.TeamSlackUserMappingEntry> mappingsByUserId) {
    if (person == null) {
      return null;
    }

    TeamService.TeamSlackUserMappingEntry mapping =
        person.userId() == null ? null : mappingsByUserId.get(person.userId());
    if (mapping == null || mapping.slackUserId() == null || mapping.slackUserId().isBlank()) {
      return person;
    }

    String slackUserId = mapping.slackUserId().trim();
    return new BadTranslationPersonRef(
        person.userId(), person.username(), slackUserId, "<@" + slackUserId + ">");
  }

  private void saveRequestSlackThread(
      Long requestId, String slackClientId, String slackChannelId, String threadTs) {
    if (requestId == null
        || isBlank(slackClientId)
        || isBlank(slackChannelId)
        || isBlank(threadTs)) {
      return;
    }

    ReviewProjectRequestSlackThread thread =
        reviewProjectRequestSlackThreadRepository
            .findByReviewProjectRequest_Id(requestId)
            .orElseGet(ReviewProjectRequestSlackThread::new);
    thread.setReviewProjectRequest(reviewProjectRequestRepository.getReferenceById(requestId));
    thread.setSlackClientId(slackClientId.trim());
    thread.setSlackChannelId(slackChannelId.trim());
    thread.setThreadTs(threadTs.trim());
    reviewProjectRequestSlackThreadRepository.save(thread);
  }

  private BadTranslationSlackDestination unavailableDestination(String note) {
    return new BadTranslationSlackDestination("UNAVAILABLE", null, null, null, false, note);
  }

  private boolean isBlank(String value) {
    return value == null || value.trim().isEmpty();
  }
}
