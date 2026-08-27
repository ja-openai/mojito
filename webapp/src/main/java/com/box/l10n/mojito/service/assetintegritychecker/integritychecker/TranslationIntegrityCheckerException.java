package com.box.l10n.mojito.service.assetintegritychecker.integritychecker;

import com.box.l10n.mojito.translationintegrity.TranslationIntegrityDiagnostic;
import com.box.l10n.mojito.translationintegrity.TranslationIntegrityEvaluation;
import java.util.List;
import java.util.Objects;

/** Maps a translation-integrity evaluation to Mojito's standard checker failure contract. */
public class TranslationIntegrityCheckerException extends IntegrityCheckException {

  static final int MAX_MESSAGE_LENGTH = 512;

  private final TranslationIntegrityEvaluation evaluation;

  public TranslationIntegrityCheckerException(
      String checkerType, TranslationIntegrityEvaluation evaluation) {
    super(formatMessage(checkerType, evaluation));
    this.evaluation = Objects.requireNonNull(evaluation, "evaluation");
  }

  public TranslationIntegrityEvaluation getEvaluation() {
    return evaluation;
  }

  static void throwIfTargetRejected(String checkerType, TranslationIntegrityEvaluation evaluation) {
    Objects.requireNonNull(evaluation, "evaluation");
    boolean targetRejected =
        switch (evaluation.disposition()) {
            // Source defects cannot be corrected by rejecting a candidate target.
          case PASS, EXEMPT, REJECT_SOURCE -> false;
          case REJECT_TARGET, AUTO_REPAIR_TARGET -> true;
        };
    if (targetRejected) {
      throw new TranslationIntegrityCheckerException(checkerType, evaluation);
    }
  }

  private static String formatMessage(
      String checkerType, TranslationIntegrityEvaluation evaluation) {
    Objects.requireNonNull(checkerType, "checkerType");
    Objects.requireNonNull(evaluation, "evaluation");

    StringBuilder message =
        new StringBuilder()
            .append(checkerType)
            .append(" translation integrity rejected target [")
            .append(evaluation.disposition())
            .append(']');
    List<TranslationIntegrityDiagnostic> diagnostics = evaluation.diagnostics();
    for (int index = 0; index < diagnostics.size(); index++) {
      String separator = index == 0 ? ": " : ", ";
      String code = diagnostics.get(index).code();
      if (message.length() + separator.length() + code.length() > MAX_MESSAGE_LENGTH) {
        appendTruncationMarker(message);
        break;
      }
      message.append(separator).append(code);
    }
    return message.toString();
  }

  private static void appendTruncationMarker(StringBuilder message) {
    String marker = ", ...";
    int maximumPrefixLength = MAX_MESSAGE_LENGTH - marker.length();
    if (message.length() > maximumPrefixLength) {
      message.setLength(maximumPrefixLength);
    }
    message.append(marker);
  }
}
