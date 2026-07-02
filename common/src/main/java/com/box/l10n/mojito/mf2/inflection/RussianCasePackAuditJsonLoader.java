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
 * Loads the generated Russian case-pack audit. This is not a runtime pack; it keeps the
 * generator-side policy for Russian variant forms explicit and machine checked.
 */
@GeneratorSupport
class RussianCasePackAuditJsonLoader {

  static final String EXPECTED_SCHEMA = "mojito-mf2-inflection/ru-case-pack-audit/v0";
  static final String EXPECTED_LOCALE = "ru";

  private static final Set<String> CASE_VALUES =
      Set.of(
          "<missing>",
          "accusative",
          "dative",
          "genitive",
          "instrumental",
          "nominative",
          "prepositional");
  private static final Set<String> PATTERN_SLOT_CASE_VALUES =
      Set.of(
          "<missing>",
          "accusative",
          "dative",
          "genitive",
          "instrumental",
          "locative",
          "nominative",
          "partitive",
          "prepositional",
          "vocative");
  private static final Set<String> GENDER_VALUES =
      Set.of("<missing>", "feminine", "masculine", "neuter");
  private static final Set<String> PATTERN_SLOT_GENDER_VALUES =
      Set.of("<missing>", "common", "feminine", "masculine", "neuter");
  private static final Set<String> NUMBER_VALUES = Set.of("<missing>", "plural", "singular");
  private static final Set<String> ANIMACY_VALUES = Set.of("<missing>", "animate", "inanimate");
  private static final Set<String> PATTERN_SLOT_ANIMACY_VALUES =
      Set.of("<missing>", "animate", "human", "inanimate");
  private static final Set<String> PART_OF_SPEECH_VALUES =
      Set.of("adjective", "adverb", "noun", "numeral", "proper-noun", "verb");
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
          "missing-case-form-keys",
          "missing-or-ambiguous-gender",
          "missing-or-ambiguous-inflection",
          "missing-pattern",
          "suffix-mismatch",
          "unsupported-pattern-part-of-speech");
  private static final Set<String> CASE_FORM_BLOCKED_REASON_VALUES =
      Set.of(
          "conflicting-form-key",
          "duplicate-term-id",
          "missing-case-form-keys",
          "missing-or-ambiguous-gender",
          "missing-or-ambiguous-inflection",
          "missing-pattern",
          "not-nominative",
          "not-singular",
          "suffix-mismatch",
          "unsupported-part-of-speech",
          "unsupported-pattern-part-of-speech");
  private static final Set<String> CASE_FORM_KEY_VALUES =
      Set.of(
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

  private final ObjectMapper objectMapper;

  RussianCasePackAuditJsonLoader() {
    this(new ObjectMapper());
  }

  RussianCasePackAuditJsonLoader(ObjectMapper objectMapper) {
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
  }

  public RussianCasePackAudit load(String json) {
    Objects.requireNonNull(json, "json");
    return load(objectMapper.readTreeUnchecked(json));
  }

  public RussianCasePackAudit load(JsonNode root) {
    RussianCasePackAudit audit =
        new RussianCasePackAudit(
            requiredText(root, "schema"),
            requiredText(root, "locale"),
            loadSources(requiredArray(root, "sources")),
            loadProvenance(requiredObject(root, "provenance")),
            loadCounts(requiredObject(root, "counts")),
            loadFeatures(requiredObject(root, "features")),
            loadReviewPolicy(requiredObject(root, "reviewPolicy")),
            loadSizeEstimates(requiredObject(root, "sizeEstimates")),
            loadSamples(requiredObject(root, "samples")));

    validateProvenance(audit);
    validateCounts(audit.counts(), audit.samples());
    validateFeatureArithmetic(audit.counts(), audit.features(), audit.samples());
    validateReviewPolicy(audit.counts(), audit.features(), audit.reviewPolicy());
    validateSizeEstimates(audit.counts(), audit.sizeEstimates());
    return audit;
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
        textArray(requiredArray(node, "sourceLabels"), "sourceLabels", null, "Russian"));
  }

  private Counts loadCounts(JsonNode node) {
    return new Counts(
        requiredInt(node, "dictionaryEntries"),
        requiredInt(node, "supportedEntries"),
        requiredInt(node, "uniqueSupportedSurfaces"),
        requiredInt(node, "skippedLines"),
        requiredInt(node, "ambiguousSupportedSurfaces"),
        requiredInt(node, "nominativeSingularCandidates"),
        requiredInt(node, "completeCaseFormCandidates"),
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
        featureMap(
            requiredObject(node, "ambiguityReasons"), "ambiguityReasons", AMBIGUITY_REASON_VALUES),
        featureMap(
            requiredObject(node, "caseFormCandidateSkipReasons"),
            "caseFormCandidateSkipReasons",
            CASE_FORM_SKIP_REASON_VALUES),
        numericFeatureMap(
            requiredObject(node, "caseFormKeyCounts"),
            "caseFormKeyCounts",
            0,
            CASE_FORM_KEY_VALUES.size()),
        featureMap(
            requiredObject(node, "conflictingFormKeys"),
            "conflictingFormKeys",
            CASE_FORM_KEY_VALUES),
        numericFeatureMap(
            requiredObject(node, "conflictingKeysPerTerm"),
            "conflictingKeysPerTerm",
            1,
            CASE_FORM_KEY_VALUES.size()),
        featureMap(
            requiredObject(node, "patternSlotAnimacy"),
            "patternSlotAnimacy",
            PATTERN_SLOT_ANIMACY_VALUES),
        featureMap(
            requiredObject(node, "patternSlotCases"), "patternSlotCases", PATTERN_SLOT_CASE_VALUES),
        featureMap(
            requiredObject(node, "patternSlotGenders"),
            "patternSlotGenders",
            PATTERN_SLOT_GENDER_VALUES),
        featureMap(requiredObject(node, "patternSlotNumbers"), "patternSlotNumbers", NUMBER_VALUES),
        requiredInt(node, "duplicateSlotRows"),
        requiredInt(node, "maxSlotsPerPattern"),
        requiredInt(node, "patternsWithDuplicateSlots"));
  }

  private SizeEstimates loadSizeEstimates(JsonNode node) {
    return new SizeEstimates(
        requiredInt(node, "metadataStringPoolBytes"),
        requiredInt(node, "metadataRowBytes"),
        requiredInt(node, "metadataLowerBoundBytes"),
        requiredInt(node, "caseFormRowsIfEager"),
        requiredInt(node, "caseFormRowBytesIfEager"));
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
                  "Russian"),
              List.copyOf(entries)));
    }

    List<CaseFormCandidate> conflictingCaseFormCandidates = new ArrayList<>();
    for (JsonNode candidateNode : requiredArray(node, "conflictingCaseFormCandidates")) {
      conflictingCaseFormCandidates.add(loadCaseFormCandidate(candidateNode));
    }

    return new Samples(
        List.copyOf(nominativeSingularCandidates),
        List.copyOf(ambiguousSurfaces),
        List.copyOf(conflictingCaseFormCandidates),
        textArray(
            requiredArray(node, "missingInflectionPatterns"),
            "missingInflectionPatterns",
            null,
            "Russian"));
  }

  private CompactEntry loadCompactEntry(JsonNode node) {
    return new CompactEntry(
        requiredText(node, "surface"),
        requiredPositiveInt(node, "line"),
        textArray(
            requiredArray(node, "partOfSpeech"), "partOfSpeech", PART_OF_SPEECH_VALUES, "Russian"),
        textArray(requiredArray(node, "case"), "case", CASE_VALUES, "Russian"),
        textArray(requiredArray(node, "gender"), "gender", GENDER_VALUES, "Russian"),
        textArray(requiredArray(node, "number"), "number", NUMBER_VALUES, "Russian"),
        textArray(requiredArray(node, "animacy"), "animacy", ANIMACY_VALUES, "Russian"),
        textArray(requiredArray(node, "inflections"), "inflections", null, "Russian"));
  }

  private CaseFormCandidate loadCaseFormCandidate(JsonNode node) {
    List<CaseFormConflict> conflicts = new ArrayList<>();
    for (JsonNode conflictNode : requiredArray(node, "conflicts")) {
      conflicts.add(
          new CaseFormConflict(
              requiredText(conflictNode, "formKey"),
              requiredText(conflictNode, "firstValue"),
              requiredText(conflictNode, "variantValue"),
              requiredText(conflictNode, "variantTemplate")));
    }

    return new CaseFormCandidate(
        requiredText(node, "surface"),
        requiredPositiveInt(node, "line"),
        textArray(
            requiredArray(node, "partOfSpeech"), "partOfSpeech", PART_OF_SPEECH_VALUES, "Russian"),
        textArray(requiredArray(node, "case"), "case", CASE_VALUES, "Russian"),
        textArray(requiredArray(node, "gender"), "gender", GENDER_VALUES, "Russian"),
        textArray(requiredArray(node, "number"), "number", NUMBER_VALUES, "Russian"),
        textArray(requiredArray(node, "animacy"), "animacy", ANIMACY_VALUES, "Russian"),
        textArray(requiredArray(node, "inflections"), "inflections", null, "Russian"),
        requiredText(node, "inflectionPattern"),
        List.copyOf(conflicts));
  }

  private void validateProvenance(RussianCasePackAudit audit) {
    if (audit.sources().isEmpty()) {
      throw new IllegalArgumentException("Russian case-pack audit must include sources");
    }
    if (audit.provenance().sourceLabels().size() != audit.sources().size()) {
      throw new IllegalArgumentException(
          "Russian provenance source label count must match sources");
    }
  }

  private void validateCounts(Counts counts, Samples samples) {
    if (counts.supportedEntries() > counts.dictionaryEntries()) {
      throw new IllegalArgumentException("Supported entries exceed dictionary entries");
    }
    if (counts.uniqueSupportedSurfaces() > counts.supportedEntries()) {
      throw new IllegalArgumentException("Unique supported surfaces exceed supported entries");
    }
    if (counts.ambiguousSupportedSurfaces() > counts.uniqueSupportedSurfaces()) {
      throw new IllegalArgumentException(
          "Ambiguous supported surfaces exceed unique supported surfaces");
    }
    if (counts.completeCaseFormCandidates() > counts.nominativeSingularCandidates()) {
      throw new IllegalArgumentException(
          "Complete case-form candidates exceed nominative singular candidates");
    }
    if (counts.dictionaryInflectionPatterns() > counts.inflectionalPatterns()) {
      throw new IllegalArgumentException("Dictionary patterns exceed inflectional pattern count");
    }
    if (counts.nounInflectionalPatterns() > counts.inflectionalPatterns()) {
      throw new IllegalArgumentException("Noun patterns exceed inflectional pattern count");
    }
    if (counts.usedInflectionalPatterns() > counts.dictionaryInflectionPatterns()) {
      throw new IllegalArgumentException("Used patterns exceed dictionary pattern count");
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
    if (counts.missingInflectionPatterns() == 0 && !samples.missingInflectionPatterns().isEmpty()) {
      throw new IllegalArgumentException("Missing-pattern samples present when count is zero");
    }
  }

  private void validateFeatureArithmetic(Counts counts, Features features, Samples samples) {
    int caseFormSkipCount = sumValues(features.caseFormCandidateSkipReasons());
    if (caseFormSkipCount + counts.completeCaseFormCandidates()
        != counts.nominativeSingularCandidates()) {
      throw new IllegalArgumentException(
          "Russian case-form skip counts do not close over nominative singular candidates");
    }

    int conflictingSkipCount =
        features.caseFormCandidateSkipReasons().getOrDefault("conflicting-form-key", 0);
    if (samples.conflictingCaseFormCandidates().size() > conflictingSkipCount) {
      throw new IllegalArgumentException(
          "Conflicting case-form samples exceed conflicting skip count");
    }
    if (sumValues(features.conflictingKeysPerTerm()) != conflictingSkipCount) {
      throw new IllegalArgumentException(
          "Russian conflicting-key distribution does not match skip count");
    }
    if (weightedNumericKeySum(features.conflictingKeysPerTerm())
        != sumValues(features.conflictingFormKeys())) {
      throw new IllegalArgumentException(
          "Russian conflicting-key distribution does not match form-key totals");
    }
  }

  private void validateSizeEstimates(Counts counts, SizeEstimates sizeEstimates) {
    if (sizeEstimates.metadataRowBytes() != counts.nominativeSingularCandidates() * 12) {
      throw new IllegalArgumentException("Russian metadata row byte estimate does not match rows");
    }
    if (sizeEstimates.metadataLowerBoundBytes()
        != sizeEstimates.metadataStringPoolBytes() + sizeEstimates.metadataRowBytes()) {
      throw new IllegalArgumentException("Russian metadata lower-bound estimate is incoherent");
    }
    int expectedCaseFormRows = counts.completeCaseFormCandidates() * CASE_FORM_KEY_VALUES.size();
    if (sizeEstimates.caseFormRowsIfEager() != expectedCaseFormRows) {
      throw new IllegalArgumentException(
          "Russian eager case-form row estimate does not match rows");
    }
    if (sizeEstimates.caseFormRowBytesIfEager() != expectedCaseFormRows * 12) {
      throw new IllegalArgumentException("Russian eager case-form byte estimate is incoherent");
    }
  }

  private void validateReviewPolicy(Counts counts, Features features, ReviewPolicy reviewPolicy) {
    if (!"closed-world-case-forms".equals(reviewPolicy.runtimeExport())) {
      throw new IllegalArgumentException("Unsupported Russian runtime export policy");
    }
    if (reviewPolicy.automaticExportTerms() > counts.completeCaseFormCandidates()) {
      throw new IllegalArgumentException(
          "Russian automatic export terms exceed complete case-form candidates");
    }
    int duplicateTermIds = reviewPolicy.blockedReasons().getOrDefault("duplicate-term-id", 0);
    if (counts.completeCaseFormCandidates() - reviewPolicy.automaticExportTerms()
        != duplicateTermIds) {
      throw new IllegalArgumentException(
          "Russian duplicate term-id count must explain non-unique complete candidates");
    }
    if (reviewPolicy.reviewRequiredSurfaces() != counts.ambiguousSupportedSurfaces()) {
      throw new IllegalArgumentException(
          "Russian review-required surfaces must match ambiguous supported surfaces");
    }
    if (reviewPolicy.blockedDictionaryEntries() + reviewPolicy.automaticExportTerms()
        != counts.dictionaryEntries()) {
      throw new IllegalArgumentException("Russian review policy must cover every dictionary entry");
    }
    if (sumValues(reviewPolicy.blockedReasons()) != reviewPolicy.blockedDictionaryEntries()) {
      throw new IllegalArgumentException(
          "Russian blocked reasons must match blocked dictionary entries");
    }
    if (!reviewPolicy.reviewRequiredReasons().equals(features.ambiguityReasons())) {
      throw new IllegalArgumentException(
          "Russian review-required reasons must match ambiguity reasons");
    }
  }

  private Map<String, Integer> featureMap(JsonNode node, String field, Set<String> allowedKeys) {
    Map<String, Integer> values = new LinkedHashMap<>();
    for (var fields = node.fields(); fields.hasNext(); ) {
      Map.Entry<String, JsonNode> entry = fields.next();
      String key = entry.getKey();
      if (!allowedKeys.contains(key)) {
        throw new IllegalArgumentException("Unsupported Russian " + field + " key: " + key);
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
        throw new IllegalArgumentException("Expected numeric Russian " + field + " key", e);
      }
      if (key < minKey || key > maxKey) {
        throw new IllegalArgumentException("Russian " + field + " key out of range: " + key);
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

  public record RussianCasePackAudit(
      String schema,
      String locale,
      List<Source> sources,
      Provenance provenance,
      Counts counts,
      Features features,
      ReviewPolicy reviewPolicy,
      SizeEstimates sizeEstimates,
      Samples samples) {

    public RussianCasePackAudit {
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
      int dictionaryEntries,
      int supportedEntries,
      int uniqueSupportedSurfaces,
      int skippedLines,
      int ambiguousSupportedSurfaces,
      int nominativeSingularCandidates,
      int completeCaseFormCandidates,
      int dictionaryInflectionPatterns,
      int missingInflectionPatterns,
      int inflectionalPatterns,
      int nounInflectionalPatterns,
      int usedInflectionalPatterns) {

    public Counts {
      requireNonNegative(dictionaryEntries, "dictionaryEntries");
      requireNonNegative(supportedEntries, "supportedEntries");
      requireNonNegative(uniqueSupportedSurfaces, "uniqueSupportedSurfaces");
      requireNonNegative(skippedLines, "skippedLines");
      requireNonNegative(ambiguousSupportedSurfaces, "ambiguousSupportedSurfaces");
      requireNonNegative(nominativeSingularCandidates, "nominativeSingularCandidates");
      requireNonNegative(completeCaseFormCandidates, "completeCaseFormCandidates");
      requireNonNegative(dictionaryInflectionPatterns, "dictionaryInflectionPatterns");
      requireNonNegative(missingInflectionPatterns, "missingInflectionPatterns");
      requireNonNegative(inflectionalPatterns, "inflectionalPatterns");
      requireNonNegative(nounInflectionalPatterns, "nounInflectionalPatterns");
      requireNonNegative(usedInflectionalPatterns, "usedInflectionalPatterns");
    }
  }

  public record Features(
      Map<String, Integer> grammaticalCase,
      Map<String, Integer> gender,
      Map<String, Integer> number,
      Map<String, Integer> animacy,
      Map<String, Integer> partOfSpeech,
      Map<String, Integer> ambiguityReasons,
      Map<String, Integer> caseFormCandidateSkipReasons,
      Map<String, Integer> caseFormKeyCounts,
      Map<String, Integer> conflictingFormKeys,
      Map<String, Integer> conflictingKeysPerTerm,
      Map<String, Integer> patternSlotAnimacy,
      Map<String, Integer> patternSlotCases,
      Map<String, Integer> patternSlotGenders,
      Map<String, Integer> patternSlotNumbers,
      int duplicateSlotRows,
      int maxSlotsPerPattern,
      int patternsWithDuplicateSlots) {

    public Features {
      grammaticalCase = unmodifiableLinkedMap(Objects.requireNonNull(grammaticalCase, "case"));
      gender = unmodifiableLinkedMap(Objects.requireNonNull(gender, "gender"));
      number = unmodifiableLinkedMap(Objects.requireNonNull(number, "number"));
      animacy = unmodifiableLinkedMap(Objects.requireNonNull(animacy, "animacy"));
      partOfSpeech = unmodifiableLinkedMap(Objects.requireNonNull(partOfSpeech, "partOfSpeech"));
      ambiguityReasons =
          unmodifiableLinkedMap(Objects.requireNonNull(ambiguityReasons, "ambiguityReasons"));
      caseFormCandidateSkipReasons =
          unmodifiableLinkedMap(
              Objects.requireNonNull(caseFormCandidateSkipReasons, "caseFormCandidateSkipReasons"));
      caseFormKeyCounts =
          unmodifiableLinkedMap(Objects.requireNonNull(caseFormKeyCounts, "caseFormKeyCounts"));
      conflictingFormKeys =
          unmodifiableLinkedMap(Objects.requireNonNull(conflictingFormKeys, "conflictingFormKeys"));
      conflictingKeysPerTerm =
          unmodifiableLinkedMap(
              Objects.requireNonNull(conflictingKeysPerTerm, "conflictingKeysPerTerm"));
      patternSlotAnimacy =
          unmodifiableLinkedMap(Objects.requireNonNull(patternSlotAnimacy, "patternSlotAnimacy"));
      patternSlotCases =
          unmodifiableLinkedMap(Objects.requireNonNull(patternSlotCases, "patternSlotCases"));
      patternSlotGenders =
          unmodifiableLinkedMap(Objects.requireNonNull(patternSlotGenders, "patternSlotGenders"));
      patternSlotNumbers =
          unmodifiableLinkedMap(Objects.requireNonNull(patternSlotNumbers, "patternSlotNumbers"));
      requireNonNegative(duplicateSlotRows, "duplicateSlotRows");
      requireNonNegative(maxSlotsPerPattern, "maxSlotsPerPattern");
      requireNonNegative(patternsWithDuplicateSlots, "patternsWithDuplicateSlots");
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
      requireNonNegative(automaticExportTerms, "automaticExportTerms");
      requireNonNegative(reviewRequiredSurfaces, "reviewRequiredSurfaces");
      requireNonNegative(blockedDictionaryEntries, "blockedDictionaryEntries");
      reviewRequiredReasons =
          unmodifiableLinkedMap(
              Objects.requireNonNull(reviewRequiredReasons, "reviewRequiredReasons"));
      blockedReasons =
          unmodifiableLinkedMap(Objects.requireNonNull(blockedReasons, "blockedReasons"));
    }
  }

  public record SizeEstimates(
      int metadataStringPoolBytes,
      int metadataRowBytes,
      int metadataLowerBoundBytes,
      int caseFormRowsIfEager,
      int caseFormRowBytesIfEager) {

    public SizeEstimates {
      requireNonNegative(metadataStringPoolBytes, "metadataStringPoolBytes");
      requireNonNegative(metadataRowBytes, "metadataRowBytes");
      requireNonNegative(metadataLowerBoundBytes, "metadataLowerBoundBytes");
      requireNonNegative(caseFormRowsIfEager, "caseFormRowsIfEager");
      requireNonNegative(caseFormRowBytesIfEager, "caseFormRowBytesIfEager");
    }
  }

  public record Samples(
      List<CompactEntry> nominativeSingularCandidates,
      List<AmbiguousSurface> ambiguousSurfaces,
      List<CaseFormCandidate> conflictingCaseFormCandidates,
      List<String> missingInflectionPatterns) {

    public Samples {
      nominativeSingularCandidates =
          List.copyOf(
              Objects.requireNonNull(nominativeSingularCandidates, "nominativeSingularCandidates"));
      ambiguousSurfaces =
          List.copyOf(Objects.requireNonNull(ambiguousSurfaces, "ambiguousSurfaces"));
      conflictingCaseFormCandidates =
          List.copyOf(
              Objects.requireNonNull(
                  conflictingCaseFormCandidates, "conflictingCaseFormCandidates"));
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

  public record CaseFormCandidate(
      String surface,
      int line,
      List<String> partOfSpeech,
      List<String> cases,
      List<String> gender,
      List<String> number,
      List<String> animacy,
      List<String> inflections,
      String inflectionPattern,
      List<CaseFormConflict> conflicts) {

    public CaseFormCandidate {
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
      inflectionPattern = requireText(inflectionPattern, "inflectionPattern");
      conflicts = List.copyOf(Objects.requireNonNull(conflicts, "conflicts"));
      if (!cases.contains("nominative") || !number.contains("singular")) {
        throw new IllegalArgumentException(
            "Russian conflicting case-form samples must be nominative singular candidates");
      }
      if (conflicts.isEmpty()) {
        throw new IllegalArgumentException("Russian conflicting case-form sample has no conflicts");
      }
    }
  }

  public record CaseFormConflict(
      String formKey, String firstValue, String variantValue, String variantTemplate) {

    public CaseFormConflict {
      formKey = requireText(formKey, "formKey");
      if (!CASE_FORM_KEY_VALUES.contains(formKey)) {
        throw new IllegalArgumentException("Unsupported Russian case-form key: " + formKey);
      }
      firstValue = requireText(firstValue, "firstValue");
      variantValue = requireText(variantValue, "variantValue");
      variantTemplate = requireText(variantTemplate, "variantTemplate");
    }
  }

  private static List<String> requireStringList(
      List<String> values, String field, Set<String> allowedValues) {
    List<String> copied = new ArrayList<>();
    for (String value : Objects.requireNonNull(values, field)) {
      value = requireText(value, field);
      if (allowedValues != null && !allowedValues.contains(value)) {
        throw new IllegalArgumentException("Unsupported Russian " + field + " value: " + value);
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

  private static void requireNonNegative(int value, String field) {
    if (value < 0) {
      throw new IllegalArgumentException(field + " must be non-negative");
    }
  }
}
