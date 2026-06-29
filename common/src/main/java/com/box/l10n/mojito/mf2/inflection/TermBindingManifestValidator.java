package com.box.l10n.mojito.mf2.inflection;

import com.box.l10n.mojito.mf2.inflection.TermRequirementJsonLoader.TermUsageCatalog;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Validates whether a message-to-term binding manifest is narrow enough for rendering. */
public class TermBindingManifestValidator {

  private static final String UNSUPPORTED_RUNTIME_TERM_INFLECTION_EXPLANATION =
      "unsupported by current V0 locale runtime";

  private final TermUsageExtractor termUsageExtractor;
  private final TermRequirementValidator termRequirementValidator;

  public TermBindingManifestValidator() {
    this(new IcuMessage2TermUsageExtractor());
  }

  public TermBindingManifestValidator(TermUsageExtractor termUsageExtractor) {
    this.termUsageExtractor = Objects.requireNonNull(termUsageExtractor, "termUsageExtractor");
    this.termRequirementValidator = new TermRequirementValidator(this.termUsageExtractor);
  }

  public TermBindingReport validate(TermUsageCatalog usageCatalog) {
    return validate(usageCatalog, null);
  }

  public TermBindingReport validate(TermUsageCatalog usageCatalog, Set<String> knownTermIds) {
    Objects.requireNonNull(usageCatalog, "usageCatalog");
    validateArgumentTermMessages(usageCatalog);
    validateTermIds(usageCatalog);

    Map<String, MessageBinding> messageBindings = new LinkedHashMap<>();
    List<Diagnostic> diagnostics = new ArrayList<>();
    int requiredArguments = 0;

    for (Map.Entry<String, String> messageEntry : usageCatalog.messages().entrySet()) {
      String messageId = messageEntry.getKey();
      Set<String> required =
          TermRenderRuntime.termBindingArguments(
              usageCatalog.locale(), messageEntry.getValue(), termUsageExtractor);
      Set<String> unsupportedRuntimeTermInflectionArguments =
          unsupportedRuntimeTermInflectionArguments(usageCatalog.locale(), messageEntry.getValue());
      requiredArguments += required.size();

      Map<String, List<String>> provided =
          usageCatalog.argumentTerms().getOrDefault(messageId, Map.of());
      Map<String, ArgumentBinding> argumentBindings = new LinkedHashMap<>();
      for (String argument : required) {
        List<String> termIds = provided.getOrDefault(argument, List.of());
        BindingStatus status =
            unsupportedRuntimeTermInflectionArguments.contains(argument)
                ? BindingStatus.UNSUPPORTED_LOCALE_RUNTIME_TERM_INFLECTION
                : statusForRequiredArgument(termIds, knownTermIds);
        ArgumentBinding argumentBinding = new ArgumentBinding(argument, status, termIds);
        argumentBindings.put(argument, argumentBinding);
        if (status != BindingStatus.OK) {
          diagnostics.add(new Diagnostic(messageId, argument, status, termIds));
        }
      }

      for (Map.Entry<String, List<String>> providedBinding : provided.entrySet()) {
        if (!required.contains(providedBinding.getKey())) {
          ArgumentBinding argumentBinding =
              new ArgumentBinding(
                  providedBinding.getKey(), BindingStatus.UNUSED, providedBinding.getValue());
          argumentBindings.put(providedBinding.getKey(), argumentBinding);
          diagnostics.add(
              new Diagnostic(
                  messageId,
                  providedBinding.getKey(),
                  BindingStatus.UNUSED,
                  providedBinding.getValue()));
        }
      }

      messageBindings.put(
          messageId,
          new MessageBinding(messageEntry.getValue(), List.copyOf(required), argumentBindings));
    }

    return new TermBindingReport(
        messageBindings,
        diagnostics,
        new Summary(usageCatalog.messages().size(), requiredArguments, diagnostics.size()));
  }

  public static void requireRenderable(TermBindingReport report) {
    Objects.requireNonNull(report, "report");
    if (report.summary().diagnostics() > 0) {
      throw new IllegalArgumentException(
          "MF2 term binding manifest is not renderable: "
              + report.summary().diagnostics()
              + " binding diagnostics: "
              + diagnosticSummary(report.diagnostics()));
    }
  }

  private void validateArgumentTermMessages(TermUsageCatalog usageCatalog) {
    for (String messageId : usageCatalog.argumentTerms().keySet()) {
      if (!usageCatalog.messages().containsKey(messageId)) {
        throw new IllegalArgumentException(
            "Argument terms reference unknown message: " + messageId);
      }
    }
  }

  private void validateTermIds(TermUsageCatalog usageCatalog) {
    for (Map.Entry<String, Map<String, List<String>>> messageEntry :
        usageCatalog.argumentTerms().entrySet()) {
      for (Map.Entry<String, List<String>> argumentEntry : messageEntry.getValue().entrySet()) {
        Set<String> seen = new HashSet<>();
        for (String termId : argumentEntry.getValue()) {
          if (termId.isBlank()) {
            throw new IllegalArgumentException(
                "Expected non-blank term id for message "
                    + messageEntry.getKey()
                    + " argument "
                    + argumentEntry.getKey());
          }
          if (!seen.add(termId)) {
            throw new IllegalArgumentException(
                "Duplicate term id for message "
                    + messageEntry.getKey()
                    + " argument "
                    + argumentEntry.getKey()
                    + ": "
                    + termId);
          }
        }
      }
    }
  }

  private BindingStatus statusForRequiredArgument(List<String> termIds, Set<String> knownTermIds) {
    if (termIds == null || termIds.isEmpty()) {
      return BindingStatus.MISSING;
    }
    if (termIds.size() > 1) {
      return BindingStatus.AMBIGUOUS;
    }
    if (knownTermIds != null && !knownTermIds.contains(termIds.getFirst())) {
      return BindingStatus.UNKNOWN;
    }
    return BindingStatus.OK;
  }

  private Set<String> unsupportedRuntimeTermInflectionArguments(String locale, String message) {
    Set<String> arguments = new LinkedHashSet<>();
    for (TermRequirementValidator.TermUsageRequirement usage :
        termRequirementValidator.requirementsForMessage(message, locale)) {
      if (usage
          .requirements()
          .contains(TermRequirementValidator.UNSUPPORTED_RUNTIME_TERM_INFLECTION)) {
        arguments.add(usage.argument());
      }
    }
    return Collections.unmodifiableSet(arguments);
  }

  private static String diagnosticSummary(List<Diagnostic> diagnostics) {
    return diagnostics.stream()
        .sorted(
            Comparator.comparing(Diagnostic::messageId)
                .thenComparing(Diagnostic::argument)
                .thenComparing(diagnostic -> diagnostic.status().name())
                .thenComparing(diagnostic -> String.join("\u0000", diagnostic.termIds())))
        .map(TermBindingManifestValidator::diagnosticSummary)
        .reduce((left, right) -> left + "; " + right)
        .orElse("");
  }

  private static String diagnosticSummary(Diagnostic diagnostic) {
    String status = diagnosticStatusSummary(diagnostic.status());
    String termIds =
        diagnostic.termIds().isEmpty() ? "" : "(" + String.join(",", diagnostic.termIds()) + ")";
    return diagnostic.messageId() + "." + diagnostic.argument() + "=" + status + termIds;
  }

  private static String diagnosticStatusSummary(BindingStatus status) {
    String code = statusCode(status);
    return status == BindingStatus.UNSUPPORTED_LOCALE_RUNTIME_TERM_INFLECTION
        ? code + " (" + UNSUPPORTED_RUNTIME_TERM_INFLECTION_EXPLANATION + ")"
        : code;
  }

  static String statusCode(BindingStatus status) {
    return status.name().toLowerCase(Locale.ROOT).replace('_', '-');
  }

  public enum BindingStatus {
    OK,
    MISSING,
    AMBIGUOUS,
    UNKNOWN,
    UNUSED,
    UNSUPPORTED_LOCALE_RUNTIME_TERM_INFLECTION
  }

  public record TermBindingReport(
      Map<String, MessageBinding> messages, List<Diagnostic> diagnostics, Summary summary) {

    public TermBindingReport {
      messages = unmodifiableLinkedMap(Objects.requireNonNull(messages, "messages"));
      diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
      summary = Objects.requireNonNull(summary, "summary");
    }
  }

  public record MessageBinding(
      String message, List<String> requiredArguments, Map<String, ArgumentBinding> arguments) {

    public MessageBinding {
      message = Objects.requireNonNull(message, "message");
      requiredArguments =
          List.copyOf(Objects.requireNonNull(requiredArguments, "requiredArguments"));
      arguments = unmodifiableLinkedMap(Objects.requireNonNull(arguments, "arguments"));
    }
  }

  public record ArgumentBinding(String argument, BindingStatus status, List<String> termIds) {

    public ArgumentBinding {
      argument = requireText(argument, "argument");
      status = Objects.requireNonNull(status, "status");
      termIds = List.copyOf(Objects.requireNonNull(termIds, "termIds"));
    }
  }

  public record Diagnostic(
      String messageId, String argument, BindingStatus status, List<String> termIds) {

    public Diagnostic {
      messageId = requireText(messageId, "messageId");
      argument = requireText(argument, "argument");
      status = Objects.requireNonNull(status, "status");
      termIds = List.copyOf(Objects.requireNonNull(termIds, "termIds"));
    }
  }

  public record Summary(int messages, int requiredArguments, int diagnostics) {

    public Summary {
      if (messages < 0 || requiredArguments < 0 || diagnostics < 0) {
        throw new IllegalArgumentException("Binding summary counts must be non-negative");
      }
    }
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return value;
  }

  private static <K, V> Map<K, V> unmodifiableLinkedMap(Map<K, V> values) {
    Map<K, V> copied = new LinkedHashMap<>();
    for (Map.Entry<K, V> entry : values.entrySet()) {
      copied.put(
          Objects.requireNonNull(entry.getKey(), "map key"),
          Objects.requireNonNull(entry.getValue(), "map value"));
    }
    return Collections.unmodifiableMap(copied);
  }
}
