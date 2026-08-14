package com.box.l10n.mojito.service.oaitranslate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
import com.box.l10n.mojito.service.locale.LocaleService;
import com.box.l10n.mojito.service.oaitranslate.GlossaryService.GlossaryTerm;
import com.box.l10n.mojito.service.oaitranslate.GlossaryService.GlossaryTrie;
import com.box.l10n.mojito.service.oaitranslate.GlossaryService.MatchType;
import com.box.l10n.mojito.service.oaitranslate.GlossaryService.MatchedGlossaryTerm;
import com.box.l10n.mojito.service.repository.RepositoryRepository;
import com.box.l10n.mojito.service.tm.search.TextUnitDTO;
import com.box.l10n.mojito.service.tm.search.TextUnitSearcher;
import com.box.l10n.mojito.service.tm.search.TextUnitSearcherParameters;
import com.box.l10n.mojito.service.tm.textunitdtocache.TextUnitDTOsCacheService;
import com.box.l10n.mojito.service.tm.textunitdtocache.UpdateType;
import com.google.common.collect.ImmutableList;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
            repositoryRepository,
            mock(TextUnitDTOsCacheService.class),
            mock(LocaleService.class),
            new SimpleMeterRegistry(),
            new GlossaryCacheConfigurationProperties());

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
            repositoryRepository,
            mock(TextUnitDTOsCacheService.class),
            mock(LocaleService.class),
            new SimpleMeterRegistry(),
            new GlossaryCacheConfigurationProperties());

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
            mock(RepositoryRepository.class),
            mock(TextUnitDTOsCacheService.class),
            mock(LocaleService.class),
            new SimpleMeterRegistry(),
            new GlossaryCacheConfigurationProperties());

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
            repositoryRepository,
            mock(TextUnitDTOsCacheService.class),
            mock(LocaleService.class),
            new SimpleMeterRegistry(),
            new GlossaryCacheConfigurationProperties());

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
            repositoryRepository,
            mock(TextUnitDTOsCacheService.class),
            mock(LocaleService.class),
            new SimpleMeterRegistry(),
            new GlossaryCacheConfigurationProperties());

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
            repositoryRepository,
            mock(TextUnitDTOsCacheService.class),
            mock(LocaleService.class),
            new SimpleMeterRegistry(),
            new GlossaryCacheConfigurationProperties());

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

  @Test
  public void sharedManagedGlossaryScopeReusesTrieAcrossRepositoriesAndNamedLookup() {
    TextUnitSearcher textUnitSearcher = mock(TextUnitSearcher.class);
    GlossaryRepository glossaryRepository = mock(GlossaryRepository.class);
    GlossaryStorageService glossaryStorageService = mock(GlossaryStorageService.class);
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    GlossaryService glossaryService =
        glossaryService(
            textUnitSearcher, glossaryRepository, glossaryStorageService, meterRegistry);
    Glossary glossary = glossary(201L, "Core", "core-glossary");

    when(glossaryRepository.findEnabledByRepositoryId(77L)).thenReturn(List.of(glossary));
    when(glossaryRepository.findEnabledByRepositoryId(88L)).thenReturn(List.of(glossary));
    when(glossaryRepository.findByNameIgnoreCaseWithBackingRepository("Core"))
        .thenReturn(java.util.Optional.of(glossary));
    when(glossaryStorageService.ensureCanonicalAsset(glossary)).thenReturn(asset(301L));
    when(textUnitSearcher.search(any(TextUnitSearcherParameters.class)))
        .thenReturn(List.of(textUnitDTO(11L, "Settings")));

    GlossaryTrie repositoryTrie = glossaryService.loadLinkedGlossaryTrieForLocale(77L, "fr-FR");
    GlossaryTrie otherRepositoryTrie =
        glossaryService.loadLinkedGlossaryTrieForLocale(88L, "fr-FR");
    GlossaryTrie namedTrie = glossaryService.loadGlossaryTrieForLocale("Core", "fr-FR");

    assertSame(repositoryTrie, otherRepositoryTrie);
    assertSame(repositoryTrie, namedTrie);
    verify(textUnitSearcher, times(2)).search(any(TextUnitSearcherParameters.class));
    assertEquals(
        1.0,
        meterRegistry
            .get(GlossaryService.CACHE_LOOKUP_METRIC)
            .tags("scope", "managed", "result", "miss")
            .counter()
            .count());
    assertEquals(
        2.0,
        meterRegistry
            .get(GlossaryService.CACHE_LOOKUP_METRIC)
            .tags("scope", "managed", "result", "hit")
            .counter()
            .count());
  }

  @Test
  public void glossaryCacheSeparatesManagedScopesLocalesAndLegacyRepositories() {
    TextUnitSearcher textUnitSearcher = mock(TextUnitSearcher.class);
    GlossaryRepository glossaryRepository = mock(GlossaryRepository.class);
    GlossaryStorageService glossaryStorageService = mock(GlossaryStorageService.class);
    GlossaryService glossaryService =
        glossaryService(
            textUnitSearcher,
            glossaryRepository,
            glossaryStorageService,
            new SimpleMeterRegistry());
    Glossary coreGlossary = glossary(201L, "Core", "core-glossary");
    Glossary brandGlossary = glossary(202L, "Brand", "brand-glossary");

    when(glossaryRepository.findEnabledByRepositoryId(77L)).thenReturn(List.of(coreGlossary));
    when(glossaryRepository.findEnabledByRepositoryId(88L)).thenReturn(List.of(brandGlossary));
    when(glossaryRepository.findByNameIgnoreCaseWithBackingRepository("legacy-glossary"))
        .thenReturn(java.util.Optional.empty());
    when(glossaryStorageService.ensureCanonicalAsset(coreGlossary)).thenReturn(asset(301L));
    when(glossaryStorageService.ensureCanonicalAsset(brandGlossary)).thenReturn(asset(302L));
    when(textUnitSearcher.search(any(TextUnitSearcherParameters.class)))
        .thenReturn(List.of(textUnitDTO(11L, "Settings")));

    GlossaryTrie coreFrench = glossaryService.loadLinkedGlossaryTrieForLocale(77L, "fr-FR");
    GlossaryTrie coreGerman = glossaryService.loadLinkedGlossaryTrieForLocale(77L, "de-DE");
    GlossaryTrie brandFrench = glossaryService.loadLinkedGlossaryTrieForLocale(88L, "fr-FR");
    GlossaryTrie legacyFrench =
        glossaryService.loadGlossaryTrieForLocale("legacy-glossary", "fr-FR");

    assertNotSame(coreFrench, coreGerman);
    assertNotSame(coreFrench, brandFrench);
    assertNotSame(coreFrench, legacyFrench);
    assertSame(legacyFrench, glossaryService.loadGlossaryTrieForLocale("legacy-glossary", "fr-FR"));
    verify(textUnitSearcher, times(7)).search(any(TextUnitSearcherParameters.class));
  }

  @Test
  public void glossaryConfigurationChangeMakesUpdatedTermsVisible() {
    TextUnitSearcher textUnitSearcher = mock(TextUnitSearcher.class);
    GlossaryRepository glossaryRepository = mock(GlossaryRepository.class);
    GlossaryStorageService glossaryStorageService = mock(GlossaryStorageService.class);
    GlossaryService glossaryService =
        glossaryService(
            textUnitSearcher,
            glossaryRepository,
            glossaryStorageService,
            new SimpleMeterRegistry());
    Glossary glossary = glossary(201L, "Core", "core-glossary");
    glossary.setLastModifiedDate(ZonedDateTime.parse("2026-08-11T17:00:00Z"));
    Asset canonicalAsset = asset(301L);

    when(glossaryRepository.findEnabledByRepositoryId(77L)).thenReturn(List.of(glossary));
    when(glossaryStorageService.ensureCanonicalAsset(glossary)).thenReturn(canonicalAsset);
    when(textUnitSearcher.search(any(TextUnitSearcherParameters.class)))
        .thenReturn(
            List.of(textUnitDTO(11L, "Settings")),
            List.of(),
            List.of(textUnitDTO(12L, "Workspace")),
            List.of());

    GlossaryTrie originalTrie = glossaryService.loadLinkedGlossaryTrieForLocale(77L, "fr-FR");
    glossary.setLastModifiedDate(glossary.getLastModifiedDate().plusSeconds(1));
    GlossaryTrie updatedTrie = glossaryService.loadLinkedGlossaryTrieForLocale(77L, "fr-FR");

    assertNotSame(originalTrie, updatedTrie);
    assertEquals("Settings", originalTrie.findMatches("Settings").get(0).matchedText());
    assertEquals("Workspace", updatedTrie.findMatches("Workspace").get(0).matchedText());
    verify(textUnitSearcher, times(4)).search(any(TextUnitSearcherParameters.class));
  }

  @Test
  public void glossaryConfigurationChangeRefreshesEveryPodWithoutEvictingUnrelatedGlossaries() {
    TextUnitSearcher textUnitSearcher = mock(TextUnitSearcher.class);
    GlossaryRepository glossaryRepository = mock(GlossaryRepository.class);
    GlossaryStorageService glossaryStorageService = mock(GlossaryStorageService.class);
    GlossaryService firstPod =
        glossaryService(
            textUnitSearcher,
            glossaryRepository,
            glossaryStorageService,
            new SimpleMeterRegistry());
    GlossaryService secondPod =
        glossaryService(
            textUnitSearcher,
            glossaryRepository,
            glossaryStorageService,
            new SimpleMeterRegistry());
    Glossary coreGlossary = glossary(201L, "Core", "core-glossary");
    Glossary brandGlossary = glossary(202L, "Brand", "brand-glossary");
    coreGlossary.setLastModifiedDate(ZonedDateTime.parse("2026-08-11T17:00:00Z"));

    when(glossaryRepository.findEnabledByRepositoryId(77L)).thenReturn(List.of(coreGlossary));
    when(glossaryRepository.findEnabledByRepositoryId(88L)).thenReturn(List.of(brandGlossary));
    when(glossaryStorageService.ensureCanonicalAsset(coreGlossary)).thenReturn(asset(301L));
    when(glossaryStorageService.ensureCanonicalAsset(brandGlossary)).thenReturn(asset(302L));
    when(textUnitSearcher.search(any(TextUnitSearcherParameters.class)))
        .thenReturn(List.of(textUnitDTO(11L, "Settings")));

    GlossaryTrie firstPodCore = firstPod.loadLinkedGlossaryTrieForLocale(77L, "fr-FR");
    GlossaryTrie secondPodCore = secondPod.loadLinkedGlossaryTrieForLocale(77L, "fr-FR");
    GlossaryTrie secondPodBrand = secondPod.loadLinkedGlossaryTrieForLocale(88L, "fr-FR");

    coreGlossary.setLastModifiedDate(coreGlossary.getLastModifiedDate().plusSeconds(1));

    assertNotSame(firstPodCore, firstPod.loadLinkedGlossaryTrieForLocale(77L, "fr-FR"));
    assertNotSame(secondPodCore, secondPod.loadLinkedGlossaryTrieForLocale(77L, "fr-FR"));
    assertSame(secondPodBrand, secondPod.loadLinkedGlossaryTrieForLocale(88L, "fr-FR"));
    verify(textUnitSearcher, times(10)).search(any(TextUnitSearcherParameters.class));
  }

  @Test
  public void concurrentGlossaryRequestsShareOneTrieLoad()
      throws InterruptedException, ExecutionException {
    TextUnitSearcher textUnitSearcher = mock(TextUnitSearcher.class);
    GlossaryRepository glossaryRepository = mock(GlossaryRepository.class);
    GlossaryStorageService glossaryStorageService = mock(GlossaryStorageService.class);
    GlossaryService glossaryService =
        glossaryService(
            textUnitSearcher,
            glossaryRepository,
            glossaryStorageService,
            new SimpleMeterRegistry());
    Glossary glossary = glossary(201L, "Core", "core-glossary");
    CountDownLatch firstSearchStarted = new CountDownLatch(1);
    CountDownLatch releaseSearch = new CountDownLatch(1);

    when(glossaryRepository.findEnabledByRepositoryId(77L)).thenReturn(List.of(glossary));
    when(glossaryStorageService.ensureCanonicalAsset(glossary)).thenReturn(asset(301L));
    when(textUnitSearcher.search(any(TextUnitSearcherParameters.class)))
        .thenAnswer(
            invocation -> {
              firstSearchStarted.countDown();
              assertTrue(releaseSearch.await(5, TimeUnit.SECONDS));
              return List.of(textUnitDTO(11L, "Settings"));
            });

    ExecutorService executor = Executors.newFixedThreadPool(8);
    try {
      List<Future<GlossaryTrie>> results = new ArrayList<>();
      for (int index = 0; index < 8; index++) {
        results.add(
            executor.submit(() -> glossaryService.loadLinkedGlossaryTrieForLocale(77L, "fr-FR")));
      }
      assertTrue(firstSearchStarted.await(5, TimeUnit.SECONDS));
      releaseSearch.countDown();

      GlossaryTrie firstTrie = results.get(0).get();
      for (Future<GlossaryTrie> result : results) {
        assertSame(firstTrie, result.get());
      }
      verify(textUnitSearcher, times(2)).search(any(TextUnitSearcherParameters.class));
    } finally {
      releaseSearch.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  public void managedGlossaryHydratesUsedSourceAndTargetTermsFromJsonCache() {
    TextUnitSearcher textUnitSearcher = mock(TextUnitSearcher.class);
    GlossaryRepository glossaryRepository = mock(GlossaryRepository.class);
    GlossaryStorageService glossaryStorageService = mock(GlossaryStorageService.class);
    TextUnitDTOsCacheService textUnitDTOsCacheService = mock(TextUnitDTOsCacheService.class);
    LocaleService localeService = mock(LocaleService.class);
    GlossaryService glossaryService =
        new GlossaryService(
            textUnitSearcher,
            glossaryRepository,
            glossaryStorageService,
            mock(GlossaryTermMetadataRepository.class),
            mock(GlossaryTermEvidenceRepository.class),
            mock(RepositoryRepository.class),
            textUnitDTOsCacheService,
            localeService,
            new SimpleMeterRegistry(),
            new GlossaryCacheConfigurationProperties());
    Glossary glossary = glossary(201L, "Core", "core-glossary");
    com.box.l10n.mojito.entity.Locale sourceLocale = locale(1L, "en-US");
    com.box.l10n.mojito.entity.Locale targetLocale = locale(2L, "fr-FR");
    glossary.getBackingRepository().setSourceLocale(sourceLocale);
    TextUnitDTO sourceTextUnit = usedTextUnitDTO(11L, "Settings");
    TextUnitDTO targetTextUnit = usedTextUnitDTO(11L, "Settings");
    targetTextUnit.setTarget("Paramètres");
    targetTextUnit.setIncludedInLocalizedFile(true);
    TextUnitDTO unusedTextUnit = textUnitDTO(12L, "Removed term");

    when(glossaryRepository.findEnabledByRepositoryId(77L)).thenReturn(List.of(glossary));
    when(glossaryStorageService.ensureCanonicalAsset(glossary)).thenReturn(asset(301L));
    when(localeService.findByBcp47Tag("fr-FR")).thenReturn(targetLocale);
    when(textUnitDTOsCacheService.getTextUnitDTOsForAssetAndLocale(
            301L, 1L, true, UpdateType.ALWAYS))
        .thenReturn(ImmutableList.of(sourceTextUnit, unusedTextUnit));
    when(textUnitDTOsCacheService.getTextUnitDTOsForAssetAndLocale(
            301L, 2L, false, UpdateType.ALWAYS))
        .thenReturn(ImmutableList.of(targetTextUnit, unusedTextUnit));

    GlossaryTrie glossaryTrie = glossaryService.loadLinkedGlossaryTrieForLocale(77L, "fr-FR");

    List<MatchedGlossaryTerm> matches = glossaryTrie.findMatches("Open Settings");
    assertEquals(1, matches.size());
    assertEquals("Paramètres", matches.get(0).glossaryTerm().target());
    assertTrue(glossaryTrie.findMatches("Removed term").isEmpty());
    verify(textUnitDTOsCacheService)
        .getTextUnitDTOsForAssetAndLocale(301L, 1L, true, UpdateType.ALWAYS);
    verify(textUnitDTOsCacheService)
        .getTextUnitDTOsForAssetAndLocale(301L, 2L, false, UpdateType.ALWAYS);
    verify(textUnitSearcher, times(0)).search(any(TextUnitSearcherParameters.class));
  }

  @Test
  public void managedGlossaryReloadRefreshesExistingLocaleWatermarkBeforeUsingUpdatedTranslation() {
    TextUnitSearcher textUnitSearcher = mock(TextUnitSearcher.class);
    GlossaryRepository glossaryRepository = mock(GlossaryRepository.class);
    GlossaryStorageService glossaryStorageService = mock(GlossaryStorageService.class);
    TextUnitDTOsCacheService textUnitDTOsCacheService = mock(TextUnitDTOsCacheService.class);
    LocaleService localeService = mock(LocaleService.class);
    GlossaryService glossaryService =
        new GlossaryService(
            textUnitSearcher,
            glossaryRepository,
            glossaryStorageService,
            mock(GlossaryTermMetadataRepository.class),
            mock(GlossaryTermEvidenceRepository.class),
            mock(RepositoryRepository.class),
            textUnitDTOsCacheService,
            localeService,
            new SimpleMeterRegistry(),
            new GlossaryCacheConfigurationProperties());
    Glossary glossary = glossary(201L, "Core", "core-glossary");
    glossary.setLastModifiedDate(ZonedDateTime.parse("2026-08-11T17:00:00Z"));
    com.box.l10n.mojito.entity.Locale sourceLocale = locale(1L, "en-US");
    com.box.l10n.mojito.entity.Locale targetLocale = locale(2L, "fr-FR");
    glossary.getBackingRepository().setSourceLocale(sourceLocale);
    TextUnitDTO oldTarget = usedTextUnitDTO(11L, "Settings");
    oldTarget.setTarget("Réglages");
    oldTarget.setIncludedInLocalizedFile(true);
    TextUnitDTO newTarget = usedTextUnitDTO(11L, "Settings");
    newTarget.setTarget("Paramètres");
    newTarget.setIncludedInLocalizedFile(true);

    when(glossaryRepository.findEnabledByRepositoryId(77L)).thenReturn(List.of(glossary));
    when(glossaryStorageService.ensureCanonicalAsset(glossary)).thenReturn(asset(301L));
    when(localeService.findByBcp47Tag("fr-FR")).thenReturn(targetLocale);
    when(textUnitDTOsCacheService.getTextUnitDTOsForAssetAndLocale(
            301L, 1L, true, UpdateType.ALWAYS))
        .thenReturn(ImmutableList.of(usedTextUnitDTO(11L, "Settings")));
    when(textUnitDTOsCacheService.getTextUnitDTOsForAssetAndLocale(
            301L, 2L, false, UpdateType.ALWAYS))
        .thenReturn(ImmutableList.of(oldTarget), ImmutableList.of(newTarget));

    GlossaryTrie originalTrie = glossaryService.loadLinkedGlossaryTrieForLocale(77L, "fr-FR");
    glossary.setLastModifiedDate(glossary.getLastModifiedDate().plusSeconds(1));
    GlossaryTrie refreshedTrie = glossaryService.loadLinkedGlossaryTrieForLocale(77L, "fr-FR");

    assertEquals("Réglages", originalTrie.findMatches("Settings").get(0).glossaryTerm().target());
    assertEquals(
        "Paramètres", refreshedTrie.findMatches("Settings").get(0).glossaryTerm().target());
    verify(textUnitDTOsCacheService, times(2))
        .getTextUnitDTOsForAssetAndLocale(301L, 2L, false, UpdateType.ALWAYS);
  }

  @Test
  public void managedGlossaryRootLocaleUsesRootJsonCacheForTargets() {
    TextUnitSearcher textUnitSearcher = mock(TextUnitSearcher.class);
    GlossaryRepository glossaryRepository = mock(GlossaryRepository.class);
    GlossaryStorageService glossaryStorageService = mock(GlossaryStorageService.class);
    TextUnitDTOsCacheService textUnitDTOsCacheService = mock(TextUnitDTOsCacheService.class);
    LocaleService localeService = mock(LocaleService.class);
    GlossaryService glossaryService =
        new GlossaryService(
            textUnitSearcher,
            glossaryRepository,
            glossaryStorageService,
            mock(GlossaryTermMetadataRepository.class),
            mock(GlossaryTermEvidenceRepository.class),
            mock(RepositoryRepository.class),
            textUnitDTOsCacheService,
            localeService,
            new SimpleMeterRegistry(),
            new GlossaryCacheConfigurationProperties());
    Glossary glossary = glossary(201L, "Core", "core-glossary");
    com.box.l10n.mojito.entity.Locale sourceLocale = locale(1L, "en-US");
    glossary.getBackingRepository().setSourceLocale(sourceLocale);
    TextUnitDTO sourceTextUnit = usedTextUnitDTO(11L, "Settings");

    when(glossaryRepository.findEnabledByRepositoryId(77L)).thenReturn(List.of(glossary));
    when(glossaryStorageService.ensureCanonicalAsset(glossary)).thenReturn(asset(301L));
    when(localeService.findByBcp47Tag("en-US")).thenReturn(sourceLocale);
    when(textUnitDTOsCacheService.getTextUnitDTOsForAssetAndLocale(
            301L, 1L, true, UpdateType.ALWAYS))
        .thenReturn(ImmutableList.of(sourceTextUnit));

    GlossaryTrie glossaryTrie = glossaryService.loadLinkedGlossaryTrieForLocale(77L, "en-US");

    assertEquals(1, glossaryTrie.findMatches("Settings").size());
    verify(textUnitDTOsCacheService, times(1))
        .getTextUnitDTOsForAssetAndLocale(301L, 1L, true, UpdateType.ALWAYS);
    verify(textUnitSearcher, times(0)).search(any(TextUnitSearcherParameters.class));
  }

  private GlossaryService glossaryService(
      TextUnitSearcher textUnitSearcher,
      GlossaryRepository glossaryRepository,
      GlossaryStorageService glossaryStorageService,
      SimpleMeterRegistry meterRegistry) {
    return new GlossaryService(
        textUnitSearcher,
        glossaryRepository,
        glossaryStorageService,
        mock(GlossaryTermMetadataRepository.class),
        mock(GlossaryTermEvidenceRepository.class),
        mock(RepositoryRepository.class),
        mock(TextUnitDTOsCacheService.class),
        mock(LocaleService.class),
        meterRegistry,
        new GlossaryCacheConfigurationProperties());
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

  private TextUnitDTO usedTextUnitDTO(long tmTextUnitId, String source) {
    TextUnitDTO textUnitDTO = textUnitDTO(tmTextUnitId, source);
    textUnitDTO.setAssetExtractionId(501L);
    textUnitDTO.setLastSuccessfulAssetExtractionId(501L);
    return textUnitDTO;
  }

  private com.box.l10n.mojito.entity.Locale locale(long localeId, String bcp47Tag) {
    com.box.l10n.mojito.entity.Locale locale = new com.box.l10n.mojito.entity.Locale();
    locale.setId(localeId);
    locale.setBcp47Tag(bcp47Tag);
    return locale;
  }
}
