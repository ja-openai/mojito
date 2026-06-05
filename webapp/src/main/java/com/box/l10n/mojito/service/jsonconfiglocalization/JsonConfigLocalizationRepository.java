package com.box.l10n.mojito.service.jsonconfiglocalization;

import com.box.l10n.mojito.entity.JsonConfigLocalizationEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

@RepositoryRestResource(exported = false)
public interface JsonConfigLocalizationRepository
    extends JpaRepository<JsonConfigLocalizationEntity, Long> {

  @EntityGraph(attributePaths = {"repository", "repository.sourceLocale"})
  List<JsonConfigLocalizationEntity> findAllByOrderByNameAscIdAsc();

  @EntityGraph(attributePaths = {"repository", "repository.sourceLocale"})
  List<JsonConfigLocalizationEntity> findByRepositoryIdOrderByNameAscIdAsc(Long repositoryId);

  @EntityGraph(attributePaths = {"repository", "repository.sourceLocale"})
  Optional<JsonConfigLocalizationEntity> findFirstByRepositoryIdOrderByNameAscIdAsc(
      Long repositoryId);

  boolean existsByRepositoryIdAndName(Long repositoryId, String name);

  boolean existsByRepositoryIdAndNameAndIdNot(Long repositoryId, String name, Long id);

  boolean existsByRepositoryIdAndAssetPath(Long repositoryId, String assetPath);

  boolean existsByRepositoryIdAndAssetPathAndIdNot(Long repositoryId, String assetPath, Long id);
}
