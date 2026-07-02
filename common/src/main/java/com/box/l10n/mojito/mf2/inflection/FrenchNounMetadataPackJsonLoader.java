package com.box.l10n.mojito.mf2.inflection;

import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.optionalArray;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.optionalInt;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.optionalObject;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.optionalText;
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
import com.box.l10n.mojito.mf2.inflection.TermRequirementValidator.Morphology;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Loads generated French noun metadata derived from dictionary inputs. This pack is a metadata and
 * diagnostic source for known surfaces; it is separate from closed-world term-form packs used for
 * deterministic rendering.
 */
@GeneratorSupport
class FrenchNounMetadataPackJsonLoader {

  static final int MASCULINE_BIT = 1;
  static final int FEMININE_BIT = 1 << 1;
  static final int SINGULAR_BIT = 1 << 2;
  static final int PLURAL_BIT = 1 << 3;
  static final int VOWEL_START_BIT = 1 << 4;
  static final String EXPECTED_SCHEMA = "mojito-mf2-inflection/fr-noun-metadata-sample-pack/v0";

  private final ObjectMapper objectMapper;

  FrenchNounMetadataPackJsonLoader() {
    this(new ObjectMapper());
  }

  FrenchNounMetadataPackJsonLoader(ObjectMapper objectMapper) {
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
  }

  public FrenchNounMetadataPack load(String json) {
    Objects.requireNonNull(json, "json");
    return load(objectMapper.readTreeUnchecked(json));
  }

  public FrenchNounMetadataPack load(JsonNode root) {
    String schema = requiredText(root, "schema");
    if (!EXPECTED_SCHEMA.equals(schema)) {
      throw new IllegalArgumentException("Expected French noun metadata schema");
    }

    List<String> strings = loadStrings(requiredArray(root, "strings"));
    List<FrenchNounMetadataRow> rows = new ArrayList<>();
    Map<String, FrenchNounMetadataRow> rowsBySurface = new LinkedHashMap<>();
    List<FrenchNounAmbiguityRow> ambiguousRows = new ArrayList<>();
    Map<String, FrenchNounAmbiguityRow> ambiguousRowsBySurface = new LinkedHashMap<>();

    for (JsonNode rowNode : requiredArray(root, "rows")) {
      int surfaceIndex = requiredStringIndex(rowNode, "surface", strings);
      String surface = strings.get(surfaceIndex);
      FrenchNounMetadataRow row =
          new FrenchNounMetadataRow(
              surfaceIndex,
              surface,
              requiredInt(rowNode, "featureBits"),
              requiredText(rowNode, "gender"),
              requiredText(rowNode, "number"),
              requiredBoolean(rowNode, "elides"),
              optionalText(rowNode, "inflectionPattern"));
      validateFeatureBits(row);
      if (rowsBySurface.put(surface, row) != null) {
        throw new IllegalArgumentException("Duplicate noun metadata surface: " + surface);
      }
      rows.add(row);
    }

    JsonNode ambiguousRowsNode = optionalArray(root, "ambiguousRows");
    if (ambiguousRowsNode != null) {
      for (JsonNode ambiguousRowNode : ambiguousRowsNode) {
        int surfaceIndex = requiredStringIndex(ambiguousRowNode, "surface", strings);
        String surface = strings.get(surfaceIndex);
        if (rowsBySurface.containsKey(surface)) {
          throw new IllegalArgumentException(
              "Ambiguous noun metadata surface also has exact row: " + surface);
        }

        List<FrenchNounAmbiguousAnalysis> analyses = new ArrayList<>();
        for (JsonNode analysisNode : requiredArray(ambiguousRowNode, "analyses")) {
          FrenchNounAmbiguousAnalysis analysis =
              new FrenchNounAmbiguousAnalysis(
                  requiredText(analysisNode, "gender"),
                  requiredText(analysisNode, "number"),
                  requiredBoolean(analysisNode, "elides"),
                  optionalText(analysisNode, "inflectionPattern"));
          validateAnalysis(analysis);
          analyses.add(analysis);
        }
        if (analyses.size() < 2) {
          throw new IllegalArgumentException(
              "Ambiguous noun metadata row must contain at least two analyses: " + surface);
        }

        FrenchNounAmbiguityRow ambiguousRow =
            new FrenchNounAmbiguityRow(
                surfaceIndex,
                surface,
                textArray(
                    requiredArray(ambiguousRowNode, "reasons"),
                    "reasons",
                    null,
                    "French noun metadata"),
                List.copyOf(analyses));
        if (ambiguousRowsBySurface.put(surface, ambiguousRow) != null) {
          throw new IllegalArgumentException(
              "Duplicate ambiguous noun metadata surface: " + surface);
        }
        ambiguousRows.add(ambiguousRow);
      }
    }

    Summary summary = loadSummary(requiredObject(root, "summary"));
    if (summary.rows() != rows.size()) {
      throw new IllegalArgumentException("Summary row count does not match rows array");
    }
    if (summary.ambiguousRows() != ambiguousRows.size()) {
      throw new IllegalArgumentException(
          "Summary ambiguous row count does not match ambiguous rows array");
    }
    if (summary.strings() != strings.size()) {
      throw new IllegalArgumentException("Summary string count does not match strings array");
    }

    return new FrenchNounMetadataPack(
        optionalText(root, "locale"),
        List.copyOf(strings),
        List.copyOf(rows),
        unmodifiableLinkedMap(rowsBySurface),
        List.copyOf(ambiguousRows),
        unmodifiableLinkedMap(ambiguousRowsBySurface),
        loadProvenance(optionalObject(root, "provenance")),
        summary);
  }

  private List<String> loadStrings(JsonNode stringsNode) {
    List<String> strings = new ArrayList<>();
    for (JsonNode stringNode : stringsNode) {
      if (!stringNode.isTextual()) {
        throw new IllegalArgumentException("Expected text value in strings array");
      }
      strings.add(stringNode.asText());
    }
    return strings;
  }

  private void validateFeatureBits(FrenchNounMetadataRow row) {
    int expected = expectedFeatureBits(row.gender(), row.number(), row.elides());
    if (row.featureBits() != expected) {
      throw new IllegalArgumentException(
          "Feature bits do not match row metadata for surface: " + row.surface());
    }
  }

  private void validateAnalysis(FrenchNounAmbiguousAnalysis analysis) {
    expectedFeatureBits(analysis.gender(), analysis.number(), analysis.elides());
  }

  private int expectedFeatureBits(String gender, String number, boolean elides) {
    int bits =
        switch (gender) {
          case "masculine" -> MASCULINE_BIT;
          case "feminine" -> FEMININE_BIT;
          default ->
              throw new IllegalArgumentException("Unsupported French noun gender: " + gender);
        };

    bits |=
        switch (number) {
          case "singular" -> SINGULAR_BIT;
          case "plural" -> PLURAL_BIT;
          default ->
              throw new IllegalArgumentException("Unsupported French noun number: " + number);
        };

    if (elides) {
      bits |= VOWEL_START_BIT;
    }
    return bits;
  }

  private Summary loadSummary(JsonNode node) {
    return new Summary(
        requiredInt(node, "rows"),
        optionalInt(node, "ambiguousRows", 0),
        requiredInt(node, "strings"),
        requiredInt(node, "stringPoolBytes"),
        requiredInt(node, "jsonBytes"),
        requiredInt(node, "binaryLowerBoundBytes"));
  }

  private Provenance loadProvenance(JsonNode node) {
    if (node == null) {
      return new Provenance(null, null, List.of());
    }

    List<Source> sources = new ArrayList<>();
    JsonNode sourcesNode = optionalArray(node, "sources");
    if (sourcesNode != null) {
      for (JsonNode sourceNode : sourcesNode) {
        sources.add(
            new Source(
                requiredText(sourceNode, "path"),
                requiredLong(sourceNode, "byteSize"),
                requiredText(sourceNode, "sha256")));
      }
    }
    return new Provenance(
        optionalText(node, "license"), optionalText(node, "generator"), List.copyOf(sources));
  }

  public record FrenchNounMetadataPack(
      String locale,
      List<String> strings,
      List<FrenchNounMetadataRow> rows,
      Map<String, FrenchNounMetadataRow> rowsBySurface,
      List<FrenchNounAmbiguityRow> ambiguousRows,
      Map<String, FrenchNounAmbiguityRow> ambiguousRowsBySurface,
      Provenance provenance,
      Summary summary) {

    public FrenchNounMetadataPack {
      locale = requireText(locale, "locale");
      strings = List.copyOf(Objects.requireNonNull(strings, "strings"));
      rows = List.copyOf(Objects.requireNonNull(rows, "rows"));
      rowsBySurface = unmodifiableLinkedMap(Objects.requireNonNull(rowsBySurface, "rowsBySurface"));
      ambiguousRows = List.copyOf(Objects.requireNonNull(ambiguousRows, "ambiguousRows"));
      ambiguousRowsBySurface =
          unmodifiableLinkedMap(
              Objects.requireNonNull(ambiguousRowsBySurface, "ambiguousRowsBySurface"));
      provenance = Objects.requireNonNull(provenance, "provenance");
      summary = Objects.requireNonNull(summary, "summary");
    }

    public Optional<FrenchNounMetadataRow> find(String surface) {
      return Optional.ofNullable(rowsBySurface.get(surface));
    }

    public Optional<FrenchNounAmbiguityRow> findAmbiguous(String surface) {
      return Optional.ofNullable(ambiguousRowsBySurface.get(surface));
    }
  }

  public record FrenchNounMetadataRow(
      int surfaceIndex,
      String surface,
      int featureBits,
      String gender,
      String number,
      boolean elides,
      String inflectionPattern) {

    public FrenchNounMetadataRow {
      if (surfaceIndex < 0) {
        throw new IllegalArgumentException("surfaceIndex must be non-negative: " + surfaceIndex);
      }
      surface = requireText(surface, "surface");
      gender = requireFrenchGender(gender);
      number = requireFrenchNumber(number);
    }

    public Morphology toMorphology() {
      return new Morphology("noun", gender, number, elides, null);
    }
  }

  public record FrenchNounAmbiguityRow(
      int surfaceIndex,
      String surface,
      List<String> reasons,
      List<FrenchNounAmbiguousAnalysis> analyses) {

    public FrenchNounAmbiguityRow {
      if (surfaceIndex < 0) {
        throw new IllegalArgumentException("surfaceIndex must be non-negative: " + surfaceIndex);
      }
      surface = requireText(surface, "surface");
      reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons"));
      analyses = List.copyOf(Objects.requireNonNull(analyses, "analyses"));
    }
  }

  public record FrenchNounAmbiguousAnalysis(
      String gender, String number, boolean elides, String inflectionPattern) {

    public FrenchNounAmbiguousAnalysis {
      gender = requireFrenchGender(gender);
      number = requireFrenchNumber(number);
    }
  }

  public record Provenance(String license, String generator, List<Source> sources) {

    public Provenance {
      sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
    }
  }

  public record Source(String path, long byteSize, String sha256) {

    public Source {
      path = requireText(path, "path");
      sha256 = requireText(sha256, "sha256");
      if (byteSize < 0) {
        throw new IllegalArgumentException("byteSize must be non-negative: " + byteSize);
      }
    }
  }

  public record Summary(
      int rows,
      int ambiguousRows,
      int strings,
      int stringPoolBytes,
      int jsonBytes,
      int binaryLowerBoundBytes) {

    public Summary {
      if (rows < 0
          || ambiguousRows < 0
          || strings < 0
          || stringPoolBytes < 0
          || jsonBytes < 0
          || binaryLowerBoundBytes < 0) {
        throw new IllegalArgumentException("summary counts must be non-negative");
      }
    }
  }

  private static String requireFrenchGender(String gender) {
    gender = requireText(gender, "gender");
    if (!"masculine".equals(gender) && !"feminine".equals(gender)) {
      throw new IllegalArgumentException("Unsupported French noun gender: " + gender);
    }
    return gender;
  }

  private static String requireFrenchNumber(String number) {
    number = requireText(number, "number");
    if (!"singular".equals(number) && !"plural".equals(number)) {
      throw new IllegalArgumentException("Unsupported French noun number: " + number);
    }
    return number;
  }
}
