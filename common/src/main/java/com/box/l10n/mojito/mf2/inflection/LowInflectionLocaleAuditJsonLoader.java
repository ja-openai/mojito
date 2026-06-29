package com.box.l10n.mojito.mf2.inflection;

import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.integerMap;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.optionalLong;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.optionalText;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.requireText;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.requiredAllowedText;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.requiredArray;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.requiredBoolean;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.requiredInt;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.requiredObject;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.requiredText;
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

/** Loads the generated low-inflection locale audit for profile-only/no-op planning. */
@GeneratorSupport
class LowInflectionLocaleAuditJsonLoader {

  static final String EXPECTED_SCHEMA = "mojito-mf2-inflection/low-inflection-locale-audit/v0";

  private static final Set<String> DATA_STATES =
      Set.of("empty-placeholder", "git-lfs-pointer", "materialized", "missing");
  private static final Set<String> RECOMMENDATION_MODES =
      Set.of(
          "data-materialization-required",
          "dictionary-inventory-only",
          "manual-review",
          "profile-only-noop");

  private final ObjectMapper objectMapper;

  LowInflectionLocaleAuditJsonLoader() {
    this(new ObjectMapper());
  }

  LowInflectionLocaleAuditJsonLoader(ObjectMapper objectMapper) {
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
  }

  public LowInflectionLocaleAudit load(String json) {
    Objects.requireNonNull(json, "json");
    return load(objectMapper.readTreeUnchecked(json));
  }

  public LowInflectionLocaleAudit load(JsonNode root) {
    String schema = requiredText(root, "schema");
    if (!EXPECTED_SCHEMA.equals(schema)) {
      throw new IllegalArgumentException("Expected low-inflection locale audit schema");
    }

    LowInflectionLocaleAudit audit =
        new LowInflectionLocaleAudit(
            schema,
            loadProvenance(requiredObject(root, "provenance")),
            loadSummary(requiredObject(root, "summary")),
            loadLocaleReports(requiredArray(root, "locales")));
    validateSummary(audit);
    validateLocaleReports(audit.locales());
    return audit;
  }

  private Provenance loadProvenance(JsonNode node) {
    return new Provenance(
        requiredText(node, "license"),
        requiredText(node, "generator"),
        requiredText(node, "unicodeRoot"));
  }

  private Summary loadSummary(JsonNode node) {
    return new Summary(
        requiredInt(node, "localeCount"),
        textArray(requiredArray(node, "locales"), "locales", null, "low-inflection"),
        integerMap(
            requiredObject(node, "modeCounts"),
            "modeCounts",
            RECOMMENDATION_MODES,
            "low-inflection"),
        textArray(
            requiredArray(node, "dataMaterializationRequiredLocales"),
            "dataMaterializationRequiredLocales",
            null,
            "low-inflection"),
        textArray(
            requiredArray(node, "profileOnlyNoopLocales"),
            "profileOnlyNoopLocales",
            null,
            "low-inflection"),
        textArray(
            requiredArray(node, "pronounInventoryLocales"),
            "pronounInventoryLocales",
            null,
            "low-inflection"));
  }

  private List<LocaleReport> loadLocaleReports(JsonNode node) {
    List<LocaleReport> reports = new ArrayList<>();
    for (JsonNode reportNode : node) {
      reports.add(
          new LocaleReport(
              requiredText(reportNode, "locale"),
              loadDataState(requiredObject(reportNode, "dataState")),
              loadSources(requiredObject(reportNode, "sources")),
              loadPronouns(requiredObject(reportNode, "pronouns")),
              loadRecommendation(requiredObject(reportNode, "recommendation"))));
    }
    return List.copyOf(reports);
  }

  private DataState loadDataState(JsonNode node) {
    return new DataState(
        requiredAllowedText(node, "dictionary", DATA_STATES, "low-inflection"),
        requiredAllowedText(node, "inflectional", DATA_STATES, "low-inflection"),
        requiredAllowedText(node, "pronouns", DATA_STATES, "low-inflection"));
  }

  private Sources loadSources(JsonNode node) {
    return new Sources(
        loadSource(requiredObject(node, "dictionary")),
        loadSource(requiredObject(node, "inflectional")),
        loadSource(requiredObject(node, "pronouns")));
  }

  private Source loadSource(JsonNode node) {
    return new Source(
        requiredText(node, "path"),
        requiredBoolean(node, "exists"),
        optionalLong(node, "byteSize"),
        requiredBoolean(node, "gitLfsPointer"),
        optionalText(node, "sha256"),
        optionalText(node, "gitLfsOidSha256"),
        optionalLong(node, "gitLfsObjectSize"));
  }

  private Pronouns loadPronouns(JsonNode node) {
    return new Pronouns(
        requiredInt(node, "rows"),
        requiredInt(node, "uniqueValues"),
        integerMap(requiredObject(node, "features"), "features", null, "low-inflection"),
        integerMap(requiredObject(node, "cases"), "cases", null, "low-inflection"),
        integerMap(requiredObject(node, "genders"), "genders", null, "low-inflection"),
        integerMap(requiredObject(node, "numbers"), "numbers", null, "low-inflection"),
        integerMap(requiredObject(node, "persons"), "persons", null, "low-inflection"),
        integerMap(requiredObject(node, "registers"), "registers", null, "low-inflection"),
        loadPronounSamples(requiredArray(node, "samples")));
  }

  private List<PronounSample> loadPronounSamples(JsonNode node) {
    List<PronounSample> samples = new ArrayList<>();
    for (JsonNode sampleNode : node) {
      samples.add(
          new PronounSample(
              requiredInt(sampleNode, "line"),
              requiredText(sampleNode, "value"),
              textArray(
                  requiredArray(sampleNode, "features"), "features", null, "low-inflection")));
    }
    return List.copyOf(samples);
  }

  private Recommendation loadRecommendation(JsonNode node) {
    return new Recommendation(
        requiredAllowedText(node, "mode", RECOMMENDATION_MODES, "low-inflection"),
        requiredBoolean(node, "runtimeTermInflection"),
        requiredBoolean(node, "profileOnly"),
        requiredText(node, "reason"),
        requiredText(node, "nextAction"));
  }

  private void validateSummary(LowInflectionLocaleAudit audit) {
    Summary summary = audit.summary();
    if (summary.localeCount() != audit.locales().size()) {
      throw new IllegalArgumentException("Low-inflection locale count does not match reports");
    }
    List<String> locales = audit.locales().stream().map(LocaleReport::locale).toList();
    if (!summary.locales().equals(locales)) {
      throw new IllegalArgumentException("Low-inflection summary locales do not match reports");
    }

    Map<String, Integer> modeCounts = new LinkedHashMap<>();
    for (LocaleReport report : audit.locales()) {
      modeCounts.merge(report.recommendation().mode(), 1, Integer::sum);
    }
    if (!summary.modeCounts().equals(modeCounts)) {
      throw new IllegalArgumentException("Low-inflection mode counts do not match reports");
    }

    List<String> materializationRequired =
        audit.locales().stream()
            .filter(
                report -> "data-materialization-required".equals(report.recommendation().mode()))
            .map(LocaleReport::locale)
            .toList();
    if (!summary.dataMaterializationRequiredLocales().equals(materializationRequired)) {
      throw new IllegalArgumentException(
          "Low-inflection materialization-required locales do not match reports");
    }

    List<String> profileOnlyNoop =
        audit.locales().stream()
            .filter(report -> "profile-only-noop".equals(report.recommendation().mode()))
            .map(LocaleReport::locale)
            .toList();
    if (!summary.profileOnlyNoopLocales().equals(profileOnlyNoop)) {
      throw new IllegalArgumentException(
          "Low-inflection profile-only locales do not match reports");
    }

    List<String> pronounInventory =
        audit.locales().stream()
            .filter(report -> report.pronouns().rows() > 0)
            .map(LocaleReport::locale)
            .toList();
    if (!summary.pronounInventoryLocales().equals(pronounInventory)) {
      throw new IllegalArgumentException(
          "Low-inflection pronoun inventory locales do not match reports");
    }
  }

  private void validateLocaleReports(List<LocaleReport> reports) {
    for (LocaleReport report : reports) {
      validateSourceState(
          "dictionary", report.dataState().dictionary(), report.sources().dictionary());
      validateSourceState(
          "inflectional", report.dataState().inflectional(), report.sources().inflectional());
      validateSourceState("pronouns", report.dataState().pronouns(), report.sources().pronouns());
      validatePronouns(report);
      validateRecommendation(report);
    }
  }

  private void validateSourceState(String field, String state, Source source) {
    String expected;
    if (!source.exists()) {
      expected = "missing";
    } else if (source.gitLfsPointer()) {
      expected = "git-lfs-pointer";
    } else if (source.byteSize() != null && source.byteSize() == 0L) {
      expected = "empty-placeholder";
    } else {
      expected = "materialized";
    }
    if (!state.equals(expected)) {
      throw new IllegalArgumentException(
          "Low-inflection " + field + " state does not match source metadata");
    }
  }

  private void validatePronouns(LocaleReport report) {
    Pronouns pronouns = report.pronouns();
    if (pronouns.uniqueValues() > pronouns.rows()) {
      throw new IllegalArgumentException("Low-inflection pronoun unique values exceed rows");
    }
    if (pronouns.samples().size() > pronouns.rows()) {
      throw new IllegalArgumentException("Low-inflection pronoun samples exceed rows");
    }
    if (pronouns.rows() > 0 && !"materialized".equals(report.dataState().pronouns())) {
      throw new IllegalArgumentException(
          "Low-inflection pronoun rows require materialized pronouns");
    }
  }

  private void validateRecommendation(LocaleReport report) {
    Recommendation recommendation = report.recommendation();
    if (recommendation.runtimeTermInflection()) {
      throw new IllegalArgumentException("Low-inflection audit must not enable term inflection");
    }
    if (!recommendation.profileOnly()
        && ("data-materialization-required".equals(recommendation.mode())
            || "dictionary-inventory-only".equals(recommendation.mode())
            || "profile-only-noop".equals(recommendation.mode()))) {
      throw new IllegalArgumentException(
          "Low-inflection profile-only recommendation mode must be profile-only");
    }
    if ("data-materialization-required".equals(recommendation.mode())
        && !("git-lfs-pointer".equals(report.dataState().dictionary())
            || "git-lfs-pointer".equals(report.dataState().inflectional()))) {
      throw new IllegalArgumentException(
          "Low-inflection materialization-required mode needs an LFS pointer");
    }
    if ("profile-only-noop".equals(recommendation.mode())
        && !("missing".equals(report.dataState().dictionary())
            || "empty-placeholder".equals(report.dataState().dictionary()))) {
      throw new IllegalArgumentException(
          "Low-inflection profile-only mode requires missing or empty dictionary");
    }
  }

  public record LowInflectionLocaleAudit(
      String schema, Provenance provenance, Summary summary, List<LocaleReport> locales) {

    public LowInflectionLocaleAudit {
      schema = requireText(schema, "schema");
      provenance = Objects.requireNonNull(provenance, "provenance");
      summary = Objects.requireNonNull(summary, "summary");
      locales = List.copyOf(Objects.requireNonNull(locales, "locales"));
    }
  }

  public record Provenance(String license, String generator, String unicodeRoot) {

    public Provenance {
      license = requireText(license, "license");
      generator = requireText(generator, "generator");
      unicodeRoot = requireText(unicodeRoot, "unicodeRoot");
    }
  }

  public record Summary(
      int localeCount,
      List<String> locales,
      Map<String, Integer> modeCounts,
      List<String> dataMaterializationRequiredLocales,
      List<String> profileOnlyNoopLocales,
      List<String> pronounInventoryLocales) {

    public Summary {
      if (localeCount < 0) {
        throw new IllegalArgumentException("localeCount must be non-negative");
      }
      locales = List.copyOf(Objects.requireNonNull(locales, "locales"));
      modeCounts = unmodifiableLinkedMap(Objects.requireNonNull(modeCounts, "modeCounts"));
      dataMaterializationRequiredLocales =
          List.copyOf(
              Objects.requireNonNull(
                  dataMaterializationRequiredLocales, "dataMaterializationRequiredLocales"));
      profileOnlyNoopLocales =
          List.copyOf(Objects.requireNonNull(profileOnlyNoopLocales, "profileOnlyNoopLocales"));
      pronounInventoryLocales =
          List.copyOf(Objects.requireNonNull(pronounInventoryLocales, "pronounInventoryLocales"));
    }
  }

  public record LocaleReport(
      String locale,
      DataState dataState,
      Sources sources,
      Pronouns pronouns,
      Recommendation recommendation) {

    public LocaleReport {
      locale = requireText(locale, "locale");
      dataState = Objects.requireNonNull(dataState, "dataState");
      sources = Objects.requireNonNull(sources, "sources");
      pronouns = Objects.requireNonNull(pronouns, "pronouns");
      recommendation = Objects.requireNonNull(recommendation, "recommendation");
    }
  }

  public record DataState(String dictionary, String inflectional, String pronouns) {}

  public record Sources(Source dictionary, Source inflectional, Source pronouns) {

    public Sources {
      dictionary = Objects.requireNonNull(dictionary, "dictionary");
      inflectional = Objects.requireNonNull(inflectional, "inflectional");
      pronouns = Objects.requireNonNull(pronouns, "pronouns");
    }
  }

  public record Source(
      String path,
      boolean exists,
      Long byteSize,
      boolean gitLfsPointer,
      String sha256,
      String gitLfsOidSha256,
      Long gitLfsObjectSize) {

    public Source {
      path = requireText(path, "path");
      if (!exists
          && (byteSize != null
              || gitLfsPointer
              || sha256 != null
              || gitLfsOidSha256 != null
              || gitLfsObjectSize != null)) {
        throw new IllegalArgumentException("missing source must not carry file metadata");
      }
      if (exists && byteSize == null) {
        throw new IllegalArgumentException("existing source requires byteSize");
      }
      if (exists && sha256 == null) {
        throw new IllegalArgumentException("existing source requires sha256");
      }
      if (gitLfsPointer && (gitLfsOidSha256 == null || gitLfsObjectSize == null)) {
        throw new IllegalArgumentException("Git LFS source requires oid and object size");
      }
      if (!gitLfsPointer && (gitLfsOidSha256 != null || gitLfsObjectSize != null)) {
        throw new IllegalArgumentException("non-Git LFS source must not carry LFS metadata");
      }
      if (byteSize != null && byteSize < 0) {
        throw new IllegalArgumentException("byteSize must be non-negative");
      }
      if (gitLfsObjectSize != null && gitLfsObjectSize < 0) {
        throw new IllegalArgumentException("gitLfsObjectSize must be non-negative");
      }
    }
  }

  public record Pronouns(
      int rows,
      int uniqueValues,
      Map<String, Integer> features,
      Map<String, Integer> cases,
      Map<String, Integer> genders,
      Map<String, Integer> numbers,
      Map<String, Integer> persons,
      Map<String, Integer> registers,
      List<PronounSample> samples) {

    public Pronouns {
      if (rows < 0 || uniqueValues < 0) {
        throw new IllegalArgumentException("pronoun counts must be non-negative");
      }
      features = unmodifiableLinkedMap(Objects.requireNonNull(features, "features"));
      cases = unmodifiableLinkedMap(Objects.requireNonNull(cases, "cases"));
      genders = unmodifiableLinkedMap(Objects.requireNonNull(genders, "genders"));
      numbers = unmodifiableLinkedMap(Objects.requireNonNull(numbers, "numbers"));
      persons = unmodifiableLinkedMap(Objects.requireNonNull(persons, "persons"));
      registers = unmodifiableLinkedMap(Objects.requireNonNull(registers, "registers"));
      samples = List.copyOf(Objects.requireNonNull(samples, "samples"));
    }
  }

  public record PronounSample(int line, String value, List<String> features) {

    public PronounSample {
      if (line <= 0) {
        throw new IllegalArgumentException("pronoun sample line must be positive");
      }
      value = requireText(value, "value");
      features = List.copyOf(Objects.requireNonNull(features, "features"));
    }
  }

  public record Recommendation(
      String mode,
      boolean runtimeTermInflection,
      boolean profileOnly,
      String reason,
      String nextAction) {

    public Recommendation {
      mode = requireText(mode, "mode");
      reason = requireText(reason, "reason");
      nextAction = requireText(nextAction, "nextAction");
    }
  }
}
