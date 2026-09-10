package com.box.l10n.mojito.service.tm;

import static org.slf4j.LoggerFactory.getLogger;

import com.box.l10n.mojito.entity.Asset;
import com.box.l10n.mojito.entity.TMTextUnit;
import com.box.l10n.mojito.service.asset.AssetRepository;
import com.box.l10n.mojito.service.assetintegritychecker.integritychecker.IntegrityCheckException;
import com.box.l10n.mojito.service.assetintegritychecker.integritychecker.IntegrityCheckerFactory;
import com.box.l10n.mojito.service.assetintegritychecker.integritychecker.PluralIntegrityCheckerRelaxer;
import com.box.l10n.mojito.service.assetintegritychecker.integritychecker.TextUnitIntegrityChecker;
import com.box.l10n.mojito.service.locale.LocaleRepository;
import java.util.Set;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * @author wyau
 */
@Component
@Transactional(readOnly = true)
public class TMTextUnitIntegrityCheckService {
  /** logger */
  static Logger logger = getLogger(TMTextUnitIntegrityCheckService.class);

  @Autowired IntegrityCheckerFactory integrityCheckerFactory;

  @Autowired AssetRepository assetRepository;

  @Autowired LocaleRepository localeRepository;

  @Autowired TMTextUnitRepository tmTextUnitRepository;

  @Autowired PluralIntegrityCheckerRelaxer pluralIntegrityCheckerRelaxer;

  /**
   * Checks the integrity of the content given the {@link com.box.l10n.mojito.entity.TMTextUnit#id}
   *
   * @throws IntegrityCheckException
   */
  public void checkTMTextUnitIntegrity(Long tmTextUnitId, String contentToCheck)
      throws IntegrityCheckException {
    checkTMTextUnitIntegrityForLocale(tmTextUnitId, contentToCheck, null);
  }

  /** Resolves target locale from the same persisted locale id used by the mutation. */
  public void checkTMTextUnitIntegrity(Long tmTextUnitId, String contentToCheck, Long localeId)
      throws IntegrityCheckException {
    String targetLocale =
        localeId == null
            ? null
            : localeRepository
                .findById(localeId)
                .orElseThrow(
                    () -> new IllegalArgumentException("Unknown target locale: " + localeId))
                .getBcp47Tag();
    checkTMTextUnitIntegrityForLocale(tmTextUnitId, contentToCheck, targetLocale);
  }

  /** For callers that already obtained the target locale from their authoritative context. */
  public void checkTMTextUnitIntegrityForLocale(
      Long tmTextUnitId, String contentToCheck, String targetLocale)
      throws IntegrityCheckException {
    logger.debug("Checking Integrity of the TMTextUnit");

    TMTextUnit tmTextUnit = tmTextUnitRepository.findById(tmTextUnitId).orElse(null);
    Asset asset = tmTextUnit.getAsset();

    Set<TextUnitIntegrityChecker> textUnitCheckers =
        integrityCheckerFactory.getTextUnitCheckers(asset);

    if (textUnitCheckers.isEmpty()) {
      logger.debug("No designated checker for this asset.  Nothing to do");
    } else {
      for (TextUnitIntegrityChecker textUnitChecker : textUnitCheckers) {
        try {
          if (targetLocale == null) {
            textUnitChecker.check(tmTextUnit.getContent(), contentToCheck);
          } else {
            textUnitChecker.check(tmTextUnit.getContent(), contentToCheck, targetLocale);
          }
        } catch (IntegrityCheckException e) {
          if (tmTextUnit.getPluralForm() != null
              && pluralIntegrityCheckerRelaxer.shouldRelaxIntegrityCheck(
                  tmTextUnit.getContent(),
                  contentToCheck,
                  tmTextUnit.getPluralForm().getName(),
                  textUnitChecker)) {
            logger.debug(
                "Relaxing the check for plural string with form: {}",
                tmTextUnit.getPluralForm().getName());
          } else {
            throw e;
          }
        }
      }
    }
  }
}
