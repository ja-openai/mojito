package com.box.l10n.mojito.mf2.inflection;

import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.optionalText;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.requireSha256Hex;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.requiredArray;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.requiredBoolean;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.requiredInt;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.requiredLong;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.requiredObject;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.requiredObjectRoot;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.requiredText;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.textArray;

import com.box.l10n.mojito.json.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Loads the generated Hindi pronoun agreement table used by the Hindi renderer extension.
 *
 * <p>Most locales render from {@link CompiledTermPack} alone. Hindi currently needs this auxiliary
 * closed-world table for pronoun forms that agree with a referenced term argument.
 */
public class HindiPronounAgreementPackJsonLoader {

  static final String EXPECTED_SCHEMA = "mojito-mf2-inflection/hi-pronoun-agreement-pack/v0";
  static final String EXPECTED_LOCALE = "hi";
  static final String EXPECTED_PACK_SHAPE = "dependency-pronoun-agreement-rows-v0";

  private static final int PRONOUN_ROW_BYTES = 8;
  private static final Set<String> PERSON_VALUES = Set.of("first", "second", "third");
  private static final Set<String> ROW_NUMBER_VALUES = Set.of("any", "plural", "singular");
  private static final Set<String> REQUEST_NUMBER_VALUES = Set.of("plural", "singular");
  private static final Set<String> CASE_VALUES =
      Set.of("accusative", "direct", "ergative", "genitive");
  private static final Set<String> REGISTER_VALUES = Set.of("formal", "informal", "intimate");
  private static final Set<String> GENDER_VALUES = Set.of("feminine", "masculine");

  private final ObjectMapper objectMapper;

  public HindiPronounAgreementPackJsonLoader() {
    this(new ObjectMapper());
  }

  public HindiPronounAgreementPackJsonLoader(ObjectMapper objectMapper) {
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
  }

  public HindiPronounAgreementPack load(String json) {
    Objects.requireNonNull(json, "json");
    return load(objectMapper.readTreeUnchecked(json));
  }

  public HindiPronounAgreementPack load(JsonNode root) {
    Objects.requireNonNull(root, "root");
    requiredObjectRoot(root, "Hindi pronoun agreement pack");
    HindiPronounAgreementPack pack =
        new HindiPronounAgreementPack(
            requiredText(root, "schema"),
            requiredText(root, "locale"),
            requiredText(root, "packShape"),
            loadProvenance(requiredObject(root, "provenance")),
            loadSummary(requiredObject(root, "summary")),
            loadRows(requiredArray(root, "rows")));

    validateProvenance(pack.provenance());
    validateSummary(pack.summary(), pack.rows());
    validateUniqueSelectors(pack.rows());
    return pack;
  }

  private Provenance loadProvenance(JsonNode node) {
    return new Provenance(
        requiredText(node, "license"),
        requiredText(node, "generator"),
        textArray(requiredArray(node, "sourceLabels"), "sourceLabels", null, "Hindi pronoun"),
        loadSources(requiredArray(node, "sources")));
  }

  private List<Source> loadSources(JsonNode node) {
    List<Source> sources = new ArrayList<>();
    for (JsonNode sourceNode : node) {
      sources.add(
          new Source(
              requiredText(sourceNode, "path"),
              requiredLong(sourceNode, "byteSize"),
              requiredText(sourceNode, "sha256"),
              requiredBoolean(sourceNode, "gitLfsPointer")));
    }
    return List.copyOf(sources);
  }

  private Summary loadSummary(JsonNode node) {
    return new Summary(
        loadBinaryLowerBoundBytes(requiredObject(node, "binaryLowerBoundBytes")),
        requiredInt(node, "dependencyRows"),
        requiredInt(node, "genitiveRows"),
        requiredInt(node, "invariantNumberRows"),
        requiredInt(node, "rows"),
        requiredInt(node, "uniqueValues"));
  }

  private BinaryLowerBoundBytes loadBinaryLowerBoundBytes(JsonNode node) {
    return new BinaryLowerBoundBytes(
        requiredInt(node, "rowBytes"),
        requiredInt(node, "stringPoolBytes"),
        requiredInt(node, "totalBytes"));
  }

  private List<Row> loadRows(JsonNode node) {
    List<Row> rows = new ArrayList<>();
    for (JsonNode rowNode : node) {
      rows.add(
          new Row(
              requiredText(rowNode, "value"),
              requiredPositiveInt(rowNode, "line"),
              requiredText(rowNode, "person"),
              requiredText(rowNode, "number"),
              requiredText(rowNode, "case"),
              optionalText(rowNode, "register"),
              optionalText(rowNode, "dependencyGender"),
              optionalText(rowNode, "dependencyNumber")));
    }
    return List.copyOf(rows);
  }

  private void validateProvenance(Provenance provenance) {
    if (provenance.sources().isEmpty()) {
      throw new IllegalArgumentException("Hindi pronoun pack must include sources");
    }
    if (provenance.sourceLabels().size() != provenance.sources().size()) {
      throw new IllegalArgumentException("Hindi pronoun source label count must match sources");
    }
    List<String> sourcePaths = provenance.sources().stream().map(Source::path).toList();
    if (!sourcePaths.equals(provenance.sourceLabels())) {
      throw new IllegalArgumentException("Hindi pronoun source labels must match source paths");
    }
  }

  private void validateSummary(Summary summary, List<Row> rows) {
    if (summary.rows() != rows.size()) {
      throw new IllegalArgumentException("Hindi pronoun summary row count mismatch");
    }
    if (summary.uniqueValues() != rows.stream().map(Row::value).distinct().count()) {
      throw new IllegalArgumentException("Hindi pronoun unique value count mismatch");
    }
    if (summary.genitiveRows()
        != rows.stream().filter(row -> "genitive".equals(row.grammaticalCase())).count()) {
      throw new IllegalArgumentException("Hindi pronoun genitive row count mismatch");
    }
    if (summary.dependencyRows() != rows.stream().filter(Row::hasDependency).count()) {
      throw new IllegalArgumentException("Hindi pronoun dependency row count mismatch");
    }
    if (summary.invariantNumberRows()
        != rows.stream().filter(row -> "any".equals(row.number())).count()) {
      throw new IllegalArgumentException("Hindi pronoun invariant number row count mismatch");
    }

    BinaryLowerBoundBytes estimate = summary.binaryLowerBoundBytes();
    if (estimate.rowBytes() != rows.size() * PRONOUN_ROW_BYTES) {
      throw new IllegalArgumentException("Hindi pronoun row byte estimate is incoherent");
    }
    int stringPoolBytes =
        rows.stream()
            .map(Row::value)
            .distinct()
            .mapToInt(value -> value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 1)
            .sum();
    if (estimate.stringPoolBytes() != stringPoolBytes) {
      throw new IllegalArgumentException("Hindi pronoun string-pool byte estimate is incoherent");
    }
    if (estimate.totalBytes() != estimate.stringPoolBytes() + estimate.rowBytes()) {
      throw new IllegalArgumentException("Hindi pronoun binary lower-bound estimate is incoherent");
    }
  }

  private void validateUniqueSelectors(List<Row> rows) {
    Set<String> selectors = new HashSet<>();
    for (Row row : rows) {
      for (String number : row.expandedNumbers()) {
        String selector =
            selectorKey(
                row.person(),
                number,
                row.grammaticalCase(),
                row.register(),
                row.dependencyGender(),
                row.dependencyNumber());
        if (!selectors.add(selector)) {
          throw new IllegalArgumentException("Ambiguous Hindi pronoun selector: " + selector);
        }
      }
    }
  }

  private int requiredPositiveInt(JsonNode node, String field) {
    int value = requiredInt(node, field);
    if (value <= 0) {
      throw new IllegalArgumentException("Expected positive int field: " + field);
    }
    return value;
  }

  private static String selectorKey(
      String person,
      String number,
      String grammaticalCase,
      String register,
      String dependencyGender,
      String dependencyNumber) {
    return person
        + "."
        + number
        + "."
        + grammaticalCase
        + "."
        + nullToDash(register)
        + "."
        + nullToDash(dependencyGender)
        + "."
        + nullToDash(dependencyNumber);
  }

  private static String nullToDash(String value) {
    return value == null ? "-" : value;
  }

  public record HindiPronounAgreementPack(
      String schema,
      String locale,
      String packShape,
      Provenance provenance,
      Summary summary,
      List<Row> rows) {

    public HindiPronounAgreementPack {
      schema = requireExpected(schema, "schema", EXPECTED_SCHEMA);
      locale = requireExpected(locale, "locale", EXPECTED_LOCALE);
      packShape = requireExpected(packShape, "packShape", EXPECTED_PACK_SHAPE);
      provenance = Objects.requireNonNull(provenance, "provenance");
      summary = Objects.requireNonNull(summary, "summary");
      rows = List.copyOf(Objects.requireNonNull(rows, "rows"));
    }

    public String renderPronoun(Request request) {
      Objects.requireNonNull(request, "request");
      List<Row> matches = rows.stream().filter(row -> row.matches(request)).toList();
      if (matches.isEmpty()) {
        throw new IllegalArgumentException("Missing Hindi pronoun form: " + request.selector());
      }
      if (matches.size() > 1) {
        throw new IllegalArgumentException("Ambiguous Hindi pronoun form: " + request.selector());
      }
      return matches.get(0).value();
    }
  }

  public record Source(String path, long byteSize, String sha256, boolean gitLfsPointer) {

    public Source {
      path = requireText(path, "path");
      sha256 = requireSha256Hex(sha256, "sha256");
      if (byteSize < 0) {
        throw new IllegalArgumentException("byteSize must be non-negative");
      }
    }
  }

  public record Provenance(
      String license, String generator, List<String> sourceLabels, List<Source> sources) {

    public Provenance {
      license = requireText(license, "license");
      generator = requireText(generator, "generator");
      sourceLabels = requireStringList(sourceLabels, "sourceLabels", null);
      sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
    }
  }

  public record Summary(
      BinaryLowerBoundBytes binaryLowerBoundBytes,
      int dependencyRows,
      int genitiveRows,
      int invariantNumberRows,
      int rows,
      int uniqueValues) {

    public Summary {
      binaryLowerBoundBytes =
          Objects.requireNonNull(binaryLowerBoundBytes, "binaryLowerBoundBytes");
      requireNonNegative(dependencyRows, "dependencyRows");
      requireNonNegative(genitiveRows, "genitiveRows");
      requireNonNegative(invariantNumberRows, "invariantNumberRows");
      requireNonNegative(rows, "rows");
      requireNonNegative(uniqueValues, "uniqueValues");
    }
  }

  public record BinaryLowerBoundBytes(int rowBytes, int stringPoolBytes, int totalBytes) {

    public BinaryLowerBoundBytes {
      requireNonNegative(rowBytes, "rowBytes");
      requireNonNegative(stringPoolBytes, "stringPoolBytes");
      requireNonNegative(totalBytes, "totalBytes");
    }
  }

  public record Row(
      String value,
      int line,
      String person,
      String number,
      String grammaticalCase,
      String register,
      String dependencyGender,
      String dependencyNumber) {

    public Row {
      value = requireText(value, "value");
      if (line <= 0) {
        throw new IllegalArgumentException("Hindi pronoun line must be positive");
      }
      person = requireExpectedValue(person, "person", PERSON_VALUES);
      number = requireExpectedValue(number, "number", ROW_NUMBER_VALUES);
      grammaticalCase = requireExpectedValue(grammaticalCase, "case", CASE_VALUES);
      register = optionalExpectedValue(register, "register", REGISTER_VALUES);
      dependencyGender = optionalExpectedValue(dependencyGender, "dependencyGender", GENDER_VALUES);
      dependencyNumber =
          optionalExpectedValue(dependencyNumber, "dependencyNumber", REQUEST_NUMBER_VALUES);

      if ("genitive".equals(grammaticalCase)) {
        if (dependencyGender == null || dependencyNumber == null) {
          throw new IllegalArgumentException("Hindi genitive pronoun row requires dependency");
        }
      } else if (dependencyGender != null || dependencyNumber != null) {
        throw new IllegalArgumentException(
            "Hindi non-genitive pronoun row cannot include dependency");
      }
      if ("second".equals(person) && register == null) {
        throw new IllegalArgumentException("Hindi second-person pronoun row requires register");
      }
      if (!"second".equals(person) && register != null) {
        throw new IllegalArgumentException("Hindi non-second pronoun row cannot include register");
      }
    }

    boolean hasDependency() {
      return dependencyGender != null || dependencyNumber != null;
    }

    List<String> expandedNumbers() {
      if ("any".equals(number)) {
        return List.of("plural", "singular");
      }
      return List.of(number);
    }

    boolean matches(Request request) {
      return person.equals(request.person())
          && numberMatches(request.number())
          && grammaticalCase.equals(request.grammaticalCase())
          && Objects.equals(register, request.register())
          && Objects.equals(dependencyGender, request.dependencyGender())
          && Objects.equals(dependencyNumber, request.dependencyNumber());
    }

    private boolean numberMatches(String requestedNumber) {
      return "any".equals(number) || number.equals(requestedNumber);
    }
  }

  public record Request(
      String person,
      String number,
      String grammaticalCase,
      String register,
      String dependencyGender,
      String dependencyNumber) {

    public Request {
      person = requireExpectedValue(person, "person", PERSON_VALUES);
      number = requireExpectedValue(number, "number", REQUEST_NUMBER_VALUES);
      grammaticalCase = requireExpectedValue(grammaticalCase, "case", CASE_VALUES);
      register = optionalExpectedValue(register, "register", REGISTER_VALUES);
      dependencyGender = optionalExpectedValue(dependencyGender, "dependencyGender", GENDER_VALUES);
      dependencyNumber =
          optionalExpectedValue(dependencyNumber, "dependencyNumber", REQUEST_NUMBER_VALUES);
      if ("genitive".equals(grammaticalCase)) {
        if (dependencyGender == null || dependencyNumber == null) {
          throw new IllegalArgumentException("Hindi genitive pronoun request requires dependency");
        }
      } else if (dependencyGender != null || dependencyNumber != null) {
        throw new IllegalArgumentException(
            "Hindi non-genitive pronoun request cannot include dependency");
      }
    }

    String selector() {
      return selectorKey(
          person, number, grammaticalCase, register, dependencyGender, dependencyNumber);
    }
  }

  private static List<String> requireStringList(
      List<String> values, String field, Set<String> allowedValues) {
    List<String> copied = new ArrayList<>();
    for (String value : Objects.requireNonNull(values, field)) {
      value = requireText(value, field);
      if (allowedValues != null && !allowedValues.contains(value)) {
        throw new IllegalArgumentException("Unsupported Hindi pronoun " + field + ": " + value);
      }
      copied.add(value);
    }
    return List.copyOf(copied);
  }

  private static String requireExpected(String value, String field, String expected) {
    value = requireText(value, field);
    if (!expected.equals(value)) {
      throw new IllegalArgumentException("Expected " + field + " " + expected + ", got " + value);
    }
    return value;
  }

  private static String requireExpectedValue(
      String value, String field, Set<String> allowedValues) {
    value = requireText(value, field);
    if (!allowedValues.contains(value)) {
      throw new IllegalArgumentException("Unsupported Hindi pronoun " + field + " value: " + value);
    }
    return value;
  }

  private static String optionalExpectedValue(
      String value, String field, Set<String> allowedValues) {
    if (value == null) {
      return null;
    }
    return requireExpectedValue(value, field, allowedValues);
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return value;
  }

  private static void requireNonNegative(int value, String field) {
    if (value < 0) {
      throw new IllegalArgumentException(field + " must be non-negative");
    }
  }
}
