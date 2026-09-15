package com.box.l10n.mojito.service.review.feedback;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.box.l10n.mojito.entity.*;
import com.box.l10n.mojito.entity.review.*;
import com.box.l10n.mojito.service.agentreview.*;
import com.box.l10n.mojito.service.oaitranslate.*;
import com.box.l10n.mojito.test.ThreadBoundTransactionAdvice;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

public class ReviewFeedbackCaptureServiceTest {
  private ThreadBoundTransactionAdvice transactionAdvice;

  @Before
  public void setupTransactions() {
    // This mock-only fixture must not inherit a database manager from another Spring test.
    var transactions = mock(PlatformTransactionManager.class);
    when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    transactionAdvice = new ThreadBoundTransactionAdvice(transactions);
  }

  @After
  public void restoreTransactions() {
    transactionAdvice.close();
  }

  @Test
  public void capturesRawValuesExactAiProvenanceAndOptionalReason() throws Exception {
    var events = mock(ReviewFeedbackEventRepository.class);
    var attempts = mock(AiTranslateTextUnitAttemptRepository.class);
    var service =
        new ReviewFeedbackCaptureService(
            events,
            attempts,
            mock(AgentReviewProposalRepository.class),
            mock(AgentReviewRunRepository.class),
            new ObjectMapper());
    var row = mock(ReviewProjectTextUnit.class, RETURNS_DEEP_STUBS);
    when(row.getId()).thenReturn(10L);
    when(row.getTmTextUnit().getId()).thenReturn(20L);
    when(row.getTmTextUnit().getContent()).thenReturn("Enable");
    when(row.getTmTextUnit().getName()).thenReturn("enable");
    when(row.getReviewProject().getId()).thenReturn(30L);
    when(row.getReviewProject().getLocale().getId()).thenReturn(40L);
    when(row.getReviewProject().getLocale().getBcp47Tag()).thenReturn("fr-FR");
    var ai = new TMTextUnitVariant();
    ai.setId(1L);
    ai.setContent("Activer");
    var accepted = new TMTextUnitVariant();
    accepted.setId(2L);
    accepted.setContent("Autorisé");
    var attempt = new AiTranslateTextUnitAttempt();
    attempt.setId(11L);
    attempt.setModel("example-model");
    attempt.setPromptFingerprint("prompt-v1");
    when(attempts.findFirstByTmTextUnit_IdAndLocale_IdAndTmTextUnitVariant_IdAndStatusOrderByIdDesc(
            20L, 40L, 1L, "IMPORTED"))
        .thenReturn(Optional.of(attempt));
    var decision = new ReviewProjectTextUnitDecision();
    decision.setId(5L);
    decision.setVersion(2L);
    decision.setDecisionState(ReviewProjectTextUnitDecision.DecisionState.DECIDED);
    decision.setVariant(accepted);
    var feedback =
        new ReviewerFeedback(
            ReviewerFeedback.Reason.TERMINOLOGY, "Use permission wording.", true, true);
    service.capture(row, decision, ai, null, "Autorise\u0301", 99L, feedback, null);
    var captured = ArgumentCaptor.forClass(ReviewFeedbackEvent.class);
    verify(events).save(captured.capture());
    var event = captured.getValue();
    var json = new ObjectMapper().readTree(event.getPayload());
    assertEquals("Autorise\u0301", json.get("finalAcceptedRaw").asText());
    assertEquals("Autorisé", json.get("finalStored").asText());
    assertEquals("Activer", json.at("/baseline/target").asText());
    assertEquals("example-model", event.getModel());
    assertEquals("prompt-v1", event.getPromptVersion());
    assertEquals("TERMINOLOGY", json.at("/feedback/reason").asText());
    assertTrue(json.at("/baseline/provenance/glossaryVersion").isNull());
    assertEquals("AI_ASSISTED", json.get("action").asText());
    assertEquals("TERMINOLOGY", event.getCategory());
    assertEquals("REVIEWER", json.get("classificationSource").asText());
    assertEquals("UNKNOWN_MATERIAL_EDIT", json.at("/diff/category").asText());
    assertNotNull(event.getCreatedAt());
    reset(events);
    when(events.existsByEventKey(event.getEventKey())).thenReturn(true);
    service.capture(row, decision, ai, null, "Autorise\u0301", 99L, feedback, null);
    verify(events, never()).save(any());
    decision.setDecisionState(ReviewProjectTextUnitDecision.DecisionState.PENDING);
    reset(events);
    service.capture(row, decision, ai, null, "different", 99L, feedback, null);
    verifyNoInteractions(events);
  }

  @Test
  public void workbenchEvidenceReusesExactVariantProvenanceWithoutAProject() throws Exception {
    var events = mock(ReviewFeedbackEventRepository.class);
    var attempts = mock(AiTranslateTextUnitAttemptRepository.class);
    var service =
        new ReviewFeedbackCaptureService(
            events,
            attempts,
            mock(AgentReviewProposalRepository.class),
            mock(AgentReviewRunRepository.class),
            new ObjectMapper());
    var unit = mock(TMTextUnit.class, RETURNS_DEEP_STUBS);
    when(unit.getId()).thenReturn(20L);
    when(unit.getContent()).thenReturn("Enable");
    when(unit.getAsset().getRepository().getId()).thenReturn(70L);
    var locale = new Locale();
    locale.setId(30L);
    locale.setBcp47Tag("fr");
    var original = new TMTextUnitVariant();
    original.setId(40L);
    original.setTmTextUnit(unit);
    original.setLocale(locale);
    original.setContent("Activer");
    var accepted = new TMTextUnitVariant();
    accepted.setId(41L);
    accepted.setContent("Activé");
    accepted.setStatus(TMTextUnitVariant.Status.APPROVED);
    accepted.setIncludedInLocalizedFile(true);
    var attempt = new AiTranslateTextUnitAttempt();
    attempt.setId(60L);
    attempt.setModel("exact-model");
    when(attempts.findFirstByTmTextUnit_IdAndLocale_IdAndTmTextUnitVariant_IdAndStatusOrderByIdDesc(
            20L, 30L, 40L, "IMPORTED"))
        .thenReturn(Optional.of(attempt));
    var feedback =
        new ReviewerFeedback(ReviewerFeedback.Reason.GRAMMAR, "Use completed form", false, false);
    service.captureWorkbench(
        original,
        accepted,
        50L,
        "Active\u0301",
        99L,
        feedback,
        "operation",
        "fingerprint",
        "event-key");
    var event = ArgumentCaptor.forClass(ReviewFeedbackEvent.class);
    verify(events).save(event.capture());
    assertNull(event.getValue().getProjectId());
    assertTrue(event.getValue().getAiBaseline());
    var payload = new ObjectMapper().readTree(event.getValue().getPayload());
    assertEquals("WORKBENCH", payload.path("surface").asText());
    assertTrue(payload.path("reviewComplete").asBoolean());
    assertEquals(70L, payload.path("repositoryId").asLong());
    assertEquals(40L, payload.path("reviewedVariantId").asLong());
    assertEquals(41L, payload.path("acceptedVariantId").asLong());
    assertEquals("Active\u0301", payload.path("finalAcceptedRaw").asText());
    assertEquals("Activé", payload.path("finalStored").asText());
    assertEquals(60L, payload.at("/baseline/provenance/attemptId").asLong());
    assertEquals("fingerprint", payload.path("requestFingerprint").asText());
    assertEquals("Use completed form", payload.at("/feedback/note").asText());
    assertEquals("GRAMMAR", event.getValue().getCategory());
    for (var status :
        java.util.List.of(
            TMTextUnitVariant.Status.REVIEW_NEEDED, TMTextUnitVariant.Status.TRANSLATION_NEEDED)) {
      reset(events);
      accepted.setStatus(status);
      accepted.setIncludedInLocalizedFile(true);
      service.captureWorkbench(
          original,
          accepted,
          50L,
          "Activé",
          99L,
          feedback,
          "pending",
          "fingerprint",
          "pending-key");
      verify(events).save(event.capture());
      var pending = new ObjectMapper().readTree(event.getValue().getPayload());
      assertFalse(pending.path("reviewComplete").asBoolean());
      assertEquals("Use completed form", pending.at("/feedback/note").asText());
    }
    reset(events);
    accepted.setIncludedInLocalizedFile(false);
    service.captureWorkbench(
        original,
        accepted,
        50L,
        "Activé",
        99L,
        feedback,
        "rejected",
        "fingerprint",
        "rejected-key");
    verify(events).save(event.capture());
    assertTrue(
        new ObjectMapper()
            .readTree(event.getValue().getPayload())
            .path("reviewComplete")
            .asBoolean());
    assertEquals("MARKED_PROBLEMATIC", event.getValue().getCategory());
  }
}
