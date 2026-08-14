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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Loads the generated Malayalam pack audit. The audit pins the generator-side evidence for choosing
 * the Malayalam runtime pack shape; it is not itself a renderer-ready pack.
 */
@GeneratorSupport
class MalayalamPackAuditJsonLoader {

  static final String EXPECTED_SCHEMA = "mojito-mf2-inflection/ml-pack-audit/v0";
  static final String EXPECTED_LOCALE = "ml";
  static final String EXPECTED_PACK_RECOMMENDATION =
      "closed-world multi-case explicit-form pack first";
  static final String EXPECTED_CASE_MODE = "explicit-form-key";
  static final String EXPECTED_NUMBER_MODE = "explicit-number-option";
  static final String EXPECTED_PRONOUN_SCOPE = "inventory-only-v0";
  static final String EXPECTED_PRONOUN_AGREEMENT_POLICY =
      "separate-malayalam-pronoun-profile-later";
  static final String EXPECTED_LATER_OPTIMIZATION =
      "suffix-composition audit after closed-world fixture";

  private static final Set<String> PART_OF_SPEECH_VALUES =
      Set.of(
          "<missing>",
          "adjective",
          "adposition",
          "adverb",
          "determiner",
          "noun",
          "numeral",
          "particle",
          "pronoun",
          "proper-noun",
          "verb");
  private static final Set<String> SAMPLE_PART_OF_SPEECH_VALUES =
      Set.of(
          "adjective",
          "adposition",
          "adverb",
          "determiner",
          "noun",
          "numeral",
          "particle",
          "pronoun",
          "proper-noun",
          "verb");
  private static final Set<String> CASE_VALUES =
      Set.of(
          "<missing>",
          "accusative",
          "dative",
          "ergative",
          "genitive",
          "instrumental",
          "locative",
          "nominative",
          "oblique",
          "sociative",
          "vocative");
  private static final Set<String> SAMPLE_CASE_VALUES =
      Set.of(
          "accusative",
          "dative",
          "ergative",
          "genitive",
          "instrumental",
          "locative",
          "nominative",
          "oblique",
          "sociative",
          "vocative");
  private static final Set<String> GENDER_VALUES =
      Set.of("<missing>", "common", "feminine", "masculine", "neuter");
  private static final Set<String> SAMPLE_GENDER_VALUES =
      Set.of("common", "feminine", "masculine", "neuter");
  private static final Set<String> NUMBER_VALUES = Set.of("<missing>", "plural", "singular");
  private static final Set<String> SAMPLE_NUMBER_VALUES = Set.of("plural", "singular");
  private static final Set<String> ANIMACY_VALUES =
      Set.of("<missing>", "animate", "human", "inanimate");
  private static final Set<String> SAMPLE_ANIMACY_VALUES = Set.of("animate", "human", "inanimate");
  private static final Set<String> AMBIGUITY_REASON_VALUES =
      Set.of(
          "multiple-animacy",
          "multiple-cases",
          "multiple-entries",
          "multiple-genders",
          "multiple-inflections",
          "multiple-numbers",
          "multiple-parts-of-speech");
  private static final Set<String> SLOT_ATTRIBUTE_KEYS =
      Set.of("animacy", "case", "count", "gender", "number");
  private static final Set<String> PRONOUN_PERSON_VALUES = Set.of("first", "second", "third");
  private static final Set<String> PRONOUN_REGISTER_VALUES = Set.of("formal", "informal");
  private static final Set<String> PRONOUN_DETERMINATION_VALUES =
      Set.of("determination=dependent", "determination=independent");
  private static final Set<String> PRONOUN_FEATURE_VALUES =
      Set.of(
          "accusative",
          "dative",
          "determination=dependent",
          "determination=independent",
          "exclusive",
          "feminine",
          "first",
          "formal",
          "genitive",
          "inclusive",
          "informal",
          "instrumental",
          "locative",
          "masculine",
          "neuter",
          "nominative",
          "plural",
          "reflexive",
          "second",
          "singular",
          "sociative",
          "third");
  private static final List<String> EXPECTED_TERM_SCOPE = List.of("noun", "proper-noun");
  private static final List<String> EXPECTED_RUNTIME_OPTIONS = List.of("case", "number");
  private static final List<String> EXPECTED_METADATA_BITS =
      List.of("partOfSpeech", "gender", "animacy");

  private final ObjectMapper objectMapper;

  MalayalamPackAuditJsonLoader() {
    this(new ObjectMapper());
  }

  MalayalamPackAuditJsonLoader(ObjectMapper objectMapper) {
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
  }

  public MalayalamPackAudit load(String json) {
    Objects.requireNonNull(json, "json");
    return load(objectMapper.readTreeUnchecked(json));
  }

  public MalayalamPackAudit load(JsonNode root) {
    MalayalamPackAudit audit =
        new MalayalamPackAudit(
            requiredText(root, "schema"),
            requiredText(root, "locale"),
            requiredText(root, "description"),
            loadProvenance(requiredObject(root, "provenance")),
            loadCounts(requiredObject(root, "counts")),
            loadFeatures(requiredObject(root, "features")),
            loadPatterns(requiredObject(root, "patterns")),
            loadPackPolicy(requiredObject(root, "packPolicy")),
            loadPronouns(requiredObject(root, "pronouns")),
            loadSamples(requiredObject(root, "samples")));

    validateProvenance(audit.provenance());
    validateCounts(audit.counts(), audit.features(), audit.patterns(), audit.samples());
    validatePatternStats(audit.patterns());
    validatePronouns(audit.pronouns());
    validatePackPolicy(audit.counts(), audit.features(), audit.patterns(), audit.packPolicy());
    return audit;
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
        List.copyOf(sources),
        textArray(requiredArray(node, "sourceLabels"), "sourceLabels", null, "Malayalam"));
  }

  private Counts loadCounts(JsonNode node) {
    return new Counts(
        requiredInt(node, "agreementEntries"),
        requiredInt(node, "agreementSurfaces"),
        requiredInt(node, "ambiguousTermSurfaces"),
        requiredInt(node, "caseTaggedAgreementEntries"),
        requiredInt(node, "dictionaryEntries"),
        requiredInt(node, "genderTaggedAgreementEntries"),
        requiredInt(node, "inflectionPatterns"),
        requiredInt(node, "missingAgreementPatterns"),
        requiredInt(node, "skippedDictionaryLines"),
        requiredInt(node, "termEntries"),
        requiredInt(node, "termSurfaces"),
        requiredInt(node, "uniqueSurfaces"),
        requiredInt(node, "usedAgreementPatterns"),
        requiredInt(node, "usedTermPatterns"));
  }

  private Features loadFeatures(JsonNode node) {
    return new Features(
        featureMap(requiredObject(node, "agreementInflections"), "agreementInflections", null),
        featureMap(
            requiredObject(node, "agreementPartOfSpeech"),
            "agreementPartOfSpeech",
            SAMPLE_PART_OF_SPEECH_VALUES),
        featureMap(
            requiredObject(node, "ambiguityReasons"), "ambiguityReasons", AMBIGUITY_REASON_VALUES),
        featureMap(requiredObject(node, "animacy"), "animacy", ANIMACY_VALUES),
        featureMap(requiredObject(node, "case"), "case", CASE_VALUES),
        featureMap(requiredObject(node, "gender"), "gender", GENDER_VALUES),
        featureMap(requiredObject(node, "number"), "number", NUMBER_VALUES),
        featureMap(requiredObject(node, "partOfSpeech"), "partOfSpeech", PART_OF_SPEECH_VALUES),
        featureMap(
            requiredObject(node, "termPartOfSpeech"),
            "termPartOfSpeech",
            SAMPLE_PART_OF_SPEECH_VALUES),
        featureMap(requiredObject(node, "unknownGrammemes"), "unknownGrammemes", null));
  }

  private Patterns loadPatterns(JsonNode node) {
    return new Patterns(
        loadPatternStats(requiredObject(node, "all")),
        loadPatternStats(requiredObject(node, "usedAgreement")),
        loadPatternStats(requiredObject(node, "usedTerms")));
  }

  private PackPolicy loadPackPolicy(JsonNode node) {
    return new PackPolicy(
        requiredText(node, "recommendation"),
        requiredText(node, "reason"),
        textArray(requiredArray(node, "termScope"), "termScope", null, "Malayalam"),
        textArray(requiredArray(node, "runtimeOptions"), "runtimeOptions", null, "Malayalam"),
        textArray(requiredArray(node, "metadataBits"), "metadataBits", null, "Malayalam"),
        requiredText(node, "caseMode"),
        requiredText(node, "numberMode"),
        requiredText(node, "pronounScope"),
        requiredText(node, "pronounAgreementPolicy"),
        requiredBoolean(node, "openWorldGeneration"),
        requiredText(node, "laterOptimization"));
  }

  private PatternStats loadPatternStats(JsonNode node) {
    return new PatternStats(
        requiredInt(node, "caseSlots"),
        requiredInt(node, "duplicateSlotRows"),
        requiredInt(node, "patternsWithDuplicateSlots"),
        slotAttributes(requiredObject(node, "slotAttributes")),
        numericFeatureMap(requiredObject(node, "slotsPerPattern"), "slotsPerPattern", 0, 256));
  }

  private Map<String, Map<String, Integer>> slotAttributes(JsonNode node) {
    Map<String, Map<String, Integer>> values = new LinkedHashMap<>();
    for (var fields = node.fields(); fields.hasNext(); ) {
      Map.Entry<String, JsonNode> entry = fields.next();
      String key = entry.getKey();
      if (!SLOT_ATTRIBUTE_KEYS.contains(key)) {
        throw new IllegalArgumentException("Unsupported Malayalam slot attribute: " + key);
      }
      values.put(
          key, featureMap(entry.getValue(), "slotAttributes." + key, slotAllowedValues(key)));
    }
    return unmodifiableLinkedMap(values);
  }

  private Set<String> slotAllowedValues(String key) {
    return switch (key) {
      case "animacy" -> SAMPLE_ANIMACY_VALUES;
      case "case" -> SAMPLE_CASE_VALUES;
      case "count" -> Set.of("countable");
      case "gender" -> SAMPLE_GENDER_VALUES;
      case "number" -> SAMPLE_NUMBER_VALUES;
      default -> throw new IllegalArgumentException("Unsupported Malayalam slot attribute: " + key);
    };
  }

  private Pronouns loadPronouns(JsonNode node) {
    return new Pronouns(
        featureMap(requiredObject(node, "cases"), "pronoun.cases", SAMPLE_CASE_VALUES),
        featureMap(
            requiredObject(node, "determination"),
            "pronoun.determination",
            PRONOUN_DETERMINATION_VALUES),
        featureMap(requiredObject(node, "features"), "pronoun.features", PRONOUN_FEATURE_VALUES),
        featureMap(requiredObject(node, "genders"), "pronoun.genders", SAMPLE_GENDER_VALUES),
        featureMap(requiredObject(node, "numbers"), "pronoun.numbers", SAMPLE_NUMBER_VALUES),
        featureMap(requiredObject(node, "persons"), "pronoun.persons", PRONOUN_PERSON_VALUES),
        featureMap(requiredObject(node, "registers"), "pronoun.registers", PRONOUN_REGISTER_VALUES),
        requiredInt(node, "rows"),
        requiredInt(node, "uniqueValues"));
  }

  private Samples loadSamples(JsonNode node) {
    return new Samples(
        loadAmbiguousSurfaces(requiredArray(node, "ambiguousTermSurfaces")),
        compactEntries(requiredArray(node, "caseTaggedAgreementEntries")),
        compactEntries(requiredArray(node, "genderTaggedAgreementEntries")),
        textArray(
            requiredArray(node, "missingAgreementPatterns"),
            "missingAgreementPatterns",
            null,
            "Malayalam"));
  }

  private List<AmbiguousSurface> loadAmbiguousSurfaces(JsonNode node) {
    List<AmbiguousSurface> samples = new ArrayList<>();
    for (JsonNode sampleNode : node) {
      samples.add(
          new AmbiguousSurface(
              requiredText(sampleNode, "surface"),
              requiredPositiveInt(sampleNode, "entries"),
              textArray(
                  requiredArray(sampleNode, "reasons"),
                  "reasons",
                  AMBIGUITY_REASON_VALUES,
                  "Malayalam"),
              compactEntries(requiredArray(sampleNode, "sampleEntries"))));
    }
    return List.copyOf(samples);
  }

  private List<CompactEntry> compactEntries(JsonNode node) {
    List<CompactEntry> samples = new ArrayList<>();
    for (JsonNode sampleNode : node) {
      samples.add(
          new CompactEntry(
              requiredText(sampleNode, "surface"),
              requiredPositiveInt(sampleNode, "line"),
              textArray(
                  requiredArray(sampleNode, "partOfSpeech"),
                  "partOfSpeech",
                  SAMPLE_PART_OF_SPEECH_VALUES,
                  "Malayalam"),
              textArray(requiredArray(sampleNode, "case"), "case", SAMPLE_CASE_VALUES, "Malayalam"),
              textArray(
                  requiredArray(sampleNode, "gender"), "gender", SAMPLE_GENDER_VALUES, "Malayalam"),
              textArray(
                  requiredArray(sampleNode, "number"), "number", SAMPLE_NUMBER_VALUES, "Malayalam"),
              textArray(
                  requiredArray(sampleNode, "animacy"),
                  "animacy",
                  SAMPLE_ANIMACY_VALUES,
                  "Malayalam"),
              textArray(
                  requiredArray(sampleNode, "inflections"), "inflections", null, "Malayalam")));
    }
    return List.copyOf(samples);
  }

  private void validateProvenance(Provenance provenance) {
    if (!"Unicode-3.0".equals(provenance.license())) {
      throw new IllegalArgumentException("Unexpected Malayalam audit license");
    }
    if (!"dev-docs/experiments/mf2-inflection/ml_pack_audit.py".equals(provenance.generator())) {
      throw new IllegalArgumentException("Unexpected Malayalam audit generator");
    }
    if (provenance.sources().isEmpty()) {
      throw new IllegalArgumentException("Malayalam audit must include sources");
    }
    List<String> sourcePaths = provenance.sources().stream().map(Source::path).toList();
    if (!sourcePaths.equals(provenance.sourceLabels())) {
      throw new IllegalArgumentException("Malayalam provenance source labels must match sources");
    }
  }

  private void validateCounts(
      Counts counts, Features features, Patterns patterns, Samples samples) {
    if (counts.uniqueSurfaces() > counts.dictionaryEntries()
        || counts.termSurfaces() > counts.termEntries()
        || counts.agreementSurfaces() > counts.agreementEntries()) {
      throw new IllegalArgumentException("Malayalam surface counts exceed entry counts");
    }
    if (counts.termEntries() > counts.dictionaryEntries()
        || counts.agreementEntries() > counts.dictionaryEntries()) {
      throw new IllegalArgumentException("Malayalam entry counts exceed dictionary entries");
    }
    if (counts.caseTaggedAgreementEntries() > counts.agreementEntries()
        || counts.genderTaggedAgreementEntries() > counts.agreementEntries()) {
      throw new IllegalArgumentException(
          "Malayalam agreement subset count exceeds agreement entries");
    }
    if (counts.usedAgreementPatterns() > counts.inflectionPatterns()
        || counts.usedTermPatterns() > counts.inflectionPatterns()
        || counts.missingAgreementPatterns() > counts.inflectionPatterns()) {
      throw new IllegalArgumentException("Malayalam pattern counts exceed available patterns");
    }
    if (features.agreementInflections().size() < counts.usedAgreementPatterns()) {
      throw new IllegalArgumentException(
          "Malayalam agreement inflection feature map is incomplete");
    }
    if (features.grammaticalCase().getOrDefault("<missing>", 0)
            + counts.caseTaggedAgreementEntries()
        != counts.agreementEntries()) {
      throw new IllegalArgumentException("Malayalam case coverage does not match counts");
    }
    if (features.gender().getOrDefault("<missing>", 0) + counts.genderTaggedAgreementEntries()
        != counts.agreementEntries()) {
      throw new IllegalArgumentException("Malayalam gender coverage does not match counts");
    }
    if (patterns.usedAgreement().slotsPerPattern().isEmpty()
        || patterns.usedTerms().slotsPerPattern().isEmpty()) {
      throw new IllegalArgumentException("Malayalam used-pattern stats must not be empty");
    }
    if (samples.caseTaggedAgreementEntries().size() > counts.caseTaggedAgreementEntries()
        || samples.genderTaggedAgreementEntries().size() > counts.genderTaggedAgreementEntries()
        || samples.ambiguousTermSurfaces().size() > counts.ambiguousTermSurfaces()) {
      throw new IllegalArgumentException("Malayalam sample count exceeds reported count");
    }
    if (samples.missingAgreementPatterns().size() > counts.missingAgreementPatterns()) {
      throw new IllegalArgumentException("Malayalam missing-pattern samples exceed reported count");
    }
    if (counts.missingAgreementPatterns() == 0 && !samples.missingAgreementPatterns().isEmpty()) {
      throw new IllegalArgumentException(
          "Malayalam missing-pattern samples present when count is zero");
    }
  }

  private void validatePatternStats(Patterns patterns) {
    validatePatternStats("all", patterns.all());
    validatePatternStats("usedAgreement", patterns.usedAgreement());
    validatePatternStats("usedTerms", patterns.usedTerms());
    if (patterns.usedAgreement().caseSlots() > patterns.all().caseSlots()
        || patterns.usedTerms().caseSlots() > patterns.usedAgreement().caseSlots()) {
      throw new IllegalArgumentException("Malayalam case slot counts are incoherent");
    }
  }

  private void validatePatternStats(String name, PatternStats stats) {
    if (stats.duplicateSlotRows() < stats.patternsWithDuplicateSlots()) {
      throw new IllegalArgumentException(
          "Malayalam " + name + " duplicate rows are lower than duplicate patterns");
    }
    if (stats.caseSlots() != sumValues(stats.slotAttributes().getOrDefault("case", Map.of()))) {
      throw new IllegalArgumentException("Malayalam " + name + " case slot count is incoherent");
    }
    if (weightedNumericKeySum(stats.slotsPerPattern()) <= 0) {
      throw new IllegalArgumentException("Malayalam " + name + " slots-per-pattern is empty");
    }
  }

  private void validatePronouns(Pronouns pronouns) {
    if (pronouns.uniqueValues() > pronouns.rows()) {
      throw new IllegalArgumentException("Malayalam unique pronouns exceed pronoun rows");
    }
    if (sumValues(pronouns.cases()) != pronouns.rows()) {
      throw new IllegalArgumentException("Malayalam pronoun case counts must sum to rows");
    }
    if (sumValues(pronouns.persons()) != pronouns.rows()) {
      throw new IllegalArgumentException("Malayalam pronoun person counts must sum to rows");
    }
    if (sumValues(pronouns.numbers()) != pronouns.rows()) {
      throw new IllegalArgumentException("Malayalam pronoun number counts must sum to rows");
    }
    if (sumValues(pronouns.determination()) > pronouns.rows()
        || sumValues(pronouns.genders()) > pronouns.rows()
        || sumValues(pronouns.registers()) > pronouns.rows()) {
      throw new IllegalArgumentException("Malayalam optional pronoun feature counts exceed rows");
    }
  }

  private void validatePackPolicy(
      Counts counts, Features features, Patterns patterns, PackPolicy policy) {
    if (!EXPECTED_PACK_RECOMMENDATION.equals(policy.recommendation())) {
      throw new IllegalArgumentException("Unexpected Malayalam pack recommendation");
    }
    if (!EXPECTED_TERM_SCOPE.equals(policy.termScope())) {
      throw new IllegalArgumentException("Unexpected Malayalam term scope");
    }
    if (!EXPECTED_RUNTIME_OPTIONS.equals(policy.runtimeOptions())) {
      throw new IllegalArgumentException("Unexpected Malayalam runtime options");
    }
    if (!EXPECTED_METADATA_BITS.equals(policy.metadataBits())) {
      throw new IllegalArgumentException("Unexpected Malayalam metadata bits");
    }
    if (!EXPECTED_CASE_MODE.equals(policy.caseMode())
        || !EXPECTED_NUMBER_MODE.equals(policy.numberMode())) {
      throw new IllegalArgumentException("Unexpected Malayalam option policy");
    }
    if (!EXPECTED_PRONOUN_SCOPE.equals(policy.pronounScope())
        || !EXPECTED_PRONOUN_AGREEMENT_POLICY.equals(policy.pronounAgreementPolicy())) {
      throw new IllegalArgumentException("Unexpected Malayalam pronoun policy");
    }
    if (!EXPECTED_LATER_OPTIMIZATION.equals(policy.laterOptimization())) {
      throw new IllegalArgumentException("Unexpected Malayalam later optimization policy");
    }
    if (policy.openWorldGeneration()) {
      throw new IllegalArgumentException("Malayalam V0 must not enable open-world generation");
    }
    if ((long) counts.caseTaggedAgreementEntries() * 100 < (long) counts.agreementEntries() * 95) {
      throw new IllegalArgumentException("Malayalam explicit case policy requires broad evidence");
    }
    if (patterns.usedTerms().caseSlots() <= 0) {
      throw new IllegalArgumentException(
          "Malayalam explicit case policy requires pattern evidence");
    }
    if (features.grammaticalCase().getOrDefault("sociative", 0) <= 0
        || features.grammaticalCase().getOrDefault("vocative", 0) <= 0) {
      throw new IllegalArgumentException(
          "Malayalam explicit case policy requires broad case inventory");
    }
  }

  private Map<String, Integer> featureMap(JsonNode node, String field, Set<String> allowedKeys) {
    Map<String, Integer> values = new LinkedHashMap<>();
    for (var fields = node.fields(); fields.hasNext(); ) {
      Map.Entry<String, JsonNode> entry = fields.next();
      String key = entry.getKey();
      if (allowedKeys != null && !allowedKeys.contains(key)) {
        throw new IllegalArgumentException("Unsupported Malayalam " + field + " key: " + key);
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
        throw new IllegalArgumentException("Expected numeric Malayalam " + field + " key", e);
      }
      if (key < minKey || key > maxKey) {
        throw new IllegalArgumentException("Malayalam " + field + " key out of range: " + key);
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
      throw new IllegalArgumentException("Expected positive integer field: " + field);
    }
    return value;
  }

  private int sumValues(Map<String, Integer> values) {
    return values.values().stream().mapToInt(Integer::intValue).sum();
  }

  private int weightedNumericKeySum(Map<String, Integer> values) {
    return values.entrySet().stream()
        .mapToInt(entry -> Integer.parseInt(entry.getKey()) * entry.getValue())
        .sum();
  }

  private static void requireNonNegative(int value, String field) {
    if (value < 0) {
      throw new IllegalArgumentException(field + " must be non-negative");
    }
  }

  private static void requireNonNegative(long value, String field) {
    if (value < 0) {
      throw new IllegalArgumentException(field + " must be non-negative");
    }
  }

  private static void requirePositive(int value, String field) {
    if (value <= 0) {
      throw new IllegalArgumentException(field + " must be positive");
    }
  }

  public record MalayalamPackAudit(
      String schema,
      String locale,
      String description,
      Provenance provenance,
      Counts counts,
      Features features,
      Patterns patterns,
      PackPolicy packPolicy,
      Pronouns pronouns,
      Samples samples) {

    public MalayalamPackAudit {
      if (!EXPECTED_SCHEMA.equals(schema)) {
        throw new IllegalArgumentException(
            "Expected schema " + EXPECTED_SCHEMA + ", got " + schema);
      }
      if (!EXPECTED_LOCALE.equals(locale)) {
        throw new IllegalArgumentException("Expected locale ml, got " + locale);
      }
      description = Objects.requireNonNull(description, "description");
      provenance = Objects.requireNonNull(provenance, "provenance");
      counts = Objects.requireNonNull(counts, "counts");
      features = Objects.requireNonNull(features, "features");
      patterns = Objects.requireNonNull(patterns, "patterns");
      packPolicy = Objects.requireNonNull(packPolicy, "packPolicy");
      pronouns = Objects.requireNonNull(pronouns, "pronouns");
      samples = Objects.requireNonNull(samples, "samples");
    }
  }

  public record Provenance(
      String license, String generator, List<Source> sources, List<String> sourceLabels) {

    public Provenance {
      license = Objects.requireNonNull(license, "license");
      generator = Objects.requireNonNull(generator, "generator");
      sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
      sourceLabels = List.copyOf(Objects.requireNonNull(sourceLabels, "sourceLabels"));
    }
  }

  public record Source(String path, long byteSize, String sha256, boolean gitLfsPointer) {

    public Source {
      path = Objects.requireNonNull(path, "path");
      sha256 = Objects.requireNonNull(sha256, "sha256");
      requireNonNegative(byteSize, "byteSize");
    }
  }

  public record Counts(
      int agreementEntries,
      int agreementSurfaces,
      int ambiguousTermSurfaces,
      int caseTaggedAgreementEntries,
      int dictionaryEntries,
      int genderTaggedAgreementEntries,
      int inflectionPatterns,
      int missingAgreementPatterns,
      int skippedDictionaryLines,
      int termEntries,
      int termSurfaces,
      int uniqueSurfaces,
      int usedAgreementPatterns,
      int usedTermPatterns) {

    public Counts {
      requireNonNegative(agreementEntries, "agreementEntries");
      requireNonNegative(agreementSurfaces, "agreementSurfaces");
      requireNonNegative(ambiguousTermSurfaces, "ambiguousTermSurfaces");
      requireNonNegative(caseTaggedAgreementEntries, "caseTaggedAgreementEntries");
      requireNonNegative(dictionaryEntries, "dictionaryEntries");
      requireNonNegative(genderTaggedAgreementEntries, "genderTaggedAgreementEntries");
      requireNonNegative(inflectionPatterns, "inflectionPatterns");
      requireNonNegative(missingAgreementPatterns, "missingAgreementPatterns");
      requireNonNegative(skippedDictionaryLines, "skippedDictionaryLines");
      requireNonNegative(termEntries, "termEntries");
      requireNonNegative(termSurfaces, "termSurfaces");
      requireNonNegative(uniqueSurfaces, "uniqueSurfaces");
      requireNonNegative(usedAgreementPatterns, "usedAgreementPatterns");
      requireNonNegative(usedTermPatterns, "usedTermPatterns");
    }
  }

  public record Features(
      Map<String, Integer> agreementInflections,
      Map<String, Integer> agreementPartOfSpeech,
      Map<String, Integer> ambiguityReasons,
      Map<String, Integer> animacy,
      Map<String, Integer> grammaticalCase,
      Map<String, Integer> gender,
      Map<String, Integer> number,
      Map<String, Integer> partOfSpeech,
      Map<String, Integer> termPartOfSpeech,
      Map<String, Integer> unknownGrammemes) {

    public Features {
      agreementInflections = unmodifiableLinkedMap(agreementInflections);
      agreementPartOfSpeech = unmodifiableLinkedMap(agreementPartOfSpeech);
      ambiguityReasons = unmodifiableLinkedMap(ambiguityReasons);
      animacy = unmodifiableLinkedMap(animacy);
      grammaticalCase = unmodifiableLinkedMap(grammaticalCase);
      gender = unmodifiableLinkedMap(gender);
      number = unmodifiableLinkedMap(number);
      partOfSpeech = unmodifiableLinkedMap(partOfSpeech);
      termPartOfSpeech = unmodifiableLinkedMap(termPartOfSpeech);
      unknownGrammemes = unmodifiableLinkedMap(unknownGrammemes);
    }
  }

  public record Patterns(PatternStats all, PatternStats usedAgreement, PatternStats usedTerms) {}

  public record PackPolicy(
      String recommendation,
      String reason,
      List<String> termScope,
      List<String> runtimeOptions,
      List<String> metadataBits,
      String caseMode,
      String numberMode,
      String pronounScope,
      String pronounAgreementPolicy,
      boolean openWorldGeneration,
      String laterOptimization) {

    public PackPolicy {
      recommendation = Objects.requireNonNull(recommendation, "recommendation");
      reason = Objects.requireNonNull(reason, "reason");
      termScope = List.copyOf(termScope);
      runtimeOptions = List.copyOf(runtimeOptions);
      metadataBits = List.copyOf(metadataBits);
      caseMode = Objects.requireNonNull(caseMode, "caseMode");
      numberMode = Objects.requireNonNull(numberMode, "numberMode");
      pronounScope = Objects.requireNonNull(pronounScope, "pronounScope");
      pronounAgreementPolicy =
          Objects.requireNonNull(pronounAgreementPolicy, "pronounAgreementPolicy");
      laterOptimization = Objects.requireNonNull(laterOptimization, "laterOptimization");
    }
  }

  public record PatternStats(
      int caseSlots,
      int duplicateSlotRows,
      int patternsWithDuplicateSlots,
      Map<String, Map<String, Integer>> slotAttributes,
      Map<String, Integer> slotsPerPattern) {

    public PatternStats {
      requireNonNegative(caseSlots, "caseSlots");
      requireNonNegative(duplicateSlotRows, "duplicateSlotRows");
      requireNonNegative(patternsWithDuplicateSlots, "patternsWithDuplicateSlots");
      Map<String, Map<String, Integer>> copied = new LinkedHashMap<>();
      for (Map.Entry<String, Map<String, Integer>> entry :
          Objects.requireNonNull(slotAttributes, "slotAttributes").entrySet()) {
        copied.put(entry.getKey(), unmodifiableLinkedMap(entry.getValue()));
      }
      slotAttributes = Collections.unmodifiableMap(copied);
      slotsPerPattern = unmodifiableLinkedMap(slotsPerPattern);
    }
  }

  public record Pronouns(
      Map<String, Integer> cases,
      Map<String, Integer> determination,
      Map<String, Integer> features,
      Map<String, Integer> genders,
      Map<String, Integer> numbers,
      Map<String, Integer> persons,
      Map<String, Integer> registers,
      int rows,
      int uniqueValues) {

    public Pronouns {
      requireNonNegative(rows, "rows");
      requireNonNegative(uniqueValues, "uniqueValues");
      cases = unmodifiableLinkedMap(cases);
      determination = unmodifiableLinkedMap(determination);
      features = unmodifiableLinkedMap(features);
      genders = unmodifiableLinkedMap(genders);
      numbers = unmodifiableLinkedMap(numbers);
      persons = unmodifiableLinkedMap(persons);
      registers = unmodifiableLinkedMap(registers);
    }
  }

  public record Samples(
      List<AmbiguousSurface> ambiguousTermSurfaces,
      List<CompactEntry> caseTaggedAgreementEntries,
      List<CompactEntry> genderTaggedAgreementEntries,
      List<String> missingAgreementPatterns) {

    public Samples {
      ambiguousTermSurfaces = List.copyOf(ambiguousTermSurfaces);
      caseTaggedAgreementEntries = List.copyOf(caseTaggedAgreementEntries);
      genderTaggedAgreementEntries = List.copyOf(genderTaggedAgreementEntries);
      missingAgreementPatterns = List.copyOf(missingAgreementPatterns);
    }
  }

  public record AmbiguousSurface(
      String surface, int entries, List<String> reasons, List<CompactEntry> sampleEntries) {

    public AmbiguousSurface {
      surface = Objects.requireNonNull(surface, "surface");
      requirePositive(entries, "entries");
      reasons = List.copyOf(reasons);
      sampleEntries = List.copyOf(sampleEntries);
    }
  }

  public record CompactEntry(
      String surface,
      int line,
      List<String> partOfSpeech,
      List<String> grammaticalCase,
      List<String> gender,
      List<String> number,
      List<String> animacy,
      List<String> inflections) {

    public CompactEntry {
      surface = Objects.requireNonNull(surface, "surface");
      requirePositive(line, "line");
      partOfSpeech = List.copyOf(partOfSpeech);
      grammaticalCase = List.copyOf(grammaticalCase);
      gender = List.copyOf(gender);
      number = List.copyOf(number);
      animacy = List.copyOf(animacy);
      inflections = List.copyOf(inflections);
    }
  }
}
