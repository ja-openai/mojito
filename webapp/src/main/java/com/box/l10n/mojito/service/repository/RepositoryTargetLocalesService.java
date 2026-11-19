package com.box.l10n.mojito.service.repository;

import com.box.l10n.mojito.entity.Locale;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.RepositoryLocale;
import com.box.l10n.mojito.rest.repository.RepositoryTargetLocalesResponse;
import com.box.l10n.mojito.rest.repository.RepositoryWithIdNotFoundException;
import com.box.l10n.mojito.service.locale.LocaleService;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RepositoryTargetLocalesService {

  private final RepositoryRepository repositories;
  private final LocaleService locales;
  private final RepositoryService repositoryService;

  public RepositoryTargetLocalesService(
      RepositoryRepository repositories,
      LocaleService locales,
      RepositoryService repositoryService) {
    this.repositories = repositories;
    this.locales = locales;
    this.repositoryService = repositoryService;
  }

  @Transactional(readOnly = true)
  public RepositoryTargetLocalesResponse getTargetLocales(Long repositoryId)
      throws RepositoryWithIdNotFoundException {
    Repository repository = getRepository(repositoryId);
    return response(repository, targetLocales(repository).keySet().stream().sorted().toList());
  }

  @Transactional(rollbackFor = RepositoryLocaleCreationException.class)
  public RepositoryTargetLocalesResponse updateTargetLocales(Long repositoryId, List<String> tags)
      throws RepositoryWithIdNotFoundException, RepositoryLocaleCreationException {
    Repository repository =
        repositories
            .findNoGraphByIdForUpdate(repositoryId)
            .filter(value -> !Boolean.TRUE.equals(value.getDeleted()))
            .orElseThrow(() -> new RepositoryWithIdNotFoundException(repositoryId));
    if (tags == null) {
      throw new IllegalArgumentException("Provide a locale list; use [] to clear target locales");
    }
    String sourceTag = repository.getSourceLocale().getBcp47Tag();
    Set<String> selected = new LinkedHashSet<>();
    for (String tag : tags) {
      String trimmed = tag == null ? "" : tag.trim();
      if (trimmed.isEmpty()) {
        throw new IllegalArgumentException("Target locale tags must not be blank");
      }
      Locale locale = locales.findByBcp47Tag(trimmed);
      if (locale == null) {
        throw new IllegalArgumentException("Unknown target locale: " + trimmed);
      }
      if (sourceTag.equals(locale.getBcp47Tag())) {
        throw new IllegalArgumentException(
            "The source locale cannot be selected as a target locale");
      }
      selected.add(locale.getBcp47Tag());
    }

    Map<String, RepositoryLocale> existing = targetLocales(repository);
    for (String tag : selected) {
      RepositoryLocale current = existing.get(tag);
      if (current != null && current.getParentLocale() != null) {
        String parentTag = current.getParentLocale().getLocale().getBcp47Tag();
        if (!parentTag.equals(sourceTag) && !selected.contains(parentTag)) {
          throw new IllegalArgumentException(
              "Cannot remove locale "
                  + parentTag
                  + " while "
                  + tag
                  + " still inherits from it. "
                  + "Keep "
                  + parentTag
                  + " selected or remove "
                  + tag
                  + " too.");
        }
      }
    }

    if (!selected.equals(existing.keySet())) {
      repositoryService.updateRepositoryTargetLocales(repository, selected);
    }
    return response(repository, selected.stream().sorted().toList());
  }

  private Map<String, RepositoryLocale> targetLocales(Repository repository) {
    Map<String, RepositoryLocale> targets = new LinkedHashMap<>();
    String sourceTag = repository.getSourceLocale().getBcp47Tag();
    for (RepositoryLocale locale : repository.getRepositoryLocales()) {
      String tag = locale.getLocale().getBcp47Tag();
      if (!sourceTag.equals(tag)) {
        targets.put(tag, locale);
      }
    }
    return targets;
  }

  private RepositoryTargetLocalesResponse response(Repository repository, List<String> tags) {
    return new RepositoryTargetLocalesResponse(repository.getSourceLocale().getBcp47Tag(), tags);
  }

  private Repository getRepository(Long repositoryId) throws RepositoryWithIdNotFoundException {
    return repositories
        .findNoGraphById(repositoryId)
        .filter(repository -> !Boolean.TRUE.equals(repository.getDeleted()))
        .orElseThrow(() -> new RepositoryWithIdNotFoundException(repositoryId));
  }
}
