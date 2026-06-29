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
 * Loads the generated Hebrew pack audit. The audit pins the generator-side evidence for choosing
 * the Hebrew runtime pack shape; it is not itself a renderer-ready pack.
 */
@GeneratorSupport
class HebrewPackAuditJsonLoader {

  static final String EXPECTED_SCHEMA = "mojito-mf2-inflection/he-pack-audit/v0";
  static final String EXPECTED_LOCALE = "he";
  static final String EXPECTED_PACK_RECOMMENDATION =
      "closed-world construct-state explicit-form pack";
  static final String EXPECTED_CASE_MODE = "unsupported-for-nouns-v0";
  static final String EXPECTED_CONSTRUCT_MODE = "explicit-form-key";
  static final String EXPECTED_ARTICLE_MODE = "not-derived-from-dictionary-v0";
  static final String EXPECTED_NUMBER_MODE = "explicit-number-option";
  static final String EXPECTED_COUNT_MODE = "singular-plural-only-by-product-policy";
  static final String EXPECTED_PRONOUN_SCOPE = "inventory-only-v0";
  static final String EXPECTED_PRONOUN_ATTACHMENT_POLICY =
      "separate-hebrew-attachment-profile-later";

  private static final Set<String> PART_OF_SPEECH_VALUES =
      Set.of(
          "<missing>",
          "adjective",
          "adposition",
          "adverb",
          "article",
          "conjunction",
          "determiner",
          "interjection",
          "noun",
          "numeral",
          "pronoun",
          "proper-noun",
          "verb");
  private static final Set<String> SAMPLE_PART_OF_SPEECH_VALUES =
      Set.of(
          "adjective",
          "adposition",
          "adverb",
          "article",
          "conjunction",
          "determiner",
          "interjection",
          "noun",
          "numeral",
          "pronoun",
          "proper-noun",
          "verb");
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
  private static final Set<String> AMBIGUITY_REASON_VALUES =
      Set.of(
          "multiple-cases",
          "multiple-definiteness",
          "multiple-entries",
          "multiple-genders",
          "multiple-inflections",
          "multiple-numbers",
          "multiple-parts-of-speech");
  private static final Set<String> UNKNOWN_GRAMMEME_VALUES =
      Set.of("abbreviation", "animate", "infinitive", "interrogative", "negative");
  private static final Set<String> SLOT_ATTRIBUTE_KEYS =
      Set.of(
          "animacy",
          "case",
          "count",
          "definiteness",
          "gender",
          "mood",
          "number",
          "person",
          "tense",
          "verb-type");
  private static final Set<String> PRONOUN_CASE_VALUES =
      Set.of("genitive", "nominative", "reflexive");
  private static final Set<String> PRONOUN_PERSON_VALUES = Set.of("first", "second", "third");
  private static final Set<String> PRONOUN_FEATURE_VALUES =
      Set.of(
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
  private static final List<String> EXPECTED_TERM_SCOPE = List.of("noun", "proper-noun");
  private static final List<String> EXPECTED_RUNTIME_OPTIONS = List.of("number", "definiteness");
  private static final List<String> EXPECTED_METADATA_BITS = List.of("gender", "partOfSpeech");
  private static final List<String> EXPECTED_APPROVED_FIXTURE_REQUIRED_FORM_KEYS =
      List.of(
          "bare.singular",
          "bare.plural",
          "construct.singular",
          "construct.plural",
          "construct.dual");
  private static final List<String> EXPECTED_APPROVED_FIXTURE_CLEAN_PART_OF_SPEECH =
      List.of("noun", "proper-noun");
  private static final List<String> EXPECTED_APPROVED_FIXTURE_EXCLUDED_PART_OF_SPEECH =
      List.of("adjective", "verb");
  private static final Set<String> APPROVED_FIXTURE_FORM_KEYS =
      Set.of(
          "bare.singular",
          "bare.plural",
          "construct.singular",
          "construct.plural",
          "construct.dual");

  private final ObjectMapper objectMapper;

  HebrewPackAuditJsonLoader() {
    this(new ObjectMapper());
  }

  HebrewPackAuditJsonLoader(ObjectMapper objectMapper) {
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
  }

  public HebrewPackAudit load(String json) {
    Objects.requireNonNull(json, "json");
    return load(objectMapper.readTreeUnchecked(json));
  }

  public HebrewPackAudit load(JsonNode root) {
    HebrewPackAudit audit =
        new HebrewPackAudit(
            requiredText(root, "schema"),
            requiredText(root, "locale"),
            requiredText(root, "description"),
            loadApprovedFixtureCandidateSearch(
                requiredObject(root, "approvedFixtureCandidateSearch")),
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
    validatePackPolicy(audit.counts(), audit.patterns(), audit.packPolicy());
    validateApprovedFixtureCandidateSearch(audit.approvedFixtureCandidateSearch());
    return audit;
  }

  private ApprovedFixtureCandidateSearch loadApprovedFixtureCandidateSearch(JsonNode node) {
    return new ApprovedFixtureCandidateSearch(
        textArray(
            requiredArray(node, "requiredFormKeys"),
            "requiredFormKeys",
            APPROVED_FIXTURE_FORM_KEYS,
            "Hebrew"),
        textArray(
            requiredArray(node, "cleanPartOfSpeech"),
            "cleanPartOfSpeech",
            SAMPLE_PART_OF_SPEECH_VALUES,
            "Hebrew"),
        textArray(
            requiredArray(node, "excludedPartOfSpeech"),
            "excludedPartOfSpeech",
            SAMPLE_PART_OF_SPEECH_VALUES,
            "Hebrew"),
        requiredBoolean(node, "singleGenderRequired"),
        requiredBoolean(node, "singlePartOfSpeechRequired"),
        requiredInt(node, "completeCleanGroups"),
        requiredInt(node, "constructDualCleanGroups"),
        requiredInt(node, "nearCompleteCleanGroups"),
        requiredInt(node, "maxObservedCleanFormKeys"),
        loadApprovedFixtureCandidateGroups(requiredArray(node, "constructDualCleanGroupSamples")),
        loadApprovedFixtureCandidateGroups(requiredArray(node, "nearCompleteCleanGroupSamples")));
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
        textArray(requiredArray(node, "sourceLabels"), "sourceLabels", null, "Hebrew"));
  }

  private Counts loadCounts(JsonNode node) {
    return new Counts(
        requiredInt(node, "agreementEntries"),
        requiredInt(node, "agreementSurfaces"),
        requiredInt(node, "ambiguousTermSurfaces"),
        requiredInt(node, "caseTaggedAgreementEntries"),
        requiredInt(node, "constructAgreementEntries"),
        requiredInt(node, "dictionaryEntries"),
        requiredInt(node, "dualAgreementEntries"),
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
        textArray(requiredArray(node, "termScope"), "termScope", null, "Hebrew"),
        textArray(requiredArray(node, "runtimeOptions"), "runtimeOptions", null, "Hebrew"),
        textArray(requiredArray(node, "metadataBits"), "metadataBits", null, "Hebrew"),
        requiredText(node, "caseMode"),
        requiredText(node, "constructMode"),
        requiredText(node, "articleMode"),
        requiredText(node, "numberMode"),
        requiredText(node, "countMode"),
        requiredText(node, "pronounScope"),
        requiredText(node, "pronounAttachmentPolicy"),
        requiredBoolean(node, "openWorldGeneration"));
  }

  private PatternStats loadPatternStats(JsonNode node) {
    return new PatternStats(
        requiredInt(node, "caseSlots"),
        requiredInt(node, "constructSlots"),
        requiredInt(node, "dualSlots"),
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
        throw new IllegalArgumentException("Unsupported Hebrew slot attribute: " + key);
      }
      values.put(
          key, featureMap(entry.getValue(), "slotAttributes." + key, slotAllowedValues(key)));
    }
    return unmodifiableLinkedMap(values);
  }

  private Set<String> slotAllowedValues(String key) {
    return switch (key) {
      case "animacy" -> Set.of("animate");
      case "case" -> SAMPLE_CASE_VALUES;
      case "count" -> Set.of("countable", "uncountable");
      case "definiteness" -> SAMPLE_DEFINITENESS_VALUES;
      case "gender" -> SAMPLE_GENDER_VALUES;
      case "mood" -> Set.of("imperative", "jussive");
      case "number" -> SAMPLE_NUMBER_VALUES;
      case "person" -> PRONOUN_PERSON_VALUES;
      case "tense" -> Set.of("future", "past", "present");
      case "verb-type" -> Set.of("infinitive");
      default -> throw new IllegalArgumentException("Unsupported Hebrew slot attribute: " + key);
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

  private List<ApprovedFixtureCandidateGroup> loadApprovedFixtureCandidateGroups(JsonNode node) {
    List<ApprovedFixtureCandidateGroup> groups = new ArrayList<>();
    for (JsonNode groupNode : node) {
      groups.add(
          new ApprovedFixtureCandidateGroup(
              textArray(requiredArray(groupNode, "inflections"), "inflections", null, "Hebrew"),
              requiredPositiveInt(groupNode, "cleanEntries"),
              textArray(
                  requiredArray(groupNode, "gender"), "gender", SAMPLE_GENDER_VALUES, "Hebrew"),
              textArray(
                  requiredArray(groupNode, "partOfSpeech"),
                  "partOfSpeech",
                  SAMPLE_PART_OF_SPEECH_VALUES,
                  "Hebrew"),
              textArray(
                  requiredArray(groupNode, "formKeys"),
                  "formKeys",
                  APPROVED_FIXTURE_FORM_KEYS,
                  "Hebrew"),
              textArray(
                  requiredArray(groupNode, "missingFormKeys"),
                  "missingFormKeys",
                  APPROVED_FIXTURE_FORM_KEYS,
                  "Hebrew"),
              compactEntriesByFormKey(requiredObject(groupNode, "sampleEntriesByFormKey"))));
    }
    return List.copyOf(groups);
  }

  private Map<String, List<CompactEntry>> compactEntriesByFormKey(JsonNode node) {
    Map<String, List<CompactEntry>> values = new LinkedHashMap<>();
    for (var fields = node.fields(); fields.hasNext(); ) {
      Map.Entry<String, JsonNode> entry = fields.next();
      String key = entry.getKey();
      if (!APPROVED_FIXTURE_FORM_KEYS.contains(key)) {
        throw new IllegalArgumentException("Unsupported Hebrew approved-fixture form key: " + key);
      }
      if (!entry.getValue().isArray()) {
        throw new IllegalArgumentException("Expected array field: sampleEntriesByFormKey." + key);
      }
      values.put(key, compactEntries(entry.getValue()));
    }
    return unmodifiableCandidateEntryMap(values);
  }

  private Samples loadSamples(JsonNode node) {
    return new Samples(
        loadAmbiguousSurfaces(requiredArray(node, "ambiguousTermSurfaces")),
        compactEntries(requiredArray(node, "caseTaggedAgreementEntries")),
        compactEntries(requiredArray(node, "constructAgreementEntries")),
        compactEntries(requiredArray(node, "dualAgreementEntries")),
        textArray(
            requiredArray(node, "missingAgreementPatterns"),
            "missingAgreementPatterns",
            null,
            "Hebrew"));
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
                  "Hebrew"),
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
                  "Hebrew"),
              textArray(requiredArray(sampleNode, "case"), "case", SAMPLE_CASE_VALUES, "Hebrew"),
              textArray(
                  requiredArray(sampleNode, "gender"), "gender", SAMPLE_GENDER_VALUES, "Hebrew"),
              textArray(
                  requiredArray(sampleNode, "number"), "number", SAMPLE_NUMBER_VALUES, "Hebrew"),
              textArray(
                  requiredArray(sampleNode, "definiteness"),
                  "definiteness",
                  SAMPLE_DEFINITENESS_VALUES,
                  "Hebrew"),
              textArray(requiredArray(sampleNode, "inflections"), "inflections", null, "Hebrew")));
    }
    return List.copyOf(samples);
  }

  private void validateProvenance(Provenance provenance) {
    if (!"Unicode-3.0".equals(provenance.license())) {
      throw new IllegalArgumentException("Unexpected Hebrew audit license");
    }
    if (!"dev-docs/experiments/mf2-inflection/he_pack_audit.py".equals(provenance.generator())) {
      throw new IllegalArgumentException("Unexpected Hebrew audit generator");
    }
    if (provenance.sources().isEmpty()) {
      throw new IllegalArgumentException("Hebrew audit must include sources");
    }
    List<String> sourcePaths = provenance.sources().stream().map(Source::path).toList();
    if (!sourcePaths.equals(provenance.sourceLabels())) {
      throw new IllegalArgumentException("Hebrew provenance source labels must match sources");
    }
  }

  private void validateCounts(
      Counts counts, Features features, Patterns patterns, Samples samples) {
    if (counts.uniqueSurfaces() > counts.dictionaryEntries()
        || counts.termSurfaces() > counts.termEntries()
        || counts.agreementSurfaces() > counts.agreementEntries()) {
      throw new IllegalArgumentException("Hebrew surface counts exceed entry counts");
    }
    if (counts.termEntries() > counts.dictionaryEntries()
        || counts.agreementEntries() > counts.dictionaryEntries()) {
      throw new IllegalArgumentException("Hebrew entry counts exceed dictionary entries");
    }
    if (counts.constructAgreementEntries() > counts.agreementEntries()
        || counts.dualAgreementEntries() > counts.agreementEntries()
        || counts.caseTaggedAgreementEntries() > counts.agreementEntries()) {
      throw new IllegalArgumentException("Hebrew agreement subset count exceeds agreement entries");
    }
    if (counts.usedAgreementPatterns() > counts.inflectionPatterns()
        || counts.usedTermPatterns() > counts.inflectionPatterns()
        || counts.missingAgreementPatterns() > counts.inflectionPatterns()) {
      throw new IllegalArgumentException("Hebrew pattern counts exceed available patterns");
    }
    if (features.agreementInflections().size() < counts.usedAgreementPatterns()) {
      throw new IllegalArgumentException("Hebrew agreement inflection feature map is incomplete");
    }
    if (patterns.usedAgreement().slotsPerPattern().isEmpty()
        || patterns.usedTerms().slotsPerPattern().isEmpty()) {
      throw new IllegalArgumentException("Hebrew used-pattern stats must not be empty");
    }
    if (samples.constructAgreementEntries().size() > counts.constructAgreementEntries()
        || samples.dualAgreementEntries().size() > counts.dualAgreementEntries()
        || samples.caseTaggedAgreementEntries().size() > counts.caseTaggedAgreementEntries()
        || samples.ambiguousTermSurfaces().size() > counts.ambiguousTermSurfaces()) {
      throw new IllegalArgumentException("Hebrew sample count exceeds reported count");
    }
    if (samples.missingAgreementPatterns().size() > counts.missingAgreementPatterns()) {
      throw new IllegalArgumentException("Hebrew missing-pattern samples exceed reported count");
    }
    if (counts.missingAgreementPatterns() == 0 && !samples.missingAgreementPatterns().isEmpty()) {
      throw new IllegalArgumentException(
          "Hebrew missing-pattern samples present when count is zero");
    }
  }

  private void validatePatternStats(Patterns patterns) {
    validatePatternStats("all", patterns.all());
    validatePatternStats("usedAgreement", patterns.usedAgreement());
    validatePatternStats("usedTerms", patterns.usedTerms());
    if (patterns.usedAgreement().dualSlots() > patterns.all().dualSlots()
        || patterns.usedTerms().dualSlots() > patterns.usedAgreement().dualSlots()) {
      throw new IllegalArgumentException("Hebrew dual slot counts are incoherent");
    }
    if (patterns.usedAgreement().constructSlots() > patterns.all().constructSlots()
        || patterns.usedTerms().constructSlots() > patterns.usedAgreement().constructSlots()) {
      throw new IllegalArgumentException("Hebrew construct slot counts are incoherent");
    }
    if (patterns.usedAgreement().caseSlots() > patterns.all().caseSlots()
        || patterns.usedTerms().caseSlots() > patterns.usedAgreement().caseSlots()) {
      throw new IllegalArgumentException("Hebrew case slot counts are incoherent");
    }
  }

  private void validatePatternStats(String name, PatternStats stats) {
    if (stats.duplicateSlotRows() < stats.patternsWithDuplicateSlots()) {
      throw new IllegalArgumentException(
          "Hebrew " + name + " duplicate rows are lower than duplicate patterns");
    }
    if (stats.dualSlots() != nestedValue(stats.slotAttributes(), "number", "dual")) {
      throw new IllegalArgumentException("Hebrew " + name + " dual slot count is incoherent");
    }
    if (stats.constructSlots()
        != nestedValue(stats.slotAttributes(), "definiteness", "construct")) {
      throw new IllegalArgumentException("Hebrew " + name + " construct slot count is incoherent");
    }
    if (stats.caseSlots() != sumValues(stats.slotAttributes().getOrDefault("case", Map.of()))) {
      throw new IllegalArgumentException("Hebrew " + name + " case slot count is incoherent");
    }
    if (weightedNumericKeySum(stats.slotsPerPattern()) <= 0) {
      throw new IllegalArgumentException("Hebrew " + name + " slots-per-pattern is empty");
    }
  }

  private void validatePronouns(Pronouns pronouns) {
    if (pronouns.uniqueValues() > pronouns.rows()) {
      throw new IllegalArgumentException("Hebrew unique pronouns exceed pronoun rows");
    }
    if (sumValues(pronouns.cases()) != pronouns.rows()) {
      throw new IllegalArgumentException("Hebrew pronoun case counts must sum to rows");
    }
    if (sumValues(pronouns.persons()) != pronouns.rows()) {
      throw new IllegalArgumentException("Hebrew pronoun person counts must sum to rows");
    }
    if (sumValues(pronouns.numbers()) != pronouns.rows()) {
      throw new IllegalArgumentException("Hebrew pronoun number counts must sum to rows");
    }
  }

  private void validatePackPolicy(Counts counts, Patterns patterns, PackPolicy policy) {
    if (!EXPECTED_PACK_RECOMMENDATION.equals(policy.recommendation())) {
      throw new IllegalArgumentException("Unexpected Hebrew pack recommendation");
    }
    if (!EXPECTED_TERM_SCOPE.equals(policy.termScope())) {
      throw new IllegalArgumentException("Unexpected Hebrew term scope");
    }
    if (!EXPECTED_RUNTIME_OPTIONS.equals(policy.runtimeOptions())) {
      throw new IllegalArgumentException("Unexpected Hebrew runtime options");
    }
    if (!EXPECTED_METADATA_BITS.equals(policy.metadataBits())) {
      throw new IllegalArgumentException("Unexpected Hebrew metadata bits");
    }
    if (!EXPECTED_CASE_MODE.equals(policy.caseMode())
        || !EXPECTED_CONSTRUCT_MODE.equals(policy.constructMode())
        || !EXPECTED_ARTICLE_MODE.equals(policy.articleMode())
        || !EXPECTED_NUMBER_MODE.equals(policy.numberMode())
        || !EXPECTED_COUNT_MODE.equals(policy.countMode())) {
      throw new IllegalArgumentException("Unexpected Hebrew option policy");
    }
    if (!EXPECTED_PRONOUN_SCOPE.equals(policy.pronounScope())
        || !EXPECTED_PRONOUN_ATTACHMENT_POLICY.equals(policy.pronounAttachmentPolicy())) {
      throw new IllegalArgumentException("Unexpected Hebrew pronoun policy");
    }
    if (policy.openWorldGeneration()) {
      throw new IllegalArgumentException("Hebrew V0 must not enable open-world generation");
    }
    if (counts.constructAgreementEntries() <= 0 || patterns.usedTerms().constructSlots() <= 0) {
      throw new IllegalArgumentException("Hebrew construct-state policy requires evidence");
    }
    if (counts.caseTaggedAgreementEntries() > counts.constructAgreementEntries()) {
      throw new IllegalArgumentException(
          "Hebrew case evidence unexpectedly exceeds construct data");
    }
  }

  private void validateApprovedFixtureCandidateSearch(ApprovedFixtureCandidateSearch search) {
    if (!EXPECTED_APPROVED_FIXTURE_REQUIRED_FORM_KEYS.equals(search.requiredFormKeys())) {
      throw new IllegalArgumentException("Unexpected Hebrew approved-fixture required form keys");
    }
    if (!EXPECTED_APPROVED_FIXTURE_CLEAN_PART_OF_SPEECH.equals(search.cleanPartOfSpeech())
        || !EXPECTED_APPROVED_FIXTURE_EXCLUDED_PART_OF_SPEECH.equals(
            search.excludedPartOfSpeech())) {
      throw new IllegalArgumentException("Unexpected Hebrew approved-fixture clean criteria");
    }
    if (!search.singleGenderRequired() || !search.singlePartOfSpeechRequired()) {
      throw new IllegalArgumentException(
          "Hebrew approved-fixture search must require one gender and one part of speech");
    }
    if (search.completeCleanGroups() != 0) {
      throw new IllegalArgumentException(
          "Hebrew audit unexpectedly found a complete approved-fixture candidate");
    }
    if (search.constructDualCleanGroups() != 1) {
      throw new IllegalArgumentException(
          "Hebrew audit expected exactly one construct-dual clean group");
    }
    if (search.nearCompleteCleanGroups() <= 0 || search.maxObservedCleanFormKeys() != 4) {
      throw new IllegalArgumentException(
          "Hebrew approved-fixture search no longer matches the expected partial coverage");
    }
    validateApprovedFixtureCandidateGroups(
        search.constructDualCleanGroupSamples(), search.constructDualCleanGroups());
    validateApprovedFixtureCandidateGroups(
        search.nearCompleteCleanGroupSamples(), search.nearCompleteCleanGroups());
    if (search.constructDualCleanGroupSamples().stream()
        .noneMatch(group -> group.formKeys().contains("construct.dual"))) {
      throw new IllegalArgumentException(
          "Hebrew approved-fixture search must include construct-dual sample evidence");
    }
  }

  private void validateApprovedFixtureCandidateGroups(
      List<ApprovedFixtureCandidateGroup> groups, int reportedCount) {
    if (groups.size() > reportedCount) {
      throw new IllegalArgumentException(
          "Hebrew approved-fixture group samples exceed reported count");
    }
    for (ApprovedFixtureCandidateGroup group : groups) {
      if (group.inflections().isEmpty()
          || group.gender().isEmpty()
          || group.partOfSpeech().isEmpty()) {
        throw new IllegalArgumentException("Hebrew approved-fixture group sample is incomplete");
      }
      if (!group.formKeys().containsAll(group.sampleEntriesByFormKey().keySet())) {
        throw new IllegalArgumentException(
            "Hebrew approved-fixture sample entries use keys outside formKeys");
      }
      for (List<CompactEntry> entries : group.sampleEntriesByFormKey().values()) {
        if (entries.isEmpty()) {
          throw new IllegalArgumentException(
              "Hebrew approved-fixture sample entry lists must not be empty");
        }
      }
    }
  }

  private Map<String, Integer> featureMap(JsonNode node, String field, Set<String> allowedKeys) {
    Map<String, Integer> values = new LinkedHashMap<>();
    for (var fields = node.fields(); fields.hasNext(); ) {
      Map.Entry<String, JsonNode> entry = fields.next();
      String key = entry.getKey();
      if (allowedKeys != null && !allowedKeys.contains(key)) {
        throw new IllegalArgumentException("Unsupported Hebrew " + field + " key: " + key);
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
        throw new IllegalArgumentException("Expected numeric Hebrew " + field + " key", e);
      }
      if (key < minKey || key > maxKey) {
        throw new IllegalArgumentException("Hebrew " + field + " key out of range: " + key);
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

  private static Map<String, List<CompactEntry>> unmodifiableCandidateEntryMap(
      Map<String, List<CompactEntry>> values) {
    Map<String, List<CompactEntry>> copied = new LinkedHashMap<>();
    for (Map.Entry<String, List<CompactEntry>> entry :
        Objects.requireNonNull(values, "values").entrySet()) {
      copied.put(entry.getKey(), List.copyOf(entry.getValue()));
    }
    return Collections.unmodifiableMap(copied);
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

  public record HebrewPackAudit(
      String schema,
      String locale,
      String description,
      ApprovedFixtureCandidateSearch approvedFixtureCandidateSearch,
      Provenance provenance,
      Counts counts,
      Features features,
      Patterns patterns,
      PackPolicy packPolicy,
      Pronouns pronouns,
      Samples samples) {

    public HebrewPackAudit {
      if (!EXPECTED_SCHEMA.equals(schema)) {
        throw new IllegalArgumentException(
            "Expected schema " + EXPECTED_SCHEMA + ", got " + schema);
      }
      if (!EXPECTED_LOCALE.equals(locale)) {
        throw new IllegalArgumentException("Expected locale he, got " + locale);
      }
      description = Objects.requireNonNull(description, "description");
      approvedFixtureCandidateSearch =
          Objects.requireNonNull(approvedFixtureCandidateSearch, "approvedFixtureCandidateSearch");
      provenance = Objects.requireNonNull(provenance, "provenance");
      counts = Objects.requireNonNull(counts, "counts");
      features = Objects.requireNonNull(features, "features");
      patterns = Objects.requireNonNull(patterns, "patterns");
      packPolicy = Objects.requireNonNull(packPolicy, "packPolicy");
      pronouns = Objects.requireNonNull(pronouns, "pronouns");
      samples = Objects.requireNonNull(samples, "samples");
    }
  }

  public record ApprovedFixtureCandidateSearch(
      List<String> requiredFormKeys,
      List<String> cleanPartOfSpeech,
      List<String> excludedPartOfSpeech,
      boolean singleGenderRequired,
      boolean singlePartOfSpeechRequired,
      int completeCleanGroups,
      int constructDualCleanGroups,
      int nearCompleteCleanGroups,
      int maxObservedCleanFormKeys,
      List<ApprovedFixtureCandidateGroup> constructDualCleanGroupSamples,
      List<ApprovedFixtureCandidateGroup> nearCompleteCleanGroupSamples) {

    public ApprovedFixtureCandidateSearch {
      requiredFormKeys = List.copyOf(requiredFormKeys);
      cleanPartOfSpeech = List.copyOf(cleanPartOfSpeech);
      excludedPartOfSpeech = List.copyOf(excludedPartOfSpeech);
      requireNonNegative(completeCleanGroups, "completeCleanGroups");
      requireNonNegative(constructDualCleanGroups, "constructDualCleanGroups");
      requireNonNegative(nearCompleteCleanGroups, "nearCompleteCleanGroups");
      requireNonNegative(maxObservedCleanFormKeys, "maxObservedCleanFormKeys");
      constructDualCleanGroupSamples = List.copyOf(constructDualCleanGroupSamples);
      nearCompleteCleanGroupSamples = List.copyOf(nearCompleteCleanGroupSamples);
    }
  }

  public record ApprovedFixtureCandidateGroup(
      List<String> inflections,
      int cleanEntries,
      List<String> gender,
      List<String> partOfSpeech,
      List<String> formKeys,
      List<String> missingFormKeys,
      Map<String, List<CompactEntry>> sampleEntriesByFormKey) {

    public ApprovedFixtureCandidateGroup {
      inflections = List.copyOf(inflections);
      requirePositive(cleanEntries, "cleanEntries");
      gender = List.copyOf(gender);
      partOfSpeech = List.copyOf(partOfSpeech);
      formKeys = List.copyOf(formKeys);
      missingFormKeys = List.copyOf(missingFormKeys);
      sampleEntriesByFormKey = unmodifiableCandidateEntryMap(sampleEntriesByFormKey);
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
      int constructAgreementEntries,
      int dictionaryEntries,
      int dualAgreementEntries,
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
      requireNonNegative(constructAgreementEntries, "constructAgreementEntries");
      requireNonNegative(dictionaryEntries, "dictionaryEntries");
      requireNonNegative(dualAgreementEntries, "dualAgreementEntries");
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
      Map<String, Integer> grammaticalCase,
      Map<String, Integer> definiteness,
      Map<String, Integer> gender,
      Map<String, Integer> number,
      Map<String, Integer> partOfSpeech,
      Map<String, Integer> termPartOfSpeech,
      Map<String, Integer> unknownGrammemes) {

    public Features {
      agreementInflections = unmodifiableLinkedMap(agreementInflections);
      agreementPartOfSpeech = unmodifiableLinkedMap(agreementPartOfSpeech);
      ambiguityReasons = unmodifiableLinkedMap(ambiguityReasons);
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
      String constructMode,
      String articleMode,
      String numberMode,
      String countMode,
      String pronounScope,
      String pronounAttachmentPolicy,
      boolean openWorldGeneration) {

    public PackPolicy {
      recommendation = Objects.requireNonNull(recommendation, "recommendation");
      reason = Objects.requireNonNull(reason, "reason");
      termScope = List.copyOf(termScope);
      runtimeOptions = List.copyOf(runtimeOptions);
      metadataBits = List.copyOf(metadataBits);
      caseMode = Objects.requireNonNull(caseMode, "caseMode");
      constructMode = Objects.requireNonNull(constructMode, "constructMode");
      articleMode = Objects.requireNonNull(articleMode, "articleMode");
      numberMode = Objects.requireNonNull(numberMode, "numberMode");
      countMode = Objects.requireNonNull(countMode, "countMode");
      pronounScope = Objects.requireNonNull(pronounScope, "pronounScope");
      pronounAttachmentPolicy =
          Objects.requireNonNull(pronounAttachmentPolicy, "pronounAttachmentPolicy");
    }
  }

  public record PatternStats(
      int caseSlots,
      int constructSlots,
      int dualSlots,
      int duplicateSlotRows,
      int patternsWithDuplicateSlots,
      Map<String, Map<String, Integer>> slotAttributes,
      Map<String, Integer> slotsPerPattern) {

    public PatternStats {
      requireNonNegative(caseSlots, "caseSlots");
      requireNonNegative(constructSlots, "constructSlots");
      requireNonNegative(dualSlots, "dualSlots");
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
      List<AmbiguousSurface> ambiguousTermSurfaces,
      List<CompactEntry> caseTaggedAgreementEntries,
      List<CompactEntry> constructAgreementEntries,
      List<CompactEntry> dualAgreementEntries,
      List<String> missingAgreementPatterns) {

    public Samples {
      ambiguousTermSurfaces = List.copyOf(ambiguousTermSurfaces);
      caseTaggedAgreementEntries = List.copyOf(caseTaggedAgreementEntries);
      constructAgreementEntries = List.copyOf(constructAgreementEntries);
      dualAgreementEntries = List.copyOf(dualAgreementEntries);
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
      List<String> definiteness,
      List<String> inflections) {

    public CompactEntry {
      surface = Objects.requireNonNull(surface, "surface");
      requirePositive(line, "line");
      partOfSpeech = List.copyOf(partOfSpeech);
      grammaticalCase = List.copyOf(grammaticalCase);
      gender = List.copyOf(gender);
      number = List.copyOf(number);
      definiteness = List.copyOf(definiteness);
      inflections = List.copyOf(inflections);
    }
  }
}
