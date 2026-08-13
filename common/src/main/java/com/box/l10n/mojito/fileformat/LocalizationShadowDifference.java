package com.box.l10n.mojito.fileformat;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** Stable, bounded-cardinality migration difference; message content is deliberately excluded. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LocalizationShadowDifference(
    String category, String id, Integer count, List<String> canonicalIds) {

  public LocalizationShadowDifference(String category, String id, Integer count) {
    this(category, id, count, null);
  }
}
