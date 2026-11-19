package com.box.l10n.mojito.service.repository;

import com.box.l10n.mojito.entity.Locale;
import com.box.l10n.mojito.service.locale.LocaleService;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class GenerationExcludedLocaleTags {

  private GenerationExcludedLocaleTags() {}

  static List<String> normalize(
      List<String> localeTags, LocaleService localeService, String excludedSourceLocaleTag) {
    if (localeTags == null) {
      throw new IllegalArgumentException("Provide a locale list; use [] to clear exclusions");
    }
    Set<String> normalized = new LinkedHashSet<>();
    for (String localeTag : localeTags) {
      String trimmed = localeTag == null ? "" : localeTag.trim();
      if (trimmed.isEmpty()) {
        throw new IllegalArgumentException("Excluded locale tags must not be blank");
      }
      Locale locale = localeService.findByBcp47Tag(trimmed);
      if (locale == null) {
        throw new IllegalArgumentException("Unknown excluded locale: " + trimmed);
      }
      if (locale.getBcp47Tag().equals(excludedSourceLocaleTag)) {
        throw new IllegalArgumentException("The source locale cannot be excluded from generation");
      }
      normalized.add(locale.getBcp47Tag());
    }
    return List.copyOf(normalized);
  }
}
