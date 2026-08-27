package com.box.l10n.mojito.translationintegrity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** A normalized, runtime-independent translation-integrity finding. */
public record TranslationIntegrityDiagnostic(
    String code,
    Severity severity,
    Subject subject,
    Map<String, Object> details,
    TranslationIntegrityRange range) {

  private static final Comparator<String> CODE_POINT_ORDER =
      TranslationIntegrityDiagnostic::compareByCodePoint;

  public static final Comparator<TranslationIntegrityDiagnostic> CANONICAL_ORDER =
      Comparator.comparing(TranslationIntegrityDiagnostic::code, CODE_POINT_ORDER)
          .thenComparing(diagnostic -> diagnostic.subject().wireValue(), CODE_POINT_ORDER)
          .thenComparing(diagnostic -> diagnostic.severity().wireValue(), CODE_POINT_ORDER)
          .thenComparing(diagnostic -> canonicalJson(diagnostic.details()), CODE_POINT_ORDER)
          .thenComparing(
              diagnostic -> canonicalJson(diagnostic.range()),
              Comparator.nullsFirst(CODE_POINT_ORDER));

  public TranslationIntegrityDiagnostic {
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(severity, "severity");
    Objects.requireNonNull(subject, "subject");
    details = immutableSortedMap(details);
  }

  public static TranslationIntegrityDiagnostic sourceError(
      String code, Map<String, Object> details) {
    return sourceError(code, details, null);
  }

  public static TranslationIntegrityDiagnostic sourceError(
      String code, Map<String, Object> details, TranslationIntegrityRange range) {
    return new TranslationIntegrityDiagnostic(code, Severity.ERROR, Subject.SOURCE, details, range);
  }

  public static TranslationIntegrityDiagnostic targetError(
      String code, Map<String, Object> details) {
    return targetError(code, details, null);
  }

  public static TranslationIntegrityDiagnostic targetError(
      String code, Map<String, Object> details, TranslationIntegrityRange range) {
    return new TranslationIntegrityDiagnostic(code, Severity.ERROR, Subject.TARGET, details, range);
  }

  public enum Severity {
    ERROR("error"),
    WARNING("warning"),
    INFO("info");

    private final String wireValue;

    Severity(String wireValue) {
      this.wireValue = wireValue;
    }

    public String wireValue() {
      return wireValue;
    }

    public static Severity fromWireValue(String value) {
      return switch (value) {
        case "error" -> ERROR;
        case "warning" -> WARNING;
        case "info" -> INFO;
        default -> throw new IllegalArgumentException("Unknown diagnostic severity: " + value);
      };
    }
  }

  public enum Subject {
    SOURCE("source"),
    TARGET("target"),
    POLICY("policy");

    private final String wireValue;

    Subject(String wireValue) {
      this.wireValue = wireValue;
    }

    public String wireValue() {
      return wireValue;
    }

    public static Subject fromWireValue(String value) {
      return switch (value) {
        case "source" -> SOURCE;
        case "target" -> TARGET;
        case "policy" -> POLICY;
        default -> throw new IllegalArgumentException("Unknown diagnostic subject: " + value);
      };
    }
  }

  private static Map<String, Object> immutableSortedMap(Map<String, ?> source) {
    Objects.requireNonNull(source, "details");
    Map<String, Object> result = new TreeMap<>(CODE_POINT_ORDER);
    source.forEach((key, value) -> result.put(key, immutableValue(value)));
    return Collections.unmodifiableMap(result);
  }

  private static Object immutableValue(Object value) {
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> result = new TreeMap<>(CODE_POINT_ORDER);
      map.forEach(
          (key, nestedValue) -> result.put(Objects.toString(key), immutableValue(nestedValue)));
      return Collections.unmodifiableMap(result);
    }
    if (value instanceof Collection<?> collection) {
      List<Object> result = new ArrayList<>(collection.size());
      collection.forEach(item -> result.add(immutableValue(item)));
      return List.copyOf(result);
    }
    return value;
  }

  private static String canonicalJson(Object value) {
    StringBuilder result = new StringBuilder();
    appendCanonicalJson(result, value);
    return result.toString();
  }

  private static void appendCanonicalJson(StringBuilder result, Object value) {
    if (value == null) {
      result.append("null");
    } else if (value instanceof String string) {
      appendJsonString(result, string);
    } else if (value instanceof Boolean || value instanceof Number) {
      result.append(value);
    } else if (value instanceof TranslationIntegrityRange integrityRange) {
      result
          .append("{\"end\":")
          .append(integrityRange.end())
          .append(",\"start\":")
          .append(integrityRange.start())
          .append('}');
    } else if (value instanceof Map<?, ?> map) {
      result.append('{');
      boolean first = true;
      for (Map.Entry<String, Object> entry : stringKeyedMap(map).entrySet()) {
        if (!first) {
          result.append(',');
        }
        appendJsonString(result, entry.getKey());
        result.append(':');
        appendCanonicalJson(result, entry.getValue());
        first = false;
      }
      result.append('}');
    } else if (value instanceof Collection<?> collection) {
      result.append('[');
      boolean first = true;
      for (Object item : collection) {
        if (!first) {
          result.append(',');
        }
        appendCanonicalJson(result, item);
        first = false;
      }
      result.append(']');
    } else {
      throw new IllegalArgumentException(
          "Unsupported canonical diagnostic value: " + value.getClass().getName());
    }
  }

  private static Map<String, Object> stringKeyedMap(Map<?, ?> map) {
    Map<String, Object> result = new TreeMap<>(CODE_POINT_ORDER);
    map.forEach(
        (key, value) -> {
          if (!(key instanceof String stringKey)) {
            throw new IllegalArgumentException("Diagnostic detail keys must be strings");
          }
          result.put(stringKey, value);
        });
    return result;
  }

  private static int compareByCodePoint(String left, String right) {
    int leftIndex = 0;
    int rightIndex = 0;
    while (leftIndex < left.length() && rightIndex < right.length()) {
      int leftCodePoint = left.codePointAt(leftIndex);
      int rightCodePoint = right.codePointAt(rightIndex);
      int comparison = Integer.compare(leftCodePoint, rightCodePoint);
      if (comparison != 0) {
        return comparison;
      }
      leftIndex += Character.charCount(leftCodePoint);
      rightIndex += Character.charCount(rightCodePoint);
    }
    return Integer.compare(left.length() - leftIndex, right.length() - rightIndex);
  }

  private static void appendJsonString(StringBuilder result, String value) {
    result.append('"');
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      switch (character) {
        case '"' -> result.append("\\\"");
        case '\\' -> result.append("\\\\");
        case '\b' -> result.append("\\b");
        case '\f' -> result.append("\\f");
        case '\n' -> result.append("\\n");
        case '\r' -> result.append("\\r");
        case '\t' -> result.append("\\t");
        default -> {
          if (character < 0x20) {
            result.append(String.format("\\u%04x", (int) character));
          } else {
            result.append(character);
          }
        }
      }
    }
    result.append('"');
  }
}
