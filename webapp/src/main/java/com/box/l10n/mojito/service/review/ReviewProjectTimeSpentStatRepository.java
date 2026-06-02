package com.box.l10n.mojito.service.review;

import com.box.l10n.mojito.entity.review.ReviewProjectTimeSpentStat;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

@RepositoryRestResource(exported = false)
public interface ReviewProjectTimeSpentStatRepository
    extends JpaRepository<ReviewProjectTimeSpentStat, Long> {

  Optional<ReviewProjectTimeSpentStat> findByAssignmentWindow_Id(Long assignmentWindowId);

  @Query(
      """
      select stat
      from ReviewProjectTimeSpentStat stat
      left join fetch stat.assignmentWindow
      left join fetch stat.reviewProject
      left join fetch stat.assignedTranslatorUser
      where (:activityAfter is null or stat.lastDecisionAt >= :activityAfter)
        and (:activityBefore is null or stat.lastDecisionAt <= :activityBefore)
        and (:status is null or stat.reviewProjectStatus = :status)
        and (:translatorUserId is null or stat.assignedTranslatorUser.id = :translatorUserId)
        and (:localeBcp47Tag is null or stat.localeBcp47Tag = :localeBcp47Tag)
      order by stat.lastDecisionAt desc, stat.id desc
      """)
  List<ReviewProjectTimeSpentStat> findReportRows(
      @Param("activityAfter") ZonedDateTime activityAfter,
      @Param("activityBefore") ZonedDateTime activityBefore,
      @Param("status") String status,
      @Param("translatorUserId") Long translatorUserId,
      @Param("localeBcp47Tag") String localeBcp47Tag,
      Pageable pageable);

  @Query(
      """
      select
        count(stat.id) as windowCount,
        count(distinct stat.reviewProject.id) as projectCount,
        sum(stat.decidedWordCount) as decidedWordCount,
        sum(stat.selfReportedSeconds) as selfReportedSeconds,
        sum(stat.estimatedActiveSeconds) as estimatedActiveSeconds,
        sum(stat.rawDecisionSpanSeconds) as rawDecisionSpanSeconds,
        sum(stat.pauseSeconds) as pauseSeconds,
        sum(stat.pauseCount) as pauseCount,
        sum(case when stat.reportedMissing = true then 1 else 0 end) as reportedMissingCount,
        sum(case when stat.reviewFlag <> com.box.l10n.mojito.entity.review.ReviewProjectTimeSpentReviewFlag.OK then 1 else 0 end) as reviewFlagCount,
        max(stat.computedAt) as lastComputedAt
      from ReviewProjectTimeSpentStat stat
      where (:activityAfter is null or stat.lastDecisionAt >= :activityAfter)
        and (:activityBefore is null or stat.lastDecisionAt <= :activityBefore)
        and (:status is null or stat.reviewProjectStatus = :status)
        and (:translatorUserId is null or stat.assignedTranslatorUser.id = :translatorUserId)
        and (:localeBcp47Tag is null or stat.localeBcp47Tag = :localeBcp47Tag)
      """)
  SummaryProjection findReportSummary(
      @Param("activityAfter") ZonedDateTime activityAfter,
      @Param("activityBefore") ZonedDateTime activityBefore,
      @Param("status") String status,
      @Param("translatorUserId") Long translatorUserId,
      @Param("localeBcp47Tag") String localeBcp47Tag);

  @Query(
      """
      select
        stat.assignedTranslatorUser.id as assignedTranslatorUserId,
        stat.assignedTranslatorUsername as assignedTranslatorUsername,
        stat.localeBcp47Tag as localeBcp47Tag,
        count(stat.id) as windowCount,
        count(distinct stat.reviewProject.id) as projectCount,
        sum(stat.decidedWordCount) as decidedWordCount,
        sum(stat.selfReportedSeconds) as selfReportedSeconds,
        sum(stat.estimatedActiveSeconds) as estimatedActiveSeconds,
        sum(stat.rawDecisionSpanSeconds) as rawDecisionSpanSeconds,
        sum(stat.pauseSeconds) as pauseSeconds,
        sum(stat.pauseCount) as pauseCount,
        sum(case when stat.reportedMissing = true then 1 else 0 end) as reportedMissingCount,
        sum(case when stat.reviewFlag <> com.box.l10n.mojito.entity.review.ReviewProjectTimeSpentReviewFlag.OK then 1 else 0 end) as reviewFlagCount,
        max(stat.computedAt) as lastComputedAt
      from ReviewProjectTimeSpentStat stat
      where (:activityAfter is null or stat.lastDecisionAt >= :activityAfter)
        and (:activityBefore is null or stat.lastDecisionAt <= :activityBefore)
        and (:status is null or stat.reviewProjectStatus = :status)
        and (:translatorUserId is null or stat.assignedTranslatorUser.id = :translatorUserId)
        and (:localeBcp47Tag is null or stat.localeBcp47Tag = :localeBcp47Tag)
      group by stat.assignedTranslatorUser.id, stat.assignedTranslatorUsername, stat.localeBcp47Tag
      order by sum(stat.selfReportedSeconds) desc, sum(stat.estimatedActiveSeconds) desc
      """)
  List<LinguistSummaryProjection> findLinguistSummaries(
      @Param("activityAfter") ZonedDateTime activityAfter,
      @Param("activityBefore") ZonedDateTime activityBefore,
      @Param("status") String status,
      @Param("translatorUserId") Long translatorUserId,
      @Param("localeBcp47Tag") String localeBcp47Tag,
      Pageable pageable);

  interface SummaryProjection {
    Long getWindowCount();

    Long getProjectCount();

    Long getDecidedWordCount();

    Long getSelfReportedSeconds();

    Long getEstimatedActiveSeconds();

    Long getRawDecisionSpanSeconds();

    Long getPauseSeconds();

    Long getPauseCount();

    Long getReportedMissingCount();

    Long getReviewFlagCount();

    ZonedDateTime getLastComputedAt();
  }

  interface LinguistSummaryProjection extends SummaryProjection {
    Long getAssignedTranslatorUserId();

    String getAssignedTranslatorUsername();

    String getLocaleBcp47Tag();
  }
}
