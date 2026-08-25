package com.box.l10n.mojito.translationintegrity.formatjs;

/**
 * Parser options matching {@code @formatjs/icu-messageformat-parser} 3.5.10, plus a Mojito
 * maximum-depth safety limit.
 *
 * <p>The upstream locale option is intentionally not exposed. Mojito's validation parser is
 * locale-neutral; resolving {@code j} date skeleton fields requires the JavaScript runtime's pinned
 * {@code Intl.Locale} data and is rendering behavior rather than structural validation.
 */
public record FormatJsParserOptions(
    boolean ignoreTag,
    boolean requiresOtherClause,
    boolean shouldParseSkeletons,
    boolean captureLocation,
    int maxNestingDepth) {

  /** Defaults of FormatJS's low-level {@code Parser} constructor. */
  public static final FormatJsParserOptions LOW_LEVEL_DEFAULTS = builder().build();

  /** Defaults applied by FormatJS's exported throwing {@code parse()} facade. */
  public static final FormatJsParserOptions UPSTREAM_PARSE_DEFAULTS =
      builder().requiresOtherClause(true).shouldParseSkeletons(true).build();

  /** Mojito's validation policy; unlike upstream it captures locations and limits recursion. */
  public static final FormatJsParserOptions MOJITO_STRICT =
      builder()
          .ignoreTag(true)
          .requiresOtherClause(true)
          .shouldParseSkeletons(true)
          .captureLocation(true)
          .maxNestingDepth(100)
          .build();

  public FormatJsParserOptions {
    if (maxNestingDepth < 0) {
      throw new IllegalArgumentException("maxNestingDepth must not be negative");
    }
  }

  public static Builder builder() {
    return new Builder();
  }

  public Builder toBuilder() {
    return new Builder()
        .ignoreTag(ignoreTag)
        .requiresOtherClause(requiresOtherClause)
        .shouldParseSkeletons(shouldParseSkeletons)
        .captureLocation(captureLocation)
        .maxNestingDepth(maxNestingDepth);
  }

  public static final class Builder {

    private boolean ignoreTag;
    private boolean requiresOtherClause;
    private boolean shouldParseSkeletons;
    private boolean captureLocation;
    private int maxNestingDepth;

    private Builder() {}

    public Builder ignoreTag(boolean ignoreTag) {
      this.ignoreTag = ignoreTag;
      return this;
    }

    public Builder requiresOtherClause(boolean requiresOtherClause) {
      this.requiresOtherClause = requiresOtherClause;
      return this;
    }

    public Builder shouldParseSkeletons(boolean shouldParseSkeletons) {
      this.shouldParseSkeletons = shouldParseSkeletons;
      return this;
    }

    public Builder captureLocation(boolean captureLocation) {
      this.captureLocation = captureLocation;
      return this;
    }

    public Builder maxNestingDepth(int maxNestingDepth) {
      this.maxNestingDepth = maxNestingDepth;
      return this;
    }

    public FormatJsParserOptions build() {
      return new FormatJsParserOptions(
          ignoreTag, requiresOtherClause, shouldParseSkeletons, captureLocation, maxNestingDepth);
    }
  }
}
