package com.box.l10n.mojito.service.searchindex;

import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.quartz.QuartzJobInfo;
import com.box.l10n.mojito.quartz.QuartzPollableTaskScheduler;
import com.box.l10n.mojito.service.pollableTask.PollableTaskRepository;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class SearchIndexReindexJobService {

  private static final String REINDEX_JOB_NAME = SearchIndexReindexJob.class.getCanonicalName();

  private final SearchIndexService searchIndexService;
  private final QuartzPollableTaskScheduler quartzPollableTaskScheduler;
  private final PollableTaskRepository pollableTaskRepository;
  private final ObjectMapper objectMapper;

  public SearchIndexReindexJobService(
      SearchIndexService searchIndexService,
      QuartzPollableTaskScheduler quartzPollableTaskScheduler,
      PollableTaskRepository pollableTaskRepository,
      ObjectMapper objectMapper) {
    this.searchIndexService = searchIndexService;
    this.quartzPollableTaskScheduler = quartzPollableTaskScheduler;
    this.pollableTaskRepository = pollableTaskRepository;
    this.objectMapper = objectMapper;
  }

  public synchronized PollableTask scheduleReindex(
      SearchIndexService.SearchIndexReindexRequest request) {
    searchIndexService.requireEnabled();
    Optional<PollableTask> activeTask = getActiveReindexTask();
    if (activeTask.isPresent()) {
      return activeTask.get();
    }

    SearchIndexService.SearchIndexReindexRequest normalizedRequest =
        request == null
            ? new SearchIndexService.SearchIndexReindexRequest(null, null, null)
            : request;
    SearchIndexService.SearchIndexReindexProgress queuedProgress =
        searchIndexService.queuedReindexProgress(normalizedRequest);
    QuartzJobInfo<
            SearchIndexService.SearchIndexReindexRequest,
            SearchIndexService.SearchIndexReindexResult>
        quartzJobInfo =
            QuartzJobInfo.newBuilder(SearchIndexReindexJob.class)
                .withInput(normalizedRequest)
                .withMessage(objectMapper.writeValueAsStringUnchecked(queuedProgress))
                .withUniqueId(queuedProgress.indexName())
                .withRequestRecovery(true)
                .build();
    return quartzPollableTaskScheduler.scheduleJob(quartzJobInfo).getPollableTask();
  }

  public Optional<PollableTask> getActiveReindexTask() {
    return pollableTaskRepository
        .findByNameAndFinishedDateIsNullOrderByCreatedDateDesc(REINDEX_JOB_NAME)
        .stream()
        .findFirst();
  }
}
