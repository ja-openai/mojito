package com.box.l10n.mojito.fileformat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Validates Android values-directory qualifiers without relying on AAPT2 at runtime. */
record AndroidResourceConfiguration(
    String path, List<String> qualifiers, String locale, String pathFeatureFlag) {

  private static final Set<String> GENDER = Set.of("masculine", "feminine", "neuter");
  private static final Set<String> LAYOUT = Set.of("ldrtl", "ldltr");
  private static final Set<String> SIZE = Set.of("small", "normal", "large", "xlarge");
  private static final Set<String> ASPECT = Set.of("long", "notlong");
  private static final Set<String> ROUND = Set.of("round", "notround");
  private static final Set<String> COLOR = Set.of("widecg", "nowidecg");
  private static final Set<String> HDR = Set.of("highdr", "lowdr");
  private static final Set<String> ORIENTATION = Set.of("port", "land", "square");
  private static final Set<String> UI_MODE =
      Set.of("car", "desk", "television", "appliance", "watch", "vrheadset");
  private static final Set<String> NIGHT = Set.of("night", "notnight");
  private static final Set<String> DENSITY =
      Set.of("ldpi", "mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi", "nodpi", "tvdpi", "anydpi");
  private static final Set<String> TOUCH = Set.of("notouch", "stylus", "finger");
  private static final Set<String> KEYBOARD_VISIBILITY =
      Set.of("keysexposed", "keyshidden", "keyssoft");
  private static final Set<String> KEYBOARD = Set.of("nokeys", "qwerty", "12key");
  private static final Set<String> NAVIGATION_VISIBILITY = Set.of("navexposed", "navhidden");
  private static final Set<String> NAVIGATION = Set.of("nonav", "dpad", "trackball", "wheel");

  static AndroidResourceConfiguration parse(String path) {
    if (path == null) {
      return null;
    }
    if (path.isBlank() || path.startsWith("/") || path.contains("\\")) {
      throw invalidPath();
    }
    List<String> parts = new ArrayList<>();
    String pathFeatureFlag = null;
    for (String part : path.split("/", -1)) {
      if (part.isBlank() || ".".equals(part) || "..".equals(part)) {
        throw invalidPath();
      }
      if (part.startsWith("flag(") && part.endsWith(")")) {
        if (pathFeatureFlag != null && !pathFeatureFlag.isEmpty()) {
          throw new LocalizationParseException(
              "MULTIPLE_ANDROID_PATH_FLAGS",
              "Android resource path cannot contain more than one flag directory");
        }
        pathFeatureFlag = part.substring(5, part.length() - 1);
      } else {
        parts.add(part);
      }
    }
    if (parts.size() < 3
        || !"res".equals(parts.get(parts.size() - 3))
        || !parts.get(parts.size() - 1).endsWith(".xml")) {
      throw invalidPath();
    }
    if (pathFeatureFlag != null && pathFeatureFlag.isEmpty()) {
      pathFeatureFlag = null;
    }
    String directory = parts.get(parts.size() - 2);
    if (!directory.equals("values") && !directory.startsWith("values-")) {
      throw invalidPath();
    }
    if (directory.equals("values")) {
      return new AndroidResourceConfiguration(path, List.of(), null, pathFeatureFlag);
    }

    List<String> qualifiers = new ArrayList<>();
    String locale = null;
    boolean bcp47 = false;
    int previousRank = -1;
    for (String qualifier : directory.substring("values-".length()).split("-", -1)) {
      if (qualifier.isEmpty()) {
        throw invalidConfiguration();
      }
      String normalized = qualifier.toLowerCase(Locale.ROOT);
      int rank;
      if (normalized.matches("mcc\\d{3}")) {
        if (unsigned(normalized.substring(3), 65536) == 0) {
          throw invalidConfiguration();
        }
        rank = 0;
      } else if (normalized.matches("mnc\\d{1,3}")) {
        rank = 1;
      } else if (normalized.startsWith("b+")) {
        if (locale != null) {
          throw invalidConfiguration();
        }
        locale = bcp47(qualifier);
        bcp47 = true;
        rank = 2;
      } else if (normalized.matches("r[a-z]{2}") && locale != null && !bcp47) {
        locale += "-" + qualifier.substring(1).toUpperCase(Locale.ROOT);
        rank = 3;
      } else if (GENDER.contains(normalized)) {
        rank = 4;
      } else if (LAYOUT.contains(normalized)) {
        rank = 5;
      } else if (normalized.matches("sw\\d+dp")) {
        rank = 6;
      } else if (normalized.matches("w\\d+dp")) {
        rank = 7;
      } else if (normalized.matches("h\\d+dp")) {
        rank = 8;
      } else if (SIZE.contains(normalized)) {
        rank = 9;
      } else if (ASPECT.contains(normalized)) {
        rank = 10;
      } else if (ROUND.contains(normalized)) {
        rank = 11;
      } else if (COLOR.contains(normalized)) {
        rank = 12;
      } else if (HDR.contains(normalized)) {
        rank = 13;
      } else if (ORIENTATION.contains(normalized)) {
        rank = 14;
      } else if (UI_MODE.contains(normalized)) {
        rank = 15;
      } else if (NIGHT.contains(normalized)) {
        rank = 16;
      } else if (DENSITY.contains(normalized) || normalized.matches("\\d+dpi")) {
        if (!DENSITY.contains(normalized)
            && unsigned(normalized.substring(0, normalized.length() - 3), 1L << 32) == 0) {
          throw invalidConfiguration();
        }
        rank = 17;
      } else if (TOUCH.contains(normalized)) {
        rank = 18;
      } else if (KEYBOARD_VISIBILITY.contains(normalized)) {
        rank = 19;
      } else if (KEYBOARD.contains(normalized)) {
        rank = 20;
      } else if (NAVIGATION_VISIBILITY.contains(normalized)) {
        rank = 21;
      } else if (NAVIGATION.contains(normalized)) {
        rank = 22;
      } else if (normalized.matches("\\d+x\\d+")) {
        String[] dimensions = normalized.split("x", -1);
        if (unsigned(dimensions[0], 65536) < unsigned(dimensions[1], 65536)) {
          throw invalidConfiguration();
        }
        rank = 23;
      } else if (normalized.matches("v\\d+")) {
        boundedVersion(normalized.substring(1));
        rank = 24;
      } else if (normalized.matches("[a-z]{2,3}")) {
        if (locale != null) {
          throw invalidConfiguration();
        }
        locale = normalized;
        rank = 2;
      } else {
        throw invalidConfiguration();
      }
      if (rank <= previousRank) {
        throw invalidConfiguration();
      }
      previousRank = rank;
      qualifiers.add(qualifier);
    }
    return new AndroidResourceConfiguration(path, List.copyOf(qualifiers), locale, pathFeatureFlag);
  }

  String effectiveKey() {
    StringBuilder normalized = new StringBuilder(locale == null ? "" : locale);
    int explicitVersion = 0;
    int implicitVersion = 0;
    for (String original : qualifiers) {
      String qualifier = original.toLowerCase(Locale.ROOT);
      if (isLocaleQualifier(qualifier)) {
        continue;
      }
      if (qualifier.startsWith("mcc")) {
        qualifier = "mcc" + unsigned(qualifier.substring(3), 65536);
      } else if (qualifier.startsWith("mnc")) {
        int network = (int) unsigned(qualifier.substring(3), 65536);
        qualifier = "mnc" + (network == 0 ? 65535 : network);
      } else if (qualifier.endsWith("dp")) {
        int prefix = qualifier.startsWith("sw") ? 2 : 1;
        int value = (int) unsigned(qualifier.substring(prefix, qualifier.length() - 2), 65536);
        if (value == 0) {
          continue;
        }
        qualifier = qualifier.substring(0, prefix) + value + "dp";
        implicitVersion = Math.max(implicitVersion, 13);
      } else if (DENSITY.contains(qualifier) || qualifier.matches("\\d+dpi")) {
        int density = density(qualifier);
        if (density == 0) {
          continue;
        }
        qualifier = "density" + density;
        implicitVersion = Math.max(implicitVersion, density == 65534 ? 21 : 4);
      } else if (qualifier.matches("\\d+x\\d+")) {
        String[] dimensions = qualifier.split("x", -1);
        long width = unsigned(dimensions[0], 65536);
        long height = unsigned(dimensions[1], 65536);
        if (width == 0 && height == 0) {
          continue;
        }
        qualifier = width + "x" + height;
      } else if (qualifier.matches("v\\d+")) {
        explicitVersion = boundedVersion(qualifier.substring(1));
        continue;
      } else if (GENDER.contains(qualifier)) {
        implicitVersion = Math.max(implicitVersion, 34);
      } else if (COLOR.contains(qualifier)
          || HDR.contains(qualifier)
          || qualifier.equals("vrheadset")) {
        implicitVersion = Math.max(implicitVersion, 26);
      } else if (ROUND.contains(qualifier)) {
        implicitVersion = Math.max(implicitVersion, 23);
      } else if (UI_MODE.contains(qualifier) || NIGHT.contains(qualifier)) {
        implicitVersion = Math.max(implicitVersion, 8);
      } else if (SIZE.contains(qualifier) || ASPECT.contains(qualifier)) {
        implicitVersion = Math.max(implicitVersion, 4);
      }
      normalized.append('|').append(qualifier);
    }
    int version = Math.max(explicitVersion, implicitVersion);
    if (version != 0) {
      normalized.append("|v").append(version);
    }
    return normalized.toString();
  }

  private boolean isLocaleQualifier(String qualifier) {
    if (qualifier.startsWith("b+")) {
      return true;
    }
    if (locale == null) {
      return false;
    }
    return qualifier.equals(locale.split("-", 2)[0])
        || qualifier.startsWith("r")
            && (qualifier.substring(1).equalsIgnoreCase(locale.substring(locale.length() - 2))
                || qualifier.equalsIgnoreCase(locale));
  }

  private static int density(String qualifier) {
    return switch (qualifier) {
      case "ldpi" -> 120;
      case "mdpi" -> 160;
      case "tvdpi" -> 213;
      case "hdpi" -> 240;
      case "xhdpi" -> 320;
      case "xxhdpi" -> 480;
      case "xxxhdpi" -> 640;
      case "anydpi" -> 65534;
      case "nodpi" -> 65535;
      default -> (int) unsigned(qualifier.substring(0, qualifier.length() - 3), 65536);
    };
  }

  private static long unsigned(String digits, long modulus) {
    long result = 0;
    for (int index = 0; index < digits.length(); index++) {
      result = (result * 10 + digits.charAt(index) - '0') % modulus;
    }
    return result;
  }

  private static int boundedVersion(String digits) {
    int result = 0;
    for (int index = 0; index < digits.length(); index++) {
      int digit = digits.charAt(index) - '0';
      if (result > (65535 - digit) / 10) {
        throw invalidConfiguration();
      }
      result = result * 10 + digit;
    }
    return result;
  }

  private static String bcp47(String qualifier) {
    String[] subtags = qualifier.substring(2).split("\\+", -1);
    if (subtags.length == 0 || !subtags[0].matches("[A-Za-z]{2,3}")) {
      throw invalidConfiguration();
    }
    StringBuilder locale = new StringBuilder(subtags[0].toLowerCase(Locale.ROOT));
    for (int index = 1; index < subtags.length; index++) {
      String value = subtags[index];
      if (!value.matches("[A-Za-z0-9]{2,8}")) {
        throw invalidConfiguration();
      }
      if (value.length() == 4 && value.chars().allMatch(Character::isLetter)) {
        value =
            value.substring(0, 1).toUpperCase(Locale.ROOT)
                + value.substring(1).toLowerCase(Locale.ROOT);
      } else if (value.length() == 2 && value.chars().allMatch(Character::isLetter)) {
        value = value.toUpperCase(Locale.ROOT);
      } else {
        value = value.toLowerCase(Locale.ROOT);
      }
      locale.append('-').append(value);
    }
    return locale.toString();
  }

  private static LocalizationParseException invalidPath() {
    return new LocalizationParseException(
        "INVALID_ANDROID_RESOURCE_PATH", "Invalid Android resource path");
  }

  private static LocalizationParseException invalidConfiguration() {
    return new LocalizationParseException(
        "INVALID_ANDROID_CONFIGURATION", "Invalid Android resource directory configuration");
  }
}
