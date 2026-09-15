package com.box.l10n.mojito.service.review.feedback;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.box.l10n.mojito.entity.*;
import com.box.l10n.mojito.entity.review.ReviewFeedbackEvent;
import com.box.l10n.mojito.entity.security.user.User;
import com.box.l10n.mojito.service.security.user.UserService;
import com.box.l10n.mojito.service.tm.*;
import com.box.l10n.mojito.service.tm.search.TextUnitDTO;
import com.box.l10n.mojito.test.ThreadBoundTransactionAdvice;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.server.ResponseStatusException;

public class WorkbenchReviewFeedbackServiceTest {
  private ThreadBoundTransactionAdvice transactionAdvice;
  private final UserService users = mock(UserService.class);
  private final TMTextUnitVariantRepository variants = mock(TMTextUnitVariantRepository.class);
  private final TMTextUnitCurrentVariantRepository currents =
      mock(TMTextUnitCurrentVariantRepository.class);
  private final ReviewFeedbackEventRepository events = mock(ReviewFeedbackEventRepository.class);
  private final ReviewFeedbackCaptureService capture = mock(ReviewFeedbackCaptureService.class);
  private final ObjectMapper mapper = new ObjectMapper();
  private final EntityManager entityManager = mock(EntityManager.class);
  private final WorkbenchReviewFeedbackService service =
      new WorkbenchReviewFeedbackService(
          users, variants, currents, events, capture, mapper, entityManager);
  private final String operation = UUID.randomUUID().toString();
  private final TMTextUnitVariant original = new TMTextUnitVariant();
  private final TMTextUnitCurrentVariant current = new TMTextUnitCurrentVariant();
  private final TMTextUnitVariant accepted = new TMTextUnitVariant();

  @Before
  public void setup() {
    var transactions = mock(PlatformTransactionManager.class);
    when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    transactionAdvice = new ThreadBoundTransactionAdvice(transactions);
    var unit = new TMTextUnit();
    unit.setId(20L);
    when(entityManager.find(TMTextUnit.class, 20L, LockModeType.PESSIMISTIC_WRITE))
        .thenReturn(unit);
    var locale = new Locale();
    locale.setId(30L);
    original.setId(40L);
    original.setTmTextUnit(unit);
    original.setLocale(locale);
    original.setContent("Original");
    current.setId(50L);
    current.setTmTextUnitVariant(original);
    when(variants.findById(40L)).thenReturn(Optional.of(original));
    accepted.setId(60L);
    accepted.setTmTextUnit(unit);
    accepted.setLocale(locale);
    accepted.setContent("Café");
    when(variants.findById(60L)).thenReturn(Optional.of(accepted));
    when(currents.findForUpdateByLocaleIdAndTmTextUnitId(30L, 20L)).thenReturn(current);
    var reviewer = new User();
    reviewer.setId(10L);
    when(users.getCurrentUser()).thenReturn(Optional.of(reviewer));
  }

  @After
  public void restoreTransactions() {
    transactionAdvice.close();
  }

  private TextUnitDTO request() {
    var request = new TextUnitDTO();
    request.setTmTextUnitId(20L);
    request.setLocaleId(30L);
    request.setTarget("Cafe\u0301");
    request.setTargetComment("Translator comment");
    request.setStatus(TMTextUnitVariant.Status.APPROVED);
    request.setIncludedInLocalizedFile(true);
    return request;
  }

  private TextUnitDTO saved() {
    var result = request();
    result.setTarget("Café");
    result.setTmTextUnitVariantId(60L);
    result.setTmTextUnitCurrentVariantId(50L);
    return result;
  }

  @Test
  public void exactBaselineUsesServerVariantAndPermission() {
    var baseline =
        new ReviewFeedbackCaptureService.Baseline(
            "Original", true, "AI_TRANSLATE", "model", "prompt", java.util.Map.of());
    when(capture.baseline(original)).thenReturn(baseline);
    assertSame(baseline, service.baseline(20L, 30L, 40L));
    verify(users).checkUserCanEditLocale(30L);
    assertEquals(
        HttpStatus.NOT_FOUND,
        assertThrows(ResponseStatusException.class, () -> service.baseline(21L, 30L, 40L))
            .getStatusCode());
    assertEquals(
        HttpStatus.NOT_FOUND,
        assertThrows(ResponseStatusException.class, () -> service.baseline(20L, 31L, 40L))
            .getStatusCode());
  }

  @Test
  public void unauthorizedBaselineAndSaveDoNotReadOrWrite() {
    doThrow(new AccessDeniedException("Forbidden")).when(users).checkUserCanEditLocale(30L);
    assertThrows(AccessDeniedException.class, () -> service.baseline(20L, 30L, 40L));
    Supplier<TextUnitDTO> write = mock(Supplier.class);
    assertThrows(
        AccessDeniedException.class, () -> service.save(request(), 40L, operation, null, write));
    verifyNoInteractions(entityManager, variants, currents, events, capture, write);
  }

  @Test
  public void changedOrMissingCurrentRejectsBeforeWriting() {
    Supplier<TextUnitDTO> write = mock(Supplier.class);
    assertEquals(
        HttpStatus.CONFLICT,
        assertThrows(
                ResponseStatusException.class,
                () -> service.save(request(), 41L, operation, null, write))
            .getStatusCode());
    when(currents.findForUpdateByLocaleIdAndTmTextUnitId(30L, 20L)).thenReturn(null);
    assertEquals(
        HttpStatus.CONFLICT,
        assertThrows(
                ResponseStatusException.class,
                () -> service.save(request(), 40L, operation, null, write))
            .getStatusCode());
    verifyNoInteractions(write, capture);
  }

  @Test
  public void metadataMustIdentifyExactVariantAndOperation() {
    Supplier<TextUnitDTO> write = mock(Supplier.class);
    assertEquals(
        HttpStatus.BAD_REQUEST,
        assertThrows(
                ResponseStatusException.class,
                () -> service.save(request(), null, operation, null, write))
            .getStatusCode());
    assertEquals(
        HttpStatus.BAD_REQUEST,
        assertThrows(
                ResponseStatusException.class,
                () -> service.save(request(), 40L, "nope", null, write))
            .getStatusCode());
    assertEquals(
        HttpStatus.BAD_REQUEST,
        assertThrows(
                ResponseStatusException.class,
                () -> service.save(request(), 40L, null, null, write))
            .getStatusCode());
    verifyNoInteractions(entityManager, variants, events, write, capture, currents);
  }

  @Test
  public void locksParentBeforeCurrentAndWriterToMatchReviewAndIncidentIntake() {
    Supplier<TextUnitDTO> write = mock(Supplier.class);
    when(write.get()).thenReturn(saved());

    service.save(request(), 40L, operation, null, write);

    var order = inOrder(users, entityManager, currents, events, write);
    order.verify(users).checkUserCanEditLocale(30L);
    order.verify(entityManager).find(TMTextUnit.class, 20L, LockModeType.PESSIMISTIC_WRITE);
    order.verify(entityManager).refresh(original.getTmTextUnit(), LockModeType.PESSIMISTIC_WRITE);
    order.verify(currents).findForUpdateByLocaleIdAndTmTextUnitId(30L, 20L);
    order.verify(entityManager).refresh(current, LockModeType.PESSIMISTIC_WRITE);
    order.verify(events).findByEventKey(anyString());
    order.verify(write).get();
  }

  @Test
  public void refreshedCurrentAfterWaitingRejectsTheEarlierVariant() {
    Supplier<TextUnitDTO> write = mock(Supplier.class);
    doAnswer(
            ignored -> {
              current.setTmTextUnitVariant(accepted);
              return null;
            })
        .when(entityManager)
        .refresh(current, LockModeType.PESSIMISTIC_WRITE);

    assertEquals(
        HttpStatus.CONFLICT,
        assertThrows(
                ResponseStatusException.class,
                () -> service.save(request(), 40L, operation, null, write))
            .getStatusCode());
    verifyNoInteractions(write, capture);
  }

  @Test
  public void writesRawEvidenceAfterTheNormalSaveAndPreservesVariantIdentity() {
    var request = request();
    var feedback = new ReviewerFeedback(ReviewerFeedback.Reason.GRAMMAR, "A reason", false, false);
    var saved =
        service.save(
            request,
            40L,
            operation,
            feedback,
            () -> {
              request.setTarget("Café");
              request.setTmTextUnitVariantId(60L);
              request.setTmTextUnitCurrentVariantId(50L);
              return request;
            });
    assertSame(request, saved);
    assertSame(
        original, current.getTmTextUnitVariant()); // The writer need not mutate this reference.
    verify(capture)
        .captureWorkbench(
            eq(original),
            eq(accepted),
            eq(50L),
            eq("Cafe\u0301"),
            eq(10L),
            eq(feedback),
            eq(operation),
            anyString(),
            anyString());
  }

  @Test
  public void failedTranslationSaveDoesNotCaptureFeedback() {
    assertThrows(
        IllegalStateException.class,
        () ->
            service.save(
                request(),
                40L,
                operation,
                null,
                () -> {
                  throw new IllegalStateException("Write failed");
                }));
    verifyNoInteractions(capture);
  }

  @Test
  public void captureFailureFailsAcceptanceInsteadOfSilentlyLosingFeedback() {
    doThrow(new IllegalStateException("Evidence failed"))
        .when(capture)
        .captureWorkbench(any(), any(), any(), any(), any(), any(), any(), any(), any());
    assertThrows(
        IllegalStateException.class,
        () -> service.save(request(), 40L, operation, null, this::saved));
  }

  @Test
  public void exactRetryReturnsNormalizedReceiptWithoutReapplyingOverLaterChanges()
      throws Exception {
    service.save(request(), 40L, operation, null, this::saved);
    var fingerprint = ArgumentCaptor.forClass(String.class);
    var eventKey = ArgumentCaptor.forClass(String.class);
    verify(capture)
        .captureWorkbench(
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            fingerprint.capture(),
            eventKey.capture());
    var payload = mapper.createObjectNode();
    payload.put("requestFingerprint", fingerprint.getValue());
    payload.put("finalStored", "Café");
    payload.put("acceptedVariantId", 60L);
    payload.put("currentVariantId", 50L);
    payload.put("acceptedStatus", "APPROVED");
    payload.put("includedInLocalizedFile", true);
    payload.putNull("targetComment");
    payload.putNull("savedBy");
    var receipt =
        new ReviewFeedbackEvent(
            eventKey.getValue(),
            null,
            20L,
            10L,
            "fr",
            "m",
            "p",
            "c",
            "k",
            "s",
            "b",
            "f",
            true,
            mapper.writeValueAsString(payload));
    when(events.findByEventKey(eventKey.getValue())).thenReturn(Optional.of(receipt));
    var later = new TMTextUnitVariant();
    later.setId(70L);
    current.setTmTextUnitVariant(later);
    Supplier<TextUnitDTO> write = mock(Supplier.class);
    var saved = service.save(request(), 40L, operation, null, write);
    assertEquals("Café", saved.getTarget());
    assertEquals(Long.valueOf(60L), saved.getTmTextUnitVariantId());
    assertSame(later, current.getTmTextUnitVariant());
    when(currents.findForUpdateByLocaleIdAndTmTextUnitId(30L, 20L)).thenReturn(null);
    assertEquals(
        Long.valueOf(60L),
        service.save(request(), 40L, operation, null, write).getTmTextUnitVariantId());
    verifyNoInteractions(write);
    var changed = request();
    changed.setTarget("Changed");
    assertEquals(
        HttpStatus.CONFLICT,
        assertThrows(
                ResponseStatusException.class,
                () -> service.save(changed, 40L, operation, null, write))
            .getStatusCode());
    var changedNote = new ReviewerFeedback(null, "New note", false, false);
    assertEquals(
        HttpStatus.CONFLICT,
        assertThrows(
                ResponseStatusException.class,
                () -> service.save(request(), 40L, operation, changedNote, write))
            .getStatusCode());
  }

  @Test
  public void writerMustReturnTheExactReviewedRowAndValidSavedVariant() {
    assertThrows(
        IllegalStateException.class,
        () -> service.save(request(), 40L, operation, null, this::request));
    var wrongRow = saved();
    wrongRow.setTmTextUnitCurrentVariantId(51L);
    assertThrows(
        IllegalStateException.class,
        () -> service.save(request(), 40L, operation, null, () -> wrongRow));
    var wrongLocale = new Locale();
    wrongLocale.setId(31L);
    accepted.setLocale(wrongLocale);
    assertEquals(
        HttpStatus.NOT_FOUND,
        assertThrows(
                ResponseStatusException.class,
                () -> service.save(request(), 40L, operation, null, this::saved))
            .getStatusCode());
    verifyNoInteractions(capture);
  }
}
