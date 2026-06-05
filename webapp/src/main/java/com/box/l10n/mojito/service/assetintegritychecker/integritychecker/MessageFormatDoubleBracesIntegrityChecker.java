package com.box.l10n.mojito.service.assetintegritychecker.integritychecker;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks the validity of the message format when double braces are used as placeholders in the
 * target content.
 *
 * <p>Verifies correct number of brackets in string then replaces double braces with a single brace
 * and runs the {@link MessageFormatIntegrityChecker} checks.
 */
public class MessageFormatDoubleBracesIntegrityChecker extends MessageFormatIntegrityChecker {

  private static final Pattern MUSTACHE_PARTIAL_PATTERN =
      Pattern.compile("\\{\\{\\s*>\\s*([^{}]+?)\\s*\\}\\}");

  @Override
  public void check(String source, String content) throws MessageFormatIntegrityCheckerException {
    verifyEqualNumberOfBraces(source);
    verifyEqualNumberOfBraces(content);
    verifyMustachePartialsMatch(source, content);
    String compatibleSource = replaceMustachePartialsWithCompatiblePlaceholders(source);
    String compatibleContent = replaceMustachePartialsWithCompatiblePlaceholders(content);
    super.check(
        replaceDoubleBracesWithSingle(compatibleSource),
        replaceDoubleBracesWithSingle(compatibleContent));
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
