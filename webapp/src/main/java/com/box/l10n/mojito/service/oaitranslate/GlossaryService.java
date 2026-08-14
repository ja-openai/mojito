package com.box.l10n.mojito.service.oaitranslate;

import com.box.l10n.mojito.entity.Asset;
import com.box.l10n.mojito.entity.Locale;
import com.box.l10n.mojito.entity.glossary.Glossary;
import com.box.l10n.mojito.entity.glossary.GlossaryTermEvidence;
import com.box.l10n.mojito.entity.glossary.GlossaryTermMetadata;
import com.box.l10n.mojito.service.glossary.GlossaryRepository;
import com.box.l10n.mojito.service.glossary.GlossaryStorageService;
import com.box.l10n.mojito.service.glossary.GlossaryTermEvidenceRepository;
import com.box.l10n.mojito.service.glossary.GlossaryTermMetadataRepository;
import com.box.l10n.mojito.service.locale.LocaleService;
import com.box.l10n.mojito.service.repository.RepositoryRepository;
import com.box.l10n.mojito.service.tm.search.TextUnitDTO;
import com.box.l10n.mojito.service.tm.search.TextUnitSearcher;
import com.box.l10n.mojito.service.tm.search.TextUnitSearcherParameters;
import com.box.l10n.mojito.service.tm.search.UsedFilter;
import com.box.l10n.mojito.service.tm.textunitdtocache.TextUnitDTOsCacheService;
import com.box.l10n.mojito.service.tm.textunitdtocache.UpdateType;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class GlossaryService {

  /** logger */
  static Logger logger = LoggerFactory.getLogger(GlossaryService.class);

  static final String CACHE_LOOKUP_METRIC = "GlossaryService.cache.lookup";
  static final String CACHE_LOAD_DURATION_METRIC = "GlossaryService.cache.loadDuration";
  static final int MAX_CACHED_GLOSSARIES = 128;

  TextUnitSearcher textUnitSearcher;
  GlossaryRepository glossaryRepository;
  GlossaryStorageService glossaryStorageService;
  GlossaryTermMetadataRepository glossaryTermMetadataRepository;
  GlossaryTermEvidenceRepository glossaryTermEvidenceRepository;
  RepositoryRepository repositoryRepository;
  private final TextUnitDTOsCacheService textUnitDTOsCacheService;
  private final LocaleService localeService;
  private final MeterRegistry meterRegistry;
  private final Cache<GlossaryCacheKey, GlossaryTrie> glossaryTrieCache;

  public GlossaryService(
      TextUnitSearcher textUnitSearcher,
      GlossaryRepository glossaryRepository,
      GlossaryStorageService glossaryStorageService,
      GlossaryTermMetadataRepository glossaryTermMetadataRepository,
      GlossaryTermEvidenceRepository glossaryTermEvidenceRepository,
      RepositoryRepository repositoryRepository,
      TextUnitDTOsCacheService textUnitDTOsCacheService,
      LocaleService localeService,
      MeterRegistry meterRegistry,
      GlossaryCacheConfigurationProperties glossaryCacheConfigurationProperties) {
    this.textUnitSearcher = textUnitSearcher;
    this.glossaryRepository = glossaryRepository;
    this.glossaryStorageService = glossaryStorageService;
    this.glossaryTermMetadataRepository = glossaryTermMetadataRepository;
    this.glossaryTermEvidenceRepository = glossaryTermEvidenceRepository;
    this.repositoryRepository = repositoryRepository;
    this.textUnitDTOsCacheService = textUnitDTOsCacheService;
    this.localeService = localeService;
    this.meterRegistry = meterRegistry;
    this.glossaryTrieCache =
        Caffeine.newBuilder()
            .maximumSize(MAX_CACHED_GLOSSARIES)
            .expireAfterWrite(glossaryCacheConfigurationProperties.getTtl())
            .build();
  }

  /**
   * Only include target and targetComment if included in localized file, ie not rejected. There
   * won't be a way to reject the source term in the UI, it has to be done with a repository push.
   *
   * @param glossaryRepositoryName
   * @param bcp47Locale The locale to load glossary terms for (e.g., "en-US")
   * @return A GlossaryTrie with all valid glossary terms for this locale
   */
  public GlossaryTrie loadGlossaryTrieForLocale(String glossaryRepositoryName, String bcp47Locale) {
    Glossary glossary =
        glossaryRepository
            .findByNameIgnoreCaseWithBackingRepository(glossaryRepositoryName)
            .orElse(null);
    if (glossary != null) {
      return loadGlossaryTrieForGlossaries(List.of(glossary), bcp47Locale);
    }

    return getCachedGlossaryTrie(
        GlossaryCacheKey.forRepository(glossaryRepositoryName, bcp47Locale),
        () -> loadGlossaryTrieForRepository(null, glossaryRepositoryName, bcp47Locale));
  }

  public GlossaryTrie loadLinkedGlossaryTrieForLocale(Long repositoryId, String bcp47Locale) {
    List<Glossary> glossaries = glossaryRepository.findEnabledByRepositoryId(repositoryId);
    if (glossaries.isEmpty()) {
      return null;
    }
    return loadGlossaryTrieForGlossaries(glossaries, bcp47Locale);
  }

  public List<MatchedGlossaryTerm> findMatchesForRepositoryAndLocale(
      Long repositoryId,
      String repositoryName,
      String glossaryName,
      String bcp47Locale,
      String sourceText,
      Long excludeTmTextUnitId) {
    if (sourceText == null || sourceText.isBlank()) {
      return List.of();
    }
    if (bcp47Locale == null || bcp47Locale.isBlank()) {
      throw new IllegalArgumentException("Locale tag is required");
    }
    if ((glossaryName == null || glossaryName.isBlank())
        && repositoryId == null
        && (repositoryName == null || repositoryName.isBlank())) {
      throw new IllegalArgumentException(
          "Repository id, repository name, or glossary name is required");
    }

    GlossaryTrie glossaryTrie =
        glossaryName != null
            ? loadGlossaryTrieForLocale(glossaryName, bcp47Locale)
            : loadLinkedGlossaryTrieForLocale(
                resolveRepositoryId(repositoryId, repositoryName), bcp47Locale);
    if (glossaryTrie == null) {
      return List.of();
    }

    return glossaryTrie.findMatches(sourceText).stream()
        .filter(
            match ->
                excludeTmTextUnitId == null
                    || match.glossaryTerm().tmTextUnitId() != excludeTmTextUnitId)
        .toList();
  }

  private Long resolveRepositoryId(Long repositoryId, String repositoryName) {
    if (repositoryId != null) {
      return repositoryId;
    }
    if (repositoryName == null || repositoryName.isBlank()) {
      return null;
    }

    com.box.l10n.mojito.entity.Repository repository =
        repositoryRepository.findByName(repositoryName.trim());
    if (repository == null || Boolean.TRUE.equals(repository.getDeleted())) {
      throw new IllegalArgumentException("Repository not found: " + repositoryName);
    }
    return repository.getId();
  }

  private GlossaryTrie loadGlossaryTrieForGlossaries(
      List<Glossary> glossaries, String bcp47Locale) {
    return getCachedGlossaryTrie(
        GlossaryCacheKey.forGlossaries(glossaries, bcp47Locale),
        () -> {
          GlossaryTrie glossaryTrie = new GlossaryTrie();
          for (Glossary glossary : glossaries) {
            loadGlossaryTrieForRepository(
                glossaryTrie, glossary, glossary.getBackingRepository().getName(), bcp47Locale);
          }
          return glossaryTrie;
        });
  }

  private GlossaryTrie getCachedGlossaryTrie(
      GlossaryCacheKey cacheKey, Supplier<GlossaryTrie> glossaryTrieLoader) {
    AtomicBoolean loaded = new AtomicBoolean();
    GlossaryTrie glossaryTrie =
        glossaryTrieCache.get(
            cacheKey,
            ignored -> {
              loaded.set(true);
              return meterRegistry
                  .timer(CACHE_LOAD_DURATION_METRIC, "scope", cacheKey.scope())
                  .record(glossaryTrieLoader);
            });
    meterRegistry
        .counter(
            CACHE_LOOKUP_METRIC, "scope", cacheKey.scope(), "result", loaded.get() ? "miss" : "hit")
        .increment();
    return glossaryTrie;
  }

  private record GlossaryCacheKey(
      List<GlossaryConfiguration> glossaryConfigurations,
      String repositoryName,
      String bcp47Locale) {

    private static GlossaryCacheKey forGlossaries(List<Glossary> glossaries, String bcp47Locale) {
      return new GlossaryCacheKey(
          glossaries.stream()
              .map(
                  glossary ->
                      new GlossaryConfiguration(glossary.getId(), glossary.getLastModifiedDate()))
              .toList(),
          null,
          bcp47Locale);
    }

    private static GlossaryCacheKey forRepository(String repositoryName, String bcp47Locale) {
      return new GlossaryCacheKey(List.of(), repositoryName, bcp47Locale);
    }

    private String scope() {
      return glossaryConfigurations.isEmpty() ? "legacy_repository" : "managed";
    }
  }

  private record GlossaryConfiguration(Long glossaryId, ZonedDateTime lastModifiedDate) {}

  private GlossaryTrie loadGlossaryTrieForRepository(
      Glossary glossary, String repositoryName, String bcp47Locale) {
    return loadGlossaryTrieForRepository(new GlossaryTrie(), glossary, repositoryName, bcp47Locale);
  }

  private GlossaryTrie loadGlossaryTrieForRepository(
      GlossaryTrie glossaryTrie, Glossary glossary, String repositoryName, String bcp47Locale) {
    Asset canonicalAsset =
        glossary == null ? null : glossaryStorageService.ensureCanonicalAsset(glossary);
    Locale sourceLocale =
        glossary == null ? null : glossary.getBackingRepository().getSourceLocale();
    List<TextUnitDTO> textUnitDTOForGlossary =
        getTextUnitDTOForGlossary(canonicalAsset, sourceLocale, repositoryName, bcp47Locale);
    Map<String, TextUnitDTO> localizedTextUnitByTermKey =
        canonicalAsset == null
            ? Map.of()
            : getLocalizedTextUnitByTermKey(
                canonicalAsset, sourceLocale, bcp47Locale, textUnitDTOForGlossary);
    Map<Long, GlossaryTermMetadata> metadataByTmTextUnitId =
        getMetadataByTmTextUnitId(glossary, textUnitDTOForGlossary);
    Map<Long, List<GlossaryEvidence>> evidenceByTmTextUnitId =
        getEvidenceByTmTextUnitId(metadataByTmTextUnitId);

    for (TextUnitDTO textUnitDTO : textUnitDTOForGlossary) {
      GlossaryTermMetadata metadata = metadataByTmTextUnitId.get(textUnitDTO.getTmTextUnitId());
      if (!shouldIncludeInMatches(metadata)) {
        continue;
      }

      String target = null;
      String targetComment = null;

      boolean doNotTranslate = getDoNotTranslate(textUnitDTO, metadata);
      boolean caseSensitive = getCaseSensitive(textUnitDTO, metadata);

      if (doNotTranslate) {
        target = textUnitDTO.getSource();
      }

      TextUnitDTO localizedTextUnit =
          localizedTextUnitByTermKey.getOrDefault(textUnitDTO.getName(), textUnitDTO);
      if (localizedTextUnit.isIncludedInLocalizedFile()) {
        if (localizedTextUnit.getTarget() != null) {
          target = localizedTextUnit.getTarget();
        }
        targetComment = localizedTextUnit.getTargetComment();
      }

      glossaryTrie.addTerm(
          new GlossaryTerm(
              textUnitDTO.getTmTextUnitId(),
              glossary == null ? null : glossary.getId(),
              glossary == null ? null : glossary.getName(),
              textUnitDTO.getName(),
              textUnitDTO.getSource(),
              getGlossaryComment(textUnitDTO, metadata),
              textUnitDTO.getComment(),
              metadata == null ? null : metadata.getPartOfSpeech(),
              metadata == null ? null : metadata.getTermType(),
              metadata == null ? null : metadata.getEnforcement(),
              metadata == null ? null : metadata.getStatus(),
              metadata == null ? null : metadata.getProvenance(),
              target,
              targetComment,
              doNotTranslate,
              caseSensitive,
              evidenceByTmTextUnitId.getOrDefault(textUnitDTO.getTmTextUnitId(), List.of())));
    }

    return glossaryTrie;
  }

  private boolean shouldIncludeInMatches(GlossaryTermMetadata metadata) {
    return metadata == null
        || GlossaryTermMetadata.STATUS_APPROVED.equalsIgnoreCase(metadata.getStatus());
  }

  /**
   * For glossaries, we get all terms, even if they don't have translation. Some terms can be
   * defined generally and apply to all locale, and some term may need specific translation per
   * locale.
   *
   * <p>A global DNT for example just need an entry for English.
   */
  List<TextUnitDTO> getTextUnitDTOForGlossary(String repositoryName, String bcp47Locale) {
    return getTextUnitDTOForGlossary(null, null, repositoryName, bcp47Locale);
  }

  List<TextUnitDTO> getTextUnitDTOForGlossary(
      Asset canonicalAsset, Locale sourceLocale, String repositoryName, String bcp47Locale) {
    if (canonicalAsset != null) {
      if (sourceLocale != null && sourceLocale.getId() != null) {
        return getCachedAssetTextUnits(canonicalAsset, sourceLocale, true);
      }

      TextUnitSearcherParameters parameters = new TextUnitSearcherParameters();
      parameters.setAssetId(canonicalAsset.getId());
      parameters.setUsedFilter(UsedFilter.USED);
      parameters.setForRootLocale(true);
      parameters.setRootLocaleExcluded(false);
      return textUnitSearcher.search(parameters);
    }

    TextUnitSearcherParameters textUnitSearcherParameters = new TextUnitSearcherParameters();

    textUnitSearcherParameters.setRepositoryNames(List.of(repositoryName));
    textUnitSearcherParameters.setLocaleTags(List.of(bcp47Locale));
    textUnitSearcherParameters.setUsedFilter(UsedFilter.USED);

    return textUnitSearcher.search(textUnitSearcherParameters);
  }

  private Map<String, TextUnitDTO> getLocalizedTextUnitByTermKey(
      Asset canonicalAsset,
      Locale sourceLocale,
      String bcp47Locale,
      List<TextUnitDTO> sourceTextUnits) {
    Locale targetLocale = localeService.findByBcp47Tag(bcp47Locale);
    if (targetLocale != null && targetLocale.getId() != null) {
      boolean isRootLocale =
          sourceLocale != null && targetLocale.getId().equals(sourceLocale.getId());
      if (isRootLocale) {
        return indexTextUnitsByTermKey(sourceTextUnits);
      }
      return indexTextUnitsByTermKey(
          getCachedAssetTextUnits(canonicalAsset, targetLocale, isRootLocale));
    }

    TextUnitSearcherParameters parameters = new TextUnitSearcherParameters();
    parameters.setAssetId(canonicalAsset.getId());
    parameters.setUsedFilter(UsedFilter.USED);
    parameters.setLocaleTags(List.of(bcp47Locale));
    parameters.setRootLocaleExcluded(false);

    return indexTextUnitsByTermKey(textUnitSearcher.search(parameters));
  }

  private List<TextUnitDTO> getCachedAssetTextUnits(
      Asset asset, Locale locale, boolean isRootLocale) {
    return textUnitDTOsCacheService
        .getTextUnitDTOsForAssetAndLocale(
            asset.getId(), locale.getId(), isRootLocale, UpdateType.ALWAYS)
        .stream()
        .filter(TextUnitDTO::isUsed)
        .toList();
  }

  private Map<String, TextUnitDTO> indexTextUnitsByTermKey(List<TextUnitDTO> textUnits) {
    Map<String, TextUnitDTO> textUnitByTermKey = new LinkedHashMap<>();
    for (TextUnitDTO textUnitDTO : textUnits) {
      textUnitByTermKey.putIfAbsent(textUnitDTO.getName(), textUnitDTO);
    }
    return textUnitByTermKey;
  }

  private Map<Long, GlossaryTermMetadata> getMetadataByTmTextUnitId(
      Glossary glossary, List<TextUnitDTO> textUnitDTOForGlossary) {
    if (glossary == null || textUnitDTOForGlossary.isEmpty()) {
      return Map.of();
    }

    List<Long> tmTextUnitIds =
        textUnitDTOForGlossary.stream().map(TextUnitDTO::getTmTextUnitId).toList();
    Map<Long, GlossaryTermMetadata> metadataByTmTextUnitId = new HashMap<>();
    for (GlossaryTermMetadata metadata :
        glossaryTermMetadataRepository.findByGlossaryIdAndTmTextUnitIdIn(
            glossary.getId(), tmTextUnitIds)) {
      metadataByTmTextUnitId.put(metadata.getTmTextUnit().getId(), metadata);
    }
    return metadataByTmTextUnitId;
  }

  private Map<Long, List<GlossaryEvidence>> getEvidenceByTmTextUnitId(
      Map<Long, GlossaryTermMetadata> metadataByTmTextUnitId) {
    if (metadataByTmTextUnitId.isEmpty()) {
      return Map.of();
    }

    Map<Long, List<GlossaryEvidence>> evidenceByTmTextUnitId = new LinkedHashMap<>();
    List<Long> metadataIds =
        metadataByTmTextUnitId.values().stream().map(GlossaryTermMetadata::getId).toList();
    Map<Long, Long> metadataIdToTmTextUnitId =
        metadataByTmTextUnitId.values().stream()
            .filter(metadata -> metadata.getId() != null)
            .filter(metadata -> metadata.getTmTextUnit() != null)
            .filter(metadata -> metadata.getTmTextUnit().getId() != null)
            .collect(
                java.util.stream.Collectors.toMap(
                    GlossaryTermMetadata::getId, metadata -> metadata.getTmTextUnit().getId()));

    for (GlossaryTermEvidence evidence :
        glossaryTermEvidenceRepository.findByGlossaryTermMetadataIdInOrderBySortOrderAsc(
            metadataIds)) {
      Long tmTextUnitId = metadataIdToTmTextUnitId.get(evidence.getGlossaryTermMetadata().getId());
      if (tmTextUnitId == null) {
        continue;
      }
      evidenceByTmTextUnitId
          .computeIfAbsent(tmTextUnitId, ignored -> new ArrayList<>())
          .add(
              new GlossaryEvidence(
                  evidence.getId(),
                  evidence.getEvidenceType(),
                  evidence.getCaption(),
                  evidence.getImageKey(),
                  evidence.getTmTextUnit() == null ? null : evidence.getTmTextUnit().getId(),
                  evidence.getCropX(),
                  evidence.getCropY(),
                  evidence.getCropWidth(),
                  evidence.getCropHeight(),
                  evidence.getSortOrder()));
    }

    return evidenceByTmTextUnitId;
  }

  private boolean getDoNotTranslate(TextUnitDTO textUnitDTO, GlossaryTermMetadata metadata) {
    if (metadata != null) {
      return Boolean.TRUE.equals(metadata.getDoNotTranslate());
    }
    return textUnitDTO.getComment() != null && textUnitDTO.getComment().contains("DNT");
  }

  private boolean getCaseSensitive(TextUnitDTO textUnitDTO, GlossaryTermMetadata metadata) {
    if (metadata != null) {
      return Boolean.TRUE.equals(metadata.getCaseSensitive());
    }
    return textUnitDTO.getComment() != null && textUnitDTO.getComment().contains("CAS");
  }

  private String getGlossaryComment(TextUnitDTO textUnitDTO, GlossaryTermMetadata metadata) {
    return textUnitDTO.getComment();
  }

  public record GlossaryTerm(
      long tmTextUnitId,
      Long glossaryId,
      String glossaryName,
      String name,
      String source,
      String comment,
      String definition,
      String partOfSpeech,
      String termType,
      String enforcement,
      String status,
      String provenance,
      String target,
      String targetComment,
      boolean doNotTranslate,
      boolean caseSensitive,
      List<GlossaryEvidence> evidence)
      implements CharTrie.Term {

    @Override
    public String text() {
      return source;
    }
  }

  public record GlossaryEvidence(
      Long id,
      String evidenceType,
      String caption,
      String imageKey,
      Long tmTextUnitId,
      Integer cropX,
      Integer cropY,
      Integer cropWidth,
      Integer cropHeight,
      Integer sortOrder) {}

  public enum MatchType {
    EXACT,
    CASE_INSENSITIVE,
    FUZZY,
    SEMANTIC
  }

  public record MatchedGlossaryTerm(
      GlossaryTerm glossaryTerm,
      MatchType matchType,
      int startIndex,
      int endIndex,
      String matchedText) {}

  public static class GlossaryTrie {
    CharTrie<GlossaryTerm> glossaryTrieSensitive = new CharTrie<>(true);
    CharTrie<GlossaryTerm> glossaryTrieInsensitive = new CharTrie<>(false);
    Set<Long> loadedTermIds = new HashSet<>();

    public boolean addTerm(GlossaryTerm term) {
      if (!loadedTermIds.add(term.tmTextUnitId())) {
        return false;
      }

      glossaryTrieSensitive.addTerm(term);

      if (!term.caseSensitive()) {
        glossaryTrieInsensitive.addTerm(term);
      }
      return true;
    }

    public Set<GlossaryTerm> findTerms(String text) {
      Set<GlossaryTerm> terms = new LinkedHashSet<>();
      for (MatchedGlossaryTerm match : findMatches(text)) {
        terms.add(match.glossaryTerm());
      }
      return terms;
    }

    public List<MatchedGlossaryTerm> findMatches(String text) {
      Map<MatchKey, MatchedGlossaryTerm> matchesByKey = new LinkedHashMap<>();

      for (CharTrie.Match<GlossaryTerm> match : glossaryTrieSensitive.findMatches(text)) {
        MatchKey key = new MatchKey(match.term(), match.startIndex(), match.endIndex());
        matchesByKey.put(
            key,
            new MatchedGlossaryTerm(
                match.term(),
                MatchType.EXACT,
                match.startIndex(),
                match.endIndex(),
                match.matchedText()));
      }

      for (CharTrie.Match<GlossaryTerm> match : glossaryTrieInsensitive.findMatches(text)) {
        MatchKey key = new MatchKey(match.term(), match.startIndex(), match.endIndex());
        matchesByKey.putIfAbsent(
            key,
            new MatchedGlossaryTerm(
                match.term(),
                MatchType.CASE_INSENSITIVE,
                match.startIndex(),
                match.endIndex(),
                match.matchedText()));
      }

      List<MatchedGlossaryTerm> matches = new ArrayList<>(matchesByKey.values());
      matches.sort(
          Comparator.comparingInt(MatchedGlossaryTerm::startIndex)
              .thenComparingInt(MatchedGlossaryTerm::endIndex)
              .thenComparing(match -> match.glossaryTerm().source()));
      return matches;
    }

    private record MatchKey(GlossaryTerm glossaryTerm, int startIndex, int endIndex) {}
  }
}
