package com.box.l10n.mojito.mf2.inflection;

import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.integerMap;
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
import com.box.l10n.mojito.mf2.inflection.CompiledTermPack.Source;
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
 * Loads generated Russian unambiguous case-form sample packs and adapts them to the closed-world
 * compiled renderer. The loader intentionally rejects partial or variant-bearing rows for the V0
 * Russian policy.
 */
@GeneratorSupport
class RussianCaseFormPackJsonLoader {

  static final String EXPECTED_SCHEMA = "mojito-mf2-inflection/ru-case-form-sample-pack/v0";
  static final String EXPECTED_LOCALE = "ru";

  private static final List<String> REQUIRED_CASE_FORM_KEYS =
      List.of(
          "accusative.plural",
          "accusative.singular",
          "dative.plural",
          "dative.singular",
          "genitive.plural",
          "genitive.singular",
          "instrumental.plural",
          "instrumental.singular",
          "nominative.plural",
          "nominative.singular",
          "prepositional.plural",
          "prepositional.singular");
  private static final Set<String> CASE_FORM_KEY_VALUES = Set.copyOf(REQUIRED_CASE_FORM_KEYS);
  private static final Set<String> GENDER_VALUES = Set.of("feminine", "masculine", "neuter");
  private static final Set<String> NUMBER_VALUES = Set.of("singular");
  private static final Set<String> ANIMACY_VALUES = Set.of("animate", "inanimate");
  private static final Set<String> PART_OF_SPEECH_VALUES = Set.of("noun", "proper-noun");
  private static final Set<String> SKIPPED_TERM_REASON_VALUES =
      Set.of(
          "conflicting-form-key",
          "duplicate-term-id",
          "missing-case-form-keys",
          "missing-or-ambiguous-gender",
          "missing-or-ambiguous-inflection",
          "missing-pattern",
          "not-nominative",
          "not-singular",
          "requested-surface-unavailable:аббатство",
          "requested-surface-unavailable:кошка",
          "requested-surface-unavailable:ресторан",
          "suffix-mismatch",
          "unsupported-part-of-speech",
          "unsupported-pattern-part-of-speech");

  private final ObjectMapper objectMapper;

  RussianCaseFormPackJsonLoader() {
    this(new ObjectMapper());
  }

  RussianCaseFormPackJsonLoader(ObjectMapper objectMapper) {
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
  }

  public RussianCaseFormPack load(String json) {
    Objects.requireNonNull(json, "json");
    return load(
        objectMapper.readTreeUnchecked(json),
        OptionalInt.of(json.getBytes(StandardCharsets.UTF_8).length));
  }

  public RussianCaseFormPack load(JsonNode root) {
    return load(root, OptionalInt.empty());
  }

  private RussianCaseFormPack load(JsonNode root, OptionalInt jsonBytes) {
    RussianCaseFormPack pack =
        new RussianCaseFormPack(
            requiredText(root, "schema"),
            requiredText(root, "locale"),
            requiredText(root, "description"),
            loadProvenance(requiredObject(root, "provenance")),
            loadStrings(requiredArray(root, "strings")),
            List.of(),
            loadSummary(requiredObject(root, "summary")));
    List<CaseFormTerm> terms = loadTerms(requiredArray(root, "terms"), pack.strings());
    pack =
        new RussianCaseFormPack(
            pack.schema(),
            pack.locale(),
            pack.description(),
            pack.provenance(),
            pack.strings(),
            terms,
            pack.summary());

    validateProvenance(pack.provenance());
    validateSummary(pack, jsonBytes);
    return pack;
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
        textArray(requiredArray(node, "sourceLabels"), "sourceLabels", null, "Russian"),
        List.copyOf(sources));
  }

  private List<String> loadStrings(JsonNode node) {
    List<String> strings = new ArrayList<>();
    for (JsonNode stringNode : node) {
      if (!stringNode.isTextual()) {
        throw new IllegalArgumentException("Expected text value in Russian strings array");
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
      if (!termId.startsWith("ru.case.")) {
        throw new IllegalArgumentException("Russian case-form term id must start with ru.case.");
      }
      if (!termIds.add(termId)) {
        throw new IllegalArgumentException("Duplicate Russian case-form term: " + termId);
      }

      List<CaseForm> forms = new ArrayList<>();
      Set<String> formKeys = new HashSet<>();
      for (JsonNode formNode : requiredArray(termNode, "forms")) {
        int key = requiredStringIndex(formNode, "key", strings);
        String formKey = strings.get(key);
        validateCaseFormKey(formKey);
        if (!formKeys.add(formKey)) {
          throw new IllegalArgumentException(
              "Duplicate Russian case-form key for term " + termId + ": " + formKey);
        }
        String kind = requiredText(formNode, "kind");
        if (!"literal".equals(kind)) {
          throw new IllegalArgumentException(
              "Russian V0 case-form rows must be literal for term " + termId);
        }
        forms.add(new CaseForm(key, requiredStringIndex(formNode, "value", strings)));
      }
      if (!formKeys.equals(CASE_FORM_KEY_VALUES)) {
        throw new IllegalArgumentException(
            "Russian case-form term must contain exactly 12 case-number forms: " + termId);
      }

      terms.add(
          new CaseFormTerm(
              id,
              requiredStringIndex(termNode, "text", strings),
              requiredPartOfSpeech(termNode, "partOfSpeech"),
              requiredGender(termNode, "gender"),
              requiredNumber(termNode, "number"),
              optionalAnimacy(termNode, "animacy"),
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
    return integerMap(node, "skippedTerms", SKIPPED_TERM_REASON_VALUES, "Russian");
  }

  private void validateProvenance(Provenance provenance) {
    if (provenance.sources().isEmpty()) {
      throw new IllegalArgumentException("Russian case-form pack must include sources");
    }
    if (provenance.sourceLabels().size() != provenance.sources().size()) {
      throw new IllegalArgumentException(
          "Russian provenance source label count must match sources");
    }
  }

  private void validateSummary(RussianCaseFormPack pack, OptionalInt jsonBytes) {
    Summary summary = pack.summary();
    if (summary.exportedTerms() != pack.terms().size()) {
      throw new IllegalArgumentException(
          "Russian summary exported term count does not match terms");
    }
    if (summary.candidateTerms() < summary.exportedTerms()) {
      throw new IllegalArgumentException(
          "Russian summary candidate term count is smaller than exports");
    }
    if (summary.strings() != pack.strings().size()) {
      throw new IllegalArgumentException("Russian summary string count does not match strings");
    }
    int formRows = pack.terms().stream().mapToInt(term -> term.forms().size()).sum();
    if (summary.formRows() != formRows) {
      throw new IllegalArgumentException("Russian summary form row count does not match forms");
    }
    int stringPoolBytes = stringPoolBytes(pack.strings());
    if (summary.stringPoolBytes() != stringPoolBytes) {
      throw new IllegalArgumentException("Russian summary string-pool byte estimate is incoherent");
    }
    int binaryLowerBoundBytes = stringPoolBytes + pack.terms().size() * 20 + formRows * 12;
    if (summary.binaryLowerBoundBytes() != binaryLowerBoundBytes) {
      throw new IllegalArgumentException(
          "Russian summary binary lower-bound estimate is incoherent");
    }
    if (jsonBytes.isPresent() && summary.jsonBytes() != jsonBytes.getAsInt()) {
      throw new IllegalArgumentException("Russian summary JSON byte count does not match input");
    }
  }

  private String requiredPartOfSpeech(JsonNode node, String field) {
    String value = requiredText(node, field);
    if (!PART_OF_SPEECH_VALUES.contains(value)) {
      throw new IllegalArgumentException("Unsupported Russian part of speech: " + value);
    }
    return value;
  }

  private String requiredGender(JsonNode node, String field) {
    String value = requiredText(node, field);
    if (!GENDER_VALUES.contains(value)) {
      throw new IllegalArgumentException("Unsupported Russian gender: " + value);
    }
    return value;
  }

  private String requiredNumber(JsonNode node, String field) {
    String value = requiredText(node, field);
    if (!NUMBER_VALUES.contains(value)) {
      throw new IllegalArgumentException("Unsupported Russian number: " + value);
    }
    return value;
  }

  private String optionalAnimacy(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isTextual() || value.asText().isBlank()) {
      throw new IllegalArgumentException("Expected optional text field: " + field);
    }
    String text = value.asText();
    if (!ANIMACY_VALUES.contains(text)) {
      throw new IllegalArgumentException("Unsupported Russian animacy: " + text);
    }
    return text;
  }

  private void validateCaseFormKey(String key) {
    if (!CASE_FORM_KEY_VALUES.contains(key)) {
      throw new IllegalArgumentException("Unsupported Russian case-form key: " + key);
    }
  }

  private static int stringPoolBytes(List<String> strings) {
    return strings.stream()
        .mapToInt(value -> value.getBytes(StandardCharsets.UTF_8).length + 1)
        .sum();
  }

  public record RussianCaseFormPack(
      String schema,
      String locale,
      String description,
      Provenance provenance,
      List<String> strings,
      List<CaseFormTerm> terms,
      Summary summary) {

    public RussianCaseFormPack {
      schema = requireExpected(schema, "schema", EXPECTED_SCHEMA);
      locale = requireExpected(locale, "locale", EXPECTED_LOCALE);
      description = requireText(description, "description");
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
                    .map(form -> new FormRow(form.key(), form.value(), false))
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
              provenance.sources()),
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
      String animacy,
      String inflectionPattern,
      int stem,
      List<CaseForm> forms) {

    public CaseFormTerm {
      partOfSpeech = requireText(partOfSpeech, "partOfSpeech");
      gender = requireText(gender, "gender");
      number = requireText(number, "number");
      if (animacy != null) {
        animacy = requireText(animacy, "animacy");
      }
      inflectionPattern = requireText(inflectionPattern, "inflectionPattern");
      forms = List.copyOf(Objects.requireNonNull(forms, "forms"));
    }
  }

  public record CaseForm(int key, int value) {}

  public record Provenance(
      String license, String generator, List<String> sourceLabels, List<Source> sources) {

    public Provenance {
      license = requireText(license, "license");
      generator = requireText(generator, "generator");
      sourceLabels = List.copyOf(Objects.requireNonNull(sourceLabels, "sourceLabels"));
      sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
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
      strings = requireNonNegative(strings, "strings");
      formRows = requireNonNegative(formRows, "formRows");
      stringPoolBytes = requireNonNegative(stringPoolBytes, "stringPoolBytes");
      jsonBytes = requireNonNegative(jsonBytes, "jsonBytes");
      binaryLowerBoundBytes = requireNonNegative(binaryLowerBoundBytes, "binaryLowerBoundBytes");
    }
  }

  private static int featureBits(String partOfSpeech, String gender, String number) {
    int bits = "noun".equals(partOfSpeech) ? 1 : 2;
    bits |=
        switch (gender) {
          case "masculine" -> 1 << 4;
          case "feminine" -> 2 << 4;
          case "neuter" -> 3 << 4;
          default -> throw new IllegalArgumentException("Unsupported Russian gender: " + gender);
        };
    bits |=
        switch (number) {
          case "singular" -> 1 << 8;
          default -> throw new IllegalArgumentException("Unsupported Russian number: " + number);
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

  private static int requireNonNegative(int value, String field) {
    if (value < 0) {
      throw new IllegalArgumentException(field + " must be non-negative");
    }
    return value;
  }
}
