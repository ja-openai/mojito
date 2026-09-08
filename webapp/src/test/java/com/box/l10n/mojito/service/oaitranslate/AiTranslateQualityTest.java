package com.box.l10n.mojito.service.oaitranslate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.Locale;
import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.RepositoryLocale;
import com.box.l10n.mojito.entity.TMTextUnitVariant.Status;
import com.box.l10n.mojito.entity.TMTextUnitVariantComment;
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
  public void pluralGuidanceOnlyGroupsPluralStringsSharingTheSameScreenshot() {
    Fixture fixture =
        new Fixture(
            "ar",
            textUnit(1L, "{count, plural, one {# item} other {# items}}"),
            textUnit(2L, "Cancel"),
            textUnit(3L, "{count, plural, one {# file} other {# files}}"));
    fixture.respondWithSourcesAsTargets();

    fixture.run(AiTranslateType.TARGET_ONLY_NEW);

    assertThat(fixture.requests).hasSize(2);
    ResponsesRequest pluralRequest = fixture.requests.getFirst();
    assertThat(fixture.input(pluralRequest).get("textUnitsToTranslate"))
        .extracting(unit -> unit.get("tmTextUnitId").asLong())
        .containsExactly(1L, 3L);
    assertThat(pluralRequest.instructions())
        .contains(
            "Plural requirements for target locale ar:",
            "cardinal plural rules have 6 categories: zero, one, two, few, many, other");
    ResponsesRequest plainRequest = fixture.requests.getLast();
    assertThat(fixture.input(plainRequest).at("/textUnitsToTranslate/0/source").asText())
        .isEqualTo("Cancel");
    assertThat(plainRequest.instructions()).isEqualTo(AiTranslateType.TARGET_ONLY_NEW.getPrompt());
    assertThat(fixture.imported).hasSize(3);
  }

  @Test
  public void cardinalAndOrdinalStringsReceiveSeparateLocalePluralGuidance() {
    Fixture fixture =
        new Fixture(
            "en-US",
            textUnit(1L, "{count, plural, one {# item} other {# items}}"),
            textUnit(2L, "{rank, selectordinal, one {#st} other {#th}}"));
    fixture.respondWithSourcesAsTargets();

    fixture.run(AiTranslateType.TARGET_ONLY_NEW);

    assertThat(fixture.requests).hasSize(2);
    assertThat(fixture.requests.getFirst().instructions())
        .contains("cardinal plural rules have 2 categories: one, other")
        .doesNotContain("ordinal plural rules");
    assertThat(fixture.requests.getLast().instructions())
        .contains("ordinal plural rules have 4 categories: one, two, few, other")
        .doesNotContain("cardinal plural rules");
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
              "request",
              false);

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
              "request",
              false);
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

  @Test
  public void messageFormatValidationIsDisabledByDefaultForOnlineTranslations() {
    for (TextUnitDTO unit : pluralTextUnitsMissingArabicForms()) {
      Fixture fixture = new Fixture("ar", unit);
      fixture.respondWith(
          request ->
              response(
                  "completed",
                  fixture.mapper.writeValueAsStringUnchecked(
                      new AiTranslateType.SimpleCompletionOutput(unit.getSource()))));

      fixture.run(AiTranslateType.TARGET_ONLY);

      assertThat(fixture.configuration.isMessageFormatValidationEnabled()).isFalse();
      assertThat(fixture.requests).hasSize(1);
      assertThat(fixture.imported).hasSize(1);
      assertThat(unit.getTarget()).isEqualTo(unit.getSource());
      assertThat(fixture.imported.getFirst().tmTextUnitVariantComment().getSeverity())
          .isEqualTo(TMTextUnitVariantComment.Severity.INFO);
      assertThat(fixture.imported.getFirst().tmTextUnitVariantComment().getContent())
          .doesNotContain("MessageFormat review findings");
      verify(fixture.lineage, never())
          .markNoBatchTextUnitFailed(any(), anyString(), any(), anyString());
    }
  }

  @Test
  public void disabledMessageFormatValidationImportsOriginalAndExistingRepairBatches() {
    for (boolean repair : List.of(false, true)) {
      for (TextUnitDTO unit : pluralTextUnitsMissingArabicForms()) {
        Fixture fixture = new Fixture("ar", unit);

        var result =
            fixture.importBatch(
                batchLine(fixture, unit.getTmTextUnitId(), unit.getSource()), repair);

        assertThat(result.repairs()).isEmpty();
        assertThat(result.errors()).isEmpty();
        assertThat(fixture.imported).hasSize(1);
        assertThat(fixture.imported.getFirst().textUnitDTO().getTarget())
            .isEqualTo(unit.getSource());
        assertThat(fixture.imported.getFirst().tmTextUnitVariantComment().getSeverity())
            .isEqualTo(TMTextUnitVariantComment.Severity.INFO);
        assertThat(fixture.imported.getFirst().tmTextUnitVariantComment().getContent())
            .doesNotContain("MessageFormat review findings");
      }
    }
  }

  @Test
  public void disabledMessageFormatValidationStillRejectsInvalidBatchResponses() {
    for (boolean repair : List.of(false, true)) {
      for (boolean empty : List.of(false, true)) {
        Fixture fixture = new Fixture("fr", textUnit(1L, "Save"));
        String output = batchLine(fixture, 1L, empty ? "" : "Enregistrer");
        if (!empty) {
          output = output.replace("\"finish_reason\":\"stop\"", "\"finish_reason\":\"length\"");
        }

        var result = fixture.importBatch(output, repair);

        assertThat(fixture.imported).isEmpty();
        assertThat(result.repairs()).isEmpty();
        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().getFirst())
            .contains(empty ? "empty target" : "did not complete successfully");
      }
    }
  }

  @Test
  public void disabledMessageFormatValidationDoesNotAddPlaceholderWarnings() {
    Fixture fixture = new Fixture("fr", textUnit(1L, "Hello {name}"));
    fixture.respondWith(
        request ->
            response(
                "completed",
                fixture.mapper.writeValueAsStringUnchecked(
                    new AiTranslateType.SimpleCompletionOutput("Bonjour"))));

    fixture.run(AiTranslateType.TARGET_ONLY);

    assertThat(fixture.requests).hasSize(1);
    assertThat(fixture.imported).hasSize(1);
    assertThat(fixture.imported.getFirst().textUnitDTO().getTarget()).isEqualTo("Bonjour");
    assertThat(fixture.imported.getFirst().tmTextUnitVariantComment().getSeverity())
        .isEqualTo(TMTextUnitVariantComment.Severity.INFO);
    assertThat(fixture.imported.getFirst().tmTextUnitVariantComment().getContent())
        .doesNotContain("MessageFormat review findings", "name");
  }

  private static List<TextUnitDTO> pluralTextUnitsMissingArabicForms() {
    TextUnitDTO icu = textUnit(1L, "{count, plural, one {# item} other {# items}}");
    TextUnitDTO mf2 =
        textUnit(
            2L, ".input {$count :integer}\n.match $count\none {{One item}}\n* {{{$count} items}}");
    mf2.setMessageFormat("MF2");
    return List.of(icu, mf2);
  }

  @Test
  public void batchQueuesOnlyInvalidCandidatesAndSecondFailureIsTerminal() {
    for (boolean repair : List.of(false, true)) {
      TextUnitDTO first = textUnit(1L, "{count, plural, one {# item} other {# items}}");
      first.setTarget("Human translation");
      first.setStatus(Status.APPROVED);
      Fixture fixture =
          new Fixture("ar", first, textUnit(2L, "{count, plural, one {# file} other {# files}}"));
      fixture.configuration.setMessageFormatValidationEnabled(true);
      var result =
          fixture.importBatch(
              batchLine(fixture, 1L, first.getSource())
                  + "\n"
                  + batchLine(fixture, 2L, arabicTarget("count")),
              repair);
      assertThat(fixture.imported)
          .extracting(t -> t.textUnitDTO().getTmTextUnitId())
          .containsExactly(2L);
      assertThat(first.getTarget()).isEqualTo("Human translation");
      assertThat(first.getStatus()).isEqualTo(Status.APPROVED);
      if (repair) {
        assertThat(result.repairs()).isEmpty();
        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().getFirst()).contains("tmTextUnitId 1");
      } else {
        assertThat(result.repairs())
            .extracting(AiTranslateService.BatchRepairCandidate::tmTextUnitId)
            .containsExactly(1L);
        assertThat(result.errors()).isEmpty();
      }
    }
  }

  @Test
  public void batchRejectsDuplicateAndUnexpectedIdsBeforeMutation() {
    for (long id : List.of(1L, 3L)) {
      Fixture fixture = new Fixture();
      String output = batchLine(fixture, 1L, "Save") + "\n" + batchLine(fixture, id, "Cancel");
      assertThatThrownBy(() -> fixture.importBatch(output, false))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("unrequested or duplicate");
      assertThat(fixture.imported).isEmpty();
      assertThat(fixture.textUnits).allSatisfy(t -> assertThat(t.getTarget()).isNull());
    }
  }

  private static String batchLine(Fixture fixture, long id, String target) {
    String output =
        fixture.mapper.writeValueAsStringUnchecked(
            new AiTranslateType.SimpleCompletionOutput(target));
    return fixture.mapper.writeValueAsStringUnchecked(
        java.util.Map.of(
            "id",
            "response-" + id,
            "custom_id",
            Long.toString(id),
            "response",
            java.util.Map.of(
                "status_code",
                200,
                "body",
                java.util.Map.of(
                    "choices",
                    List.of(
                        java.util.Map.of(
                            "finish_reason",
                            "stop",
                            "message",
                            java.util.Map.of("role", "assistant", "content", output)))))));
  }

  private static String arabicTarget(String name) {
    return "{"
        + name
        + ", plural, zero {No items} one {One item} two {Two items}"
        + " few {# items} many {# items} other {# items}}";
  }

  @Test
  public void repairsOnlyTheFailedStringAndKeepsTheOriginalContext() {
    Fixture fixture =
        new Fixture(
            "ar",
            textUnit(1L, "{count, plural, one {# item} other {# items}}"),
            textUnit(2L, "{count, plural, one {# file} other {# files}}"));
    fixture.configuration.setMessageFormatValidationEnabled(true);
    fixture.respondWith(
        request -> {
          var input =
              fixture.mapper.convertValue(
                  fixture.input(request), AiTranslateType.CompletionMultiTextUnitInput.class);
          boolean repair = fixture.requests.size() == 2;
          if (repair) {
            assertThat(input.textUnitsToTranslate())
                .extracting(AiTranslateType.CompletionMultiTextUnitInput.TextUnit::tmTextUnitId)
                .containsExactly(1L);
            assertThat(input.textUnitsToTranslate().getFirst().sourceDescription()).isNotBlank();
            assertThat(request.instructions())
                .contains(
                    "Plural requirements for target locale ar",
                    AiTranslateCandidateValidation.REPAIR_INSTRUCTION);
            assertThat(request.input().getLast().content().getFirst())
                .isInstanceOf(OpenAIClient.ResponsesRequest.InputMessage.Text.class);
            String feedback =
                ((OpenAIClient.ResponsesRequest.InputMessage.Text)
                        request.input().getLast().content().getFirst())
                    .text();
            assertThat(feedback).contains("diagnostics", "target", "count");
          }
          return response(
              "completed",
              fixture.mapper.writeValueAsStringUnchecked(
                  new AiTranslateType.CompletionMultiTextUnitOutput(
                      input.textUnitsToTranslate().stream()
                          .map(
                              unit ->
                                  new AiTranslateType.CompletionMultiTextUnitOutput.Target(
                                      unit.tmTextUnitId(),
                                      repair || unit.tmTextUnitId() == 2L
                                          ? arabicTarget("count")
                                          : unit.source()))
                          .toList(),
                      null)));
        });

    fixture.run(AiTranslateType.TARGET_ONLY_NEW);

    assertThat(fixture.requests).hasSize(2);
    assertThat(fixture.imported).hasSize(2);
    verify(fixture.lineage)
        .markNoBatchTextUnitFailed(
            any(), anyString(), org.mockito.ArgumentMatchers.eq(1L), anyString());
    verify(fixture.lineage, never())
        .markNoBatchTextUnitFailed(
            any(), anyString(), org.mockito.ArgumentMatchers.eq(2L), anyString());
  }

  @Test
  public void unresolvedRepairPreservesHumanTargetStatusAndAttribution() {
    TextUnitDTO unit = textUnit(1L, "{count, plural, one {# item} other {# items}}");
    unit.setTarget("Human translation");
    unit.setTargetComment("Human context");
    unit.setStatus(Status.APPROVED);
    unit.setTranslatorIdentity("translator");
    unit.setReviewerIdentity("reviewer");
    Fixture fixture = new Fixture("ar", unit);
    fixture.configuration.setMessageFormatValidationEnabled(true);
    fixture.respondWith(
        request ->
            response(
                "completed",
                fixture.mapper.writeValueAsStringUnchecked(
                    new AiTranslateType.SimpleCompletionOutput(unit.getSource()))));

    fixture.run(AiTranslateType.TARGET_ONLY);

    assertThat(fixture.requests).hasSize(2);
    assertThat(fixture.imported).isEmpty();
    assertThat(unit.getTarget()).isEqualTo("Human translation");
    assertThat(unit.getTargetComment()).isEqualTo("Human context");
    assertThat(unit.getStatus()).isEqualTo(Status.APPROVED);
    assertThat(unit.getTranslatorIdentity()).isEqualTo("translator");
    assertThat(unit.getReviewerIdentity()).isEqualTo("reviewer");
  }

  @Test
  public void warningsAreImportedAsReviewFindingsWithoutRetry() {
    Fixture fixture = new Fixture("fr", textUnit(1L, "Hello {name}"));
    fixture.configuration.setMessageFormatValidationEnabled(true);
    fixture.respondWith(
        request ->
            response(
                "completed",
                fixture.mapper.writeValueAsStringUnchecked(
                    new AiTranslateType.SimpleCompletionOutput("Bonjour"))));

    fixture.run(AiTranslateType.TARGET_ONLY);

    assertThat(fixture.requests).hasSize(1);
    assertThat(fixture.imported).hasSize(1);
    assertThat(fixture.imported.getFirst().tmTextUnitVariantComment().getSeverity())
        .isEqualTo(TMTextUnitVariantComment.Severity.WARNING);
    assertThat(fixture.imported.getFirst().tmTextUnitVariantComment().getContent())
        .contains("name");
  }

  @Test
  public void sourceDefectDoesNotCauseRepairOrImport() {
    Fixture fixture = new Fixture("ar", textUnit(1L, "{count, plural, one {item}"));
    fixture.configuration.setMessageFormatValidationEnabled(true);
    fixture.respondWith(
        request ->
            response(
                "completed",
                fixture.mapper.writeValueAsStringUnchecked(
                    new AiTranslateType.SimpleCompletionOutput("Item"))));

    fixture.run(AiTranslateType.TARGET_ONLY);

    assertThat(fixture.requests).hasSize(1);
    assertThat(fixture.imported).isEmpty();
    assertThat(fixture.textUnits.getFirst().getTarget()).isNull();
  }

  @Test
  public void invalidRepairResponseCannotMutateTheCandidate() {
    for (String failure : List.of("incomplete", "wrong-id", "empty")) {
      TextUnitDTO unit = textUnit(1L, "{count, plural, one {# item} other {# items}}");
      unit.setTarget("Human translation");
      Fixture fixture = new Fixture("ar", unit);
      fixture.configuration.setMessageFormatValidationEnabled(true);
      fixture.respondWith(
          request -> {
            if (fixture.requests.size() == 1) {
              return response(
                  "completed",
                  fixture.mapper.writeValueAsStringUnchecked(
                      new AiTranslateType.CompletionMultiTextUnitOutput(
                          List.of(
                              new AiTranslateType.CompletionMultiTextUnitOutput.Target(
                                  1L, unit.getSource())),
                          null)));
            }
            return response(
                failure.equals("incomplete") ? "incomplete" : "completed",
                fixture.mapper.writeValueAsStringUnchecked(
                    new AiTranslateType.CompletionMultiTextUnitOutput(
                        List.of(
                            new AiTranslateType.CompletionMultiTextUnitOutput.Target(
                                failure.equals("wrong-id") ? 2L : 1L,
                                failure.equals("empty") ? "" : arabicTarget("count"))),
                        null)));
          });

      fixture.run(AiTranslateType.TARGET_ONLY_NEW);

      assertThat(fixture.requests).hasSize(2);
      assertThat(fixture.imported).isEmpty();
      assertThat(unit.getTarget()).isEqualTo("Human translation");
    }
  }

  @Test
  public void repairRequestRetainsScreenshotAndProviderSettings() {
    TextUnitDTO unit = textUnit(1L, "{count, plural, one {# item} other {# items}}");
    Fixture fixture = new Fixture("ar", unit);
    String payload =
        fixture.mapper.writeValueAsStringUnchecked(
            new AiTranslateType.CompletionMultiTextUnitInput(
                "ar",
                List.of(
                    new AiTranslateType.CompletionMultiTextUnitInput.TextUnit(
                        1L, unit.getSource(), "Description", null, List.of(), List.of()),
                    new AiTranslateType.CompletionMultiTextUnitInput.TextUnit(
                        2L, "Peer", "Peer description", null, List.of(), List.of()))));
    ResponsesRequest original =
        ResponsesRequest.builder()
            .model("model")
            .reasoningEffort("high")
            .textVerbosity("low")
            .serviceTier("default")
            .instructions("Locale and source instructions")
            .addJsonSchema(AiTranslateType.CompletionMultiTextUnitOutput.class)
            .addUserText(payload)
            .addUserImageUrl("https://example.com/screenshot.png")
            .addMetadata("request", "original")
            .build();

    ResponsesRequest repair =
        fixture.service.buildRepairRequest(
            original,
            AiTranslateType.TARGET_ONLY_NEW,
            unit,
            unit.getSource(),
            AiTranslateCandidateValidation.evaluate(unit, unit.getSource(), "ar"));

    assertThat(repair.input().get(1)).isEqualTo(original.input().get(1));
    assertThat(repair.model()).isEqualTo(original.model());
    assertThat(repair.reasoning()).isEqualTo(original.reasoning());
    assertThat(repair.text()).isEqualTo(original.text());
    assertThat(repair.serviceTier()).isEqualTo(original.serviceTier());
    assertThat(repair.metadata()).isEqualTo(original.metadata());
    assertThat(fixture.input(repair).get("textUnitsToTranslate")).hasSize(1);
    assertThat(fixture.input(repair).at("/textUnitsToTranslate/0/sourceDescription").asText())
        .isEqualTo("Description");
  }

  @Test
  public void mf2MissingFormsAreRepairedBeforeImport() {
    TextUnitDTO unit =
        textUnit(
            1L, ".input {$count :integer}\n.match $count\none {{One item}}\n* {{{$count} items}}");
    unit.setMessageFormat("MF2");
    Fixture fixture = new Fixture("ar", unit);
    fixture.configuration.setMessageFormatValidationEnabled(true);
    String target =
        ".input {$count :integer}\n.match $count\nzero {{No items}}\none {{One item}}"
            + "\ntwo {{Two items}}\nfew {{{$count} items}}\nmany {{{$count} items}}\n* {{{$count} items}}";
    fixture.respondWith(
        request ->
            response(
                "completed",
                fixture.mapper.writeValueAsStringUnchecked(
                    new AiTranslateType.SimpleCompletionOutput(
                        fixture.requests.size() == 1 ? unit.getSource() : target))));

    fixture.run(AiTranslateType.TARGET_ONLY);

    assertThat(fixture.requests).hasSize(2);
    assertThat(fixture.imported).hasSize(1);
    assertThat(unit.getTarget()).isEqualTo(target);
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
    final AiTranslateConfigurationProperties configuration =
        new AiTranslateConfigurationProperties();
    final OpenAIClient provider = mock(OpenAIClient.class);
    final OpenAIClientPool pool = mock(OpenAIClientPool.class);
    final AiTranslateTextUnitAttemptService lineage = mock(AiTranslateTextUnitAttemptService.class);
    final List<TextUnitDTO> textUnits;
    final List<ResponsesRequest> requests = new ArrayList<>();
    final StructuredBlobStorage blobs = mock(StructuredBlobStorage.class);
    final AiTranslateBatchRepairService batchRepairs = mock(AiTranslateBatchRepairService.class);
    List<TextUnitDTOWithVariantComment> imported = List.of();
    final AiTranslateService service;

    Fixture() {
      this("fr-FR", textUnit(1L, "Save"), textUnit(2L, "Cancel"));
    }

    Fixture(String localeTag, TextUnitDTO... textUnits) {
      this.textUnits = List.of(textUnits);
      this.textUnits.forEach(unit -> unit.setTargetLocale(localeTag));
      AiTranslateService.configureObjectMapper(mapper);
      Repository repository = new Repository();
      repository.setId(10L);
      repository.setName("test");
      Locale locale = new Locale();
      locale.setId(20L);
      locale.setBcp47Tag(localeTag);
      RepositoryLocale repositoryLocale = new RepositoryLocale();
      repositoryLocale.setLocale(locale);
      repositoryLocale.setRepository(repository);
      RepositoryRepository repositories = mock(RepositoryRepository.class);
      when(repositories.findByName("test")).thenReturn(repository);
      RepositoryService repositoryService = mock(RepositoryService.class);
      when(repositoryService.getRepositoryLocalesWithoutRootLocale(repository))
          .thenReturn(Set.of(repositoryLocale));
      TextUnitSearcher searcher = mock(TextUnitSearcher.class);
      when(searcher.search(any())).thenReturn(this.textUnits);
      TMTextUnitVariantRepository variants = mock(TMTextUnitVariantRepository.class);
      when(variants.findAllByIdIn(anyList()))
          .thenReturn(
              this.textUnits.stream()
                  .map(
                      unit -> {
                        unit.setTmTextUnitVariantId(unit.getTmTextUnitId() + 1000);
                        var variant = new com.box.l10n.mojito.entity.TMTextUnitVariant();
                        variant.setId(unit.getTmTextUnitVariantId());
                        variant.setTmTextUnitVariantComments(Set.of());
                        return variant;
                      })
                  .toList());
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
              blobs,
              configuration,
              provider,
              pool,
              mapper,
              Retry.backoff(1, Duration.ofMillis(1)),
              mock(QuartzPollableTaskScheduler.class),
              mock(PollableTaskBlobStorage.class),
              mock(PollableTaskService.class),
              mock(TextUnitDTOsCacheService.class),
              mock(AssetTextUnitRepository.class),
              variants,
              mock(GlossaryService.class),
              new SimpleMeterRegistry(),
              mock(ScreenshotService.class),
              mock(AiTranslateScreenshotService.class),
              mock(AiTranslateLegacyBatchService.class),
              mock(AiTranslateLocalePromptSuffixService.class),
              new AiTranslateSourcePromptRuleService(
                  mock(AiTranslateSourcePromptRuleRepository.class)),
              lineage);
      service.batchRepairService = batchRepairs;
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

    void respondWithSourcesAsTargets() {
      respondWith(
          request -> {
            AiTranslateType.CompletionMultiTextUnitInput input =
                mapper.convertValue(
                    input(request), AiTranslateType.CompletionMultiTextUnitInput.class);
            return response(
                "completed",
                mapper.writeValueAsStringUnchecked(
                    new AiTranslateType.CompletionMultiTextUnitOutput(
                        input.textUnitsToTranslate().stream()
                            .map(
                                unit ->
                                    new AiTranslateType.CompletionMultiTextUnitOutput.Target(
                                        unit.tmTextUnitId(),
                                        input.locale().equals("ar")
                                                && unit.source().contains(", plural,")
                                            ? arabicTarget("count")
                                            : unit.source().contains("selectordinal")
                                                ? "{rank, selectordinal, one {#st} two {#nd} few {#rd} other {#th}}"
                                                : unit.source()))
                            .toList(),
                        null)));
          });
    }

    JsonNode input(ResponsesRequest request) {
      return mapper.readValueUnchecked(
          ((ResponsesRequest.InputMessage.Text) request.input().getFirst().content().getFirst())
              .text(),
          JsonNode.class);
    }

    AiTranslateService.BatchImportResult importBatch(String output, boolean repair) {
      var batch =
          mapper.readValueUnchecked(
              "{\"id\":\"batch\",\"input_file_id\":\"input\",\"output_file_id\":\"output\",\"metadata\":{\"textUnitDTOs\":\"snapshot\""
                  + (repair ? ",\"repairAttempt\":\"1\"" : "")
                  + "}}",
              OpenAIClient.RetrieveBatchResponse.class);
      when(batchRepairs.getRequestedTextUnitIds(any()))
          .thenReturn(
              textUnits.stream()
                  .map(TextUnitDTO::getTmTextUnitId)
                  .collect(java.util.stream.Collectors.toSet()));
      when(blobs.getString(StructuredBlobStorage.Prefix.AI_TRANSLATE_WS, "snapshot"))
          .thenReturn(
              java.util.Optional.of(
                  mapper.writeValueAsStringUnchecked(
                      new AiTranslateService.AiTranslateBlobStorage(
                          textUnits.stream()
                              .map(
                                  unit ->
                                      new AiTranslateService.TextUnitDTOWithVariantComments(
                                          unit, Set.of()))
                              .toList()))));
      when(provider.downloadFileContent(any()))
          .thenReturn(new OpenAIClient.DownloadFileContentResponse(output));
      PollableTask task = new PollableTask();
      task.setId(30L);
      return service.importBatchForRepair(
          batch, AiTranslateType.TARGET_ONLY, Status.REVIEW_NEEDED, task);
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
