package com.box.l10n.mojito.service.team;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.Locale;
import com.box.l10n.mojito.entity.Team;
import com.box.l10n.mojito.entity.review.ReviewProject;
import com.box.l10n.mojito.entity.review.ReviewProjectAssignmentEventType;
import com.box.l10n.mojito.entity.review.ReviewProjectRequest;
import com.box.l10n.mojito.entity.review.ReviewProjectRequestSlackThread;
import com.box.l10n.mojito.entity.review.ReviewProjectType;
import com.box.l10n.mojito.entity.security.user.User;
import com.box.l10n.mojito.service.review.ReviewProjectRepository;
import com.box.l10n.mojito.service.review.ReviewProjectRequestSlackThreadRepository;
import com.box.l10n.mojito.slack.SlackClient;
import com.box.l10n.mojito.slack.SlackClientException;
import com.box.l10n.mojito.slack.SlackClients;
import com.box.l10n.mojito.slack.request.Message;
import com.box.l10n.mojito.slack.response.ChatPostMessageResponse;
import com.box.l10n.mojito.utils.ServerConfig;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.web.client.RestClientException;

public class TeamSlackNotificationServiceTest {

  private final TeamService teamService = Mockito.mock(TeamService.class);
  private final SlackClients slackClients = Mockito.mock(SlackClients.class);
  private final ReviewProjectRequestSlackThreadRepository requestSlackThreadRepository =
      Mockito.mock(ReviewProjectRequestSlackThreadRepository.class);
  private final ReviewProjectRepository projectRepository =
      Mockito.mock(ReviewProjectRepository.class);
  private final ReviewProjectSlackAttachmentService attachmentService =
      Mockito.mock(ReviewProjectSlackAttachmentService.class);
  private final SlackClient slackClient = Mockito.mock(SlackClient.class);
  private final ServerConfig serverConfig = new ServerConfig();

  private TeamSlackNotificationService teamSlackNotificationService;

  @Before
  public void setUp() {
    serverConfig.setUrl("http://localhost:8080/");
    when(attachmentService.buildAttachmentSummary(any())).thenReturn("");
    teamSlackNotificationService =
        new TeamSlackNotificationService(
            teamService,
            slackClients,
            requestSlackThreadRepository,
            projectRepository,
            attachmentService,
            serverConfig,
            "America/Los_Angeles");
  }

  @Test
  public void createRequestNotificationStoresRootThread() throws Exception {
    ReviewProjectRequest request = reviewProjectRequest(44L, "Payments launch");
    ReviewProject project = reviewProject(91L, request, team(7L), "fr-FR");

    when(teamService.getTeamSlackSettings(7L))
        .thenReturn(new TeamService.TeamSlackSettings(true, "client-1", "channel-1"));
    when(teamService.getTeamSlackUserMappings(7L)).thenReturn(List.of());
    when(slackClients.getById("client-1")).thenReturn(slackClient);
    when(slackClient.sendInstantMessage(any(Message.class))).thenReturn(chatResponse("171.001"));
    when(requestSlackThreadRepository.findByReviewProjectRequest_Id(44L))
        .thenReturn(Optional.empty());

    teamSlackNotificationService.sendReviewProjectCreateRequestNotification(
        request, List.of(project));

    List<Message> messages = sentMessages(2);
    assertThat(messages.get(0).getChannel()).isEqualTo("channel-1");
    assertThat(messages.get(0).getThreadTs()).isNull();
    assertThat(messages.get(0).getText())
        .isEqualTo("*<http://localhost:8080/review-projects?requestId=44|Payments launch>*");
    assertThat(messages.get(0).getUnfurlLinks()).isFalse();
    assertThat(messages.get(0).getUnfurlMedia()).isFalse();
    assertThat(messages.get(1).getChannel()).isEqualTo("channel-1");
    assertThat(messages.get(1).getThreadTs()).isEqualTo("171.001");
    assertThat(messages.get(1).getText()).startsWith("*Review request details*\n");

    ArgumentCaptor<ReviewProjectRequestSlackThread> threadCaptor =
        ArgumentCaptor.forClass(ReviewProjectRequestSlackThread.class);
    verify(requestSlackThreadRepository).save(threadCaptor.capture());
    assertThat(threadCaptor.getValue().getReviewProjectRequest()).isSameAs(request);
    assertThat(threadCaptor.getValue().getSlackClientId()).isEqualTo("client-1");
    assertThat(threadCaptor.getValue().getSlackChannelId()).isEqualTo("channel-1");
    assertThat(threadCaptor.getValue().getThreadTs()).isEqualTo("171.001");

    InOrder deliveryOrder = inOrder(slackClient, requestSlackThreadRepository);
    deliveryOrder.verify(slackClient).sendInstantMessage(messages.get(0));
    deliveryOrder.verify(requestSlackThreadRepository).save(threadCaptor.getValue());
    deliveryOrder.verify(slackClient).sendInstantMessage(messages.get(1));
  }

  @Test
  public void createRequestNotificationSummarizesDescriptionLocalesAndTranslators()
      throws Exception {
    ReviewProjectRequest request =
        reviewProjectRequest(
            49L,
            "Checkout review",
            "Check wording in the checkout flow. Keep CTA language consistent across locales.");
    ReviewProject projectA = reviewProject(101L, request, team(7L), "fr-FR");
    ReviewProject projectB = reviewProject(102L, request, team(7L), "ca");
    ReviewProject projectC = reviewProject(103L, request, team(7L), "ca");
    projectA.setAssignedTranslatorUser(user(301L, "translator_fr1"));
    projectB.setAssignedTranslatorUser(user(302L, "translator_es2"));
    projectC.setAssignedTranslatorUser(user(302L, "translator_es2"));
    projectA.setAssignedPmUser(user(300L, "review_pm"));
    projectB.setAssignedPmUser(user(300L, "review_pm"));
    projectA.setType(ReviewProjectType.NORMAL);
    projectB.setType(ReviewProjectType.NORMAL);
    projectC.setType(ReviewProjectType.NORMAL);
    projectA.setDueDate(ZonedDateTime.parse("2026-09-10T17:00:00Z"));
    projectB.setDueDate(ZonedDateTime.parse("2026-09-10T17:00:00Z"));

    when(teamService.getTeamSlackSettings(7L))
        .thenReturn(new TeamService.TeamSlackSettings(true, "client-1", "channel-1"));
    when(teamService.getTeamSlackUserMappings(7L))
        .thenReturn(
            List.of(
                new TeamService.TeamSlackUserMappingEntry(
                    300L, "review_pm", "U_PM", null, null, null),
                new TeamService.TeamSlackUserMappingEntry(
                    301L, "translator_fr1", "U_TRANSLATOR", null, null, null)));
    when(slackClients.getById("client-1")).thenReturn(slackClient);
    when(slackClient.sendInstantMessage(any(Message.class))).thenReturn(chatResponse("171.010"));
    when(requestSlackThreadRepository.findByReviewProjectRequest_Id(49L))
        .thenReturn(Optional.empty());

    teamSlackNotificationService.sendReviewProjectCreateRequestNotification(
        request, List.of(projectA, projectB, projectC));

    List<Message> messages = sentMessages(2);
    assertThat(messages.get(0).getText())
        .isEqualTo(
            "*<http://localhost:8080/review-projects?requestId=49|Checkout review>* — Due: 2026-09-10 10:00 PDT");
    assertThat(messages.get(1).getThreadTs()).isEqualTo("171.010");
    assertThat(messages.get(1).getText())
        .isEqualTo(
            "*Review request details*\n"
                + "Request: <http://localhost:8080/review-projects?requestId=49|request #49>\n"
                + "Review words: 0 per locale · 0 total\n"
                + "Type: Normal\n"
                + "Due: 2026-09-10 10:00 PDT\n"
                + "Description: Check wording in the checkout flow. Keep CTA language consistent across locales.\n"
                + "Locales (2): ca, fr-FR\n"
                + "Assigned PMs: <@U_PM>\n"
                + "Assigned Translators: <@U_TRANSLATOR> (fr-FR), translator_es2 (ca)");
  }

  @Test
  public void createRequestNotificationShowsAutomationSourceAndAllLocales() throws Exception {
    ReviewProjectRequest request =
        reviewProjectRequest(
            50L, "Nightly web review", "Created by review automation Web nightly sweep (cron)");
    List<ReviewProject> projects =
        List.of(
            reviewProject(201L, request, team(7L), "am"),
            reviewProject(202L, request, team(7L), "bn"),
            reviewProject(203L, request, team(7L), "bs"),
            reviewProject(204L, request, team(7L), "ca"),
            reviewProject(205L, request, team(7L), "cs"),
            reviewProject(206L, request, team(7L), "da"),
            reviewProject(207L, request, team(7L), "de"),
            reviewProject(208L, request, team(7L), "de"));

    when(teamService.getTeamSlackSettings(7L))
        .thenReturn(new TeamService.TeamSlackSettings(true, "client-1", "channel-1"));
    when(teamService.getTeamSlackUserMappings(7L)).thenReturn(List.of());
    when(slackClients.getById("client-1")).thenReturn(slackClient);
    when(slackClient.sendInstantMessage(any(Message.class))).thenReturn(chatResponse("171.011"));
    when(requestSlackThreadRepository.findByReviewProjectRequest_Id(50L))
        .thenReturn(Optional.empty());

    teamSlackNotificationService.sendReviewProjectCreateRequestNotification(request, projects);

    List<Message> messages = sentMessages(2);
    assertThat(messages.get(0).getText())
        .isEqualTo("*<http://localhost:8080/review-projects?requestId=50|Nightly web review>*");
    assertThat(messages.get(1).getThreadTs()).isEqualTo("171.011");
    assertThat(messages.get(1).getText())
        .contains("Source: Automation — Web nightly sweep (cron)")
        .contains("Locales (7): am, bn, bs, ca, cs, da, de")
        .doesNotContain("Description:");
  }

  @Test
  public void assignmentNotificationRepliesInExistingRequestThread() throws Exception {
    ReviewProjectRequest request = reviewProjectRequest(45L, "Catalog refresh");
    ReviewProject project = reviewProject(92L, request, team(7L), "ja-JP");
    ReviewProjectRequestSlackThread requestThread =
        requestThread(request, "client-1", "channel-1", "171.002");

    when(teamService.getTeamSlackSettings(7L))
        .thenReturn(new TeamService.TeamSlackSettings(true, "client-1", "channel-1"));
    when(teamService.getTeamSlackUserMappings(7L)).thenReturn(List.of());
    when(slackClients.getById("client-1")).thenReturn(slackClient);
    when(slackClient.sendInstantMessage(any(Message.class))).thenReturn(chatResponse("171.003"));
    when(requestSlackThreadRepository.findByReviewProjectRequest_Id(45L))
        .thenReturn(Optional.of(requestThread));

    teamSlackNotificationService.sendReviewProjectAssignmentNotification(
        project, ReviewProjectAssignmentEventType.REASSIGNED, "Shift to APAC");

    ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
    verify(slackClient).sendInstantMessage(messageCaptor.capture());
    assertThat(messageCaptor.getValue().getThreadTs()).isEqualTo("171.002");
    assertThat(messageCaptor.getValue().getText())
        .contains("Mojito review project reassigned: #92 — Catalog refresh [ja-JP]")
        .contains("Note: Shift to APAC")
        .doesNotContain("View request in Mojito:");
    verify(requestSlackThreadRepository, never()).save(any(ReviewProjectRequestSlackThread.class));
  }

  @Test
  public void assignmentNotificationFallsBackToNewRootWhenDestinationChanges() throws Exception {
    ReviewProjectRequest request = reviewProjectRequest(46L, "Mobile QA");
    ReviewProject project = reviewProject(93L, request, team(7L), "de-DE");
    ReviewProjectRequestSlackThread requestThread =
        requestThread(request, "client-1", "old-channel", "171.004");

    when(teamService.getTeamSlackSettings(7L))
        .thenReturn(new TeamService.TeamSlackSettings(true, "client-1", "channel-2"));
    when(teamService.getTeamSlackUserMappings(7L)).thenReturn(List.of());
    when(slackClients.getById("client-1")).thenReturn(slackClient);
    when(slackClient.sendInstantMessage(any(Message.class))).thenReturn(chatResponse("171.005"));
    when(requestSlackThreadRepository.findByReviewProjectRequest_Id(46L))
        .thenReturn(Optional.of(requestThread), Optional.of(requestThread));

    teamSlackNotificationService.sendReviewProjectAssignmentNotification(
        project, ReviewProjectAssignmentEventType.REASSIGNED, null);

    List<Message> messages = sentMessages(2);
    assertThat(messages.get(0).getChannel()).isEqualTo("channel-2");
    assertThat(messages.get(0).getThreadTs()).isNull();
    assertThat(messages.get(0).getText())
        .isEqualTo("*<http://localhost:8080/review-projects?requestId=46|Mobile QA>*");
    assertThat(messages.get(1).getChannel()).isEqualTo("channel-2");
    assertThat(messages.get(1).getThreadTs()).isEqualTo("171.005");
    assertThat(messages.get(1).getText()).contains("Mojito review project reassigned: #93");

    ArgumentCaptor<ReviewProjectRequestSlackThread> threadCaptor =
        ArgumentCaptor.forClass(ReviewProjectRequestSlackThread.class);
    verify(requestSlackThreadRepository).save(threadCaptor.capture());
    assertThat(threadCaptor.getValue().getSlackChannelId()).isEqualTo("channel-2");
    assertThat(threadCaptor.getValue().getThreadTs()).isEqualTo("171.005");
  }

  @Test
  public void requestAssignmentNotificationRepliesInExistingRequestThread() throws Exception {
    ReviewProjectRequest request = reviewProjectRequest(47L, "Web refresh");
    ReviewProject projectA = reviewProject(94L, request, team(7L), "fr-FR");
    ReviewProject projectB = reviewProject(95L, request, team(7L), "fr-FR");
    ReviewProjectRequestSlackThread requestThread =
        requestThread(request, "client-1", "channel-1", "171.006");

    when(teamService.getTeamSlackSettings(7L))
        .thenReturn(new TeamService.TeamSlackSettings(true, "client-1", "channel-1"));
    when(teamService.getTeamSlackUserMappings(7L)).thenReturn(List.of());
    when(slackClients.getById("client-1")).thenReturn(slackClient);
    when(slackClient.sendInstantMessage(any(Message.class))).thenReturn(chatResponse("171.007"));
    when(requestSlackThreadRepository.findByReviewProjectRequest_Id(47L))
        .thenReturn(Optional.of(requestThread));

    teamSlackNotificationService.sendReviewProjectRequestAssignmentNotification(
        request, List.of(projectA, projectB));

    ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
    verify(slackClient).sendInstantMessage(messageCaptor.capture());
    assertThat(messageCaptor.getValue().getThreadTs()).isEqualTo("171.006");
    assertThat(messageCaptor.getValue().getText())
        .startsWith("*Review request details*\n")
        .contains("Locales (1): fr-FR")
        .doesNotContain("View request in Mojito:");
    verify(requestSlackThreadRepository, never()).save(any(ReviewProjectRequestSlackThread.class));
  }

  @Test
  public void createRequestNotificationRepliesInExistingRequestThread() throws Exception {
    ReviewProjectRequest request = reviewProjectRequest(48L, "Catalog refresh");
    ReviewProject project = reviewProject(96L, request, team(7L), "ja-JP");
    ReviewProjectRequestSlackThread requestThread =
        requestThread(request, "client-1", "channel-1", "171.008");

    when(teamService.getTeamSlackSettings(7L))
        .thenReturn(new TeamService.TeamSlackSettings(true, "client-1", "channel-1"));
    when(teamService.getTeamSlackUserMappings(7L)).thenReturn(List.of());
    when(slackClients.getById("client-1")).thenReturn(slackClient);
    when(slackClient.sendInstantMessage(any(Message.class))).thenReturn(chatResponse("171.009"));
    when(requestSlackThreadRepository.findByReviewProjectRequest_Id(48L))
        .thenReturn(Optional.of(requestThread));

    teamSlackNotificationService.sendReviewProjectCreateRequestNotification(
        request, List.of(project));

    ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
    verify(slackClient).sendInstantMessage(messageCaptor.capture());
    assertThat(messageCaptor.getValue().getThreadTs()).isEqualTo("171.008");
    assertThat(messageCaptor.getValue().getText())
        .startsWith("*Review request details*\n")
        .contains("Locales (1): ja-JP")
        .doesNotContain("View request in Mojito:");
    verify(requestSlackThreadRepository, never()).save(any(ReviewProjectRequestSlackThread.class));
  }

  @Test
  public void createRequestNotificationCreatesNewRootWhenClientChanges() throws Exception {
    ReviewProjectRequest request = reviewProjectRequest(51L, "Desktop review");
    ReviewProject project = reviewProject(209L, request, team(7L), "fr-FR");
    ReviewProjectRequestSlackThread previousThread =
        requestThread(request, "old-client", "channel-1", "171.012");
    configureTeamSlack();
    when(requestSlackThreadRepository.findByReviewProjectRequest_Id(51L))
        .thenReturn(Optional.of(previousThread));
    when(slackClient.sendInstantMessage(any(Message.class))).thenReturn(chatResponse("171.013"));

    teamSlackNotificationService.sendReviewProjectCreateRequestNotification(
        request, List.of(project));

    List<Message> messages = sentMessages(2);
    assertThat(messages.get(0).getThreadTs()).isNull();
    assertThat(messages.get(0).getText())
        .isEqualTo("*<http://localhost:8080/review-projects?requestId=51|Desktop review>*");
    assertThat(messages.get(1).getThreadTs()).isEqualTo("171.013");
    assertThat(messages.get(1).getText()).startsWith("*Review request details*\n");
    verify(requestSlackThreadRepository).save(previousThread);
    assertThat(previousThread.getSlackClientId()).isEqualTo("client-1");
    assertThat(previousThread.getSlackChannelId()).isEqualTo("channel-1");
    assertThat(previousThread.getThreadTs()).isEqualTo("171.013");
  }

  @Test
  public void assignmentWithoutExistingThreadCreatesCompactEmergencyRoot() throws Exception {
    ReviewProjectRequest request = reviewProjectRequest(52L, "Urgent checkout fix");
    ReviewProject project = reviewProject(210L, request, team(7L), "fr-FR");
    project.setType(ReviewProjectType.EMERGENCY);
    configureTeamSlack();
    when(slackClient.sendInstantMessage(any(Message.class))).thenReturn(chatResponse("171.014"));

    teamSlackNotificationService.sendReviewProjectAssignmentNotification(
        project, ReviewProjectAssignmentEventType.ASSIGNED, "Coverage needed");

    List<Message> messages = sentMessages(2);
    assertThat(messages.get(0).getThreadTs()).isNull();
    assertThat(messages.get(0).getText())
        .isEqualTo(
            "\uD83D\uDEA8 *<http://localhost:8080/review-projects?requestId=52|Urgent checkout fix>*");
    assertThat(messages.get(1).getThreadTs()).isEqualTo("171.014");
    assertThat(messages.get(1).getText())
        .startsWith("[EMERGENCY] Mojito review project assigned: #210")
        .contains("Note: Coverage needed");
  }

  @Test
  public void createRequestNotificationStopsWhenRootSendFails() throws Exception {
    ReviewProjectRequest request = reviewProjectRequest(53L, "Search review");
    ReviewProject project = reviewProject(211L, request, team(7L), "fr-FR");
    configureTeamSlack();
    when(slackClient.sendInstantMessage(any(Message.class)))
        .thenThrow(new SlackClientException("Unable to post root"));

    teamSlackNotificationService.sendReviewProjectCreateRequestNotification(
        request, List.of(project));

    Message root = sentMessages(1).get(0);
    assertThat(root.getThreadTs()).isNull();
    assertThat(root.getText())
        .startsWith("*<http://localhost:8080/review-projects?requestId=53|Search review>*");
    verify(requestSlackThreadRepository, never()).save(any(ReviewProjectRequestSlackThread.class));
  }

  @Test
  public void createRequestNotificationStopsWhenRootResponseIsMissing() throws Exception {
    assertMissingRootTimestampStopsDetails(null);
  }

  @Test
  public void createRequestNotificationStopsWhenRootTimestampIsMissing() throws Exception {
    assertMissingRootTimestampStopsDetails(chatResponse(null));
  }

  @Test
  public void createRequestNotificationStopsWhenRootTimestampIsBlank() throws Exception {
    assertMissingRootTimestampStopsDetails(chatResponse("  "));
  }

  @Test
  public void createRequestNotificationRetainsRootWhenDetailsFailAndReusesItOnRetry()
      throws Exception {
    assertDetailsFailureRetainsRoot(new SlackClientException("Unable to post details"));
  }

  @Test
  public void createRequestNotificationRetainsRootWhenDetailsTransportFailsAndReusesItOnRetry()
      throws Exception {
    assertDetailsFailureRetainsRoot(new RestClientException("Slack connection failed"));
  }

  private void assertDetailsFailureRetainsRoot(Exception failure) throws Exception {
    ReviewProjectRequest request = reviewProjectRequest(55L, "Settings review");
    ReviewProject project = reviewProject(213L, request, team(7L), "fr-FR");
    ReviewProjectRequestSlackThread savedThread =
        requestThread(request, "client-1", "channel-1", "171.015");
    configureTeamSlack();
    when(requestSlackThreadRepository.findByReviewProjectRequest_Id(55L))
        .thenReturn(Optional.empty(), Optional.empty(), Optional.of(savedThread));
    when(slackClient.sendInstantMessage(any(Message.class)))
        .thenReturn(chatResponse("171.015"))
        .thenThrow(failure)
        .thenReturn(chatResponse("171.016"));

    teamSlackNotificationService.sendReviewProjectCreateRequestNotification(
        request, List.of(project));
    teamSlackNotificationService.sendReviewProjectCreateRequestNotification(
        request, List.of(project));

    List<Message> messages = sentMessages(3);
    assertThat(messages.get(0).getThreadTs()).isNull();
    assertThat(messages.get(1).getThreadTs()).isEqualTo("171.015");
    assertThat(messages.get(2).getThreadTs()).isEqualTo("171.015");
    assertThat(messages.get(2).getText()).isEqualTo(messages.get(1).getText());

    ArgumentCaptor<ReviewProjectRequestSlackThread> threadCaptor =
        ArgumentCaptor.forClass(ReviewProjectRequestSlackThread.class);
    verify(requestSlackThreadRepository).save(threadCaptor.capture());
    assertThat(threadCaptor.getValue().getReviewProjectRequest()).isSameAs(request);
    assertThat(threadCaptor.getValue().getThreadTs()).isEqualTo("171.015");
    InOrder deliveryOrder = inOrder(slackClient, requestSlackThreadRepository);
    deliveryOrder.verify(slackClient).sendInstantMessage(messages.get(0));
    deliveryOrder.verify(requestSlackThreadRepository).save(threadCaptor.getValue());
    deliveryOrder.verify(slackClient).sendInstantMessage(messages.get(1));
  }

  @Test
  public void assignmentWithoutRequestRemainsStandalone() throws Exception {
    assertStandaloneAssignment(null);
  }

  @Test
  public void assignmentWithoutPersistedRequestRemainsStandalone() throws Exception {
    assertStandaloneAssignment(reviewProjectRequest(null, "Unsaved request"));
  }

  private void assertMissingRootTimestampStopsDetails(ChatPostMessageResponse response)
      throws Exception {
    ReviewProjectRequest request = reviewProjectRequest(54L, "Profile review");
    ReviewProject project = reviewProject(212L, request, team(7L), "fr-FR");
    configureTeamSlack();
    when(slackClient.sendInstantMessage(any(Message.class))).thenReturn(response);

    teamSlackNotificationService.sendReviewProjectCreateRequestNotification(
        request, List.of(project));

    Message root = sentMessages(1).get(0);
    assertThat(root.getThreadTs()).isNull();
    assertThat(root.getText())
        .startsWith("*<http://localhost:8080/review-projects?requestId=54|Profile review>*");
    verify(requestSlackThreadRepository, never()).save(any(ReviewProjectRequestSlackThread.class));
  }

  private void assertStandaloneAssignment(ReviewProjectRequest request) throws Exception {
    ReviewProject project = reviewProject(214L, request, team(7L), "fr-FR");
    configureTeamSlack();
    when(slackClient.sendInstantMessage(any(Message.class))).thenReturn(chatResponse("171.017"));

    teamSlackNotificationService.sendReviewProjectAssignmentNotification(
        project, ReviewProjectAssignmentEventType.ASSIGNED, "Direct assignment");

    Message message = sentMessages(1).get(0);
    assertThat(message.getChannel()).isEqualTo("channel-1");
    assertThat(message.getThreadTs()).isNull();
    assertThat(message.getText())
        .startsWith("Mojito review project assigned: #214")
        .contains("Note: Direct assignment")
        .contains("View project: <http://localhost:8080/review-projects/214|review project #214>");
    verifyNoInteractions(requestSlackThreadRepository);
  }

  @Test
  public void requestDetailsSumSplitWorkPerLocaleAndKeepAttachmentsInThread() throws Exception {
    ReviewProjectRequest request = reviewProjectRequest(61L, "Catalog & checkout\nreview");
    ReviewProject frA = reviewProject(221L, request, team(7L), "fr-FR");
    ReviewProject frB = reviewProject(222L, request, team(7L), "fr-FR");
    ReviewProject de = reviewProject(223L, request, team(7L), "de-DE");
    frA.setWordCount(700);
    frB.setWordCount(500);
    de.setWordCount(900);
    frA.setDueDate(ZonedDateTime.parse("2026-09-12T17:00:00Z"));
    de.setDueDate(ZonedDateTime.parse("2026-09-10T17:00:00Z"));
    configureTeamSlack();
    when(attachmentService.buildAttachmentSummary(61L))
        .thenReturn(
            "\nScreenshots / attachments: <http://localhost:8080/api/images/context.png|context.png>");
    when(slackClient.sendInstantMessage(any(Message.class))).thenReturn(chatResponse("171.021"));

    teamSlackNotificationService.sendReviewProjectCreateRequestNotification(
        request, List.of(frA, frB, de));

    List<Message> messages = sentMessages(2);
    assertThat(messages.get(0).getText())
        .isEqualTo(
            "*<http://localhost:8080/review-projects?requestId=61|Catalog &amp; checkout review>* — Earliest due: 2026-09-10 10:00 PDT");
    assertThat(messages.get(0).getText()).doesNotContain("attachments", "Review words");
    assertThat(messages.get(1).getThreadTs()).isEqualTo("171.021");
    assertThat(messages.get(1).getText())
        .contains(
            "Review words: 900–1,200 per locale · 2,100 total",
            "Screenshots / attachments:",
            "Locales (2): de-DE, fr-FR");
  }

  @Test
  public void changedDestinationUsesAllRequestDeadlinesForAssignmentParent() throws Exception {
    ReviewProjectRequest request = reviewProjectRequest(62L, "Regional review");
    ReviewProject fr = reviewProject(224L, request, team(7L), "fr-FR");
    ReviewProject de = reviewProject(225L, request, team(7L), "de-DE");
    fr.setDueDate(ZonedDateTime.parse("2026-09-12T17:00:00Z"));
    de.setDueDate(ZonedDateTime.parse("2026-09-10T17:00:00Z"));
    configureTeamSlack();
    when(projectRepository.findByRequestIdWithAssignment(62L)).thenReturn(List.of(fr, de));
    when(slackClient.sendInstantMessage(any(Message.class))).thenReturn(chatResponse("171.022"));

    teamSlackNotificationService.sendReviewProjectAssignmentNotification(
        fr, ReviewProjectAssignmentEventType.REASSIGNED, null);

    List<Message> messages = sentMessages(2);
    assertThat(messages.get(0).getText()).contains("Earliest due: 2026-09-10 10:00 PDT");
    assertThat(messages.get(1).getThreadTs()).isEqualTo("171.022");
  }

  private void configureTeamSlack() {
    when(teamService.getTeamSlackSettings(7L))
        .thenReturn(new TeamService.TeamSlackSettings(true, "client-1", "channel-1"));
    when(teamService.getTeamSlackUserMappings(7L)).thenReturn(List.of());
    when(slackClients.getById("client-1")).thenReturn(slackClient);
  }

  private List<Message> sentMessages(int count) throws SlackClientException {
    ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
    verify(slackClient, times(count)).sendInstantMessage(messageCaptor.capture());
    return messageCaptor.getAllValues();
  }

  private ReviewProjectRequest reviewProjectRequest(Long id, String name) {
    return reviewProjectRequest(id, name, null);
  }

  private ReviewProjectRequest reviewProjectRequest(Long id, String name, String notes) {
    ReviewProjectRequest request = new ReviewProjectRequest();
    request.setId(id);
    request.setName(name);
    request.setNotes(notes);
    return request;
  }

  private ReviewProject reviewProject(
      Long id, ReviewProjectRequest request, Team team, String localeBcp47Tag) {
    ReviewProject project = new ReviewProject();
    project.setId(id);
    project.setReviewProjectRequest(request);
    project.setTeam(team);

    Locale locale = new Locale();
    locale.setBcp47Tag(localeBcp47Tag);
    project.setLocale(locale);
    return project;
  }

  private Team team(Long id) {
    Team team = new Team();
    team.setId(id);
    return team;
  }

  private User user(Long id, String username) {
    User user = new User();
    user.setId(id);
    user.setUsername(username);
    return user;
  }

  private ReviewProjectRequestSlackThread requestThread(
      ReviewProjectRequest request, String clientId, String channelId, String threadTs) {
    ReviewProjectRequestSlackThread thread = new ReviewProjectRequestSlackThread();
    thread.setReviewProjectRequest(request);
    thread.setSlackClientId(clientId);
    thread.setSlackChannelId(channelId);
    thread.setThreadTs(threadTs);
    return thread;
  }

  private ChatPostMessageResponse chatResponse(String ts) {
    ChatPostMessageResponse response = new ChatPostMessageResponse();
    response.setOk(true);
    response.setTs(ts);
    return response;
  }
}
