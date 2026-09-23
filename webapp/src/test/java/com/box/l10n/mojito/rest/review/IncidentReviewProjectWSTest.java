package com.box.l10n.mojito.rest.review;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.agentreview.IncidentReviewJobService;
import com.box.l10n.mojito.service.pollableTask.PollableFuture;
import org.junit.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

public class IncidentReviewProjectWSTest {
  @Test
  public void previewAndCreateReturnAcceptedTaskWithoutWaiting() throws Exception {
    IncidentReviewJobService jobs = mock(IncidentReviewJobService.class);
    PollableFuture future = mock(PollableFuture.class);
    PollableTask task = new PollableTask();
    task.setId(17L);
    when(future.getPollableTask()).thenReturn(task);
    when(jobs.preview(any())).thenReturn(future);
    when(jobs.create(any())).thenReturn(future);
    var mvc =
        MockMvcBuilders.standaloneSetup(new IncidentReviewProjectWS(jobs))
            .setMessageConverters(new MappingJackson2HttpMessageConverter(new ObjectMapper()))
            .build();

    for (String path :
        new String[] {"/api/incident-review-projects/preview", "/api/incident-review-projects"}) {
      mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{}"))
          .andExpect(status().isAccepted())
          .andExpect(jsonPath("$.pollableTaskId").value(17));
    }
    verify(future, never()).get();
  }
}
