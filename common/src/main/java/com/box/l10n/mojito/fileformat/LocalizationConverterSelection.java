package com.box.l10n.mojito.fileformat;

import com.box.l10n.mojito.okapi.FilterConfigIdOverride;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Explicit per-request portable conversion without changing legacy filter defaults. */
public final class LocalizationConverterSelection {

  public static final String PORTABLE_OPTION = "mojito.converter=portable";
  public static final String OKAPI_OPTION = "mojito.converter=okapi";
  public static final String LEGACY_JSON_COMMENT_MIGRATION_OPTION =
      "mojito.migrateLegacyJsonComments=true";

  public enum Mode {
    OKAPI,
    PORTABLE
  }

  private LocalizationConverterSelection() {}

  public static List<String> usePortable(List<String> filterOptions) {
    List<String> selected =
        filterOptions == null ? new ArrayList<>() : new ArrayList<>(filterOptions);
    selected.remove(OKAPI_OPTION);
    if (!selected.contains(PORTABLE_OPTION)) {
      selected.add(PORTABLE_OPTION);
    }
    return List.copyOf(selected);
  }

  public static boolean isPortable(List<String> filterOptions) {
    return filterOptions != null
        && filterOptions.contains(PORTABLE_OPTION)
        && !filterOptions.contains(OKAPI_OPTION);
  }

  public static List<String> useOkapi(List<String> filterOptions) {
    List<String> selected =
        filterOptions == null ? new ArrayList<>() : new ArrayList<>(filterOptions);
    selected.remove(PORTABLE_OPTION);
    selected.remove(LEGACY_JSON_COMMENT_MIGRATION_OPTION);
    if (!selected.contains(OKAPI_OPTION)) {
      selected.add(OKAPI_OPTION);
    }
    return List.copyOf(selected);
  }

  public static List<String> useLegacyJsonCommentMigration(List<String> filterOptions) {
    List<String> selected = new ArrayList<>(usePortable(filterOptions));
    if (!selected.contains(LEGACY_JSON_COMMENT_MIGRATION_OPTION)) {
      selected.add(LEGACY_JSON_COMMENT_MIGRATION_OPTION);
    }
    return List.copyOf(selected);
  }

  public static boolean isLegacyJsonCommentMigration(List<String> filterOptions) {
    return filterOptions != null && filterOptions.contains(LEGACY_JSON_COMMENT_MIGRATION_OPTION);
  }

  public static boolean isPortable(
      List<String> filterOptions, boolean portableByDefault, String assetPath) {
    return isPortable(filterOptions)
        || (portableByDefault
            && (filterOptions == null || !filterOptions.contains(OKAPI_OPTION))
            && supportedFormat(assetPath) != null);
  }

  public static List<String> platformOptions(List<String> filterOptions) {
    if (filterOptions == null) {
      return null;
    }
    return filterOptions.stream()
        .filter(option -> !PORTABLE_OPTION.equals(option))
        .filter(option -> !OKAPI_OPTION.equals(option))
        .filter(option -> !LEGACY_JSON_COMMENT_MIGRATION_OPTION.equals(option))
        .toList();
  }

  public static LocalizationFileFormat format(
      String assetPath, FilterConfigIdOverride filterConfigIdOverride) {
    LocalizationFileFormat format = supportedFormat(assetPath);
    if (format != null) {
      return format == LocalizationFileFormat.CSV
              && filterConfigIdOverride == FilterConfigIdOverride.CSV_ADOBE_MAGENTO
          ? LocalizationFileFormat.CSV_ADOBE_MAGENTO
          : format;
    }
    String override =
        filterConfigIdOverride == null
            ? ""
            : " (filter override " + filterConfigIdOverride.name() + ")";
    throw new LocalizationParseException(
        "UNSUPPORTED_PORTABLE_FORMAT",
        "Portable conversion is unsupported for " + assetPath + override);
  }

  private static LocalizationFileFormat supportedFormat(String assetPath) {
    if (assetPath == null) {
      return null;
    }
    String path = assetPath.toLowerCase(Locale.ROOT);
    if (path.endsWith(".xml")) {
      return LocalizationFileFormat.ANDROID;
    }
    if (path.endsWith(".stringsdict")) {
      return LocalizationFileFormat.APPLE_STRINGSDICT;
    }
    if (path.endsWith(".strings")) {
      return LocalizationFileFormat.APPLE_STRINGS;
    }
    if (path.endsWith(".xcstrings")) {
      return LocalizationFileFormat.APPLE_XCSTRINGS;
    }
    if (path.endsWith(".pot") || path.endsWith(".po")) {
      return LocalizationFileFormat.GETTEXT_PO;
    }
    if (path.endsWith(".properties")) {
      return LocalizationFileFormat.JAVA_PROPERTIES;
    }
    if (path.endsWith(".resx") || path.endsWith(".resw")) {
      return LocalizationFileFormat.RESX;
    }
    if (path.endsWith(".xtb")) {
      return LocalizationFileFormat.XTB;
    }
    if (path.endsWith(".json")) {
      return LocalizationFileFormat.FORMATJS_JSON;
    }
    if (path.endsWith(".yaml") || path.endsWith(".yml")) {
      return LocalizationFileFormat.YAML;
    }
    if (path.endsWith(".js")) {
      return LocalizationFileFormat.JAVASCRIPT;
    }
    if (path.endsWith(".ts")) {
      return LocalizationFileFormat.TYPESCRIPT;
    }
    if (path.endsWith(".csv")) {
      return LocalizationFileFormat.CSV;
    }
    if (path.endsWith(".html") || path.endsWith(".htm")) {
      return LocalizationFileFormat.HTML;
    }
    return null;
  }
}
