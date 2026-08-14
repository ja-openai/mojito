package com.box.l10n.mojito.mf2.inflection;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Extracts MF2 `:term` usages from a message source.
 *
 * <p>Implementations should return usages in source order. The argument is the variable name
 * without the leading `$`; options are string values after MF2 option parsing, with variable
 * references preserved as values such as `$count`. Spans use {@link SourceSpan}'s Java-string
 * offset contract and cover the full term expression.
 */
public interface TermUsageExtractor {

  List<TermUsage> extract(String message);

  record TermUsage(String argument, Map<String, String> options, SourceSpan span) {

    public TermUsage(String argument, Map<String, String> options, int start, int end) {
      this(argument, options, new SourceSpan(start, end));
    }

    public TermUsage {
      if (argument == null || argument.isBlank()) {
        throw new IllegalArgumentException("Term usage argument is required");
      }
      options = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(options)));
      span = Objects.requireNonNull(span, "span");
    }

    public int start() {
      return span.start();
    }

    public int end() {
      return span.end();
    }
  }
}
