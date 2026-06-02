package com.box.l10n.mojito.service.pullrun;

import com.box.l10n.mojito.entity.PullRun;
import com.box.l10n.mojito.entity.PullRunTextUnitVariant;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.service.commit.CommitToPullRunRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaDelete;
import jakarta.persistence.criteria.Root;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Service to manage PullRun data.
 *
 * @author garion
 */
@Service
public class PullRunService {

  /** Logger */
  static Logger logger = LoggerFactory.getLogger(PullRunService.class);

  @Autowired PullRunRepository pullRunRepository;

  @Autowired CommitToPullRunRepository commitToPullRunRepository;

  @Autowired PullRunAssetRepository pullRunAssetRepository;

  @Autowired PullRunTextUnitVariantRepository pullRunTextUnitVariantRepository;

  @Autowired EntityManager entityManager;

  @Autowired TransactionTemplate transactionTemplate;

  @Value("${l10n.PullRunService.cleanup-job.batchsize:100000}")
  int deleteBatchSize;

  @Value("${l10n.PullRunService.cleanup-job.waitMs:2000}")
  int deleteWaitMs;

  public PullRun getOrCreate(String pullRunName, Repository repository) {
    return pullRunRepository
        .findByName(pullRunName)
        .orElseGet(
            () -> {
              PullRun pullRun = new PullRun();
              pullRun.setName(pullRunName);
              pullRun.setRepository(repository);
              pullRunRepository.save(pullRun);
              return pullRun;
            });
  }

  public void deleteAllPullEntitiesOlderThan(Duration retentionDuration) {
    ZonedDateTime beforeDate =
        ZonedDateTime.now().minusSeconds((int) retentionDuration.getSeconds());

    int batchNumber = 1;
    int deleteCount;
    do {
      List<Long> ids =
          pullRunTextUnitVariantRepository.findIdsByPullRunWithCreatedDateBefore(
              beforeDate, PageRequest.of(0, deleteBatchSize));
      deleteCount = deletePullRunTextUnitVariantsByIds(ids);
      logger.debug(
          "Deleted {} pullRunTextUnitVariant rows in batch: {}", deleteCount, batchNumber++);
      waitForConfiguredTime();
    } while (deleteCount == deleteBatchSize);

    pullRunAssetRepository.deleteAllByPullRunWithCreatedDateBefore(beforeDate);
    commitToPullRunRepository.deleteAllByPullRunWithCreatedDateBefore(beforeDate);
    pullRunRepository.deleteAllByCreatedDateBefore(beforeDate);
  }

  int deletePullRunTextUnitVariantsByIds(List<Long> ids) {
    if (ids.isEmpty()) {
      return 0;
    }

    return transactionTemplate.execute(
        status -> {
          CriteriaBuilder criteriaBuilder = entityManager.getCriteriaBuilder();
          CriteriaDelete<PullRunTextUnitVariant> delete =
              criteriaBuilder.createCriteriaDelete(PullRunTextUnitVariant.class);
          Root<PullRunTextUnitVariant> root = delete.from(PullRunTextUnitVariant.class);
          delete.where(root.get("id").in(ids));
          return entityManager.createQuery(delete).executeUpdate();
        });
  }

  private void waitForConfiguredTime() {
    try {
      Thread.sleep(deleteWaitMs);
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }
}
