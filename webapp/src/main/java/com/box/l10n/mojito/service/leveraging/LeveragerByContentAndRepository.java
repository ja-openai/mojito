package com.box.l10n.mojito.service.leveraging;

import com.box.l10n.mojito.entity.TMTextUnit;
import com.box.l10n.mojito.service.security.user.UserService;
import com.box.l10n.mojito.service.tm.TMService;
import com.box.l10n.mojito.service.tm.TMTextUnitVariantCommentService;
import com.box.l10n.mojito.service.tm.search.StatusFilter;
import com.box.l10n.mojito.service.tm.search.TextUnitDTO;
import com.box.l10n.mojito.service.tm.search.TextUnitSearcher;
import com.box.l10n.mojito.service.tm.search.TextUnitSearcherParameters;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Performs leveraging based on the content and repository of the text units.
 *
 * @author garion
 */
public class LeveragerByContentAndRepository extends AbstractLeverager {

  /** logger */
  static Logger logger = LoggerFactory.getLogger(LeveragerByContentAndRepository.class);

  List<Long> repositoryIds;
  List<String> repositoryNames;

  public LeveragerByContentAndRepository(
      List<Long> repositoryIds,
      List<String> repositoryNames,
      TextUnitSearcher textUnitSearcher,
      TMService tmService,
      UserService userService,
      TMTextUnitVariantLeveragingService tmTextUnitVariantLeveragingService,
      TMTextUnitVariantCommentService tmTextUnitVariantCommentService,
      PlatformTransactionManager transactionManager) {
    super(
        textUnitSearcher,
        tmService,
        userService,
        tmTextUnitVariantLeveragingService,
        tmTextUnitVariantCommentService,
        transactionManager);
    this.repositoryIds = repositoryIds;
    this.repositoryNames = repositoryNames;
  }

  @Override
  public List<TextUnitDTO> getLeveragingMatches(
      TMTextUnit tmTextUnit, Long sourceTmId, Long sourceAssetId) {
    logger.debug("Get TextUnitDTOs for leveraging by content and repository");

    TextUnitSearcherParameters textUnitSearcherParameters = new TextUnitSearcherParameters();
    textUnitSearcherParameters.setTmId(sourceTmId);
    textUnitSearcherParameters.setRepositoryIds(repositoryIds);
    textUnitSearcherParameters.setRepositoryNames(repositoryNames);
    textUnitSearcherParameters.setSource(tmTextUnit.getContent());
    textUnitSearcherParameters.setStatusFilter(StatusFilter.TRANSLATED);
    if (tmTextUnit.getPluralForm() != null) {
      textUnitSearcherParameters.setPluralFormId(tmTextUnit.getPluralForm().getId());
    } else {
      textUnitSearcherParameters.setPluralFormsExcluded(true);
    }
    return textUnitSearcher.search(textUnitSearcherParameters);
  }

  @Override
  public boolean isTranslationNeededIfUniqueMatch() {
    return true;
  }

  @Override
  public String getType() {
    return "Leverage by content and repository";
  }
}
