package com.box.l10n.mojito.service.cms;

import com.box.l10n.mojito.entity.Asset;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.RepositoryLocale;
import com.box.l10n.mojito.entity.TMTextUnit;
import com.box.l10n.mojito.entity.TMTextUnitVariant;
import com.box.l10n.mojito.entity.cms.CmsAuditableEntity;
import com.box.l10n.mojito.entity.cms.CmsContentEntry;
import com.box.l10n.mojito.entity.cms.CmsContentEntryVariant;
import com.box.l10n.mojito.entity.cms.CmsContentFieldMapping;
import com.box.l10n.mojito.entity.cms.CmsContentProject;
import com.box.l10n.mojito.entity.cms.CmsContentType;
import com.box.l10n.mojito.entity.cms.CmsContentTypeField;
import com.box.l10n.mojito.entity.cms.CmsPublishSnapshot;
import com.box.l10n.mojito.entity.cms.CmsPublishSnapshotSeal;
import com.box.l10n.mojito.entity.security.user.User;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.okapi.TextUnitUtils;
import com.box.l10n.mojito.service.asset.AssetRepository;
import com.box.l10n.mojito.service.asset.VirtualAsset;
import com.box.l10n.mojito.service.asset.VirtualAssetBadRequestException;
import com.box.l10n.mojito.service.asset.VirtualAssetService;
import com.box.l10n.mojito.service.asset.VirtualAssetTextUnit;
import com.box.l10n.mojito.service.asset.VirtualTextUnitBatchUpdaterService;
import com.box.l10n.mojito.service.assetExtraction.AssetTextUnitToTMTextUnitRepository;
import com.box.l10n.mojito.service.assetintegritychecker.integritychecker.MessageFormatIntegrityChecker;
import com.box.l10n.mojito.service.assetintegritychecker.integritychecker.MessageFormatIntegrityCheckerException;
import com.box.l10n.mojito.service.repository.RepositoryRepository;
import com.box.l10n.mojito.service.security.user.UserService;
import com.box.l10n.mojito.service.tm.TMTextUnitCurrentVariantMutationLockService;
import com.box.l10n.mojito.service.tm.TMTextUnitRepository;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CmsContentService {

  public static final int DEFAULT_LIMIT = 50;
  public static final int MAX_LIMIT = 200;

  private static final int PUBLISH_SNAPSHOT_HISTORY_LIMIT = 10;
  private static final String SNAPSHOT_ARTIFACT_FORMAT_VERSION = "mojito.microCms.v1";
  private static final String SNAPSHOT_DELIVERY_DESCRIPTOR_FORMAT_VERSION =
      "mojito.microCms.snapshot-delivery-descriptor.v1";
  private static final String PUBLISH_PACKAGE_STATE_FORMAT_VERSION =
      "mojito.microCms.publish-package-state.v1";
  private static final Pattern KEY_PATTERN = Pattern.compile("[a-z0-9][a-z0-9_-]*");
  private static final Set<String> DELIVERY_HINTS =
      Set.of("BLOB_CDN", "STATSIG_DYNAMIC_CONFIG", "EXPERIENCE_FRAMEWORK");
  private static final List<String> SUPPORTED_DELIVERY_TARGETS =
      List.of("statsig-dynamic-config", "blob-cdn", "experience-framework");
  private static final Set<String> SNAPSHOT_ARTIFACT_FIELDS =
      Set.of(
          "formatVersion",
          "snapshotVersion",
          "generatedAt",
          "delivery",
          "project",
          "locales",
          "contentTypes",
          "entries",
          "completeness");
  private static final Set<String> SNAPSHOT_ARTIFACT_DELIVERY_FIELDS =
      Set.of("runtimeDependency", "projectHint", "supportedTargets");
  private static final Set<String> SNAPSHOT_ARTIFACT_PROJECT_FIELDS =
      Set.of("key", "name", "sourceLocale");
  private static final Set<String> SNAPSHOT_ARTIFACT_CONTENT_TYPE_FIELDS =
      Set.of("key", "name", "schemaVersion", "metadataSchema", "fields");
  private static final Set<String> SNAPSHOT_ARTIFACT_CONTENT_TYPE_FIELD_FIELDS =
      Set.of("key", "name", "type", "localizable", "required");
  private static final Set<String> SNAPSHOT_ARTIFACT_ENTRY_FIELDS =
      Set.of("key", "name", "type", "status", "metadata", "variants");
  private static final Set<String> SNAPSHOT_ARTIFACT_VARIANT_FIELDS =
      Set.of("key", "name", "status", "candidateGroupKey", "metadata", "fields");
  private static final Set<String> SNAPSHOT_ARTIFACT_RUNTIME_FIELD_FIELDS =
      Set.of("stringId", "source", "values");
  private static final Set<String> SNAPSHOT_ARTIFACT_COMPLETENESS_FIELDS =
      Set.of(
          "localeTag",
          "totalFields",
          "approvedFields",
          "missingFields",
          "reviewNeededFields",
          "translationNeededFields",
          "complete");
  private static final Set<String> METADATA_SCHEMA_KEYS =
      Set.of(
          "$schema",
          "title",
          "description",
          "type",
          "properties",
          "required",
          "additionalProperties");
  private static final Set<String> METADATA_PROPERTY_SCHEMA_KEYS = Set.of("description", "type");
  private static final Set<String> METADATA_PROPERTY_TYPES =
      Set.of("string", "number", "integer", "boolean", "object", "array", "null");

  private final CmsContentProjectRepository projectRepository;
  private final CmsContentTypeRepository contentTypeRepository;
  private final CmsContentTypeFieldRepository fieldRepository;
  private final CmsContentEntryRepository entryRepository;
  private final CmsContentEntryVariantRepository variantRepository;
  private final CmsContentFieldMappingRepository mappingRepository;
  private final CmsPublishSnapshotRepository snapshotRepository;
  private final CmsPublishSnapshotSealRepository snapshotSealRepository;
  private final RepositoryRepository repositoryRepository;
  private final AssetRepository assetRepository;
  private final VirtualAssetService virtualAssetService;
  private final VirtualTextUnitBatchUpdaterService virtualTextUnitBatchUpdaterService;
  private final AssetTextUnitToTMTextUnitRepository assetTextUnitToTMTextUnitRepository;
  private final TMTextUnitRepository tmTextUnitRepository;
  private final TMTextUnitCurrentVariantMutationLockService
      tmTextUnitCurrentVariantMutationLockService;
  private final TextUnitUtils textUnitUtils;
  private final UserService userService;
  private final ObjectMapper objectMapper;
  private final CmsSnapshotSigningService snapshotSigningService;
  private final CmsContentConfigurationProperties configurationProperties;
  private final com.fasterxml.jackson.databind.ObjectMapper strictTreeObjectMapper;
  private final MessageFormatIntegrityChecker messageFormatIntegrityChecker =
      new MessageFormatIntegrityChecker();

  public CmsContentService(
      CmsContentProjectRepository projectRepository,
      CmsContentTypeRepository contentTypeRepository,
      CmsContentTypeFieldRepository fieldRepository,
      CmsContentEntryRepository entryRepository,
      CmsContentEntryVariantRepository variantRepository,
      CmsContentFieldMappingRepository mappingRepository,
      CmsPublishSnapshotRepository snapshotRepository,
      CmsPublishSnapshotSealRepository snapshotSealRepository,
      RepositoryRepository repositoryRepository,
      AssetRepository assetRepository,
      VirtualAssetService virtualAssetService,
      VirtualTextUnitBatchUpdaterService virtualTextUnitBatchUpdaterService,
      AssetTextUnitToTMTextUnitRepository assetTextUnitToTMTextUnitRepository,
      TMTextUnitRepository tmTextUnitRepository,
      TMTextUnitCurrentVariantMutationLockService tmTextUnitCurrentVariantMutationLockService,
      TextUnitUtils textUnitUtils,
      UserService userService,
      ObjectMapper objectMapper,
      CmsSnapshotSigningService snapshotSigningService,
      CmsContentConfigurationProperties configurationProperties) {
    this.projectRepository = projectRepository;
    this.contentTypeRepository = contentTypeRepository;
    this.fieldRepository = fieldRepository;
    this.entryRepository = entryRepository;
    this.variantRepository = variantRepository;
    this.mappingRepository = mappingRepository;
    this.snapshotRepository = snapshotRepository;
    this.snapshotSealRepository = snapshotSealRepository;
    this.repositoryRepository = repositoryRepository;
    this.assetRepository = assetRepository;
    this.virtualAssetService = virtualAssetService;
    this.virtualTextUnitBatchUpdaterService = virtualTextUnitBatchUpdaterService;
    this.assetTextUnitToTMTextUnitRepository = assetTextUnitToTMTextUnitRepository;
    this.tmTextUnitRepository = tmTextUnitRepository;
    this.tmTextUnitCurrentVariantMutationLockService = tmTextUnitCurrentVariantMutationLockService;
    this.textUnitUtils = textUnitUtils;
    this.userService = userService;
    this.objectMapper = objectMapper;
    this.snapshotSigningService = snapshotSigningService;
    this.configurationProperties = configurationProperties;
    this.strictTreeObjectMapper =
        objectMapper.copy().enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);
  }

  @Transactional(readOnly = true)
  public SearchProjectsView searchProjects(String searchQuery, Boolean enabled, Integer limit) {
    requireAdmin();
    Page<CmsContentProject> page =
        projectRepository.search(
            normalizeSearchQuery(searchQuery), enabled, PageRequest.of(0, normalizeLimit(limit)));
    List<ProjectSummary> projects = page.getContent().stream().map(this::toProjectSummary).toList();
    return new SearchProjectsView(projects, page.getTotalElements());
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public ProjectDetail getProject(Long projectId) {
    requireAdmin();
    CmsContentProject project = findProjectForUpdate(projectId);
    return toProjectDetail(project);
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public ProjectDetail createProject(ProjectCommand command) {
    requireAdmin();
    command =
        command == null ? new ProjectCommand(null, null, null, null, null, null, null) : command;
    String key = normalizeKey(command.projectKey(), "Project key");
    ensureProjectKeyAvailable(key, null);
    String name = normalizeName(command.name(), CmsContentProject.NAME_MAX_LENGTH, "Project name");
    Repository repository = resolveRepository(command.repositoryId());
    Asset asset = resolveProjectAsset(repository, normalizeAssetPath(command.assetPath(), key));
    ensureProjectAssetAvailable(asset, null);

    CmsContentProject project = new CmsContentProject();
    project.setProjectKey(key);
    project.setName(name);
    project.setDescription(
        normalizeOptionalText(
            command.description(), CmsContentProject.DESCRIPTION_MAX_LENGTH, "Description"));
    project.setEnabled(command.enabled() == null ? Boolean.TRUE : command.enabled());
    project.setRepository(repository);
    project.setAsset(asset);
    project.setDeliveryHint(normalizeDeliveryHint(command.deliveryHint()));
    CmsContentProject saved = projectRepository.saveAndFlush(project);
    return getProject(saved.getId());
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public ProjectDetail updateProject(Long projectId, ProjectUpdateCommand command) {
    requireAdmin();
    command = command == null ? new ProjectUpdateCommand() : command;
    CmsContentProject project = findProjectForUpdate(projectId);
    requireExpectedVersion(
        project.getEntityVersion(), command.expectedVersion(), "Content project");
    project.setName(
        command.hasName()
            ? normalizeName(command.name(), CmsContentProject.NAME_MAX_LENGTH, "Project name")
            : project.getName());
    project.setDescription(
        command.hasDescription()
            ? normalizeOptionalText(
                command.description(), CmsContentProject.DESCRIPTION_MAX_LENGTH, "Description")
            : project.getDescription());
    project.setEnabled(
        command.hasEnabled() && command.enabled() != null
            ? command.enabled()
            : project.getEnabled());
    project.setDeliveryHint(
        !command.hasDeliveryHint() || command.deliveryHint() == null
            ? project.getDeliveryHint()
            : normalizeDeliveryHint(command.deliveryHint()));
    projectRepository.saveAndFlush(project);
    return getProject(project.getId());
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public ProjectDetail createContentType(Long projectId, ContentTypeCommand command) {
    requireAdmin();
    command = command == null ? new ContentTypeCommand(null, null, null, null, null) : command;
    CmsContentProject project = findProjectForUpdate(projectId);
    String key = normalizeKey(command.typeKey(), "Content type key");
    ensureTypeKeyAvailable(project.getId(), key, null);

    CmsContentType contentType = new CmsContentType();
    contentType.setProject(project);
    contentType.setTypeKey(key);
    contentType.setName(
        normalizeName(command.name(), CmsContentType.NAME_MAX_LENGTH, "Content type name"));
    contentType.setDescription(
        normalizeOptionalText(
            command.description(), CmsContentType.DESCRIPTION_MAX_LENGTH, "Description"));
    contentType.setSchemaVersion(normalizeNewContentTypeSchemaVersion(command.schemaVersion()));
    contentType.setMetadataSchemaJson(normalizeMetadataSchemaJson(command.metadataSchemaJson()));
    contentTypeRepository.saveAndFlush(contentType);
    return getProject(project.getId());
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public ProjectDetail updateContentType(Long contentTypeId, ContentTypeUpdateCommand command) {
    requireAdmin();
    command = command == null ? new ContentTypeUpdateCommand() : command;
    lockProjectForContentTypeUpdate(contentTypeId);
    CmsContentType contentType = findContentType(contentTypeId);
    requireExpectedVersion(
        contentType.getEntityVersion(), command.expectedVersion(), "Content type");
    contentType.setName(
        command.hasName()
            ? normalizeName(command.name(), CmsContentType.NAME_MAX_LENGTH, "Content type name")
            : contentType.getName());
    contentType.setDescription(
        command.hasDescription()
            ? normalizeOptionalText(
                command.description(), CmsContentType.DESCRIPTION_MAX_LENGTH, "Description")
            : contentType.getDescription());
    rejectManualSchemaVersionChange(contentType, command.schemaVersion());
    String metadataSchemaJson =
        command.hasMetadataSchemaJson()
            ? normalizeMetadataSchemaJson(command.metadataSchemaJson())
            : contentType.getMetadataSchemaJson();
    validateExistingMetadata(contentType, metadataSchemaJson);
    boolean schemaChanged =
        !Objects.equals(contentType.getMetadataSchemaJson(), metadataSchemaJson);
    contentType.setMetadataSchemaJson(metadataSchemaJson);
    if (schemaChanged) {
      bumpContentTypeSchemaVersion(contentType);
    } else {
      contentTypeRepository.saveAndFlush(contentType);
    }
    return getProject(contentType.getProject().getId());
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public ProjectDetail createContentTypeField(Long contentTypeId, ContentTypeFieldCommand command) {
    requireAdmin();
    command =
        command == null
            ? new ContentTypeFieldCommand(null, null, null, null, null, null, null)
            : command;
    lockProjectForContentTypeUpdate(contentTypeId);
    CmsContentType contentType = findContentType(contentTypeId);
    String key = normalizeKey(command.fieldKey(), "Field key");
    ensureFieldKeyAvailable(contentType.getId(), key, null);

    CmsContentTypeField field = new CmsContentTypeField();
    field.setContentType(contentType);
    field.setFieldKey(key);
    field.setName(normalizeName(command.name(), CmsContentTypeField.NAME_MAX_LENGTH, "Field name"));
    field.setDescription(
        normalizeOptionalText(
            command.description(), CmsContentTypeField.DESCRIPTION_MAX_LENGTH, "Description"));
    field.setFieldType(
        command.fieldType() == null ? CmsContentTypeField.FieldType.TEXT : command.fieldType());
    field.setLocalizable(requireLocalizableField(command.localizable()));
    field.setRequired(command.required() == null ? Boolean.FALSE : command.required());
    field.setSortOrder(normalizeSortOrder(command.sortOrder()));
    fieldRepository.saveAndFlush(field);
    validateReadyEntriesForContentType(contentType);
    bumpContentTypeSchemaVersion(contentType);
    return getProject(contentType.getProject().getId());
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public ProjectDetail updateContentTypeField(Long fieldId, ContentTypeFieldUpdateCommand command) {
    requireAdmin();
    command = command == null ? new ContentTypeFieldUpdateCommand() : command;
    lockProjectForFieldUpdate(fieldId);
    CmsContentTypeField field = findField(fieldId);
    requireExpectedVersion(
        field.getEntityVersion(), command.expectedVersion(), "Content type field");
    boolean localizable =
        requireLocalizableField(
            command.hasLocalizable() && command.localizable() != null
                ? command.localizable()
                : field.getLocalizable());
    String name =
        command.hasName()
            ? normalizeName(command.name(), CmsContentTypeField.NAME_MAX_LENGTH, "Field name")
            : field.getName();
    String description =
        command.hasDescription()
            ? normalizeOptionalText(
                command.description(), CmsContentTypeField.DESCRIPTION_MAX_LENGTH, "Description")
            : field.getDescription();
    CmsContentTypeField.FieldType fieldType =
        command.hasFieldType() && command.fieldType() != null
            ? command.fieldType()
            : field.getFieldType();
    validateFieldMappingsForFieldType(field, fieldType);
    boolean required =
        command.hasRequired() && command.required() != null
            ? command.required()
            : field.getRequired();
    Integer sortOrder =
        normalizeSortOrder(
            command.hasSortOrder() && command.sortOrder() != null
                ? command.sortOrder()
                : field.getSortOrder());
    boolean schemaChanged =
        !Objects.equals(field.getName(), name)
            || !Objects.equals(field.getDescription(), description)
            || !Objects.equals(field.getFieldType(), fieldType)
            || !Objects.equals(field.getLocalizable(), localizable)
            || !Objects.equals(field.getRequired(), required)
            || !Objects.equals(field.getSortOrder(), sortOrder);
    field.setName(name);
    field.setDescription(description);
    field.setFieldType(fieldType);
    field.setLocalizable(localizable);
    field.setRequired(required);
    field.setSortOrder(sortOrder);
    fieldRepository.saveAndFlush(field);
    validateReadyEntriesForContentType(field.getContentType());
    if (schemaChanged) {
      bumpContentTypeSchemaVersion(field.getContentType());
    }
    return getProject(field.getContentType().getProject().getId());
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public ProjectDetail createEntry(Long projectId, EntryCommand command) {
    requireAdmin();
    command = command == null ? new EntryCommand(null, null, null, null, null, null) : command;
    CmsContentProject project = findProjectForUpdate(projectId);
    CmsContentType contentType = findContentType(command.contentTypeId());
    if (!Objects.equals(contentType.getProject().getId(), project.getId())) {
      throw new IllegalArgumentException(
          "Content type does not belong to project: " + contentType.getId());
    }

    String key = normalizeKey(command.entryKey(), "Entry key");
    ensureEntryKeyAvailable(project.getId(), key, null);
    rejectReadyStatusForNewEntry(command.status());

    CmsContentEntry entry = new CmsContentEntry();
    entry.setProject(project);
    entry.setContentType(contentType);
    entry.setEntryKey(key);
    entry.setName(normalizeName(command.name(), CmsContentEntry.NAME_MAX_LENGTH, "Entry name"));
    entry.setDescription(
        normalizeOptionalText(
            command.description(), CmsContentEntry.DESCRIPTION_MAX_LENGTH, "Description"));
    entry.setStatus(command.status() == null ? CmsContentEntry.Status.DRAFT : command.status());
    entry.setMetadataJson(
        normalizeEntryMetadataJson(command.metadataJson(), contentType, "Entry metadata"));
    CmsContentEntry saved = entryRepository.saveAndFlush(entry);

    CmsContentEntryVariant defaultVariant = new CmsContentEntryVariant();
    defaultVariant.setEntry(saved);
    defaultVariant.setContentType(saved.getContentType());
    defaultVariant.setContentProjectId(saved.getProject().getId());
    defaultVariant.setVariantKey("default");
    defaultVariant.setName("Default");
    setVariantStatus(defaultVariant, CmsContentEntryVariant.Status.CONTROL);
    defaultVariant.setSortOrder(0);
    variantRepository.saveAndFlush(defaultVariant);

    return getProject(project.getId());
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public ProjectDetail updateEntry(Long entryId, EntryUpdateCommand command) {
    requireAdmin();
    command = command == null ? new EntryUpdateCommand() : command;
    lockProjectForEntryUpdate(entryId);
    CmsContentEntry entry = findEntry(entryId);
    requireExpectedVersion(entry.getEntityVersion(), command.expectedVersion(), "Content entry");
    entry.setName(
        command.hasName()
            ? normalizeName(command.name(), CmsContentEntry.NAME_MAX_LENGTH, "Entry name")
            : entry.getName());
    entry.setDescription(
        command.hasDescription()
            ? normalizeOptionalText(
                command.description(), CmsContentEntry.DESCRIPTION_MAX_LENGTH, "Description")
            : entry.getDescription());
    entry.setStatus(
        command.hasStatus() && command.status() != null ? command.status() : entry.getStatus());
    entry.setMetadataJson(
        command.hasMetadataJson()
            ? normalizeEntryMetadataJson(
                command.metadataJson(), entry.getContentType(), "Entry metadata")
            : entry.getMetadataJson());
    validateReadyEntryStructure(entry);
    entryRepository.saveAndFlush(entry);
    return getProject(entry.getProject().getId());
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public ProjectDetail createVariant(Long entryId, VariantCommand command) {
    requireAdmin();
    command = command == null ? new VariantCommand(null, null, null, null, null, null) : command;
    lockProjectForEntryUpdate(entryId);
    CmsContentEntry entry = findEntryForUpdate(entryId);
    String key = normalizeKey(command.variantKey(), "Variant key");
    ensureVariantKeyAvailable(entry.getId(), key, null);

    CmsContentEntryVariant variant = new CmsContentEntryVariant();
    variant.setEntry(entry);
    variant.setContentType(entry.getContentType());
    variant.setContentProjectId(entry.getProject().getId());
    variant.setVariantKey(key);
    variant.setName(
        normalizeName(command.name(), CmsContentEntryVariant.NAME_MAX_LENGTH, "Variant name"));
    CmsContentEntryVariant.Status status =
        command.status() == null ? CmsContentEntryVariant.Status.CANDIDATE : command.status();
    String candidateGroupKey =
        normalizeOptionalKey(command.candidateGroupKey(), "Candidate group key");
    validateCandidateGroupKey(status, candidateGroupKey);
    variant.setCandidateGroupKey(candidateGroupKey);
    ensureControlVariantInvariant(entry, status, null, null);
    setVariantStatus(variant, status);
    variant.setMetadataJson(
        normalizeMetadataObjectJson(command.metadataJson(), "Variant metadata"));
    variant.setSortOrder(normalizeSortOrder(command.sortOrder()));
    variantRepository.saveAndFlush(variant);
    validateReadyEntryStructure(entry);
    return getProject(entry.getProject().getId());
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public ProjectDetail updateVariant(Long variantId, VariantUpdateCommand command) {
    requireAdmin();
    command = command == null ? new VariantUpdateCommand() : command;
    lockProjectForVariantUpdate(variantId);
    CmsContentEntry entry = findEntryForVariantUpdate(variantId);
    CmsContentEntryVariant variant = findVariant(variantId);
    requireExpectedVersion(
        variant.getEntityVersion(), command.expectedVersion(), "Content variant");
    variant.setName(
        command.hasName()
            ? normalizeName(command.name(), CmsContentEntryVariant.NAME_MAX_LENGTH, "Variant name")
            : variant.getName());
    String candidateGroupKey =
        command.hasCandidateGroupKey()
            ? normalizeOptionalKey(command.candidateGroupKey(), "Candidate group key")
            : variant.getCandidateGroupKey();
    CmsContentEntryVariant.Status status =
        command.hasStatus() && command.status() != null ? command.status() : variant.getStatus();
    validateCandidateGroupKey(status, candidateGroupKey);
    variant.setCandidateGroupKey(candidateGroupKey);
    transitionVariantStatus(entry, variant, status);
    variant.setMetadataJson(
        command.hasMetadataJson()
            ? normalizeMetadataObjectJson(command.metadataJson(), "Variant metadata")
            : variant.getMetadataJson());
    variant.setSortOrder(
        normalizeSortOrder(
            command.hasSortOrder() && command.sortOrder() != null
                ? command.sortOrder()
                : variant.getSortOrder()));
    variantRepository.saveAndFlush(variant);
    validateReadyEntryStructure(entry);
    return getProject(entry.getProject().getId());
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public ProjectDetail upsertFieldMapping(Long variantId, FieldMappingCommand command) {
    requireAdmin();
    command = command == null ? new FieldMappingCommand(null, null, null, null, null) : command;
    lockProjectForVariantUpdate(variantId);
    CmsContentEntryVariant variant = findVariant(variantId);
    CmsContentTypeField field = findField(command.fieldId());
    CmsContentEntry entry = variant.getEntry();
    validateProjectRepositoryAvailable(entry.getProject());
    validateProjectVirtualAssetAvailable(entry.getProject());
    if (!Objects.equals(field.getContentType().getId(), entry.getContentType().getId())) {
      throw new IllegalArgumentException(
          "Field does not belong to the entry content type: " + field.getId());
    }
    if (!Boolean.TRUE.equals(field.getLocalizable())) {
      throw new IllegalArgumentException("Only localizable fields can be mapped to text units");
    }

    CmsContentFieldMapping mapping =
        mappingRepository.findByVariantIdAndFieldId(variant.getId(), field.getId()).orElse(null);
    requireMappingExpectedVersion(mapping, command.expectedVersion());

    String stringId = normalizeOptionalStringId(command.stringId());
    if (command.tmTextUnitId() != null && stringId != null) {
      throw new IllegalArgumentException("Map using either TM text unit ID or string ID, not both");
    }
    boolean mapsExistingTextUnit = command.tmTextUnitId() != null || stringId != null;
    String sourceContent =
        !mapsExistingTextUnit
            ? normalizeSourceContent(command.sourceContent())
            : normalizeOptionalSourceContent(command.sourceContent());
    String sourceComment =
        normalizeOptionalText(command.sourceComment(), Integer.MAX_VALUE, "Translator context");
    String mappingPath = mappingPath(variant, field);
    if (!mapsExistingTextUnit) {
      validateFieldSourceContent(field, sourceContent, mappingPath);
      requireTranslatorContext(sourceComment, mappingPath);
    }
    TMTextUnit tmTextUnit =
        !mapsExistingTextUnit
            ? getOrCreateTextUnit(variant, field, sourceContent, sourceComment)
            : resolveMappedTextUnit(
                variant, command.tmTextUnitId(), stringId, sourceContent, sourceComment);
    requireMojitoStringId(tmTextUnit.getName(), mappingPath);
    requireSourceContent(tmTextUnit.getContent(), mappingPath);
    validateFieldSourceContent(field, tmTextUnit.getContent(), mappingPath);
    ensureTextUnitAvailableForMapping(tmTextUnit, mapping);
    requireTranslatorContext(tmTextUnit.getComment(), mappingPath);

    if (mapping == null) {
      mapping = new CmsContentFieldMapping();
      mapping.setVariant(variant);
      mapping.setField(field);
      mapping.setContentType(entry.getContentType());
    }
    mapping.setContentProjectId(entry.getProject().getId());
    mapping.setAssetId(entry.getProject().getAsset().getId());
    mapping.setTmTextUnit(tmTextUnit);
    mappingRepository.saveAndFlush(mapping);
    validateReadyEntryStructure(entry);

    return getProject(entry.getProject().getId());
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public ProjectDetail unmapFieldMapping(Long mappingId, FieldMappingDeleteCommand command) {
    requireAdmin();
    command = command == null ? new FieldMappingDeleteCommand(null) : command;
    lockProjectForFieldMappingUpdate(mappingId);
    CmsContentFieldMapping mapping = findFieldMapping(mappingId);
    requireExpectedVersion(mapping.getEntityVersion(), command.expectedVersion(), "Field mapping");
    Long projectId = mapping.getVariant().getEntry().getProject().getId();
    CmsContentEntry entry = mapping.getVariant().getEntry();
    mappingRepository.delete(mapping);
    mappingRepository.flush();
    validateReadyEntryStructure(entry);
    return getProject(projectId);
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public EntryCompletenessView getEntryCompleteness(Long entryId, List<String> localeTags) {
    requireAdmin();
    lockProjectForEntryUpdate(entryId);
    CmsContentEntry entry = findEntry(entryId);
    EntryPublishableState publishableState =
        validateEntryPublishableStructure(
            entry,
            "Entry does not have exactly one active control variant: ",
            "Entry has candidate variants without candidate group keys: ",
            "Entry has publishable variants without mapped localizable fields: ",
            "Entry has missing required field mappings: ");
    List<CmsContentFieldMapping> mappings = publishableState.mappings();
    List<String> resolvedLocaleTags = resolveLocaleTags(entry.getProject(), localeTags);
    Map<Long, Map<String, CmsCurrentVariantRow>> currentVariants =
        loadCurrentVariants(entry.getProject(), mappings, resolvedLocaleTags, false);
    validatePublishableFieldIntegrity(
        entry.getProject(), mappings, resolvedLocaleTags, currentVariants);
    return toEntryCompletenessView(entry, mappings, resolvedLocaleTags, currentVariants);
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public ProjectCompletenessView getProjectCompleteness(Long projectId, List<String> localeTags) {
    requireAdmin();
    CmsContentProject project = findProjectForUpdate(projectId);
    String authoringSha256 = buildPublishRequestAuthoringSha256(project);
    PublishPreflight preflight = buildPublishPreflight(project, localeTags, false);
    List<EntryCompletenessView> entries =
        preflight.entries().stream()
            .map(
                entry ->
                    toEntryCompletenessView(
                        entry,
                        preflight.mappings(),
                        preflight.localeTags(),
                        preflight.currentVariants()))
            .toList();
    PublishPackage publishPackage = buildPublishPackage(project, preflight);
    return new ProjectCompletenessView(
        project.getId(),
        project.getProjectKey(),
        authoringSha256,
        publishPackage.sha256(),
        publishPackage.byteSize(),
        preflight.localeTags(),
        preflight.completeness(),
        entries,
        preflight.completeness().stream().allMatch(LocaleCompleteness::complete));
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public PublishSnapshotView publishProject(
      Long projectId, PublishCommand command, String publishRequestKey) {
    requireAdmin();
    String validatedPublishRequestKey = validatePublishRequestKey(publishRequestKey);
    PublishCommand validatedCommand = requirePublishCommand(command);
    String expectedAuthoringSha256 =
        validateExpectedPublishAuthoringSha256(validatedCommand.expectedAuthoringSha256());
    String expectedPackageSha256 =
        validateExpectedPublishPackageSha256(validatedCommand.expectedPackageSha256());
    List<String> requestedLocaleTags = normalizeRequestedLocaleTags(validatedCommand.localeTags());
    String publishRequestLocaleTags = String.join(",", requestedLocaleTags);
    CmsContentProject project = findProjectForUpdate(projectId);
    validatePublishSnapshotHistory(project);
    Optional<CmsPublishSnapshot> existingSnapshot =
        snapshotRepository.findByProjectIdAndPublishRequestKey(
            projectId, validatedPublishRequestKey);
    if (existingSnapshot.isPresent()) {
      requireMatchingPublishRequest(
          existingSnapshot.get(),
          requestedLocaleTags,
          expectedAuthoringSha256,
          expectedPackageSha256);
      return toPublishSnapshotView(existingSnapshot.get());
    }
    String publishRequestAuthoringSha256 = buildPublishRequestAuthoringSha256(project);
    requireMatchingExpectedPublishAuthoringSha256(
        expectedAuthoringSha256, publishRequestAuthoringSha256);
    requireProjectDeliverable(project);
    User publisher = requireCurrentUser();

    PublishPreflight preflight = buildPublishPreflight(project, requestedLocaleTags, true);
    List<LocaleCompleteness> completeness = preflight.completeness();
    List<LocaleCompleteness> incomplete =
        completeness.stream().filter(locale -> !locale.complete()).toList();
    if (!incomplete.isEmpty()) {
      String localeList =
          incomplete.stream().map(LocaleCompleteness::localeTag).collect(Collectors.joining(", "));
      throw new IllegalArgumentException("Cannot publish with incomplete locales: " + localeList);
    }
    PublishPackage publishPackage = buildPublishPackage(project, preflight);
    requireMatchingExpectedPublishPackageSha256(expectedPackageSha256, publishPackage.sha256());

    int version = requireLastPublishedSnapshotVersion(project) + 1;
    Instant publishedAt = Instant.now();
    String artifactJson = buildArtifact(publishPackage.payload(), version, publishedAt);
    String artifactSha256 = DigestUtils.sha256Hex(artifactJson);
    long artifactByteSize = artifactJson.getBytes(StandardCharsets.UTF_8).length;
    requirePublishArtifactWithinConfiguredByteLimit(artifactByteSize);
    String completenessJson = objectMapper.writeValueAsStringUnchecked(completeness);

    CmsPublishSnapshot snapshot = new CmsPublishSnapshot();
    snapshot.setProject(project);
    snapshot.setCreatedByUser(publisher);
    snapshot.setCreatedByUsername(requirePublisherUsername(publisher));
    snapshot.setPublishedAt(publishedAt.toString());
    snapshot.setSnapshotVersion(version);
    snapshot.setPublishRequestKey(validatedPublishRequestKey);
    snapshot.setPublishRequestLocaleTags(publishRequestLocaleTags);
    snapshot.setPublishRequestAuthoringSha256(publishRequestAuthoringSha256);
    snapshot.setPublishRequestPackageSha256(publishPackage.sha256());
    snapshot.setStatus(CmsPublishSnapshot.Status.PUBLISHED);
    snapshot.setLocaleTags(String.join(",", preflight.localeTags()));
    snapshot.setArtifactJson(artifactJson);
    snapshot.setArtifactSha256(artifactSha256);
    snapshot.setArtifactByteSize(artifactByteSize);
    snapshot.setCompletenessJson(completenessJson);
    snapshotSigningService.sign(snapshot);
    CmsPublishSnapshot saved = snapshotRepository.save(snapshot);
    sealPublishSnapshot(saved);
    advanceLastPublishedSnapshotVersion(projectId, version - 1, version);
    return toPublishSnapshotView(saved);
  }

  @Transactional(readOnly = true)
  public SnapshotArtifact getSnapshotArtifact(String projectKey, Integer snapshotVersion) {
    requireSnapshotDeliveryReader();
    String normalizedProjectKey = validateStableProjectKey(projectKey);
    int normalizedSnapshotVersion = requireSnapshotVersion(snapshotVersion);
    CmsContentProject project = findProjectByKey(normalizedProjectKey);
    validatePublishSnapshotHistory(project);
    CmsPublishSnapshot snapshot =
        snapshotRepository
            .findByProjectKeyAndSnapshotVersion(normalizedProjectKey, normalizedSnapshotVersion)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "Publish snapshot not found: "
                            + normalizedProjectKey
                            + " v"
                            + normalizedSnapshotVersion));
    return toSnapshotArtifact(snapshot);
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public SnapshotDeliveryDescriptor getLatestPublishedSnapshotDescriptor(String projectKey) {
    requireSnapshotDeliveryReader();
    String normalizedProjectKey = validateStableProjectKey(projectKey);
    CmsContentProject project = findProjectByKeyForUpdate(normalizedProjectKey);
    validatePublishSnapshotHistory(project);
    requireProjectDeliverable(project);
    CmsPublishSnapshot snapshot =
        snapshotRepository
            .findFirstByProjectProjectKeyIgnoreCaseOrderBySnapshotVersionDesc(normalizedProjectKey)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "Published snapshot not found: " + normalizedProjectKey));
    return toSnapshotDeliveryDescriptor(snapshot);
  }

  private SnapshotArtifact toSnapshotArtifact(CmsPublishSnapshot snapshot) {
    validateSnapshotArtifactIntegrity(snapshot);
    return new SnapshotArtifact(
        snapshot.getArtifactJson(),
        snapshot.getArtifactSha256(),
        snapshot.getArtifactByteSize(),
        snapshot.getSnapshotSigningKeyId(),
        snapshot.getSnapshotSignature(),
        snapshot.getArtifactSignature(),
        snapshotArtifactFilename(snapshot));
  }

  private ProjectDetail toProjectDetail(CmsContentProject project) {
    validatePublishSnapshotHistory(project);
    List<CmsContentType> contentTypes =
        contentTypeRepository.findByProjectIdOrderByNameAscIdAsc(project.getId());
    List<Long> typeIds = contentTypes.stream().map(CmsContentType::getId).toList();
    Map<Long, List<FieldView>> fieldsByTypeId =
        typeIds.isEmpty()
            ? Map.of()
            : fieldRepository
                .findByContentTypeIdInOrderBySortOrderAscFieldKeyAscIdAsc(typeIds)
                .stream()
                .map(this::toFieldView)
                .collect(
                    Collectors.groupingBy(
                        FieldView::contentTypeId, LinkedHashMap::new, Collectors.toList()));

    List<CmsContentEntry> entries =
        entryRepository.findByProjectIdOrderByNameAscIdAsc(project.getId());
    List<Long> entryIds = entries.stream().map(CmsContentEntry::getId).toList();
    Map<Long, List<VariantView>> variantsByEntryId =
        entryIds.isEmpty()
            ? Map.of()
            : variantRepository
                .findByEntryIdInOrderBySortOrderAscVariantKeyAscIdAsc(entryIds)
                .stream()
                .map(this::toVariantView)
                .collect(
                    Collectors.groupingBy(
                        VariantView::entryId, LinkedHashMap::new, Collectors.toList()));

    Map<Long, List<FieldMappingView>> mappingsByVariantId =
        mappingRepository.findMappingsByProjectId(project.getId()).stream()
            .map(this::toFieldMappingView)
            .collect(
                Collectors.groupingBy(
                    FieldMappingView::variantId, LinkedHashMap::new, Collectors.toList()));

    List<EntryView> entryViews =
        entries.stream()
            .map(
                entry ->
                    toEntryView(
                        entry,
                        variantsByEntryId.getOrDefault(entry.getId(), List.of()).stream()
                            .map(
                                variant ->
                                    new VariantView(
                                        variant.id(),
                                        variant.entityVersion(),
                                        variant.audit(),
                                        variant.entryId(),
                                        variant.variantKey(),
                                        variant.name(),
                                        variant.candidateGroupKey(),
                                        variant.status(),
                                        variant.metadataJson(),
                                        variant.sortOrder(),
                                        mappingsByVariantId.getOrDefault(variant.id(), List.of())))
                            .toList()))
            .toList();

    List<ContentTypeView> typeViews =
        contentTypes.stream()
            .map(
                type ->
                    toContentTypeView(type, fieldsByTypeId.getOrDefault(type.getId(), List.of())))
            .toList();

    List<PublishSnapshotView> snapshots =
        snapshotRepository
            .findByProjectIdOrderBySnapshotVersionDesc(
                project.getId(), PageRequest.of(0, PUBLISH_SNAPSHOT_HISTORY_LIMIT))
            .stream()
            .map(this::toPublishSnapshotView)
            .toList();

    return new ProjectDetail(
        toProjectView(project),
        buildPublishRequestAuthoringSha256(project),
        typeViews,
        entryViews,
        snapshots);
  }

  private PublishPackage buildPublishPackage(
      CmsContentProject project, PublishPreflight preflight) {
    Map<String, Object> payload =
        buildArtifactPayload(
            project,
            preflight.entries(),
            preflight.mappings(),
            preflight.localeTags(),
            preflight.completeness(),
            preflight.currentVariants());
    Map<String, Object> publishPackageState = new LinkedHashMap<>();
    publishPackageState.put("formatVersion", PUBLISH_PACKAGE_STATE_FORMAT_VERSION);
    publishPackageState.put("artifactFormatVersion", SNAPSHOT_ARTIFACT_FORMAT_VERSION);
    publishPackageState.putAll(payload);
    String publishPackageJson = objectMapper.writeValueAsStringUnchecked(publishPackageState);
    long publishPackageByteSize = publishPackageJson.getBytes(StandardCharsets.UTF_8).length;
    requirePublishPackageWithinConfiguredByteLimit(publishPackageByteSize);
    return new PublishPackage(
        payload, DigestUtils.sha256Hex(publishPackageJson), publishPackageByteSize);
  }

  private String buildArtifact(Map<String, Object> payload, int version, Instant publishedAt) {
    Map<String, Object> artifact = new LinkedHashMap<>();
    artifact.put("formatVersion", SNAPSHOT_ARTIFACT_FORMAT_VERSION);
    artifact.put("snapshotVersion", version);
    artifact.put("generatedAt", publishedAt.toString());
    artifact.putAll(payload);
    return objectMapper.writeValueAsStringUnchecked(artifact);
  }

  private void requirePublishPackageWithinConfiguredByteLimit(long publishPackageByteSize) {
    long maxPublishArtifactBytes = requireMaxPublishArtifactBytes();
    if (publishPackageByteSize > maxPublishArtifactBytes) {
      throw new IllegalArgumentException(
          "Content project publish package exceeds configured artifact byte limit: "
              + publishPackageByteSize
              + " > "
              + maxPublishArtifactBytes);
    }
  }

  private void requirePublishArtifactWithinConfiguredByteLimit(long artifactByteSize) {
    long maxPublishArtifactBytes = requireMaxPublishArtifactBytes();
    if (artifactByteSize > maxPublishArtifactBytes) {
      throw new IllegalArgumentException(
          "Content project publish artifact exceeds configured artifact byte limit: "
              + artifactByteSize
              + " > "
              + maxPublishArtifactBytes);
    }
  }

  private long requireMaxPublishArtifactBytes() {
    long maxPublishArtifactBytes = configurationProperties.getMaxPublishArtifactBytes();
    if (maxPublishArtifactBytes <= 0) {
      throw new IllegalStateException("CMS max publish artifact bytes must be positive");
    }
    return maxPublishArtifactBytes;
  }

  private Map<String, Object> buildArtifactPayload(
      CmsContentProject project,
      List<CmsContentEntry> entries,
      List<CmsContentFieldMapping> mappings,
      List<String> localeTags,
      List<LocaleCompleteness> completeness,
      Map<Long, Map<String, CmsCurrentVariantRow>> currentVariants) {
    String sourceLocale = project.getRepository().getSourceLocale().getBcp47Tag();
    Map<String, RepositoryLocale> repositoryLocalesByTag = repositoryLocalesByTag(project);
    Map<Long, List<CmsContentFieldMapping>> mappingsByVariantId =
        mappings.stream()
            .collect(
                Collectors.groupingBy(
                    mapping -> mapping.getVariant().getId(),
                    LinkedHashMap::new,
                    Collectors.toList()));
    Set<Long> contentTypeIds =
        entries.stream().map(entry -> entry.getContentType().getId()).collect(Collectors.toSet());
    List<CmsContentType> contentTypes =
        contentTypeRepository.findByProjectIdOrderByNameAscIdAsc(project.getId()).stream()
            .filter(contentType -> contentTypeIds.contains(contentType.getId()))
            .sorted(
                Comparator.comparing(CmsContentType::getTypeKey)
                    .thenComparing(CmsContentType::getId))
            .toList();
    List<CmsContentEntryVariant> variants =
        entries.isEmpty()
            ? List.of()
            : variantRepository
                .findByEntryIdInOrderBySortOrderAscVariantKeyAscIdAsc(
                    entries.stream().map(CmsContentEntry::getId).toList())
                .stream()
                .filter(this::isPublishableVariant)
                .toList();
    Map<Long, List<CmsContentEntryVariant>> variantsByEntryId =
        variants.stream()
            .collect(
                Collectors.groupingBy(
                    variant -> variant.getEntry().getId(),
                    LinkedHashMap::new,
                    Collectors.toList()));

    Map<String, Object> artifact = new LinkedHashMap<>();
    artifact.put(
        "delivery",
        artifactObject(
            "runtimeDependency",
            "none",
            "projectHint",
            project.getDeliveryHint(),
            "supportedTargets",
            SUPPORTED_DELIVERY_TARGETS));
    artifact.put(
        "project",
        artifactObject(
            "key",
            project.getProjectKey(),
            "name",
            project.getName(),
            "sourceLocale",
            sourceLocale));
    artifact.put("locales", localeTags);
    artifact.put("contentTypes", contentTypes.stream().map(this::toArtifactContentType).toList());
    artifact.put(
        "entries",
        entries.stream()
            .map(
                entry ->
                    toArtifactEntry(
                        entry,
                        variantsByEntryId.getOrDefault(entry.getId(), List.of()),
                        mappingsByVariantId,
                        currentVariants,
                        repositoryLocalesByTag,
                        localeTags,
                        sourceLocale))
            .toList());
    artifact.put("completeness", completeness);
    return artifact;
  }

  private Map<String, Object> toArtifactContentType(CmsContentType contentType) {
    Map<String, Object> type = new LinkedHashMap<>();
    type.put("key", contentType.getTypeKey());
    type.put("name", contentType.getName());
    type.put("schemaVersion", contentType.getSchemaVersion());
    type.put("metadataSchema", parseMetadataSchemaJson(contentType.getMetadataSchemaJson()));
    type.put(
        "fields",
        fieldRepository
            .findByContentTypeIdOrderBySortOrderAscFieldKeyAscIdAsc(contentType.getId())
            .stream()
            .map(
                field ->
                    artifactObject(
                        "key",
                        field.getFieldKey(),
                        "name",
                        field.getName(),
                        "type",
                        field.getFieldType().name(),
                        "localizable",
                        field.getLocalizable(),
                        "required",
                        field.getRequired()))
            .toList());
    return type;
  }

  private Map<String, Object> artifactObject(Object... keyValues) {
    if (keyValues.length % 2 != 0) {
      throw new IllegalArgumentException("Artifact object keys and values must be paired");
    }
    Map<String, Object> object = new LinkedHashMap<>();
    for (int index = 0; index < keyValues.length; index += 2) {
      object.put((String) keyValues[index], keyValues[index + 1]);
    }
    return object;
  }

  private Map<String, Object> toArtifactEntry(
      CmsContentEntry entry,
      List<CmsContentEntryVariant> variants,
      Map<Long, List<CmsContentFieldMapping>> mappingsByVariantId,
      Map<Long, Map<String, CmsCurrentVariantRow>> currentVariants,
      Map<String, RepositoryLocale> repositoryLocalesByTag,
      List<String> localeTags,
      String sourceLocale) {
    Map<String, Object> entryJson = new LinkedHashMap<>();
    entryJson.put("key", entry.getEntryKey());
    entryJson.put("name", entry.getName());
    entryJson.put("type", entry.getContentType().getTypeKey());
    entryJson.put("status", entry.getStatus().name());
    entryJson.put("metadata", parseJson(entry.getMetadataJson()));
    entryJson.put(
        "variants",
        variants.stream()
            .map(
                variant ->
                    toArtifactVariant(
                        variant,
                        mappingsByVariantId.getOrDefault(variant.getId(), List.of()),
                        currentVariants,
                        repositoryLocalesByTag,
                        localeTags,
                        sourceLocale))
            .toList());
    return entryJson;
  }

  private Map<String, Object> toArtifactVariant(
      CmsContentEntryVariant variant,
      List<CmsContentFieldMapping> mappings,
      Map<Long, Map<String, CmsCurrentVariantRow>> currentVariants,
      Map<String, RepositoryLocale> repositoryLocalesByTag,
      List<String> localeTags,
      String sourceLocale) {
    Map<String, Object> variantJson = new LinkedHashMap<>();
    variantJson.put("key", variant.getVariantKey());
    variantJson.put("name", variant.getName());
    variantJson.put("status", variant.getStatus().name());
    variantJson.put("candidateGroupKey", variant.getCandidateGroupKey());
    variantJson.put("metadata", parseJson(variant.getMetadataJson()));

    Map<String, Object> fields = new LinkedHashMap<>();
    for (CmsContentFieldMapping mapping : mappings.stream().sorted(mappingComparator()).toList()) {
      TMTextUnit textUnit = mapping.getTmTextUnit();
      Map<String, Object> fieldJson = new LinkedHashMap<>();
      fieldJson.put("stringId", textUnit.getName());
      fieldJson.put("source", textUnit.getContent());
      Map<String, String> values = new LinkedHashMap<>();
      for (String localeTag : localeTags) {
        if (sourceLocale.equals(localeTag)) {
          values.put(localeTag, textUnit.getContent());
          continue;
        }
        CmsCurrentVariantRow row =
            resolveCurrentVariant(
                currentVariants, repositoryLocalesByTag, textUnit.getId(), localeTag);
        values.put(localeTag, row == null ? null : row.target());
      }
      fieldJson.put("values", values);
      fields.put(mapping.getField().getFieldKey(), fieldJson);
    }
    variantJson.put("fields", fields);
    return variantJson;
  }

  private JsonNode validateSnapshotArtifactIntegrity(CmsPublishSnapshot snapshot) {
    requireSnapshotPublisherUsername(snapshot);
    validateSnapshotPublishRequestMetadata(snapshot);
    if (!CmsPublishSnapshot.Status.PUBLISHED.equals(snapshot.getStatus())) {
      throw new IllegalStateException("Publish snapshot status is invalid: " + snapshot.getId());
    }
    if (snapshot.getArtifactJson() == null) {
      throw new IllegalStateException(
          "Publish snapshot artifact JSON is missing: " + snapshot.getId());
    }
    String artifactSha256 = DigestUtils.sha256Hex(snapshot.getArtifactJson());
    if (!Objects.equals(snapshot.getArtifactSha256(), artifactSha256)) {
      throw new IllegalStateException(
          "Publish snapshot artifact SHA-256 mismatch: " + snapshot.getId());
    }
    long artifactByteSize = snapshot.getArtifactJson().getBytes(StandardCharsets.UTF_8).length;
    if (!Objects.equals(snapshot.getArtifactByteSize(), artifactByteSize)) {
      throw new IllegalStateException(
          "Publish snapshot artifact byte size mismatch: " + snapshot.getId());
    }
    snapshotSigningService.validate(snapshot);
    JsonNode artifact = readSnapshotJson(snapshot.getArtifactJson(), "artifact JSON", snapshot);
    if (!artifact.isObject()) {
      throw new IllegalStateException(
          "Publish snapshot artifact JSON must be an object: " + snapshot.getId());
    }
    validateSnapshotArtifactMetadata(snapshot, artifact);
    return artifact;
  }

  private void validateSnapshotArtifactMetadata(CmsPublishSnapshot snapshot, JsonNode artifact) {
    requireSnapshotArtifactFields(artifact, SNAPSHOT_ARTIFACT_FIELDS, snapshot, "envelope");
    if (!SNAPSHOT_ARTIFACT_FORMAT_VERSION.equals(artifact.path("formatVersion").asText())) {
      throw new IllegalStateException(
          "Publish snapshot artifact format version mismatch: " + snapshot.getId());
    }
    if (!artifact.path("snapshotVersion").canConvertToInt()
        || !Objects.equals(
            snapshot.getSnapshotVersion(), artifact.path("snapshotVersion").asInt())) {
      throw new IllegalStateException(
          "Publish snapshot artifact version mismatch: " + snapshot.getId());
    }
    JsonNode artifactProject = validateSnapshotArtifactProject(snapshot, artifact);
    List<String> storedLocaleTags = parseSnapshotLocaleTags(snapshot);
    List<String> artifactLocaleTags = parseArtifactLocaleTags(artifact, snapshot);
    if (!Objects.equals(storedLocaleTags, artifactLocaleTags)) {
      throw new IllegalStateException("Publish snapshot locale tags mismatch: " + snapshot.getId());
    }
    validateSnapshotArtifactEnvelope(snapshot, artifact, artifactProject, storedLocaleTags);
    JsonNode storedCompleteness =
        canonicalizeJson(
            readSnapshotJson(snapshot.getCompletenessJson(), "completeness JSON", snapshot));
    JsonNode artifactCompleteness = artifact.path("completeness");
    if (!artifactCompleteness.isArray()) {
      throw new IllegalStateException(
          "Publish snapshot artifact completeness is missing: " + snapshot.getId());
    }
    validateSnapshotArtifactCompleteness(
        snapshot, artifactCompleteness, storedLocaleTags, countSnapshotArtifactFields(artifact));
    if (!Objects.equals(storedCompleteness, canonicalizeJson(artifactCompleteness))) {
      throw new IllegalStateException(
          "Publish snapshot completeness mismatch: " + snapshot.getId());
    }
  }

  private JsonNode validateSnapshotArtifactProject(CmsPublishSnapshot snapshot, JsonNode artifact) {
    JsonNode artifactProject = requireSnapshotArtifactObject(artifact, "project", snapshot);
    requireSnapshotArtifactFields(
        artifactProject, SNAPSHOT_ARTIFACT_PROJECT_FIELDS, snapshot, "project");
    if (!Objects.equals(
        snapshot.getProject().getProjectKey(),
        requireSnapshotArtifactKey(artifactProject, "key", snapshot))) {
      throw new IllegalStateException(
          "Publish snapshot artifact project mismatch: " + snapshot.getId());
    }
    return artifactProject;
  }

  private void validateSnapshotArtifactEnvelope(
      CmsPublishSnapshot snapshot,
      JsonNode artifact,
      JsonNode artifactProject,
      List<String> localeTags) {
    validateSnapshotArtifactGeneratedAt(snapshot, artifact);
    validateSnapshotArtifactDelivery(snapshot, artifact);
    String sourceLocale =
        validateSnapshotArtifactProjectEnvelope(snapshot, artifactProject, localeTags);
    Map<String, ArtifactContentTypeSchema> contentTypeSchemasByKey =
        validateSnapshotArtifactContentTypes(snapshot, artifact);
    validateSnapshotArtifactEntries(
        snapshot, artifact, contentTypeSchemasByKey, localeTags, sourceLocale);
  }

  private void validateSnapshotArtifactGeneratedAt(CmsPublishSnapshot snapshot, JsonNode artifact) {
    String generatedAt = requireSnapshotArtifactText(artifact, "generatedAt", snapshot);
    Instant generatedAtInstant;
    try {
      generatedAtInstant = Instant.parse(generatedAt);
    } catch (RuntimeException ex) {
      throw invalidSnapshotArtifact(snapshot, "generatedAt");
    }
    if (!Objects.equals(generatedAtInstant.toString(), generatedAt)) {
      throw invalidSnapshotArtifact(snapshot, "generatedAt");
    }
    if (!Objects.equals(snapshot.getPublishedAt(), generatedAt)) {
      throw invalidSnapshotArtifact(snapshot, "generatedAt");
    }
  }

  private void validateSnapshotArtifactDelivery(CmsPublishSnapshot snapshot, JsonNode artifact) {
    JsonNode delivery = requireSnapshotArtifactObject(artifact, "delivery", snapshot);
    requireSnapshotArtifactFields(
        delivery, SNAPSHOT_ARTIFACT_DELIVERY_FIELDS, snapshot, "delivery");
    if (!"none".equals(requireSnapshotArtifactText(delivery, "runtimeDependency", snapshot))) {
      throw invalidSnapshotArtifact(snapshot, "delivery");
    }
    if (!DELIVERY_HINTS.contains(requireSnapshotArtifactText(delivery, "projectHint", snapshot))) {
      throw invalidSnapshotArtifact(snapshot, "delivery");
    }
    if (!Objects.equals(
        SUPPORTED_DELIVERY_TARGETS,
        parseSnapshotArtifactTextArray(delivery, "supportedTargets", snapshot))) {
      throw invalidSnapshotArtifact(snapshot, "delivery");
    }
  }

  private String validateSnapshotArtifactProjectEnvelope(
      CmsPublishSnapshot snapshot, JsonNode artifactProject, List<String> localeTags) {
    requireSnapshotArtifactText(artifactProject, "name", snapshot);
    String sourceLocale = requireSnapshotArtifactText(artifactProject, "sourceLocale", snapshot);
    if (!Objects.equals(localeTags.getFirst(), sourceLocale)) {
      throw invalidSnapshotArtifact(snapshot, "project");
    }
    return sourceLocale;
  }

  private void validateSnapshotArtifactCompleteness(
      CmsPublishSnapshot snapshot,
      JsonNode completeness,
      List<String> localeTags,
      int artifactFieldCount) {
    if (artifactFieldCount <= 0 || completeness.size() != localeTags.size()) {
      throw invalidSnapshotArtifact(snapshot, "completeness");
    }
    for (int index = 0; index < localeTags.size(); index++) {
      JsonNode localeCompleteness = completeness.get(index);
      if (!localeCompleteness.isObject()) {
        throw invalidSnapshotArtifact(snapshot, "completeness");
      }
      requireSnapshotArtifactFields(
          localeCompleteness, SNAPSHOT_ARTIFACT_COMPLETENESS_FIELDS, snapshot, "completeness");
      if (!Objects.equals(
              localeTags.get(index),
              requireSnapshotArtifactText(localeCompleteness, "localeTag", snapshot))
          || !hasSnapshotArtifactInt(localeCompleteness, "totalFields", artifactFieldCount)
          || !hasSnapshotArtifactInt(localeCompleteness, "approvedFields", artifactFieldCount)
          || !hasSnapshotArtifactInt(localeCompleteness, "missingFields", 0)
          || !hasSnapshotArtifactInt(localeCompleteness, "reviewNeededFields", 0)
          || !hasSnapshotArtifactInt(localeCompleteness, "translationNeededFields", 0)
          || !localeCompleteness.path("complete").isBoolean()
          || !localeCompleteness.path("complete").asBoolean()) {
        throw invalidSnapshotArtifact(snapshot, "completeness");
      }
    }
  }

  private int countSnapshotArtifactFields(JsonNode artifact) {
    int fieldCount = 0;
    for (JsonNode entry : artifact.path("entries")) {
      for (JsonNode variant : entry.path("variants")) {
        fieldCount += variant.path("fields").size();
      }
    }
    return fieldCount;
  }

  private Map<String, ArtifactContentTypeSchema> validateSnapshotArtifactContentTypes(
      CmsPublishSnapshot snapshot, JsonNode artifact) {
    JsonNode contentTypes = requireSnapshotArtifactArray(artifact, "contentTypes", snapshot);
    if (contentTypes.isEmpty()) {
      throw invalidSnapshotArtifact(snapshot, "content types");
    }
    Map<String, ArtifactContentTypeSchema> contentTypeSchemasByKey = new LinkedHashMap<>();
    for (JsonNode contentType : contentTypes) {
      if (!contentType.isObject()) {
        throw invalidSnapshotArtifact(snapshot, "content types");
      }
      requireSnapshotArtifactFields(
          contentType, SNAPSHOT_ARTIFACT_CONTENT_TYPE_FIELDS, snapshot, "content types");
      String typeKey = requireSnapshotArtifactKey(contentType, "key", snapshot);
      if (contentTypeSchemasByKey.containsKey(typeKey)) {
        throw invalidSnapshotArtifact(snapshot, "content types");
      }
      requireSnapshotArtifactText(contentType, "name", snapshot);
      if (!contentType.path("schemaVersion").canConvertToInt()
          || contentType.path("schemaVersion").asInt() < 1
          || !contentType.path("metadataSchema").isObject()) {
        throw invalidSnapshotArtifact(snapshot, "content types");
      }
      validateSnapshotArtifactMetadataSchema(snapshot, contentType.path("metadataSchema"));
      JsonNode fields = requireSnapshotArtifactArray(contentType, "fields", snapshot);
      if (fields.isEmpty()) {
        throw invalidSnapshotArtifact(snapshot, "content type fields");
      }
      Set<String> fieldKeys = new LinkedHashSet<>();
      Set<String> requiredFieldKeys = new LinkedHashSet<>();
      Map<String, CmsContentTypeField.FieldType> fieldTypesByKey = new LinkedHashMap<>();
      for (JsonNode field : fields) {
        if (!field.isObject()) {
          throw invalidSnapshotArtifact(snapshot, "content type fields");
        }
        requireSnapshotArtifactFields(
            field, SNAPSHOT_ARTIFACT_CONTENT_TYPE_FIELD_FIELDS, snapshot, "content type fields");
        String fieldKey = requireSnapshotArtifactKey(field, "key", snapshot);
        if (!fieldKeys.add(fieldKey)) {
          throw invalidSnapshotArtifact(snapshot, "content type fields");
        }
        requireSnapshotArtifactText(field, "name", snapshot);
        CmsContentTypeField.FieldType fieldType = parseSnapshotArtifactFieldType(field, snapshot);
        if (!field.path("localizable").isBoolean()
            || !field.path("localizable").asBoolean()
            || !field.path("required").isBoolean()) {
          throw invalidSnapshotArtifact(snapshot, "content type fields");
        }
        fieldTypesByKey.put(fieldKey, fieldType);
        if (field.path("required").asBoolean()) {
          requiredFieldKeys.add(fieldKey);
        }
      }
      contentTypeSchemasByKey.put(
          typeKey,
          new ArtifactContentTypeSchema(
              Set.copyOf(fieldKeys),
              Set.copyOf(requiredFieldKeys),
              Map.copyOf(fieldTypesByKey),
              contentType.path("metadataSchema")));
    }
    return contentTypeSchemasByKey;
  }

  private void validateSnapshotArtifactEntries(
      CmsPublishSnapshot snapshot,
      JsonNode artifact,
      Map<String, ArtifactContentTypeSchema> contentTypeSchemasByKey,
      List<String> localeTags,
      String sourceLocale) {
    JsonNode entries = requireSnapshotArtifactArray(artifact, "entries", snapshot);
    if (entries.isEmpty()) {
      throw invalidSnapshotArtifact(snapshot, "entries");
    }
    Set<String> entryKeys = new LinkedHashSet<>();
    for (JsonNode entry : entries) {
      if (!entry.isObject()) {
        throw invalidSnapshotArtifact(snapshot, "entries");
      }
      requireSnapshotArtifactFields(entry, SNAPSHOT_ARTIFACT_ENTRY_FIELDS, snapshot, "entries");
      if (!entryKeys.add(requireSnapshotArtifactKey(entry, "key", snapshot))) {
        throw invalidSnapshotArtifact(snapshot, "entries");
      }
      requireSnapshotArtifactText(entry, "name", snapshot);
      ArtifactContentTypeSchema contentTypeSchema =
          contentTypeSchemasByKey.get(requireSnapshotArtifactKey(entry, "type", snapshot));
      if (contentTypeSchema == null
          || !"READY".equals(requireSnapshotArtifactText(entry, "status", snapshot))
          || !entry.path("metadata").isObject()) {
        throw invalidSnapshotArtifact(snapshot, "entries");
      }
      validateSnapshotArtifactEntryMetadata(snapshot, contentTypeSchema, entry.path("metadata"));
      validateSnapshotArtifactVariants(
          snapshot,
          requireSnapshotArtifactArray(entry, "variants", snapshot),
          contentTypeSchema,
          localeTags,
          sourceLocale);
    }
  }

  private void validateSnapshotArtifactVariants(
      CmsPublishSnapshot snapshot,
      JsonNode variants,
      ArtifactContentTypeSchema contentTypeSchema,
      List<String> localeTags,
      String sourceLocale) {
    if (variants.isEmpty()) {
      throw invalidSnapshotArtifact(snapshot, "variants");
    }
    Set<String> variantKeys = new LinkedHashSet<>();
    int controlVariantCount = 0;
    for (JsonNode variant : variants) {
      if (!variant.isObject()) {
        throw invalidSnapshotArtifact(snapshot, "variants");
      }
      requireSnapshotArtifactFields(
          variant, SNAPSHOT_ARTIFACT_VARIANT_FIELDS, snapshot, "variants");
      if (!variantKeys.add(requireSnapshotArtifactKey(variant, "key", snapshot))) {
        throw invalidSnapshotArtifact(snapshot, "variants");
      }
      requireSnapshotArtifactText(variant, "name", snapshot);
      String status = requireSnapshotArtifactText(variant, "status", snapshot);
      JsonNode candidateGroupKey = variant.path("candidateGroupKey");
      if (!Set.of("CONTROL", "CANDIDATE").contains(status)
          || !variant.path("metadata").isObject()
          || (!candidateGroupKey.isNull()
              && (!candidateGroupKey.isTextual() || !isArtifactKey(candidateGroupKey.asText())))
          || ("CANDIDATE".equals(status) && candidateGroupKey.isNull())) {
        throw invalidSnapshotArtifact(snapshot, "variants");
      }
      if ("CONTROL".equals(status)) {
        controlVariantCount++;
      }
      validateSnapshotArtifactFields(
          snapshot,
          requireSnapshotArtifactObject(variant, "fields", snapshot),
          contentTypeSchema,
          localeTags,
          sourceLocale);
    }
    if (controlVariantCount != 1) {
      throw invalidSnapshotArtifact(snapshot, "control variants");
    }
  }

  private void validateSnapshotArtifactFields(
      CmsPublishSnapshot snapshot,
      JsonNode fields,
      ArtifactContentTypeSchema contentTypeSchema,
      List<String> localeTags,
      String sourceLocale) {
    if (fields.isEmpty()) {
      throw invalidSnapshotArtifact(snapshot, "fields");
    }
    var fieldEntries = fields.fields();
    while (fieldEntries.hasNext()) {
      Map.Entry<String, JsonNode> fieldEntry = fieldEntries.next();
      if (!contentTypeSchema.fieldKeys().contains(fieldEntry.getKey())
          || !isArtifactKey(fieldEntry.getKey())
          || !fieldEntry.getValue().isObject()) {
        throw invalidSnapshotArtifact(snapshot, "fields");
      }
      JsonNode field = fieldEntry.getValue();
      requireSnapshotArtifactFields(
          field, SNAPSHOT_ARTIFACT_RUNTIME_FIELD_FIELDS, snapshot, "fields");
      if (!hasUsableMojitoStringId(requireSnapshotArtifactText(field, "stringId", snapshot))) {
        throw invalidSnapshotArtifact(snapshot, "fields");
      }
      String source = requireSnapshotArtifactText(field, "source", snapshot);
      JsonNode values = requireSnapshotArtifactObject(field, "values", snapshot);
      if (values.size() != localeTags.size()) {
        throw invalidSnapshotArtifact(snapshot, "field values");
      }
      for (String localeTag : localeTags) {
        JsonNode value = values.path(localeTag);
        if (!value.isTextual() || value.asText().isBlank()) {
          throw invalidSnapshotArtifact(snapshot, "field values");
        }
      }
      if (!Objects.equals(source, values.path(sourceLocale).asText())) {
        throw invalidSnapshotArtifact(snapshot, "field values");
      }
      validateSnapshotArtifactFieldIntegrity(
          snapshot,
          contentTypeSchema.fieldTypesByKey().get(fieldEntry.getKey()),
          source,
          values,
          localeTags);
    }
    if (!contentTypeSchema.requiredFieldKeys().stream().allMatch(fields::has)) {
      throw invalidSnapshotArtifact(snapshot, "required fields");
    }
  }

  private void validateSnapshotArtifactMetadataSchema(
      CmsPublishSnapshot snapshot, JsonNode metadataSchema) {
    try {
      validateMetadataSchema(metadataSchema);
    } catch (IllegalArgumentException ex) {
      throw invalidSnapshotArtifact(snapshot, "content types");
    }
  }

  private void validateSnapshotArtifactEntryMetadata(
      CmsPublishSnapshot snapshot, ArtifactContentTypeSchema contentTypeSchema, JsonNode metadata) {
    try {
      validateMetadata(contentTypeSchema.metadataSchema(), metadata, "Snapshot entry metadata");
    } catch (IllegalArgumentException ex) {
      throw invalidSnapshotArtifact(snapshot, "entry metadata");
    }
  }

  private JsonNode requireSnapshotArtifactObject(
      JsonNode parent, String fieldName, CmsPublishSnapshot snapshot) {
    JsonNode object = parent.path(fieldName);
    if (!object.isObject()) {
      throw missingSnapshotArtifact(snapshot, fieldName);
    }
    return object;
  }

  private JsonNode requireSnapshotArtifactArray(
      JsonNode parent, String fieldName, CmsPublishSnapshot snapshot) {
    JsonNode array = parent.path(fieldName);
    if (!array.isArray()) {
      throw missingSnapshotArtifact(snapshot, fieldName);
    }
    return array;
  }

  private void requireSnapshotArtifactFields(
      JsonNode object, Set<String> expectedFields, CmsPublishSnapshot snapshot, String fieldName) {
    Set<String> actualFields = new LinkedHashSet<>();
    object.fieldNames().forEachRemaining(actualFields::add);
    if (!Objects.equals(expectedFields, actualFields)) {
      throw invalidSnapshotArtifact(snapshot, fieldName);
    }
  }

  private List<String> parseSnapshotArtifactTextArray(
      JsonNode parent, String fieldName, CmsPublishSnapshot snapshot) {
    JsonNode array = requireSnapshotArtifactArray(parent, fieldName, snapshot);
    List<String> values = new ArrayList<>();
    for (JsonNode value : array) {
      if (!value.isTextual() || value.asText().isBlank()) {
        throw invalidSnapshotArtifact(snapshot, fieldName);
      }
      values.add(value.asText());
    }
    return List.copyOf(values);
  }

  private String requireSnapshotArtifactText(
      JsonNode parent, String fieldName, CmsPublishSnapshot snapshot) {
    JsonNode value = parent.path(fieldName);
    if (!value.isTextual() || value.asText().isBlank()) {
      throw missingSnapshotArtifact(snapshot, fieldName);
    }
    return value.asText();
  }

  private String requireSnapshotArtifactKey(
      JsonNode parent, String fieldName, CmsPublishSnapshot snapshot) {
    String key = requireSnapshotArtifactText(parent, fieldName, snapshot);
    if (!isArtifactKey(key)) {
      throw invalidSnapshotArtifact(snapshot, fieldName);
    }
    return key;
  }

  private boolean isArtifactKey(String key) {
    return key != null && KEY_PATTERN.matcher(key).matches();
  }

  private boolean isSha256(String value) {
    return value != null && value.matches("[0-9a-f]{64}");
  }

  private boolean hasSnapshotArtifactInt(JsonNode parent, String fieldName, int expectedValue) {
    JsonNode value = parent.path(fieldName);
    return value.canConvertToInt() && value.asInt() == expectedValue;
  }

  private CmsContentTypeField.FieldType parseSnapshotArtifactFieldType(
      JsonNode field, CmsPublishSnapshot snapshot) {
    try {
      return CmsContentTypeField.FieldType.valueOf(
          requireSnapshotArtifactText(field, "type", snapshot));
    } catch (IllegalArgumentException ex) {
      throw invalidSnapshotArtifact(snapshot, "content type fields");
    }
  }

  private void validateSnapshotArtifactFieldIntegrity(
      CmsPublishSnapshot snapshot,
      CmsContentTypeField.FieldType fieldType,
      String source,
      JsonNode values,
      List<String> localeTags) {
    if (!CmsContentTypeField.FieldType.ICU_MESSAGE.equals(fieldType)) {
      return;
    }
    try {
      messageFormatIntegrityChecker.check(source, source);
      for (String localeTag : localeTags) {
        messageFormatIntegrityChecker.check(source, values.path(localeTag).asText());
      }
    } catch (MessageFormatIntegrityCheckerException ex) {
      throw invalidSnapshotArtifact(snapshot, "ICU fields");
    }
  }

  private IllegalStateException missingSnapshotArtifact(
      CmsPublishSnapshot snapshot, String fieldName) {
    return new IllegalStateException(
        "Publish snapshot artifact " + fieldName + " is missing: " + snapshot.getId());
  }

  private IllegalStateException invalidSnapshotArtifact(
      CmsPublishSnapshot snapshot, String fieldName) {
    return new IllegalStateException(
        "Publish snapshot artifact has invalid " + fieldName + ": " + snapshot.getId());
  }

  private record ArtifactContentTypeSchema(
      Set<String> fieldKeys,
      Set<String> requiredFieldKeys,
      Map<String, CmsContentTypeField.FieldType> fieldTypesByKey,
      JsonNode metadataSchema) {}

  private record EntryPublishableState(List<CmsContentFieldMapping> mappings) {}

  private record PublishPreflight(
      List<CmsContentEntry> entries,
      List<CmsContentFieldMapping> mappings,
      List<String> localeTags,
      Map<Long, Map<String, CmsCurrentVariantRow>> currentVariants,
      List<LocaleCompleteness> completeness) {}

  private JsonNode readSnapshotJson(String json, String label, CmsPublishSnapshot snapshot) {
    if (json == null || json.isBlank()) {
      throw new IllegalStateException(
          "Publish snapshot " + label + " is missing: " + snapshot.getId());
    }
    try {
      return strictTreeObjectMapper.readTree(json);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException(
          "Publish snapshot " + label + " is invalid: " + snapshot.getId(), ex);
    }
  }

  private List<String> parseSnapshotLocaleTags(CmsPublishSnapshot snapshot) {
    if (snapshot.getLocaleTags() == null || snapshot.getLocaleTags().isBlank()) {
      throw new IllegalStateException(
          "Publish snapshot locale tags are missing: " + snapshot.getId());
    }
    List<String> localeTags =
        List.of(snapshot.getLocaleTags().split(",")).stream().map(String::trim).toList();
    if (localeTags.stream().anyMatch(String::isBlank)
        || localeTags.stream().anyMatch(localeTag -> !isCanonicalLocaleTag(localeTag))
        || new LinkedHashSet<>(localeTags).size() != localeTags.size()
        || !isCanonicalLocaleTagOrder(localeTags)
        || !Objects.equals(snapshot.getLocaleTags(), String.join(",", localeTags))) {
      throw new IllegalStateException(
          "Publish snapshot locale tags are invalid: " + snapshot.getId());
    }
    return localeTags;
  }

  private void validateSnapshotPublishRequestMetadata(CmsPublishSnapshot snapshot) {
    String publishRequestKey = snapshot.getPublishRequestKey();
    if (publishRequestKey == null
        || publishRequestKey.length() > CmsPublishSnapshot.PUBLISH_REQUEST_KEY_MAX_LENGTH
        || !isArtifactKey(publishRequestKey)) {
      throw new IllegalStateException(
          "Publish snapshot request key is invalid: " + snapshot.getId());
    }
    parseSnapshotPublishRequestLocaleTags(snapshot);
    String publishRequestAuthoringSha256 = snapshot.getPublishRequestAuthoringSha256();
    if (!isSha256(publishRequestAuthoringSha256)) {
      throw new IllegalStateException(
          "Publish snapshot request authoring SHA-256 is invalid: " + snapshot.getId());
    }
    String publishRequestPackageSha256 = snapshot.getPublishRequestPackageSha256();
    if (!isSha256(publishRequestPackageSha256)) {
      throw new IllegalStateException(
          "Publish snapshot request package SHA-256 is invalid: " + snapshot.getId());
    }
  }

  private List<String> parseSnapshotPublishRequestLocaleTags(CmsPublishSnapshot snapshot) {
    String publishRequestLocaleTags = snapshot.getPublishRequestLocaleTags();
    if (publishRequestLocaleTags == null) {
      throw new IllegalStateException(
          "Publish snapshot request locale tags are missing: " + snapshot.getId());
    }
    if (publishRequestLocaleTags.isEmpty()) {
      return List.of();
    }
    List<String> localeTags =
        List.of(publishRequestLocaleTags.split(",", -1)).stream().map(String::trim).toList();
    if (localeTags.stream().anyMatch(String::isBlank)
        || localeTags.stream().anyMatch(localeTag -> !isCanonicalLocaleTag(localeTag))
        || new LinkedHashSet<>(localeTags).size() != localeTags.size()
        || !Objects.equals(localeTags, localeTags.stream().sorted().toList())
        || !Objects.equals(publishRequestLocaleTags, String.join(",", localeTags))) {
      throw new IllegalStateException(
          "Publish snapshot request locale tags are invalid: " + snapshot.getId());
    }
    return List.copyOf(localeTags);
  }

  private List<String> parseArtifactLocaleTags(JsonNode artifact, CmsPublishSnapshot snapshot) {
    JsonNode localeTags = artifact.path("locales");
    if (!localeTags.isArray()) {
      throw new IllegalStateException(
          "Publish snapshot artifact locales are missing: " + snapshot.getId());
    }
    List<String> result = new ArrayList<>();
    for (JsonNode localeTag : localeTags) {
      if (!localeTag.isTextual() || localeTag.asText().isBlank()) {
        throw new IllegalStateException(
            "Publish snapshot artifact locales are invalid: " + snapshot.getId());
      }
      result.add(localeTag.asText());
    }
    if (result.stream().anyMatch(localeTag -> !isCanonicalLocaleTag(localeTag))
        || new LinkedHashSet<>(result).size() != result.size()
        || !isCanonicalLocaleTagOrder(result)) {
      throw new IllegalStateException(
          "Publish snapshot artifact locales are invalid: " + snapshot.getId());
    }
    return List.copyOf(result);
  }

  private boolean isCanonicalLocaleTag(String localeTag) {
    return Objects.equals(Locale.forLanguageTag(localeTag).toLanguageTag(), localeTag);
  }

  private boolean isCanonicalLocaleTagOrder(List<String> localeTags) {
    return localeTags.size() <= 2
        || Objects.equals(
            localeTags.subList(1, localeTags.size()),
            localeTags.stream().skip(1).sorted().toList());
  }

  private List<LocaleCompleteness> buildCompleteness(
      CmsContentProject project,
      List<CmsContentFieldMapping> mappings,
      List<String> localeTags,
      Map<Long, Map<String, CmsCurrentVariantRow>> currentVariants) {
    String sourceLocale = project.getRepository().getSourceLocale().getBcp47Tag();
    Map<String, RepositoryLocale> repositoryLocalesByTag = repositoryLocalesByTag(project);
    int total = mappings.size();
    List<LocaleCompleteness> result = new ArrayList<>();
    for (String localeTag : localeTags) {
      int approved = 0;
      int missing = 0;
      int reviewNeeded = 0;
      int translationNeeded = 0;
      for (CmsContentFieldMapping mapping : mappings) {
        if (sourceLocale.equals(localeTag)) {
          approved++;
          continue;
        }
        CmsCurrentVariantRow row =
            resolveCurrentVariant(
                currentVariants,
                repositoryLocalesByTag,
                mapping.getTmTextUnit().getId(),
                localeTag);
        if (row == null || row.target() == null || row.target().isBlank()) {
          missing++;
        } else if (TMTextUnitVariant.Status.APPROVED.equals(row.status())
            && Boolean.TRUE.equals(row.includedInLocalizedFile())) {
          approved++;
        } else if (TMTextUnitVariant.Status.REVIEW_NEEDED.equals(row.status())) {
          reviewNeeded++;
        } else {
          translationNeeded++;
        }
      }
      result.add(
          new LocaleCompleteness(
              localeTag,
              total,
              approved,
              missing,
              reviewNeeded,
              translationNeeded,
              total == approved));
    }
    return result;
  }

  private EntryCompletenessView toEntryCompletenessView(
      CmsContentEntry entry,
      List<CmsContentFieldMapping> mappings,
      List<String> localeTags,
      Map<Long, Map<String, CmsCurrentVariantRow>> currentVariants) {
    List<CmsContentFieldMapping> entryMappings =
        mappings.stream()
            .filter(
                mapping -> Objects.equals(mapping.getVariant().getEntry().getId(), entry.getId()))
            .toList();
    return new EntryCompletenessView(
        entry.getId(),
        entry.getEntryKey(),
        buildCompleteness(entry.getProject(), entryMappings, localeTags, currentVariants));
  }

  private PublishPreflight buildPublishPreflight(
      CmsContentProject project, List<String> requestedLocaleTags, boolean lockTranslationState) {
    validateProjectRepositoryAvailable(project);
    validateProjectVirtualAssetAvailable(project);
    List<CmsContentEntry> publishableEntries = findPublishableEntries(project.getId());
    if (publishableEntries.isEmpty()) {
      throw new IllegalArgumentException("Content project has no ready entries to publish");
    }
    List<CmsContentEntryVariant> publishableVariants = findPublishableVariants(publishableEntries);
    requireExactlyOneActiveControlVariant(
        publishableEntries,
        publishableVariants,
        "Cannot publish ready entries without exactly one active control variant: ");
    validatePublishableVariantCandidateGroups(
        publishableVariants, "Cannot publish candidate variants without candidate group keys: ");
    validateArtifactMetadata(publishableEntries, publishableVariants);
    List<CmsContentFieldMapping> mappings =
        mappingRepository.findMappingsByProjectId(project.getId()).stream()
            .filter(this::isPublishableMapping)
            .toList();
    if (mappings.isEmpty()) {
      throw new IllegalArgumentException("Content project has no mapped localizable fields");
    }
    requirePublishableVariantsHaveMappings(
        publishableVariants,
        mappings,
        "Cannot publish ready variants without mapped localizable fields: ");
    validatePublishableMappingsActiveAndTranslatable(project, mappings);
    List<String> missingRequiredMappings =
        findMissingRequiredFieldMappings(publishableEntries, publishableVariants, mappings);
    if (!missingRequiredMappings.isEmpty()) {
      throw new IllegalArgumentException(
          "Cannot publish with missing required field mappings: "
              + String.join(", ", missingRequiredMappings));
    }

    List<String> localeTags = resolveLocaleTags(project, requestedLocaleTags);
    Map<Long, Map<String, CmsCurrentVariantRow>> currentVariants =
        loadCurrentVariants(project, mappings, localeTags, lockTranslationState);
    validatePublishableFieldIntegrity(project, mappings, localeTags, currentVariants);
    return new PublishPreflight(
        publishableEntries,
        mappings,
        localeTags,
        currentVariants,
        buildCompleteness(project, mappings, localeTags, currentVariants));
  }

  private Map<Long, Map<String, CmsCurrentVariantRow>> loadCurrentVariants(
      CmsContentProject project,
      List<CmsContentFieldMapping> mappings,
      List<String> localeTags,
      boolean lockTranslationState) {
    Set<Long> textUnitIds =
        mappings.stream()
            .map(mapping -> mapping.getTmTextUnit().getId())
            .collect(Collectors.toCollection(LinkedHashSet::new));
    if (textUnitIds.isEmpty() || localeTags.isEmpty()) {
      return Map.of();
    }
    List<String> lookupLocaleTags = localeTagsWithParents(project, localeTags);
    if (lockTranslationState) {
      lockCurrentTranslationState(textUnitIds, lookupLocaleTags);
    }
    return snapshotRepository.findCurrentVariantRows(textUnitIds, lookupLocaleTags).stream()
        .collect(
            Collectors.groupingBy(
                CmsCurrentVariantRow::tmTextUnitId,
                LinkedHashMap::new,
                Collectors.toMap(
                    CmsCurrentVariantRow::localeTag,
                    row -> row,
                    (first, second) -> first,
                    LinkedHashMap::new)));
  }

  private void lockCurrentTranslationState(Set<Long> textUnitIds, List<String> lookupLocaleTags) {
    tmTextUnitCurrentVariantMutationLockService.lockTextUnits(textUnitIds);
    snapshotRepository.lockCurrentVariantRows(textUnitIds, lookupLocaleTags);
  }

  private List<String> localeTagsWithParents(CmsContentProject project, List<String> localeTags) {
    Map<String, RepositoryLocale> repositoryLocalesByTag = repositoryLocalesByTag(project);
    LinkedHashSet<String> lookupLocaleTags = new LinkedHashSet<>(localeTags);
    for (String localeTag : localeTags) {
      RepositoryLocale repositoryLocale = repositoryLocalesByTag.get(localeTag);
      while (repositoryLocale != null && repositoryLocale.getParentLocale() != null) {
        repositoryLocale = repositoryLocale.getParentLocale();
        if (repositoryLocale.getLocale() == null) {
          break;
        }
        lookupLocaleTags.add(repositoryLocale.getLocale().getBcp47Tag());
      }
    }
    return List.copyOf(lookupLocaleTags);
  }

  private Map<String, RepositoryLocale> repositoryLocalesByTag(CmsContentProject project) {
    return project.getRepository().getRepositoryLocales().stream()
        .filter(repositoryLocale -> repositoryLocale.getLocale() != null)
        .collect(
            Collectors.toMap(
                repositoryLocale -> repositoryLocale.getLocale().getBcp47Tag(),
                repositoryLocale -> repositoryLocale,
                (first, second) -> first,
                LinkedHashMap::new));
  }

  private CmsCurrentVariantRow resolveCurrentVariant(
      Map<Long, Map<String, CmsCurrentVariantRow>> currentVariants,
      Map<String, RepositoryLocale> repositoryLocalesByTag,
      Long tmTextUnitId,
      String localeTag) {
    Map<String, CmsCurrentVariantRow> variantsByLocale =
        currentVariants.getOrDefault(tmTextUnitId, Map.of());
    CmsCurrentVariantRow row = variantsByLocale.get(localeTag);
    if (isIncludedCurrentVariant(row)) {
      return row;
    }

    RepositoryLocale repositoryLocale = repositoryLocalesByTag.get(localeTag);
    if (repositoryLocale == null || repositoryLocale.isToBeFullyTranslated()) {
      return null;
    }
    while (repositoryLocale != null && repositoryLocale.getParentLocale() != null) {
      repositoryLocale = repositoryLocale.getParentLocale();
      if (repositoryLocale.getParentLocale() == null || repositoryLocale.getLocale() == null) {
        return null;
      }
      row = variantsByLocale.get(repositoryLocale.getLocale().getBcp47Tag());
      if (isIncludedCurrentVariant(row)) {
        return row;
      }
    }
    return null;
  }

  private boolean isIncludedCurrentVariant(CmsCurrentVariantRow row) {
    return row != null && Boolean.TRUE.equals(row.includedInLocalizedFile());
  }

  private List<String> findMissingRequiredFieldMappings(
      List<CmsContentEntry> entries,
      List<CmsContentEntryVariant> variants,
      List<CmsContentFieldMapping> mappings) {
    if (entries.isEmpty()) {
      return List.of();
    }
    List<Long> contentTypeIds =
        entries.stream().map(entry -> entry.getContentType().getId()).distinct().toList();
    List<CmsContentTypeField> fields =
        fieldRepository.findByContentTypeIdInOrderBySortOrderAscFieldKeyAscIdAsc(contentTypeIds);
    return findMissingRequiredFieldMappings(entries, variants, fields, mappings);
  }

  private void requirePublishableVariantsHaveMappings(
      List<CmsContentEntryVariant> variants,
      List<CmsContentFieldMapping> mappings,
      String errorPrefix) {
    List<String> variantsWithoutMappings = findVariantsWithoutMappings(variants, mappings);
    if (!variantsWithoutMappings.isEmpty()) {
      throw new IllegalArgumentException(errorPrefix + String.join(", ", variantsWithoutMappings));
    }
  }

  private List<String> findVariantsWithoutMappings(
      List<CmsContentEntryVariant> variants, List<CmsContentFieldMapping> mappings) {
    Set<Long> mappedVariantIds =
        mappings.stream().map(mapping -> mapping.getVariant().getId()).collect(Collectors.toSet());
    return variants.stream()
        .filter(variant -> !mappedVariantIds.contains(variant.getId()))
        .map(this::variantPath)
        .toList();
  }

  private List<String> findMissingRequiredFieldMappings(
      CmsContentEntry entry,
      List<CmsContentEntryVariant> variants,
      List<CmsContentFieldMapping> mappings) {
    List<CmsContentTypeField> fields =
        fieldRepository.findByContentTypeIdOrderBySortOrderAscFieldKeyAscIdAsc(
            entry.getContentType().getId());
    return findMissingRequiredFieldMappings(List.of(entry), variants, fields, mappings);
  }

  private List<String> findMissingRequiredFieldMappings(
      List<CmsContentEntry> entries,
      List<CmsContentEntryVariant> variants,
      List<CmsContentTypeField> fields,
      List<CmsContentFieldMapping> mappings) {
    Map<Long, List<CmsContentTypeField>> requiredFieldsByContentTypeId =
        fields.stream()
            .filter(
                field ->
                    Boolean.TRUE.equals(field.getLocalizable())
                        && Boolean.TRUE.equals(field.getRequired()))
            .collect(
                Collectors.groupingBy(
                    field -> field.getContentType().getId(),
                    LinkedHashMap::new,
                    Collectors.toList()));
    if (requiredFieldsByContentTypeId.isEmpty()) {
      return List.of();
    }

    Set<String> mappedVariantFields =
        mappings.stream()
            .map(mapping -> mapping.getVariant().getId() + ":" + mapping.getField().getId())
            .collect(Collectors.toSet());
    Map<Long, List<CmsContentEntryVariant>> variantsByEntryId =
        variants.stream()
            .collect(
                Collectors.groupingBy(
                    variant -> variant.getEntry().getId(),
                    LinkedHashMap::new,
                    Collectors.toList()));

    List<String> missing = new ArrayList<>();
    for (CmsContentEntry entry : entries) {
      List<CmsContentTypeField> requiredFields =
          requiredFieldsByContentTypeId.getOrDefault(entry.getContentType().getId(), List.of());
      if (requiredFields.isEmpty()) {
        continue;
      }
      for (CmsContentEntryVariant variant :
          variantsByEntryId.getOrDefault(entry.getId(), List.of())) {
        for (CmsContentTypeField field : requiredFields) {
          if (!mappedVariantFields.contains(variant.getId() + ":" + field.getId())) {
            missing.add(
                entry.getEntryKey() + "." + variant.getVariantKey() + "." + field.getFieldKey());
          }
        }
      }
    }
    return missing;
  }

  private TMTextUnit getOrCreateTextUnit(
      CmsContentEntryVariant variant,
      CmsContentTypeField field,
      String sourceContent,
      String sourceComment) {
    CmsContentProject project = variant.getEntry().getProject();
    String stringId = buildStringId(project, variant, field);
    String md5 = textUnitUtils.computeTextUnitMD5(stringId, sourceContent, sourceComment);
    VirtualAssetTextUnit textUnit = new VirtualAssetTextUnit();
    textUnit.setName(stringId);
    textUnit.setContent(sourceContent);
    textUnit.setComment(sourceComment);
    textUnit.setDoNotTranslate(false);
    try {
      virtualTextUnitBatchUpdaterService.updateCmsTextUnits(
          project.getAsset(), List.of(textUnit), false);
    } catch (VirtualAssetBadRequestException ex) {
      throw new IllegalArgumentException("Failed to register CMS text unit", ex);
    }
    TMTextUnit tmTextUnit = tmTextUnitRepository.findFirstByAssetAndMd5(project.getAsset(), md5);
    if (tmTextUnit == null) {
      throw new IllegalStateException("CMS text unit was not created: " + stringId);
    }
    return tmTextUnit;
  }

  private TMTextUnit resolveMappedTextUnit(
      CmsContentEntryVariant variant,
      Long tmTextUnitId,
      String stringId,
      String sourceContent,
      String sourceComment) {
    TMTextUnit tmTextUnit =
        tmTextUnitId == null
            ? findActiveMappedTextUnitByStringId(variant, stringId)
            : tmTextUnitRepository
                .findById(tmTextUnitId)
                .orElseThrow(
                    () -> new IllegalArgumentException("Text unit not found: " + tmTextUnitId));
    return validateMappedTextUnit(variant, tmTextUnit, sourceContent, sourceComment);
  }

  private TMTextUnit findActiveMappedTextUnitByStringId(
      CmsContentEntryVariant variant, String stringId) {
    Asset asset = variant.getEntry().getProject().getAsset();
    List<TMTextUnit> matchingTextUnits =
        assetTextUnitToTMTextUnitRepository.findTmTextUnitsByAssetExtractionIdAndAssetTextUnitName(
            asset.getLastSuccessfulAssetExtraction().getId(), stringId);
    if (matchingTextUnits.isEmpty()) {
      throw new IllegalArgumentException(
          "Mapped string ID is not active on the content project virtual asset: " + stringId);
    }
    if (matchingTextUnits.size() > 1) {
      throw new IllegalArgumentException(
          "Mapped string ID resolves to multiple active text units; use TM text unit ID: "
              + stringId);
    }
    return matchingTextUnits.getFirst();
  }

  private TMTextUnit validateMappedTextUnit(
      CmsContentEntryVariant variant,
      TMTextUnit tmTextUnit,
      String sourceContent,
      String sourceComment) {
    if (!Objects.equals(
        tmTextUnit.getAsset().getId(), variant.getEntry().getProject().getAsset().getId())) {
      throw new IllegalArgumentException(
          "Mapped text unit must belong to the content project asset");
    }
    Asset asset = variant.getEntry().getProject().getAsset();
    if (asset.getLastSuccessfulAssetExtraction() == null
        || assetTextUnitToTMTextUnitRepository
            .findIdByAssetExtractionIdAndTmTextUnitId(
                asset.getLastSuccessfulAssetExtraction().getId(), tmTextUnit.getId())
            .isEmpty()) {
      throw new IllegalArgumentException(
          "Mapped text unit must be active on the content project virtual asset");
    }
    if (assetTextUnitToTMTextUnitRepository
        .findDoNotTranslateIdByAssetExtractionIdAndTmTextUnitId(
            asset.getLastSuccessfulAssetExtraction().getId(), tmTextUnit.getId())
        .isPresent()) {
      throw new IllegalArgumentException("Mapped text unit must be translatable");
    }
    if (sourceContent != null && !sourceContent.equals(tmTextUnit.getContent())) {
      throw new IllegalArgumentException("Source content does not match the mapped text unit");
    }
    if (sourceComment != null && !sourceComment.equals(tmTextUnit.getComment())) {
      throw new IllegalArgumentException("Translator context does not match the mapped text unit");
    }
    return tmTextUnit;
  }

  private String buildStringId(
      CmsContentProject project, CmsContentEntryVariant variant, CmsContentTypeField field) {
    CmsContentEntry entry = variant.getEntry();
    return "cms.%s.%s.%s.%s"
        .formatted(
            project.getProjectKey(),
            entry.getEntryKey(),
            variant.getVariantKey(),
            field.getFieldKey());
  }

  private Asset resolveProjectAsset(Repository repository, String assetPath) {
    Asset existing = assetRepository.findByPathAndRepositoryId(assetPath, repository.getId());
    if (existing != null) {
      if (!Boolean.TRUE.equals(existing.getVirtual())) {
        throw new IllegalArgumentException("CMS asset path already exists as a non-virtual asset");
      }
      ensureProjectAssetAvailable(existing, null);
      throw new IllegalArgumentException(
          "CMS asset path already exists; choose an unused path for a dedicated CMS virtual asset");
    }
    VirtualAsset virtualAsset = new VirtualAsset();
    virtualAsset.setRepositoryId(repository.getId());
    virtualAsset.setPath(assetPath);
    try {
      VirtualAsset saved = virtualAssetService.createOrUpdateVirtualAsset(virtualAsset);
      Asset asset = assetRepository.findById(saved.getId()).orElseThrow();
      asset.setCmsManaged(true);
      return assetRepository.saveAndFlush(asset);
    } catch (VirtualAssetBadRequestException ex) {
      throw new IllegalArgumentException("Failed to create CMS virtual asset", ex);
    }
  }

  private List<CmsContentEntry> findPublishableEntries(Long projectId) {
    return entryRepository.findByProjectIdOrderByNameAscIdAsc(projectId).stream()
        .filter(this::isPublishableEntry)
        .sorted(
            Comparator.comparing(CmsContentEntry::getEntryKey)
                .thenComparing(CmsContentEntry::getId))
        .toList();
  }

  private boolean isPublishableMapping(CmsContentFieldMapping mapping) {
    return isPublishableEntry(mapping.getVariant().getEntry())
        && isPublishableVariant(mapping.getVariant());
  }

  private boolean isPublishableEntry(CmsContentEntry entry) {
    return CmsContentEntry.Status.READY.equals(entry.getStatus());
  }

  private boolean isPublishableVariant(CmsContentEntryVariant variant) {
    return !CmsContentEntryVariant.Status.ARCHIVED.equals(variant.getStatus());
  }

  private List<String> findEntriesWithInvalidPublishableControlCount(
      List<CmsContentEntry> entries, List<CmsContentEntryVariant> variants) {
    if (entries.isEmpty()) {
      return List.of();
    }
    Map<Long, List<CmsContentEntryVariant>> variantsByEntryId =
        variants.stream()
            .collect(
                Collectors.groupingBy(
                    variant -> variant.getEntry().getId(),
                    LinkedHashMap::new,
                    Collectors.toList()));
    return entries.stream()
        .filter(
            entry ->
                variantsByEntryId.getOrDefault(entry.getId(), List.of()).stream()
                        .filter(
                            variant ->
                                CmsContentEntryVariant.Status.CONTROL.equals(variant.getStatus()))
                        .count()
                    != 1)
        .map(CmsContentEntry::getEntryKey)
        .toList();
  }

  private List<CmsContentEntryVariant> findPublishableVariants(List<CmsContentEntry> entries) {
    if (entries.isEmpty()) {
      return List.of();
    }
    return variantRepository
        .findByEntryIdInOrderBySortOrderAscVariantKeyAscIdAsc(
            entries.stream().map(CmsContentEntry::getId).toList())
        .stream()
        .filter(this::isPublishableVariant)
        .toList();
  }

  private void validatePublishableVariantCandidateGroups(
      List<CmsContentEntryVariant> variants, String errorPrefix) {
    List<String> candidatesWithoutGroupKeys =
        variants.stream()
            .filter(
                variant ->
                    CmsContentEntryVariant.Status.CANDIDATE.equals(variant.getStatus())
                        && !hasCandidateGroupKey(variant.getCandidateGroupKey()))
            .map(this::variantPath)
            .toList();
    if (!candidatesWithoutGroupKeys.isEmpty()) {
      throw new IllegalArgumentException(
          errorPrefix + String.join(", ", candidatesWithoutGroupKeys));
    }
  }

  private void validateArtifactMetadata(
      List<CmsContentEntry> entries, List<CmsContentEntryVariant> variants) {
    for (CmsContentEntry entry : entries) {
      validateMetadata(
          entry.getContentType().getMetadataSchemaJson(),
          entry.getMetadataJson(),
          "Entry metadata for " + entry.getEntryKey());
    }
    for (CmsContentEntryVariant variant : variants) {
      validateMetadata(
          null, variant.getMetadataJson(), "Variant metadata for " + variantPath(variant));
    }
  }

  private void rejectReadyStatusForNewEntry(CmsContentEntry.Status status) {
    if (CmsContentEntry.Status.READY.equals(status)) {
      throw new IllegalArgumentException(
          "New content entries must start as DRAFT or ARCHIVED; map fields before marking READY");
    }
  }

  private void validateReadyEntriesForContentType(CmsContentType contentType) {
    entryRepository.findByContentTypeIdOrderByNameAscIdAsc(contentType.getId()).stream()
        .filter(this::isPublishableEntry)
        .forEach(this::validateReadyEntryStructure);
  }

  private void validateReadyEntryStructure(CmsContentEntry entry) {
    if (!isPublishableEntry(entry)) {
      return;
    }
    validateEntryPublishableStructure(
        entry,
        "Ready entry does not have exactly one active control variant: ",
        "Ready entry has candidate variants without candidate group keys: ",
        "Ready entry has publishable variants without mapped localizable fields: ",
        "Ready entry has missing required field mappings: ");
  }

  private EntryPublishableState validateEntryPublishableStructure(
      CmsContentEntry entry,
      String invalidControlErrorPrefix,
      String candidateGroupErrorPrefix,
      String unmappedVariantErrorPrefix,
      String missingRequiredMappingErrorPrefix) {
    validateProjectRepositoryAvailable(entry.getProject());
    validateProjectVirtualAssetAvailable(entry.getProject());
    List<CmsContentEntryVariant> publishableVariants = findPublishableVariants(List.of(entry));
    requireExactlyOneActiveControlVariant(
        List.of(entry), publishableVariants, invalidControlErrorPrefix);
    validatePublishableVariantCandidateGroups(publishableVariants, candidateGroupErrorPrefix);
    validateArtifactMetadata(List.of(entry), publishableVariants);
    List<CmsContentFieldMapping> mappings =
        mappingRepository.findMappingsByEntryId(entry.getId()).stream()
            .filter(mapping -> isPublishableVariant(mapping.getVariant()))
            .toList();
    requirePublishableVariantsHaveMappings(
        publishableVariants, mappings, unmappedVariantErrorPrefix);
    List<String> missingRequiredMappings =
        findMissingRequiredFieldMappings(entry, publishableVariants, mappings);
    if (!missingRequiredMappings.isEmpty()) {
      throw new IllegalArgumentException(
          missingRequiredMappingErrorPrefix + String.join(", ", missingRequiredMappings));
    }
    validatePublishableMappingsActiveAndTranslatable(entry.getProject(), mappings);
    validatePublishableFieldSources(mappings);
    return new EntryPublishableState(mappings);
  }

  private void validatePublishableFieldSources(List<CmsContentFieldMapping> mappings) {
    for (CmsContentFieldMapping mapping : mappings) {
      validateFieldSourceContent(
          mapping.getField(), mapping.getTmTextUnit().getContent(), mappingPath(mapping));
    }
  }

  private void requireExactlyOneActiveControlVariant(
      List<CmsContentEntry> entries, List<CmsContentEntryVariant> variants, String errorPrefix) {
    List<String> entriesWithInvalidControlCount =
        findEntriesWithInvalidPublishableControlCount(entries, variants);
    if (!entriesWithInvalidControlCount.isEmpty()) {
      throw new IllegalArgumentException(
          errorPrefix + String.join(", ", entriesWithInvalidControlCount));
    }
  }

  private void validatePublishableMappingsActiveAndTranslatable(
      CmsContentProject project, List<CmsContentFieldMapping> mappings) {
    validateProjectVirtualAssetAvailable(project);
    Asset asset = project.getAsset();
    Set<Long> mappedTextUnitIds =
        mappings.stream()
            .map(mapping -> mapping.getTmTextUnit().getId())
            .collect(Collectors.toCollection(LinkedHashSet::new));
    Long assetExtractionId = asset.getLastSuccessfulAssetExtraction().getId();
    Set<Long> activeTextUnitIds =
        assetTextUnitToTMTextUnitRepository.findTmTextUnitIdsByAssetExtractionIdAndTmTextUnitIdIn(
            assetExtractionId, mappedTextUnitIds);
    List<String> inactiveMappings =
        mappings.stream()
            .filter(mapping -> !activeTextUnitIds.contains(mapping.getTmTextUnit().getId()))
            .map(this::mappingPath)
            .distinct()
            .toList();
    if (!inactiveMappings.isEmpty()) {
      throw new IllegalArgumentException(
          "Cannot publish with inactive mapped text units: " + String.join(", ", inactiveMappings));
    }

    Set<Long> doNotTranslateTextUnitIds =
        assetTextUnitToTMTextUnitRepository
            .findDoNotTranslateTmTextUnitIdsByAssetExtractionIdAndTmTextUnitIdIn(
                assetExtractionId, mappedTextUnitIds);
    List<String> doNotTranslateMappings =
        mappings.stream()
            .filter(mapping -> doNotTranslateTextUnitIds.contains(mapping.getTmTextUnit().getId()))
            .map(this::mappingPath)
            .distinct()
            .toList();
    if (!doNotTranslateMappings.isEmpty()) {
      throw new IllegalArgumentException(
          "Cannot publish with do-not-translate mapped text units: "
              + String.join(", ", doNotTranslateMappings));
    }

    List<String> mappingsWithoutMojitoStringId =
        mappings.stream()
            .filter(mapping -> !hasUsableMojitoStringId(mapping.getTmTextUnit().getName()))
            .map(this::mappingPath)
            .distinct()
            .toList();
    if (!mappingsWithoutMojitoStringId.isEmpty()) {
      throw new IllegalArgumentException(
          "Publishable mapped text units must have usable Mojito string IDs: "
              + String.join(", ", mappingsWithoutMojitoStringId));
    }

    List<String> mappingsWithoutSourceContent =
        mappings.stream()
            .filter(mapping -> !hasSourceContent(mapping.getTmTextUnit().getContent()))
            .map(this::mappingPath)
            .distinct()
            .toList();
    if (!mappingsWithoutSourceContent.isEmpty()) {
      throw new IllegalArgumentException(
          "Publishable mapped text units must have source content: "
              + String.join(", ", mappingsWithoutSourceContent));
    }

    List<String> mappingsWithoutTranslatorContext =
        mappings.stream()
            .filter(mapping -> !hasTranslatorContext(mapping.getTmTextUnit().getComment()))
            .map(this::mappingPath)
            .distinct()
            .toList();
    if (!mappingsWithoutTranslatorContext.isEmpty()) {
      throw new IllegalArgumentException(
          "Publishable mapped text units must have translator context: "
              + String.join(", ", mappingsWithoutTranslatorContext));
    }
  }

  private void validateProjectVirtualAssetAvailable(CmsContentProject project) {
    Asset asset = project.getAsset();
    if (!Boolean.TRUE.equals(asset.getVirtual())) {
      throw new IllegalArgumentException("Content project asset must remain virtual");
    }
    if (!Boolean.TRUE.equals(asset.getCmsManaged())) {
      throw new IllegalArgumentException("Content project asset must remain CMS-managed");
    }
    if (Boolean.TRUE.equals(asset.getDeleted())) {
      throw new IllegalArgumentException("Content project virtual asset is deleted");
    }
    if (asset.getLastSuccessfulAssetExtraction() == null) {
      throw new IllegalArgumentException(
          "Content project virtual asset has no successful extraction");
    }
  }

  private void requireProjectEnabled(CmsContentProject project) {
    if (!Boolean.TRUE.equals(project.getEnabled())) {
      throw new IllegalArgumentException("Content project is disabled: " + project.getProjectKey());
    }
  }

  private void requireProjectDeliverable(CmsContentProject project) {
    requireProjectEnabled(project);
    validateProjectRepositoryAvailable(project);
    validateProjectVirtualAssetAvailable(project);
  }

  private void validateProjectRepositoryAvailable(CmsContentProject project) {
    Repository repository = project.getRepository();
    if (Boolean.TRUE.equals(repository.getDeleted())) {
      throw new IllegalArgumentException(
          "Content project repository is deleted: " + project.getProjectKey());
    }
    if (Boolean.TRUE.equals(repository.getHidden())) {
      throw new IllegalArgumentException(
          "Content project repository is hidden: " + project.getProjectKey());
    }
  }

  private void validateFieldMappingsForFieldType(
      CmsContentTypeField field, CmsContentTypeField.FieldType fieldType) {
    if (!CmsContentTypeField.FieldType.ICU_MESSAGE.equals(fieldType)) {
      return;
    }
    mappingRepository
        .findByFieldId(field.getId())
        .forEach(
            mapping ->
                validateFieldSourceContent(
                    fieldType, mapping.getTmTextUnit().getContent(), mappingPath(mapping)));
  }

  private void ensureTextUnitAvailableForMapping(
      TMTextUnit tmTextUnit, CmsContentFieldMapping currentMapping) {
    mappingRepository
        .findByTmTextUnitId(tmTextUnit.getId())
        .filter(
            existing ->
                !Objects.equals(
                    existing.getId(), currentMapping == null ? null : currentMapping.getId()))
        .ifPresent(
            existing -> {
              throw new IllegalArgumentException(
                  "Mapped text unit is already bound to CMS field: " + mappingPath(existing));
            });
  }

  private void validateFieldSourceContent(
      CmsContentTypeField field, String sourceContent, String mappingPath) {
    validateFieldSourceContent(field.getFieldType(), sourceContent, mappingPath);
  }

  private void validateFieldSourceContent(
      CmsContentTypeField.FieldType fieldType, String sourceContent, String mappingPath) {
    if (!CmsContentTypeField.FieldType.ICU_MESSAGE.equals(fieldType)) {
      return;
    }
    validateIcuMessage(mappingPath + " source", sourceContent, sourceContent);
  }

  private void requireTranslatorContext(String sourceComment, String mappingPath) {
    if (!hasTranslatorContext(sourceComment)) {
      throw new IllegalArgumentException(
          "Translator context is required for mapping: " + mappingPath);
    }
  }

  private void requireMojitoStringId(String stringId, String mappingPath) {
    if (!hasMojitoStringId(stringId)) {
      throw new IllegalArgumentException(
          "Mojito string ID is required for mapping: " + mappingPath);
    }
    if (stringId.length() > 4000) {
      throw new IllegalArgumentException(
          "Mojito string ID must be at most 4000 characters for mapping: " + mappingPath);
    }
  }

  private void requireSourceContent(String sourceContent, String mappingPath) {
    if (!hasSourceContent(sourceContent)) {
      throw new IllegalArgumentException("Source content is required for mapping: " + mappingPath);
    }
  }

  private boolean hasUsableMojitoStringId(String stringId) {
    return hasMojitoStringId(stringId) && stringId.length() <= 4000;
  }

  private boolean hasMojitoStringId(String stringId) {
    return stringId != null && !stringId.isBlank();
  }

  private boolean hasSourceContent(String sourceContent) {
    return sourceContent != null && !sourceContent.isBlank();
  }

  private boolean hasTranslatorContext(String sourceComment) {
    return sourceComment != null && !sourceComment.isBlank();
  }

  private void validateCandidateGroupKey(
      CmsContentEntryVariant.Status status, String candidateGroupKey) {
    if (CmsContentEntryVariant.Status.CANDIDATE.equals(status)
        && !hasCandidateGroupKey(candidateGroupKey)) {
      throw new IllegalArgumentException("Candidate variants require a candidate group key");
    }
  }

  private boolean hasCandidateGroupKey(String candidateGroupKey) {
    return candidateGroupKey != null && !candidateGroupKey.isBlank();
  }

  private void validatePublishableFieldIntegrity(
      CmsContentProject project,
      List<CmsContentFieldMapping> mappings,
      List<String> localeTags,
      Map<Long, Map<String, CmsCurrentVariantRow>> currentVariants) {
    String sourceLocale = project.getRepository().getSourceLocale().getBcp47Tag();
    Map<String, RepositoryLocale> repositoryLocalesByTag = repositoryLocalesByTag(project);
    for (CmsContentFieldMapping mapping : mappings) {
      if (!CmsContentTypeField.FieldType.ICU_MESSAGE.equals(mapping.getField().getFieldType())) {
        continue;
      }
      String sourceContent = mapping.getTmTextUnit().getContent();
      String mappingPath = mappingPath(mapping);
      validateIcuMessage(mappingPath + " source", sourceContent, sourceContent);
      for (String localeTag : localeTags) {
        if (sourceLocale.equals(localeTag)) {
          continue;
        }
        CmsCurrentVariantRow row =
            resolveCurrentVariant(
                currentVariants,
                repositoryLocalesByTag,
                mapping.getTmTextUnit().getId(),
                localeTag);
        if (row == null || row.target() == null || row.target().isBlank()) {
          continue;
        }
        validateIcuMessage(mappingPath + " " + localeTag, sourceContent, row.target());
      }
    }
  }

  private void validateIcuMessage(String label, String sourceContent, String targetContent) {
    try {
      messageFormatIntegrityChecker.check(sourceContent, targetContent);
    } catch (MessageFormatIntegrityCheckerException ex) {
      throw new IllegalArgumentException(
          "ICU message integrity check failed for " + label + ": " + ex.getMessage(), ex);
    }
  }

  private String mappingPath(CmsContentFieldMapping mapping) {
    return mappingPath(mapping.getVariant(), mapping.getField());
  }

  private String mappingPath(CmsContentEntryVariant variant, CmsContentTypeField field) {
    return variantPath(variant) + "." + field.getFieldKey();
  }

  private String variantPath(CmsContentEntryVariant variant) {
    return variant.getEntry().getEntryKey() + "." + variant.getVariantKey();
  }

  private void ensureControlVariantInvariant(
      CmsContentEntry entry,
      CmsContentEntryVariant.Status status,
      Long currentVariantId,
      CmsContentEntryVariant.Status currentStatus) {
    List<CmsContentEntryVariant> otherControlVariants =
        findOtherControlVariants(entry, currentVariantId);
    if (CmsContentEntryVariant.Status.CONTROL.equals(status)) {
      otherControlVariants.stream()
          .findFirst()
          .ifPresent(
              variant -> {
                throw new IllegalArgumentException(
                    "Content entry already has a control variant: " + variant.getVariantKey());
              });
      return;
    }
    if (currentVariantId != null
        && otherControlVariants.isEmpty()
        && CmsContentEntryVariant.Status.CONTROL.equals(currentStatus)) {
      throw new IllegalArgumentException("Content entry must keep a control variant");
    }
  }

  private void transitionVariantStatus(
      CmsContentEntry entry, CmsContentEntryVariant variant, CmsContentEntryVariant.Status status) {
    if (CmsContentEntryVariant.Status.CONTROL.equals(status)
        && !CmsContentEntryVariant.Status.CONTROL.equals(variant.getStatus())) {
      promoteVariantToControl(entry, variant);
      return;
    }
    ensureControlVariantInvariant(entry, status, variant.getId(), variant.getStatus());
    setVariantStatus(variant, status);
  }

  private void promoteVariantToControl(CmsContentEntry entry, CmsContentEntryVariant variant) {
    List<CmsContentEntryVariant> currentControls = findOtherControlVariants(entry, variant.getId());
    if (currentControls.size() > 1) {
      throw new IllegalArgumentException(
          "Content entry has multiple control variants: "
              + currentControls.stream()
                  .map(CmsContentEntryVariant::getVariantKey)
                  .collect(Collectors.joining(", ")));
    }
    if (!currentControls.isEmpty()) {
      CmsContentEntryVariant currentControl = currentControls.get(0);
      setVariantStatus(currentControl, CmsContentEntryVariant.Status.ARCHIVED);
      variantRepository.saveAndFlush(currentControl);
    }
    setVariantStatus(variant, CmsContentEntryVariant.Status.CONTROL);
  }

  private List<CmsContentEntryVariant> findOtherControlVariants(
      CmsContentEntry entry, Long currentVariantId) {
    return variantRepository
        .findByEntryIdOrderBySortOrderAscVariantKeyAscIdAsc(entry.getId())
        .stream()
        .filter(variant -> !Objects.equals(variant.getId(), currentVariantId))
        .filter(variant -> CmsContentEntryVariant.Status.CONTROL.equals(variant.getStatus()))
        .toList();
  }

  private void setVariantStatus(
      CmsContentEntryVariant variant, CmsContentEntryVariant.Status status) {
    variant.setStatus(status);
    variant.setControlEntryId(
        CmsContentEntryVariant.Status.CONTROL.equals(status) ? variant.getEntry().getId() : null);
  }

  private CmsContentProject findProject(Long projectId) {
    return projectRepository
        .findByIdWithRepositoryAndAsset(projectId)
        .orElseThrow(() -> new IllegalArgumentException("Content project not found: " + projectId));
  }

  private CmsContentProject findProjectForUpdate(Long projectId) {
    return projectRepository
        .findByIdWithRepositoryAndAssetForUpdate(projectId)
        .orElseThrow(() -> new IllegalArgumentException("Content project not found: " + projectId));
  }

  private CmsContentProject findProjectByKey(String projectKey) {
    return projectRepository
        .findByProjectKeyWithRepositoryAndAsset(projectKey)
        .orElseThrow(
            () -> new IllegalArgumentException("Content project not found: " + projectKey));
  }

  private CmsContentProject findProjectByKeyForUpdate(String projectKey) {
    return projectRepository
        .findByProjectKeyWithRepositoryAndAssetForUpdate(projectKey)
        .orElseThrow(
            () -> new IllegalArgumentException("Content project not found: " + projectKey));
  }

  private void lockProjectForContentTypeUpdate(Long contentTypeId) {
    projectRepository
        .findByContentTypeIdWithRepositoryAndAssetForUpdate(contentTypeId)
        .orElseThrow(
            () -> new IllegalArgumentException("Content type not found: " + contentTypeId));
  }

  private void lockProjectForFieldUpdate(Long fieldId) {
    projectRepository
        .findByFieldIdWithRepositoryAndAssetForUpdate(fieldId)
        .orElseThrow(
            () -> new IllegalArgumentException("Content type field not found: " + fieldId));
  }

  private void lockProjectForEntryUpdate(Long entryId) {
    projectRepository
        .findByEntryIdWithRepositoryAndAssetForUpdate(entryId)
        .orElseThrow(() -> new IllegalArgumentException("Content entry not found: " + entryId));
  }

  private void lockProjectForVariantUpdate(Long variantId) {
    projectRepository
        .findByVariantIdWithRepositoryAndAssetForUpdate(variantId)
        .orElseThrow(
            () -> new IllegalArgumentException("Content entry variant not found: " + variantId));
  }

  private void lockProjectForFieldMappingUpdate(Long mappingId) {
    projectRepository
        .findByFieldMappingIdWithRepositoryAndAssetForUpdate(mappingId)
        .orElseThrow(() -> new IllegalArgumentException("Field mapping not found: " + mappingId));
  }

  private CmsContentType findContentType(Long contentTypeId) {
    return contentTypeRepository
        .findByIdWithProject(contentTypeId)
        .orElseThrow(
            () -> new IllegalArgumentException("Content type not found: " + contentTypeId));
  }

  private CmsContentTypeField findField(Long fieldId) {
    return fieldRepository
        .findByIdWithContentType(fieldId)
        .orElseThrow(
            () -> new IllegalArgumentException("Content type field not found: " + fieldId));
  }

  private CmsContentEntry findEntry(Long entryId) {
    return entryRepository
        .findByIdWithProjectAndType(entryId)
        .orElseThrow(() -> new IllegalArgumentException("Content entry not found: " + entryId));
  }

  private CmsContentEntry findEntryForUpdate(Long entryId) {
    return entryRepository
        .findByIdWithProjectAndTypeForUpdate(entryId)
        .orElseThrow(() -> new IllegalArgumentException("Content entry not found: " + entryId));
  }

  private CmsContentEntry findEntryForVariantUpdate(Long variantId) {
    return entryRepository
        .findByVariantIdWithProjectAndTypeForUpdate(variantId)
        .orElseThrow(
            () -> new IllegalArgumentException("Content entry variant not found: " + variantId));
  }

  private CmsContentEntryVariant findVariant(Long variantId) {
    return variantRepository
        .findByIdWithEntry(variantId)
        .orElseThrow(
            () -> new IllegalArgumentException("Content entry variant not found: " + variantId));
  }

  private CmsContentFieldMapping findFieldMapping(Long mappingId) {
    return mappingRepository
        .findByIdWithVariantFieldAndTextUnit(mappingId)
        .orElseThrow(() -> new IllegalArgumentException("Field mapping not found: " + mappingId));
  }

  private Repository resolveRepository(Long repositoryId) {
    if (repositoryId == null) {
      throw new IllegalArgumentException("Repository id is required");
    }
    List<Repository> repositories =
        repositoryRepository.findByIdInAndDeletedFalseAndHiddenFalseOrderByNameAsc(
            List.of(repositoryId));
    if (repositories.isEmpty()) {
      throw new IllegalArgumentException("Repository not found: " + repositoryId);
    }
    return repositories.get(0);
  }

  private List<String> resolveLocaleTags(CmsContentProject project, List<String> requestedTags) {
    String sourceTag = project.getRepository().getSourceLocale().getBcp47Tag();
    Set<String> availableTags = new LinkedHashSet<>();
    availableTags.add(sourceTag);
    for (RepositoryLocale repositoryLocale : project.getRepository().getRepositoryLocales()) {
      availableTags.add(repositoryLocale.getLocale().getBcp47Tag());
    }

    List<String> normalizedRequestedTags = normalizeRequestedLocaleTags(requestedTags);
    LinkedHashSet<String> selectedLocaleTags = new LinkedHashSet<>();
    if (normalizedRequestedTags.isEmpty()) {
      project.getRepository().getRepositoryLocales().stream()
          .sorted(
              Comparator.comparing(repositoryLocale -> repositoryLocale.getLocale().getBcp47Tag()))
          .map(repositoryLocale -> repositoryLocale.getLocale().getBcp47Tag())
          .forEach(selectedLocaleTags::add);
    } else {
      selectedLocaleTags.addAll(normalizedRequestedTags);
    }
    List<String> unknownTags =
        selectedLocaleTags.stream().filter(tag -> !availableTags.contains(tag)).toList();
    if (!unknownTags.isEmpty()) {
      throw new IllegalArgumentException(
          "Locale(s) are not configured on the project repository: " + unknownTags);
    }
    LinkedHashSet<String> localeTags = new LinkedHashSet<>();
    localeTags.add(sourceTag);
    selectedLocaleTags.stream()
        .filter(tag -> !sourceTag.equals(tag))
        .sorted()
        .forEach(localeTags::add);
    return List.copyOf(localeTags);
  }

  private List<String> normalizeRequestedLocaleTags(List<String> requestedTags) {
    if (requestedTags == null || requestedTags.isEmpty()) {
      return List.of();
    }
    LinkedHashSet<String> normalizedTags = new LinkedHashSet<>();
    for (String requestedTag : requestedTags) {
      if (requestedTag == null || requestedTag.trim().isEmpty()) {
        throw new IllegalArgumentException("Locale tags must not contain blank values");
      }
      String normalizedTag = requestedTag.trim();
      if (!normalizedTags.add(normalizedTag)) {
        throw new IllegalArgumentException(
            "Locale tags must not contain duplicate values: " + normalizedTag);
      }
    }
    return normalizedTags.stream().sorted().toList();
  }

  private String buildPublishRequestAuthoringSha256(CmsContentProject project) {
    List<CmsContentType> contentTypes =
        contentTypeRepository.findByProjectIdOrderByNameAscIdAsc(project.getId());
    List<Long> contentTypeIds = contentTypes.stream().map(CmsContentType::getId).toList();
    Map<Long, List<CmsContentTypeField>> fieldsByContentTypeId =
        contentTypeIds.isEmpty()
            ? Map.of()
            : fieldRepository
                .findByContentTypeIdInOrderBySortOrderAscFieldKeyAscIdAsc(contentTypeIds)
                .stream()
                .collect(
                    Collectors.groupingBy(
                        field -> field.getContentType().getId(),
                        LinkedHashMap::new,
                        Collectors.toList()));

    List<CmsContentEntry> entries =
        entryRepository.findByProjectIdOrderByNameAscIdAsc(project.getId());
    List<Long> entryIds = entries.stream().map(CmsContentEntry::getId).toList();
    Map<Long, List<CmsContentEntryVariant>> variantsByEntryId =
        entryIds.isEmpty()
            ? Map.of()
            : variantRepository
                .findByEntryIdInOrderBySortOrderAscVariantKeyAscIdAsc(entryIds)
                .stream()
                .collect(
                    Collectors.groupingBy(
                        variant -> variant.getEntry().getId(),
                        LinkedHashMap::new,
                        Collectors.toList()));
    Map<Long, List<CmsContentFieldMapping>> mappingsByVariantId =
        mappingRepository.findMappingsByProjectId(project.getId()).stream()
            .collect(
                Collectors.groupingBy(
                    mapping -> mapping.getVariant().getId(),
                    LinkedHashMap::new,
                    Collectors.toList()));

    Map<String, Object> authoringState = new LinkedHashMap<>();
    authoringState.put("project", entityVersionState(project.getId(), project.getEntityVersion()));
    authoringState.put(
        "contentTypes",
        contentTypes.stream()
            .map(
                contentType ->
                    List.of(
                        contentType.getId(),
                        contentType.getEntityVersion(),
                        fieldsByContentTypeId.getOrDefault(contentType.getId(), List.of()).stream()
                            .map(
                                field ->
                                    entityVersionState(field.getId(), field.getEntityVersion()))
                            .toList()))
            .toList());
    authoringState.put(
        "entries",
        entries.stream()
            .map(
                entry ->
                    List.of(
                        entry.getId(),
                        entry.getEntityVersion(),
                        variantsByEntryId.getOrDefault(entry.getId(), List.of()).stream()
                            .map(
                                variant ->
                                    List.of(
                                        variant.getId(),
                                        variant.getEntityVersion(),
                                        mappingsByVariantId
                                            .getOrDefault(variant.getId(), List.of())
                                            .stream()
                                            .map(this::fieldMappingAuthoringState)
                                            .toList()))
                            .toList()))
            .toList());
    return DigestUtils.sha256Hex(objectMapper.writeValueAsStringUnchecked(authoringState));
  }

  private List<Object> fieldMappingAuthoringState(CmsContentFieldMapping mapping) {
    List<Object> state = new ArrayList<>();
    state.add(mapping.getId());
    state.add(mapping.getEntityVersion());
    state.add(mapping.getTmTextUnit().getId());
    state.add(mapping.getTmTextUnit().getName());
    state.add(mapping.getTmTextUnit().getContent());
    state.add(mapping.getTmTextUnit().getComment());
    return state;
  }

  private List<Long> entityVersionState(Long id, Long entityVersion) {
    if (id == null || entityVersion == null) {
      throw new IllegalStateException("CMS authoring state has an unsaved or unversioned row");
    }
    return List.of(id, entityVersion);
  }

  private void requireMatchingPublishRequest(
      CmsPublishSnapshot snapshot,
      List<String> requestedLocaleTags,
      String expectedAuthoringSha256,
      String expectedPackageSha256) {
    if (!Objects.equals(parseSnapshotPublishRequestLocaleTags(snapshot), requestedLocaleTags)) {
      throw new CmsContentConflictException(
          "Publish request key was already used for another locale scope; use a new key");
    }
    if (!Objects.equals(snapshot.getPublishRequestAuthoringSha256(), expectedAuthoringSha256)) {
      throw new CmsContentConflictException(
          "Publish request key was already used for another authoring revision; use a new key");
    }
    if (!Objects.equals(snapshot.getPublishRequestPackageSha256(), expectedPackageSha256)) {
      throw new CmsContentConflictException(
          "Publish request key was already used for another validated package; use a new key");
    }
  }

  private void ensureProjectKeyAvailable(String key, Long currentId) {
    projectRepository
        .findByProjectKeyIgnoreCase(key)
        .filter(existing -> !Objects.equals(existing.getId(), currentId))
        .ifPresent(
            existing -> {
              throw new IllegalArgumentException("Content project already exists: " + key);
            });
  }

  private void ensureProjectAssetAvailable(Asset asset, Long currentId) {
    projectRepository
        .findByAssetId(asset.getId())
        .filter(existing -> !Objects.equals(existing.getId(), currentId))
        .ifPresent(
            existing -> {
              throw new IllegalArgumentException(
                  "CMS asset is already assigned to content project: " + existing.getProjectKey());
            });
  }

  private void ensureTypeKeyAvailable(Long projectId, String key, Long currentId) {
    contentTypeRepository
        .findByProjectIdAndTypeKeyIgnoreCase(projectId, key)
        .filter(existing -> !Objects.equals(existing.getId(), currentId))
        .ifPresent(
            existing -> {
              throw new IllegalArgumentException("Content type already exists: " + key);
            });
  }

  private void ensureFieldKeyAvailable(Long contentTypeId, String key, Long currentId) {
    fieldRepository
        .findByContentTypeIdAndFieldKeyIgnoreCase(contentTypeId, key)
        .filter(existing -> !Objects.equals(existing.getId(), currentId))
        .ifPresent(
            existing -> {
              throw new IllegalArgumentException("Content type field already exists: " + key);
            });
  }

  private void ensureEntryKeyAvailable(Long projectId, String key, Long currentId) {
    entryRepository
        .findByProjectIdAndEntryKeyIgnoreCase(projectId, key)
        .filter(existing -> !Objects.equals(existing.getId(), currentId))
        .ifPresent(
            existing -> {
              throw new IllegalArgumentException("Content entry already exists: " + key);
            });
  }

  private void ensureVariantKeyAvailable(Long entryId, String key, Long currentId) {
    variantRepository
        .findByEntryIdAndVariantKeyIgnoreCase(entryId, key)
        .filter(existing -> !Objects.equals(existing.getId(), currentId))
        .ifPresent(
            existing -> {
              throw new IllegalArgumentException("Content entry variant already exists: " + key);
            });
  }

  private void requireExpectedVersion(Long actualVersion, Long expectedVersion, String label) {
    if (expectedVersion == null) {
      throw new IllegalArgumentException(label + " expectedVersion is required");
    }
    if (!Objects.equals(actualVersion, expectedVersion)) {
      throw changedSinceLoaded(label);
    }
  }

  private void requireMappingExpectedVersion(CmsContentFieldMapping mapping, Long expectedVersion) {
    if (mapping == null) {
      if (expectedVersion != null) {
        throw changedSinceLoaded("Field mapping");
      }
      return;
    }
    if (expectedVersion == null || !Objects.equals(mapping.getEntityVersion(), expectedVersion)) {
      throw changedSinceLoaded("Field mapping");
    }
  }

  private CmsContentConflictException changedSinceLoaded(String label) {
    return new CmsContentConflictException(
        label + " changed since it was loaded; refresh and retry");
  }

  private Integer normalizeNewContentTypeSchemaVersion(Integer schemaVersion) {
    if (schemaVersion == null || schemaVersion == 1) {
      return 1;
    }
    throw new IllegalArgumentException("New content type schema version must start at 1");
  }

  private Integer normalizeSortOrder(Integer sortOrder) {
    if (sortOrder == null) {
      return 0;
    }
    if (sortOrder < 0) {
      throw new IllegalArgumentException("Sort order must be at least 0");
    }
    return sortOrder;
  }

  private void rejectManualSchemaVersionChange(
      CmsContentType contentType, Integer requestedSchemaVersion) {
    if (requestedSchemaVersion != null
        && !Objects.equals(contentType.getSchemaVersion(), requestedSchemaVersion)) {
      throw new IllegalArgumentException("Content type schema version is managed automatically");
    }
  }

  private void bumpContentTypeSchemaVersion(CmsContentType contentType) {
    contentType.setSchemaVersion(Math.addExact(contentType.getSchemaVersion(), 1));
    contentTypeRepository.saveAndFlush(contentType);
  }

  private ProjectSummary toProjectSummary(CmsContentProject project) {
    return new ProjectSummary(
        project.getId(),
        project.getEntityVersion(),
        toAuditView(project),
        project.getProjectKey(),
        project.getName(),
        project.getDescription(),
        Boolean.TRUE.equals(project.getEnabled()),
        new RepositoryRef(project.getRepository().getId(), project.getRepository().getName()),
        new AssetRef(project.getAsset().getId(), project.getAsset().getPath()),
        project.getDeliveryHint());
  }

  private ProjectView toProjectView(CmsContentProject project) {
    return new ProjectView(
        project.getId(),
        project.getEntityVersion(),
        toAuditView(project),
        project.getProjectKey(),
        project.getName(),
        project.getDescription(),
        Boolean.TRUE.equals(project.getEnabled()),
        new RepositoryRef(project.getRepository().getId(), project.getRepository().getName()),
        new AssetRef(project.getAsset().getId(), project.getAsset().getPath()),
        project.getRepository().getSourceLocale().getBcp47Tag(),
        project.getDeliveryHint());
  }

  private ContentTypeView toContentTypeView(CmsContentType contentType, List<FieldView> fields) {
    return new ContentTypeView(
        contentType.getId(),
        contentType.getEntityVersion(),
        toAuditView(contentType),
        contentType.getProject().getId(),
        contentType.getTypeKey(),
        contentType.getName(),
        contentType.getDescription(),
        contentType.getSchemaVersion(),
        contentType.getMetadataSchemaJson(),
        fields);
  }

  private FieldView toFieldView(CmsContentTypeField field) {
    return new FieldView(
        field.getId(),
        field.getEntityVersion(),
        toAuditView(field),
        field.getContentType().getId(),
        field.getFieldKey(),
        field.getName(),
        field.getDescription(),
        field.getFieldType(),
        Boolean.TRUE.equals(field.getLocalizable()),
        Boolean.TRUE.equals(field.getRequired()),
        field.getSortOrder());
  }

  private EntryView toEntryView(CmsContentEntry entry, List<VariantView> variants) {
    return new EntryView(
        entry.getId(),
        entry.getEntityVersion(),
        toAuditView(entry),
        entry.getProject().getId(),
        entry.getContentType().getId(),
        entry.getEntryKey(),
        entry.getName(),
        entry.getDescription(),
        entry.getStatus(),
        entry.getMetadataJson(),
        variants);
  }

  private VariantView toVariantView(CmsContentEntryVariant variant) {
    return new VariantView(
        variant.getId(),
        variant.getEntityVersion(),
        toAuditView(variant),
        variant.getEntry().getId(),
        variant.getVariantKey(),
        variant.getName(),
        variant.getCandidateGroupKey(),
        variant.getStatus(),
        variant.getMetadataJson(),
        variant.getSortOrder(),
        List.of());
  }

  private FieldMappingView toFieldMappingView(CmsContentFieldMapping mapping) {
    return new FieldMappingView(
        mapping.getId(),
        mapping.getEntityVersion(),
        toAuditView(mapping),
        mapping.getVariant().getId(),
        mapping.getField().getId(),
        mapping.getField().getFieldKey(),
        mapping.getTmTextUnit().getId(),
        mapping.getTmTextUnit().getName(),
        mapping.getTmTextUnit().getContent(),
        mapping.getTmTextUnit().getComment());
  }

  private PublishSnapshotView toPublishSnapshotView(CmsPublishSnapshot snapshot) {
    validateSnapshotArtifactIntegrity(snapshot);
    String publisherUsername = requireSnapshotPublisherUsername(snapshot);
    return new PublishSnapshotView(
        snapshot.getId(),
        snapshot.getProject().getId(),
        snapshot.getSnapshotVersion(),
        snapshot.getStatus(),
        parseSnapshotLocaleTags(snapshot),
        snapshot.getArtifactSha256(),
        snapshot.getArtifactByteSize(),
        snapshot.getSnapshotSigningKeyId(),
        snapshot.getSnapshotSignature(),
        snapshot.getArtifactSignature(),
        snapshotArtifactFilename(snapshot),
        snapshotArtifactExportPath(snapshot),
        publisherUsername,
        snapshot.getPublishedAt());
  }

  private SnapshotDeliveryDescriptor toSnapshotDeliveryDescriptor(CmsPublishSnapshot snapshot) {
    JsonNode artifact = validateSnapshotArtifactIntegrity(snapshot);
    return new SnapshotDeliveryDescriptor(
        SNAPSHOT_DELIVERY_DESCRIPTOR_FORMAT_VERSION,
        snapshot.getProject().getProjectKey(),
        snapshot.getSnapshotVersion(),
        snapshot.getStatus(),
        parseSnapshotLocaleTags(snapshot),
        snapshotArtifactProjectHint(snapshot, artifact),
        snapshot.getArtifactSha256(),
        snapshot.getArtifactByteSize(),
        snapshot.getSnapshotSigningKeyId(),
        snapshot.getSnapshotSignature(),
        snapshot.getArtifactSignature(),
        snapshotArtifactFilename(snapshot),
        snapshotArtifactExportPath(snapshot),
        snapshot.getPublishedAt());
  }

  private String snapshotArtifactProjectHint(CmsPublishSnapshot snapshot, JsonNode artifact) {
    return requireSnapshotArtifactText(
        requireSnapshotArtifactObject(artifact, "delivery", snapshot), "projectHint", snapshot);
  }

  private AuditView toAuditView(CmsAuditableEntity entity) {
    return new AuditView(
        entity.getCreatedDate(),
        entity.getLastModifiedDate(),
        requireCmsActorUsername(entity.getCreatedByUser(), entity, "creator"),
        requireCmsActorUsername(entity.getLastModifiedByUser(), entity, "last modifier"));
  }

  private String requireCmsActorUsername(User actor, CmsAuditableEntity entity, String actorLabel) {
    if (actor == null || actor.getUsername() == null || actor.getUsername().isBlank()) {
      throw new IllegalStateException(
          "CMS "
              + actorLabel
              + " is missing: "
              + entity.getClass().getSimpleName()
              + " "
              + entity.getId());
    }
    return actor.getUsername();
  }

  private String snapshotArtifactFilename(CmsPublishSnapshot snapshot) {
    return snapshot.getProject().getProjectKey() + ".v" + snapshot.getSnapshotVersion() + ".json";
  }

  private String snapshotArtifactExportPath(CmsPublishSnapshot snapshot) {
    return "/api/content-cms/projects/"
        + snapshot.getProject().getProjectKey()
        + "/publish-snapshots/"
        + snapshot.getSnapshotVersion()
        + "/artifact";
  }

  private Comparator<CmsContentFieldMapping> mappingComparator() {
    return Comparator.comparing(
            (CmsContentFieldMapping mapping) -> mapping.getField().getSortOrder())
        .thenComparing(mapping -> mapping.getField().getFieldKey())
        .thenComparing(CmsContentFieldMapping::getId);
  }

  private JsonNode parseJson(String json) {
    if (json == null || json.isBlank()) {
      return objectMapper.createObjectNode();
    }
    try {
      return canonicalizeJson(strictTreeObjectMapper.readTree(json));
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Stored JSON is invalid", e);
    }
  }

  private String normalizeOptionalJson(String json, String label) {
    if (json == null || json.isBlank()) {
      return null;
    }
    return objectMapper.writeValueAsStringUnchecked(canonicalizeJson(readJson(json.trim(), label)));
  }

  private JsonNode canonicalizeJson(JsonNode node) {
    if (node.isObject()) {
      ObjectNode objectNode = objectMapper.createObjectNode();
      List<String> fieldNames = new ArrayList<>();
      node.fieldNames().forEachRemaining(fieldNames::add);
      fieldNames.stream()
          .sorted()
          .forEach(fieldName -> objectNode.set(fieldName, canonicalizeJson(node.get(fieldName))));
      return objectNode;
    }
    if (node.isArray()) {
      ArrayNode arrayNode = objectMapper.createArrayNode();
      node.forEach(value -> arrayNode.add(canonicalizeJson(value)));
      return arrayNode;
    }
    return node;
  }

  private String normalizeMetadataSchemaJson(String metadataSchemaJson) {
    if (metadataSchemaJson == null || metadataSchemaJson.isBlank()) {
      return null;
    }
    JsonNode metadataSchema = readJson(metadataSchemaJson.trim(), "Metadata schema");
    validateMetadataSchema(metadataSchema);
    return objectMapper.writeValueAsStringUnchecked(canonicalizeMetadataSchema(metadataSchema));
  }

  private JsonNode parseMetadataSchemaJson(String metadataSchemaJson) {
    if (metadataSchemaJson == null || metadataSchemaJson.isBlank()) {
      return objectMapper.createObjectNode();
    }
    JsonNode metadataSchema = readJson(metadataSchemaJson, "Stored metadata schema");
    validateMetadataSchema(metadataSchema);
    return canonicalizeMetadataSchema(metadataSchema);
  }

  private JsonNode canonicalizeMetadataSchema(JsonNode metadataSchema) {
    ObjectNode canonicalMetadataSchema = (ObjectNode) canonicalizeJson(metadataSchema);
    if (!canonicalMetadataSchema.path("required").isArray()) {
      return canonicalMetadataSchema;
    }
    List<String> requiredPropertyNames = new ArrayList<>();
    canonicalMetadataSchema
        .path("required")
        .forEach(required -> requiredPropertyNames.add(required.asText()));
    ArrayNode required = objectMapper.createArrayNode();
    requiredPropertyNames.stream().sorted().forEach(required::add);
    canonicalMetadataSchema.set("required", required);
    return canonicalMetadataSchema;
  }

  private String normalizeEntryMetadataJson(
      String metadataJson, CmsContentType contentType, String label) {
    String normalized = normalizeOptionalJson(metadataJson, label);
    validateMetadata(contentType.getMetadataSchemaJson(), normalized, label);
    return normalized;
  }

  private String normalizeMetadataObjectJson(String metadataJson, String label) {
    String normalized = normalizeOptionalJson(metadataJson, label);
    validateMetadata(null, normalized, label);
    return normalized;
  }

  private void validateExistingMetadata(CmsContentType contentType, String metadataSchemaJson) {
    List<CmsContentEntry> entries =
        entryRepository.findByContentTypeIdOrderByNameAscIdAsc(contentType.getId());
    for (CmsContentEntry entry : entries) {
      validateMetadata(
          metadataSchemaJson, entry.getMetadataJson(), "Entry metadata for " + entry.getEntryKey());
    }
  }

  private void validateMetadata(String metadataSchemaJson, String metadataJson, String label) {
    JsonNode metadata =
        metadataJson == null ? objectMapper.createObjectNode() : readJson(metadataJson, label);
    JsonNode schema =
        metadataSchemaJson == null ? null : readJson(metadataSchemaJson, "Stored metadata schema");
    validateMetadata(schema, metadata, label);
  }

  private void validateMetadata(JsonNode schema, JsonNode metadata, String label) {
    if (!metadata.isObject()) {
      throw new IllegalArgumentException(label + " must be a JSON object");
    }
    if (schema == null) {
      return;
    }

    validateMetadataSchema(schema);
    JsonNode properties = schema.path("properties");
    for (JsonNode required : schema.path("required")) {
      if (!metadata.has(required.asText())) {
        throw new IllegalArgumentException(
            label + " is missing required property: " + required.asText());
      }
    }
    if (schema.path("additionalProperties").isBoolean()
        && !schema.path("additionalProperties").asBoolean()) {
      metadata
          .fieldNames()
          .forEachRemaining(
              propertyName -> {
                if (!properties.has(propertyName)) {
                  throw new IllegalArgumentException(
                      label + " has unsupported property: " + propertyName);
                }
              });
    }
    metadata
        .fieldNames()
        .forEachRemaining(
            propertyName -> {
              JsonNode propertySchema = properties.path(propertyName);
              if (!propertySchema.isMissingNode()) {
                validateMetadataPropertyValue(
                    metadata.path(propertyName), propertySchema, label, propertyName);
              }
            });
  }

  private void validateMetadataSchema(JsonNode schema) {
    if (!schema.isObject()) {
      throw new IllegalArgumentException("Metadata schema must be a JSON object");
    }
    validateSupportedSchemaKeys(schema, METADATA_SCHEMA_KEYS, "Metadata schema");
    if (schema.has("type") && !"object".equals(schema.path("type").asText())) {
      throw new IllegalArgumentException("Metadata schema type must be object");
    }
    if (schema.has("$schema") && !schema.path("$schema").isTextual()) {
      throw new IllegalArgumentException("Metadata schema $schema must be a string");
    }
    if (schema.has("title") && !schema.path("title").isTextual()) {
      throw new IllegalArgumentException("Metadata schema title must be a string");
    }
    if (schema.has("description") && !schema.path("description").isTextual()) {
      throw new IllegalArgumentException("Metadata schema description must be a string");
    }
    JsonNode properties = schema.path("properties");
    if (schema.has("properties") && !properties.isObject()) {
      throw new IllegalArgumentException("Metadata schema properties must be an object");
    }
    properties
        .fields()
        .forEachRemaining(
            property -> validateMetadataPropertySchema(property.getKey(), property.getValue()));
    if (schema.has("required") && !schema.path("required").isArray()) {
      throw new IllegalArgumentException("Metadata schema required must be an array");
    }
    Set<String> requiredPropertyNames = new LinkedHashSet<>();
    for (JsonNode required : schema.path("required")) {
      if (!required.isTextual() || required.asText().isBlank()) {
        throw new IllegalArgumentException("Metadata schema required entries must be strings");
      }
      if (!requiredPropertyNames.add(required.asText())) {
        throw new IllegalArgumentException(
            "Metadata schema required property is duplicated: " + required.asText());
      }
      if (!properties.has(required.asText())) {
        throw new IllegalArgumentException(
            "Metadata schema required property is not declared: " + required.asText());
      }
    }
    if (schema.has("additionalProperties") && !schema.path("additionalProperties").isBoolean()) {
      throw new IllegalArgumentException("Metadata schema additionalProperties must be boolean");
    }
  }

  private void validateMetadataPropertySchema(String propertyName, JsonNode propertySchema) {
    if (!propertySchema.isObject()) {
      throw new IllegalArgumentException(
          "Metadata schema property must be an object: " + propertyName);
    }
    validateSupportedSchemaKeys(
        propertySchema, METADATA_PROPERTY_SCHEMA_KEYS, "Metadata schema property " + propertyName);
    if (propertySchema.has("description") && !propertySchema.path("description").isTextual()) {
      throw new IllegalArgumentException(
          "Metadata schema property description must be a string: " + propertyName);
    }
    if (propertySchema.has("type")) {
      String type = propertySchema.path("type").asText();
      if (!propertySchema.path("type").isTextual() || !METADATA_PROPERTY_TYPES.contains(type)) {
        throw new IllegalArgumentException(
            "Unsupported metadata schema property type for " + propertyName + ": " + type);
      }
    }
  }

  private void validateSupportedSchemaKeys(
      JsonNode schema, Set<String> supportedKeys, String label) {
    schema
        .fieldNames()
        .forEachRemaining(
            key -> {
              if (!supportedKeys.contains(key)) {
                throw new IllegalArgumentException(label + " has unsupported keyword: " + key);
              }
            });
  }

  private void validateMetadataPropertyValue(
      JsonNode value, JsonNode propertySchema, String label, String propertyName) {
    if (!propertySchema.has("type")) {
      return;
    }
    String type = propertySchema.path("type").asText();
    boolean matches =
        switch (type) {
          case "string" -> value.isTextual();
          case "number" -> value.isNumber();
          case "integer" -> value.isIntegralNumber();
          case "boolean" -> value.isBoolean();
          case "object" -> value.isObject();
          case "array" -> value.isArray();
          case "null" -> value.isNull();
          default -> false;
        };
    if (!matches) {
      throw new IllegalArgumentException(label + " property " + propertyName + " must be " + type);
    }
  }

  private JsonNode readJson(String json, String label) {
    try {
      return strictTreeObjectMapper.readTree(json);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException(label + " must be valid JSON");
    }
  }

  private String normalizeKey(String key, String label) {
    String normalized =
        key == null ? "" : key.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "-");
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(label + " is required");
    }
    if (normalized.length() > CmsContentProject.KEY_MAX_LENGTH
        || !KEY_PATTERN.matcher(normalized).matches()) {
      throw new IllegalArgumentException(
          label + " must use lowercase letters, numbers, underscores, or hyphens");
    }
    return normalized;
  }

  private String validatePublishRequestKey(String publishRequestKey) {
    String normalized = publishRequestKey == null ? "" : publishRequestKey.trim();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException("Publish request key is required");
    }
    if (!Objects.equals(normalized, publishRequestKey)) {
      throw new IllegalArgumentException(
          "Publish request key must not have leading or trailing whitespace");
    }
    if (normalized.length() > CmsPublishSnapshot.PUBLISH_REQUEST_KEY_MAX_LENGTH
        || !KEY_PATTERN.matcher(normalized).matches()) {
      throw new IllegalArgumentException(
          "Publish request key must use lowercase letters, numbers, underscores, or hyphens");
    }
    return normalized;
  }

  private PublishCommand requirePublishCommand(PublishCommand command) {
    if (command == null) {
      throw new IllegalArgumentException("Publish command is required");
    }
    return command;
  }

  private String validateExpectedPublishAuthoringSha256(String expectedAuthoringSha256) {
    if (expectedAuthoringSha256 == null || expectedAuthoringSha256.isBlank()) {
      throw new IllegalArgumentException("Expected authoring SHA-256 is required");
    }
    if (!Objects.equals(expectedAuthoringSha256, expectedAuthoringSha256.trim())
        || !isSha256(expectedAuthoringSha256)) {
      throw new IllegalArgumentException("Expected authoring SHA-256 is invalid");
    }
    return expectedAuthoringSha256;
  }

  private String validateExpectedPublishPackageSha256(String expectedPackageSha256) {
    if (expectedPackageSha256 == null || expectedPackageSha256.isBlank()) {
      throw new IllegalArgumentException("Expected package SHA-256 is required");
    }
    if (!Objects.equals(expectedPackageSha256, expectedPackageSha256.trim())
        || !isSha256(expectedPackageSha256)) {
      throw new IllegalArgumentException("Expected package SHA-256 is invalid");
    }
    return expectedPackageSha256;
  }

  private void requireMatchingExpectedPublishAuthoringSha256(
      String expectedAuthoringSha256, String publishRequestAuthoringSha256) {
    if (!Objects.equals(expectedAuthoringSha256, publishRequestAuthoringSha256)) {
      throw new CmsContentConflictException(
          "Content project authoring revision changed; refresh and validate before publish");
    }
  }

  private void requireMatchingExpectedPublishPackageSha256(
      String expectedPackageSha256, String publishPackageSha256) {
    if (!Objects.equals(expectedPackageSha256, publishPackageSha256)) {
      throw new CmsContentConflictException(
          "Content project publish package changed; validate again before publish");
    }
  }

  private String validateStableProjectKey(String projectKey) {
    String normalized = projectKey == null ? "" : projectKey.trim();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException("Project key is required");
    }
    if (!Objects.equals(normalized, projectKey)) {
      throw new IllegalArgumentException(
          "Stable project key must not have leading or trailing whitespace");
    }
    if (normalized.length() > CmsContentProject.KEY_MAX_LENGTH
        || !KEY_PATTERN.matcher(normalized).matches()) {
      throw new IllegalArgumentException(
          "Stable project key must use lowercase letters, numbers, underscores, or hyphens");
    }
    return normalized;
  }

  private String normalizeOptionalKey(String key, String label) {
    if (key == null || key.isBlank()) {
      return null;
    }
    return normalizeKey(key, label);
  }

  private String normalizeAssetPath(String assetPath, String projectKey) {
    String normalized =
        assetPath == null || assetPath.isBlank() ? "cms/" + projectKey : assetPath.trim();
    if (normalized.length() > 255) {
      throw new IllegalArgumentException("Asset path must be at most 255 characters");
    }
    return normalized;
  }

  private String normalizeDeliveryHint(String deliveryHint) {
    String normalized =
        deliveryHint == null || deliveryHint.isBlank()
            ? "BLOB_CDN"
            : deliveryHint.trim().toUpperCase(Locale.ROOT);
    if (normalized.length() > CmsContentProject.DELIVERY_HINT_MAX_LENGTH) {
      throw new IllegalArgumentException("Delivery hint is too long");
    }
    if (!DELIVERY_HINTS.contains(normalized)) {
      throw new IllegalArgumentException("Unsupported delivery hint: " + normalized);
    }
    return normalized;
  }

  private int requireSnapshotVersion(Integer snapshotVersion) {
    if (snapshotVersion == null || snapshotVersion < 1) {
      throw new IllegalArgumentException("Snapshot version must be at least 1");
    }
    return snapshotVersion;
  }

  private int requireLastPublishedSnapshotVersion(CmsContentProject project) {
    Integer lastPublishedSnapshotVersion = project.getLastPublishedSnapshotVersion();
    if (lastPublishedSnapshotVersion == null || lastPublishedSnapshotVersion < 0) {
      throw new IllegalStateException(
          "CMS content project has invalid publish snapshot history watermark: "
              + project.getProjectKey());
    }
    return lastPublishedSnapshotVersion;
  }

  private void validatePublishSnapshotHistory(CmsContentProject project) {
    int lastPublishedSnapshotVersion = requireLastPublishedSnapshotVersion(project);
    Integer maxSnapshotVersion =
        snapshotRepository.findMaxSnapshotVersionByProjectId(project.getId());
    int storedMaxSnapshotVersion = maxSnapshotVersion == null ? 0 : maxSnapshotVersion;
    long storedSnapshotCount = snapshotRepository.countByProjectId(project.getId());
    long storedSnapshotSealCount = snapshotSealRepository.countBySnapshotProjectId(project.getId());
    if (storedMaxSnapshotVersion != lastPublishedSnapshotVersion
        || storedSnapshotCount != lastPublishedSnapshotVersion
        || storedSnapshotSealCount != lastPublishedSnapshotVersion) {
      throw new IllegalStateException(
          "CMS publish snapshot history is incomplete for content project: "
              + project.getProjectKey());
    }
  }

  private void advanceLastPublishedSnapshotVersion(
      Long projectId, int expectedSnapshotVersion, int nextSnapshotVersion) {
    if (projectRepository.advanceLastPublishedSnapshotVersion(
            projectId, expectedSnapshotVersion, nextSnapshotVersion)
        != 1) {
      throw new CmsContentConflictException(
          "Content project publish snapshot history changed; retry publish");
    }
  }

  private void sealPublishSnapshot(CmsPublishSnapshot snapshot) {
    CmsPublishSnapshotSeal seal = new CmsPublishSnapshotSeal();
    seal.setPublishSnapshotId(requirePublishedSnapshotId(snapshot));
    snapshotSealRepository.save(seal);
  }

  private Long requirePublishedSnapshotId(CmsPublishSnapshot snapshot) {
    if (snapshot.getId() == null) {
      throw new IllegalStateException("CMS publish snapshot must be stored before sealing");
    }
    return snapshot.getId();
  }

  private String normalizeName(String name, int maxLength, String label) {
    String normalized = name == null ? "" : name.trim().replaceAll("\\s+", " ");
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(label + " is required");
    }
    if (normalized.length() > maxLength) {
      throw new IllegalArgumentException(label + " must be at most " + maxLength + " characters");
    }
    return normalized;
  }

  private String normalizeOptionalText(String value, int maxLength, String label) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String normalized = value.trim();
    if (normalized.length() > maxLength) {
      throw new IllegalArgumentException(label + " must be at most " + maxLength + " characters");
    }
    return normalized;
  }

  private String normalizeSourceContent(String sourceContent) {
    if (sourceContent == null || sourceContent.isBlank()) {
      throw new IllegalArgumentException("Source content is required");
    }
    return sourceContent;
  }

  private String normalizeOptionalSourceContent(String sourceContent) {
    if (sourceContent == null || sourceContent.isBlank()) {
      return null;
    }
    return sourceContent;
  }

  private String normalizeOptionalStringId(String stringId) {
    if (stringId == null || stringId.isBlank()) {
      return null;
    }
    String normalized = stringId.trim();
    if (normalized.length() > 4000) {
      throw new IllegalArgumentException("Mojito string ID must be at most 4000 characters");
    }
    return normalized;
  }

  private boolean requireLocalizableField(Boolean localizable) {
    if (Boolean.FALSE.equals(localizable)) {
      throw new IllegalArgumentException(
          "MVP content type fields must be localizable; use metadata for non-localizable values");
    }
    return true;
  }

  private String normalizeSearchQuery(String searchQuery) {
    if (searchQuery == null) {
      return null;
    }
    String trimmed = searchQuery.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private int normalizeLimit(Integer limit) {
    if (limit == null) {
      return DEFAULT_LIMIT;
    }
    return Math.max(1, Math.min(MAX_LIMIT, limit));
  }

  private void requireAdmin() {
    if (!userService.isCurrentUserAdmin()) {
      throw new AccessDeniedException("Admin role required");
    }
  }

  private void requireSnapshotDeliveryReader() {
    if (!userService.isCurrentUserAdmin() && !userService.isCurrentUserCmsDelivery()) {
      throw new AccessDeniedException("Admin or CMS delivery role required");
    }
  }

  private User requireCurrentUser() {
    return userService
        .getCurrentUser()
        .orElseThrow(() -> new AccessDeniedException("Authenticated user required"));
  }

  private String requireSnapshotPublisherUsername(CmsPublishSnapshot snapshot) {
    String publisherUsername = snapshot.getCreatedByUsername();
    if (publisherUsername == null || publisherUsername.isBlank()) {
      throw new IllegalStateException("Publish snapshot publisher is missing: " + snapshot.getId());
    }
    return publisherUsername;
  }

  private String requirePublisherUsername(User publisher) {
    if (publisher == null || publisher.getUsername() == null || publisher.getUsername().isBlank()) {
      throw new IllegalStateException("Authenticated publisher username is missing");
    }
    return publisher.getUsername();
  }

  public record SearchProjectsView(List<ProjectSummary> projects, long totalCount) {}

  public record ProjectSummary(
      Long id,
      Long entityVersion,
      AuditView audit,
      String projectKey,
      String name,
      String description,
      boolean enabled,
      RepositoryRef repository,
      AssetRef asset,
      String deliveryHint) {}

  public record ProjectDetail(
      ProjectView project,
      String authoringSha256,
      List<ContentTypeView> contentTypes,
      List<EntryView> entries,
      List<PublishSnapshotView> publishSnapshots) {}

  public record ProjectView(
      Long id,
      Long entityVersion,
      AuditView audit,
      String projectKey,
      String name,
      String description,
      boolean enabled,
      RepositoryRef repository,
      AssetRef asset,
      String sourceLocale,
      String deliveryHint) {}

  public record RepositoryRef(Long id, String name) {}

  public record AssetRef(Long id, String path) {}

  public record AuditView(
      ZonedDateTime createdDate,
      ZonedDateTime lastModifiedDate,
      String createdByUsername,
      String lastModifiedByUsername) {}

  public record ContentTypeView(
      Long id,
      Long entityVersion,
      AuditView audit,
      Long projectId,
      String typeKey,
      String name,
      String description,
      Integer schemaVersion,
      String metadataSchemaJson,
      List<FieldView> fields) {}

  public record FieldView(
      Long id,
      Long entityVersion,
      AuditView audit,
      Long contentTypeId,
      String fieldKey,
      String name,
      String description,
      CmsContentTypeField.FieldType fieldType,
      boolean localizable,
      boolean required,
      Integer sortOrder) {}

  public record EntryView(
      Long id,
      Long entityVersion,
      AuditView audit,
      Long projectId,
      Long contentTypeId,
      String entryKey,
      String name,
      String description,
      CmsContentEntry.Status status,
      String metadataJson,
      List<VariantView> variants) {}

  public record VariantView(
      Long id,
      Long entityVersion,
      AuditView audit,
      Long entryId,
      String variantKey,
      String name,
      String candidateGroupKey,
      CmsContentEntryVariant.Status status,
      String metadataJson,
      Integer sortOrder,
      List<FieldMappingView> fieldMappings) {}

  public record FieldMappingView(
      Long id,
      Long entityVersion,
      AuditView audit,
      Long variantId,
      Long fieldId,
      String fieldKey,
      Long tmTextUnitId,
      String stringId,
      String sourceContent,
      String sourceComment) {}

  public record LocaleCompleteness(
      String localeTag,
      int totalFields,
      int approvedFields,
      int missingFields,
      int reviewNeededFields,
      int translationNeededFields,
      boolean complete) {}

  public record EntryCompletenessView(
      Long entryId, String entryKey, List<LocaleCompleteness> locales) {}

  public record ProjectCompletenessView(
      Long projectId,
      String projectKey,
      String authoringSha256,
      String publishPackageSha256,
      Long publishPackageByteSize,
      List<String> localeTags,
      List<LocaleCompleteness> locales,
      List<EntryCompletenessView> entries,
      boolean complete) {}

  public record PublishSnapshotView(
      Long id,
      Long projectId,
      Integer snapshotVersion,
      CmsPublishSnapshot.Status status,
      List<String> localeTags,
      String artifactSha256,
      Long artifactByteSize,
      String snapshotSigningKeyId,
      String snapshotSignature,
      String artifactSignature,
      String artifactFilename,
      String artifactExportPath,
      String createdByUsername,
      String publishedAt) {}

  public record SnapshotDeliveryDescriptor(
      String formatVersion,
      String projectKey,
      Integer snapshotVersion,
      CmsPublishSnapshot.Status status,
      List<String> localeTags,
      String projectHint,
      String artifactSha256,
      Long artifactByteSize,
      String snapshotSigningKeyId,
      String snapshotSignature,
      String artifactSignature,
      String artifactFilename,
      String artifactExportPath,
      String publishedAt) {}

  public record SnapshotArtifact(
      String artifactJson,
      String artifactSha256,
      Long artifactByteSize,
      String snapshotSigningKeyId,
      String snapshotSignature,
      String artifactSignature,
      String filename) {}

  public record ProjectCommand(
      String projectKey,
      String name,
      String description,
      Boolean enabled,
      Long repositoryId,
      String assetPath,
      String deliveryHint) {}

  public static final class ProjectUpdateCommand {
    private String name;
    private boolean namePresent;
    private String description;
    private boolean descriptionPresent;
    private Boolean enabled;
    private boolean enabledPresent;
    private String deliveryHint;
    private boolean deliveryHintPresent;
    private Long expectedVersion;

    public ProjectUpdateCommand() {}

    public ProjectUpdateCommand(
        String name,
        String description,
        Boolean enabled,
        String deliveryHint,
        Long expectedVersion) {
      setName(name);
      setDescription(description);
      setEnabled(enabled);
      setDeliveryHint(deliveryHint);
      setExpectedVersion(expectedVersion);
    }

    public String name() {
      return name;
    }

    public boolean hasName() {
      return namePresent;
    }

    @JsonSetter("name")
    public void setName(String name) {
      this.name = name;
      this.namePresent = true;
    }

    public String description() {
      return description;
    }

    public boolean hasDescription() {
      return descriptionPresent;
    }

    @JsonSetter("description")
    public void setDescription(String description) {
      this.description = description;
      this.descriptionPresent = true;
    }

    public Boolean enabled() {
      return enabled;
    }

    public boolean hasEnabled() {
      return enabledPresent;
    }

    @JsonSetter("enabled")
    public void setEnabled(Boolean enabled) {
      this.enabled = enabled;
      this.enabledPresent = true;
    }

    public String deliveryHint() {
      return deliveryHint;
    }

    public boolean hasDeliveryHint() {
      return deliveryHintPresent;
    }

    @JsonSetter("deliveryHint")
    public void setDeliveryHint(String deliveryHint) {
      this.deliveryHint = deliveryHint;
      this.deliveryHintPresent = true;
    }

    public Long expectedVersion() {
      return expectedVersion;
    }

    @JsonSetter("expectedVersion")
    public void setExpectedVersion(Long expectedVersion) {
      this.expectedVersion = expectedVersion;
    }
  }

  public record ContentTypeCommand(
      String typeKey,
      String name,
      String description,
      Integer schemaVersion,
      String metadataSchemaJson) {}

  public static final class ContentTypeUpdateCommand {
    private String name;
    private boolean namePresent;
    private String description;
    private boolean descriptionPresent;
    private Integer schemaVersion;
    private String metadataSchemaJson;
    private boolean metadataSchemaJsonPresent;
    private Long expectedVersion;

    public ContentTypeUpdateCommand() {}

    public ContentTypeUpdateCommand(
        String name,
        String description,
        Integer schemaVersion,
        String metadataSchemaJson,
        Long expectedVersion) {
      setName(name);
      setDescription(description);
      setSchemaVersion(schemaVersion);
      setMetadataSchemaJson(metadataSchemaJson);
      setExpectedVersion(expectedVersion);
    }

    public String name() {
      return name;
    }

    public boolean hasName() {
      return namePresent;
    }

    @JsonSetter("name")
    public void setName(String name) {
      this.name = name;
      this.namePresent = true;
    }

    public String description() {
      return description;
    }

    public boolean hasDescription() {
      return descriptionPresent;
    }

    @JsonSetter("description")
    public void setDescription(String description) {
      this.description = description;
      this.descriptionPresent = true;
    }

    public Integer schemaVersion() {
      return schemaVersion;
    }

    @JsonSetter("schemaVersion")
    public void setSchemaVersion(Integer schemaVersion) {
      this.schemaVersion = schemaVersion;
    }

    public String metadataSchemaJson() {
      return metadataSchemaJson;
    }

    public boolean hasMetadataSchemaJson() {
      return metadataSchemaJsonPresent;
    }

    @JsonSetter("metadataSchemaJson")
    public void setMetadataSchemaJson(String metadataSchemaJson) {
      this.metadataSchemaJson = metadataSchemaJson;
      this.metadataSchemaJsonPresent = true;
    }

    public Long expectedVersion() {
      return expectedVersion;
    }

    @JsonSetter("expectedVersion")
    public void setExpectedVersion(Long expectedVersion) {
      this.expectedVersion = expectedVersion;
    }
  }

  public record ContentTypeFieldCommand(
      String fieldKey,
      String name,
      String description,
      CmsContentTypeField.FieldType fieldType,
      Boolean localizable,
      Boolean required,
      Integer sortOrder) {}

  public static final class ContentTypeFieldUpdateCommand {
    private String name;
    private boolean namePresent;
    private String description;
    private boolean descriptionPresent;
    private CmsContentTypeField.FieldType fieldType;
    private boolean fieldTypePresent;
    private Boolean localizable;
    private boolean localizablePresent;
    private Boolean required;
    private boolean requiredPresent;
    private Integer sortOrder;
    private boolean sortOrderPresent;
    private Long expectedVersion;

    public ContentTypeFieldUpdateCommand() {}

    public ContentTypeFieldUpdateCommand(
        String name,
        String description,
        CmsContentTypeField.FieldType fieldType,
        Boolean localizable,
        Boolean required,
        Integer sortOrder,
        Long expectedVersion) {
      setName(name);
      setDescription(description);
      setFieldType(fieldType);
      setLocalizable(localizable);
      setRequired(required);
      setSortOrder(sortOrder);
      setExpectedVersion(expectedVersion);
    }

    public String name() {
      return name;
    }

    public boolean hasName() {
      return namePresent;
    }

    @JsonSetter("name")
    public void setName(String name) {
      this.name = name;
      this.namePresent = true;
    }

    public String description() {
      return description;
    }

    public boolean hasDescription() {
      return descriptionPresent;
    }

    @JsonSetter("description")
    public void setDescription(String description) {
      this.description = description;
      this.descriptionPresent = true;
    }

    public CmsContentTypeField.FieldType fieldType() {
      return fieldType;
    }

    public boolean hasFieldType() {
      return fieldTypePresent;
    }

    @JsonSetter("fieldType")
    public void setFieldType(CmsContentTypeField.FieldType fieldType) {
      this.fieldType = fieldType;
      this.fieldTypePresent = true;
    }

    public Boolean localizable() {
      return localizable;
    }

    public boolean hasLocalizable() {
      return localizablePresent;
    }

    @JsonSetter("localizable")
    public void setLocalizable(Boolean localizable) {
      this.localizable = localizable;
      this.localizablePresent = true;
    }

    public Boolean required() {
      return required;
    }

    public boolean hasRequired() {
      return requiredPresent;
    }

    @JsonSetter("required")
    public void setRequired(Boolean required) {
      this.required = required;
      this.requiredPresent = true;
    }

    public Integer sortOrder() {
      return sortOrder;
    }

    public boolean hasSortOrder() {
      return sortOrderPresent;
    }

    @JsonSetter("sortOrder")
    public void setSortOrder(Integer sortOrder) {
      this.sortOrder = sortOrder;
      this.sortOrderPresent = true;
    }

    public Long expectedVersion() {
      return expectedVersion;
    }

    @JsonSetter("expectedVersion")
    public void setExpectedVersion(Long expectedVersion) {
      this.expectedVersion = expectedVersion;
    }
  }

  public record EntryCommand(
      Long contentTypeId,
      String entryKey,
      String name,
      String description,
      CmsContentEntry.Status status,
      String metadataJson) {}

  public static final class EntryUpdateCommand {
    private String name;
    private boolean namePresent;
    private String description;
    private boolean descriptionPresent;
    private CmsContentEntry.Status status;
    private boolean statusPresent;
    private String metadataJson;
    private boolean metadataJsonPresent;
    private Long expectedVersion;

    public EntryUpdateCommand() {}

    public EntryUpdateCommand(
        String name,
        String description,
        CmsContentEntry.Status status,
        String metadataJson,
        Long expectedVersion) {
      setName(name);
      setDescription(description);
      setStatus(status);
      setMetadataJson(metadataJson);
      setExpectedVersion(expectedVersion);
    }

    public String name() {
      return name;
    }

    public boolean hasName() {
      return namePresent;
    }

    @JsonSetter("name")
    public void setName(String name) {
      this.name = name;
      this.namePresent = true;
    }

    public String description() {
      return description;
    }

    public boolean hasDescription() {
      return descriptionPresent;
    }

    @JsonSetter("description")
    public void setDescription(String description) {
      this.description = description;
      this.descriptionPresent = true;
    }

    public CmsContentEntry.Status status() {
      return status;
    }

    public boolean hasStatus() {
      return statusPresent;
    }

    @JsonSetter("status")
    public void setStatus(CmsContentEntry.Status status) {
      this.status = status;
      this.statusPresent = true;
    }

    public String metadataJson() {
      return metadataJson;
    }

    public boolean hasMetadataJson() {
      return metadataJsonPresent;
    }

    @JsonSetter("metadataJson")
    public void setMetadataJson(String metadataJson) {
      this.metadataJson = metadataJson;
      this.metadataJsonPresent = true;
    }

    public Long expectedVersion() {
      return expectedVersion;
    }

    @JsonSetter("expectedVersion")
    public void setExpectedVersion(Long expectedVersion) {
      this.expectedVersion = expectedVersion;
    }
  }

  public record VariantCommand(
      String variantKey,
      String name,
      String candidateGroupKey,
      CmsContentEntryVariant.Status status,
      String metadataJson,
      Integer sortOrder) {}

  public static final class VariantUpdateCommand {
    private String name;
    private boolean namePresent;
    private String candidateGroupKey;
    private boolean candidateGroupKeyPresent;
    private CmsContentEntryVariant.Status status;
    private boolean statusPresent;
    private String metadataJson;
    private boolean metadataJsonPresent;
    private Integer sortOrder;
    private boolean sortOrderPresent;
    private Long expectedVersion;

    public VariantUpdateCommand() {}

    public VariantUpdateCommand(
        String name,
        String candidateGroupKey,
        CmsContentEntryVariant.Status status,
        String metadataJson,
        Integer sortOrder,
        Long expectedVersion) {
      setName(name);
      setCandidateGroupKey(candidateGroupKey);
      setStatus(status);
      setMetadataJson(metadataJson);
      setSortOrder(sortOrder);
      setExpectedVersion(expectedVersion);
    }

    public String name() {
      return name;
    }

    public boolean hasName() {
      return namePresent;
    }

    @JsonSetter("name")
    public void setName(String name) {
      this.name = name;
      this.namePresent = true;
    }

    public String candidateGroupKey() {
      return candidateGroupKey;
    }

    public boolean hasCandidateGroupKey() {
      return candidateGroupKeyPresent;
    }

    @JsonSetter("candidateGroupKey")
    public void setCandidateGroupKey(String candidateGroupKey) {
      this.candidateGroupKey = candidateGroupKey;
      this.candidateGroupKeyPresent = true;
    }

    public CmsContentEntryVariant.Status status() {
      return status;
    }

    public boolean hasStatus() {
      return statusPresent;
    }

    @JsonSetter("status")
    public void setStatus(CmsContentEntryVariant.Status status) {
      this.status = status;
      this.statusPresent = true;
    }

    public String metadataJson() {
      return metadataJson;
    }

    public boolean hasMetadataJson() {
      return metadataJsonPresent;
    }

    @JsonSetter("metadataJson")
    public void setMetadataJson(String metadataJson) {
      this.metadataJson = metadataJson;
      this.metadataJsonPresent = true;
    }

    public Integer sortOrder() {
      return sortOrder;
    }

    public boolean hasSortOrder() {
      return sortOrderPresent;
    }

    @JsonSetter("sortOrder")
    public void setSortOrder(Integer sortOrder) {
      this.sortOrder = sortOrder;
      this.sortOrderPresent = true;
    }

    public Long expectedVersion() {
      return expectedVersion;
    }

    @JsonSetter("expectedVersion")
    public void setExpectedVersion(Long expectedVersion) {
      this.expectedVersion = expectedVersion;
    }
  }

  public record FieldMappingCommand(
      Long fieldId,
      Long tmTextUnitId,
      String stringId,
      String sourceContent,
      String sourceComment,
      Long expectedVersion) {
    public FieldMappingCommand(
        Long fieldId,
        Long tmTextUnitId,
        String sourceContent,
        String sourceComment,
        Long expectedVersion) {
      this(fieldId, tmTextUnitId, null, sourceContent, sourceComment, expectedVersion);
    }
  }

  public record FieldMappingDeleteCommand(Long expectedVersion) {}

  public record PublishCommand(
      List<String> localeTags, String expectedAuthoringSha256, String expectedPackageSha256) {}

  private record PublishPackage(Map<String, Object> payload, String sha256, Long byteSize) {}
}
