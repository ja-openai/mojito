package com.box.l10n.mojito.translationintegrity.formatjs;

import com.box.l10n.mojito.translationintegrity.TranslationIntegrityDiagnostic;
import com.box.l10n.mojito.translationintegrity.TranslationIntegrityDisposition;
import com.box.l10n.mojito.translationintegrity.TranslationIntegrityEvaluation;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Detects violations of the FormatJS apostrophe-before-rich-tag contract. */
public final class FormatJsApostropheBeforeTagTranslationIntegrityEvaluator {

  private static final FormatJsParserOptions PARSER_OPTIONS =
      FormatJsParserOptions.MOJITO_STRICT.toBuilder().pythonOpaqueTagCompatibility(true).build();

  /** Evaluates this rule in isolation after message-syntax validation. */
  public TranslationIntegrityEvaluation evaluate(String target) {
    return compose(target, TranslationIntegrityEvaluation.pass());
  }

  /**
   * Composes this rule with an already-evaluated FormatJS structural contract.
   *
   * <p>A parse failure emits nothing because the message-syntax lane owns and dominates malformed
   * source or target diagnostics. Every detected target violation is rejected; this evaluator never
   * mutates or proposes a replacement target.
   */
  TranslationIntegrityEvaluation compose(
      String target, TranslationIntegrityEvaluation structuralEvaluation) {
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(structuralEvaluation, "structuralEvaluation");

    if (structuralEvaluation.disposition() == TranslationIntegrityDisposition.REJECT_SOURCE
        || hasSyntaxDiagnostic(structuralEvaluation)) {
      return structuralEvaluation;
    }

    List<Finding> findings = findings(target);
    if (findings.isEmpty()) {
      return structuralEvaluation;
    }

    List<TranslationIntegrityDiagnostic> diagnostics =
        diagnostics(structuralEvaluation.diagnostics(), findings);
    return rejected(structuralEvaluation, diagnostics);
  }

  private static List<Finding> findings(String target) {
    FormatJsParser parser = new FormatJsParser(target, PARSER_OPTIONS);
    FormatJsParseResult result = parser.parseResult();
    if (!result.isSuccess()) {
      return List.of();
    }

    List<Finding> findings = new ArrayList<>();
    for (FormatJsParser.ApostropheQuote quote : parser.apostropheQuotes()) {
      FormatJsTagTokenScanner.TagToken tag =
          FormatJsTagTokenScanner.scan(target, quote.openingOffset() + 1);
      if (tag != null) {
        findings.add(new Finding(quote.openingOffset(), tag.value()));
      }
    }
    for (FormatJsParser.OpaqueTagSpan span : parser.pythonOpaqueTagSpans()) {
      findings.addAll(findRawOpaqueSpanCandidates(target, span));
    }
    findings.sort(Comparator.comparingInt(Finding::openingOffset));
    return List.copyOf(findings);
  }

  /**
   * Preserves legacy true-positive detection inside a non-exact compatibility span.
   *
   * <p>Complete exact tokens remain atomic so attribute apostrophes cannot become findings. Pound
   * signs are conservatively ordinary text because plural context is unavailable here.
   */
  private static List<Finding> findRawOpaqueSpanCandidates(
      String target, FormatJsParser.OpaqueTagSpan span) {
    FormatJsTagTokenScanner.TagToken completeSpan =
        FormatJsTagTokenScanner.scan(target, span.startOffset());
    if (completeSpan != null && completeSpan.endOffset() == span.endOffset()) {
      return List.of();
    }

    List<Finding> findings = new ArrayList<>();
    boolean inQuote = false;
    int position = span.startOffset();
    while (position < span.endOffset()) {
      char character = target.charAt(position);
      if (character == '\'') {
        if (position + 1 < span.endOffset() && target.charAt(position + 1) == '\'') {
          position += 2;
          continue;
        }
        if (inQuote) {
          inQuote = false;
          position++;
          continue;
        }

        char next =
            position + 1 < span.endOffset() ? target.charAt(position + 1) : Character.MIN_VALUE;
        if ("{}<>".indexOf(next) >= 0) {
          FormatJsTagTokenScanner.TagToken followingTag =
              next == '<' ? FormatJsTagTokenScanner.scan(target, position + 1) : null;
          if (followingTag != null && followingTag.endOffset() <= span.endOffset()) {
            findings.add(new Finding(position, followingTag.value()));
          }
          inQuote = true;
        }
        position++;
        continue;
      }

      if (!inQuote && character == '<') {
        FormatJsTagTokenScanner.TagToken nestedTag = FormatJsTagTokenScanner.scan(target, position);
        if (nestedTag != null && nestedTag.endOffset() <= span.endOffset()) {
          position = nestedTag.endOffset();
          continue;
        }
      }
      position++;
    }
    return List.copyOf(findings);
  }

  private static List<TranslationIntegrityDiagnostic> diagnostics(
      List<TranslationIntegrityDiagnostic> structuralDiagnostics, List<Finding> findings) {
    List<TranslationIntegrityDiagnostic> diagnostics = new ArrayList<>(structuralDiagnostics);
    Map<String, Integer> occurrences = new HashMap<>();
    for (Finding finding : findings) {
      int occurrence = occurrences.merge(finding.tag(), 1, Integer::sum);
      diagnostics.add(
          TranslationIntegrityDiagnostic.targetError(
              "unrenderable-tag-apostrophe",
              Map.of("tag", finding.tag(), "occurrence", occurrence)));
    }
    return diagnostics;
  }

  private static TranslationIntegrityEvaluation rejected(
      TranslationIntegrityEvaluation structuralEvaluation,
      List<TranslationIntegrityDiagnostic> diagnostics) {
    return new TranslationIntegrityEvaluation(
        diagnostics,
        structuralEvaluation.policyDiagnostics(),
        TranslationIntegrityDisposition.REJECT_TARGET,
        structuralEvaluation.reviewDisposition(),
        null);
  }

  private static boolean hasSyntaxDiagnostic(TranslationIntegrityEvaluation evaluation) {
    return evaluation.diagnostics().stream()
        .anyMatch(
            diagnostic ->
                diagnostic.code().equals("source-format-invalid")
                    || diagnostic.code().equals("target-format-invalid"));
  }

  private record Finding(int openingOffset, String tag) {}
}
