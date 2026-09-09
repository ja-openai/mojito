package com.box.l10n.mojito.service.oaireview;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.rest.textunit.AiReviewChatWS;
import com.box.l10n.mojito.rest.textunit.AiReviewChatWS.AiReviewChatMessage;
import com.box.l10n.mojito.rest.textunit.AiReviewChatWS.AiReviewChatRequest;
import com.box.l10n.mojito.rest.textunit.AiReviewChatWS.AiReviewChatResponse;
import com.box.l10n.mojito.service.oaireview.AiReviewInteractiveService.Prepared;
import com.box.l10n.mojito.service.oaireview.AiReviewInteractiveService.Settings;
import java.util.List;
import org.junit.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

public class AiReviewConfiguredChatJobTest {

  @Test
  public void workerUsesSerializedRequesterAndSettingsWithItsTaskId() {
    ObjectMapper mapper = ObjectMapper.withNoFailOnUnknownProperties();
    Prepared prepared =
        mapper.readValueUnchecked(mapper.writeValueAsStringUnchecked(prepared()), Prepared.class);
    assertEquals(Long.valueOf(17), prepared.userId());
    assertEquals("ultra", prepared.settings().profileId());
    assertEquals("selected-model", prepared.settings().modelName());
    assertEquals("max", prepared.settings().reasoningEffort());
    assertEquals("ultra", prepared.request().presetId());
    assertEquals("priority", prepared.settings().serviceTier());
    AiReviewConfiguredChatJob job = job();
    AiReviewChatResponse response =
        new AiReviewChatResponse(
            new AiReviewChatMessage("assistant", "Review result"), List.of(), null);
    when(job.aiReviewChatWS.chatPrepared(prepared, 91L)).thenReturn(response);

    AiReviewChatJob.Result result = job.call(prepared);

    assertSame(response, result.response());
    assertNull(result.error());
    verify(job.aiReviewChatWS).chatPrepared(prepared, 91L);
    verifyNoMoreInteractions(job.aiReviewChatWS);
  }

  @Test
  public void workerKeepsExpectedProviderFailuresInTheResult() {
    for (HttpStatus status : List.of(HttpStatus.BAD_GATEWAY, HttpStatus.GATEWAY_TIMEOUT)) {
      AiReviewConfiguredChatJob job = job();
      when(job.aiReviewChatWS.chatPrepared(prepared(), 91L))
          .thenThrow(new ResponseStatusException(status, "AI review is temporarily unavailable."));

      AiReviewChatJob.Result result = job.call(prepared());

      assertNull(result.response());
      assertEquals(status.value(), result.error().status());
      assertEquals("AI review is temporarily unavailable.", result.error().message());
    }
  }

  @Test
  public void providerFailureWithoutReasonUsesTheSafeFallback() {
    AiReviewConfiguredChatJob job = job();
    when(job.aiReviewChatWS.chatPrepared(prepared(), 91L))
        .thenThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY));

    AiReviewChatJob.Result result = job.call(prepared());

    assertNull(result.response());
    assertEquals(502, result.error().status());
    assertEquals("AI review failed. Please retry.", result.error().message());
  }

  @Test
  public void unexpectedFailuresReachQuartzFailureTracking() {
    AiReviewConfiguredChatJob job = job();
    IllegalStateException failure = new IllegalStateException("unexpected internal failure");
    when(job.aiReviewChatWS.chatPrepared(prepared(), 91L)).thenThrow(failure);

    assertSame(failure, assertThrows(IllegalStateException.class, () -> job.call(prepared())));
  }

  private AiReviewConfiguredChatJob job() {
    AiReviewConfiguredChatJob job = new AiReviewConfiguredChatJob();
    job.aiReviewChatWS = mock(AiReviewChatWS.class);
    PollableTask task = new PollableTask();
    task.setId(91L);
    ReflectionTestUtils.setField(job, "currentPollableTask", task);
    return job;
  }

  private Prepared prepared() {
    AiReviewChatRequest request =
        new AiReviewChatRequest(
            "Source",
            "Target",
            "fr",
            "Description",
            42L,
            List.of(new AiReviewChatMessage("user", "Review this translation.")),
            null,
            "manual",
            "review_project",
            null,
            "ultra");
    return new Prepared(
        request, 17L, new Settings("ultra", "selected-model", "max", "low", "priority"));
  }
}
