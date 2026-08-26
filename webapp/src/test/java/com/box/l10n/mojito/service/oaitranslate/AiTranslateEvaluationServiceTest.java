package com.box.l10n.mojito.service.oaitranslate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.service.oaitranslate.AiTranslateEvaluationService.EvaluationReport;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.Test;
import org.springframework.data.domain.Pageable;

public class AiTranslateEvaluationServiceTest {

  @Test
  public void reportUsesHumanDecisionAsReferenceAndGroupsPromptCohorts() {
    AiTranslateTextUnitAttemptRepository repository =
        mock(AiTranslateTextUnitAttemptRepository.class);
    ZonedDateTime reviewedAt = ZonedDateTime.parse("2026-08-26T10:15:30Z");
    when(repository.findEvaluationRows(isNull(), isNull(), isNull(), any(Pageable.class)))
        .thenReturn(
            List.of(
                row(1L, reviewedAt, "prompt-a", "Bonjour", "Bonjour"),
                row(2L, reviewedAt.plusMinutes(1), "prompt-a", "Salut", "Bonjour"),
                row(3L, reviewedAt.plusMinutes(2), "prompt-b", "Merci", "Merci"),
                row(3L, reviewedAt.plusMinutes(2), "prompt-b", "Merci", "Merci")));

    AiTranslateEvaluationService service = new AiTranslateEvaluationService(repository);

    EvaluationReport report = service.getReport(null, " ", null, 50);

    assertEquals(3, report.summary().reviewedCount());
    assertEquals(2, report.summary().exactAcceptedCount());
    assertEquals(1, report.summary().editedCount());
    assertEquals(2.0 / 3.0, report.summary().exactAcceptanceRate(), 0.0001);
    assertEquals(2, report.cohorts().size());
    assertEquals("prompt-a", report.cohorts().get(0).promptFingerprint());
    assertEquals(2, report.cohorts().get(0).summary().reviewedCount());
    assertTrue(report.examples().get(0).exactAccepted());
    assertFalse(report.examples().get(1).exactAccepted());
  }

  @Test
  public void normalizedEditDistanceIsBoundedAndSkipsPathologicalInputs() {
    assertEquals(0.0, AiTranslateEvaluationService.normalizedEditDistance("same", "same"), 0.0);
    assertEquals(
        1.0 / 3.0, AiTranslateEvaluationService.normalizedEditDistance("cat", "cut"), 0.0001);
    assertNull(
        AiTranslateEvaluationService.normalizedEditDistance("a".repeat(2000), "b".repeat(2000)));
  }

  @Test(expected = IllegalArgumentException.class)
  public void reportRejectsUnboundedLimit() {
    AiTranslateEvaluationService service =
        new AiTranslateEvaluationService(mock(AiTranslateTextUnitAttemptRepository.class));

    service.getReport(null, null, null, AiTranslateEvaluationService.MAX_LIMIT + 1);
  }

  @Test
  public void promptFingerprintChangesWithPromptContent() {
    String first = AiTranslateService.promptFingerprint("prompt one");
    String repeated = AiTranslateService.promptFingerprint("prompt one");
    String second = AiTranslateService.promptFingerprint("prompt two");

    assertEquals(64, first.length());
    assertEquals(first, repeated);
    assertFalse(first.equals(second));
    assertNull(AiTranslateService.promptFingerprint(null));
  }

  private AiTranslateEvaluationRow row(
      Long attemptId,
      ZonedDateTime reviewedAt,
      String promptFingerprint,
      String aiTarget,
      String acceptedTarget) {
    return new AiTranslateEvaluationRow(
        attemptId,
        100L + attemptId,
        reviewedAt,
        10L,
        20L,
        "repository",
        30L + attemptId,
        "text.unit." + attemptId,
        "Hello",
        "Greeting",
        "fr-FR",
        "example-model",
        promptFingerprint,
        "medium",
        "low",
        aiTarget,
        acceptedTarget,
        "Reviewer note");
  }
}
