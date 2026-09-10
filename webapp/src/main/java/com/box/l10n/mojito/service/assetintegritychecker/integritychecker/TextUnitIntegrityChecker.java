package com.box.l10n.mojito.service.assetintegritychecker.integritychecker;

/**
 * @author aloison
 */
public interface TextUnitIntegrityChecker {

  void check(String sourceContent, String targetContent) throws IntegrityCheckException;

  /** Checks with target locale context. Legacy checkers keep their existing behavior. */
  default void check(String sourceContent, String targetContent, String targetLocale)
      throws IntegrityCheckException {
    check(sourceContent, targetContent);
  }

  LocalizableString extractNonLocalizableParts(String string);

  String restoreNonLocalizableParts(LocalizableString localizableString);
}
