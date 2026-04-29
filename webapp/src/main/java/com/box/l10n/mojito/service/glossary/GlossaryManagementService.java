package com.box.l10n.mojito.service.glossary;

import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.glossary.Glossary;
import com.box.l10n.mojito.service.security.user.UserService;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GlossaryManagementService {

  public static final int DEFAULT_LIMIT = 50;
  public static final int MAX_LIMIT = 200;

  private final GlossaryRepository glossaryRepository;
  private final com.box.l10n.mojito.service.repository.RepositoryRepository repositoryRepository;
  private final GlossaryTermMetadataRepository glossaryTermMetadataRepository;
  private final GlossaryTermEvidenceRepository glossaryTermEvidenceRepository;
  private final GlossaryTermTranslationProposalRepository glossaryTermTranslationProposalRepository;
  private final GlossaryStorageService glossaryStorageService;
  private final UserService userService;

  public GlossaryManagementService(
      GlossaryRepository glossaryRepository,
      com.box.l10n.mojito.service.repository.RepositoryRepository repositoryRepository,
      GlossaryTermMetadataRepository glossaryTermMetadataRepository,
      GlossaryTermEvidenceRepository glossaryTermEvidenceRepository,
      GlossaryTermTranslationProposalRepository glossaryTermTranslationProposalRepository,
      GlossaryStorageService glossaryStorageService,
      UserService userService) {
    this.glossaryRepository = glossaryRepository;
    this.repositoryRepository = repositoryRepository;
    this.glossaryTermMetadataRepository = glossaryTermMetadataRepository;
    this.glossaryTermEvidenceRepository = glossaryTermEvidenceRepository;
    this.glossaryTermTranslationProposalRepository = glossaryTermTranslationProposalRepository;
    this.glossaryStorageService = glossaryStorageService;
    this.userService = userService;
  }

  @Transactional(readOnly = true)
  public SearchGlossariesView searchGlossaries(String searchQuery, Boolean enabled, Integer limit) {
    requireGlossaryReader();
    boolean canManageDisabled = userService.isCurrentUserAdminOrPm();

    String normalizedSearchQuery = normalizeSearchQuery(searchQuery);
    int resolvedLimit = normalizeLimit(limit);

    List<Glossary> filteredGlossaries =
        glossaryRepository
            .findAll(
                Sort.by(Sort.Order.asc("priority"), Sort.Order.asc("name"), Sort.Order.asc("id")))
            .stream()
            .filter(
                glossary ->
                    normalizedSearchQuery == null || matches(glossary, normalizedSearchQuery))
            .filter(
                glossary ->
                    canManageDisabled
                        ? enabled == null
                            || Objects.equals(Boolean.TRUE.equals(glossary.getEnabled()), enabled)
                        : Boolean.TRUE.equals(glossary.getEnabled()))
            .toList();

    List<GlossarySummary> glossaries =
        filteredGlossaries.stream().limit(resolvedLimit).map(this::toSummary).toList();

    return new SearchGlossariesView(glossaries, filteredGlossaries.size());
  }

  @Transactional(readOnly = true)
  public GlossaryDetail getGlossary(Long glossaryId) {
    requireGlossaryReader();
    Glossary glossary =
        glossaryRepository
            .findByIdWithBindings(glossaryId)
            .orElseThrow(() -> new IllegalArgumentException("Glossary not found: " + glossaryId));
    if (!userService.isCurrentUserAdminOrPm() && !Boolean.TRUE.equals(glossary.getEnabled())) {
      throw new AccessDeniedException("Only enabled glossaries are visible to translators");
    }
    return toDetail(glossary);
  }

  @Transactional
  public GlossaryDetail createGlossary(
      String name,
      String description,
      Boolean enabled,
      Integer priority,
      String scopeMode,
      List<String> localeTags,
      List<Long> repositoryIds,
      List<Long> excludedRepositoryIds) {
    requireAdmin();

    String normalizedName = normalizeName(name);
    ensureNameAvailable(normalizedName, null);

    Glossary glossary = new Glossary();
    glossary.setName(normalizedName);
    glossary.setDescription(normalizeDescription(description));
    glossary.setEnabled(enabled == null ? Boolean.TRUE : enabled);
    glossary.setPriority(normalizePriority(priority));
    String normalizedScopeMode = normalizeScopeMode(scopeMode);
    glossary.setScopeMode(normalizedScopeMode);
    glossary.setBackingRepository(
        glossaryStorageService.createManagedBackingRepository(normalizedName));
    glossary.setAssetPath(GlossaryStorageService.DEFAULT_ASSET_PATH);
    glossary.setRepositories(
        Glossary.SCOPE_MODE_SELECTED_REPOSITORIES.equals(normalizedScopeMode)
            ? resolveRepositories(repositoryIds)
            : new LinkedHashSet<>());
    glossary.setExcludedRepositories(
        Glossary.SCOPE_MODE_GLOBAL.equals(normalizedScopeMode)
            ? resolveRepositories(excludedRepositoryIds)
            : new LinkedHashSet<>());

    Glossary savedGlossary = glossaryRepository.save(glossary);
    glossaryStorageService.ensureCanonicalAsset(savedGlossary);
    glossaryStorageService.replaceLocales(savedGlossary, localeTags);

    return getGlossary(savedGlossary.getId());
  }

  @Transactional
  public GlossaryDetail updateGlossary(
      Long glossaryId,
      String name,
      String description,
      Boolean enabled,
      Integer priority,
      String scopeMode,
      List<String> localeTags,
      List<Long> repositoryIds,
      List<Long> excludedRepositoryIds) {
    return updateGlossary(
        glossaryId,
        name,
        description,
        enabled,
        priority,
        scopeMode,
        localeTags,
        repositoryIds,
        excludedRepositoryIds,
        null);
  }

  @Transactional
  public GlossaryDetail updateGlossary(
      Long glossaryId,
      String name,
      String description,
      Boolean enabled,
      Integer priority,
      String scopeMode,
      List<String> localeTags,
      List<Long> repositoryIds,
      List<Long> excludedRepositoryIds,
      String backingRepositoryName) {
    requireAdmin();

    Glossary glossary =
        glossaryRepository
            .findByIdWithBindings(glossaryId)
            .orElseThrow(() -> new IllegalArgumentException("Glossary not found: " + glossaryId));

    String normalizedName = normalizeName(name);
    ensureNameAvailable(normalizedName, glossary.getId());

    glossary.setName(normalizedName);
    glossary.setDescription(normalizeDescription(description));
    glossary.setEnabled(enabled == null ? Boolean.TRUE : enabled);
    glossary.setPriority(normalizePriority(priority));
    String normalizedScopeMode = normalizeScopeMode(scopeMode);
    glossary.setScopeMode(normalizedScopeMode);
    glossary.setRepositories(
        Glossary.SCOPE_MODE_SELECTED_REPOSITORIES.equals(normalizedScopeMode)
            ? resolveRepositories(repositoryIds)
            : new LinkedHashSet<>());
    glossary.setExcludedRepositories(
        Glossary.SCOPE_MODE_GLOBAL.equals(normalizedScopeMode)
            ? resolveRepositories(excludedRepositoryIds)
            : new LinkedHashSet<>());

    glossaryRepository.save(glossary);
    glossaryStorageService.ensureCanonicalAsset(glossary);
    if (hasLocaleChanges(glossary, localeTags)) {
      glossaryStorageService.replaceLocales(glossary, localeTags);
    }
    if (backingRepositoryName != null) {
      glossaryStorageService.renameManagedBackingRepository(glossary, backingRepositoryName);
    }
    return getGlossary(glossary.getId());
  }

  @Transactional
  public void deleteGlossary(Long glossaryId) {
    requireAdmin();
    Glossary glossary =
        glossaryRepository
            .findByIdWithBindings(glossaryId)
            .orElseThrow(() -> new IllegalArgumentException("Glossary not found: " + glossaryId));
    glossaryTermTranslationProposalRepository.deleteByGlossaryId(glossaryId);
    glossaryTermEvidenceRepository.deleteByGlossaryId(glossaryId);
    glossaryTermMetadataRepository.deleteByGlossaryId(glossaryId);
    glossary.getRepositories().clear();
    glossary.getExcludedRepositories().clear();
    glossaryStorageService.deleteManagedBackingRepository(glossary);
    glossaryRepository.delete(glossary);
  }

  private GlossarySummary toSummary(Glossary glossary) {
    int repositoryCount = getScopeRepositoryCount(glossary);
    return new GlossarySummary(
        glossary.getId(),
        glossary.getCreatedDate(),
        glossary.getLastModifiedDate(),
        glossary.getName(),
        glossary.getDescription(),
        Boolean.TRUE.equals(glossary.getEnabled()),
        glossary.getPriority(),
        normalizeScopeMode(glossary.getScopeMode()),
        glossary.getAssetPath(),
        repositoryCount,
        new RepositoryRef(
            glossary.getBackingRepository().getId(), glossary.getBackingRepository().getName()));
  }

  private GlossaryDetail toDetail(Glossary glossary) {
    List<RepositoryRef> repositories = toRepositoryRefs(glossary.getRepositories());
    List<RepositoryRef> excludedRepositories = toRepositoryRefs(glossary.getExcludedRepositories());
    List<String> localeTags =
        glossary.getBackingRepository().getRepositoryLocales().stream()
            .filter(repositoryLocale -> repositoryLocale.getParentLocale() != null)
            .map(repositoryLocale -> repositoryLocale.getLocale().getBcp47Tag())
            .filter(Objects::nonNull)
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .toList();

    return new GlossaryDetail(
        glossary.getId(),
        glossary.getCreatedDate(),
        glossary.getLastModifiedDate(),
        glossary.getName(),
        glossary.getDescription(),
        Boolean.TRUE.equals(glossary.getEnabled()),
        glossary.getPriority(),
        normalizeScopeMode(glossary.getScopeMode()),
        new RepositoryRef(
            glossary.getBackingRepository().getId(), glossary.getBackingRepository().getName()),
        glossary.getAssetPath(),
        localeTags,
        repositories,
        excludedRepositories);
  }

  private List<RepositoryRef> toRepositoryRefs(Set<Repository> repositories) {
    if (repositories == null || repositories.isEmpty()) {
      return List.of();
    }
    return repositories.stream()
        .filter(repository -> !Boolean.TRUE.equals(repository.getDeleted()))
        .filter(repository -> !Boolean.TRUE.equals(repository.getHidden()))
        .sorted(
            Comparator.comparing(Repository::getName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Repository::getId))
        .map(repository -> new RepositoryRef(repository.getId(), repository.getName()))
        .toList();
  }

  private boolean hasLocaleChanges(Glossary glossary, List<String> localeTags) {
    String rootLocaleTag =
        glossary.getBackingRepository().getRepositoryLocales().stream()
            .filter(repositoryLocale -> repositoryLocale.getParentLocale() == null)
            .map(repositoryLocale -> repositoryLocale.getLocale().getBcp47Tag())
            .findFirst()
            .orElse(null);

    Set<String> currentLocaleTags =
        glossary.getBackingRepository().getRepositoryLocales().stream()
            .filter(repositoryLocale -> repositoryLocale.getParentLocale() != null)
            .map(repositoryLocale -> repositoryLocale.getLocale().getBcp47Tag())
            .map(tag -> tag.toLowerCase(Locale.ROOT))
            .collect(Collectors.toCollection(LinkedHashSet::new));

    Set<String> nextLocaleTags =
        (localeTags == null ? List.<String>of() : localeTags)
            .stream()
                .filter(tag -> tag != null && !tag.isBlank())
                .map(String::trim)
                .filter(tag -> rootLocaleTag == null || !tag.equalsIgnoreCase(rootLocaleTag))
                .map(tag -> tag.toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));

    return !currentLocaleTags.equals(nextLocaleTags);
  }

  private boolean matches(Glossary glossary, String normalizedSearchQuery) {
    return glossary.getName().toLowerCase().contains(normalizedSearchQuery)
        || (glossary.getDescription() != null
            && glossary.getDescription().toLowerCase().contains(normalizedSearchQuery))
        || (glossary.getBackingRepository() != null
            && glossary
                .getBackingRepository()
                .getName()
                .toLowerCase()
                .contains(normalizedSearchQuery));
  }

  private Set<Repository> resolveRepositories(List<Long> repositoryIds) {
    List<Long> normalizedIds =
        (repositoryIds == null ? List.<Long>of() : repositoryIds)
            .stream().filter(id -> id != null && id > 0).distinct().toList();
    if (normalizedIds.isEmpty()) {
      return new LinkedHashSet<>();
    }

    List<Repository> repositories =
        repositoryRepository.findByIdInAndDeletedFalseAndHiddenFalseOrderByNameAsc(normalizedIds);
    if (repositories.size() != normalizedIds.size()) {
      Set<Long> foundIds =
          repositories.stream().map(Repository::getId).collect(java.util.stream.Collectors.toSet());
      List<Long> missingIds = normalizedIds.stream().filter(id -> !foundIds.contains(id)).toList();
      throw new IllegalArgumentException("Unknown repositories: " + missingIds);
    }
    return new LinkedHashSet<>(repositories);
  }

  private void ensureNameAvailable(String normalizedName, Long currentId) {
    glossaryRepository
        .findByNameIgnoreCase(normalizedName)
        .filter(existing -> !Objects.equals(existing.getId(), currentId))
        .ifPresent(
            existing -> {
              throw new IllegalArgumentException("Glossary already exists: " + normalizedName);
            });
  }

  private int normalizeLimit(Integer limit) {
    if (limit == null) {
      return DEFAULT_LIMIT;
    }
    return Math.max(1, Math.min(MAX_LIMIT, limit));
  }

  private String normalizeSearchQuery(String searchQuery) {
    if (searchQuery == null) {
      return null;
    }
    String trimmed = searchQuery.trim().toLowerCase();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private String normalizeName(String name) {
    String normalized = name == null ? "" : name.trim().replaceAll("\\s+", " ");
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException("Glossary name is required");
    }
    if (normalized.length() > Glossary.NAME_MAX_LENGTH) {
      throw new IllegalArgumentException(
          "Glossary name must be at most " + Glossary.NAME_MAX_LENGTH + " characters");
    }
    return normalized;
  }

  private String normalizeDescription(String description) {
    if (description == null) {
      return null;
    }
    String normalized = description.trim();
    if (normalized.isEmpty()) {
      return null;
    }
    if (normalized.length() > Glossary.DESCRIPTION_MAX_LENGTH) {
      throw new IllegalArgumentException(
          "Glossary description must be at most "
              + Glossary.DESCRIPTION_MAX_LENGTH
              + " characters");
    }
    return normalized;
  }

  private int normalizePriority(Integer priority) {
    return priority == null ? 0 : priority;
  }

  private String normalizeScopeMode(String scopeMode) {
    if (scopeMode == null || scopeMode.isBlank()) {
      return Glossary.SCOPE_MODE_GLOBAL;
    }
    String normalized = scopeMode.trim().toUpperCase();
    if (!Glossary.SCOPE_MODE_GLOBAL.equals(normalized)
        && !Glossary.SCOPE_MODE_SELECTED_REPOSITORIES.equals(normalized)) {
      throw new IllegalArgumentException("Unknown glossary scope mode: " + scopeMode);
    }
    return normalized;
  }

  private int getScopeRepositoryCount(Glossary glossary) {
    String scopeMode = normalizeScopeMode(glossary.getScopeMode());
    if (Glossary.SCOPE_MODE_GLOBAL.equals(scopeMode)) {
      return glossary.getExcludedRepositories() == null
          ? 0
          : glossary.getExcludedRepositories().size();
    }
    return glossary.getRepositories() == null ? 0 : glossary.getRepositories().size();
  }

  private void requireGlossaryReader() {
    if (!userService.isCurrentUserTranslationRole()) {
      throw new AccessDeniedException("Translation role required");
    }
  }

  private void requireAdmin() {
    if (!userService.isCurrentUserAdmin()) {
      throw new AccessDeniedException("Admin role required");
    }
  }

  public record SearchGlossariesView(List<GlossarySummary> glossaries, long totalCount) {}

  public record GlossarySummary(
      Long id,
      ZonedDateTime createdDate,
      ZonedDateTime lastModifiedDate,
      String name,
      String description,
      boolean enabled,
      int priority,
      String scopeMode,
      String assetPath,
      int repositoryCount,
      RepositoryRef backingRepository) {}

  public record GlossaryDetail(
      Long id,
      ZonedDateTime createdDate,
      ZonedDateTime lastModifiedDate,
      String name,
      String description,
      boolean enabled,
      int priority,
      String scopeMode,
      RepositoryRef backingRepository,
      String assetPath,
      List<String> localeTags,
      List<RepositoryRef> repositories,
      List<RepositoryRef> excludedRepositories) {}

  public record RepositoryRef(Long id, String name) {}
}
