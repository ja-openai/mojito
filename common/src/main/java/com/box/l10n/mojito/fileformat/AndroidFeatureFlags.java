package com.box.l10n.mojito.fileformat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Preserves AAPT2's ordered, last-declaration-wins feature-flag properties. */
final class AndroidFeatureFlags {

  private static final String RUNTIME = "\u0000runtime:";
  private static final String UNSET = "\u0000unset:";
  private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_.-]*");

  private AndroidFeatureFlags() {}

  static Map<String, Boolean> values(List<AndroidFeatureFlag> definitions) {
    if (definitions == null) {
      throw invalid();
    }
    Map<String, Boolean> result = new LinkedHashMap<>();
    for (AndroidFeatureFlag definition : definitions) {
      if (definition == null
          || definition.name() == null
          || !NAME.matcher(definition.name()).matches()) {
        throw invalid();
      }
      String name = definition.name();
      result.remove(RUNTIME + name);
      result.remove(UNSET + name);
      if (definition.readOnly()) {
        if (definition.value() == null) {
          result.put(UNSET + name, true);
          result.remove(name);
        } else {
          result.put(name, definition.value());
        }
      } else {
        result.put(name, true);
        result.put(RUNTIME + name, true);
      }
    }
    return result;
  }

  static boolean runtime(Map<String, Boolean> values, String name) {
    return values.containsKey(RUNTIME + name);
  }

  static boolean unset(Map<String, Boolean> values, String name) {
    return values.containsKey(UNSET + name);
  }

  private static LocalizationParseException invalid() {
    return new LocalizationParseException(
        "INVALID_ANDROID_FEATURE_FLAG", "Android feature flags require valid names and modes");
  }
}
