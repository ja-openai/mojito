package com.box.l10n.mojito.service.review;

import com.box.l10n.mojito.entity.review.ReviewProject;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

@RepositoryRestResource(exported = false)
public interface ReviewProjectRepository extends JpaRepository<ReviewProject, Long> {

  boolean existsByTeam_Id(Long teamId);

  @Query(
      """
      select new com.box.l10n.mojito.service.review.ReviewProjectDetail(
        rp.id,
        rp.type,
        rp.status,
        rp.createdDate,
        rp.dueDate,
        rp.closeReason,
        rp.textUnitCount,
        rp.wordCount,
        locale.id,
        locale.bcp47Tag,
        request.id,
        request.name,
        request.notes,
        coalesce(request.createdDate, rp.createdDate),
        requestCreatedBy.username,
        team.id,
        team.name,
        assignedPm.id,
        assignedPm.username,
        assignedTranslator.id,
        assignedTranslator.username
      )
      from ReviewProject rp
      left join rp.locale locale
      left join rp.reviewProjectRequest request
      left join request.createdByUser requestCreatedBy
      left join rp.team team
      left join rp.assignedPmUser assignedPm
      left join rp.assignedTranslatorUser assignedTranslator
      where rp.id = :id
      """)
  Optional<ReviewProjectDetail> findDetailById(@Param("id") Long id);

  @Query(
      """
      select distinct request.id
      from ReviewProject rp
      join rp.reviewProjectRequest request
      where rp.id in :projectIds
      """)
  List<Long> findRequestIdsByProjectIds(@Param("projectIds") List<Long> projectIds);

  @Query(
      """
      select rp
      from ReviewProject rp
      join fetch rp.reviewProjectRequest request
      left join fetch rp.team
      left join fetch rp.assignedPmUser
      left join fetch rp.assignedTranslatorUser
      left join fetch rp.locale
      where request.id = :requestId
      order by rp.id
      """)
  List<ReviewProject> findByRequestIdWithAssignment(@Param("requestId") Long requestId);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      """
      delete from ReviewProject rp
      where rp.id in :projectIds
      """)
  int deleteByProjectIds(@Param("projectIds") List<Long> projectIds);
}
