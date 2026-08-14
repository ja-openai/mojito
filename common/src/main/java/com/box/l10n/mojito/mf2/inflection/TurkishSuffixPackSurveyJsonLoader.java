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
 * Loads the generated Turkish suffix-pack survey. The survey is a generator contract for deciding
 * the rule-plus-exception Turkish runtime shape; it is not the final runtime pack.
 */
@GeneratorSupport
class TurkishSuffixPackSurveyJsonLoader {

  static final String EXPECTED_SCHEMA = "mojito-mf2-inflection/tr-suffix-pack-survey/v0";
  static final String EXPECTED_LOCALE = "tr";
  static final String EXPECTED_RECOMMENDATION = "rule-plus-exception suffix pack";
  static final String EXPECTED_MUTATION_STRATEGY = "explicit-template-forms";

  private static final List<String> EXPECTED_METADATA_BITS =
      List.of(
          "vowelEnd",
          "frontVowel",
          "roundedVowel",
          "foreign",
          "exception",
          "hardConsonant",
          "softConsonant",
          "compound");
  private static final List<String> EXPECTED_RENDERER_SCOPE =
      List.of("plural", "nominative", "accusative", "dative", "locative", "ablative");
  private static final List<String> EXPECTED_EXPLICIT_REVIEW_FLAGS =
      List.of("exception", "foreign", "soft-consonant");
  private static final Set<String> CASE_VALUES =
      Set.of(
          "<missing>",
          "ablative",
          "absolutive",
          "accusative",
          "dative",
          "genitive",
          "locative",
          "nominative");
  private static final Set<String> NUMBER_VALUES =
      Set.of("<missing>", "dual", "plural", "singular");
  private static final Set<String> PART_OF_SPEECH_VALUES =
      Set.of("adjective", "adverb", "noun", "numeral", "proper-noun", "verb");
  private static final Set<String> PATTERN_SLOT_OTHER_ATTRIBUTE_VALUES =
      Set.of("animacy=animate", "count=uncountable", "definiteness=definite");
  private static final Set<String> SUPPLEMENTAL_FLAG_VALUES =
      Set.of(
          "adjective",
          "back-round",
          "back-unround",
          "compound",
          "consonant-end",
          "exception",
          "foreign",
          "front-round",
          "front-unround",
          "hard-consonant",
          "noun",
          "proper-noun",
          "soft-consonant",
          "vowel-end");
  private static final Set<String> SLOT_ATTRIBUTE_KEYS =
      Set.of("animacy", "case", "count", "definiteness", "number");

  private final ObjectMapper objectMapper;

  TurkishSuffixPackSurveyJsonLoader() {
    this(new ObjectMapper());
  }

  TurkishSuffixPackSurveyJsonLoader(ObjectMapper objectMapper) {
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
  }

  public TurkishSuffixPackSurvey load(String json) {
    Objects.requireNonNull(json, "json");
    return load(objectMapper.readTreeUnchecked(json));
  }

  public TurkishSuffixPackSurvey load(JsonNode root) {
    TurkishSuffixPackSurvey survey =
        new TurkishSuffixPackSurvey(
            requiredText(root, "schema"),
            requiredText(root, "locale"),
            loadSources(requiredArray(root, "sources")),
            loadProvenance(requiredObject(root, "provenance")),
            loadCounts(requiredObject(root, "counts")),
            loadFeatures(requiredObject(root, "features")),
            loadPackShape(requiredObject(root, "packShape")),
            loadCompositionPolicy(requiredObject(root, "compositionPolicy")),
            loadSamples(requiredObject(root, "samples")));

    validateProvenance(survey);
    validateCounts(survey.counts(), survey.samples());
    validateFeatureArithmetic(survey.counts(), survey.features(), survey.packShape());
    validatePackShape(survey.counts(), survey.features(), survey.packShape());
    validateCompositionPolicy(survey.counts(), survey.features(), survey.compositionPolicy());
    validateCompositionSamples(survey.compositionPolicy(), survey.samples());
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
        textArray(requiredArray(node, "sourceLabels"), "sourceLabels", null, "Turkish"));
  }

  private Counts loadCounts(JsonNode node) {
    return new Counts(
        requiredInt(node, "dictionaryEntries"),
        requiredInt(node, "dictionarySkippedLines"),
        requiredInt(node, "supportedEntries"),
        requiredInt(node, "uniqueSupportedSurfaces"),
        requiredInt(node, "supportedWithoutInflection"),
        requiredInt(node, "defaultInflectionEntries"),
        requiredInt(node, "explicitInflectionEntries"),
        requiredInt(node, "dictionaryInflectionPatterns"),
        requiredInt(node, "missingInflectionPatterns"),
        requiredInt(node, "inflectionalPatterns"),
        requiredInt(node, "nounInflectionalPatterns"),
        requiredInt(node, "usedInflectionalPatterns"),
        requiredInt(node, "supplementalEntries"),
        requiredInt(node, "supplementalSkippedLines"),
        requiredInt(node, "supportedSurfacesCoveredBySupplemental"),
        requiredInt(node, "defaultInflectionEntriesCoveredBySupplemental"),
        requiredInt(node, "supplementalOnlySurfaces"));
  }

  private Features loadFeatures(JsonNode node) {
    return new Features(
        featureMap(requiredObject(node, "case"), "case", CASE_VALUES),
        featureMap(requiredObject(node, "number"), "number", NUMBER_VALUES),
        featureMap(requiredObject(node, "partOfSpeech"), "partOfSpeech", PART_OF_SPEECH_VALUES),
        featureMap(requiredObject(node, "patternSlotCases"), "patternSlotCases", CASE_VALUES),
        featureMap(requiredObject(node, "patternSlotNumbers"), "patternSlotNumbers", NUMBER_VALUES),
        featureMap(
            requiredObject(node, "patternSlotOtherAttributes"),
            "patternSlotOtherAttributes",
            PATTERN_SLOT_OTHER_ATTRIBUTE_VALUES),
        featureMap(
            requiredObject(node, "supplementalFlags"),
            "supplementalFlags",
            SUPPLEMENTAL_FLAG_VALUES),
        supplementalCombinationMap(requiredObject(node, "supplementalCombinations")),
        featureMap(requiredObject(node, "inflectionPatterns"), "inflectionPatterns", null),
        numericFeatureMap(requiredObject(node, "slotsPerPattern"), "slotsPerPattern", 0, 16),
        requiredInt(node, "templateRows"));
  }

  private PackShape loadPackShape(JsonNode node) {
    return new PackShape(
        requiredText(node, "recommendation"),
        requiredText(node, "reason"),
        textArray(requiredArray(node, "metadataBits"), "metadataBits", null, "Turkish"),
        requiredInt(node, "supplementalExceptionRows"),
        requiredInt(node, "supplementalStringPoolBytes"),
        requiredInt(node, "supplementalRowBytes"),
        requiredInt(node, "supplementalMetadataLowerBoundBytes"),
        requiredInt(node, "explicitTemplateRows"),
        requiredInt(node, "explicitTemplateRowBytes"),
        requiredInt(node, "explicitTemplateBaseCandidateTerms"),
        requiredInt(node, "explicitTemplateCompiledFormRows"),
        requiredInt(node, "explicitTemplateCompiledStringPoolBytes"),
        requiredInt(node, "explicitTemplateCompiledTermRowBytes"),
        requiredInt(node, "explicitTemplateCompiledFormRowBytes"),
        requiredInt(node, "explicitTemplateCompiledLowerBoundBytes"),
        requiredInt(node, "explicitTemplateCompiledJsonBytes"));
  }

  private CompositionPolicy loadCompositionPolicy(JsonNode node) {
    return new CompositionPolicy(
        requiredText(node, "ruleSafeInflection"),
        textArray(requiredArray(node, "rendererScope"), "rendererScope", null, "Turkish"),
        requiredText(node, "mutationStrategy"),
        requiredText(node, "mutationStrategyReason"),
        textArray(
            requiredArray(node, "requiresExplicitFormFlags"),
            "requiresExplicitFormFlags",
            SUPPLEMENTAL_FLAG_VALUES,
            "Turkish"),
        requiredInt(node, "supplementalRowsRequiringExplicitReview"),
        requiredInt(node, "caseTemplatePatterns"),
        requiredInt(node, "caseTemplateRows"),
        requiredInt(node, "caseTemplateRowBytes"),
        requiredInt(node, "emptySuffixCaseTemplateRows"),
        requiredInt(node, "suffixPreservingCaseTemplateRows"),
        requiredInt(node, "consonantMutationPatterns"),
        requiredInt(node, "consonantMutationTemplateRows"),
        requiredInt(node, "consonantMutationTemplateRowBytes"),
        requiredInt(node, "pluralTemplateRows"),
        requiredInt(node, "pluralTemplateRowBytes"));
  }

  private Samples loadSamples(JsonNode node) {
    return new Samples(
        entrySamples(requiredArray(node, "defaultInflectionCoveredBySupplemental"), true),
        entrySamples(requiredArray(node, "defaultInflectionMissingSupplemental"), false),
        entrySamples(requiredArray(node, "explicitInflectionEntries"), false),
        entrySamples(requiredArray(node, "supportedWithoutInflection"), false),
        supplementalSamples(requiredArray(node, "supplementalOnlySurfaces")),
        patternSamples(requiredArray(node, "inflectionPatterns")),
        mutationTemplateSamples(requiredArray(node, "consonantMutationTemplates")),
        textArray(
            requiredArray(node, "missingInflectionPatterns"),
            "missingInflectionPatterns",
            null,
            "Turkish"));
  }

  private List<EntrySample> entrySamples(JsonNode node, boolean requireSupplementalFlags) {
    List<EntrySample> samples = new ArrayList<>();
    for (JsonNode sampleNode : node) {
      List<String> supplementalFlags =
          sampleNode.has("supplementalFlags")
              ? textArray(
                  requiredArray(sampleNode, "supplementalFlags"),
                  "supplementalFlags",
                  SUPPLEMENTAL_FLAG_VALUES,
                  "Turkish")
              : List.of();
      if (requireSupplementalFlags && supplementalFlags.isEmpty()) {
        throw new IllegalArgumentException("Turkish sample must include supplemental flags");
      }
      samples.add(
          new EntrySample(
              requiredText(sampleNode, "surface"),
              requiredPositiveInt(sampleNode, "line"),
              textArray(
                  requiredArray(sampleNode, "partOfSpeech"),
                  "partOfSpeech",
                  PART_OF_SPEECH_VALUES,
                  "Turkish"),
              textArray(requiredArray(sampleNode, "case"), "case", CASE_VALUES, "Turkish"),
              textArray(requiredArray(sampleNode, "number"), "number", NUMBER_VALUES, "Turkish"),
              textArray(requiredArray(sampleNode, "inflections"), "inflections", null, "Turkish"),
              supplementalFlags));
    }
    return List.copyOf(samples);
  }

  private List<SupplementalSurfaceSample> supplementalSamples(JsonNode node) {
    List<SupplementalSurfaceSample> samples = new ArrayList<>();
    for (JsonNode sampleNode : node) {
      samples.add(
          new SupplementalSurfaceSample(
              requiredText(sampleNode, "surface"),
              textArray(
                  requiredArray(sampleNode, "flags"),
                  "flags",
                  SUPPLEMENTAL_FLAG_VALUES,
                  "Turkish")));
    }
    return List.copyOf(samples);
  }

  private List<InflectionPatternSample> patternSamples(JsonNode node) {
    List<InflectionPatternSample> samples = new ArrayList<>();
    for (JsonNode sampleNode : node) {
      List<PatternSlotSample> slots = new ArrayList<>();
      for (JsonNode slotNode : requiredArray(sampleNode, "slots")) {
        slots.add(
            new PatternSlotSample(
                slotAttributes(requiredObject(slotNode, "attrs")),
                requiredText(slotNode, "template")));
      }
      samples.add(
          new InflectionPatternSample(
              requiredText(sampleNode, "name"),
              textArray(
                  requiredArray(sampleNode, "partOfSpeech"),
                  "partOfSpeech",
                  PART_OF_SPEECH_VALUES,
                  "Turkish"),
              requiredTextAllowEmpty(sampleNode, "suffix"),
              requiredInt(sampleNode, "words"),
              List.copyOf(slots)));
    }
    return List.copyOf(samples);
  }

  private List<MutationTemplateSample> mutationTemplateSamples(JsonNode node) {
    List<MutationTemplateSample> samples = new ArrayList<>();
    for (JsonNode sampleNode : node) {
      samples.add(
          new MutationTemplateSample(
              requiredText(sampleNode, "pattern"),
              requiredText(sampleNode, "suffix"),
              slotAttributes(requiredObject(sampleNode, "attrs")),
              requiredText(sampleNode, "template"),
              textArray(requiredArray(sampleNode, "surfaces"), "surfaces", null, "Turkish")));
    }
    return List.copyOf(samples);
  }

  private Map<String, String> slotAttributes(JsonNode node) {
    Map<String, String> attrs = new LinkedHashMap<>();
    for (var fields = node.fields(); fields.hasNext(); ) {
      Map.Entry<String, JsonNode> entry = fields.next();
      String key = entry.getKey();
      if (!SLOT_ATTRIBUTE_KEYS.contains(key)) {
        throw new IllegalArgumentException("Unsupported Turkish slot attribute: " + key);
      }
      attrs.put(key, requiredTextValue(entry.getValue(), "slotAttributes." + key));
    }
    validateSlotAttributeCombination(attrs);
    return unmodifiableLinkedMap(attrs);
  }

  private void validateSlotAttributeCombination(Map<String, String> attrs) {
    for (Map.Entry<String, String> entry : attrs.entrySet()) {
      String value = entry.getValue();
      switch (entry.getKey()) {
        case "case" -> {
          if (!CASE_VALUES.contains(value) || "<missing>".equals(value)) {
            throw new IllegalArgumentException("Unsupported Turkish slot case: " + value);
          }
        }
        case "number" -> {
          if (!NUMBER_VALUES.contains(value) || "<missing>".equals(value)) {
            throw new IllegalArgumentException("Unsupported Turkish slot number: " + value);
          }
        }
        case "animacy" -> {
          if (!"animate".equals(value)) {
            throw new IllegalArgumentException("Unsupported Turkish slot animacy: " + value);
          }
        }
        case "count" -> {
          if (!"uncountable".equals(value)) {
            throw new IllegalArgumentException("Unsupported Turkish slot count: " + value);
          }
        }
        case "definiteness" -> {
          if (!"definite".equals(value)) {
            throw new IllegalArgumentException("Unsupported Turkish slot definiteness: " + value);
          }
        }
        default -> throw new IllegalArgumentException("Unsupported Turkish slot attribute");
      }
    }
  }

  private void validateProvenance(TurkishSuffixPackSurvey survey) {
    if (survey.sources().isEmpty()) {
      throw new IllegalArgumentException("Turkish suffix survey must include sources");
    }
    if (survey.provenance().sourceLabels().size() != survey.sources().size()) {
      throw new IllegalArgumentException(
          "Turkish provenance source label count must match sources");
    }
    List<String> sourcePaths = survey.sources().stream().map(Source::path).toList();
    if (!sourcePaths.equals(survey.provenance().sourceLabels())) {
      throw new IllegalArgumentException(
          "Turkish provenance source labels must match source paths");
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
    if (counts.nounInflectionalPatterns() > counts.inflectionalPatterns()) {
      throw new IllegalArgumentException("Noun patterns exceed inflectional pattern count");
    }
    if (counts.missingInflectionPatterns() > counts.dictionaryInflectionPatterns()) {
      throw new IllegalArgumentException("Missing patterns exceed dictionary pattern count");
    }
    if (counts.defaultInflectionEntriesCoveredBySupplemental()
        > counts.defaultInflectionEntries()) {
      throw new IllegalArgumentException(
          "Default supplemental coverage exceeds default inflection entries");
    }
    if (counts.supportedSurfacesCoveredBySupplemental() > counts.supportedEntries()) {
      throw new IllegalArgumentException("Supplemental coverage exceeds supported entries");
    }
    if (counts.supportedSurfacesCoveredBySupplemental() + counts.supplementalOnlySurfaces()
        != counts.supplementalEntries()) {
      throw new IllegalArgumentException("Turkish supplemental coverage does not close");
    }
    if (samples.defaultInflectionCoveredBySupplemental().size()
        > counts.defaultInflectionEntriesCoveredBySupplemental()) {
      throw new IllegalArgumentException("Covered default samples exceed reported count");
    }
    if (samples.defaultInflectionMissingSupplemental().size()
        > counts.defaultInflectionEntries()
            - counts.defaultInflectionEntriesCoveredBySupplemental()) {
      throw new IllegalArgumentException("Missing default samples exceed reported count");
    }
    if (samples.explicitInflectionEntries().size() > counts.explicitInflectionEntries()) {
      throw new IllegalArgumentException("Explicit inflection samples exceed reported count");
    }
    if (samples.supportedWithoutInflection().size() > counts.supportedWithoutInflection()) {
      throw new IllegalArgumentException("No-inflection samples exceed reported count");
    }
    if (samples.supplementalOnlySurfaces().size() > counts.supplementalOnlySurfaces()) {
      throw new IllegalArgumentException("Supplemental-only samples exceed reported count");
    }
    if (samples.inflectionPatterns().size() > counts.usedInflectionalPatterns()) {
      throw new IllegalArgumentException("Pattern samples exceed used pattern count");
    }
    if (samples.missingInflectionPatterns().size() > counts.missingInflectionPatterns()) {
      throw new IllegalArgumentException("Missing pattern samples exceed reported count");
    }
    if (counts.missingInflectionPatterns() == 0 && !samples.missingInflectionPatterns().isEmpty()) {
      throw new IllegalArgumentException("Missing-pattern samples present when count is zero");
    }
  }

  private void validateFeatureArithmetic(Counts counts, Features features, PackShape packShape) {
    if (features.inflectionPatterns().size() != counts.dictionaryInflectionPatterns()) {
      throw new IllegalArgumentException("Turkish inflection pattern feature count mismatch");
    }
    if (sumValues(features.patternSlotCases()) != features.templateRows()
        || sumValues(features.patternSlotNumbers()) != features.templateRows()) {
      throw new IllegalArgumentException("Turkish pattern slot counts do not match template rows");
    }
    if (weightedNumericKeySum(features.slotsPerPattern()) != features.templateRows()) {
      throw new IllegalArgumentException("Turkish slots-per-pattern counts do not match templates");
    }
    if (packShape.explicitTemplateRows() != features.templateRows()) {
      throw new IllegalArgumentException("Turkish explicit template rows do not match features");
    }
  }

  private void validatePackShape(Counts counts, Features features, PackShape packShape) {
    if (!EXPECTED_RECOMMENDATION.equals(packShape.recommendation())) {
      throw new IllegalArgumentException("Unexpected Turkish pack recommendation");
    }
    if (!EXPECTED_METADATA_BITS.equals(packShape.metadataBits())) {
      throw new IllegalArgumentException("Unexpected Turkish metadata bits");
    }
    if (packShape.supplementalExceptionRows() != counts.supplementalEntries()) {
      throw new IllegalArgumentException("Turkish supplemental row count does not match counts");
    }
    if (packShape.supplementalRowBytes() != packShape.supplementalExceptionRows() * 8) {
      throw new IllegalArgumentException("Turkish supplemental row byte estimate is incoherent");
    }
    if (packShape.supplementalMetadataLowerBoundBytes()
        != packShape.supplementalStringPoolBytes() + packShape.supplementalRowBytes()) {
      throw new IllegalArgumentException("Turkish supplemental lower-bound estimate is incoherent");
    }
    if (packShape.explicitTemplateRowBytes() != features.templateRows() * 12) {
      throw new IllegalArgumentException("Turkish explicit template byte estimate is incoherent");
    }
    if (packShape.explicitTemplateBaseCandidateTerms() > counts.explicitInflectionEntries()) {
      throw new IllegalArgumentException(
          "Turkish explicit-template candidates exceed explicit inflection entries");
    }
    if (packShape.explicitTemplateCompiledTermRowBytes()
        != packShape.explicitTemplateBaseCandidateTerms() * 20) {
      throw new IllegalArgumentException(
          "Turkish explicit-template term-row estimate is incoherent");
    }
    if (packShape.explicitTemplateCompiledFormRowBytes()
        != packShape.explicitTemplateCompiledFormRows() * 12) {
      throw new IllegalArgumentException(
          "Turkish explicit-template form-row estimate is incoherent");
    }
    if (packShape.explicitTemplateCompiledLowerBoundBytes()
        != packShape.explicitTemplateCompiledStringPoolBytes()
            + packShape.explicitTemplateCompiledTermRowBytes()
            + packShape.explicitTemplateCompiledFormRowBytes()) {
      throw new IllegalArgumentException(
          "Turkish explicit-template lower-bound estimate is incoherent");
    }
    if (packShape.explicitTemplateBaseCandidateTerms() > 0
        && packShape.explicitTemplateCompiledFormRows()
            < packShape.explicitTemplateBaseCandidateTerms()) {
      throw new IllegalArgumentException(
          "Turkish explicit-template compiled forms do not cover candidate terms");
    }
    if (packShape.explicitTemplateCompiledJsonBytes()
        < packShape.explicitTemplateCompiledLowerBoundBytes()) {
      throw new IllegalArgumentException(
          "Turkish explicit-template JSON estimate is smaller than binary lower bound");
    }
  }

  private void validateCompositionPolicy(
      Counts counts, Features features, CompositionPolicy compositionPolicy) {
    if (!"1".equals(compositionPolicy.ruleSafeInflection())) {
      throw new IllegalArgumentException("Unexpected Turkish rule-safe inflection");
    }
    if (!EXPECTED_RENDERER_SCOPE.equals(compositionPolicy.rendererScope())) {
      throw new IllegalArgumentException("Unexpected Turkish renderer scope");
    }
    if (!EXPECTED_MUTATION_STRATEGY.equals(compositionPolicy.mutationStrategy())) {
      throw new IllegalArgumentException("Unexpected Turkish mutation strategy");
    }
    if (!EXPECTED_EXPLICIT_REVIEW_FLAGS.equals(compositionPolicy.requiresExplicitFormFlags())) {
      throw new IllegalArgumentException("Unexpected Turkish explicit-review flags");
    }
    if (compositionPolicy.supplementalRowsRequiringExplicitReview()
        > counts.supplementalEntries()) {
      throw new IllegalArgumentException(
          "Turkish explicit-review supplemental rows exceed supplemental entries");
    }
    if (compositionPolicy.supplementalRowsRequiringExplicitReview()
        < features.supplementalFlags().getOrDefault("foreign", 0)) {
      throw new IllegalArgumentException(
          "Turkish explicit-review rows do not cover foreign supplemental entries");
    }
    if (compositionPolicy.caseTemplateRows()
        != compositionPolicy.emptySuffixCaseTemplateRows()
            + compositionPolicy.suffixPreservingCaseTemplateRows()
            + compositionPolicy.consonantMutationTemplateRows()) {
      throw new IllegalArgumentException("Turkish case-template row policy does not close");
    }
    if (compositionPolicy.consonantMutationTemplateRows() <= 0
        || compositionPolicy.consonantMutationPatterns() <= 0) {
      throw new IllegalArgumentException("Turkish mutation policy must include mutation templates");
    }
    if (compositionPolicy.consonantMutationTemplateRows() > compositionPolicy.caseTemplateRows()) {
      throw new IllegalArgumentException("Turkish mutation rows exceed case template rows");
    }
    if (compositionPolicy.caseTemplateRowBytes() != compositionPolicy.caseTemplateRows() * 12) {
      throw new IllegalArgumentException("Turkish case-template byte estimate is incoherent");
    }
    if (compositionPolicy.consonantMutationTemplateRowBytes()
        != compositionPolicy.consonantMutationTemplateRows() * 12) {
      throw new IllegalArgumentException("Turkish mutation byte estimate is incoherent");
    }
    if (compositionPolicy.pluralTemplateRowBytes() != compositionPolicy.pluralTemplateRows() * 12) {
      throw new IllegalArgumentException("Turkish plural-template byte estimate is incoherent");
    }
    if (compositionPolicy.caseTemplateRows() > features.templateRows()
        || compositionPolicy.pluralTemplateRows() > features.templateRows()) {
      throw new IllegalArgumentException("Turkish composition policy exceeds template rows");
    }
  }

  private void validateCompositionSamples(CompositionPolicy compositionPolicy, Samples samples) {
    if (samples.consonantMutationTemplates().size()
        > compositionPolicy.consonantMutationTemplateRows()) {
      throw new IllegalArgumentException("Turkish mutation samples exceed mutation row count");
    }
    if (compositionPolicy.consonantMutationTemplateRows() > 0
        && samples.consonantMutationTemplates().isEmpty()) {
      throw new IllegalArgumentException("Turkish mutation policy requires mutation samples");
    }
    for (MutationTemplateSample sample : samples.consonantMutationTemplates()) {
      if (!sample.attrs().containsKey("case")) {
        throw new IllegalArgumentException("Turkish mutation sample must include a case");
      }
      if (sample.template().startsWith("{stem}" + sample.suffix())) {
        throw new IllegalArgumentException(
            "Turkish mutation sample must not preserve the original suffix");
      }
    }
  }

  private Map<String, Integer> featureMap(JsonNode node, String field, Set<String> allowedKeys) {
    Map<String, Integer> values = new LinkedHashMap<>();
    for (var fields = node.fields(); fields.hasNext(); ) {
      Map.Entry<String, JsonNode> entry = fields.next();
      String key = entry.getKey();
      if (allowedKeys != null && !allowedKeys.contains(key)) {
        throw new IllegalArgumentException("Unsupported Turkish " + field + " key: " + key);
      }
      int value = requiredIntValue(entry.getValue(), field + "." + key);
      if (value < 0) {
        throw new IllegalArgumentException("Feature count must be non-negative for " + field);
      }
      values.put(key, value);
    }
    return unmodifiableLinkedMap(values);
  }

  private Map<String, Integer> supplementalCombinationMap(JsonNode node) {
    Map<String, Integer> values = new LinkedHashMap<>();
    for (var fields = node.fields(); fields.hasNext(); ) {
      Map.Entry<String, JsonNode> entry = fields.next();
      String combination = entry.getKey();
      for (String flag : combination.split(" ")) {
        if (!SUPPLEMENTAL_FLAG_VALUES.contains(flag)) {
          throw new IllegalArgumentException(
              "Unsupported Turkish supplemental combination: " + combination);
        }
      }
      int value = requiredIntValue(entry.getValue(), "supplementalCombinations." + combination);
      if (value < 0) {
        throw new IllegalArgumentException(
            "Feature count must be non-negative for supplementalCombinations");
      }
      values.put(combination, value);
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
        throw new IllegalArgumentException("Expected numeric Turkish " + field + " key", e);
      }
      if (key < minKey || key > maxKey) {
        throw new IllegalArgumentException("Turkish " + field + " key out of range: " + key);
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

  private String requiredTextAllowEmpty(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual()) {
      throw new IllegalArgumentException("Expected text field: " + field);
    }
    return value.asText();
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

  public record TurkishSuffixPackSurvey(
      String schema,
      String locale,
      List<Source> sources,
      Provenance provenance,
      Counts counts,
      Features features,
      PackShape packShape,
      CompositionPolicy compositionPolicy,
      Samples samples) {

    public TurkishSuffixPackSurvey {
      schema = requireExpected(schema, "schema", EXPECTED_SCHEMA);
      locale = requireExpected(locale, "locale", EXPECTED_LOCALE);
      sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
      provenance = Objects.requireNonNull(provenance, "provenance");
      counts = Objects.requireNonNull(counts, "counts");
      features = Objects.requireNonNull(features, "features");
      packShape = Objects.requireNonNull(packShape, "packShape");
      compositionPolicy = Objects.requireNonNull(compositionPolicy, "compositionPolicy");
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
      int dictionarySkippedLines,
      int supportedEntries,
      int uniqueSupportedSurfaces,
      int supportedWithoutInflection,
      int defaultInflectionEntries,
      int explicitInflectionEntries,
      int dictionaryInflectionPatterns,
      int missingInflectionPatterns,
      int inflectionalPatterns,
      int nounInflectionalPatterns,
      int usedInflectionalPatterns,
      int supplementalEntries,
      int supplementalSkippedLines,
      int supportedSurfacesCoveredBySupplemental,
      int defaultInflectionEntriesCoveredBySupplemental,
      int supplementalOnlySurfaces) {

    public Counts {
      requireNonNegative(dictionaryEntries, "dictionaryEntries");
      requireNonNegative(dictionarySkippedLines, "dictionarySkippedLines");
      requireNonNegative(supportedEntries, "supportedEntries");
      requireNonNegative(uniqueSupportedSurfaces, "uniqueSupportedSurfaces");
      requireNonNegative(supportedWithoutInflection, "supportedWithoutInflection");
      requireNonNegative(defaultInflectionEntries, "defaultInflectionEntries");
      requireNonNegative(explicitInflectionEntries, "explicitInflectionEntries");
      requireNonNegative(dictionaryInflectionPatterns, "dictionaryInflectionPatterns");
      requireNonNegative(missingInflectionPatterns, "missingInflectionPatterns");
      requireNonNegative(inflectionalPatterns, "inflectionalPatterns");
      requireNonNegative(nounInflectionalPatterns, "nounInflectionalPatterns");
      requireNonNegative(usedInflectionalPatterns, "usedInflectionalPatterns");
      requireNonNegative(supplementalEntries, "supplementalEntries");
      requireNonNegative(supplementalSkippedLines, "supplementalSkippedLines");
      requireNonNegative(
          supportedSurfacesCoveredBySupplemental, "supportedSurfacesCoveredBySupplemental");
      requireNonNegative(
          defaultInflectionEntriesCoveredBySupplemental,
          "defaultInflectionEntriesCoveredBySupplemental");
      requireNonNegative(supplementalOnlySurfaces, "supplementalOnlySurfaces");
    }
  }

  public record Features(
      Map<String, Integer> grammaticalCase,
      Map<String, Integer> number,
      Map<String, Integer> partOfSpeech,
      Map<String, Integer> patternSlotCases,
      Map<String, Integer> patternSlotNumbers,
      Map<String, Integer> patternSlotOtherAttributes,
      Map<String, Integer> supplementalFlags,
      Map<String, Integer> supplementalCombinations,
      Map<String, Integer> inflectionPatterns,
      Map<String, Integer> slotsPerPattern,
      int templateRows) {

    public Features {
      grammaticalCase = unmodifiableLinkedMap(Objects.requireNonNull(grammaticalCase, "case"));
      number = unmodifiableLinkedMap(Objects.requireNonNull(number, "number"));
      partOfSpeech = unmodifiableLinkedMap(Objects.requireNonNull(partOfSpeech, "partOfSpeech"));
      patternSlotCases =
          unmodifiableLinkedMap(Objects.requireNonNull(patternSlotCases, "patternSlotCases"));
      patternSlotNumbers =
          unmodifiableLinkedMap(Objects.requireNonNull(patternSlotNumbers, "patternSlotNumbers"));
      patternSlotOtherAttributes =
          unmodifiableLinkedMap(
              Objects.requireNonNull(patternSlotOtherAttributes, "patternSlotOtherAttributes"));
      supplementalFlags =
          unmodifiableLinkedMap(Objects.requireNonNull(supplementalFlags, "supplementalFlags"));
      supplementalCombinations =
          unmodifiableLinkedMap(
              Objects.requireNonNull(supplementalCombinations, "supplementalCombinations"));
      inflectionPatterns =
          unmodifiableLinkedMap(Objects.requireNonNull(inflectionPatterns, "inflectionPatterns"));
      slotsPerPattern =
          unmodifiableLinkedMap(Objects.requireNonNull(slotsPerPattern, "slotsPerPattern"));
      requireNonNegative(templateRows, "templateRows");
    }
  }

  public record PackShape(
      String recommendation,
      String reason,
      List<String> metadataBits,
      int supplementalExceptionRows,
      int supplementalStringPoolBytes,
      int supplementalRowBytes,
      int supplementalMetadataLowerBoundBytes,
      int explicitTemplateRows,
      int explicitTemplateRowBytes,
      int explicitTemplateBaseCandidateTerms,
      int explicitTemplateCompiledFormRows,
      int explicitTemplateCompiledStringPoolBytes,
      int explicitTemplateCompiledTermRowBytes,
      int explicitTemplateCompiledFormRowBytes,
      int explicitTemplateCompiledLowerBoundBytes,
      int explicitTemplateCompiledJsonBytes) {

    public PackShape {
      recommendation = requireText(recommendation, "recommendation");
      reason = requireText(reason, "reason");
      metadataBits = requireStringList(metadataBits, "metadataBits", null);
      requireNonNegative(supplementalExceptionRows, "supplementalExceptionRows");
      requireNonNegative(supplementalStringPoolBytes, "supplementalStringPoolBytes");
      requireNonNegative(supplementalRowBytes, "supplementalRowBytes");
      requireNonNegative(
          supplementalMetadataLowerBoundBytes, "supplementalMetadataLowerBoundBytes");
      requireNonNegative(explicitTemplateRows, "explicitTemplateRows");
      requireNonNegative(explicitTemplateRowBytes, "explicitTemplateRowBytes");
      requireNonNegative(explicitTemplateBaseCandidateTerms, "explicitTemplateBaseCandidateTerms");
      requireNonNegative(explicitTemplateCompiledFormRows, "explicitTemplateCompiledFormRows");
      requireNonNegative(
          explicitTemplateCompiledStringPoolBytes, "explicitTemplateCompiledStringPoolBytes");
      requireNonNegative(
          explicitTemplateCompiledTermRowBytes, "explicitTemplateCompiledTermRowBytes");
      requireNonNegative(
          explicitTemplateCompiledFormRowBytes, "explicitTemplateCompiledFormRowBytes");
      requireNonNegative(
          explicitTemplateCompiledLowerBoundBytes, "explicitTemplateCompiledLowerBoundBytes");
      requireNonNegative(explicitTemplateCompiledJsonBytes, "explicitTemplateCompiledJsonBytes");
    }
  }

  public record CompositionPolicy(
      String ruleSafeInflection,
      List<String> rendererScope,
      String mutationStrategy,
      String mutationStrategyReason,
      List<String> requiresExplicitFormFlags,
      int supplementalRowsRequiringExplicitReview,
      int caseTemplatePatterns,
      int caseTemplateRows,
      int caseTemplateRowBytes,
      int emptySuffixCaseTemplateRows,
      int suffixPreservingCaseTemplateRows,
      int consonantMutationPatterns,
      int consonantMutationTemplateRows,
      int consonantMutationTemplateRowBytes,
      int pluralTemplateRows,
      int pluralTemplateRowBytes) {

    public CompositionPolicy {
      ruleSafeInflection = requireText(ruleSafeInflection, "ruleSafeInflection");
      rendererScope = requireStringList(rendererScope, "rendererScope", null);
      mutationStrategy = requireText(mutationStrategy, "mutationStrategy");
      mutationStrategyReason = requireText(mutationStrategyReason, "mutationStrategyReason");
      requiresExplicitFormFlags =
          requireStringList(
              requiresExplicitFormFlags, "requiresExplicitFormFlags", SUPPLEMENTAL_FLAG_VALUES);
      requireNonNegative(
          supplementalRowsRequiringExplicitReview, "supplementalRowsRequiringExplicitReview");
      requireNonNegative(caseTemplatePatterns, "caseTemplatePatterns");
      requireNonNegative(caseTemplateRows, "caseTemplateRows");
      requireNonNegative(caseTemplateRowBytes, "caseTemplateRowBytes");
      requireNonNegative(emptySuffixCaseTemplateRows, "emptySuffixCaseTemplateRows");
      requireNonNegative(suffixPreservingCaseTemplateRows, "suffixPreservingCaseTemplateRows");
      requireNonNegative(consonantMutationPatterns, "consonantMutationPatterns");
      requireNonNegative(consonantMutationTemplateRows, "consonantMutationTemplateRows");
      requireNonNegative(consonantMutationTemplateRowBytes, "consonantMutationTemplateRowBytes");
      requireNonNegative(pluralTemplateRows, "pluralTemplateRows");
      requireNonNegative(pluralTemplateRowBytes, "pluralTemplateRowBytes");
    }
  }

  public record Samples(
      List<EntrySample> defaultInflectionCoveredBySupplemental,
      List<EntrySample> defaultInflectionMissingSupplemental,
      List<EntrySample> explicitInflectionEntries,
      List<EntrySample> supportedWithoutInflection,
      List<SupplementalSurfaceSample> supplementalOnlySurfaces,
      List<InflectionPatternSample> inflectionPatterns,
      List<MutationTemplateSample> consonantMutationTemplates,
      List<String> missingInflectionPatterns) {

    public Samples {
      defaultInflectionCoveredBySupplemental =
          List.copyOf(
              Objects.requireNonNull(
                  defaultInflectionCoveredBySupplemental,
                  "defaultInflectionCoveredBySupplemental"));
      defaultInflectionMissingSupplemental =
          List.copyOf(
              Objects.requireNonNull(
                  defaultInflectionMissingSupplemental, "defaultInflectionMissingSupplemental"));
      explicitInflectionEntries =
          List.copyOf(
              Objects.requireNonNull(explicitInflectionEntries, "explicitInflectionEntries"));
      supportedWithoutInflection =
          List.copyOf(
              Objects.requireNonNull(supportedWithoutInflection, "supportedWithoutInflection"));
      supplementalOnlySurfaces =
          List.copyOf(Objects.requireNonNull(supplementalOnlySurfaces, "supplementalOnlySurfaces"));
      inflectionPatterns =
          List.copyOf(Objects.requireNonNull(inflectionPatterns, "inflectionPatterns"));
      consonantMutationTemplates =
          List.copyOf(
              Objects.requireNonNull(consonantMutationTemplates, "consonantMutationTemplates"));
      missingInflectionPatterns =
          requireStringList(missingInflectionPatterns, "missingInflectionPatterns", null);
    }
  }

  public record EntrySample(
      String surface,
      int line,
      List<String> partOfSpeech,
      List<String> cases,
      List<String> number,
      List<String> inflections,
      List<String> supplementalFlags) {

    public EntrySample {
      surface = requireText(surface, "surface");
      if (line <= 0) {
        throw new IllegalArgumentException("line must be positive");
      }
      partOfSpeech = requireStringList(partOfSpeech, "partOfSpeech", PART_OF_SPEECH_VALUES);
      cases = requireStringList(cases, "case", CASE_VALUES);
      number = requireStringList(number, "number", NUMBER_VALUES);
      inflections = requireStringList(inflections, "inflections", null);
      supplementalFlags =
          requireStringList(supplementalFlags, "supplementalFlags", SUPPLEMENTAL_FLAG_VALUES);
    }
  }

  public record SupplementalSurfaceSample(String surface, List<String> flags) {

    public SupplementalSurfaceSample {
      surface = requireText(surface, "surface");
      flags = requireStringList(flags, "flags", SUPPLEMENTAL_FLAG_VALUES);
    }
  }

  public record InflectionPatternSample(
      String name,
      List<String> partOfSpeech,
      String suffix,
      int words,
      List<PatternSlotSample> slots) {

    public InflectionPatternSample {
      name = requireText(name, "name");
      partOfSpeech = requireStringList(partOfSpeech, "partOfSpeech", PART_OF_SPEECH_VALUES);
      suffix = Objects.requireNonNull(suffix, "suffix");
      requireNonNegative(words, "words");
      slots = List.copyOf(Objects.requireNonNull(slots, "slots"));
    }
  }

  public record PatternSlotSample(Map<String, String> attrs, String template) {

    public PatternSlotSample {
      attrs = unmodifiableLinkedMap(Objects.requireNonNull(attrs, "attrs"));
      template = requireText(template, "template");
    }
  }

  public record MutationTemplateSample(
      String pattern,
      String suffix,
      Map<String, String> attrs,
      String template,
      List<String> surfaces) {

    public MutationTemplateSample {
      pattern = requireText(pattern, "pattern");
      suffix = requireText(suffix, "suffix");
      attrs = unmodifiableLinkedMap(Objects.requireNonNull(attrs, "attrs"));
      template = requireText(template, "template");
      surfaces = requireStringList(surfaces, "surfaces", null);
    }
  }

  private static List<String> requireStringList(
      List<String> values, String field, Set<String> allowedValues) {
    List<String> copied = new ArrayList<>();
    for (String value : Objects.requireNonNull(values, field)) {
      value = requireText(value, field);
      if (allowedValues != null && !allowedValues.contains(value)) {
        throw new IllegalArgumentException("Unsupported Turkish " + field + " value: " + value);
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
