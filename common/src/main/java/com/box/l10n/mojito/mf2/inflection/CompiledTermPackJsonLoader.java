package com.box.l10n.mojito.mf2.inflection;

import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.optionalArray;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.optionalInt;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.optionalObject;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.optionalStringIndex;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.optionalText;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.requiredArray;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.requiredBoolean;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.requiredInt;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.requiredLong;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.requiredObject;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.requiredObjectRoot;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.requiredStringIndex;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.requiredText;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.textArray;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.mf2.inflection.CompiledTermPack.BinaryLowerBoundBytes;
import com.box.l10n.mojito.mf2.inflection.CompiledTermPack.ExportPolicy;
import com.box.l10n.mojito.mf2.inflection.CompiledTermPack.FormRow;
import com.box.l10n.mojito.mf2.inflection.CompiledTermPack.FormSet;
import com.box.l10n.mojito.mf2.inflection.CompiledTermPack.Provenance;
import com.box.l10n.mojito.mf2.inflection.CompiledTermPack.SizeEstimates;
import com.box.l10n.mojito.mf2.inflection.CompiledTermPack.Source;
import com.box.l10n.mojito.mf2.inflection.CompiledTermPack.TermRow;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Loads the compact compiled term-pack JSON contract used by runtime rendering and native-pack
 * generation.
 *
 * <p>This loader is part of the Java integration surface: glossary export/import code should use it
 * to validate generated packs before rendering or binary encoding. Locale-specific source-data
 * loaders should stay behind this model and emit {@link CompiledTermPack} instead of exposing their
 * raw report shapes to runtime callers.
 */
public class CompiledTermPackJsonLoader {

  private final ObjectMapper objectMapper;

  public CompiledTermPackJsonLoader() {
    this(new ObjectMapper());
  }

  public CompiledTermPackJsonLoader(ObjectMapper objectMapper) {
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
  }

  public CompiledTermPack load(String json) {
    Objects.requireNonNull(json, "json");
    return load(objectMapper.readTreeUnchecked(json));
  }

  public CompiledTermPack load(JsonNode root) {
    Objects.requireNonNull(root, "root");
    requiredObjectRoot(root, "compiled term pack");
    rejectDiagnostics(root);

    String schema = optionalText(root, "schema");
    if (schema == null) {
      schema = CompiledTermPack.SCHEMA;
    }
    String locale = optionalText(root, "locale");
    Provenance provenance = loadProvenance(optionalObject(root, "provenance"));
    JsonNode sizeEstimatesNode = optionalObject(root, "sizeEstimates");
    SizeEstimates sizeEstimates = loadSizeEstimates(sizeEstimatesNode);

    List<String> strings = new ArrayList<>();
    for (JsonNode stringNode : requiredArray(root, "strings")) {
      if (!stringNode.isTextual()) {
        throw new IllegalArgumentException("Expected text value in strings array");
      }
      strings.add(stringNode.asText());
    }

    List<FormSet> formSets = new ArrayList<>();
    Set<String> termIds = new HashSet<>();
    for (JsonNode formSetNode : requiredArray(root, "formSets")) {
      int term = requiredStringIndex(formSetNode, "term", strings);
      String termId = strings.get(term);
      if (!termIds.add(termId)) {
        throw new IllegalArgumentException("Duplicate form set for term: " + termId);
      }

      List<FormRow> forms = new ArrayList<>();
      Set<String> formKeys = new HashSet<>();
      for (JsonNode formNode : requiredArray(formSetNode, "forms")) {
        int key = requiredStringIndex(formNode, "key", strings);
        String formKey = strings.get(key);
        if (!formKeys.add(formKey)) {
          throw new IllegalArgumentException(
              "Duplicate form key for term " + termId + ": " + formKey);
        }
        forms.add(
            new FormRow(
                key,
                requiredStringIndex(formNode, "value", strings),
                isPatternKind(requiredText(formNode, "kind"))));
      }
      formSets.add(new FormSet(term, forms));
    }

    List<TermRow> terms = new ArrayList<>();
    for (JsonNode termNode : requiredArray(root, "terms")) {
      terms.add(
          new TermRow(
              requiredStringIndex(termNode, "id", strings),
              requiredStringIndex(termNode, "text", strings),
              requiredInt(termNode, "featureBits"),
              optionalStringIndex(termNode, "sense", strings),
              requiredInt(termNode, "formSet")));
    }

    ExportPolicy exportPolicy = validateGenerationSummary(root, strings, terms, formSets);
    validateSizeEstimates(sizeEstimatesNode, sizeEstimates, strings, terms, formSets);

    return new CompiledTermPack(
        schema,
        locale,
        List.copyOf(strings),
        List.copyOf(terms),
        List.copyOf(formSets),
        provenance,
        sizeEstimates,
        exportPolicy);
  }

  private void rejectDiagnostics(JsonNode root) {
    JsonNode diagnostics = root.get("diagnostics");
    if (diagnostics == null || diagnostics.isNull()) {
      return;
    }
    if (!diagnostics.isArray()) {
      throw new IllegalArgumentException("Expected array field: diagnostics");
    }
    if (!diagnostics.isEmpty()) {
      throw new IllegalArgumentException("Compiled term pack contains diagnostics");
    }
  }

  private Provenance loadProvenance(JsonNode node) {
    if (node == null) {
      return Provenance.empty();
    }

    List<String> sourceLabels = List.of();
    JsonNode sourceLabelsNode = optionalArray(node, "sourceLabels");
    if (sourceLabelsNode != null) {
      sourceLabels = textArray(sourceLabelsNode, "sourceLabels", null, "compiled term pack");
    }

    List<Source> sources = new ArrayList<>();
    JsonNode sourcesNode = optionalArray(node, "sources");
    if (sourcesNode != null) {
      for (JsonNode sourceNode : sourcesNode) {
        sources.add(
            new Source(
                requiredText(sourceNode, "path"),
                requiredLong(sourceNode, "byteSize"),
                requiredText(sourceNode, "sha256"),
                requiredBoolean(sourceNode, "gitLfsPointer")));
      }
    }

    return new Provenance(
        optionalText(node, "license"),
        optionalText(node, "generator"),
        sourceLabels,
        List.copyOf(sources));
  }

  private SizeEstimates loadSizeEstimates(JsonNode node) {
    if (node == null) {
      return SizeEstimates.empty();
    }

    JsonNode binaryLowerBoundBytesNode = optionalObject(node, "binaryLowerBoundBytes");
    BinaryLowerBoundBytes binaryLowerBoundBytes =
        binaryLowerBoundBytesNode == null
            ? BinaryLowerBoundBytes.empty()
            : new BinaryLowerBoundBytes(
                requiredInt(binaryLowerBoundBytesNode, "stringPoolBytes"),
                requiredInt(binaryLowerBoundBytesNode, "termRowBytes"),
                requiredInt(binaryLowerBoundBytesNode, "formRowBytes"),
                requiredInt(binaryLowerBoundBytesNode, "bindingReferenceBytes"),
                requiredInt(binaryLowerBoundBytesNode, "totalBytes"));

    return new SizeEstimates(optionalInt(node, "compactJsonBytes"), binaryLowerBoundBytes);
  }

  private void validateSizeEstimates(
      JsonNode sizeEstimatesNode,
      SizeEstimates sizeEstimates,
      List<String> strings,
      List<TermRow> terms,
      List<FormSet> formSets) {
    if (sizeEstimatesNode == null
        || optionalObject(sizeEstimatesNode, "binaryLowerBoundBytes") == null) {
      return;
    }

    BinaryLowerBoundBytes estimate = sizeEstimates.binaryLowerBoundBytes();
    int stringPoolBytes = stringPoolBytes(strings);
    if (estimate.stringPoolBytes() != stringPoolBytes) {
      throw new IllegalArgumentException("Compiled size estimate string-pool byte count mismatch");
    }
    int termRowBytes = terms.size() * CompiledTermPackBinaryCodec.TERM_ROW_BYTES;
    if (estimate.termRowBytes() != termRowBytes) {
      throw new IllegalArgumentException("Compiled size estimate term-row byte count mismatch");
    }
    int formRows = formSets.stream().mapToInt(formSet -> formSet.forms().size()).sum();
    int formRowBytes = formRows * CompiledTermPackBinaryCodec.FORM_ROW_BYTES;
    if (estimate.formRowBytes() != formRowBytes) {
      throw new IllegalArgumentException("Compiled size estimate form-row byte count mismatch");
    }
    if (estimate.bindingReferenceBytes() != 0) {
      throw new IllegalArgumentException(
          "Compiled size estimate binding-reference byte count mismatch");
    }
  }

  private ExportPolicy validateGenerationSummary(
      JsonNode root, List<String> strings, List<TermRow> terms, List<FormSet> formSets) {
    JsonNode summary = optionalObject(root, "generationSummary");
    if (summary == null) {
      return ExportPolicy.empty();
    }

    int formRowCount = formSets.stream().mapToInt(formSet -> formSet.forms().size()).sum();
    int candidateTerms =
        requireNonNegative(requiredInt(summary, "candidateTerms"), "candidateTerms");
    int exportedTerms = requireNonNegative(requiredInt(summary, "exportedTerms"), "exportedTerms");
    int formRows = requireNonNegative(requiredInt(summary, "formRows"), "formRows");
    if (exportedTerms > candidateTerms) {
      throw new IllegalArgumentException(
          "Compiled generation summary exported terms exceed candidates");
    }
    if (exportedTerms != terms.size()) {
      throw new IllegalArgumentException("Compiled generation summary exported terms mismatch");
    }
    if (formRows != formRowCount) {
      throw new IllegalArgumentException("Compiled generation summary form row count mismatch");
    }
    ExportPolicy exportPolicy = loadExportPolicy(summary, candidateTerms, exportedTerms);

    JsonNode exportedFormKeysNode = optionalArray(summary, "exportedFormKeys");
    if (exportedFormKeysNode == null) {
      return exportPolicy;
    }

    List<String> exportedFormKeys =
        textArray(exportedFormKeysNode, "exportedFormKeys", null, "compiled generation summary");
    requireUnique(exportedFormKeys, "exportedFormKeys");
    requiredText(summary, "policy");

    List<String> missingFormKeys =
        textArray(
            requiredArray(summary, "missingFormKeys"),
            "missingFormKeys",
            null,
            "compiled generation summary");
    requireUnique(missingFormKeys, "missingFormKeys");

    Integer requiredFormRows = optionalInt(summary, "requiredFormRows");
    Integer requiredFormRowsPerTerm = optionalInt(summary, "requiredFormRowsPerTerm");
    JsonNode reviewDiagnostics = requiredArray(summary, "reviewDiagnostics");
    if (requiredFormRows != null && requiredFormRowsPerTerm != null) {
      throw new IllegalArgumentException(
          "Compiled generation summary must not mix total and per-term required form rows");
    }
    if (requiredFormRows != null) {
      requireNonNegative(requiredFormRows, "requiredFormRows");
      if (requiredFormRows < formRows || requiredFormRows - formRows != missingFormKeys.size()) {
        throw new IllegalArgumentException(
            "Compiled generation summary missing form count mismatch");
      }
      if (exportedFormKeys.size() != formRows) {
        throw new IllegalArgumentException(
            "Compiled generation summary exported form key count mismatch");
      }
      validateFlatExportedFormKeys(strings, formSets, exportedFormKeys);
      validateSourceRows(summary, exportedFormKeys, formRows);
      validateSourceRowsByFormKey(requiredObject(summary, "sourceRowsByFormKey"), exportedFormKeys);
      validateMissingFormReviewReasonCount(
          exportPolicy,
          validateReviewDiagnostics(reviewDiagnostics, strings, terms, missingFormKeys).size());
    } else if (requiredFormRowsPerTerm != null) {
      requireNonNegative(requiredFormRowsPerTerm, "requiredFormRowsPerTerm");
      int requiredTotal = requiredFormRowsPerTerm * exportedTerms;
      Set<MissingFormCell> expectedMissingFormCells =
          missingFormCellsForEveryTerm(strings, terms, missingFormKeys);
      if (requiredTotal < formRows || requiredTotal - formRows != expectedMissingFormCells.size()) {
        throw new IllegalArgumentException(
            "Compiled generation summary per-term missing form count mismatch");
      }
      if (requiredFormRowsPerTerm < exportedFormKeys.size()
          || exportedFormKeys.size() * exportedTerms != formRows) {
        throw new IllegalArgumentException(
            "Compiled generation summary per-term exported form key count mismatch");
      }
      validatePerTermExportedFormKeys(strings, formSets, exportedFormKeys);
      validatePerTermSummaries(
          requiredArray(summary, "terms"),
          strings,
          terms,
          exportedFormKeys,
          requiredFormRowsPerTerm);
      validateMissingFormReviewReasonCount(
          exportPolicy,
          validateReviewDiagnostics(reviewDiagnostics, strings, terms, expectedMissingFormCells)
              .size());
    }
    return exportPolicy;
  }

  private ExportPolicy loadExportPolicy(JsonNode summary, int candidateTerms, int exportedTerms) {
    JsonNode exportPolicy = optionalObject(summary, "exportPolicy");
    if (exportPolicy == null) {
      return ExportPolicy.empty();
    }
    String runtimeExport = requiredText(exportPolicy, "runtimeExport");
    String compositionMode = requiredText(exportPolicy, "compositionMode");
    List<String> deferredComposition =
        textArray(
            requiredArray(exportPolicy, "deferredComposition"),
            "deferredComposition",
            null,
            "compiled export policy");
    requireUnique(deferredComposition, "deferredComposition");

    int automaticExportTerms =
        requireNonNegative(
            requiredInt(exportPolicy, "automaticExportTerms"), "automaticExportTerms");
    int reviewRequiredTerms =
        requireNonNegative(requiredInt(exportPolicy, "reviewRequiredTerms"), "reviewRequiredTerms");
    int blockedTerms =
        requireNonNegative(requiredInt(exportPolicy, "blockedTerms"), "blockedTerms");
    if (automaticExportTerms + reviewRequiredTerms != exportedTerms) {
      throw new IllegalArgumentException("Compiled export policy exported terms mismatch");
    }
    if (automaticExportTerms + reviewRequiredTerms + blockedTerms != candidateTerms) {
      throw new IllegalArgumentException(
          "Compiled export policy term counts do not match candidates");
    }

    Map<String, Integer> reviewRequiredReasons =
        positiveReasonCounts(requiredObject(exportPolicy, "reviewRequiredReasons"));
    Map<String, Integer> blockedReasons =
        positiveReasonCounts(requiredObject(exportPolicy, "blockedReasons"));
    validateReasonCountSum(
        reviewRequiredReasons,
        reviewRequiredTerms,
        "Compiled export policy review reason counts do not match review-required terms");
    validateReasonCountSum(
        blockedReasons,
        blockedTerms,
        "Compiled export policy blocked reason counts do not match blocked terms");
    return new ExportPolicy(
        runtimeExport,
        compositionMode,
        deferredComposition,
        automaticExportTerms,
        reviewRequiredTerms,
        blockedTerms,
        reviewRequiredReasons,
        blockedReasons);
  }

  private Map<String, Integer> positiveReasonCounts(JsonNode node) {
    Map<String, Integer> reasonCounts = new LinkedHashMap<>();
    node.fields()
        .forEachRemaining(
            entry -> {
              if (entry.getKey().isBlank()) {
                throw new IllegalArgumentException("Compiled export policy reason is blank");
              }
              int count = InflectionJsonFields.requiredIntValue(entry.getValue(), entry.getKey());
              if (count <= 0) {
                throw new IllegalArgumentException(
                    "Compiled export policy reason count must be positive: " + entry.getKey());
              }
              reasonCounts.put(entry.getKey(), count);
            });
    return reasonCounts;
  }

  private void validateReasonCountSum(
      Map<String, Integer> reasonCounts, int expectedTerms, String message) {
    int actualTerms = reasonCounts.values().stream().mapToInt(Integer::intValue).sum();
    if (actualTerms != expectedTerms) {
      throw new IllegalArgumentException(message);
    }
  }

  private void validateMissingFormReviewReasonCount(ExportPolicy exportPolicy, int expectedTerms) {
    if (!exportPolicy.present()) {
      return;
    }
    int actualTerms =
        exportPolicy
            .reviewRequiredReasons()
            .getOrDefault(TermInflectionDiagnostics.MISSING_FORM_CELL, 0);
    if (actualTerms != expectedTerms) {
      throw new IllegalArgumentException(
          "Compiled export policy missing-form-cell review reason count does not match review diagnostics");
    }
  }

  private void validateFlatExportedFormKeys(
      List<String> strings, List<FormSet> formSets, List<String> exportedFormKeys) {
    List<String> formKeys = new ArrayList<>();
    for (FormSet formSet : formSets) {
      for (FormRow form : formSet.forms()) {
        formKeys.add(strings.get(form.key()));
      }
    }
    if (!formKeys.equals(exportedFormKeys)) {
      throw new IllegalArgumentException(
          "Compiled generation summary exported form keys do not match compiled form rows");
    }
  }

  private void validatePerTermExportedFormKeys(
      List<String> strings, List<FormSet> formSets, List<String> exportedFormKeys) {
    Set<String> expectedFormKeys = new HashSet<>(exportedFormKeys);
    for (FormSet formSet : formSets) {
      Set<String> formKeys = new HashSet<>();
      for (FormRow form : formSet.forms()) {
        formKeys.add(strings.get(form.key()));
      }
      if (!formKeys.equals(expectedFormKeys)) {
        throw new IllegalArgumentException(
            "Compiled generation summary per-term exported form keys do not match compiled form rows");
      }
    }
  }

  private void validateSourceRows(JsonNode summary, List<String> exportedFormKeys, int formRows) {
    JsonNode sourceRowsNode = requiredArray(summary, "sourceRows");
    if (sourceRowsNode.size() != formRows || sourceRowsNode.size() != exportedFormKeys.size()) {
      throw new IllegalArgumentException("Compiled generation summary source row count mismatch");
    }
    for (JsonNode sourceRowNode : sourceRowsNode) {
      requireNonNegative(
          InflectionJsonFields.requiredIntValue(sourceRowNode, "sourceRows"), "sourceRows");
    }
  }

  private void validatePerTermSummaries(
      JsonNode termSummaries,
      List<String> strings,
      List<TermRow> terms,
      List<String> exportedFormKeys,
      int requiredFormRowsPerTerm) {
    if (termSummaries.size() != terms.size()) {
      throw new IllegalArgumentException("Compiled generation summary term count mismatch");
    }
    Set<String> knownTermIds = termIds(strings, terms);
    Set<String> seenTermIds = new HashSet<>();
    for (JsonNode termSummary : termSummaries) {
      String termId = requiredText(termSummary, "termId");
      if (!knownTermIds.contains(termId)) {
        throw new IllegalArgumentException("Unknown compiled generation summary term: " + termId);
      }
      if (!seenTermIds.add(termId)) {
        throw new IllegalArgumentException("Duplicate compiled generation summary term: " + termId);
      }
      JsonNode sourceRowsNode = requiredArray(termSummary, "sourceRows");
      if (sourceRowsNode.size() != exportedFormKeys.size()
          || sourceRowsNode.size() > requiredFormRowsPerTerm) {
        throw new IllegalArgumentException(
            "Compiled generation summary per-term source row count mismatch");
      }
      for (JsonNode sourceRowNode : sourceRowsNode) {
        requireNonNegative(
            InflectionJsonFields.requiredIntValue(sourceRowNode, "sourceRows"), "sourceRows");
      }
      validateSourceRowsByFormKey(
          requiredObject(termSummary, "sourceRowsByFormKey"), exportedFormKeys);
    }
  }

  private void validateSourceRowsByFormKey(JsonNode node, List<String> exportedFormKeys) {
    Map<String, Integer> sourceRowsByFormKey = new LinkedHashMap<>();
    node.fields()
        .forEachRemaining(
            entry -> {
              sourceRowsByFormKey.put(
                  entry.getKey(),
                  requireNonNegative(
                      InflectionJsonFields.requiredIntValue(
                          entry.getValue(), "sourceRowsByFormKey"),
                      "sourceRowsByFormKey"));
            });
    if (!sourceRowsByFormKey.keySet().equals(new HashSet<>(exportedFormKeys))) {
      throw new IllegalArgumentException(
          "Compiled generation summary source rows do not match exported form keys");
    }
  }

  private Set<String> validateReviewDiagnostics(
      JsonNode diagnostics,
      List<String> strings,
      List<TermRow> terms,
      List<String> missingFormKeys) {
    if (diagnostics.size() != missingFormKeys.size()) {
      throw new IllegalArgumentException(
          "Compiled generation summary review diagnostics count mismatch");
    }
    Set<String> knownTermIds = termIds(strings, terms);
    Set<String> expectedMissingFormKeys = new HashSet<>(missingFormKeys);
    Set<String> diagnosedMissingFormKeys = new HashSet<>();
    Set<String> diagnosedTermIds = new HashSet<>();
    for (JsonNode diagnosticNode : diagnostics) {
      String termId = requiredText(diagnosticNode, "termId");
      if (!knownTermIds.contains(termId)) {
        throw new IllegalArgumentException(
            "Unknown compiled generation summary diagnostic term: " + termId);
      }
      diagnosedTermIds.add(termId);
      String reason = requiredText(diagnosticNode, "reason");
      if (!TermInflectionDiagnostics.MISSING_FORM_CELL.equals(reason)) {
        throw new IllegalArgumentException(
            "Unsupported compiled generation summary review reason: " + reason);
      }
      String formKey = requiredText(diagnosticNode, "formKey");
      if (!expectedMissingFormKeys.contains(formKey)) {
        throw new IllegalArgumentException(
            "Compiled generation summary diagnostic does not match missing form: " + formKey);
      }
      if (!diagnosedMissingFormKeys.add(formKey)) {
        throw new IllegalArgumentException(
            "Duplicate compiled generation summary diagnostic form: " + formKey);
      }
    }
    if (!diagnosedMissingFormKeys.equals(expectedMissingFormKeys)) {
      throw new IllegalArgumentException(
          "Compiled generation summary diagnostics do not cover missing forms");
    }
    return diagnosedTermIds;
  }

  private Set<String> validateReviewDiagnostics(
      JsonNode diagnostics,
      List<String> strings,
      List<TermRow> terms,
      Set<MissingFormCell> expectedMissingFormCells) {
    if (diagnostics.size() != expectedMissingFormCells.size()) {
      throw new IllegalArgumentException(
          "Compiled generation summary review diagnostics count mismatch");
    }
    Set<String> knownTermIds = termIds(strings, terms);
    Set<MissingFormCell> diagnosedMissingFormCells = new HashSet<>();
    Set<String> diagnosedTermIds = new HashSet<>();
    for (JsonNode diagnosticNode : diagnostics) {
      String termId = requiredText(diagnosticNode, "termId");
      if (!knownTermIds.contains(termId)) {
        throw new IllegalArgumentException(
            "Unknown compiled generation summary diagnostic term: " + termId);
      }
      diagnosedTermIds.add(termId);
      String reason = requiredText(diagnosticNode, "reason");
      if (!TermInflectionDiagnostics.MISSING_FORM_CELL.equals(reason)) {
        throw new IllegalArgumentException(
            "Unsupported compiled generation summary review reason: " + reason);
      }
      String formKey = requiredText(diagnosticNode, "formKey");
      MissingFormCell missingFormCell = new MissingFormCell(termId, formKey);
      if (!expectedMissingFormCells.contains(missingFormCell)) {
        throw new IllegalArgumentException(
            "Compiled generation summary diagnostic does not match missing form: "
                + termId
                + "/"
                + formKey);
      }
      if (!diagnosedMissingFormCells.add(missingFormCell)) {
        throw new IllegalArgumentException(
            "Duplicate compiled generation summary diagnostic cell: " + termId + "/" + formKey);
      }
    }
    if (!diagnosedMissingFormCells.equals(expectedMissingFormCells)) {
      throw new IllegalArgumentException(
          "Compiled generation summary diagnostics do not cover missing forms");
    }
    return diagnosedTermIds;
  }

  private Set<MissingFormCell> missingFormCellsForEveryTerm(
      List<String> strings, List<TermRow> terms, List<String> missingFormKeys) {
    Set<MissingFormCell> missingFormCells = new HashSet<>();
    for (TermRow term : terms) {
      String termId = strings.get(term.id());
      for (String formKey : missingFormKeys) {
        missingFormCells.add(new MissingFormCell(termId, formKey));
      }
    }
    return missingFormCells;
  }

  private Set<String> termIds(List<String> strings, List<TermRow> terms) {
    Set<String> termIds = new HashSet<>();
    for (TermRow term : terms) {
      termIds.add(strings.get(term.id()));
    }
    return termIds;
  }

  private int requireNonNegative(int value, String field) {
    if (value < 0) {
      throw new IllegalArgumentException("Compiled generation summary " + field + " is negative");
    }
    return value;
  }

  private void requireUnique(List<String> values, String field) {
    if (new HashSet<>(values).size() != values.size()) {
      throw new IllegalArgumentException("Duplicate compiled generation summary " + field);
    }
  }

  private boolean isPatternKind(String kind) {
    if ("pattern".equals(kind)) {
      return true;
    }
    if ("literal".equals(kind)) {
      return false;
    }
    throw new IllegalArgumentException("Unknown form row kind: " + kind);
  }

  private static int stringPoolBytes(List<String> strings) {
    return strings.stream()
        .mapToInt(value -> value.getBytes(StandardCharsets.UTF_8).length + 1)
        .sum();
  }

  private record MissingFormCell(String termId, String formKey) {}
}
