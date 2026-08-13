package com.box.l10n.mojito.fileformat;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/** A FormatJS-compatible descriptor with optional, loss-aware native-format metadata. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LocalizationMessage(
    String defaultMessage,
    String description,
    Map<String, String> variants,
    List<LocalizationPlaceholder> placeholders,
    Map<String, Object> metadata) {

  static LocalizationMessage of(
      String message,
      String description,
      Map<String, String> variants,
      List<LocalizationPlaceholder> placeholders,
      Map<String, Object> metadata) {
    return new LocalizationMessage(
        message,
        description == null || description.isBlank() ? null : description,
        variants == null || variants.isEmpty() ? null : variants,
        placeholders == null || placeholders.isEmpty() ? null : List.copyOf(placeholders),
        metadata == null || metadata.isEmpty() ? null : metadata);
  }
}
