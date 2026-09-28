package com.box.l10n.mojito.service.assetintegritychecker.integritychecker;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks the validity of the message format when double braces are used as placeholders in the
 * target content.
 *
 * <p>Verifies correct number of brackets in string then replaces double braces with a single brace
 * and runs the {@link MessageFormatIntegrityChecker} checks. Sources that cannot be parsed this way
 * can also use simple Mustache triple-brace variables, whose names and counts must be preserved.
 */
public class MessageFormatDoubleBracesIntegrityChecker extends MessageFormatIntegrityChecker {

  private static final Pattern MUSTACHE_PARTIAL_PATTERN =
      Pattern.compile("\\{\\{\\s*>\\s*([^{}]+?)\\s*\\}\\}");

  private static final Pattern MUSTACHE_TRIPLE_VARIABLE_PATTERN =
      Pattern.compile("\\{\\{\\{\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\}\\}\\}(?!\\})");

  @Override
  public void check(String source, String content) throws MessageFormatIntegrityCheckerException {
    verifyEqualNumberOfBraces(source);
    verifyEqualNumberOfBraces(content);
    verifyMustachePartialsMatch(source, content);
    String compatibleSource = replaceMustachePartialsWithCompatiblePlaceholders(source);
    String compatibleContent = replaceMustachePartialsWithCompatiblePlaceholders(content);

    // Adjacent ICU branch/argument braces and quoted literals can look like Mustache variables.
    // Keep the legacy interpretation whenever the source already parses; never retry a failed
    // target under a different interpretation.
    if (source.contains("{{{") && !isLegacyMessageFormat(compatibleSource)) {
      Map<String, Integer> sourceVariables = new LinkedHashMap<>();
      String maskedSource = maskMustacheTripleVariables(source, sourceVariables);
      if (!sourceVariables.isEmpty()) {
        Map<String, Integer> contentVariables = new LinkedHashMap<>();
        String maskedContent = maskMustacheTripleVariables(content, contentVariables);
        if (!sourceVariables.equals(contentVariables)) {
          throw new MessageFormatIntegrityCheckerException(
              "Mustache triple-brace variables do not match source. Found: "
                  + contentVariables
                  + ", expected: "
                  + sourceVariables);
        }
        compatibleSource = replaceMustachePartialsWithCompatiblePlaceholders(maskedSource);
        compatibleContent = replaceMustachePartialsWithCompatiblePlaceholders(maskedContent);
      }
    }

    super.check(
        replaceDoubleBracesWithSingle(compatibleSource),
        replaceDoubleBracesWithSingle(compatibleContent));
  }

  private boolean isLegacyMessageFormat(String source) {
    String compatibleSource = replaceDoubleBracesWithSingle(source);
    try {
      super.check(compatibleSource, compatibleSource);
      return true;
    } catch (MessageFormatIntegrityCheckerException e) {
      return false;
    }
  }

  private String maskMustacheTripleVariables(String text, Map<String, Integer> variables) {
    StringBuilder masked = new StringBuilder(text.length());
    Matcher matcher = MUSTACHE_TRIPLE_VARIABLE_PATTERN.matcher(text);
    int depth = 0;
    for (int i = 0; i < text.length(); i++) {
      // Only top-level variables are supported: braces inside an ICU branch belong to ICU.
      if (depth == 0 && text.startsWith("{{{", i)) {
        if (!matcher.region(i, text.length()).lookingAt()) {
          throw new MessageFormatIntegrityCheckerException(
              "Invalid or unsupported Mustache triple-brace variable at index " + i);
        }
        variables.merge(matcher.group(1), 1, Integer::sum);
        // A literal cannot collide with ICU argument names. This is only a validation copy.
        masked.append("mojitoMustacheVariable");
        i = matcher.end() - 1;
      } else {
        char c = text.charAt(i);
        if (c == '{') {
          depth++;
        } else if (c == '}') {
          depth--;
        }
        masked.append(c);
      }
    }
    return masked.toString();
  }

  private void verifyEqualNumberOfBraces(String str) throws MessageFormatIntegrityCheckerException {
    ArrayDeque<Character> stack = new ArrayDeque<>();
    for (Character c : str.toCharArray()) {
      if (c.equals('{')) {
        stack.push(c);
        continue;
      } else if (c.equals('}')) {
        if (stack.isEmpty()) {
          throw new MessageFormatIntegrityCheckerException(
              "Invalid pattern, closing bracket found with no associated opening bracket.");
        }
        stack.pop();
      }
    }
    if (!stack.isEmpty()) {
      throw new MessageFormatIntegrityCheckerException(
          "Invalid pattern, there is more left than right braces in string.");
    }
  }

  private String replaceDoubleBracesWithSingle(String str) {
    StringBuilder result = new StringBuilder(str.length());
    int doubleBracesDepth = 0;

    for (int i = 0; i < str.length(); i++) {
      if (i + 1 < str.length() && str.charAt(i) == '{' && str.charAt(i + 1) == '{') {
        result.append('{');
        doubleBracesDepth++;
        i++;
      } else if (i + 1 < str.length()
          && str.charAt(i) == '}'
          && str.charAt(i + 1) == '}'
          && doubleBracesDepth > 0) {
        result.append('}');
        doubleBracesDepth--;
        i++;
      } else {
        result.append(str.charAt(i));
      }
    }

    return result.toString();
  }

  private void verifyMustachePartialsMatch(String source, String content)
      throws MessageFormatIntegrityCheckerException {
    List<String> sourcePartials = getMustachePartials(source);
    List<String> contentPartials = getMustachePartials(content);

    if (!sourcePartials.equals(contentPartials)) {
      throw new MessageFormatIntegrityCheckerException(
          "Mustache partials do not match source. Found: "
              + contentPartials
              + ", expected: "
              + sourcePartials);
    }
  }

  private List<String> getMustachePartials(String str) {
    List<String> partials = new ArrayList<>();
    Matcher matcher = MUSTACHE_PARTIAL_PATTERN.matcher(str);

    while (matcher.find()) {
      partials.add(toCanonicalMustachePartial(matcher.group(1)));
    }

    return partials;
  }

  private String replaceMustachePartialsWithCompatiblePlaceholders(String str) {
    Matcher matcher = MUSTACHE_PARTIAL_PATTERN.matcher(str);
    StringBuffer result = new StringBuffer();

    while (matcher.find()) {
      matcher.appendReplacement(
          result,
          Matcher.quoteReplacement(
              "{{"
                  + toMessageFormatArgumentName(toCanonicalMustachePartial(matcher.group(1)))
                  + "}}"));
    }
    matcher.appendTail(result);

    return result.toString();
  }

  private String toCanonicalMustachePartial(String partialName) {
    return "{{> " + partialName.trim() + " }}";
  }

  private String toMessageFormatArgumentName(String placeholder) {
    StringBuilder result = new StringBuilder("mojitoMustachePartial");

    placeholder
        .codePoints()
        .forEach(
            codePoint -> {
              if (Character.isLetterOrDigit(codePoint) || codePoint == '_') {
                result.appendCodePoint(codePoint);
              } else {
                result.append('_').append(Integer.toHexString(codePoint)).append('_');
              }
            });

    return result.toString();
  }
}
