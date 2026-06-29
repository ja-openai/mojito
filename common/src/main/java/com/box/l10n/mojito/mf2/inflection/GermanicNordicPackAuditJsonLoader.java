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
 * Loads the generated Germanic/Nordic pack audit. The audit pins cross-locale evidence for the
 * Danish/Swedish explicit genitive-definiteness path and the Bokmal/Dutch metadata-first path.
 */
@GeneratorSupport
class GermanicNordicPackAuditJsonLoader {

  static final String EXPECTED_SCHEMA = "mojito-mf2-inflection/germanic-nordic-pack-audit/v0";
  static final List<String> EXPECTED_LOCALES = List.of("da", "nb", "nl", "sv");
  static final List<String> EXPECTED_CASE_RUNTIME_CANDIDATE_LOCALES = List.of("da", "sv");
  static final List<String> EXPECTED_METADATA_FIRST_LOCALES = List.of("nb", "nl");
  static final String EXPECTED_GENERATOR =
      "dev-docs/experiments/mf2-inflection/germanic_nordic_pack_audit.py";
  static final String EXPECTED_EXPLICIT_RECOMMENDATION =
      "closed-world explicit genitive/definiteness pack first";
  static final String EXPECTED_EXPLICIT_CASE_MODE = "nominative-genitive-explicit-form-key";
  static final String EXPECTED_EXPLICIT_ARTICLE_MODE =
      "defer-composition-until-product-term-policy";
  static final String EXPECTED_BOKMAL_RECOMMENDATION =
      "closed-world definiteness/gender metadata pack first";
  static final String EXPECTED_DUTCH_RECOMMENDATION =
      "metadata-and-diminutive audit before case runtime";
  static final String EXPECTED_PRONOUN_SCOPE = "inventory-only-v0";

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
          "particle",
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
          "particle",
          "pronoun",
          "proper-noun",
          "verb");
  private static final Set<String> CASE_VALUES =
      Set.of("<missing>", "accusative", "dative", "genitive", "nominative");
  private static final Set<String> SAMPLE_CASE_VALUES =
      Set.of("accusative", "dative", "genitive", "nominative");
  private static final Set<String> SLOT_CASE_VALUES =
      Set.of("accusative", "dative", "genitive", "nominative", "oblique", "partitive");
  private static final Set<String> GENDER_VALUES =
      Set.of("<missing>", "common", "feminine", "masculine", "neuter");
  private static final Set<String> SAMPLE_GENDER_VALUES =
      Set.of("common", "feminine", "masculine", "neuter");
  private static final Set<String> NUMBER_VALUES = Set.of("<missing>", "plural", "singular");
  private static final Set<String> SAMPLE_NUMBER_VALUES = Set.of("plural", "singular");
  private static final Set<String> DEFINITENESS_VALUES =
      Set.of("<missing>", "definite", "indefinite");
  private static final Set<String> SAMPLE_DEFINITENESS_VALUES = Set.of("definite", "indefinite");
  private static final Set<String> DEGREE_VALUES =
      Set.of("<missing>", "comparative", "positive", "superlative");
  private static final Set<String> SAMPLE_DEGREE_VALUES =
      Set.of("comparative", "positive", "superlative");
  private static final Set<String> AMBIGUITY_REASON_VALUES =
      Set.of(
          "multiple-cases",
          "multiple-definiteness",
          "multiple-entries",
          "multiple-genders",
          "multiple-inflections",
          "multiple-numbers",
          "multiple-parts-of-speech");
  private static final Set<String> SLOT_ATTRIBUTE_KEYS =
      Set.of(
          "adjective-type",
          "case",
          "comparison-degree",
          "count",
          "definiteness",
          "gender",
          "mood",
          "number",
          "person",
          "register",
          "sizeness",
          "tense",
          "verb-type",
          "voice",
          "word-order");
  private static final Set<String> PRONOUN_PERSON_VALUES = Set.of("first", "second", "third");
  private static final List<String> EXPECTED_TERM_SCOPE = List.of("noun", "proper-noun");
  private static final List<String> EXPECTED_EXPLICIT_RUNTIME_OPTIONS =
      List.of("case", "number", "definiteness");
  private static final List<String> EXPECTED_METADATA_RUNTIME_OPTIONS =
      List.of("number", "definiteness");
  private static final List<String> EXPECTED_NUMBER_ONLY_RUNTIME_OPTIONS = List.of("number");
  private static final List<String> EXPECTED_BASIC_METADATA_BITS =
      List.of("gender", "partOfSpeech");
  private static final List<String> EXPECTED_DUTCH_METADATA_BITS =
      List.of("gender", "partOfSpeech", "sizeness");

  private final ObjectMapper objectMapper;

  GermanicNordicPackAuditJsonLoader() {
    this(new ObjectMapper());
  }

  GermanicNordicPackAuditJsonLoader(ObjectMapper objectMapper) {
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
  }

  public GermanicNordicPackAudit load(String json) {
    Objects.requireNonNull(json, "json");
    return load(objectMapper.readTreeUnchecked(json));
  }

  public GermanicNordicPackAudit load(JsonNode root) {
    List<LocaleAudit> locales = new ArrayList<>();
    for (JsonNode localeNode : requiredArray(root, "locales")) {
      locales.add(loadLocaleAudit(localeNode));
    }
    GermanicNordicPackAudit audit =
        new GermanicNordicPackAudit(
            requiredText(root, "schema"),
            requiredText(root, "description"),
            requiredText(root, "cacheDir"),
            requiredText(root, "unicodeRoot"),
            loadSummary(requiredObject(root, "summary")),
            List.copyOf(locales));

    validateSummary(audit);
    for (LocaleAudit localeAudit : audit.locales()) {
      validateLocaleAudit(localeAudit);
    }
    return audit;
  }

  private Summary loadSummary(JsonNode node) {
    return new Summary(
        textArray(
            requiredArray(node, "caseRuntimeCandidateLocales"),
            "caseRuntimeCandidateLocales",
            Set.copyOf(EXPECTED_LOCALES),
            "Germanic/Nordic"),
        requiredInt(node, "localeCount"),
        textArray(
            requiredArray(node, "locales"),
            "locales",
            Set.copyOf(EXPECTED_LOCALES),
            "Germanic/Nordic"),
        textArray(
            requiredArray(node, "metadataFirstLocales"),
            "metadataFirstLocales",
            Set.copyOf(EXPECTED_LOCALES),
            "Germanic/Nordic"));
  }

  private LocaleAudit loadLocaleAudit(JsonNode node) {
    String locale = requiredText(node, "locale");
    return new LocaleAudit(
        locale,
        requiredText(node, "description"),
        loadProvenance(requiredObject(node, "provenance")),
        loadCounts(requiredObject(node, "counts")),
        loadFeatures(requiredObject(node, "features")),
        loadPatterns(requiredObject(node, "patterns")),
        loadPackPolicy(requiredObject(node, "packPolicy")),
        loadPronouns(requiredObject(node, "pronouns")),
        loadSamples(requiredObject(node, "samples")));
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
        textArray(requiredArray(node, "sourceLabels"), "sourceLabels", null, "Germanic/Nordic"),
        requiredText(node, "locale"));
  }

  private Counts loadCounts(JsonNode node) {
    return new Counts(
        requiredInt(node, "agreementEntries"),
        requiredInt(node, "agreementSurfaces"),
        requiredInt(node, "ambiguousTermSurfaces"),
        requiredInt(node, "caseTaggedAgreementEntries"),
        requiredInt(node, "definiteAgreementEntries"),
        requiredInt(node, "dictionaryEntries"),
        requiredInt(node, "diminutiveEntries"),
        requiredInt(node, "genitiveAgreementEntries"),
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
            PART_OF_SPEECH_VALUES),
        featureMap(
            requiredObject(node, "ambiguityReasons"), "ambiguityReasons", AMBIGUITY_REASON_VALUES),
        featureMap(requiredObject(node, "case"), "case", CASE_VALUES),
        featureMap(requiredObject(node, "definiteness"), "definiteness", DEFINITENESS_VALUES),
        featureMap(requiredObject(node, "degree"), "degree", DEGREE_VALUES),
        featureMap(requiredObject(node, "gender"), "gender", GENDER_VALUES),
        featureMap(requiredObject(node, "number"), "number", NUMBER_VALUES),
        featureMap(requiredObject(node, "partOfSpeech"), "partOfSpeech", PART_OF_SPEECH_VALUES),
        featureMap(
            requiredObject(node, "termPartOfSpeech"), "termPartOfSpeech", PART_OF_SPEECH_VALUES),
        featureMap(requiredObject(node, "unknownGrammemes"), "unknownGrammemes", null));
  }

  private Patterns loadPatterns(JsonNode node) {
    return new Patterns(
        loadPatternStats(requiredObject(node, "all")),
        loadPatternStats(requiredObject(node, "usedAgreement")),
        loadPatternStats(requiredObject(node, "usedTerms")));
  }

  private PatternStats loadPatternStats(JsonNode node) {
    return new PatternStats(
        requiredInt(node, "caseSlots"),
        requiredInt(node, "definitenessSlots"),
        requiredInt(node, "duplicateSlotRows"),
        requiredInt(node, "genderSlots"),
        requiredInt(node, "numberSlots"),
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
        throw new IllegalArgumentException("Unsupported Germanic/Nordic slot attribute: " + key);
      }
      values.put(
          key, featureMap(entry.getValue(), "slotAttributes." + key, slotAllowedValues(key)));
    }
    return unmodifiableLinkedMap(values);
  }

  private Set<String> slotAllowedValues(String key) {
    return switch (key) {
      case "adjective-type" -> Set.of("attributive", "predicative");
      case "case" -> SLOT_CASE_VALUES;
      case "comparison-degree" -> SAMPLE_DEGREE_VALUES;
      case "count" -> Set.of("countable", "uncountable");
      case "definiteness" -> SAMPLE_DEFINITENESS_VALUES;
      case "gender" -> SAMPLE_GENDER_VALUES;
      case "mood" -> Set.of("imperative", "indicative", "subjunctive");
      case "number" -> SAMPLE_NUMBER_VALUES;
      case "person" -> PRONOUN_PERSON_VALUES;
      case "register" -> Set.of("formal", "informal");
      case "sizeness" -> Set.of("diminutive");
      case "tense" -> Set.of("past", "present");
      case "verb-type" -> Set.of("infinitive", "participle");
      case "voice" -> Set.of("active");
      case "word-order" -> Set.of("subject-verb", "verb-subject");
      default ->
          throw new IllegalArgumentException("Unsupported Germanic/Nordic slot attribute: " + key);
    };
  }

  private PackPolicy loadPackPolicy(JsonNode node) {
    return new PackPolicy(
        requiredText(node, "articleMode"),
        requiredText(node, "caseMode"),
        textArray(requiredArray(node, "metadataBits"), "metadataBits", null, "Germanic/Nordic"),
        requiredBoolean(node, "openWorldGeneration"),
        requiredText(node, "pronounScope"),
        requiredText(node, "reason"),
        requiredText(node, "recommendation"),
        textArray(requiredArray(node, "runtimeOptions"), "runtimeOptions", null, "Germanic/Nordic"),
        textArray(requiredArray(node, "termScope"), "termScope", null, "Germanic/Nordic"));
  }

  private Pronouns loadPronouns(JsonNode node) {
    return new Pronouns(
        featureMap(requiredObject(node, "cases"), "pronoun.cases", SAMPLE_CASE_VALUES),
        featureMap(
            requiredObject(node, "definiteness"),
            "pronoun.definiteness",
            SAMPLE_DEFINITENESS_VALUES),
        featureMap(requiredObject(node, "dependencies"), "pronoun.dependencies", null),
        featureMap(requiredObject(node, "features"), "pronoun.features", null),
        featureMap(requiredObject(node, "genders"), "pronoun.genders", SAMPLE_GENDER_VALUES),
        featureMap(requiredObject(node, "numbers"), "pronoun.numbers", SAMPLE_NUMBER_VALUES),
        featureMap(requiredObject(node, "persons"), "pronoun.persons", PRONOUN_PERSON_VALUES),
        requiredInt(node, "rows"),
        requiredInt(node, "uniqueValues"));
  }

  private Samples loadSamples(JsonNode node) {
    return new Samples(
        loadAmbiguousSurfaces(requiredArray(node, "ambiguousTermSurfaces")),
        compactEntries(requiredArray(node, "caseTaggedAgreementEntries")),
        compactEntries(requiredArray(node, "definiteAgreementEntries")),
        compactEntries(requiredArray(node, "diminutiveEntries")),
        compactEntries(requiredArray(node, "genitiveAgreementEntries")),
        textArray(
            requiredArray(node, "missingAgreementPatterns"),
            "missingAgreementPatterns",
            null,
            "Germanic/Nordic"));
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
                  "Germanic/Nordic"),
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
                  "Germanic/Nordic"),
              textArray(
                  requiredArray(sampleNode, "case"), "case", SAMPLE_CASE_VALUES, "Germanic/Nordic"),
              textArray(
                  requiredArray(sampleNode, "gender"),
                  "gender",
                  SAMPLE_GENDER_VALUES,
                  "Germanic/Nordic"),
              textArray(
                  requiredArray(sampleNode, "number"),
                  "number",
                  SAMPLE_NUMBER_VALUES,
                  "Germanic/Nordic"),
              textArray(
                  requiredArray(sampleNode, "definiteness"),
                  "definiteness",
                  SAMPLE_DEFINITENESS_VALUES,
                  "Germanic/Nordic"),
              textArray(
                  requiredArray(sampleNode, "degree"),
                  "degree",
                  SAMPLE_DEGREE_VALUES,
                  "Germanic/Nordic"),
              textArray(
                  requiredArray(sampleNode, "inflections"),
                  "inflections",
                  null,
                  "Germanic/Nordic")));
    }
    return List.copyOf(samples);
  }

  private void validateSummary(GermanicNordicPackAudit audit) {
    Summary summary = audit.summary();
    if (!EXPECTED_LOCALES.equals(summary.locales())) {
      throw new IllegalArgumentException("Unexpected Germanic/Nordic locale order");
    }
    if (summary.localeCount() != summary.locales().size()) {
      throw new IllegalArgumentException("Germanic/Nordic locale count does not match locales");
    }
    if (!EXPECTED_CASE_RUNTIME_CANDIDATE_LOCALES.equals(summary.caseRuntimeCandidateLocales())) {
      throw new IllegalArgumentException(
          "Unexpected Germanic/Nordic case-runtime candidate locales");
    }
    if (!EXPECTED_METADATA_FIRST_LOCALES.equals(summary.metadataFirstLocales())) {
      throw new IllegalArgumentException("Unexpected Germanic/Nordic metadata-first locales");
    }
    List<String> actualLocales = audit.locales().stream().map(LocaleAudit::locale).toList();
    if (!summary.locales().equals(actualLocales)) {
      throw new IllegalArgumentException("Germanic/Nordic locale reports must match summary order");
    }
  }

  private void validateLocaleAudit(LocaleAudit audit) {
    validateProvenance(audit.locale(), audit.provenance());
    validateCounts(
        audit.locale(), audit.counts(), audit.features(), audit.patterns(), audit.samples());
    validatePatternStats(audit.locale(), audit.patterns());
    validatePronouns(audit.locale(), audit.pronouns());
    validatePackPolicy(
        audit.locale(), audit.counts(), audit.features(), audit.patterns(), audit.packPolicy());
  }

  private void validateProvenance(String locale, Provenance provenance) {
    if (!locale.equals(provenance.locale())) {
      throw new IllegalArgumentException("Germanic/Nordic provenance locale mismatch: " + locale);
    }
    if (!"Unicode-3.0".equals(provenance.license())) {
      throw new IllegalArgumentException("Unexpected Germanic/Nordic audit license");
    }
    if (!EXPECTED_GENERATOR.equals(provenance.generator())) {
      throw new IllegalArgumentException("Unexpected Germanic/Nordic audit generator");
    }
    if (provenance.sources().size() != 3) {
      throw new IllegalArgumentException("Germanic/Nordic audit must include three sources");
    }
    List<String> sourcePaths = provenance.sources().stream().map(Source::path).toList();
    if (!sourcePaths.equals(provenance.sourceLabels())) {
      throw new IllegalArgumentException(
          "Germanic/Nordic provenance source labels must match sources");
    }
    String dictionary = "dictionary_" + locale + ".lst";
    String inflectional = "inflectional_" + locale + ".xml";
    String pronoun = "pronoun_" + locale + ".csv";
    if (!sourcePaths.get(0).endsWith(dictionary)
        || !sourcePaths.get(1).endsWith(inflectional)
        || !sourcePaths.get(2).endsWith(pronoun)) {
      throw new IllegalArgumentException("Germanic/Nordic provenance source locale mismatch");
    }
  }

  private void validateCounts(
      String locale, Counts counts, Features features, Patterns patterns, Samples samples) {
    if (counts.uniqueSurfaces() > counts.dictionaryEntries()
        || counts.termSurfaces() > counts.termEntries()
        || counts.agreementSurfaces() > counts.agreementEntries()) {
      throw new IllegalArgumentException("Germanic/Nordic surface counts exceed entry counts");
    }
    if (counts.termEntries() > counts.dictionaryEntries()
        || counts.agreementEntries() > counts.dictionaryEntries()) {
      throw new IllegalArgumentException("Germanic/Nordic entry counts exceed dictionary entries");
    }
    if (counts.caseTaggedAgreementEntries() > counts.agreementEntries()
        || counts.definiteAgreementEntries() > counts.agreementEntries()
        || counts.diminutiveEntries() > counts.agreementEntries()
        || counts.genitiveAgreementEntries() > counts.agreementEntries()) {
      throw new IllegalArgumentException(
          "Germanic/Nordic agreement subset count exceeds agreement entries");
    }
    if (counts.usedAgreementPatterns() > counts.inflectionPatterns()
        || counts.usedTermPatterns() > counts.usedAgreementPatterns()
        || counts.missingAgreementPatterns() > counts.inflectionPatterns()) {
      throw new IllegalArgumentException(
          "Germanic/Nordic pattern counts exceed available patterns");
    }
    if (features.agreementInflections().size() < counts.usedAgreementPatterns()) {
      throw new IllegalArgumentException(
          "Germanic/Nordic agreement inflection feature map is incomplete");
    }
    if (patterns.usedAgreement().slotsPerPattern().isEmpty()
        || patterns.usedTerms().slotsPerPattern().isEmpty()) {
      throw new IllegalArgumentException("Germanic/Nordic used-pattern stats must not be empty");
    }
    if (samples.ambiguousTermSurfaces().size() > counts.ambiguousTermSurfaces()
        || samples.caseTaggedAgreementEntries().size() > counts.caseTaggedAgreementEntries()
        || samples.definiteAgreementEntries().size() > counts.definiteAgreementEntries()
        || samples.diminutiveEntries().size() > counts.diminutiveEntries()
        || samples.genitiveAgreementEntries().size() > counts.genitiveAgreementEntries()
        || samples.missingAgreementPatterns().size() > counts.missingAgreementPatterns()) {
      throw new IllegalArgumentException("Germanic/Nordic sample count exceeds reported count");
    }
    if (counts.missingAgreementPatterns() == 0 && !samples.missingAgreementPatterns().isEmpty()) {
      throw new IllegalArgumentException(
          "Germanic/Nordic missing-pattern samples present when count is zero");
    }
    if (!EXPECTED_LOCALES.contains(locale)) {
      throw new IllegalArgumentException("Unsupported Germanic/Nordic locale: " + locale);
    }
  }

  private void validatePatternStats(String locale, Patterns patterns) {
    validatePatternStats(locale, "all", patterns.all());
    validatePatternStats(locale, "usedAgreement", patterns.usedAgreement());
    validatePatternStats(locale, "usedTerms", patterns.usedTerms());
    requireMonotonicSlots(
        locale,
        "case",
        patterns.all().caseSlots(),
        patterns.usedAgreement().caseSlots(),
        patterns.usedTerms().caseSlots());
    requireMonotonicSlots(
        locale,
        "definiteness",
        patterns.all().definitenessSlots(),
        patterns.usedAgreement().definitenessSlots(),
        patterns.usedTerms().definitenessSlots());
    requireMonotonicSlots(
        locale,
        "gender",
        patterns.all().genderSlots(),
        patterns.usedAgreement().genderSlots(),
        patterns.usedTerms().genderSlots());
    requireMonotonicSlots(
        locale,
        "number",
        patterns.all().numberSlots(),
        patterns.usedAgreement().numberSlots(),
        patterns.usedTerms().numberSlots());
  }

  private void validatePatternStats(String locale, String name, PatternStats stats) {
    if (stats.duplicateSlotRows() < stats.patternsWithDuplicateSlots()) {
      throw new IllegalArgumentException(
          "Germanic/Nordic " + locale + " " + name + " duplicate rows are incoherent");
    }
    if (stats.caseSlots() != sumValues(stats.slotAttributes().getOrDefault("case", Map.of()))) {
      throw new IllegalArgumentException(
          "Germanic/Nordic " + locale + " " + name + " case slot count is incoherent");
    }
    if (stats.definitenessSlots()
        != sumValues(stats.slotAttributes().getOrDefault("definiteness", Map.of()))) {
      throw new IllegalArgumentException(
          "Germanic/Nordic " + locale + " " + name + " definiteness slot count is incoherent");
    }
    if (stats.genderSlots() != sumValues(stats.slotAttributes().getOrDefault("gender", Map.of()))) {
      throw new IllegalArgumentException(
          "Germanic/Nordic " + locale + " " + name + " gender slot count is incoherent");
    }
    if (stats.numberSlots() != sumValues(stats.slotAttributes().getOrDefault("number", Map.of()))) {
      throw new IllegalArgumentException(
          "Germanic/Nordic " + locale + " " + name + " number slot count is incoherent");
    }
    if (weightedNumericKeySum(stats.slotsPerPattern()) <= 0) {
      throw new IllegalArgumentException(
          "Germanic/Nordic " + locale + " " + name + " slots-per-pattern is empty");
    }
  }

  private void requireMonotonicSlots(
      String locale, String field, int all, int usedAgreement, int usedTerms) {
    if (usedAgreement > all || usedTerms > usedAgreement) {
      throw new IllegalArgumentException(
          "Germanic/Nordic " + locale + " " + field + " slot counts are incoherent");
    }
  }

  private void validatePronouns(String locale, Pronouns pronouns) {
    if (pronouns.uniqueValues() > pronouns.rows()) {
      throw new IllegalArgumentException(
          "Germanic/Nordic " + locale + " unique pronouns exceed pronoun rows");
    }
    if (sumValues(pronouns.persons()) != pronouns.rows()) {
      throw new IllegalArgumentException(
          "Germanic/Nordic " + locale + " pronoun person counts must sum to rows");
    }
    if (sumValues(pronouns.cases()) > pronouns.rows()
        || sumValues(pronouns.definiteness()) > pronouns.rows()
        || sumValues(pronouns.dependencies()) > pronouns.rows()
        || sumValues(pronouns.genders()) > pronouns.rows()
        || sumValues(pronouns.numbers()) > pronouns.rows()) {
      throw new IllegalArgumentException(
          "Germanic/Nordic " + locale + " optional pronoun feature counts exceed rows");
    }
  }

  private void validatePackPolicy(
      String locale, Counts counts, Features features, Patterns patterns, PackPolicy policy) {
    if (!EXPECTED_TERM_SCOPE.equals(policy.termScope())) {
      throw new IllegalArgumentException("Unexpected Germanic/Nordic term scope: " + locale);
    }
    if (!EXPECTED_PRONOUN_SCOPE.equals(policy.pronounScope())) {
      throw new IllegalArgumentException("Unexpected Germanic/Nordic pronoun policy: " + locale);
    }
    if (policy.openWorldGeneration()) {
      throw new IllegalArgumentException(
          "Germanic/Nordic V0 must not enable open-world generation");
    }
    switch (locale) {
      case "da", "sv" -> validateExplicitGenitivePolicy(locale, counts, features, patterns, policy);
      case "nb" -> validateBokmalMetadataPolicy(counts, features, policy);
      case "nl" -> validateDutchMetadataPolicy(counts, patterns, policy);
      default ->
          throw new IllegalArgumentException("Unsupported Germanic/Nordic locale: " + locale);
    }
  }

  private void validateExplicitGenitivePolicy(
      String locale, Counts counts, Features features, Patterns patterns, PackPolicy policy) {
    if (!EXPECTED_EXPLICIT_RECOMMENDATION.equals(policy.recommendation())
        || !EXPECTED_EXPLICIT_RUNTIME_OPTIONS.equals(policy.runtimeOptions())
        || !EXPECTED_BASIC_METADATA_BITS.equals(policy.metadataBits())
        || !EXPECTED_EXPLICIT_CASE_MODE.equals(policy.caseMode())
        || !EXPECTED_EXPLICIT_ARTICLE_MODE.equals(policy.articleMode())) {
      throw new IllegalArgumentException(
          "Unexpected Germanic/Nordic explicit genitive policy: " + locale);
    }
    if ((long) counts.caseTaggedAgreementEntries() * 100 < (long) counts.agreementEntries() * 85
        || counts.genitiveAgreementEntries() <= 0
        || counts.definiteAgreementEntries() <= 0
        || patterns.usedTerms().caseSlots() <= 0
        || patterns.usedTerms().definitenessSlots() <= 0) {
      throw new IllegalArgumentException(
          "Germanic/Nordic explicit genitive policy lacks evidence: " + locale);
    }
    if (features.grammaticalCase().getOrDefault("genitive", 0) <= 0
        || features.definiteness().getOrDefault("definite", 0) <= 0
        || features.definiteness().getOrDefault("indefinite", 0) <= 0) {
      throw new IllegalArgumentException(
          "Germanic/Nordic explicit genitive policy lacks feature evidence: " + locale);
    }
  }

  private void validateBokmalMetadataPolicy(Counts counts, Features features, PackPolicy policy) {
    if (!EXPECTED_BOKMAL_RECOMMENDATION.equals(policy.recommendation())
        || !EXPECTED_METADATA_RUNTIME_OPTIONS.equals(policy.runtimeOptions())
        || !EXPECTED_BASIC_METADATA_BITS.equals(policy.metadataBits())
        || !"unsupported-for-nouns-v0".equals(policy.caseMode())
        || !"defer-article-and-suffix-composition".equals(policy.articleMode())) {
      throw new IllegalArgumentException("Unexpected Germanic/Nordic Bokmal metadata policy");
    }
    if (counts.caseTaggedAgreementEntries() > 10
        || counts.genitiveAgreementEntries() > 5
        || counts.definiteAgreementEntries() <= 0
        || features.grammaticalCase().getOrDefault("<missing>", 0)
            < counts.agreementEntries() * 99 / 100) {
      throw new IllegalArgumentException("Germanic/Nordic Bokmal policy requires sparse case data");
    }
  }

  private void validateDutchMetadataPolicy(Counts counts, Patterns patterns, PackPolicy policy) {
    if (!EXPECTED_DUTCH_RECOMMENDATION.equals(policy.recommendation())
        || !EXPECTED_NUMBER_ONLY_RUNTIME_OPTIONS.equals(policy.runtimeOptions())
        || !EXPECTED_DUTCH_METADATA_BITS.equals(policy.metadataBits())
        || !"inventory-only-v0".equals(policy.caseMode())
        || !"defer-composition".equals(policy.articleMode())) {
      throw new IllegalArgumentException("Unexpected Germanic/Nordic Dutch metadata policy");
    }
    if (counts.diminutiveEntries() <= 0
        || counts.definiteAgreementEntries() != 0
        || patterns.usedTerms().definitenessSlots() != 0
        || counts.caseTaggedAgreementEntries() >= 1_000) {
      throw new IllegalArgumentException(
          "Germanic/Nordic Dutch policy requires metadata-first data");
    }
  }

  private Map<String, Integer> featureMap(JsonNode node, String field, Set<String> allowedKeys) {
    Map<String, Integer> values = new LinkedHashMap<>();
    for (var fields = node.fields(); fields.hasNext(); ) {
      Map.Entry<String, JsonNode> entry = fields.next();
      String key = entry.getKey();
      if (allowedKeys != null && !allowedKeys.contains(key)) {
        throw new IllegalArgumentException("Unsupported Germanic/Nordic " + field + " key: " + key);
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
        throw new IllegalArgumentException("Expected numeric Germanic/Nordic " + field + " key", e);
      }
      if (key < minKey || key > maxKey) {
        throw new IllegalArgumentException(
            "Germanic/Nordic " + field + " key out of range: " + key);
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

  public record GermanicNordicPackAudit(
      String schema,
      String description,
      String cacheDir,
      String unicodeRoot,
      Summary summary,
      List<LocaleAudit> locales) {

    public GermanicNordicPackAudit {
      if (!EXPECTED_SCHEMA.equals(schema)) {
        throw new IllegalArgumentException(
            "Expected schema " + EXPECTED_SCHEMA + ", got " + schema);
      }
      description = Objects.requireNonNull(description, "description");
      cacheDir = Objects.requireNonNull(cacheDir, "cacheDir");
      unicodeRoot = Objects.requireNonNull(unicodeRoot, "unicodeRoot");
      summary = Objects.requireNonNull(summary, "summary");
      locales = List.copyOf(Objects.requireNonNull(locales, "locales"));
    }

    public LocaleAudit locale(String locale) {
      return locales.stream()
          .filter(audit -> audit.locale().equals(locale))
          .findFirst()
          .orElseThrow(
              () -> new IllegalArgumentException("Unknown Germanic/Nordic locale: " + locale));
    }
  }

  public record Summary(
      List<String> caseRuntimeCandidateLocales,
      int localeCount,
      List<String> locales,
      List<String> metadataFirstLocales) {

    public Summary {
      caseRuntimeCandidateLocales =
          List.copyOf(
              Objects.requireNonNull(caseRuntimeCandidateLocales, "caseRuntimeCandidateLocales"));
      requirePositive(localeCount, "localeCount");
      locales = List.copyOf(Objects.requireNonNull(locales, "locales"));
      metadataFirstLocales =
          List.copyOf(Objects.requireNonNull(metadataFirstLocales, "metadataFirstLocales"));
    }
  }

  public record LocaleAudit(
      String locale,
      String description,
      Provenance provenance,
      Counts counts,
      Features features,
      Patterns patterns,
      PackPolicy packPolicy,
      Pronouns pronouns,
      Samples samples) {

    public LocaleAudit {
      locale = Objects.requireNonNull(locale, "locale");
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
      String license,
      String generator,
      List<Source> sources,
      List<String> sourceLabels,
      String locale) {

    public Provenance {
      license = Objects.requireNonNull(license, "license");
      generator = Objects.requireNonNull(generator, "generator");
      sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
      sourceLabels = List.copyOf(Objects.requireNonNull(sourceLabels, "sourceLabels"));
      locale = Objects.requireNonNull(locale, "locale");
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
      int definiteAgreementEntries,
      int dictionaryEntries,
      int diminutiveEntries,
      int genitiveAgreementEntries,
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
      requireNonNegative(definiteAgreementEntries, "definiteAgreementEntries");
      requireNonNegative(dictionaryEntries, "dictionaryEntries");
      requireNonNegative(diminutiveEntries, "diminutiveEntries");
      requireNonNegative(genitiveAgreementEntries, "genitiveAgreementEntries");
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
      Map<String, Integer> degree,
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
      degree = unmodifiableLinkedMap(degree);
      gender = unmodifiableLinkedMap(gender);
      number = unmodifiableLinkedMap(number);
      partOfSpeech = unmodifiableLinkedMap(partOfSpeech);
      termPartOfSpeech = unmodifiableLinkedMap(termPartOfSpeech);
      unknownGrammemes = unmodifiableLinkedMap(unknownGrammemes);
    }
  }

  public record Patterns(PatternStats all, PatternStats usedAgreement, PatternStats usedTerms) {

    public Patterns {
      all = Objects.requireNonNull(all, "all");
      usedAgreement = Objects.requireNonNull(usedAgreement, "usedAgreement");
      usedTerms = Objects.requireNonNull(usedTerms, "usedTerms");
    }
  }

  public record PatternStats(
      int caseSlots,
      int definitenessSlots,
      int duplicateSlotRows,
      int genderSlots,
      int numberSlots,
      int patternsWithDuplicateSlots,
      Map<String, Map<String, Integer>> slotAttributes,
      Map<String, Integer> slotsPerPattern) {

    public PatternStats {
      requireNonNegative(caseSlots, "caseSlots");
      requireNonNegative(definitenessSlots, "definitenessSlots");
      requireNonNegative(duplicateSlotRows, "duplicateSlotRows");
      requireNonNegative(genderSlots, "genderSlots");
      requireNonNegative(numberSlots, "numberSlots");
      requireNonNegative(patternsWithDuplicateSlots, "patternsWithDuplicateSlots");
      slotAttributes = unmodifiableLinkedMap(slotAttributes);
      slotsPerPattern = unmodifiableLinkedMap(slotsPerPattern);
    }
  }

  public record PackPolicy(
      String articleMode,
      String caseMode,
      List<String> metadataBits,
      boolean openWorldGeneration,
      String pronounScope,
      String reason,
      String recommendation,
      List<String> runtimeOptions,
      List<String> termScope) {

    public PackPolicy {
      articleMode = Objects.requireNonNull(articleMode, "articleMode");
      caseMode = Objects.requireNonNull(caseMode, "caseMode");
      metadataBits = List.copyOf(Objects.requireNonNull(metadataBits, "metadataBits"));
      pronounScope = Objects.requireNonNull(pronounScope, "pronounScope");
      reason = Objects.requireNonNull(reason, "reason");
      recommendation = Objects.requireNonNull(recommendation, "recommendation");
      runtimeOptions = List.copyOf(Objects.requireNonNull(runtimeOptions, "runtimeOptions"));
      termScope = List.copyOf(Objects.requireNonNull(termScope, "termScope"));
    }
  }

  public record Pronouns(
      Map<String, Integer> cases,
      Map<String, Integer> definiteness,
      Map<String, Integer> dependencies,
      Map<String, Integer> features,
      Map<String, Integer> genders,
      Map<String, Integer> numbers,
      Map<String, Integer> persons,
      int rows,
      int uniqueValues) {

    public Pronouns {
      cases = unmodifiableLinkedMap(cases);
      definiteness = unmodifiableLinkedMap(definiteness);
      dependencies = unmodifiableLinkedMap(dependencies);
      features = unmodifiableLinkedMap(features);
      genders = unmodifiableLinkedMap(genders);
      numbers = unmodifiableLinkedMap(numbers);
      persons = unmodifiableLinkedMap(persons);
      requireNonNegative(rows, "rows");
      requireNonNegative(uniqueValues, "uniqueValues");
    }
  }

  public record Samples(
      List<AmbiguousSurface> ambiguousTermSurfaces,
      List<CompactEntry> caseTaggedAgreementEntries,
      List<CompactEntry> definiteAgreementEntries,
      List<CompactEntry> diminutiveEntries,
      List<CompactEntry> genitiveAgreementEntries,
      List<String> missingAgreementPatterns) {

    public Samples {
      ambiguousTermSurfaces =
          List.copyOf(Objects.requireNonNull(ambiguousTermSurfaces, "ambiguousTermSurfaces"));
      caseTaggedAgreementEntries =
          List.copyOf(
              Objects.requireNonNull(caseTaggedAgreementEntries, "caseTaggedAgreementEntries"));
      definiteAgreementEntries =
          List.copyOf(Objects.requireNonNull(definiteAgreementEntries, "definiteAgreementEntries"));
      diminutiveEntries =
          List.copyOf(Objects.requireNonNull(diminutiveEntries, "diminutiveEntries"));
      genitiveAgreementEntries =
          List.copyOf(Objects.requireNonNull(genitiveAgreementEntries, "genitiveAgreementEntries"));
      missingAgreementPatterns =
          List.copyOf(Objects.requireNonNull(missingAgreementPatterns, "missingAgreementPatterns"));
    }
  }

  public record AmbiguousSurface(
      String surface, int entries, List<String> reasons, List<CompactEntry> sampleEntries) {

    public AmbiguousSurface {
      surface = Objects.requireNonNull(surface, "surface");
      requirePositive(entries, "entries");
      reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons"));
      sampleEntries = List.copyOf(Objects.requireNonNull(sampleEntries, "sampleEntries"));
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
      List<String> degree,
      List<String> inflections) {

    public CompactEntry {
      surface = Objects.requireNonNull(surface, "surface");
      requirePositive(line, "line");
      partOfSpeech = List.copyOf(Objects.requireNonNull(partOfSpeech, "partOfSpeech"));
      grammaticalCase = List.copyOf(Objects.requireNonNull(grammaticalCase, "grammaticalCase"));
      gender = List.copyOf(Objects.requireNonNull(gender, "gender"));
      number = List.copyOf(Objects.requireNonNull(number, "number"));
      definiteness = List.copyOf(Objects.requireNonNull(definiteness, "definiteness"));
      degree = List.copyOf(Objects.requireNonNull(degree, "degree"));
      inflections = List.copyOf(Objects.requireNonNull(inflections, "inflections"));
    }
  }
}
