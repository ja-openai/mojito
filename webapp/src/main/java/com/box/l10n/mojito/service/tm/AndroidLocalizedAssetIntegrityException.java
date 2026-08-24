package com.box.l10n.mojito.service.tm;

import java.util.List;

/** Raised when generated Android resources violate a runtime or markup contract. */
public class AndroidLocalizedAssetIntegrityException extends RuntimeException {

  private final List<AndroidLocalizedAssetIntegrityValidator.Diagnostic> diagnostics;

  public AndroidLocalizedAssetIntegrityException(
      List<AndroidLocalizedAssetIntegrityValidator.Diagnostic> diagnostics, Throwable cause) {
    super(message(diagnostics), cause);
    this.diagnostics = List.copyOf(diagnostics);
  }

  public List<AndroidLocalizedAssetIntegrityValidator.Diagnostic> getDiagnostics() {
    return diagnostics;
  }

  private static String message(
      List<AndroidLocalizedAssetIntegrityValidator.Diagnostic> diagnostics) {
    return "Generated Android resource integrity validation failed: "
        + diagnostics.stream()
            .map(AndroidLocalizedAssetIntegrityValidator.Diagnostic::toString)
            .toList();
  }
}
