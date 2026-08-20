package com.box.l10n.mojito.service.tm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.Asset;
import com.box.l10n.mojito.entity.AssetExtraction;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.localtm.merger.Branch;
import com.box.l10n.mojito.localtm.merger.BranchData;
import com.box.l10n.mojito.localtm.merger.BranchStateTextUnit;
import com.box.l10n.mojito.localtm.merger.MultiBranchState;
import com.box.l10n.mojito.okapi.FilterConfigIdOverride;
import com.box.l10n.mojito.okapi.asset.AssetPathToFilterConfigMapper;
import com.box.l10n.mojito.okapi.asset.UnsupportedAssetFilterTypeException;
import com.box.l10n.mojito.okapi.extractor.AssetExtractor;
import com.box.l10n.mojito.okapi.extractor.AssetExtractorTextUnit;
import com.box.l10n.mojito.service.assetExtraction.LocalBranchToEntityBranchConverter;
import com.box.l10n.mojito.service.assetExtraction.MultiBranchStateService;
import com.box.l10n.mojito.service.branch.BranchRepository;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class NoSourceJsonAugmenterTest {

  private static final List<String> FORMAT_JS_OPTIONS =
      List.of(
          "noteKeyPattern=description",
          "extractAllPairs=false",
          "exceptions=defaultMessage",
          "removeKeySuffix=/defaultMessage");

  @Mock MultiBranchStateService multiBranchStateService;

  @Mock BranchRepository branchRepository;

  @Mock AssetExtractor assetExtractor;

  private ObjectMapper objectMapper;
  private NoSourceJsonAugmenter augmenter;
  private Asset asset;

  @Before
  public void setUp() throws Exception {
    objectMapper = new ObjectMapper();
    augmenter =
        new NoSourceJsonAugmenter(
            multiBranchStateService,
            new LocalBranchToEntityBranchConverter(),
            branchRepository,
            assetExtractor,
            new AssetPathToFilterConfigMapper(),
            objectMapper);

    asset = new Asset();
    asset.setPath("en.json");
    Repository repository = new Repository();
    repository.setName("test-repository");
    asset.setRepository(repository);
    AssetExtraction assetExtraction = new AssetExtraction();
    assetExtraction.setId(31L);
    assetExtraction.setVersion(4L);
    asset.setLastSuccessfulAssetExtraction(assetExtraction);

    lenient()
        .when(branchRepository.findByNameAndRepository(nullable(String.class), eq(repository)))
        .thenAnswer(
            invocation -> {
              com.box.l10n.mojito.entity.Branch branch = new com.box.l10n.mojito.entity.Branch();
              branch.setName(invocation.getArgument(0));
              branch.setRepository(repository);
              return branch;
            });
  }

  @Test
  public void appendsFlatBranchOnlyTextUnitsAndPreservesSourceComments() throws Exception {
    setState(
        state(
            List.of("authoring/checkout"),
            textUnit("local", "Server local", null, "local-md5", "authoring/checkout"),
            textUnit("checkout.submit", "Submit order", null, "submit-md5", "authoring/checkout")));
    String source = "{\n  // Keep this comment\n  \"local\": \"Local source\"\n}";
    String augmented = augmenter.augment(asset, source, null, List.of(), List.of());

    assertThat(augmented).contains("// Keep this comment");
    JsonNode root = readTree(augmented);
    assertThat(root.get("local").asText()).isEqualTo("Local source");
    assertThat(root.get("checkout.submit").asText()).isEqualTo("Submit order");
  }

  @Test
  public void appendsFormatJsSourceAndLiteralDescriptionForMd5Matching() throws Exception {
    setState(
        state(
            List.of("authoring/checkout"),
            textUnit(
                "checkout.submit",
                "Submit order",
                "Primary checkout action",
                "submit-md5",
                "authoring/checkout")));

    String source = "{\n  \"existing\": {\"defaultMessage\": \"Existing\"}\n}";
    String augmented =
        augmenter.augment(asset, source, null, FORMAT_JS_OPTIONS, List.of("authoring/checkout"));

    JsonNode message = readTree(augmented).get("checkout.submit");
    assertThat(message.get("defaultMessage").asText()).isEqualTo("Submit order");
    assertThat(message.get("description").asText()).isEqualTo("Primary checkout action");
  }

  @Test
  public void explicitBranchesLimitAndOrderAddedEntries() throws Exception {
    setState(
        state(
            List.of("authoring/one", "authoring/two", "authoring/three"),
            textUnit("one", "One", null, "one-md5", "authoring/one"),
            textUnit("two", "Two", null, "two-md5", "authoring/two"),
            textUnit("three", "Three", null, "three-md5", "authoring/three")));

    String augmented =
        augmenter.augment(asset, "{}", null, List.of(), List.of("authoring/two", "authoring/one"));

    assertThat(augmented).doesNotContain("\"three\"");
    assertThat(augmented.indexOf("\"two\"")).isLessThan(augmented.indexOf("\"one\""));
  }

  @Test
  public void emptyBranchSelectionUsesAllActiveMembershipOnly() throws Exception {
    setState(
        state(
            List.of("authoring/active"),
            textUnit("active", "Active", null, "active-md5", "authoring/active"),
            textUnit("deleted", "Deleted", null, "deleted-md5", "authoring/deleted")));

    JsonNode root = readTree(augmenter.augment(asset, "{}", null, List.of(), List.of()));

    assertThat(root.has("active")).isTrue();
    assertThat(root.has("deleted")).isFalse();
  }

  @Test
  public void textUnitWithMasterAndAuthoringMembershipCanBeSelectedByAuthoringBranch()
      throws Exception {
    setState(
        state(
            List.of(
                LocalBranchToEntityBranchConverter.NULL_BRANCH_TEXT_PLACEHOLDER,
                "authoring/checkout"),
            textUnit(
                "checkout.submit",
                "Submit order",
                null,
                "submit-md5",
                LocalBranchToEntityBranchConverter.NULL_BRANCH_TEXT_PLACEHOLDER,
                "authoring/checkout")));

    String augmented =
        augmenter.augment(asset, "{}", null, List.of(), List.of("authoring/checkout"));

    assertThat(countOccurrences(augmented, "\"checkout.submit\"")).isEqualTo(1);
  }

  @Test
  public void nullBranchCanBeSelectedWithoutSelectingNamedBranches() throws Exception {
    String defaultBranch = LocalBranchToEntityBranchConverter.NULL_BRANCH_TEXT_PLACEHOLDER;
    setState(
        state(
            List.of(defaultBranch, "authoring/checkout"),
            textUnit("default-only", "Default", null, "default-md5", defaultBranch),
            textUnit("authoring-only", "Authoring", null, "authoring-md5", "authoring/checkout")));

    JsonNode root =
        readTree(
            augmenter.augment(
                asset, "{}", null, List.of(), java.util.Arrays.asList((String) null)));

    assertThat(root.has("default-only")).isTrue();
    assertThat(root.has("authoring-only")).isFalse();
  }

  @Test
  public void nullBranchAndNamedBranchCanBeSelectedTogether() throws Exception {
    String defaultBranch = LocalBranchToEntityBranchConverter.NULL_BRANCH_TEXT_PLACEHOLDER;
    setState(
        state(
            List.of(defaultBranch, "authoring/checkout"),
            textUnit("default-only", "Default", null, "default-md5", defaultBranch),
            textUnit("authoring-only", "Authoring", null, "authoring-md5", "authoring/checkout")));

    JsonNode root =
        readTree(
            augmenter.augment(
                asset, "{}", null, List.of(), java.util.Arrays.asList(null, "authoring/checkout")));

    assertThat(root.has("default-only")).isTrue();
    assertThat(root.has("authoring-only")).isTrue();
  }

  @Test
  public void namedNullBranchIsDistinctFromDefaultBranch() throws Exception {
    String defaultBranch = LocalBranchToEntityBranchConverter.NULL_BRANCH_TEXT_PLACEHOLDER;
    setState(
        state(
            List.of(defaultBranch, "null"),
            textUnit("default-only", "Default", null, "default-md5", defaultBranch),
            textUnit("named-null", "Named null", null, "named-null-md5", "null")));

    JsonNode root = readTree(augmenter.augment(asset, "{}", null, List.of(), List.of("null")));

    assertThat(root.has("default-only")).isFalse();
    assertThat(root.has("named-null")).isTrue();
  }

  @Test
  public void repositoryBranchWithoutAssetMembershipAddsNothing() throws Exception {
    setState(state(List.of("authoring/active")));

    assertThat(augmenter.augment(asset, "{}", null, List.of(), List.of("authoring/other-asset")))
        .isEqualTo("{}");
  }

  @Test
  public void unsupportedAssetIsUnchangedWhenSelectedBranchHasNoAssetMembership() throws Exception {
    setState(state(List.of("authoring/active")));
    asset.setPath("messages.properties");

    assertThat(
            augmenter.augment(
                asset, "key=value", null, List.of(), List.of("authoring/other-asset")))
        .isEqualTo("key=value");
  }

  @Test
  public void unsupportedAssetIsUnchangedWhenSelectedActiveBranchHasNoTextUnits() throws Exception {
    setState(state(List.of("authoring/active")));
    asset.setPath("messages.properties");

    assertThat(augmenter.augment(asset, "key=value", null, List.of(), List.of("authoring/active")))
        .isEqualTo("key=value");
  }

  @Test
  public void explicitUnknownRepositoryBranchFailsClearly() throws Exception {
    setState(state(List.of("authoring/active")));
    when(branchRepository.findByNameAndRepository("authoring/typo", asset.getRepository()))
        .thenReturn(null);

    assertThatThrownBy(
            () ->
                augmenter.augment(
                    asset, "{}", null, List.of(), List.of("authoring/active", "authoring/typo")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not active in repository test-repository")
        .hasMessageContaining("authoring/typo");
  }

  @Test
  public void nestedOrPathLikeBranchOnlyTextUnitFailsClearly() throws Exception {
    setState(
        state(
            List.of("authoring/checkout"),
            textUnit("nested/value", "Branch source", null, "branch-md5", "authoring/checkout")));

    assertThatThrownBy(() -> augmenter.augment(asset, "{}", null, List.of(), List.of()))
        .isInstanceOf(UnsupportedAssetFilterTypeException.class)
        .hasMessageContaining("nested or path-like JSON key")
        .hasMessageContaining("nested/value");
  }

  @Test
  public void nestedRootMapFailsClearly() throws Exception {
    setState(
        state(
            List.of("authoring/checkout"),
            textUnit("checkout.submit", "Submit order", null, "submit-md5", "authoring/checkout")));

    assertThatThrownBy(
            () ->
                augmenter.augment(
                    asset, "{\"nested\":{\"value\":\"Local source\"}}", null, List.of(), List.of()))
        .isInstanceOf(UnsupportedAssetFilterTypeException.class)
        .hasMessageContaining("flat JSON string values at the document root");
  }

  @Test
  public void trailingRootCommentRemainsAfterAppendedEntryAndParses() throws Exception {
    setState(
        state(
            List.of("authoring/checkout"),
            textUnit("checkout.submit", "Submit order", null, "submit-md5", "authoring/checkout")));
    String source = "{\n  \"local\": \"Local source\"\n  // Keep this trailing comment\n}";

    String augmented = augmenter.augment(asset, source, null, List.of(), List.of());

    assertThat(augmented.indexOf("\"checkout.submit\""))
        .isLessThan(augmented.indexOf("// Keep this trailing comment"));
    JsonNode root = readTree(augmented);
    assertThat(root.get("local").asText()).isEqualTo("Local source");
    assertThat(root.get("checkout.submit").asText()).isEqualTo("Submit order");
  }

  @Test
  public void sameNameAndMd5AcrossBranchesIsDeduplicated() throws Exception {
    setState(
        state(
            List.of("authoring/one", "authoring/two"),
            textUnit("shared", "Shared", null, "same-md5", "authoring/one"),
            textUnit("shared", "Shared", null, "same-md5", "authoring/two")));

    String augmented = augmenter.augment(asset, "{}", null, List.of(), List.of());

    assertThat(countOccurrences(augmented, "\"shared\"")).isEqualTo(1);
  }

  @Test
  public void sameNameAndDifferentMd5AcrossBranchesFails() throws Exception {
    setState(
        state(
            List.of("authoring/one", "authoring/two"),
            textUnit("shared", "First", null, "first-md5", "authoring/one"),
            textUnit("shared", "Second", null, "second-md5", "authoring/two")));

    assertThatThrownBy(() -> augmenter.augment(asset, "{}", null, List.of(), List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("conflicting source strings")
        .hasMessageContaining("shared");
  }

  @Test
  public void pluralBranchOnlyTextUnitFails() throws Exception {
    BranchStateTextUnit plural =
        BranchStateTextUnit.builder()
            .name("items")
            .source("One item")
            .pluralForm("items")
            .pluralFormOther("Other items")
            .md5("plural-md5")
            .branchNameToBranchDatas(ImmutableMap.of("authoring/checkout", BranchData.of()))
            .build();
    setState(state(List.of("authoring/checkout"), plural));

    assertThatThrownBy(() -> augmenter.augment(asset, "{}", null, List.of(), List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("plural text units")
        .hasMessageContaining("items");
  }

  @Test
  public void unsupportedFilterFailsClearly() throws Exception {
    setState(
        state(
            List.of("authoring/checkout"),
            textUnit("checkout.submit", "Submit order", null, "submit-md5", "authoring/checkout")));
    asset.setPath("messages.properties");

    assertThatThrownBy(() -> augmenter.augment(asset, "key=value", null, List.of(), List.of()))
        .hasMessageContaining("supported only for JSON assets");
  }

  @Test
  public void formatJsCommentRequiresLiteralNoteKeyPattern() throws Exception {
    setState(
        state(
            List.of("authoring/checkout"),
            textUnit(
                "checkout.submit",
                "Submit order",
                "Primary checkout action",
                "submit-md5",
                "authoring/checkout")));
    List<String> regexNoteOptions =
        List.of(
            "noteKeyPattern=description.*",
            "extractAllPairs=false",
            "exceptions=defaultMessage",
            "removeKeySuffix=/defaultMessage");

    assertThatThrownBy(() -> augmenter.augment(asset, "{}", null, regexNoteOptions, List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("literal noteKeyPattern");
  }

  @Test
  public void sourceTransformFilterOptionFailsClearly() throws Exception {
    setState(
        state(
            List.of("authoring/checkout"),
            textUnit("checkout.submit", "Submit order", null, "submit-md5", "authoring/checkout")));

    assertThatThrownBy(
            () ->
                augmenter.augment(asset, "{}", null, List.of("convertToHtmlCodes=true"), List.of()))
        .isInstanceOf(UnsupportedAssetFilterTypeException.class)
        .hasMessageContaining("transform source content or text-unit names");
  }

  @Test
  public void reExtractionIdentityMismatchFailsClearly() throws Exception {
    setState(
        state(
            List.of("authoring/checkout"),
            textUnit("checkout.submit", "Submit order", null, "submit-md5", "authoring/checkout")));
    when(assetExtractor.getAssetExtractorTextUnitsForAsset(
            eq("en.json"), anyString(), eq(null), eq(List.of())))
        .thenReturn(
            List.of(
                extractedTextUnit(
                    "checkout.submit", "Changed during extraction", null, null, null)));

    assertThatThrownBy(() -> augmenter.augment(asset, "{}", null, List.of(), List.of()))
        .isInstanceOf(UnsupportedAssetFilterTypeException.class)
        .hasMessageContaining("cannot reconstruct branch-only text unit")
        .hasMessageContaining("checkout.submit");
  }

  @Test
  public void rootArrayFailsClearly() throws Exception {
    setState(
        state(
            List.of("authoring/checkout"),
            textUnit("checkout.submit", "Submit order", null, "submit-md5", "authoring/checkout")));

    assertThatThrownBy(() -> augmenter.augment(asset, "[]", null, List.of(), List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("JSON object at the document root");
  }

  @Test
  public void nonJsonFilterOverrideFailsEvenForJsonPath() throws Exception {
    setState(
        state(
            List.of("authoring/checkout"),
            textUnit("checkout.submit", "Submit order", null, "submit-md5", "authoring/checkout")));

    assertThatThrownBy(
            () ->
                augmenter.augment(
                    asset, "{}", FilterConfigIdOverride.PROPERTIES_JAVA, List.of(), List.of()))
        .hasMessageContaining("supported only for JSON assets");
  }

  private void setState(MultiBranchState multiBranchState)
      throws UnsupportedAssetFilterTypeException {
    when(multiBranchStateService.getMultiBranchStateForAssetExtractionId(31L, 4L))
        .thenReturn(multiBranchState);
    lenient()
        .when(
            assetExtractor.getAssetExtractorTextUnitsForAsset(
                eq("en.json"), anyString(), eq(null), anyList()))
        .thenReturn(
            multiBranchState.getBranchStateTextUnits().stream()
                .map(this::extractedTextUnit)
                .toList());
  }

  private MultiBranchState state(List<String> activeBranchNames, BranchStateTextUnit... textUnits) {
    ImmutableSet<Branch> branches =
        activeBranchNames.stream()
            .map(
                name ->
                    Branch.builder()
                        .name(name)
                        .createdAt(ZonedDateTime.parse("2026-08-19T00:00:00Z"))
                        .build())
            .collect(ImmutableSet.toImmutableSet());
    return MultiBranchState.builder()
        .branches(branches)
        .branchStateTextUnits(ImmutableList.copyOf(textUnits))
        .build();
  }

  private BranchStateTextUnit textUnit(
      String name, String source, String comment, String md5, String... branchNames) {
    ImmutableMap.Builder<String, BranchData> branchNameToBranchData = ImmutableMap.builder();
    for (String branchName : branchNames) {
      branchNameToBranchData.put(branchName, BranchData.of());
    }
    return BranchStateTextUnit.builder()
        .name(name)
        .source(source)
        .comments(comment)
        .md5(md5)
        .branchNameToBranchDatas(branchNameToBranchData.build())
        .build();
  }

  private AssetExtractorTextUnit extractedTextUnit(BranchStateTextUnit textUnit) {
    return extractedTextUnit(
        textUnit.getName(),
        textUnit.getSource(),
        textUnit.getComments(),
        textUnit.getPluralForm(),
        textUnit.getPluralFormOther());
  }

  private AssetExtractorTextUnit extractedTextUnit(
      String name, String source, String comments, String pluralForm, String pluralFormOther) {
    AssetExtractorTextUnit textUnit = new AssetExtractorTextUnit();
    textUnit.setName(name);
    textUnit.setSource(source);
    textUnit.setComments(comments);
    textUnit.setPluralForm(pluralForm);
    textUnit.setPluralFormOther(pluralFormOther);
    return textUnit;
  }

  private JsonNode readTree(String content) throws IOException {
    try (JsonParser parser = objectMapper.createParser(content)) {
      parser.enable(JsonParser.Feature.ALLOW_COMMENTS);
      parser.enable(JsonParser.Feature.ALLOW_TRAILING_COMMA);
      return parser.readValueAsTree();
    }
  }

  private int countOccurrences(String value, String substring) {
    int count = 0;
    int index = 0;
    while ((index = value.indexOf(substring, index)) >= 0) {
      count++;
      index += substring.length();
    }
    return count;
  }
}
