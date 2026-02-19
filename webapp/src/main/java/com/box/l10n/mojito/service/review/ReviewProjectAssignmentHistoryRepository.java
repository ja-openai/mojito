package com.box.l10n.mojito.service.review;

import com.box.l10n.mojito.entity.review.ReviewProjectAssignmentHistory;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

@RepositoryRestResource(exported = false)
public interface ReviewProjectAssignmentHistoryRepository
    extends JpaRepository<ReviewProjectAssignmentHistory, Long> {

  @Query(
      """
      select h
      from ReviewProjectAssignmentHistory h
      left join fetch h.team team
      left join fetch h.assignedPmUser pm
      left join fetch h.assignedTranslatorUser translator
      left join fetch h.createdByUser createdBy
      where h.reviewProject.id = :projectId
      order by h.createdDate desc, h.id desc
      """)
  List<ReviewProjectAssignmentHistory> findByProjectId(@Param("projectId") Long projectId);
}
