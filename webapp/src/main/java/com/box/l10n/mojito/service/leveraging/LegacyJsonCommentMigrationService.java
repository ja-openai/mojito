package com.box.l10n.mojito.service.leveraging;

import com.box.l10n.mojito.entity.TMTextUnit;
import com.box.l10n.mojito.entity.TMTextUnitCurrentVariant;
import com.box.l10n.mojito.entity.TMTextUnitVariantComment;
import com.box.l10n.mojito.entity.TMTextUnitVariantLeveraging;
import com.box.l10n.mojito.entity.security.user.User;
import com.box.l10n.mojito.service.security.user.UserService;
import com.box.l10n.mojito.service.tm.AddTMTextUnitCurrentVariantResult;
import com.box.l10n.mojito.service.tm.TMService;
import com.box.l10n.mojito.service.tm.TMTextUnitCurrentVariantRepository;
import com.box.l10n.mojito.service.tm.TMTextUnitRepository;
import com.box.l10n.mojito.service.tm.TMTextUnitVariantCommentService;
import com.box.l10n.mojito.service.tm.search.StatusFilter;
import com.box.l10n.mojito.service.tm.search.TextUnitDTO;
import com.box.l10n.mojito.service.tm.search.TextUnitSearcher;
import com.box.l10n.mojito.service.tm.search.TextUnitSearcherParameters;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Safely copies legacy escaped-comment JSON translations to their corrected identities. */
@Service
public class LegacyJsonCommentMigrationService {

  private static final Logger logger =
      LoggerFactory.getLogger(LegacyJsonCommentMigrationService.class);
  private static final String LEVERAGING_TYPE = "Leverage with TmTextUnit";

  @Autowired TextUnitSearcher textUnitSearcher;

  @Autowired TMTextUnitRepository tmTextUnitRepository;

  @Autowired TMTextUnitCurrentVariantRepository tmTextUnitCurrentVariantRepository;

  @Autowired TMTextUnitVariantLeveragingRepository tmTextUnitVariantLeveragingRepository;

  @Autowired TMService tmService;

  @Autowired UserService userService;

  @Autowired TMTextUnitVariantCommentService tmTextUnitVariantCommentService;

  @Autowired TMTextUnitVariantLeveragingService tmTextUnitVariantLeveragingService;

  /**
   * Copies each current legacy translation while holding the corrected locale row lock. Existing
   * corrected translations are replaceable only when their current variant was itself leveraged
   * from this legacy text unit; a human edit creates a new variant without that provenance.
   */
  @Transactional
  public void migrate(Long destinationTmTextUnitId, Long legacyTmTextUnitId) {
    TMTextUnit destination =
        tmTextUnitRepository
            .findById(destinationTmTextUnitId)
            .orElseThrow(() -> new IllegalStateException("Missing corrected JSON text unit"));
    User leverageUser = userService.findOrCreateLeverageUser();

    for (TextUnitDTO source : translatedVariants(legacyTmTextUnitId)) {
      TMTextUnitCurrentVariant current =
          tmTextUnitCurrentVariantRepository.findForUpdateByLocaleIdAndTmTextUnitId(
              source.getLocaleId(), destinationTmTextUnitId);
      if (!isMissingOrLeveragedFromLegacy(current, legacyTmTextUnitId)) {
        logger.debug(
            "Preserve corrected JSON translation without matching legacy provenance, text unit: {}, locale: {}",
            destinationTmTextUnitId,
            source.getLocaleId());
        continue;
      }

      AddTMTextUnitCurrentVariantResult result =
          tmService.addTMTextUnitCurrentVariantWithResult(
              current,
              destination.getTm().getId(),
              destination.getAsset().getId(),
              destinationTmTextUnitId,
              source.getLocaleId(),
              source.getTarget(),
              source.getTargetComment(),
              source.getStatus(),
              source.isIncludedInLocalizedFile(),
              null,
              leverageUser);
      if (result.isTmTextUnitCurrentVariantUpdated()) {
        copyLeveragingMetadata(result.getTmTextUnitCurrentVariant(), source);
      }
    }
  }

  private List<TextUnitDTO> translatedVariants(Long legacyTmTextUnitId) {
    TextUnitSearcherParameters parameters = new TextUnitSearcherParameters();
    parameters.setTmTextUnitIds(legacyTmTextUnitId);
    parameters.setStatusFilter(StatusFilter.TRANSLATED);
    return textUnitSearcher.search(parameters);
  }

  private boolean isMissingOrLeveragedFromLegacy(
      TMTextUnitCurrentVariant current, Long legacyTmTextUnitId) {
    if (current == null) {
      return true;
    }
    if (current.getTmTextUnitVariant() == null) {
      return false;
    }
    TMTextUnitVariantLeveraging leveraging =
        tmTextUnitVariantLeveragingRepository.findByTmTextUnitVariant_Id(
            current.getTmTextUnitVariant().getId());
    return leveraging != null && legacyTmTextUnitId.equals(leveraging.getSourceTmTextUnitId());
  }

  private void copyLeveragingMetadata(TMTextUnitCurrentVariant destination, TextUnitDTO source) {
    Long destinationVariantId = destination.getTmTextUnitVariant().getId();
    tmTextUnitVariantCommentService.copyComments(
        source.getTmTextUnitVariantId(), destinationVariantId);
    tmTextUnitVariantCommentService.addComment(
        destination.getTmTextUnitVariant(),
        TMTextUnitVariantComment.Type.LEVERAGING,
        TMTextUnitVariantComment.Severity.INFO,
        LEVERAGING_TYPE
            + " - leveraging from tmTextUnitId: "
            + source.getTmTextUnitId()
            + ", tmTextUnitVariantId: "
            + source.getTmTextUnitVariantId()
            + ", unique match: true");
    tmTextUnitVariantLeveragingService.saveLeveraging(
        destination.getTmTextUnitVariant(),
        source.getTmTextUnitId(),
        source.getTmTextUnitVariantId(),
        LEVERAGING_TYPE,
        true);
  }
}
