package com.box.l10n.mojito.rest.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.box.l10n.mojito.entity.Asset;
import com.box.l10n.mojito.entity.Locale;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.RepositoryGenerationSettings;
import com.box.l10n.mojito.entity.RepositoryLocale;
import com.box.l10n.mojito.service.asset.AssetRepository;
import com.box.l10n.mojito.service.locale.LocaleService;
import com.box.l10n.mojito.service.repository.GlobalGenerationService;
import com.box.l10n.mojito.service.repository.RepositoryGenerationService;
import com.box.l10n.mojito.service.repository.RepositoryGenerationSettingsRepository;
import com.box.l10n.mojito.service.repository.RepositoryLocaleRepository;
import com.box.l10n.mojito.service.repository.RepositoryRepository;
import com.box.l10n.mojito.service.tm.LocaleGenerationExcludedException;
import com.box.l10n.mojito.service.tm.TMService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Optional;
import org.junit.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

public class AssetWSGenerationExclusionsTest {

  @Test
  public void directLocalizedRequestsReturnConflictEvenWithMappingOrSourceLessPull()
      throws Exception {
    Repository repository = new Repository();
    repository.setId(7L);
    RepositoryGenerationSettings saved = new RepositoryGenerationSettings();
    saved.setRepositoryId(7L);
    saved.setExcludedLocaleTags(List.of("fr"));
    RepositoryGenerationSettingsRepository settings =
        mock(RepositoryGenerationSettingsRepository.class);
    when(settings.findById(7L)).thenReturn(Optional.of(saved));
    Asset asset = new Asset();
    asset.setRepository(repository);
    Locale locale = new Locale();
    locale.setBcp47Tag("fr");
    RepositoryLocale repositoryLocale = new RepositoryLocale();
    repositoryLocale.setLocale(locale);
    AssetWS controller = new AssetWS();
    controller.assetRepository = mock(AssetRepository.class);
    controller.repositoryLocaleRepository = mock(RepositoryLocaleRepository.class);
    controller.tmService = new TMService();
    ReflectionTestUtils.setField(
        controller.tmService,
        "repositoryGenerationService",
        new RepositoryGenerationService(
            mock(RepositoryRepository.class),
            mock(LocaleService.class),
            mock(GlobalGenerationService.class),
            settings));
    when(controller.assetRepository.getReferenceById(11L)).thenReturn(asset);
    when(controller.repositoryLocaleRepository.findByRepositoryIdAndLocaleId(7L, 13L))
        .thenReturn(repositoryLocale);

    var meters = new SimpleMeterRegistry();
    try {
      controller.meterRegistry = meters;
      var mvc = MockMvcBuilders.standaloneSetup(controller).build();
      for (boolean sourceLess : List.of(false, true)) {
        var result =
            mvc.perform(
                    post("/api/assets/11/localized/13")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            "{\"content\":\"{}\",\"outputBcp47tag\":\"fr-FR\",\"pullWithNoSource\":"
                                + sourceLess
                                + "}"))
                .andExpect(status().isConflict())
                .andReturn();
        assertThat(result.getResolvedException())
            .isInstanceOf(LocaleGenerationExcludedException.class)
            .hasMessageContaining("Locale fr is excluded");
      }
    } finally {
      meters.close();
    }
  }
}
