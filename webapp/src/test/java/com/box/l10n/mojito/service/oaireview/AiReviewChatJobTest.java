package com.box.l10n.mojito.service.oaireview;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.rest.textunit.AiReviewChatWS;
import com.box.l10n.mojito.rest.textunit.AiReviewChatWS.AiReviewChatMessage;
import com.box.l10n.mojito.rest.textunit.AiReviewChatWS.AiReviewChatRequest;
import com.box.l10n.mojito.rest.textunit.AiReviewChatWS.AiReviewChatResponse;
import java.util.List;
import org.junit.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public class AiReviewChatJobTest {

  @Test
  public void workerUsesTheExistingReviewWithTheCompleteRequest() {
    AiReviewChatJob job = job();
    AiReviewChatRequest request = request();
    AiReviewChatResponse response =
        new AiReviewChatResponse(
            new AiReviewChatMessage("assistant", "Review result"), List.of(), null);
    when(job.aiReviewChatWS.chat(request)).thenReturn(response);
    AiReviewChatJob.Result result = job.call(request);
    assertSame(response, result.response());
    assertNull(result.error());
    verify(job.aiReviewChatWS).chat(request);
  }

  @Test
  public void workerKeepsExpectedProviderFailuresInTheResult() {
    for (HttpStatus status : List.of(HttpStatus.BAD_GATEWAY, HttpStatus.GATEWAY_TIMEOUT)) {
      AiReviewChatJob job = job();
      when(job.aiReviewChatWS.chat(request()))
          .thenThrow(
              new ResponseStatusException(
                  status, "AI review is temporarily unavailable. Please retry."));
      AiReviewChatJob.Result result = job.call(request());
      assertNull(result.response());
      assertEquals(status.value(), result.error().status());
      assertEquals("AI review is temporarily unavailable. Please retry.", result.error().message());
    }
  }

  @Test
  public void unexpectedFailuresReachQuartzFailureTracking() {
    AiReviewChatJob job = job();
    IllegalStateException failure = new IllegalStateException("unexpected internal failure");
    when(job.aiReviewChatWS.chat(request())).thenThrow(failure);
    assertSame(failure, assertThrows(IllegalStateException.class, () -> job.call(request())));
  }

  private AiReviewChatJob job() {
    AiReviewChatJob job = new AiReviewChatJob();
    job.aiReviewChatWS = mock(AiReviewChatWS.class);
    return job;
  }

  private AiReviewChatRequest request() {
    return new AiReviewChatRequest(
        "Source",
        "Target",
        "fr",
        "Description",
        42L,
        List.of(
            new AiReviewChatMessage("user", "Glossary and warning context"),
            new AiReviewChatMessage("user", "Review this translation.")));
  }
}
