package com.box.l10n.mojito.service.badtranslation;

import com.box.l10n.mojito.entity.Locale;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.RepositoryLocale;
import com.box.l10n.mojito.service.locale.LocaleService;
import com.box.l10n.mojito.service.repository.RepositoryRepository;
import com.box.l10n.mojito.service.tm.search.TextUnitDTO;
import com.box.l10n.mojito.service.tm.search.TextUnitSearcher;
import com.box.l10n.mojito.service.tm.search.TextUnitSearcherParameters;
import com.box.l10n.mojito.utils.ServerConfig;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class BadTranslationLookupService {

  public enum ResolutionStatus {
    NOT_FOUND,
    UNIQUE_MATCH,
    AMBIGUOUS
  }

  public enum LocaleResolutionStrategy {
    EXACT,
    NORMALIZED,
    LANGUAGE_ONLY
  }

  public record FindTranslationInput(String stringId, String observedLocale, String repository) {}

  public record RepositoryRef(Long id, String name) {}

  public record LocaleRef(Long id, String bcp47Tag) {}

  public record LocaleResolution(
      String observedLocale,
      LocaleRef resolvedLocale,
      LocaleResolutionStrategy strategy,
      boolean usedFallback) {}

  public record TranslationCandidate(
      RepositoryRef repository,
      Long tmTextUnitId,
      String textUnitLink,
      Long tmTextUnitCurrentVariantId,
      Long tmTextUnitVariantId,
      String stringId,
      String assetPath,
      Long assetId,
      String source,
      String target,
      String targetComment,
      String status,
      boolean includedInLocalizedFile,
      ZonedDateTime createdDate,
      boolean canReject) {}

  public record FindTranslationResult(
      RepositoryRef requestedRepository,
      LocaleResolution localeResolution,
      ResolutionStatus resolutionStatus,
      int matchCount,
      List<TranslationCandidate> candidates) {}

  private final RepositoryRepository repositoryRepository;
  private final LocaleService localeService;
  private final TextUnitSearcher textUnitSearcher;
  private final ServerConfig serverConfig;

  public BadTranslationLookupService(
      RepositoryRepository repositoryRepository,
      LocaleService localeService,
      TextUnitSearcher textUnitSearcher,
      ServerConfig serverConfig) {
    this.repositoryRepository = Objects.requireNonNull(repositoryRepository);
    this.localeService = Objects.requireNonNull(localeService);
    this.textUnitSearcher = Objects.requireNonNull(textUnitSearcher);
    this.serverConfig = Objects.requireNonNull(serverConfig);
  }

  public FindTranslationResult findTranslation(FindTranslationInput input) {
    FindTranslationInput validatedInput = validateFindTranslationInput(input);
    Optional<Repository> requestedRepository =
        resolveOptionalRepository(validatedInput.repository());
    LocaleResolution localeResolution =
        resolveLocale(validatedInput.observedLocale(), requestedRepository.orElse(null));
    LocaleRef resolvedLocale = localeResolution.resolvedLocale();

    TextUnitSearcherParameters searcherParameters = new TextUnitSearcherParameters();
    searcherParameters.setName(validatedInput.stringId());
    searcherParameters.setLocaleTags(List.of(resolvedLocale.bcp47Tag()));
    searcherParameters.setPluralFormsFiltered(false);
    searcherParameters.setLimit(200);
    requestedRepository.ifPresent(
        repository -> searcherParameters.setRepositoryNames(List.of(repository.getName())));

    List<TranslationCandidate> candidates =
        textUnitSearcher.search(searcherParameters).stream()
            .filter(textUnitDTO -> validatedInput.stringId().equals(textUnitDTO.getName()))
            .filter(
                textUnitDTO ->
                    localeMatchesCandidate(
                        localeResolution,
                        validatedInput.observedLocale(),
                        textUnitDTO.getTargetLocale()))
            .map(this::toCandidate)
            .sorted(
                java.util.Comparator.comparing(
                        (TranslationCandidate candidate) -> candidate.repository().name())
                    .thenComparing(
                        candidate -> candidate.assetPath() == null ? "" : candidate.assetPath()))
            .toList();

    ResolutionStatus resolutionStatus =
        candidates.isEmpty()
            ? ResolutionStatus.NOT_FOUND
            : candidates.size() == 1 ? ResolutionStatus.UNIQUE_MATCH : ResolutionStatus.AMBIGUOUS;

    return new FindTranslationResult(
        requestedRepository.map(this::toRepositoryRef).orElse(null),
        localeResolution,
        resolutionStatus,
        candidates.size(),
        candidates);
  }

  private FindTranslationInput validateFindTranslationInput(FindTranslationInput input) {
    if (input == null) {
      throw new IllegalArgumentException("input is required");
    }

    return new FindTranslationInput(
        requireNonBlank(input.stringId(), "stringId"),
        requireNonBlank(input.observedLocale(), "observedLocale"),
        normalizeOptional(input.repository()));
  }

  private TranslationCandidate toCandidate(TextUnitDTO textUnitDTO) {
    RepositoryRef repositoryRef = new RepositoryRef(null, textUnitDTO.getRepositoryName());

    return new TranslationCandidate(
        repositoryRef,
        textUnitDTO.getTmTextUnitId(),
        buildTextUnitLink(textUnitDTO.getTmTextUnitId(), textUnitDTO.getTargetLocale()),
        textUnitDTO.getTmTextUnitCurrentVariantId(),
        textUnitDTO.getTmTextUnitVariantId(),
        textUnitDTO.getName(),
        textUnitDTO.getAssetPath(),
        textUnitDTO.getAssetId(),
        textUnitDTO.getSource(),
        textUnitDTO.getTarget(),
        textUnitDTO.getTargetComment(),
        textUnitDTO.getStatus() == null ? null : textUnitDTO.getStatus().name(),
        textUnitDTO.isIncludedInLocalizedFile(),
        textUnitDTO.getCreatedDate(),
        textUnitDTO.getTmTextUnitCurrentVariantId() != null
            && textUnitDTO.getTmTextUnitVariantId() != null);
  }

  private LocaleResolution resolveLocale(String observedLocale, Repository repository) {
    String normalizedObservedLocale = normalizeLocaleTag(observedLocale);

    Locale exactMatch = localeService.findByBcp47Tag(observedLocale);
    if (exactMatch != null && isRepositoryLocaleMatch(repository, exactMatch)) {
      return new LocaleResolution(
          observedLocale,
          new LocaleRef(exactMatch.getId(), exactMatch.getBcp47Tag()),
          LocaleResolutionStrategy.EXACT,
          false);
    }

    if (!normalizedObservedLocale.equalsIgnoreCase(observedLocale)) {
      Locale normalizedMatch = localeService.findByBcp47Tag(normalizedObservedLocale);
      if (normalizedMatch != null && isRepositoryLocaleMatch(repository, normalizedMatch)) {
        return new LocaleResolution(
            observedLocale,
            new LocaleRef(normalizedMatch.getId(), normalizedMatch.getBcp47Tag()),
            LocaleResolutionStrategy.NORMALIZED,
            true);
      }
    }

    String languageOnlyLocale = extractLanguageTag(normalizedObservedLocale);
    if (!languageOnlyLocale.equalsIgnoreCase(normalizedObservedLocale)) {
      Locale languageOnlyMatch = localeService.findByBcp47Tag(languageOnlyLocale);
      if (languageOnlyMatch != null && isRepositoryLocaleMatch(repository, languageOnlyMatch)) {
        return new LocaleResolution(
            observedLocale,
            new LocaleRef(languageOnlyMatch.getId(), languageOnlyMatch.getBcp47Tag()),
            LocaleResolutionStrategy.LANGUAGE_ONLY,
            true);
      }
    }

    throw new IllegalArgumentException(
        "Could not resolve locale from observedLocale: " + observedLocale);
  }

  private Optional<Repository> resolveOptionalRepository(String repositoryName) {
    if (repositoryName == null) {
      return Optional.empty();
    }

    Repository repository = repositoryRepository.findByName(repositoryName);
    if (repository == null) {
      throw new IllegalArgumentException("Unknown repository: " + repositoryName);
    }
    return Optional.of(repository);
  }

  private RepositoryRef toRepositoryRef(Repository repository) {
    return new RepositoryRef(repository.getId(), repository.getName());
  }

  private boolean isRepositoryLocaleMatch(Repository repository, Locale locale) {
    if (repository == null) {
      return true;
    }

    if (repository.getRepositoryLocales() == null || repository.getRepositoryLocales().isEmpty()) {
      return true;
    }

    return repository.getRepositoryLocales().stream()
        .map(RepositoryLocale::getLocale)
        .filter(Objects::nonNull)
        .map(Locale::getId)
        .anyMatch(locale.getId()::equals);
  }

  private boolean localeMatchesCandidate(
      LocaleResolution localeResolution, String observedLocale, String candidateLocale) {
    if (candidateLocale == null || candidateLocale.isBlank()) {
      return false;
    }

    String normalizedCandidateLocale = normalizeLocaleTag(candidateLocale);
    String normalizedObservedLocale = normalizeLocaleTag(observedLocale);
    String resolvedLocale = localeResolution.resolvedLocale().bcp47Tag();

    if (resolvedLocale.equalsIgnoreCase(normalizedCandidateLocale)
        || normalizedObservedLocale.equalsIgnoreCase(normalizedCandidateLocale)) {
      return true;
    }

    return localeResolution.usedFallback()
        && extractLanguageTag(normalizedObservedLocale)
            .equalsIgnoreCase(extractLanguageTag(normalizedCandidateLocale));
  }

  private String normalizeLocaleTag(String localeTag) {
    return localeTag.trim().replace('_', '-');
  }

  private String extractLanguageTag(String localeTag) {
    int separatorIndex = localeTag.indexOf('-');
    if (separatorIndex < 0) {
      return localeTag;
    }
    return localeTag.substring(0, separatorIndex);
  }

  private String requireNonBlank(String value, String fieldName) {
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException(fieldName + " is required");
    }
    return value.trim();
  }

  private String normalizeOptional(String value) {
    if (value == null || value.trim().isEmpty()) {
      return null;
    }
    return value.trim();
  }

  private String buildTextUnitLink(Long tmTextUnitId, String localeTag) {
    String baseUrl = normalizeServerBaseUrl();
    if (tmTextUnitId == null || baseUrl == null) {
      return null;
    }

    String textUnitLink = baseUrl + "/text-units/" + tmTextUnitId;
    if (localeTag == null || localeTag.isBlank()) {
      return textUnitLink;
    }
    return textUnitLink + "?locale=" + URLEncoder.encode(localeTag.trim(), StandardCharsets.UTF_8);
  }

  private String normalizeServerBaseUrl() {
    String configured = serverConfig.getUrl();
    if (configured == null || configured.isBlank()) {
      return null;
    }

    String trimmed = configured.trim();
    while (trimmed.endsWith("/")) {
      trimmed = trimmed.substring(0, trimmed.length() - 1);
    }
    return trimmed.isBlank() ? null : trimmed;
  }
}
