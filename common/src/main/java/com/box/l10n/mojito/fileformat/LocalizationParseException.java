package com.box.l10n.mojito.fileformat;

/** Stable, implementation-independent parser failure exposed to conformance runners and callers. */
public class LocalizationParseException extends RuntimeException {

  private final String code;

  public LocalizationParseException(String code, String message) {
    super(message);
    this.code = code;
  }

  public LocalizationParseException(String code, String message, Throwable cause) {
    super(message, cause);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
