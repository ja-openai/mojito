package com.box.l10n.mojito.service.glossary;

import com.box.l10n.mojito.entity.TMTextUnit;
import com.box.l10n.mojito.entity.glossary.GlossaryTermInflectionProfile;
import com.box.l10n.mojito.entity.glossary.GlossaryTermMetadata;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.mf2.inflection.CompiledTermPack;
import com.box.l10n.mojito.mf2.inflection.CompiledTermPack.ExportPolicy;
import com.box.l10n.mojito.mf2.inflection.TermInflectionDiagnostics;
import com.box.l10n.mojito.mf2.inflection.TermInflectionDiagnostics.DiagnosticSummary;
import com.box.l10n.mojito.mf2.inflection.TermInflectionProfilePackJsonLoader;
import com.box.l10n.mojito.mf2.inflection.TermInflectionProfilePackJsonLoader.Diagnostic;
import com.box.l10n.mojito.mf2.inflection.TermInflectionProfilePackJsonLoader.TermInflectionProfilePack;
import com.box.l10n.mojito.service.security.user.UserService;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.UncheckedIOException;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GlossaryTermInflectionProfileService {

  private static final String PROFILE_PACK_GENERATOR = "GlossaryTermInflectionProfileService";
  private static final String PROFILE_PACK_LICENSE = "Mojito-authored";
  private static final String COMPILED_PROFILE_PACK_RUNTIME_EXPORT =
      "closed-world-glossary-approved-profile-forms";
  private static final String COMPILED_PROFILE_PACK_COMPOSITION_MODE = "explicit-form-rows-v0";
  private static final String COMPILED_PROFILE_PACK_DISABLED_REASON = "disabled-profile";
  private static final int INFLECTION_PROFILE_PACK_IMPORT_CONTENT_MAX_CHARS = 5_000_000;
  private static final int INFLECTION_PROFILE_JSON_FIELD_MAX_CHARS = 256_000;
  private static final int INFLECTION_PROFILE_PACK_STORED_JSON_FIELDS_MAX_CHARS =
      INFLECTION_PROFILE_PACK_IMPORT_CONTENT_MAX_CHARS;
  private static final int INFLECTION_PROFILE_PACK_MAX_PROFILE_COUNT = 10_000;
  private static final Pattern SHA256_HEX_PATTERN = Pattern.compile("[0-9a-f]{64}");

  private final GlossaryTermInflectionProfileRepository profileRepository;
  private final GlossaryTermMetadataRepository glossaryTermMetadataRepository;
  private final UserService userService;
  private final ObjectMapper objectMapper;
  private final TermInflectionProfilePackJsonLoader profilePackJsonLoader;

  public GlossaryTermInflectionProfileService(
      GlossaryTermInflectionProfileRepository profileRepository,
      GlossaryTermMetadataRepository glossaryTermMetadataRepository,
      UserService userService,
      ObjectMapper objectMapper) {
    this.profileRepository = profileRepository;
    this.glossaryTermMetadataRepository = glossaryTermMetadataRepository;
    this.userService = userService;
    this.objectMapper = objectMapper;
    this.profilePackJsonLoader = new TermInflectionProfilePackJsonLoader(objectMapper);
  }

  @Transactional(readOnly = true)
  public List<InflectionProfileView> getProfiles(Long glossaryId, String localeTag) {
    requireGlossaryReader();
    return getProfilesForSystem(glossaryId, localeTag);
  }

  @Transactional(readOnly = true)
  public List<InflectionProfileView> getProfilesForSystem(Long glossaryId, String localeTag) {
    String normalizedLocaleTag = normalizeLocaleTag(localeTag);
    requireStoredProfilePackWithinLimit(glossaryId, normalizedLocaleTag);
    List<GlossaryTermInflectionProfile> profiles =
        profileRepository.findByGlossaryIdAndLocaleTag(glossaryId, normalizedLocaleTag);
    requireProfilePackCountWithinLimit(profiles.size(), "stored locale " + normalizedLocaleTag);
    requireStoredProfileJsonFieldsValidAndWithinLimit(
        profiles, "stored locale " + normalizedLocaleTag);
    return profiles.stream().map(this::toView).toList();
  }

  @Transactional
  public InflectionProfileView upsertProfile(
      Long glossaryId, Long tmTextUnitId, InflectionProfileInput input) {
    requireTermManager();
    return upsertProfileForSystem(glossaryId, tmTextUnitId, input);
  }

  @Transactional
  public InflectionProfileView upsertProfileForSystem(
      Long glossaryId, Long tmTextUnitId, InflectionProfileInput input) {
    if (input == null) {
      throw new IllegalArgumentException("Inflection profile input is required");
    }
    requireProfileJsonFieldsWithinLimit(input);

    GlossaryTermMetadata metadata =
        glossaryTermMetadataRepository
            .findByGlossaryIdAndTmTextUnitId(glossaryId, tmTextUnitId)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "Glossary term metadata not found: " + glossaryId + " / " + tmTextUnitId));
    String localeTag = normalizeLocaleTag(input.localeTag());
    GlossaryTermInflectionProfile profile =
        profileRepository
            .findByGlossaryTermMetadataIdAndLocaleTag(metadata.getId(), localeTag)
            .orElseGet(GlossaryTermInflectionProfile::new);
    profile.setGlossaryTermMetadata(metadata);
    profile.setLocaleTag(localeTag);
    profile.setSchema(TermInflectionProfilePackJsonLoader.EXPECTED_SCHEMA);
    profile.setStatus(normalizeStatus(input.status()));
    profile.setMorphologyJson(canonicalJsonObject(input.morphologyJson(), "morphologyJson"));
    profile.setFormsJson(canonicalJsonObject(input.formsJson(), "formsJson"));
    profile.setDiagnosticsJson(canonicalJsonArray(input.diagnosticsJson(), "diagnosticsJson"));
    profile.setProvenanceJson(canonicalJsonObject(input.provenanceJson(), "provenanceJson"));

    profilePack(localeTag, List.of(profile));
    return toView(profileRepository.save(profile));
  }

  @Transactional
  public InflectionProfileView reviewProfile(
      Long glossaryId, Long tmTextUnitId, InflectionProfileReviewInput input) {
    requireTermManager();
    return reviewProfileForSystem(glossaryId, tmTextUnitId, input);
  }

  @Transactional
  public InflectionProfileView reviewProfileForSystem(
      Long glossaryId, Long tmTextUnitId, InflectionProfileReviewInput input) {
    if (input == null) {
      throw new IllegalArgumentException("Inflection profile review input is required");
    }
    requireProfileJsonFieldsWithinLimit(input);

    GlossaryTermMetadata metadata =
        glossaryTermMetadataRepository
            .findByGlossaryIdAndTmTextUnitId(glossaryId, tmTextUnitId)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "Glossary term metadata not found: " + glossaryId + " / " + tmTextUnitId));
    String localeTag = normalizeLocaleTag(input.localeTag());
    GlossaryTermInflectionProfile profile =
        profileRepository
            .findByGlossaryTermMetadataIdAndLocaleTag(metadata.getId(), localeTag)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "Inflection profile not found: "
                            + glossaryId
                            + " / "
                            + tmTextUnitId
                            + " / "
                            + localeTag));

    profile.setStatus(normalizeStatus(input.status()));
    if (input.morphologyJson() != null) {
      profile.setMorphologyJson(canonicalJsonObject(input.morphologyJson(), "morphologyJson"));
    }
    if (input.formsJson() != null) {
      profile.setFormsJson(canonicalJsonObject(input.formsJson(), "formsJson"));
    }
    if (input.diagnosticsJson() != null) {
      profile.setDiagnosticsJson(canonicalJsonArray(input.diagnosticsJson(), "diagnosticsJson"));
    }
    if (input.provenanceJson() != null) {
      profile.setProvenanceJson(canonicalJsonObject(input.provenanceJson(), "provenanceJson"));
    }

    profilePack(localeTag, List.of(profile));
    return toView(profileRepository.save(profile));
  }

  @Transactional(readOnly = true)
  public TermInflectionProfilePack profilePackForSystem(Long glossaryId, String localeTag) {
    String normalizedLocaleTag = normalizeLocaleTag(localeTag);
    requireStoredProfilePackWithinLimit(glossaryId, normalizedLocaleTag);
    return profilePack(
        normalizedLocaleTag,
        profileRepository.findByGlossaryIdAndLocaleTag(glossaryId, normalizedLocaleTag));
  }

  @Transactional(readOnly = true)
  public TermInflectionProfilePack profilePack(Long glossaryId, String localeTag) {
    requireGlossaryReader();
    return profilePackForSystem(glossaryId, localeTag);
  }

  @Transactional(readOnly = true)
  public CompiledTermPack compileProfilePackForSystem(Long glossaryId, String localeTag) {
    return compileProfilePackExportForSystem(glossaryId, localeTag).pack();
  }

  @Transactional(readOnly = true)
  public CompiledInflectionProfilePackExport compileProfilePackExportForSystem(
      Long glossaryId, String localeTag) {
    TermInflectionProfilePack pack = profilePackForSystem(glossaryId, localeTag);
    return reviewedCompiledExport(pack);
  }

  @Transactional(readOnly = true)
  public CompiledTermPack compileProfilePack(Long glossaryId, String localeTag) {
    requireGlossaryReader();
    return compileProfilePackForSystem(glossaryId, localeTag);
  }

  @Transactional(readOnly = true)
  public CompiledInflectionProfilePackExport compileProfilePackExport(
      Long glossaryId, String localeTag) {
    requireGlossaryReader();
    return compileProfilePackExportForSystem(glossaryId, localeTag);
  }

  @Transactional
  public InflectionProfileImportResult importProfilePack(Long glossaryId, String content) {
    requireTermManager();
    return importProfilePackForSystem(glossaryId, content);
  }

  @Transactional
  public InflectionProfileImportResult importProfilePackForSystem(Long glossaryId, String content) {
    TermInflectionProfilePack pack = loadProfilePackContent(content);
    requireProfilePackCountWithinLimit(pack.profiles().size(), "import content");
    List<InflectionProfileImportJsonFields> importJsonFields = importJsonFields(pack);
    Map<String, GlossaryTermMetadata> metadataByTermId = metadataByTermId(glossaryId);
    String localeTag = normalizeLocaleTag(pack.locale());
    List<InflectionProfileImportCandidate> importCandidates = new ArrayList<>();
    for (InflectionProfileImportJsonFields importedProfileJsonFields : importJsonFields) {
      TermInflectionProfilePackJsonLoader.Profile importedProfile =
          importedProfileJsonFields.importedProfile();
      GlossaryTermMetadata metadata = metadataForProfile(metadataByTermId, importedProfile);
      GlossaryTermInflectionProfile existingProfile =
          profileRepository
              .findByGlossaryTermMetadataIdAndLocaleTag(metadata.getId(), localeTag)
              .orElse(null);
      importCandidates.add(
          new InflectionProfileImportCandidate(
              importedProfileJsonFields, metadata, existingProfile));
    }

    List<InflectionProfileView> profiles = new ArrayList<>();
    int createdCount = 0;
    int updatedCount = 0;

    for (InflectionProfileImportCandidate candidate : importCandidates) {
      InflectionProfileImportJsonFields importedProfileJsonFields =
          candidate.importedProfileJsonFields();
      TermInflectionProfilePackJsonLoader.Profile importedProfile =
          importedProfileJsonFields.importedProfile();
      GlossaryTermInflectionProfile profile = candidate.existingProfile();
      boolean created = profile == null;
      if (created) {
        profile = new GlossaryTermInflectionProfile();
        createdCount++;
      } else {
        updatedCount++;
      }

      profile.setGlossaryTermMetadata(candidate.metadata());
      profile.setLocaleTag(localeTag);
      profile.setSchema(TermInflectionProfilePackJsonLoader.EXPECTED_SCHEMA);
      profile.setStatus(importedProfile.status());
      profile.setMorphologyJson(importedProfileJsonFields.morphologyJson());
      profile.setFormsJson(importedProfileJsonFields.formsJson());
      profile.setDiagnosticsJson(importedProfileJsonFields.diagnosticsJson());
      profile.setProvenanceJson(importedProfileJsonFields.provenanceJson());
      profiles.add(toView(profileRepository.save(profile)));
    }

    return new InflectionProfileImportResult(
        localeTag, profiles.size(), createdCount, updatedCount, profiles);
  }

  private TermInflectionProfilePack profilePack(
      String localeTag, List<GlossaryTermInflectionProfile> profiles) {
    requireProfilePackCountWithinLimit(profiles.size(), "stored locale " + localeTag);
    requireStoredProfileJsonFieldsValidAndWithinLimit(profiles, "stored locale " + localeTag);
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("schema", TermInflectionProfilePackJsonLoader.EXPECTED_SCHEMA);
    root.put("locale", localeTag);
    root.put("provenance", packProvenance(profiles));
    root.put("profiles", profileJsonRows(profiles));
    return profilePackJsonLoader.load(objectMapper.writeValueAsStringUnchecked(root));
  }

  private Map<String, Object> packProvenance(List<GlossaryTermInflectionProfile> profiles) {
    Map<String, Object> provenance = new LinkedHashMap<>();
    provenance.put("license", PROFILE_PACK_LICENSE);
    provenance.put("generator", PROFILE_PACK_GENERATOR);
    ProvenanceSources provenanceSources = provenanceSources(profiles);
    provenance.put("sourceLabels", provenanceSources.sourceLabels());
    provenance.put("sources", provenanceSources.sources());
    return provenance;
  }

  private List<Map<String, Object>> profileJsonRows(List<GlossaryTermInflectionProfile> profiles) {
    List<Map<String, Object>> rows = new ArrayList<>();
    for (GlossaryTermInflectionProfile profile : profiles) {
      GlossaryTermMetadata metadata = profile.getGlossaryTermMetadata();
      TMTextUnit tmTextUnit = metadata.getTmTextUnit();
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("termId", requireText(tmTextUnit.getName(), "termId"));
      row.put("source", requireText(tmTextUnit.getContent(), "source"));
      row.put("status", profile.getStatus());
      row.put("morphology", objectMapper.readTreeUnchecked(profile.getMorphologyJson()));
      row.put("forms", objectMapper.readTreeUnchecked(profile.getFormsJson()));
      row.put("diagnostics", objectMapper.readTreeUnchecked(profile.getDiagnosticsJson()));
      row.put("provenance", objectMapper.readTreeUnchecked(profile.getProvenanceJson()));
      rows.add(row);
    }
    return rows;
  }

  private CompiledInflectionProfilePackExport reviewedCompiledExport(
      TermInflectionProfilePack pack) {
    List<TermInflectionProfilePackJsonLoader.Profile> sortedProfiles =
        pack.profiles().stream()
            .sorted(Comparator.comparing(TermInflectionProfilePackJsonLoader.Profile::termId))
            .toList();
    int approvedProfileCount = 0;
    List<SkippedInflectionProfile> skippedProfiles = new ArrayList<>();
    for (TermInflectionProfilePackJsonLoader.Profile profile : sortedProfiles) {
      if (TermInflectionProfilePackJsonLoader.STATUS_APPROVED.equals(profile.status())) {
        approvedProfileCount++;
      } else if (TermInflectionProfilePackJsonLoader.STATUS_DISABLED.equals(profile.status())) {
        skippedProfiles.add(
            new SkippedInflectionProfile(
                profile.termId(),
                profile.source(),
                profile.status(),
                profile.diagnostics().size(),
                diagnosticSummaries(profile.diagnostics()),
                missingFormKeys(profile.diagnostics())));
      }
    }

    List<String> unapprovedProfiles =
        sortedProfiles.stream()
            .filter(
                profile ->
                    !TermInflectionProfilePackJsonLoader.STATUS_DISABLED.equals(profile.status()))
            .filter(
                profile ->
                    !TermInflectionProfilePackJsonLoader.STATUS_APPROVED.equals(profile.status()))
            .map(profile -> profile.termId() + "=" + profile.status())
            .sorted()
            .toList();
    if (!unapprovedProfiles.isEmpty()) {
      throw new IllegalArgumentException(
          "Cannot compile inflection profile pack for locale "
              + pack.locale()
              + " with unapproved profiles: "
              + String.join(", ", unapprovedProfiles)
              + ". Approve or disable generated/review-needed profiles before compiled export.");
    }
    CompiledTermPack compiledPack = pack.toCompiledTermPack();
    return new CompiledInflectionProfilePackExport(
        compiledPackWithExportPolicy(
            compiledPack, compiledExportPolicy(approvedProfileCount, skippedProfiles)),
        approvedProfileCount,
        skippedProfiles);
  }

  private ExportPolicy compiledExportPolicy(
      int approvedProfileCount, List<SkippedInflectionProfile> skippedProfiles) {
    Map<String, Integer> blockedReasons =
        skippedProfiles.isEmpty()
            ? Map.of()
            : Map.of(COMPILED_PROFILE_PACK_DISABLED_REASON, skippedProfiles.size());
    return new ExportPolicy(
        COMPILED_PROFILE_PACK_RUNTIME_EXPORT,
        COMPILED_PROFILE_PACK_COMPOSITION_MODE,
        List.of(),
        approvedProfileCount,
        0,
        skippedProfiles.size(),
        Map.of(),
        blockedReasons);
  }

  private List<DiagnosticSummary> diagnosticSummaries(List<Diagnostic> diagnostics) {
    return diagnostics.stream()
        .map(
            diagnostic ->
                new DiagnosticSummary(
                    diagnostic.code(),
                    diagnostic.reason(),
                    diagnostic.message(),
                    diagnostic.formKey(),
                    diagnostic.termId(),
                    null,
                    null,
                    null,
                    List.of(),
                    List.of()))
        .toList();
  }

  private List<String> missingFormKeys(List<Diagnostic> diagnostics) {
    Set<String> formKeys = new TreeSet<>();
    for (Diagnostic diagnostic : diagnostics) {
      if (isMissingFormCellDiagnostic(diagnostic) && diagnostic.formKey() != null) {
        formKeys.add(diagnostic.formKey());
      }
    }
    return List.copyOf(formKeys);
  }

  private boolean isMissingFormCellDiagnostic(Diagnostic diagnostic) {
    return TermInflectionDiagnostics.MISSING_FORM_CELL.equals(diagnostic.code())
        || TermInflectionDiagnostics.MISSING_FORM_CELL.equals(diagnostic.reason());
  }

  private CompiledTermPack compiledPackWithExportPolicy(
      CompiledTermPack pack, ExportPolicy exportPolicy) {
    return new CompiledTermPack(
        pack.schema(),
        pack.locale(),
        pack.strings(),
        pack.terms(),
        pack.formSets(),
        pack.provenance(),
        pack.sizeEstimates(),
        exportPolicy);
  }

  private String profileProvenanceJson(
      TermInflectionProfilePack pack, TermInflectionProfilePackJsonLoader.Profile importedProfile) {
    if (importedProfile.provenance() != null && !importedProfile.provenance().isEmpty()) {
      return objectMapper.writeValueAsStringUnchecked(importedProfile.provenance());
    }
    return objectMapper.writeValueAsStringUnchecked(pack.provenance());
  }

  private List<InflectionProfileImportJsonFields> importJsonFields(TermInflectionProfilePack pack) {
    List<InflectionProfileImportJsonFields> importJsonFields = new ArrayList<>();
    for (TermInflectionProfilePackJsonLoader.Profile importedProfile : pack.profiles()) {
      importJsonFields.add(importJsonFields(pack, importedProfile));
    }
    return importJsonFields;
  }

  private InflectionProfileImportJsonFields importJsonFields(
      TermInflectionProfilePack pack, TermInflectionProfilePackJsonLoader.Profile importedProfile) {
    return new InflectionProfileImportJsonFields(
        importedProfile,
        importedProfileJsonField(
            objectMapper.writeValueAsStringUnchecked(importedProfile.morphology()),
            "morphologyJson",
            importedProfile),
        importedProfileJsonField(
            objectMapper.writeValueAsStringUnchecked(importedProfile.forms()),
            "formsJson",
            importedProfile),
        importedProfileJsonField(
            objectMapper.writeValueAsStringUnchecked(importedProfile.diagnostics()),
            "diagnosticsJson",
            importedProfile),
        importedProfileJsonField(
            profileProvenanceJson(pack, importedProfile), "provenanceJson", importedProfile));
  }

  private String importedProfileJsonField(
      String value, String field, TermInflectionProfilePackJsonLoader.Profile importedProfile) {
    return requireMaxCharacters(
        value,
        "Imported inflection profile " + field + " for term " + importedProfile.termId(),
        INFLECTION_PROFILE_JSON_FIELD_MAX_CHARS);
  }

  private ProvenanceSources provenanceSources(List<GlossaryTermInflectionProfile> profiles) {
    List<String> sourceLabels = new ArrayList<>();
    List<Map<String, Object>> sources = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (GlossaryTermInflectionProfile profile : profiles) {
      JsonNode provenance = objectMapper.readTreeUnchecked(profile.getProvenanceJson());
      JsonNode labelNodes = provenance.get("sourceLabels");
      JsonNode sourceNodes = provenance.get("sources");
      if (labelNodes == null
          || sourceNodes == null
          || !labelNodes.isArray()
          || !sourceNodes.isArray()
          || labelNodes.size() != sourceNodes.size()) {
        continue;
      }
      for (int i = 0; i < labelNodes.size(); i++) {
        JsonNode labelNode = labelNodes.get(i);
        JsonNode sourceNode = sourceNodes.get(i);
        if (!labelNode.isTextual()
            || labelNode.asText().isBlank()
            || !isValidProvenanceSource(sourceNode)) {
          continue;
        }
        String key = labelNode.asText() + "\u0000" + sourceNode.get("path").asText();
        if (!seen.add(key)) {
          continue;
        }
        sourceLabels.add(labelNode.asText());
        sources.add(provenanceSource(sourceNode));
      }
    }
    return new ProvenanceSources(List.copyOf(sourceLabels), List.copyOf(sources));
  }

  private boolean isValidProvenanceSource(JsonNode sourceNode) {
    return sourceNode != null
        && sourceNode.isObject()
        && sourceNode.hasNonNull("path")
        && sourceNode.get("path").isTextual()
        && !sourceNode.get("path").asText().isBlank()
        && sourceNode.hasNonNull("byteSize")
        && sourceNode.get("byteSize").canConvertToLong()
        && sourceNode.get("byteSize").asLong() >= 0
        && sourceNode.hasNonNull("sha256")
        && sourceNode.get("sha256").isTextual()
        && SHA256_HEX_PATTERN.matcher(sourceNode.get("sha256").asText()).matches()
        && sourceNode.hasNonNull("gitLfsPointer")
        && sourceNode.get("gitLfsPointer").isBoolean();
  }

  private Map<String, Object> provenanceSource(JsonNode sourceNode) {
    Map<String, Object> source = new LinkedHashMap<>();
    source.put("path", sourceNode.get("path").asText());
    source.put("byteSize", sourceNode.get("byteSize").asLong());
    source.put("sha256", sourceNode.get("sha256").asText());
    source.put("gitLfsPointer", sourceNode.get("gitLfsPointer").asBoolean());
    return source;
  }

  private Map<String, GlossaryTermMetadata> metadataByTermId(Long glossaryId) {
    Map<String, GlossaryTermMetadata> metadataByTermId = new LinkedHashMap<>();
    for (GlossaryTermMetadata metadata :
        glossaryTermMetadataRepository.findByGlossaryId(glossaryId)) {
      TMTextUnit tmTextUnit = metadata.getTmTextUnit();
      String termId = requireText(tmTextUnit.getName(), "termId");
      GlossaryTermMetadata previous = metadataByTermId.put(termId, metadata);
      if (previous != null) {
        throw new IllegalArgumentException("Duplicate glossary term key: " + termId);
      }
    }
    return metadataByTermId;
  }

  private GlossaryTermMetadata metadataForProfile(
      Map<String, GlossaryTermMetadata> metadataByTermId,
      TermInflectionProfilePackJsonLoader.Profile importedProfile) {
    GlossaryTermMetadata metadata = metadataByTermId.get(importedProfile.termId());
    if (metadata == null) {
      throw new IllegalArgumentException(
          "Glossary term metadata not found for imported inflection profile: "
              + importedProfile.termId());
    }
    String source = requireText(metadata.getTmTextUnit().getContent(), "source");
    if (!source.equals(importedProfile.source())) {
      throw new IllegalArgumentException(
          "Imported inflection profile source mismatch for term "
              + importedProfile.termId()
              + ": expected "
              + source
              + ", got "
              + importedProfile.source());
    }
    return metadata;
  }

  private InflectionProfileView toView(GlossaryTermInflectionProfile profile) {
    GlossaryTermMetadata metadata = profile.getGlossaryTermMetadata();
    TMTextUnit tmTextUnit = metadata.getTmTextUnit();
    return new InflectionProfileView(
        profile.getId(),
        profile.getCreatedDate(),
        profile.getLastModifiedDate(),
        metadata.getId(),
        tmTextUnit.getId(),
        tmTextUnit.getName(),
        tmTextUnit.getContent(),
        profile.getLocaleTag(),
        profile.getSchema(),
        profile.getStatus(),
        profile.getMorphologyJson(),
        profile.getFormsJson(),
        profile.getDiagnosticsJson(),
        profile.getProvenanceJson());
  }

  private String canonicalJsonObject(String json, String field) {
    JsonNode node =
        requiredJson(
            requireMaxCharacters(json, field, INFLECTION_PROFILE_JSON_FIELD_MAX_CHARS), field);
    if (!node.isObject()) {
      throw new IllegalArgumentException(field + " must be a JSON object");
    }
    return objectMapper.writeValueAsStringUnchecked(node);
  }

  private String canonicalJsonArray(String json, String field) {
    JsonNode node =
        json == null || json.isBlank()
            ? objectMapper.createArrayNode()
            : requiredJson(
                requireMaxCharacters(json, field, INFLECTION_PROFILE_JSON_FIELD_MAX_CHARS), field);
    if (!node.isArray()) {
      throw new IllegalArgumentException(field + " must be a JSON array");
    }
    return objectMapper.writeValueAsStringUnchecked(node);
  }

  private JsonNode requiredJson(String json, String field) {
    if (json == null || json.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
    try {
      return objectMapper.readTreeUnchecked(json);
    } catch (UncheckedIOException e) {
      throw new IllegalArgumentException(field + " must be valid JSON", e);
    }
  }

  private String normalizeLocaleTag(String localeTag) {
    return requireText(localeTag, "localeTag");
  }

  private void requireStoredProfilePackWithinLimit(Long glossaryId, String localeTag) {
    String context = "stored locale " + localeTag;
    GlossaryTermInflectionProfileRepository.ProfilePackStorageBounds storageBounds =
        profileRepository.findStorageBoundsByGlossaryIdAndLocaleTag(
            glossaryId, localeTag, INFLECTION_PROFILE_JSON_FIELD_MAX_CHARS);
    long profileCount =
        storageBounds == null ? 0 : storageBoundValue(storageBounds.getProfileCount());
    long oversizedProfileCount =
        storageBounds == null
            ? 0
            : storageBoundValue(storageBounds.getOversizedJsonFieldProfileCount());
    long totalJsonFieldChars =
        storageBounds == null ? 0 : storageBoundValue(storageBounds.getTotalJsonFieldCharacters());
    requireProfilePackCountWithinLimit(profileCount, context);
    requireStoredProfileJsonFieldsWithinFetchLimit(oversizedProfileCount, context);
    requireStoredProfileJsonTotalWithinFetchLimit(totalJsonFieldChars, context);
  }

  private TermInflectionProfilePack loadProfilePackContent(String content) {
    try {
      return profilePackJsonLoader.load(
          requireTextWithin(content, "content", INFLECTION_PROFILE_PACK_IMPORT_CONTENT_MAX_CHARS));
    } catch (UncheckedIOException e) {
      throw new IllegalArgumentException("content must be valid inflection profile pack JSON", e);
    }
  }

  private void requireStoredProfileJsonFieldsValidAndWithinLimit(
      List<GlossaryTermInflectionProfile> profiles, String context) {
    long totalJsonFieldChars = 0;
    for (GlossaryTermInflectionProfile profile : profiles) {
      totalJsonFieldChars =
          requireStoredProfileJsonFieldWithinLimit(
              profile.getMorphologyJson(), "morphologyJson", context, totalJsonFieldChars);
      requireStoredProfileJsonObject(profile.getMorphologyJson(), "morphologyJson", context);
      totalJsonFieldChars =
          requireStoredProfileJsonFieldWithinLimit(
              profile.getFormsJson(), "formsJson", context, totalJsonFieldChars);
      requireStoredProfileJsonObject(profile.getFormsJson(), "formsJson", context);
      totalJsonFieldChars =
          requireStoredProfileJsonFieldWithinLimit(
              profile.getDiagnosticsJson(), "diagnosticsJson", context, totalJsonFieldChars);
      requireStoredProfileJsonArray(profile.getDiagnosticsJson(), "diagnosticsJson", context);
      totalJsonFieldChars =
          requireStoredProfileJsonFieldWithinLimit(
              profile.getProvenanceJson(), "provenanceJson", context, totalJsonFieldChars);
      requireStoredProfileJsonObject(profile.getProvenanceJson(), "provenanceJson", context);
    }
  }

  private void requireStoredProfileJsonFieldsWithinFetchLimit(
      long oversizedProfileCount, String context) {
    if (oversizedProfileCount > 0) {
      throw new IllegalArgumentException(
          "Stored inflection profile JSON fields for "
              + context
              + " must each be at most "
              + INFLECTION_PROFILE_JSON_FIELD_MAX_CHARS
              + " characters before loading profile rows: "
              + context
              + " has "
              + oversizedProfileCount
              + " profile(s) with oversized JSON fields");
    }
  }

  private void requireStoredProfileJsonTotalWithinFetchLimit(
      long totalJsonFieldChars, String context) {
    if (totalJsonFieldChars > INFLECTION_PROFILE_PACK_STORED_JSON_FIELDS_MAX_CHARS) {
      throw new IllegalArgumentException(
          "Stored inflection profile JSON fields for "
              + context
              + " must total at most "
              + INFLECTION_PROFILE_PACK_STORED_JSON_FIELDS_MAX_CHARS
              + " characters before loading profile rows: "
              + context
              + " has "
              + totalJsonFieldChars);
    }
  }

  private static long storageBoundValue(Long value) {
    return value == null ? 0 : value;
  }

  private void requireStoredProfileJsonObject(String value, String field, String context) {
    String label = storedProfileJsonFieldLabel(field, context);
    JsonNode node = requiredJson(value, label);
    if (!node.isObject()) {
      throw new IllegalArgumentException(label + " must be a JSON object");
    }
  }

  private void requireStoredProfileJsonArray(String value, String field, String context) {
    String label = storedProfileJsonFieldLabel(field, context);
    JsonNode node = requiredJson(value, label);
    if (!node.isArray()) {
      throw new IllegalArgumentException(label + " must be a JSON array");
    }
  }

  private String storedProfileJsonFieldLabel(String field, String context) {
    return "Stored inflection profile " + field + " for " + context;
  }

  private long requireStoredProfileJsonFieldWithinLimit(
      String value, String field, String context, long totalJsonFieldChars) {
    requireMaxCharacters(
        value,
        "Stored inflection profile " + field + " for " + context,
        INFLECTION_PROFILE_JSON_FIELD_MAX_CHARS);
    long nextTotalJsonFieldChars = totalJsonFieldChars + textLength(value);
    if (nextTotalJsonFieldChars > INFLECTION_PROFILE_PACK_STORED_JSON_FIELDS_MAX_CHARS) {
      throw new IllegalArgumentException(
          "Stored inflection profile JSON fields for "
              + context
              + " must total at most "
              + INFLECTION_PROFILE_PACK_STORED_JSON_FIELDS_MAX_CHARS
              + " characters: "
              + context
              + " has "
              + nextTotalJsonFieldChars);
    }
    return nextTotalJsonFieldChars;
  }

  private static int textLength(String value) {
    return value == null ? 0 : value.length();
  }

  private static void requireProfilePackCountWithinLimit(long profileCount, String context) {
    if (profileCount > INFLECTION_PROFILE_PACK_MAX_PROFILE_COUNT) {
      throw new IllegalArgumentException(
          "Inflection profile pack must include at most "
              + INFLECTION_PROFILE_PACK_MAX_PROFILE_COUNT
              + " profiles for current V0 admin/review/export flows: "
              + context
              + " has "
              + profileCount);
    }
  }

  private String normalizeStatus(String status) {
    String normalized = requireText(status, "status").trim().toUpperCase(Locale.ROOT);
    return switch (normalized) {
      case TermInflectionProfilePackJsonLoader.STATUS_APPROVED,
              TermInflectionProfilePackJsonLoader.STATUS_DISABLED,
              TermInflectionProfilePackJsonLoader.STATUS_GENERATED,
              TermInflectionProfilePackJsonLoader.STATUS_REVIEW_NEEDED ->
          normalized;
      default ->
          throw new IllegalArgumentException("Unsupported inflection profile status: " + status);
    };
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return value.trim();
  }

  private void requireProfileJsonFieldsWithinLimit(InflectionProfileInput input) {
    optionalTextWithin(
        input.morphologyJson(), "morphologyJson", INFLECTION_PROFILE_JSON_FIELD_MAX_CHARS);
    optionalTextWithin(input.formsJson(), "formsJson", INFLECTION_PROFILE_JSON_FIELD_MAX_CHARS);
    optionalTextWithin(
        input.diagnosticsJson(), "diagnosticsJson", INFLECTION_PROFILE_JSON_FIELD_MAX_CHARS);
    optionalTextWithin(
        input.provenanceJson(), "provenanceJson", INFLECTION_PROFILE_JSON_FIELD_MAX_CHARS);
  }

  private void requireProfileJsonFieldsWithinLimit(InflectionProfileReviewInput input) {
    optionalTextWithin(
        input.morphologyJson(), "morphologyJson", INFLECTION_PROFILE_JSON_FIELD_MAX_CHARS);
    optionalTextWithin(input.formsJson(), "formsJson", INFLECTION_PROFILE_JSON_FIELD_MAX_CHARS);
    optionalTextWithin(
        input.diagnosticsJson(), "diagnosticsJson", INFLECTION_PROFILE_JSON_FIELD_MAX_CHARS);
    optionalTextWithin(
        input.provenanceJson(), "provenanceJson", INFLECTION_PROFILE_JSON_FIELD_MAX_CHARS);
  }

  private static String requireTextWithin(String value, String field, int maxChars) {
    return requireMaxCharacters(requireText(value, field), field, maxChars);
  }

  private static String optionalTextWithin(String value, String field, int maxChars) {
    if (value == null) {
      return null;
    }
    return requireMaxCharacters(value, field, maxChars);
  }

  private static String requireMaxCharacters(String value, String field, int maxChars) {
    if (value != null && value.length() > maxChars) {
      throw new IllegalArgumentException(field + " must be at most " + maxChars + " characters");
    }
    return value;
  }

  private void requireGlossaryReader() {
    if (!userService.isCurrentUserTranslationRole()) {
      throw new AccessDeniedException("Translation role required to access inflection profiles");
    }
  }

  private void requireTermManager() {
    if (!userService.isCurrentUserAdminOrPm()) {
      throw new AccessDeniedException(
          "PM or admin access is required to manage inflection profiles");
    }
  }

  public record InflectionProfileInput(
      String localeTag,
      String status,
      String morphologyJson,
      String formsJson,
      String diagnosticsJson,
      String provenanceJson) {}

  public record InflectionProfileReviewInput(
      String localeTag,
      String status,
      String morphologyJson,
      String formsJson,
      String diagnosticsJson,
      String provenanceJson) {}

  public record InflectionProfileImportResult(
      String localeTag,
      int profileCount,
      int createdProfileCount,
      int updatedProfileCount,
      List<InflectionProfileView> profiles) {}

  public record SkippedInflectionProfile(
      String termId,
      String source,
      String status,
      int diagnosticCount,
      List<DiagnosticSummary> diagnosticSummaries,
      List<String> missingFormKeys) {

    public SkippedInflectionProfile {
      termId = requireText(termId, "termId");
      source = requireText(source, "source");
      status = requireText(status, "status");
      if (diagnosticCount < 0) {
        throw new IllegalArgumentException("diagnosticCount must be non-negative");
      }
      diagnosticSummaries =
          List.copyOf(Objects.requireNonNull(diagnosticSummaries, "diagnosticSummaries"));
      missingFormKeys = List.copyOf(Objects.requireNonNull(missingFormKeys, "missingFormKeys"));
    }
  }

  public record CompiledInflectionProfilePackExport(
      CompiledTermPack pack,
      int approvedProfileCount,
      List<SkippedInflectionProfile> skippedProfiles) {

    public CompiledInflectionProfilePackExport {
      pack = Objects.requireNonNull(pack, "pack");
      if (approvedProfileCount < 0) {
        throw new IllegalArgumentException("approvedProfileCount must be non-negative");
      }
      skippedProfiles = List.copyOf(Objects.requireNonNull(skippedProfiles, "skippedProfiles"));
    }

    public int skippedProfileCount() {
      return skippedProfiles.size();
    }

    public ExportPolicy exportPolicy() {
      return pack.exportPolicy();
    }
  }

  private record ProvenanceSources(List<String> sourceLabels, List<Map<String, Object>> sources) {}

  private record InflectionProfileImportCandidate(
      InflectionProfileImportJsonFields importedProfileJsonFields,
      GlossaryTermMetadata metadata,
      GlossaryTermInflectionProfile existingProfile) {}

  private record InflectionProfileImportJsonFields(
      TermInflectionProfilePackJsonLoader.Profile importedProfile,
      String morphologyJson,
      String formsJson,
      String diagnosticsJson,
      String provenanceJson) {}

  public record InflectionProfileView(
      Long id,
      ZonedDateTime createdDate,
      ZonedDateTime lastModifiedDate,
      Long glossaryTermMetadataId,
      Long tmTextUnitId,
      String termId,
      String source,
      String localeTag,
      String schema,
      String status,
      String morphologyJson,
      String formsJson,
      String diagnosticsJson,
      String provenanceJson) {}
}
