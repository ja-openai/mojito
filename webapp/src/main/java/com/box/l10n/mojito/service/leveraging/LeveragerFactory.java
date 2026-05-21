package com.box.l10n.mojito.service.leveraging;

import com.box.l10n.mojito.service.security.user.UserService;
import com.box.l10n.mojito.service.tm.TMService;
import com.box.l10n.mojito.service.tm.TMTextUnitVariantCommentService;
import com.box.l10n.mojito.service.tm.search.TextUnitSearcher;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class LeveragerFactory {

  final TextUnitSearcher textUnitSearcher;
  final TMService tmService;
  final UserService userService;
  final TMTextUnitVariantLeveragingService tmTextUnitVariantLeveragingService;
  final TMTextUnitVariantCommentService tmTextUnitVariantCommentService;

  public LeveragerFactory(
      TextUnitSearcher textUnitSearcher,
      TMService tmService,
      UserService userService,
      TMTextUnitVariantLeveragingService tmTextUnitVariantLeveragingService,
      TMTextUnitVariantCommentService tmTextUnitVariantCommentService) {
    this.textUnitSearcher = textUnitSearcher;
    this.tmService = tmService;
    this.userService = userService;
    this.tmTextUnitVariantLeveragingService = tmTextUnitVariantLeveragingService;
    this.tmTextUnitVariantCommentService = tmTextUnitVariantCommentService;
  }

  public LeveragerByContentAndRepository byContentAndRepository(
      List<Long> repositoryIds, List<String> repositoryNames) {
    return new LeveragerByContentAndRepository(
        repositoryIds,
        repositoryNames,
        textUnitSearcher,
        tmService,
        userService,
        tmTextUnitVariantLeveragingService,
        tmTextUnitVariantCommentService);
  }

  public LeveragerByTmTextUnit byTmTextUnit(
      Long tmTextUnitId, boolean translationNeededIfUniqueMatch) {
    return new LeveragerByTmTextUnit(
        tmTextUnitId,
        translationNeededIfUniqueMatch,
        textUnitSearcher,
        tmService,
        userService,
        tmTextUnitVariantLeveragingService,
        tmTextUnitVariantCommentService);
  }
}
