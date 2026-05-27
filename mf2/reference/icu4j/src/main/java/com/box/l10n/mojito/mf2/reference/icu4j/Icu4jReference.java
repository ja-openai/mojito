package com.box.l10n.mojito.mf2.reference.icu4j;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ibm.icu.message2.MessageFormatter;
import com.ibm.icu.text.PluralRules;
import com.ibm.icu.util.ULocale;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

public final class Icu4jReference {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static volatile int sink;

  public static void main(String[] args) throws Exception {
    String command = args.length > 0 ? args[0] : "compare";
    Path fixtureDirectory =
        Path.of(args.length > 1 ? args[1] : "../../conformance/fixtures/source-to-model");

    switch (command) {
      case "compare" -> compare(fixtureDirectory);
      case "plural-categories" -> pluralCategories(fixtureDirectory);
      case "bench" -> {
        int iterations = args.length > 2 ? Integer.parseInt(args[2]) : 100_000;
        int warmupIterations = args.length > 3 ? Integer.parseInt(args[3]) : 10_000;
        bench(fixtureDirectory, iterations, warmupIterations);
      }
      default -> {
        System.err.println(
            "Usage: Icu4jReference [compare|bench|plural-categories] [fixture-dir-or-generated-plural-json] [iterations] [warmup-iterations]");
        System.exit(2);
      }
    }
  }

  private static void pluralCategories(Path generatedPluralRulesPath) throws IOException {
    JsonNode generated = OBJECT_MAPPER.readTree(generatedPluralRulesPath.toFile());
    for (String pluralTypeName : List.of("cardinal", "ordinal")) {
      JsonNode locales = generated.path(pluralTypeName).path("locales");
      JsonNode parents = generated.path(pluralTypeName).path("parents");
      Set<String> localeIdSet = new LinkedHashSet<>();
      locales.fieldNames().forEachRemaining(localeIdSet::add);
      parents.fieldNames().forEachRemaining(localeIdSet::add);
      addPluralProbeLocales(localeIdSet);
      List<String> localeIds = new ArrayList<>(localeIdSet);
      localeIds.sort(String::compareTo);

      PluralRules.PluralType pluralType =
          pluralTypeName.equals("cardinal")
              ? PluralRules.PluralType.CARDINAL
              : PluralRules.PluralType.ORDINAL;

      for (String localeId : localeIds) {
        PluralRules rules =
            PluralRules.forLocale(
                ULocale.forLanguageTag(localeId.replace('_', '-')), pluralType);
        for (String sample : pluralSamples()) {
          Map<String, String> row = new LinkedHashMap<>();
          row.put("type", pluralTypeName);
          row.put("locale", localeId);
          row.put("sample", sample);
          row.put("category", selectPluralSample(rules, sample));
          System.out.println(OBJECT_MAPPER.writeValueAsString(row));
        }
      }
    }
  }

  private static void addPluralProbeLocales(Set<String> localeIds) {
    if (localeIds.contains("az")) {
      localeIds.add("az-Arab");
    }
    if (localeIds.contains("en")) {
      localeIds.add("en-US-u-nu-latn");
    }
    if (localeIds.contains("pt")) {
      localeIds.add("pt-AO");
      localeIds.add("pt-PT-u-nu-latn");
    }
    if (localeIds.contains("sr")) {
      localeIds.add("sr-Latn");
    }
    if (localeIds.contains("zh")) {
      localeIds.add("zh-Hant-TW");
    }
  }

  private static String selectPluralSample(PluralRules rules, String sample) {
    String normalized = sample.trim();
    String unsigned = normalized.replaceFirst("^[+-]", "");
    String fraction = "";
    int decimalIndex = unsigned.indexOf('.');
    if (decimalIndex >= 0) {
      fraction = unsigned.substring(decimalIndex + 1);
    }
    BigDecimal decimal = new BigDecimal(normalized).abs();
    long fractionDigits = fraction.isEmpty() ? 0 : Long.parseLong(fraction);
    return rules.select(decimal.doubleValue(), fraction.length(), fractionDigits);
  }

  private static List<String> pluralSamples() {
    Set<String> samples = new LinkedHashSet<>();
    samples.add("-2");
    samples.add("-1");
    for (int value = 0; value <= 250; value++) {
      samples.add(Integer.toString(value));
    }
    for (String value :
        List.of(
            "1000",
            "1001",
            "1002",
            "10000",
            "10001",
            "100000",
            "100001",
            "1000000",
            "1000001",
            "2000000")) {
      samples.add(value);
    }
    for (String value :
        List.of(
            "0.0",
            "0.1",
            "0.2",
            "0.5",
            "1.0",
            "1.00",
            "1.01",
            "1.1",
            "1.2",
            "1.5",
            "2.0",
            "2.00",
            "2.01",
            "2.1",
            "2.2",
            "3.0",
            "3.1",
            "4.0",
            "5.0",
            "10.0",
            "11.0",
            "12.0",
            "14.0",
            "21.0",
            "22.0",
            "100.0",
            "101.0",
            "1000.0",
            "-1.5")) {
      samples.add(value);
    }
    return List.copyOf(samples);
  }

  private static void compare(Path fixtureDirectory) throws IOException {
    List<ReferenceCase> cases = loadCases(fixtureDirectory);
    int passed = 0;
    int failed = 0;
    int unsupported = 0;

    for (ReferenceCase referenceCase : cases) {
      if (referenceCase.unsupportedReason != null) {
        unsupported++;
        System.out.printf(
            "UNSUPPORTED %s: %s%n", referenceCase.name, referenceCase.unsupportedReason);
        continue;
      }

      try {
        String actual = referenceCase.formatter.formatToString(referenceCase.arguments);
        if (actual.equals(referenceCase.expected)) {
          passed++;
        } else {
          failed++;
          System.out.printf(
              "MISMATCH %s:%n  expected: %s%n  actual:   %s%n",
              referenceCase.name, referenceCase.expected, actual);
        }
      } catch (RuntimeException e) {
        unsupported++;
        System.out.printf("UNSUPPORTED %s: %s%n", referenceCase.name, e.getMessage());
      }
    }

    System.out.printf(
        "icu4j compare total=%d passed=%d failed=%d unsupported=%d%n",
        cases.size(), passed, failed, unsupported);

    if (failed > 0) {
      System.exit(1);
    }
  }

  private static void bench(Path fixtureDirectory, int iterations, int warmupIterations)
      throws IOException {
    List<ReferenceCase> cases =
        loadCases(fixtureDirectory).stream()
            .filter(referenceCase -> referenceCase.unsupportedReason == null)
            .toList();
    if (cases.isEmpty()) {
      System.err.println("No supported ICU4J format cases found.");
      System.exit(2);
    }

    for (int index = 0; index < warmupIterations; index++) {
      ReferenceCase referenceCase = cases.get(index % cases.size());
      sink = referenceCase.formatter.formatToString(referenceCase.arguments).length();
    }

    long bytes = 0;
    long started = System.nanoTime();
    for (int index = 0; index < iterations; index++) {
      ReferenceCase referenceCase = cases.get(index % cases.size());
      String output = referenceCase.formatter.formatToString(referenceCase.arguments);
      bytes += output.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }
    long elapsed = System.nanoTime() - started;
    double seconds = elapsed / 1_000_000_000.0;
    sink = (int) bytes;

    System.out.printf(
        Locale.ROOT,
        "icu4j format iterations=%d warmup=%d cases=%d seconds=%.6f ops_per_second=%.0f bytes=%d%n",
        iterations,
        warmupIterations,
        cases.size(),
        seconds,
        iterations / seconds,
        bytes);
  }

  private static List<ReferenceCase> loadCases(Path fixtureDirectory) throws IOException {
    List<ReferenceCase> cases = new ArrayList<>();
    try (Stream<Path> paths = Files.list(fixtureDirectory)) {
      List<Path> fixturePaths =
          paths
              .filter(path -> path.getFileName().toString().endsWith(".json"))
              .sorted(Comparator.comparing(path -> path.getFileName().toString()))
              .toList();

      for (Path fixturePath : fixturePaths) {
        JsonNode fixture = OBJECT_MAPPER.readTree(fixturePath.toFile());
        String source = fixture.path("source").asText();
        for (JsonNode formatCase : fixture.path("formatCases")) {
          String locale = formatCase.path("locale").asText("en");
          String name = fixture.path("name").asText(fixturePath.getFileName().toString());
          Map<String, Object> arguments = toMap(formatCase.path("arguments"));
          String expected = formatCase.path("expected").asText();
          String bidiIsolation = formatCase.path("bidiIsolation").asText("none");
          cases.add(buildCase(
              name + "[" + locale + "]", source, locale, bidiIsolation, arguments, expected));
        }
      }
    }
    return cases;
  }

  private static ReferenceCase buildCase(
      String name,
      String source,
      String locale,
      String bidiIsolation,
      Map<String, Object> arguments,
      String expected) {
    try {
      MessageFormatter formatter =
          MessageFormatter.builder()
              .setLocale(Locale.forLanguageTag(locale.replace('_', '-')))
              .setBidiIsolation(toBidiIsolation(bidiIsolation))
              .setErrorHandlingBehavior(MessageFormatter.ErrorHandlingBehavior.STRICT)
              .setPattern(source)
              .build();
      formatter.formatToString(arguments);
      return new ReferenceCase(name, formatter, arguments, expected, null);
    } catch (RuntimeException e) {
      return new ReferenceCase(name, null, arguments, expected, e.getMessage());
    }
  }

  private static MessageFormatter.BidiIsolation toBidiIsolation(String value) {
    return "default".equals(value)
        ? MessageFormatter.BidiIsolation.DEFAULT
        : MessageFormatter.BidiIsolation.NONE;
  }

  private static Map<String, Object> toMap(JsonNode node) {
    Map<String, Object> result = new LinkedHashMap<>();
    if (!node.isObject()) {
      return result;
    }
    Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
    while (fields.hasNext()) {
      Map.Entry<String, JsonNode> field = fields.next();
      result.put(field.getKey(), toObject(field.getValue()));
    }
    return result;
  }

  private static Object toObject(JsonNode node) {
    if (node.isTextual()) {
      return node.asText();
    }
    if (node.isIntegralNumber()) {
      return node.canConvertToInt() ? node.asInt() : node.asLong();
    }
    if (node.isFloatingPointNumber()) {
      return node.asDouble();
    }
    if (node.isBoolean()) {
      return node.asBoolean();
    }
    if (node.isNull()) {
      return null;
    }
    if (node.isArray()) {
      List<Object> values = new ArrayList<>();
      for (JsonNode item : node) {
        values.add(toObject(item));
      }
      return values;
    }
    if (node.isObject()) {
      return toMap(node);
    }
    return node.asText();
  }

  private record ReferenceCase(
      String name,
      MessageFormatter formatter,
      Map<String, Object> arguments,
      String expected,
      String unsupportedReason) {}
}
