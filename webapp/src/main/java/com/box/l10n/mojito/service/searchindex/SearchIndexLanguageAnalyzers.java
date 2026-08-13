package com.box.l10n.mojito.service.searchindex;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

final class SearchIndexLanguageAnalyzers {

  static final String DEFAULT_LANGUAGE = "default";
  static final String FOLDED_ANALYZER = "mojito_folded";

  private static final Map<String, String> ANALYZERS =
      Map.ofEntries(
          Map.entry("ar", "arabic"),
          Map.entry("hy", "armenian"),
          Map.entry("eu", "basque"),
          Map.entry("bn", "bengali"),
          Map.entry("bg", "bulgarian"),
          Map.entry("ca", "catalan"),
          Map.entry("zh_hans", "smartcn"),
          Map.entry("zh_hant", "icu_analyzer"),
          Map.entry("cs", "czech"),
          Map.entry("da", "danish"),
          Map.entry("nl", "dutch"),
          Map.entry("en", "english"),
          Map.entry("et", "estonian"),
          Map.entry("fi", "finnish"),
          Map.entry("fr", "french"),
          Map.entry("gl", "galician"),
          Map.entry("de", "german"),
          Map.entry("el", "greek"),
          Map.entry("hi", "hindi"),
          Map.entry("hu", "hungarian"),
          Map.entry("id", "indonesian"),
          Map.entry("ga", "irish"),
          Map.entry("it", "italian"),
          Map.entry("ja", "kuromoji"),
          Map.entry("ko", "nori"),
          Map.entry("lv", "latvian"),
          Map.entry("lt", "lithuanian"),
          Map.entry("no", "norwegian"),
          Map.entry("fa", "persian"),
          Map.entry("pt", "portuguese"),
          Map.entry("pt_br", "brazilian"),
          Map.entry("ro", "romanian"),
          Map.entry("ru", "russian"),
          Map.entry("ckb", "sorani"),
          Map.entry("es", "spanish"),
          Map.entry("sv", "swedish"),
          Map.entry("th", "thai"),
          Map.entry("tr", "turkish"));

  private SearchIndexLanguageAnalyzers() {}

  static String languageKey(String localeTag) {
    if (localeTag == null || localeTag.isBlank()) {
      return DEFAULT_LANGUAGE;
    }

    Locale locale = Locale.forLanguageTag(localeTag.trim().replace('_', '-'));
    String language = locale.getLanguage();
    if (language.equals("zh")) {
      String script = locale.getScript();
      if (script.equalsIgnoreCase("Hant")) {
        return "zh_hant";
      }
      if (script.isEmpty()) {
        String country = locale.getCountry();
        if (country.equalsIgnoreCase("TW")
            || country.equalsIgnoreCase("HK")
            || country.equalsIgnoreCase("MO")) {
          return "zh_hant";
        }
      }
      return "zh_hans";
    }
    if (language.equals("pt") && locale.getCountry().equalsIgnoreCase("BR")) {
      return "pt_br";
    }
    if (language.equals("nb") || language.equals("nn")) {
      return "no";
    }
    return ANALYZERS.containsKey(language) ? language : DEFAULT_LANGUAGE;
  }

  static String normalizeLocaleTag(String localeTag) {
    return localeTag.trim().replace('_', '-').toLowerCase(Locale.ROOT);
  }

  static Map<String, Object> localizedTextMapping() {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put(DEFAULT_LANGUAGE, Map.of("type", "text", "analyzer", FOLDED_ANALYZER));
    ANALYZERS.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(
            entry ->
                fields.put(entry.getKey(), Map.of("type", "text", "analyzer", entry.getValue())));
    return Map.of("dynamic", false, "properties", fields);
  }
}
