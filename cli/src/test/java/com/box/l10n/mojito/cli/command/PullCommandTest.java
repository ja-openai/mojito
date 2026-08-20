package com.box.l10n.mojito.cli.command;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.box.l10n.mojito.cldr.PluralRuleService;
import com.box.l10n.mojito.cli.CLITestBase;
import com.box.l10n.mojito.entity.Locale;
import com.box.l10n.mojito.entity.PullRun;
import com.box.l10n.mojito.entity.PushRun;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.TMTextUnit;
import com.box.l10n.mojito.entity.TMTextUnitVariant;
import com.box.l10n.mojito.fileformat.LocalizationCatalog;
import com.box.l10n.mojito.fileformat.LocalizationFileConverters;
import com.box.l10n.mojito.fileformat.LocalizationFileFormat;
import com.box.l10n.mojito.fileformat.LocalizationMessage;
import com.box.l10n.mojito.fileformat.LocalizationParseException;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.rest.client.AssetClient;
import com.box.l10n.mojito.rest.client.RepositoryClient;
import com.box.l10n.mojito.rest.entity.Asset;
import com.box.l10n.mojito.rest.entity.RepositoryStatistic;
import com.box.l10n.mojito.service.commit.CommitService;
import com.box.l10n.mojito.service.delta.DeltaService;
import com.box.l10n.mojito.service.delta.DeltaType;
import com.box.l10n.mojito.service.delta.dtos.DeltaResponseDTO;
import com.box.l10n.mojito.service.locale.LocaleService;
import com.box.l10n.mojito.service.pullrun.PullRunRepository;
import com.box.l10n.mojito.service.pullrun.PullRunTextUnitVariantRepository;
import com.box.l10n.mojito.service.tm.TMImportService;
import com.box.l10n.mojito.service.tm.TMService;
import com.box.l10n.mojito.service.tm.TMTextUnitCurrentVariantRepository;
import com.box.l10n.mojito.service.tm.TMTextUnitCurrentVariantService;
import com.box.l10n.mojito.service.tm.TMTextUnitRepository;
import com.box.l10n.mojito.service.tm.TMTextUnitVariantRepository;
import com.google.common.collect.ImmutableList;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import org.assertj.core.api.Assertions;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Batch rename when adding a test: find . -name "*properties" -exec rename "s/properties/json/" {}
 * \;
 */
public class PullCommandTest extends CLITestBase {

  /** logger */
  static Logger logger = LoggerFactory.getLogger(PullCommandTest.class);

  @Autowired RepositoryClient repositoryClient;

  @Autowired AssetClient assetClient;

  @Autowired TMTextUnitVariantRepository tmTextUnitVariantRepository;

  @Autowired CommitService commitService;

  @Autowired DeltaService deltaService;

  @Autowired TMService tmService;

  @Autowired TMImportService portableTestImportService;

  @Autowired LocaleService localeService;

  @Autowired TMTextUnitCurrentVariantRepository tmTextUnitCurrentVariantRepository;

  @Autowired TMTextUnitCurrentVariantService tmTextUnitCurrentVariantService;

  @Autowired TMTextUnitRepository tmTextUnitRepository;

  @Autowired PullRunRepository pullRunRepository;

  @Autowired PullRunTextUnitVariantRepository pullRunTextUnitVariantRepository;

  @Test
  public void pull() throws Exception {

    Repository repository = createTestRepoUsingRepoService();

    getL10nJCommander()
        .run(
            "push",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath());

    Asset asset =
        assetClient.getAssetByPathAndRepositoryId("source-xliff.xliff", repository.getId());
    importTranslations(asset.getId(), "source-xliff_", "fr-FR");
    importTranslations(asset.getId(), "source-xliff_", "ja-JP");

    Asset asset2 =
        assetClient.getAssetByPathAndRepositoryId("source2-xliff.xliff", repository.getId());
    importTranslations(asset2.getId(), "source2-xliff_", "fr-FR");
    importTranslations(asset2.getId(), "source2-xliff_", "ja-JP");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getTargetTestDir("target").getAbsolutePath());

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source_modified").getAbsolutePath(),
            "-t",
            getTargetTestDir("target_modified").getAbsolutePath());

    checkExpectedGeneratedResources();
  }

  @Test
  public void pullWithAsyncWS() throws Exception {

    Repository repository = createTestRepoUsingRepoService();

    getL10nJCommander()
        .run(
            "push",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath());

    Asset asset =
        assetClient.getAssetByPathAndRepositoryId("source-xliff.xliff", repository.getId());
    importTranslations(asset.getId(), "source-xliff_", "fr-FR");
    importTranslations(asset.getId(), "source-xliff_", "ja-JP");

    Asset asset2 =
        assetClient.getAssetByPathAndRepositoryId("source2-xliff.xliff", repository.getId());
    importTranslations(asset2.getId(), "source2-xliff_", "fr-FR");
    importTranslations(asset2.getId(), "source2-xliff_", "ja-JP");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getTargetTestDir("target").getAbsolutePath(),
            "--async-ws");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source_modified").getAbsolutePath(),
            "-t",
            getTargetTestDir("target_modified").getAbsolutePath(),
            "--async-ws");

    checkExpectedGeneratedResources();
  }

  @Test
  public void pullWithParallelWS() throws Exception {

    Repository repository = createTestRepoUsingRepoService();

    getL10nJCommander()
        .run(
            "push",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath());

    Asset asset =
        assetClient.getAssetByPathAndRepositoryId("source-xliff.xliff", repository.getId());
    importTranslations(asset.getId(), "source-xliff_", "fr-FR");
    importTranslations(asset.getId(), "source-xliff_", "ja-JP");

    Asset asset2 =
        assetClient.getAssetByPathAndRepositoryId("source2-xliff.xliff", repository.getId());
    importTranslations(asset2.getId(), "source2-xliff_", "fr-FR");
    importTranslations(asset2.getId(), "source2-xliff_", "ja-JP");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getTargetTestDir("target").getAbsolutePath(),
            "--async-ws");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source_modified").getAbsolutePath(),
            "-t",
            getTargetTestDir("target_modified").getAbsolutePath(),
            "--parallel");

    checkExpectedGeneratedResources();
  }

  @Test
  public void pullWithDuplicatedTextUnits() throws Exception {

    Repository repository = createTestRepoUsingRepoService();

    getL10nJCommander()
        .run(
            "push",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-ft",
            "PROPERTIES_NOBASENAME");

    Asset asset = assetClient.getAssetByPathAndRepositoryId("en.properties", repository.getId());
    importTranslations(asset.getId(), "source-xliff_", "fr-FR");
    importTranslations(asset.getId(), "source-xliff_", "ja-JP");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getTargetTestDir("target").getAbsolutePath(),
            "-ft",
            "PROPERTIES_NOBASENAME");

    checkExpectedGeneratedResources();
  }

  @Test
  public void pullPropertiesNoBasename() throws Exception {

    Repository repository = createTestRepoUsingRepoService();

    getL10nJCommander()
        .run(
            "push",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-ft",
            "PROPERTIES_NOBASENAME");

    Asset asset = assetClient.getAssetByPathAndRepositoryId("en.properties", repository.getId());
    importTranslations(asset.getId(), "source-xliff_", "fr-FR");
    importTranslations(asset.getId(), "source-xliff_", "ja-JP");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getTargetTestDir("target").getAbsolutePath(),
            "-ft",
            "PROPERTIES_NOBASENAME");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source_modified").getAbsolutePath(),
            "-t",
            getTargetTestDir("target_modified").getAbsolutePath(),
            "-ft",
            "PROPERTIES_NOBASENAME");

    checkExpectedGeneratedResources();
  }

  @Test
  public void pullProperties() throws Exception {

    Repository repository = createTestRepoUsingRepoService();

    getL10nJCommander()
        .run(
            "push",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath());

    Asset asset = assetClient.getAssetByPathAndRepositoryId("demo.properties", repository.getId());
    importTranslations(asset.getId(), "source-xliff_", "fr-FR");
    importTranslations(asset.getId(), "source-xliff_", "ja-JP");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getTargetTestDir("target").getAbsolutePath());

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source_modified").getAbsolutePath(),
            "-t",
            getTargetTestDir("target_modified").getAbsolutePath());

    checkExpectedGeneratedResources();
  }

  @Test
  public void portableConverterReusesExistingPropertiesDataset() throws Exception {
    assertPortableMatchesExistingDataset(
        "pullProperties", "demo.properties", new String[0], new String[0]);
  }

  @Test
  public void portableConverterReusesExistingJavaPropertiesDataset() throws Exception {
    String[] options = {"-ft", "PROPERTIES_JAVA"};
    assertPortableMatchesExistingDataset("pullPropertiesJava", "demo.properties", options, options);
  }

  @Test
  public void portableConverterRemovesUntranslatedPropertiesLikeOkapi() throws Exception {
    Repository repository = createTestRepoUsingRepoService();
    Path dataset =
        Path.of(
            "src/test/resources/com/box/l10n/mojito/cli/command/PullCommandTest_IO/pullProperties");
    String original = dataset.resolve("input/source").toAbsolutePath().toString();
    String modified = dataset.resolve("input/source_modified").toAbsolutePath().toString();
    getL10nJCommander()
        .run("push", "-r", repository.getName(), "-s", original, "--converter", "portable");
    Asset asset = assetClient.getAssetByPathAndRepositoryId("demo.properties", repository.getId());
    importTranslationsFromDataset(dataset, asset.getId(), "fr-FR");
    importTranslationsFromDataset(dataset, asset.getId(), "ja-JP");

    File legacy = getTargetTestDir("legacy");
    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            modified,
            "-t",
            legacy.getAbsolutePath(),
            "--inheritance-mode",
            "REMOVE_UNTRANSLATED",
            "--converter",
            "okapi");
    File portable = getTargetTestDir("portable");
    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            modified,
            "-t",
            portable.getAbsolutePath(),
            "--inheritance-mode",
            "REMOVE_UNTRANSLATED",
            "--converter",
            "portable");

    assertEquivalentProperties(legacy.toPath(), portable.toPath());
    try (var outputs = Files.walk(portable.toPath())) {
      for (Path output : outputs.filter(Files::isRegularFile).toList()) {
        assertFalse(output.toString(), Files.readString(output).contains("@#$untranslated$#@"));
      }
    }
  }

  @Test
  public void portableConverterReusesExistingResxDataset() throws Exception {
    assertPortableMatchesExistingDataset("pullResx", "Test.resx", new String[0], new String[0]);
  }

  @Test
  public void portableConverterReusesExistingReswDataset() throws Exception {
    assertPortableMatchesExistingDataset(
        "pullResw",
        "en/Resources.resw",
        new String[0],
        new String[] {"-lm", "fr:fr-FR,fr-CA:fr-CA,ja:ja-JP", "-lmt", "MAP_ONLY"});
  }

  @Test
  public void portableConverterReusesExistingXtbDataset() throws Exception {
    assertPortableMatchesExistingDataset(
        "pullXtb",
        "Resources-en-US.xtb",
        new String[] {"-sl", "en-US"},
        new String[] {"-sl", "en-US"});
  }

  @Test
  public void portableConverterReusesExistingCsvDataset() throws Exception {
    assertPortableMatchesExistingDataset("pullCsv", "demo.csv", new String[0], new String[0]);
  }

  @Test
  public void portableConverterReusesExistingAdobeMagentoCsvDataset() throws Exception {
    String[] options = {"-ft", "CSV_ADOBE_MAGENTO", "-sl", "en_US"};
    assertPortableMatchesExistingDataset("pullCsvAdobeMagento", "i18n/en_US.csv", options, options);
  }

  @Test
  public void portableConverterRemovesUntranslatedResxLikeOkapi() throws Exception {
    assertPortableXmlRemovalMatchesOkapi("pullResx", "Test.resx", new String[0]);
  }

  @Test
  public void portableConverterRemovesUntranslatedXtbLikeOkapi() throws Exception {
    assertPortableXmlRemovalMatchesOkapi(
        "pullXtb", "Resources-en-US.xtb", new String[] {"-sl", "en-US"});
  }

  private void assertPortableXmlRemovalMatchesOkapi(
      String datasetName, String assetPath, String[] sourceOptions) throws Exception {
    Repository repository = createTestRepoUsingRepoService();
    Path dataset =
        Path.of(
            "src/test/resources/com/box/l10n/mojito/cli/command/PullCommandTest_IO", datasetName);
    String original = dataset.resolve("input/source").toAbsolutePath().toString();
    String modified = dataset.resolve("input/source_modified").toAbsolutePath().toString();
    List<String> push =
        new ArrayList<>(List.of("push", "-r", repository.getName(), "-s", original));
    push.addAll(List.of(sourceOptions));
    push.addAll(List.of("--converter", "portable"));
    getL10nJCommander().run(push.toArray(String[]::new));
    Asset asset = assetClient.getAssetByPathAndRepositoryId(assetPath, repository.getId());
    importTranslationsFromDataset(dataset, asset.getId(), "fr-FR");
    importTranslationsFromDataset(dataset, asset.getId(), "ja-JP");

    File legacy = getTargetTestDir("legacy");
    File portable = getTargetTestDir("portable");
    for (String backend : List.of("okapi", "portable")) {
      File target = "okapi".equals(backend) ? legacy : portable;
      List<String> pull =
          new ArrayList<>(
              List.of(
                  "pull",
                  "-r",
                  repository.getName(),
                  "-s",
                  modified,
                  "-t",
                  target.getAbsolutePath(),
                  "--inheritance-mode",
                  "REMOVE_UNTRANSLATED",
                  "--converter",
                  backend));
      pull.addAll(List.of(sourceOptions));
      getL10nJCommander().run(pull.toArray(String[]::new));
    }
    LocalizationFileFormat format =
        assetPath.endsWith(".xtb") ? LocalizationFileFormat.XTB : LocalizationFileFormat.RESX;
    try (var outputs = Files.walk(portable.toPath())) {
      for (Path actual : outputs.filter(Files::isRegularFile).toList()) {
        LocalizationCatalog actualCatalog =
            LocalizationFileConverters.parse(format, Files.readAllBytes(actual));
        Path expected = legacy.toPath().resolve(portable.toPath().relativize(actual));
        LocalizationCatalog expectedCatalog;
        try {
          expectedCatalog = LocalizationFileConverters.parse(format, Files.readAllBytes(expected));
        } catch (LocalizationParseException failure) {
          assertTrue(
              "Portable XML retains a valid empty root when Okapi corrupts the document",
              actualCatalog.messages().isEmpty());
          continue;
        }
        assertEquals(expectedCatalog.messages().keySet(), actualCatalog.messages().keySet());
        for (String id : expectedCatalog.messages().keySet()) {
          assertEquals(
              actual + " " + id,
              expectedCatalog.messages().get(id).defaultMessage(),
              actualCatalog.messages().get(id).defaultMessage());
        }
      }
    }
  }

  @Test
  public void portableConverterReusesExistingAndroidDataset() throws Exception {
    Repository repository = createTestRepoUsingRepoService();
    Path source = getTargetTestDir("source").toPath();
    Path original =
        Path.of(
            "src/test/resources/com/box/l10n/mojito/cli/command/PullCommandTest_IO",
            "pullAndroidStrings",
            "input/source/res/values/strings.xml");
    Path android = source.resolve("res/values/strings.xml");
    Files.createDirectories(android.getParent());
    Files.writeString(
        android,
        Files.readString(original)
            .replace("100_character_description_", "character_description")
            .replaceAll("name=\"([0-9])", "name=\"number_$1"));

    getL10nJCommander()
        .run(
            "push", "-r", repository.getName(), "-s", source.toString(), "--converter", "portable");
    Asset asset =
        assetClient.getAssetByPathAndRepositoryId("res/values/strings.xml", repository.getId());
    List<TMTextUnit> units = tmTextUnitRepository.findByTm_id(repository.getTm().getId());
    assertEquals(5, units.size());
    Locale french = localeService.findByBcp47Tag("fr-FR");
    for (TMTextUnit unit : units) {
      tmService.addCurrentTMTextUnitVariant(
          unit.getId(), french.getId(), unit.getContent() + " traduit");
    }

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            source.toString(),
            "-t",
            getTargetTestDir("target").getAbsolutePath(),
            "--converter",
            "portable");
    assertNotNull(asset);
    String localized =
        Files.readString(
            getTargetTestDir("target").toPath().resolve("res/values-fr-rFR/strings.xml"));
    assertTrue(localized.contains("15 min traduit"));
    assertTrue(localized.contains("character_description"));
    assertFalse(
        "Android format placeholders must not be escaped twice", localized.contains("%%1$s"));
  }

  @Test
  public void portableConverterReusesExistingAppleStringsDataset() throws Exception {
    assertPortableMatchesExistingDataset(
        "pullMacStrings", "en.lproj/Localizable.strings", new String[0], new String[0]);
  }

  @Test
  public void portableConverterReusesExistingJavaScriptDataset() throws Exception {
    String[] localeMapping = {"-lm", "fr:fr-FR,ja:ja-JP", "-lmt", "MAP_ONLY"};
    assertPortableMatchesExistingDataset("pullJS", "en.js", new String[0], localeMapping);
  }

  @Test
  public void portableConverterReusesExistingTypeScriptDataset() throws Exception {
    String[] localeMapping = {"-lm", "fr:fr-FR,ja:ja-JP", "-lmt", "MAP_ONLY"};
    assertPortableMatchesExistingDataset("pullTS", "en.ts", new String[0], localeMapping);
  }

  @Test
  public void portableConverterReusesExistingHtmlAlphaDataset() throws Exception {
    String[] pushOptions = {"-ft", "HTML_ALPHA", "-fo", "processImageUrls=true"};
    String[] pullOptions = {
      "-ft",
      "HTML_ALPHA",
      "-fo",
      "processImageUrls=true",
      "-lm",
      "fr:fr-FR,fr-CA:fr-CA,ja:ja-JP",
      "-lmt",
      "MAP_ONLY"
    };
    assertPortableMatchesExistingDataset("pullHtml", "demo.html", pushOptions, pullOptions);
  }

  @Test
  public void portableConverterReusesExistingAppleStringsdictDataset() throws Exception {
    assertPortableMatchesExistingDataset(
        "pullMacStringsdict", "en.lproj/Localizable.stringsdict", new String[0], new String[0]);
  }

  @Test
  public void portableConverterRemovesUntranslatedAppleStringsdictLikeOkapi() throws Exception {
    Repository repository = createTestRepoUsingRepoService();
    Path dataset =
        Path.of(
            "src/test/resources/com/box/l10n/mojito/cli/command/PullCommandTest_IO/pullMacStringsdict");
    String original = dataset.resolve("input/source").toAbsolutePath().toString();
    String modified = dataset.resolve("input/source_modified").toAbsolutePath().toString();
    getL10nJCommander()
        .run("push", "-r", repository.getName(), "-s", original, "--converter", "portable");
    Asset asset =
        assetClient.getAssetByPathAndRepositoryId(
            "en.lproj/Localizable.stringsdict", repository.getId());
    importTranslationsFromDataset(dataset, asset.getId(), "fr-FR");
    importTranslationsFromDataset(dataset, asset.getId(), "ja-JP");

    File legacy = getTargetTestDir("legacy");
    File portable = getTargetTestDir("portable");
    for (String backend : List.of("okapi", "portable")) {
      getL10nJCommander()
          .run(
              "pull",
              "-r",
              repository.getName(),
              "-s",
              modified,
              "-t",
              ("okapi".equals(backend) ? legacy : portable).getAbsolutePath(),
              "--inheritance-mode",
              "REMOVE_UNTRANSLATED",
              "--converter",
              backend);
    }
    try (var outputs = Files.walk(portable.toPath())) {
      for (Path actual : outputs.filter(Files::isRegularFile).toList()) {
        LocalizationCatalog actualCatalog =
            LocalizationFileConverters.parse(
                LocalizationFileFormat.APPLE_STRINGSDICT, Files.readAllBytes(actual));
        assertFalse(
            "Source-modified untranslated plural must be removed",
            actualCatalog.messages().containsKey("recipe_ingredients_with_count"));
        if (actual.toString().contains("fr-CA")) {
          assertTrue(
              "Missing locale retains a valid empty dictionary",
              actualCatalog.messages().isEmpty());
        } else {
          assertEquals(Set.of("plural_recipe_cook_hours"), actualCatalog.messages().keySet());
        }
        Path legacyOutput = legacy.toPath().resolve(portable.toPath().relativize(actual));
        try {
          LocalizationFileConverters.parse(
              LocalizationFileFormat.APPLE_STRINGSDICT, Files.readAllBytes(legacyOutput));
          fail("The existing customized stringsdict writer should expose its malformed cleanup");
        } catch (LocalizationParseException expected) {
          assertEquals("INVALID_XML", expected.code());
        }
      }
    }
  }

  @Test
  public void portableConverterReusesExistingGettextDataset() throws Exception {
    assertPortableMatchesExistingDataset(
        "pullPo",
        "LC_MESSAGES/messages.pot",
        new String[0],
        new String[] {"-lm", "fr:fr-FR,fr-CA:fr-CA,ja:ja-JP", "-lmt", "MAP_ONLY"});
  }

  @Test
  public void portableConverterSupportsExpandedRussianGettextPlurals() throws Exception {
    Repository repository = createTestRepoUsingRepoService();
    repositoryService.addRepositoryLocale(repository, "ru-RU", null, true);
    Path dataset =
        Path.of(
            "src/test/resources/com/box/l10n/mojito/cli/command/PullCommandTest_IO",
            "recordPullPoPlural");
    Path source = getTargetTestDir("source").toPath();
    Path sourceAsset = source.resolve("LC_MESSAGES/messages.pot");
    Files.createDirectories(sourceAsset.getParent());
    Files.writeString(
        sourceAsset,
        Files.readString(dataset.resolve("input/source/LC_MESSAGES/messages.pot"))
            + """
                #. Test second plural
                #: file.js:40
                msgctxt "boat"
                msgid "There is {number} boat"
                msgid_plural "There are {number} boats"
                msgstr[0] ""
                msgstr[1] ""

                """);
    getL10nJCommander()
        .run(
            "push", "-r", repository.getName(), "-s", source.toString(), "--converter", "portable");
    Asset asset =
        assetClient.getAssetByPathAndRepositoryId("LC_MESSAGES/messages.pot", repository.getId());
    Locale russian = localeService.findByBcp47Tag("ru-RU");
    for (TMTextUnit unit : tmTextUnitRepository.findByTm_id(repository.getTm().getId())) {
      String category = unit.getName().substring(unit.getName().lastIndexOf('_') + 1);
      tmService.addCurrentTMTextUnitVariant(
          unit.getId(), russian.getId(), category + "-" + unit.getContent());
    }

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            source.toString(),
            "-t",
            getTargetTestDir("target").getAbsolutePath(),
            "-lm",
            "ru-RU:ru-RU",
            "-lmt",
            "MAP_ONLY",
            "--record-pull-run",
            "--converter",
            "portable");
    String localized =
        Files.readString(
            getTargetTestDir("target").toPath().resolve("ru_RU/LC_MESSAGES/messages.po"));
    assertTrue(localized.contains("msgstr[0] \"one-There is {number} car\""));
    assertTrue(localized.contains("msgstr[1] \"few-There are {number} cars\""));
    assertTrue(localized.contains("msgstr[2] \"many-There are {number} cars\""));
    assertTrue(localized.contains("msgstr[0] \"one-There is {number} boat\""));
    assertTrue(localized.contains("msgstr[1] \"few-There are {number} boats\""));
    assertTrue(localized.contains("msgstr[2] \"many-There are {number} boats\""));
    assertEquals(6, pullRunTextUnitVariantRepository.count());
  }

  @Test
  public void portableConverterReusesExistingConfiguredJsonDataset() throws Exception {
    String[] options = {
      "-ft", "JSON", "-fo", "noteKeyPattern=note", "extractAllPairs=false", "exceptions=string"
    };
    assertPortableMatchesExistingDataset("pullJsonWithNote", "demo.json", options, options);
  }

  @Test
  public void portableConverterRoundTripsUtf16AndroidThroughStringTransport() throws Exception {
    Repository repository = createTestRepoUsingRepoService();
    Path source = getTargetTestDir("utf16-android-source").toPath();
    Path output = getTargetTestDir("utf16-android-output").toPath();
    Path sourceFile = source.resolve("res/values/strings.xml");
    Files.createDirectories(sourceFile.getParent());
    String content =
        "<?xml version=\"1.0\" encoding=\"UTF-16\"?>\n"
            + "<resources><string name=\"welcome\">Welcome</string></resources>\n";
    writeUtf16LeWithBom(sourceFile, content);

    getL10nJCommander()
        .run(
            "push", "-r", repository.getName(), "-s", source.toString(), "--converter", "portable");
    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            source.toString(),
            "-t",
            output.toString(),
            "--converter",
            "portable");

    byte[] localized = Files.readAllBytes(output.resolve("res/values-fr-rFR/strings.xml"));
    assertEquals((byte) 0xff, localized[0]);
    assertEquals((byte) 0xfe, localized[1]);
    String decoded = new String(localized, 2, localized.length - 2, StandardCharsets.UTF_16LE);
    assertTrue(decoded.contains("encoding=\"UTF-16\""));
    assertTrue(decoded.contains("Welcome"));
  }

  @Test
  public void portableConverterRoundTripsUtf16ApplePluralThroughStringTransport() throws Exception {
    Repository repository = createTestRepoUsingRepoService();
    Path source = getTargetTestDir("utf16-apple-source").toPath();
    Path output = getTargetTestDir("utf16-apple-output").toPath();
    Path sourceFile = source.resolve("en.lproj/Localizable.stringsdict");
    Files.createDirectories(sourceFile.getParent());
    String content =
        """
        <?xml version="1.0" encoding="UTF-16"?>
        <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
        <plist version="1.0"><dict>
          <key>files</key><dict>
            <key>NSStringLocalizedFormatKey</key><string>%#@count@</string>
            <key>count</key><dict>
              <key>NSStringFormatSpecTypeKey</key><string>NSStringPluralRuleType</string>
              <key>NSStringFormatValueTypeKey</key><string>d</string>
              <key>one</key><string>%d file</string>
              <key>other</key><string>%d files</string>
            </dict>
          </dict>
        </dict></plist>
        """;
    writeUtf16LeWithBom(sourceFile, content);

    getL10nJCommander()
        .run(
            "push", "-r", repository.getName(), "-s", source.toString(), "--converter", "portable");
    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            source.toString(),
            "-t",
            output.toString(),
            "--converter",
            "portable");

    byte[] localized = Files.readAllBytes(output.resolve("fr-FR.lproj/Localizable.stringsdict"));
    assertEquals((byte) 0xff, localized[0]);
    assertEquals((byte) 0xfe, localized[1]);
    String decoded = new String(localized, 2, localized.length - 2, StandardCharsets.UTF_16LE);
    assertTrue(decoded.contains("encoding=\"UTF-16\""));
    assertTrue(decoded.contains("%d files"));
  }

  private static void writeUtf16LeWithBom(Path path, String content) throws IOException {
    byte[] utf16 = content.getBytes(StandardCharsets.UTF_16LE);
    byte[] withBom = new byte[utf16.length + 2];
    withBom[0] = (byte) 0xff;
    withBom[1] = (byte) 0xfe;
    System.arraycopy(utf16, 0, withBom, 2, utf16.length);
    Files.write(path, withBom);
  }

  @Test
  public void portableJsonMigrationDecodesCommentsAndUsesExistingLeveraging() throws Exception {
    assertPortableJsonMigration(false);
  }

  @Test
  public void portableJsonMigrationPreservesApprovedTranslationsWhenExplicitlyRequested()
      throws Exception {
    assertPortableJsonMigration(true, false);
  }

  @Test
  public void portableJsonMigrationRetriesAfterCorrectedIdentityAlreadyExists() throws Exception {
    assertPortableJsonMigration(true, true);
  }

  private void assertPortableJsonMigration(boolean migrateLegacyComments) throws Exception {
    assertPortableJsonMigration(migrateLegacyComments, false);
  }

  private void assertPortableJsonMigration(
      boolean migrateLegacyComments, boolean createCorrectedIdentityBeforeMigration)
      throws Exception {
    Repository repository = createTestRepoUsingRepoService();
    Path source = getTargetTestDir("legacy-json-source").toPath();
    Path translations = getTargetTestDir("legacy-json-translations").toPath();
    Path output = getTargetTestDir("migrated-json-output").toPath();
    Files.createDirectories(source);
    Files.createDirectories(translations);

    String english =
        """
        {
          "quoted": {
            "defaultMessage": "The harbor is open",
            "description": "Label for \\\"Northern Harbor\\\""
          },
          "multiline": {
            "defaultMessage": "Harbor details",
            "description": "First line\\nsecond line"
          }
        }
        """;
    String french =
        english
            .replace("The harbor is open", "Le port est ouvert")
            .replace("Harbor details", "Détails du port");
    String japanese =
        english.replace("The harbor is open", "港は開いています").replace("Harbor details", "港の詳細");
    Files.writeString(source.resolve("en.json"), english);
    Files.writeString(translations.resolve("fr-FR.json"), french);
    Files.writeString(translations.resolve("fr-CA.json"), french);
    Files.writeString(translations.resolve("ja-JP.json"), japanese);

    runConfiguredJsonMigrationCommand("push", repository.getName(), source, null, "okapi");
    runConfiguredJsonMigrationCommand(
        "import", repository.getName(), source, translations, "okapi");

    TMTextUnit oldQuoted =
        tmTextUnitRepository.findByTm_id(repository.getTm().getId()).stream()
            .filter(unit -> unit.getName().equals("quoted"))
            .findFirst()
            .orElseThrow();
    assertEquals("Label for \\\"Northern Harbor\\\"", oldQuoted.getComment());
    assertNotNull(
        "Legacy import must populate the existing identity before migration",
        tmTextUnitCurrentVariantRepository.findByLocale_IdAndTmTextUnit_Id(
            localeService.findByBcp47Tag("fr-FR").getId(), oldQuoted.getId()));

    if (createCorrectedIdentityBeforeMigration) {
      runConfiguredJsonMigrationCommand(
          "push", repository.getName(), source, null, "portable", false);
    }
    runConfiguredJsonMigrationCommand(
        "push", repository.getName(), source, null, "portable", migrateLegacyComments);

    TMTextUnit correctedQuoted =
        tmTextUnitRepository.findByTm_id(repository.getTm().getId()).stream()
            .filter(unit -> unit.getName().equals("quoted"))
            .filter(unit -> unit.getComment().equals("Label for \"Northern Harbor\""))
            .findFirst()
            .orElseThrow();
    assertFalse(oldQuoted.getId().equals(correctedQuoted.getId()));
    for (String locale : List.of("fr-FR", "ja-JP")) {
      var current =
          tmTextUnitCurrentVariantRepository.findByLocale_IdAndTmTextUnit_Id(
              localeService.findByBcp47Tag(locale).getId(), correctedQuoted.getId());
      assertNotNull(locale, current);
      assertEquals(
          locale,
          migrateLegacyComments
              ? TMTextUnitVariant.Status.APPROVED
              : TMTextUnitVariant.Status.TRANSLATION_NEEDED,
          current.getTmTextUnitVariant().getStatus());
    }

    runConfiguredJsonMigrationCommand("pull", repository.getName(), source, output, "portable");
    assertEquals(french, Files.readString(output.resolve("fr-FR.json")));
    assertEquals(japanese, Files.readString(output.resolve("ja-JP.json")));
  }

  private void runConfiguredJsonMigrationCommand(
      String command, String repository, Path source, Path target, String converter) {
    runConfiguredJsonMigrationCommand(command, repository, source, target, converter, false);
  }

  private void runConfiguredJsonMigrationCommand(
      String command,
      String repository,
      Path source,
      Path target,
      String converter,
      boolean migrateLegacyComments) {
    List<String> arguments =
        new ArrayList<>(
            List.of(
                command,
                "-r",
                repository,
                "-s",
                source.toString(),
                "-ft",
                "JSON_NOBASENAME",
                "--converter",
                converter));
    if (target != null) {
      arguments.addAll(List.of("-t", target.toString()));
    }
    if (migrateLegacyComments) {
      arguments.add("--migrate-legacy-json-comments");
    }
    arguments.addAll(
        List.of(
            "-fo",
            "noteKeyPattern=description",
            "extractAllPairs=false",
            "exceptions=defaultMessage",
            "removeKeySuffix=/defaultMessage"));
    getL10nJCommander().run(arguments.toArray(String[]::new));
  }

  @Test
  public void portableConverterReusesExistingChromeJsonDataset() throws Exception {
    String[] options = {"-ft", "CHROME_EXT_JSON"};
    assertPortableMatchesExistingDataset(
        "pullJsonFromChromeExtension", "_locales/en/messages.json", options, options);
  }

  @Test
  public void portableConverterReusesExistingFormatJsDataset() throws Exception {
    assertPortableMatchesExistingDataset(
        "pullJsonDefaultFormatJs",
        "en.json",
        new String[] {"-ft", "FORMATJS_JSON_NOBASENAME"},
        new String[] {"-ft", "FORMATJS_JSON_NOBASENAME"});
  }

  @Test
  public void portableConverterReusesExistingYamlDataset() throws Exception {
    assertPortableMatchesExistingDataset(
        "pullYaml",
        "demo.yaml",
        new String[0],
        new String[] {"-lm", "fr:fr-FR,ja:ja-JP", "-lmt", "MAP_ONLY"});
  }

  @Test
  public void portableConverterReusesExistingConfiguredYamlDataset() throws Exception {
    String[] options = {
      "-fo", "extractAllPairs=false", "exceptions=1_day_duration|1_year_duration"
    };
    assertPortableMatchesExistingDataset(
        "pullYamlWithFilterOptions",
        "demo.yaml",
        options,
        new String[] {
          "-lm",
          "fr:fr-FR,ja:ja-JP",
          "-lmt",
          "MAP_ONLY",
          "-fo",
          "extractAllPairs=false",
          "exceptions=1_day_duration|1_year_duration"
        });
  }

  @Test
  public void portableConverterPreservesRemoveUntranslated() throws Exception {
    assertPortableMatchesExistingDataset(
        "pullJsonDefaultFormatJsRemoveUntranslated",
        "en.json",
        new String[] {"-ft", "FORMATJS_JSON_NOBASENAME"},
        new String[] {
          "-ft", "FORMATJS_JSON_NOBASENAME", "--inheritance-mode", "REMOVE_UNTRANSLATED"
        });
  }

  @Test
  public void portableConverterPreservesPullRunBookkeeping() throws Exception {
    assertPortableMatchesExistingDataset(
        "pullProperties", "demo.properties", new String[0], new String[] {"--record-pull-run"});
    assertTrue(
        "Recorded pull runs must retain their translated text-unit variants",
        pullRunTextUnitVariantRepository.count() > 0);
  }

  @Test
  public void portableConverterSupportsExistingAsyncPull() throws Exception {
    assertPortableMatchesExistingDataset(
        "pullProperties", "demo.properties", new String[0], new String[] {"--async-ws"});
  }

  @Test
  public void portableConverterSupportsExistingParallelPull() throws Exception {
    assertPortableMatchesExistingDataset(
        "pullProperties", "demo.properties", new String[0], new String[] {"--parallel"});
  }

  private void assertPortableMatchesExistingDataset(
      String dataset, String assetPath, String[] pushOptions, String[] pullOptions)
      throws Exception {
    Repository repository = createTestRepoUsingRepoService();
    Path input =
        Path.of("src/test/resources/com/box/l10n/mojito/cli/command/PullCommandTest_IO", dataset);
    File source = input.resolve("input/source").toFile();
    List<String> push = new ArrayList<>();
    push.addAll(List.of("push", "-r", repository.getName(), "-s", source.getAbsolutePath()));
    push.addAll(List.of(pushOptions));
    push.addAll(List.of("--converter", "portable"));
    getL10nJCommander().run(push.toArray(String[]::new));

    Asset asset = assetClient.getAssetByPathAndRepositoryId(assetPath, repository.getId());
    importTranslationsFromDataset(input, asset.getId(), "fr-FR");
    importTranslationsFromDataset(input, asset.getId(), "ja-JP");

    File output = getTargetTestDir("target");
    List<String> pull = new ArrayList<>();
    pull.addAll(
        List.of(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            source.getAbsolutePath(),
            "-t",
            output.getAbsolutePath(),
            "--converter",
            "portable"));
    pull.addAll(List.of(pullOptions));
    getL10nJCommander().run(pull.toArray(String[]::new));
    if (assetPath.endsWith(".properties")) {
      assertEquivalentProperties(input.resolve("expected/target"), output.toPath());
    } else if (assetPath.endsWith(".stringsdict")) {
      assertEquivalentApplePluralResources(input.resolve("expected/target"), output.toPath());
    } else if (assetPath.endsWith(".xtb")) {
      assertEquivalentXmlResources(input.resolve("expected/target"), output.toPath());
    } else {
      checkDirectoriesContainSameContent(input.resolve("expected/target").toFile(), output);
    }
  }

  private void assertEquivalentXmlResources(Path expected, Path actual) throws IOException {
    assertEquivalentXmlCatalogs(expected, actual, ".xtb");
  }

  private void assertEquivalentXmlCatalogs(Path expected, Path actual, String assetPath)
      throws IOException {
    LocalizationFileFormat format =
        assetPath.endsWith(".xtb") ? LocalizationFileFormat.XTB : LocalizationFileFormat.RESX;
    try (var expectedFiles = Files.walk(expected)) {
      for (Path expectedFile : expectedFiles.filter(Files::isRegularFile).toList()) {
        Path actualFile = actual.resolve(expected.relativize(expectedFile));
        assertTrue("Missing localized file: " + actualFile, Files.exists(actualFile));
        LocalizationCatalog expectedCatalog =
            LocalizationFileConverters.parse(format, Files.readAllBytes(expectedFile));
        LocalizationCatalog actualCatalog =
            LocalizationFileConverters.parse(format, Files.readAllBytes(actualFile));
        assertEquals(expectedFile.toString(), expectedCatalog.locale(), actualCatalog.locale());
        assertEquals(expectedFile.toString(), expectedCatalog.messages(), actualCatalog.messages());
      }
    }
  }

  private void assertEquivalentApplePluralResources(Path expected, Path actual) throws IOException {
    try (var expectedFiles = Files.walk(expected)) {
      for (Path expectedFile : expectedFiles.filter(Files::isRegularFile).toList()) {
        Path actualFile = actual.resolve(expected.relativize(expectedFile));
        assertTrue("Missing localized file: " + actualFile, Files.exists(actualFile));
        LocalizationCatalog expectedCatalog =
            LocalizationFileConverters.parse(
                LocalizationFileFormat.APPLE_STRINGSDICT, Files.readAllBytes(expectedFile));
        LocalizationCatalog actualCatalog =
            LocalizationFileConverters.parse(
                LocalizationFileFormat.APPLE_STRINGSDICT, Files.readAllBytes(actualFile));
        assertEquals(
            expectedFile.toString(),
            expectedCatalog.messages().keySet(),
            actualCatalog.messages().keySet());
        String locale = expectedFile.getParent().getFileName().toString().replace(".lproj", "");
        for (var entry : expectedCatalog.messages().entrySet()) {
          LocalizationMessage expectedMessage = entry.getValue();
          LocalizationMessage actualMessage = actualCatalog.messages().get(entry.getKey());
          assertNotNull(actualMessage);
          for (String category : PluralRuleService.getKeywordsForLanguageTag(locale)) {
            assertEquals(
                expectedFile + " " + entry.getKey() + "#" + category,
                expectedMessage.variants().get(category),
                actualMessage.variants().get(category));
          }
        }
      }
    }
  }

  private void assertEquivalentProperties(Path expected, Path actual) throws IOException {
    try (var expectedFiles = Files.walk(expected)) {
      for (Path expectedFile : expectedFiles.filter(Files::isRegularFile).toList()) {
        Path actualFile = actual.resolve(expected.relativize(expectedFile));
        assertTrue("Missing localized file: " + actualFile, Files.exists(actualFile));
        Properties expectedValues = new Properties();
        Properties actualValues = new Properties();
        try (var reader = Files.newBufferedReader(expectedFile)) {
          expectedValues.load(reader);
        }
        try (var reader = Files.newBufferedReader(actualFile)) {
          actualValues.load(reader);
        }
        assertEquals(expectedFile.toString(), expectedValues, actualValues);
      }
    }
  }

  private void importTranslationsFromDataset(Path dataset, Long assetId, String locale)
      throws IOException {
    Path xliff = dataset.resolve("input/translations/source-xliff_" + locale + ".xliff");
    portableTestImportService.importXLIFF(assetId, Files.readString(xliff), true);
  }

  @Test
  public void pullPropertiesJava() throws Exception {

    Repository repository = createTestRepoUsingRepoService();

    getL10nJCommander()
        .run(
            "push",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-ft",
            "PROPERTIES_JAVA");

    Asset asset = assetClient.getAssetByPathAndRepositoryId("demo.properties", repository.getId());
    importTranslations(asset.getId(), "source-xliff_", "fr-FR");
    importTranslations(asset.getId(), "source-xliff_", "ja-JP");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getTargetTestDir("target").getAbsolutePath(),
            "-ft",
            "PROPERTIES_JAVA");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source_modified").getAbsolutePath(),
            "-t",
            getTargetTestDir("target_modified").getAbsolutePath(),
            "-ft",
            "PROPERTIES_JAVA");

    checkExpectedGeneratedResources();
  }

  @Test
  public void pullPropertiesNoBasenameEnUs() throws Exception {

    Repository repository = createTestRepoUsingRepoService();

    getL10nJCommander()
        .run(
            "push",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-ft",
            "PROPERTIES_NOBASENAME",
            "-sl",
            "en-US");

    Asset asset = assetClient.getAssetByPathAndRepositoryId("en-US.properties", repository.getId());
    importTranslations(asset.getId(), "source-xliff_", "fr-FR");
    importTranslations(asset.getId(), "source-xliff_", "ja-JP");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getTargetTestDir("target").getAbsolutePath(),
            "-ft",
            "PROPERTIES_NOBASENAME",
            "-sl",
            "en-US");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source_modified").getAbsolutePath(),
            "-t",
            getTargetTestDir("target_modified").getAbsolutePath(),
            "-ft",
            "PROPERTIES_NOBASENAME",
            "-sl",
            "en-US");

    checkExpectedGeneratedResources();
  }

  @Test
  public void leveraging() throws Exception {

    Repository repository = createTestRepoUsingRepoService();

    getL10nJCommander()
        .run(
            "push",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-ft",
            "PROPERTIES_NOBASENAME");

    Asset asset = assetClient.getAssetByPathAndRepositoryId("en.properties", repository.getId());
    importTranslations(asset.getId(), "source-xliff_", "fr-FR");
    importTranslations(asset.getId(), "source-xliff_", "ja-JP");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getTargetTestDir("target").getAbsolutePath(),
            "-ft",
            "PROPERTIES_NOBASENAME");

    logger.debug("Change text unit to test leveraging");

    getL10nJCommander()
        .run(
            "push",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source_modified").getAbsolutePath(),
            "-ft",
            "PROPERTIES_NOBASENAME");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source_modified").getAbsolutePath(),
            "-t",
            getTargetTestDir("target_modified").getAbsolutePath(),
            "-ft",
            "PROPERTIES_NOBASENAME");

    checkExpectedGeneratedResources();
  }

  @Test
  public void localeMapping() throws Exception {

    Repository repository = createTestRepoUsingRepoService();

    getL10nJCommander()
        .run(
            "push",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath());

    Asset asset =
        assetClient.getAssetByPathAndRepositoryId("source-xliff.xliff", repository.getId());
    importTranslations(asset.getId(), "source-xliff_", "fr-FR");
    importTranslations(asset.getId(), "source-xliff_", "ja-JP");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getTargetTestDir("target").getAbsolutePath(),
            "-lm",
            "fr:fr-FR,fr-FR:fr-FR,ja:ja-JP",
            "-lmt",
            "MAP_ONLY");

    checkExpectedGeneratedResources();
  }

  @Test
  public void localeMappingParallel() throws Exception {

    Repository repository = createTestRepoUsingRepoService();

    getL10nJCommander()
        .run(
            "push",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath());

    Asset asset =
        assetClient.getAssetByPathAndRepositoryId("source-xliff.xliff", repository.getId());
    importTranslations(asset.getId(), "source-xliff_", "fr-FR");
    importTranslations(asset.getId(), "source-xliff_", "ja-JP");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getTargetTestDir("target").getAbsolutePath(),
            "-lm",
            "fr:fr-FR,fr-FR:fr-FR,ja:ja-JP",
            "-lmt",
            "MAP_ONLY",
            "--parallel");

    checkExpectedGeneratedResources();
  }

  @Test
  public void assetMapping() throws Exception {
    Repository repository = createTestRepoUsingRepoService();

    getL10nJCommander()
        .run(
            "push",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath());

    Asset asset =
        assetClient.getAssetByPathAndRepositoryId("source-xliff.xliff", repository.getId());
    importTranslations(asset.getId(), "source-xliff_", "fr-FR");
    importTranslations(asset.getId(), "source-xliff_", "ja-JP");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("mapping").getAbsolutePath(),
            "-t",
            getTargetTestDir("target").getAbsolutePath(),
            "-am",
            "mapping-xliff.xliff:source-xliff.xliff");

    checkExpectedGeneratedResources();
  }

  @Test
  public void assetMappingParallel() throws Exception {
    Repository repository = createTestRepoUsingRepoService();

    getL10nJCommander()
        .run(
            "push",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath());

    Asset asset =
        assetClient.getAssetByPathAndRepositoryId("source-xliff.xliff", repository.getId());
    importTranslations(asset.getId(), "source-xliff_", "fr-FR");
    importTranslations(asset.getId(), "source-xliff_", "ja-JP");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("mapping").getAbsolutePath(),
            "-t",
            getTargetTestDir("target").getAbsolutePath(),
            "-am",
            "mapping-xliff.xliff:source-xliff.xliff",
            "--parallel");

    checkExpectedGeneratedResources();
  }

  @Test
  public void pullAndroidStrings() throws Exception {

    Repository repository = createTestRepoUsingRepoService();

    getL10nJCommander()
        .run(
            "push",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath());

    Asset asset =
        assetClient.getAssetByPathAndRepositoryId("res/values/strings.xml", repository.getId());
    importTranslations(asset.getId(), "source-xliff_", "fr-FR");
    importTranslations(asset.getId(), "source-xliff_", "ja-JP");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getTargetTestDir("target").getAbsolutePath());

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getTargetTestDir("removeDescription").getAbsolutePath(),
            "-fo",
            "removeDescription=true");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source_modified").getAbsolutePath(),
            "-t",
            getTargetTestDir("target_modified").getAbsolutePath());

    checkExpectedGeneratedResources();
  }

  @Test
  public void pullAndroidStringsSkipEmpty() throws Exception {

    Repository repository = createTestRepoUsingRepoService();

    getL10nJCommander()
        .run(
            "push",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath());

    Asset asset =
        assetClient.getAssetByPathAndRepositoryId("res/values/strings.xml", repository.getId());

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getTargetTestDir("target").getAbsolutePath(),
            "-fo",
            "postEmptyResourcesToEmptyFile=true",
            "postRemoveTranslatableFalse=true",
            "removeDescription=true",
            "--inheritance-mode",
            "REMOVE_UNTRANSLATED",
            "--skip-empty-output");

    checkExpectedGeneratedResources();
  }

  @Test
  public void pullAndroidStringsSkipEmptyParallel() throws Exception {

    Repository repository = createTestRepoUsingRepoService();

    getL10nJCommander()
        .run(
            "push",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath());

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getTargetTestDir("target").getAbsolutePath(),
            "-fo",
            "postEmptyResourcesToEmptyFile=true",
            "postRemoveTranslatableFalse=true",
            "removeDescription=true",
            "--inheritance-mode",
            "REMOVE_UNTRANSLATED",
            "--skip-empty-output",
            "--parallel");

    checkExpectedGeneratedResources();
  }

  @Test
  public void pullMacStrings() throws Exception {

    Repository repository = createTestRepoUsingRepoService();

    getL10nJCommander()
        .run(
            "push",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath());

    Asset asset =
        assetClient.getAssetByPathAndRepositoryId(
            "en.lproj/Localizable.strings", repository.getId());
    importTranslations(asset.getId(), "source-xliff_", "fr-FR");
    importTranslations(asset.getId(), "source-xliff_", "ja-JP");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getTargetTestDir("target").getAbsolutePath());

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source_modified").getAbsolutePath(),
            "-t",
            getTargetTestDir("target_modified").getAbsolutePath());

    checkExpectedGeneratedResources();
  }

  @Test
  public void pullMacStringsdict() throws Exception {

    Repository repository = createTestRepoUsingRepoService();

    getL10nJCommander()
        .run(
            "push",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath());

    Asset asset =
        assetClient.getAssetByPathAndRepositoryId(
            "en.lproj/Localizable.stringsdict", repository.getId());
    importTranslations(asset.getId(), "source-xliff_", "fr-FR");
    importTranslations(asset.getId(), "source-xliff_", "ja-JP");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getTargetTestDir("target").getAbsolutePath());

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source_modified").getAbsolutePath(),
            "-t",
            getTargetTestDir("target_modified").getAbsolutePath());

    checkExpectedGeneratedResources();
  }

  @Test
  public void pullResw() throws Exception {

    Repository repository = createTestRepoUsingRepoService();

    getL10nJCommander()
        .run(
            "push",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath());

    Asset asset =
        assetClient.getAssetByPathAndRepositoryId("en/Resources.resw", repository.getId());
    importTranslations(asset.getId(), "source-xliff_", "fr-FR");
    importTranslations(asset.getId(), "source-xliff_", "ja-JP");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getTargetTestDir("target").getAbsolutePath(),
            "-lm",
            "fr:fr-FR,fr-CA:fr-CA,ja:ja-JP",
            "-lmt",
            "MAP_ONLY");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source_modified").getAbsolutePath(),
            "-t",
            getTargetTestDir("target_modified").getAbsolutePath(),
            "-lm",
            "fr:fr-FR,fr-CA:fr-CA,ja:ja-JP",
            "-lmt",
            "MAP_ONLY");

    checkExpectedGeneratedResources();
  }

  @Test
  public void pullResx() throws Exception {

    Repository repository = createTestRepoUsingRepoService();

    getL10nJCommander()
        .run(
            "push",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath());

    Asset asset = assetClient.getAssetByPathAndRepositoryId("Test.resx", repository.getId());
    importTranslations(asset.getId(), "source-xliff_", "fr-FR");
    importTranslations(asset.getId(), "source-xliff_", "ja-JP");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getTargetTestDir("target").getAbsolutePath());

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source_modified").getAbsolutePath(),
            "-t",
            getTargetTestDir("target_modified").getAbsolutePath());

    checkExpectedGeneratedResources();
  }

  @Test
  public void pullResxSourceRegex() throws Exception {

    Repository repository = createTestRepoUsingRepoService();

    getL10nJCommander()
        .run(
            "push",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-sr",
            "Localization\\.resx|Test\\.resx");

    Asset asset =
        assetClient.getAssetByPathAndRepositoryId("Localization.resx", repository.getId());
    importTranslations(asset.getId(), "source-xliff_Localization_", "fr-FR");
    importTranslations(asset.getId(), "source-xliff_Localization_", "ja-JP");
    asset = assetClient.getAssetByPathAndRepositoryId("Test.resx", repository.getId());
    importTranslations(asset.getId(), "source-xliff_Test_", "fr-FR");
    importTranslations(asset.getId(), "source-xliff_Test_", "ja-JP");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getTargetTestDir("target").getAbsolutePath(),
            "-sr",
            "Localization\\.resx|Test\\.resx");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source_modified").getAbsolutePath(),
            "-t",
            getTargetTestDir("target_modified").getAbsolutePath(),
            "-sr",
            "Localization\\.resx|Test\\.resx");

    checkExpectedGeneratedResources();
  }

  @Test
  public void pullDirectoryIncludeExcludePatterns() throws Exception {

    Repository repository = createTestRepoUsingRepoService();

    getL10nJCommander()
        .run(
            "push",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "--dir-path-include-patterns",
            "*/resources",
            "other",
            "--dir-path-exclude-patterns",
            "b/resources");

    Asset asset =
        assetClient.getAssetByPathAndRepositoryId(
            "a/resources/demo.properties", repository.getId());
    importTranslations(asset.getId(), "source-xliff_", "fr-FR");
    importTranslations(asset.getId(), "source-xliff_", "ja-JP");
    asset =
        assetClient.getAssetByPathAndRepositoryId(
            "c/resources/demo.properties", repository.getId());
    importTranslations(asset.getId(), "source-xliff_", "fr-FR");
    importTranslations(asset.getId(), "source-xliff_", "ja-JP");
    asset = assetClient.getAssetByPathAndRepositoryId("other/demo.properties", repository.getId());
    importTranslations(asset.getId(), "source-xliff_", "fr-FR");
    importTranslations(asset.getId(), "source-xliff_", "ja-JP");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getTargetTestDir("target").getAbsolutePath(),
            "--dir-path-include-patterns",
            "*/resources",
            "other",
            "--dir-path-exclude-patterns",
            "b/resources");

    checkExpectedGeneratedResources();
  }

  @Test
  public void testLatestTMTextUnitVariant() throws Exception {
    Repository repository1 = createTestRepoUsingRepoService("repo1", false);
    Repository repository2 = createTestRepoUsingRepoService("repo2", false);

    getL10nJCommander()
        .run(
            "push",
            "-r",
            repository1.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath());
    getL10nJCommander()
        .run(
            "push",
            "-r",
            repository2.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath());

    Asset asset1 =
        assetClient.getAssetByPathAndRepositoryId("source-xliff.xliff", repository1.getId());
    importTranslations(asset1.getId(), "source-xliff_", "fr-FR");

    logger.debug("Test findLatestTMTextUnitVariant");
    TMTextUnitVariant latestTmTextUnitVariantOfRepository1 =
        tmTextUnitVariantRepository.findTopByTmTextUnitTmIdOrderByCreatedDateDesc(
            repository1.getTm().getId());
    assertNotNull(
        "should have TMTextUnitVariant from imports above", latestTmTextUnitVariantOfRepository1);

    logger.debug("Test findLatestTMTextUnitVariant again after a translation is added to other TM");
    Asset asset2 =
        assetClient.getAssetByPathAndRepositoryId("source-xliff.xliff", repository2.getId());
    importTranslations(asset2.getId(), "source-xliff_", "fr-FR");
    TMTextUnitVariant tmTextUnitVariant =
        tmTextUnitVariantRepository.findTopByTmTextUnitTmIdOrderByCreatedDateDesc(
            repository1.getTm().getId());
    assertEquals(
        "should have returned the same TMTextUnitVariant as above latestTmTextUnitVariantOfRepository1",
        latestTmTextUnitVariantOfRepository1.getId(),
        tmTextUnitVariant.getId());
  }

  @Test
  public void pullXcodeXliff() throws Exception {

    Repository repository = createTestRepoUsingRepoService();

    getL10nJCommander()
        .run(
            "push",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-ft",
            "XCODE_XLIFF");

    Asset asset = assetClient.getAssetByPathAndRepositoryId("en.xliff", repository.getId());
    importTranslations(asset.getId(), "source-xliff_", "fr-FR");
    importTranslations(asset.getId(), "source-xliff_", "ja-JP");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getTargetTestDir("target").getAbsolutePath(),
            "-lm",
            "fr:fr-FR,fr-CA:fr-CA,ja:ja-JP",
            "-lmt",
            "MAP_ONLY",
            "-ft",
            "XCODE_XLIFF");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source_modified").getAbsolutePath(),
            "-t",
            getTargetTestDir("target_modified").getAbsolutePath(),
            "-lm",
            "fr:fr-FR,fr-CA:fr-CA,ja:ja-JP",
            "-lmt",
            "MAP_ONLY",
            "-ft",
            "XCODE_XLIFF");

    checkExpectedGeneratedResources();
  }

  @Test
  public void pullPo() throws Exception {

    Repository repository = createTestRepoUsingRepoService();

    getL10nJCommander()
        .run(
            "push",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath());

    Asset asset =
        assetClient.getAssetByPathAndRepositoryId("LC_MESSAGES/messages.pot", repository.getId());
    importTranslations(asset.getId(), "source-xliff_", "fr-FR");
    importTranslations(asset.getId(), "source-xliff_", "ja-JP");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getTargetTestDir("target").getAbsolutePath(),
            "-lm",
            "fr:fr-FR,fr-CA:fr-CA,ja:ja-JP",
            "-lmt",
            "MAP_ONLY");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source_modified").getAbsolutePath(),
            "-t",
            getTargetTestDir("target_modified").getAbsolutePath(),
            "-lm",
            "fr:fr-FR,fr-CA:fr-CA,ja:ja-JP",
            "-lmt",
            "MAP_ONLY");

    checkExpectedGeneratedResources();
  }

  @Test
  public void pullHtml() throws Exception {

    Repository repository = createTestRepoUsingRepoService();

    getL10nJCommander()
        .run(
            "push",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-ft",
            "HTML_ALPHA",
            "-fo",
            "processImageUrls=true");

    Asset asset = assetClient.getAssetByPathAndRepositoryId("demo.html", repository.getId());
    importTranslations(asset.getId(), "source-xliff_", "fr-FR");
    importTranslations(asset.getId(), "source-xliff_", "ja-JP");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getTargetTestDir("target").getAbsolutePath(),
            "-lm",
            "fr:fr-FR,fr-CA:fr-CA,ja:ja-JP",
            "-lmt",
            "MAP_ONLY",
            "-ft",
            "HTML_ALPHA",
            "-fo",
            "processImageUrls=true");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source_modified").getAbsolutePath(),
            "-t",
            getTargetTestDir("target_modified").getAbsolutePath(),
            "-lm",
            "fr:fr-FR,fr-CA:fr-CA,ja:ja-JP",
            "-lmt",
            "MAP_ONLY",
            "-ft",
            "HTML_ALPHA",
            "-fo",
            "processImageUrls=true");

    checkExpectedGeneratedResources();
  }

  @Test
  public void removeUntranslated() throws Exception {

    Repository repository = createTestRepoUsingRepoService();

    getL10nJCommander()
        .run(
            "push",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath());

    Asset asset =
        assetClient.getAssetByPathAndRepositoryId("LC_MESSAGES/messages.pot", repository.getId());
    importTranslations(asset.getId(), "source-xliff_", "fr-FR");
    importTranslations(asset.getId(), "source-xliff_", "ja-JP");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getTargetTestDir("target").getAbsolutePath(),
            "-lm",
            "fr:fr-FR,ja:ja-JP",
            "-lmt",
            "MAP_ONLY",
            "--inheritance-mode",
            "REMOVE_UNTRANSLATED");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source_modified").getAbsolutePath(),
            "-t",
            getTargetTestDir("target_modified").getAbsolutePath(),
            "-lm",
            "fr:fr-FR,ja:ja-JP",
            "-lmt",
            "MAP_ONLY",
            "--inheritance-mode",
            "REMOVE_UNTRANSLATED");

    checkExpectedGeneratedResources();
  }

  @Test
  public void onlyApproved() throws Exception {

    Repository repository = createTestRepoUsingRepoService();

    getL10nJCommander()
        .run(
            "push",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath());

    Asset asset = assetClient.getAssetByPathAndRepositoryId("test.xliff", repository.getId());
    importTranslations(asset.getId(), "source-xliff_", "fr-FR");
    importTranslations(asset.getId(), "source-xliff_", "ja-JP");

    updateTranslationsStatus(asset.getId(), TMTextUnitVariant.Status.REVIEW_NEEDED, "fr-FR");
    updateTranslationsStatus(asset.getId(), TMTextUnitVariant.Status.REVIEW_NEEDED, "ja-JP");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getTargetTestDir("target").getAbsolutePath(),
            "-lm",
            "fr:fr-FR,fr-CA:fr-CA,ja:ja-JP",
            "-lmt",
            "MAP_ONLY",
            "--inheritance-mode",
            "REMOVE_UNTRANSLATED",
            "--status",
            "ACCEPTED");

    updateTranslationsStatus(asset.getId(), TMTextUnitVariant.Status.APPROVED, "fr-FR");
    updateTranslationsStatus(asset.getId(), TMTextUnitVariant.Status.APPROVED, "ja-JP");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source_modified").getAbsolutePath(),
            "-t",
            getTargetTestDir("target_modified").getAbsolutePath(),
            "-lm",
            "fr:fr-FR,fr-CA:fr-CA,ja:ja-JP",
            "-lmt",
            "MAP_ONLY",
            "--inheritance-mode",
            "REMOVE_UNTRANSLATED",
            "--status",
            "ACCEPTED");

    checkExpectedGeneratedResources();
  }

  @Test
  public void pullXtb() throws Exception {
    Repository repository = createTestRepoUsingRepoService();

    getL10nJCommander()
        .run(
            "push",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-sl",
            "en-US");

    Asset asset =
        assetClient.getAssetByPathAndRepositoryId("Resources-en-US.xtb", repository.getId());
    importTranslations(asset.getId(), "source-xliff_", "fr-FR");
    importTranslations(asset.getId(), "source-xliff_", "ja-JP");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getTargetTestDir("target").getAbsolutePath(),
            "-sl",
            "en-US");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source_modified").getAbsolutePath(),
            "-t",
            getTargetTestDir("target_modified").getAbsolutePath(),
            "-sl",
            "en-US");

    checkExpectedGeneratedResources();
  }

  @Test
  public void pullCsv() throws Exception {
    Repository repository = createTestRepoUsingRepoService();

    getL10nJCommander()
        .run(
            "push",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath());

    Asset asset = assetClient.getAssetByPathAndRepositoryId("demo.csv", repository.getId());

    importTranslations(asset.getId(), "source-xliff_", "fr-FR");
    importTranslations(asset.getId(), "source-xliff_", "ja-JP");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getTargetTestDir("target").getAbsolutePath());

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source_modified").getAbsolutePath(),
            "-t",
            getTargetTestDir("target_modified").getAbsolutePath());

    checkExpectedGeneratedResources();
  }

  @Test
  public void pullCsvAdobeMagento() throws Exception {
    Repository repository = createTestRepoUsingRepoService();

    getL10nJCommander()
        .run(
            "push",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-ft",
            "CSV_ADOBE_MAGENTO",
            "-sl",
            "en_US");

    Asset asset = assetClient.getAssetByPathAndRepositoryId("i18n/en_US.csv", repository.getId());

    importTranslations(asset.getId(), "source-xliff_", "fr-FR");
    importTranslations(asset.getId(), "source-xliff_", "ja-JP");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getTargetTestDir("target").getAbsolutePath(),
            "-ft",
            "CSV_ADOBE_MAGENTO",
            "-sl",
            "en_US");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source_modified").getAbsolutePath(),
            "-t",
            getTargetTestDir("target_modified").getAbsolutePath(),
            "-ft",
            "CSV_ADOBE_MAGENTO",
            "-sl",
            "en_US");

    checkExpectedGeneratedResources();
  }

  @Test
  public void pullJS() throws Exception {
    Repository repository = createTestRepoUsingRepoService();

    getL10nJCommander()
        .run(
            "push",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath());

    Asset asset = assetClient.getAssetByPathAndRepositoryId("en.js", repository.getId());

    importTranslations(asset.getId(), "source-xliff_", "fr-FR");
    importTranslations(asset.getId(), "source-xliff_", "ja-JP");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getTargetTestDir("target").getAbsolutePath(),
            "-lm",
            "fr:fr-FR,ja:ja-JP",
            "-lmt",
            "MAP_ONLY");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source_modified").getAbsolutePath(),
            "-t",
            getTargetTestDir("target_modified").getAbsolutePath(),
            "-lm",
            "fr:fr-FR,ja:ja-JP",
            "-lmt",
            "MAP_ONLY");

    checkExpectedGeneratedResources();
  }

  @Test
  public void pullTS() throws Exception {
    Repository repository = createTestRepoUsingRepoService();

    getL10nJCommander()
        .run(
            "push",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath());

    Asset asset = assetClient.getAssetByPathAndRepositoryId("en.ts", repository.getId());

    importTranslations(asset.getId(), "source-xliff_", "fr-FR");
    importTranslations(asset.getId(), "source-xliff_", "ja-JP");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getTargetTestDir("target").getAbsolutePath(),
            "-lm",
            "fr:fr-FR,ja:ja-JP",
            "-lmt",
            "MAP_ONLY");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source_modified").getAbsolutePath(),
            "-t",
            getTargetTestDir("target_modified").getAbsolutePath(),
            "-lm",
            "fr:fr-FR,ja:ja-JP",
            "-lmt",
            "MAP_ONLY");

    checkExpectedGeneratedResources();
  }

  @Test
  public void pullJson() throws Exception {

    Repository repository = createTestRepoUsingRepoService();

    getL10nJCommander()
        .run(
            "push",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-ft",
            "JSON");

    Asset asset = assetClient.getAssetByPathAndRepositoryId("demo.json", repository.getId());
    importTranslations(asset.getId(), "source-xliff_", "fr-FR");
    importTranslations(asset.getId(), "source-xliff_", "ja-JP");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getTargetTestDir("target").getAbsolutePath(),
            "-ft",
            "JSON");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source_modified").getAbsolutePath(),
            "-t",
            getTargetTestDir("target_modified").getAbsolutePath(),
            "-ft",
            "JSON");

    checkExpectedGeneratedResources();
  }

  @Test
  public void pullJsonNobasename() throws Exception {

    Repository repository = createTestRepoUsingRepoService();

    getL10nJCommander()
        .run(
            "push",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-ft",
            "JSON_NOBASENAME");

    Asset asset = assetClient.getAssetByPathAndRepositoryId("en.json", repository.getId());
    importTranslations(asset.getId(), "source-xliff_", "fr-FR");
    importTranslations(asset.getId(), "source-xliff_", "ja-JP");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getTargetTestDir("target").getAbsolutePath(),
            "-ft",
            "JSON_NOBASENAME");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source_modified").getAbsolutePath(),
            "-t",
            getTargetTestDir("target_modified").getAbsolutePath(),
            "-ft",
            "JSON_NOBASENAME");

    checkExpectedGeneratedResources();
  }

  @Test
  public void pullJsonWithNote() throws Exception {

    Repository repository = createTestRepoUsingRepoService();

    getL10nJCommander()
        .run(
            "push",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-fo",
            "noteKeyPattern=note",
            "extractAllPairs=false",
            "exceptions=string",
            "-ft",
            "JSON");

    Asset asset = assetClient.getAssetByPathAndRepositoryId("demo.json", repository.getId());
    importTranslations(asset.getId(), "source-xliff_", "fr-FR");
    importTranslations(asset.getId(), "source-xliff_", "ja-JP");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getTargetTestDir("target").getAbsolutePath(),
            "-fo",
            "noteKeyPattern=note",
            "extractAllPairs=false",
            "exceptions=string",
            "-ft",
            "JSON");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source_modified").getAbsolutePath(),
            "-t",
            getTargetTestDir("target_modified").getAbsolutePath(),
            "-fo",
            "noteKeyPattern=note",
            "extractAllPairs=false",
            "exceptions=string",
            "-ft",
            "JSON");

    checkExpectedGeneratedResources();
  }

  @Test
  public void pullJsonDefaultFormatJs() throws Exception {

    Repository repository = createTestRepoUsingRepoService();

    getL10nJCommander()
        .run(
            "push",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-ft",
            "FORMATJS_JSON_NOBASENAME");

    Asset asset = assetClient.getAssetByPathAndRepositoryId("en.json", repository.getId());
    importTranslations(asset.getId(), "source-xliff_", "fr-FR");
    importTranslations(asset.getId(), "source-xliff_", "ja-JP");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getTargetTestDir("target").getAbsolutePath(),
            "-ft",
            "FORMATJS_JSON_NOBASENAME");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source_modified").getAbsolutePath(),
            "-t",
            getTargetTestDir("target_modified").getAbsolutePath(),
            "-ft",
            "FORMATJS_JSON_NOBASENAME");

    checkExpectedGeneratedResources();
  }

  @Test
  public void pullWithNoSourceFromAuthoringBranch() throws Exception {
    Repository repository = createTestRepoUsingRepoService();

    getL10nJCommander()
        .run(
            "push",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-ft",
            "FORMATJS_JSON_NOBASENAME");

    getL10nJCommander()
        .run(
            "push",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source_with_branch_string").getAbsolutePath(),
            "-ft",
            "FORMATJS_JSON_NOBASENAME",
            "--branch",
            "authoring/checkout");

    Asset asset = assetClient.getAssetByPathAndRepositoryId("en.json", repository.getId());
    TMTextUnit authoredTextUnit =
        tmTextUnitRepository.findFirstByAssetIdAndName(asset.getId(), "checkout.pay");
    tmService.addCurrentTMTextUnitVariant(
        authoredTextUnit.getId(), localeService.findByBcp47Tag("fr-FR").getId(), "Payer");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getTargetTestDir("target_without_flag").getAbsolutePath(),
            "-ft",
            "FORMATJS_JSON_NOBASENAME");

    String outputWithoutFlag =
        java.nio.file.Files.readString(
            getTargetTestDir("target_without_flag").toPath().resolve("fr-FR.json"));
    Assertions.assertThat(outputWithoutFlag).doesNotContain("checkout.pay");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getTargetTestDir("target_with_flag").getAbsolutePath(),
            "-ft",
            "FORMATJS_JSON_NOBASENAME",
            "--pull-with-no-source-branches",
            "authoring/checkout");

    String outputWithFlag =
        java.nio.file.Files.readString(
            getTargetTestDir("target_with_flag").toPath().resolve("fr-FR.json"));
    var checkoutPay = new ObjectMapper().readTree(outputWithFlag).get("checkout.pay");
    Assertions.assertThat(checkoutPay).isNotNull();
    Assertions.assertThat(checkoutPay.get("defaultMessage").asText()).isEqualTo("Payer");
    Assertions.assertThat(checkoutPay.get("description").asText())
        .isEqualTo("Checkout primary action");

    String unrelatedAssetOutput =
        java.nio.file.Files.readString(
            getTargetTestDir("target_with_flag")
                .toPath()
                .resolve("settings")
                .resolve("fr-FR.json"));
    Assertions.assertThat(unrelatedAssetOutput)
        .contains("settings.title")
        .doesNotContain("checkout.pay");
  }

  @Test
  public void pullJsonDefaultFormatJsRemoveUntranslated() throws Exception {
    Repository repository = createTestRepoUsingRepoService();

    getL10nJCommander()
        .run(
            "push",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-ft",
            "FORMATJS_JSON_NOBASENAME");

    Asset asset = assetClient.getAssetByPathAndRepositoryId("en.json", repository.getId());
    importTranslations(asset.getId(), "source-xliff_", "fr-FR");
    importTranslations(asset.getId(), "source-xliff_", "ja-JP");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getTargetTestDir("target").getAbsolutePath(),
            "-ft",
            "FORMATJS_JSON_NOBASENAME",
            "--inheritance-mode",
            "REMOVE_UNTRANSLATED");

    checkExpectedGeneratedResources();
  }

  @Test
  public void pullJsonFromChromeExtension() throws Exception {

    Repository repository = createTestRepoUsingRepoService();

    getL10nJCommander()
        .run(
            "push",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-ft",
            "CHROME_EXT_JSON");

    Asset asset =
        assetClient.getAssetByPathAndRepositoryId("_locales/en/messages.json", repository.getId());
    importTranslations(asset.getId(), "source-xliff_", "fr-FR");
    importTranslations(asset.getId(), "source-xliff_", "ja-JP");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getTargetTestDir("target").getAbsolutePath(),
            "-ft",
            "CHROME_EXT_JSON");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source_modified").getAbsolutePath(),
            "-t",
            getTargetTestDir("target_modified").getAbsolutePath(),
            "-ft",
            "CHROME_EXT_JSON");

    checkExpectedGeneratedResources();
  }

  @Test
  public void pullJsonI18NextParser() throws Exception {

    Repository repository = createTestRepoUsingRepoService();

    getL10nJCommander()
        .run(
            "push",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-ft",
            "I18NEXT_PARSER_JSON");

    Asset asset =
        assetClient.getAssetByPathAndRepositoryId("locales/en/demo.json", repository.getId());
    importTranslations(asset.getId(), "source-xliff_", "fr-FR");
    importTranslations(asset.getId(), "source-xliff_", "ja-JP");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getTargetTestDir("target").getAbsolutePath(),
            "-ft",
            "I18NEXT_PARSER_JSON");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source_modified").getAbsolutePath(),
            "-t",
            getTargetTestDir("target_modified").getAbsolutePath(),
            "-ft",
            "I18NEXT_PARSER_JSON");

    checkExpectedGeneratedResources();
  }

  @Test
  public void pullJsonVSCodeExtension() throws Exception {

    Repository repository = createTestRepoUsingRepoService();

    getL10nJCommander()
        .run(
            "push",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-ft",
            "VSCODE_EXTENSION_JSON");

    Asset asset = assetClient.getAssetByPathAndRepositoryId("package.nls.json", repository.getId());
    importTranslations(asset.getId(), "source-xliff_", "fr-FR");
    importTranslations(asset.getId(), "source-xliff_", "ja-JP");

    Asset asset2 =
        assetClient.getAssetByPathAndRepositoryId("l10n/bundle.l10n.json", repository.getId());
    importTranslations(asset2.getId(), "source-xliff_", "fr-FR");
    importTranslations(asset2.getId(), "source-xliff_", "ja-JP");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getTargetTestDir("target").getAbsolutePath(),
            "-ft",
            "VSCODE_EXTENSION_JSON");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source_modified").getAbsolutePath(),
            "-t",
            getTargetTestDir("target_modified").getAbsolutePath(),
            "-ft",
            "VSCODE_EXTENSION_JSON");

    checkExpectedGeneratedResources();
  }

  @Test
  public void pullFullyTranslated() throws Exception {

    Repository repository = createTestRepoUsingRepoService();
    repositoryService.addRepositoryLocale(repository, "en-AU", null, false);

    getL10nJCommander()
        .run(
            "push",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath());

    Asset asset = assetClient.getAssetByPathAndRepositoryId("demo.properties", repository.getId());
    importTranslations(asset.getId(), "source-xliff_", "fr-FR");
    importTranslations(asset.getId(), "source-xliff_", "ja-JP");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getTargetTestDir("target").getAbsolutePath());

    waitForCondition(
        "repo stats must be updated - wait for jp to be fully translated and others to be untranslated",
        () -> {
          com.box.l10n.mojito.rest.entity.Repository repo =
              repositoryClient.getRepositoryById(repository.getId());
          RepositoryStatistic repositoryStatistic = repo.getRepositoryStatistic();

          boolean statsReady =
              repositoryStatistic.getRepositoryLocaleStatistics().stream()
                  .allMatch(
                      repositoryLocaleStatistic -> {
                        if ("ja-JP".equals(repositoryLocaleStatistic.getLocale().getBcp47Tag())) {
                          return repositoryLocaleStatistic.getForTranslationCount() == 0;
                        } else {
                          return repositoryLocaleStatistic.getForTranslationCount() > 0;
                        }
                      });

          return !repositoryStatistic.getRepositoryLocaleStatistics().isEmpty() && statsReady;
        });

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getTargetTestDir("target_fully_translated").getAbsolutePath(),
            "--fully-translated");

    checkExpectedGeneratedResources();
  }

  @Test
  public void pullFullyTranslatedParallel() throws Exception {

    Repository repository = createTestRepoUsingRepoService();
    repositoryService.addRepositoryLocale(repository, "en-AU", null, false);

    getL10nJCommander()
        .run(
            "push",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath());

    Asset asset = assetClient.getAssetByPathAndRepositoryId("demo.properties", repository.getId());
    importTranslations(asset.getId(), "source-xliff_", "fr-FR");
    importTranslations(asset.getId(), "source-xliff_", "ja-JP");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getTargetTestDir("target").getAbsolutePath(),
            "--parallel");

    waitForCondition(
        "repo stats must be updated - wait for jp to be fully translated and others to be untranslated",
        () -> {
          com.box.l10n.mojito.rest.entity.Repository repo =
              repositoryClient.getRepositoryById(repository.getId());
          RepositoryStatistic repositoryStatistic = repo.getRepositoryStatistic();

          boolean statsReady =
              repositoryStatistic.getRepositoryLocaleStatistics().stream()
                  .allMatch(
                      repositoryLocaleStatistic -> {
                        if ("ja-JP".equals(repositoryLocaleStatistic.getLocale().getBcp47Tag())) {
                          return repositoryLocaleStatistic.getForTranslationCount() == 0;
                        } else {
                          return repositoryLocaleStatistic.getForTranslationCount() > 0;
                        }
                      });

          return !repositoryStatistic.getRepositoryLocaleStatistics().isEmpty() && statsReady;
        });

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getTargetTestDir("target_fully_translated").getAbsolutePath(),
            "--fully-translated",
            "--parallel");

    checkExpectedGeneratedResources();
  }

  @Test
  public void recordPullPoPlural() throws Exception {
    Repository repository = createTestRepoUsingRepoService();
    repositoryService.addRepositoryLocale(repository, "ru-RU", null, true);

    String pushCommitHash = "ccaa11";

    logger.debug("Create the base commit that correspond to the initial push");
    getL10nJCommander()
        .run(
            "commit-create",
            "-r",
            repository.getName(),
            "--commit-hash",
            pushCommitHash,
            "--author-email",
            "coder@mail.com",
            "--author-name",
            "coder",
            "--creation-date",
            ZonedDateTime.now().toString());

    logger.debug("Initial push");
    getL10nJCommander()
        .run(
            "push",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "--record-push-run",
            "--commit-hash",
            pushCommitHash);

    logger.debug("Get pushRun for the initial push to test delta later on");
    PushRun pushRun =
        commitService
            .getLastPushRun(ImmutableList.of(pushCommitHash), repository.getId())
            .orElseThrow(() -> new RuntimeException("There must be a push run"));

    logger.debug("Record a first pull run to generate the baseline");
    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getTargetTestDir("target_baseline").getAbsolutePath(),
            "-lm",
            "ru-RU:ru-RU",
            "--record-pull-run",
            "-lmt",
            "MAP_ONLY");

    getL10nJCommander()
        .run(
            "import",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getInputResourcesTestDir("translations").getAbsolutePath(),
            "-lm",
            "ru-RU:ru-RU",
            "-lmt",
            "MAP_ONLY");

    logger.debug("Record a second pull run after translation import");
    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getTargetTestDir("target_translated").getAbsolutePath(),
            "-lm",
            "ru-RU:ru-RU",
            "--record-pull-run",
            "-lmt",
            "MAP_ONLY");

    logger.debug("Simulate commit and linked to pull-run");
    String pullRunHash1 = "ddaa11";
    getL10nJCommander()
        .run(
            "commit-create",
            "-r",
            repository.getName(),
            "--commit-hash",
            pullRunHash1,
            "--author-email",
            "coder@mail.com",
            "--author-name",
            "coder",
            "--creation-date",
            ZonedDateTime.now().toString());
    getL10nJCommander()
        .run(
            "commit-to-pull-run",
            "-r",
            repository.getName(),
            "-i",
            getTargetTestDir("target_translated").getAbsolutePath(),
            "--commit-hash",
            pullRunHash1);

    // For language like cs-CZ or ru-RU, PO file use only 3 plural forms while Mojito store 4 form
    // (CLDR).
    // This leads to the number of recorded entry in the pull run to lower that the number of
    // current translations.
    // This might look strange but is expected
    Locale ruRU = localeService.findByBcp47Tag("ru-RU");
    List<TMTextUnit> tmTextUnits = tmTextUnitRepository.findByTm_id(repository.getTm().getId());

    long countOfCurrentTranslationForRuRU =
        tmTextUnits.stream()
            .map(
                tmTextUnit ->
                    tmTextUnitCurrentVariantRepository.findByLocale_IdAndTmTextUnit_Id(
                        ruRU.getId(), tmTextUnit.getId()))
            .filter(Objects::nonNull)
            .count();
    Assertions.assertThat(countOfCurrentTranslationForRuRU).isEqualTo(4);

    long countOfPullRunTextUnitVariantForRuRU =
        tmTextUnits.stream()
            .flatMap(
                tmTextUnit ->
                    pullRunTextUnitVariantRepository
                        .findByTmTextUnitVariant_TmTextUnitIdAndLocaleId(
                            tmTextUnit.getId(), ruRU.getId())
                        .stream())
            .count();
    Assertions.assertThat(countOfPullRunTextUnitVariantForRuRU).isEqualTo(3);

    PullRun pullRun =
        commitService
            .getLastPullRun(ImmutableList.of(pullRunHash1), repository.getId())
            .orElseThrow(() -> new RuntimeException("There must be a pull run"));

    // Advance the date of the PullRun to make sure the translation is older than the PullRun
    // creation date
    pullRun.setCreatedDate(pullRun.getCreatedDate().plusDays(1));
    pullRunRepository.save(pullRun);

    DeltaResponseDTO deltas =
        deltaService.getDeltasForRuns(
            repository, null, ImmutableList.of(pushRun), ImmutableList.of(pullRun));

    // As long as the translation for the other plural form wasn't changed
    // after the pull recording was done, it shouldn't be included in the
    // delta results.
    Assertions.assertThat(deltas.getTranslationsPerLocale()).isEmpty();

    pullRunNamesToNormalizedValueForTests();
    checkExpectedGeneratedResources();
  }

  @Test
  public void recordPullRunAndOTA() throws Exception {
    Repository repository = createTestRepoUsingRepoService();

    String pushCommitHash = "ccaa11";

    getL10nJCommander()
        .run(
            "commit-create",
            "-r",
            repository.getName(),
            "--commit-hash",
            pushCommitHash,
            "--author-email",
            "coder@mail.com",
            "--author-name",
            "coder",
            "--creation-date",
            ZonedDateTime.now().toString());

    logger.debug("Initial push");
    getL10nJCommander()
        .run(
            "push",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "--record-push-run",
            "--commit-hash",
            pushCommitHash);

    logger.debug("Get pushRun for the initial push to test delta later on");
    PushRun pushRun =
        commitService
            .getLastPushRun(ImmutableList.of(pushCommitHash), repository.getId())
            .orElseThrow(() -> new RuntimeException("There must be a push run"));

    logger.debug("Record a first pull run to generate the baseline");
    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getTargetTestDir("target_baseline").getAbsolutePath(),
            "--record-pull-run");

    logger.debug("Simulate commit and linked to pull-run");
    String pullRunHash1 = "ddaa11";
    getL10nJCommander()
        .run(
            "commit-create",
            "-r",
            repository.getName(),
            "--commit-hash",
            pullRunHash1,
            "--author-email",
            "coder@mail.com",
            "--author-name",
            "coder",
            "--creation-date",
            ZonedDateTime.now().toString());

    getL10nJCommander()
        .run(
            "commit-to-pull-run",
            "-r",
            repository.getName(),
            "-i",
            getTargetTestDir("target_baseline").getAbsolutePath(),
            "--commit-hash",
            pullRunHash1);

    PullRun pullRunBaseline =
        commitService
            .getLastPullRun(ImmutableList.of(pullRunHash1), repository.getId())
            .orElseThrow(() -> new RuntimeException("There must be a pull run"));

    logger.debug(
        "Check that generating a delta yield empty result for the initial push run and baseline pull run");
    DeltaResponseDTO deltaForBaseline =
        deltaService.getDeltasForRuns(
            repository,
            null,
            // so empty() should basically process nothing
            ImmutableList.of(pushRun),
            ImmutableList.of(pullRunBaseline));

    Assertions.assertThat(deltaForBaseline.getTranslationsPerLocale()).isEmpty();

    logger.debug("Import French translations to test delta generation");
    Asset asset = assetClient.getAssetByPathAndRepositoryId("demo.properties", repository.getId());
    importTranslations(asset.getId(), "source-xliff_", "fr-FR");

    logger.debug(
        "Generating a delta for the initial push run and baseline pull run should now return the new French translations");
    DeltaResponseDTO deltaForBaselineToFrenchImport =
        deltaService.getDeltasForRuns(
            repository, null, ImmutableList.of(pushRun), ImmutableList.of(pullRunBaseline));

    Assertions.assertThat(deltaForBaselineToFrenchImport.getTranslationsPerLocale()).hasSize(1);
    checkFrenchTranslationsInDelta(deltaForBaselineToFrenchImport);

    logger.debug("Record a pull run after the French import for later delta testing");
    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getTargetTestDir("target_after_french_import").getAbsolutePath(),
            "--record-pull-run");

    logger.debug("Simulate commit and linked to pull-run");
    String pullRunHash2 = "ddaa22";
    getL10nJCommander()
        .run(
            "commit-create",
            "-r",
            repository.getName(),
            "--commit-hash",
            pullRunHash2,
            "--author-email",
            "coder@mail.com",
            "--author-name",
            "coder",
            "--creation-date",
            ZonedDateTime.now().toString());
    getL10nJCommander()
        .run(
            "commit-to-pull-run",
            "-r",
            repository.getName(),
            "-i",
            getTargetTestDir("target_after_french_import").getAbsolutePath(),
            "--commit-hash",
            pullRunHash2);

    PullRun pullRunAfterFrenchImport =
        commitService
            .getLastPullRun(ImmutableList.of(pullRunHash2), repository.getId())
            .orElseThrow(() -> new RuntimeException("There must be a pull run"));

    logger.debug("Add Japanese translations to test delta generation");
    importTranslations(asset.getId(), "source-xliff_", "ja-JP");

    logger.debug(
        "Generating a delta for the initial push run and baseline pull run should now return the new French and Japanese translations");
    DeltaResponseDTO deltaForBaselineToFrenchAndJapaneseImport =
        deltaService.getDeltasForRuns(
            repository, null, ImmutableList.of(pushRun), ImmutableList.of(pullRunBaseline));

    Assertions.assertThat(deltaForBaselineToFrenchAndJapaneseImport.getTranslationsPerLocale())
        .hasSize(2);
    checkFrenchTranslationsInDelta(deltaForBaselineToFrenchAndJapaneseImport);
    checkJapaneseTranslationsInDelta(deltaForBaselineToFrenchAndJapaneseImport);

    logger.debug(
        "Check that the delta for the pull run generated after the French import only return Japanese translations");
    DeltaResponseDTO deltaForAfterFrenchImportToFrenchAndJapaneseImport =
        deltaService.getDeltasForRuns(
            repository,
            null,
            ImmutableList.of(pushRun),
            ImmutableList.of(pullRunAfterFrenchImport));

    Assertions.assertThat(
            deltaForAfterFrenchImportToFrenchAndJapaneseImport.getTranslationsPerLocale())
        .hasSize(1);
    checkJapaneseTranslationsInDelta(deltaForAfterFrenchImportToFrenchAndJapaneseImport);

    logger.debug("Record a pull run after the French and Japanese imports for later delta testing");
    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getTargetTestDir("target_after_french_and_japanese_imports").getAbsolutePath(),
            "--record-pull-run");

    logger.debug("Simulate commit and linked to pull-run");
    String pullRunHash3 = "ddaa33";
    getL10nJCommander()
        .run(
            "commit-create",
            "-r",
            repository.getName(),
            "--commit-hash",
            pullRunHash3,
            "--author-email",
            "coder@mail.com",
            "--author-name",
            "coder",
            "--creation-date",
            ZonedDateTime.now().toString());
    getL10nJCommander()
        .run(
            "commit-to-pull-run",
            "-r",
            repository.getName(),
            "-i",
            getTargetTestDir("target_after_french_and_japanese_imports").getAbsolutePath(),
            "--commit-hash",
            pullRunHash3);

    PullRun pullRunAfterFrenchAndJapaneseImports =
        commitService
            .getLastPullRun(ImmutableList.of(pullRunHash3), repository.getId())
            .orElseThrow(() -> new RuntimeException("There must be a pull run"));

    logger.debug("Update one translation in French and remove one in Japanese");
    Locale frFR = localeService.findByBcp47Tag("fr-FR");
    Locale jaJP = localeService.findByBcp47Tag("ja-JP");
    TMTextUnit tmTextUnit =
        tmTextUnitRepository.findFirstByAssetIdAndName(asset.getId(), "1_month_duration");
    tmService.addCurrentTMTextUnitVariant(tmTextUnit.getId(), frFR.getId(), "1 mois -- update");
    tmTextUnitCurrentVariantService.removeCurrentVariant(
        tmTextUnitCurrentVariantRepository
            .findByLocale_IdAndTmTextUnit_Id(jaJP.getId(), tmTextUnit.getId())
            .getId());

    logger.debug("Check that the delta for the pull run generated returns 1 modifications");
    DeltaResponseDTO deltaForAfterFrenchAndJapaneseImportsToSmallChanges =
        deltaService.getDeltasForRuns(
            repository,
            null,
            ImmutableList.of(pushRun),
            ImmutableList.of(pullRunAfterFrenchAndJapaneseImports));

    Assertions.assertThat(
            deltaForAfterFrenchImportToFrenchAndJapaneseImport.getTranslationsPerLocale())
        .hasSize(1);
    Assertions.assertThat(
            deltaForAfterFrenchAndJapaneseImportsToSmallChanges
                .getTranslationsPerLocale()
                .get("fr-FR")
                .getTranslationsByTextUnitName())
        .hasSize(1);
    Assertions.assertThat(
            deltaForAfterFrenchAndJapaneseImportsToSmallChanges
                .getTranslationsPerLocale()
                .get("fr-FR")
                .getTranslationsByTextUnitName())
        .extractingByKey("1_month_duration")
        .hasFieldOrPropertyWithValue("text", "1 mois -- update")
        .hasFieldOrPropertyWithValue("deltaType", DeltaType.UPDATED_TRANSLATION);

    logger.debug("Create a second commit that correspond to the second push");
    String pushCommitHashModified = "ccaa22";
    getL10nJCommander()
        .run(
            "commit-create",
            "-r",
            repository.getName(),
            "--commit-hash",
            pushCommitHashModified,
            "--author-email",
            "coder@mail.com",
            "--author-name",
            "coder",
            "--creation-date",
            ZonedDateTime.now().toString());

    getL10nJCommander()
        .run(
            "push",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source_modified").getAbsolutePath(),
            "--record-push-run",
            "--commit-hash",
            pushCommitHashModified);

    logger.debug("Get pushRun for the initial push to test delta later on");
    PushRun pushRunModified =
        commitService
            .getLastPushRun(ImmutableList.of(pushCommitHashModified), repository.getId())
            .orElseThrow(() -> new RuntimeException("There must be a push run"));

    DeltaResponseDTO deltaWithPushModifiedForAfterFrenchAndJapaneseImportsToSmallChanges =
        deltaService.getDeltasForRuns(
            repository,
            null,
            ImmutableList.of(pushRunModified),
            ImmutableList.of(pullRunAfterFrenchAndJapaneseImports));

    Assertions.assertThat(
            deltaWithPushModifiedForAfterFrenchAndJapaneseImportsToSmallChanges
                .getTranslationsPerLocale())
        .hasSize(1);
    Assertions.assertThat(
            deltaWithPushModifiedForAfterFrenchAndJapaneseImportsToSmallChanges
                .getTranslationsPerLocale()
                .get("fr-FR")
                .getTranslationsByTextUnitName())
        .hasSize(1);
    Assertions.assertThat(
            deltaWithPushModifiedForAfterFrenchAndJapaneseImportsToSmallChanges
                .getTranslationsPerLocale()
                .get("fr-FR")
                .getTranslationsByTextUnitName())
        .extractingByKey("1_month_duration")
        .hasFieldOrPropertyWithValue("text", "1 mois -- update")
        .hasFieldOrPropertyWithValue("deltaType", DeltaType.UPDATED_TRANSLATION);

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source_modified").getAbsolutePath(),
            "-t",
            getTargetTestDir("target_modified").getAbsolutePath(),
            "--record-pull-run");

    logger.debug("Simulate commit and linked to pull-run");
    String pullRunHash4 = "ddaa44";
    getL10nJCommander()
        .run(
            "commit-create",
            "-r",
            repository.getName(),
            "--commit-hash",
            pullRunHash4,
            "--author-email",
            "coder@mail.com",
            "--author-name",
            "coder",
            "--creation-date",
            ZonedDateTime.now().toString());
    getL10nJCommander()
        .run(
            "commit-to-pull-run",
            "-r",
            repository.getName(),
            "-i",
            getTargetTestDir("target_modified").getAbsolutePath(),
            "--commit-hash",
            pullRunHash4);

    PullRun pullRunModified =
        commitService
            .getLastPullRun(ImmutableList.of(pullRunHash4), repository.getId())
            .orElseThrow(() -> new RuntimeException("There must be a pull run"));

    DeltaResponseDTO deltaWithPushModifiedForPullModified =
        deltaService.getDeltasForRuns(
            repository, null, ImmutableList.of(pushRunModified), ImmutableList.of(pullRunModified));

    Assertions.assertThat(deltaWithPushModifiedForPullModified.getTranslationsPerLocale())
        .hasSize(0);

    pullRunNamesToNormalizedValueForTests();
    checkExpectedGeneratedResources();
  }

  @Test
  public void pullYaml() throws Exception {
    Repository repository = createTestRepoUsingRepoService();

    getL10nJCommander()
        .run(
            "push",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath());

    Asset asset = assetClient.getAssetByPathAndRepositoryId("demo.yaml", repository.getId());

    importTranslations(asset.getId(), "source-xliff_", "fr-FR");
    importTranslations(asset.getId(), "source-xliff_", "ja-JP");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getTargetTestDir("target").getAbsolutePath(),
            "-lm",
            "fr:fr-FR,ja:ja-JP",
            "-lmt",
            "MAP_ONLY");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source_modified").getAbsolutePath(),
            "-t",
            getTargetTestDir("target_modified").getAbsolutePath(),
            "-lm",
            "fr:fr-FR,ja:ja-JP",
            "-lmt",
            "MAP_ONLY");

    checkExpectedGeneratedResources();
  }

  @Test
  public void pullYamlWithFilterOptions() throws Exception {
    Repository repository = createTestRepoUsingRepoService();

    getL10nJCommander()
        .run(
            "push",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-fo",
            "extractAllPairs=false",
            "exceptions=1_day_duration|1_year_duration");

    Asset asset = assetClient.getAssetByPathAndRepositoryId("demo.yaml", repository.getId());

    importTranslations(asset.getId(), "source-xliff_", "fr-FR");
    importTranslations(asset.getId(), "source-xliff_", "ja-JP");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getTargetTestDir("target").getAbsolutePath(),
            "-lm",
            "fr:fr-FR,ja:ja-JP",
            "-lmt",
            "MAP_ONLY",
            "-fo",
            "extractAllPairs=false",
            "exceptions=1_day_duration|1_year_duration");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source_modified").getAbsolutePath(),
            "-t",
            getTargetTestDir("target_modified").getAbsolutePath(),
            "-lm",
            "fr:fr-FR,ja:ja-JP",
            "-lmt",
            "MAP_ONLY",
            "-fo",
            "extractAllPairs=false",
            "exceptions=1_day_duration|1_year_duration");

    checkExpectedGeneratedResources();
  }

  private void pullRunNamesToNormalizedValueForTests() throws IOException {
    modifyFilesInTargetTestDirectory(
        input -> {
          return input.replaceAll("\\d", "1").replaceAll("[a-z]", "1");
        },
        "pull-run-name.txt");
  }

  private void printDelta(DeltaResponseDTO delta) {
    delta
        .getTranslationsPerLocale()
        .forEach(
            (locale, deltaLocaleDataDTO) -> {
              deltaLocaleDataDTO
                  .getTranslationsByTextUnitName()
                  .forEach(
                      (textUnitName, deltaTranslationDTO) -> {
                        logger.info(locale);
                        logger.info(textUnitName);
                        logger.info(deltaTranslationDTO.getText());
                        logger.info(deltaTranslationDTO.getDeltaType().toString());
                        logger.info("---");
                      });
            });
  }

  private void checkJapaneseTranslationsInDelta(DeltaResponseDTO delta) {
    Assertions.assertThat(
            delta.getTranslationsPerLocale().get("ja-JP").getTranslationsByTextUnitName())
        .hasSize(5);
    Assertions.assertThat(
            delta.getTranslationsPerLocale().get("ja-JP").getTranslationsByTextUnitName())
        .extractingByKey("100_character_description_")
        .hasFieldOrPropertyWithValue("text", "100文字の説明:")
        .hasFieldOrPropertyWithValue("deltaType", DeltaType.NEW_TRANSLATION);
    Assertions.assertThat(
            delta.getTranslationsPerLocale().get("ja-JP").getTranslationsByTextUnitName())
        .extractingByKey("1_hour_duration")
        .hasFieldOrPropertyWithValue("text", "1時間")
        .hasFieldOrPropertyWithValue("deltaType", DeltaType.NEW_TRANSLATION);
    Assertions.assertThat(
            delta.getTranslationsPerLocale().get("ja-JP").getTranslationsByTextUnitName())
        .extractingByKey("1_day_duration")
        .hasFieldOrPropertyWithValue("text", "1日")
        .hasFieldOrPropertyWithValue("deltaType", DeltaType.NEW_TRANSLATION);
    Assertions.assertThat(
            delta.getTranslationsPerLocale().get("ja-JP").getTranslationsByTextUnitName())
        .extractingByKey("1_month_duration")
        .hasFieldOrPropertyWithValue("text", "1か月")
        .hasFieldOrPropertyWithValue("deltaType", DeltaType.NEW_TRANSLATION);
    Assertions.assertThat(
            delta.getTranslationsPerLocale().get("ja-JP").getTranslationsByTextUnitName())
        .extractingByKey("15_min_duration")
        .hasFieldOrPropertyWithValue("text", "15分")
        .hasFieldOrPropertyWithValue("deltaType", DeltaType.NEW_TRANSLATION);
  }

  private void checkFrenchTranslationsInDelta(DeltaResponseDTO delta) {
    Assertions.assertThat(
            delta.getTranslationsPerLocale().get("fr-FR").getTranslationsByTextUnitName())
        .hasSize(5);
    Assertions.assertThat(
            delta.getTranslationsPerLocale().get("fr-FR").getTranslationsByTextUnitName())
        .extractingByKey("100_character_description_")
        .hasFieldOrPropertyWithValue("text", "Description de 100 caractères :")
        .hasFieldOrPropertyWithValue("deltaType", DeltaType.NEW_TRANSLATION);
    Assertions.assertThat(
            delta.getTranslationsPerLocale().get("fr-FR").getTranslationsByTextUnitName())
        .extractingByKey("1_hour_duration")
        .hasFieldOrPropertyWithValue("text", "1 heure")
        .hasFieldOrPropertyWithValue("deltaType", DeltaType.NEW_TRANSLATION);
    Assertions.assertThat(
            delta.getTranslationsPerLocale().get("fr-FR").getTranslationsByTextUnitName())
        .extractingByKey("1_day_duration")
        .hasFieldOrPropertyWithValue("text", "1 jour")
        .hasFieldOrPropertyWithValue("deltaType", DeltaType.NEW_TRANSLATION);
    Assertions.assertThat(
            delta.getTranslationsPerLocale().get("fr-FR").getTranslationsByTextUnitName())
        .extractingByKey("1_month_duration")
        .hasFieldOrPropertyWithValue("text", "1 mois")
        .hasFieldOrPropertyWithValue("deltaType", DeltaType.NEW_TRANSLATION);
    Assertions.assertThat(
            delta.getTranslationsPerLocale().get("fr-FR").getTranslationsByTextUnitName())
        .extractingByKey("15_min_duration")
        .hasFieldOrPropertyWithValue("text", "15 min")
        .hasFieldOrPropertyWithValue("deltaType", DeltaType.NEW_TRANSLATION);
  }
}
