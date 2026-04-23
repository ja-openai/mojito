package com.box.l10n.mojito.service.oaitranslate;

import com.box.l10n.mojito.entity.glossary.Glossary;
import com.box.l10n.mojito.entity.glossary.GlossaryTermEvidence;
import com.box.l10n.mojito.entity.glossary.GlossaryTermMetadata;
import com.box.l10n.mojito.service.glossary.GlossaryRepository;
import com.box.l10n.mojito.service.glossary.GlossaryTermEvidenceRepository;
import com.box.l10n.mojito.service.glossary.GlossaryTermMetadataRepository;
import com.box.l10n.mojito.service.repository.RepositoryRepository;
import com.box.l10n.mojito.service.tm.search.TextUnitDTO;
import com.box.l10n.mojito.service.tm.search.TextUnitSearcher;
import com.box.l10n.mojito.service.tm.search.TextUnitSearcherParameters;
import com.box.l10n.mojito.service.tm.search.UsedFilter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class GlossaryService {

  /** logger */
  static Logger logger = LoggerFactory.getLogger(GlossaryService.class);

  TextUnitSearcher textUnitSearcher;
  GlossaryRepository glossaryRepository;
  GlossaryTermMetadataRepository glossaryTermMetadataRepository;
  GlossaryTermEvidenceRepository glossaryTermEvidenceRepository;
  RepositoryRepository repositoryRepository;

  public GlossaryService(
      TextUnitSearcher textUnitSearcher,
      GlossaryRepository glossaryRepository,
      GlossaryTermMetadataRepository glossaryTermMetadataRepository,
      GlossaryTermEvidenceRepository glossaryTermEvidenceRepository,
      RepositoryRepository repositoryRepository) {
    this.textUnitSearcher = textUnitSearcher;
    this.glossaryRepository = glossaryRepository;
    this.glossaryTermMetadataRepository = glossaryTermMetadataRepository;
    this.glossaryTermEvidenceRepository = glossaryTermEvidenceRepository;
    this.repositoryRepository = repositoryRepository;
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

    return loadGlossaryTrieForRepository(null, glossaryRepositoryName, bcp47Locale);
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
    GlossaryTrie glossaryTrie = new GlossaryTrie();
    for (Glossary glossary : glossaries) {
      loadGlossaryTrieForRepository(
          glossaryTrie, glossary, glossary.getBackingRepository().getName(), bcp47Locale);
    }
    return glossaryTrie;
  }

  private GlossaryTrie loadGlossaryTrieForRepository(
      Glossary glossary, String repositoryName, String bcp47Locale) {
    return loadGlossaryTrieForRepository(new GlossaryTrie(), glossary, repositoryName, bcp47Locale);
  }

  private GlossaryTrie loadGlossaryTrieForRepository(
      GlossaryTrie glossaryTrie, Glossary glossary, String repositoryName, String bcp47Locale) {
    List<TextUnitDTO> textUnitDTOForGlossary =
        getTextUnitDTOForGlossary(repositoryName, bcp47Locale);
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

      if (textUnitDTO.isIncludedInLocalizedFile()) {
        if (textUnitDTO.getTarget() != null) {
          target = textUnitDTO.getTarget();
        }
        targetComment = textUnitDTO.getTargetComment();
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
    TextUnitSearcherParameters textUnitSearcherParameters = new TextUnitSearcherParameters();

    textUnitSearcherParameters.setRepositoryNames(List.of(repositoryName));
    textUnitSearcherParameters.setLocaleTags(List.of(bcp47Locale));
    textUnitSearcherParameters.setUsedFilter(UsedFilter.USED);

    return textUnitSearcher.search(textUnitSearcherParameters);
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
    Set<TermKey> loadedTerms = new HashSet<>();

    public boolean addTerm(GlossaryTerm term) {
      if (!loadedTerms.add(new TermKey(term.source(), term.caseSensitive()))) {
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

    private record TermKey(String source, boolean caseSensitive) {}
  }
}
