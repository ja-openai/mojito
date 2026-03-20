package com.box.l10n.mojito.service.review;

import com.box.l10n.mojito.entity.review.ReviewAutomation;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

@RepositoryRestResource(exported = false)
public interface ReviewAutomationRepository extends JpaRepository<ReviewAutomation, Long> {

  Optional<ReviewAutomation> findByNameIgnoreCase(String name);

  @Query(
      """
      select new com.box.l10n.mojito.service.review.ReviewAutomationOptionRow(
        ra.id,
        ra.name,
        ra.enabled
      )
      from ReviewAutomation ra
      order by lower(ra.name) asc, ra.id asc
      """)
  List<ReviewAutomationOptionRow> findAllOptionRows();

  @EntityGraph(attributePaths = {"features", "team"})
  @Query("select ra from ReviewAutomation ra where ra.id = :id")
  Optional<ReviewAutomation> findByIdWithFeatures(@Param("id") Long id);

  @Query(
      value =
          """
          select new com.box.l10n.mojito.service.review.ReviewAutomationSummaryRow(
            ra.id,
            ra.createdDate,
            ra.lastModifiedDate,
            ra.name,
            ra.enabled,
            ra.cronExpression,
            ra.timeZone,
            team.id,
            team.name,
            ra.dueDateOffsetDays,
            ra.maxWordCountPerProject,
            count(distinct rf.id)
          )
          from ReviewAutomation ra
          left join ra.team team
          left join ra.features rf
          where (:enabled is null or ra.enabled = :enabled)
            and (:searchQuery is null or trim(:searchQuery) = '' or lower(ra.name) like concat('%', lower(:searchQuery), '%'))
          group by
            ra.id,
            ra.createdDate,
            ra.lastModifiedDate,
            ra.name,
            ra.enabled,
            ra.cronExpression,
            ra.timeZone,
            team.id,
            team.name,
            ra.dueDateOffsetDays,
            ra.maxWordCountPerProject
          order by lower(ra.name) asc, ra.id asc
          """,
      countQuery =
          """
          select count(ra.id)
          from ReviewAutomation ra
          where (:enabled is null or ra.enabled = :enabled)
            and (:searchQuery is null or trim(:searchQuery) = '' or lower(ra.name) like concat('%', lower(:searchQuery), '%'))
          """)
  Page<ReviewAutomationSummaryRow> searchSummaryRows(
      @Param("searchQuery") String searchQuery,
      @Param("enabled") Boolean enabled,
      Pageable pageable);

  @Query(
      """
      select new com.box.l10n.mojito.service.review.ReviewAutomationFeatureRow(
        ra.id,
        rf.id,
        rf.name
      )
      from ReviewAutomation ra
      join ra.features rf
      where ra.id in :automationIds
      order by lower(rf.name) asc, rf.id asc
      """)
  List<ReviewAutomationFeatureRow> findFeatureRowsByAutomationIds(
      @Param("automationIds") List<Long> automationIds);

  @Query(
      """
      select new com.box.l10n.mojito.service.review.ReviewAutomationFeatureAssignmentRow(
        ra.id,
        ra.name,
        rf.id,
        rf.name
      )
      from ReviewAutomation ra
      join ra.features rf
      where ra.enabled = true
        and rf.id in :featureIds
        and (:excludedAutomationId is null or ra.id <> :excludedAutomationId)
      order by lower(ra.name) asc, ra.id asc, lower(rf.name) asc, rf.id asc
      """)
  List<ReviewAutomationFeatureAssignmentRow> findEnabledFeatureAssignments(
      @Param("featureIds") List<Long> featureIds,
      @Param("excludedAutomationId") Long excludedAutomationId);

  @Query(
      """
      select new com.box.l10n.mojito.service.review.ReviewAutomationOptionRow(
        ra.id,
        ra.name,
        ra.enabled
      )
      from ReviewAutomation ra
      join ra.features rf
      where rf.id = :featureId
      order by lower(ra.name) asc, ra.id asc
      """)
  List<ReviewAutomationOptionRow> findOptionRowsByFeatureId(@Param("featureId") Long featureId);
}
