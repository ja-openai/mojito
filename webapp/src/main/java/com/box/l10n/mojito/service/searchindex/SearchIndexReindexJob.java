package com.box.l10n.mojito.service.searchindex;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.quartz.QuartzPollableJob;
import com.box.l10n.mojito.service.pollableTask.PollableTaskService;
import java.util.concurrent.atomic.AtomicReference;
import org.quartz.DisallowConcurrentExecution;
import org.springframework.beans.factory.annotation.Autowired;

@DisallowConcurrentExecution
public class SearchIndexReindexJob
    extends QuartzPollableJob<
        SearchIndexService.SearchIndexReindexRequest, SearchIndexService.SearchIndexReindexResult> {

  @Autowired private SearchIndexService searchIndexService;
  @Autowired private PollableTaskService pollableTaskService;
  @Autowired private ObjectMapper objectMapper;

  @Override
  public SearchIndexService.SearchIndexReindexResult call(
      SearchIndexService.SearchIndexReindexRequest request) {
    long pollableTaskId = getCurrentPollableTask().getId();
    AtomicReference<SearchIndexService.SearchIndexReindexProgress> latestProgress =
        new AtomicReference<>(searchIndexService.queuedReindexProgress(request));
    try {
      return searchIndexService.reindex(
          request,
          progress -> {
            latestProgress.set(progress);
            pollableTaskService.updateMessage(
                pollableTaskId, objectMapper.writeValueAsStringUnchecked(progress));
          });
    } catch (RuntimeException exception) {
      SearchIndexService.SearchIndexReindexProgress failedProgress =
          latestProgress.get().withStatus("FAILED", exception.getMessage());
      pollableTaskService.updateMessage(
          pollableTaskId, objectMapper.writeValueAsStringUnchecked(failedProgress));
      throw exception;
    }
  }
}
