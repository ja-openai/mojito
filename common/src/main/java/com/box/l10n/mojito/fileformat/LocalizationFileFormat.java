package com.box.l10n.mojito.fileformat;

/** Native resource formats accepted by the portable localization catalog converters. */
public enum LocalizationFileFormat {
  ANDROID("android"),
  APPLE_STRINGS("apple_strings"),
  APPLE_STRINGSDICT("apple_stringsdict"),
  APPLE_XCSTRINGS("apple_xcstrings"),
  GETTEXT_PO("gettext_po"),
  JAVA_PROPERTIES("java_properties"),
  FORMATJS_JSON("formatjs_json");

  private final String id;

  LocalizationFileFormat(String id) {
    this.id = id;
  }

  public String id() {
    return id;
  }

  public static LocalizationFileFormat fromId(String id) {
    for (LocalizationFileFormat format : values()) {
      if (format.id.equals(id)) {
        return format;
      }
    }
    throw new IllegalArgumentException("Unsupported localization file format: " + id);
  }
}
