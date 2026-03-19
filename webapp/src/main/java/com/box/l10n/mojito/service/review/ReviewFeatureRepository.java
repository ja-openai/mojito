package com.box.l10n.mojito.service.review;

import com.box.l10n.mojito.entity.review.ReviewFeature;
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
public interface ReviewFeatureRepository extends JpaRepository<ReviewFeature, Long> {

  Optional<ReviewFeature> findByNameIgnoreCase(String name);

  @Query(
      """
      select new com.box.l10n.mojito.service.review.ReviewFeatureOptionRow(
        rf.id,
        rf.name,
        rf.enabled
      )
      from ReviewFeature rf
      order by lower(rf.name) asc, rf.id asc
      """)
  List<ReviewFeatureOptionRow> findAllOptionRows();

  @EntityGraph(attributePaths = "repositories")
  @Query("select rf from ReviewFeature rf where rf.id = :id")
  Optional<ReviewFeature> findByIdWithRepositories(@Param("id") Long id);

  @Query(
      value =
          """
          select new com.box.l10n.mojito.service.review.ReviewFeatureSummaryRow(
            rf.id,
            rf.createdDate,
            rf.lastModifiedDate,
            rf.name,
            rf.enabled,
            count(distinct r.id)
          )
          from ReviewFeature rf
          left join rf.repositories r
          where (:enabled is null or rf.enabled = :enabled)
            and (:searchQuery is null or trim(:searchQuery) = '' or lower(rf.name) like concat('%', lower(:searchQuery), '%'))
          group by rf.id, rf.createdDate, rf.lastModifiedDate, rf.name, rf.enabled
          order by lower(rf.name) asc, rf.id asc
          """,
      countQuery =
          """
          select count(rf.id)
          from ReviewFeature rf
          where (:enabled is null or rf.enabled = :enabled)
            and (:searchQuery is null or trim(:searchQuery) = '' or lower(rf.name) like concat('%', lower(:searchQuery), '%'))
          """)
  Page<ReviewFeatureSummaryRow> searchSummaryRows(
      @Param("searchQuery") String searchQuery,
      @Param("enabled") Boolean enabled,
      Pageable pageable);

  @Query(
      """
      select new com.box.l10n.mojito.service.review.ReviewFeatureRepositoryRow(
        rf.id,
        r.id,
        r.name
      )
      from ReviewFeature rf
      join rf.repositories r
      where rf.id in :featureIds
        and r.deleted = false
      order by lower(r.name) asc, r.id asc
      """)
  List<ReviewFeatureRepositoryRow> findRepositoryRowsByFeatureIds(
      @Param("featureIds") List<Long> featureIds);
}
