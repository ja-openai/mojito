package com.box.l10n.mojito.rest.glossary;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.mf2.inflection.CompiledTermPack;
import com.box.l10n.mojito.mf2.inflection.CompiledTermPack.ExportPolicy;
import com.box.l10n.mojito.mf2.inflection.CompiledTermPack.Provenance;
import com.box.l10n.mojito.mf2.inflection.CompiledTermPack.SizeEstimates;
import com.box.l10n.mojito.mf2.inflection.TermInflectionProfilePackJsonLoader;
import com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage;
import com.box.l10n.mojito.service.glossary.GlossaryImportExportService;
import com.box.l10n.mojito.service.glossary.GlossaryManagementService;
import com.box.l10n.mojito.service.glossary.GlossaryTermIndexCurationService;
import com.box.l10n.mojito.service.glossary.GlossaryTermInflectionProfileService;
import com.box.l10n.mojito.service.glossary.GlossaryTermService;
import com.box.l10n.mojito.service.oaitranslate.GlossaryService;
import com.box.l10n.mojito.service.security.user.UserService;
import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

@RunWith(MockitoJUnitRunner.class)
public class GlossaryWSTest {

  @Mock GlossaryManagementService glossaryManagementService;
  @Mock GlossaryImportExportService glossaryImportExportService;
  @Mock GlossaryTermService glossaryTermService;
  @Mock GlossaryTermIndexCurationService glossaryTermIndexCurationService;
  @Mock GlossaryTermInflectionProfileService glossaryTermInflectionProfileService;
  @Mock GlossaryService glossaryService;
  @Mock StructuredBlobStorage structuredBlobStorage;
  @Mock TermIndexEntriesHybridProperties termIndexEntriesHybridProperties;
  @Mock AsyncTaskExecutor termIndexEntriesHybridExecutor;
  @Mock UserService userService;

  ObjectMapper objectMapper;
  private GlossaryWS glossaryWS;
  private SimpleMeterRegistry meterRegistry;

  @Before
  public void setUp() {
    objectMapper = new ObjectMapper();
    meterRegistry = new SimpleMeterRegistry();
    glossaryWS =
        new GlossaryWS(
            glossaryManagementService,
            glossaryImportExportService,
            glossaryTermService,
            glossaryTermIndexCurationService,
            glossaryTermInflectionProfileService,
            glossaryService,
            structuredBlobStorage,
            objectMapper,
            termIndexEntriesHybridProperties,
            termIndexEntriesHybridExecutor,
            userService,
            meterRegistry);
  }

  @Test
  public void matchGlossaryTermsRecordsSuccessMetric() {
    when(glossaryService.findMatchesForRepositoryAndLocale(
            12L, null, null, "fr-FR", "Show mobile previews", null))
        .thenReturn(List.of());

    GlossaryWS.MatchGlossaryTermsResponse response =
        glossaryWS.matchGlossaryTerms(
            new GlossaryWS.MatchGlossaryTermsRequest(
                12L, null, null, "fr-FR", "Show mobile previews", null));

    assertEquals(0, response.matchedTerms().size());
    assertEquals(1.0, matchDurationCount("success", "repository_id"), 0.0);
  }

  @Test
  public void matchGlossaryTermsReturnsTermKeyForBindingConsumers() {
    when(glossaryService.findMatchesForRepositoryAndLocale(
            12L, null, null, "fr-FR", "Show mobile previews", null))
        .thenReturn(
            List.of(
                new GlossaryService.MatchedGlossaryTerm(
                    new GlossaryService.GlossaryTerm(
                        34L,
                        56L,
                        "Product UI",
                        "product.mobile_preview",
                        "mobile preview",
                        "Button label context",
                        "A preview of a mobile UI",
                        "noun",
                        "product term",
                        "required",
                        "APPROVED",
                        "human curated",
                        "aperçu mobile",
                        "Use noun form",
                        false,
                        false,
                        List.of()),
                    GlossaryService.MatchType.EXACT,
                    5,
                    19,
                    "mobile preview")));

    GlossaryWS.MatchGlossaryTermsResponse response =
        glossaryWS.matchGlossaryTerms(
            new GlossaryWS.MatchGlossaryTermsRequest(
                12L, null, null, "fr-FR", "Show mobile previews", null));

    GlossaryWS.MatchGlossaryTermsResponse.MatchedGlossaryTermResponse match =
        response.matchedTerms().get(0);
    assertEquals("product.mobile_preview", match.termKey());
    assertEquals(34L, match.tmTextUnitId().longValue());
    assertEquals("mobile preview", match.source());
  }

  @Test
  public void matchGlossaryTermsRecordsBadRequestMetric() {
    when(glossaryService.findMatchesForRepositoryAndLocale(null, null, null, null, null, null))
        .thenThrow(new IllegalArgumentException("Locale tag is required"));

    ResponseStatusException exception =
        assertThrows(ResponseStatusException.class, () -> glossaryWS.matchGlossaryTerms(null));

    assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    assertEquals(1.0, matchDurationCount("bad_request", "missing"), 0.0);
  }

  @Test
  public void matchGlossaryTermsRecordsErrorMetric() {
    when(glossaryService.findMatchesForRepositoryAndLocale(
            null, "mobile", null, "fr-FR", "Show mobile previews", null))
        .thenThrow(new IllegalStateException("Glossary matching failed"));

    IllegalStateException exception =
        assertThrows(
            IllegalStateException.class,
            () ->
                glossaryWS.matchGlossaryTerms(
                    new GlossaryWS.MatchGlossaryTermsRequest(
                        null, "mobile", null, "fr-FR", "Show mobile previews", null)));

    assertEquals("Glossary matching failed", exception.getMessage());
    assertEquals(1.0, matchDurationCount("error", "repository_name"), 0.0);
  }

  @Test
  public void getInflectionProfilesMapsServiceViews() {
    when(glossaryTermInflectionProfileService.getProfiles(1L, "fr"))
        .thenReturn(
            List.of(
                inflectionProfileView(
                    "REVIEW_NEEDED",
                    """
                    [
                      {
                        "reason": "missing-form-cell",
                        "formKey": "construct.accusative.dual",
                        "message": "Missing construct.accusative.dual"
                      },
                      {
                        "code": "missing-form-cell",
                        "formKey": "construct.genitive.dual",
                        "message": "Missing construct.genitive.dual"
                      },
                      {
                        "code": "ambiguous",
                        "message": "Source row has multiple candidates"
                      },
                      {
                        "messageId": "checkout.pay",
                        "argument": "owner",
                        "relatedArgument": "item",
                        "missing": ["agreeWith.gender", "agreeWith.count"],
                        "span": [0, 91]
                      }
                    ]
                    """)));

    GlossaryWS.SearchInflectionProfilesResponse response =
        glossaryWS.getInflectionProfiles(1L, "fr");

    assertEquals(1, response.profiles().size());
    GlossaryWS.InflectionProfileResponse profile = response.profiles().getFirst();
    assertEquals(3L, profile.glossaryTermMetadataId().longValue());
    assertEquals(2L, profile.tmTextUnitId().longValue());
    assertEquals("item.iron_sword", profile.termId());
    assertEquals("iron sword", profile.source());
    assertEquals("fr", profile.localeTag());
    assertEquals("REVIEW_NEEDED", profile.status());
    assertEquals(
        List.of("construct.accusative.dual", "construct.genitive.dual"), profile.missingFormKeys());
    assertEquals(4, profile.diagnosticSummaries().size());
    assertEquals("construct.accusative.dual", profile.diagnosticSummaries().getFirst().formKey());
    assertEquals("item", profile.diagnosticSummaries().get(3).relatedArgument());
    assertEquals(
        List.of("agreeWith.gender", "agreeWith.count"),
        profile.diagnosticSummaries().get(3).missing());
    assertEquals(List.of(0, 91), profile.diagnosticSummaries().get(3).span());
  }

  @Test
  public void upsertInflectionProfileUsesPathLocale() {
    when(glossaryTermInflectionProfileService.upsertProfile(
            eq(1L), eq(2L), org.mockito.ArgumentMatchers.any()))
        .thenReturn(inflectionProfileView("APPROVED"));

    GlossaryWS.InflectionProfileResponse response =
        glossaryWS.upsertInflectionProfile(
            1L,
            2L,
            "fr",
            new GlossaryWS.UpsertInflectionProfileRequest(
                "approved",
                "{\"partOfSpeech\":\"noun\"}",
                "{\"bare.singular\":\"epee\"}",
                null,
                "{\"source\":\"manual\"}"));

    ArgumentCaptor<GlossaryTermInflectionProfileService.InflectionProfileInput> inputCaptor =
        ArgumentCaptor.forClass(GlossaryTermInflectionProfileService.InflectionProfileInput.class);
    verify(glossaryTermInflectionProfileService)
        .upsertProfile(eq(1L), eq(2L), inputCaptor.capture());

    GlossaryTermInflectionProfileService.InflectionProfileInput input = inputCaptor.getValue();
    assertEquals("fr", input.localeTag());
    assertEquals("approved", input.status());
    assertEquals("{\"partOfSpeech\":\"noun\"}", input.morphologyJson());
    assertEquals("{\"bare.singular\":\"epee\"}", input.formsJson());
    assertEquals("{\"source\":\"manual\"}", input.provenanceJson());
    assertEquals("item.iron_sword", response.termId());
  }

  @Test
  public void reviewInflectionProfileUsesPathLocaleAndPartialReviewInput() {
    when(glossaryTermInflectionProfileService.reviewProfile(
            eq(1L), eq(2L), org.mockito.ArgumentMatchers.any()))
        .thenReturn(inflectionProfileView("APPROVED"));

    GlossaryWS.InflectionProfileResponse response =
        glossaryWS.reviewInflectionProfile(
            1L,
            2L,
            "fr",
            new GlossaryWS.ReviewInflectionProfileRequest(
                "approved",
                "{\"partOfSpeech\":\"noun\"}",
                "{\"bare.singular\":\"epee\"}",
                "[]",
                "{\"reviewedBy\":\"translator\"}"));

    ArgumentCaptor<GlossaryTermInflectionProfileService.InflectionProfileReviewInput> inputCaptor =
        ArgumentCaptor.forClass(
            GlossaryTermInflectionProfileService.InflectionProfileReviewInput.class);
    verify(glossaryTermInflectionProfileService)
        .reviewProfile(eq(1L), eq(2L), inputCaptor.capture());

    GlossaryTermInflectionProfileService.InflectionProfileReviewInput input =
        inputCaptor.getValue();
    assertEquals("fr", input.localeTag());
    assertEquals("approved", input.status());
    assertEquals("{\"partOfSpeech\":\"noun\"}", input.morphologyJson());
    assertEquals("{\"bare.singular\":\"epee\"}", input.formsJson());
    assertEquals("[]", input.diagnosticsJson());
    assertEquals("{\"reviewedBy\":\"translator\"}", input.provenanceJson());
    assertEquals("item.iron_sword", response.termId());
  }

  @Test
  public void exportCompiledInflectionProfilePackReturnsJsonAttachment() {
    when(glossaryTermInflectionProfileService.compileProfilePackExport(1L, "fr-FR"))
        .thenReturn(
            new GlossaryTermInflectionProfileService.CompiledInflectionProfilePackExport(
                new CompiledTermPack(
                    CompiledTermPack.SCHEMA,
                    "fr-FR",
                    List.of(),
                    List.of(),
                    List.of(),
                    Provenance.empty(),
                    SizeEstimates.empty(),
                    new ExportPolicy(
                        "closed-world-glossary-approved-profile-forms",
                        "explicit-form-rows-v0",
                        List.of(),
                        2,
                        0,
                        1,
                        Map.of(),
                        Map.of("disabled-profile", 1))),
                2,
                List.of(
                    new GlossaryTermInflectionProfileService.SkippedInflectionProfile(
                        "item.disabled", "disabled", "DISABLED", 1, List.of(), List.of()))));

    ResponseEntity<byte[]> response = glossaryWS.exportCompiledInflectionProfilePack(1L, "fr-FR");

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(
        "2", response.getHeaders().getFirst(GlossaryWS.INFLECTION_APPROVED_PROFILE_COUNT_HEADER));
    assertEquals(
        "1", response.getHeaders().getFirst(GlossaryWS.INFLECTION_SKIPPED_PROFILE_COUNT_HEADER));
    assertEquals(
        "closed-world-glossary-approved-profile-forms",
        response.getHeaders().getFirst(GlossaryWS.INFLECTION_RUNTIME_EXPORT_HEADER));
    assertEquals(
        "explicit-form-rows-v0",
        response.getHeaders().getFirst(GlossaryWS.INFLECTION_COMPOSITION_MODE_HEADER));
    assertEquals(
        "attachment; filename=\"glossary-1-inflection-fr-FR-compiled.json\"",
        response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION));
    byte[] bodyBytes = response.getBody();
    Assert.assertNotNull(bodyBytes);
    String body = new String(bodyBytes, StandardCharsets.UTF_8);
    assertTrue(body.contains("\"schema\":\"mojito-mf2-inflection/compiled-term-pack/v0\""));
    assertTrue(body.contains("\"locale\":\"fr-FR\""));
    assertTrue(body.contains("\"exportPolicy\""));
    assertTrue(body.contains("\"runtimeExport\":\"closed-world-glossary-approved-profile-forms\""));
  }

  @Test
  public void exportCompiledInflectionProfilePackReturnsApprovedArabicRows() {
    when(glossaryTermInflectionProfileService.compileProfilePackExport(1L, "ar"))
        .thenReturn(
            new GlossaryTermInflectionProfileService.CompiledInflectionProfilePackExport(
                new CompiledTermPack(
                    CompiledTermPack.SCHEMA,
                    "ar",
                    List.of(
                        "ar.explicit.message",
                        "رسالة",
                        "construct.genitive.dual",
                        "رسالتي",
                        "indefinite.genitive.plural",
                        "رسائل"),
                    List.of(new CompiledTermPack.TermRow(0, 1, 0, null, 0)),
                    List.of(
                        new CompiledTermPack.FormSet(
                            0,
                            List.of(
                                new CompiledTermPack.FormRow(2, 3, false),
                                new CompiledTermPack.FormRow(4, 5, false)))),
                    Provenance.empty(),
                    SizeEstimates.empty(),
                    new ExportPolicy(
                        "closed-world-glossary-approved-profile-forms",
                        "explicit-form-rows-v0",
                        List.of(),
                        1,
                        0,
                        0,
                        Map.of(),
                        Map.of())),
                1,
                List.of()));

    ResponseEntity<byte[]> response = glossaryWS.exportCompiledInflectionProfilePack(1L, "ar");

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(
        "1", response.getHeaders().getFirst(GlossaryWS.INFLECTION_APPROVED_PROFILE_COUNT_HEADER));
    assertEquals(
        "0", response.getHeaders().getFirst(GlossaryWS.INFLECTION_SKIPPED_PROFILE_COUNT_HEADER));
    assertEquals(
        "closed-world-glossary-approved-profile-forms",
        response.getHeaders().getFirst(GlossaryWS.INFLECTION_RUNTIME_EXPORT_HEADER));
    assertEquals(
        "explicit-form-rows-v0",
        response.getHeaders().getFirst(GlossaryWS.INFLECTION_COMPOSITION_MODE_HEADER));
    byte[] bodyBytes = response.getBody();
    Assert.assertNotNull(bodyBytes);
    String body = new String(bodyBytes, StandardCharsets.UTF_8);
    assertTrue(body.contains("\"locale\":\"ar\""));
    assertTrue(body.contains("\"ar.explicit.message\""));
    assertTrue(body.contains("\"construct.genitive.dual\""));
    assertTrue(body.contains("\"رسالتي\""));
    assertTrue(body.contains("\"indefinite.genitive.plural\""));
    assertTrue(body.contains("\"رسائل\""));
    assertTrue(body.contains("\"automaticExportTerms\":1"));
    assertTrue(body.contains("\"reviewRequiredTerms\":0"));
    assertTrue(body.contains("\"blockedTerms\":0"));
  }

  @Test
  public void exportCompiledInflectionProfilePackMapsValidationErrors() {
    when(glossaryTermInflectionProfileService.compileProfilePackExport(1L, "fr"))
        .thenThrow(
            new IllegalArgumentException("Cannot compile inflection profile pack for locale fr"));

    ResponseStatusException exception =
        assertThrows(
            ResponseStatusException.class,
            () -> glossaryWS.exportCompiledInflectionProfilePack(1L, "fr"));

    assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    assertEquals("Cannot compile inflection profile pack for locale fr", exception.getReason());
  }

  @Test
  public void reportInflectionBindingManifestReturnsJsonReport() {
    when(glossaryTermInflectionProfileService.profilePack(1L, "es"))
        .thenReturn(profilePack("es", "item.water"));

    ResponseEntity<byte[]> response =
        glossaryWS.reportInflectionBindingManifest(
            1L,
            "es",
            new GlossaryWS.InflectionBindingManifestReportRequest(
                """
                {
                  "schema": "mojito-mf2-inflection/message-term-binding-manifest/v0",
                  "locale": "es",
                  "messages": {
                    "inventory.deleted": "Has eliminado {$item :term article=definite count=$count}."
                  },
                  "argumentTerms": {
                    "inventory.deleted": {
                      "item": ["item.water", "item.fire"]
                    }
                  }
                }
                """));

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(MediaType.APPLICATION_JSON, response.getHeaders().getContentType());
    byte[] bodyBytes = response.getBody();
    Assert.assertNotNull(bodyBytes);
    JsonNode json = objectMapper.readTreeUnchecked(new String(bodyBytes, StandardCharsets.UTF_8));
    assertEquals("mojito-mf2-inflection/term-binding-report/v0", json.get("schema").asText());
    assertEquals("es", json.get("locale").asText());
    assertEquals(1, json.at("/summary/messages").asInt());
    assertEquals(1, json.at("/summary/requiredArguments").asInt());
    assertEquals(1, json.at("/summary/diagnostics").asInt());
    assertEquals("ambiguous", json.at("/diagnostics/0/status").asText());
    assertEquals("item.fire", json.at("/diagnostics/0/termIds/1").asText());
    verify(glossaryTermInflectionProfileService).profilePack(1L, "es");
  }

  @Test
  public void reportInflectionBindingManifestReportsUnknownTermIdsBeforeRendering() {
    when(glossaryTermInflectionProfileService.profilePack(1L, "es"))
        .thenReturn(profilePack("es", "item.water"));

    ResponseEntity<byte[]> response =
        glossaryWS.reportInflectionBindingManifest(
            1L,
            "es",
            new GlossaryWS.InflectionBindingManifestReportRequest(
                """
                {
                  "schema": "mojito-mf2-inflection/message-term-binding-manifest/v0",
                  "locale": "es",
                  "messages": {
                    "inventory.deleted": "Has eliminado {$item :term article=definite count=$count}."
                  },
                  "argumentTerms": {
                    "inventory.deleted": {
                      "item": ["item.missing"]
                    }
                  }
                }
                """));

    byte[] bodyBytes = response.getBody();
    Assert.assertNotNull(bodyBytes);
    JsonNode json = objectMapper.readTreeUnchecked(new String(bodyBytes, StandardCharsets.UTF_8));
    assertEquals(1, json.at("/summary/diagnostics").asInt());
    assertEquals("unknown", json.at("/diagnostics/0/status").asText());
    assertEquals("item.missing", json.at("/diagnostics/0/termIds/0").asText());
    assertEquals("unknown", json.at("/messages/inventory.deleted/arguments/item/status").asText());
  }

  @Test
  public void reportInflectionBindingManifestUsesPathLocaleWhenManifestOmitsLocale() {
    when(glossaryTermInflectionProfileService.profilePack(1L, "hi"))
        .thenReturn(profilePack("hi", "hi.case.अंगारा"));

    ResponseEntity<byte[]> response =
        glossaryWS.reportInflectionBindingManifest(
            1L,
            "hi",
            new GlossaryWS.InflectionBindingManifestReportRequest(
                """
                {
                  "schema": "mojito-mf2-inflection/message-term-binding-manifest/v0",
                  "messages": {
                    "inventory.owner": "{$owner :term person=first case=genitive agreeWith=$item}."
                  },
                  "argumentTerms": {
                    "inventory.owner": {
                      "item": ["hi.case.अंगारा"]
                    }
                  }
                }
                """));

    byte[] bodyBytes = response.getBody();
    Assert.assertNotNull(bodyBytes);
    JsonNode json = objectMapper.readTreeUnchecked(new String(bodyBytes, StandardCharsets.UTF_8));
    assertEquals("hi", json.get("locale").asText());
    assertEquals("item", json.at("/messages/inventory.owner/requiredArguments/0").asText());
    assertTrue(json.at("/messages/inventory.owner/arguments/owner").isMissingNode());
  }

  @Test
  public void reportInflectionBindingManifestRejectsLocaleMismatch() {
    ResponseStatusException exception =
        assertThrows(
            ResponseStatusException.class,
            () ->
                glossaryWS.reportInflectionBindingManifest(
                    1L,
                    "fr",
                    new GlossaryWS.InflectionBindingManifestReportRequest(
                        """
                        {
                          "schema": "mojito-mf2-inflection/message-term-binding-manifest/v0",
                          "locale": "es",
                          "messages": {
                            "inventory.deleted": "{$item :term article=definite}."
                          },
                          "argumentTerms": {
                            "inventory.deleted": {
                              "item": ["item.water"]
                            }
                          }
                        }
                        """)));

    assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    assertTrue(exception.getReason().contains("does not match requested locale fr"));
  }

  @Test
  public void reportInflectionBindingManifestRejectsBlankMessageIdWithStableReason() {
    assertReportInflectionBindingManifestRejected(
        bindingManifestWithBlankMessageId(), "Expected non-blank message id");
  }

  @Test
  public void reportInflectionBindingManifestRejectsBlankArgumentNameWithStableReason() {
    assertReportInflectionBindingManifestRejected(
        bindingManifestWithBlankArgumentName(),
        "Expected non-blank argument name for message: inventory.deleted");
  }

  @Test
  public void reportInflectionBindingManifestRejectsBlankTermIdWithStableReason() {
    assertReportInflectionBindingManifestRejected(
        bindingManifestWithBlankTermId(),
        "Expected non-blank term id for message inventory.deleted argument item");
  }

  @Test
  public void reportInflectionBindingManifestRejectsDuplicateTermIdWithStableReason() {
    assertReportInflectionBindingManifestRejected(
        bindingManifestWithDuplicateTermId(),
        "Duplicate term id for message inventory.deleted argument item: item.water");
  }

  @Test
  public void renderInflectionBindingManifestReturnsRenderedMessages() {
    when(glossaryTermInflectionProfileService.compileProfilePack(1L, "fr"))
        .thenReturn(
            compiledProfilePack(
                "fr", "item.file", "\"count.one\":\"file\",\"count.other\":\"files\""));

    GlossaryWS.InflectionBindingManifestRenderResponse response =
        glossaryWS.renderInflectionBindingManifest(
            1L,
            "fr",
            new GlossaryWS.InflectionBindingManifestRenderRequest(
                """
                {
                  "schema": "mojito-mf2-inflection/message-term-binding-manifest/v0",
                  "locale": "fr",
                  "messages": {
                    "inventory.deleted": "Deleted {$count} {$item :term count=$count}."
                  },
                  "argumentTerms": {
                    "inventory.deleted": {
                      "item": ["item.file"]
                    }
                  }
                }
                """,
                Map.of("count", "2")));

    assertEquals("fr", response.locale());
    assertEquals("Deleted 2 files.", response.messages().get("inventory.deleted"));
    verify(glossaryTermInflectionProfileService).compileProfilePack(1L, "fr");
  }

  @Test
  public void renderInflectionBindingManifestPreflightsAllCountAlternatives() {
    when(glossaryTermInflectionProfileService.compileProfilePack(1L, "fr"))
        .thenReturn(compiledProfilePack("fr", "item.file", "\"count.one\":\"file\""));

    ResponseStatusException exception =
        assertThrows(
            ResponseStatusException.class,
            () ->
                glossaryWS.renderInflectionBindingManifest(
                    1L,
                    "fr",
                    new GlossaryWS.InflectionBindingManifestRenderRequest(
                        """
                        {
                          "schema": "mojito-mf2-inflection/message-term-binding-manifest/v0",
                          "locale": "fr",
                          "messages": {
                            "inventory.deleted": "Deleted {$count} {$item :term count=$count}."
                          },
                          "argumentTerms": {
                            "inventory.deleted": {
                              "item": ["item.file"]
                            }
                          }
                        }
                        """,
                        Map.of("count", "1"))));

    assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    assertTrue(
        exception
            .getReason()
            .contains("term argument item in message inventory.deleted bound to item.file"));
    assertTrue(exception.getReason().contains("Missing form count.other for term item.file"));
  }

  @Test
  public void renderInflectionBindingManifestRejectsUnknownTermIdsBeforeRendering() {
    when(glossaryTermInflectionProfileService.compileProfilePack(1L, "fr"))
        .thenReturn(compiledProfilePack("fr", "item.file", "\"bare.singular\":\"file\""));

    ResponseStatusException exception =
        assertThrows(
            ResponseStatusException.class,
            () ->
                glossaryWS.renderInflectionBindingManifest(
                    1L,
                    "fr",
                    new GlossaryWS.InflectionBindingManifestRenderRequest(
                        """
                        {
                          "schema": "mojito-mf2-inflection/message-term-binding-manifest/v0",
                          "locale": "fr",
                          "messages": {
                            "inventory.deleted": "Deleted {$item :term}."
                          },
                          "argumentTerms": {
                            "inventory.deleted": {
                              "item": ["item.missing"]
                            }
                          }
                        }
                        """,
                        Map.of())));

    assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    assertTrue(exception.getReason().contains("not renderable: 1 binding diagnostics"));
  }

  @Test
  public void renderInflectionBindingManifestExplainsUnsupportedCurrentV0RuntimeLocale() {
    when(glossaryTermInflectionProfileService.compileProfilePack(1L, "ja"))
        .thenReturn(compiledProfilePack("ja", "item.file", "\"bare.singular\":\"file\""));

    ResponseStatusException exception =
        assertThrows(
            ResponseStatusException.class,
            () ->
                glossaryWS.renderInflectionBindingManifest(
                    1L,
                    "ja",
                    new GlossaryWS.InflectionBindingManifestRenderRequest(
                        """
                        {
                          "schema": "mojito-mf2-inflection/message-term-binding-manifest/v0",
                          "locale": "ja",
                          "messages": {
                            "inventory.deleted": "Deleted {$item :term number=plural}."
                          },
                          "argumentTerms": {
                            "inventory.deleted": {
                              "item": ["item.file"]
                            }
                          }
                        }
                        """,
                        Map.of())));

    assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    assertTrue(exception.getReason().contains("not renderable: 1 binding diagnostics"));
    assertTrue(
        exception
            .getReason()
            .contains("inventory.deleted.item=unsupported-locale-runtime-term-inflection"));
    assertTrue(exception.getReason().contains("unsupported by current V0 locale runtime"));
  }

  @Test
  public void renderInflectionBindingManifestRejectsBlankMessageIdWithStableReason() {
    assertRenderInflectionBindingManifestRejected(
        bindingManifestWithBlankMessageId(), "Expected non-blank message id");
  }

  @Test
  public void renderInflectionBindingManifestRejectsBlankArgumentNameWithStableReason() {
    assertRenderInflectionBindingManifestRejected(
        bindingManifestWithBlankArgumentName(),
        "Expected non-blank argument name for message: inventory.deleted");
  }

  @Test
  public void renderInflectionBindingManifestRejectsBlankTermIdWithStableReason() {
    assertRenderInflectionBindingManifestRejected(
        bindingManifestWithBlankTermId(),
        "Expected non-blank term id for message inventory.deleted argument item");
  }

  @Test
  public void renderInflectionBindingManifestRejectsDuplicateTermIdWithStableReason() {
    assertRenderInflectionBindingManifestRejected(
        bindingManifestWithDuplicateTermId(),
        "Duplicate term id for message inventory.deleted argument item: item.water");
  }

  @Test
  public void renderInflectionBindingManifestUsesPathLocaleWhenManifestOmitsLocale() {
    when(glossaryTermInflectionProfileService.compileProfilePack(1L, "fr"))
        .thenReturn(compiledProfilePack("fr", "item.sword", "\"bare.singular\":\"épée\""));

    GlossaryWS.InflectionBindingManifestRenderResponse response =
        glossaryWS.renderInflectionBindingManifest(
            1L,
            "fr",
            new GlossaryWS.InflectionBindingManifestRenderRequest(
                """
                {
                  "schema": "mojito-mf2-inflection/message-term-binding-manifest/v0",
                  "messages": {
                    "inventory.found": "Trouvé {$item :term}."
                  },
                  "argumentTerms": {
                    "inventory.found": {
                      "item": ["item.sword"]
                    }
                  }
                }
                """,
                null));

    assertEquals("fr", response.locale());
    assertEquals("Trouvé épée.", response.messages().get("inventory.found"));
  }

  @Test
  public void renderInflectionBindingManifestRejectsInvalidRuntimeVariableName() {
    when(glossaryTermInflectionProfileService.compileProfilePack(1L, "fr"))
        .thenReturn(compiledProfilePack("fr", "item.file", "\"bare.singular\":\"file\""));

    ResponseStatusException exception =
        assertThrows(
            ResponseStatusException.class,
            () ->
                glossaryWS.renderInflectionBindingManifest(
                    1L,
                    "fr",
                    new GlossaryWS.InflectionBindingManifestRenderRequest(
                        """
                        {
                          "schema": "mojito-mf2-inflection/message-term-binding-manifest/v0",
                          "messages": {
                            "inventory.deleted": "Deleted {$item :term}."
                          },
                          "argumentTerms": {
                            "inventory.deleted": {
                              "item": ["item.file"]
                            }
                          }
                        }
                        """,
                        Map.of("1count", "2"))));

    assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    assertTrue(exception.getReason().contains("Invalid runtime variable name: 1count"));
  }

  @Test
  public void renderInflectionBindingManifestRejectsNullRuntimeVariableValues() {
    when(glossaryTermInflectionProfileService.compileProfilePack(1L, "fr"))
        .thenReturn(compiledProfilePack("fr", "item.file", "\"bare.singular\":\"file\""));
    Map<String, String> variables = new LinkedHashMap<>();
    variables.put("count", null);

    ResponseStatusException exception =
        assertThrows(
            ResponseStatusException.class,
            () ->
                glossaryWS.renderInflectionBindingManifest(
                    1L,
                    "fr",
                    new GlossaryWS.InflectionBindingManifestRenderRequest(
                        """
                        {
                          "schema": "mojito-mf2-inflection/message-term-binding-manifest/v0",
                          "messages": {
                            "inventory.deleted": "Deleted {$item :term}."
                          },
                          "argumentTerms": {
                            "inventory.deleted": {
                              "item": ["item.file"]
                            }
                          }
                        }
                        """,
                        variables)));

    assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    assertTrue(exception.getReason().contains("Runtime variable count must not be null"));
  }

  @Test
  public void exportInflectionProfilePackReturnsJsonAttachment() {
    when(glossaryTermInflectionProfileService.profilePack(1L, "fr"))
        .thenReturn(
            new TermInflectionProfilePackJsonLoader()
                .load(
                    """
                    {
                      "schema": "mojito-mf2-inflection/term-inflection-profile-pack/v0",
                      "locale": "fr",
                      "provenance": {"sourceLabels": [], "sources": []},
                      "profiles": [{
                        "termId": "item.iron_sword",
                        "source": "iron sword",
                        "status": "APPROVED",
                        "morphology": {"partOfSpeech": "noun"},
                        "forms": {"bare.singular": "epee de fer"},
                        "diagnostics": []
                      }]
                    }
                    """));

    ResponseEntity<byte[]> response = glossaryWS.exportInflectionProfilePack(1L, "fr");

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(
        "attachment; filename=\"glossary-1-inflection-fr.json\"",
        response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION));
    byte[] bodyBytes = response.getBody();
    Assert.assertNotNull(bodyBytes);
    String body = new String(bodyBytes, StandardCharsets.UTF_8);
    assertTrue(
        body.contains("\"schema\":\"mojito-mf2-inflection/term-inflection-profile-pack/v0\""));
    assertTrue(body.contains("\"termId\":\"item.iron_sword\""));
  }

  @Test
  public void exportInflectionProfilePackMapsValidationErrors() {
    when(glossaryTermInflectionProfileService.profilePack(1L, "fr"))
        .thenThrow(new IllegalArgumentException("Source-backed provenance requires license"));

    ResponseStatusException exception =
        assertThrows(
            ResponseStatusException.class, () -> glossaryWS.exportInflectionProfilePack(1L, "fr"));

    assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    assertEquals("Source-backed provenance requires license", exception.getReason());
  }

  @Test
  public void importInflectionProfilesMapsServiceResult() {
    when(glossaryTermInflectionProfileService.importProfilePack(1L, "{\"schema\":\"pack\"}"))
        .thenReturn(
            new GlossaryTermInflectionProfileService.InflectionProfileImportResult(
                "fr", 1, 1, 0, List.of(inflectionProfileView("APPROVED"))));

    GlossaryWS.ImportInflectionProfilesResponse response =
        glossaryWS.importInflectionProfiles(
            1L, new GlossaryWS.ImportInflectionProfilesRequest("{\"schema\":\"pack\"}"));

    assertEquals("fr", response.localeTag());
    assertEquals(1, response.profileCount());
    assertEquals(1, response.createdProfileCount());
    assertEquals(0, response.updatedProfileCount());
    assertEquals("item.iron_sword", response.profiles().getFirst().termId());
  }

  @Test
  public void importInflectionProfilesMapsValidationErrors() {
    when(glossaryTermInflectionProfileService.importProfilePack(1L, "{\"schema\":\"pack\"}"))
        .thenThrow(new IllegalArgumentException("Source-backed provenance requires generator"));

    ResponseStatusException exception =
        assertThrows(
            ResponseStatusException.class,
            () ->
                glossaryWS.importInflectionProfiles(
                    1L, new GlossaryWS.ImportInflectionProfilesRequest("{\"schema\":\"pack\"}")));

    assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    assertEquals("Source-backed provenance requires generator", exception.getReason());
  }

  private double matchDurationCount(String result, String scope) {
    return meterRegistry
        .find(GlossaryWS.MATCH_DURATION_METRIC)
        .tag("result", result)
        .tag("scope", scope)
        .timer()
        .count();
  }

  private GlossaryTermInflectionProfileService.InflectionProfileView inflectionProfileView(
      String status) {
    return inflectionProfileView(status, "[]");
  }

  private GlossaryTermInflectionProfileService.InflectionProfileView inflectionProfileView(
      String status, String diagnosticsJson) {
    return new GlossaryTermInflectionProfileService.InflectionProfileView(
        4L,
        null,
        null,
        3L,
        2L,
        "item.iron_sword",
        "iron sword",
        "fr",
        "mojito-mf2-inflection/term-inflection-profile-pack/v0",
        status,
        "{\"partOfSpeech\":\"noun\"}",
        "{\"bare.singular\":\"epee\"}",
        diagnosticsJson,
        "{\"source\":\"manual\"}");
  }

  private CompiledTermPack compiledProfilePack(String locale, String termId, String formsJson) {
    return new TermInflectionProfilePackJsonLoader()
        .load(
            """
            {
              "schema": "mojito-mf2-inflection/term-inflection-profile-pack/v0",
              "locale": "%s",
              "provenance": {"sourceLabels": [], "sources": []},
              "profiles": [{
                "termId": "%s",
                "source": "fixture term",
                "status": "APPROVED",
                "morphology": {"partOfSpeech": "noun"},
                "forms": {%s},
                "diagnostics": []
              }]
            }
            """
                .formatted(locale, termId, formsJson))
        .toCompiledTermPack();
  }

  private TermInflectionProfilePackJsonLoader.TermInflectionProfilePack profilePack(
      String locale, String termId) {
    return new TermInflectionProfilePackJsonLoader()
        .load(
            """
            {
              "schema": "mojito-mf2-inflection/term-inflection-profile-pack/v0",
              "locale": "%s",
              "provenance": {"sourceLabels": [], "sources": []},
              "profiles": [{
                "termId": "%s",
                "source": "fixture term",
                "status": "APPROVED",
                "morphology": {"partOfSpeech": "noun"},
                "forms": {"bare.singular": "fixture term"},
                "diagnostics": []
              }]
            }
            """
                .formatted(locale, termId));
  }

  private void assertReportInflectionBindingManifestRejected(String manifest, String reason) {
    when(glossaryTermInflectionProfileService.profilePack(1L, "fr"))
        .thenReturn(profilePack("fr", "item.water"));

    ResponseStatusException exception =
        assertThrows(
            ResponseStatusException.class,
            () ->
                glossaryWS.reportInflectionBindingManifest(
                    1L, "fr", new GlossaryWS.InflectionBindingManifestReportRequest(manifest)));

    assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    assertEquals(reason, exception.getReason());
  }

  private void assertRenderInflectionBindingManifestRejected(String manifest, String reason) {
    ResponseStatusException exception =
        assertThrows(
            ResponseStatusException.class,
            () ->
                glossaryWS.renderInflectionBindingManifest(
                    1L,
                    "fr",
                    new GlossaryWS.InflectionBindingManifestRenderRequest(manifest, Map.of())));

    assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    assertEquals(reason, exception.getReason());
  }

  private String bindingManifestWithBlankMessageId() {
    return """
        {
          "schema": "mojito-mf2-inflection/message-term-binding-manifest/v0",
          "locale": "fr",
          "messages": {
            "": "{$item :term article=definite}."
          },
          "argumentTerms": {
            "": {
              "item": ["item.water"]
            }
          }
        }
        """;
  }

  private String bindingManifestWithBlankArgumentName() {
    return """
        {
          "schema": "mojito-mf2-inflection/message-term-binding-manifest/v0",
          "locale": "fr",
          "messages": {
            "inventory.deleted": "{$item :term article=definite}."
          },
          "argumentTerms": {
            "inventory.deleted": {
              "": ["item.water"]
            }
          }
        }
        """;
  }

  private String bindingManifestWithBlankTermId() {
    return """
        {
          "schema": "mojito-mf2-inflection/message-term-binding-manifest/v0",
          "locale": "fr",
          "messages": {
            "inventory.deleted": "{$item :term article=definite}."
          },
          "argumentTerms": {
            "inventory.deleted": {
              "item": [" "]
            }
          }
        }
        """;
  }

  private String bindingManifestWithDuplicateTermId() {
    return """
        {
          "schema": "mojito-mf2-inflection/message-term-binding-manifest/v0",
          "locale": "fr",
          "messages": {
            "inventory.deleted": "{$item :term article=definite}."
          },
          "argumentTerms": {
            "inventory.deleted": {
              "item": ["item.water", "item.water"]
            }
          }
        }
        """;
  }
}
