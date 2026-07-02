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
 * Loads the generated Italian noun/proper-noun report. The report locks the compact metadata shape
 * for Italian article generation: gender, number, and article class are explicit generator outputs
 * instead of hidden runtime guesses.
 */
@GeneratorSupport
class ItalianNounPackReportJsonLoader {

  static final String EXPECTED_SCHEMA = "mojito-mf2-inflection/it-noun-pack-report/v0";
  static final String EXPECTED_LOCALE = "it";

  private static final Set<String> GENDER_VALUES =
      Set.of("<missing>", "feminine", "masculine", "neuter");
  private static final Set<String> NUMBER_VALUES = Set.of("<missing>", "plural", "singular");
  private static final Set<String> PART_OF_SPEECH_VALUES =
      Set.of("adjective", "adverb", "noun", "numeral", "proper-noun", "verb");
  private static final Set<String> PATTERN_PART_OF_SPEECH_VALUES =
      Set.of("abbreviation", "adjective", "article", "noun", "verb");
  private static final Set<String> ARTICLE_VALUES = Set.of("definite", "indefinite");
  private static final Set<String> ARTICLE_GENDER_VALUES = Set.of("feminine", "masculine");
  private static final Set<String> ARTICLE_NUMBER_VALUES = Set.of("plural", "singular");
  private static final Set<String> ARTICLE_CLASS_VALUES = Set.of("elision", "lo", "standard");
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

  ItalianNounPackReportJsonLoader() {
    this(new ObjectMapper());
  }

  ItalianNounPackReportJsonLoader(ObjectMapper objectMapper) {
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
  }

  public ItalianNounPackReport load(String json) {
    Objects.requireNonNull(json, "json");
    return load(objectMapper.readTreeUnchecked(json));
  }

  public ItalianNounPackReport load(JsonNode root) {
    ItalianNounPackReport report =
        new ItalianNounPackReport(
            requiredText(root, "schema"),
            requiredText(root, "locale"),
            loadSources(requiredArray(root, "sources")),
            loadProvenance(requiredObject(root, "provenance")),
            loadCounts(requiredObject(root, "counts")),
            loadFeatures(requiredObject(root, "features")),
            loadSizeEstimates(requiredObject(root, "sizeEstimates")),
            loadArticleStrategy(requiredObject(root, "articleStrategy")),
            loadReviewPolicy(requiredObject(root, "reviewPolicy")),
            loadSamples(requiredObject(root, "samples")));

    validateProvenance(report);
    validateCounts(report.counts(), report.samples());
    validateSizeEstimates(report.counts(), report.sizeEstimates());
    validateArticleStrategy(report.counts(), report.articleStrategy());
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
        textArray(requiredArray(node, "sourceLabels"), "sourceLabels", null, "Italian"),
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

  private ArticleStrategy loadArticleStrategy(JsonNode node) {
    return new ArticleStrategy(
        loadArticleForms(requiredArray(node, "articleForms")),
        loadArticleCounts(requiredObject(node, "counts")),
        featureMap(
            requiredObject(node, "articleClassCounts"), "articleClassCounts", ARTICLE_CLASS_VALUES),
        loadArticleSizeEstimates(requiredObject(node, "sizeEstimates")),
        loadArticleSamples(requiredObject(node, "samples")));
  }

  private List<ArticleForm> loadArticleForms(JsonNode node) {
    List<ArticleForm> articleForms = new ArrayList<>();
    for (JsonNode articleFormNode : node) {
      articleForms.add(
          new ArticleForm(
              requiredText(articleFormNode, "article"),
              requiredText(articleFormNode, "gender"),
              requiredText(articleFormNode, "number"),
              requiredText(articleFormNode, "articleClass"),
              requiredText(articleFormNode, "form")));
    }
    return List.copyOf(articleForms);
  }

  private ArticleCounts loadArticleCounts(JsonNode node) {
    return new ArticleCounts(
        requiredInt(node, "articleCandidateSurfaces"),
        requiredInt(node, "dictionaryVowelStartSurfaces"),
        requiredInt(node, "masculineLoClassSurfaces"),
        requiredInt(node, "surfaceVowelStartSurfaces"));
  }

  private ArticleSizeEstimates loadArticleSizeEstimates(JsonNode node) {
    JsonNode eagerPhrasePack = requiredObject(node, "eagerPhrasePack");
    return new ArticleSizeEstimates(
        new ArticlePhrasePackEstimate(
            requiredInt(eagerPhrasePack, "phraseRows"),
            requiredInt(eagerPhrasePack, "stringPoolBytes"),
            requiredInt(eagerPhrasePack, "phraseRowBytes"),
            requiredInt(eagerPhrasePack, "binaryLowerBoundBytes")));
  }

  private ArticleSamples loadArticleSamples(JsonNode node) {
    return new ArticleSamples(
        articleCandidateSamples(requiredArray(node, "articleCandidates")),
        articleCandidateSamples(requiredArray(node, "loClassCandidates")),
        articleCandidateSamples(requiredArray(node, "vowelStartCandidates")),
        articleCandidateSamples(requiredArray(node, "targetedArticleCandidates")));
  }

  private List<ArticleCandidateSample> articleCandidateSamples(JsonNode node) {
    List<ArticleCandidateSample> samples = new ArrayList<>();
    for (JsonNode sampleNode : node) {
      samples.add(
          new ArticleCandidateSample(
              requiredText(sampleNode, "surface"),
              requiredText(sampleNode, "gender"),
              requiredText(sampleNode, "number"),
              requiredText(sampleNode, "articleClass"),
              requiredBoolean(sampleNode, "dictionaryVowelStart"),
              requiredBoolean(sampleNode, "surfaceVowelStart"),
              phraseForms(requiredObject(sampleNode, "phraseForms"))));
    }
    return List.copyOf(samples);
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
              textArray(requiredArray(entryNode, "partOfSpeech"), "partOfSpeech", null, "Italian"),
              textArray(requiredArray(entryNode, "gender"), "gender", GENDER_VALUES, "Italian"),
              textArray(requiredArray(entryNode, "number"), "number", NUMBER_VALUES, "Italian"),
              textArray(requiredArray(entryNode, "inflections"), "inflections", null, "Italian")));
    }

    return new Samples(
        List.copyOf(genderNumberCandidates),
        List.copyOf(blockingAmbiguities),
        textArray(
            requiredArray(node, "missingInflectionPatterns"),
            "missingInflectionPatterns",
            null,
            "Italian"),
        List.copyOf(entries));
  }

  private SurfaceSample loadSurfaceSample(JsonNode node) {
    return new SurfaceSample(
        requiredText(node, "surface"),
        textArray(requiredArray(node, "genders"), "genders", GENDER_VALUES, "Italian"),
        textArray(requiredArray(node, "numbers"), "numbers", NUMBER_VALUES, "Italian"),
        textArray(
            requiredArray(node, "partsOfSpeech"),
            "partsOfSpeech",
            PART_OF_SPEECH_VALUES,
            "Italian"),
        textArray(requiredArray(node, "inflections"), "inflections", null, "Italian"),
        textArray(requiredArray(node, "reasons"), "reasons", AMBIGUITY_REASON_VALUES, "Italian"),
        requiredInt(node, "entries"),
        requiredBoolean(node, "dictionaryVowelStart"));
  }

  private void validateProvenance(ItalianNounPackReport report) {
    if (!report.sources().equals(report.provenance().sources())) {
      throw new IllegalArgumentException("Italian report provenance sources must match sources");
    }
    if (report.provenance().sourceLabels().size() != report.sources().size()) {
      throw new IllegalArgumentException(
          "Italian report provenance source label count must match sources");
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
      throw new IllegalArgumentException("Italian metadata row byte estimate does not match rows");
    }
    if (estimate.binaryLowerBoundBytes() != estimate.stringPoolBytes() + estimate.rowBytes()) {
      throw new IllegalArgumentException("Italian metadata binary estimate does not match parts");
    }
  }

  private void validateArticleStrategy(Counts counts, ArticleStrategy articleStrategy) {
    validateArticleForms(articleStrategy.articleForms());

    ArticleCounts articleCounts = articleStrategy.counts();
    if (articleCounts.articleCandidateSurfaces() != counts.genderNumberCandidateSurfaces()) {
      throw new IllegalArgumentException(
          "Italian article candidate count must match gender/number candidates");
    }
    int articleClassTotal =
        articleStrategy.articleClassCounts().values().stream().mapToInt(Integer::intValue).sum();
    if (articleClassTotal != articleCounts.articleCandidateSurfaces()) {
      throw new IllegalArgumentException("Italian article-class counts must sum to candidates");
    }
    if (articleStrategy.articleClassCounts().getOrDefault("lo", 0)
        != articleCounts.masculineLoClassSurfaces()) {
      throw new IllegalArgumentException("Italian lo article-class count mismatch");
    }
    if (articleStrategy.articleClassCounts().getOrDefault("elision", 0)
        != articleCounts.surfaceVowelStartSurfaces()) {
      throw new IllegalArgumentException("Italian elision article-class count mismatch");
    }
    if (articleCounts.dictionaryVowelStartSurfaces() > articleCounts.surfaceVowelStartSurfaces()) {
      throw new IllegalArgumentException(
          "Italian dictionary vowel-start count exceeds surface vowel-start count");
    }

    ArticlePhrasePackEstimate estimate = articleStrategy.sizeEstimates().eagerPhrasePack();
    int expectedPhraseRows = articleCounts.articleCandidateSurfaces() * ARTICLE_VALUES.size();
    if (estimate.phraseRows() != expectedPhraseRows) {
      throw new IllegalArgumentException(
          "Italian article phrase-row count does not match articles");
    }
    if (estimate.phraseRowBytes() != estimate.phraseRows() * 12) {
      throw new IllegalArgumentException("Italian article phrase-row byte estimate is incoherent");
    }
    if (estimate.binaryLowerBoundBytes()
        != estimate.stringPoolBytes() + estimate.phraseRowBytes()) {
      throw new IllegalArgumentException("Italian article binary estimate does not match parts");
    }
  }

  private void validateReviewPolicy(Counts counts, ReviewPolicy reviewPolicy) {
    if (!"article-shell-composition".equals(reviewPolicy.compactRuntime())) {
      throw new IllegalArgumentException("Unsupported Italian compact runtime");
    }
    if (reviewPolicy.automaticExportSurfaces() != counts.exactGenderNumberSurfaces()) {
      throw new IllegalArgumentException(
          "Italian automatic export count must match exact gender/number surfaces");
    }
    if (reviewPolicy.automaticExportSurfaces() + reviewPolicy.reviewRequiredSurfaces()
        != counts.genderNumberCandidateSurfaces()) {
      throw new IllegalArgumentException(
          "Italian review policy must cover every gender/number candidate");
    }
    if (reviewPolicy.blockedSurfaces()
        != counts.uniqueSupportedSurfaces() - counts.genderNumberCandidateSurfaces()) {
      throw new IllegalArgumentException(
          "Italian blocked review count must match non-candidate surfaces");
    }
    if (reviewPolicy.reviewRequiredSurfaces() == 0
        && !reviewPolicy.reviewRequiredReasons().isEmpty()) {
      throw new IllegalArgumentException(
          "Italian review reasons present when no surfaces require review");
    }
    if (reviewPolicy.blockedSurfaces() == 0 && !reviewPolicy.blockedReasons().isEmpty()) {
      throw new IllegalArgumentException(
          "Italian blocked reasons present when no surfaces are blocked");
    }
  }

  private void validateArticleForms(List<ArticleForm> articleForms) {
    Set<String> seen = new HashSet<>();
    for (ArticleForm articleForm : articleForms) {
      if (!seen.add(articleForm.key())) {
        throw new IllegalArgumentException("Duplicate Italian article form: " + articleForm.key());
      }
    }
    int expectedRows = ARTICLE_VALUES.size() * 10;
    if (articleForms.size() != expectedRows) {
      throw new IllegalArgumentException("Unexpected Italian article-form table size");
    }

    for (String article : ARTICLE_VALUES) {
      requireArticleForm(seen, article, "masculine", "singular", "standard");
      requireArticleForm(seen, article, "masculine", "singular", "lo");
      requireArticleForm(seen, article, "masculine", "singular", "elision");
      requireArticleForm(seen, article, "masculine", "plural", "standard");
      requireArticleForm(seen, article, "masculine", "plural", "lo");
      requireArticleForm(seen, article, "masculine", "plural", "elision");
      requireArticleForm(seen, article, "feminine", "singular", "standard");
      requireArticleForm(seen, article, "feminine", "singular", "elision");
      requireArticleForm(seen, article, "feminine", "plural", "standard");
      requireArticleForm(seen, article, "feminine", "plural", "elision");
    }
  }

  private void requireArticleForm(
      Set<String> seen, String article, String gender, String number, String articleClassValue) {
    String key = ArticleForm.key(article, gender, number, articleClassValue);
    if (!seen.contains(key)) {
      throw new IllegalArgumentException("Missing Italian article form: " + key);
    }
  }

  private Map<String, String> phraseForms(JsonNode node) {
    Map<String, String> values = new LinkedHashMap<>();
    node.fields()
        .forEachRemaining(
            entry -> {
              String key = entry.getKey();
              if (!ARTICLE_VALUES.contains(key)) {
                throw new IllegalArgumentException("Unsupported Italian phraseForms key: " + key);
              }
              values.put(key, requiredTextValue(entry.getValue(), "phraseForms." + key));
            });
    if (!values.keySet().containsAll(ARTICLE_VALUES)) {
      throw new IllegalArgumentException("Italian phraseForms must include every article");
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
                throw new IllegalArgumentException("Unsupported Italian " + field + " key: " + key);
              }
              int value = requiredIntValue(entry.getValue(), field + "." + key);
              if (value < 0) {
                throw new IllegalArgumentException(
                    "Italian feature counts must be non-negative: " + field);
              }
              values.put(key, value);
            });
    return unmodifiableLinkedMap(values);
  }

  private static String requireSchema(String schema) {
    if (!EXPECTED_SCHEMA.equals(schema)) {
      throw new IllegalArgumentException("Expected Italian noun-pack report schema");
    }
    return schema;
  }

  private static String requireLocale(String locale) {
    if (!EXPECTED_LOCALE.equals(locale)) {
      throw new IllegalArgumentException("Expected Italian noun-pack report locale");
    }
    return locale;
  }

  public record ItalianNounPackReport(
      String schema,
      String locale,
      List<Source> sources,
      Provenance provenance,
      Counts counts,
      Features features,
      SizeEstimates sizeEstimates,
      ArticleStrategy articleStrategy,
      ReviewPolicy reviewPolicy,
      Samples samples) {

    public ItalianNounPackReport {
      schema = requireSchema(schema);
      locale = requireLocale(locale);
      sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
      provenance = Objects.requireNonNull(provenance, "provenance");
      counts = Objects.requireNonNull(counts, "counts");
      features = Objects.requireNonNull(features, "features");
      sizeEstimates = Objects.requireNonNull(sizeEstimates, "sizeEstimates");
      articleStrategy = Objects.requireNonNull(articleStrategy, "articleStrategy");
      reviewPolicy = Objects.requireNonNull(reviewPolicy, "reviewPolicy");
      samples = Objects.requireNonNull(samples, "samples");
      if (sources.isEmpty()) {
        throw new IllegalArgumentException("Italian report must include sources");
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

  public record ArticleStrategy(
      List<ArticleForm> articleForms,
      ArticleCounts counts,
      Map<String, Integer> articleClassCounts,
      ArticleSizeEstimates sizeEstimates,
      ArticleSamples samples) {

    public ArticleStrategy {
      articleForms = List.copyOf(Objects.requireNonNull(articleForms, "articleForms"));
      counts = Objects.requireNonNull(counts, "counts");
      articleClassCounts =
          unmodifiableLinkedMap(Objects.requireNonNull(articleClassCounts, "articleClassCounts"));
      sizeEstimates = Objects.requireNonNull(sizeEstimates, "sizeEstimates");
      samples = Objects.requireNonNull(samples, "samples");
    }
  }

  public record ArticleForm(
      String article, String gender, String number, String articleClassValue, String form) {

    public ArticleForm {
      article = requireExpectedValue(article, "article", ARTICLE_VALUES);
      gender = requireExpectedValue(gender, "gender", ARTICLE_GENDER_VALUES);
      number = requireExpectedValue(number, "number", ARTICLE_NUMBER_VALUES);
      articleClassValue =
          requireExpectedValue(articleClassValue, "articleClass", ARTICLE_CLASS_VALUES);
      form = requireTextValue(form, "form");
      if ("feminine".equals(gender) && "lo".equals(articleClassValue)) {
        throw new IllegalArgumentException("Italian lo article class is masculine-only");
      }
    }

    String key() {
      return key(article, gender, number, articleClassValue);
    }

    static String key(String article, String gender, String number, String articleClassValue) {
      return article + "." + gender + "." + number + "." + articleClassValue;
    }
  }

  public record ArticleCounts(
      int articleCandidateSurfaces,
      int dictionaryVowelStartSurfaces,
      int masculineLoClassSurfaces,
      int surfaceVowelStartSurfaces) {}

  public record ArticleSizeEstimates(ArticlePhrasePackEstimate eagerPhrasePack) {

    public ArticleSizeEstimates {
      eagerPhrasePack = Objects.requireNonNull(eagerPhrasePack, "eagerPhrasePack");
    }
  }

  public record ArticlePhrasePackEstimate(
      int phraseRows, int stringPoolBytes, int phraseRowBytes, int binaryLowerBoundBytes) {}

  public record ArticleSamples(
      List<ArticleCandidateSample> articleCandidates,
      List<ArticleCandidateSample> loClassCandidates,
      List<ArticleCandidateSample> vowelStartCandidates,
      List<ArticleCandidateSample> targetedArticleCandidates) {

    public ArticleSamples {
      articleCandidates =
          List.copyOf(Objects.requireNonNull(articleCandidates, "articleCandidates"));
      loClassCandidates =
          List.copyOf(Objects.requireNonNull(loClassCandidates, "loClassCandidates"));
      vowelStartCandidates =
          List.copyOf(Objects.requireNonNull(vowelStartCandidates, "vowelStartCandidates"));
      targetedArticleCandidates =
          List.copyOf(
              Objects.requireNonNull(targetedArticleCandidates, "targetedArticleCandidates"));
    }
  }

  public record ArticleCandidateSample(
      String surface,
      String gender,
      String number,
      String articleClassValue,
      boolean dictionaryVowelStart,
      boolean surfaceVowelStart,
      Map<String, String> phraseForms) {

    public ArticleCandidateSample {
      surface = requireTextValue(surface, "surface");
      gender = requireExpectedValue(gender, "gender", ARTICLE_GENDER_VALUES);
      number = requireExpectedValue(number, "number", ARTICLE_NUMBER_VALUES);
      articleClassValue =
          requireExpectedValue(articleClassValue, "articleClass", ARTICLE_CLASS_VALUES);
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
        throw new IllegalArgumentException("Italian review-policy counts must be non-negative");
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
      int entries,
      boolean dictionaryVowelStart) {

    public SurfaceSample {
      surface = requireTextValue(surface, "surface");
      genders = List.copyOf(Objects.requireNonNull(genders, "genders"));
      numbers = List.copyOf(Objects.requireNonNull(numbers, "numbers"));
      partsOfSpeech = List.copyOf(Objects.requireNonNull(partsOfSpeech, "partsOfSpeech"));
      inflections = List.copyOf(Objects.requireNonNull(inflections, "inflections"));
      reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons"));
      if (entries <= 0) {
        throw new IllegalArgumentException("Italian sample entries must be positive");
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
        throw new IllegalArgumentException("Italian entry sample line must be positive");
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
      throw new IllegalArgumentException("Unsupported Italian " + field + " value: " + value);
    }
    return value;
  }
}
