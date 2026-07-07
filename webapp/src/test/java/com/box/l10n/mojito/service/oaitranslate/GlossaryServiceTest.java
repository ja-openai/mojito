package com.box.l10n.mojito.service.oaitranslate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.Asset;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.TMTextUnit;
import com.box.l10n.mojito.entity.glossary.Glossary;
import com.box.l10n.mojito.entity.glossary.GlossaryTermMetadata;
import com.box.l10n.mojito.service.glossary.GlossaryRepository;
import com.box.l10n.mojito.service.glossary.GlossaryStorageService;
import com.box.l10n.mojito.service.glossary.GlossaryTermEvidenceRepository;
import com.box.l10n.mojito.service.glossary.GlossaryTermMetadataRepository;
import com.box.l10n.mojito.service.oaitranslate.GlossaryService.GlossaryTerm;
import com.box.l10n.mojito.service.oaitranslate.GlossaryService.GlossaryTrie;
import com.box.l10n.mojito.service.oaitranslate.GlossaryService.MatchType;
import com.box.l10n.mojito.service.oaitranslate.GlossaryService.MatchedGlossaryTerm;
import com.box.l10n.mojito.service.repository.RepositoryRepository;
import com.box.l10n.mojito.service.tm.search.TextUnitDTO;
import com.box.l10n.mojito.service.tm.search.TextUnitSearcher;
import com.box.l10n.mojito.service.tm.search.TextUnitSearcherParameters;
import java.util.List;
import java.util.Set;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GlossaryServiceTest {

  static Logger logger = LoggerFactory.getLogger(GlossaryServiceTest.class);

  GlossaryTerm term(long id, String source, boolean caseSensitive) {
    return new GlossaryTerm(
        id,
        null,
        null,
        "TERM",
        source,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        false,
        caseSensitive,
        List.of());
  }

  @Test
  public void matchInsensitiveTerm_anyCasing() {
    GlossaryTrie trie = new GlossaryTrie();
    trie.addTerm(term(1, "Settings", false));

    assertMatches(trie, "settings");
    assertMatches(trie, "SeTtInGs");
    assertMatches(trie, "SETTINGS");
    assertMatches(trie, "Settings");
  }

  @Test
  public void matchSensitiveTerm_onlyExactCasing() {
    GlossaryTrie trie = new GlossaryTrie();
    trie.addTerm(term(2, "Settings", true));

    assertMatches(trie, "Settings");
    assertNoMatch(trie, "settings");
    assertNoMatch(trie, "SETTINGS");
    assertNoMatch(trie, "SeTtInGs");
  }

  @Test
  public void mixOfSensitiveAndInsensitiveTerms() {
    GlossaryTrie trie = new GlossaryTrie();
    trie.addTerm(term(3, "Settings", true));
    trie.addTerm(term(4, "Accounts", false));

    Set<GlossaryTerm> match1 = trie.findTerms("Settings");
    assertTrue(match1.stream().anyMatch(t -> t.text().equals("Settings")));

    Set<GlossaryTerm> match2 = trie.findTerms("ACCOUNTS");
    assertTrue(match2.stream().anyMatch(t -> t.text().equals("Accounts")));

    Set<GlossaryTerm> match3 = trie.findTerms("settings");
    assertFalse(match3.stream().anyMatch(t -> t.text().equals("Settings")));
  }

  @Test
  public void findMatches_includes_match_metadata() {
    GlossaryTrie trie = new GlossaryTrie();
    trie.addTerm(term(5, "Settings", true));

    List<MatchedGlossaryTerm> matches = trie.findMatches("Open Settings now");

    assertEquals(1, matches.size());
    MatchedGlossaryTerm match = matches.get(0);
    assertEquals("Settings", match.glossaryTerm().source());
    assertEquals(MatchType.EXACT, match.matchType());
    assertEquals(5, match.startIndex());
    assertEquals(13, match.endIndex());
    assertEquals("Settings", match.matchedText());
  }

  @Test
  public void findMatches_prefers_exact_over_case_insensitive_for_same_span() {
    GlossaryTrie trie = new GlossaryTrie();
    trie.addTerm(term(6, "Settings", false));

    List<MatchedGlossaryTerm> exactMatches = trie.findMatches("Settings");
    assertEquals(1, exactMatches.size());
    assertEquals(MatchType.EXACT, exactMatches.get(0).matchType());

    List<MatchedGlossaryTerm> insensitiveMatches = trie.findMatches("SETTINGS");
    assertEquals(1, insensitiveMatches.size());
    assertEquals(MatchType.CASE_INSENSITIVE, insensitiveMatches.get(0).matchType());
  }

  @Test
  public void sameSourceDifferentTermIdsAllMatch() {
    GlossaryTrie trie = new GlossaryTrie();

    trie.addTerm(
        new GlossaryTerm(
            7L,
            101L,
            "Core",
            "TERM",
            "Settings",
            "Core definition",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            false,
            false,
            List.of()));
    trie.addTerm(
        new GlossaryTerm(
            8L,
            102L,
            "Brand",
            "TERM",
            "Settings",
            "Brand definition",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            false,
            false,
            List.of()));

    List<MatchedGlossaryTerm> matches = trie.findMatches("Settings");

    assertEquals(2, matches.size());
    assertEquals(Long.valueOf(101L), matches.get(0).glossaryTerm().glossaryId());
    assertEquals("Core", matches.get(0).glossaryTerm().glossaryName());
    assertEquals("Core definition", matches.get(0).glossaryTerm().comment());
    assertEquals(Long.valueOf(102L), matches.get(1).glossaryTerm().glossaryId());
    assertEquals("Brand", matches.get(1).glossaryTerm().glossaryName());
    assertEquals("Brand definition", matches.get(1).glossaryTerm().comment());
  }

  @Test
  public void sameTermIdIsLoadedOnce() {
    GlossaryTrie trie = new GlossaryTrie();

    trie.addTerm(term(9L, "Settings", false));
    trie.addTerm(term(9L, "Settings", false));

    List<MatchedGlossaryTerm> matches = trie.findMatches("Settings");

    assertEquals(1, matches.size());
    assertEquals(9L, matches.get(0).glossaryTerm().tmTextUnitId());
  }

  @Test
  public void linkedGlossariesAreMergedByRepository() {
    TextUnitSearcher textUnitSearcher = mock(TextUnitSearcher.class);
    GlossaryRepository glossaryRepository = mock(GlossaryRepository.class);
    GlossaryStorageService glossaryStorageService = mock(GlossaryStorageService.class);
    GlossaryTermMetadataRepository glossaryTermMetadataRepository =
        mock(GlossaryTermMetadataRepository.class);
    GlossaryTermEvidenceRepository glossaryTermEvidenceRepository =
        mock(GlossaryTermEvidenceRepository.class);
    RepositoryRepository repositoryRepository = mock(RepositoryRepository.class);
    GlossaryService glossaryService =
        new GlossaryService(
            textUnitSearcher,
            glossaryRepository,
            glossaryStorageService,
            glossaryTermMetadataRepository,
            glossaryTermEvidenceRepository,
            repositoryRepository);

    Glossary coreGlossary = glossary(201L, "Core", "core-glossary");
    Glossary brandGlossary = glossary(202L, "Brand", "brand-glossary");
    Asset coreAsset = asset(301L);
    Asset brandAsset = asset(302L);

    when(glossaryRepository.findEnabledByRepositoryId(77L))
        .thenReturn(List.of(coreGlossary, brandGlossary));
    when(glossaryStorageService.ensureCanonicalAsset(coreGlossary)).thenReturn(coreAsset);
    when(glossaryStorageService.ensureCanonicalAsset(brandGlossary)).thenReturn(brandAsset);
    when(glossaryTermMetadataRepository.findByGlossaryIdAndTmTextUnitIdIn(any(), any()))
        .thenReturn(List.of());
    when(glossaryTermEvidenceRepository.findByGlossaryTermMetadataIdInOrderBySortOrderAsc(any()))
        .thenReturn(List.of());
    when(textUnitSearcher.search(any(TextUnitSearcherParameters.class)))
        .thenAnswer(
            invocation -> {
              TextUnitSearcherParameters parameters = invocation.getArgument(0);
              Long assetId = parameters.getAssetId();
              if (coreAsset.getId().equals(assetId)) {
                return List.of(textUnitDTO(11L, "Settings"));
              }
              if (brandAsset.getId().equals(assetId)) {
                return List.of(textUnitDTO(12L, "Workspace"));
              }
              return List.of();
            });

    GlossaryTrie trie = glossaryService.loadLinkedGlossaryTrieForLocale(77L, "fr-FR");
    List<MatchedGlossaryTerm> matches = trie.findMatches("Open Settings in Workspace");

    assertEquals(2, matches.size());
    assertEquals("Core", matches.get(0).glossaryTerm().glossaryName());
    assertEquals("Settings", matches.get(0).glossaryTerm().source());
    assertEquals("Brand", matches.get(1).glossaryTerm().glossaryName());
    assertEquals("Workspace", matches.get(1).glossaryTerm().source());
  }

  @Test
  public void linkedGlossariesSkipNonApprovedTerms() {
    TextUnitSearcher textUnitSearcher = mock(TextUnitSearcher.class);
    GlossaryRepository glossaryRepository = mock(GlossaryRepository.class);
    GlossaryStorageService glossaryStorageService = mock(GlossaryStorageService.class);
    GlossaryTermMetadataRepository glossaryTermMetadataRepository =
        mock(GlossaryTermMetadataRepository.class);
    GlossaryTermEvidenceRepository glossaryTermEvidenceRepository =
        mock(GlossaryTermEvidenceRepository.class);
    RepositoryRepository repositoryRepository = mock(RepositoryRepository.class);
    GlossaryService glossaryService =
        new GlossaryService(
            textUnitSearcher,
            glossaryRepository,
            glossaryStorageService,
            glossaryTermMetadataRepository,
            glossaryTermEvidenceRepository,
            repositoryRepository);

    Glossary glossary = glossary(201L, "Core", "core-glossary");
    when(glossaryRepository.findEnabledByRepositoryId(77L)).thenReturn(List.of(glossary));
    when(glossaryStorageService.ensureCanonicalAsset(glossary)).thenReturn(asset(301L));
    when(glossaryTermMetadataRepository.findByGlossaryIdAndTmTextUnitIdIn(any(), any()))
        .thenReturn(
            List.of(
                metadata(glossary, 11L, GlossaryTermMetadata.STATUS_CANDIDATE),
                metadata(glossary, 12L, GlossaryTermMetadata.STATUS_APPROVED)));
    when(glossaryTermEvidenceRepository.findByGlossaryTermMetadataIdInOrderBySortOrderAsc(any()))
        .thenReturn(List.of());
    when(textUnitSearcher.search(any(TextUnitSearcherParameters.class)))
        .thenReturn(List.of(textUnitDTO(11L, "Settings"), textUnitDTO(12L, "Workspace")));

    List<MatchedGlossaryTerm> matches =
        glossaryService.findMatchesForRepositoryAndLocale(
            77L, null, null, "fr-FR", "Open Settings in Workspace", null);

    assertEquals(1, matches.size());
    assertEquals("Workspace", matches.get(0).matchedText());
  }

  @Test
  public void linkedGlossariesReturnNullWhenRepositoryHasNone() {
    GlossaryService glossaryService =
        new GlossaryService(
            mock(TextUnitSearcher.class),
            mock(GlossaryRepository.class),
            mock(GlossaryStorageService.class),
            mock(GlossaryTermMetadataRepository.class),
            mock(GlossaryTermEvidenceRepository.class),
            mock(RepositoryRepository.class));

    when(glossaryService.glossaryRepository.findEnabledByRepositoryId(88L)).thenReturn(List.of());

    assertNull(glossaryService.loadLinkedGlossaryTrieForLocale(88L, "fr-FR"));
  }

  @Test
  public void findMatchesForRepositoryAndLocaleUsesRepositoryLinkedGlossaries() {
    TextUnitSearcher textUnitSearcher = mock(TextUnitSearcher.class);
    GlossaryRepository glossaryRepository = mock(GlossaryRepository.class);
    GlossaryStorageService glossaryStorageService = mock(GlossaryStorageService.class);
    GlossaryTermMetadataRepository glossaryTermMetadataRepository =
        mock(GlossaryTermMetadataRepository.class);
    GlossaryTermEvidenceRepository glossaryTermEvidenceRepository =
        mock(GlossaryTermEvidenceRepository.class);
    RepositoryRepository repositoryRepository = mock(RepositoryRepository.class);
    GlossaryService glossaryService =
        new GlossaryService(
            textUnitSearcher,
            glossaryRepository,
            glossaryStorageService,
            glossaryTermMetadataRepository,
            glossaryTermEvidenceRepository,
            repositoryRepository);

    Glossary glossary = glossary(201L, "Core", "core-glossary");
    when(glossaryRepository.findEnabledByRepositoryId(77L)).thenReturn(List.of(glossary));
    when(glossaryStorageService.ensureCanonicalAsset(glossary)).thenReturn(asset(301L));
    when(glossaryTermMetadataRepository.findByGlossaryIdAndTmTextUnitIdIn(any(), any()))
        .thenReturn(List.of());
    when(glossaryTermEvidenceRepository.findByGlossaryTermMetadataIdInOrderBySortOrderAsc(any()))
        .thenReturn(List.of());
    when(textUnitSearcher.search(any(TextUnitSearcherParameters.class)))
        .thenReturn(List.of(textUnitDTO(11L, "Settings")));

    List<MatchedGlossaryTerm> matches =
        glossaryService.findMatchesForRepositoryAndLocale(
            77L, null, null, "fr-FR", "Open Settings", null);

    assertEquals(1, matches.size());
    assertEquals("Core", matches.get(0).glossaryTerm().glossaryName());
    assertEquals("Settings", matches.get(0).matchedText());
  }

  @Test
  public void managedGlossaryMatchingUsesCanonicalAssetTermsOnly() {
    TextUnitSearcher textUnitSearcher = mock(TextUnitSearcher.class);
    GlossaryRepository glossaryRepository = mock(GlossaryRepository.class);
    GlossaryStorageService glossaryStorageService = mock(GlossaryStorageService.class);
    GlossaryTermMetadataRepository glossaryTermMetadataRepository =
        mock(GlossaryTermMetadataRepository.class);
    GlossaryTermEvidenceRepository glossaryTermEvidenceRepository =
        mock(GlossaryTermEvidenceRepository.class);
    RepositoryRepository repositoryRepository = mock(RepositoryRepository.class);
    GlossaryService glossaryService =
        new GlossaryService(
            textUnitSearcher,
            glossaryRepository,
            glossaryStorageService,
            glossaryTermMetadataRepository,
            glossaryTermEvidenceRepository,
            repositoryRepository);

    Glossary glossary = glossary(201L, "Core", "core-glossary");
    Asset canonicalAsset = asset(301L);
    when(glossaryRepository.findEnabledByRepositoryId(77L)).thenReturn(List.of(glossary));
    when(glossaryStorageService.ensureCanonicalAsset(glossary)).thenReturn(canonicalAsset);
    when(glossaryTermMetadataRepository.findByGlossaryIdAndTmTextUnitIdIn(any(), any()))
        .thenReturn(List.of());
    when(glossaryTermEvidenceRepository.findByGlossaryTermMetadataIdInOrderBySortOrderAsc(any()))
        .thenReturn(List.of());
    when(textUnitSearcher.search(any(TextUnitSearcherParameters.class)))
        .thenAnswer(
            invocation -> {
              TextUnitSearcherParameters parameters = invocation.getArgument(0);
              if (canonicalAsset.getId().equals(parameters.getAssetId())) {
                return List.of(textUnitDTO(11L, "Settings"));
              }
              return List.of(textUnitDTO(99L, "Stray repository term"));
            });

    List<MatchedGlossaryTerm> matches =
        glossaryService.findMatchesForRepositoryAndLocale(
            77L, null, null, "fr-FR", "Open Settings and Stray repository term", null);

    assertEquals(1, matches.size());
    assertEquals(11L, matches.get(0).glossaryTerm().tmTextUnitId());
    assertEquals("Settings", matches.get(0).matchedText());
  }

  @Test
  public void findMatchesForRepositoryAndLocaleCanExcludeActiveGlossaryTerm() {
    TextUnitSearcher textUnitSearcher = mock(TextUnitSearcher.class);
    GlossaryRepository glossaryRepository = mock(GlossaryRepository.class);
    GlossaryStorageService glossaryStorageService = mock(GlossaryStorageService.class);
    GlossaryTermMetadataRepository glossaryTermMetadataRepository =
        mock(GlossaryTermMetadataRepository.class);
    GlossaryTermEvidenceRepository glossaryTermEvidenceRepository =
        mock(GlossaryTermEvidenceRepository.class);
    RepositoryRepository repositoryRepository = mock(RepositoryRepository.class);
    GlossaryService glossaryService =
        new GlossaryService(
            textUnitSearcher,
            glossaryRepository,
            glossaryStorageService,
            glossaryTermMetadataRepository,
            glossaryTermEvidenceRepository,
            repositoryRepository);

    Glossary glossary = glossary(201L, "Core", "core-glossary");
    when(glossaryRepository.findEnabledByRepositoryId(77L)).thenReturn(List.of(glossary));
    when(glossaryStorageService.ensureCanonicalAsset(glossary)).thenReturn(asset(301L));
    when(glossaryTermMetadataRepository.findByGlossaryIdAndTmTextUnitIdIn(any(), any()))
        .thenReturn(List.of());
    when(glossaryTermEvidenceRepository.findByGlossaryTermMetadataIdInOrderBySortOrderAsc(any()))
        .thenReturn(List.of());
    when(textUnitSearcher.search(any(TextUnitSearcherParameters.class)))
        .thenReturn(List.of(textUnitDTO(11L, "Settings")));

    List<MatchedGlossaryTerm> matches =
        glossaryService.findMatchesForRepositoryAndLocale(
            77L, null, null, "fr-FR", "Open Settings", 11L);

    assertTrue(matches.isEmpty());
  }

  void assertMatches(GlossaryTrie trie, String text) {
    Set<GlossaryTerm> results = trie.findTerms(text);
    assertFalse(results.isEmpty(), "Expected match for: '" + text + "'");
  }

  void assertNoMatch(GlossaryTrie trie, String text) {
    Set<GlossaryTerm> results = trie.findTerms(text);
    assertTrue(results.isEmpty(), "Expected no match for: '" + text + "'");
  }

  private Glossary glossary(long glossaryId, String glossaryName, String backingRepositoryName) {
    Repository repository = new Repository();
    repository.setName(backingRepositoryName);

    Glossary glossary = new Glossary();
    glossary.setId(glossaryId);
    glossary.setName(glossaryName);
    glossary.setBackingRepository(repository);
    return glossary;
  }

  private Asset asset(long assetId) {
    Asset asset = new Asset();
    asset.setId(assetId);
    return asset;
  }

  private GlossaryTermMetadata metadata(Glossary glossary, long tmTextUnitId, String status) {
    TMTextUnit tmTextUnit = new TMTextUnit();
    tmTextUnit.setId(tmTextUnitId);

    GlossaryTermMetadata metadata = new GlossaryTermMetadata();
    metadata.setGlossary(glossary);
    metadata.setTmTextUnit(tmTextUnit);
    metadata.setStatus(status);
    return metadata;
  }

  private TextUnitDTO textUnitDTO(long tmTextUnitId, String source) {
    TextUnitDTO textUnitDTO = new TextUnitDTO();
    textUnitDTO.setTmTextUnitId(tmTextUnitId);
    textUnitDTO.setName(source);
    textUnitDTO.setSource(source);
    return textUnitDTO;
  }
}
