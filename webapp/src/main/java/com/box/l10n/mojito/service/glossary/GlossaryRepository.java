package com.box.l10n.mojito.service.glossary;

import com.box.l10n.mojito.entity.glossary.Glossary;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

@RepositoryRestResource(exported = false)
public interface GlossaryRepository extends JpaRepository<Glossary, Long> {

  @Modifying(flushAutomatically = true)
  @Query(
      """
      update Glossary glossary
      set glossary.lastModifiedDate =
        case
          when glossary.lastModifiedDate is null or glossary.lastModifiedDate < :now
            then :now
          else timestampadd(SECOND, 1, glossary.lastModifiedDate)
        end
      where glossary.id = :glossaryId
      """)
  void advanceLastModifiedDate(
      @Param("glossaryId") Long glossaryId, @Param("now") ZonedDateTime now);

  Optional<Glossary> findByNameIgnoreCase(String name);

  @EntityGraph(attributePaths = "backingRepository")
  @Query("select g from Glossary g where lower(g.name) = lower(:name)")
  Optional<Glossary> findByNameIgnoreCaseWithBackingRepository(@Param("name") String name);

  @EntityGraph(attributePaths = {"backingRepository", "repositories"})
  @Query("select g from Glossary g where g.id = :id")
  Optional<Glossary> findByIdWithBackingRepositoryAndRepositories(@Param("id") Long id);

  @EntityGraph(attributePaths = {"backingRepository", "repositories", "excludedRepositories"})
  @Query("select g from Glossary g where g.id = :id")
  Optional<Glossary> findByIdWithBindings(@Param("id") Long id);

  @Query("select distinct g.backingRepository.id from Glossary g")
  List<Long> findBackingRepositoryIds();

  @EntityGraph(attributePaths = "backingRepository")
  @Query(
      """
      select distinct g
      from Glossary g
      where g.enabled = true
        and (
          (g.scopeMode = 'GLOBAL'
            and not exists (
              select 1
              from g.excludedRepositories excludedRepository
              where excludedRepository.id = :repositoryId
            )
          )
          or
          (g.scopeMode = 'SELECTED_REPOSITORIES'
            and exists (
              select 1
              from g.repositories selectedRepository
              where selectedRepository.id = :repositoryId
            )
          )
        )
      order by g.priority asc, lower(g.name) asc, g.id asc
      """)
  List<Glossary> findEnabledByRepositoryId(@Param("repositoryId") Long repositoryId);

  @EntityGraph(attributePaths = "backingRepository")
  @Query(
      """
      select g
      from Glossary g
      where g.enabled = true
        and g.scopeMode = 'GLOBAL'
      order by g.priority asc, lower(g.name) asc, g.id asc
      """)
  List<Glossary> findEnabledGlobalGlossaries();

  @EntityGraph(attributePaths = "backingRepository")
  @Query(
      """
      select g
      from Glossary g
      join g.repositories selectedRepository
      where g.enabled = true
        and g.scopeMode = 'SELECTED_REPOSITORIES'
        and selectedRepository.id = :repositoryId
      order by g.priority asc, lower(g.name) asc, g.id asc
      """)
  List<Glossary> findEnabledScopedByRepositoryId(@Param("repositoryId") Long repositoryId);
}
