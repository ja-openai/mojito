package com.box.l10n.mojito.service.oaitranslate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.Locale;
import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.RepositoryLocale;
import com.box.l10n.mojito.entity.TMTextUnitVariant.Status;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.openai.OpenAIClient;
import com.box.l10n.mojito.openai.OpenAIClient.ResponsesRequest;
import com.box.l10n.mojito.openai.OpenAIClient.ResponsesResponse;
import com.box.l10n.mojito.openai.OpenAIClientPool;
import com.box.l10n.mojito.quartz.QuartzPollableTaskScheduler;
import com.box.l10n.mojito.service.assetTextUnit.AssetTextUnitRepository;
import com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage;
import com.box.l10n.mojito.service.pollableTask.PollableTaskBlobStorage;
import com.box.l10n.mojito.service.pollableTask.PollableTaskService;
import com.box.l10n.mojito.service.repository.RepositoryRepository;
import com.box.l10n.mojito.service.repository.RepositoryService;
import com.box.l10n.mojito.service.screenshot.ScreenshotService;
import com.box.l10n.mojito.service.tm.TMTextUnitVariantRepository;
import com.box.l10n.mojito.service.tm.importer.BulkImportLineageService.ImportContext;
import com.box.l10n.mojito.service.tm.importer.TextUnitBatchImporterService;
import com.box.l10n.mojito.service.tm.importer.TextUnitBatchImporterService.TextUnitDTOWithVariantComment;
import com.box.l10n.mojito.service.tm.search.StatusFilter;
import com.box.l10n.mojito.service.tm.search.TextUnitDTO;
import com.box.l10n.mojito.service.tm.search.TextUnitSearcher;
import com.box.l10n.mojito.service.tm.textunitdtocache.TextUnitDTOsCacheService;
import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import org.junit.Test;
import reactor.util.retry.Retry;

public class AiTranslateQualityTest {

  @Test
  public void singleTargetTypesNeverShareOutputAcrossStringsWithTheSameScreenshot() {
    for (AiTranslateType type : AiTranslateType.values()) {
      if (type == AiTranslateType.TARGET_ONLY_NEW) {
        continue;
      }
      Fixture fixture = new Fixture();
      fixture.respondWith(
          request -> {
            JsonNode input = fixture.input(request);
            assertThat(input.has("textUnitsToTranslate")).isFalse();
            String target = input.get("source").asText().equals("Save") ? "Enregistrer" : "Annuler";
            Object output =
                switch (type) {
                  case WITH_REVIEW ->
                      new AiTranslateType.CompletionOutput(
                          input.get("source").asText(),
                          new AiTranslateType.CompletionOutput.Target(target, "", 100),
                          null,
                          null,
                          null,
                          null);
                  case TARGET_WITH_CONFIDENCE ->
                      new AiTranslateType.WithConfidenceCompletionOutput(target, 100);
                  default -> new AiTranslateType.SimpleCompletionOutput(target);
                };
            return response("completed", fixture.mapper.writeValueAsStringUnchecked(output));
          });

      fixture.run(type);

      assertThat(fixture.requests).as(type.name()).hasSize(2);
      assertThat(fixture.textUnits)
          .extracting(TextUnitDTO::getTarget)
          .containsExactly("Enregistrer", "Annuler");
      assertThat(fixture.imported).hasSize(2);
      assertThat(fixture.requests)
          .allSatisfy(request -> assertThat(request.serviceTier()).isEqualTo("default"));
    }
  }

  @Test
  public void multiTargetOutputKeepsScreenshotGroupingAndMapsReorderedIds() {
    Fixture fixture = new Fixture();
    fixture.respondWith(
        request ->
            response(
                "completed",
                """
        {"targets":[{"tmTextUnitId":2,"target":"Annuler"},{"tmTextUnitId":1,"target":"Enregistrer"}]}
        """));

    fixture.run(AiTranslateType.TARGET_ONLY_NEW);

    assertThat(fixture.requests).hasSize(1);
    assertThat(fixture.input(fixture.requests.getFirst()).get("textUnitsToTranslate").size())
        .isEqualTo(2);
    assertThat(fixture.textUnits)
        .extracting(TextUnitDTO::getTarget)
        .containsExactly("Enregistrer", "Annuler");
    assertThat(fixture.imported).hasSize(2);
  }

  @Test
  public void invalidGroupNeverImportsOrMutatesAnyTarget() {
    for (String output :
        List.of(
            "{\"targets\":[{\"tmTextUnitId\":1,\"target\":\"Enregistrer\"}]}",
            "{\"targets\":[{\"tmTextUnitId\":1,\"target\":\"Enregistrer\"},{\"tmTextUnitId\":2,\"target\":\"Annuler\"},{\"tmTextUnitId\":3,\"target\":\"Unexpected\"}]}",
            "{\"targets\":[{\"tmTextUnitId\":1,\"target\":\"Enregistrer\"},{\"tmTextUnitId\":1,\"target\":\"Annuler\"}]}",
            "{\"targets\":[{\"tmTextUnitId\":1,\"target\":\"Enregistrer\"},{\"tmTextUnitId\":2,\"target\":\"\"}]}",
            "{\"targets\":[{\"tmTextUnitId\":1,\"target\":\"Enregistrer\"},{\"tmTextUnitId\":null,\"target\":\"Annuler\"}]}")) {
      Fixture fixture = new Fixture();
      fixture.respondWith(request -> response("completed", output));

      fixture.run(AiTranslateType.TARGET_ONLY_NEW);

      assertThat(fixture.imported).isEmpty();
      assertThat(fixture.textUnits)
          .allSatisfy(textUnit -> assertThat(textUnit.getTarget()).isNull());
      verify(fixture.lineage)
          .markNoBatchFailed(any(), anyString(), anyString(), any(), anyString());
    }
  }

  @Test
  public void incompleteResponseWithValidJsonDoesNotImport() {
    Fixture fixture = new Fixture();
    fixture.respondWith(
        request ->
            response(
                "incomplete",
                """
        {"targets":[{"tmTextUnitId":1,"target":"Enregistrer"},{"tmTextUnitId":2,"target":"Annuler"}]}
        """));

    fixture.run(AiTranslateType.TARGET_ONLY_NEW);

    assertThat(fixture.imported).isEmpty();
    assertThat(fixture.textUnits).allSatisfy(textUnit -> assertThat(textUnit.getTarget()).isNull());
    verify(fixture.lineage).markNoBatchFailed(any(), anyString(), anyString(), any(), anyString());
  }

  @Test
  public void emptyCandidatePreservesExistingTranslationAndReviewAttribution() {
    for (String target : Arrays.asList(null, "", " \n\t", "\u00a0")) {
      TextUnitDTO textUnit = textUnit(1L, "Save");
      textUnit.setTarget("Previous translation");
      textUnit.setTargetComment("Human context");
      textUnit.setStatus(Status.APPROVED);
      textUnit.setTranslatorIdentity("translator");
      textUnit.setReviewerIdentity("reviewer");

      var prepared =
          AiTranslateService.prepareForTextUnitDTOForImport(
              "response",
              AiTranslateType.TARGET_ONLY,
              Status.REVIEW_NEEDED,
              textUnit,
              new AiTranslateType.SimpleCompletionOutput(target),
              "request");

      assertThat(prepared.error()).contains("empty target");
      assertThat(textUnit.getTarget()).isEqualTo("Previous translation");
      assertThat(textUnit.getTargetComment()).isEqualTo("Human context");
      assertThat(textUnit.getStatus()).isEqualTo(Status.APPROVED);
      assertThat(textUnit.getTranslatorIdentity()).isEqualTo("translator");
      assertThat(textUnit.getReviewerIdentity()).isEqualTo("reviewer");
    }
  }

  @Test
  public void intentionallyBlankSourceCanRemainBlank() {
    for (String source : List.of("", " \n", "\u00a0")) {
      TextUnitDTO textUnit = textUnit(1L, source);
      var prepared =
          AiTranslateService.prepareForTextUnitDTOForImport(
              "response",
              AiTranslateType.TARGET_ONLY,
              Status.REVIEW_NEEDED,
              textUnit,
              new AiTranslateType.SimpleCompletionOutput(source),
              "request");
      assertThat(prepared.error()).isNull();
    }
  }

  @Test
  public void inputConversionsPreserveGlossaryProtectionAndSourceIdentity() {
    var single =
        new AiTranslateType.CompletionInput(
            "fr-FR",
            "Open ChatGPT",
            "Button",
            null,
            List.of(
                new AiTranslateType.CompletionInput.GlossaryTerm(
                    "ChatGPT", "Product", "ChatGPT", null, true)),
            List.of());

    var multi = AiTranslateType.CompletionMultiTextUnitInput.from(42L, single);

    assertThat(multi.textUnitsToTranslate().getFirst().tmTextUnitId()).isEqualTo(42L);
    assertThat(multi.textUnitsToTranslate().getFirst().glossaryTerms().getFirst().doNotTranslate())
        .isTrue();
    assertThat(AiTranslateType.CompletionInput.from(multi)).isEqualTo(single);
    assertThatThrownBy(
            () ->
                AiTranslateType.CompletionInput.from(
                    new AiTranslateType.CompletionMultiTextUnitInput(
                        "fr-FR",
                        List.of(
                            multi.textUnitsToTranslate().getFirst(),
                            multi.textUnitsToTranslate().getFirst()))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  public void statedConfidenceDoesNotBypassSourceIdentity() {
    var output =
        new AiTranslateType.CompletionOutput(
            "Delete",
            new AiTranslateType.CompletionOutput.Target("Supprimer", "", 100),
            null,
            null,
            null,
            new AiTranslateType.CompletionOutput.ReviewRequired(false, ""));

    assertThatThrownBy(
            () ->
                AiTranslateService.validateCompletionOutput(
                    AiTranslateType.WITH_REVIEW, List.of(textUnit(1L, "Save")), output))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("requested source");
  }

  private static TextUnitDTO textUnit(long id, String source) {
    TextUnitDTO textUnit = new TextUnitDTO();
    textUnit.setTmTextUnitId(id);
    textUnit.setSource(source);
    textUnit.setComment("s:00000000-0000-0000-0000-000000000001");
    return textUnit;
  }

  private static ResponsesResponse response(String status, String output) {
    return new ResponsesResponse(
        "response-id",
        "response",
        1L,
        status,
        null,
        null,
        "model",
        List.of(
            new ResponsesResponse.Output(
                "message",
                "message",
                "completed",
                List.of(new ResponsesResponse.Content("output_text", output)),
                "assistant")),
        null,
        null);
  }

  private static class Fixture {
    final ObjectMapper mapper = new ObjectMapper();
    final OpenAIClient provider = mock(OpenAIClient.class);
    final OpenAIClientPool pool = mock(OpenAIClientPool.class);
    final AiTranslateTextUnitAttemptService lineage = mock(AiTranslateTextUnitAttemptService.class);
    final List<TextUnitDTO> textUnits = List.of(textUnit(1L, "Save"), textUnit(2L, "Cancel"));
    final List<ResponsesRequest> requests = new ArrayList<>();
    List<TextUnitDTOWithVariantComment> imported = List.of();
    final AiTranslateService service;

    Fixture() {
      AiTranslateService.configureObjectMapper(mapper);
      Repository repository = new Repository();
      repository.setId(10L);
      repository.setName("test");
      Locale locale = new Locale();
      locale.setId(20L);
      locale.setBcp47Tag("fr-FR");
      RepositoryLocale repositoryLocale = new RepositoryLocale();
      repositoryLocale.setLocale(locale);
      repositoryLocale.setRepository(repository);
      RepositoryRepository repositories = mock(RepositoryRepository.class);
      when(repositories.findByName("test")).thenReturn(repository);
      RepositoryService repositoryService = mock(RepositoryService.class);
      when(repositoryService.getRepositoryLocalesWithoutRootLocale(repository))
          .thenReturn(Set.of(repositoryLocale));
      TextUnitSearcher searcher = mock(TextUnitSearcher.class);
      when(searcher.search(any())).thenReturn(textUnits);
      TextUnitBatchImporterService importer = mock(TextUnitBatchImporterService.class);
      when(importer.importTextUnitsWithVariantComment(
              anyList(), any(), any(), (ImportContext) any()))
          .thenAnswer(
              invocation -> {
                imported = List.copyOf(invocation.getArgument(0));
                return List.of();
              });
      when(pool.submit(any()))
          .thenAnswer(
              invocation -> {
                Function<OpenAIClient, CompletableFuture<ResponsesResponse>> action =
                    invocation.getArgument(0);
                return action.apply(provider);
              });
      service =
          new AiTranslateService(
              searcher,
              repositories,
              repositoryService,
              importer,
              mock(StructuredBlobStorage.class),
              new AiTranslateConfigurationProperties(),
              provider,
              pool,
              mapper,
              Retry.backoff(1, Duration.ofMillis(1)),
              mock(QuartzPollableTaskScheduler.class),
              mock(PollableTaskBlobStorage.class),
              mock(PollableTaskService.class),
              mock(TextUnitDTOsCacheService.class),
              mock(AssetTextUnitRepository.class),
              mock(TMTextUnitVariantRepository.class),
              mock(GlossaryService.class),
              new SimpleMeterRegistry(),
              mock(ScreenshotService.class),
              mock(AiTranslateScreenshotService.class),
              mock(AiTranslateLegacyBatchService.class),
              mock(AiTranslateLocalePromptSuffixService.class),
              new AiTranslateSourcePromptRuleService(
                  mock(AiTranslateSourcePromptRuleRepository.class)),
              lineage);
    }

    void respondWith(Function<ResponsesRequest, ResponsesResponse> response) {
      when(provider.getResponses(any(), any()))
          .thenAnswer(
              invocation -> {
                ResponsesRequest request = invocation.getArgument(0);
                requests.add(request);
                return CompletableFuture.completedFuture(response.apply(request));
              });
    }

    JsonNode input(ResponsesRequest request) {
      return mapper.readValueUnchecked(
          ((ResponsesRequest.InputMessage.Text) request.input().getFirst().content().getFirst())
              .text(),
          JsonNode.class);
    }

    void run(AiTranslateType type) {
      PollableTask task = new PollableTask();
      task.setId(30L);
      service.aiTranslateNoBatch(
          new AiTranslateService.AiTranslateInput(
              "test",
              null,
              100,
              null,
              false,
              null,
              null,
              null,
              type.name(),
              StatusFilter.FOR_TRANSLATION.name(),
              Status.REVIEW_NEEDED.name(),
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              false,
              false,
              false,
              false,
              null),
          task);
    }
  }
}
