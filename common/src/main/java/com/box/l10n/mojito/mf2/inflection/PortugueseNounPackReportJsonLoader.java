package com.box.l10n.mojito.mf2.inflection;

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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Loads the generated Portuguese noun/proper-noun report. The report captures the compact
 * gender/number metadata shape plus the fixed agreement form table used for articles, contractions,
 * possessives, and demonstratives.
 */
@GeneratorSupport
class PortugueseNounPackReportJsonLoader {

  static final String EXPECTED_SCHEMA = "mojito-mf2-inflection/pt-noun-pack-report/v0";
  static final String EXPECTED_LOCALE = "pt";

  private static final Set<String> GENDER_VALUES =
      Set.of("<missing>", "feminine", "masculine", "neuter");
  private static final Set<String> NUMBER_VALUES = Set.of("<missing>", "plural", "singular");
  private static final Set<String> AGREEMENT_GENDER_VALUES = Set.of("feminine", "masculine");
  private static final Set<String> AGREEMENT_NUMBER_VALUES = Set.of("plural", "singular");
  private static final Set<String> PART_OF_SPEECH_VALUES =
      Set.of("adjective", "adverb", "noun", "numeral", "proper-noun", "verb");
  private static final Set<String> PATTERN_PART_OF_SPEECH_VALUES =
      Set.of("abbreviation", "adjective", "article", "noun", "verb");
  private static final Set<String> AGREEMENT_CATEGORY_VALUES =
      Set.of(
          "deDefiniteArticle",
          "deDemonstrativeDistal",
          "deDemonstrativeMedial",
          "deDemonstrativeProximal",
          "definiteArticle",
          "demonstrativeDistal",
          "demonstrativeMedial",
          "demonstrativeProximal",
          "emDefiniteArticle",
          "emDemonstrativeDistal",
          "emDemonstrativeMedial",
          "emDemonstrativeProximal",
          "emIndefiniteArticle",
          "indefiniteArticle",
          "porDefiniteArticle",
          "possessiveArticle");
  private static final Set<String> AMBIGUITY_REASON_VALUES =
      Set.of(
          "missing-gender",
          "missing-number",
          "multiple-analyses",
          "multiple-genders",
          "multiple-inflections",
          "multiple-numbers",
          "multiple-parts-of-speech");

  private final ObjectMapper objectMapper;

  PortugueseNounPackReportJsonLoader() {
    this(new ObjectMapper());
  }

  PortugueseNounPackReportJsonLoader(ObjectMapper objectMapper) {
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
  }

  public PortugueseNounPackReport load(String json) {
    Objects.requireNonNull(json, "json");
    return load(objectMapper.readTreeUnchecked(json));
  }

  public PortugueseNounPackReport load(JsonNode root) {
    PortugueseNounPackReport report =
        new PortugueseNounPackReport(
            requiredText(root, "schema"),
            requiredText(root, "locale"),
            loadSources(requiredArray(root, "sources")),
            loadProvenance(requiredObject(root, "provenance")),
            loadCounts(requiredObject(root, "counts")),
            loadFeatures(requiredObject(root, "features")),
            loadSizeEstimates(requiredObject(root, "sizeEstimates")),
            loadAgreementStrategy(requiredObject(root, "agreementStrategy")),
            loadReviewPolicy(requiredObject(root, "reviewPolicy")),
            loadSamples(requiredObject(root, "samples")));

    validateProvenance(report);
    validateCounts(report.counts(), report.samples());
    validateSizeEstimates(report.counts(), report.sizeEstimates());
    validateAgreementStrategy(report.counts(), report.agreementStrategy());
    validateReviewPolicy(report.counts(), report.reviewPolicy());
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
        textArray(requiredArray(node, "sourceLabels"), "sourceLabels", null, "Portuguese"),
        List.copyOf(sources));
  }

  private Counts loadCounts(JsonNode node) {
    return new Counts(
        requiredInt(node, "dictionaryEntries"),
        requiredInt(node, "supportedEntries"),
        requiredInt(node, "uniqueSupportedSurfaces"),
        requiredInt(node, "skippedLines"),
        requiredInt(node, "ambiguousSupportedSurfaces"),
        requiredInt(node, "exactGenderNumberSurfaces"),
        requiredInt(node, "genderNumberCandidateSurfaces"),
        requiredInt(node, "dictionaryInflectionPatterns"),
        requiredInt(node, "missingInflectionPatterns"),
        requiredInt(node, "inflectionalPatterns"),
        requiredInt(node, "nounInflectionalPatterns"),
        requiredInt(node, "usedInflectionalPatterns"));
  }

  private Features loadFeatures(JsonNode node) {
    return new Features(
        featureMap(requiredObject(node, "gender"), "gender", GENDER_VALUES),
        featureMap(requiredObject(node, "number"), "number", NUMBER_VALUES),
        featureMap(requiredObject(node, "partOfSpeech"), "partOfSpeech", PART_OF_SPEECH_VALUES),
        featureMap(
            requiredObject(node, "ambiguityReasons"), "ambiguityReasons", AMBIGUITY_REASON_VALUES),
        featureMap(
            requiredObject(node, "patternPartOfSpeech"),
            "patternPartOfSpeech",
            PATTERN_PART_OF_SPEECH_VALUES),
        featureMap(requiredObject(node, "patternSlotGenders"), "patternSlotGenders", GENDER_VALUES),
        featureMap(
            requiredObject(node, "patternSlotNumbers"), "patternSlotNumbers", NUMBER_VALUES));
  }

  private SizeEstimates loadSizeEstimates(JsonNode node) {
    JsonNode genderNumberMetadataPack = requiredObject(node, "genderNumberMetadataPack");
    return new SizeEstimates(
        new MetadataPackEstimate(
            requiredInt(genderNumberMetadataPack, "stringPoolBytes"),
            requiredInt(genderNumberMetadataPack, "rowBytes"),
            requiredInt(genderNumberMetadataPack, "binaryLowerBoundBytes")));
  }

  private AgreementStrategy loadAgreementStrategy(JsonNode node) {
    return new AgreementStrategy(
        loadAgreementForms(requiredArray(node, "agreementForms")),
        loadAgreementCounts(requiredObject(node, "counts")),
        loadAgreementSizeEstimates(requiredObject(node, "sizeEstimates")),
        loadAgreementSamples(requiredObject(node, "samples")));
  }

  private List<AgreementForm> loadAgreementForms(JsonNode node) {
    List<AgreementForm> agreementForms = new ArrayList<>();
    for (JsonNode agreementFormNode : node) {
      agreementForms.add(
          new AgreementForm(
              requiredText(agreementFormNode, "category"),
              requiredText(agreementFormNode, "gender"),
              requiredText(agreementFormNode, "number"),
              requiredText(agreementFormNode, "form")));
    }
    return List.copyOf(agreementForms);
  }

  private AgreementCounts loadAgreementCounts(JsonNode node) {
    return new AgreementCounts(
        requiredInt(node, "agreementCandidateSurfaces"),
        requiredInt(node, "agreementFormCategories"));
  }

  private AgreementSizeEstimates loadAgreementSizeEstimates(JsonNode node) {
    JsonNode eagerPhrasePack = requiredObject(node, "eagerPhrasePack");
    return new AgreementSizeEstimates(
        new AgreementPhrasePackEstimate(
            requiredInt(eagerPhrasePack, "phraseRows"),
            requiredInt(eagerPhrasePack, "stringPoolBytes"),
            requiredInt(eagerPhrasePack, "phraseRowBytes"),
            requiredInt(eagerPhrasePack, "binaryLowerBoundBytes")));
  }

  private AgreementSamples loadAgreementSamples(JsonNode node) {
    return new AgreementSamples(
        agreementCandidateSamples(requiredArray(node, "agreementCandidates")),
        agreementCandidateSamples(requiredArray(node, "targetedAgreementCandidates")));
  }

  private ReviewPolicy loadReviewPolicy(JsonNode node) {
    return new ReviewPolicy(
        requiredText(node, "compactRuntime"),
        requiredInt(node, "automaticExportSurfaces"),
        requiredInt(node, "reviewRequiredSurfaces"),
        requiredInt(node, "blockedSurfaces"),
        featureMap(
            requiredObject(node, "reviewRequiredReasons"),
            "reviewRequiredReasons",
            AMBIGUITY_REASON_VALUES),
        featureMap(
            requiredObject(node, "blockedReasons"), "blockedReasons", AMBIGUITY_REASON_VALUES));
  }

  private List<AgreementCandidateSample> agreementCandidateSamples(JsonNode node) {
    List<AgreementCandidateSample> samples = new ArrayList<>();
    for (JsonNode sampleNode : node) {
      samples.add(
          new AgreementCandidateSample(
              requiredText(sampleNode, "surface"),
              requiredText(sampleNode, "gender"),
              requiredText(sampleNode, "number"),
              phraseForms(requiredObject(sampleNode, "phraseForms"))));
    }
    return List.copyOf(samples);
  }

  private Samples loadSamples(JsonNode node) {
    List<SurfaceSample> genderNumberCandidates = new ArrayList<>();
    for (JsonNode sampleNode : requiredArray(node, "genderNumberCandidates")) {
      genderNumberCandidates.add(loadSurfaceSample(sampleNode));
    }

    List<SurfaceSample> blockingAmbiguities = new ArrayList<>();
    for (JsonNode sampleNode : requiredArray(node, "blockingAmbiguities")) {
      blockingAmbiguities.add(loadSurfaceSample(sampleNode));
    }

    List<EntrySample> entries = new ArrayList<>();
    for (JsonNode entryNode : requiredArray(node, "entries")) {
      entries.add(
          new EntrySample(
              requiredText(entryNode, "surface"),
              requiredInt(entryNode, "line"),
              textArray(
                  requiredArray(entryNode, "partOfSpeech"), "partOfSpeech", null, "Portuguese"),
              textArray(requiredArray(entryNode, "gender"), "gender", GENDER_VALUES, "Portuguese"),
              textArray(requiredArray(entryNode, "number"), "number", NUMBER_VALUES, "Portuguese"),
              textArray(
                  requiredArray(entryNode, "inflections"), "inflections", null, "Portuguese")));
    }

    return new Samples(
        List.copyOf(genderNumberCandidates),
        List.copyOf(blockingAmbiguities),
        textArray(
            requiredArray(node, "missingInflectionPatterns"),
            "missingInflectionPatterns",
            null,
            "Portuguese"),
        List.copyOf(entries));
  }

  private SurfaceSample loadSurfaceSample(JsonNode node) {
    return new SurfaceSample(
        requiredText(node, "surface"),
        textArray(requiredArray(node, "genders"), "genders", GENDER_VALUES, "Portuguese"),
        textArray(requiredArray(node, "numbers"), "numbers", NUMBER_VALUES, "Portuguese"),
        textArray(
            requiredArray(node, "partsOfSpeech"),
            "partsOfSpeech",
            PART_OF_SPEECH_VALUES,
            "Portuguese"),
        textArray(requiredArray(node, "inflections"), "inflections", null, "Portuguese"),
        textArray(requiredArray(node, "reasons"), "reasons", AMBIGUITY_REASON_VALUES, "Portuguese"),
        requiredInt(node, "entries"));
  }

  private void validateProvenance(PortugueseNounPackReport report) {
    if (!report.sources().equals(report.provenance().sources())) {
      throw new IllegalArgumentException("Portuguese report provenance sources must match sources");
    }
    if (report.provenance().sourceLabels().size() != report.sources().size()) {
      throw new IllegalArgumentException(
          "Portuguese report provenance source label count must match sources");
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
    if (counts.exactGenderNumberSurfaces() > counts.genderNumberCandidateSurfaces()) {
      throw new IllegalArgumentException("Exact surfaces exceed gender/number candidates");
    }
    if (counts.genderNumberCandidateSurfaces() > counts.uniqueSupportedSurfaces()) {
      throw new IllegalArgumentException(
          "Gender/number candidates exceed unique supported surfaces");
    }
    if (counts.usedInflectionalPatterns() > counts.inflectionalPatterns()) {
      throw new IllegalArgumentException("Used patterns exceed inflectional pattern count");
    }
    if (counts.dictionaryInflectionPatterns() > counts.inflectionalPatterns()) {
      throw new IllegalArgumentException("Dictionary patterns exceed inflectional pattern count");
    }
    if (counts.missingInflectionPatterns() == 0 && !samples.missingInflectionPatterns().isEmpty()) {
      throw new IllegalArgumentException("Missing-pattern samples present when count is zero");
    }
  }

  private void validateSizeEstimates(Counts counts, SizeEstimates sizeEstimates) {
    MetadataPackEstimate estimate = sizeEstimates.genderNumberMetadataPack();
    if (estimate.rowBytes() != counts.genderNumberCandidateSurfaces() * 8) {
      throw new IllegalArgumentException(
          "Portuguese metadata row byte estimate does not match rows");
    }
    if (estimate.binaryLowerBoundBytes() != estimate.stringPoolBytes() + estimate.rowBytes()) {
      throw new IllegalArgumentException(
          "Portuguese metadata binary estimate does not match parts");
    }
  }

  private void validateAgreementStrategy(Counts counts, AgreementStrategy agreementStrategy) {
    validateAgreementForms(agreementStrategy.agreementForms());

    AgreementCounts agreementCounts = agreementStrategy.counts();
    if (agreementCounts.agreementCandidateSurfaces() != counts.genderNumberCandidateSurfaces()) {
      throw new IllegalArgumentException(
          "Portuguese agreement candidate count must match gender/number candidates");
    }
    if (agreementCounts.agreementFormCategories() != AGREEMENT_CATEGORY_VALUES.size()) {
      throw new IllegalArgumentException("Portuguese agreement category count mismatch");
    }

    AgreementPhrasePackEstimate estimate = agreementStrategy.sizeEstimates().eagerPhrasePack();
    int expectedPhraseRows =
        agreementCounts.agreementCandidateSurfaces() * agreementCounts.agreementFormCategories();
    if (estimate.phraseRows() != expectedPhraseRows) {
      throw new IllegalArgumentException(
          "Portuguese agreement phrase-row count does not match categories");
    }
    if (estimate.phraseRowBytes() != estimate.phraseRows() * 12) {
      throw new IllegalArgumentException(
          "Portuguese agreement phrase-row byte estimate is incoherent");
    }
    if (estimate.binaryLowerBoundBytes()
        != estimate.stringPoolBytes() + estimate.phraseRowBytes()) {
      throw new IllegalArgumentException(
          "Portuguese agreement binary estimate does not match parts");
    }
  }

  private void validateReviewPolicy(Counts counts, ReviewPolicy reviewPolicy) {
    if (!"agreement-shell-composition".equals(reviewPolicy.compactRuntime())) {
      throw new IllegalArgumentException("Unsupported Portuguese compact runtime");
    }
    if (reviewPolicy.automaticExportSurfaces() != counts.exactGenderNumberSurfaces()) {
      throw new IllegalArgumentException(
          "Portuguese automatic export count must match exact gender/number surfaces");
    }
    if (reviewPolicy.automaticExportSurfaces() + reviewPolicy.reviewRequiredSurfaces()
        != counts.genderNumberCandidateSurfaces()) {
      throw new IllegalArgumentException(
          "Portuguese review policy must cover every gender/number candidate");
    }
    if (reviewPolicy.blockedSurfaces()
        != counts.uniqueSupportedSurfaces() - counts.genderNumberCandidateSurfaces()) {
      throw new IllegalArgumentException(
          "Portuguese blocked review count must match non-candidate surfaces");
    }
    if (reviewPolicy.reviewRequiredSurfaces() == 0
        && !reviewPolicy.reviewRequiredReasons().isEmpty()) {
      throw new IllegalArgumentException(
          "Portuguese review reasons present when no surfaces require review");
    }
    if (reviewPolicy.blockedSurfaces() == 0 && !reviewPolicy.blockedReasons().isEmpty()) {
      throw new IllegalArgumentException(
          "Portuguese blocked reasons present when no surfaces are blocked");
    }
  }

  private void validateAgreementForms(List<AgreementForm> agreementForms) {
    Set<String> seen = new HashSet<>();
    for (AgreementForm agreementForm : agreementForms) {
      if (!seen.add(agreementForm.key())) {
        throw new IllegalArgumentException(
            "Duplicate Portuguese agreement form: " + agreementForm.key());
      }
    }
    int expectedRows =
        AGREEMENT_CATEGORY_VALUES.size()
            * AGREEMENT_GENDER_VALUES.size()
            * AGREEMENT_NUMBER_VALUES.size();
    if (agreementForms.size() != expectedRows) {
      throw new IllegalArgumentException("Unexpected Portuguese agreement-form table size");
    }
    for (String category : AGREEMENT_CATEGORY_VALUES) {
      for (String gender : AGREEMENT_GENDER_VALUES) {
        for (String number : AGREEMENT_NUMBER_VALUES) {
          String key = AgreementForm.key(category, gender, number);
          if (!seen.contains(key)) {
            throw new IllegalArgumentException("Missing Portuguese agreement form: " + key);
          }
        }
      }
    }
  }

  private Map<String, String> phraseForms(JsonNode node) {
    Map<String, String> values = new LinkedHashMap<>();
    node.fields()
        .forEachRemaining(
            entry -> {
              String key = entry.getKey();
              if (!AGREEMENT_CATEGORY_VALUES.contains(key)) {
                throw new IllegalArgumentException(
                    "Unsupported Portuguese phraseForms key: " + key);
              }
              values.put(key, requiredTextValue(entry.getValue(), "phraseForms." + key));
            });
    if (!values.keySet().containsAll(AGREEMENT_CATEGORY_VALUES)) {
      throw new IllegalArgumentException(
          "Portuguese phraseForms must include every agreement category");
    }
    return unmodifiableLinkedMap(values);
  }

  private Map<String, Integer> featureMap(JsonNode node, String field, Set<String> allowedKeys) {
    Map<String, Integer> values = new LinkedHashMap<>();
    node.fields()
        .forEachRemaining(
            entry -> {
              String key = entry.getKey();
              if (allowedKeys != null && !allowedKeys.contains(key)) {
                throw new IllegalArgumentException(
                    "Unsupported Portuguese " + field + " key: " + key);
              }
              int value = requiredIntValue(entry.getValue(), field + "." + key);
              if (value < 0) {
                throw new IllegalArgumentException(
                    "Portuguese feature counts must be non-negative: " + field);
              }
              values.put(key, value);
            });
    return unmodifiableLinkedMap(values);
  }

  private static String requireSchema(String schema) {
    if (!EXPECTED_SCHEMA.equals(schema)) {
      throw new IllegalArgumentException("Expected Portuguese noun-pack report schema");
    }
    return schema;
  }

  private static String requireLocale(String locale) {
    if (!EXPECTED_LOCALE.equals(locale)) {
      throw new IllegalArgumentException("Expected Portuguese noun-pack report locale");
    }
    return locale;
  }

  public record PortugueseNounPackReport(
      String schema,
      String locale,
      List<Source> sources,
      Provenance provenance,
      Counts counts,
      Features features,
      SizeEstimates sizeEstimates,
      AgreementStrategy agreementStrategy,
      ReviewPolicy reviewPolicy,
      Samples samples) {

    public PortugueseNounPackReport {
      schema = requireSchema(schema);
      locale = requireLocale(locale);
      sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
      provenance = Objects.requireNonNull(provenance, "provenance");
      counts = Objects.requireNonNull(counts, "counts");
      features = Objects.requireNonNull(features, "features");
      sizeEstimates = Objects.requireNonNull(sizeEstimates, "sizeEstimates");
      agreementStrategy = Objects.requireNonNull(agreementStrategy, "agreementStrategy");
      reviewPolicy = Objects.requireNonNull(reviewPolicy, "reviewPolicy");
      samples = Objects.requireNonNull(samples, "samples");
      if (sources.isEmpty()) {
        throw new IllegalArgumentException("Portuguese report must include sources");
      }
    }
  }

  public record Source(String path, long byteSize, String sha256, boolean gitLfsPointer) {

    public Source {
      path = requireTextValue(path, "path");
      sha256 = requireTextValue(sha256, "sha256");
      if (byteSize < 0) {
        throw new IllegalArgumentException("byteSize must be non-negative");
      }
    }
  }

  public record Provenance(
      String license, String generator, List<String> sourceLabels, List<Source> sources) {

    public Provenance {
      license = requireTextValue(license, "license");
      generator = requireTextValue(generator, "generator");
      sourceLabels = List.copyOf(Objects.requireNonNull(sourceLabels, "sourceLabels"));
      sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
    }
  }

  public record Counts(
      int dictionaryEntries,
      int supportedEntries,
      int uniqueSupportedSurfaces,
      int skippedLines,
      int ambiguousSupportedSurfaces,
      int exactGenderNumberSurfaces,
      int genderNumberCandidateSurfaces,
      int dictionaryInflectionPatterns,
      int missingInflectionPatterns,
      int inflectionalPatterns,
      int nounInflectionalPatterns,
      int usedInflectionalPatterns) {}

  public record Features(
      Map<String, Integer> gender,
      Map<String, Integer> number,
      Map<String, Integer> partOfSpeech,
      Map<String, Integer> ambiguityReasons,
      Map<String, Integer> patternPartOfSpeech,
      Map<String, Integer> patternSlotGenders,
      Map<String, Integer> patternSlotNumbers) {

    public Features {
      gender = unmodifiableLinkedMap(Objects.requireNonNull(gender, "gender"));
      number = unmodifiableLinkedMap(Objects.requireNonNull(number, "number"));
      partOfSpeech = unmodifiableLinkedMap(Objects.requireNonNull(partOfSpeech, "partOfSpeech"));
      ambiguityReasons =
          unmodifiableLinkedMap(Objects.requireNonNull(ambiguityReasons, "ambiguityReasons"));
      patternPartOfSpeech =
          unmodifiableLinkedMap(Objects.requireNonNull(patternPartOfSpeech, "patternPartOfSpeech"));
      patternSlotGenders =
          unmodifiableLinkedMap(Objects.requireNonNull(patternSlotGenders, "patternSlotGenders"));
      patternSlotNumbers =
          unmodifiableLinkedMap(Objects.requireNonNull(patternSlotNumbers, "patternSlotNumbers"));
    }
  }

  public record SizeEstimates(MetadataPackEstimate genderNumberMetadataPack) {

    public SizeEstimates {
      genderNumberMetadataPack =
          Objects.requireNonNull(genderNumberMetadataPack, "genderNumberMetadataPack");
    }
  }

  public record MetadataPackEstimate(
      int stringPoolBytes, int rowBytes, int binaryLowerBoundBytes) {}

  public record AgreementStrategy(
      List<AgreementForm> agreementForms,
      AgreementCounts counts,
      AgreementSizeEstimates sizeEstimates,
      AgreementSamples samples) {

    public AgreementStrategy {
      agreementForms = List.copyOf(Objects.requireNonNull(agreementForms, "agreementForms"));
      counts = Objects.requireNonNull(counts, "counts");
      sizeEstimates = Objects.requireNonNull(sizeEstimates, "sizeEstimates");
      samples = Objects.requireNonNull(samples, "samples");
    }
  }

  public record AgreementForm(String category, String gender, String number, String form) {

    public AgreementForm {
      category = requireExpectedValue(category, "category", AGREEMENT_CATEGORY_VALUES);
      gender = requireExpectedValue(gender, "gender", AGREEMENT_GENDER_VALUES);
      number = requireExpectedValue(number, "number", AGREEMENT_NUMBER_VALUES);
      form = requireTextValue(form, "form");
    }

    String key() {
      return key(category, gender, number);
    }

    static String key(String category, String gender, String number) {
      return category + "." + gender + "." + number;
    }
  }

  public record AgreementCounts(int agreementCandidateSurfaces, int agreementFormCategories) {}

  public record AgreementSizeEstimates(AgreementPhrasePackEstimate eagerPhrasePack) {

    public AgreementSizeEstimates {
      eagerPhrasePack = Objects.requireNonNull(eagerPhrasePack, "eagerPhrasePack");
    }
  }

  public record AgreementPhrasePackEstimate(
      int phraseRows, int stringPoolBytes, int phraseRowBytes, int binaryLowerBoundBytes) {}

  public record AgreementSamples(
      List<AgreementCandidateSample> agreementCandidates,
      List<AgreementCandidateSample> targetedAgreementCandidates) {

    public AgreementSamples {
      agreementCandidates =
          List.copyOf(Objects.requireNonNull(agreementCandidates, "agreementCandidates"));
      targetedAgreementCandidates =
          List.copyOf(
              Objects.requireNonNull(targetedAgreementCandidates, "targetedAgreementCandidates"));
    }
  }

  public record AgreementCandidateSample(
      String surface, String gender, String number, Map<String, String> phraseForms) {

    public AgreementCandidateSample {
      surface = requireTextValue(surface, "surface");
      gender = requireExpectedValue(gender, "gender", AGREEMENT_GENDER_VALUES);
      number = requireExpectedValue(number, "number", AGREEMENT_NUMBER_VALUES);
      phraseForms = unmodifiableLinkedMap(Objects.requireNonNull(phraseForms, "phraseForms"));
    }
  }

  public record ReviewPolicy(
      String compactRuntime,
      int automaticExportSurfaces,
      int reviewRequiredSurfaces,
      int blockedSurfaces,
      Map<String, Integer> reviewRequiredReasons,
      Map<String, Integer> blockedReasons) {

    public ReviewPolicy {
      compactRuntime = requireTextValue(compactRuntime, "compactRuntime");
      if (automaticExportSurfaces < 0 || reviewRequiredSurfaces < 0 || blockedSurfaces < 0) {
        throw new IllegalArgumentException("Portuguese review-policy counts must be non-negative");
      }
      reviewRequiredReasons =
          unmodifiableLinkedMap(
              Objects.requireNonNull(reviewRequiredReasons, "reviewRequiredReasons"));
      blockedReasons =
          unmodifiableLinkedMap(Objects.requireNonNull(blockedReasons, "blockedReasons"));
    }
  }

  public record Samples(
      List<SurfaceSample> genderNumberCandidates,
      List<SurfaceSample> blockingAmbiguities,
      List<String> missingInflectionPatterns,
      List<EntrySample> entries) {

    public Samples {
      genderNumberCandidates =
          List.copyOf(Objects.requireNonNull(genderNumberCandidates, "genderNumberCandidates"));
      blockingAmbiguities =
          List.copyOf(Objects.requireNonNull(blockingAmbiguities, "blockingAmbiguities"));
      missingInflectionPatterns =
          List.copyOf(
              Objects.requireNonNull(missingInflectionPatterns, "missingInflectionPatterns"));
      entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
    }
  }

  public record SurfaceSample(
      String surface,
      List<String> genders,
      List<String> numbers,
      List<String> partsOfSpeech,
      List<String> inflections,
      List<String> reasons,
      int entries) {

    public SurfaceSample {
      surface = requireTextValue(surface, "surface");
      genders = List.copyOf(Objects.requireNonNull(genders, "genders"));
      numbers = List.copyOf(Objects.requireNonNull(numbers, "numbers"));
      partsOfSpeech = List.copyOf(Objects.requireNonNull(partsOfSpeech, "partsOfSpeech"));
      inflections = List.copyOf(Objects.requireNonNull(inflections, "inflections"));
      reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons"));
      if (entries <= 0) {
        throw new IllegalArgumentException("Portuguese sample entries must be positive");
      }
    }
  }

  public record EntrySample(
      String surface,
      int line,
      List<String> partOfSpeech,
      List<String> gender,
      List<String> number,
      List<String> inflections) {

    public EntrySample {
      surface = requireTextValue(surface, "surface");
      partOfSpeech = List.copyOf(Objects.requireNonNull(partOfSpeech, "partOfSpeech"));
      gender = List.copyOf(Objects.requireNonNull(gender, "gender"));
      number = List.copyOf(Objects.requireNonNull(number, "number"));
      inflections = List.copyOf(Objects.requireNonNull(inflections, "inflections"));
      if (line <= 0) {
        throw new IllegalArgumentException("Portuguese entry sample line must be positive");
      }
    }
  }

  private static String requireTextValue(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return value;
  }

  private static String requireExpectedValue(
      String value, String field, Set<String> allowedValues) {
    value = requireTextValue(value, field);
    if (!allowedValues.contains(value)) {
      throw new IllegalArgumentException("Unsupported Portuguese " + field + " value: " + value);
    }
    return value;
  }
}
