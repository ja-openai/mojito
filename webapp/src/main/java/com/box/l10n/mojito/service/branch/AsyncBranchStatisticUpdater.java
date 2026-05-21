package com.box.l10n.mojito.service.branch;

import static com.box.l10n.mojito.utils.TaskExecutorUtils.waitForAllFutures;

import com.box.l10n.mojito.entity.Branch;
import com.google.common.collect.ImmutableMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

@Component
public class AsyncBranchStatisticUpdater {

  // Lazy annotation is used to resolve the circular dependency between AsyncBranchStatisticUpdater
  // and BranchStatisticService. BranchStatisticService is still used as a bean so the transaction
  // proxy around updateBranchStatisticInTx is applied inside each executor task.
  @Autowired @Lazy BranchStatisticService branchStatisticService;

  @Autowired
  @Qualifier("statisticsTaskExecutor")
  TaskExecutor statisticsTaskExecutor;

  public void updateBranchStatistics(
      List<Branch> branches,
      Map<String, ImmutableMap<Long, ForTranslationCountForTmTextUnitId>>
          mapBranchNameToTranslationCountForTextUnitId) {
    List<CompletableFuture<Void>> futures =
        branches.stream()
            .map(
                branch ->
                    CompletableFuture.runAsync(
                        () ->
                            updateBranchStatistics(
                                branch, mapBranchNameToTranslationCountForTextUnitId),
                        statisticsTaskExecutor::execute))
            .collect(Collectors.toList());
    waitForAllFutures(futures);
  }

  void updateBranchStatistics(
      Branch branch,
      Map<String, ImmutableMap<Long, ForTranslationCountForTmTextUnitId>>
          mapBranchNameToTranslationCountForTextUnitId) {
    branchStatisticService.updateBranchStatisticInTx(
        branch,
        mapBranchNameToTranslationCountForTextUnitId.getOrDefault(
            branch.getName(), ImmutableMap.of()));
  }
}
