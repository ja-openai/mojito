package com.box.l10n.mojito.mf2.inflection;

import com.ibm.icu.message2.MFDataModel;
import com.ibm.icu.message2.MFParseException;
import com.ibm.icu.message2.MFParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ICU4J-backed semantic extractor for `:term` usages.
 *
 * <p>ICU4J 78.3 exposes MF2 semantic nodes but not source spans, so this spike pairs the ICU parse
 * result with the temporary regex extractor as a span side table. Keep this behind {@link
 * TermUsageExtractor} until parser-backed spans are available.
 */
final class IcuMessage2TermUsageExtractor implements TermUsageExtractor {

  private final TermUsageExtractor spanExtractor;

  IcuMessage2TermUsageExtractor() {
    this(new RegexTermUsageExtractor());
  }

  IcuMessage2TermUsageExtractor(TermUsageExtractor spanExtractor) {
    this.spanExtractor = Objects.requireNonNull(spanExtractor, "spanExtractor");
  }

  @Override
  public List<TermUsage> extract(String message) {
    Objects.requireNonNull(message, "message");
    MFDataModel.Message parsed = parse(message);
    List<SemanticTermUsage> semanticUsages = new ArrayList<>();
    collectTerms(parsed, semanticUsages);
    return attachSpans(message, semanticUsages);
  }

  private MFDataModel.Message parse(String message) {
    try {
      return MFParser.parse(message);
    } catch (MFParseException e) {
      throw new IllegalArgumentException("Invalid MF2 message", e);
    }
  }

  private void collectTerms(MFDataModel.Message message, List<SemanticTermUsage> terms) {
    if (message instanceof MFDataModel.PatternMessage patternMessage) {
      collectDeclarations(patternMessage.declarations, terms);
      collectTerms(patternMessage.pattern, terms);
      return;
    }
    if (message instanceof MFDataModel.SelectMessage selectMessage) {
      collectDeclarations(selectMessage.declarations, terms);
      for (MFDataModel.Expression selector : selectMessage.selectors) {
        collectTerms(selector, terms);
      }
      for (MFDataModel.Variant variant : selectMessage.variants) {
        collectTerms(variant.value, terms);
      }
    }
  }

  private void collectDeclarations(
      List<MFDataModel.Declaration> declarations, List<SemanticTermUsage> terms) {
    for (MFDataModel.Declaration declaration : declarations) {
      if (declaration instanceof MFDataModel.InputDeclaration inputDeclaration) {
        collectTerms(inputDeclaration.value, terms);
      } else if (declaration instanceof MFDataModel.LocalDeclaration localDeclaration) {
        collectTerms(localDeclaration.value, terms);
      }
    }
  }

  private void collectTerms(MFDataModel.Pattern pattern, List<SemanticTermUsage> terms) {
    for (MFDataModel.PatternPart part : pattern.parts) {
      if (part instanceof MFDataModel.Expression expression) {
        collectTerms(expression, terms);
      }
    }
  }

  private void collectTerms(MFDataModel.Expression expression, List<SemanticTermUsage> terms) {
    if (expression instanceof MFDataModel.VariableExpression variableExpression) {
      collectVariableExpression(variableExpression, terms);
    }
  }

  private void collectVariableExpression(
      MFDataModel.VariableExpression expression, List<SemanticTermUsage> terms) {
    if (expression.function == null || !"term".equals(expression.function.name)) {
      return;
    }
    terms.add(new SemanticTermUsage(expression.arg.name, options(expression.function.options)));
  }

  private Map<String, String> options(Map<String, MFDataModel.Option> options) {
    Map<String, String> values = new LinkedHashMap<>();
    options.values().stream()
        .sorted((left, right) -> left.name.compareTo(right.name))
        .forEach(option -> values.put(option.name, value(option.value)));
    return values;
  }

  private String value(MFDataModel.LiteralOrVariableRef value) {
    if (value instanceof MFDataModel.VariableRef variableRef) {
      return "$" + variableRef.name;
    }
    if (value instanceof MFDataModel.Literal literal) {
      return literal.value;
    }
    throw new IllegalArgumentException("Unsupported MF2 option value: " + value.getClass());
  }

  private List<TermUsage> attachSpans(String message, List<SemanticTermUsage> semanticUsages) {
    List<TermUsage> spanUsages = spanExtractor.extract(message);
    if (semanticUsages.size() != spanUsages.size()) {
      throw new IllegalArgumentException(
          "ICU term usage count "
              + semanticUsages.size()
              + " does not match span side-table count "
              + spanUsages.size());
    }

    List<TermUsage> usages = new ArrayList<>();
    for (int i = 0; i < semanticUsages.size(); i++) {
      SemanticTermUsage semanticUsage = semanticUsages.get(i);
      TermUsage spanUsage = spanUsages.get(i);
      if (!semanticUsage.argument().equals(spanUsage.argument())
          || !semanticUsage.options().equals(spanUsage.options())) {
        throw new IllegalArgumentException(
            "ICU term usage does not match span side-table usage at index "
                + i
                + ": semantic="
                + semanticUsage
                + ", span="
                + spanUsage);
      }
      usages.add(
          new TermUsage(semanticUsage.argument(), semanticUsage.options(), spanUsage.span()));
    }
    return List.copyOf(usages);
  }

  private record SemanticTermUsage(String argument, Map<String, String> options) {}
}
