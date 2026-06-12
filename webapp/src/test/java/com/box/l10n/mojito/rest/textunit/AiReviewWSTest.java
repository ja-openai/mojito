package com.box.l10n.mojito.rest.textunit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.AiReviewProto;
import com.box.l10n.mojito.entity.TMTextUnitVariant;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.rest.textunit.AiReviewType.AiReviewTextUnitVariantOutput;
import com.box.l10n.mojito.service.oaireview.AiReviewService;
import com.box.l10n.mojito.service.tm.AiReviewProtoRepository;
import com.box.l10n.mojito.service.tm.TMTextUnitVariantRepository;
import com.box.l10n.mojito.service.tm.search.TextUnitDTO;
import com.box.l10n.mojito.service.tm.search.TextUnitSearcher;
import com.box.l10n.mojito.service.tm.search.TextUnitSearcherParameters;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class AiReviewWSTest {

  @Mock TextUnitSearcher textUnitSearcher;
  @Mock AiReviewService aiReviewService;
  @Mock AiReviewProtoRepository aiReviewProtoRepository;
  @Mock TMTextUnitVariantRepository tmTextUnitVariantRepository;

  private AiReviewWS aiReviewWS;
  private SimpleMeterRegistry meterRegistry;

  @Before
  public void setUp() {
    ObjectMapper objectMapper = new ObjectMapper();
    AiReviewService.configureObjectMapper(objectMapper);
    meterRegistry = new SimpleMeterRegistry();
    aiReviewWS =
        new AiReviewWS(
            textUnitSearcher,
            aiReviewService,
            aiReviewProtoRepository,
            tmTextUnitVariantRepository,
            objectMapper,
            meterRegistry);
  }

  @Test
  public void cacheOnlySingleTextUnitReviewReturnsCachedReviewAfterTextUnitLookup() {
    AiReviewProto aiReviewProto = new AiReviewProto();
    aiReviewProto.setJsonReview(
        """
        {
          "source": "Show mobile previews",
          "target": {
            "content": "Afficher les aperçus mobiles",
            "explanation": "Natural UI wording.",
            "confidenceLevel": 91
          }
        }
        """);
    when(aiReviewProtoRepository.findByTmTextUnitVariantIdAndRunName(
            31L, AiReviewWS.RUN_NAME_FOR_FRONTEND))
        .thenReturn(aiReviewProto);
    when(textUnitSearcher.search(any(TextUnitSearcherParameters.class)))
        .thenReturn(List.of(new TextUnitDTO()));

    AiReviewWS.ProtoAiReviewSingleTextUnitResponse response =
        aiReviewWS.getAiReviewForSingleTextUnit(
            new AiReviewWS.ProtoAiReviewSingleTextUnitRequest(31L), true);

    assertNull(response.textUnitDTO());
    assertEquals("Afficher les aperçus mobiles", response.aiReviewOutput().target().content());
    assertEquals(1.0, precomputedLookupCount("cache_only", "hit"), 0.0);
    verify(textUnitSearcher).search(any(TextUnitSearcherParameters.class));
    verify(aiReviewService, never()).getAiReviewSingleTextUnit(org.mockito.ArgumentMatchers.any());
  }

  @Test
  public void cacheOnlySingleTextUnitReviewReturnsNullWithoutSearchingWhenMissing() {
    when(aiReviewProtoRepository.findByTmTextUnitVariantIdAndRunName(
            31L, AiReviewWS.RUN_NAME_FOR_FRONTEND))
        .thenReturn(null);

    AiReviewWS.ProtoAiReviewSingleTextUnitResponse response =
        aiReviewWS.getAiReviewForSingleTextUnit(
            new AiReviewWS.ProtoAiReviewSingleTextUnitRequest(31L), true);

    assertNull(response.textUnitDTO());
    assertNull(response.aiReviewOutput());
    assertEquals(1.0, precomputedLookupCount("cache_only", "miss"), 0.0);
    verify(textUnitSearcher, never()).search(any(TextUnitSearcherParameters.class));
    verify(aiReviewService, never()).getAiReviewSingleTextUnit(any());
  }

  @Test
  public void cacheOnlySingleTextUnitReviewReturnsNullWithoutSearchingWhenUnreadable() {
    AiReviewProto aiReviewProto = new AiReviewProto();
    aiReviewProto.setJsonReview("{not-json");
    when(aiReviewProtoRepository.findByTmTextUnitVariantIdAndRunName(
            31L, AiReviewWS.RUN_NAME_FOR_FRONTEND))
        .thenReturn(aiReviewProto);

    AiReviewWS.ProtoAiReviewSingleTextUnitResponse response =
        aiReviewWS.getAiReviewForSingleTextUnit(
            new AiReviewWS.ProtoAiReviewSingleTextUnitRequest(31L), true);

    assertNull(response.textUnitDTO());
    assertNull(response.aiReviewOutput());
    assertEquals(1.0, precomputedLookupCount("cache_only", "unreadable"), 0.0);
    verify(textUnitSearcher, never()).search(any(TextUnitSearcherParameters.class));
    verify(aiReviewService, never()).getAiReviewSingleTextUnit(any());
  }

  @Test
  public void cacheOnlySingleTextUnitReviewReturnsNullWhenCachedReviewIsStale() {
    AiReviewProto aiReviewProto = new AiReviewProto();
    aiReviewProto.setJsonReview(
        """
        {
          "source": "Show mobile previews",
          "target": {
            "content": "Afficher les aperçus mobiles",
            "explanation": "Natural UI wording.",
            "confidenceLevel": 91
          }
        }
        """);
    when(aiReviewProtoRepository.findByTmTextUnitVariantIdAndRunName(
            31L, AiReviewWS.RUN_NAME_FOR_FRONTEND))
        .thenReturn(aiReviewProto);
    when(textUnitSearcher.search(any(TextUnitSearcherParameters.class))).thenReturn(List.of());

    AiReviewWS.ProtoAiReviewSingleTextUnitResponse response =
        aiReviewWS.getAiReviewForSingleTextUnit(
            new AiReviewWS.ProtoAiReviewSingleTextUnitRequest(31L), true);

    assertNull(response.textUnitDTO());
    assertNull(response.aiReviewOutput());
    assertEquals(1.0, precomputedLookupCount("cache_only", "stale"), 0.0);
    verify(textUnitSearcher).search(any(TextUnitSearcherParameters.class));
    verify(aiReviewService, never()).getAiReviewSingleTextUnit(any());
  }

  @Test
  public void cacheOnlySingleTextUnitReviewReturnsNullWithoutSearchingWhenCachedReviewIsEmpty() {
    AiReviewProto aiReviewProto = new AiReviewProto();
    aiReviewProto.setJsonReview("{}");
    when(aiReviewProtoRepository.findByTmTextUnitVariantIdAndRunName(
            31L, AiReviewWS.RUN_NAME_FOR_FRONTEND))
        .thenReturn(aiReviewProto);

    AiReviewWS.ProtoAiReviewSingleTextUnitResponse response =
        aiReviewWS.getAiReviewForSingleTextUnit(
            new AiReviewWS.ProtoAiReviewSingleTextUnitRequest(31L), true);

    assertNull(response.textUnitDTO());
    assertNull(response.aiReviewOutput());
    assertEquals(1.0, precomputedLookupCount("cache_only", "empty"), 0.0);
    verify(textUnitSearcher, never()).search(any(TextUnitSearcherParameters.class));
    verify(aiReviewService, never()).getAiReviewSingleTextUnit(any());
  }

  @Test
  public void cacheOnlySingleTextUnitReviewReturnsNullWhenCachedRatingIsMissingExplanation() {
    AiReviewProto aiReviewProto = new AiReviewProto();
    aiReviewProto.setJsonReview(
        """
        {
          "existingTargetRating": {
            "score": 1
          }
        }
        """);
    when(aiReviewProtoRepository.findByTmTextUnitVariantIdAndRunName(
            31L, AiReviewWS.RUN_NAME_FOR_FRONTEND))
        .thenReturn(aiReviewProto);

    AiReviewWS.ProtoAiReviewSingleTextUnitResponse response =
        aiReviewWS.getAiReviewForSingleTextUnit(
            new AiReviewWS.ProtoAiReviewSingleTextUnitRequest(31L), true);

    assertNull(response.textUnitDTO());
    assertNull(response.aiReviewOutput());
    assertEquals(1.0, precomputedLookupCount("cache_only", "empty"), 0.0);
    verify(textUnitSearcher, never()).search(any(TextUnitSearcherParameters.class));
    verify(aiReviewService, never()).getAiReviewSingleTextUnit(any());
  }

  @Test
  public void cacheOnlySingleTextUnitReviewReturnsNullWhenCachedRatingIsMissingScore() {
    AiReviewProto aiReviewProto = new AiReviewProto();
    aiReviewProto.setJsonReview(
        """
        {
          "existingTargetRating": {
            "explanation": "Rating without score should not be used."
          }
        }
        """);
    when(aiReviewProtoRepository.findByTmTextUnitVariantIdAndRunName(
            31L, AiReviewWS.RUN_NAME_FOR_FRONTEND))
        .thenReturn(aiReviewProto);

    AiReviewWS.ProtoAiReviewSingleTextUnitResponse response =
        aiReviewWS.getAiReviewForSingleTextUnit(
            new AiReviewWS.ProtoAiReviewSingleTextUnitRequest(31L), true);

    assertNull(response.textUnitDTO());
    assertNull(response.aiReviewOutput());
    assertEquals(1.0, precomputedLookupCount("cache_only", "empty"), 0.0);
    verify(textUnitSearcher, never()).search(any(TextUnitSearcherParameters.class));
    verify(aiReviewService, never()).getAiReviewSingleTextUnit(any());
  }

  @Test
  public void cacheOnlySingleTextUnitReviewReturnsNullWhenCachedRatingScoreIsOutOfRange() {
    AiReviewProto aiReviewProto = new AiReviewProto();
    aiReviewProto.setJsonReview(
        """
        {
          "existingTargetRating": {
            "score": 82,
            "explanation": "Out-of-range score should not be used."
          }
        }
        """);
    when(aiReviewProtoRepository.findByTmTextUnitVariantIdAndRunName(
            31L, AiReviewWS.RUN_NAME_FOR_FRONTEND))
        .thenReturn(aiReviewProto);

    AiReviewWS.ProtoAiReviewSingleTextUnitResponse response =
        aiReviewWS.getAiReviewForSingleTextUnit(
            new AiReviewWS.ProtoAiReviewSingleTextUnitRequest(31L), true);

    assertNull(response.textUnitDTO());
    assertNull(response.aiReviewOutput());
    assertEquals(1.0, precomputedLookupCount("cache_only", "empty"), 0.0);
    verify(textUnitSearcher, never()).search(any(TextUnitSearcherParameters.class));
    verify(aiReviewService, never()).getAiReviewSingleTextUnit(any());
  }

  @Test
  public void cacheOnlySingleTextUnitReviewKeepsReviewRequiredFlagWithoutReason() {
    AiReviewProto aiReviewProto = new AiReviewProto();
    aiReviewProto.setJsonReview(
        """
        {
          "reviewRequired": {
            "required": true
          }
        }
        """);
    when(aiReviewProtoRepository.findByTmTextUnitVariantIdAndRunName(
            31L, AiReviewWS.RUN_NAME_FOR_FRONTEND))
        .thenReturn(aiReviewProto);
    when(textUnitSearcher.search(any(TextUnitSearcherParameters.class)))
        .thenReturn(List.of(new TextUnitDTO()));

    AiReviewWS.ProtoAiReviewSingleTextUnitResponse response =
        aiReviewWS.getAiReviewForSingleTextUnit(
            new AiReviewWS.ProtoAiReviewSingleTextUnitRequest(31L), true);

    assertNull(response.textUnitDTO());
    assertEquals(true, response.aiReviewOutput().reviewRequired().required());
    assertEquals(1.0, precomputedLookupCount("cache_only", "hit"), 0.0);
    verify(textUnitSearcher).search(any(TextUnitSearcherParameters.class));
    verify(aiReviewService, never()).getAiReviewSingleTextUnit(any());
  }

  @Test
  public void liveSingleTextUnitReviewReturnsCachedReviewWithoutRecomputing() {
    AiReviewProto aiReviewProto = new AiReviewProto();
    aiReviewProto.setJsonReview(
        """
        {
          "source": "Show mobile previews",
          "target": {
            "content": "Afficher les aperçus mobiles",
            "explanation": "Natural UI wording.",
            "confidenceLevel": 91
          }
        }
        """);
    when(aiReviewProtoRepository.findByTmTextUnitVariantIdAndRunName(
            31L, AiReviewWS.RUN_NAME_FOR_FRONTEND))
        .thenReturn(aiReviewProto);
    TextUnitDTO textUnit = new TextUnitDTO();
    when(textUnitSearcher.search(any(TextUnitSearcherParameters.class)))
        .thenReturn(List.of(textUnit));

    AiReviewWS.ProtoAiReviewSingleTextUnitResponse response =
        aiReviewWS.getAiReviewForSingleTextUnit(
            new AiReviewWS.ProtoAiReviewSingleTextUnitRequest(31L), false);

    assertSame(textUnit, response.textUnitDTO());
    assertEquals("Afficher les aperçus mobiles", response.aiReviewOutput().target().content());
    assertEquals(1.0, precomputedLookupCount("live_or_compute", "hit"), 0.0);
    verify(textUnitSearcher).search(any(TextUnitSearcherParameters.class));
    verify(aiReviewService, never()).getAiReviewSingleTextUnit(any());
    verify(aiReviewProtoRepository, never()).save(any(AiReviewProto.class));
  }

  @Test
  public void liveSingleTextUnitReviewDoesNotRecomputeWhenCachedReviewIsStale() {
    AiReviewProto aiReviewProto = new AiReviewProto();
    aiReviewProto.setJsonReview(
        """
        {
          "source": "Show mobile previews",
          "target": {
            "content": "Afficher les aperçus mobiles",
            "explanation": "Natural UI wording.",
            "confidenceLevel": 91
          }
        }
        """);
    when(aiReviewProtoRepository.findByTmTextUnitVariantIdAndRunName(
            31L, AiReviewWS.RUN_NAME_FOR_FRONTEND))
        .thenReturn(aiReviewProto);
    when(textUnitSearcher.search(any(TextUnitSearcherParameters.class))).thenReturn(List.of());

    RuntimeException exception =
        assertThrows(
            RuntimeException.class,
            () ->
                aiReviewWS.getAiReviewForSingleTextUnit(
                    new AiReviewWS.ProtoAiReviewSingleTextUnitRequest(31L), false));

    assertEquals("Wrong tmTextUnitVariantId", exception.getMessage());
    assertEquals(1.0, precomputedLookupCount("live_or_compute", "stale"), 0.0);
    verify(textUnitSearcher).search(any(TextUnitSearcherParameters.class));
    verify(aiReviewService, never()).getAiReviewSingleTextUnit(any());
    verify(aiReviewProtoRepository, never()).save(any(AiReviewProto.class));
  }

  @Test
  public void liveSingleTextUnitReviewRecomputesWhenCachedReviewIsEmpty() {
    AiReviewProto aiReviewProto = new AiReviewProto();
    aiReviewProto.setJsonReview("{}");
    when(aiReviewProtoRepository.findByTmTextUnitVariantIdAndRunName(
            31L, AiReviewWS.RUN_NAME_FOR_FRONTEND))
        .thenReturn(aiReviewProto);
    TextUnitDTO textUnit = new TextUnitDTO();
    textUnit.setTargetLocale("fr-FR");
    textUnit.setSource("Show mobile previews");
    textUnit.setComment("Button label");
    textUnit.setTarget("Afficher les aperçus mobiles");
    textUnit.setIncludedInLocalizedFile(true);
    when(textUnitSearcher.search(any(TextUnitSearcherParameters.class)))
        .thenReturn(List.of(textUnit));
    AiReviewTextUnitVariantOutput reviewOutput =
        new AiReviewTextUnitVariantOutput(
            "Show mobile previews",
            new AiReviewTextUnitVariantOutput.Target(
                "Afficher les aperçus mobiles", "Natural UI wording.", 91),
            null,
            null,
            null,
            null);
    when(aiReviewService.getAiReviewSingleTextUnit(any())).thenReturn(reviewOutput);

    AiReviewWS.ProtoAiReviewSingleTextUnitResponse response =
        aiReviewWS.getAiReviewForSingleTextUnit(
            new AiReviewWS.ProtoAiReviewSingleTextUnitRequest(31L), false);

    assertSame(textUnit, response.textUnitDTO());
    assertSame(reviewOutput, response.aiReviewOutput());
    assertEquals(1.0, precomputedLookupCount("live_or_compute", "empty"), 0.0);
    verify(textUnitSearcher).search(any(TextUnitSearcherParameters.class));
    verify(aiReviewService).getAiReviewSingleTextUnit(any());
    ArgumentCaptor<AiReviewProto> savedReview = ArgumentCaptor.forClass(AiReviewProto.class);
    verify(aiReviewProtoRepository).save(savedReview.capture());
    assertSame(aiReviewProto, savedReview.getValue());
    verify(tmTextUnitVariantRepository, never()).getReferenceById(any());
  }

  @Test
  public void liveSingleTextUnitReviewRecomputesWhenCachedReviewIsUnreadable() {
    AiReviewProto aiReviewProto = new AiReviewProto();
    aiReviewProto.setJsonReview("{not-json");
    when(aiReviewProtoRepository.findByTmTextUnitVariantIdAndRunName(
            31L, AiReviewWS.RUN_NAME_FOR_FRONTEND))
        .thenReturn(aiReviewProto);
    TextUnitDTO textUnit = new TextUnitDTO();
    textUnit.setTargetLocale("fr-FR");
    textUnit.setSource("Show mobile previews");
    textUnit.setComment("Button label");
    textUnit.setTarget("Afficher les aperçus mobiles");
    textUnit.setIncludedInLocalizedFile(true);
    when(textUnitSearcher.search(any(TextUnitSearcherParameters.class)))
        .thenReturn(List.of(textUnit));
    AiReviewTextUnitVariantOutput reviewOutput =
        new AiReviewTextUnitVariantOutput(
            "Show mobile previews",
            new AiReviewTextUnitVariantOutput.Target(
                "Afficher les aperçus mobiles", "Natural UI wording.", 91),
            null,
            null,
            null,
            null);
    when(aiReviewService.getAiReviewSingleTextUnit(any())).thenReturn(reviewOutput);

    AiReviewWS.ProtoAiReviewSingleTextUnitResponse response =
        aiReviewWS.getAiReviewForSingleTextUnit(
            new AiReviewWS.ProtoAiReviewSingleTextUnitRequest(31L), false);

    assertSame(textUnit, response.textUnitDTO());
    assertSame(reviewOutput, response.aiReviewOutput());
    assertEquals(1.0, precomputedLookupCount("live_or_compute", "unreadable"), 0.0);
    verify(textUnitSearcher).search(any(TextUnitSearcherParameters.class));
    verify(aiReviewService).getAiReviewSingleTextUnit(any());
    ArgumentCaptor<AiReviewProto> savedReview = ArgumentCaptor.forClass(AiReviewProto.class);
    verify(aiReviewProtoRepository).save(savedReview.capture());
    assertSame(aiReviewProto, savedReview.getValue());
    verify(tmTextUnitVariantRepository, never()).getReferenceById(any());
  }

  @Test
  public void liveSingleTextUnitReviewComputesAndStoresReviewOnCacheMiss() {
    when(aiReviewProtoRepository.findByTmTextUnitVariantIdAndRunName(
            31L, AiReviewWS.RUN_NAME_FOR_FRONTEND))
        .thenReturn(null);
    TextUnitDTO textUnit = new TextUnitDTO();
    textUnit.setTargetLocale("fr-FR");
    textUnit.setSource("Show mobile previews");
    textUnit.setComment("Button label");
    textUnit.setTarget("Afficher les aperçus mobiles");
    textUnit.setIncludedInLocalizedFile(true);
    when(textUnitSearcher.search(any(TextUnitSearcherParameters.class)))
        .thenReturn(List.of(textUnit));
    AiReviewTextUnitVariantOutput reviewOutput =
        new AiReviewTextUnitVariantOutput(
            "Show mobile previews",
            new AiReviewTextUnitVariantOutput.Target(
                "Afficher les aperçus mobiles", "Natural UI wording.", 91),
            null,
            null,
            new AiReviewTextUnitVariantOutput.ExistingTargetRating("Good translation.", 2),
            null);
    when(aiReviewService.getAiReviewSingleTextUnit(any())).thenReturn(reviewOutput);
    TMTextUnitVariant variant = new TMTextUnitVariant();
    when(tmTextUnitVariantRepository.getReferenceById(31L)).thenReturn(variant);

    AiReviewWS.ProtoAiReviewSingleTextUnitResponse response =
        aiReviewWS.getAiReviewForSingleTextUnit(
            new AiReviewWS.ProtoAiReviewSingleTextUnitRequest(31L), false);

    assertSame(textUnit, response.textUnitDTO());
    assertSame(reviewOutput, response.aiReviewOutput());
    assertEquals(1.0, precomputedLookupCount("live_or_compute", "miss"), 0.0);
    verify(textUnitSearcher).search(any(TextUnitSearcherParameters.class));
    verify(aiReviewService).getAiReviewSingleTextUnit(any());
    ArgumentCaptor<AiReviewProto> savedReview = ArgumentCaptor.forClass(AiReviewProto.class);
    verify(aiReviewProtoRepository).save(savedReview.capture());
    assertSame(variant, savedReview.getValue().getTmTextUnitVariant());
    assertEquals(AiReviewWS.RUN_NAME_FOR_FRONTEND, savedReview.getValue().getRunName());
  }

  private double precomputedLookupCount(String requestMode, String result) {
    return meterRegistry
        .find(AiReviewWS.PRECOMPUTED_REVIEW_LOOKUP_METRIC)
        .tag("requestMode", requestMode)
        .tag("result", result)
        .counter()
        .count();
  }
}
