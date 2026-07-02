package com.box.l10n.mojito.mf2.inflection;

import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.integerMap;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.requireText;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.requiredArray;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.requiredBoolean;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.requiredInt;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.requiredLong;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.requiredObject;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.requiredStringIndex;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.requiredText;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.textArray;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.unmodifiableLinkedMap;

import com.box.l10n.mojito.json.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Loads generated Dutch noun metadata. The pack validates gender, number, and diminutive metadata
 * and intentionally does not model broad Dutch noun case or definiteness rendering.
 */
@GeneratorSupport
class DutchNounMetadataPackJsonLoader {

  static final String EXPECTED_SCHEMA = "mojito-mf2-inflection/nl-noun-metadata-pack/v0";
  static final String EXPECTED_LOCALE = "nl";

  private static final int ROW_WIDTH_BYTES = 12;
  private static final int DIMINUTIVE_BIT = 1 << 12;

  private static final Map<String, Integer> PART_OF_SPEECH_BITS =
      Map.of("noun", 1, "proper-noun", 2);
  private static final Map<String, Integer> GENDER_BITS =
      Map.of("masculine", 1 << 4, "feminine", 1 << 5, "neuter", 1 << 6);
  private static final Map<String, Integer> NUMBER_BITS =
      Map.of("singular", 1 << 8, "plural", 1 << 9);
  private static final Set<String> REVIEW_DIAGNOSTICS =
      Set.of(
          "missing-part-of-speech",
          "multiple-parts-of-speech",
          "missing-gender",
          "multiple-genders",
          "missing-number",
          "multiple-numbers",
          "missing-inflection",
          "multiple-inflections");

  private final ObjectMapper objectMapper;

  DutchNounMetadataPackJsonLoader() {
    this(new ObjectMapper());
  }

  DutchNounMetadataPackJsonLoader(ObjectMapper objectMapper) {
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
  }

  public DutchNounMetadataPack load(String json) {
    Objects.requireNonNull(json, "json");
    return load(objectMapper.readTreeUnchecked(json));
  }

  public DutchNounMetadataPack load(JsonNode root) {
    String schema = requiredText(root, "schema");
    if (!EXPECTED_SCHEMA.equals(schema)) {
      throw new IllegalArgumentException("Expected Dutch metadata schema");
    }
    String locale = requiredText(root, "locale");
    if (!EXPECTED_LOCALE.equals(locale)) {
      throw new IllegalArgumentException("Expected Dutch locale");
    }

    List<String> strings = loadStrings(requiredArray(root, "strings"));
    List<NounMetadataRow> rows = new ArrayList<>();
    Map<String, NounMetadataRow> rowsBySurface = new LinkedHashMap<>();
    for (JsonNode rowNode : requiredArray(root, "rows")) {
      int surfaceIndex = requiredStringIndex(rowNode, "surface", strings);
      String surface = strings.get(surfaceIndex);
      NounMetadataRow row =
          new NounMetadataRow(
              surfaceIndex,
              surface,
              requiredInt(rowNode, "sourceRow"),
              requiredInt(rowNode, "featureBits"),
              featureValues(rowNode, "partOfSpeech", PART_OF_SPEECH_BITS.keySet()),
              featureValues(rowNode, "gender", GENDER_BITS.keySet()),
              featureValues(rowNode, "number", NUMBER_BITS.keySet()),
              requiredBoolean(rowNode, "diminutive"),
              textArray(
                  requiredArray(rowNode, "inflectionPatterns"),
                  "inflectionPatterns",
                  null,
                  "Dutch"),
              textArray(
                  requiredArray(rowNode, "reviewDiagnostics"),
                  "reviewDiagnostics",
                  REVIEW_DIAGNOSTICS,
                  "Dutch"));
      validateFeatureBits(row);
      if (rowsBySurface.put(surface, row) != null) {
        throw new IllegalArgumentException("Duplicate Dutch metadata surface: " + surface);
      }
      rows.add(row);
    }

    DutchNounMetadataPack pack =
        new DutchNounMetadataPack(
            schema,
            locale,
            loadProvenance(requiredObject(root, "provenance")),
            strings,
            rows,
            rowsBySurface,
            loadGenerationSummary(requiredObject(root, "generationSummary")),
            loadFeatures(requiredObject(root, "features")),
            loadSizeEstimates(requiredObject(root, "sizeEstimates")));
    validateSummary(pack);
    validateSizeEstimates(pack);
    validateProvenance(pack.provenance());
    return pack;
  }

  private List<String> loadStrings(JsonNode node) {
    return textArray(node, "strings", null, "Dutch");
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
        textArray(requiredArray(node, "sourceLabels"), "sourceLabels", null, "Dutch"),
        List.copyOf(sources));
  }

  private GenerationSummary loadGenerationSummary(JsonNode node) {
    return new GenerationSummary(
        requiredInt(node, "dictionaryEntries"),
        requiredInt(node, "skippedDictionaryLines"),
        requiredInt(node, "termRows"),
        requiredInt(node, "metadataCandidateRows"),
        requiredInt(node, "metadataCandidateSurfaces"),
        requiredInt(node, "diminutiveRows"),
        requiredInt(node, "metadataDiminutiveRows"),
        requiredInt(node, "exportedRows"),
        requiredInt(node, "exportedDiminutiveRows"),
        requiredInt(node, "reviewDiagnosticRows"),
        requiredInt(node, "caseTaggedTermRows"),
        requiredInt(node, "definitenessTaggedTermRows"),
        requiredInt(node, "multiGenderRows"),
        requiredInt(node, "multiNumberRows"));
  }

  private Features loadFeatures(JsonNode node) {
    return new Features(
        featureMap(
            requiredObject(node, "partOfSpeech"), "partOfSpeech", PART_OF_SPEECH_BITS.keySet()),
        featureMap(requiredObject(node, "gender"), "gender", GENDER_BITS.keySet()),
        featureMap(requiredObject(node, "number"), "number", NUMBER_BITS.keySet()),
        featureMap(requiredObject(node, "diminutive"), "diminutive", Set.of("false", "true")));
  }

  private SizeEstimates loadSizeEstimates(JsonNode node) {
    return new SizeEstimates(
        loadMetadataPackEstimate(requiredObject(node, "sampleMetadataPack"), true),
        loadMetadataPackEstimate(requiredObject(node, "fullMetadataPack"), false));
  }

  private MetadataPackEstimate loadMetadataPackEstimate(JsonNode node, boolean hasJsonBytes) {
    return new MetadataPackEstimate(
        requiredInt(node, "stringPoolBytes"),
        requiredInt(node, "rowBytes"),
        requiredInt(node, "binaryLowerBoundBytes"),
        hasJsonBytes ? requiredInt(node, "jsonBytes") : null);
  }

  private Map<String, Integer> featureMap(JsonNode node, String field, Set<String> allowedKeys) {
    return integerMap(node, field, allowedKeys, "Dutch");
  }

  private List<String> featureValues(JsonNode node, String field, Set<String> allowedValues) {
    return textArray(requiredArray(node, field), field, allowedValues, "Dutch");
  }

  private void validateFeatureBits(NounMetadataRow row) {
    int expected = 0;
    expected |= expectedBits(row.partOfSpeech(), PART_OF_SPEECH_BITS);
    expected |= expectedBits(row.gender(), GENDER_BITS);
    expected |= expectedBits(row.number(), NUMBER_BITS);
    if (row.diminutive()) {
      expected |= DIMINUTIVE_BIT;
    }
    if (row.featureBits() != expected) {
      throw new IllegalArgumentException(
          "Feature bits do not match Dutch metadata for surface: " + row.surface());
    }
  }

  private int expectedBits(List<String> values, Map<String, Integer> bitValues) {
    int bits = 0;
    for (String value : values) {
      bits |= bitValues.get(value);
    }
    return bits;
  }

  private void validateSummary(DutchNounMetadataPack pack) {
    GenerationSummary summary = pack.generationSummary();
    if (summary.exportedRows() != pack.rows().size()) {
      throw new IllegalArgumentException("Dutch exported row count does not match rows");
    }
    long rowsWithDiagnostics =
        pack.rows().stream().filter(row -> !row.reviewDiagnostics().isEmpty()).count();
    if (summary.reviewDiagnosticRows() != rowsWithDiagnostics) {
      throw new IllegalArgumentException("Dutch review diagnostic count does not match rows");
    }
    long exportedDiminutiveRows = pack.rows().stream().filter(NounMetadataRow::diminutive).count();
    if (summary.exportedDiminutiveRows() != exportedDiminutiveRows) {
      throw new IllegalArgumentException("Dutch exported diminutive count does not match rows");
    }
    if (summary.metadataCandidateRows() < summary.exportedRows()) {
      throw new IllegalArgumentException("Dutch metadata candidates must cover exported rows");
    }
    if (summary.metadataDiminutiveRows() > summary.metadataCandidateRows()) {
      throw new IllegalArgumentException(
          "Dutch metadata diminutive rows cannot exceed metadata candidates");
    }
    if (summary.caseTaggedTermRows() > summary.termRows()
        || summary.definitenessTaggedTermRows() > summary.termRows()) {
      throw new IllegalArgumentException("Dutch case/definiteness rows cannot exceed term rows");
    }
  }

  private void validateSizeEstimates(DutchNounMetadataPack pack) {
    MetadataPackEstimate sample = pack.sizeEstimates().sampleMetadataPack();
    int stringPoolBytes =
        pack.strings().stream()
            .mapToInt(value -> value.getBytes(StandardCharsets.UTF_8).length + 1)
            .sum();
    if (sample.stringPoolBytes() != stringPoolBytes) {
      throw new IllegalArgumentException("Dutch string-pool byte estimate does not match strings");
    }
    if (sample.rowBytes() != pack.rows().size() * ROW_WIDTH_BYTES) {
      throw new IllegalArgumentException("Dutch sample row byte estimate does not match rows");
    }
    if (sample.binaryLowerBoundBytes() != sample.stringPoolBytes() + sample.rowBytes()) {
      throw new IllegalArgumentException("Dutch sample binary estimate does not match parts");
    }

    MetadataPackEstimate full = pack.sizeEstimates().fullMetadataPack();
    if (full.rowBytes() != pack.generationSummary().metadataCandidateRows() * ROW_WIDTH_BYTES) {
      throw new IllegalArgumentException("Dutch full row byte estimate does not match candidates");
    }
    if (full.binaryLowerBoundBytes() != full.stringPoolBytes() + full.rowBytes()) {
      throw new IllegalArgumentException("Dutch full binary estimate does not match parts");
    }
  }

  private void validateProvenance(Provenance provenance) {
    if (provenance.sourceLabels().size() != provenance.sources().size()) {
      throw new IllegalArgumentException("Dutch source labels must match sources");
    }
    for (int i = 0; i < provenance.sources().size(); i++) {
      if (!provenance.sourceLabels().get(i).equals(provenance.sources().get(i).path())) {
        throw new IllegalArgumentException("Dutch source labels must match source paths");
      }
    }
  }

  public record DutchNounMetadataPack(
      String schema,
      String locale,
      Provenance provenance,
      List<String> strings,
      List<NounMetadataRow> rows,
      Map<String, NounMetadataRow> rowsBySurface,
      GenerationSummary generationSummary,
      Features features,
      SizeEstimates sizeEstimates) {

    public DutchNounMetadataPack {
      schema = requireText(schema, "schema");
      locale = requireText(locale, "locale");
      provenance = Objects.requireNonNull(provenance, "provenance");
      strings = List.copyOf(Objects.requireNonNull(strings, "strings"));
      rows = List.copyOf(Objects.requireNonNull(rows, "rows"));
      rowsBySurface = unmodifiableLinkedMap(Objects.requireNonNull(rowsBySurface, "rowsBySurface"));
      generationSummary = Objects.requireNonNull(generationSummary, "generationSummary");
      features = Objects.requireNonNull(features, "features");
      sizeEstimates = Objects.requireNonNull(sizeEstimates, "sizeEstimates");
    }

    public Optional<NounMetadataRow> find(String surface) {
      return Optional.ofNullable(rowsBySurface.get(surface));
    }
  }

  public record NounMetadataRow(
      int surfaceIndex,
      String surface,
      int sourceRow,
      int featureBits,
      List<String> partOfSpeech,
      List<String> gender,
      List<String> number,
      boolean diminutive,
      List<String> inflectionPatterns,
      List<String> reviewDiagnostics) {

    public NounMetadataRow {
      if (surfaceIndex < 0) {
        throw new IllegalArgumentException("surfaceIndex must be non-negative: " + surfaceIndex);
      }
      if (sourceRow <= 0) {
        throw new IllegalArgumentException("sourceRow must be positive: " + sourceRow);
      }
      surface = requireText(surface, "surface");
      partOfSpeech = List.copyOf(Objects.requireNonNull(partOfSpeech, "partOfSpeech"));
      gender = List.copyOf(Objects.requireNonNull(gender, "gender"));
      number = List.copyOf(Objects.requireNonNull(number, "number"));
      inflectionPatterns =
          List.copyOf(Objects.requireNonNull(inflectionPatterns, "inflectionPatterns"));
      reviewDiagnostics =
          List.copyOf(Objects.requireNonNull(reviewDiagnostics, "reviewDiagnostics"));
    }
  }

  public record GenerationSummary(
      int dictionaryEntries,
      int skippedDictionaryLines,
      int termRows,
      int metadataCandidateRows,
      int metadataCandidateSurfaces,
      int diminutiveRows,
      int metadataDiminutiveRows,
      int exportedRows,
      int exportedDiminutiveRows,
      int reviewDiagnosticRows,
      int caseTaggedTermRows,
      int definitenessTaggedTermRows,
      int multiGenderRows,
      int multiNumberRows) {

    public GenerationSummary {
      if (dictionaryEntries < 0
          || skippedDictionaryLines < 0
          || termRows < 0
          || metadataCandidateRows < 0
          || metadataCandidateSurfaces < 0
          || diminutiveRows < 0
          || metadataDiminutiveRows < 0
          || exportedRows < 0
          || exportedDiminutiveRows < 0
          || reviewDiagnosticRows < 0
          || caseTaggedTermRows < 0
          || definitenessTaggedTermRows < 0
          || multiGenderRows < 0
          || multiNumberRows < 0) {
        throw new IllegalArgumentException("generation summary counts must be non-negative");
      }
    }
  }

  public record Features(
      Map<String, Integer> partOfSpeech,
      Map<String, Integer> gender,
      Map<String, Integer> number,
      Map<String, Integer> diminutive) {

    public Features {
      partOfSpeech = unmodifiableLinkedMap(Objects.requireNonNull(partOfSpeech, "partOfSpeech"));
      gender = unmodifiableLinkedMap(Objects.requireNonNull(gender, "gender"));
      number = unmodifiableLinkedMap(Objects.requireNonNull(number, "number"));
      diminutive = unmodifiableLinkedMap(Objects.requireNonNull(diminutive, "diminutive"));
    }
  }

  public record SizeEstimates(
      MetadataPackEstimate sampleMetadataPack, MetadataPackEstimate fullMetadataPack) {

    public SizeEstimates {
      sampleMetadataPack = Objects.requireNonNull(sampleMetadataPack, "sampleMetadataPack");
      fullMetadataPack = Objects.requireNonNull(fullMetadataPack, "fullMetadataPack");
    }
  }

  public record MetadataPackEstimate(
      int stringPoolBytes, int rowBytes, int binaryLowerBoundBytes, Integer jsonBytes) {

    public MetadataPackEstimate {
      if (stringPoolBytes < 0
          || rowBytes < 0
          || binaryLowerBoundBytes < 0
          || (jsonBytes != null && jsonBytes < 0)) {
        throw new IllegalArgumentException("metadata pack estimates must be non-negative");
      }
    }
  }

  public record Provenance(
      String license, String generator, List<String> sourceLabels, List<Source> sources) {

    public Provenance {
      license = requireText(license, "license");
      generator = requireText(generator, "generator");
      sourceLabels = List.copyOf(Objects.requireNonNull(sourceLabels, "sourceLabels"));
      sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
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
}
