# Java Runtime Prototype Plan

The executable Java implementation should live in Mojito `common` or a future
standalone grammar module, not as an ad hoc docs script. Mojito already has
Jackson dependencies available in `common`, so the Java version should use the
same fixture JSON files and mirror the JavaScript/Python conformance behavior.

## Proposed Package

```text
common/src/main/java/com/box/l10n/mojito/grammar/
  GrammarFormatter.java
  GrammarResourceResolver.java
  ResourceStore.java
  TermValue.java
  PersonValue.java
  Morphology.java
  DiagnosticError.java

common/src/main/java/com/box/l10n/mojito/grammar/adapter/
  LocaleGrammarAdapter.java
  FrenchGrammarAdapter.java
  GermanGrammarAdapter.java
  RussianGrammarAdapter.java
  ArabicGrammarAdapter.java
  SwahiliGrammarAdapter.java
  JapaneseGrammarAdapter.java
  KoreanGrammarAdapter.java
  WelshGrammarAdapter.java

common/src/test/java/com/box/l10n/mojito/grammar/
  GrammarFixtureTest.java
```

## Java API

```java
public interface ResourceStore {
  Optional<String> getString(String key);
  Optional<JsonNode> getJson(String key);
  Optional<byte[]> getBytes(String key);
}

public interface GrammarResourceResolver {
  MessageValue message(String messageId, Locale locale);
  TermValue term(String termId, Locale locale);
  PersonValue person(String personId, Locale locale);
  LocaleGrammarProfile profile(Locale locale);
}

public interface GrammarFormatter {
  String format(String messageId, Map<String, Object> args);
}
```

## Fixture Test

The Java test should load:

```text
dev-docs/experiments/mf2-grammar-agreement/fixtures/**/*.json
```

and assert:

- every positive `examples[]` output matches;
- every negative `examples[].error` emits the expected diagnostic code/fields;
- the Java diagnostics match the JS/Python diagnostic shape.

## Why Not A Standalone Java Script Here?

Java has no built-in JSON parser in current JDKs. The correct implementation
should use Mojito's existing Jackson dependencies and be added as real testable
Java code once we move from exploration docs into module work. A throwaway Java
file with a hand-rolled JSON parser would test the wrong thing.
