package com.box.l10n.mojito.service.tm;

import com.box.l10n.mojito.entity.TMTextUnit;
import com.box.l10n.mojito.service.security.user.UserService;
import com.box.l10n.mojito.service.tm.search.TextUnitDTO;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Keeps an editor's exact current-target comparison and write under one parent/current lock. */
@Service
public class TMTextUnitSaveGuardService {
  private final UserService users;
  private final EntityManager entityManager;
  private final TMTextUnitCurrentVariantRepository currents;

  public TMTextUnitSaveGuardService(
      UserService users, EntityManager entityManager, TMTextUnitCurrentVariantRepository currents) {
    this.users = users;
    this.entityManager = entityManager;
    this.currents = currents;
  }

  // TextUnitWS supplies READ_COMMITTED, so a waiter sees the preceding writer's committed target.
  @Transactional(propagation = Propagation.MANDATORY)
  public TextUnitDTO save(
      Long unitId, Long localeId, Long expectedVariantId, Supplier<TextUnitDTO> write) {
    if (unitId == null
        || unitId <= 0
        || localeId == null
        || localeId <= 0
        || (expectedVariantId != null && expectedVariantId <= 0)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid translation baseline");
    }
    users.checkUserCanEditLocale(localeId);
    // A missing current row cannot be locked. All guarded first saves and generated candidates
    // therefore lock the text-unit parent first, including before checking an absent baseline.
    var unit = entityManager.find(TMTextUnit.class, unitId, LockModeType.PESSIMISTIC_WRITE);
    if (unit == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Text unit not found");
    }
    entityManager.refresh(unit, LockModeType.PESSIMISTIC_WRITE);
    var current = currents.findForUpdateByLocaleIdAndTmTextUnitId(localeId, unitId);
    if (current != null) entityManager.refresh(current, LockModeType.PESSIMISTIC_WRITE);
    Long currentVariantId =
        current == null || current.getTmTextUnitVariant() == null
            ? null
            : current.getTmTextUnitVariant().getId();
    // Ordinary editors can intentionally recreate a deleted translation. Deletion retains a
    // current row with a null variant, which is the same absent-target baseline shown in the UI.
    if (!Objects.equals(currentVariantId, expectedVariantId)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "The translation changed. Refresh it before saving your edit.");
    }
    return write.get();
  }
}
