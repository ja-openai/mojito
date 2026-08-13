package com.box.l10n.mojito.fileformat;

import java.util.Set;

/** Native AAPT2 reference grammar shared by Android resource parsing and regeneration. */
final class AndroidResourceReferences {

  private static final Set<String> TYPES =
      Set.of(
          "anim",
          "animator",
          "array",
          "attr",
          "^attr-private",
          "bool",
          "color",
          "configVarying",
          "dimen",
          "drawable",
          "font",
          "fraction",
          "id",
          "integer",
          "interpolator",
          "layout",
          "macro",
          "menu",
          "mipmap",
          "navigation",
          "plurals",
          "raw",
          "string",
          "style",
          "styleable",
          "transition",
          "xml");

  private AndroidResourceReferences() {}

  static boolean isResourceType(String type) {
    return TYPES.contains(type);
  }

  static boolean matches(String source) {
    String value = normalize(source);
    if ("@null".equals(value) || "@empty".equals(value)) {
      return true;
    }
    if (value.startsWith("@")) {
      return resource(value.substring(1));
    }
    return value.startsWith("?") && attribute(value.substring(1));
  }

  static String normalize(String source) {
    String value = source.trim();
    if (value.startsWith("@@") && !value.startsWith("@@+") && resource(value.substring(2))) {
      return value.substring(1);
    }
    return value;
  }

  private static boolean resource(String value) {
    boolean create = value.startsWith("+");
    if (create) {
      value = value.substring(1);
    }
    boolean restricted = value.startsWith("*");
    if (restricted) {
      value = value.substring(1);
    }
    if (create && restricted) {
      return false;
    }
    int slash = value.indexOf('/');
    if (slash <= 0 || slash == value.length() - 1) {
      return false;
    }
    String qualifiedType = value.substring(0, slash);
    int packageSeparator = qualifiedType.indexOf(':');
    if (packageSeparator == 0 || packageSeparator == qualifiedType.length() - 1) {
      return false;
    }
    String type =
        packageSeparator < 0 ? qualifiedType : qualifiedType.substring(packageSeparator + 1);
    return TYPES.contains(type) && (!create || "id".equals(type));
  }

  private static boolean attribute(String value) {
    if (value.startsWith("*")) {
      value = value.substring(1);
    }
    int slash = value.indexOf('/');
    if (slash < 0) {
      int packageSeparator = value.indexOf(':');
      return !value.isEmpty() && packageSeparator != 0 && packageSeparator != value.length() - 1;
    }
    if (slash == value.length() - 1) {
      return false;
    }
    String qualifiedType = value.substring(0, slash);
    int packageSeparator = qualifiedType.indexOf(':');
    if (packageSeparator == 0 || packageSeparator == qualifiedType.length() - 1) {
      return false;
    }
    String type =
        packageSeparator < 0 ? qualifiedType : qualifiedType.substring(packageSeparator + 1);
    return "attr".equals(type);
  }
}
