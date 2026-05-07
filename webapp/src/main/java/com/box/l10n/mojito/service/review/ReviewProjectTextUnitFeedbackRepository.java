package com.box.l10n.mojito.service.review;

import com.box.l10n.mojito.entity.review.ReviewProjectTextUnitFeedback;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

@RepositoryRestResource(exported = false)
public interface ReviewProjectTextUnitFeedbackRepository
    extends JpaRepository<ReviewProjectTextUnitFeedback, Long> {

  @Query(
      """
      select f
      from ReviewProjectTextUnitFeedback f
      join fetch f.reviewProjectTextUnit rptu
      join fetch f.reviewerUser reviewer
      where rptu.reviewProject.id = :reviewProjectId
      order by rptu.id asc, f.lastModifiedDate desc
      """)
  List<ReviewProjectTextUnitFeedback> findByReviewProjectIdOrderByTextUnitIdAndLastModified(
      @Param("reviewProjectId") Long reviewProjectId);

  @Query(
      """
      select f
      from ReviewProjectTextUnitFeedback f
      join fetch f.reviewProjectTextUnit rptu
      join fetch rptu.tmTextUnit ttu
      join fetch f.reviewerUser reviewer
      join rptu.reviewProject rp
      where rp.reviewProjectRequest.id = :requestId
        and rp.type in (
          com.box.l10n.mojito.entity.review.ReviewProjectType.TERMINOLOGY,
          com.box.l10n.mojito.entity.review.ReviewProjectType.TERM_CANDIDATE
        )
        and rp.terminologyPhase = com.box.l10n.mojito.entity.review.ReviewProjectTerminologyPhase.SPECIALIST_INPUT
        and ttu.id in :tmTextUnitIds
      order by ttu.id asc, f.lastModifiedDate desc
      """)
  List<ReviewProjectTextUnitFeedback> findSpecialistFeedbackByRequestIdAndTmTextUnitIds(
      @Param("requestId") Long requestId, @Param("tmTextUnitIds") List<Long> tmTextUnitIds);

  @Query(
      """
      select f
      from ReviewProjectTextUnitFeedback f
      join fetch f.reviewerUser reviewer
      where f.reviewProjectTextUnit.id = :reviewProjectTextUnitId
      order by f.lastModifiedDate desc
      """)
  List<ReviewProjectTextUnitFeedback> findByReviewProjectTextUnitIdOrderByLastModifiedDateDesc(
      @Param("reviewProjectTextUnitId") Long reviewProjectTextUnitId);

  boolean existsByReviewProjectTextUnitId(Long reviewProjectTextUnitId);

  Optional<ReviewProjectTextUnitFeedback> findByReviewProjectTextUnitIdAndReviewerUserId(
      Long reviewProjectTextUnitId, Long reviewerUserId);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      """
      delete from ReviewProjectTextUnitFeedback f
      where f.reviewProjectTextUnit.reviewProject.id in :projectIds
      """)
  int deleteByReviewProjectIds(@Param("projectIds") List<Long> projectIds);
}
