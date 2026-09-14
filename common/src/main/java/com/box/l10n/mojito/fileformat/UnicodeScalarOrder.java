package com.box.l10n.mojito.fileformat;

import java.util.Comparator;

/** Shared Unicode ordering for deterministic localization file output. */
final class UnicodeScalarOrder {

  static final Comparator<String> COMPARATOR =
      (left, right) -> {
        int leftIndex = 0;
        int rightIndex = 0;
        while (leftIndex < left.length() && rightIndex < right.length()) {
          int first = left.codePointAt(leftIndex);
          int second = right.codePointAt(rightIndex);
          if (first != second) {
            return Integer.compare(first, second);
          }
          leftIndex += Character.charCount(first);
          rightIndex += Character.charCount(second);
        }
        return Integer.compare(left.length() - leftIndex, right.length() - rightIndex);
      };

  private UnicodeScalarOrder() {}
}
