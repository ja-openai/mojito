package com.box.l10n.mojito.mf2.inflection;

import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.optionalText;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.requiredArray;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.requiredBoolean;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.requiredInt;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.requiredIntValue;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.requiredLong;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.requiredObject;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.requiredText;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.requiredTextValue;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.textArray;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.unmodifiableLinkedMap;

import com.box.l10n.mojito.json.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Loads the generated Hindi pack survey. The survey is a generator contract for the first Hindi
 * runtime shape: direct/oblique/vocative case-form rows plus a pronoun agreement table.
 */
@GeneratorSupport
class HindiPackSurveyJsonLoader {

  static final String EXPECTED_SCHEMA = "mojito-mf2-inflection/hi-pack-survey/v0";
  static final String EXPECTED_LOCALE = "hi";
  static final String EXPECTED_RECOMMENDATION = "case-form rows plus pronoun agreement table";

  private static final List<String> EXPECTED_TERM_CASES = List.of("direct", "oblique", "vocative");
  private static final List<String> EXPECTED_METADATA_BITS =
      List.of("gender", "number", "animacy", "partOfSpeech");
  private static final List<String> EXPECTED_PRONOUN_DEPENDENCY_KEYS = List.of("gender", "number");
  private static final Set<String> TERM_CASE_VALUES =
      Set.of("<missing>", "direct", "oblique", "vocative");
  private static final Set<String> PATTERN_SLOT_CASE_VALUES =
      Set.of("<missing>", "causative", "direct", "oblique", "vocative");
  private static final Set<String> GENDER_VALUES = Set.of("<missing>", "feminine", "masculine");
  private static final Set<String> SAMPLE_GENDER_VALUES = Set.of("feminine", "masculine");
  private static final Set<String> NUMBER_VALUES = Set.of("<missing>", "plural", "singular");
  private static final Set<String> SAMPLE_NUMBER_VALUES = Set.of("plural", "singular");
  private static final Set<String> ANIMACY_VALUES = Set.of("<missing>", "animate", "inanimate");
  private static final Set<String> SAMPLE_ANIMACY_VALUES = Set.of("animate", "inanimate");
  private static final Set<String> PART_OF_SPEECH_VALUES =
      Set.of(
          "<missing>",
          "adjective",
          "adposition",
          "adverb",
          "noun",
          "numeral",
          "proper-noun",
          "verb");
  private static final Set<String> SAMPLE_PART_OF_SPEECH_VALUES =
      Set.of("adjective", "adposition", "adverb", "noun", "numeral", "proper-noun", "verb");
  private static final Set<String> PATTERN_SLOT_OTHER_ATTRIBUTE_VALUES =
      Set.of(
          "aspect=imperfect",
          "aspect=perfect",
          "comparison-degree=comparative",
          "comparison-degree=positive",
          "comparison-degree=superlative",
          "count=uncountable",
          "definiteness=definite",
          "mood=imperative",
          "mood=subjunctive",
          "person=first",
          "person=second",
          "person=third",
          "tense=future",
          "transitivity=intransitive",
          "transitivity=transitive",
          "verb-type=gerund",
          "verb-type=infinitive",
          "verb-type=participle");
  private static final Set<String> PRONOUN_FEATURE_VALUES =
      Set.of(
          "accusative",
          "direct",
          "ergative",
          "first",
          "formal",
          "genitive",
          "informal",
          "intimate",
          "number",
          "plural",
          "second",
          "singular");
  private static final Set<String> PRONOUN_DEPENDENCY_FEATURE_VALUES =
      Set.of("feminine", "masculine", "plural", "singular");
  private static final Set<String> AMBIGUITY_REASON_VALUES =
      Set.of(
          "multiple-analyses",
          "multiple-animacy",
          "multiple-cases",
          "multiple-genders",
          "multiple-inflections",
          "multiple-numbers",
          "multiple-parts-of-speech");
  private static final Set<String> CASE_FORM_SKIP_REASON_VALUES =
      Set.of(
          "conflicting-form-key",
          "missing-direct-singular-form",
          "missing-or-ambiguous-gender",
          "missing-or-ambiguous-inflection",
          "non-direct-singular-surface",
          "suffix-mismatch",
          "unsupported-pattern-part-of-speech");
  private static final Set<String> CASE_FORM_KEY_VALUES =
      Set.of(
          "direct.plural",
          "direct.singular",
          "oblique.plural",
          "oblique.singular",
          "vocative.plural",
          "vocative.singular");

  private final ObjectMapper objectMapper;

  HindiPackSurveyJsonLoader() {
    this(new ObjectMapper());
  }

  HindiPackSurveyJsonLoader(ObjectMapper objectMapper) {
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
  }

  public HindiPackSurvey load(String json) {
    Objects.requireNonNull(json, "json");
    return load(objectMapper.readTreeUnchecked(json));
  }

  public HindiPackSurvey load(JsonNode root) {
    HindiPackSurvey survey =
        new HindiPackSurvey(
            requiredText(root, "schema"),
            requiredText(root, "locale"),
            loadSources(requiredArray(root, "sources")),
            loadProvenance(requiredObject(root, "provenance")),
            loadCounts(requiredObject(root, "counts")),
            loadFeatures(requiredObject(root, "features")),
            loadPackShape(requiredObject(root, "packShape")),
            loadSamples(requiredObject(root, "samples")));

    validateProvenance(survey);
    validateCounts(survey.counts(), survey.samples());
    validateFeatureArithmetic(survey.counts(), survey.features());
    validatePackShape(survey.counts(), survey.packShape());
    return survey;
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

  private Provenance loadProvenance(JsonNode node) {
    return new Provenance(
        requiredText(node, "license"),
        requiredText(node, "generator"),
        textArray(requiredArray(node, "sourceLabels"), "sourceLabels", null, "Hindi"));
  }

  private Counts loadCounts(JsonNode node) {
    return new Counts(
        requiredInt(node, "agreementEntries"),
        requiredInt(node, "agreementSurfaces"),
        requiredInt(node, "ambiguousAgreementSurfaces"),
        requiredInt(node, "dictionaryEntries"),
        requiredInt(node, "dictionarySkippedLines"),
        requiredInt(node, "inflectionalPatterns"),
        requiredInt(node, "missingInflectionPatterns"),
        requiredInt(node, "pronounRows"),
        requiredInt(node, "termEntries"),
        requiredInt(node, "termSurfaces"),
        requiredInt(node, "usedAgreementPatterns"));
  }

  private Features loadFeatures(JsonNode node) {
    return new Features(
        featureMap(requiredObject(node, "inflectionPatterns"), "inflectionPatterns", null),
        featureMap(requiredObject(node, "partOfSpeech"), "partOfSpeech", PART_OF_SPEECH_VALUES),
        featureMap(
            requiredObject(node, "patternSlotAnimacy"), "patternSlotAnimacy", ANIMACY_VALUES),
        featureMap(
            requiredObject(node, "patternSlotCases"), "patternSlotCases", PATTERN_SLOT_CASE_VALUES),
        featureMap(requiredObject(node, "patternSlotGenders"), "patternSlotGenders", GENDER_VALUES),
        featureMap(requiredObject(node, "patternSlotNumbers"), "patternSlotNumbers", NUMBER_VALUES),
        featureMap(
            requiredObject(node, "patternSlotOtherAttributes"),
            "patternSlotOtherAttributes",
            PATTERN_SLOT_OTHER_ATTRIBUTE_VALUES),
        featureMap(
            requiredObject(node, "pronounDependencyFeatures"),
            "pronounDependencyFeatures",
            PRONOUN_DEPENDENCY_FEATURE_VALUES),
        featureMap(
            requiredObject(node, "pronounFeatures"), "pronounFeatures", PRONOUN_FEATURE_VALUES),
        numericFeatureMap(requiredObject(node, "slotsPerPattern"), "slotsPerPattern", 0, 256),
        requiredInt(node, "templateRows"),
        featureMap(requiredObject(node, "termAnimacy"), "termAnimacy", ANIMACY_VALUES),
        featureMap(requiredObject(node, "termCase"), "termCase", TERM_CASE_VALUES),
        featureMap(requiredObject(node, "termGender"), "termGender", GENDER_VALUES),
        featureMap(requiredObject(node, "termNumber"), "termNumber", NUMBER_VALUES));
  }

  private PackShape loadPackShape(JsonNode node) {
    return new PackShape(
        loadCaseFormPack(requiredObject(node, "caseFormPack")),
        textArray(requiredArray(node, "metadataBits"), "metadataBits", null, "Hindi"),
        textArray(
            requiredArray(node, "pronounDependencyKeys"), "pronounDependencyKeys", null, "Hindi"),
        requiredInt(node, "pronounTableRows"),
        requiredText(node, "reason"),
        requiredText(node, "recommendation"),
        textArray(requiredArray(node, "termCases"), "termCases", null, "Hindi"));
  }

  private CaseFormPack loadCaseFormPack(JsonNode node) {
    return new CaseFormPack(
        loadBinaryLowerBoundBytes(requiredObject(node, "binaryLowerBoundBytes")),
        requiredInt(node, "candidateTerms"),
        requiredInt(node, "formRows"),
        featureMap(
            requiredObject(node, "skippedTerms"),
            "caseFormPack.skippedTerms",
            CASE_FORM_SKIP_REASON_VALUES));
  }

  private BinaryLowerBoundBytes loadBinaryLowerBoundBytes(JsonNode node) {
    return new BinaryLowerBoundBytes(
        requiredInt(node, "formRowBytes"),
        requiredInt(node, "stringPoolBytes"),
        requiredInt(node, "termRowBytes"),
        requiredInt(node, "totalBytes"));
  }

  private Samples loadSamples(JsonNode node) {
    return new Samples(
        ambiguousAgreementSurfaceSamples(requiredArray(node, "ambiguousAgreementSurfaces")),
        caseFormTermSamples(requiredArray(node, "caseFormTerms")),
        textArray(
            requiredArray(node, "missingInflectionPatterns"),
            "missingInflectionPatterns",
            null,
            "Hindi"),
        pronounSamples(requiredArray(node, "pronouns")),
        termEntrySamples(requiredArray(node, "termEntries")));
  }

  private List<AmbiguousAgreementSurfaceSample> ambiguousAgreementSurfaceSamples(JsonNode node) {
    List<AmbiguousAgreementSurfaceSample> samples = new ArrayList<>();
    for (JsonNode sampleNode : node) {
      samples.add(
          new AmbiguousAgreementSurfaceSample(
              requiredText(sampleNode, "surface"),
              textArray(
                  requiredArray(sampleNode, "reasons"),
                  "reasons",
                  AMBIGUITY_REASON_VALUES,
                  "Hindi"),
              requiredPositiveInt(sampleNode, "entries")));
    }
    return List.copyOf(samples);
  }

  private List<CaseFormTermSample> caseFormTermSamples(JsonNode node) {
    List<CaseFormTermSample> samples = new ArrayList<>();
    for (JsonNode sampleNode : node) {
      samples.add(
          new CaseFormTermSample(
              requiredText(sampleNode, "termId"),
              requiredText(sampleNode, "text"),
              requiredExpectedText(sampleNode, "gender", SAMPLE_GENDER_VALUES),
              nullableExpectedText(sampleNode, "animacy", SAMPLE_ANIMACY_VALUES),
              requiredText(sampleNode, "pattern"),
              formMap(requiredObject(sampleNode, "forms"))));
    }
    return List.copyOf(samples);
  }

  private List<PronounSample> pronounSamples(JsonNode node) {
    List<PronounSample> samples = new ArrayList<>();
    for (JsonNode sampleNode : node) {
      samples.add(
          new PronounSample(
              pronounFeatureArray(requiredArray(sampleNode, "features"), "features"),
              requiredPositiveInt(sampleNode, "line"),
              requiredText(sampleNode, "value")));
    }
    return List.copyOf(samples);
  }

  private List<TermEntrySample> termEntrySamples(JsonNode node) {
    List<TermEntrySample> samples = new ArrayList<>();
    for (JsonNode sampleNode : node) {
      samples.add(
          new TermEntrySample(
              textArray(
                  requiredArray(sampleNode, "animacy"), "animacy", SAMPLE_ANIMACY_VALUES, "Hindi"),
              textArray(requiredArray(sampleNode, "case"), "case", TERM_CASE_VALUES, "Hindi"),
              textArray(
                  requiredArray(sampleNode, "gender"), "gender", SAMPLE_GENDER_VALUES, "Hindi"),
              textArray(requiredArray(sampleNode, "inflections"), "inflections", null, "Hindi"),
              requiredPositiveInt(sampleNode, "line"),
              textArray(
                  requiredArray(sampleNode, "number"), "number", SAMPLE_NUMBER_VALUES, "Hindi"),
              textArray(
                  requiredArray(sampleNode, "partOfSpeech"),
                  "partOfSpeech",
                  SAMPLE_PART_OF_SPEECH_VALUES,
                  "Hindi"),
              requiredText(sampleNode, "surface")));
    }
    return List.copyOf(samples);
  }

  private void validateProvenance(HindiPackSurvey survey) {
    if (survey.sources().isEmpty()) {
      throw new IllegalArgumentException("Hindi pack survey must include sources");
    }
    if (survey.provenance().sourceLabels().size() != survey.sources().size()) {
      throw new IllegalArgumentException("Hindi provenance source label count must match sources");
    }
    List<String> sourcePaths = survey.sources().stream().map(Source::path).toList();
    if (!sourcePaths.equals(survey.provenance().sourceLabels())) {
      throw new IllegalArgumentException("Hindi provenance source labels must match source paths");
    }
  }

  private void validateCounts(Counts counts, Samples samples) {
    if (counts.termEntries() > counts.dictionaryEntries()) {
      throw new IllegalArgumentException("Hindi term entries exceed dictionary entries");
    }
    if (counts.termSurfaces() > counts.termEntries()) {
      throw new IllegalArgumentException("Hindi term surfaces exceed term entries");
    }
    if (counts.agreementEntries() > counts.dictionaryEntries()) {
      throw new IllegalArgumentException("Hindi agreement entries exceed dictionary entries");
    }
    if (counts.agreementSurfaces() > counts.agreementEntries()) {
      throw new IllegalArgumentException("Hindi agreement surfaces exceed agreement entries");
    }
    if (counts.ambiguousAgreementSurfaces() > counts.agreementSurfaces()) {
      throw new IllegalArgumentException(
          "Hindi ambiguous agreement surfaces exceed agreement surfaces");
    }
    if (counts.usedAgreementPatterns() > counts.inflectionalPatterns()) {
      throw new IllegalArgumentException("Hindi used patterns exceed inflectional pattern count");
    }
    if (samples.ambiguousAgreementSurfaces().size() > counts.ambiguousAgreementSurfaces()) {
      throw new IllegalArgumentException("Hindi ambiguous agreement samples exceed reported count");
    }
    if (samples.caseFormTerms().size() > counts.termEntries()) {
      throw new IllegalArgumentException("Hindi case-form samples exceed term entries");
    }
    if (samples.pronouns().size() > counts.pronounRows()) {
      throw new IllegalArgumentException("Hindi pronoun samples exceed reported row count");
    }
    if (samples.termEntries().size() > counts.termEntries()) {
      throw new IllegalArgumentException("Hindi term-entry samples exceed term entries");
    }
    if (samples.missingInflectionPatterns().size() > counts.missingInflectionPatterns()) {
      throw new IllegalArgumentException("Hindi missing-pattern samples exceed reported count");
    }
    if (counts.missingInflectionPatterns() == 0 && !samples.missingInflectionPatterns().isEmpty()) {
      throw new IllegalArgumentException("Missing-pattern samples present when count is zero");
    }
  }

  private void validateFeatureArithmetic(Counts counts, Features features) {
    if (features.inflectionPatterns().size() != counts.usedAgreementPatterns()) {
      throw new IllegalArgumentException("Hindi inflection pattern feature count mismatch");
    }
    if (sumValues(features.patternSlotAnimacy()) != features.templateRows()
        || sumValues(features.patternSlotCases()) != features.templateRows()
        || sumValues(features.patternSlotGenders()) != features.templateRows()
        || sumValues(features.patternSlotNumbers()) != features.templateRows()) {
      throw new IllegalArgumentException("Hindi pattern slot counts do not match template rows");
    }
    if (weightedNumericKeySum(features.slotsPerPattern()) != features.templateRows()) {
      throw new IllegalArgumentException("Hindi slots-per-pattern counts do not match templates");
    }
  }

  private void validatePackShape(Counts counts, PackShape packShape) {
    if (!EXPECTED_RECOMMENDATION.equals(packShape.recommendation())) {
      throw new IllegalArgumentException("Unexpected Hindi pack recommendation");
    }
    if (!EXPECTED_TERM_CASES.equals(packShape.termCases())) {
      throw new IllegalArgumentException("Unexpected Hindi term cases");
    }
    if (!EXPECTED_METADATA_BITS.equals(packShape.metadataBits())) {
      throw new IllegalArgumentException("Unexpected Hindi metadata bits");
    }
    if (!EXPECTED_PRONOUN_DEPENDENCY_KEYS.equals(packShape.pronounDependencyKeys())) {
      throw new IllegalArgumentException("Unexpected Hindi pronoun dependency keys");
    }
    if (packShape.pronounTableRows() != counts.pronounRows()) {
      throw new IllegalArgumentException("Hindi pronoun table rows must match pronoun count");
    }

    CaseFormPack caseFormPack = packShape.caseFormPack();
    int skippedTerms = sumValues(caseFormPack.skippedTerms());
    if (caseFormPack.candidateTerms() + skippedTerms != counts.termEntries()) {
      throw new IllegalArgumentException(
          "Hindi case-form candidate terms and skipped terms must close over term entries");
    }
    if (caseFormPack.candidateTerms() > 0
        && caseFormPack.formRows() < caseFormPack.candidateTerms()) {
      throw new IllegalArgumentException("Hindi case-form rows do not cover candidate terms");
    }

    BinaryLowerBoundBytes estimate = caseFormPack.binaryLowerBoundBytes();
    if (estimate.termRowBytes() != caseFormPack.candidateTerms() * 20) {
      throw new IllegalArgumentException("Hindi case-form term-row estimate is incoherent");
    }
    if (estimate.formRowBytes() != caseFormPack.formRows() * 12) {
      throw new IllegalArgumentException("Hindi case-form form-row estimate is incoherent");
    }
    if (estimate.totalBytes()
        != estimate.stringPoolBytes() + estimate.termRowBytes() + estimate.formRowBytes()) {
      throw new IllegalArgumentException("Hindi case-form lower-bound estimate is incoherent");
    }
  }

  private Map<String, String> formMap(JsonNode node) {
    Map<String, String> values = new LinkedHashMap<>();
    for (var fields = node.fields(); fields.hasNext(); ) {
      Map.Entry<String, JsonNode> entry = fields.next();
      String key = entry.getKey();
      if (!CASE_FORM_KEY_VALUES.contains(key)) {
        throw new IllegalArgumentException("Unsupported Hindi case-form key: " + key);
      }
      values.put(key, requiredTextValue(entry.getValue(), "forms." + key));
    }
    if (values.isEmpty()) {
      throw new IllegalArgumentException("Hindi case-form sample must include forms");
    }
    return unmodifiableLinkedMap(values);
  }

  private Map<String, Integer> featureMap(JsonNode node, String field, Set<String> allowedKeys) {
    Map<String, Integer> values = new LinkedHashMap<>();
    for (var fields = node.fields(); fields.hasNext(); ) {
      Map.Entry<String, JsonNode> entry = fields.next();
      String key = entry.getKey();
      if (allowedKeys != null && !allowedKeys.contains(key)) {
        throw new IllegalArgumentException("Unsupported Hindi " + field + " key: " + key);
      }
      int value = requiredIntValue(entry.getValue(), field + "." + key);
      if (value < 0) {
        throw new IllegalArgumentException("Feature count must be non-negative for " + field);
      }
      values.put(key, value);
    }
    return unmodifiableLinkedMap(values);
  }

  private Map<String, Integer> numericFeatureMap(
      JsonNode node, String field, int minKey, int maxKey) {
    Map<String, Integer> values = new LinkedHashMap<>();
    for (var fields = node.fields(); fields.hasNext(); ) {
      Map.Entry<String, JsonNode> entry = fields.next();
      int key;
      try {
        key = Integer.parseInt(entry.getKey());
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("Expected numeric Hindi " + field + " key", e);
      }
      if (key < minKey || key > maxKey) {
        throw new IllegalArgumentException("Hindi " + field + " key out of range: " + key);
      }
      int value = requiredIntValue(entry.getValue(), field + "." + key);
      if (value < 0) {
        throw new IllegalArgumentException("Feature count must be non-negative for " + field);
      }
      values.put(entry.getKey(), value);
    }
    return unmodifiableLinkedMap(values);
  }

  private int requiredPositiveInt(JsonNode node, String field) {
    int value = requiredInt(node, field);
    if (value <= 0) {
      throw new IllegalArgumentException("Expected positive int field: " + field);
    }
    return value;
  }

  private String requiredExpectedText(JsonNode node, String field, Set<String> allowedValues) {
    String value = requiredText(node, field);
    if (!allowedValues.contains(value)) {
      throw new IllegalArgumentException("Unsupported Hindi " + field + " value: " + value);
    }
    return value;
  }

  private String nullableExpectedText(JsonNode node, String field, Set<String> allowedValues) {
    String text = optionalText(node, field);
    if (text == null) {
      return null;
    }
    if (!allowedValues.contains(text)) {
      throw new IllegalArgumentException("Unsupported Hindi " + field + " value: " + text);
    }
    return text;
  }

  private List<String> pronounFeatureArray(JsonNode node, String field) {
    List<String> values = textArray(node, field, null, "Hindi");
    for (String value : values) {
      if (!isAllowedPronounSampleFeature(value)) {
        throw new IllegalArgumentException("Unsupported Hindi " + field + " value: " + value);
      }
    }
    return values;
  }

  private boolean isAllowedPronounSampleFeature(String value) {
    if (PRONOUN_FEATURE_VALUES.contains(value)) {
      return true;
    }
    if (!value.startsWith("dependency=")) {
      return false;
    }
    return PRONOUN_DEPENDENCY_FEATURE_VALUES.contains(value.substring("dependency=".length()));
  }

  private static int sumValues(Map<String, Integer> values) {
    int sum = 0;
    for (int value : values.values()) {
      sum += value;
    }
    return sum;
  }

  private static int weightedNumericKeySum(Map<String, Integer> values) {
    int sum = 0;
    for (Map.Entry<String, Integer> entry : values.entrySet()) {
      sum += Integer.parseInt(entry.getKey()) * entry.getValue();
    }
    return sum;
  }

  public record HindiPackSurvey(
      String schema,
      String locale,
      List<Source> sources,
      Provenance provenance,
      Counts counts,
      Features features,
      PackShape packShape,
      Samples samples) {

    public HindiPackSurvey {
      schema = requireExpected(schema, "schema", EXPECTED_SCHEMA);
      locale = requireExpected(locale, "locale", EXPECTED_LOCALE);
      sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
      provenance = Objects.requireNonNull(provenance, "provenance");
      counts = Objects.requireNonNull(counts, "counts");
      features = Objects.requireNonNull(features, "features");
      packShape = Objects.requireNonNull(packShape, "packShape");
      samples = Objects.requireNonNull(samples, "samples");
    }
  }

  public record Source(String path, long byteSize, String sha256, boolean gitLfsPointer) {

    public Source {
      path = requireText(path, "path");
      sha256 = requireText(sha256, "sha256");
      if (byteSize < 0) {
        throw new IllegalArgumentException("byteSize must be non-negative");
      }
    }
  }

  public record Provenance(String license, String generator, List<String> sourceLabels) {

    public Provenance {
      license = requireText(license, "license");
      generator = requireText(generator, "generator");
      sourceLabels = requireStringList(sourceLabels, "sourceLabels", null);
    }
  }

  public record Counts(
      int agreementEntries,
      int agreementSurfaces,
      int ambiguousAgreementSurfaces,
      int dictionaryEntries,
      int dictionarySkippedLines,
      int inflectionalPatterns,
      int missingInflectionPatterns,
      int pronounRows,
      int termEntries,
      int termSurfaces,
      int usedAgreementPatterns) {

    public Counts {
      requireNonNegative(agreementEntries, "agreementEntries");
      requireNonNegative(agreementSurfaces, "agreementSurfaces");
      requireNonNegative(ambiguousAgreementSurfaces, "ambiguousAgreementSurfaces");
      requireNonNegative(dictionaryEntries, "dictionaryEntries");
      requireNonNegative(dictionarySkippedLines, "dictionarySkippedLines");
      requireNonNegative(inflectionalPatterns, "inflectionalPatterns");
      requireNonNegative(missingInflectionPatterns, "missingInflectionPatterns");
      requireNonNegative(pronounRows, "pronounRows");
      requireNonNegative(termEntries, "termEntries");
      requireNonNegative(termSurfaces, "termSurfaces");
      requireNonNegative(usedAgreementPatterns, "usedAgreementPatterns");
    }
  }

  public record Features(
      Map<String, Integer> inflectionPatterns,
      Map<String, Integer> partOfSpeech,
      Map<String, Integer> patternSlotAnimacy,
      Map<String, Integer> patternSlotCases,
      Map<String, Integer> patternSlotGenders,
      Map<String, Integer> patternSlotNumbers,
      Map<String, Integer> patternSlotOtherAttributes,
      Map<String, Integer> pronounDependencyFeatures,
      Map<String, Integer> pronounFeatures,
      Map<String, Integer> slotsPerPattern,
      int templateRows,
      Map<String, Integer> termAnimacy,
      Map<String, Integer> termCase,
      Map<String, Integer> termGender,
      Map<String, Integer> termNumber) {

    public Features {
      inflectionPatterns =
          unmodifiableLinkedMap(Objects.requireNonNull(inflectionPatterns, "inflectionPatterns"));
      partOfSpeech = unmodifiableLinkedMap(Objects.requireNonNull(partOfSpeech, "partOfSpeech"));
      patternSlotAnimacy =
          unmodifiableLinkedMap(Objects.requireNonNull(patternSlotAnimacy, "patternSlotAnimacy"));
      patternSlotCases =
          unmodifiableLinkedMap(Objects.requireNonNull(patternSlotCases, "patternSlotCases"));
      patternSlotGenders =
          unmodifiableLinkedMap(Objects.requireNonNull(patternSlotGenders, "patternSlotGenders"));
      patternSlotNumbers =
          unmodifiableLinkedMap(Objects.requireNonNull(patternSlotNumbers, "patternSlotNumbers"));
      patternSlotOtherAttributes =
          unmodifiableLinkedMap(
              Objects.requireNonNull(patternSlotOtherAttributes, "patternSlotOtherAttributes"));
      pronounDependencyFeatures =
          unmodifiableLinkedMap(
              Objects.requireNonNull(pronounDependencyFeatures, "pronounDependencyFeatures"));
      pronounFeatures =
          unmodifiableLinkedMap(Objects.requireNonNull(pronounFeatures, "pronounFeatures"));
      slotsPerPattern =
          unmodifiableLinkedMap(Objects.requireNonNull(slotsPerPattern, "slotsPerPattern"));
      requireNonNegative(templateRows, "templateRows");
      termAnimacy = unmodifiableLinkedMap(Objects.requireNonNull(termAnimacy, "termAnimacy"));
      termCase = unmodifiableLinkedMap(Objects.requireNonNull(termCase, "termCase"));
      termGender = unmodifiableLinkedMap(Objects.requireNonNull(termGender, "termGender"));
      termNumber = unmodifiableLinkedMap(Objects.requireNonNull(termNumber, "termNumber"));
    }
  }

  public record PackShape(
      CaseFormPack caseFormPack,
      List<String> metadataBits,
      List<String> pronounDependencyKeys,
      int pronounTableRows,
      String reason,
      String recommendation,
      List<String> termCases) {

    public PackShape {
      caseFormPack = Objects.requireNonNull(caseFormPack, "caseFormPack");
      metadataBits = requireStringList(metadataBits, "metadataBits", null);
      pronounDependencyKeys =
          requireStringList(pronounDependencyKeys, "pronounDependencyKeys", null);
      requireNonNegative(pronounTableRows, "pronounTableRows");
      reason = requireText(reason, "reason");
      recommendation = requireText(recommendation, "recommendation");
      termCases = requireStringList(termCases, "termCases", null);
    }
  }

  public record CaseFormPack(
      BinaryLowerBoundBytes binaryLowerBoundBytes,
      int candidateTerms,
      int formRows,
      Map<String, Integer> skippedTerms) {

    public CaseFormPack {
      binaryLowerBoundBytes =
          Objects.requireNonNull(binaryLowerBoundBytes, "binaryLowerBoundBytes");
      requireNonNegative(candidateTerms, "candidateTerms");
      requireNonNegative(formRows, "formRows");
      skippedTerms = unmodifiableLinkedMap(Objects.requireNonNull(skippedTerms, "skippedTerms"));
    }
  }

  public record BinaryLowerBoundBytes(
      int formRowBytes, int stringPoolBytes, int termRowBytes, int totalBytes) {

    public BinaryLowerBoundBytes {
      requireNonNegative(formRowBytes, "formRowBytes");
      requireNonNegative(stringPoolBytes, "stringPoolBytes");
      requireNonNegative(termRowBytes, "termRowBytes");
      requireNonNegative(totalBytes, "totalBytes");
    }
  }

  public record Samples(
      List<AmbiguousAgreementSurfaceSample> ambiguousAgreementSurfaces,
      List<CaseFormTermSample> caseFormTerms,
      List<String> missingInflectionPatterns,
      List<PronounSample> pronouns,
      List<TermEntrySample> termEntries) {

    public Samples {
      ambiguousAgreementSurfaces =
          List.copyOf(
              Objects.requireNonNull(ambiguousAgreementSurfaces, "ambiguousAgreementSurfaces"));
      caseFormTerms = List.copyOf(Objects.requireNonNull(caseFormTerms, "caseFormTerms"));
      missingInflectionPatterns =
          requireStringList(missingInflectionPatterns, "missingInflectionPatterns", null);
      pronouns = List.copyOf(Objects.requireNonNull(pronouns, "pronouns"));
      termEntries = List.copyOf(Objects.requireNonNull(termEntries, "termEntries"));
    }
  }

  public record AmbiguousAgreementSurfaceSample(String surface, List<String> reasons, int entries) {

    public AmbiguousAgreementSurfaceSample {
      surface = requireText(surface, "surface");
      reasons = requireStringList(reasons, "reasons", AMBIGUITY_REASON_VALUES);
      if (entries <= 0) {
        throw new IllegalArgumentException("Hindi ambiguous sample entries must be positive");
      }
    }
  }

  public record CaseFormTermSample(
      String termId,
      String text,
      String gender,
      String animacy,
      String pattern,
      Map<String, String> forms) {

    public CaseFormTermSample {
      termId = requireText(termId, "termId");
      text = requireText(text, "text");
      gender = requireExpectedValue(gender, "gender", SAMPLE_GENDER_VALUES);
      if (animacy != null) {
        animacy = requireExpectedValue(animacy, "animacy", SAMPLE_ANIMACY_VALUES);
      }
      pattern = requireText(pattern, "pattern");
      forms = unmodifiableLinkedMap(Objects.requireNonNull(forms, "forms"));
    }
  }

  public record PronounSample(List<String> features, int line, String value) {

    public PronounSample {
      features = requirePronounSampleFeatures(features);
      if (line <= 0) {
        throw new IllegalArgumentException("Hindi pronoun sample line must be positive");
      }
      value = requireText(value, "value");
    }
  }

  public record TermEntrySample(
      List<String> animacy,
      List<String> cases,
      List<String> gender,
      List<String> inflections,
      int line,
      List<String> number,
      List<String> partOfSpeech,
      String surface) {

    public TermEntrySample {
      animacy = requireStringList(animacy, "animacy", SAMPLE_ANIMACY_VALUES);
      cases = requireStringList(cases, "case", TERM_CASE_VALUES);
      gender = requireStringList(gender, "gender", SAMPLE_GENDER_VALUES);
      inflections = requireStringList(inflections, "inflections", null);
      if (line <= 0) {
        throw new IllegalArgumentException("Hindi term-entry sample line must be positive");
      }
      number = requireStringList(number, "number", SAMPLE_NUMBER_VALUES);
      partOfSpeech = requireStringList(partOfSpeech, "partOfSpeech", SAMPLE_PART_OF_SPEECH_VALUES);
      surface = requireText(surface, "surface");
    }
  }

  private static List<String> requireStringList(
      List<String> values, String field, Set<String> allowedValues) {
    List<String> copied = new ArrayList<>();
    for (String value : Objects.requireNonNull(values, field)) {
      value = requireText(value, field);
      if (allowedValues != null && !allowedValues.contains(value)) {
        throw new IllegalArgumentException("Unsupported Hindi " + field + " value: " + value);
      }
      copied.add(value);
    }
    return List.copyOf(copied);
  }

  private static List<String> requirePronounSampleFeatures(List<String> values) {
    List<String> copied = new ArrayList<>();
    for (String value : Objects.requireNonNull(values, "features")) {
      value = requireText(value, "features");
      if (!PRONOUN_FEATURE_VALUES.contains(value) && !isAllowedPronounDependencyFeature(value)) {
        throw new IllegalArgumentException("Unsupported Hindi features value: " + value);
      }
      copied.add(value);
    }
    return List.copyOf(copied);
  }

  private static boolean isAllowedPronounDependencyFeature(String value) {
    if (!value.startsWith("dependency=")) {
      return false;
    }
    return PRONOUN_DEPENDENCY_FEATURE_VALUES.contains(value.substring("dependency=".length()));
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
      throw new IllegalArgumentException("Unsupported Hindi " + field + " value: " + value);
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
