package com.box.l10n.mojito.service.oaitranslate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
import com.box.l10n.mojito.service.tm.search.TextUnitDTO;
import com.box.l10n.mojito.service.tm.search.TextUnitSearcher;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Set;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

public class AiTranslateLegacyBatchServiceTest {

  @Test
  public void batchRequestsCarryIdentityGlossaryProtectionAndPerStringPluralGuidance() {
    TextUnitSearcher textUnitSearcher = mock(TextUnitSearcher.class);
    RepositoryRepository repositoryRepository = mock(RepositoryRepository.class);
    RepositoryService repositoryService = mock(RepositoryService.class);
    OpenAIClient openAIClient = mock(OpenAIClient.class);
    ObjectMapper mapper = new ObjectMapper();
    AiTranslateService.configureObjectMapper(mapper);
    AiTranslateLegacyBatchService service =
        new AiTranslateLegacyBatchService(
            textUnitSearcher,
            repositoryRepository,
            repositoryService,
            mock(StructuredBlobStorage.class),
            new AiTranslateConfigurationProperties(),
            openAIClient,
            mapper,
            mock(AssetTextUnitRepository.class),
            mock(TMTextUnitVariantRepository.class),
            mock(GlossaryService.class));
    Repository repository = new Repository();
    repository.setId(77L);
    repository.setName("product");
    Locale locale = new Locale();
    locale.setId(88L);
    locale.setBcp47Tag("fr-FR");
    RepositoryLocale repositoryLocale = new RepositoryLocale();
    repositoryLocale.setRepository(repository);
    repositoryLocale.setLocale(locale);
    TextUnitDTO textUnit = new TextUnitDTO();
    textUnit.setTmTextUnitId(42L);
    textUnit.setSource("Open ChatGPT");
    textUnit.setTargetLocale("fr-FR");
    TextUnitDTO pluralTextUnit = new TextUnitDTO();
    pluralTextUnit.setTmTextUnitId(43L);
    pluralTextUnit.setSource(
        "{count, plural, one {# ChatGPT workspace} other {# ChatGPT workspaces}}");
    pluralTextUnit.setTargetLocale("fr-FR");
    when(repositoryRepository.findByName("product")).thenReturn(repository);
    when(repositoryService.getRepositoryLocalesWithoutRootLocale(repository))
        .thenReturn(Set.of(repositoryLocale));
    when(textUnitSearcher.search(any())).thenReturn(List.of(textUnit, pluralTextUnit));
    when(openAIClient.uploadFile(any()))
        .thenReturn(
            new OpenAIClient.UploadFileResponse(
                "file", "file-id", "batch", "batch.jsonl", 1, 0L, "processed", null));
    when(openAIClient.createBatch(any())).thenReturn(mock(OpenAIClient.CreateBatchResponse.class));

    var result =
        service.createBatches(
            new AiTranslateService.AiTranslateInput(
                "product",
                null,
                100,
                null,
                true,
                null,
                null,
                null,
                AiTranslateType.TARGET_ONLY_NEW.name(),
                StatusFilter.FOR_TRANSLATION.name(),
                null,
                null,
                null,
                null,
                "ChatGPT",
                "Product name",
                null,
                null,
                true,
                true,
                false,
                false,
                null));

    assertThat(result.batchCreationErrors()).isEmpty();
    ArgumentCaptor<OpenAIClient.UploadFileRequest> upload =
        ArgumentCaptor.forClass(OpenAIClient.UploadFileRequest.class);
    verify(openAIClient).uploadFile(upload.capture());
    JsonNode uploadJson = mapper.valueToTree(upload.getValue());
    List<JsonNode> batchLines = batchLines(mapper, uploadJson);
    assertThat(batchLines).hasSize(2);
    JsonNode batchLine = batchLines.getFirst();
    assertThat(batchLine.at("/body/messages/0/content").asText())
        .isEqualTo(AiTranslateType.TARGET_ONLY_NEW.getPrompt());
    assertThat(batchLines.getLast().get("custom_id").asText()).isEqualTo("43");
    assertThat(batchLines.getLast().at("/body/messages/0/content").asText())
        .contains(
            "Plural requirements for target locale fr-FR:",
            "cardinal plural rules have 3 categories: one, many, other");
    assertThat(batchLine.get("custom_id").asText()).isEqualTo("42");
    assertThat(batchLine.at("/body/service_tier").isMissingNode()).isTrue();
    assertThat(batchLine.at("/body/reasoning_effort").asText()).isEqualTo("max");
    JsonNode input =
        mapper.readValueUnchecked(
            batchLine.at("/body/messages/1/content").asText(), JsonNode.class);
    assertThat(input.get("locale").asText()).isEqualTo("fr-FR");
    assertThat(input.at("/textUnitsToTranslate/0/tmTextUnitId").asLong()).isEqualTo(42L);
    assertThat(input.at("/textUnitsToTranslate/0/source").asText()).isEqualTo("Open ChatGPT");
    assertThat(input.at("/textUnitsToTranslate/0/glossaryTerms/0/doNotTranslate").asBoolean())
        .isTrue();
    assertThat(input.at("/textUnitsToTranslate/0/glossaryTerms/0/termTarget").asText())
        .isEqualTo("ChatGPT");

    service.createBatches(
        new AiTranslateService.AiTranslateInput(
            "product",
            null,
            100,
            null,
            true,
            "gpt-4.1",
            null,
            null,
            AiTranslateType.TARGET_ONLY.name(),
            StatusFilter.FOR_TRANSLATION.name(),
            null,
            null,
            null,
            null,
            "ChatGPT",
            "Product name",
            null,
            null,
            true,
            true,
            false,
            false,
            null));

    verify(openAIClient, times(2)).uploadFile(upload.capture());
    JsonNode legacyUpload = mapper.valueToTree(upload.getValue());
    List<JsonNode> legacyBatchLines = batchLines(mapper, legacyUpload);
    assertThat(legacyBatchLines).hasSize(2);
    JsonNode legacyBatchLine = legacyBatchLines.getFirst();
    assertThat(legacyBatchLine.at("/body/messages/0/content").asText())
        .isEqualTo(AiTranslateType.TARGET_ONLY.getPrompt());
    assertThat(legacyBatchLines.getLast().at("/body/messages/0/content").asText())
        .contains(
            "Plural requirements for target locale fr-FR:",
            "cardinal plural rules have 3 categories: one, many, other");
    assertThat(legacyBatchLine.at("/body/reasoning_effort").isMissingNode()).isTrue();
    JsonNode legacyInput =
        mapper.readValueUnchecked(
            legacyBatchLine.at("/body/messages/1/content").asText(), JsonNode.class);
    assertThat(legacyInput.get("source").asText()).isEqualTo("Open ChatGPT");
    assertThat(legacyInput.has("textUnitsToTranslate")).isFalse();
  }

  private static List<JsonNode> batchLines(ObjectMapper mapper, JsonNode uploadJson) {
    return uploadJson
        .at("/fileContent/value")
        .asText()
        .lines()
        .map(line -> mapper.readValueUnchecked(line, JsonNode.class))
        .toList();
  }

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
