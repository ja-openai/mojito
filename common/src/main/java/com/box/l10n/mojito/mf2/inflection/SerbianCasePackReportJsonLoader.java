package com.box.l10n.mojito.mf2.inflection;

import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.requiredArray;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.requiredBoolean;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.requiredInt;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.requiredIntValue;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.requiredLong;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.requiredObject;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.requiredText;
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
 * Loads the generated Serbian case-pack report. The report is a generator contract, not the final
 * runtime case-pack format; strict validation here keeps the next compact Java loader honest.
 */
@GeneratorSupport
class SerbianCasePackReportJsonLoader {

  static final String EXPECTED_SCHEMA = "mojito-mf2-inflection/sr-case-pack-report/v0";
  static final String EXPECTED_LOCALE = "sr";

  private static final Set<String> CASE_VALUES =
      Set.of(
          "<missing>",
          "accusative",
          "dative",
          "genitive",
          "instrumental",
          "locative",
          "nominative",
          "vocative");
  private static final Set<String> GENDER_VALUES =
      Set.of("<missing>", "feminine", "masculine", "neuter");
  private static final Set<String> NUMBER_VALUES = Set.of("<missing>", "plural", "singular");
  private static final Set<String> ANIMACY_VALUES = Set.of("<missing>", "animate", "inanimate");
  private static final Set<String> PART_OF_SPEECH_VALUES = Set.of("noun", "proper-noun");
  private static final Set<String> AMBIGUITY_REASON_VALUES =
      Set.of(
          "multiple-analyses",
          "multiple-animacy",
          "multiple-cases",
          "multiple-genders",
          "multiple-inflections",
          "multiple-numbers",
          "multiple-parts-of-speech");
  private static final Set<String> CASE_FORM_BLOCKED_REASON_VALUES =
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

  SerbianCasePackReportJsonLoader() {
    this(new ObjectMapper());
  }

  SerbianCasePackReportJsonLoader(ObjectMapper objectMapper) {
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
  }

  public SerbianCasePackReport load(String json) {
    Objects.requireNonNull(json, "json");
    return load(objectMapper.readTreeUnchecked(json));
  }

  public SerbianCasePackReport load(JsonNode root) {
    SerbianCasePackReport report =
        new SerbianCasePackReport(
            requiredText(root, "schema"),
            requiredText(root, "locale"),
            loadSources(requiredArray(root, "sources")),
            loadProvenance(requiredObject(root, "provenance")),
            loadCounts(requiredObject(root, "counts")),
            loadFeatures(requiredObject(root, "features")),
            loadReviewPolicy(requiredObject(root, "reviewPolicy")),
            loadSizeEstimates(requiredObject(root, "sizeEstimates")),
            loadSamples(requiredObject(root, "samples")));

    validateProvenance(report);
    validateCounts(report.counts(), report.samples());
    validateReviewPolicy(report.counts(), report.features(), report.reviewPolicy());
    validateSizeEstimates(report.sizeEstimates());
    return report;
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
        textArray(requiredArray(node, "sourceLabels"), "sourceLabels", null, "Serbian"));
  }

  private Counts loadCounts(JsonNode node) {
    return new Counts(
        requiredInt(node, "dictionaryEntries"),
        requiredInt(node, "supportedEntries"),
        requiredInt(node, "uniqueSupportedSurfaces"),
        requiredInt(node, "skippedLines"),
        requiredInt(node, "ambiguousSupportedSurfaces"),
        requiredInt(node, "nominativeSingularCandidates"),
        requiredInt(node, "dictionaryInflectionPatterns"),
        requiredInt(node, "missingInflectionPatterns"),
        requiredInt(node, "inflectionalPatterns"),
        requiredInt(node, "nounInflectionalPatterns"),
        requiredInt(node, "usedInflectionalPatterns"));
  }

  private Features loadFeatures(JsonNode node) {
    return new Features(
        featureMap(requiredObject(node, "case"), "case", CASE_VALUES),
        featureMap(requiredObject(node, "gender"), "gender", GENDER_VALUES),
        featureMap(requiredObject(node, "number"), "number", NUMBER_VALUES),
        featureMap(requiredObject(node, "animacy"), "animacy", ANIMACY_VALUES),
        featureMap(requiredObject(node, "partOfSpeech"), "partOfSpeech", PART_OF_SPEECH_VALUES),
        featureMap(requiredObject(node, "patternSlotCases"), "patternSlotCases", CASE_VALUES),
        featureMap(requiredObject(node, "patternSlotGenders"), "patternSlotGenders", GENDER_VALUES),
        featureMap(requiredObject(node, "patternSlotNumbers"), "patternSlotNumbers", NUMBER_VALUES),
        featureMap(
            requiredObject(node, "ambiguityReasons"), "ambiguityReasons", AMBIGUITY_REASON_VALUES));
  }

  private SizeEstimates loadSizeEstimates(JsonNode node) {
    return new SizeEstimates(
        requiredInt(node, "surfaceStringPoolBytes"),
        requiredInt(node, "patternTemplateStringPoolBytes"),
        requiredInt(node, "exactSurfaceRowBytes"),
        requiredInt(node, "patternSlotRows"),
        requiredInt(node, "patternSlotRowBytes"),
        requiredInt(node, "simpleCasePackBytes"));
  }

  private ReviewPolicy loadReviewPolicy(JsonNode node) {
    return new ReviewPolicy(
        requiredText(node, "runtimeExport"),
        requiredInt(node, "automaticExportTerms"),
        requiredInt(node, "reviewRequiredSurfaces"),
        requiredInt(node, "blockedDictionaryEntries"),
        featureMap(
            requiredObject(node, "reviewRequiredReasons"),
            "reviewRequiredReasons",
            AMBIGUITY_REASON_VALUES),
        featureMap(
            requiredObject(node, "blockedReasons"),
            "blockedReasons",
            CASE_FORM_BLOCKED_REASON_VALUES));
  }

  private Samples loadSamples(JsonNode node) {
    List<CompactEntry> nominativeSingularCandidates = new ArrayList<>();
    for (JsonNode entryNode : requiredArray(node, "nominativeSingularCandidates")) {
      nominativeSingularCandidates.add(loadCompactEntry(entryNode));
    }

    List<AmbiguousSurface> ambiguousSurfaces = new ArrayList<>();
    for (JsonNode ambiguousNode : requiredArray(node, "ambiguousSurfaces")) {
      List<CompactEntry> entries = new ArrayList<>();
      for (JsonNode entryNode : requiredArray(ambiguousNode, "entries")) {
        entries.add(loadCompactEntry(entryNode));
      }
      ambiguousSurfaces.add(
          new AmbiguousSurface(
              requiredText(ambiguousNode, "surface"),
              textArray(
                  requiredArray(ambiguousNode, "reasons"),
                  "reasons",
                  AMBIGUITY_REASON_VALUES,
                  "Serbian"),
              List.copyOf(entries)));
    }

    return new Samples(
        List.copyOf(nominativeSingularCandidates),
        List.copyOf(ambiguousSurfaces),
        textArray(
            requiredArray(node, "missingInflectionPatterns"),
            "missingInflectionPatterns",
            null,
            "Serbian"));
  }

  private CompactEntry loadCompactEntry(JsonNode node) {
    return new CompactEntry(
        requiredText(node, "surface"),
        requiredPositiveInt(node, "line"),
        textArray(
            requiredArray(node, "partOfSpeech"), "partOfSpeech", PART_OF_SPEECH_VALUES, "Serbian"),
        textArray(requiredArray(node, "case"), "case", CASE_VALUES, "Serbian"),
        textArray(requiredArray(node, "gender"), "gender", GENDER_VALUES, "Serbian"),
        textArray(requiredArray(node, "number"), "number", NUMBER_VALUES, "Serbian"),
        textArray(requiredArray(node, "animacy"), "animacy", ANIMACY_VALUES, "Serbian"),
        textArray(requiredArray(node, "inflections"), "inflections", null, "Serbian"));
  }

  private Map<String, Integer> featureMap(JsonNode node, String field, Set<String> allowedValues) {
    Map<String, Integer> values = new LinkedHashMap<>();
    for (var fields = node.fields(); fields.hasNext(); ) {
      Map.Entry<String, JsonNode> entry = fields.next();
      String key = entry.getKey();
      validateAllowedValue(field, key, allowedValues);
      int value = requiredIntValue(entry.getValue(), field + "." + key);
      if (value < 0) {
        throw new IllegalArgumentException("Feature count must be non-negative for " + field);
      }
      values.put(key, value);
    }
    return unmodifiableLinkedMap(values);
  }

  private void validateProvenance(SerbianCasePackReport report) {
    if (report.sources().isEmpty()) {
      throw new IllegalArgumentException("Serbian case-pack report must include sources");
    }
    if (report.provenance().sourceLabels().size() != report.sources().size()) {
      throw new IllegalArgumentException("Provenance source label count does not match sources");
    }
  }

  private void validateCounts(Counts counts, Samples samples) {
    if (counts.supportedEntries() > counts.dictionaryEntries()) {
      throw new IllegalArgumentException("Supported entries exceed dictionary entries");
    }
    if (counts.uniqueSupportedSurfaces() > counts.supportedEntries()) {
      throw new IllegalArgumentException("Unique supported surfaces exceed supported entries");
    }
    if (counts.usedInflectionalPatterns() > counts.dictionaryInflectionPatterns()) {
      throw new IllegalArgumentException("Used patterns exceed dictionary pattern count");
    }
    if (counts.usedInflectionalPatterns() > counts.inflectionalPatterns()) {
      throw new IllegalArgumentException("Used patterns exceed inflectional pattern count");
    }
    if (counts.usedInflectionalPatterns() > counts.nounInflectionalPatterns()) {
      throw new IllegalArgumentException("Used patterns exceed noun inflectional pattern count");
    }
    if (counts.missingInflectionPatterns() > counts.dictionaryInflectionPatterns()) {
      throw new IllegalArgumentException("Missing patterns exceed dictionary pattern count");
    }
    if (samples.nominativeSingularCandidates().size() > counts.nominativeSingularCandidates()) {
      throw new IllegalArgumentException(
          "Nominative singular sample count exceeds reported candidate count");
    }
    if (samples.ambiguousSurfaces().size() > counts.ambiguousSupportedSurfaces()) {
      throw new IllegalArgumentException("Ambiguous surface sample count exceeds reported count");
    }
    if (samples.missingInflectionPatterns().size() > counts.missingInflectionPatterns()) {
      throw new IllegalArgumentException("Missing pattern sample count exceeds reported count");
    }
  }

  private void validateSizeEstimates(SizeEstimates sizeEstimates) {
    int expectedSimpleCasePackBytes =
        sizeEstimates.surfaceStringPoolBytes()
            + sizeEstimates.patternTemplateStringPoolBytes()
            + sizeEstimates.exactSurfaceRowBytes()
            + sizeEstimates.patternSlotRowBytes();
    if (sizeEstimates.simpleCasePackBytes() != expectedSimpleCasePackBytes) {
      throw new IllegalArgumentException("Simple case-pack byte estimate does not match parts");
    }
  }

  private void validateReviewPolicy(Counts counts, Features features, ReviewPolicy reviewPolicy) {
    if (!"closed-world-case-forms".equals(reviewPolicy.runtimeExport())) {
      throw new IllegalArgumentException("Unsupported Serbian runtime export policy");
    }
    if (reviewPolicy.reviewRequiredSurfaces() != counts.ambiguousSupportedSurfaces()) {
      throw new IllegalArgumentException(
          "Serbian review-required surfaces must match ambiguous supported surfaces");
    }
    if (reviewPolicy.blockedDictionaryEntries() + reviewPolicy.automaticExportTerms()
        != counts.dictionaryEntries()) {
      throw new IllegalArgumentException("Serbian review policy must cover every dictionary entry");
    }
    if (sumValues(reviewPolicy.blockedReasons()) != reviewPolicy.blockedDictionaryEntries()) {
      throw new IllegalArgumentException(
          "Serbian blocked reasons must match blocked dictionary entries");
    }
    if (!reviewPolicy.reviewRequiredReasons().equals(features.ambiguityReasons())) {
      throw new IllegalArgumentException(
          "Serbian review-required reasons must match ambiguity reasons");
    }
  }

  private int requiredPositiveInt(JsonNode node, String field) {
    int value = requiredInt(node, field);
    if (value <= 0) {
      throw new IllegalArgumentException("Expected positive int field: " + field);
    }
    return value;
  }

  private void validateAllowedValue(String field, String value, Set<String> allowedValues) {
    if (!allowedValues.contains(value)) {
      throw new IllegalArgumentException("Unsupported Serbian " + field + " value: " + value);
    }
  }

  private static int sumValues(Map<String, Integer> values) {
    int sum = 0;
    for (int value : values.values()) {
      sum += value;
    }
    return sum;
  }

  public record SerbianCasePackReport(
      String schema,
      String locale,
      List<Source> sources,
      Provenance provenance,
      Counts counts,
      Features features,
      ReviewPolicy reviewPolicy,
      SizeEstimates sizeEstimates,
      Samples samples) {

    public SerbianCasePackReport {
      schema = requireExpected(schema, "schema", EXPECTED_SCHEMA);
      locale = requireExpected(locale, "locale", EXPECTED_LOCALE);
      sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
      provenance = Objects.requireNonNull(provenance, "provenance");
      counts = Objects.requireNonNull(counts, "counts");
      features = Objects.requireNonNull(features, "features");
      reviewPolicy = Objects.requireNonNull(reviewPolicy, "reviewPolicy");
      sizeEstimates = Objects.requireNonNull(sizeEstimates, "sizeEstimates");
      samples = Objects.requireNonNull(samples, "samples");
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

  public record Provenance(String license, String generator, List<String> sourceLabels) {

    public Provenance {
      license = requireText(license, "license");
      generator = requireText(generator, "generator");
      sourceLabels = requireStringList(sourceLabels, "sourceLabels", null);
    }
  }

  public record Counts(
      int dictionaryEntries,
      int supportedEntries,
      int uniqueSupportedSurfaces,
      int skippedLines,
      int ambiguousSupportedSurfaces,
      int nominativeSingularCandidates,
      int dictionaryInflectionPatterns,
      int missingInflectionPatterns,
      int inflectionalPatterns,
      int nounInflectionalPatterns,
      int usedInflectionalPatterns) {

    public Counts {
      if (dictionaryEntries < 0
          || supportedEntries < 0
          || uniqueSupportedSurfaces < 0
          || skippedLines < 0
          || ambiguousSupportedSurfaces < 0
          || nominativeSingularCandidates < 0
          || dictionaryInflectionPatterns < 0
          || missingInflectionPatterns < 0
          || inflectionalPatterns < 0
          || nounInflectionalPatterns < 0
          || usedInflectionalPatterns < 0) {
        throw new IllegalArgumentException("count values must be non-negative");
      }
    }
  }

  public record Features(
      Map<String, Integer> grammaticalCase,
      Map<String, Integer> gender,
      Map<String, Integer> number,
      Map<String, Integer> animacy,
      Map<String, Integer> partOfSpeech,
      Map<String, Integer> patternSlotCases,
      Map<String, Integer> patternSlotGenders,
      Map<String, Integer> patternSlotNumbers,
      Map<String, Integer> ambiguityReasons) {

    public Features {
      grammaticalCase = unmodifiableLinkedMap(Objects.requireNonNull(grammaticalCase, "case"));
      gender = unmodifiableLinkedMap(Objects.requireNonNull(gender, "gender"));
      number = unmodifiableLinkedMap(Objects.requireNonNull(number, "number"));
      animacy = unmodifiableLinkedMap(Objects.requireNonNull(animacy, "animacy"));
      partOfSpeech = unmodifiableLinkedMap(Objects.requireNonNull(partOfSpeech, "partOfSpeech"));
      patternSlotCases =
          unmodifiableLinkedMap(Objects.requireNonNull(patternSlotCases, "patternSlotCases"));
      patternSlotGenders =
          unmodifiableLinkedMap(Objects.requireNonNull(patternSlotGenders, "patternSlotGenders"));
      patternSlotNumbers =
          unmodifiableLinkedMap(Objects.requireNonNull(patternSlotNumbers, "patternSlotNumbers"));
      ambiguityReasons =
          unmodifiableLinkedMap(Objects.requireNonNull(ambiguityReasons, "ambiguityReasons"));
    }
  }

  public record ReviewPolicy(
      String runtimeExport,
      int automaticExportTerms,
      int reviewRequiredSurfaces,
      int blockedDictionaryEntries,
      Map<String, Integer> reviewRequiredReasons,
      Map<String, Integer> blockedReasons) {

    public ReviewPolicy {
      runtimeExport = requireText(runtimeExport, "runtimeExport");
      if (automaticExportTerms < 0 || reviewRequiredSurfaces < 0 || blockedDictionaryEntries < 0) {
        throw new IllegalArgumentException("Serbian review-policy counts must be non-negative");
      }
      reviewRequiredReasons =
          unmodifiableLinkedMap(
              Objects.requireNonNull(reviewRequiredReasons, "reviewRequiredReasons"));
      blockedReasons =
          unmodifiableLinkedMap(Objects.requireNonNull(blockedReasons, "blockedReasons"));
    }
  }

  public record SizeEstimates(
      int surfaceStringPoolBytes,
      int patternTemplateStringPoolBytes,
      int exactSurfaceRowBytes,
      int patternSlotRows,
      int patternSlotRowBytes,
      int simpleCasePackBytes) {

    public SizeEstimates {
      if (surfaceStringPoolBytes < 0
          || patternTemplateStringPoolBytes < 0
          || exactSurfaceRowBytes < 0
          || patternSlotRows < 0
          || patternSlotRowBytes < 0
          || simpleCasePackBytes < 0) {
        throw new IllegalArgumentException("size estimates must be non-negative");
      }
    }
  }

  public record Samples(
      List<CompactEntry> nominativeSingularCandidates,
      List<AmbiguousSurface> ambiguousSurfaces,
      List<String> missingInflectionPatterns) {

    public Samples {
      nominativeSingularCandidates =
          List.copyOf(
              Objects.requireNonNull(nominativeSingularCandidates, "nominativeSingularCandidates"));
      ambiguousSurfaces =
          List.copyOf(Objects.requireNonNull(ambiguousSurfaces, "ambiguousSurfaces"));
      missingInflectionPatterns =
          requireStringList(missingInflectionPatterns, "missingInflectionPatterns", null);
    }
  }

  public record AmbiguousSurface(String surface, List<String> reasons, List<CompactEntry> entries) {

    public AmbiguousSurface {
      surface = requireText(surface, "surface");
      reasons = requireStringList(reasons, "reasons", AMBIGUITY_REASON_VALUES);
      entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
    }
  }

  public record CompactEntry(
      String surface,
      int line,
      List<String> partOfSpeech,
      List<String> cases,
      List<String> gender,
      List<String> number,
      List<String> animacy,
      List<String> inflections) {

    public CompactEntry {
      surface = requireText(surface, "surface");
      if (line <= 0) {
        throw new IllegalArgumentException("line must be positive: " + line);
      }
      partOfSpeech = requireStringList(partOfSpeech, "partOfSpeech", PART_OF_SPEECH_VALUES);
      cases = requireStringList(cases, "case", CASE_VALUES);
      gender = requireStringList(gender, "gender", GENDER_VALUES);
      number = requireStringList(number, "number", NUMBER_VALUES);
      animacy = requireStringList(animacy, "animacy", ANIMACY_VALUES);
      inflections = requireStringList(inflections, "inflections", null);
    }
  }

  private static List<String> requireStringList(
      List<String> values, String field, Set<String> allowedValues) {
    List<String> copied = new ArrayList<>();
    for (String value : Objects.requireNonNull(values, field)) {
      value = requireText(value, field);
      if (allowedValues != null && !allowedValues.contains(value)) {
        throw new IllegalArgumentException("Unsupported Serbian " + field + " value: " + value);
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

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return value;
  }
}
