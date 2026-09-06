package com.box.l10n.mojito.translationintegrity.messageformat;

import com.box.l10n.mojito.cldr.PluralRuleService;
import com.box.l10n.mojito.translationintegrity.TranslationIntegrityDiagnostic;
import com.box.l10n.mojito.translationintegrity.TranslationIntegrityDiagnostic.Severity;
import com.box.l10n.mojito.translationintegrity.TranslationIntegrityDiagnostic.Subject;
import com.box.l10n.mojito.translationintegrity.TranslationIntegrityDisposition;
import com.box.l10n.mojito.translationintegrity.TranslationIntegrityEvaluation;
import com.box.l10n.mojito.translationintegrity.TranslationIntegrityInputLimits;
import com.ibm.icu.message2.MFDataModel;
import com.ibm.icu.message2.MFParseException;
import com.ibm.icu.message2.MFParser;
import com.ibm.icu.text.PluralRules;
import com.ibm.icu.text.PluralRules.PluralType;
import com.ibm.icu.util.IllformedLocaleException;
import com.ibm.icu.util.ULocale;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Checks generated MF2 candidates without treating ambiguous wording changes as hard failures. */
@SuppressWarnings("deprecation")
public final class Mf2TranslationIntegrityEvaluator {

  private static final Set<String> CATEGORIES =
      Set.of("zero", "one", "two", "few", "many", "other");
  private static final Set<String> NUMERIC_FUNCTIONS =
      Set.of("number", "integer", "percent", "offset");
  private static final int MAX_CONTEXTS = 4096;
  private static final String FALLBACK = "\u0000";
  private static final Pattern FIXED_OPERAND = Pattern.compile("(n|i|v) = ([0-9]+)");

  private Mf2TranslationIntegrityEvaluator() {}

  public static TranslationIntegrityEvaluation evaluate(
      String source, String target, String targetLocale) {
    TranslationIntegrityEvaluation limits =
        TranslationIntegrityInputLimits.evaluate(source, target);
    if (limits.disposition() != TranslationIntegrityDisposition.PASS) return limits;
    Model original;
    Model translated;
    try {
      original = new Model(parse(source));
    } catch (MFParseException | IllegalArgumentException exception) {
      return new TranslationIntegrityEvaluation(
          List.of(TranslationIntegrityDiagnostic.sourceError("mf2-invalid-source", Map.of())),
          TranslationIntegrityDisposition.REJECT_SOURCE);
    }
    try {
      translated = new Model(parse(target));
    } catch (MFParseException | IllegalArgumentException exception) {
      return new TranslationIntegrityEvaluation(
          List.of(TranslationIntegrityDiagnostic.targetError("mf2-invalid-target", Map.of())),
          TranslationIntegrityDisposition.REJECT_TARGET);
    }
    Set<TranslationIntegrityDiagnostic> diagnostics = new LinkedHashSet<>();
    for (String binding : translated.externalBindings) {
      if (!original.externalBindings.contains(binding)) {
        error(diagnostics, "mf2-unknown-binding", Map.of("variable", binding));
      }
    }
    original.declarations.forEach(
        (name, declaration) -> {
          if (declaration instanceof MFDataModel.InputDeclaration
              && (!translated.declarations.containsKey(name)
                  || !original.binding(name).equals(translated.binding(name)))) {
            error(diagnostics, "mf2-input-contract-changed", Map.of("variable", name));
          }
        });
    List<Row> originalRows = alignSourceRows(original, translated);
    if (originalRows == null) {
      error(diagnostics, "mf2-selector-contract-changed", Map.of());
      return result(diagnostics);
    }
    if (translated.selectors.isEmpty()) {
      comparePatterns(
          original,
          original.rows.getFirst(),
          translated,
          translated.rows.getFirst(),
          List.of(),
          null,
          diagnostics);
      return result(diagnostics);
    }
    if (original.selectors.size() != translated.selectors.size()) {
      policyWarning(diagnostics, "mf2-selector-expansion-unverified", Map.of());
    }
    List<Selector> selectors = translated.selectors.stream().map(Selector::new).toList();
    ULocale locale = supportedLocale(targetLocale);
    if (locale == null) {
      policyWarning(diagnostics, "mf2-plural-locale-unavailable", Map.of());
    }
    for (int index = 0; index < selectors.size(); index++) {
      if (selectors.get(index).unsupported) {
        policyWarning(
            diagnostics, "mf2-selector-semantics-unverified", Map.of("selectorIndex", index));
      }
    }
    List<List<String>> domains = new ArrayList<>();
    long contextCount = 1;
    for (int index = 0; index < selectors.size(); index++) {
      Set<String> keys = new LinkedHashSet<>();
      keys.add(FALLBACK);
      for (Row row : originalRows) keys.add(row.keys.get(index));
      for (Row row : translated.rows) keys.add(row.keys.get(index));
      Selector selector = selectors.get(index);
      if (locale != null && selector.pluralType != null && !selector.unsupported) {
        keys.addAll(
            PluralRuleService.getMessageFormatKeywordsForLanguageTag(
                targetLocale, selector.pluralType));
      }
      contextCount *= keys.size();
      if (contextCount > MAX_CONTEXTS) {
        policyWarning(
            diagnostics, "mf2-selector-context-limit", Map.of("maximumContexts", MAX_CONTEXTS));
        // A bounded check must still protect literal selector keys, exact cases, and rendered
        // bindings.
        for (Row row : originalRows)
          compareContext(
              original, originalRows, translated, selectors, row.keys, locale, diagnostics);
        for (Row row : translated.rows)
          compareContext(
              original, originalRows, translated, selectors, row.keys, locale, diagnostics);
        return result(diagnostics);
      }
      domains.add(List.copyOf(keys));
    }
    visitContexts(
        domains,
        0,
        new ArrayList<>(),
        context ->
            compareContext(
                original, originalRows, translated, selectors, context, locale, diagnostics));
    return result(diagnostics);
  }

  private static MFDataModel.Message parse(String value) throws MFParseException {
    return MFParser.parse(value.startsWith("\uFEFF") ? value.substring(1) : value);
  }

  private static ULocale supportedLocale(String targetLocale) {
    if (targetLocale == null || targetLocale.isBlank()) return null;
    try {
      ULocale locale =
          new ULocale.Builder().setLanguageTag(targetLocale.trim().replace('_', '-')).build();
      return PluralRuleService.getMessageFormatKeywordsForLanguageTag(
                  locale.toLanguageTag(), PluralType.CARDINAL)
              .isEmpty()
          ? null
          : locale;
    } catch (IllformedLocaleException exception) {
      return null;
    }
  }

  private static List<Row> alignSourceRows(Model source, Model target) {
    List<Integer> indices = new ArrayList<>();
    int sourceIndex = 0;
    for (Expression selector : target.selectors) {
      if (sourceIndex < source.selectors.size()
          && source.selectors.get(sourceIndex).equals(selector)) {
        indices.add(sourceIndex++);
      } else if (selector.argument.startsWith("$")
          && source.externalBindings.contains(selector.argument.substring(1))) {
        indices.add(-1);
      } else return null;
    }
    if (sourceIndex != source.selectors.size()) return null;
    return source.rows.stream()
        .map(
            row ->
                new Row(
                    indices.stream()
                        .map(index -> index < 0 ? FALLBACK : row.keys.get(index))
                        .toList(),
                    row.pattern))
        .toList();
  }

  private static void compareContext(
      Model source,
      List<Row> sourceRows,
      Model target,
      List<Selector> selectors,
      List<String> context,
      ULocale locale,
      Set<TranslationIntegrityDiagnostic> diagnostics) {
    Row sourceRow = bestRow(sourceRows, selectors, context, null);
    Row targetRow = bestRow(target.rows, selectors, context, locale);
    boolean newPluralContext = false;
    for (int index = 0; index < selectors.size(); index++) {
      int dimension = index;
      String key = targetRow.keys.get(index);
      if (selectors.get(index).pluralType != null
          && CATEGORIES.contains(key)
          && sourceRows.stream().noneMatch(row -> row.keys.get(dimension).equals(key)))
        newPluralContext = true;
    }
    for (int index = 0; index < selectors.size(); index++) {
      Selector selector = selectors.get(index);
      String sourceKey = sourceRow.keys.get(index);
      String targetKey = targetRow.keys.get(index);
      if (!sourceKey.equals(FALLBACK)
          && (selector.pluralType == null || !CATEGORIES.contains(sourceKey))
          && !sourceKey.equals(targetKey)) {
        if (!newPluralContext || numeric(sourceKey) != null) {
          error(
              diagnostics,
              "mf2-selector-case-missing",
              Map.of("selectorIndex", index, "case", sourceKey, "context", display(context)));
        } else {
          // A newly added plural branch can legitimately share wording across another selector.
          // Preserve its original cases and report the uncertain meaning change for review.
          warning(
              diagnostics,
              "mf2-expanded-branch-context-unverified",
              Map.of(
                  "selectorIndex", index, "case", sourceKey, "variant", display(targetRow.keys)));
        }
      }
      String category = context.get(index);
      if (locale == null
          || selector.pluralType == null
          || selector.unsupported
          || !CATEGORIES.contains(category)
          || category.equals("other")) continue;
      if (!PluralRules.forLocale(locale, selector.pluralType).getKeywords().contains(category))
        continue;
      if (!targetKey.equals(category) && !selector.exactCovers(targetKey, category, locale)) {
        error(
            diagnostics,
            "mf2-plural-category-missing",
            Map.of("selectorIndex", index, "category", category, "context", display(context)));
      } else if (selector.exactCovers(targetKey, category, locale)) {
        Double fixed = selector.singleton(category, locale);
        for (double signed : new double[] {fixed, -fixed}) {
          List<String> signedContext = new ArrayList<>(context);
          String exactKey = BigDecimal.valueOf(signed).stripTrailingZeros().toPlainString();
          signedContext.set(index, exactKey);
          String selectedKey =
              bestRow(target.rows, selectors, signedContext, locale).keys.get(index);
          if (!selectedKey.equals(exactKey) && !selectedKey.equals(category)) {
            policyWarning(
                diagnostics,
                "mf2-singleton-sign-coverage-unverified",
                Map.of(
                    "selectorIndex",
                    index,
                    "category",
                    category,
                    "variant",
                    display(targetRow.keys)));
          }
        }
      }
    }
    comparePatterns(source, sourceRow, target, targetRow, selectors, locale, diagnostics);
  }

  private static void comparePatterns(
      Model source,
      Row sourceRow,
      Model target,
      Row targetRow,
      List<Selector> selectors,
      ULocale locale,
      Set<TranslationIntegrityDiagnostic> diagnostics) {
    List<Expression> expected = source.expressions(sourceRow.pattern);
    List<Expression> actual = target.expressions(targetRow.pattern);
    for (Expression expression : expected) {
      if (actual.contains(expression)) continue;
      boolean fixedCounter = false;
      if (expression.bare) {
        for (int index = 0; index < selectors.size(); index++) {
          Selector selector = selectors.get(index);
          if (expression.argument.equals(selector.expression.argument)
              && selector.fixedCount(targetRow.keys.get(index), locale)) fixedCounter = true;
        }
      }
      if (fixedCounter) continue;
      boolean conflictingFormat =
          actual.stream()
              .anyMatch(
                  candidate ->
                      candidate.argument.equals(expression.argument)
                          && !candidate.equals(expression));
      if (conflictingFormat && !expression.bare) {
        error(
            diagnostics,
            "mf2-expression-contract-changed",
            Map.of("argument", expression.argument, "variant", display(targetRow.keys)));
      } else {
        warning(
            diagnostics,
            "mf2-rendered-expression-missing",
            Map.of("argument", expression.argument, "variant", display(targetRow.keys)));
      }
    }
  }

  private static Row bestRow(
      List<Row> rows, List<Selector> selectors, List<String> context, ULocale locale) {
    Row best = null;
    List<Integer> bestRanks = null;
    for (Row row : rows) {
      List<Integer> ranks = new ArrayList<>();
      boolean matches = true;
      for (int index = 0; index < context.size(); index++) {
        String key = row.keys.get(index);
        String value = context.get(index);
        Selector selector = selectors.get(index);
        if (key.equals(FALLBACK)) ranks.add(2);
        else if (key.equals(value))
          ranks.add(CATEGORIES.contains(key) && selector.pluralType != null ? 1 : 0);
        else if (selector.exactCovers(key, value, locale)) ranks.add(0);
        else if (locale != null
            && selector.pluralType != null
            && !selector.unsupported
            && numeric(value) != null
            && CATEGORIES.contains(key)
            && PluralRules.forLocale(locale, selector.pluralType)
                .select(Double.parseDouble(value))
                .equals(key)) ranks.add(1);
        else {
          matches = false;
          break;
        }
      }
      if (matches && (best == null || precedes(ranks, bestRanks))) {
        best = row;
        bestRanks = ranks;
      }
    }
    // MFParser guarantees an all-wildcard fallback.
    return best;
  }

  private static boolean precedes(List<Integer> left, List<Integer> right) {
    for (int index = 0; index < left.size(); index++) {
      int comparison = Integer.compare(left.get(index), right.get(index));
      if (comparison != 0) return comparison < 0;
    }
    return false;
  }

  private static void visitContexts(
      List<List<String>> domains,
      int index,
      List<String> context,
      java.util.function.Consumer<List<String>> visitor) {
    if (index == domains.size()) {
      visitor.accept(context);
      return;
    }
    for (String key : domains.get(index)) {
      context.add(key);
      visitContexts(domains, index + 1, context, visitor);
      context.removeLast();
    }
  }

  private static List<String> display(List<String> keys) {
    return keys.stream().map(key -> key.equals(FALLBACK) ? "*" : key).toList();
  }

  private static String numeric(String value) {
    if (!value.matches("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?")) return null;
    return new BigDecimal(value).stripTrailingZeros().toPlainString();
  }

  private static void error(
      Set<TranslationIntegrityDiagnostic> diagnostics, String code, Map<String, Object> details) {
    diagnostics.add(TranslationIntegrityDiagnostic.targetError(code, details));
  }

  private static void warning(
      Set<TranslationIntegrityDiagnostic> diagnostics, String code, Map<String, Object> details) {
    diagnostics.add(
        new TranslationIntegrityDiagnostic(code, Severity.WARNING, Subject.TARGET, details, null));
  }

  private static void policyWarning(
      Set<TranslationIntegrityDiagnostic> diagnostics, String code, Map<String, Object> details) {
    diagnostics.add(
        new TranslationIntegrityDiagnostic(code, Severity.WARNING, Subject.POLICY, details, null));
  }

  private static TranslationIntegrityEvaluation result(
      Set<TranslationIntegrityDiagnostic> diagnostics) {
    return new TranslationIntegrityEvaluation(
        List.copyOf(diagnostics),
        diagnostics.stream().anyMatch(diagnostic -> diagnostic.severity() == Severity.ERROR)
            ? TranslationIntegrityDisposition.REJECT_TARGET
            : TranslationIntegrityDisposition.PASS);
  }

  private record Function(String name, Map<String, String> options) {}

  private record Expression(
      String argument, List<Function> functions, Map<String, String> attributes, boolean bare) {
    @Override
    public boolean equals(Object other) {
      return other instanceof Expression expression
          && argument.equals(expression.argument)
          && functions.equals(expression.functions)
          && attributes.equals(expression.attributes);
    }

    @Override
    public int hashCode() {
      return Objects.hash(argument, functions, attributes);
    }
  }

  private record Row(List<String> keys, MFDataModel.Pattern pattern) {}

  private static final class Selector {
    final Expression expression;
    final PluralType pluralType;
    final boolean unsupported;

    Selector(Expression expression) {
      this.expression = expression;
      Function function = expression.functions.getLast();
      String select = function.options.get("select");
      boolean numeric = NUMERIC_FUNCTIONS.contains(function.name);
      pluralType =
          numeric && (select == null || select.equals("L:ordinal"))
              ? (select == null ? PluralType.CARDINAL : PluralType.ORDINAL)
              : null;
      unsupported =
          !(function.name.equals("string") || Set.of("number", "integer").contains(function.name))
              || expression.functions.size() != 1
              || !expression.attributes.isEmpty()
              || function.options.keySet().stream().anyMatch(key -> !key.equals("select"))
              || select != null && !Set.of("L:exact", "L:ordinal").contains(select);
    }

    boolean exactCovers(String key, String category, ULocale locale) {
      if (locale == null || unsupported || pluralType == null || numeric(key) == null) return false;
      Double fixed = singleton(category, locale);
      return fixed != null && new BigDecimal(key).abs().compareTo(BigDecimal.valueOf(fixed)) == 0;
    }

    boolean fixedCount(String key, ULocale locale) {
      if (unsupported || !Set.of("number", "integer").contains(expression.functions.getLast().name))
        return false;
      return numeric(key) != null
          || locale != null && pluralType == PluralType.CARDINAL && singleton(key, locale) != null;
    }

    private Double singleton(String category, ULocale locale) {
      if (pluralType != PluralType.CARDINAL || !CATEGORIES.contains(category)) return null;
      String rules = PluralRules.forLocale(locale, pluralType).getRules(category);
      if (rules == null || rules.isEmpty()) return null;
      Double fixed = null;
      for (String alternative : rules.split(" or ")) {
        Map<String, Double> operands = new HashMap<>();
        for (String relation : alternative.split(" and ")) {
          Matcher matcher = FIXED_OPERAND.matcher(relation.trim());
          if (matcher.matches()) operands.put(matcher.group(1), Double.valueOf(matcher.group(2)));
        }
        Double value = operands.get("n");
        if (value == null && Double.valueOf(0).equals(operands.get("v"))) value = operands.get("i");
        if (value == null || fixed != null && !fixed.equals(value)) return null;
        fixed = value;
      }
      return fixed;
    }
  }

  private static final class Model {
    final Map<String, MFDataModel.Declaration> declarations = new HashMap<>();
    final Set<String> externalBindings = new HashSet<>();
    final List<Expression> selectors;
    final List<Row> rows;

    Model(MFDataModel.Message message) {
      List<MFDataModel.Declaration> declared =
          message instanceof MFDataModel.SelectMessage select
              ? select.declarations
              : ((MFDataModel.PatternMessage) message).declarations;
      for (MFDataModel.Declaration declaration : declared) {
        declarations.put(
            declaration instanceof MFDataModel.InputDeclaration input
                ? input.name
                : ((MFDataModel.LocalDeclaration) declaration).name,
            declaration);
      }
      declarations.keySet().forEach(this::binding);
      if (message instanceof MFDataModel.SelectMessage select) {
        selectors =
            select.selectors.stream()
                .map(expression -> resolve(expression, new HashSet<>(), false))
                .toList();
        if (selectors.stream().anyMatch(expression -> expression.functions.isEmpty())) {
          throw new IllegalArgumentException("Selector has no selecting function");
        }
        rows =
            select.variants.stream()
                .map(
                    variant -> {
                      List<String> keys = new ArrayList<>();
                      for (int index = 0; index < variant.keys.size(); index++) {
                        MFDataModel.LiteralOrCatchallKey key = variant.keys.get(index);
                        if (key instanceof MFDataModel.CatchallKey) {
                          keys.add(FALLBACK);
                          continue;
                        }
                        String value = ((MFDataModel.Literal) key).value;
                        Selector selector = new Selector(selectors.get(index));
                        String number =
                            !selector.unsupported
                                    && NUMERIC_FUNCTIONS.contains(
                                        selector.expression.functions.getLast().name)
                                ? numeric(value)
                                : null;
                        keys.add(number == null ? value : number);
                      }
                      return new Row(List.copyOf(keys), variant.value);
                    })
                .toList();
        if (rows.stream().map(Row::keys).distinct().count() != rows.size()) {
          throw new IllegalArgumentException("Duplicate numeric variants");
        }
      } else {
        selectors = List.of();
        rows = List.of(new Row(List.of(), ((MFDataModel.PatternMessage) message).pattern));
      }
      rows.forEach(row -> expressions(row.pattern));
    }

    Expression binding(String name) {
      MFDataModel.Declaration declaration = declarations.get(name);
      if (declaration == null) {
        externalBindings.add(name);
        return new Expression("$" + name, List.of(), Map.of(), true);
      }
      MFDataModel.Expression expression =
          declaration instanceof MFDataModel.InputDeclaration input
              ? input.value
              : ((MFDataModel.LocalDeclaration) declaration).value;
      return resolve(
          expression,
          new HashSet<>(Set.of(name)),
          declaration instanceof MFDataModel.InputDeclaration);
    }

    List<Expression> expressions(MFDataModel.Pattern pattern) {
      return pattern.parts.stream()
          .filter(MFDataModel.Expression.class::isInstance)
          .map(MFDataModel.Expression.class::cast)
          .map(expression -> resolve(expression, new HashSet<>(), false))
          .toList();
    }

    Expression resolve(MFDataModel.Expression expression, Set<String> visiting, boolean input) {
      String argument;
      List<Function> functions = new ArrayList<>();
      Map<String, String> attributes = new TreeMap<>();
      MFDataModel.FunctionRef function;
      List<MFDataModel.Attribute> attrs;
      boolean bare = false;
      if (expression instanceof MFDataModel.VariableExpression variable) {
        argument = "$" + variable.arg.name;
        MFDataModel.Declaration bound = declarations.get(variable.arg.name);
        if (!input && bound != null) {
          if (!visiting.add(variable.arg.name))
            throw new IllegalArgumentException("Circular binding");
          Expression inherited =
              resolve(
                  bound instanceof MFDataModel.InputDeclaration declaration
                      ? declaration.value
                      : ((MFDataModel.LocalDeclaration) bound).value,
                  visiting,
                  bound instanceof MFDataModel.InputDeclaration);
          visiting.remove(variable.arg.name);
          argument = inherited.argument;
          functions.addAll(inherited.functions);
          attributes.putAll(inherited.attributes);
        } else externalBindings.add(variable.arg.name);
        function = variable.function;
        attrs = variable.attributes;
        bare = function == null && attrs.isEmpty();
      } else if (expression instanceof MFDataModel.LiteralExpression literal) {
        argument = "L:" + literal.arg.value;
        function = literal.function;
        attrs = literal.attributes;
      } else if (expression instanceof MFDataModel.FunctionExpression functional) {
        argument = ":";
        function = functional.function;
        attrs = functional.attributes;
      } else if (expression instanceof MFDataModel.Markup markup) {
        argument = "#" + markup.name + ":" + String.valueOf(markup.kind);
        function = new MFDataModel.FunctionRef("markup", markup.options);
        attrs = markup.attributes;
      } else throw new IllegalArgumentException("Unsupported expression");
      if (function != null) {
        Map<String, String> options = new TreeMap<>();
        for (MFDataModel.Option option : function.options.values()) {
          String value = value(option.value, visiting);
          if (option.name.equals("select")
              && NUMERIC_FUNCTIONS.contains(function.name)
              && Set.of("L:plural", "L:cardinal").contains(value)) continue;
          options.put(option.name, value);
        }
        functions.add(new Function(function.name, Map.copyOf(options)));
      }
      for (MFDataModel.Attribute attribute : attrs)
        attributes.put(attribute.name, value(attribute.value, visiting));
      return new Expression(argument, List.copyOf(functions), Map.copyOf(attributes), bare);
    }

    String value(MFDataModel.LiteralOrVariableRef value, Set<String> visiting) {
      if (value == null) return "";
      if (value instanceof MFDataModel.Literal literal) return "L:" + literal.value;
      String name = ((MFDataModel.VariableRef) value).name;
      return "V:"
          + resolve(
              new MFDataModel.VariableExpression(
                  new MFDataModel.VariableRef(name), null, List.of()),
              visiting,
              false);
    }
  }
}
