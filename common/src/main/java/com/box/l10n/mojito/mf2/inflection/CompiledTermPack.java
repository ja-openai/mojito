package com.box.l10n.mojito.mf2.inflection;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable closed-world term-pack model used by JSON, binary, validation, and render paths. */
public record CompiledTermPack(
    String schema,
    String locale,
    List<String> strings,
    List<CompiledTermPack.TermRow> terms,
    List<CompiledTermPack.FormSet> formSets,
    CompiledTermPack.Provenance provenance,
    CompiledTermPack.SizeEstimates sizeEstimates,
    CompiledTermPack.ExportPolicy exportPolicy) {

  public static final String SCHEMA = "mojito-mf2-inflection/compiled-term-pack/v0";

  public CompiledTermPack(List<String> strings, List<TermRow> terms, List<FormSet> formSets) {
    this(
        SCHEMA,
        null,
        strings,
        terms,
        formSets,
        Provenance.empty(),
        SizeEstimates.empty(),
        ExportPolicy.empty());
  }

  public CompiledTermPack(
      String schema,
      String locale,
      List<String> strings,
      List<CompiledTermPack.TermRow> terms,
      List<CompiledTermPack.FormSet> formSets,
      CompiledTermPack.Provenance provenance,
      CompiledTermPack.SizeEstimates sizeEstimates) {
    this(schema, locale, strings, terms, formSets, provenance, sizeEstimates, ExportPolicy.empty());
  }

  public CompiledTermPack {
    schema = requireSchema(schema);
    locale = optionalText(locale, "locale");
    strings = List.copyOf(Objects.requireNonNull(strings, "strings"));
    terms = List.copyOf(Objects.requireNonNull(terms, "terms"));
    formSets = List.copyOf(Objects.requireNonNull(formSets, "formSets"));
    provenance = Objects.requireNonNull(provenance, "provenance");
    sizeEstimates = Objects.requireNonNull(sizeEstimates, "sizeEstimates");
    exportPolicy = Objects.requireNonNull(exportPolicy, "exportPolicy");

    for (String string : strings) {
      requireText(string, "strings value");
    }

    Set<Integer> formSetTerms = new HashSet<>();
    for (FormSet formSet : formSets) {
      requireStringIndex(formSet.term(), strings, "formSet.term");
      if (formSet.forms().isEmpty()) {
        throw new IllegalArgumentException(
            "Compiled form set requires forms: " + strings.get(formSet.term()));
      }
      if (!formSetTerms.add(formSet.term())) {
        throw new IllegalArgumentException(
            "Duplicate form set for term: " + strings.get(formSet.term()));
      }

      Set<Integer> formKeys = new HashSet<>();
      for (FormRow form : formSet.forms()) {
        requireStringIndex(form.key(), strings, "form.key");
        requireStringIndex(form.value(), strings, "form.value");
        if (!formKeys.add(form.key())) {
          throw new IllegalArgumentException(
              "Duplicate compiled form key for term "
                  + strings.get(formSet.term())
                  + ": "
                  + strings.get(form.key()));
        }
      }
    }

    Set<Integer> termIds = new HashSet<>();
    for (TermRow term : terms) {
      requireStringIndex(term.id(), strings, "term.id");
      requireStringIndex(term.text(), strings, "term.text");
      if (term.sense() != null) {
        requireStringIndex(term.sense(), strings, "term.sense");
      }
      if (term.formSet() < 0 || term.formSet() >= formSets.size()) {
        throw new IllegalArgumentException("Term form set index out of bounds: " + term.formSet());
      }
      if (formSets.get(term.formSet()).term() != term.id()) {
        throw new IllegalArgumentException(
            "Term row id does not match form set term: " + strings.get(term.id()));
      }
      if (!termIds.add(term.id())) {
        throw new IllegalArgumentException(
            "Duplicate compiled term row: " + strings.get(term.id()));
      }
    }

    for (Integer formSetTerm : formSetTerms) {
      if (!termIds.contains(formSetTerm)) {
        throw new IllegalArgumentException(
            "Compiled form set has no term row: " + strings.get(formSetTerm));
      }
    }
  }

  private static String requireSchema(String schema) {
    if (!SCHEMA.equals(schema)) {
      throw new IllegalArgumentException("Expected compiled term pack schema: " + SCHEMA);
    }
    return schema;
  }

  private static void requireStringIndex(int index, List<String> strings, String field) {
    if (index < 0 || index >= strings.size()) {
      throw new IllegalArgumentException("String index out of bounds for " + field + ": " + index);
    }
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return value;
  }

  private static String optionalText(String value, String field) {
    if (value == null) {
      return null;
    }
    if (value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }

  public record TermRow(int id, int text, int featureBits, Integer sense, int formSet) {}

  public record FormSet(int term, List<FormRow> forms) {

    public FormSet {
      forms = List.copyOf(Objects.requireNonNull(forms, "forms"));
    }
  }

  public record FormRow(int key, int value, boolean pattern) {}

  public record Provenance(
      String license, String generator, List<String> sourceLabels, List<Source> sources) {

    public Provenance {
      license = optionalText(license, "license");
      generator = optionalText(generator, "generator");
      sourceLabels =
          List.copyOf(
              Objects.requireNonNull(sourceLabels, "sourceLabels").stream()
                  .map(label -> requireText(label, "sourceLabels"))
                  .toList());
      sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
      if (sourceLabels.size() != sources.size()) {
        throw new IllegalArgumentException("Provenance source label count does not match sources");
      }
      if (new HashSet<>(sourceLabels).size() != sourceLabels.size()) {
        throw new IllegalArgumentException("Duplicate provenance source label");
      }
      if (!sources.isEmpty()) {
        if (license == null) {
          throw new IllegalArgumentException("Source-backed provenance requires license");
        }
        if (generator == null) {
          throw new IllegalArgumentException("Source-backed provenance requires generator");
        }
      }
    }

    public static Provenance empty() {
      return new Provenance(null, null, List.of(), List.of());
    }
  }

  public record Source(String path, long byteSize, String sha256, boolean gitLfsPointer) {

    public Source {
      path = requireText(path, "path");
      sha256 = InflectionJsonFields.requireSha256Hex(sha256, "sha256");
      if (byteSize < 0) {
        throw new IllegalArgumentException("byteSize must be non-negative: " + byteSize);
      }
    }
  }

  public record SizeEstimates(
      Integer compactJsonBytes, BinaryLowerBoundBytes binaryLowerBoundBytes) {

    public SizeEstimates {
      if (compactJsonBytes != null && compactJsonBytes < 0) {
        throw new IllegalArgumentException("compactJsonBytes must be non-negative");
      }
      binaryLowerBoundBytes =
          Objects.requireNonNull(binaryLowerBoundBytes, "binaryLowerBoundBytes");
    }

    public static SizeEstimates empty() {
      return new SizeEstimates(null, BinaryLowerBoundBytes.empty());
    }
  }

  public record BinaryLowerBoundBytes(
      int stringPoolBytes,
      int termRowBytes,
      int formRowBytes,
      int bindingReferenceBytes,
      int totalBytes) {

    public BinaryLowerBoundBytes {
      if (stringPoolBytes < 0
          || termRowBytes < 0
          || formRowBytes < 0
          || bindingReferenceBytes < 0
          || totalBytes < 0) {
        throw new IllegalArgumentException("binary lower-bound byte counts must be non-negative");
      }
      if (totalBytes != stringPoolBytes + termRowBytes + formRowBytes + bindingReferenceBytes) {
        throw new IllegalArgumentException("Binary lower-bound total does not match parts");
      }
    }

    public static BinaryLowerBoundBytes empty() {
      return new BinaryLowerBoundBytes(0, 0, 0, 0, 0);
    }
  }

  public record ExportPolicy(
      String runtimeExport,
      String compositionMode,
      List<String> deferredComposition,
      int automaticExportTerms,
      int reviewRequiredTerms,
      int blockedTerms,
      Map<String, Integer> reviewRequiredReasons,
      Map<String, Integer> blockedReasons) {

    public ExportPolicy {
      runtimeExport = optionalText(runtimeExport, "runtimeExport");
      compositionMode = optionalText(compositionMode, "compositionMode");
      deferredComposition =
          List.copyOf(Objects.requireNonNull(deferredComposition, "deferredComposition"));
      reviewRequiredReasons =
          immutableReasonCounts(
              Objects.requireNonNull(reviewRequiredReasons, "reviewRequiredReasons"),
              "reviewRequiredReasons");
      blockedReasons =
          immutableReasonCounts(
              Objects.requireNonNull(blockedReasons, "blockedReasons"), "blockedReasons");

      requireNonNegative(automaticExportTerms, "automaticExportTerms");
      requireNonNegative(reviewRequiredTerms, "reviewRequiredTerms");
      requireNonNegative(blockedTerms, "blockedTerms");

      for (String deferredStep : deferredComposition) {
        requireText(deferredStep, "deferredComposition");
      }
      if (new HashSet<>(deferredComposition).size() != deferredComposition.size()) {
        throw new IllegalArgumentException("Duplicate deferredComposition value");
      }

      boolean present =
          runtimeExport != null
              || compositionMode != null
              || !deferredComposition.isEmpty()
              || automaticExportTerms != 0
              || reviewRequiredTerms != 0
              || blockedTerms != 0
              || !reviewRequiredReasons.isEmpty()
              || !blockedReasons.isEmpty();
      if (present) {
        if (runtimeExport == null) {
          throw new IllegalArgumentException("runtimeExport is required");
        }
        if (compositionMode == null) {
          throw new IllegalArgumentException("compositionMode is required");
        }
        requireReasonCountSum(reviewRequiredReasons, reviewRequiredTerms, "reviewRequiredReasons");
        requireReasonCountSum(blockedReasons, blockedTerms, "blockedReasons");
      }
    }

    public boolean present() {
      return runtimeExport != null;
    }

    public static ExportPolicy empty() {
      return new ExportPolicy(null, null, List.of(), 0, 0, 0, Map.of(), Map.of());
    }

    private static Map<String, Integer> immutableReasonCounts(
        Map<String, Integer> counts, String field) {
      Map<String, Integer> copy = new LinkedHashMap<>();
      for (Map.Entry<String, Integer> entry : counts.entrySet()) {
        requireText(entry.getKey(), field + " key");
        Integer value = Objects.requireNonNull(entry.getValue(), field + " value");
        if (value <= 0) {
          throw new IllegalArgumentException(field + " value must be positive");
        }
        copy.put(entry.getKey(), value);
      }
      return Collections.unmodifiableMap(copy);
    }

    private static void requireNonNegative(int value, String field) {
      if (value < 0) {
        throw new IllegalArgumentException(field + " must be non-negative");
      }
    }

    private static void requireReasonCountSum(
        Map<String, Integer> reasonCounts, int expectedTerms, String field) {
      int actualTerms = reasonCounts.values().stream().mapToInt(Integer::intValue).sum();
      if (actualTerms != expectedTerms) {
        throw new IllegalArgumentException(field + " must sum to the matching term count");
      }
    }
  }
}
