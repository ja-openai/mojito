package com.box.l10n.mojito.mf2.inflection;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.mf2.inflection.TermRequirementValidator.Diagnostic;
import com.box.l10n.mojito.mf2.inflection.TermRequirementValidator.MessageRequirement;
import com.box.l10n.mojito.mf2.inflection.TermRequirementValidator.TermRequirementReport;
import com.box.l10n.mojito.mf2.inflection.TermRequirementValidator.TermUsageRequirement;
import com.box.l10n.mojito.mf2.inflection.TermRequirementValidator.TermValidation;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Writes deterministic JSON reports for term metadata requirements.
 *
 * <p>The writer sorts map-backed report content so generated fixtures and non-Java conformance
 * checks can compare reports byte-for-byte after pretty-printing.
 */
public class TermRequirementReportJsonWriter {

  static final String SCHEMA = "mojito-mf2-inflection/term-requirement-report/v0";

  private final ObjectMapper objectMapper;

  public TermRequirementReportJsonWriter() {
    this(ObjectMapper.withIndentedOutput());
  }

  public TermRequirementReportJsonWriter(ObjectMapper objectMapper) {
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
  }

  public String write(String locale, TermRequirementReport report) {
    Objects.requireNonNull(report, "report");
    return objectMapper.writeValueAsStringUnchecked(toJsonValue(locale, report)) + "\n";
  }

  Map<String, Object> toJsonValue(String locale, TermRequirementReport report) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("diagnostics", diagnosticsJson(report.diagnostics()));
    payload.put("locale", locale);
    payload.put("messages", messagesJson(report.messages()));
    payload.put("schema", SCHEMA);
    payload.put("summary", summaryJson(report));
    return payload;
  }

  private Map<String, Object> messagesJson(Map<String, MessageRequirement> messages) {
    Map<String, Object> payload = new LinkedHashMap<>();
    for (Map.Entry<String, MessageRequirement> message : sortedEntries(messages)) {
      Map<String, Object> messagePayload = new LinkedHashMap<>();
      messagePayload.put("source", message.getValue().source());
      messagePayload.put("termUsages", termUsagesJson(message.getValue().termUsages()));
      payload.put(message.getKey(), messagePayload);
    }
    return payload;
  }

  private List<Object> termUsagesJson(List<TermUsageRequirement> usages) {
    List<Object> payload = new ArrayList<>();
    for (TermUsageRequirement usage : usages) {
      Map<String, Object> usagePayload = new LinkedHashMap<>();
      usagePayload.put("argument", usage.argument());
      usagePayload.put("options", sortedStringMap(usage.options()));
      usagePayload.put("requirements", usage.requirements());
      usagePayload.put("span", List.of(usage.span().start(), usage.span().end()));
      usagePayload.put("termIds", usage.termIds());
      usagePayload.put("validations", validationsJson(usage.validations()));
      payload.add(usagePayload);
    }
    return payload;
  }

  private List<Object> validationsJson(List<TermValidation> validations) {
    List<Object> payload = new ArrayList<>();
    for (TermValidation validation : validations) {
      Map<String, Object> validationPayload = new LinkedHashMap<>();
      validationPayload.put("missing", validation.missing());
      validationPayload.put("status", validation.status().name().toLowerCase(Locale.ROOT));
      validationPayload.put("termId", validation.termId());
      payload.add(validationPayload);
    }
    return payload;
  }

  private List<Object> diagnosticsJson(List<Diagnostic> diagnostics) {
    List<Object> payload = new ArrayList<>();
    for (Diagnostic diagnostic : sortedDiagnostics(diagnostics)) {
      Map<String, Object> diagnosticPayload = new LinkedHashMap<>();
      diagnosticPayload.put("argument", diagnostic.argument());
      diagnosticPayload.put("messageId", diagnostic.messageId());
      diagnosticPayload.put("missing", diagnostic.missing());
      if (diagnostic.relatedArgument() != null) {
        diagnosticPayload.put("relatedArgument", diagnostic.relatedArgument());
      }
      diagnosticPayload.put("span", List.of(diagnostic.span().start(), diagnostic.span().end()));
      diagnosticPayload.put("termId", diagnostic.termId());
      payload.add(diagnosticPayload);
    }
    return payload;
  }

  private Map<String, Object> summaryJson(TermRequirementReport report) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("diagnostics", report.summary().diagnostics());
    payload.put("messages", report.summary().messages());
    payload.put("termUsages", report.summary().termUsages());
    return payload;
  }

  private <T> List<Map.Entry<String, T>> sortedEntries(Map<String, T> values) {
    return values.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList();
  }

  private Map<String, String> sortedStringMap(Map<String, String> values) {
    Map<String, String> payload = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : sortedEntries(values)) {
      payload.put(entry.getKey(), entry.getValue());
    }
    return payload;
  }

  private List<Diagnostic> sortedDiagnostics(List<Diagnostic> diagnostics) {
    return diagnostics.stream()
        .sorted(
            Comparator.comparing(Diagnostic::messageId)
                .thenComparing(Diagnostic::argument)
                .thenComparing(diagnostic -> Objects.toString(diagnostic.relatedArgument(), ""))
                .thenComparing(diagnostic -> Objects.toString(diagnostic.termId(), ""))
                .thenComparingInt(diagnostic -> diagnostic.span().start())
                .thenComparingInt(diagnostic -> diagnostic.span().end())
                .thenComparing(diagnostic -> String.join("\u0000", diagnostic.missing())))
        .toList();
  }
}
