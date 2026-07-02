package com.box.l10n.mojito.mf2.inflection;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.mf2.inflection.TermBindingManifestValidator.ArgumentBinding;
import com.box.l10n.mojito.mf2.inflection.TermBindingManifestValidator.Diagnostic;
import com.box.l10n.mojito.mf2.inflection.TermBindingManifestValidator.MessageBinding;
import com.box.l10n.mojito.mf2.inflection.TermBindingManifestValidator.TermBindingReport;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Writes deterministic JSON diagnostics for schema-gated message-to-term binding manifests.
 *
 * <p>This report is the human/tooling companion to {@link TermBindingManifestValidator}: it
 * preserves missing, ambiguous, unknown, and unused binding diagnostics in sorted order.
 */
public class TermBindingManifestReportJsonWriter {

  static final String SCHEMA = "mojito-mf2-inflection/term-binding-report/v0";

  private final ObjectMapper objectMapper;

  public TermBindingManifestReportJsonWriter() {
    this(ObjectMapper.withIndentedOutput());
  }

  public TermBindingManifestReportJsonWriter(ObjectMapper objectMapper) {
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
  }

  public String write(String locale, TermBindingReport report) {
    Objects.requireNonNull(report, "report");
    return objectMapper.writeValueAsStringUnchecked(toJsonValue(locale, report)) + "\n";
  }

  Map<String, Object> toJsonValue(String locale, TermBindingReport report) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("diagnostics", diagnosticsJson(report.diagnostics()));
    payload.put("locale", locale);
    payload.put("messages", messagesJson(report.messages()));
    payload.put("schema", SCHEMA);
    payload.put("summary", summaryJson(report));
    return payload;
  }

  private Map<String, Object> messagesJson(Map<String, MessageBinding> messages) {
    Map<String, Object> payload = new LinkedHashMap<>();
    for (Map.Entry<String, MessageBinding> message : sortedEntries(messages)) {
      Map<String, Object> messagePayload = new LinkedHashMap<>();
      messagePayload.put("arguments", argumentsJson(message.getValue().arguments()));
      messagePayload.put("requiredArguments", message.getValue().requiredArguments());
      messagePayload.put("source", message.getValue().message());
      payload.put(message.getKey(), messagePayload);
    }
    return payload;
  }

  private Map<String, Object> argumentsJson(Map<String, ArgumentBinding> arguments) {
    Map<String, Object> payload = new LinkedHashMap<>();
    for (Map.Entry<String, ArgumentBinding> argument : sortedEntries(arguments)) {
      Map<String, Object> argumentPayload = new LinkedHashMap<>();
      argumentPayload.put(
          "status", TermBindingManifestValidator.statusCode(argument.getValue().status()));
      argumentPayload.put("termIds", argument.getValue().termIds());
      payload.put(argument.getKey(), argumentPayload);
    }
    return payload;
  }

  private List<Object> diagnosticsJson(List<Diagnostic> diagnostics) {
    List<Object> payload = new ArrayList<>();
    for (Diagnostic diagnostic : sortedDiagnostics(diagnostics)) {
      Map<String, Object> diagnosticPayload = new LinkedHashMap<>();
      diagnosticPayload.put("argument", diagnostic.argument());
      diagnosticPayload.put("messageId", diagnostic.messageId());
      diagnosticPayload.put("status", TermBindingManifestValidator.statusCode(diagnostic.status()));
      diagnosticPayload.put("termIds", diagnostic.termIds());
      payload.add(diagnosticPayload);
    }
    return payload;
  }

  private Map<String, Object> summaryJson(TermBindingReport report) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("diagnostics", report.summary().diagnostics());
    payload.put("messages", report.summary().messages());
    payload.put("requiredArguments", report.summary().requiredArguments());
    return payload;
  }

  private <T> List<Map.Entry<String, T>> sortedEntries(Map<String, T> values) {
    return values.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList();
  }

  private List<Diagnostic> sortedDiagnostics(List<Diagnostic> diagnostics) {
    return diagnostics.stream()
        .sorted(
            Comparator.comparing(Diagnostic::messageId)
                .thenComparing(Diagnostic::argument)
                .thenComparing(diagnostic -> diagnostic.status().name())
                .thenComparing(diagnostic -> String.join("\u0000", diagnostic.termIds())))
        .toList();
  }
}
