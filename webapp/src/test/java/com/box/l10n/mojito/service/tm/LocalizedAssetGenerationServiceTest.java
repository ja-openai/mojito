package com.box.l10n.mojito.service.tm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.Asset;
import com.box.l10n.mojito.entity.Locale;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.RepositoryLocale;
import com.box.l10n.mojito.okapi.InheritanceMode;
import com.box.l10n.mojito.okapi.Status;
import com.box.l10n.mojito.rest.asset.AssetWithIdNotFoundException;
import com.box.l10n.mojito.rest.asset.LocalizedAssetBody;
import com.box.l10n.mojito.service.asset.AssetRepository;
import com.box.l10n.mojito.service.repository.RepositoryLocaleRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class LocalizedAssetGenerationServiceTest {

  @Mock AssetRepository assetRepository;
  @Mock RepositoryLocaleRepository repositoryLocaleRepository;
  @Mock TMService tmService;
  @Mock Asset asset;
  @Mock Repository repository;
  @Mock RepositoryLocale repositoryLocale;
  @Mock Locale locale;

  LocalizedAssetGenerationService localizedAssetGenerationService;

  @Before
  public void setUp() {
    localizedAssetGenerationService =
        new LocalizedAssetGenerationService(
            assetRepository, repositoryLocaleRepository, tmService, new SimpleMeterRegistry());
  }

  @Test
  public void generateUsesSharedTmPathAndRepositoryLocaleTag() throws Exception {
    LocalizedAssetBody input = localizedAssetBody(null);
    when(assetRepository.findById(10L)).thenReturn(Optional.of(asset));
    when(asset.getRepository()).thenReturn(repository);
    when(repository.getId()).thenReturn(20L);
    when(repository.getName()).thenReturn("repo");
    when(repositoryLocaleRepository.findByRepositoryIdAndLocaleId(20L, 30L))
        .thenReturn(repositoryLocale);
    when(repositoryLocale.getLocale()).thenReturn(locale);
    when(locale.getBcp47Tag()).thenReturn("fr-FR");
    when(tmService.generateLocalized(
            asset,
            "source",
            repositoryLocale,
            null,
            null,
            List.of("opt"),
            Status.ACCEPTED,
            InheritanceMode.REMOVE_UNTRANSLATED,
            "pull-run"))
        .thenReturn("localized");

    LocalizedAssetBody output = localizedAssetGenerationService.generate(input);

    assertThat(output).isSameAs(input);
    assertThat(output.getContent()).isEqualTo("localized");
    assertThat(output.getBcp47Tag()).isEqualTo("fr-FR");
    verify(tmService)
        .generateLocalized(
            asset,
            "source",
            repositoryLocale,
            null,
            null,
            List.of("opt"),
            Status.ACCEPTED,
            InheritanceMode.REMOVE_UNTRANSLATED,
            "pull-run");
  }

  @Test
  public void generateUsesExplicitOutputTagWhenProvided() throws Exception {
    LocalizedAssetBody input = localizedAssetBody("fr");
    when(assetRepository.findById(10L)).thenReturn(Optional.of(asset));
    when(asset.getRepository()).thenReturn(repository);
    when(repository.getId()).thenReturn(20L);
    when(repository.getName()).thenReturn("repo");
    when(repositoryLocaleRepository.findByRepositoryIdAndLocaleId(20L, 30L))
        .thenReturn(repositoryLocale);
    when(repositoryLocale.getLocale()).thenReturn(locale);
    when(locale.getBcp47Tag()).thenReturn("fr-FR");
    when(tmService.generateLocalized(
            asset,
            "source",
            repositoryLocale,
            "fr",
            null,
            List.of("opt"),
            Status.ACCEPTED,
            InheritanceMode.REMOVE_UNTRANSLATED,
            "pull-run"))
        .thenReturn("localized");

    LocalizedAssetBody output = localizedAssetGenerationService.generate(input);

    assertThat(output.getContent()).isEqualTo("localized");
    assertThat(output.getBcp47Tag()).isEqualTo("fr");
  }

  @Test
  public void generateRejectsMissingAsset() {
    LocalizedAssetBody input = localizedAssetBody(null);
    when(assetRepository.findById(10L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> localizedAssetGenerationService.generate(input))
        .isInstanceOf(AssetWithIdNotFoundException.class);
  }

  private LocalizedAssetBody localizedAssetBody(String outputBcp47Tag) {
    LocalizedAssetBody localizedAssetBody = new LocalizedAssetBody();
    localizedAssetBody.setAssetId(10L);
    localizedAssetBody.setLocaleId(30L);
    localizedAssetBody.setContent("source");
    localizedAssetBody.setOutputBcp47tag(outputBcp47Tag);
    localizedAssetBody.setFilterOptions(List.of("opt"));
    localizedAssetBody.setStatus(Status.ACCEPTED);
    localizedAssetBody.setInheritanceMode(InheritanceMode.REMOVE_UNTRANSLATED);
    localizedAssetBody.setPullRunName("pull-run");
    return localizedAssetBody;
  }
}
