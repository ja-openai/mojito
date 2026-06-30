package com.box.l10n.mojito.service.assetintegritychecker.integritychecker;

import java.util.regex.Pattern;

/** Checks ICU apostrophe quoting at FormatJS rich-text tag boundaries. */
public class FormatJsRichTextIntegrityChecker extends AbstractTextUnitIntegrityChecker {

  private static final Pattern SINGLE_APOSTROPHE_BEFORE_OPENING_TAG =
      Pattern.compile("(?<!')'(?=<[A-Za-z])");

  @Override
  public void check(String sourceContent, String targetContent) throws IntegrityCheckException {
    if (targetContent != null
        && SINGLE_APOSTROPHE_BEFORE_OPENING_TAG.matcher(targetContent).find()) {
      throw new FormatJsRichTextIntegrityCheckerException(
          "A single ASCII apostrophe before a FormatJS rich-text opening tag is invalid. "
              + "Use two apostrophes, for example l''<privacyLink>, so the tag compiles.");
    }
  }
}
