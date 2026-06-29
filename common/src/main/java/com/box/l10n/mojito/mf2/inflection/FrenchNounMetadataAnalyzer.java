package com.box.l10n.mojito.mf2.inflection;

import com.box.l10n.mojito.mf2.inflection.FrenchGenderSuffixRulePackJsonLoader.FrenchGenderSuffixRulePack;
import com.box.l10n.mojito.mf2.inflection.FrenchGenderSuffixRulePackJsonLoader.SuffixRule;
import com.box.l10n.mojito.mf2.inflection.FrenchNounMetadataPackJsonLoader.FrenchNounAmbiguityRow;
import com.box.l10n.mojito.mf2.inflection.FrenchNounMetadataPackJsonLoader.FrenchNounMetadataPack;
import com.box.l10n.mojito.mf2.inflection.FrenchNounMetadataPackJsonLoader.FrenchNounMetadataRow;
import com.box.l10n.mojito.mf2.inflection.TermRequirementValidator.Morphology;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Looks up French noun metadata for authoring prefill and diagnostics. Exact dictionary rows win,
 * ambiguous dictionary rows stay ambiguous, and suffix rules are only a heuristic fallback with
 * source/confidence metadata.
 */
@GeneratorSupport
class FrenchNounMetadataAnalyzer {

  private final FrenchNounMetadataPack pack;
  private final FrenchGenderSuffixRulePack suffixRulePack;

  FrenchNounMetadataAnalyzer(FrenchNounMetadataPack pack) {
    this(pack, null);
  }

  FrenchNounMetadataAnalyzer(
      FrenchNounMetadataPack pack, FrenchGenderSuffixRulePack suffixRulePack) {
    this.pack = Objects.requireNonNull(pack, "pack");
    this.suffixRulePack = suffixRulePack;
  }

  public Analysis analyze(String surface) {
    String normalizedSurface = normalizeSurface(surface);
    if (normalizedSurface.isBlank()) {
      return Analysis.unknown(surface, normalizedSurface);
    }

    return pack.find(normalizedSurface)
        .map(row -> Analysis.exact(surface, normalizedSurface, row))
        .or(
            () ->
                pack.findAmbiguous(normalizedSurface)
                    .map(row -> Analysis.ambiguous(surface, normalizedSurface, row)))
        .orElseGet(() -> analyzeWithSuffixRules(surface, normalizedSurface));
  }

  private Analysis analyzeWithSuffixRules(String surface, String normalizedSurface) {
    if (suffixRulePack == null) {
      return Analysis.unknown(surface, normalizedSurface);
    }
    return suffixRulePack
        .bestRuleFor(normalizedSurface)
        .map(rule -> Analysis.heuristic(surface, normalizedSurface, rule))
        .orElseGet(() -> Analysis.unknown(surface, normalizedSurface));
  }

  public static String normalizeSurface(String surface) {
    return Objects.requireNonNull(surface, "surface")
        .strip()
        .toLowerCase(Locale.FRENCH)
        .replace('\u2019', '\'');
  }

  public enum AnalysisStatus {
    EXACT,
    HEURISTIC,
    UNKNOWN,
    AMBIGUOUS
  }

  public enum AnalysisSource {
    DICTIONARY,
    SUFFIX_RULE,
    NONE
  }

  public record Analysis(
      String input,
      String normalizedSurface,
      AnalysisStatus status,
      AnalysisSource source,
      FrenchNounMetadataRow matchedRow,
      FrenchNounAmbiguityRow ambiguityRow,
      String heuristicGender,
      Double heuristicConfidence) {

    static Analysis exact(String input, String normalizedSurface, FrenchNounMetadataRow row) {
      return new Analysis(
          input,
          normalizedSurface,
          AnalysisStatus.EXACT,
          AnalysisSource.DICTIONARY,
          row,
          null,
          null,
          null);
    }

    static Analysis ambiguous(String input, String normalizedSurface, FrenchNounAmbiguityRow row) {
      return new Analysis(
          input,
          normalizedSurface,
          AnalysisStatus.AMBIGUOUS,
          AnalysisSource.DICTIONARY,
          null,
          row,
          null,
          null);
    }

    static Analysis heuristic(String input, String normalizedSurface, SuffixRule rule) {
      return new Analysis(
          input,
          normalizedSurface,
          AnalysisStatus.HEURISTIC,
          AnalysisSource.SUFFIX_RULE,
          null,
          null,
          rule.gender(),
          rule.confidence());
    }

    static Analysis unknown(String input, String normalizedSurface) {
      return new Analysis(
          input,
          normalizedSurface,
          AnalysisStatus.UNKNOWN,
          AnalysisSource.NONE,
          null,
          null,
          null,
          null);
    }

    public Optional<FrenchNounMetadataRow> matchedRowOptional() {
      return Optional.ofNullable(matchedRow);
    }

    public Optional<FrenchNounAmbiguityRow> ambiguityRowOptional() {
      return Optional.ofNullable(ambiguityRow);
    }

    public Optional<Morphology> morphology() {
      return matchedRowOptional().map(FrenchNounMetadataRow::toMorphology);
    }

    public Optional<String> gender() {
      return morphology().map(Morphology::gender).or(() -> Optional.ofNullable(heuristicGender));
    }

    public Optional<Double> confidence() {
      return Optional.ofNullable(heuristicConfidence);
    }
  }
}
