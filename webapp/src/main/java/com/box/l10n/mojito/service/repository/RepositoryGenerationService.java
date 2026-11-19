package com.box.l10n.mojito.service.repository;

import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.RepositoryGenerationSettings;
import com.box.l10n.mojito.rest.repository.RepositoryWithIdNotFoundException;
import com.box.l10n.mojito.service.locale.LocaleService;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RepositoryGenerationService {

  private final RepositoryRepository repositoryRepository;
  private final LocaleService localeService;
  private final GlobalGenerationService globalGenerationService;
  private final RepositoryGenerationSettingsRepository repositoryGenerationSettingsRepository;

  public RepositoryGenerationService(
      RepositoryRepository repositoryRepository,
      LocaleService localeService,
      GlobalGenerationService globalGenerationService,
      RepositoryGenerationSettingsRepository repositoryGenerationSettingsRepository) {
    this.repositoryRepository = repositoryRepository;
    this.localeService = localeService;
    this.globalGenerationService = globalGenerationService;
    this.repositoryGenerationSettingsRepository = repositoryGenerationSettingsRepository;
  }

  @Transactional(readOnly = true)
  public Set<String> getEffectiveExcludedLocaleTags(Repository repository) {
    Set<String> excluded = new LinkedHashSet<>(globalGenerationService.getExcludedLocaleTags());
    if (repository != null && repository.getId() != null) {
      excluded.addAll(getStoredExcludedLocaleTags(repository.getId()));
    }
    return Set.copyOf(excluded);
  }

  @Transactional(readOnly = true)
  public List<String> getExcludedLocaleTags(Long repositoryId)
      throws RepositoryWithIdNotFoundException {
    getRepository(repositoryId);
    return getStoredExcludedLocaleTags(repositoryId);
  }

  @Transactional
  public List<String> updateExcludedLocaleTags(Long repositoryId, List<String> localeTags)
      throws RepositoryWithIdNotFoundException {
    Repository repository = getRepository(repositoryId);
    List<String> exclusions =
        GenerationExcludedLocaleTags.normalize(
            localeTags, localeService, repository.getSourceLocale().getBcp47Tag());
    RepositoryGenerationSettings settings =
        repositoryGenerationSettingsRepository
            .findById(repositoryId)
            .orElseGet(RepositoryGenerationSettings::new);
    settings.setRepositoryId(repositoryId);
    settings.setExcludedLocaleTags(exclusions);
    repositoryGenerationSettingsRepository.save(settings);
    return settings.getExcludedLocaleTags();
  }

  private List<String> getStoredExcludedLocaleTags(Long repositoryId) {
    return repositoryGenerationSettingsRepository
        .findById(repositoryId)
        .map(RepositoryGenerationSettings::getExcludedLocaleTags)
        .orElseGet(List::of);
  }

  private Repository getRepository(Long repositoryId) throws RepositoryWithIdNotFoundException {
    return repositoryRepository
        .findNoGraphById(repositoryId)
        .filter(repository -> !Boolean.TRUE.equals(repository.getDeleted()))
        .orElseThrow(() -> new RepositoryWithIdNotFoundException(repositoryId));
  }
}
