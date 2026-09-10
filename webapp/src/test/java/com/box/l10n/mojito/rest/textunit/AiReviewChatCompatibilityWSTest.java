package com.box.l10n.mojito.rest.textunit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.rest.textunit.AiReviewChatWS.AiReviewChatMessage;
import com.box.l10n.mojito.rest.textunit.AiReviewChatWS.AiReviewChatRequest;
import com.box.l10n.mojito.rest.textunit.AiReviewChatWS.AiReviewChatResponse;
import com.box.l10n.mojito.service.oaireview.AiReviewChatJob;
import com.box.l10n.mojito.service.oaireview.AiReviewExecutionProperties;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.server.ResponseStatusException;

@RunWith(MockitoJUnitRunner.class)
public class AiReviewChatCompatibilityWSTest {

  @Mock AiReviewChatJobsWS jobs;
  @Mock ScheduledExecutorService statusChecks;
  @Mock ScheduledFuture<?> scheduled;

  private final ObjectMapper mapper = ObjectMapper.withNoFailOnUnknownProperties();
  private final AiReviewExecutionProperties execution = new AiReviewExecutionProperties();
  private final Authentication requester =
      new UsernamePasswordAuthenticationToken("requester", "unused", List.of());
  private AiReviewChatCompatibilityWS ws;
  private MockMvc mvc;

  @Before
  public void setUp() {
    ws = new AiReviewChatCompatibilityWS(jobs, execution, statusChecks);
    mvc = MockMvcBuilders.standaloneSetup(ws).build();
    SecurityContextHolder.getContext().setAuthentication(requester);
  }

  @After
  public void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  public void legacyRouteReturnsTheOriginalResponseThroughTheJobPath() throws Exception {
    prepareJob();
    when(jobs.get(91L))
        .thenReturn(new AiReviewChatJobsWS.StatusResponse("pending", null, null))
        .thenReturn(new AiReviewChatJobsWS.StatusResponse("completed", response(), null));

    MvcResult pending = postReview();
    Runnable poll = scheduledCheck();
    poll.run();
    verify(scheduled, never()).cancel(anyBoolean());
    poll.run();

    mvc.perform(asyncDispatch(pending))
        .andExpect(status().isOk())
        .andExpect(content().json(mapper.writeValueAsStringUnchecked(response())));
    verify(jobs).start(reviewRequest());
    verify(scheduled).cancel(false);
    verify(jobs, never()).cancel(91L);
  }

  @Test
  public void checksUseTheRequesterAndRestoreTheSchedulerThreadAuthentication() {
    prepareJob();
    when(jobs.get(91L))
        .thenAnswer(
            ignored -> {
              assertSame(requester, SecurityContextHolder.getContext().getAuthentication());
              return new AiReviewChatJobsWS.StatusResponse("completed", response(), null);
            });
    DeferredResult<AiReviewChatResponse> result = ws.chat(reviewRequest());
    Authentication unrelated =
        new UsernamePasswordAuthenticationToken("another user", "unused", List.of());
    SecurityContextHolder.getContext().setAuthentication(unrelated);

    scheduledCheck().run();

    assertEquals(response(), result.getResult());
    assertSame(unrelated, SecurityContextHolder.getContext().getAuthentication());
  }

  @Test
  public void providerFailureKeepsItsSafeStatusAndMessage() throws Exception {
    prepareJob();
    when(jobs.get(91L))
        .thenReturn(
            new AiReviewChatJobsWS.StatusResponse(
                "failed", null, new AiReviewChatJob.Error(504, "AI review request timed out.")));
    MvcResult pending = postReview();

    scheduledCheck().run();

    mvc.perform(asyncDispatch(pending))
        .andExpect(status().isGatewayTimeout())
        .andExpect(status().reason("AI review request timed out."));
    verify(scheduled).cancel(false);
    verify(jobs).cancel(91L);
  }

  @Test
  public void timeoutCancelsAsTheRequesterAndStopsFurtherChecks() throws Exception {
    execution.setTimeoutSeconds(75);
    prepareJob();
    doAnswer(
            ignored -> {
              assertSame(requester, SecurityContextHolder.getContext().getAuthentication());
              return null;
            })
        .when(jobs)
        .cancel(91L);
    MvcResult pending = postReview();
    MockAsyncContext context = (MockAsyncContext) pending.getRequest().getAsyncContext();
    assertEquals(105_000L, context.getTimeout());
    SecurityContextHolder.clearContext();

    for (AsyncListener listener : context.getListeners()) {
      listener.onTimeout(new AsyncEvent(context));
    }
    scheduledCheck().run();
    context.complete();

    ResponseStatusException failure = (ResponseStatusException) pending.getAsyncResult();
    assertEquals(HttpStatus.GATEWAY_TIMEOUT, failure.getStatusCode());
    verify(jobs).cancel(91L);
    verify(jobs, never()).get(91L);
    verify(scheduled).cancel(false);
    assertNull(SecurityContextHolder.getContext().getAuthentication());
  }

  @Test
  public void disconnectCancelsTheJobOnceEvenWhenCompletionAlsoRuns() throws Exception {
    prepareJob();
    MvcResult pending = postReview();
    MockAsyncContext context = (MockAsyncContext) pending.getRequest().getAsyncContext();
    for (AsyncListener listener : context.getListeners()) {
      listener.onError(new AsyncEvent(context, new IOException("client disconnected")));
    }
    context.complete();
    scheduledCheck().run();

    verify(jobs).cancel(91L);
    verify(jobs, never()).get(91L);
    verify(scheduled).cancel(false);
  }

  @Test
  public void completionWithoutAResultAlsoCancelsTheJob() throws Exception {
    prepareJob();
    MvcResult pending = postReview();

    ((MockAsyncContext) pending.getRequest().getAsyncContext()).complete();

    verify(jobs).cancel(91L);
    verify(scheduled).cancel(false);
  }

  @Test
  public void rejectedStartDoesNotScheduleStatusChecks() {
    ResponseStatusException denied = new ResponseStatusException(HttpStatus.NOT_FOUND);
    when(jobs.start(reviewRequest())).thenThrow(denied);

    assertSame(denied, assertThrows(ResponseStatusException.class, () -> ws.chat(reviewRequest())));
    verifyNoInteractions(statusChecks);
    verify(jobs, never()).cancel(91L);
  }

  @Test
  public void readFailureIsPreservedEvenWhenCancellationFails() {
    prepareJob();
    ResponseStatusException gone =
        new ResponseStatusException(HttpStatus.GONE, "AI review result is no longer available.");
    when(jobs.get(91L)).thenThrow(gone);
    doThrow(new IllegalStateException("database unavailable")).when(jobs).cancel(91L);
    DeferredResult<AiReviewChatResponse> result = ws.chat(reviewRequest());

    scheduledCheck().run();

    assertSame(gone, result.getResult());
    verify(scheduled).cancel(false);
  }

  @Test
  public void aResultBeforeScheduleReturnsStillCancelsTheScheduledHandle() {
    when(jobs.start(reviewRequest())).thenReturn(new AiReviewChatJobsWS.StartResponse(91L));
    when(jobs.get(91L))
        .thenReturn(new AiReviewChatJobsWS.StatusResponse("completed", response(), null));
    doAnswer(
            invocation -> {
              invocation.<Runnable>getArgument(0).run();
              return scheduled;
            })
        .when(statusChecks)
        .scheduleWithFixedDelay(any(Runnable.class), eq(0L), eq(2L), eq(TimeUnit.SECONDS));

    DeferredResult<AiReviewChatResponse> result = ws.chat(reviewRequest());

    assertEquals(response(), result.getResult());
    verify(scheduled).cancel(false);
  }

  @Test
  public void rejectedSchedulingCancelsTheAdmittedJob() {
    when(jobs.start(reviewRequest())).thenReturn(new AiReviewChatJobsWS.StartResponse(91L));
    when(statusChecks.scheduleWithFixedDelay(
            any(Runnable.class), eq(0L), eq(2L), eq(TimeUnit.SECONDS)))
        .thenThrow(new RejectedExecutionException("executor stopped"));

    DeferredResult<AiReviewChatResponse> result = ws.chat(reviewRequest());

    ResponseStatusException failure = (ResponseStatusException) result.getResult();
    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, failure.getStatusCode());
    assertEquals("AI review failed. Please retry.", failure.getReason());
    verify(jobs).cancel(91L);
  }

  @Test
  public void synchronousHelperCannotBeInvokedOverHttp() throws Exception {
    assertNull(
        AiReviewChatWS.class
            .getMethod("chat", AiReviewChatRequest.class)
            .getAnnotation(PostMapping.class));
  }

  @Test
  public void shutdownStopsTheStatusExecutor() {
    ws.stop();
    verify(statusChecks).shutdownNow();
  }

  private void prepareJob() {
    when(jobs.start(reviewRequest())).thenReturn(new AiReviewChatJobsWS.StartResponse(91L));
    doReturn(scheduled)
        .when(statusChecks)
        .scheduleWithFixedDelay(any(Runnable.class), eq(0L), eq(2L), eq(TimeUnit.SECONDS));
  }

  private Runnable scheduledCheck() {
    ArgumentCaptor<Runnable> check = ArgumentCaptor.forClass(Runnable.class);
    verify(statusChecks)
        .scheduleWithFixedDelay(check.capture(), eq(0L), eq(2L), eq(TimeUnit.SECONDS));
    return check.getValue();
  }

  private MvcResult postReview() throws Exception {
    return mvc.perform(
            post("/api/ai/review")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsStringUnchecked(reviewRequest())))
        .andExpect(request().asyncStarted())
        .andReturn();
  }

  private AiReviewChatRequest reviewRequest() {
    return new AiReviewChatRequest(
        "Account",
        "Compte",
        "fr",
        null,
        null,
        List.of(new AiReviewChatMessage("user", "Review the translation.")));
  }

  private AiReviewChatResponse response() {
    return new AiReviewChatResponse(
        new AiReviewChatMessage("assistant", "Keep the approved translation."), List.of(), null);
  }
}
