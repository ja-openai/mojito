package com.box.l10n.mojito.translationintegrity.messageformat;

import com.box.l10n.mojito.cldr.PluralRuleService;
import com.box.l10n.mojito.translationintegrity.TranslationIntegrityDiagnostic;
import com.box.l10n.mojito.translationintegrity.TranslationIntegrityDiagnostic.Severity;
import com.box.l10n.mojito.translationintegrity.TranslationIntegrityDiagnostic.Subject;
import com.box.l10n.mojito.translationintegrity.TranslationIntegrityDisposition;
import com.box.l10n.mojito.translationintegrity.TranslationIntegrityEvaluation;
import com.box.l10n.mojito.translationintegrity.TranslationIntegrityInputLimits;
import com.ibm.icu.text.MessagePattern;
import com.ibm.icu.text.MessagePattern.ArgType;
import com.ibm.icu.text.MessagePattern.Part;
import com.ibm.icu.text.PluralRules;
import com.ibm.icu.text.PluralRules.PluralType;
import com.ibm.icu.util.ULocale;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Deterministic candidate checks for embedded ICU messages. Missing rendered information is
 * advisory; syntax, runtime arguments, selector contracts, and provably missing plural coverage
 * reject a target. This evaluator neither calls a model nor changes a translation.
 */
public final class IcuTranslationIntegrityEvaluator {

  private IcuTranslationIntegrityEvaluator() {}

  public static TranslationIntegrityEvaluation evaluate(
      String source, String target, String targetLocale) {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(target, "target");
    TranslationIntegrityEvaluation limits =
        TranslationIntegrityInputLimits.evaluate(source, target);
    if (limits.disposition() != TranslationIntegrityDisposition.PASS) {
      return limits;
    }
    Parsed sourceMessage;
    Parsed targetMessage;
    try {
      sourceMessage = Parsed.parse(source);
    } catch (IllegalArgumentException exception) {
      return invalid(true);
    }
    try {
      targetMessage = Parsed.parse(target);
    } catch (IllegalArgumentException exception) {
      return invalid(false);
    }
    List<TranslationIntegrityDiagnostic> diagnostics = new ArrayList<>();
    Set<String> invented = new TreeSet<>(targetMessage.arguments);
    invented.removeAll(sourceMessage.arguments);
    if (!invented.isEmpty()) {
      diagnostics.add(
          TranslationIntegrityDiagnostic.targetError(
              "unknown-argument", Map.of("arguments", List.copyOf(invented))));
    }
    Checker checker = new Checker(targetLocale, diagnostics);
    checker.compareFormats(sourceMessage.message, targetMessage.message);
    checker.validateCoverage(targetMessage.message, "message");
    checker.compare(
        sourceMessage.message, targetMessage.message, "message", Set.of(), Map.of(), Set.of());
    boolean rejected =
        diagnostics.stream().anyMatch(diagnostic -> diagnostic.severity() == Severity.ERROR);
    return new TranslationIntegrityEvaluation(
        diagnostics,
        rejected
            ? TranslationIntegrityDisposition.REJECT_TARGET
            : TranslationIntegrityDisposition.PASS);
  }

  private static TranslationIntegrityEvaluation invalid(boolean source) {
    return new TranslationIntegrityEvaluation(
        List.of(
            source
                ? TranslationIntegrityDiagnostic.sourceError("source-format-invalid", Map.of())
                : TranslationIntegrityDiagnostic.targetError("target-format-invalid", Map.of())),
        source
            ? TranslationIntegrityDisposition.REJECT_SOURCE
            : TranslationIntegrityDisposition.REJECT_TARGET);
  }

  private static final class Checker {
    private final String locale;
    private final List<TranslationIntegrityDiagnostic> diagnostics;

    private Checker(String locale, List<TranslationIntegrityDiagnostic> diagnostics) {
      this.locale = locale;
      this.diagnostics = diagnostics;
    }

    private Set<String> categories(Selector selector) {
      return PluralRuleService.getMessageFormatKeywordsForLanguageTag(
          locale, selector.pluralType());
    }

    private PluralRules rules(Selector selector) {
      return PluralRules.forLocale(
          ULocale.forLanguageTag(locale.trim().replace('_', '-')), selector.pluralType());
    }

    private void compareFormats(Message source, Message target) {
      Map<RenderKey, Set<FormatSpec>> sourceFormats = source.formats();
      Map<RenderKey, Set<FormatSpec>> targetFormats = target.formats();
      sourceFormats.forEach(
          (key, sourceSpecs) -> {
            Set<FormatSpec> targetSpecs = targetFormats.get(key);
            if (targetSpecs == null) {
              // The rendered-information comparison owns omissions and fixed-count allowances.
              return;
            }
            boolean changed =
                sourceSpecs.stream()
                        .anyMatch(spec -> targetSpecs.stream().noneMatch(spec::compatible))
                    || targetSpecs.stream()
                        .anyMatch(spec -> sourceSpecs.stream().noneMatch(spec::compatible));
            if (changed) {
              diagnostics.add(
                  new TranslationIntegrityDiagnostic(
                      "argument-format-changed",
                      Severity.WARNING,
                      Subject.TARGET,
                      Map.of(
                          "argument",
                          key.name,
                          "offset",
                          key.offset,
                          "sourceFormats",
                          sourceSpecs.stream().map(FormatSpec::description).sorted().toList(),
                          "targetFormats",
                          targetSpecs.stream().map(FormatSpec::description).sorted().toList()),
                      null));
            }
          });
    }

    private void validateCoverage(Message message, String context) {
      for (Selector selector : message.selectors) {
        String selectorContext = context + "/" + selector.name + ":" + selector.type;
        if (selector.isPlural()) {
          Set<String> categories = categories(selector);
          if (categories.isEmpty()) {
            warning("plural-locale-unknown", selectorContext, selector.name);
          } else if (selector.hasCustomNumberFormat()) {
            // A formatter can round or scale the number used by ICU selection. Category names
            // alone cannot prove which branches are reachable in this case.
            warning("plural-format-analysis-unsupported", selectorContext, selector.name);
          } else {
            List<String> missing = new ArrayList<>();
            for (String category : new TreeSet<>(categories)) {
              if (!selector.branches.containsKey(category)) {
                ExactCoverage coverage = exactCoverage(selector, category, rules(selector));
                if (coverage == ExactCoverage.MISSING) {
                  missing.add(category);
                } else if (coverage == ExactCoverage.UNCERTAIN) {
                  warning(
                      "plural-exact-coverage-uncertain",
                      selectorContext + "[" + category + "]",
                      selector.name);
                }
              }
            }
            if (!missing.isEmpty()) {
              diagnostics.add(
                  TranslationIntegrityDiagnostic.targetError(
                      "missing-plural-categories",
                      Map.of(
                          "argument", selector.name,
                          "context", selectorContext,
                          "locale", locale,
                          "pluralType", selector.pluralType().name(),
                          "missing", missing)));
            }
          }
        } else if (selector.type == ArgType.CHOICE) {
          warning("choice-analysis-unsupported", selectorContext, selector.name);
        }
        selector.branches.forEach(
            (key, branch) -> validateCoverage(branch, selectorContext + "[" + key + "]"));
      }
    }

    private void compare(
        Message source,
        Message target,
        String context,
        Set<RenderKey> inheritedTarget,
        Map<String, FixedQuantity> quantities,
        Set<RenderUse> expectedCount) {
      Set<RenderKey> available = new HashSet<>(inheritedTarget);
      available.addAll(target.guaranteedRendering());
      Set<RenderUse> expected = new LinkedHashSet<>(source.rendering);
      expected.addAll(expectedCount);
      for (RenderUse use : expected) {
        if (!available.contains(use.key)
            && !(use.bare
                && quantities.containsKey(use.key.name)
                && quantities.get(use.key.name).allowsOmission(use.key))) {
          diagnostics.add(
              new TranslationIntegrityDiagnostic(
                  "missing-rendered-argument",
                  Severity.WARNING,
                  Subject.TARGET,
                  Map.of("argument", use.key.name, "context", context, "offset", use.key.offset),
                  null));
        }
      }

      Map<String, Integer> occurrences = new HashMap<>();
      for (Selector sourceSelector : source.selectors) {
        String identity = sourceSelector.name + ":" + sourceSelector.type;
        int occurrence = occurrences.merge(identity, 1, Integer::sum);
        List<Selector> matches =
            target.selectors.stream()
                .filter(
                    selector ->
                        selector.name.equals(sourceSelector.name)
                            && selector.type == sourceSelector.type)
                .toList();
        String selectorContext = context + "/" + identity + "#" + occurrence;
        if (matches.size() < occurrence) {
          error("missing-selector", selectorContext, sourceSelector.name);
          continue;
        }
        Selector targetSelector = matches.get(occurrence - 1);
        if (sourceSelector.type == ArgType.CHOICE) {
          // Choice contracts need their own comparison of interval boundaries. Do not infer
          // plural behavior, or reject application-defined format changes, from a guessed model.
          continue;
        }
        if (Double.compare(sourceSelector.offset, targetSelector.offset) != 0) {
          error("plural-offset-changed", selectorContext, sourceSelector.name);
        }
        if (sourceSelector.type == ArgType.SELECT) {
          Set<String> missing = new TreeSet<>(sourceSelector.branches.keySet());
          missing.removeAll(targetSelector.branches.keySet());
          if (!missing.isEmpty()) {
            diagnostics.add(
                TranslationIntegrityDiagnostic.targetError(
                    "missing-select-branches",
                    Map.of(
                        "argument",
                        sourceSelector.name,
                        "context",
                        selectorContext,
                        "missing",
                        List.copyOf(missing))));
          }
        } else {
          Set<Double> missing = new TreeSet<>(sourceSelector.exacts.keySet());
          missing.removeAll(targetSelector.exacts.keySet());
          if (!missing.isEmpty()) {
            diagnostics.add(
                TranslationIntegrityDiagnostic.targetError(
                    "missing-exact-branches",
                    Map.of(
                        "argument",
                        sourceSelector.name,
                        "context",
                        selectorContext,
                        "missing",
                        List.copyOf(missing))));
          }
        }
        Set<RenderKey> inherited = new HashSet<>(inheritedTarget);
        target.rendering.forEach(use -> inherited.add(use.key));
        Set<RenderUse> count = new LinkedHashSet<>();
        if (sourceSelector.isPlural()) {
          sourceSelector
              .branches
              .values()
              .forEach(branch -> branch.collectCount(sourceSelector.name, count));
        }
        for (Map.Entry<String, Message> targetBranch : targetSelector.branches.entrySet()) {
          String key = targetBranch.getKey();
          Message sourceBranch = sourceSelector.branches.get(key);
          if (key.startsWith("=")) {
            Double exact = targetSelector.exactValue(key);
            String sourceKey = sourceSelector.exacts.get(exact);
            sourceBranch = sourceKey == null ? null : sourceSelector.branches.get(sourceKey);
          }
          if (sourceBranch == null) {
            sourceBranch = sourceSelector.branches.get("other");
          }
          Map<String, FixedQuantity> branchQuantities = new HashMap<>(quantities);
          if (targetSelector.isPlural()) {
            FixedQuantity quantity = fixedQuantity(targetSelector, key);
            if (quantity != null) {
              branchQuantities.put(targetSelector.name, quantity);
            }
          }
          compare(
              sourceBranch,
              targetBranch.getValue(),
              selectorContext + "[" + key + "]",
              inherited,
              branchQuantities,
              count);
        }
      }
    }

    private FixedQuantity fixedQuantity(Selector selector, String key) {
      if (selector.hasCustomNumberFormat()) {
        return null;
      }
      if (key.startsWith("=")) {
        return new FixedQuantity(true);
      }
      if (selector.offset != 0 || categories(selector).isEmpty()) {
        return null;
      }
      Set<Double> values = finiteCategoryValues(rules(selector), key);
      // Singleton categories permit idiomatic grammatical omission (Arabic one/two, for example).
      // This is a linguistic allowance, not a claim that category selection fixes signed input.
      return values != null && values.size() == 1 ? new FixedQuantity(false) : null;
    }

    private void error(String code, String context, String argument) {
      diagnostics.add(
          TranslationIntegrityDiagnostic.targetError(
              code, Map.of("argument", argument, "context", context)));
    }

    private void warning(String code, String context, String argument) {
      diagnostics.add(
          new TranslationIntegrityDiagnostic(
              code,
              Severity.WARNING,
              Subject.TARGET,
              Map.of("argument", argument, "context", context),
              null));
    }
  }

  private enum ExactCoverage {
    COVERED,
    MISSING,
    UNCERTAIN
  }

  private static ExactCoverage exactCoverage(
      Selector selector, String category, PluralRules rules) {
    if (category.equals("other") || selector.exacts.isEmpty()) {
      return ExactCoverage.MISSING;
    }
    Set<Double> values = finiteCategoryValues(rules, category);
    if (values == null || values.isEmpty()) {
      return ExactCoverage.MISSING;
    }
    // Exact cases compare original input, before offset and absolute-value plural selection.
    // No assumption that counts are nonnegative is available at this boundary.
    boolean covered =
        values.stream()
            .allMatch(
                value ->
                    selector.exacts.containsKey(normalize(value + selector.offset))
                        && selector.exacts.containsKey(normalize(-value + selector.offset)));
    if (covered) {
      return ExactCoverage.COVERED;
    }
    // Positive-only counters are common, but this API has no domain metadata. An exact case
    // replacing that category may be intentional; flag the unproven negative-input coverage
    // for review instead of forcing an unnecessary model rewrite.
    boolean nonnegativeCovered =
        values.stream()
            .allMatch(value -> selector.exacts.containsKey(normalize(value + selector.offset)));
    return nonnegativeCovered ? ExactCoverage.UNCERTAIN : ExactCoverage.MISSING;
  }

  @SuppressWarnings("deprecation")
  private static Set<Double> finiteCategoryValues(PluralRules rules, String category) {
    Set<Double> values = new HashSet<>();
    for (PluralRules.SampleType type : PluralRules.SampleType.values()) {
      var quantities = rules.getAllKeywordValues(category, type);
      if (quantities == null) {
        return null;
      }
      for (var quantity : quantities) {
        double value = quantity.toDouble();
        // Decimal samples for bounded intervals (French one, for example) do not enumerate all
        // possible fractional values. Only finite integral-valued categories support this proof.
        if (!Double.isFinite(value) || value != Math.rint(value)) {
          return null;
        }
        values.add(normalize(value));
      }
    }
    return values;
  }

  private static double normalize(double value) {
    return value == 0 ? 0 : value;
  }

  private record FixedQuantity(boolean exact) {
    private boolean allowsOmission(RenderKey key) {
      return exact || key.offset == 0;
    }
  }

  private record RenderKey(String name, double offset) {}

  private record FormatSpec(String type, String style) {
    private boolean compatible(FormatSpec other) {
      if (type.equals("#") || other.type.equals("#")) {
        FormatSpec explicit = type.equals("#") ? other : this;
        return explicit.type.equals("#")
            || explicit.type.isEmpty()
            || (explicit.type.equals("number") && explicit.style.isEmpty());
      }
      return equals(other);
    }

    private String description() {
      return type.isEmpty() ? "default" : type + (style.isEmpty() ? "" : ", " + style);
    }
  }

  private record RenderUse(
      RenderKey key, boolean bare, boolean customNumberFormat, FormatSpec format) {}

  private record Message(List<RenderUse> rendering, List<Selector> selectors) {
    private Map<RenderKey, Set<FormatSpec>> formats() {
      Map<RenderKey, Set<FormatSpec>> result = new HashMap<>();
      rendering.forEach(
          use -> result.computeIfAbsent(use.key, ignored -> new HashSet<>()).add(use.format));
      selectors.forEach(
          selector ->
              selector
                  .branches
                  .values()
                  .forEach(
                      branch ->
                          branch
                              .formats()
                              .forEach(
                                  (key, specs) ->
                                      result
                                          .computeIfAbsent(key, ignored -> new HashSet<>())
                                          .addAll(specs))));
      return result;
    }

    private Set<RenderKey> guaranteedRendering() {
      Set<RenderKey> result = new HashSet<>();
      rendering.forEach(use -> result.add(use.key));
      for (Selector selector : selectors) {
        Set<RenderKey> intersection = null;
        for (Message branch : selector.branches.values()) {
          if (intersection == null) {
            intersection = new HashSet<>(branch.guaranteedRendering());
          } else {
            intersection.retainAll(branch.guaranteedRendering());
          }
        }
        if (intersection != null) {
          result.addAll(intersection);
        }
      }
      return result;
    }

    private void collectCount(String name, Collection<RenderUse> result) {
      rendering.stream().filter(use -> use.key.name.equals(name)).forEach(result::add);
      selectors.forEach(
          selector ->
              selector.branches.values().forEach(branch -> branch.collectCount(name, result)));
    }
  }

  private record Selector(
      String name,
      ArgType type,
      double offset,
      Map<String, Message> branches,
      Map<Double, String> exacts) {
    private boolean isPlural() {
      return type == ArgType.PLURAL || type == ArgType.SELECTORDINAL;
    }

    private PluralType pluralType() {
      return type == ArgType.SELECTORDINAL ? PluralType.ORDINAL : PluralType.CARDINAL;
    }

    private Double exactValue(String key) {
      return exacts.entrySet().stream()
          .filter(entry -> entry.getValue().equals(key))
          .map(Map.Entry::getKey)
          .findFirst()
          .orElseThrow();
    }

    private boolean hasCustomNumberFormat() {
      Set<RenderUse> rendering = new HashSet<>();
      branches.values().forEach(branch -> branch.collectCount(name, rendering));
      return rendering.stream().anyMatch(RenderUse::customNumberFormat);
    }
  }

  private record Parsed(Message message, Set<String> arguments) {
    private static Parsed parse(String text) {
      MessagePattern pattern = new MessagePattern(text);
      Set<String> arguments = new HashSet<>();
      return new Parsed(parseMessage(pattern, 0, null, arguments, 0), arguments);
    }

    private static Message parseMessage(
        MessagePattern pattern, int start, RenderKey plural, Set<String> arguments, int depth) {
      if (depth > 64) {
        throw new IllegalArgumentException("Message nesting limit exceeded");
      }
      List<RenderUse> rendering = new ArrayList<>();
      List<Selector> selectors = new ArrayList<>();
      int limit = pattern.getLimitPartIndex(start);
      for (int index = start + 1; index < limit; index++) {
        Part part = pattern.getPart(index);
        if (part.getType() == Part.Type.REPLACE_NUMBER && plural != null) {
          rendering.add(new RenderUse(plural, true, false, new FormatSpec("#", "")));
        } else if (part.getType() == Part.Type.ARG_START) {
          String name = pattern.getSubstring(pattern.getPart(index + 1));
          arguments.add(name);
          ArgType type = part.getArgType();
          int argumentLimit = pattern.getLimitPartIndex(index);
          if (type == ArgType.NONE || type == ArgType.SIMPLE) {
            boolean custom = false;
            String format = "";
            String style = "";
            if (type == ArgType.SIMPLE) {
              format = pattern.getSubstring(pattern.getPart(index + 2)).toLowerCase(Locale.ROOT);
              style =
                  index + 3 < argumentLimit
                      ? pattern.getSubstring(pattern.getPart(index + 3)).trim()
                      : "";
              custom = !format.equals("number") || index + 3 < argumentLimit;
            }
            rendering.add(
                new RenderUse(
                    new RenderKey(name, 0),
                    type == ArgType.NONE,
                    custom,
                    new FormatSpec(format, style)));
          } else {
            Map<String, Message> branches = new LinkedHashMap<>();
            Map<Double, String> exacts = new LinkedHashMap<>();
            double offset = type.hasPluralStyle() ? pattern.getPluralOffset(index + 2) : 0;
            String key = null;
            Double exact = null;
            for (int branchIndex = index + 2; branchIndex < argumentLimit; branchIndex++) {
              Part branchPart = pattern.getPart(branchIndex);
              if (branchPart.getType() == Part.Type.ARG_SELECTOR) {
                key = pattern.getSubstring(branchPart);
                if (type == ArgType.CHOICE) {
                  key = pattern.getNumericValue(pattern.getPart(branchIndex - 1)) + key;
                }
              } else if (branchPart.getType().hasNumericValue()
                  && key != null
                  && key.startsWith("=")) {
                exact = normalize(pattern.getNumericValue(branchPart));
              } else if (branchPart.getType() == Part.Type.MSG_START) {
                Message branch =
                    parseMessage(
                        pattern,
                        branchIndex,
                        type.hasPluralStyle() ? new RenderKey(name, offset) : plural,
                        arguments,
                        depth + 1);
                if (key == null || branches.putIfAbsent(key, branch) != null) {
                  throw new IllegalArgumentException("Duplicate or missing selector key");
                }
                if (exact != null && exacts.putIfAbsent(exact, key) != null) {
                  throw new IllegalArgumentException("Duplicate exact selector key");
                }
                exact = null;
                key = null;
                branchIndex = pattern.getLimitPartIndex(branchIndex);
              }
            }
            selectors.add(new Selector(name, type, offset, branches, exacts));
          }
          index = argumentLimit;
        }
      }
      return new Message(rendering, selectors);
    }
  }
}
