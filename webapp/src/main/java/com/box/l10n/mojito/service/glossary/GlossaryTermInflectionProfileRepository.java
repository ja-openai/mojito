package com.box.l10n.mojito.service.glossary;

import com.box.l10n.mojito.entity.glossary.GlossaryTermInflectionProfile;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

@RepositoryRestResource(exported = false)
public interface GlossaryTermInflectionProfileRepository
    extends JpaRepository<GlossaryTermInflectionProfile, Long> {

  @EntityGraph(attributePaths = {"glossaryTermMetadata", "glossaryTermMetadata.tmTextUnit"})
  Optional<GlossaryTermInflectionProfile> findByGlossaryTermMetadataIdAndLocaleTag(
      Long glossaryTermMetadataId, String localeTag);

  @Query(
      """
      select profile
      from GlossaryTermInflectionProfile profile
      join fetch profile.glossaryTermMetadata metadata
      join fetch metadata.tmTextUnit tmTextUnit
      where metadata.glossary.id = :glossaryId
        and profile.localeTag = :localeTag
      order by tmTextUnit.name asc, profile.id asc
      """)
  List<GlossaryTermInflectionProfile> findByGlossaryIdAndLocaleTag(
      @Param("glossaryId") Long glossaryId, @Param("localeTag") String localeTag);

  @Query(
      """
      select profile
      from GlossaryTermInflectionProfile profile
      join fetch profile.glossaryTermMetadata metadata
      join fetch metadata.tmTextUnit tmTextUnit
      where metadata.glossary.id = :glossaryId
        and profile.localeTag = :localeTag
      order by tmTextUnit.name asc, profile.id asc
      """)
  List<GlossaryTermInflectionProfile> findByGlossaryIdAndLocaleTag(
      @Param("glossaryId") Long glossaryId,
      @Param("localeTag") String localeTag,
      Pageable pageable);

  @Query(
      """
      select profile
      from GlossaryTermInflectionProfile profile
      join fetch profile.glossaryTermMetadata metadata
      join fetch metadata.tmTextUnit tmTextUnit
      where metadata.glossary.id = :glossaryId
        and profile.localeTag = :localeTag
        and (
          profile.status is null
          or profile.status <> :approvedStatus
          or profile.diagnosticsJson is null
          or profile.diagnosticsJson <> :emptyDiagnosticsJson
        )
      order by tmTextUnit.name asc, profile.id asc
      """)
  List<GlossaryTermInflectionProfile> findProfilesNeedingReviewByGlossaryIdAndLocaleTag(
      @Param("glossaryId") Long glossaryId,
      @Param("localeTag") String localeTag,
      @Param("approvedStatus") String approvedStatus,
      @Param("emptyDiagnosticsJson") String emptyDiagnosticsJson,
      Pageable pageable);

  @Query(
      """
      select count(profile)
      from GlossaryTermInflectionProfile profile
      join profile.glossaryTermMetadata metadata
      where metadata.glossary.id = :glossaryId
        and profile.localeTag = :localeTag
      """)
  long countByGlossaryIdAndLocaleTag(
      @Param("glossaryId") Long glossaryId, @Param("localeTag") String localeTag);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      """
      delete from GlossaryTermInflectionProfile profile
      where profile.glossaryTermMetadata.glossary.id = :glossaryId
      """)
  int deleteByGlossaryId(@Param("glossaryId") Long glossaryId);
}
