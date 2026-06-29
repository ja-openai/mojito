package com.box.l10n.mojito.mf2.inflection;

import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.integerMap;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.requireText;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.requiredArray;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.requiredBoolean;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.requiredInt;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.requiredLong;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.requiredObject;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.requiredStringIndex;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.requiredText;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.textArray;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.unmodifiableLinkedMap;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.mf2.inflection.CompiledTermPack.BinaryLowerBoundBytes;
import com.box.l10n.mojito.mf2.inflection.CompiledTermPack.FormRow;
import com.box.l10n.mojito.mf2.inflection.CompiledTermPack.FormSet;
import com.box.l10n.mojito.mf2.inflection.CompiledTermPack.SizeEstimates;
import com.box.l10n.mojito.mf2.inflection.CompiledTermPack.TermRow;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Loads generated Serbian case forms and adapts them to the closed-world compiled term renderer.
 * This is still a JSON fixture format; production packing can replace the storage shape without
 * changing the renderer contract.
 */
@GeneratorSupport
class SerbianCaseFormPackJsonLoader {

  static final String EXPECTED_SCHEMA = "mojito-mf2-inflection/sr-case-form-sample-pack/v0";
  static final String EXPECTED_LOCALE = "sr";

  private static final Set<String> CASE_VALUES =
      Set.of(
          "accusative", "dative", "genitive", "instrumental", "locative", "nominative", "vocative");
  private static final Set<String> GENDER_VALUES = Set.of("feminine", "masculine", "neuter");
  private static final Set<String> NUMBER_VALUES = Set.of("plural", "singular");
  private static final Set<String> PART_OF_SPEECH_VALUES = Set.of("noun", "proper-noun");
  private static final Set<String> SKIPPED_TERM_REASON_VALUES =
      Set.of(
          "conflicting-form-key",
          "duplicate-term-id",
          "missing-accusative-singular-form",
          "missing-nominative-singular-form",
          "missing-or-ambiguous-gender",
          "missing-or-ambiguous-inflection",
          "missing-pattern",
          "not-nominative",
          "not-singular",
          "suffix-mismatch",
          "unsupported-part-of-speech",
          "unsupported-pattern-part-of-speech");

  private final ObjectMapper objectMapper;

  SerbianCaseFormPackJsonLoader() {
    this(new ObjectMapper());
  }

  SerbianCaseFormPackJsonLoader(ObjectMapper objectMapper) {
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
  }

  public SerbianCaseFormPack load(String json) {
    Objects.requireNonNull(json, "json");
    return load(
        objectMapper.readTreeUnchecked(json),
        OptionalInt.of(json.getBytes(StandardCharsets.UTF_8).length));
  }

  public SerbianCaseFormPack load(JsonNode root) {
    return load(root, OptionalInt.empty());
  }

  private SerbianCaseFormPack load(JsonNode root, OptionalInt jsonBytes) {
    String schema = requireExpected(requiredText(root, "schema"), "schema", EXPECTED_SCHEMA);
    String locale = requireExpected(requiredText(root, "locale"), "locale", EXPECTED_LOCALE);
    Provenance provenance = loadProvenance(requiredObject(root, "provenance"));
    List<String> strings = loadStrings(requiredArray(root, "strings"));
    List<CaseFormTerm> terms = loadTerms(requiredArray(root, "terms"), strings);
    Summary summary = loadSummary(requiredObject(root, "summary"));

    validateProvenance(provenance);
    if (summary.exportedTerms() != terms.size()) {
      throw new IllegalArgumentException("Summary exported term count does not match terms array");
    }
    if (summary.candidateTerms() < summary.exportedTerms()) {
      throw new IllegalArgumentException("Summary candidate term count is smaller than exports");
    }
    if (summary.strings() != strings.size()) {
      throw new IllegalArgumentException("Summary string count does not match strings array");
    }
    int formRows = terms.stream().mapToInt(term -> term.forms().size()).sum();
    if (summary.formRows() != formRows) {
      throw new IllegalArgumentException("Summary form row count does not match term forms");
    }
    int stringPoolBytes = stringPoolBytes(strings);
    if (summary.stringPoolBytes() != stringPoolBytes) {
      throw new IllegalArgumentException("Serbian summary string-pool byte estimate is incoherent");
    }
    int binaryLowerBoundBytes = stringPoolBytes + terms.size() * 20 + formRows * 12;
    if (summary.binaryLowerBoundBytes() != binaryLowerBoundBytes) {
      throw new IllegalArgumentException(
          "Serbian summary binary lower-bound estimate is incoherent");
    }
    if (jsonBytes.isPresent() && summary.jsonBytes() != jsonBytes.getAsInt()) {
      throw new IllegalArgumentException("Serbian summary JSON byte count does not match input");
    }

    return new SerbianCaseFormPack(schema, locale, provenance, strings, terms, summary);
  }

  private Provenance loadProvenance(JsonNode node) {
    List<Source> sources = new ArrayList<>();
    for (JsonNode sourceNode : requiredArray(node, "sources")) {
      sources.add(
          new Source(
              requiredText(sourceNode, "path"),
              requiredLong(sourceNode, "byteSize"),
              requiredText(sourceNode, "sha256"),
              requiredBoolean(sourceNode, "gitLfsPointer")));
    }
    return new Provenance(
        requiredText(node, "license"),
        requiredText(node, "generator"),
        textArray(requiredArray(node, "sourceLabels"), "sourceLabels", null, "Serbian"),
        List.copyOf(sources));
  }

  private List<String> loadStrings(JsonNode node) {
    List<String> strings = new ArrayList<>();
    for (JsonNode stringNode : node) {
      if (!stringNode.isTextual()) {
        throw new IllegalArgumentException("Expected text value in strings array");
      }
      strings.add(stringNode.asText());
    }
    return List.copyOf(strings);
  }

  private List<CaseFormTerm> loadTerms(JsonNode node, List<String> strings) {
    List<CaseFormTerm> terms = new ArrayList<>();
    Set<String> termIds = new HashSet<>();
    for (JsonNode termNode : node) {
      int id = requiredStringIndex(termNode, "id", strings);
      String termId = strings.get(id);
      if (!termIds.add(termId)) {
        throw new IllegalArgumentException("Duplicate Serbian case-form term: " + termId);
      }

      List<CaseForm> forms = new ArrayList<>();
      Set<String> formKeys = new HashSet<>();
      for (JsonNode formNode : requiredArray(termNode, "forms")) {
        int key = requiredStringIndex(formNode, "key", strings);
        String formKey = strings.get(key);
        validateCaseFormKey(formKey);
        if (!formKeys.add(formKey)) {
          throw new IllegalArgumentException(
              "Duplicate Serbian case-form key for term " + termId + ": " + formKey);
        }
        forms.add(
            new CaseForm(
                key,
                requiredStringIndex(formNode, "value", strings),
                isPatternKind(requiredText(formNode, "kind"))));
      }
      if (!formKeys.contains("nominative.singular")) {
        throw new IllegalArgumentException(
            "Serbian case-form term is missing nominative.singular: " + termId);
      }

      terms.add(
          new CaseFormTerm(
              id,
              requiredStringIndex(termNode, "text", strings),
              requiredPartOfSpeech(termNode, "partOfSpeech"),
              requiredGender(termNode, "gender"),
              requiredNumber(termNode, "number"),
              requiredText(termNode, "inflectionPattern"),
              requiredStringIndex(termNode, "stem", strings),
              List.copyOf(forms)));
    }
    return List.copyOf(terms);
  }

  private Summary loadSummary(JsonNode node) {
    return new Summary(
        requiredInt(node, "candidateTerms"),
        requiredInt(node, "exportedTerms"),
        skippedTermCounts(requiredObject(node, "skippedTerms")),
        requiredInt(node, "strings"),
        requiredInt(node, "formRows"),
        requiredInt(node, "stringPoolBytes"),
        requiredInt(node, "jsonBytes"),
        requiredInt(node, "binaryLowerBoundBytes"));
  }

  private Map<String, Integer> skippedTermCounts(JsonNode node) {
    return integerMap(node, "skippedTerms", SKIPPED_TERM_REASON_VALUES, "Serbian");
  }

  private void validateProvenance(Provenance provenance) {
    if (provenance.sources().isEmpty()) {
      throw new IllegalArgumentException("Serbian case-form pack must include sources");
    }
    if (provenance.sourceLabels().size() != provenance.sources().size()) {
      throw new IllegalArgumentException(
          "Serbian provenance source label count must match sources");
    }
  }

  private String requiredPartOfSpeech(JsonNode node, String field) {
    String value = requiredText(node, field);
    if (!PART_OF_SPEECH_VALUES.contains(value)) {
      throw new IllegalArgumentException("Unsupported Serbian part of speech: " + value);
    }
    return value;
  }

  private String requiredGender(JsonNode node, String field) {
    String value = requiredText(node, field);
    if (!GENDER_VALUES.contains(value)) {
      throw new IllegalArgumentException("Unsupported Serbian gender: " + value);
    }
    return value;
  }

  private String requiredNumber(JsonNode node, String field) {
    String value = requiredText(node, field);
    if (!NUMBER_VALUES.contains(value)) {
      throw new IllegalArgumentException("Unsupported Serbian number: " + value);
    }
    return value;
  }

  private void validateCaseFormKey(String key) {
    String[] parts = key.split("\\.");
    if (parts.length != 2 || !CASE_VALUES.contains(parts[0]) || !NUMBER_VALUES.contains(parts[1])) {
      throw new IllegalArgumentException("Unsupported Serbian case-form key: " + key);
    }
  }

  private boolean isPatternKind(String kind) {
    if ("pattern".equals(kind)) {
      return true;
    }
    if ("literal".equals(kind)) {
      return false;
    }
    throw new IllegalArgumentException("Unknown Serbian case-form row kind: " + kind);
  }

  private static int stringPoolBytes(List<String> strings) {
    return strings.stream()
        .mapToInt(value -> value.getBytes(StandardCharsets.UTF_8).length + 1)
        .sum();
  }

  public record SerbianCaseFormPack(
      String schema,
      String locale,
      Provenance provenance,
      List<String> strings,
      List<CaseFormTerm> terms,
      Summary summary) {

    public SerbianCaseFormPack {
      schema = requireExpected(schema, "schema", EXPECTED_SCHEMA);
      locale = requireExpected(locale, "locale", EXPECTED_LOCALE);
      provenance = Objects.requireNonNull(provenance, "provenance");
      strings = List.copyOf(Objects.requireNonNull(strings, "strings"));
      terms = List.copyOf(Objects.requireNonNull(terms, "terms"));
      summary = Objects.requireNonNull(summary, "summary");
    }

    public Optional<CaseFormTerm> findTerm(String termId) {
      Objects.requireNonNull(termId, "termId");
      return terms.stream().filter(term -> strings.get(term.id()).equals(termId)).findFirst();
    }

    public CompiledTermPack toCompiledTermPack() {
      List<TermRow> termRows = new ArrayList<>();
      List<FormSet> formSets = new ArrayList<>();
      for (CaseFormTerm term : terms) {
        int formSetIndex = formSets.size();
        formSets.add(
            new FormSet(
                term.id(),
                term.forms().stream()
                    .map(form -> new FormRow(form.key(), form.value(), form.pattern()))
                    .toList()));
        termRows.add(
            new TermRow(
                term.id(),
                term.text(),
                featureBits(term.partOfSpeech(), term.gender(), term.number()),
                null,
                formSetIndex));
      }
      return new CompiledTermPack(
          CompiledTermPack.SCHEMA,
          locale,
          strings,
          List.copyOf(termRows),
          List.copyOf(formSets),
          new CompiledTermPack.Provenance(
              provenance.license(),
              provenance.generator(),
              provenance.sourceLabels(),
              provenance.sources().stream()
                  .map(
                      source ->
                          new CompiledTermPack.Source(
                              source.path(),
                              source.byteSize(),
                              source.sha256(),
                              source.gitLfsPointer()))
                  .toList()),
          new SizeEstimates(
              null,
              new BinaryLowerBoundBytes(
                  summary.stringPoolBytes(),
                  terms.size() * 20,
                  summary.formRows() * 12,
                  0,
                  summary.binaryLowerBoundBytes())));
    }
  }

  public record CaseFormTerm(
      int id,
      int text,
      String partOfSpeech,
      String gender,
      String number,
      String inflectionPattern,
      int stem,
      List<CaseForm> forms) {

    public CaseFormTerm {
      partOfSpeech = requireText(partOfSpeech, "partOfSpeech");
      gender = requireText(gender, "gender");
      number = requireText(number, "number");
      inflectionPattern = requireText(inflectionPattern, "inflectionPattern");
      forms = List.copyOf(Objects.requireNonNull(forms, "forms"));
    }
  }

  public record CaseForm(int key, int value, boolean pattern) {}

  public record Provenance(
      String license, String generator, List<String> sourceLabels, List<Source> sources) {

    public Provenance {
      license = requireText(license, "license");
      generator = requireText(generator, "generator");
      sourceLabels = List.copyOf(Objects.requireNonNull(sourceLabels, "sourceLabels"));
      sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
    }
  }

  public record Source(String path, long byteSize, String sha256, boolean gitLfsPointer) {

    public Source {
      path = requireText(path, "path");
      sha256 = requireText(sha256, "sha256");
      if (byteSize < 0) {
        throw new IllegalArgumentException("byteSize must be non-negative: " + byteSize);
      }
    }
  }

  public record Summary(
      int candidateTerms,
      int exportedTerms,
      Map<String, Integer> skippedTerms,
      int strings,
      int formRows,
      int stringPoolBytes,
      int jsonBytes,
      int binaryLowerBoundBytes) {

    public Summary {
      requireNonNegative(candidateTerms, "candidateTerms");
      requireNonNegative(exportedTerms, "exportedTerms");
      skippedTerms = unmodifiableLinkedMap(Objects.requireNonNull(skippedTerms, "skippedTerms"));
      requireNonNegative(strings, "strings");
      requireNonNegative(formRows, "formRows");
      requireNonNegative(stringPoolBytes, "stringPoolBytes");
      requireNonNegative(jsonBytes, "jsonBytes");
      requireNonNegative(binaryLowerBoundBytes, "binaryLowerBoundBytes");
    }
  }

  private static int featureBits(String partOfSpeech, String gender, String number) {
    int bits = "noun".equals(partOfSpeech) ? 1 : 0;
    bits |=
        switch (gender) {
          case "masculine" -> 1 << 4;
          case "feminine" -> 2 << 4;
          case "neuter" -> 3 << 4;
          default -> throw new IllegalArgumentException("Unsupported Serbian gender: " + gender);
        };
    bits |=
        switch (number) {
          case "singular" -> 1 << 8;
          case "plural" -> 2 << 8;
          default -> throw new IllegalArgumentException("Unsupported Serbian number: " + number);
        };
    return bits;
  }

  private static String requireExpected(String value, String field, String expected) {
    value = requireText(value, field);
    if (!expected.equals(value)) {
      throw new IllegalArgumentException("Expected " + field + " " + expected + ", got " + value);
    }
    return value;
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
