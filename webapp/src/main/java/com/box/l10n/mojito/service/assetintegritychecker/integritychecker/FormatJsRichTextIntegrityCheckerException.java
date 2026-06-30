package com.box.l10n.mojito.service.assetintegritychecker.integritychecker;

/** Thrown when ICU apostrophe quoting hides a FormatJS rich-text opening tag. */
public class FormatJsRichTextIntegrityCheckerException extends IntegrityCheckException {

  public FormatJsRichTextIntegrityCheckerException(String message) {
    super(message);
  }
}
