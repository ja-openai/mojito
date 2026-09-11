package com.box.l10n.mojito.rest.textunit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.rest.textunit.AiReviewChatWS.AiReviewChatMessage;
import com.box.l10n.mojito.rest.textunit.AiReviewChatWS.AiReviewChatRequest;
import com.box.l10n.mojito.rest.textunit.AiReviewChatWS.AiReviewChatResponse;
import com.box.l10n.mojito.service.oaireview.AiReviewChatJob;
import com.box.l10n.mojito.service.oaireview.AiReviewChatJobAccess;
import com.box.l10n.mojito.service.oaireview.AiReviewConfiguredChatJob;
import com.box.l10n.mojito.service.oaireview.AiReviewDispatchService;
import com.box.l10n.mojito.service.oaireview.AiReviewExecutionProperties;
import com.box.l10n.mojito.service.oaireview.AiReviewInteractiveService.Prepared;
import com.box.l10n.mojito.service.oaireview.AiReviewInteractiveService.Settings;
import com.box.l10n.mojito.service.oaireview.AiReviewSubmissionRateObserver;
import com.box.l10n.mojito.service.pollableTask.PollableTaskBlobStorage;
import com.box.l10n.mojito.service.pollableTask.PollableTaskService;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

@RunWith(MockitoJUnitRunner.class)
public class AiReviewChatJobsWSTest {

  @Mock AiReviewChatWS review;
  @Mock PollableTaskService tasks;
  @Mock PollableTaskBlobStorage storage;
  @Mock AiReviewChatJobAccess access;
  @Mock AiReviewDispatchService dispatch;
  @Mock AiReviewSubmissionRateObserver submissions;

  private final ObjectMapper mapper = ObjectMapper.withNoFailOnUnknownProperties();
  private final AiReviewExecutionProperties execution = new AiReviewExecutionProperties();
  private AiReviewChatJobsWS ws;

  @Before
  public void setUp() {
    ws =
        new AiReviewChatJobsWS(
            review, tasks, storage, access, mapper, dispatch, execution, submissions);
  }

  @Test
  public void startReturns202AndDirectlyDispatchesTheAuthenticatedRequesterAndFrozenSettings()
      throws Exception {
    AiReviewChatRequest request = request();
    Prepared prepared =
        new Prepared(
            request, 17L, new Settings("ultra", "selected-model", "max", "low", "priority"));
    when(review.prepare(request)).thenReturn(prepared);
    when(tasks.createPollableTask(
            null, AiReviewConfiguredChatJob.class.getCanonicalName(), null, 0, 180L))
        .thenReturn(task());

    MockMvcBuilders.standaloneSetup(ws)
        .build()
        .perform(
            post("/api/ai/review/jobs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsStringUnchecked(request)))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.taskId").value(91));

    ArgumentCaptor<Prepared> dispatched = ArgumentCaptor.forClass(Prepared.class);
    verify(dispatch).start(eq(91L), dispatched.capture());
    assertSame(prepared, dispatched.getValue());
    assertEquals(
        prepared,
        mapper.readValueUnchecked(
            mapper.writeValueAsStringUnchecked(dispatched.getValue()), Prepared.class));
    verify(tasks)
        .createPollableTask(
            null, AiReviewConfiguredChatJob.class.getCanonicalName(), null, 0, 180L);
    verify(review).prepare(request);
    verify(submissions).observe(eq(17L), any(), anyLong());
    verify(review, never()).chat(any());
    verify(review, never()).chatPrepared(any(), any());
    verifyNoInteractions(storage, access);
  }

  @Test
  public void invalidRequestNeverCreatesOrDispatchesWork() {
    for (AiReviewChatRequest request :
        List.of(
            new AiReviewChatRequest("source", null, "fr", null, null, null),
            new AiReviewChatRequest("source", null, "fr", null, null, List.of()))) {
      assertEquals(
          HttpStatus.BAD_REQUEST,
          assertThrows(ResponseStatusException.class, () -> ws.start(request)).getStatusCode());
    }
    assertThrows(ResponseStatusException.class, () -> ws.start(null));
    verifyNoInteractions(review, tasks, dispatch, submissions);
  }

  @Test
  public void missingProviderIsDetectedBeforeCreatingTheTask() {
    doThrow(new IllegalStateException("provider missing")).when(review).prepare(request());
    assertThrows(IllegalStateException.class, () -> ws.start(request()));
    verifyNoInteractions(tasks, dispatch);
  }

  @Test
  public void rejectedPreparationNeverCreatesOrDispatchesWork() {
    ResponseStatusException invalid =
        new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown AI review version.");
    when(review.prepare(request())).thenThrow(invalid);
    assertSame(invalid, assertThrows(ResponseStatusException.class, () -> ws.start(request())));
    verifyNoInteractions(tasks, dispatch);
  }

  @Test
  public void taskPersistsTheEffectiveSpeedBudgetBeforeDispatch() {
    String[] presets = {"fastest", "fast", "balanced"};
    long[] budgets = {15, 20, 30};
    for (int i = 0; i < presets.length; i++) {
      Prepared prepared =
          new Prepared(
              request(), 17L, new Settings(presets[i], "selected-model", "low", "low", "priority"));
      when(review.prepare(request())).thenReturn(prepared);
      when(tasks.createPollableTask(
              null, AiReviewConfiguredChatJob.class.getCanonicalName(), null, 0, budgets[i]))
          .thenReturn(task());

      assertEquals(91L, ws.start(request()).taskId());

      InOrder order = inOrder(tasks, dispatch);
      order
          .verify(tasks)
          .createPollableTask(
              null, AiReviewConfiguredChatJob.class.getCanonicalName(), null, 0, budgets[i]);
      order.verify(dispatch).start(91L, prepared);
    }
  }

  @Test
  public void taskUsesTheConfiguredOverallDeadline() {
    execution.setTimeoutSeconds(75);
    Prepared prepared =
        new Prepared(
            request(), 17L, new Settings("ultra", "selected-model", "max", "low", "priority"));
    when(review.prepare(request())).thenReturn(prepared);
    when(tasks.createPollableTask(
            null, AiReviewConfiguredChatJob.class.getCanonicalName(), null, 0, 75L))
        .thenReturn(task());

    assertEquals(91L, ws.start(request()).taskId());
    verify(dispatch).start(91L, prepared);
  }

  @Test
  public void cancelChecksOwnershipAndReturnsNoContent() throws Exception {
    PollableTask task = task();
    when(tasks.getPollableTask(91L)).thenReturn(task);

    MockMvcBuilders.standaloneSetup(ws)
        .build()
        .perform(delete("/api/ai/review/jobs/91"))
        .andExpect(status().isNoContent());

    InOrder ownershipFirst = inOrder(access, dispatch);
    ownershipFirst.verify(access).assertCanRead(task);
    ownershipFirst.verify(dispatch).cancel(91L);
    verifyNoInteractions(storage, review);
  }

  @Test
  public void anotherUserCannotCancelTheTask() {
    PollableTask task = task();
    when(tasks.getPollableTask(91L)).thenReturn(task);
    ResponseStatusException denied = new ResponseStatusException(HttpStatus.NOT_FOUND);
    doThrow(denied).when(access).assertCanRead(task);

    assertSame(denied, assertThrows(ResponseStatusException.class, () -> ws.cancel(91L)));
    verifyNoInteractions(dispatch, storage, review);
  }

  @Test
  public void missingAndOtherTaskTypesCannotBeCanceledAsReview() {
    assertEquals(
        HttpStatus.NOT_FOUND,
        assertThrows(ResponseStatusException.class, () -> ws.cancel(404L)).getStatusCode());
    PollableTask otherTask = task();
    otherTask.setName("another job");
    when(tasks.getPollableTask(91L)).thenReturn(otherTask);
    assertEquals(
        HttpStatus.NOT_FOUND,
        assertThrows(ResponseStatusException.class, () -> ws.cancel(91L)).getStatusCode());
    verifyNoInteractions(dispatch, storage, access, review);
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
    verifyNoInteractions(review);
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
