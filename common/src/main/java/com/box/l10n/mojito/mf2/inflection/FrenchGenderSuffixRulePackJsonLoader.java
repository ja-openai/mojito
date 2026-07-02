package com.box.l10n.mojito.mf2.inflection;

import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.optionalArray;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.optionalObject;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.optionalText;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.requireText;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.requiredArray;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.requiredDouble;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.requiredInt;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.requiredLong;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.requiredObject;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.requiredText;

import com.box.l10n.mojito.json.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Loads a compact generated suffix-rule fallback for French noun gender hints. Rules are useful for
 * prefill and low-confidence diagnostics, but they are not a deterministic replacement for explicit
 * term metadata or dictionary-backed exact rows.
 */
@GeneratorSupport
class FrenchGenderSuffixRulePackJsonLoader {

  static final String EXPECTED_SCHEMA = "mojito-mf2-inflection/fr-gender-suffix-rule-pack/v0";

  private final ObjectMapper objectMapper;

  FrenchGenderSuffixRulePackJsonLoader() {
    this(new ObjectMapper());
  }

  FrenchGenderSuffixRulePackJsonLoader(ObjectMapper objectMapper) {
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
  }

  public FrenchGenderSuffixRulePack load(String json) {
    Objects.requireNonNull(json, "json");
    return load(objectMapper.readTreeUnchecked(json));
  }

  public FrenchGenderSuffixRulePack load(JsonNode root) {
    String schema = requiredText(root, "schema");
    if (!EXPECTED_SCHEMA.equals(schema)) {
      throw new IllegalArgumentException("Expected French suffix-rule schema");
    }

    List<SuffixRule> rules = new ArrayList<>();
    for (JsonNode ruleNode : requiredArray(root, "rules")) {
      SuffixRule rule =
          new SuffixRule(
              requiredText(ruleNode, "suffix"),
              requiredText(ruleNode, "gender"),
              requiredDouble(ruleNode, "confidence"),
              requiredInt(ruleNode, "support"));
      validateRule(rule);
      rules.add(rule);
    }

    Summary summary = loadSummary(requiredObject(root, "summary"));
    if (summary.exportedRules() != rules.size()) {
      throw new IllegalArgumentException("Summary exported rule count does not match rules array");
    }
    if (summary.rules() < summary.exportedRules()) {
      throw new IllegalArgumentException("Summary total rule count is smaller than exported rules");
    }

    return new FrenchGenderSuffixRulePack(
        optionalText(root, "locale"),
        List.copyOf(rules),
        loadProvenance(optionalObject(root, "provenance")),
        summary);
  }

  private void validateRule(SuffixRule rule) {
    if (rule.suffix().isBlank()) {
      throw new IllegalArgumentException("Expected non-blank suffix rule");
    }
    if (!"masculine".equals(rule.gender()) && !"feminine".equals(rule.gender())) {
      throw new IllegalArgumentException("Unsupported French suffix-rule gender: " + rule.gender());
    }
    if (rule.confidence() < 0 || rule.confidence() > 1) {
      throw new IllegalArgumentException("Suffix-rule confidence must be between 0 and 1");
    }
    if (rule.support() <= 0) {
      throw new IllegalArgumentException("Suffix-rule support must be positive");
    }
  }

  private Summary loadSummary(JsonNode node) {
    return new Summary(
        requiredInt(node, "trainingSurfaces"),
        requiredInt(node, "rules"),
        requiredInt(node, "exportedRules"),
        requiredDouble(node, "suffixOnlyAccuracy"),
        requiredInt(node, "suffixRuleBytes"),
        requiredInt(node, "exportedSuffixRuleBytes"),
        requiredInt(node, "maxSuffixLen"),
        requiredInt(node, "minSupport"),
        requiredDouble(node, "minConfidence"));
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

  public record FrenchGenderSuffixRulePack(
      String locale, List<SuffixRule> rules, Provenance provenance, Summary summary) {

    public FrenchGenderSuffixRulePack {
      locale = requireText(locale, "locale");
      rules = List.copyOf(Objects.requireNonNull(rules, "rules"));
      provenance = Objects.requireNonNull(provenance, "provenance");
      summary = Objects.requireNonNull(summary, "summary");
    }

    public Optional<SuffixRule> bestRuleFor(String surface) {
      Objects.requireNonNull(surface, "surface");
      return rules.stream().filter(rule -> surface.endsWith(rule.suffix())).findFirst();
    }
  }

  public record SuffixRule(String suffix, String gender, double confidence, int support) {

    public SuffixRule {
      suffix = requireText(suffix, "suffix");
      gender = requireText(gender, "gender");
      if (!"masculine".equals(gender) && !"feminine".equals(gender)) {
        throw new IllegalArgumentException("Unsupported French suffix-rule gender: " + gender);
      }
      if (confidence < 0 || confidence > 1) {
        throw new IllegalArgumentException("confidence must be between 0 and 1: " + confidence);
      }
      if (support < 0) {
        throw new IllegalArgumentException("support must be non-negative: " + support);
      }
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
      int trainingSurfaces,
      int rules,
      int exportedRules,
      double suffixOnlyAccuracy,
      int suffixRuleBytes,
      int exportedSuffixRuleBytes,
      int maxSuffixLen,
      int minSupport,
      double minConfidence) {

    public Summary {
      if (trainingSurfaces < 0
          || rules < 0
          || exportedRules < 0
          || suffixRuleBytes < 0
          || exportedSuffixRuleBytes < 0
          || maxSuffixLen < 0
          || minSupport < 0) {
        throw new IllegalArgumentException("summary counts must be non-negative");
      }
      if (suffixOnlyAccuracy < 0
          || suffixOnlyAccuracy > 1
          || minConfidence < 0
          || minConfidence > 1) {
        throw new IllegalArgumentException("summary confidence values must be between 0 and 1");
      }
    }
  }
}
