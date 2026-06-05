package com.box.l10n.mojito.service.jsonconfiglocalization;

import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.RepositoryLocale;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.asset.VirtualAsset;
import com.box.l10n.mojito.service.asset.VirtualAssetBadRequestException;
import com.box.l10n.mojito.service.asset.VirtualAssetRequiredException;
import com.box.l10n.mojito.service.asset.VirtualAssetService;
import com.box.l10n.mojito.service.asset.VirtualAssetTextUnit;
import com.box.l10n.mojito.service.repository.RepositoryRepository;
import com.box.l10n.mojito.service.tm.search.TextUnitDTO;
import com.box.l10n.mojito.service.tm.search.TextUnitSearcher;
import com.box.l10n.mojito.service.tm.search.TextUnitSearcherParameters;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JsonConfigLocalizationProcessorService {

  private static final String DEFAULT_ASSET_PATH = "json-config-localization/strings.json";
  private static final String ROOT_ARRAY_COLLECTION_KEY = "$";
  private static final String DEFAULT_ITEM_ID_FIELD = "id";
  private static final String DEFAULT_TRANSLATIONS_FIELD = "translations";
  private static final String DEFAULT_SOURCE_LOCALE_TAG = "en-US";
  private static final String DEFAULT_SOURCE_FIELD = "source";
  private static final String DEFAULT_COMMENT_FIELD = "description";
  private static final String DEFAULT_FORMATJS_SOURCE_FIELD = "defaultMessage";

  private static final SourceConfigProfile DEFAULT_PROFILE =
      new SourceConfigProfile(
          SourceConfigFormat.EMBEDDED_TRANSLATIONS,
          "",
          DEFAULT_ITEM_ID_FIELD,
          DEFAULT_TRANSLATIONS_FIELD,
          DEFAULT_SOURCE_LOCALE_TAG,
          List.of(),
          DEFAULT_SOURCE_FIELD,
          DEFAULT_COMMENT_FIELD);

  private final ObjectMapper objectMapper;
  private final JsonConfigLocalizationService jsonConfigLocalizationService;
  private final RepositoryRepository repositoryRepository;
  private final VirtualAssetService virtualAssetService;
  private final TextUnitSearcher textUnitSearcher;

  public JsonConfigLocalizationProcessorService(
      ObjectMapper objectMapper,
      JsonConfigLocalizationService jsonConfigLocalizationService,
      RepositoryRepository repositoryRepository,
      VirtualAssetService virtualAssetService,
      TextUnitSearcher textUnitSearcher) {
    this.objectMapper = objectMapper;
    this.jsonConfigLocalizationService = jsonConfigLocalizationService;
    this.repositoryRepository = repositoryRepository;
    this.virtualAssetService = virtualAssetService;
    this.textUnitSearcher = textUnitSearcher;
  }

  public DetectMappingResult detectMapping(DetectMappingInput input) {
    List<String> warnings = new ArrayList<>();
    SourceConfigProfile profile = detectProfile(nullToBlank(input.schemaJson()), warnings);
    return new DetectMappingResult(profile, warnings);
  }

  public ExtractionResult extract(ExtractionInput input) {
    List<String> warnings = new ArrayList<>();
    JsonNode sourceConfig = readSourceConfig(nullToBlank(input.sourceConfigJson()), warnings);
    SourceConfigProfile detectedProfile = detectProfile(nullToBlank(input.schemaJson()), warnings);
    SourceConfigProfile profile =
        inferProfileFromSource(
            sourceConfig, applyOverrides(detectedProfile, input.profile()), warnings);
    SourceSchemaConstraints schemaConstraints =
        sourceSchemaConstraints(nullToBlank(input.schemaJson()), profile);
    Extraction extraction = extractStrings(sourceConfig, profile, schemaConstraints, warnings);
    return new ExtractionResult(
        profile,
        writeJson(sourceConfig),
        extraction.strings(),
        dedupeWarnings(extraction.warnings()));
  }

  public ExtractForRepositoryResult extractForRepository(
      Long repositoryId, ExtractForRepositoryInput input)
      throws VirtualAssetBadRequestException, VirtualAssetRequiredException {
    return extractForRepository(repositoryId, input, false);
  }

  public ExtractForRepositoryResult extractForRepositoryForSystem(
      Long repositoryId, ExtractForRepositoryInput input)
      throws VirtualAssetBadRequestException, VirtualAssetRequiredException {
    return extractForRepository(repositoryId, input, true);
  }

  public ExtractForRepositoryResult extractForSetup(Long setupId, ExtractForRepositoryInput input)
      throws VirtualAssetBadRequestException, VirtualAssetRequiredException {
    return extractForSetup(setupId, input, false);
  }

  public ExtractForRepositoryResult extractForSetupForSystem(
      Long setupId, ExtractForRepositoryInput input)
      throws VirtualAssetBadRequestException, VirtualAssetRequiredException {
    return extractForSetup(setupId, input, true);
  }

  private ExtractForRepositoryResult extractForSetup(
      Long setupId, ExtractForRepositoryInput input, boolean systemAccess)
      throws VirtualAssetBadRequestException, VirtualAssetRequiredException {
    JsonConfigLocalizationService.JsonConfigLocalization existingSetup =
        systemAccess
            ? jsonConfigLocalizationService.getByIdForSystem(setupId)
            : jsonConfigLocalizationService.getById(setupId);
    ExtractForRepositoryInput effectiveInput =
        new ExtractForRepositoryInput(
            firstNonBlank(input == null ? null : input.name(), existingSetup.name()),
            firstNonBlank(input == null ? null : input.assetPath(), existingSetup.assetPath()),
            firstNonBlank(input == null ? null : input.provider(), existingSetup.provider()),
            firstNonBlank(
                input == null ? null : input.providerConfigId(), existingSetup.providerConfigId()),
            firstText(input == null ? null : input.schemaJson(), existingSetup.schemaJson()),
            firstText(
                input == null ? null : input.sourceConfigJson(), existingSetup.sourceConfigJson()),
            input == null || input.profile() == null
                ? readProfile(existingSetup.extractionMappingJson(), existingSetup.schemaJson())
                : input.profile(),
            input == null ? null : input.strings(),
            firstText(
                input == null ? null : input.outputLocaleMappingJson(),
                existingSetup.outputLocaleMappingJson()),
            input == null ? null : input.expectedLastModifiedDate());
    return extractForSetup(existingSetup, effectiveInput, systemAccess);
  }

  private ExtractForRepositoryResult extractForRepository(
      Long repositoryId, ExtractForRepositoryInput input, boolean systemAccess)
      throws VirtualAssetBadRequestException, VirtualAssetRequiredException {
    ExtractionResult extraction =
        input.strings() != null && nullToBlank(input.sourceConfigJson()).isBlank()
            ? explicitStringExtraction(input.schemaJson(), input.profile())
            : extract(
                new ExtractionInput(input.schemaJson(), input.sourceConfigJson(), input.profile()));
    List<JsonConfigString> strings =
        input.strings() == null
            ? extraction.strings()
            : normalizeInputStrings(input.strings(), extraction.profile());
    if (input.strings() != null && !nullToBlank(input.sourceConfigJson()).isBlank()) {
      validateInputStringsExistInSourceConfig(strings, extraction.strings());
    }
    String assetPath = firstNonBlank(input.assetPath(), DEFAULT_ASSET_PATH);
    JsonConfigLocalizationService.JsonConfigLocalization setup =
        systemAccess
            ? jsonConfigLocalizationService.upsertForRepositoryForSystem(
                repositoryId,
                new JsonConfigLocalizationService.JsonConfigLocalizationInput(
                    input.name(),
                    assetPath,
                    input.provider(),
                    input.providerConfigId(),
                    input.schemaJson(),
                    extraction.sourceConfigJson(),
                    writeJson(extraction.profile()),
                    input.outputLocaleMappingJson(),
                    null,
                    null,
                    null,
                    null,
                    input.expectedLastModifiedDate()))
            : jsonConfigLocalizationService.upsertForRepository(
                repositoryId,
                new JsonConfigLocalizationService.JsonConfigLocalizationInput(
                    input.name(),
                    assetPath,
                    input.provider(),
                    input.providerConfigId(),
                    input.schemaJson(),
                    extraction.sourceConfigJson(),
                    writeJson(extraction.profile()),
                    input.outputLocaleMappingJson(),
                    null,
                    null,
                    null,
                    null,
                    input.expectedLastModifiedDate()));

    VirtualAsset virtualAsset = new VirtualAsset();
    virtualAsset.setRepositoryId(repositoryId);
    virtualAsset.setPath(assetPath);
    virtualAsset.setDeleted(false);
    VirtualAsset savedVirtualAsset = virtualAssetService.createOrUpdateVirtualAsset(virtualAsset);

    PollableTask pollableTask =
        virtualAssetService
            .replaceTextUnits(savedVirtualAsset.getId(), toVirtualAssetTextUnits(strings))
            .getPollableTask();

    return new ExtractForRepositoryResult(setup, strings, extraction.warnings(), pollableTask);
  }

  private ExtractForRepositoryResult extractForSetup(
      JsonConfigLocalizationService.JsonConfigLocalization existingSetup,
      ExtractForRepositoryInput input,
      boolean systemAccess)
      throws VirtualAssetBadRequestException, VirtualAssetRequiredException {
    Long repositoryId = existingSetup.repository().id();
    ExtractionResult extraction =
        input.strings() != null && nullToBlank(input.sourceConfigJson()).isBlank()
            ? explicitStringExtraction(input.schemaJson(), input.profile())
            : extract(
                new ExtractionInput(input.schemaJson(), input.sourceConfigJson(), input.profile()));
    List<JsonConfigString> strings =
        input.strings() == null
            ? extraction.strings()
            : normalizeInputStrings(input.strings(), extraction.profile());
    if (input.strings() != null && !nullToBlank(input.sourceConfigJson()).isBlank()) {
      validateInputStringsExistInSourceConfig(strings, extraction.strings());
    }
    String assetPath = firstNonBlank(input.assetPath(), existingSetup.assetPath());
    JsonConfigLocalizationService.JsonConfigLocalization setup =
        systemAccess
            ? jsonConfigLocalizationService.updateForSystem(
                existingSetup.id(),
                new JsonConfigLocalizationService.JsonConfigLocalizationInput(
                    input.name(),
                    assetPath,
                    input.provider(),
                    input.providerConfigId(),
                    input.schemaJson(),
                    extraction.sourceConfigJson(),
                    writeJson(extraction.profile()),
                    input.outputLocaleMappingJson(),
                    existingSetup.automationEnabled(),
                    existingSetup.automationCronExpression(),
                    existingSetup.automationTimeZone(),
                    existingSetup.automationOptionsJson(),
                    input.expectedLastModifiedDate()))
            : jsonConfigLocalizationService.update(
                existingSetup.id(),
                new JsonConfigLocalizationService.JsonConfigLocalizationInput(
                    input.name(),
                    assetPath,
                    input.provider(),
                    input.providerConfigId(),
                    input.schemaJson(),
                    extraction.sourceConfigJson(),
                    writeJson(extraction.profile()),
                    input.outputLocaleMappingJson(),
                    existingSetup.automationEnabled(),
                    existingSetup.automationCronExpression(),
                    existingSetup.automationTimeZone(),
                    existingSetup.automationOptionsJson(),
                    input.expectedLastModifiedDate()));

    VirtualAsset virtualAsset = new VirtualAsset();
    virtualAsset.setRepositoryId(repositoryId);
    virtualAsset.setPath(assetPath);
    virtualAsset.setDeleted(false);
    VirtualAsset savedVirtualAsset = virtualAssetService.createOrUpdateVirtualAsset(virtualAsset);

    PollableTask pollableTask =
        virtualAssetService
            .replaceTextUnits(savedVirtualAsset.getId(), toVirtualAssetTextUnits(strings))
            .getPollableTask();

    return new ExtractForRepositoryResult(setup, strings, extraction.warnings(), pollableTask);
  }

  @Transactional(readOnly = true)
  public TranslationScope getTranslationScopeForRepositoryForSystem(Long repositoryId) {
    JsonConfigLocalizationService.JsonConfigLocalization setup =
        jsonConfigLocalizationService.getByRepositoryIdForSystem(repositoryId);
    return getTranslationScopeForSetup(setup);
  }

  @Transactional(readOnly = true)
  public TranslationScope getTranslationScopeForSetupForSystem(Long setupId) {
    return getTranslationScopeForSetup(jsonConfigLocalizationService.getByIdForSystem(setupId));
  }

  private TranslationScope getTranslationScopeForSetup(
      JsonConfigLocalizationService.JsonConfigLocalization setup) {
    Long repositoryId = setup.repository().id();
    Repository repository = findRepository(repositoryId);
    List<Long> tmTextUnitIds =
        searchSourceTextUnits(repository, setup.assetPath()).stream()
            .filter(TextUnitDTO::isUsed)
            .map(TextUnitDTO::getTmTextUnitId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
    return new TranslationScope(repository.getName(), targetLocaleTags(repository), tmTextUnitIds);
  }

  private ExtractionResult explicitStringExtraction(
      String schemaJson, SourceConfigProfile profileOverride) {
    List<String> warnings = new ArrayList<>();
    SourceConfigProfile detectedProfile = detectProfile(nullToBlank(schemaJson), warnings);
    SourceConfigProfile profile = applyOverrides(detectedProfile, profileOverride);
    return new ExtractionResult(profile, null, List.of(), dedupeWarnings(warnings));
  }

  @Transactional(readOnly = true)
  public ExportResult exportForRepository(Long repositoryId) {
    return exportForRepository(repositoryId, false);
  }

  @Transactional(readOnly = true)
  public ExportResult exportForRepositoryForSystem(Long repositoryId) {
    return exportForRepository(repositoryId, true);
  }

  private ExportResult exportForRepository(Long repositoryId, boolean systemAccess) {
    JsonConfigLocalizationService.JsonConfigLocalization setup =
        systemAccess
            ? jsonConfigLocalizationService.getByRepositoryIdForSystem(repositoryId)
            : jsonConfigLocalizationService.getByRepositoryId(repositoryId);
    return exportForSetup(setup);
  }

  @Transactional(readOnly = true)
  public ExportResult exportForSetup(Long setupId) {
    return exportForSetup(jsonConfigLocalizationService.getById(setupId));
  }

  @Transactional(readOnly = true)
  public ExportResult exportForSetupForSystem(Long setupId) {
    return exportForSetup(jsonConfigLocalizationService.getByIdForSystem(setupId));
  }

  private ExportResult exportForSetup(JsonConfigLocalizationService.JsonConfigLocalization setup) {
    Long repositoryId = setup.repository().id();
    Repository repository = findRepository(repositoryId);
    List<String> warnings = new ArrayList<>();
    JsonNode sourceConfig = readSourceConfig(setup.sourceConfigJson(), warnings);
    SourceConfigProfile profile =
        inferProfileFromSource(
            sourceConfig, readProfile(setup.extractionMappingJson(), setup.schemaJson()), warnings);
    Map<String, String> outputLocaleMapping =
        readOutputLocaleMapping(setup.outputLocaleMappingJson());

    Map<String, TextUnitDTO> sourceTextUnitsByName =
        searchSourceTextUnits(repository, setup.assetPath()).stream()
            .filter(textUnit -> textUnit.getName() != null)
            .collect(
                Collectors.toMap(
                    TextUnitDTO::getName,
                    Function.identity(),
                    (left, right) -> left.isUsed() ? left : right,
                    LinkedHashMap::new));

    Map<Long, TextUnitDTO> sourceTextUnitsByTmTextUnitId =
        sourceTextUnitsByName.values().stream()
            .filter(TextUnitDTO::isUsed)
            .filter(textUnit -> textUnit.getTmTextUnitId() != null)
            .collect(
                Collectors.toMap(
                    TextUnitDTO::getTmTextUnitId,
                    Function.identity(),
                    (left, right) -> left,
                    LinkedHashMap::new));
    warnAboutUnexportableActiveStrings(sourceTextUnitsByName, sourceConfig, profile, warnings);

    Map<String, TextUnitDTO> targetTextUnitsByKey =
        searchTargetTextUnits(repository, new ArrayList<>(sourceTextUnitsByTmTextUnitId.keySet()))
            .stream()
            .filter(textUnit -> textUnit.getTargetLocale() != null)
            .filter(textUnit -> textUnit.getTmTextUnitId() != null)
            .collect(
                Collectors.toMap(
                    textUnit -> targetKey(textUnit.getTmTextUnitId(), textUnit.getTargetLocale()),
                    Function.identity(),
                    (left, right) -> left,
                    LinkedHashMap::new));

    if (profile.format() != SourceConfigFormat.EMBEDDED_TRANSLATIONS) {
      return exportLocaleMap(
          repository,
          outputLocaleMapping,
          sourceTextUnitsByName,
          sourceTextUnitsByTmTextUnitId,
          targetTextUnitsByKey,
          warnings);
    }

    if (collectionNode(sourceConfig, profile) == null) {
      warnings.add(
          "Embedded multilingual config export requires a config array at "
              + printableCollectionKey(profile.collectionKey())
              + ". Showing locale-map export instead.");
      return exportLocaleMap(
          repository,
          outputLocaleMapping,
          sourceTextUnitsByName,
          sourceTextUnitsByTmTextUnitId,
          targetTextUnitsByKey,
          warnings);
    }

    localizeSourceConfig(
        sourceConfig,
        repository,
        profile,
        outputLocaleMapping,
        sourceTextUnitsByName,
        targetTextUnitsByKey,
        warnings);

    return new ExportResult(prettyJson(sourceConfig), dedupeWarnings(warnings));
  }

  private ExportResult exportLocaleMap(
      Repository repository,
      Map<String, String> outputLocaleMapping,
      Map<String, TextUnitDTO> sourceTextUnitsByName,
      Map<Long, TextUnitDTO> sourceTextUnitsByTmTextUnitId,
      Map<String, TextUnitDTO> targetTextUnitsByKey,
      List<String> warnings) {
    ObjectNode localeMap = objectMapper.createObjectNode();
    List<TextUnitDTO> activeSourceTextUnits = activeSourceTextUnits(sourceTextUnitsByName);
    String sourceLocaleTag = repositorySourceLocaleTag(repository);
    ObjectNode sourceLocaleObject =
        localeMap.putObject(outputLocaleTag(sourceLocaleTag, outputLocaleMapping));

    for (TextUnitDTO sourceTextUnit : activeSourceTextUnits) {
      sourceLocaleObject.put(sourceTextUnit.getName(), nullToBlank(sourceTextUnit.getSource()));
    }

    for (String targetLocaleTag : targetLocaleTags(repository)) {
      ObjectNode targetLocaleObject = objectMapper.createObjectNode();
      for (TextUnitDTO sourceTextUnit : activeSourceTextUnits) {
        if (sourceTextUnit.getTmTextUnitId() == null
            || !sourceTextUnitsByTmTextUnitId.containsKey(sourceTextUnit.getTmTextUnitId())) {
          continue;
        }
        TextUnitDTO targetTextUnit =
            targetTextUnitsByKey.get(targetKey(sourceTextUnit.getTmTextUnitId(), targetLocaleTag));
        String target = targetTextUnit == null ? null : targetTextUnit.getTarget();
        if (target != null && !target.isBlank()) {
          targetLocaleObject.put(sourceTextUnit.getName(), target);
        }
      }

      if (!targetLocaleObject.isEmpty()) {
        localeMap.set(outputLocaleTag(targetLocaleTag, outputLocaleMapping), targetLocaleObject);
      }
    }

    return new ExportResult(prettyJson(localeMap), dedupeWarnings(warnings));
  }

  private List<TextUnitDTO> activeSourceTextUnits(Map<String, TextUnitDTO> sourceTextUnitsByName) {
    return sourceTextUnitsByName.values().stream()
        .filter(TextUnitDTO::isUsed)
        .filter(textUnit -> textUnit.getName() != null)
        .sorted(Comparator.comparing(TextUnitDTO::getName))
        .toList();
  }

  private String outputLocaleTag(String mojitoLocaleTag, Map<String, String> outputLocaleMapping) {
    return outputLocaleMapping.getOrDefault(mojitoLocaleTag, mojitoLocaleTag);
  }

  private void warnAboutUnexportableActiveStrings(
      Map<String, TextUnitDTO> sourceTextUnitsByName,
      JsonNode sourceConfig,
      SourceConfigProfile profile,
      List<String> warnings) {
    Set<String> exportableStringIds;
    try {
      exportableStringIds =
          extractStrings(
                  sourceConfig.deepCopy(),
                  profile,
                  SourceSchemaConstraints.empty(),
                  new ArrayList<>())
              .strings()
              .stream()
              .map(JsonConfigString::stringId)
              .collect(Collectors.toSet());
    } catch (IllegalArgumentException e) {
      warnings.add(e.getMessage());
      return;
    }
    List<String> unexportableStringIds =
        sourceTextUnitsByName.values().stream()
            .filter(TextUnitDTO::isUsed)
            .map(TextUnitDTO::getName)
            .filter(name -> name != null && !exportableStringIds.contains(name))
            .sorted()
            .limit(5)
            .toList();

    if (!unexportableStringIds.isEmpty()) {
      warnings.add(
          "Active strings not present in Config JSON were not exported: "
              + String.join(", ", unexportableStringIds)
              + ". Edit Config and extract again, or remove them from the bundle.");
    }
  }

  private void localizeSourceConfig(
      JsonNode sourceConfig,
      Repository repository,
      SourceConfigProfile profile,
      Map<String, String> outputLocaleMapping,
      Map<String, TextUnitDTO> sourceTextUnitsByName,
      Map<String, TextUnitDTO> targetTextUnitsByKey,
      List<String> warnings) {
    ArrayNode collection = collectionNode(sourceConfig, profile);
    if (collection == null) {
      throw new IllegalArgumentException(
          "Config collection must be an array: " + profile.collectionKey());
    }

    List<String> targetLocaleTags = targetLocaleTags(repository);
    for (int itemIndex = 0; itemIndex < collection.size(); itemIndex++) {
      JsonNode itemNode = collection.get(itemIndex);
      if (!(itemNode instanceof ObjectNode item)) {
        warnings.add("Skipped non-object item at index " + itemIndex + ".");
        continue;
      }

      String itemKey =
          resolveItemKey(item, profile.collectionKey(), profile.itemIdField(), itemIndex);
      ObjectNode translations = objectField(item, profile.translationsField());
      ObjectNode sourceLocaleObject = objectField(translations, profile.sourceLocaleTag());
      Map<String, String> originalSourceFields =
          translatableTextFields(sourceLocaleObject, profile.translatableFields());
      clearTranslatableFields(sourceLocaleObject, profile.translatableFields());

      for (String field : profile.translatableFields()) {
        String stringId = stringId(itemKey, field);
        TextUnitDTO sourceTextUnit = sourceTextUnitsByName.get(stringId);
        if (sourceTextUnit == null) {
          String originalSource = originalSourceFields.get(field);
          if (originalSource != null) {
            sourceLocaleObject.put(field, originalSource);
          }
          continue;
        }
        if (!sourceTextUnit.isUsed()) {
          continue;
        }

        sourceLocaleObject.put(field, nullToBlank(sourceTextUnit.getSource()));
      }

      Set<String> outputLocales = new LinkedHashSet<>();
      outputLocales.add(profile.sourceLocaleTag());
      for (String targetLocaleTag : targetLocaleTags) {
        String outputLocaleTag = outputLocaleMapping.getOrDefault(targetLocaleTag, targetLocaleTag);
        if (!outputLocales.add(outputLocaleTag)) {
          warnings.add(
              "Skipped duplicate output locale "
                  + outputLocaleTag
                  + " mapped from "
                  + targetLocaleTag
                  + ".");
          continue;
        }

        ObjectNode targetLocaleObject = objectNodeOrNew(translations.get(outputLocaleTag));
        clearTranslatableFields(targetLocaleObject, profile.translatableFields());

        int translatedFieldCount = 0;
        for (String field : profile.translatableFields()) {
          String stringId = stringId(itemKey, field);
          TextUnitDTO sourceTextUnit = sourceTextUnitsByName.get(stringId);
          if (sourceTextUnit == null
              || !sourceTextUnit.isUsed()
              || sourceTextUnit.getTmTextUnitId() == null) {
            continue;
          }

          TextUnitDTO targetTextUnit =
              targetTextUnitsByKey.get(
                  targetKey(sourceTextUnit.getTmTextUnitId(), targetLocaleTag));
          String target = targetTextUnit != null ? targetTextUnit.getTarget() : null;
          if (target != null && !target.isBlank()) {
            targetLocaleObject.put(field, target);
            translatedFieldCount++;
          }
        }

        if (translatedFieldCount > 0 || hasNonTranslatableFields(targetLocaleObject, profile)) {
          translations.set(outputLocaleTag, targetLocaleObject);
        } else {
          translations.remove(outputLocaleTag);
        }
      }
    }
  }

  private List<TextUnitDTO> searchSourceTextUnits(Repository repository, String assetPath) {
    TextUnitSearcherParameters parameters = new TextUnitSearcherParameters();
    parameters.setRepositoryIds(repository.getId());
    parameters.setLocaleTags(List.of(repositorySourceLocaleTag(repository)));
    parameters.setForRootLocale(true);
    parameters.setRootLocaleExcluded(false);
    parameters.setAssetPath(assetPath);
    parameters.setLimit(10_000);
    return textUnitSearcher.search(parameters);
  }

  private List<TextUnitDTO> searchTargetTextUnits(Repository repository, List<Long> tmTextUnitIds) {
    if (tmTextUnitIds.isEmpty()) {
      return List.of();
    }

    List<String> targetLocaleTags = targetLocaleTags(repository);
    if (targetLocaleTags.isEmpty()) {
      return List.of();
    }

    TextUnitSearcherParameters parameters = new TextUnitSearcherParameters();
    parameters.setRepositoryIds(repository.getId());
    parameters.setLocaleTags(targetLocaleTags);
    parameters.setTmTextUnitIds(tmTextUnitIds);
    parameters.setLimit(Math.max(10_000, tmTextUnitIds.size() * targetLocaleTags.size()));
    return textUnitSearcher.search(parameters);
  }

  private List<String> targetLocaleTags(Repository repository) {
    String sourceLocaleTag = repositorySourceLocaleTag(repository);
    return repository.getRepositoryLocales().stream()
        .map(RepositoryLocale::getLocale)
        .filter(locale -> locale != null && locale.getBcp47Tag() != null)
        .map(locale -> locale.getBcp47Tag())
        .filter(localeTag -> !localeTag.equals(sourceLocaleTag))
        .distinct()
        .sorted()
        .toList();
  }

  private String repositorySourceLocaleTag(Repository repository) {
    return repository.getSourceLocale() == null
            || repository.getSourceLocale().getBcp47Tag() == null
        ? "en"
        : repository.getSourceLocale().getBcp47Tag();
  }

  private SourceConfigProfile readProfile(String profileJson, String schemaJson) {
    if (profileJson != null && !profileJson.isBlank()) {
      try {
        return normalizeProfile(objectMapper.readValue(profileJson, SourceConfigProfile.class));
      } catch (IOException e) {
        throw new IllegalArgumentException("Invalid extraction mapping JSON: " + e.getMessage(), e);
      }
    }

    return detectProfile(schemaJson, new ArrayList<>());
  }

  private Map<String, String> readOutputLocaleMapping(String outputLocaleMappingJson) {
    if (outputLocaleMappingJson == null || outputLocaleMappingJson.isBlank()) {
      return Map.of();
    }

    try {
      return objectMapper.readValue(
          outputLocaleMappingJson, new TypeReference<LinkedHashMap<String, String>>() {});
    } catch (IOException e) {
      throw new IllegalArgumentException(
          "Invalid output locale mapping JSON: " + e.getMessage(), e);
    }
  }

  private Extraction extractStrings(
      JsonNode sourceConfig,
      SourceConfigProfile profile,
      SourceSchemaConstraints schemaConstraints,
      List<String> warnings) {
    SourceConfigProfile normalizedProfile = validateCompleteProfile(normalizeProfile(profile));
    if (normalizedProfile.format() == SourceConfigFormat.FLAT_SOURCE_ARRAY) {
      return extractFlatSourceArrayStrings(
          sourceConfig, normalizedProfile, schemaConstraints, warnings);
    }
    if (normalizedProfile.format() == SourceConfigFormat.FORMATJS_MAP) {
      return extractFormatJsMapStrings(
          sourceConfig, normalizedProfile, schemaConstraints, warnings);
    }

    ArrayNode collection = collectionNode(sourceConfig, normalizedProfile);
    if (collection == null) {
      throw new IllegalArgumentException(
          "Config collection must be an array: " + normalizedProfile.collectionKey());
    }

    List<JsonConfigString> strings = new ArrayList<>();
    for (int itemIndex = 0; itemIndex < collection.size(); itemIndex++) {
      JsonNode itemNode = collection.get(itemIndex);
      if (!(itemNode instanceof ObjectNode item)) {
        warnings.add("Skipped non-object item at index " + itemIndex + ".");
        continue;
      }

      String itemKey =
          resolveItemKey(
              item, normalizedProfile.collectionKey(), normalizedProfile.itemIdField(), itemIndex);
      ObjectNode translations = objectField(item, normalizedProfile.translationsField());
      ObjectNode sourceLocaleObject =
          objectField(translations, normalizedProfile.sourceLocaleTag());

      for (String field : normalizedProfile.translatableFields()) {
        JsonNode fieldNode = sourceLocaleObject.get(field);
        if (fieldNode == null) {
          warnings.add("Missing " + field + " for item " + itemKey + ".");
          continue;
        }
        if (!fieldNode.isTextual()) {
          warnings.add("Skipped non-string " + field + " for item " + itemKey + ".");
          continue;
        }

        String source = fieldNode.asText();
        if (source.isBlank() && schemaConstraints.embeddedNonEmptyFields().contains(field)) {
          warnings.add(stringId(itemKey, field) + " is missing non-empty " + field + "; skipped.");
          continue;
        }
        strings.add(new JsonConfigString(stringId(itemKey, field), source, "", true, false));
      }
    }

    strings.sort(Comparator.comparing(JsonConfigString::stringId));
    return new Extraction(strings, warnings);
  }

  private Extraction extractFlatSourceArrayStrings(
      JsonNode sourceConfig,
      SourceConfigProfile profile,
      SourceSchemaConstraints schemaConstraints,
      List<String> warnings) {
    ArrayNode collection = collectionNode(sourceConfig, profile);
    if (collection == null) {
      throw new IllegalArgumentException(
          "Config collection must be an array: " + profile.collectionKey());
    }

    List<JsonConfigString> strings = new ArrayList<>();
    Set<String> seenStringIds = new LinkedHashSet<>();
    for (int itemIndex = 0; itemIndex < collection.size(); itemIndex++) {
      JsonNode itemNode = collection.get(itemIndex);
      if (!(itemNode instanceof ObjectNode item)) {
        warnings.add("Skipped non-object item at index " + itemIndex + ".");
        continue;
      }

      String itemKey =
          resolveItemKey(item, profile.collectionKey(), profile.itemIdField(), itemIndex);
      if (!seenStringIds.add(itemKey)) {
        warnings.add("Duplicate string id \"" + itemKey + "\" skipped.");
        continue;
      }

      JsonNode sourceNode = item.get(profile.sourceField());
      if (sourceNode == null || !sourceNode.isTextual()) {
        warnings.add(itemKey + " is missing " + profile.sourceField() + "; skipped.");
        continue;
      }
      if (sourceNode.asText().isBlank()
          && schemaConstraints.flatNonEmptyFields().contains(profile.sourceField())) {
        warnings.add(itemKey + " is missing non-empty " + profile.sourceField() + "; skipped.");
        continue;
      }

      strings.add(
          new JsonConfigString(
              itemKey,
              sourceNode.asText(),
              optionalTextField(item, profile.commentField()),
              true,
              false));
    }

    strings.sort(Comparator.comparing(JsonConfigString::stringId));
    return new Extraction(strings, warnings);
  }

  private Extraction extractFormatJsMapStrings(
      JsonNode sourceConfig,
      SourceConfigProfile profile,
      SourceSchemaConstraints schemaConstraints,
      List<String> warnings) {
    ObjectNode sourceObject = formatJsMapNode(sourceConfig, profile);

    List<JsonConfigString> strings = new ArrayList<>();
    sourceObject
        .fields()
        .forEachRemaining(
            entry -> {
              String stringId = entry.getKey();
              JsonNode messageNode = entry.getValue();
              if (!(messageNode instanceof ObjectNode messageObject)) {
                warnings.add(stringId + " is not an object; skipped.");
                return;
              }

              JsonNode sourceNode = messageObject.get(profile.sourceField());
              if (sourceNode == null || !sourceNode.isTextual()) {
                warnings.add(stringId + " is missing " + profile.sourceField() + "; skipped.");
                return;
              }
              if (sourceNode.asText().isBlank()
                  && schemaConstraints.formatJsNonEmptyFields().contains(profile.sourceField())) {
                warnings.add(
                    stringId + " is missing non-empty " + profile.sourceField() + "; skipped.");
                return;
              }

              strings.add(
                  new JsonConfigString(
                      stringId,
                      sourceNode.asText(),
                      optionalTextField(messageObject, profile.commentField()),
                      true,
                      false));
            });

    strings.sort(Comparator.comparing(JsonConfigString::stringId));
    return new Extraction(strings, warnings);
  }

  private SourceSchemaConstraints sourceSchemaConstraints(
      String schemaText, SourceConfigProfile profile) {
    if (schemaText == null || schemaText.isBlank()) {
      return SourceSchemaConstraints.empty();
    }

    JsonNode schema;
    try {
      schema = readJson(schemaText, "schema");
    } catch (IllegalArgumentException e) {
      return SourceSchemaConstraints.empty();
    }

    SourceConfigProfile normalizedProfile = normalizeProfile(profile);
    if (normalizedProfile.format() == SourceConfigFormat.FLAT_SOURCE_ARRAY) {
      return new SourceSchemaConstraints(
          Set.of(), nonEmptyFlatSourceFields(schema, normalizedProfile), Set.of());
    }
    if (normalizedProfile.format() == SourceConfigFormat.FORMATJS_MAP) {
      return new SourceSchemaConstraints(
          Set.of(), Set.of(), nonEmptyFormatJsSourceFields(schema, normalizedProfile));
    }
    return new SourceSchemaConstraints(
        nonEmptyEmbeddedSourceFields(schema, normalizedProfile), Set.of(), Set.of());
  }

  private Set<String> nonEmptyFlatSourceFields(JsonNode schema, SourceConfigProfile profile) {
    ObjectNode itemProperties = schemaItemProperties(schema, profile.collectionKey());
    JsonNode sourceFieldSchema =
        itemProperties == null ? null : itemProperties.get(profile.sourceField());
    return stringFieldRequiresNonEmpty(sourceFieldSchema)
        ? Set.of(profile.sourceField())
        : Set.of();
  }

  private Set<String> nonEmptyFormatJsSourceFields(JsonNode schema, SourceConfigProfile profile) {
    ObjectNode messageProperties = formatJsMessageProperties(schema, profile.collectionKey());
    JsonNode sourceFieldSchema =
        messageProperties == null ? null : messageProperties.get(profile.sourceField());
    return stringFieldRequiresNonEmpty(sourceFieldSchema)
        ? Set.of(profile.sourceField())
        : Set.of();
  }

  private Set<String> nonEmptyEmbeddedSourceFields(JsonNode schema, SourceConfigProfile profile) {
    ObjectNode itemProperties = schemaItemProperties(schema, profile.collectionKey());
    ObjectNode translationsProperties =
        childObjectNode(
            objectNodeOrNull(
                itemProperties == null ? null : itemProperties.get(profile.translationsField())),
            "properties");
    ObjectNode sourceLocaleProperties =
        childObjectNode(
            objectNodeOrNull(
                translationsProperties == null
                    ? null
                    : translationsProperties.get(profile.sourceLocaleTag())),
            "properties");
    if (sourceLocaleProperties == null) {
      return Set.of();
    }

    return profile.translatableFields().stream()
        .filter(field -> stringFieldRequiresNonEmpty(sourceLocaleProperties.get(field)))
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  private ObjectNode schemaItemProperties(JsonNode schema, String collectionKey) {
    if (ROOT_ARRAY_COLLECTION_KEY.equals(collectionKey)) {
      return itemProperties(schema);
    }
    ObjectNode schemaObject = objectNodeOrNull(schema);
    ObjectNode properties = childObjectNode(schemaObject, "properties");
    return itemProperties(properties == null ? null : properties.get(collectionKey));
  }

  private ObjectNode formatJsMessageProperties(JsonNode schema, String collectionKey) {
    ObjectNode mapSchema = objectNodeOrNull(schema);
    if (mapSchema == null) {
      return null;
    }
    if (collectionKey != null && !collectionKey.isBlank()) {
      ObjectNode properties = childObjectNode(mapSchema, "properties");
      mapSchema = objectNodeOrNull(properties == null ? null : properties.get(collectionKey));
      if (mapSchema == null) {
        return null;
      }
    }

    ObjectNode additionalProperties = objectNodeOrNull(mapSchema.get("additionalProperties"));
    ObjectNode messageProperties = childObjectNode(additionalProperties, "properties");
    if (messageProperties != null) {
      return messageProperties;
    }
    return childObjectNode(mapSchema, "properties");
  }

  private boolean stringFieldRequiresNonEmpty(JsonNode fieldSchema) {
    JsonNode minLength = fieldSchema == null ? null : fieldSchema.get("minLength");
    return minLength != null && minLength.isIntegralNumber() && minLength.asInt() > 0;
  }

  private SourceConfigProfile detectProfile(String schemaText, List<String> warnings) {
    if (schemaText == null || schemaText.isBlank()) {
      warnings.add("Schema not provided; mapping will be inferred from the config.");
      return DEFAULT_PROFILE;
    }

    JsonNode schema;
    try {
      schema = readJson(schemaText, "schema");
    } catch (IllegalArgumentException e) {
      warnings.add("Schema not used for detection: " + e.getMessage());
      return DEFAULT_PROFILE;
    }

    if (schema instanceof ObjectNode schemaObject
        && "array".equals(text(schemaObject.get("type")))) {
      ObjectNode rootArrayItemProperties = itemProperties(schemaObject);
      if (rootArrayItemProperties == null) {
        warnings.add("Root array schema has no object item properties; using config inference.");
        return DEFAULT_PROFILE;
      }
      return detectProfileFromItemProperties(
          ROOT_ARRAY_COLLECTION_KEY, rootArrayItemProperties, warnings);
    }

    if (!(schema instanceof ObjectNode schemaObject)) {
      warnings.add("Schema root is not an object; using config inference.");
      return DEFAULT_PROFILE;
    }

    ObjectNode properties = objectNodeOrNull(schema.get("properties"));
    if (properties == null) {
      warnings.add("Schema has no top-level properties; using config inference.");
      return DEFAULT_PROFILE;
    }

    String collectionKey = detectCollectionKey(properties).orElse(DEFAULT_PROFILE.collectionKey());
    ObjectNode itemProperties = itemProperties(properties.get(collectionKey));
    if (itemProperties == null) {
      warnings.add("Schema collection has no object item properties; using config inference.");
      return new SourceConfigProfile(
          collectionKey,
          DEFAULT_ITEM_ID_FIELD,
          DEFAULT_TRANSLATIONS_FIELD,
          DEFAULT_SOURCE_LOCALE_TAG,
          DEFAULT_PROFILE.translatableFields());
    }

    return detectProfileFromItemProperties(collectionKey, itemProperties, warnings);
  }

  private SourceConfigProfile detectProfileFromItemProperties(
      String collectionKey, ObjectNode itemProperties, List<String> warnings) {
    String translationsField =
        detectTranslationsField(itemProperties).orElse(DEFAULT_TRANSLATIONS_FIELD);
    ObjectNode translationsProperties =
        childObjectNode(objectNodeOrNull(itemProperties.get(translationsField)), "properties");
    String sourceLocaleTag =
        detectSourceLocaleTag(translationsProperties).orElse(DEFAULT_SOURCE_LOCALE_TAG);
    List<String> translatableFields =
        detectTranslatableFields(translationsProperties, sourceLocaleTag).orElse(List.of());
    String itemIdField =
        detectItemIdField(itemProperties, translationsField, translatableFields)
            .orElse(DEFAULT_ITEM_ID_FIELD);

    if (DEFAULT_ITEM_ID_FIELD.equals(itemIdField) && !itemProperties.has(DEFAULT_ITEM_ID_FIELD)) {
      warnings.add(
          "Schema item has no stable string id field; defaulted item id field to \"id\". "
              + "Config items without id will use index-based string ids.");
    }

    return new SourceConfigProfile(
        collectionKey, itemIdField, translationsField, sourceLocaleTag, translatableFields);
  }

  private Optional<String> detectCollectionKey(ObjectNode properties) {
    List<String> candidates = new ArrayList<>();
    properties
        .fields()
        .forEachRemaining(
            entry -> {
              JsonNode property = entry.getValue();
              if ("array".equals(text(property.get("type")))) {
                candidates.add(entry.getKey());
              }
            });
    if (candidates.contains("items")) {
      return Optional.of("items");
    }
    return candidates.stream().findFirst();
  }

  private ObjectNode itemProperties(JsonNode collectionProperty) {
    if (collectionProperty == null) {
      return null;
    }
    JsonNode items = collectionProperty.get("items");
    if (items == null) {
      return null;
    }
    return objectNodeOrNull(items.get("properties"));
  }

  private Optional<String> detectTranslationsField(ObjectNode itemProperties) {
    List<String> candidates = new ArrayList<>();
    itemProperties
        .fields()
        .forEachRemaining(
            entry -> {
              JsonNode property = entry.getValue();
              JsonNode propertyProperties = property.get("properties");
              if ("object".equals(text(property.get("type")))
                  && propertyProperties != null
                  && propertyProperties.isObject()
                  && hasLocaleLikeProperty((ObjectNode) propertyProperties)) {
                candidates.add(entry.getKey());
              }
            });
    if (candidates.contains(DEFAULT_PROFILE.translationsField())) {
      return Optional.of(DEFAULT_PROFILE.translationsField());
    }
    return candidates.stream().findFirst();
  }

  private boolean hasLocaleLikeProperty(ObjectNode properties) {
    return properties.properties().stream().anyMatch(entry -> looksLikeLocaleTag(entry.getKey()));
  }

  private Optional<String> detectSourceLocaleTag(ObjectNode translationsProperties) {
    if (translationsProperties == null) {
      return Optional.empty();
    }
    if (translationsProperties.has(DEFAULT_PROFILE.sourceLocaleTag())) {
      return Optional.of(DEFAULT_PROFILE.sourceLocaleTag());
    }
    return translationsProperties.properties().stream()
        .map(Map.Entry::getKey)
        .filter(this::looksLikeLocaleTag)
        .findFirst();
  }

  private Optional<List<String>> detectTranslatableFields(
      ObjectNode translationsProperties, String sourceLocaleTag) {
    if (translationsProperties == null) {
      return Optional.empty();
    }
    ObjectNode sourceLocaleProperties =
        childObjectNode(
            objectNodeOrNull(translationsProperties.get(sourceLocaleTag)), "properties");
    if (sourceLocaleProperties == null) {
      return Optional.empty();
    }

    List<String> fields =
        sourceLocaleProperties.properties().stream()
            .filter(entry -> "string".equals(text(entry.getValue().get("type"))))
            .map(Map.Entry::getKey)
            .toList();
    return fields.isEmpty() ? Optional.empty() : Optional.of(fields);
  }

  private Optional<String> detectItemIdField(
      ObjectNode itemProperties, String translationsField, List<String> translatableFields) {
    List<String> preferredFieldNames = List.of("id", "key", "name", "slug");
    for (String preferredFieldName : preferredFieldNames) {
      JsonNode property = itemProperties.get(preferredFieldName);
      if (property != null && "string".equals(text(property.get("type")))) {
        return Optional.of(preferredFieldName);
      }
    }

    return itemProperties.properties().stream()
        .filter(entry -> !entry.getKey().equals(translationsField))
        .filter(entry -> !translatableFields.contains(entry.getKey()))
        .filter(entry -> "string".equals(text(entry.getValue().get("type"))))
        .map(Map.Entry::getKey)
        .findFirst();
  }

  private SourceConfigProfile applyOverrides(
      SourceConfigProfile detectedProfile, SourceConfigProfile profileOverride) {
    if (profileOverride == null) {
      return normalizeProfile(detectedProfile);
    }

    SourceConfigFormat format =
        profileOverride.format() == null ? detectedProfile.format() : profileOverride.format();
    SourceConfigProfile base =
        format == detectedProfile.format() ? detectedProfile : profileDefaults(format);

    return normalizeProfile(
        new SourceConfigProfile(
            format,
            firstNonBlank(profileOverride.collectionKey(), base.collectionKey()),
            firstNonBlank(profileOverride.itemIdField(), base.itemIdField()),
            firstNonBlank(profileOverride.translationsField(), base.translationsField()),
            firstNonBlank(profileOverride.sourceLocaleTag(), base.sourceLocaleTag()),
            profileOverride.translatableFields() == null
                    || profileOverride.translatableFields().isEmpty()
                ? base.translatableFields()
                : profileOverride.translatableFields(),
            firstNonBlank(profileOverride.sourceField(), base.sourceField()),
            firstNonBlank(profileOverride.commentField(), base.commentField())));
  }

  private SourceConfigProfile normalizeProfile(SourceConfigProfile profile) {
    if (profile == null) {
      return DEFAULT_PROFILE;
    }

    SourceConfigFormat format =
        profile.format() == null ? DEFAULT_PROFILE.format() : profile.format();
    SourceConfigProfile defaults = profileDefaults(format);
    SourceConfigProfile normalized =
        new SourceConfigProfile(
            format,
            firstNonBlank(profile.collectionKey(), defaults.collectionKey()),
            firstNonBlank(profile.itemIdField(), DEFAULT_ITEM_ID_FIELD),
            firstNonBlank(profile.translationsField(), DEFAULT_TRANSLATIONS_FIELD),
            firstNonBlank(profile.sourceLocaleTag(), DEFAULT_SOURCE_LOCALE_TAG),
            profile.translatableFields() == null
                ? List.of()
                : profile.translatableFields().stream()
                    .map(String::trim)
                    .filter(field -> !field.isBlank())
                    .distinct()
                    .toList(),
            firstNonBlank(profile.sourceField(), defaults.sourceField()),
            firstNonBlank(profile.commentField(), defaults.commentField()));

    return normalized;
  }

  private SourceConfigProfile validateCompleteProfile(SourceConfigProfile profile) {
    SourceConfigProfile normalized = normalizeProfile(profile);
    if (normalized.format() == SourceConfigFormat.FORMATJS_MAP) {
      if (normalized.sourceField().isBlank()) {
        throw new IllegalArgumentException("Source field is required.");
      }
      return normalized;
    }

    if (normalized.format() == SourceConfigFormat.FLAT_SOURCE_ARRAY) {
      if (normalized.collectionKey().isBlank()) {
        throw new IllegalArgumentException(
            "Collection key is required. Paste a schema or config with a translatable collection or provide a mapping.");
      }
      if (normalized.itemIdField().isBlank()) {
        throw new IllegalArgumentException("Item id field is required.");
      }
      if (normalized.sourceField().isBlank()) {
        throw new IllegalArgumentException("Source field is required.");
      }
      return normalized;
    }

    if (normalized.collectionKey().isBlank()) {
      throw new IllegalArgumentException(
          "Collection key is required. Paste a schema or config with a translatable collection or provide a mapping.");
    }
    if (normalized.translationsField().isBlank()) {
      throw new IllegalArgumentException("Translations field is required.");
    }
    if (normalized.sourceLocaleTag().isBlank()) {
      throw new IllegalArgumentException("Source locale is required.");
    }
    if (normalized.translatableFields().isEmpty()) {
      throw new IllegalArgumentException("At least one translatable field is required.");
    }

    return normalized;
  }

  private SourceConfigProfile inferProfileFromSource(
      JsonNode sourceConfig, SourceConfigProfile partialProfile, List<String> warnings) {
    SourceConfigProfile normalizedProfile = normalizeProfile(partialProfile);
    if (normalizedProfile.format() == SourceConfigFormat.FORMATJS_MAP) {
      return validateCompleteProfile(normalizedProfile);
    }

    String collectionKey = normalizedProfile.collectionKey();
    ArrayNode collection = null;

    if (collectionKey.isBlank()) {
      Optional<SourceCollectionCandidate> collectionCandidate =
          detectCollectionFromSource(sourceConfig, normalizedProfile);
      if (collectionCandidate.isPresent()) {
        SourceCollectionCandidate candidate = collectionCandidate.get();
        collectionKey = candidate.collectionKey();
        collection = candidate.collection();
        warnings.add("Inferred collection key \"" + collectionKey + "\" from config.");
      }
    } else {
      collection = collectionNode(sourceConfig, normalizedProfile);
    }

    if (normalizedProfile.format() == SourceConfigFormat.EMBEDDED_TRANSLATIONS) {
      Optional<SourceConfigProfile> formatJsProfile =
          inferFormatJsProfileFromSource(sourceConfig, normalizedProfile);
      if (formatJsProfile.isPresent()
          && (collection == null || !collectionLooksTranslatable(collection, normalizedProfile))) {
        warnings.add("Inferred FormatJS message map from config.");
        return validateCompleteProfile(formatJsProfile.get());
      }

      Optional<String> flatSourceField = detectSourceFieldFromFlatCollection(collection);
      if (collection != null
          && flatSourceField.isPresent()
          && !collectionLooksTranslatable(collection, normalizedProfile)) {
        warnings.add("Inferred flat source array from config.");
        return validateCompleteProfile(
            new SourceConfigProfile(
                SourceConfigFormat.FLAT_SOURCE_ARRAY,
                collectionKey,
                firstNonBlank(normalizedProfile.itemIdField(), DEFAULT_ITEM_ID_FIELD),
                DEFAULT_TRANSLATIONS_FIELD,
                DEFAULT_SOURCE_LOCALE_TAG,
                List.of(),
                flatSourceField.get(),
                firstNonBlank(normalizedProfile.commentField(), DEFAULT_COMMENT_FIELD)));
      }
    }

    ObjectNode sampleItem = firstObjectItem(collection);
    String translationsField = normalizedProfile.translationsField();
    if (sampleItem != null
        && (translationsField.isBlank()
            || !(sampleItem.get(translationsField) instanceof ObjectNode))) {
      translationsField =
          detectTranslationsFieldFromSource(sampleItem)
              .orElse(normalizedProfile.translationsField());
    }
    translationsField = firstNonBlank(translationsField, DEFAULT_TRANSLATIONS_FIELD);

    ObjectNode translations =
        sampleItem == null ? null : objectNodeOrNull(sampleItem.get(translationsField));
    String sourceLocaleTag = normalizedProfile.sourceLocaleTag();
    if (translations != null
        && (sourceLocaleTag.isBlank()
            || !(translations.get(sourceLocaleTag) instanceof ObjectNode))) {
      sourceLocaleTag =
          detectSourceLocaleTagFromSource(translations).orElse(normalizedProfile.sourceLocaleTag());
    }
    sourceLocaleTag = firstNonBlank(sourceLocaleTag, DEFAULT_SOURCE_LOCALE_TAG);

    List<String> translatableFields = normalizedProfile.translatableFields();
    if (translatableFields.isEmpty()) {
      ObjectNode sourceLocale =
          translations == null ? null : objectNodeOrNull(translations.get(sourceLocaleTag));
      translatableFields = detectTranslatableFieldsFromSource(sourceLocale);
      if (!translatableFields.isEmpty()) {
        warnings.add(
            "Inferred translatable fields "
                + String.join(", ", translatableFields)
                + " from config.");
      }
    }

    return validateCompleteProfile(
        new SourceConfigProfile(
            normalizedProfile.format(),
            collectionKey,
            normalizedProfile.itemIdField(),
            translationsField,
            sourceLocaleTag,
            translatableFields,
            normalizedProfile.sourceField(),
            normalizedProfile.commentField()));
  }

  private Optional<SourceConfigProfile> inferFormatJsProfileFromSource(
      JsonNode sourceConfig, SourceConfigProfile profile) {
    String commentField = firstNonBlank(profile.commentField(), DEFAULT_COMMENT_FIELD);
    if (!(sourceConfig instanceof ObjectNode sourceObject)) {
      return Optional.empty();
    }

    for (String sourceField : formatJsSourceFieldCandidates(profile.sourceField())) {
      if (!profile.collectionKey().isBlank()
          && sourceObject.get(profile.collectionKey()) instanceof ObjectNode nestedMap
          && objectLooksLikeFormatJsMap(nestedMap, sourceField)) {
        return Optional.of(
            new SourceConfigProfile(
                SourceConfigFormat.FORMATJS_MAP,
                profile.collectionKey(),
                DEFAULT_ITEM_ID_FIELD,
                DEFAULT_TRANSLATIONS_FIELD,
                DEFAULT_SOURCE_LOCALE_TAG,
                List.of(),
                sourceField,
                commentField));
      }

      if (objectLooksLikeFormatJsMap(sourceObject, sourceField)) {
        return Optional.of(
            new SourceConfigProfile(
                SourceConfigFormat.FORMATJS_MAP,
                "",
                DEFAULT_ITEM_ID_FIELD,
                DEFAULT_TRANSLATIONS_FIELD,
                DEFAULT_SOURCE_LOCALE_TAG,
                List.of(),
                sourceField,
                commentField));
      }
    }

    return Optional.empty();
  }

  private List<String> formatJsSourceFieldCandidates(String configuredSourceField) {
    return List.of(DEFAULT_FORMATJS_SOURCE_FIELD, configuredSourceField, DEFAULT_SOURCE_FIELD)
        .stream()
        .filter(Objects::nonNull)
        .map(String::trim)
        .filter(field -> !field.isBlank())
        .distinct()
        .toList();
  }

  private boolean objectLooksLikeFormatJsMap(ObjectNode sourceObject, String sourceField) {
    if (sourceObject.isEmpty()) {
      return false;
    }

    return sourceObject.properties().stream()
        .map(Map.Entry::getValue)
        .filter(ObjectNode.class::isInstance)
        .map(ObjectNode.class::cast)
        .anyMatch(
            message -> message.get(sourceField) != null && message.get(sourceField).isTextual());
  }

  private SourceConfigProfile profileDefaults(SourceConfigFormat format) {
    if (format == SourceConfigFormat.FORMATJS_MAP) {
      return new SourceConfigProfile(
          SourceConfigFormat.FORMATJS_MAP,
          "",
          DEFAULT_ITEM_ID_FIELD,
          DEFAULT_TRANSLATIONS_FIELD,
          DEFAULT_SOURCE_LOCALE_TAG,
          List.of(),
          DEFAULT_FORMATJS_SOURCE_FIELD,
          DEFAULT_COMMENT_FIELD);
    }
    if (format == SourceConfigFormat.FLAT_SOURCE_ARRAY) {
      return new SourceConfigProfile(
          SourceConfigFormat.FLAT_SOURCE_ARRAY,
          "",
          DEFAULT_ITEM_ID_FIELD,
          DEFAULT_TRANSLATIONS_FIELD,
          DEFAULT_SOURCE_LOCALE_TAG,
          List.of(),
          DEFAULT_SOURCE_FIELD,
          DEFAULT_COMMENT_FIELD);
    }
    return DEFAULT_PROFILE;
  }

  private Optional<SourceCollectionCandidate> detectCollectionFromSource(
      JsonNode sourceConfig, SourceConfigProfile profile) {
    if (sourceConfig instanceof ArrayNode rootArray) {
      return Optional.of(new SourceCollectionCandidate(ROOT_ARRAY_COLLECTION_KEY, rootArray));
    }

    if (!(sourceConfig instanceof ObjectNode sourceObject)) {
      return Optional.empty();
    }

    List<SourceCollectionCandidate> arrayCandidates = new ArrayList<>();
    sourceObject
        .fields()
        .forEachRemaining(
            entry -> {
              if (entry.getValue() instanceof ArrayNode arrayNode) {
                arrayCandidates.add(new SourceCollectionCandidate(entry.getKey(), arrayNode));
              }
            });

    Optional<SourceCollectionCandidate> translationCandidate =
        arrayCandidates.stream()
            .filter(candidate -> collectionLooksTranslatable(candidate.collection(), profile))
            .findFirst();
    return translationCandidate.or(() -> arrayCandidates.stream().findFirst());
  }

  private boolean collectionLooksTranslatable(ArrayNode collection, SourceConfigProfile profile) {
    ObjectNode sampleItem = firstObjectItem(collection);
    if (sampleItem == null) {
      return false;
    }

    String translationsField =
        firstNonBlank(profile.translationsField(), DEFAULT_TRANSLATIONS_FIELD);
    JsonNode translationsNode = sampleItem.get(translationsField);
    ObjectNode translations = objectNodeOrNull(translationsNode);
    if (translations == null) {
      Optional<String> detectedTranslationsField = detectTranslationsFieldFromSource(sampleItem);
      if (detectedTranslationsField.isEmpty()) {
        return false;
      }
      translations = objectNodeOrNull(sampleItem.get(detectedTranslationsField.get()));
    }
    return translations != null && detectSourceLocaleTagFromSource(translations).isPresent();
  }

  private Optional<String> detectTranslationsFieldFromSource(ObjectNode item) {
    JsonNode preferredTranslations = item.get(DEFAULT_TRANSLATIONS_FIELD);
    if (preferredTranslations instanceof ObjectNode preferredTranslationsObject
        && detectSourceLocaleTagFromSource(preferredTranslationsObject).isPresent()) {
      return Optional.of(DEFAULT_TRANSLATIONS_FIELD);
    }

    return item.properties().stream()
        .filter(entry -> entry.getValue() instanceof ObjectNode)
        .filter(entry -> detectSourceLocaleTagFromSource((ObjectNode) entry.getValue()).isPresent())
        .map(Map.Entry::getKey)
        .findFirst();
  }

  private Optional<String> detectSourceFieldFromFlatCollection(ArrayNode collection) {
    ObjectNode sampleItem = firstObjectItem(collection);
    if (sampleItem == null || detectTranslationsFieldFromSource(sampleItem).isPresent()) {
      return Optional.empty();
    }

    return detectSourceFieldFromFlatItem(sampleItem);
  }

  private Optional<String> detectSourceFieldFromFlatItem(ObjectNode item) {
    for (String preferredField : List.of("source", "defaultMessage", "message", "text")) {
      JsonNode fieldNode = item.get(preferredField);
      if (fieldNode != null && fieldNode.isTextual()) {
        return Optional.of(preferredField);
      }
    }

    return item.properties().stream()
        .filter(entry -> entry.getValue().isTextual())
        .map(Map.Entry::getKey)
        .filter(field -> !List.of("id", "key", "name", "description", "comment").contains(field))
        .findFirst();
  }

  private Optional<String> detectSourceLocaleTagFromSource(ObjectNode translations) {
    if (translations.get(DEFAULT_SOURCE_LOCALE_TAG) instanceof ObjectNode) {
      return Optional.of(DEFAULT_SOURCE_LOCALE_TAG);
    }
    return translations.properties().stream()
        .map(Map.Entry::getKey)
        .filter(this::looksLikeLocaleTag)
        .findFirst();
  }

  private List<String> detectTranslatableFieldsFromSource(ObjectNode sourceLocale) {
    if (sourceLocale == null) {
      return List.of();
    }
    return sourceLocale.properties().stream()
        .filter(entry -> entry.getValue().isTextual())
        .map(Map.Entry::getKey)
        .toList();
  }

  private ObjectNode firstObjectItem(ArrayNode collection) {
    if (collection == null) {
      return null;
    }
    for (JsonNode item : collection) {
      if (item instanceof ObjectNode itemObject) {
        return itemObject;
      }
    }
    return null;
  }

  private ArrayNode collectionNode(JsonNode sourceConfig, SourceConfigProfile profile) {
    if (ROOT_ARRAY_COLLECTION_KEY.equals(profile.collectionKey())
        && sourceConfig instanceof ArrayNode rootArray) {
      return rootArray;
    }
    if (!(sourceConfig instanceof ObjectNode sourceObject)) {
      return null;
    }
    JsonNode collectionNode = sourceObject.get(profile.collectionKey());
    return collectionNode instanceof ArrayNode arrayNode ? arrayNode : null;
  }

  private ObjectNode formatJsMapNode(JsonNode sourceConfig, SourceConfigProfile profile) {
    if (!(sourceConfig instanceof ObjectNode sourceObject)) {
      throw new IllegalArgumentException("Config root must be an object for FormatJS message map.");
    }
    if (profile.collectionKey().isBlank()) {
      return sourceObject;
    }
    JsonNode nestedMap = sourceObject.get(profile.collectionKey());
    if (nestedMap instanceof ObjectNode nestedMapObject) {
      return nestedMapObject;
    }
    throw new IllegalArgumentException(
        "Config must contain a FormatJS message map object at " + profile.collectionKey() + ".");
  }

  private List<VirtualAssetTextUnit> toVirtualAssetTextUnits(List<JsonConfigString> strings) {
    return strings.stream()
        .map(
            string -> {
              VirtualAssetTextUnit textUnit = new VirtualAssetTextUnit();
              textUnit.setName(string.stringId());
              textUnit.setContent(string.source());
              textUnit.setComment(string.comment());
              textUnit.setDoNotTranslate(string.doNotTranslate());
              return textUnit;
            })
        .toList();
  }

  private List<JsonConfigString> normalizeInputStrings(
      List<JsonConfigString> strings, SourceConfigProfile profile) {
    return strings.stream()
        .filter(JsonConfigString::used)
        .map(
            string -> {
              String stringId = nullToBlank(string.stringId()).trim();
              if (stringId.isBlank()) {
                throw new IllegalArgumentException("Source string id is required.");
              }

              return new JsonConfigString(
                  stringId,
                  string.source() == null ? "" : string.source(),
                  string.comment() == null ? "" : string.comment(),
                  true,
                  string.doNotTranslate());
            })
        .sorted(Comparator.comparing(JsonConfigString::stringId))
        .toList();
  }

  private void validateInputStringsExistInSourceConfig(
      List<JsonConfigString> inputStrings, List<JsonConfigString> extractedStrings) {
    Set<String> extractedStringIds =
        extractedStrings.stream().map(JsonConfigString::stringId).collect(Collectors.toSet());
    List<String> unmatchedStringIds =
        inputStrings.stream()
            .map(JsonConfigString::stringId)
            .filter(stringId -> !extractedStringIds.contains(stringId))
            .limit(5)
            .toList();

    if (!unmatchedStringIds.isEmpty()) {
      throw new IllegalArgumentException(
          "Active string ids must exist in Config JSON. Edit Config and extract again, or remove"
              + " from bundle: "
              + String.join(", ", unmatchedStringIds)
              + ".");
    }
  }

  private Repository findRepository(Long repositoryId) {
    return repositoryRepository
        .findById(repositoryId)
        .orElseThrow(() -> new IllegalArgumentException("Repository not found: " + repositoryId));
  }

  private JsonNode readSourceConfig(String json, List<String> warnings) {
    JsonNode sourceConfig = readJson(json, "config");
    JsonNode unwrappedSourceConfig = unwrapSourceConfig(sourceConfig, warnings);
    if (unwrappedSourceConfig instanceof ObjectNode || unwrappedSourceConfig instanceof ArrayNode) {
      return unwrappedSourceConfig;
    }
    throw new IllegalArgumentException(
        "Invalid config JSON: root must be an object or array after unwrapping.");
  }

  private JsonNode unwrapSourceConfig(JsonNode sourceConfig, List<String> warnings) {
    JsonNode current = sourceConfig;
    if (current instanceof ObjectNode objectNode) {
      if (objectNode.get("data") instanceof ObjectNode dataObject) {
        Optional<JsonNode> wrappedDataSource = sourceConfigPayloadCandidate(dataObject);
        if (wrappedDataSource.isPresent()) {
          current = wrappedDataSource.get();
          warnings.add("Unwrapped config from Statsig-style data payload.");
        }
      } else if (looksLikeStatsigConfigPayload(objectNode)) {
        Optional<JsonNode> wrappedSource = sourceConfigPayloadCandidate(objectNode);
        if (wrappedSource.isPresent()) {
          current = wrappedSource.get();
          warnings.add("Unwrapped config from Statsig-style response payload.");
        }
      }
    }

    if (current != null && current.isTextual()) {
      JsonNode parsed = readJson(current.asText(), "config string payload");
      warnings.add("Parsed config from JSON string payload.");
      return parsed;
    }

    return current;
  }

  private boolean looksLikeStatsigConfigPayload(ObjectNode objectNode) {
    return objectNode.has("defaultValueJson5")
        || objectNode.has("returnValueJson5")
        || objectNode.has("schemaJson5")
        || (objectNode.has("defaultValue")
            && (objectNode.has("id")
                || objectNode.has("name")
                || objectNode.has("rules")
                || objectNode.has("schema")
                || objectNode.has("permalink")));
  }

  private Optional<JsonNode> sourceConfigPayloadCandidate(ObjectNode objectNode) {
    for (String fieldName : List.of("defaultValue", "returnValue", "value")) {
      JsonNode value = objectNode.get(fieldName);
      if (value instanceof ObjectNode
          || value instanceof ArrayNode
          || (value != null && value.isTextual())) {
        return Optional.of(value);
      }
    }
    for (String fieldName : List.of("defaultValueJson5", "returnValueJson5", "valueJson5")) {
      JsonNode value = objectNode.get(fieldName);
      if (value != null && value.isTextual() && !value.asText().isBlank()) {
        return Optional.of(value);
      }
    }
    return Optional.empty();
  }

  private JsonNode readJson(String json, String label) {
    try {
      JsonParser parser = objectMapper.createParser(stripJsonTrailingCommas(json));
      parser.configure(JsonParser.Feature.ALLOW_COMMENTS, true);
      return parser.readValueAsTree();
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException(
          "Invalid " + label + " JSON: " + e.getOriginalMessage(), e);
    } catch (IOException e) {
      throw new IllegalArgumentException("Invalid " + label + " JSON: " + e.getMessage(), e);
    }
  }

  private ObjectNode readObject(String json, String label) {
    JsonNode node = readJson(json, label);
    if (!(node instanceof ObjectNode objectNode)) {
      throw new IllegalArgumentException("Invalid " + label + " JSON: root must be an object.");
    }
    return objectNode;
  }

  private String stripJsonTrailingCommas(String json) {
    StringBuilder result = new StringBuilder(json.length());
    boolean inString = false;
    boolean escaped = false;
    for (int index = 0; index < json.length(); index++) {
      char current = json.charAt(index);
      if (inString) {
        result.append(current);
        if (escaped) {
          escaped = false;
        } else if (current == '\\') {
          escaped = true;
        } else if (current == '"') {
          inString = false;
        }
        continue;
      }

      if (current == '"') {
        inString = true;
        result.append(current);
        continue;
      }

      if (current == ',') {
        int nextIndex = index + 1;
        while (nextIndex < json.length() && Character.isWhitespace(json.charAt(nextIndex))) {
          nextIndex++;
        }
        if (nextIndex < json.length()
            && (json.charAt(nextIndex) == '}' || json.charAt(nextIndex) == ']')) {
          continue;
        }
      }

      result.append(current);
    }
    return result.toString();
  }

  private ObjectNode objectNodeOrNull(JsonNode node) {
    return node instanceof ObjectNode objectNode ? objectNode : null;
  }

  private ObjectNode objectNodeOrNew(JsonNode node) {
    return node instanceof ObjectNode objectNode
        ? objectNode.deepCopy()
        : objectMapper.createObjectNode();
  }

  private ObjectNode childObjectNode(ObjectNode parent, String fieldName) {
    if (parent == null) {
      return null;
    }
    return objectNodeOrNull(parent.get(fieldName));
  }

  private ObjectNode objectField(ObjectNode parent, String fieldName) {
    JsonNode existing = parent.get(fieldName);
    if (existing instanceof ObjectNode objectNode) {
      return objectNode;
    }

    ObjectNode objectNode = objectMapper.createObjectNode();
    parent.set(fieldName, objectNode);
    return objectNode;
  }

  private void clearTranslatableFields(ObjectNode objectNode, List<String> translatableFields) {
    objectNode.remove(translatableFields);
  }

  private Map<String, String> translatableTextFields(
      ObjectNode objectNode, List<String> translatableFields) {
    Map<String, String> fields = new LinkedHashMap<>();
    for (String field : translatableFields) {
      JsonNode value = objectNode.get(field);
      if (value != null && value.isTextual()) {
        fields.put(field, value.asText());
      }
    }
    return fields;
  }

  private String optionalTextField(ObjectNode objectNode, String fieldName) {
    JsonNode node = objectNode.get(fieldName);
    return node != null && node.isTextual() ? node.asText() : "";
  }

  private boolean hasNonTranslatableFields(ObjectNode objectNode, SourceConfigProfile profile) {
    return objectNode.properties().stream()
        .anyMatch(entry -> !profile.translatableFields().contains(entry.getKey()));
  }

  private String resolveItemKey(
      ObjectNode item, String collectionKey, String itemIdField, int itemIndex) {
    JsonNode idNode = item.get(itemIdField);
    if (idNode != null && idNode.isTextual() && !idNode.asText().isBlank()) {
      return idNode.asText();
    }
    if (idNode != null && idNode.isNumber()) {
      return idNode.asText();
    }
    return collectionKey + "." + itemIndex;
  }

  private String stringId(String itemKey, String field) {
    return itemKey + "." + field;
  }

  private String targetKey(Long tmTextUnitId, String localeTag) {
    return tmTextUnitId + "\u0000" + localeTag;
  }

  private String text(JsonNode node) {
    return node != null && node.isTextual() ? node.asText() : null;
  }

  private boolean looksLikeLocaleTag(String value) {
    return value.matches("^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$");
  }

  private String firstNonBlank(String first, String fallback) {
    return first == null || first.isBlank() ? fallback : first.trim();
  }

  private String firstText(String first, String fallback) {
    if (first != null) {
      return first;
    }
    return fallback;
  }

  private String printableCollectionKey(String collectionKey) {
    return collectionKey == null || collectionKey.isBlank()
        ? "the configured collection"
        : collectionKey;
  }

  private String nullToBlank(String value) {
    return value == null ? "" : value;
  }

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Unable to write JSON: " + e.getMessage(), e);
    }
  }

  private String prettyJson(Object value) {
    try {
      return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Unable to write JSON: " + e.getMessage(), e);
    }
  }

  private List<String> dedupeWarnings(List<String> warnings) {
    return new ArrayList<>(new LinkedHashSet<>(warnings));
  }

  private record SourceCollectionCandidate(String collectionKey, ArrayNode collection) {}

  private record Extraction(List<JsonConfigString> strings, List<String> warnings) {}

  private record SourceSchemaConstraints(
      Set<String> embeddedNonEmptyFields,
      Set<String> flatNonEmptyFields,
      Set<String> formatJsNonEmptyFields) {
    private static SourceSchemaConstraints empty() {
      return new SourceSchemaConstraints(Set.of(), Set.of(), Set.of());
    }
  }

  public enum SourceConfigFormat {
    EMBEDDED_TRANSLATIONS,
    FLAT_SOURCE_ARRAY,
    FORMATJS_MAP
  }

  public record SourceConfigProfile(
      SourceConfigFormat format,
      String collectionKey,
      String itemIdField,
      String translationsField,
      String sourceLocaleTag,
      List<String> translatableFields,
      String sourceField,
      String commentField) {

    public SourceConfigProfile(
        String collectionKey,
        String itemIdField,
        String translationsField,
        String sourceLocaleTag,
        List<String> translatableFields) {
      this(
          SourceConfigFormat.EMBEDDED_TRANSLATIONS,
          collectionKey,
          itemIdField,
          translationsField,
          sourceLocaleTag,
          translatableFields,
          DEFAULT_SOURCE_FIELD,
          DEFAULT_COMMENT_FIELD);
    }
  }

  public record JsonConfigString(
      String stringId, String source, String comment, boolean used, boolean doNotTranslate) {}

  public record DetectMappingInput(String schemaJson) {}

  public record DetectMappingResult(SourceConfigProfile profile, List<String> warnings) {}

  public record ExtractionInput(
      String schemaJson, String sourceConfigJson, SourceConfigProfile profile) {}

  public record ExtractionResult(
      SourceConfigProfile profile,
      String sourceConfigJson,
      List<JsonConfigString> strings,
      List<String> warnings) {}

  public record ExtractForRepositoryInput(
      String name,
      String assetPath,
      String provider,
      String providerConfigId,
      String schemaJson,
      String sourceConfigJson,
      SourceConfigProfile profile,
      List<JsonConfigString> strings,
      String outputLocaleMappingJson,
      ZonedDateTime expectedLastModifiedDate) {

    public ExtractForRepositoryInput(
        String name,
        String assetPath,
        String provider,
        String providerConfigId,
        String schemaJson,
        String sourceConfigJson,
        SourceConfigProfile profile,
        List<JsonConfigString> strings,
        String outputLocaleMappingJson) {
      this(
          name,
          assetPath,
          provider,
          providerConfigId,
          schemaJson,
          sourceConfigJson,
          profile,
          strings,
          outputLocaleMappingJson,
          null);
    }
  }

  public record ExtractForRepositoryResult(
      JsonConfigLocalizationService.JsonConfigLocalization setup,
      List<JsonConfigString> strings,
      List<String> warnings,
      PollableTask pollableTask) {}

  public record ExportResult(String json, List<String> warnings) {}

  public record TranslationScope(
      String repositoryName, List<String> targetLocaleTags, List<Long> tmTextUnitIds) {}
}
