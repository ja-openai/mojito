package com.box.l10n.mojito.translationintegrity.formatjs;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Validation AST derived from the public element model in {@code
 * @formatjs/icu-messageformat-parser} 3.5.10.
 */
public sealed interface FormatJsElement
    permits FormatJsElement.Literal,
        FormatJsElement.Argument,
        FormatJsElement.NumberArgument,
        FormatJsElement.DateArgument,
        FormatJsElement.TimeArgument,
        FormatJsElement.SelectArgument,
        FormatJsElement.PluralArgument,
        FormatJsElement.Pound,
        FormatJsElement.Tag {

  Type type();

  FormatJsSourceLocation location();

  enum Type {
    LITERAL(0),
    ARGUMENT(1),
    NUMBER(2),
    DATE(3),
    TIME(4),
    SELECT(5),
    PLURAL(6),
    POUND(7),
    TAG(8);

    private final int code;

    Type(int code) {
      this.code = code;
    }

    public int code() {
      return code;
    }
  }

  record Literal(String value, FormatJsSourceLocation location) implements FormatJsElement {

    public Literal {
      Objects.requireNonNull(value, "value");
    }

    @Override
    public Type type() {
      return Type.LITERAL;
    }
  }

  record Argument(String value, FormatJsSourceLocation location) implements FormatJsElement {

    public Argument {
      Objects.requireNonNull(value, "value");
    }

    @Override
    public Type type() {
      return Type.ARGUMENT;
    }
  }

  record NumberArgument(String value, FormatJsStyle style, FormatJsSourceLocation location)
      implements FormatJsElement {

    public NumberArgument {
      Objects.requireNonNull(value, "value");
    }

    @Override
    public Type type() {
      return Type.NUMBER;
    }
  }

  record DateArgument(String value, FormatJsStyle style, FormatJsSourceLocation location)
      implements FormatJsElement {

    public DateArgument {
      Objects.requireNonNull(value, "value");
    }

    @Override
    public Type type() {
      return Type.DATE;
    }
  }

  record TimeArgument(String value, FormatJsStyle style, FormatJsSourceLocation location)
      implements FormatJsElement {

    public TimeArgument {
      Objects.requireNonNull(value, "value");
    }

    @Override
    public Type type() {
      return Type.TIME;
    }
  }

  record SelectArgument(String value, Map<String, Option> options, FormatJsSourceLocation location)
      implements FormatJsElement {

    public SelectArgument {
      Objects.requireNonNull(value, "value");
      options = immutableMap(options);
    }

    @Override
    public Type type() {
      return Type.SELECT;
    }
  }

  record PluralArgument(
      String value,
      Map<String, Option> options,
      double offset,
      PluralType pluralType,
      FormatJsSourceLocation location)
      implements FormatJsElement {

    public PluralArgument {
      Objects.requireNonNull(value, "value");
      options = immutableMap(options);
      Objects.requireNonNull(pluralType, "pluralType");
    }

    @Override
    public Type type() {
      return Type.PLURAL;
    }
  }

  record Pound(FormatJsSourceLocation location) implements FormatJsElement {

    @Override
    public Type type() {
      return Type.POUND;
    }
  }

  record Tag(String value, List<FormatJsElement> children, FormatJsSourceLocation location)
      implements FormatJsElement {

    public Tag {
      Objects.requireNonNull(value, "value");
      children = List.copyOf(children);
    }

    @Override
    public Type type() {
      return Type.TAG;
    }
  }

  record Option(List<FormatJsElement> value, FormatJsSourceLocation location) {

    public Option {
      value = List.copyOf(value);
    }
  }

  enum PluralType {
    CARDINAL,
    ORDINAL
  }

  sealed interface FormatJsStyle permits NamedStyle, NumberSkeleton, DateTimeSkeleton {}

  record NamedStyle(String value) implements FormatJsStyle {

    public NamedStyle {
      Objects.requireNonNull(value, "value");
    }
  }

  record NumberSkeletonToken(String stem, List<String> options) {

    public NumberSkeletonToken {
      Objects.requireNonNull(stem, "stem");
      options = List.copyOf(options);
    }
  }

  record NumberSkeleton(
      List<NumberSkeletonToken> tokens,
      Map<String, Object> parsedOptions,
      FormatJsSourceLocation location)
      implements FormatJsStyle {

    public NumberSkeleton {
      tokens = List.copyOf(tokens);
      parsedOptions = immutableMap(parsedOptions);
    }
  }

  record DateTimeSkeleton(
      String pattern, Map<String, Object> parsedOptions, FormatJsSourceLocation location)
      implements FormatJsStyle {

    public DateTimeSkeleton {
      Objects.requireNonNull(pattern, "pattern");
      parsedOptions = immutableMap(parsedOptions);
    }
  }

  private static <T> Map<String, T> immutableMap(Map<String, T> source) {
    Objects.requireNonNull(source, "source");
    return Collections.unmodifiableMap(new LinkedHashMap<>(source));
  }
}
