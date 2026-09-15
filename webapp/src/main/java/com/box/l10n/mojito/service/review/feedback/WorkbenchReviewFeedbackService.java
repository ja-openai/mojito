package com.box.l10n.mojito.service.review.feedback;

import com.box.l10n.mojito.entity.TMTextUnit;
import com.box.l10n.mojito.entity.TMTextUnitVariant;
import com.box.l10n.mojito.service.security.user.UserService;
import com.box.l10n.mojito.service.tm.TMTextUnitCurrentVariantRepository;
import com.box.l10n.mojito.service.tm.TMTextUnitVariantRepository;
import com.box.l10n.mojito.service.tm.search.TextUnitDTO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Workbench evidence uses exact variant identities and the existing acceptance event store. */
@Service
public class WorkbenchReviewFeedbackService {
  private final UserService users;
  private final TMTextUnitVariantRepository variants;
  private final TMTextUnitCurrentVariantRepository currents;
  private final ReviewFeedbackEventRepository events;
  private final ReviewFeedbackCaptureService capture;
  private final ObjectMapper mapper;
  private final EntityManager entityManager;

  public WorkbenchReviewFeedbackService(
      UserService users,
      TMTextUnitVariantRepository variants,
      TMTextUnitCurrentVariantRepository currents,
      ReviewFeedbackEventRepository events,
      ReviewFeedbackCaptureService capture,
      ObjectMapper mapper,
      EntityManager entityManager) {
    this.users = users;
    this.variants = variants;
    this.currents = currents;
    this.events = events;
    this.capture = capture;
    this.mapper = mapper;
    this.entityManager = entityManager;
  }

  @Transactional(readOnly = true)
  public ReviewFeedbackCaptureService.Baseline baseline(
      Long unitId, Long localeId, Long variantId) {
    users.checkUserCanEditLocale(localeId);
    return capture.baseline(exactVariant(unitId, localeId, variantId));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public TextUnitDTO save(
      TextUnitDTO request,
      Long reviewedVariantId,
      String operationId,
      ReviewerFeedback feedback,
      Supplier<TextUnitDTO> saveTranslation) {
    users.checkUserCanEditLocale(request.getLocaleId());
    if (reviewedVariantId == null || reviewedVariantId <= 0 || operationId == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Review variant and operation ID are required");
    }
    try {
      if (!UUID.fromString(operationId).toString().equals(operationId))
        throw new IllegalArgumentException();
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid feedback operation ID");
    }
    Long reviewerId =
        users
            .getCurrentUser()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN))
            .getId();
    String rawFinal = request.getTarget();
    String fingerprint =
        ReviewFeedbackCaptureService.hash(
            json(
                Arrays.asList(
                    request.getTmTextUnitId(),
                    request.getLocaleId(),
                    reviewedVariantId,
                    rawFinal,
                    request.getTargetComment(),
                    request.getStatus(),
                    request.isIncludedInLocalizedFile(),
                    feedback)));
    String key = ReviewFeedbackCaptureService.hash("WORKBENCH:" + reviewerId + ":" + operationId);
    // Match review/intake lock order. Inserting a variant also locks its parent through the FK.
    var unit =
        entityManager.find(
            TMTextUnit.class, request.getTmTextUnitId(), LockModeType.PESSIMISTIC_WRITE);
    if (unit != null) entityManager.refresh(unit, LockModeType.PESSIMISTIC_WRITE);
    var current =
        currents.findForUpdateByLocaleIdAndTmTextUnitId(
            request.getLocaleId(), request.getTmTextUnitId());
    if (current != null) entityManager.refresh(current, LockModeType.PESSIMISTIC_WRITE);
    // Check the durable receipt before stale-state validation so an exact retry never re-applies
    // a translation over a later edit or fails merely because the current row was removed.
    var receipt = events.findByEventKey(key).orElse(null);
    if (receipt != null) {
      JsonNode payload = tree(receipt.getPayload());
      if (!fingerprint.equals(payload.path("requestFingerprint").asText())) {
        throw new ResponseStatusException(
            HttpStatus.CONFLICT, "This feedback operation was already used for another save");
      }
      return receipt(request, payload);
    }
    if (unit == null
        || current == null
        || current.getTmTextUnitVariant() == null
        || !Objects.equals(current.getTmTextUnitVariant().getId(), reviewedVariantId)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "The translation changed. Refresh it before saving your review.");
    }
    var reviewed =
        exactVariant(request.getTmTextUnitId(), request.getLocaleId(), reviewedVariantId);
    TextUnitDTO saved = saveTranslation.get();
    if (saved.getTmTextUnitVariantId() == null
        || !Objects.equals(saved.getTmTextUnitCurrentVariantId(), current.getId())) {
      throw new IllegalStateException(
          "The saved translation does not match the reviewed current row");
    }
    var accepted =
        exactVariant(
            request.getTmTextUnitId(), request.getLocaleId(), saved.getTmTextUnitVariantId());
    capture.captureWorkbench(
        reviewed,
        accepted,
        saved.getTmTextUnitCurrentVariantId(),
        rawFinal,
        reviewerId,
        feedback,
        operationId,
        fingerprint,
        key);
    return saved;
  }

  private TMTextUnitVariant exactVariant(Long unitId, Long localeId, Long variantId) {
    var variant =
        variants
            .findById(variantId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    if (!Objects.equals(variant.getTmTextUnit().getId(), unitId)
        || !Objects.equals(variant.getLocale().getId(), localeId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    return variant;
  }

  private TextUnitDTO receipt(TextUnitDTO request, JsonNode payload) {
    request.setTarget(
        payload.path("finalStored").isNull() ? null : payload.path("finalStored").asText());
    request.setTargetComment(
        payload.path("targetComment").isNull() ? null : payload.path("targetComment").asText());
    request.setTmTextUnitVariantId(payload.path("acceptedVariantId").asLong());
    request.setTmTextUnitCurrentVariantId(payload.path("currentVariantId").asLong());
    request.setStatus(TMTextUnitVariant.Status.valueOf(payload.path("acceptedStatus").asText()));
    request.setIncludedInLocalizedFile(payload.path("includedInLocalizedFile").asBoolean());
    request.setTranslationCreatedByUsername(
        payload.path("savedBy").isNull() ? null : payload.path("savedBy").asText());
    return request;
  }

  private String json(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("Could not serialize Workbench feedback", e);
    }
  }

  private JsonNode tree(String value) {
    try {
      return mapper.readTree(value);
    } catch (Exception e) {
      throw new IllegalStateException("Could not read Workbench feedback receipt", e);
    }
  }
}
