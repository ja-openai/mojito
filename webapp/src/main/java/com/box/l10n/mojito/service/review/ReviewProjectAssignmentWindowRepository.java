package com.box.l10n.mojito.service.review;

import com.box.l10n.mojito.entity.review.ReviewProjectAssignmentWindow;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

@RepositoryRestResource(exported = false)
public interface ReviewProjectAssignmentWindowRepository
    extends JpaRepository<ReviewProjectAssignmentWindow, Long> {

  @Query(
      """
      select window
      from ReviewProjectAssignmentWindow window
      left join fetch window.reviewProject project
      left join fetch window.assignedTranslatorUser translator
      left join fetch window.selfReportedByUser reporter
      where project.id = :reviewProjectId
        and window.endedAt is null
      order by window.assignedAt desc, window.id desc
      """)
  Optional<ReviewProjectAssignmentWindow> findOpenWindowByReviewProjectId(
      @Param("reviewProjectId") Long reviewProjectId);

  @Query(
      """
      select window
      from ReviewProjectAssignmentWindow window
      left join fetch window.reviewProject project
      left join fetch window.assignedTranslatorUser translator
      left join fetch window.selfReportedByUser reporter
      where project.id = :reviewProjectId
      order by window.assignedAt, window.id
      """)
  List<ReviewProjectAssignmentWindow> findByReviewProjectIdOrderByAssignedAt(
      @Param("reviewProjectId") Long reviewProjectId);
}
