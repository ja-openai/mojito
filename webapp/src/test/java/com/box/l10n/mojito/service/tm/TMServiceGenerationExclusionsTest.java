package com.box.l10n.mojito.service.tm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.Asset;
import com.box.l10n.mojito.entity.Locale;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.RepositoryGenerationSettings;
import com.box.l10n.mojito.entity.RepositoryLocale;
import com.box.l10n.mojito.fileformat.LocalizationConverterSelection;
import com.box.l10n.mojito.okapi.InheritanceMode;
import com.box.l10n.mojito.okapi.Status;
import com.box.l10n.mojito.service.locale.LocaleService;
import com.box.l10n.mojito.service.repository.GlobalGenerationService;
import com.box.l10n.mojito.service.repository.RepositoryGenerationService;
import com.box.l10n.mojito.service.repository.RepositoryGenerationSettingsRepository;
import com.box.l10n.mojito.service.repository.RepositoryRepository;
import java.util.List;
import java.util.Optional;
import org.junit.Test;

public class TMServiceGenerationExclusionsTest {

  private final GlobalGenerationService global = mock(GlobalGenerationService.class);
  private final RepositoryGenerationSettingsRepository settings =
      mock(RepositoryGenerationSettingsRepository.class);

  @Test
  public void excludedLocaleFailsBeforeSourceLessAugmentationOrEitherConverter() {
    Repository repository = repository(7L);
    excludeLocales(repository, List.of("fr"));
    Asset asset = asset(repository);
    TMService service = service();
    service.noSourceJsonAugmenter = mock(NoSourceJsonAugmenter.class);

    for (List<String> options :
        List.of(List.<String>of(), List.of(LocalizationConverterSelection.PORTABLE_OPTION))) {
      assertThatThrownBy(
              () ->
                  service.generateLocalized(
                      asset,
                      "{}",
                      locale("fr"),
                      "fr-FR",
                      null,
                      options,
                      Status.ALL,
                      InheritanceMode.USE_PARENT,
                      null,
                      true,
                      List.of()))
          .isInstanceOf(LocaleGenerationExcludedException.class)
          .hasMessage("Locale fr is excluded from localized file generation for this repository");
    }
    verifyNoInteractions(service.noSourceJsonAugmenter);
  }

  @Test
  public void exclusionsUseExactRepositoryLocaleAndDoNotAffectAnotherRepository() throws Exception {
    Repository excluded = repository(7L);
    excludeLocales(excluded, List.of("fr"));

    assertThat(generate(asset(excluded), "fr-CA", "fr")).isEqualTo("{}");
    assertThat(generate(asset(repository(8L)), "fr", null)).isEqualTo("{}");
    verify(settings).findById(8L);

    excludeLocales(excluded, List.of());
    assertThat(generate(asset(excluded), "fr", null)).isEqualTo("{}");
  }

  @Test
  public void globalExclusionsApplyAcrossRepositoriesAndToSourceLocales() throws Exception {
    when(global.getExcludedLocaleTags()).thenReturn(List.of("fr"));
    for (Repository repository : List.of(repository(7L), repository(8L))) {
      repository.setSourceLocale(locale("fr").getLocale());
      assertThatThrownBy(() -> generate(asset(repository), "fr", "fr-FR"))
          .isInstanceOf(LocaleGenerationExcludedException.class);
      assertThat(generate(asset(repository), "fr-CA", "fr")).isEqualTo("{}");
    }
    assertThatThrownBy(() -> generate(asset(null), "fr", null))
        .isInstanceOf(LocaleGenerationExcludedException.class);
  }

  @Test
  public void clearingGlobalExclusionsKeepsRepositoryExclusions() throws Exception {
    Repository repository = repository(7L);
    excludeLocales(repository, List.of("ja"));
    when(global.getExcludedLocaleTags()).thenReturn(List.of("fr"));
    assertThatThrownBy(() -> generate(asset(repository), "fr", null))
        .isInstanceOf(LocaleGenerationExcludedException.class);
    assertThatThrownBy(() -> generate(asset(repository), "ja", null))
        .isInstanceOf(LocaleGenerationExcludedException.class);

    when(global.getExcludedLocaleTags()).thenReturn(List.of());
    assertThat(generate(asset(repository), "fr", null)).isEqualTo("{}");
    assertThatThrownBy(() -> generate(asset(repository), "ja", null))
        .isInstanceOf(LocaleGenerationExcludedException.class);
  }

  @Test
  public void globalRulesDoNotRequireARepositorySettingsRowOrRepositoryId() throws Exception {
    when(global.getExcludedLocaleTags()).thenReturn(List.of("fr"));
    assertThatThrownBy(() -> generate(asset(new Repository()), "fr", null))
        .isInstanceOf(LocaleGenerationExcludedException.class);
    assertThat(generate(asset(new Repository()), "ja", null)).isEqualTo("{}");
    verifyNoInteractions(settings);
  }

  private TMService service() {
    TMService service = new TMService();
    service.repositoryGenerationService =
        new RepositoryGenerationService(
            mock(RepositoryRepository.class), mock(LocaleService.class), global, settings);
    return service;
  }

  private Repository repository(Long id) {
    Repository repository = new Repository();
    repository.setId(id);
    return repository;
  }

  private void excludeLocales(Repository repository, List<String> localeTags) {
    RepositoryGenerationSettings saved = new RepositoryGenerationSettings();
    saved.setRepositoryId(repository.getId());
    saved.setExcludedLocaleTags(localeTags);
    when(settings.findById(repository.getId())).thenReturn(Optional.of(saved));
  }

  private String generate(Asset asset, String tag, String outputTag) throws Exception {
    return service()
        .generateLocalized(
            asset,
            "{}",
            locale(tag),
            outputTag,
            null,
            List.of(LocalizationConverterSelection.PORTABLE_OPTION),
            Status.ALL,
            InheritanceMode.USE_PARENT,
            null);
  }

  private Asset asset(Repository repository) {
    Asset asset = new Asset();
    asset.setPath("messages.json");
    asset.setRepository(repository);
    return asset;
  }

  private RepositoryLocale locale(String tag) {
    Locale locale = new Locale();
    locale.setBcp47Tag(tag);
    RepositoryLocale repositoryLocale = new RepositoryLocale();
    repositoryLocale.setLocale(locale);
    return repositoryLocale;
  }
}
