package com.box.l10n.mojito.rest.textunit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.quartz.QuartzJobInfo;
import com.box.l10n.mojito.quartz.QuartzPollableTaskScheduler;
import com.box.l10n.mojito.rest.textunit.AiReviewChatWS.AiReviewChatMessage;
import com.box.l10n.mojito.rest.textunit.AiReviewChatWS.AiReviewChatRequest;
import com.box.l10n.mojito.rest.textunit.AiReviewChatWS.AiReviewChatResponse;
import com.box.l10n.mojito.service.oaireview.AiReviewChatJob;
import com.box.l10n.mojito.service.oaireview.AiReviewChatJobAccess;
import com.box.l10n.mojito.service.oaireview.AiReviewConfigurationProperties;
import com.box.l10n.mojito.service.oaireview.AiReviewConfiguredChatJob;
import com.box.l10n.mojito.service.oaireview.AiReviewInteractiveService.Prepared;
import com.box.l10n.mojito.service.oaireview.AiReviewInteractiveService.Settings;
import com.box.l10n.mojito.service.pollableTask.PollableFuture;
import com.box.l10n.mojito.service.pollableTask.PollableTaskBlobStorage;
import com.box.l10n.mojito.service.pollableTask.PollableTaskService;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

@RunWith(MockitoJUnitRunner.class)
public class AiReviewChatJobsWSTest {

  @Mock AiReviewChatWS review;
  @Mock QuartzPollableTaskScheduler scheduler;
  @Mock PollableTaskService tasks;
  @Mock PollableTaskBlobStorage storage;
  @Mock AiReviewChatJobAccess access;

  private final ObjectMapper mapper = ObjectMapper.withNoFailOnUnknownProperties();
  private AiReviewChatJobsWS ws;

  @Before
  public void setUp() {
    AiReviewConfigurationProperties configuration = new AiReviewConfigurationProperties();
    configuration.setSchedulerName("review-queue");
    ws = new AiReviewChatJobsWS(review, configuration, scheduler, tasks, storage, access, mapper);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void startReturns202AndQueuesTheAuthenticatedRequesterAndFrozenSettings()
      throws Exception {
    AiReviewChatRequest request = request();
    Prepared prepared =
        new Prepared(
            request, 17L, new Settings("ultra", "selected-model", "max", "low", "priority"));
    when(review.prepare(request)).thenReturn(prepared);
    PollableFuture<AiReviewChatJob.Result> future = mock(PollableFuture.class);
    when(future.getPollableTask()).thenReturn(task());
    when(scheduler.scheduleJob(any(QuartzJobInfo.class))).thenReturn(future);

    MockMvcBuilders.standaloneSetup(ws)
        .build()
        .perform(
            post("/api/ai/review/jobs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsStringUnchecked(request)))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.taskId").value(91));

    ArgumentCaptor<QuartzJobInfo> scheduled = ArgumentCaptor.forClass(QuartzJobInfo.class);
    verify(scheduler).scheduleJob(scheduled.capture());
    assertSame(prepared, scheduled.getValue().getInput());
    assertEquals(
        prepared,
        mapper.readValueUnchecked(
            mapper.writeValueAsStringUnchecked(scheduled.getValue().getInput()), Prepared.class));
    assertEquals(AiReviewConfiguredChatJob.class, scheduled.getValue().getClazz());
    assertEquals("review-queue", scheduled.getValue().getScheduler());
    assertTrue(scheduled.getValue().isInlineInput());
    assertTrue(scheduled.getValue().getRequestRecovery());
    verify(review).prepare(request);
    verify(review, never()).chat(any());
    verify(review, never()).chatPrepared(any(), any());
    verify(future, never()).get();
  }

  @Test
  public void invalidRequestNeverSchedulesWork() {
    for (AiReviewChatRequest request :
        List.of(
            new AiReviewChatRequest("source", null, "fr", null, null, null),
            new AiReviewChatRequest("source", null, "fr", null, null, List.of()))) {
      assertEquals(
          HttpStatus.BAD_REQUEST,
          assertThrows(ResponseStatusException.class, () -> ws.start(request)).getStatusCode());
    }
    assertThrows(ResponseStatusException.class, () -> ws.start(null));
    verifyNoInteractions(review, scheduler);
  }

  @Test
  public void missingProviderIsDetectedBeforeScheduling() {
    doThrow(new IllegalStateException("provider missing")).when(review).prepare(request());
    assertThrows(IllegalStateException.class, () -> ws.start(request()));
    verifyNoInteractions(scheduler);
  }

  @Test
  public void rejectedPreparationNeverSchedulesWork() {
    ResponseStatusException invalid =
        new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown AI review version.");
    when(review.prepare(request())).thenThrow(invalid);
    assertSame(invalid, assertThrows(ResponseStatusException.class, () -> ws.start(request())));
    verifyNoInteractions(scheduler);
  }

  @Test
  public void pendingDoesNotReadOutput() {
    for (String jobName : reviewJobNames()) {
      PollableTask task = task();
      task.setName(jobName);
      when(tasks.getPollableTask(91L)).thenReturn(task);
      AiReviewChatJobsWS.StatusResponse response = ws.get(91L);
      assertEquals("pending", response.status());
      assertNull(response.response());
      verify(access).assertCanRead(task);
    }
    verifyNoInteractions(storage);
  }

  @Test
  public void completedResponseIsReadFromSharedStorage() {
    PollableTask task = task();
    task.setFinishedDate(ZonedDateTime.now());
    when(tasks.getPollableTask(91L)).thenReturn(task);
    AiReviewChatResponse reviewResponse =
        new AiReviewChatResponse(
            new AiReviewChatMessage("assistant", "Keep the approved translation."),
            List.of(),
            null);
    when(storage.findOutputJson(91L))
        .thenReturn(
            Optional.of(
                mapper.writeValueAsStringUnchecked(
                    new AiReviewChatJob.Result(reviewResponse, null))));

    AiReviewChatJobsWS.StatusResponse response = ws.get(91L);
    assertEquals("completed", response.status());
    assertEquals(reviewResponse, response.response());
    assertNull(response.error());
    verifyNoInteractions(review, scheduler);
  }

  @Test
  public void providerFailureKeepsItsStatusAndSafeMessage() {
    PollableTask task = task();
    task.setFinishedDate(ZonedDateTime.now());
    when(tasks.getPollableTask(91L)).thenReturn(task);
    AiReviewChatJob.Error error = new AiReviewChatJob.Error(504, "AI review request timed out.");
    when(storage.findOutputJson(91L))
        .thenReturn(
            Optional.of(
                mapper.writeValueAsStringUnchecked(new AiReviewChatJob.Result(null, error))));

    AiReviewChatJobsWS.StatusResponse response = ws.get(91L);
    assertEquals("failed", response.status());
    assertEquals(error, response.error());
    assertNull(response.response());
  }

  @Test
  public void missingAndOtherTaskTypesCannotBePolledAsReview() {
    assertEquals(
        HttpStatus.NOT_FOUND,
        assertThrows(ResponseStatusException.class, () -> ws.get(404L)).getStatusCode());
    PollableTask otherTask = task();
    otherTask.setName("another job");
    when(tasks.getPollableTask(91L)).thenReturn(otherTask);
    assertEquals(
        HttpStatus.NOT_FOUND,
        assertThrows(ResponseStatusException.class, () -> ws.get(91L)).getStatusCode());
    verifyNoInteractions(storage, access);
  }

  @Test
  public void ownershipIsCheckedBeforeReadingStoredOutput() {
    for (String jobName : reviewJobNames()) {
      PollableTask task = task();
      task.setName(jobName);
      task.setFinishedDate(ZonedDateTime.now());
      when(tasks.getPollableTask(91L)).thenReturn(task);
      ResponseStatusException denied = new ResponseStatusException(HttpStatus.NOT_FOUND);
      doThrow(denied).when(access).assertCanRead(task);
      assertSame(denied, assertThrows(ResponseStatusException.class, () -> ws.get(91L)));
    }
    verifyNoInteractions(storage);
  }

  @Test
  public void expiredOutputReturnsGone() {
    PollableTask task = task();
    task.setFinishedDate(ZonedDateTime.now());
    when(tasks.getPollableTask(91L)).thenReturn(task);
    when(storage.findOutputJson(91L)).thenReturn(Optional.empty());
    assertEquals(
        HttpStatus.GONE,
        assertThrows(ResponseStatusException.class, () -> ws.get(91L)).getStatusCode());
  }

  @Test
  public void crashedWorkerDoesNotExposeItsStackTrace() {
    PollableTask task = task();
    task.setErrorMessage("private provider details");
    task.setFinishedDate(ZonedDateTime.now());
    when(tasks.getPollableTask(91L)).thenReturn(task);
    assertEquals(
        new AiReviewChatJob.Error(500, "AI review failed. Please retry."), ws.get(91L).error());
    verifyNoInteractions(storage);
  }

  @Test
  public void abandonedTaskHasBoundedPendingTime() {
    PollableTask task = task();
    task.setCreatedDate(ZonedDateTime.now().minusHours(2));
    task.setTimeout(3600L);
    when(tasks.getPollableTask(91L)).thenReturn(task);
    AiReviewChatJobsWS.StatusResponse response = ws.get(91L);
    assertEquals("failed", response.status());
    assertEquals(504, response.error().status());
    verifyNoInteractions(storage);
  }

  private PollableTask task() {
    PollableTask task = new PollableTask();
    task.setId(91L);
    task.setName(AiReviewConfiguredChatJob.class.getCanonicalName());
    task.setCreatedDate(ZonedDateTime.now());
    task.setTimeout(3600L);
    return task;
  }

  private AiReviewChatRequest request() {
    return new AiReviewChatRequest(
        "Account",
        "الحساب",
        "ar",
        "Menu label",
        31L,
        List.of(
            new AiReviewChatMessage("user", "Glossary: Account = الحساب"),
            new AiReviewChatMessage("user", "Review the translation.")),
        null,
        "follow_up",
        "review_project",
        null,
        "ultra");
  }

  private List<String> reviewJobNames() {
    return List.of(
        AiReviewChatJob.class.getCanonicalName(),
        AiReviewConfiguredChatJob.class.getCanonicalName());
  }
}
