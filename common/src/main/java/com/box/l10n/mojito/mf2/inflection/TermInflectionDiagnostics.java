package com.box.l10n.mojito.mf2.inflection;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Shared helpers for structured term-inflection profile diagnostics. */
public final class TermInflectionDiagnostics {

  public static final String MISSING_FORM_CELL = "missing-form-cell";

  private TermInflectionDiagnostics() {}

  public static List<DiagnosticSummary> diagnosticSummaries(JsonNode diagnostics) {
    if (diagnostics == null || !diagnostics.isArray()) {
      return List.of();
    }

    List<DiagnosticSummary> summaries = new ArrayList<>();
    for (JsonNode diagnostic : diagnostics) {
      summaries.add(
          new DiagnosticSummary(
              textField(diagnostic, "code"),
              textField(diagnostic, "reason"),
              textField(diagnostic, "message"),
              textField(diagnostic, "formKey"),
              textField(diagnostic, "termId"),
              textField(diagnostic, "messageId"),
              textField(diagnostic, "argument"),
              textField(diagnostic, "relatedArgument"),
              textArrayField(diagnostic, "missing"),
              intArrayField(diagnostic, "span")));
    }
    return List.copyOf(summaries);
  }

  public static List<String> missingFormKeys(JsonNode diagnostics) {
    if (diagnostics == null || !diagnostics.isArray()) {
      return List.of();
    }

    Set<String> formKeys = new TreeSet<>();
    for (JsonNode diagnostic : diagnostics) {
      if (isMissingFormCellDiagnostic(diagnostic)) {
        JsonNode formKey = diagnostic.get("formKey");
        if (formKey != null && formKey.isTextual() && !formKey.asText().isBlank()) {
          formKeys.add(formKey.asText().trim());
        }
      }
    }
    return List.copyOf(formKeys);
  }

  private static boolean isMissingFormCellDiagnostic(JsonNode diagnostic) {
    return isMissingFormCellDiagnosticValue(diagnostic.get("code"))
        || isMissingFormCellDiagnosticValue(diagnostic.get("reason"));
  }

  private static boolean isMissingFormCellDiagnosticValue(JsonNode value) {
    return value != null && value.isTextual() && MISSING_FORM_CELL.equals(value.asText());
  }

  private static String textField(JsonNode node, String fieldName) {
    JsonNode value = node.get(fieldName);
    if (value == null || !value.isTextual() || value.asText().isBlank()) {
      return null;
    }
    return value.asText().trim();
  }

  private static List<String> textArrayField(JsonNode node, String fieldName) {
    JsonNode values = node.get(fieldName);
    if (values == null || !values.isArray()) {
      return List.of();
    }

    List<String> result = new ArrayList<>();
    for (JsonNode value : values) {
      if (value.isTextual() && !value.asText().isBlank()) {
        result.add(value.asText().trim());
      }
    }
    return List.copyOf(result);
  }

  private static List<Integer> intArrayField(JsonNode node, String fieldName) {
    JsonNode values = node.get(fieldName);
    if (values == null || !values.isArray()) {
      return List.of();
    }

    List<Integer> result = new ArrayList<>();
    for (JsonNode value : values) {
      if (value.isInt()) {
        result.add(value.asInt());
      }
    }
    return List.copyOf(result);
  }

  public record DiagnosticSummary(
      String code,
      String reason,
      String message,
      String formKey,
      String termId,
      String messageId,
      String argument,
      String relatedArgument,
      List<String> missing,
      List<Integer> span) {

    public DiagnosticSummary {
      missing = missing == null ? List.of() : List.copyOf(missing);
      span = span == null ? List.of() : List.copyOf(span);
    }
  }
}
