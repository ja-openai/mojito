package com.box.l10n.mojito.rest.review;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.pollableTask.PollableFuture;
import com.box.l10n.mojito.service.review.CreateReviewProjectRequestCommand;
import com.box.l10n.mojito.service.review.CreateReviewProjectRequestResult;
import com.box.l10n.mojito.service.review.ReviewProjectService;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public class ReviewProjectWSCreateTest {

  private final ReviewProjectService reviewProjectService = mock(ReviewProjectService.class);
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final ReviewProjectWS reviewProjectWS =
      new ReviewProjectWS(reviewProjectService, null, objectMapper, null, null);

  @Test
  public void createForwardsOptionalWordLimit() throws Exception {
    PollableFuture<CreateReviewProjectRequestResult> future = mock(PollableFuture.class);
    PollableTask task = new PollableTask();
    task.setId(17L);
    when(future.getPollableTask()).thenReturn(task);
    when(reviewProjectService.createReviewProjectRequestAsync(any())).thenReturn(future);

    CreateReviewProjectRequestRequest request =
        objectMapper.readValue(
            "{\"maxWordCountPerProject\":2000}", CreateReviewProjectRequestRequest.class);
    reviewProjectWS.createReviewProjectRequest(request);

    ArgumentCaptor<CreateReviewProjectRequestCommand> command =
        ArgumentCaptor.forClass(CreateReviewProjectRequestCommand.class);
    verify(reviewProjectService).createReviewProjectRequestAsync(command.capture());
    assertEquals(Integer.valueOf(2000), command.getValue().maxWordCountPerProject());
  }

  @Test
  public void omittedWordLimitRemainsUnlimited() throws Exception {
    CreateReviewProjectRequestRequest request =
        objectMapper.readValue("{}", CreateReviewProjectRequestRequest.class);
    assertNull(request.maxWordCountPerProject());
  }

  @Test
  public void rejectsNonPositiveWordLimitBeforeScheduling() throws Exception {
    for (int limit : new int[] {0, -1}) {
      CreateReviewProjectRequestRequest request =
          objectMapper.readValue(
              "{\"maxWordCountPerProject\":" + limit + "}",
              CreateReviewProjectRequestRequest.class);
      ResponseStatusException error =
          assertThrows(
              ResponseStatusException.class,
              () -> reviewProjectWS.createReviewProjectRequest(request));
      assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
      assertEquals("maxWordCountPerProject must be positive", error.getReason());
    }
    verifyNoInteractions(reviewProjectService);
  }
}
