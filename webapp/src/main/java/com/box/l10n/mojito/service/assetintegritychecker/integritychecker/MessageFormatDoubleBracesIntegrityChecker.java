package com.box.l10n.mojito.service.assetintegritychecker.integritychecker;

import java.util.ArrayDeque;

/**
 * Checks the validity of the message format when double braces are used as placeholders in the
 * target content.
 *
 * <p>Verifies correct number of brackets in string then replaces double braces with a single brace
 * and runs the {@link MessageFormatIntegrityChecker} checks.
 */
public class MessageFormatDoubleBracesIntegrityChecker extends MessageFormatIntegrityChecker {

  @Override
  public void check(String source, String content) throws MessageFormatIntegrityCheckerException {
    verifyEqualNumberOfBraces(source);
    verifyEqualNumberOfBraces(content);
    super.check(replaceDoubleBracesWithSingle(source), replaceDoubleBracesWithSingle(content));
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
}
