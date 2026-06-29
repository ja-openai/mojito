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
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.requiredObjectRoot;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.requiredText;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.textArray;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.unmodifiableLinkedMap;

import com.box.l10n.mojito.json.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Loads the derived pronoun metadata pack for profile-only/no-op low-inflection locales. */
@GeneratorSupport
class PronounProfilePackJsonLoader {

  static final String EXPECTED_SCHEMA = "mojito-mf2-inflection/pronoun-profile-pack/v0";

  private static final Set<String> DATA_STATES =
      Set.of("empty-placeholder", "git-lfs-pointer", "materialized", "missing");
  private static final Set<String> MODES =
      Set.of("data-materialization-required", "profile-only-noop");

  private final ObjectMapper objectMapper;

  PronounProfilePackJsonLoader() {
    this(new ObjectMapper());
  }

  PronounProfilePackJsonLoader(ObjectMapper objectMapper) {
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
  }

  PronounProfilePack load(String json) {
    Objects.requireNonNull(json, "json");
    return load(objectMapper.readTreeUnchecked(json));
  }

  PronounProfilePack load(JsonNode root) {
    requiredObjectRoot(root, "pronoun profile pack");
    String schema = requiredText(root, "schema");
    if (!EXPECTED_SCHEMA.equals(schema)) {
      throw new IllegalArgumentException("Expected pronoun profile pack schema");
    }

    PronounProfilePack pack =
        new PronounProfilePack(
            schema,
            loadProvenance(requiredObject(root, "provenance")),
            loadSummary(requiredObject(root, "summary")),
            loadLocaleProfiles(requiredArray(root, "locales")));
    validateSummary(pack);
    validateLocaleProfiles(pack.locales());
    return pack;
  }

  private Provenance loadProvenance(JsonNode node) {
    return new Provenance(
        requiredText(node, "license"),
        requiredText(node, "generator"),
        requiredText(node, "sourceAudit"),
        requiredText(node, "sourceAuditSchema"));
  }

  private Summary loadSummary(JsonNode node) {
    return new Summary(
        requiredInt(node, "localeCount"),
        textArray(
            requiredArray(node, "profileOnlyLocales"),
            "profileOnlyLocales",
            null,
            "pronoun profile"),
        textArray(
            requiredArray(node, "dataMaterializationRequiredLocales"),
            "dataMaterializationRequiredLocales",
            null,
            "pronoun profile"),
        textArray(
            requiredArray(node, "profileOnlyNoopLocales"),
            "profileOnlyNoopLocales",
            null,
            "pronoun profile"),
        textArray(
            requiredArray(node, "pronounInventoryLocales"),
            "pronounInventoryLocales",
            null,
            "pronoun profile"),
        requiredBoolean(node, "runtimeTermInflection"),
        requiredInt(node, "totalPronounRows"),
        requiredInt(node, "totalUniquePronounValues"));
  }

  private List<LocaleProfile> loadLocaleProfiles(JsonNode node) {
    List<LocaleProfile> profiles = new ArrayList<>();
    for (JsonNode profileNode : node) {
      profiles.add(
          new LocaleProfile(
              requiredText(profileNode, "locale"),
              requiredAllowedText(profileNode, "mode", MODES, "pronoun profile"),
              requiredBoolean(profileNode, "profileOnly"),
              requiredBoolean(profileNode, "runtimeTermInflection"),
              requiredAllowedText(profileNode, "dictionaryState", DATA_STATES, "pronoun profile"),
              requiredAllowedText(profileNode, "inflectionalState", DATA_STATES, "pronoun profile"),
              loadSource(requiredObject(profileNode, "pronounSource")),
              loadPronouns(requiredObject(profileNode, "pronouns"))));
    }
    return List.copyOf(profiles);
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
        integerMap(requiredObject(node, "features"), "features", null, "pronoun profile"),
        integerMap(requiredObject(node, "cases"), "cases", null, "pronoun profile"),
        integerMap(requiredObject(node, "genders"), "genders", null, "pronoun profile"),
        integerMap(requiredObject(node, "numbers"), "numbers", null, "pronoun profile"),
        integerMap(requiredObject(node, "persons"), "persons", null, "pronoun profile"),
        integerMap(requiredObject(node, "registers"), "registers", null, "pronoun profile"),
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
                  requiredArray(sampleNode, "features"), "features", null, "pronoun profile")));
    }
    return List.copyOf(samples);
  }

  private void validateSummary(PronounProfilePack pack) {
    Summary summary = pack.summary();
    if (summary.localeCount() != pack.locales().size()) {
      throw new IllegalArgumentException("Pronoun profile locale count does not match profiles");
    }
    if (summary.runtimeTermInflection()) {
      throw new IllegalArgumentException("Pronoun profile pack must not enable term inflection");
    }

    List<String> profileOnlyLocales =
        pack.locales().stream()
            .filter(LocaleProfile::profileOnly)
            .map(LocaleProfile::locale)
            .toList();
    if (!summary.profileOnlyLocales().equals(profileOnlyLocales)) {
      throw new IllegalArgumentException("Pronoun profile-only locales do not match profiles");
    }

    List<String> materializationRequired =
        pack.locales().stream()
            .filter(profile -> "data-materialization-required".equals(profile.mode()))
            .map(LocaleProfile::locale)
            .toList();
    if (!summary.dataMaterializationRequiredLocales().equals(materializationRequired)) {
      throw new IllegalArgumentException(
          "Pronoun profile materialization-required locales do not match profiles");
    }

    List<String> profileOnlyNoop =
        pack.locales().stream()
            .filter(profile -> "profile-only-noop".equals(profile.mode()))
            .map(LocaleProfile::locale)
            .toList();
    if (!summary.profileOnlyNoopLocales().equals(profileOnlyNoop)) {
      throw new IllegalArgumentException("Pronoun profile no-op locales do not match profiles");
    }

    List<String> pronounInventory =
        pack.locales().stream()
            .filter(profile -> profile.pronouns().rows() > 0)
            .map(LocaleProfile::locale)
            .toList();
    if (!summary.pronounInventoryLocales().equals(pronounInventory)) {
      throw new IllegalArgumentException("Pronoun profile inventory locales do not match profiles");
    }

    int totalRows = pack.locales().stream().mapToInt(profile -> profile.pronouns().rows()).sum();
    if (summary.totalPronounRows() != totalRows) {
      throw new IllegalArgumentException("Pronoun profile row total does not match profiles");
    }

    int totalUnique =
        pack.locales().stream().mapToInt(profile -> profile.pronouns().uniqueValues()).sum();
    if (summary.totalUniquePronounValues() != totalUnique) {
      throw new IllegalArgumentException(
          "Pronoun profile unique-value total does not match profiles");
    }
  }

  private void validateLocaleProfiles(List<LocaleProfile> profiles) {
    for (LocaleProfile profile : profiles) {
      if (!profile.profileOnly()) {
        throw new IllegalArgumentException("Pronoun profile locale must be profile-only");
      }
      if (profile.runtimeTermInflection()) {
        throw new IllegalArgumentException(
            "Pronoun profile locale must not enable term inflection");
      }
      validatePronounSource(profile);
      validatePronouns(profile.pronouns());
      validateMode(profile);
    }
  }

  private void validatePronounSource(LocaleProfile profile) {
    Source source = profile.pronounSource();
    if (!source.exists()) {
      throw new IllegalArgumentException("Pronoun profile source must exist");
    }
    if (source.gitLfsPointer()) {
      throw new IllegalArgumentException("Pronoun profile source must be materialized");
    }
    if (source.byteSize() == null || source.byteSize() <= 0) {
      throw new IllegalArgumentException("Pronoun profile source requires positive byteSize");
    }
    if (source.sha256() == null) {
      throw new IllegalArgumentException("Pronoun profile source requires sha256");
    }
    if (profile.pronouns().rows() == 0) {
      throw new IllegalArgumentException("Pronoun profile locale requires pronoun rows");
    }
  }

  private void validatePronouns(Pronouns pronouns) {
    if (pronouns.uniqueValues() > pronouns.rows()) {
      throw new IllegalArgumentException("Pronoun profile unique values exceed rows");
    }
    if (pronouns.samples().size() > pronouns.rows()) {
      throw new IllegalArgumentException("Pronoun profile samples exceed rows");
    }
  }

  private void validateMode(LocaleProfile profile) {
    if ("data-materialization-required".equals(profile.mode())
        && !("git-lfs-pointer".equals(profile.dictionaryState())
            || "git-lfs-pointer".equals(profile.inflectionalState()))) {
      throw new IllegalArgumentException(
          "Pronoun profile materialization-required mode needs an LFS pointer");
    }
    if ("profile-only-noop".equals(profile.mode())
        && (!("missing".equals(profile.dictionaryState())
                || "empty-placeholder".equals(profile.dictionaryState()))
            || !"missing".equals(profile.inflectionalState()))) {
      throw new IllegalArgumentException(
          "Pronoun profile no-op mode requires missing or empty dictionary and missing inflectional data");
    }
  }

  public record PronounProfilePack(
      String schema, Provenance provenance, Summary summary, List<LocaleProfile> locales) {

    public PronounProfilePack {
      schema = requireText(schema, "schema");
      provenance = Objects.requireNonNull(provenance, "provenance");
      summary = Objects.requireNonNull(summary, "summary");
      locales = List.copyOf(Objects.requireNonNull(locales, "locales"));
    }
  }

  public record Provenance(
      String license, String generator, String sourceAudit, String sourceAuditSchema) {

    public Provenance {
      license = requireText(license, "license");
      generator = requireText(generator, "generator");
      sourceAudit = requireText(sourceAudit, "sourceAudit");
      sourceAuditSchema = requireText(sourceAuditSchema, "sourceAuditSchema");
    }
  }

  public record Summary(
      int localeCount,
      List<String> profileOnlyLocales,
      List<String> dataMaterializationRequiredLocales,
      List<String> profileOnlyNoopLocales,
      List<String> pronounInventoryLocales,
      boolean runtimeTermInflection,
      int totalPronounRows,
      int totalUniquePronounValues) {

    public Summary {
      if (localeCount < 0 || totalPronounRows < 0 || totalUniquePronounValues < 0) {
        throw new IllegalArgumentException("pronoun profile summary counts must be non-negative");
      }
      profileOnlyLocales =
          List.copyOf(Objects.requireNonNull(profileOnlyLocales, "profileOnlyLocales"));
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

  public record LocaleProfile(
      String locale,
      String mode,
      boolean profileOnly,
      boolean runtimeTermInflection,
      String dictionaryState,
      String inflectionalState,
      Source pronounSource,
      Pronouns pronouns) {

    public LocaleProfile {
      locale = requireText(locale, "locale");
      mode = requireText(mode, "mode");
      dictionaryState = requireText(dictionaryState, "dictionaryState");
      inflectionalState = requireText(inflectionalState, "inflectionalState");
      pronounSource = Objects.requireNonNull(pronounSource, "pronounSource");
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
        throw new IllegalArgumentException("missing pronoun source must not carry file metadata");
      }
      if (gitLfsPointer && (gitLfsOidSha256 == null || gitLfsObjectSize == null)) {
        throw new IllegalArgumentException("Git LFS pronoun source requires oid and object size");
      }
      if (!gitLfsPointer && (gitLfsOidSha256 != null || gitLfsObjectSize != null)) {
        throw new IllegalArgumentException(
            "non-Git LFS pronoun source must not carry LFS metadata");
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
        throw new IllegalArgumentException("pronoun profile counts must be non-negative");
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
        throw new IllegalArgumentException("pronoun profile sample line must be positive");
      }
      value = requireText(value, "value");
      features = List.copyOf(Objects.requireNonNull(features, "features"));
    }
  }
}
