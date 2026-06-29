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
 * Loads the generated Arabic pack audit. The audit is a generator contract for deciding the Arabic
 * runtime shape; it is not itself a renderer-ready pack.
 */
@GeneratorSupport
class ArabicPackAuditJsonLoader {

  static final String EXPECTED_SCHEMA = "mojito-mf2-inflection/ar-pack-audit/v0";
  static final String EXPECTED_LOCALE = "ar";
  static final String EXPECTED_PACK_RECOMMENDATION = "closed-world explicit-form pack";
  static final String EXPECTED_CASE_MODE = "explicit-form-key";
  static final String EXPECTED_NUMBER_MODE = "explicit-number-option";
  static final String EXPECTED_CONSTRUCT_MODE = "explicit-form-key";
  static final String EXPECTED_COUNT_MODE = "no-implicit-count-to-dual";
  static final String EXPECTED_PRONOUN_SCOPE = "inventory-only-v0";
  static final String EXPECTED_PRONOUN_ATTACHMENT_POLICY =
      "separate-arabic-attachment-profile-later";
  static final String EXPECTED_ADJECTIVE_AGREEMENT_POLICY = "out-of-scope-for-v0";

  private static final Set<String> PART_OF_SPEECH_VALUES =
      Set.of("<missing>", "adjective", "adverb", "noun", "proper-noun", "verb");
  private static final Set<String> SAMPLE_PART_OF_SPEECH_VALUES =
      Set.of("adjective", "adverb", "noun", "proper-noun", "verb");
  private static final Set<String> CASE_VALUES =
      Set.of("<missing>", "accusative", "genitive", "nominative");
  private static final Set<String> SAMPLE_CASE_VALUES =
      Set.of("accusative", "genitive", "nominative");
  private static final Set<String> GENDER_VALUES = Set.of("<missing>", "feminine", "masculine");
  private static final Set<String> SAMPLE_GENDER_VALUES = Set.of("feminine", "masculine");
  private static final Set<String> NUMBER_VALUES =
      Set.of("<missing>", "dual", "plural", "singular");
  private static final Set<String> SAMPLE_NUMBER_VALUES = Set.of("dual", "plural", "singular");
  private static final Set<String> DEFINITENESS_VALUES =
      Set.of("<missing>", "construct", "definite", "indefinite");
  private static final Set<String> SAMPLE_DEFINITENESS_VALUES =
      Set.of("construct", "definite", "indefinite");
  private static final Set<String> ANIMACY_VALUES = Set.of("<missing>", "animate", "inanimate");
  private static final Set<String> SAMPLE_ANIMACY_VALUES = Set.of("animate", "inanimate");
  private static final Set<String> AMBIGUITY_REASON_VALUES =
      Set.of(
          "multiple-animacy",
          "multiple-cases",
          "multiple-definiteness",
          "multiple-entries",
          "multiple-genders",
          "multiple-inflections",
          "multiple-numbers",
          "multiple-parts-of-speech");
  private static final Set<String> UNKNOWN_GRAMMEME_VALUES =
      Set.of(
          "adposition",
          "conjunction",
          "determiner",
          "gerund",
          "indicative",
          "informal",
          "interjection",
          "jussive",
          "numeral",
          "participle",
          "particle",
          "present",
          "pronoun",
          "subjunctive",
          "uncountable");
  private static final Set<String> SLOT_ATTRIBUTE_KEYS =
      Set.of(
          "animacy",
          "aspect",
          "case",
          "count",
          "definiteness",
          "gender",
          "mood",
          "number",
          "person",
          "register",
          "tense",
          "verb-type",
          "voice");
  private static final Set<String> PRONOUN_FEATURE_VALUES =
      Set.of(
          "accusative",
          "dual",
          "feminine",
          "first",
          "genitive",
          "masculine",
          "nominative",
          "plural",
          "reflexive",
          "second",
          "singular",
          "third");
  private static final Set<String> PRONOUN_CASE_VALUES =
      Set.of("accusative", "genitive", "nominative", "reflexive");
  private static final Set<String> PRONOUN_PERSON_VALUES = Set.of("first", "second", "third");
  private static final List<String> EXPECTED_TERM_SCOPE = List.of("noun", "proper-noun");
  private static final List<String> EXPECTED_RUNTIME_OPTIONS =
      List.of("case", "number", "definiteness");
  private static final List<String> EXPECTED_METADATA_BITS =
      List.of("gender", "animacy", "partOfSpeech");
  private static final List<String> EXPECTED_REVIEW_REQUIRED_REASONS =
      List.of(
          "ambiguous-surface",
          "construct-state",
          "dual-number",
          "verb-bearing-pattern-slot",
          "missing-or-ambiguous-gender",
          "pronoun-attachment");
  private static final Set<String> REVIEW_REQUIRED_REASON_VALUES =
      Set.copyOf(EXPECTED_REVIEW_REQUIRED_REASONS);

  private final ObjectMapper objectMapper;

  ArabicPackAuditJsonLoader() {
    this(new ObjectMapper());
  }

  ArabicPackAuditJsonLoader(ObjectMapper objectMapper) {
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
  }

  public ArabicPackAudit load(String json) {
    Objects.requireNonNull(json, "json");
    return load(objectMapper.readTreeUnchecked(json));
  }

  public ArabicPackAudit load(JsonNode root) {
    ArabicPackAudit audit =
        new ArabicPackAudit(
            requiredText(root, "schema"),
            requiredText(root, "locale"),
            requiredText(root, "description"),
            loadProvenance(requiredObject(root, "provenance")),
            loadCounts(requiredObject(root, "counts")),
            loadFeatures(requiredObject(root, "features")),
            loadPatterns(requiredObject(root, "patterns")),
            loadPackPolicy(requiredObject(root, "packPolicy")),
            loadPronouns(requiredObject(root, "pronouns")),
            loadSamples(requiredObject(root, "samples")),
            textArray(requiredArray(root, "policyQuestions"), "policyQuestions", null, "Arabic"));

    validateProvenance(audit.provenance());
    validateCounts(audit.counts(), audit.features(), audit.patterns(), audit.samples());
    validatePatternStats(audit.patterns());
    validatePronouns(audit.pronouns());
    validatePackPolicy(
        audit.counts(), audit.features(), audit.patterns(), audit.pronouns(), audit.packPolicy());
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
        textArray(requiredArray(node, "sourceLabels"), "sourceLabels", null, "Arabic"));
  }

  private Counts loadCounts(JsonNode node) {
    return new Counts(
        requiredInt(node, "agreementEntries"),
        requiredInt(node, "ambiguousAgreementSurfaces"),
        requiredInt(node, "baseCandidateEntries"),
        requiredInt(node, "constructAgreementEntries"),
        requiredInt(node, "dictionaryEntries"),
        requiredInt(node, "dualAgreementEntries"),
        requiredInt(node, "fullySpecifiedAgreementEntries"),
        requiredInt(node, "inflectionPatterns"),
        requiredInt(node, "missingAgreementPatterns"),
        requiredInt(node, "skippedDictionaryLines"),
        requiredInt(node, "termEntries"),
        requiredInt(node, "usedAgreementPatterns"),
        requiredInt(node, "usedTermPatterns"));
  }

  private Features loadFeatures(JsonNode node) {
    return new Features(
        featureMap(requiredObject(node, "agreementInflections"), "agreementInflections", null),
        featureMap(
            requiredObject(node, "ambiguityReasons"), "ambiguityReasons", AMBIGUITY_REASON_VALUES),
        featureMap(requiredObject(node, "animacy"), "animacy", ANIMACY_VALUES),
        featureMap(requiredObject(node, "case"), "case", CASE_VALUES),
        featureMap(requiredObject(node, "definiteness"), "definiteness", DEFINITENESS_VALUES),
        featureMap(requiredObject(node, "gender"), "gender", GENDER_VALUES),
        featureMap(requiredObject(node, "number"), "number", NUMBER_VALUES),
        featureMap(requiredObject(node, "partOfSpeech"), "partOfSpeech", PART_OF_SPEECH_VALUES),
        featureMap(
            requiredObject(node, "termPartOfSpeech"),
            "termPartOfSpeech",
            SAMPLE_PART_OF_SPEECH_VALUES),
        featureMap(
            requiredObject(node, "unknownGrammemes"), "unknownGrammemes", UNKNOWN_GRAMMEME_VALUES));
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
        textArray(requiredArray(node, "termScope"), "termScope", null, "Arabic"),
        textArray(requiredArray(node, "runtimeOptions"), "runtimeOptions", null, "Arabic"),
        textArray(requiredArray(node, "metadataBits"), "metadataBits", null, "Arabic"),
        requiredText(node, "caseMode"),
        requiredText(node, "numberMode"),
        requiredText(node, "constructMode"),
        requiredText(node, "countMode"),
        requiredText(node, "pronounScope"),
        requiredText(node, "pronounAttachmentPolicy"),
        requiredText(node, "adjectiveAgreementPolicy"),
        requiredBoolean(node, "openWorldGeneration"),
        requiredInt(node, "baseCandidateEntries"),
        requiredInt(node, "explicitFormCandidateRows"),
        requiredInt(node, "ambiguousSurfaceRows"),
        requiredInt(node, "verbBearingTermPatternSlots"),
        textArray(
            requiredArray(node, "reviewRequiredReasons"),
            "reviewRequiredReasons",
            REVIEW_REQUIRED_REASON_VALUES,
            "Arabic"),
        featureMap(
            requiredObject(node, "reviewRequiredEvidence"),
            "reviewRequiredEvidence",
            REVIEW_REQUIRED_REASON_VALUES));
  }

  private PatternStats loadPatternStats(JsonNode node) {
    return new PatternStats(
        requiredInt(node, "constructSlots"),
        requiredInt(node, "dualSlots"),
        requiredInt(node, "duplicateSlotRows"),
        requiredInt(node, "fullySpecifiedCaseNumberGenderDefinitenessSlots"),
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
        throw new IllegalArgumentException("Unsupported Arabic slot attribute: " + key);
      }
      values.put(
          key, featureMap(entry.getValue(), "slotAttributes." + key, slotAllowedValues(key)));
    }
    return unmodifiableLinkedMap(values);
  }

  private Set<String> slotAllowedValues(String key) {
    return switch (key) {
      case "animacy" -> SAMPLE_ANIMACY_VALUES;
      case "aspect" -> Set.of("imperfect", "perfect");
      case "case" -> SAMPLE_CASE_VALUES;
      case "count" -> Set.of("countable", "uncountable");
      case "definiteness" -> SAMPLE_DEFINITENESS_VALUES;
      case "gender" -> SAMPLE_GENDER_VALUES;
      case "mood" -> Set.of("imperative", "indicative", "jussive", "subjunctive");
      case "number" -> SAMPLE_NUMBER_VALUES;
      case "person" -> PRONOUN_PERSON_VALUES;
      case "register" -> Set.of("informal");
      case "tense" -> Set.of("past", "present");
      case "verb-type" -> Set.of("gerund", "participle");
      case "voice" -> Set.of("active", "passive");
      default -> throw new IllegalArgumentException("Unsupported Arabic slot attribute: " + key);
    };
  }

  private Pronouns loadPronouns(JsonNode node) {
    return new Pronouns(
        featureMap(requiredObject(node, "cases"), "pronoun.cases", PRONOUN_CASE_VALUES),
        featureMap(requiredObject(node, "features"), "pronoun.features", PRONOUN_FEATURE_VALUES),
        featureMap(requiredObject(node, "genders"), "pronoun.genders", SAMPLE_GENDER_VALUES),
        featureMap(requiredObject(node, "numbers"), "pronoun.numbers", SAMPLE_NUMBER_VALUES),
        featureMap(requiredObject(node, "persons"), "pronoun.persons", PRONOUN_PERSON_VALUES),
        requiredInt(node, "rows"),
        requiredInt(node, "uniqueValues"));
  }

  private Samples loadSamples(JsonNode node) {
    return new Samples(
        loadAmbiguousSurfaces(requiredArray(node, "ambiguousAgreementSurfaces")),
        compactEntries(requiredArray(node, "baseCandidates")),
        compactEntries(requiredArray(node, "constructAgreementEntries")),
        compactEntries(requiredArray(node, "dualAgreementEntries")),
        textArray(
            requiredArray(node, "missingAgreementPatterns"),
            "missingAgreementPatterns",
            null,
            "Arabic"),
        loadPronounSamples(requiredArray(node, "pronouns")));
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
                  "Arabic"),
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
                  "Arabic"),
              textArray(requiredArray(sampleNode, "case"), "case", SAMPLE_CASE_VALUES, "Arabic"),
              textArray(
                  requiredArray(sampleNode, "gender"), "gender", SAMPLE_GENDER_VALUES, "Arabic"),
              textArray(
                  requiredArray(sampleNode, "number"), "number", SAMPLE_NUMBER_VALUES, "Arabic"),
              textArray(
                  requiredArray(sampleNode, "definiteness"),
                  "definiteness",
                  SAMPLE_DEFINITENESS_VALUES,
                  "Arabic"),
              textArray(
                  requiredArray(sampleNode, "animacy"), "animacy", SAMPLE_ANIMACY_VALUES, "Arabic"),
              textArray(requiredArray(sampleNode, "inflections"), "inflections", null, "Arabic")));
    }
    return List.copyOf(samples);
  }

  private List<PronounSample> loadPronounSamples(JsonNode node) {
    List<PronounSample> samples = new ArrayList<>();
    for (JsonNode sampleNode : node) {
      samples.add(
          new PronounSample(
              requiredText(sampleNode, "value"),
              requiredPositiveInt(sampleNode, "line"),
              textArray(
                  requiredArray(sampleNode, "features"),
                  "pronoun.features",
                  PRONOUN_FEATURE_VALUES,
                  "Arabic")));
    }
    return List.copyOf(samples);
  }

  private void validateProvenance(Provenance provenance) {
    if (!"Unicode-3.0".equals(provenance.license())) {
      throw new IllegalArgumentException("Unexpected Arabic audit license");
    }
    if (!"dev-docs/experiments/mf2-inflection/ar_pack_audit.py".equals(provenance.generator())) {
      throw new IllegalArgumentException("Unexpected Arabic audit generator");
    }
    if (provenance.sources().isEmpty()) {
      throw new IllegalArgumentException("Arabic audit must include sources");
    }
    List<String> sourcePaths = provenance.sources().stream().map(Source::path).toList();
    if (!sourcePaths.equals(provenance.sourceLabels())) {
      throw new IllegalArgumentException("Arabic provenance source labels must match sources");
    }
  }

  private void validateCounts(
      Counts counts, Features features, Patterns patterns, Samples samples) {
    if (counts.termEntries() > counts.dictionaryEntries()
        || counts.agreementEntries() > counts.dictionaryEntries()) {
      throw new IllegalArgumentException("Arabic entry counts exceed dictionary entries");
    }
    if (counts.baseCandidateEntries() > counts.termEntries()) {
      throw new IllegalArgumentException("Arabic base candidates exceed term entries");
    }
    if (counts.dualAgreementEntries() > counts.agreementEntries()
        || counts.constructAgreementEntries() > counts.agreementEntries()
        || counts.fullySpecifiedAgreementEntries() > counts.agreementEntries()) {
      throw new IllegalArgumentException("Arabic agreement subset count exceeds agreement entries");
    }
    if (counts.usedAgreementPatterns() > counts.inflectionPatterns()
        || counts.usedTermPatterns() > counts.inflectionPatterns()
        || counts.missingAgreementPatterns() > counts.inflectionPatterns()) {
      throw new IllegalArgumentException("Arabic pattern counts exceed available patterns");
    }
    if (features.agreementInflections().size() < counts.usedAgreementPatterns()) {
      throw new IllegalArgumentException("Arabic agreement inflection feature map is incomplete");
    }
    if (patterns.usedAgreement().slotsPerPattern().isEmpty()
        || patterns.usedTerms().slotsPerPattern().isEmpty()) {
      throw new IllegalArgumentException("Arabic used-pattern stats must not be empty");
    }
    if (samples.baseCandidates().size() > counts.baseCandidateEntries()
        || samples.dualAgreementEntries().size() > counts.dualAgreementEntries()
        || samples.constructAgreementEntries().size() > counts.constructAgreementEntries()
        || samples.ambiguousAgreementSurfaces().size() > counts.ambiguousAgreementSurfaces()) {
      throw new IllegalArgumentException("Arabic sample count exceeds reported count");
    }
    if (samples.missingAgreementPatterns().size() > counts.missingAgreementPatterns()) {
      throw new IllegalArgumentException("Arabic missing-pattern samples exceed reported count");
    }
    if (counts.missingAgreementPatterns() == 0 && !samples.missingAgreementPatterns().isEmpty()) {
      throw new IllegalArgumentException(
          "Arabic missing-pattern samples present when count is zero");
    }
  }

  private void validatePatternStats(Patterns patterns) {
    validatePatternStats("all", patterns.all());
    validatePatternStats("usedAgreement", patterns.usedAgreement());
    validatePatternStats("usedTerms", patterns.usedTerms());
    if (patterns.usedAgreement().dualSlots() > patterns.all().dualSlots()
        || patterns.usedTerms().dualSlots() > patterns.usedAgreement().dualSlots()) {
      throw new IllegalArgumentException("Arabic dual slot counts are incoherent");
    }
    if (patterns.usedAgreement().constructSlots() > patterns.all().constructSlots()
        || patterns.usedTerms().constructSlots() > patterns.usedAgreement().constructSlots()) {
      throw new IllegalArgumentException("Arabic construct slot counts are incoherent");
    }
  }

  private void validatePackPolicy(
      Counts counts, Features features, Patterns patterns, Pronouns pronouns, PackPolicy policy) {
    if (!EXPECTED_PACK_RECOMMENDATION.equals(policy.recommendation())) {
      throw new IllegalArgumentException("Unexpected Arabic pack recommendation");
    }
    if (!EXPECTED_TERM_SCOPE.equals(policy.termScope())) {
      throw new IllegalArgumentException("Unexpected Arabic term scope");
    }
    if (!EXPECTED_RUNTIME_OPTIONS.equals(policy.runtimeOptions())) {
      throw new IllegalArgumentException("Unexpected Arabic runtime options");
    }
    if (!EXPECTED_METADATA_BITS.equals(policy.metadataBits())) {
      throw new IllegalArgumentException("Unexpected Arabic metadata bits");
    }
    if (!EXPECTED_CASE_MODE.equals(policy.caseMode())
        || !EXPECTED_NUMBER_MODE.equals(policy.numberMode())
        || !EXPECTED_CONSTRUCT_MODE.equals(policy.constructMode())
        || !EXPECTED_COUNT_MODE.equals(policy.countMode())) {
      throw new IllegalArgumentException("Unexpected Arabic option policy");
    }
    if (!EXPECTED_PRONOUN_SCOPE.equals(policy.pronounScope())
        || !EXPECTED_PRONOUN_ATTACHMENT_POLICY.equals(policy.pronounAttachmentPolicy())) {
      throw new IllegalArgumentException("Unexpected Arabic pronoun policy");
    }
    if (!EXPECTED_ADJECTIVE_AGREEMENT_POLICY.equals(policy.adjectiveAgreementPolicy())) {
      throw new IllegalArgumentException("Unexpected Arabic adjective agreement policy");
    }
    if (policy.openWorldGeneration()) {
      throw new IllegalArgumentException("Arabic V0 must not enable open-world generation");
    }
    if (policy.baseCandidateEntries() != counts.baseCandidateEntries()
        || policy.explicitFormCandidateRows() != counts.fullySpecifiedAgreementEntries()
        || policy.ambiguousSurfaceRows() != counts.ambiguousAgreementSurfaces()) {
      throw new IllegalArgumentException("Arabic pack policy evidence does not match counts");
    }
    int verbBearingTermPatternSlots =
        sumValues(patterns.usedTerms().slotAttributes().getOrDefault("verb-type", Map.of()));
    if (policy.verbBearingTermPatternSlots() != verbBearingTermPatternSlots) {
      throw new IllegalArgumentException(
          "Arabic pack policy verb-bearing slot evidence does not match pattern stats");
    }
    if (policy.verbBearingTermPatternSlots() <= 0) {
      throw new IllegalArgumentException("Arabic pack policy requires verb-bearing slot evidence");
    }
    if (!EXPECTED_REVIEW_REQUIRED_REASONS.equals(policy.reviewRequiredReasons())) {
      throw new IllegalArgumentException("Unexpected Arabic review-required reasons");
    }
    if (!policy.reviewRequiredEvidence().keySet().equals(REVIEW_REQUIRED_REASON_VALUES)) {
      throw new IllegalArgumentException("Arabic review-required evidence keys are incomplete");
    }
    if (policy.reviewRequiredEvidence().get("ambiguous-surface")
            != counts.ambiguousAgreementSurfaces()
        || policy.reviewRequiredEvidence().get("construct-state")
            != counts.constructAgreementEntries()
        || policy.reviewRequiredEvidence().get("dual-number") != counts.dualAgreementEntries()
        || policy.reviewRequiredEvidence().get("verb-bearing-pattern-slot")
            != policy.verbBearingTermPatternSlots()
        || policy.reviewRequiredEvidence().get("pronoun-attachment") != pronouns.rows()) {
      throw new IllegalArgumentException("Arabic review-required evidence does not match counts");
    }
    int genderEvidence = policy.reviewRequiredEvidence().get("missing-or-ambiguous-gender");
    if (genderEvidence < features.gender().getOrDefault("<missing>", 0)
        || genderEvidence > counts.agreementEntries()) {
      throw new IllegalArgumentException("Arabic gender-review evidence is out of range");
    }
  }

  private void validatePatternStats(String name, PatternStats stats) {
    if (stats.duplicateSlotRows() < stats.patternsWithDuplicateSlots()) {
      throw new IllegalArgumentException(
          "Arabic " + name + " duplicate rows are lower than duplicate patterns");
    }
    if (stats.dualSlots() != nestedValue(stats.slotAttributes(), "number", "dual")) {
      throw new IllegalArgumentException("Arabic " + name + " dual slot count is incoherent");
    }
    if (stats.constructSlots()
        != nestedValue(stats.slotAttributes(), "definiteness", "construct")) {
      throw new IllegalArgumentException("Arabic " + name + " construct slot count is incoherent");
    }
    int caseSlots = sumValues(stats.slotAttributes().getOrDefault("case", Map.of()));
    if (stats.fullySpecifiedCaseNumberGenderDefinitenessSlots() > caseSlots) {
      throw new IllegalArgumentException(
          "Arabic " + name + " fully specified slots exceed case slots");
    }
    if (weightedNumericKeySum(stats.slotsPerPattern()) <= 0) {
      throw new IllegalArgumentException("Arabic " + name + " slots-per-pattern is empty");
    }
  }

  private void validatePronouns(Pronouns pronouns) {
    if (pronouns.uniqueValues() > pronouns.rows()) {
      throw new IllegalArgumentException("Arabic unique pronouns exceed pronoun rows");
    }
    if (sumValues(pronouns.cases()) != pronouns.rows()) {
      throw new IllegalArgumentException("Arabic pronoun case counts must sum to rows");
    }
    if (sumValues(pronouns.persons()) != pronouns.rows()) {
      throw new IllegalArgumentException("Arabic pronoun person counts must sum to rows");
    }
    if (sumValues(pronouns.numbers()) != pronouns.rows()) {
      throw new IllegalArgumentException("Arabic pronoun number counts must sum to rows");
    }
  }

  private Map<String, Integer> featureMap(JsonNode node, String field, Set<String> allowedKeys) {
    Map<String, Integer> values = new LinkedHashMap<>();
    for (var fields = node.fields(); fields.hasNext(); ) {
      Map.Entry<String, JsonNode> entry = fields.next();
      String key = entry.getKey();
      if (allowedKeys != null && !allowedKeys.contains(key)) {
        throw new IllegalArgumentException("Unsupported Arabic " + field + " key: " + key);
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
        throw new IllegalArgumentException("Expected numeric Arabic " + field + " key", e);
      }
      if (key < minKey || key > maxKey) {
        throw new IllegalArgumentException("Arabic " + field + " key out of range: " + key);
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

  private int nestedValue(Map<String, Map<String, Integer>> values, String outerKey, String key) {
    return values.getOrDefault(outerKey, Map.of()).getOrDefault(key, 0);
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

  public record ArabicPackAudit(
      String schema,
      String locale,
      String description,
      Provenance provenance,
      Counts counts,
      Features features,
      Patterns patterns,
      PackPolicy packPolicy,
      Pronouns pronouns,
      Samples samples,
      List<String> policyQuestions) {

    public ArabicPackAudit {
      if (!EXPECTED_SCHEMA.equals(schema)) {
        throw new IllegalArgumentException(
            "Expected schema " + EXPECTED_SCHEMA + ", got " + schema);
      }
      if (!EXPECTED_LOCALE.equals(locale)) {
        throw new IllegalArgumentException("Expected locale ar, got " + locale);
      }
      description = Objects.requireNonNull(description, "description");
      provenance = Objects.requireNonNull(provenance, "provenance");
      counts = Objects.requireNonNull(counts, "counts");
      features = Objects.requireNonNull(features, "features");
      patterns = Objects.requireNonNull(patterns, "patterns");
      packPolicy = Objects.requireNonNull(packPolicy, "packPolicy");
      pronouns = Objects.requireNonNull(pronouns, "pronouns");
      samples = Objects.requireNonNull(samples, "samples");
      policyQuestions = List.copyOf(Objects.requireNonNull(policyQuestions, "policyQuestions"));
      if (policyQuestions.isEmpty()) {
        throw new IllegalArgumentException("Arabic audit requires policy questions");
      }
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
      int ambiguousAgreementSurfaces,
      int baseCandidateEntries,
      int constructAgreementEntries,
      int dictionaryEntries,
      int dualAgreementEntries,
      int fullySpecifiedAgreementEntries,
      int inflectionPatterns,
      int missingAgreementPatterns,
      int skippedDictionaryLines,
      int termEntries,
      int usedAgreementPatterns,
      int usedTermPatterns) {

    public Counts {
      requireNonNegative(agreementEntries, "agreementEntries");
      requireNonNegative(ambiguousAgreementSurfaces, "ambiguousAgreementSurfaces");
      requireNonNegative(baseCandidateEntries, "baseCandidateEntries");
      requireNonNegative(constructAgreementEntries, "constructAgreementEntries");
      requireNonNegative(dictionaryEntries, "dictionaryEntries");
      requireNonNegative(dualAgreementEntries, "dualAgreementEntries");
      requireNonNegative(fullySpecifiedAgreementEntries, "fullySpecifiedAgreementEntries");
      requireNonNegative(inflectionPatterns, "inflectionPatterns");
      requireNonNegative(missingAgreementPatterns, "missingAgreementPatterns");
      requireNonNegative(skippedDictionaryLines, "skippedDictionaryLines");
      requireNonNegative(termEntries, "termEntries");
      requireNonNegative(usedAgreementPatterns, "usedAgreementPatterns");
      requireNonNegative(usedTermPatterns, "usedTermPatterns");
    }
  }

  public record Features(
      Map<String, Integer> agreementInflections,
      Map<String, Integer> ambiguityReasons,
      Map<String, Integer> animacy,
      Map<String, Integer> grammaticalCase,
      Map<String, Integer> definiteness,
      Map<String, Integer> gender,
      Map<String, Integer> number,
      Map<String, Integer> partOfSpeech,
      Map<String, Integer> termPartOfSpeech,
      Map<String, Integer> unknownGrammemes) {

    public Features {
      agreementInflections = unmodifiableLinkedMap(agreementInflections);
      ambiguityReasons = unmodifiableLinkedMap(ambiguityReasons);
      animacy = unmodifiableLinkedMap(animacy);
      grammaticalCase = unmodifiableLinkedMap(grammaticalCase);
      definiteness = unmodifiableLinkedMap(definiteness);
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
      String constructMode,
      String countMode,
      String pronounScope,
      String pronounAttachmentPolicy,
      String adjectiveAgreementPolicy,
      boolean openWorldGeneration,
      int baseCandidateEntries,
      int explicitFormCandidateRows,
      int ambiguousSurfaceRows,
      int verbBearingTermPatternSlots,
      List<String> reviewRequiredReasons,
      Map<String, Integer> reviewRequiredEvidence) {

    public PackPolicy {
      recommendation = Objects.requireNonNull(recommendation, "recommendation");
      reason = Objects.requireNonNull(reason, "reason");
      termScope = List.copyOf(termScope);
      runtimeOptions = List.copyOf(runtimeOptions);
      metadataBits = List.copyOf(metadataBits);
      caseMode = Objects.requireNonNull(caseMode, "caseMode");
      numberMode = Objects.requireNonNull(numberMode, "numberMode");
      constructMode = Objects.requireNonNull(constructMode, "constructMode");
      countMode = Objects.requireNonNull(countMode, "countMode");
      pronounScope = Objects.requireNonNull(pronounScope, "pronounScope");
      pronounAttachmentPolicy =
          Objects.requireNonNull(pronounAttachmentPolicy, "pronounAttachmentPolicy");
      adjectiveAgreementPolicy =
          Objects.requireNonNull(adjectiveAgreementPolicy, "adjectiveAgreementPolicy");
      requireNonNegative(baseCandidateEntries, "baseCandidateEntries");
      requireNonNegative(explicitFormCandidateRows, "explicitFormCandidateRows");
      requireNonNegative(ambiguousSurfaceRows, "ambiguousSurfaceRows");
      requireNonNegative(verbBearingTermPatternSlots, "verbBearingTermPatternSlots");
      reviewRequiredReasons = List.copyOf(reviewRequiredReasons);
      reviewRequiredEvidence = unmodifiableLinkedMap(reviewRequiredEvidence);
    }
  }

  public record PatternStats(
      int constructSlots,
      int dualSlots,
      int duplicateSlotRows,
      int fullySpecifiedCaseNumberGenderDefinitenessSlots,
      int patternsWithDuplicateSlots,
      Map<String, Map<String, Integer>> slotAttributes,
      Map<String, Integer> slotsPerPattern) {

    public PatternStats {
      requireNonNegative(constructSlots, "constructSlots");
      requireNonNegative(dualSlots, "dualSlots");
      requireNonNegative(duplicateSlotRows, "duplicateSlotRows");
      requireNonNegative(
          fullySpecifiedCaseNumberGenderDefinitenessSlots,
          "fullySpecifiedCaseNumberGenderDefinitenessSlots");
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
      Map<String, Integer> features,
      Map<String, Integer> genders,
      Map<String, Integer> numbers,
      Map<String, Integer> persons,
      int rows,
      int uniqueValues) {

    public Pronouns {
      requireNonNegative(rows, "rows");
      requireNonNegative(uniqueValues, "uniqueValues");
      cases = unmodifiableLinkedMap(cases);
      features = unmodifiableLinkedMap(features);
      genders = unmodifiableLinkedMap(genders);
      numbers = unmodifiableLinkedMap(numbers);
      persons = unmodifiableLinkedMap(persons);
    }
  }

  public record Samples(
      List<AmbiguousSurface> ambiguousAgreementSurfaces,
      List<CompactEntry> baseCandidates,
      List<CompactEntry> constructAgreementEntries,
      List<CompactEntry> dualAgreementEntries,
      List<String> missingAgreementPatterns,
      List<PronounSample> pronouns) {

    public Samples {
      ambiguousAgreementSurfaces = List.copyOf(ambiguousAgreementSurfaces);
      baseCandidates = List.copyOf(baseCandidates);
      constructAgreementEntries = List.copyOf(constructAgreementEntries);
      dualAgreementEntries = List.copyOf(dualAgreementEntries);
      missingAgreementPatterns = List.copyOf(missingAgreementPatterns);
      pronouns = List.copyOf(pronouns);
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
      List<String> definiteness,
      List<String> animacy,
      List<String> inflections) {

    public CompactEntry {
      surface = Objects.requireNonNull(surface, "surface");
      requirePositive(line, "line");
      partOfSpeech = List.copyOf(partOfSpeech);
      grammaticalCase = List.copyOf(grammaticalCase);
      gender = List.copyOf(gender);
      number = List.copyOf(number);
      definiteness = List.copyOf(definiteness);
      animacy = List.copyOf(animacy);
      inflections = List.copyOf(inflections);
    }
  }

  public record PronounSample(String value, int line, List<String> features) {

    public PronounSample {
      value = Objects.requireNonNull(value, "value");
      requirePositive(line, "line");
      features = List.copyOf(features);
    }
  }
}
