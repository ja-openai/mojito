package com.box.l10n.mojito.service.oaitranslate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.Locale;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.RepositoryLocale;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.openai.OpenAIClient;
import com.box.l10n.mojito.service.assetTextUnit.AssetTextUnitRepository;
import com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage;
import com.box.l10n.mojito.service.repository.RepositoryRepository;
import com.box.l10n.mojito.service.repository.RepositoryService;
import com.box.l10n.mojito.service.tm.TMTextUnitVariantRepository;
import com.box.l10n.mojito.service.tm.search.StatusFilter;
import com.box.l10n.mojito.service.tm.search.TextUnitSearcher;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public class AiTranslateLegacyBatchServiceTest {

  @Test
  public void emptyLocaleSkipsGlossaryBlobStorageAndProviderRequests() {
    TextUnitSearcher textUnitSearcher = mock(TextUnitSearcher.class);
    RepositoryRepository repositoryRepository = mock(RepositoryRepository.class);
    RepositoryService repositoryService = mock(RepositoryService.class);
    StructuredBlobStorage structuredBlobStorage = mock(StructuredBlobStorage.class);
    OpenAIClient openAIClient = mock(OpenAIClient.class);
    GlossaryService glossaryService = mock(GlossaryService.class);
    AiTranslateLegacyBatchService legacyBatchService =
        new AiTranslateLegacyBatchService(
            textUnitSearcher,
            repositoryRepository,
            repositoryService,
            structuredBlobStorage,
            new AiTranslateConfigurationProperties(),
            openAIClient,
            mock(ObjectMapper.class),
            mock(AssetTextUnitRepository.class),
            mock(TMTextUnitVariantRepository.class),
            glossaryService);

    Repository repository = new Repository();
    repository.setId(77L);
    repository.setName("product-repository");
    Locale locale = new Locale();
    locale.setId(88L);
    locale.setBcp47Tag("fr-FR");
    RepositoryLocale repositoryLocale = new RepositoryLocale();
    repositoryLocale.setRepository(repository);
    repositoryLocale.setLocale(locale);

    when(repositoryRepository.findByName("product-repository")).thenReturn(repository);
    when(repositoryService.getRepositoryLocalesWithoutRootLocale(repository))
        .thenReturn(Set.of(repositoryLocale));
    when(textUnitSearcher.search(any())).thenReturn(List.of());

    AiTranslateLegacyBatchService.LegacyBatchCreationResult result =
        legacyBatchService.createBatches(
            new AiTranslateService.AiTranslateInput(
                "product-repository",
                null,
                100,
                null,
                true,
                null,
                null,
                null,
                AiTranslateType.WITH_REVIEW.name(),
                StatusFilter.FOR_TRANSLATION.name(),
                null,
                null,
                null,
                "Core",
                null,
                null,
                null,
                null,
                false,
                false,
                false,
                false,
                null));

    assertEquals(List.of(), result.createdBatches());
    assertEquals(List.of("fr-FR"), result.skippedLocales());
    assertEquals(List.of(), result.batchCreationErrors());
    verifyNoInteractions(glossaryService, structuredBlobStorage, openAIClient);
  }
}
