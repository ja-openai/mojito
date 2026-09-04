package com.box.l10n.mojito.service.oaitranslate;

import com.box.l10n.mojito.cldr.PluralRuleService;
import com.box.l10n.mojito.service.tm.search.MessageFormatDetector;
import com.box.l10n.mojito.service.tm.search.TextUnitDTO;
import com.ibm.icu.message2.MFDataModel;
import com.ibm.icu.message2.MFParseException;
import com.ibm.icu.message2.MFParser;
import com.ibm.icu.text.MessagePattern;
import com.ibm.icu.text.PluralRules.PluralType;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Adds target-locale plural requirements only when the source has parsed plural selectors. */
public final class AiTranslatePluralPrompt {

  private static final List<String> CATEGORY_ORDER =
      List.of("zero", "one", "two", "few", "many", "other");
  private static final Set<String> MF2_NUMERIC_FUNCTIONS =
      Set.of("number", "integer", "percent", "offset");

  private AiTranslatePluralPrompt() {}

  public static String getPromptSuffix(TextUnitDTO textUnit, String targetLocale) {
    if (textUnit == null || textUnit.getSource() == null || targetLocale == null) {
      return null;
    }
    boolean mf2 = MessageFormatDetector.MF2.equals(textUnit.getMessageFormat());
    Set<PluralType> pluralTypes =
        mf2 ? mf2PluralTypes(textUnit.getSource()) : icuPluralTypes(textUnit.getSource());
    if (pluralTypes.isEmpty()) {
      return null;
    }
    StringBuilder requirements = new StringBuilder();
    for (PluralType pluralType : pluralTypes) {
      Set<String> keywords =
          PluralRuleService.getMessageFormatKeywordsForLanguageTag(targetLocale, pluralType);
      if (keywords.isEmpty()) {
        continue;
      }
      String kind = pluralType.name().toLowerCase(Locale.ROOT);
      String categories =
          String.join(", ", CATEGORY_ORDER.stream().filter(keywords::contains).toList());
      requirements
          .append("\nICU/CLDR ")
          .append(kind)
          .append(" plural rules have ")
          .append(keywords.size())
          .append(keywords.size() == 1 ? " category: " : " categories: ")
          .append(categories)
          .append(". For every ")
          .append(kind)
          .append(" plural selector in this message, include translated branches covering every ")
          .append("listed category, even when absent from the source.");
    }
    if (requirements.isEmpty()) {
      return null;
    }
    requirements.append(
        "\nPreserve variable names, exact-number branches, offsets, and non-plural selector values.");
    if (mf2) {
      requirements.append(
          " Keep MF2 selector order and the * fallback, which covers the other category."
              + " Cover plural categories in each relevant selector context."
              + " Do not change selectors with select=exact or runtime-dependent select options.");
    } else {
      requirements.append(" Keep ICU other branches and # placeholders.");
    }
    return "Plural requirements for target locale " + targetLocale + ":" + requirements;
  }

  private static Set<PluralType> icuPluralTypes(String source) {
    Set<PluralType> pluralTypes = EnumSet.noneOf(PluralType.class);
    try {
      MessagePattern pattern = new MessagePattern(source);
      for (int index = 0; index < pattern.countParts(); index++) {
        MessagePattern.Part part = pattern.getPart(index);
        if (part.getType() == MessagePattern.Part.Type.ARG_START) {
          if (part.getArgType() == MessagePattern.ArgType.PLURAL) {
            pluralTypes.add(PluralType.CARDINAL);
          } else if (part.getArgType() == MessagePattern.ArgType.SELECTORDINAL) {
            pluralTypes.add(PluralType.ORDINAL);
          }
        }
      }
    } catch (IllegalArgumentException ignored) {
      // MessagePattern handles ICU quoting and nested arguments; malformed sources add no guidance.
    }
    return pluralTypes;
  }

  private static Set<PluralType> mf2PluralTypes(String source) {
    Set<PluralType> pluralTypes = EnumSet.noneOf(PluralType.class);
    try {
      String normalized = source.startsWith("\uFEFF") ? source.substring(1) : source;
      if (MFParser.parse(normalized) instanceof MFDataModel.SelectMessage message) {
        Map<String, MFDataModel.Expression> declarations = new HashMap<>();
        for (MFDataModel.Declaration declaration : message.declarations) {
          if (declaration instanceof MFDataModel.InputDeclaration input) {
            declarations.put(input.name, input.value);
          } else if (declaration instanceof MFDataModel.LocalDeclaration local) {
            declarations.put(local.name, local.value);
          }
        }
        for (MFDataModel.Expression selector : message.selectors) {
          PluralType pluralType = mf2PluralType(selector, declarations, new HashSet<>());
          if (pluralType != null) {
            pluralTypes.add(pluralType);
          }
        }
      }
    } catch (MFParseException | IllegalArgumentException ignored) {
      // Invalid source syntax must not prevent the existing translation request from running.
    }
    return pluralTypes;
  }

  private static PluralType mf2PluralType(
      MFDataModel.Expression expression,
      Map<String, MFDataModel.Expression> declarations,
      Set<String> visited) {
    MFDataModel.FunctionRef function;
    if (expression instanceof MFDataModel.VariableExpression variable) {
      function = variable.function;
      if (function == null) {
        return visited.add(variable.arg.name)
            ? mf2PluralType(declarations.get(variable.arg.name), declarations, visited)
            : null;
      }
    } else if (expression instanceof MFDataModel.LiteralExpression literal) {
      function = literal.function;
    } else {
      return null;
    }
    if (function == null || !MF2_NUMERIC_FUNCTIONS.contains(function.name)) {
      return null;
    }
    MFDataModel.Option select = function.options.get("select");
    if (select == null) {
      return PluralType.CARDINAL;
    }
    if (select.value instanceof MFDataModel.Literal literal) {
      return switch (literal.value) {
        case "cardinal", "plural" -> PluralType.CARDINAL;
        case "ordinal" -> PluralType.ORDINAL;
        default -> null;
      };
    }
    return null;
  }
}
