package com.box.l10n.mojito.service.tm;

import com.box.l10n.mojito.entity.Asset;
import com.box.l10n.mojito.entity.AssetExtraction;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.localtm.merger.BranchStateTextUnit;
import com.box.l10n.mojito.localtm.merger.MultiBranchState;
import com.box.l10n.mojito.okapi.FilterConfigIdOverride;
import com.box.l10n.mojito.okapi.asset.AssetPathToFilterConfigMapper;
import com.box.l10n.mojito.okapi.asset.UnsupportedAssetFilterTypeException;
import com.box.l10n.mojito.okapi.extractor.AssetExtractor;
import com.box.l10n.mojito.okapi.extractor.AssetExtractorTextUnit;
import com.box.l10n.mojito.okapi.filters.JSONFilter;
import com.box.l10n.mojito.service.assetExtraction.LocalBranchToEntityBranchConverter;
import com.box.l10n.mojito.service.assetExtraction.MultiBranchStateService;
import com.box.l10n.mojito.service.branch.BranchRepository;
import com.box.l10n.mojito.utils.OptionsParser;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** Adds selected branch-only text units to JSON source before localization. */
@Service
public class NoSourceJsonAugmenter {

  static final String FORMAT_JS_SOURCE_FIELD = "defaultMessage";
  static final String FORMAT_JS_SOURCE_SUFFIX = "/" + FORMAT_JS_SOURCE_FIELD;

  private static final String REMOVE_KEY_SUFFIX_OPTION = "removeKeySuffix";
  private static final String NOTE_KEY_PATTERN_OPTION = "noteKeyPattern";
  private static final String EXTRACT_ALL_PAIRS_OPTION = "extractAllPairs";
  private static final String EXCEPTIONS_OPTION = "exceptions";
  private static final String CODE_FINDER_DATA_OPTION = "codeFinderData";
  private static final String CONVERT_TO_HTML_CODES_OPTION = "convertToHtmlCodes";
  private static final String USE_FULL_KEY_PATH_OPTION = "useFullKeyPath";
  private static final String USE_LEADING_SLASH_ON_KEY_PATH_OPTION = "useLeadingSlashOnKeyPath";
  private static final Pattern REGEX_META_CHARACTERS = Pattern.compile("[\\\\.^$|?*+()\\[\\]{}]");

  private final MultiBranchStateService multiBranchStateService;
  private final LocalBranchToEntityBranchConverter localBranchToEntityBranchConverter;
  private final BranchRepository branchRepository;
  private final AssetExtractor assetExtractor;
  private final AssetPathToFilterConfigMapper assetPathToFilterConfigMapper;
  private final ObjectMapper objectMapper;

  public NoSourceJsonAugmenter(
      MultiBranchStateService multiBranchStateService,
      LocalBranchToEntityBranchConverter localBranchToEntityBranchConverter,
      BranchRepository branchRepository,
      AssetExtractor assetExtractor,
      AssetPathToFilterConfigMapper assetPathToFilterConfigMapper,
      ObjectMapper objectMapper) {
    this.multiBranchStateService = multiBranchStateService;
    this.localBranchToEntityBranchConverter = localBranchToEntityBranchConverter;
    this.branchRepository = branchRepository;
    this.assetExtractor = assetExtractor;
    this.assetPathToFilterConfigMapper = assetPathToFilterConfigMapper;
    this.objectMapper = objectMapper;
  }

  /**
   * Adds text units used on the requested branches but absent from the local JSON source.
   *
   * <p>An empty branch list means every active branch in the current multi-branch state. A null
   * list entry selects Mojito's unnamed default branch, while the string "null" continues to mean a
   * branch literally named "null". An explicit branch list is processed in caller order. Existing
   * local text-unit names always win.
   */
  public String augment(
      Asset asset,
      String content,
      FilterConfigIdOverride filterConfigIdOverride,
      List<String> filterOptions,
      List<String> branchNames)
      throws UnsupportedAssetFilterTypeException {
    validateAsset(asset);
    validateRequestedBranches(asset, branchNames);

    AssetExtraction assetExtraction = asset.getLastSuccessfulAssetExtraction();
    if (assetExtraction == null) {
      return content;
    }

    MultiBranchState multiBranchState =
        multiBranchStateService.getMultiBranchStateForAssetExtractionId(
            assetExtraction.getId(), assetExtraction.getVersion());
    List<String> selectedBranches = selectedActiveBranches(multiBranchState, branchNames);
    if (selectedBranches.isEmpty()
        || !hasTextUnitsOnSelectedBranches(multiBranchState, selectedBranches)) {
      return content;
    }

    validateJsonFilter(asset, filterConfigIdOverride);
    ObjectNode root = readRootObject(content);
    LinkedHashMap<String, BranchStateTextUnit> textUnitsToAdd =
        selectTextUnitsToAdd(multiBranchState, selectedBranches, fieldNames(root));
    if (textUnitsToAdd.isEmpty()) {
      return content;
    }

    JsonShape jsonShape = getJsonShape(root, filterOptions, textUnitsToAdd.values());
    List<String> serializedEntries =
        textUnitsToAdd.values().stream()
            .map(textUnit -> serializeEntry(textUnit, jsonShape))
            .toList();

    String augmentedContent = appendRootEntries(content, root, serializedEntries);
    verifyTextUnitIdentity(
        asset, augmentedContent, filterConfigIdOverride, filterOptions, textUnitsToAdd.values());
    return augmentedContent;
  }

  private void validateAsset(Asset asset) {
    if (asset == null || asset.getPath() == null || asset.getRepository() == null) {
      throw new IllegalArgumentException("An asset with a path and repository must be provided");
    }
  }

  private void validateJsonFilter(Asset asset, FilterConfigIdOverride filterConfigIdOverride)
      throws UnsupportedAssetFilterTypeException {
    String filterConfigId =
        filterConfigIdOverride == null
            ? assetPathToFilterConfigMapper.getFilterConfigIdFromPath(asset.getPath())
            : filterConfigIdOverride.getOkapiFilterId();
    if (!JSONFilter.FILTER_CONFIG_ID.equals(filterConfigId)) {
      throw new UnsupportedAssetFilterTypeException(
          "Pulling branch-only text units is currently supported only for JSON assets");
    }
  }

  private ObjectNode readRootObject(String content) {
    if (content == null) {
      throw new IllegalArgumentException("JSON source content must be provided");
    }
    try (JsonParser parser = objectMapper.createParser(content)) {
      parser.enable(JsonParser.Feature.ALLOW_COMMENTS);
      parser.enable(JsonParser.Feature.ALLOW_TRAILING_COMMA);
      JsonNode root = parser.readValueAsTree();
      if (!(root instanceof ObjectNode objectNode)) {
        throw new IllegalArgumentException(
            "Pulling branch-only text units requires a JSON object at the document root");
      }
      return objectNode;
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException(
          "Cannot add branch-only text units to invalid JSON: " + e.getOriginalMessage(), e);
    } catch (IOException e) {
      throw new IllegalArgumentException(
          "Cannot add branch-only text units to invalid JSON: " + e.getMessage(), e);
    }
  }

  private List<String> selectedActiveBranches(
      MultiBranchState multiBranchState, List<String> requestedBranchNames) {
    LinkedHashSet<String> activeBranchNames = new LinkedHashSet<>();
    multiBranchState.getBranches().forEach(branch -> activeBranchNames.add(branch.getName()));

    if (requestedBranchNames == null || requestedBranchNames.isEmpty()) {
      return List.copyOf(activeBranchNames);
    }

    LinkedHashSet<String> selected = new LinkedHashSet<>();
    requestedBranchNames.stream()
        .map(localBranchToEntityBranchConverter::entityBranchNameToLocalBranchName)
        .filter(activeBranchNames::contains)
        .forEach(selected::add);
    return List.copyOf(selected);
  }

  private boolean hasTextUnitsOnSelectedBranches(
      MultiBranchState multiBranchState, List<String> selectedBranches) {
    return multiBranchState.getBranchStateTextUnits().stream()
        .anyMatch(
            textUnit ->
                selectedBranches.stream()
                    .anyMatch(textUnit.getBranchNameToBranchDatas()::containsKey));
  }

  private void validateRequestedBranches(Asset asset, List<String> requestedBranchNames) {
    if (requestedBranchNames == null || requestedBranchNames.isEmpty()) {
      return;
    }

    List<String> unknownBranchNames = new ArrayList<>();
    for (String requestedBranchName : requestedBranchNames) {
      String entityBranchName =
          localBranchToEntityBranchConverter.localBranchNameToEntityBranchName(requestedBranchName);
      com.box.l10n.mojito.entity.Branch branch =
          branchRepository.findByNameAndRepository(entityBranchName, asset.getRepository());
      if (branch == null || Boolean.TRUE.equals(branch.getDeleted())) {
        unknownBranchNames.add(String.valueOf(requestedBranchName));
      }
    }
    if (!unknownBranchNames.isEmpty()) {
      throw new IllegalArgumentException(
          "Requested branches are not active in repository "
              + asset.getRepository().getName()
              + ": "
              + String.join(", ", unknownBranchNames));
    }
  }

  private LinkedHashMap<String, BranchStateTextUnit> selectTextUnitsToAdd(
      MultiBranchState multiBranchState,
      List<String> selectedBranches,
      Set<String> localRootFieldNames) {
    LinkedHashMap<String, BranchStateTextUnit> selectedByName = new LinkedHashMap<>();

    for (String branchName : selectedBranches) {
      for (BranchStateTextUnit textUnit : multiBranchState.getBranchStateTextUnits()) {
        if (!textUnit.getBranchNameToBranchDatas().containsKey(branchName)) {
          continue;
        }
        String name = textUnit.getName();
        if (name == null || localRootFieldNames.contains(name)) {
          continue;
        }

        BranchStateTextUnit existing = selectedByName.get(name);
        if (existing == null) {
          selectedByName.put(name, textUnit);
        } else if (!Objects.equals(existing.getMd5(), textUnit.getMd5())) {
          throw new IllegalArgumentException(
              "Selected branches contain conflicting source strings for JSON key: " + name);
        }
      }
    }

    return selectedByName;
  }

  private Set<String> fieldNames(ObjectNode root) {
    Set<String> fieldNames = new LinkedHashSet<>();
    root.fieldNames().forEachRemaining(fieldNames::add);
    return fieldNames;
  }

  private JsonShape getJsonShape(
      ObjectNode root, List<String> filterOptions, Iterable<BranchStateTextUnit> textUnitsToAdd)
      throws UnsupportedAssetFilterTypeException {
    OptionsParser options = new OptionsParser(filterOptions);
    validateRoundTripOptions(options);
    String removeKeySuffix = options.getString(REMOVE_KEY_SUFFIX_OPTION, (String) null);

    if (removeKeySuffix == null) {
      if (!options.getBoolean(EXTRACT_ALL_PAIRS_OPTION, true)) {
        throw unsupportedJsonShape();
      }
      for (BranchStateTextUnit textUnit : textUnitsToAdd) {
        validateNonPlural(textUnit);
        validateRootTextUnitName(textUnit);
        if (textUnit.getComments() != null) {
          throw new IllegalArgumentException(
              "Flat JSON cannot preserve the source comment for branch-only key: "
                  + textUnit.getName());
        }
      }
      validateFlatRootMap(root);
      return new JsonShape(false, null);
    }

    if (!FORMAT_JS_SOURCE_SUFFIX.equals(removeKeySuffix)
        || options.getBoolean(EXTRACT_ALL_PAIRS_OPTION, true)) {
      throw unsupportedJsonShape();
    }

    String exceptions = options.getString(EXCEPTIONS_OPTION, (String) null);
    if (exceptions == null
        || !Pattern.compile(exceptions).matcher("message/" + FORMAT_JS_SOURCE_FIELD).find()) {
      throw unsupportedJsonShape();
    }

    String noteField = options.getString(NOTE_KEY_PATTERN_OPTION, (String) null);
    for (BranchStateTextUnit textUnit : textUnitsToAdd) {
      validateNonPlural(textUnit);
      validateRootTextUnitName(textUnit);
      if (textUnit.getComments() != null) {
        if (!isLiteralFieldName(noteField)) {
          throw new IllegalArgumentException(
              "FormatJS branch-only strings with comments require a literal noteKeyPattern");
        }
        if (FORMAT_JS_SOURCE_FIELD.equals(noteField)) {
          throw new IllegalArgumentException(
              "FormatJS noteKeyPattern cannot be " + FORMAT_JS_SOURCE_FIELD);
        }
      }
    }
    validateFormatJsRootMap(root);
    return new JsonShape(true, noteField);
  }

  private void validateRoundTripOptions(OptionsParser options)
      throws UnsupportedAssetFilterTypeException {
    if (Boolean.TRUE.equals(options.getBoolean(CONVERT_TO_HTML_CODES_OPTION, false))
        || options.getString(CODE_FINDER_DATA_OPTION, (String) null) != null
        || Boolean.FALSE.equals(options.getBoolean(USE_FULL_KEY_PATH_OPTION, true))
        || Boolean.TRUE.equals(options.getBoolean(USE_LEADING_SLASH_ON_KEY_PATH_OPTION, false))) {
      throw new UnsupportedAssetFilterTypeException(
          "Pulling branch-only text units does not support JSON options that transform source "
              + "content or text-unit names");
    }
  }

  private void validateFlatRootMap(ObjectNode root) throws UnsupportedAssetFilterTypeException {
    if (root.properties().stream().anyMatch(entry -> !entry.getValue().isTextual())) {
      throw new UnsupportedAssetFilterTypeException(
          "Pulling branch-only text units requires flat JSON string values at the document root");
    }
  }

  private void validateFormatJsRootMap(ObjectNode root) throws UnsupportedAssetFilterTypeException {
    boolean invalidEntry =
        root.properties().stream()
            .anyMatch(
                entry ->
                    !entry.getValue().isObject()
                        || !entry.getValue().has(FORMAT_JS_SOURCE_FIELD)
                        || !entry.getValue().get(FORMAT_JS_SOURCE_FIELD).isTextual());
    if (invalidEntry) {
      throw new UnsupportedAssetFilterTypeException(
          "Pulling branch-only FormatJS text units requires root entries with a textual "
              + FORMAT_JS_SOURCE_FIELD);
    }
  }

  private void validateRootTextUnitName(BranchStateTextUnit textUnit)
      throws UnsupportedAssetFilterTypeException {
    if (textUnit.getName().contains("/")) {
      throw new UnsupportedAssetFilterTypeException(
          "Cannot reconstruct a nested or path-like JSON key for branch-only text unit: "
              + textUnit.getName());
    }
  }

  private UnsupportedAssetFilterTypeException unsupportedJsonShape() {
    return new UnsupportedAssetFilterTypeException(
        "Pulling branch-only text units supports flat JSON or FormatJS JSON with "
            + "removeKeySuffix=/defaultMessage");
  }

  private void validateNonPlural(BranchStateTextUnit textUnit) {
    if (textUnit.getPluralForm() != null || textUnit.getPluralFormOther() != null) {
      throw new IllegalArgumentException(
          "Pulling branch-only plural text units is not supported for JSON key: "
              + textUnit.getName());
    }
    if (textUnit.getSource() == null) {
      throw new IllegalArgumentException(
          "Branch-only JSON text units must have source content: " + textUnit.getName());
    }
  }

  private boolean isLiteralFieldName(String fieldName) {
    return fieldName != null
        && !fieldName.isBlank()
        && !REGEX_META_CHARACTERS.matcher(fieldName).find();
  }

  private String serializeEntry(BranchStateTextUnit textUnit, JsonShape jsonShape) {
    JsonNode value;
    if (jsonShape.formatJs()) {
      ObjectNode message = objectMapper.createObjectNode();
      message.put(FORMAT_JS_SOURCE_FIELD, textUnit.getSource());
      if (textUnit.getComments() != null) {
        message.put(jsonShape.noteField(), textUnit.getComments());
      }
      value = message;
    } else {
      value = objectMapper.getNodeFactory().textNode(textUnit.getSource());
    }

    return objectMapper.writeValueAsStringUnchecked(textUnit.getName())
        + ":"
        + objectMapper.writeValueAsStringUnchecked(value);
  }

  private void verifyTextUnitIdentity(
      Asset asset,
      String augmentedContent,
      FilterConfigIdOverride filterConfigIdOverride,
      List<String> filterOptions,
      Iterable<BranchStateTextUnit> expectedTextUnits)
      throws UnsupportedAssetFilterTypeException {
    List<AssetExtractorTextUnit> extractedTextUnits =
        assetExtractor.getAssetExtractorTextUnitsForAsset(
            asset.getPath(), augmentedContent, filterConfigIdOverride, filterOptions);

    for (BranchStateTextUnit expected : expectedTextUnits) {
      boolean identityPreserved =
          extractedTextUnits.stream()
              .anyMatch(
                  actual ->
                      Objects.equals(expected.getName(), actual.getName())
                          && Objects.equals(expected.getSource(), actual.getSource())
                          && Objects.equals(expected.getComments(), actual.getComments())
                          && Objects.equals(expected.getPluralForm(), actual.getPluralForm())
                          && Objects.equals(
                              expected.getPluralFormOther(), actual.getPluralFormOther()));
      if (!identityPreserved) {
        throw new UnsupportedAssetFilterTypeException(
            "JSON filter options cannot reconstruct branch-only text unit without changing its "
                + "identity: "
                + expected.getName());
      }
    }
  }

  private String appendRootEntries(
      String content, ObjectNode root, List<String> serializedEntries) {
    RootObjectLayout layout = findRootObjectLayout(content);
    boolean hasExistingFields = !root.isEmpty();
    boolean alreadyHasTrailingComma =
        layout.lastTokenIndex() >= 0 && content.charAt(layout.lastTokenIndex()) == ',';
    int insertionIndex =
        layout.lastTokenIndex() >= 0 ? layout.lastTokenIndex() + 1 : layout.rootStartIndex() + 1;
    String newline = content.contains("\r\n") ? "\r\n" : "\n";

    StringBuilder addition = new StringBuilder();
    if (hasExistingFields && !alreadyHasTrailingComma) {
      addition.append(',');
    }
    addition.append(newline);
    for (int index = 0; index < serializedEntries.size(); index++) {
      if (index > 0) {
        addition.append(',').append(newline);
      }
      addition.append("  ").append(serializedEntries.get(index));
    }
    if (!containsLineBreak(content, insertionIndex, layout.rootEndIndex())) {
      addition.append(newline);
    }

    return content.substring(0, insertionIndex) + addition + content.substring(insertionIndex);
  }

  private boolean containsLineBreak(String content, int start, int end) {
    for (int index = start; index < end; index++) {
      char current = content.charAt(index);
      if (current == '\n' || current == '\r') {
        return true;
      }
    }
    return false;
  }

  private RootObjectLayout findRootObjectLayout(String content) {
    int rootStart = -1;
    int rootEnd = -1;
    int lastToken = -1;
    int nesting = 0;
    boolean inString = false;
    boolean escaped = false;
    boolean inLineComment = false;
    boolean inBlockComment = false;
    char quote = 0;

    for (int index = 0; index < content.length(); index++) {
      char current = content.charAt(index);
      char next = index + 1 < content.length() ? content.charAt(index + 1) : 0;

      if (inLineComment) {
        if (current == '\n' || current == '\r') {
          inLineComment = false;
        }
        continue;
      }
      if (inBlockComment) {
        if (current == '*' && next == '/') {
          inBlockComment = false;
          index++;
        }
        continue;
      }
      if (inString) {
        if (escaped) {
          escaped = false;
        } else if (current == '\\') {
          escaped = true;
        } else if (current == quote) {
          inString = false;
          if (nesting > 0) {
            lastToken = index;
          }
        }
        continue;
      }
      if (current == '/' && next == '/') {
        inLineComment = true;
        index++;
        continue;
      }
      if (current == '/' && next == '*') {
        inBlockComment = true;
        index++;
        continue;
      }
      if (current == '"' || current == '\'') {
        inString = true;
        quote = current;
        if (nesting > 0) {
          lastToken = index;
        }
        continue;
      }
      if (Character.isWhitespace(current)) {
        continue;
      }

      if (rootStart < 0) {
        if (current == '{') {
          rootStart = index;
          nesting = 1;
        }
        continue;
      }

      if (current == '{' || current == '[') {
        nesting++;
        lastToken = index;
      } else if (current == '}' || current == ']') {
        if (nesting == 1 && current == '}') {
          rootEnd = index;
          break;
        }
        nesting--;
        lastToken = index;
      } else {
        lastToken = index;
      }
    }

    if (rootStart < 0 || rootEnd < 0) {
      throw new IllegalArgumentException(
          "Pulling branch-only text units requires a JSON object at the document root");
    }
    return new RootObjectLayout(rootStart, rootEnd, lastToken);
  }

  private record JsonShape(boolean formatJs, String noteField) {}

  private record RootObjectLayout(int rootStartIndex, int rootEndIndex, int lastTokenIndex) {}
}
