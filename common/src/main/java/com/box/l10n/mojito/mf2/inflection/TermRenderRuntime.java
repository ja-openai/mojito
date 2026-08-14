package com.box.l10n.mojito.mf2.inflection;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared runtime helpers for deterministic MF2 term rendering. */
final class TermRenderRuntime {

  private static final Pattern PLACEHOLDER_PATTERN =
      Pattern.compile("\\{\\$(?<name>[A-Za-z_][\\w.-]*)\\}");

  private TermRenderRuntime() {}

  static BoundMessage bindMessage(
      TermRequirementJsonLoader.TermUsageCatalog usageCatalog,
      String messageId,
      TermUsageExtractor termUsageExtractor) {
    Objects.requireNonNull(usageCatalog, "usageCatalog");
    Objects.requireNonNull(messageId, "messageId");
    Objects.requireNonNull(termUsageExtractor, "termUsageExtractor");

    String message = usageCatalog.messages().get(messageId);
    if (message == null) {
      throw new IllegalArgumentException("Unknown bound message: " + messageId);
    }

    Set<String> bindingArguments =
        termBindingArguments(usageCatalog.locale(), message, termUsageExtractor);
    Map<String, List<String>> manifestBindings =
        usageCatalog.argumentTerms().getOrDefault(messageId, Map.of());
    Map<String, String> termArguments = new LinkedHashMap<>();
    for (String argument : bindingArguments) {
      List<String> termIds = manifestBindings.get(argument);
      if (termIds == null || termIds.isEmpty()) {
        throw new IllegalArgumentException(
            "Missing term binding for message " + messageId + " argument " + argument);
      }
      if (termIds.size() > 1) {
        throw new IllegalArgumentException(
            "Ambiguous term binding for message "
                + messageId
                + " argument "
                + argument
                + ": "
                + termIds);
      }
      termArguments.put(argument, termIds.get(0));
    }

    for (String argument : manifestBindings.keySet()) {
      if (!bindingArguments.contains(argument)) {
        throw new IllegalArgumentException(
            "Term binding manifest includes unused argument " + messageId + "." + argument);
      }
    }

    return new BoundMessage(message, termArguments);
  }

  static TermRequirementJsonLoader.TermUsageCatalog singleMessageCatalog(
      TermRequirementJsonLoader.TermUsageCatalog usageCatalog, String messageId) {
    Objects.requireNonNull(usageCatalog, "usageCatalog");
    Objects.requireNonNull(messageId, "messageId");

    String message = usageCatalog.messages().get(messageId);
    if (message == null) {
      throw new IllegalArgumentException("Unknown bound message: " + messageId);
    }

    Map<String, String> messages = new LinkedHashMap<>();
    messages.put(messageId, message);
    Map<String, Map<String, List<String>>> argumentTerms = new LinkedHashMap<>();
    argumentTerms.put(messageId, usageCatalog.argumentTerms().getOrDefault(messageId, Map.of()));
    return new TermRequirementJsonLoader.TermUsageCatalog(
        usageCatalog.schema(), usageCatalog.locale(), messages, argumentTerms);
  }

  static Set<String> termBindingArguments(
      String locale, String message, TermUsageExtractor termUsageExtractor) {
    Objects.requireNonNull(message, "message");
    Objects.requireNonNull(termUsageExtractor, "termUsageExtractor");

    Set<String> arguments = new LinkedHashSet<>();
    for (TermUsageExtractor.TermUsage usage : termUsageExtractor.extract(message)) {
      if (HindiPronounTermOptions.isPronounUsage(locale, usage.options())) {
        String agreeWith = usage.options().get(HindiPronounTermOptions.AGREE_WITH);
        if (agreeWith != null) {
          arguments.add(
              HindiPronounTermOptions.variableReferenceName(
                  agreeWith, HindiPronounTermOptions.AGREE_WITH));
        }
      } else {
        arguments.add(usage.argument());
      }
    }
    return Collections.unmodifiableSet(new LinkedHashSet<>(arguments));
  }

  static String renderPattern(String value, Map<String, String> variables) {
    Matcher matcher = PLACEHOLDER_PATTERN.matcher(value);
    StringBuilder rendered = new StringBuilder();
    while (matcher.find()) {
      String name = matcher.group("name");
      if (!variables.containsKey(name) || variables.get(name) == null) {
        throw new IllegalArgumentException("Missing pattern variable: " + name);
      }
      matcher.appendReplacement(rendered, Matcher.quoteReplacement(variables.get(name)));
    }
    matcher.appendTail(rendered);
    return rendered.toString();
  }

  static String numberFromCountReference(String countReference, Map<String, String> variables) {
    if (countReference == null) {
      return "singular";
    }
    String countName = TermUsageOptions.countVariableName(countReference);
    return numberFromVariable(countName, variables, "Missing count variable: ");
  }

  static String numberFromVariable(
      String variableName, Map<String, String> variables, String missingMessagePrefix) {
    if (!variables.containsKey(variableName) || variables.get(variableName) == null) {
      throw new IllegalArgumentException(missingMessagePrefix + variableName);
    }
    return isOne(variables.get(variableName)) ? "singular" : "plural";
  }

  private static boolean isOne(String value) {
    try {
      double parsed = Double.parseDouble(value);
      if (!Double.isFinite(parsed)) {
        throw new IllegalArgumentException("Count variable must be finite: " + value);
      }
      return parsed == 1;
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("Count variable must be numeric: " + value, e);
    }
  }

  record BoundMessage(String message, Map<String, String> termArguments) {

    BoundMessage {
      message = Objects.requireNonNull(message, "message");
      termArguments =
          Collections.unmodifiableMap(
              new LinkedHashMap<>(Objects.requireNonNull(termArguments, "termArguments")));
    }
  }
}
