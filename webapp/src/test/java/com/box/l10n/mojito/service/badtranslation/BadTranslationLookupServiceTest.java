package com.box.l10n.mojito.service.badtranslation;

import static org.assertj.core.api.Assertions.assertThat;

import com.box.l10n.mojito.entity.Locale;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.service.locale.LocaleService;
import com.box.l10n.mojito.service.repository.RepositoryRepository;
import com.box.l10n.mojito.service.tm.search.TextUnitDTO;
import com.box.l10n.mojito.service.tm.search.TextUnitSearcher;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

public class BadTranslationLookupServiceTest {

  private final RepositoryRepository repositoryRepository =
      Mockito.mock(RepositoryRepository.class);
  private final LocaleService localeService = Mockito.mock(LocaleService.class);
  private final TextUnitSearcher textUnitSearcher = Mockito.mock(TextUnitSearcher.class);

  private BadTranslationLookupService service;

  @Before
  public void setUp() {
    service =
        new BadTranslationLookupService(repositoryRepository, localeService, textUnitSearcher);
  }

  @Test
  public void findTranslationWithoutRepositoryReturnsCandidatesAcrossRepositories() {
    Mockito.when(localeService.findByBcp47Tag("hr-HR")).thenReturn(locale(7L, "hr-HR"));
    Mockito.when(textUnitSearcher.search(Mockito.any()))
        .thenReturn(
            List.of(
                textUnitDTO("web-a", "/src/a.ts", "bad one"),
                textUnitDTO("web-b", "/src/b.ts", "bad two")));

    BadTranslationLookupService.FindTranslationResult result =
        service.findTranslation(
            new BadTranslationLookupService.FindTranslationInput(
                "Hermes.GptStudio.Analytics.AgentAnalyticsBarChart.tooltipDateLabel",
                "hr-HR",
                null));

    assertThat(result.resolutionStatus())
        .isEqualTo(BadTranslationLookupService.ResolutionStatus.AMBIGUOUS);
    assertThat(result.localeResolution().resolvedLocale().bcp47Tag()).isEqualTo("hr-HR");
    assertThat(result.localeResolution().strategy())
        .isEqualTo(BadTranslationLookupService.LocaleResolutionStrategy.EXACT);
    assertThat(result.matchCount()).isEqualTo(2);
    assertThat(result.candidates())
        .extracting(candidate -> candidate.repository().name())
        .containsExactly("web-a", "web-b");
  }

  @Test
  public void findTranslationNormalizesObservedLocaleBeforeLookup() {
    Mockito.when(localeService.findByBcp47Tag("hr_HR")).thenReturn(null);
    Mockito.when(localeService.findByBcp47Tag("hr-HR")).thenReturn(locale(7L, "hr-HR"));
    Mockito.when(textUnitSearcher.search(Mockito.any()))
        .thenReturn(List.of(textUnitDTO("web-a", "/src/a.ts", "bad one")));

    BadTranslationLookupService.FindTranslationResult result =
        service.findTranslation(
            new BadTranslationLookupService.FindTranslationInput(
                "Hermes.GptStudio.Analytics.AgentAnalyticsBarChart.tooltipDateLabel",
                "hr_HR",
                null));

    assertThat(result.localeResolution().resolvedLocale().bcp47Tag()).isEqualTo("hr-HR");
    assertThat(result.localeResolution().strategy())
        .isEqualTo(BadTranslationLookupService.LocaleResolutionStrategy.NORMALIZED);
  }

  @Test
  public void findTranslationFallsBackToLanguageOnlyLocale() {
    Mockito.when(localeService.findByBcp47Tag("hr-HR")).thenReturn(null);
    Mockito.when(localeService.findByBcp47Tag("hr")).thenReturn(locale(7L, "hr"));
    Mockito.when(repositoryRepository.findByName("chatgpt-web-restricted"))
        .thenReturn(repository(11L, "chatgpt-web-restricted"));
    Mockito.when(textUnitSearcher.search(Mockito.any()))
        .thenReturn(List.of(textUnitDTO("chatgpt-web-restricted", "/src/hr.json", "{bad}")));

    BadTranslationLookupService.FindTranslationResult result =
        service.findTranslation(
            new BadTranslationLookupService.FindTranslationInput(
                "Hermes.GptStudio.Analytics.AgentAnalyticsBarChart.tooltipDateLabel",
                "hr-HR",
                "chatgpt-web-restricted"));

    assertThat(result.resolutionStatus())
        .isEqualTo(BadTranslationLookupService.ResolutionStatus.UNIQUE_MATCH);
    assertThat(result.localeResolution().resolvedLocale().bcp47Tag()).isEqualTo("hr");
    assertThat(result.localeResolution().strategy())
        .isEqualTo(BadTranslationLookupService.LocaleResolutionStrategy.LANGUAGE_ONLY);
    assertThat(result.candidates()).hasSize(1);
    assertThat(result.candidates().getFirst().canReject()).isTrue();
  }

  private Locale locale(Long id, String bcp47Tag) {
    Locale locale = new Locale();
    locale.setId(id);
    locale.setBcp47Tag(bcp47Tag);
    return locale;
  }

  private Repository repository(Long id, String name) {
    Repository repository = new Repository();
    repository.setId(id);
    repository.setName(name);
    return repository;
  }

  private TextUnitDTO textUnitDTO(String repositoryName, String assetPath, String target) {
    TextUnitDTO textUnitDTO = new TextUnitDTO();
    textUnitDTO.setRepositoryName(repositoryName);
    textUnitDTO.setName("Hermes.GptStudio.Analytics.AgentAnalyticsBarChart.tooltipDateLabel");
    textUnitDTO.setTargetLocale("hr-HR");
    textUnitDTO.setTmTextUnitId(41L);
    textUnitDTO.setTmTextUnitCurrentVariantId(52L);
    textUnitDTO.setTmTextUnitVariantId(63L);
    textUnitDTO.setAssetPath(assetPath);
    textUnitDTO.setAssetId(74L);
    textUnitDTO.setSource(
        "{month} {day, selectordinal, one {#st} two {#nd} few {#rd} other {#th}}");
    textUnitDTO.setTarget(target);
    textUnitDTO.setTargetComment("ICU ordinal");
    textUnitDTO.setIncludedInLocalizedFile(true);
    textUnitDTO.setCreatedDate(ZonedDateTime.parse("2026-04-15T10:15:30Z"));
    textUnitDTO.setStatus(com.box.l10n.mojito.entity.TMTextUnitVariant.Status.APPROVED);
    return textUnitDTO;
  }
}
