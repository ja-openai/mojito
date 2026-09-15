package com.box.l10n.mojito.service.badtranslation;

import com.box.l10n.mojito.entity.Locale;
import com.box.l10n.mojito.entity.TMTextUnit;
import com.box.l10n.mojito.entity.TMTextUnitVariant;
import com.box.l10n.mojito.entity.TranslationIncident;
import com.box.l10n.mojito.service.agentreview.AgentReviewStateFingerprint;
import com.box.l10n.mojito.service.agentreview.AgentReviewStateService;
import com.box.l10n.mojito.service.tm.TMTextUnitCurrentVariantRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Shared incident identity for REST/MCP reports and routed agent findings. */
@Service
public class TranslationIncidentIntakeService {
  private final TranslationIncidentRepository incidents;
  private final AgentReviewStateService reviewedStates;
  private final TMTextUnitCurrentVariantRepository currentVariants;
  private final EntityManager entityManager;

  public TranslationIncidentIntakeService(
      TranslationIncidentRepository incidents,
      AgentReviewStateService reviewedStates,
      TMTextUnitCurrentVariantRepository currentVariants,
      EntityManager entityManager) {
    this.incidents = incidents;
    this.reviewedStates = reviewedStates;
    this.currentVariants = currentVariants;
    this.entityManager = entityManager;
  }

  /** Resolve the report against a locked current snapshot before it can become an incident. */
  @Transactional(propagation = Propagation.MANDATORY)
  public TranslationIncident create(TranslationIncident incident, String concernKey) {
    if (incident.getReviewTeamId() != null
        && entityManager.find(com.box.l10n.mojito.entity.Team.class, incident.getReviewTeamId())
            == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A valid teamId is required");
    }
    if (incident.getSelectedTmTextUnitId() != null) {
      TMTextUnit unit =
          entityManager.find(
              TMTextUnit.class, incident.getSelectedTmTextUnitId(), LockModeType.PESSIMISTIC_WRITE);
      if (unit == null)
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Reported string no longer exists");
      entityManager.refresh(unit, LockModeType.PESSIMISTIC_WRITE);
      var currentRow =
          currentVariants.findForUpdateByLocaleIdAndTmTextUnitId(
              incident.getResolvedLocaleId(), unit.getId());
      if (currentRow != null) entityManager.refresh(currentRow, LockModeType.PESSIMISTIC_WRITE);
      TMTextUnitVariant current = currentRow == null ? null : currentRow.getTmTextUnitVariant();
      if (current != null) entityManager.refresh(current, LockModeType.PESSIMISTIC_WRITE);
      // The lookup snapshot also contains reject/audit context. Do not silently mix it with a new
      // target.
      if (!Objects.equals(unit.getContent(), incident.getSelectedSource())
          || !Objects.equals(
              current == null ? null : current.getId(), incident.getSelectedTmTextUnitVariantId())
          || (current != null
              && (!Objects.equals(current.getContent(), incident.getSelectedTarget())
                  || !Objects.equals(
                      current.getStatus() == null ? null : current.getStatus().name(),
                      incident.getSelectedTranslationStatus())
                  || !Objects.equals(
                      current.isIncludedInLocalizedFile(),
                      incident.getSelectedIncludedInLocalizedFile())))) {
        throw new ResponseStatusException(
            HttpStatus.CONFLICT, "Translation changed during lookup; retry this report");
      }
      incident.setSelectedSourceComment(unit.getComment());
      if (current == null) {
        incident.setSelectedTarget(null);
        incident.setSelectedTranslationStatus(null);
        incident.setSelectedIncludedInLocalizedFile(null);
      }
      incident.setSelectedTmTextUnitCurrentVariantId(
          currentRow == null ? null : currentRow.getId());
      String state = state(incident);
      if (reviewedStates.isReviewed(incident.getReviewTeamId(), incident.getReviewType(), state)) {
        throw new ResponseStatusException(
            HttpStatus.CONFLICT,
            "This exact translation has already been reviewed by this team. Use Review again for an"
                + " explicit new round.");
      }
      incident.setIntakeFingerprint(
          AgentReviewStateFingerprint.finding(
              incident.getReviewTeamId(),
              incident.getReviewType(),
              state,
              concernKey,
              incident.getReason()));
    } else {
      // No string parent exists yet. Serialize unresolved reports for the resolved locale instead.
      entityManager.find(
          Locale.class, incident.getResolvedLocaleId(), LockModeType.PESSIMISTIC_WRITE);
      String unresolved =
          AgentReviewStateFingerprint.of(
              null,
              incident.getResolvedLocaleId(),
              incident.getStringId(),
              incident.getRepositoryName(),
              null,
              incident.getLookupCandidatesJson(),
              incident.getLookupResolutionStatus(),
              null);
      incident.setIntakeFingerprint(
          AgentReviewStateFingerprint.finding(
              incident.getReviewTeamId(),
              incident.getReviewType(),
              unresolved,
              concernKey,
              incident.getReason()));
    }
    return saveOrReuse(incident);
  }

  private TranslationIncident findLegacyPending(TranslationIncident incoming) {
    for (TranslationIncident candidate :
        incidents.findRecentOpenForString(
            incoming.getSelectedTmTextUnitId(),
            incoming.getResolvedLocaleId(),
            org.springframework.data.domain.PageRequest.of(0, 100))) {
      if (candidate.getIntakeFingerprint() != null) continue;
      Long teamId = candidate.getReviewTeamId();
      if (teamId == null && candidate.getReviewRunId() != null) {
        var owner =
            entityManager.find(
                com.box.l10n.mojito.entity.agentreview.AgentReviewRun.class,
                candidate.getReviewRunId());
        if (owner != null) teamId = owner.getTeamId();
      }
      String key =
          AgentReviewStateFingerprint.finding(
              teamId, candidate.getReviewType(), state(candidate), null, candidate.getReason());
      if (incoming.getIntakeFingerprint().equals(key)) {
        candidate.setIntakeFingerprint(key);
        incidents.flush();
        return candidate;
      }
    }
    return null;
  }

  /** Caller holds the string parent lock. The unique nullable key is a second database guard. */
  @Transactional(propagation = Propagation.MANDATORY)
  public TranslationIncident saveOrReuse(TranslationIncident incident) {
    if (incident.getActiveIntakeFingerprint() != null) {
      TranslationIncident existing =
          incidents
              .findByActiveIntakeFingerprint(incident.getActiveIntakeFingerprint())
              .orElse(null);
      if (existing != null) return existing;
      if (incident.getSelectedTmTextUnitId() != null) {
        TranslationIncident legacy = findLegacyPending(incident);
        if (legacy != null) return legacy;
      }
    }
    return incidents.saveAndFlush(incident);
  }

  public static String state(TranslationIncident incident) {
    return AgentReviewStateFingerprint.of(
        incident.getSelectedTmTextUnitId(),
        incident.getResolvedLocaleId(),
        incident.getSelectedSource(),
        incident.getSelectedSourceComment(),
        incident.getSelectedTmTextUnitVariantId(),
        incident.getSelectedTarget(),
        incident.getSelectedTranslationStatus(),
        incident.getSelectedIncludedInLocalizedFile());
  }
}
