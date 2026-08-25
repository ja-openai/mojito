package com.box.l10n.mojito.translationintegrity.formatjs;

import java.util.Objects;

/**
 * A semantic skeleton failure that bubbles outside the parser result, matching the exception
 * boundary of {@code @formatjs/icu-messageformat-parser} 3.5.10 and its skeleton parser 2.1.9.
 */
public final class FormatJsSkeletonException extends IllegalArgumentException {

  private final SkeletonType skeletonType;

  FormatJsSkeletonException(
      SkeletonType skeletonType, String message, IllegalArgumentException cause) {
    super(message, cause);
    this.skeletonType = Objects.requireNonNull(skeletonType, "skeletonType");
  }

  public SkeletonType skeletonType() {
    return skeletonType;
  }

  public enum SkeletonType {
    NUMBER,
    DATE_TIME
  }
}
