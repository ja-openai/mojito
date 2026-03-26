package com.box.l10n.mojito.service.team;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.Locale;
import com.box.l10n.mojito.entity.Team;
import com.box.l10n.mojito.entity.review.ReviewProject;
import com.box.l10n.mojito.entity.review.ReviewProjectAssignmentEventType;
import com.box.l10n.mojito.entity.review.ReviewProjectRequest;
import com.box.l10n.mojito.entity.review.ReviewProjectRequestSlackThread;
import com.box.l10n.mojito.service.review.ReviewProjectRequestSlackThreadRepository;
import com.box.l10n.mojito.slack.SlackClient;
import com.box.l10n.mojito.slack.SlackClients;
import com.box.l10n.mojito.slack.request.Message;
import com.box.l10n.mojito.slack.response.ChatPostMessageResponse;
import com.box.l10n.mojito.utils.ServerConfig;
import java.util.List;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

public class TeamSlackNotificationServiceTest {

  private final TeamService teamService = Mockito.mock(TeamService.class);
  private final SlackClients slackClients = Mockito.mock(SlackClients.class);
  private final ReviewProjectRequestSlackThreadRepository requestSlackThreadRepository =
      Mockito.mock(ReviewProjectRequestSlackThreadRepository.class);
  private final SlackClient slackClient = Mockito.mock(SlackClient.class);
  private final ServerConfig serverConfig = new ServerConfig();

  private TeamSlackNotificationService teamSlackNotificationService;

  @Before
  public void setUp() {
    serverConfig.setUrl("http://localhost:8080/");
    teamSlackNotificationService =
        new TeamSlackNotificationService(
            teamService,
            slackClients,
            requestSlackThreadRepository,
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

    ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
    verify(slackClient).sendInstantMessage(messageCaptor.capture());
    assertThat(messageCaptor.getValue().getThreadTs()).isNull();

    ArgumentCaptor<ReviewProjectRequestSlackThread> threadCaptor =
        ArgumentCaptor.forClass(ReviewProjectRequestSlackThread.class);
    verify(requestSlackThreadRepository).save(threadCaptor.capture());
    assertThat(threadCaptor.getValue().getReviewProjectRequest()).isSameAs(request);
    assertThat(threadCaptor.getValue().getSlackClientId()).isEqualTo("client-1");
    assertThat(threadCaptor.getValue().getSlackChannelId()).isEqualTo("channel-1");
    assertThat(threadCaptor.getValue().getThreadTs()).isEqualTo("171.001");
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

    ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
    verify(slackClient).sendInstantMessage(messageCaptor.capture());
    assertThat(messageCaptor.getValue().getThreadTs()).isNull();

    ArgumentCaptor<ReviewProjectRequestSlackThread> threadCaptor =
        ArgumentCaptor.forClass(ReviewProjectRequestSlackThread.class);
    verify(requestSlackThreadRepository).save(threadCaptor.capture());
    assertThat(threadCaptor.getValue().getSlackChannelId()).isEqualTo("channel-2");
    assertThat(threadCaptor.getValue().getThreadTs()).isEqualTo("171.005");
  }

  private ReviewProjectRequest reviewProjectRequest(Long id, String name) {
    ReviewProjectRequest request = new ReviewProjectRequest();
    request.setId(id);
    request.setName(name);
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
