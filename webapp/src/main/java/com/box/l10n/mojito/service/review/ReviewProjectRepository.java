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
      value =
          """
          update review_project rp
          left join (
            select rptu.review_project_id, count(rptud.id) decided_count
            from review_project_text_unit rptu
            join review_project_text_unit_decision rptud
              on rptud.review_project_text_unit_id = rptu.id
              and rptud.decision_state = 'DECIDED'
            group by rptu.review_project_id
          ) decided_counts
            on decided_counts.review_project_id = rp.id
          set rp.decided_count = coalesce(decided_counts.decided_count, 0)
          where rp.review_project_request_id = :requestId
          """,
      nativeQuery = true)
  int recomputeDecidedCountsByRequestId(@Param("requestId") Long requestId);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      """
      update ReviewProject rp
      set rp.decidedCount = rp.decidedCount + 1
      where rp.id = :projectId
      """)
  int incrementDecidedCount(@Param("projectId") Long projectId);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      """
      update ReviewProject rp
      set rp.decidedCount =
        case
          when rp.decidedCount > 0 then rp.decidedCount - 1
          else 0
        end
      where rp.id = :projectId
      """)
  int decrementDecidedCount(@Param("projectId") Long projectId);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      """
      delete from ReviewProject rp
      where rp.id in :projectIds
      """)
  int deleteByProjectIds(@Param("projectIds") List<Long> projectIds);
}
