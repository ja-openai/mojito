package com.box.l10n.mojito.service.review;

import com.box.l10n.mojito.entity.review.ReviewProjectTextUnitDecision;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

@RepositoryRestResource(exported = false)
public interface ReviewProjectTextUnitDecisionRepository
    extends JpaRepository<ReviewProjectTextUnitDecision, Long> {

  List<ReviewProjectTextUnitDecision> findByReviewProjectTextUnit_ReviewProject_Id(
      Long reviewProjectId);

  Optional<ReviewProjectTextUnitDecision> findByReviewProjectTextUnitId(
      Long reviewProjectTextUnitId);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      """
      delete from ReviewProjectTextUnitDecision d
      where d.reviewProjectTextUnit.reviewProject.id in :projectIds
      """)
  int deleteByReviewProjectIds(@Param("projectIds") List<Long> projectIds);

  @Query(
      """
      select new com.box.l10n.mojito.service.review.ReviewProjectTimeSpentDecisionRow(
        decision.id,
        lastModifiedBy.id,
        decision.lastModifiedDate,
        textUnit.wordCount
      )
      from ReviewProjectTextUnitDecision decision
      join decision.reviewProjectTextUnit reviewProjectTextUnit
      join reviewProjectTextUnit.tmTextUnit textUnit
      left join decision.lastModifiedByUser lastModifiedBy
      where reviewProjectTextUnit.reviewProject.id = :reviewProjectId
        and decision.decisionState = com.box.l10n.mojito.entity.review.ReviewProjectTextUnitDecision.DecisionState.DECIDED
      order by decision.lastModifiedDate, decision.id
      """)
  List<ReviewProjectTimeSpentDecisionRow> findTimeSpentDecisionRowsByReviewProjectId(
      @Param("reviewProjectId") Long reviewProjectId);
}
