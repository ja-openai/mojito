package com.box.l10n.mojito.okapi;

import com.box.l10n.mojito.entity.TMTextUnit;
import com.box.l10n.mojito.security.AuditorAwareImpl;
import com.box.l10n.mojito.service.locale.LocaleService;
import com.box.l10n.mojito.service.security.user.UserRepository;
import com.box.l10n.mojito.service.tm.TMService;
import com.box.l10n.mojito.service.tm.TMTextUnitCurrentVariantRepository;
import com.box.l10n.mojito.service.tm.TMTextUnitRepository;
import com.box.l10n.mojito.service.tm.TMTextUnitVariantCommentService;
import com.box.l10n.mojito.service.tm.TMTextUnitVariantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * @author jaurambault
 */
public class ImportTranslationsByIdStep extends AbstractImportTranslationsStep {

  /** Logger */
  static Logger logger = LoggerFactory.getLogger(ImportTranslationsByIdStep.class);

  public ImportTranslationsByIdStep(
      TextUnitUtils textUnitUtils,
      TMTextUnitRepository tmTextUnitRepository,
      TMTextUnitCurrentVariantRepository tmTextUnitCurrentVariantRepository,
      LocaleService localeService,
      TMTextUnitVariantRepository tmTextUnitVariantRepository,
      TMTextUnitVariantCommentService tmMTextUnitVariantCommentService,
      UserRepository userRepository,
      AuditorAwareImpl auditorAwareImpl,
      TMService tmService,
      PlatformTransactionManager transactionManager) {
    super(
        textUnitUtils,
        tmTextUnitRepository,
        tmTextUnitCurrentVariantRepository,
        localeService,
        tmTextUnitVariantRepository,
        tmMTextUnitVariantCommentService,
        userRepository,
        auditorAwareImpl,
        tmService,
        transactionManager);
  }

  @Override
  public String getName() {
    return "Import translations";
  }

  @Override
  public String getDescription() {
    return "Updates the TM with the extracted new/changed variants."
        + " Expects: raw document. Sends back: original events.";
  }

  @Override
  TMTextUnit getTMTextUnit() {

    TMTextUnit tmTextUnit = null;

    try {
      Long tmTextUnitId = Long.valueOf(textUnit.getId());
      tmTextUnit = tmTextUnitRepository.findById(tmTextUnitId).orElse(null);
    } catch (NumberFormatException nfe) {
      logger.debug("Could not convert the textUnit id into a Long (TextUnit id)", nfe);
    }

    return tmTextUnit;
  }
}
