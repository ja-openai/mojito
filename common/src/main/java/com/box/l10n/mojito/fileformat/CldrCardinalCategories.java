package com.box.l10n.mojito.fileformat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ibm.icu.text.PluralRules;
import com.ibm.icu.util.ULocale;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

/** Version-pinned cardinal categories, independently cross-checked against runtime ICU. */
final class CldrCardinalCategories {

  private static final String RESOURCE =
      "/com/box/l10n/mojito/fileformat/cldr-cardinal-categories.v1.json";
  private static final JsonNode MANIFEST = load();

  private CldrCardinalCategories() {}

  static Set<String> forLocale(String locale) {
    String normalized = locale.replace('_', '-');
    if (normalized.equals("und") || normalized.startsWith("und-")) {
      return Set.of();
    }
    String canonical = ULocale.forLanguageTag(normalized).toLanguageTag();
    JsonNode categories = MANIFEST.path("cardinalCategories").path(canonical);
    if (!categories.isArray()) {
      int separator = canonical.indexOf('-');
      categories =
          MANIFEST
              .path("cardinalCategories")
              .path(separator < 0 ? canonical : canonical.substring(0, separator));
    }
    if (!categories.isArray()) {
      return Set.of();
    }
    Set<String> expected = new HashSet<>();
    categories.forEach(category -> expected.add(category.asText()));
    ULocale language = ULocale.forLanguageTag(normalized);
    if (!PluralRules.forLocale(language, PluralRules.PluralType.CARDINAL)
        .getKeywords()
        .equals(expected)) {
      return Set.of();
    }
    return Set.copyOf(expected);
  }

  private static JsonNode load() {
    try (InputStream source = CldrCardinalCategories.class.getResourceAsStream(RESOURCE)) {
      if (source == null) {
        throw new IllegalStateException("Missing pinned CLDR cardinal-category resource");
      }
      JsonNode manifest = new ObjectMapper().readTree(source);
      if (manifest.path("schemaVersion").asInt() != 1
          || !"46".equals(manifest.path("cldrVersion").asText())
          || !"16.0.0".equals(manifest.path("unicodeVersion").asText())
          || !"https://github.com/unicode-org/cldr-json/blob/46.0.0/cldr-json/cldr-core/supplemental/plurals.json"
              .equals(manifest.path("source").asText())
          || !manifest.path("cardinalCategories").isObject()) {
        throw new IllegalStateException(
            "Invalid or unaudited pinned CLDR cardinal-category resource");
      }
      return manifest;
    } catch (IOException exception) {
      throw new IllegalStateException("Cannot load pinned CLDR cardinal categories", exception);
    }
  }
}
