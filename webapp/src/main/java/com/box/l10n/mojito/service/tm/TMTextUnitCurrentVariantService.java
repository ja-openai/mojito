package com.box.l10n.mojito.service.tm;

import com.box.l10n.mojito.entity.TMTextUnitCurrentVariant;
import com.box.l10n.mojito.entity.TMTextUnitVariant;
import java.util.LinkedHashSet;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class TMTextUnitCurrentVariantService {

  /** logger */
  static Logger logger = LoggerFactory.getLogger(TMTextUnitCurrentVariantService.class);

  @Autowired TMTextUnitCurrentVariantRepository tmTextUnitCurrentVariantRepository;

  @Autowired TMService tmService;

  /**
   * Removes a {@link TMTextUnitVariant} from being the current variant. In other words, removes the
   * translation.
   *
   * <p>We're updating the {@link TMTextUnitCurrentVariant#tmTextUnitVariant} with "null" value
   * instead of removing the full record to be able to very easily track translation deletion.
   *
   * <p>If the record was removed we'd have to look into envers table, which would be more
   * complicated. With this it is also easy to fetch delta of changes and apply them to a previous
   * state.
   *
   * @param textUnitId
   */
  public boolean removeCurrentVariant(Long tmTextUnitCurrentVariantId) {
    TMTextUnitCurrentVariant tmtucv =
        tmTextUnitCurrentVariantRepository.findById(tmTextUnitCurrentVariantId).orElse(null);

    if (tmtucv == null) {
      logger.debug("No current variant, do nothing");
      return false;
    } else {
      logger.debug(
          "Update tmTextUnitCurrentVariant with id: {} to remove the current variant (\"remove\" current translation)",
          tmtucv.getId());
      tmtucv.setTmTextUnitVariant(null);
      tmTextUnitCurrentVariantRepository.save(tmtucv);
      return true;
    }
  }

  @Transactional
  public int removeCurrentVariants(List<Long> tmTextUnitCurrentVariantIds) {
    if (tmTextUnitCurrentVariantIds == null || tmTextUnitCurrentVariantIds.isEmpty()) {
      return 0;
    }

    int removedCount = 0;
    for (Long tmTextUnitCurrentVariantId : new LinkedHashSet<>(tmTextUnitCurrentVariantIds)) {
      if (tmTextUnitCurrentVariantId != null && removeCurrentVariant(tmTextUnitCurrentVariantId)) {
        removedCount += 1;
      }
    }

    return removedCount;
  }

  @Transactional
  public int updateCurrentVariantStatuses(
      List<Long> tmTextUnitCurrentVariantIds,
      TMTextUnitVariant.Status status,
      boolean includedInLocalizedFile) {
    if (tmTextUnitCurrentVariantIds == null || tmTextUnitCurrentVariantIds.isEmpty()) {
      return 0;
    }

    int updatedCount = 0;
    for (Long tmTextUnitCurrentVariantId : new LinkedHashSet<>(tmTextUnitCurrentVariantIds)) {
      if (tmTextUnitCurrentVariantId == null) {
        continue;
      }

      TMTextUnitCurrentVariant currentVariant =
          tmTextUnitCurrentVariantRepository.findById(tmTextUnitCurrentVariantId).orElse(null);
      if (currentVariant == null || currentVariant.getTmTextUnitVariant() == null) {
        continue;
      }

      TMTextUnitVariant variant = currentVariant.getTmTextUnitVariant();
      AddTMTextUnitCurrentVariantResult result =
          tmService.addTMTextUnitCurrentVariantWithResult(
              currentVariant.getTmTextUnit().getId(),
              currentVariant.getLocale().getId(),
              variant.getContent(),
              variant.getComment(),
              status,
              includedInLocalizedFile,
              null);
      if (result.isTmTextUnitCurrentVariantUpdated()) {
        updatedCount += 1;
      }
    }

    return updatedCount;
  }
}
