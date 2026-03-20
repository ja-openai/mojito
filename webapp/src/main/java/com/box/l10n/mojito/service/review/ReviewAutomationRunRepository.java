package com.box.l10n.mojito.service.review;

import com.box.l10n.mojito.entity.review.ReviewAutomationRun;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReviewAutomationRunRepository extends JpaRepository<ReviewAutomationRun, Long> {

  @Query(
      """
      select new com.box.l10n.mojito.service.review.ReviewAutomationRunSummaryRow(
        run.id,
        automation.id,
        automation.name,
        run.triggerSource,
        requestedByUser.id,
        requestedByUser.username,
        run.status,
        run.createdDate,
        run.startedAt,
        run.finishedAt,
        run.featureCount,
        run.createdProjectRequestCount,
        run.createdProjectCount,
        run.createdLocaleCount,
        run.skippedLocaleCount,
        run.erroredLocaleCount,
        run.errorMessage
      )
      from ReviewAutomationRun run
      join run.reviewAutomation automation
      left join run.requestedByUser requestedByUser
      order by run.createdDate desc, run.id desc
      """)
  List<ReviewAutomationRunSummaryRow> findRecentRunRows(Pageable pageable);

  @Query(
      """
      select new com.box.l10n.mojito.service.review.ReviewAutomationRunSummaryRow(
        run.id,
        automation.id,
        automation.name,
        run.triggerSource,
        requestedByUser.id,
        requestedByUser.username,
        run.status,
        run.createdDate,
        run.startedAt,
        run.finishedAt,
        run.featureCount,
        run.createdProjectRequestCount,
        run.createdProjectCount,
        run.createdLocaleCount,
        run.skippedLocaleCount,
        run.erroredLocaleCount,
        run.errorMessage
      )
      from ReviewAutomationRun run
      join run.reviewAutomation automation
      left join run.requestedByUser requestedByUser
      where automation.id in :automationIds
      order by run.createdDate desc, run.id desc
      """)
  List<ReviewAutomationRunSummaryRow> findRecentRunRowsByAutomationIds(
      @Param("automationIds") List<Long> automationIds, Pageable pageable);
}
