package com.box.l10n.mojito.service.review;

import com.box.l10n.mojito.entity.review.ReviewProjectTextUnitSuggestion;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

@RepositoryRestResource(exported = false)
public interface ReviewProjectTextUnitSuggestionRepository
    extends JpaRepository<ReviewProjectTextUnitSuggestion, Long> {

  Optional<ReviewProjectTextUnitSuggestion> findByReviewProjectTextUnitId(
      Long reviewProjectTextUnitId);

  @Query(
      """
      select s
      from ReviewProjectTextUnitSuggestion s
      join fetch s.reviewProjectTextUnit rptu
      left join fetch s.lastModifiedByUser lastModifiedBy
      where rptu.reviewProject.id = :reviewProjectId
      order by rptu.id asc
      """)
  List<ReviewProjectTextUnitSuggestion> findByReviewProjectIdOrderByTextUnitId(
      @Param("reviewProjectId") Long reviewProjectId);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      """
      delete from ReviewProjectTextUnitSuggestion s
      where s.reviewProjectTextUnit.reviewProject.id in :projectIds
      """)
  int deleteByReviewProjectIds(@Param("projectIds") List<Long> projectIds);
}
