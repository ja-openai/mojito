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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Loads the generated German article/case report. This report is a generator contract for deciding
 * the German build-validation and closed-world runtime-pack shape; it is not the final runtime
 * artifact.
 */
@GeneratorSupport
class GermanArticleCaseReportJsonLoader {

  static final String EXPECTED_SCHEMA = "mojito-mf2-inflection/de-article-case-pack-report/v0";
  static final String EXPECTED_LOCALE = "de";

  private static final Set<String> ARTICLE_VALUES = Set.of("definite", "indefinite");
  private static final Set<String> CASE_VALUES =
      Set.of("<missing>", "accusative", "dative", "genitive", "nominative");
  private static final Set<String> FORM_CASE_VALUES =
      Set.of("accusative", "dative", "genitive", "nominative");
  private static final Set<String> GENDER_VALUES =
      Set.of("<missing>", "feminine", "masculine", "neuter");
  private static final Set<String> ARTICLE_GENDER_VALUES =
      Set.of("*", "feminine", "masculine", "neuter");
  private static final Set<String> NUMBER_VALUES = Set.of("<missing>", "plural", "singular");
  private static final Set<String> FORM_NUMBER_VALUES = Set.of("plural", "singular");
  private static final Set<String> PART_OF_SPEECH_VALUES =
      Set.of("adjective", "noun", "numeral", "proper-noun", "verb");
  private static final Set<String> CANDIDATE_PART_OF_SPEECH_VALUES = Set.of("noun", "proper-noun");
  private static final Set<String> AMBIGUITY_REASON_VALUES =
      Set.of(
          "multiple-analyses",
          "multiple-cases",
          "multiple-genders",
          "multiple-inflections",
          "multiple-numbers",
          "multiple-parts-of-speech");
  private static final Set<String> CANDIDATE_SKIP_REASON_VALUES =
      Set.of(
          "conflicting-noun-form-key",
          "duplicate-term-id",
          "missing-article-form",
          "missing-noun-case-forms",
          "missing-or-ambiguous-gender",
          "missing-or-ambiguous-inflection",
          "missing-pattern",
          "not-nominative",
          "not-singular",
          "singular-plural-surface-not-invariant",
          "suffix-mismatch",
          "unsupported-part-of-speech",
          "unsupported-pattern-part-of-speech");

  private final ObjectMapper objectMapper;

  GermanArticleCaseReportJsonLoader() {
    this(new ObjectMapper());
  }

  GermanArticleCaseReportJsonLoader(ObjectMapper objectMapper) {
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
  }

  public GermanArticleCaseReport load(String json) {
    Objects.requireNonNull(json, "json");
    return load(objectMapper.readTreeUnchecked(json));
  }

  public GermanArticleCaseReport load(JsonNode root) {
    GermanArticleCaseReport report =
        new GermanArticleCaseReport(
            requiredText(root, "schema"),
            requiredText(root, "locale"),
            loadArticleForms(requiredArray(root, "articleForms")),
            loadSources(requiredArray(root, "sources")),
            loadProvenance(requiredObject(root, "provenance")),
            loadCounts(requiredObject(root, "counts")),
            loadFeatures(requiredObject(root, "features")),
            loadReviewPolicy(requiredObject(root, "reviewPolicy")),
            loadSizeEstimates(requiredObject(root, "sizeEstimates")),
            loadSamples(requiredObject(root, "samples")));

    validateProvenance(report);
    validateArticleForms(report.articleForms());
    validateCounts(report.counts(), report.samples());
    validateReviewPolicy(report.counts(), report.features(), report.reviewPolicy());
    validateSizeEstimates(report.counts(), report.sizeEstimates());
    return report;
  }

  private List<ArticleForm> loadArticleForms(JsonNode node) {
    List<ArticleForm> articleForms = new ArrayList<>();
    for (JsonNode articleNode : node) {
      articleForms.add(
          new ArticleForm(
              requiredText(articleNode, "article"),
              requiredText(articleNode, "gender"),
              requiredText(articleNode, "number"),
              requiredText(articleNode, "case"),
              requiredTextAllowBlank(articleNode, "form")));
    }
    return List.copyOf(articleForms);
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
        textArray(requiredArray(node, "sourceLabels"), "sourceLabels", null, "German"),
        List.copyOf(sources));
  }

  private Counts loadCounts(JsonNode node) {
    return new Counts(
        requiredInt(node, "dictionaryEntries"),
        requiredInt(node, "supportedEntries"),
        requiredInt(node, "uniqueSupportedSurfaces"),
        requiredInt(node, "skippedLines"),
        requiredInt(node, "ambiguousSupportedSurfaces"),
        requiredInt(node, "dictionaryInflectionPatterns"),
        requiredInt(node, "missingInflectionPatterns"),
        requiredInt(node, "inflectionalPatterns"),
        requiredInt(node, "nounInflectionalPatterns"),
        requiredInt(node, "usedInflectionalPatterns"),
        requiredInt(node, "articleCaseCandidateTerms"));
  }

  private Features loadFeatures(JsonNode node) {
    return new Features(
        featureMap(requiredObject(node, "case"), "case", CASE_VALUES),
        featureMap(requiredObject(node, "gender"), "gender", GENDER_VALUES),
        featureMap(requiredObject(node, "number"), "number", NUMBER_VALUES),
        featureMap(requiredObject(node, "partOfSpeech"), "partOfSpeech", PART_OF_SPEECH_VALUES),
        featureMap(requiredObject(node, "patternSlotCases"), "patternSlotCases", CASE_VALUES),
        featureMap(requiredObject(node, "patternSlotGenders"), "patternSlotGenders", GENDER_VALUES),
        featureMap(requiredObject(node, "patternSlotNumbers"), "patternSlotNumbers", NUMBER_VALUES),
        featureMap(
            requiredObject(node, "ambiguityReasons"), "ambiguityReasons", AMBIGUITY_REASON_VALUES),
        featureMap(
            requiredObject(node, "candidateSkipReasons"),
            "candidateSkipReasons",
            CANDIDATE_SKIP_REASON_VALUES));
  }

  private SizeEstimates loadSizeEstimates(JsonNode node) {
    return new SizeEstimates(
        requiredInt(node, "stringPoolBytes"),
        requiredInt(node, "termRowBytes"),
        requiredInt(node, "formRows"),
        requiredInt(node, "formRowBytes"),
        requiredInt(node, "binaryLowerBoundBytes"));
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
            CANDIDATE_SKIP_REASON_VALUES));
  }

  private Samples loadSamples(JsonNode node) {
    List<ArticleCaseCandidate> articleCaseCandidates = new ArrayList<>();
    for (JsonNode candidateNode : requiredArray(node, "articleCaseCandidates")) {
      articleCaseCandidates.add(loadArticleCaseCandidate(candidateNode));
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
                  "German"),
              List.copyOf(entries)));
    }

    return new Samples(
        List.copyOf(articleCaseCandidates),
        List.copyOf(ambiguousSurfaces),
        textArray(
            requiredArray(node, "missingInflectionPatterns"),
            "missingInflectionPatterns",
            null,
            "German"));
  }

  private ArticleCaseCandidate loadArticleCaseCandidate(JsonNode node) {
    return new ArticleCaseCandidate(
        requiredText(node, "termId"),
        requiredText(node, "text"),
        requiredText(node, "gender"),
        requiredText(node, "partOfSpeech"),
        requiredText(node, "inflectionPattern"),
        requiredText(node, "stem"),
        formMap(requiredObject(node, "nounForms"), "nounForms", 2),
        formMap(requiredObject(node, "phraseForms"), "phraseForms", 3));
  }

  private CompactEntry loadCompactEntry(JsonNode node) {
    return new CompactEntry(
        requiredText(node, "surface"),
        requiredPositiveInt(node, "line"),
        textArray(
            requiredArray(node, "partOfSpeech"), "partOfSpeech", PART_OF_SPEECH_VALUES, "German"),
        textArray(requiredArray(node, "case"), "case", CASE_VALUES, "German"),
        textArray(requiredArray(node, "gender"), "gender", GENDER_VALUES, "German"),
        textArray(requiredArray(node, "number"), "number", NUMBER_VALUES, "German"),
        textArray(requiredArray(node, "inflections"), "inflections", null, "German"));
  }

  private Map<String, String> formMap(JsonNode node, String field, int expectedKeyParts) {
    Map<String, String> values = new LinkedHashMap<>();
    for (var fields = node.fields(); fields.hasNext(); ) {
      Map.Entry<String, JsonNode> entry = fields.next();
      String key = requireText(entry.getKey(), field + ".key");
      validateFormKey(field, key, expectedKeyParts);
      String value = requiredTextValue(entry.getValue(), field + "." + key);
      if (values.put(key, value) != null) {
        throw new IllegalArgumentException("Duplicate " + field + " key: " + key);
      }
    }
    return unmodifiableLinkedMap(values);
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

  private void validateProvenance(GermanArticleCaseReport report) {
    if (report.sources().isEmpty()) {
      throw new IllegalArgumentException("German article/case report must include sources");
    }
    if (report.provenance().sourceLabels().size() != report.sources().size()) {
      throw new IllegalArgumentException("Provenance source label count does not match sources");
    }
    if (!report.provenance().sources().equals(report.sources())) {
      throw new IllegalArgumentException("Provenance sources do not match top-level sources");
    }
  }

  private void validateArticleForms(List<ArticleForm> articleForms) {
    Map<String, ArticleForm> formsByKey = new LinkedHashMap<>();
    for (ArticleForm articleForm : articleForms) {
      String key = articleForm.key();
      if (formsByKey.put(key, articleForm) != null) {
        throw new IllegalArgumentException("Duplicate German article form: " + key);
      }
    }

    int expectedForms =
        ARTICLE_VALUES.size() * (GENDER_VALUES.size() - 1) * 4 + ARTICLE_VALUES.size() * 4;
    if (articleForms.size() != expectedForms) {
      throw new IllegalArgumentException(
          "Unexpected German article-form table size: " + articleForms.size());
    }
    requireArticleForm(formsByKey, "definite.masculine.singular.accusative", "den");
    requireArticleForm(formsByKey, "definite.neuter.singular.nominative", "das");
    requireArticleForm(formsByKey, "indefinite.*.plural.nominative", "");
  }

  private void requireArticleForm(Map<String, ArticleForm> articleForms, String key, String form) {
    ArticleForm articleForm = articleForms.get(key);
    if (articleForm == null || !form.equals(articleForm.form())) {
      throw new IllegalArgumentException("Missing expected German article form: " + key);
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
    if (counts.missingInflectionPatterns() > counts.dictionaryInflectionPatterns()) {
      throw new IllegalArgumentException("Missing patterns exceed dictionary pattern count");
    }
    if (counts.articleCaseCandidateTerms() > counts.supportedEntries()) {
      throw new IllegalArgumentException("Article/case candidates exceed supported entries");
    }
    if (samples.articleCaseCandidates().size() > counts.articleCaseCandidateTerms()) {
      throw new IllegalArgumentException("Article/case sample count exceeds reported candidates");
    }
    if (samples.ambiguousSurfaces().size() > counts.ambiguousSupportedSurfaces()) {
      throw new IllegalArgumentException("Ambiguous surface sample count exceeds reported count");
    }
    if (samples.missingInflectionPatterns().size() > counts.missingInflectionPatterns()) {
      throw new IllegalArgumentException("Missing pattern sample count exceeds reported count");
    }
  }

  private void validateSizeEstimates(Counts counts, SizeEstimates sizeEstimates) {
    if (sizeEstimates.termRowBytes() != counts.articleCaseCandidateTerms() * 20) {
      throw new IllegalArgumentException("German term-row byte estimate does not match candidates");
    }
    if (sizeEstimates.formRows() != counts.articleCaseCandidateTerms() * 16) {
      throw new IllegalArgumentException("German form-row count does not match candidates");
    }
    if (sizeEstimates.formRowBytes() != sizeEstimates.formRows() * 12) {
      throw new IllegalArgumentException("German form-row byte estimate does not match rows");
    }
    int expected =
        sizeEstimates.stringPoolBytes()
            + sizeEstimates.termRowBytes()
            + sizeEstimates.formRowBytes();
    if (sizeEstimates.binaryLowerBoundBytes() != expected) {
      throw new IllegalArgumentException("German binary lower-bound estimate does not match parts");
    }
  }

  private void validateReviewPolicy(Counts counts, Features features, ReviewPolicy reviewPolicy) {
    if (!"closed-world-article-case-forms".equals(reviewPolicy.runtimeExport())) {
      throw new IllegalArgumentException("Unsupported German runtime export policy");
    }
    if (reviewPolicy.automaticExportTerms() != counts.articleCaseCandidateTerms()) {
      throw new IllegalArgumentException(
          "German automatic export terms must match article/case candidates");
    }
    if (reviewPolicy.reviewRequiredSurfaces() != counts.ambiguousSupportedSurfaces()) {
      throw new IllegalArgumentException(
          "German review-required surfaces must match ambiguous supported surfaces");
    }
    if (reviewPolicy.blockedDictionaryEntries() + reviewPolicy.automaticExportTerms()
        != counts.dictionaryEntries()) {
      throw new IllegalArgumentException("German review policy must cover every dictionary entry");
    }
    if (!reviewPolicy.reviewRequiredReasons().equals(features.ambiguityReasons())) {
      throw new IllegalArgumentException(
          "German review-required reasons must match ambiguity reasons");
    }
    if (!reviewPolicy.blockedReasons().equals(features.candidateSkipReasons())) {
      throw new IllegalArgumentException(
          "German blocked reasons must match candidate skip reasons");
    }
  }

  private void validateFormKey(String field, String key, int expectedKeyParts) {
    String[] parts = key.split("\\.");
    if (parts.length != expectedKeyParts) {
      throw new IllegalArgumentException("Unsupported German " + field + " key: " + key);
    }
    int offset = 0;
    if (expectedKeyParts == 3) {
      validateAllowedValue(field, parts[0], ARTICLE_VALUES);
      offset = 1;
    }
    validateAllowedValue(field, parts[offset], FORM_CASE_VALUES);
    validateAllowedValue(field, parts[offset + 1], FORM_NUMBER_VALUES);
  }

  private int requiredPositiveInt(JsonNode node, String field) {
    int value = requiredInt(node, field);
    if (value <= 0) {
      throw new IllegalArgumentException("Expected positive int field: " + field);
    }
    return value;
  }

  private String requiredTextAllowBlank(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual()) {
      throw new IllegalArgumentException("Expected text field: " + field);
    }
    return value.asText();
  }

  private void validateAllowedValue(String field, String value, Set<String> allowedValues) {
    if (!allowedValues.contains(value)) {
      throw new IllegalArgumentException("Unsupported German " + field + " value: " + value);
    }
  }

  public record GermanArticleCaseReport(
      String schema,
      String locale,
      List<ArticleForm> articleForms,
      List<Source> sources,
      Provenance provenance,
      Counts counts,
      Features features,
      ReviewPolicy reviewPolicy,
      SizeEstimates sizeEstimates,
      Samples samples) {

    public GermanArticleCaseReport {
      schema = requireExpected(schema, "schema", EXPECTED_SCHEMA);
      locale = requireExpected(locale, "locale", EXPECTED_LOCALE);
      articleForms = List.copyOf(Objects.requireNonNull(articleForms, "articleForms"));
      sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
      provenance = Objects.requireNonNull(provenance, "provenance");
      counts = Objects.requireNonNull(counts, "counts");
      features = Objects.requireNonNull(features, "features");
      reviewPolicy = Objects.requireNonNull(reviewPolicy, "reviewPolicy");
      sizeEstimates = Objects.requireNonNull(sizeEstimates, "sizeEstimates");
      samples = Objects.requireNonNull(samples, "samples");
    }
  }

  public record ArticleForm(
      String article, String gender, String number, String grammaticalCase, String form) {

    public ArticleForm {
      article = requireExpectedValue(article, "article", ARTICLE_VALUES);
      gender = requireExpectedValue(gender, "gender", ARTICLE_GENDER_VALUES);
      number = requireExpectedValue(number, "number", FORM_NUMBER_VALUES);
      grammaticalCase = requireExpectedValue(grammaticalCase, "case", FORM_CASE_VALUES);
      form = Objects.requireNonNull(form, "form");
      if ("*".equals(gender) != "plural".equals(number)) {
        throw new IllegalArgumentException("German wildcard article gender is only for plural");
      }
    }

    public String key() {
      return article + "." + gender + "." + number + "." + grammaticalCase;
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

  public record Provenance(
      String license, String generator, List<String> sourceLabels, List<Source> sources) {

    public Provenance {
      license = requireText(license, "license");
      generator = requireText(generator, "generator");
      sourceLabels = requireStringList(sourceLabels, "sourceLabels", null);
      sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
    }
  }

  public record Counts(
      int dictionaryEntries,
      int supportedEntries,
      int uniqueSupportedSurfaces,
      int skippedLines,
      int ambiguousSupportedSurfaces,
      int dictionaryInflectionPatterns,
      int missingInflectionPatterns,
      int inflectionalPatterns,
      int nounInflectionalPatterns,
      int usedInflectionalPatterns,
      int articleCaseCandidateTerms) {

    public Counts {
      if (dictionaryEntries < 0
          || supportedEntries < 0
          || uniqueSupportedSurfaces < 0
          || skippedLines < 0
          || ambiguousSupportedSurfaces < 0
          || dictionaryInflectionPatterns < 0
          || missingInflectionPatterns < 0
          || inflectionalPatterns < 0
          || nounInflectionalPatterns < 0
          || usedInflectionalPatterns < 0
          || articleCaseCandidateTerms < 0) {
        throw new IllegalArgumentException("count values must be non-negative");
      }
    }
  }

  public record Features(
      Map<String, Integer> grammaticalCase,
      Map<String, Integer> gender,
      Map<String, Integer> number,
      Map<String, Integer> partOfSpeech,
      Map<String, Integer> patternSlotCases,
      Map<String, Integer> patternSlotGenders,
      Map<String, Integer> patternSlotNumbers,
      Map<String, Integer> ambiguityReasons,
      Map<String, Integer> candidateSkipReasons) {

    public Features {
      grammaticalCase = unmodifiableLinkedMap(Objects.requireNonNull(grammaticalCase, "case"));
      gender = unmodifiableLinkedMap(Objects.requireNonNull(gender, "gender"));
      number = unmodifiableLinkedMap(Objects.requireNonNull(number, "number"));
      partOfSpeech = unmodifiableLinkedMap(Objects.requireNonNull(partOfSpeech, "partOfSpeech"));
      patternSlotCases =
          unmodifiableLinkedMap(Objects.requireNonNull(patternSlotCases, "patternSlotCases"));
      patternSlotGenders =
          unmodifiableLinkedMap(Objects.requireNonNull(patternSlotGenders, "patternSlotGenders"));
      patternSlotNumbers =
          unmodifiableLinkedMap(Objects.requireNonNull(patternSlotNumbers, "patternSlotNumbers"));
      ambiguityReasons =
          unmodifiableLinkedMap(Objects.requireNonNull(ambiguityReasons, "ambiguityReasons"));
      candidateSkipReasons =
          unmodifiableLinkedMap(
              Objects.requireNonNull(candidateSkipReasons, "candidateSkipReasons"));
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
        throw new IllegalArgumentException("German review-policy counts must be non-negative");
      }
      reviewRequiredReasons =
          unmodifiableLinkedMap(
              Objects.requireNonNull(reviewRequiredReasons, "reviewRequiredReasons"));
      blockedReasons =
          unmodifiableLinkedMap(Objects.requireNonNull(blockedReasons, "blockedReasons"));
    }
  }

  public record SizeEstimates(
      int stringPoolBytes,
      int termRowBytes,
      int formRows,
      int formRowBytes,
      int binaryLowerBoundBytes) {

    public SizeEstimates {
      if (stringPoolBytes < 0
          || termRowBytes < 0
          || formRows < 0
          || formRowBytes < 0
          || binaryLowerBoundBytes < 0) {
        throw new IllegalArgumentException("size estimates must be non-negative");
      }
    }
  }

  public record Samples(
      List<ArticleCaseCandidate> articleCaseCandidates,
      List<AmbiguousSurface> ambiguousSurfaces,
      List<String> missingInflectionPatterns) {

    public Samples {
      articleCaseCandidates =
          List.copyOf(Objects.requireNonNull(articleCaseCandidates, "articleCaseCandidates"));
      ambiguousSurfaces =
          List.copyOf(Objects.requireNonNull(ambiguousSurfaces, "ambiguousSurfaces"));
      missingInflectionPatterns =
          requireStringList(missingInflectionPatterns, "missingInflectionPatterns", null);
    }
  }

  public record ArticleCaseCandidate(
      String termId,
      String text,
      String gender,
      String partOfSpeech,
      String inflectionPattern,
      String stem,
      Map<String, String> nounForms,
      Map<String, String> phraseForms) {

    public ArticleCaseCandidate {
      termId = requireText(termId, "termId");
      text = requireText(text, "text");
      gender = requireExpectedValue(gender, "gender", GENDER_VALUES);
      if ("<missing>".equals(gender)) {
        throw new IllegalArgumentException("Article/case candidate gender is required");
      }
      partOfSpeech =
          requireExpectedValue(partOfSpeech, "partOfSpeech", CANDIDATE_PART_OF_SPEECH_VALUES);
      inflectionPattern = requireText(inflectionPattern, "inflectionPattern");
      stem = requireText(stem, "stem");
      nounForms = unmodifiableLinkedMap(Objects.requireNonNull(nounForms, "nounForms"));
      phraseForms = unmodifiableLinkedMap(Objects.requireNonNull(phraseForms, "phraseForms"));
      if (nounForms.size() != FORM_CASE_VALUES.size() * FORM_NUMBER_VALUES.size()) {
        throw new IllegalArgumentException("German candidate must include every noun case form");
      }
      if (phraseForms.size()
          != ARTICLE_VALUES.size() * FORM_CASE_VALUES.size() * FORM_NUMBER_VALUES.size()) {
        throw new IllegalArgumentException("German candidate must include every phrase form");
      }
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
      inflections = requireStringList(inflections, "inflections", null);
    }
  }

  private static List<String> requireStringList(
      List<String> values, String field, Set<String> allowedValues) {
    List<String> copied = new ArrayList<>();
    for (String value : Objects.requireNonNull(values, field)) {
      value = requireText(value, field);
      if (allowedValues != null && !allowedValues.contains(value)) {
        throw new IllegalArgumentException("Unsupported German " + field + " value: " + value);
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

  private static String requireExpectedValue(String value, String field, Set<String> values) {
    value = requireText(value, field);
    if (!values.contains(value)) {
      throw new IllegalArgumentException("Unsupported German " + field + " value: " + value);
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
