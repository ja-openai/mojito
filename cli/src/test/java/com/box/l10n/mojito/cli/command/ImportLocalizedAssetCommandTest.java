package com.box.l10n.mojito.cli.command;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.box.l10n.mojito.cli.CLITestBase;
import com.box.l10n.mojito.entity.AssetIntegrityChecker;
import com.box.l10n.mojito.entity.Locale;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.TMTextUnitVariant;
import com.box.l10n.mojito.fileformat.LocalizationCatalog;
import com.box.l10n.mojito.fileformat.LocalizationFileConverters;
import com.box.l10n.mojito.fileformat.LocalizationFileFormat;
import com.box.l10n.mojito.fileformat.LocalizationPlaceholder;
import com.box.l10n.mojito.service.assetintegritychecker.integritychecker.IntegrityCheckerType;
import com.box.l10n.mojito.service.locale.LocaleService;
import com.box.l10n.mojito.service.tm.TMTextUnitCurrentVariantRepository;
import com.box.l10n.mojito.service.tm.TMTextUnitVariantRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * @author jeanaurambault
 */
public class ImportLocalizedAssetCommandTest extends CLITestBase {

  /** logger */
  static Logger logger = LoggerFactory.getLogger(DropXliffImportCommandTest.class);

  @Autowired LocaleService localeService;

  @Autowired TMTextUnitCurrentVariantRepository tmTextUnitCurrentVariantRepository;

  @Autowired TMTextUnitVariantRepository tmTextUnitVariantRepository;

  @Test
  public void portableConverterReusesExistingCsvImportDataset() throws Exception {
    assertPortableImportMatchesExistingDataset("importCsv", List.of());
  }

  @Test
  public void portableConverterReusesExistingAdobeMagentoCsvImportDataset() throws Exception {
    assertPortableImportMatchesExistingDataset(
        "importCsvAdobeMagento", List.of("-ft", "CSV_ADOBE_MAGENTO", "-sl", "en_US"));
  }

  @Test
  public void portableConverterReusesExistingPropertiesImportDataset() throws Exception {
    assertPortableImportMatchesExistingDataset("importProperties", List.of());
  }

  @Test
  public void portableConverterReusesExistingAppleStringsImportDataset() throws Exception {
    assertPortableImportMatchesExistingDataset("importMacStrings", List.of());
  }

  @Test
  public void portableConverterReusesExistingAndroidPluralImportDataset() throws Exception {
    assertPortableImportMatchesExistingDataset("importAndroidStringsPlural", List.of());
  }

  @Test
  public void portableJsonMigrationDoesNotAffectAndroidAndAppleImports() throws Exception {
    Repository repository = createTestRepoUsingRepoService();
    Path directory = getTargetTestDir().toPath();
    Path source = directory.resolve("source");
    Path translated = directory.resolve("translations");
    Path output = directory.resolve("output");
    Files.createDirectories(source.resolve("res/values"));
    Files.createDirectories(source.resolve("en.lproj"));
    Files.createDirectories(translated.resolve("res/values-fr-rFR"));
    Files.createDirectories(translated.resolve("res/values-fr-rCA"));
    Files.createDirectories(translated.resolve("res/values-ja-rJP"));
    Files.createDirectories(translated.resolve("fr-FR.lproj"));
    Files.createDirectories(translated.resolve("fr-CA.lproj"));
    Files.createDirectories(translated.resolve("ja-JP.lproj"));
    Files.createDirectories(output);

    String androidSource =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <resources>
          <string name="welcome" description="Harbor greeting">Welcome</string>
          <plurals name="boat_count">
            <item quantity="one">%d boat</item>
            <item quantity="other">%d boats</item>
          </plurals>
          <string name="protected" translatable="false">Keep untouched</string>
        </resources>
        """;
    String androidFrench =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <resources>
          <string name="welcome" description="Harbor greeting">Bienvenue</string>
          <plurals name="boat_count">
            <item quantity="one">%d bateau</item>
            <item quantity="other">%d bateaux</item>
          </plurals>
          <string name="protected" translatable="false">Keep untouched</string>
        </resources>
        """;
    String androidJapanese =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <resources>
          <string name="welcome" description="Harbor greeting">ようこそ</string>
          <plurals name="boat_count">
            <item quantity="other">%d 艇</item>
          </plurals>
          <string name="protected" translatable="false">Keep untouched</string>
        </resources>
        """;
    Files.writeString(source.resolve("res/values/strings.xml"), androidSource);
    Files.writeString(translated.resolve("res/values-fr-rFR/strings.xml"), androidFrench);
    Files.writeString(translated.resolve("res/values-fr-rCA/strings.xml"), androidFrench);
    Files.writeString(translated.resolve("res/values-ja-rJP/strings.xml"), androidJapanese);

    Files.writeString(
        source.resolve("en.lproj/Localizable.strings"),
        "/* Harbor greeting */\n\"welcome_ios\" = \"Welcome\";\n");
    Files.writeString(
        translated.resolve("fr-FR.lproj/Localizable.strings"),
        "/* Harbor greeting */\n\"welcome_ios\" = \"Bienvenue\";\n");
    Files.writeString(
        translated.resolve("fr-CA.lproj/Localizable.strings"),
        "/* Harbor greeting */\n\"welcome_ios\" = \"Bienvenue\";\n");
    Files.writeString(
        translated.resolve("ja-JP.lproj/Localizable.strings"),
        "/* Harbor greeting */\n\"welcome_ios\" = \"ようこそ\";\n");

    Files.writeString(
        source.resolve("en.lproj/Localizable.stringsdict"),
        applePluralDictionary("%d boat", "%d boats"));
    Files.writeString(
        translated.resolve("fr-FR.lproj/Localizable.stringsdict"),
        applePluralDictionary("%d bateau", "%d bateaux"));
    Files.writeString(
        translated.resolve("fr-CA.lproj/Localizable.stringsdict"),
        applePluralDictionary("%d bateau", "%d bateaux"));
    Files.writeString(
        translated.resolve("ja-JP.lproj/Localizable.stringsdict"),
        applePluralDictionary(null, "%d 艇"));

    getL10nJCommander()
        .run(
            "push",
            "-r",
            repository.getName(),
            "-s",
            source.toString(),
            "--converter",
            "portable",
            "--migrate-legacy-json-comments");
    runPortableCommand("import", repository.getName(), source, translated, List.of());
    runPortableCommand("pull", repository.getName(), source, output, List.of());

    String frenchAndroid = Files.readString(output.resolve("res/values-fr-rFR/strings.xml"));
    String japaneseAndroid = Files.readString(output.resolve("res/values-ja-rJP/strings.xml"));
    assertTrue(frenchAndroid.contains("Bienvenue"));
    assertTrue(frenchAndroid.contains("%d bateau"));
    assertTrue(frenchAndroid.contains("%d bateaux"));
    assertTrue(frenchAndroid.contains("Keep untouched"));
    assertTrue(japaneseAndroid.contains("ようこそ"));
    assertTrue(japaneseAndroid.contains("%d 艇"));
    assertTrue(
        Files.readString(output.resolve("fr-FR.lproj/Localizable.strings"))
            .contains("\"welcome_ios\" = \"Bienvenue\""));
    assertTrue(
        Files.readString(output.resolve("ja-JP.lproj/Localizable.strings"))
            .contains("\"welcome_ios\" = \"ようこそ\""));
    assertTrue(
        Files.readString(output.resolve("fr-FR.lproj/Localizable.stringsdict"))
            .contains("%d bateaux"));
    assertTrue(
        Files.readString(output.resolve("ja-JP.lproj/Localizable.stringsdict")).contains("%d 艇"));
  }

  private static String applePluralDictionary(String one, String other) {
    String oneCategory = one == null ? "" : "      <key>one</key><string>" + one + "</string>\n";
    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <plist version="1.0">
          <dict>
            <key>boat_count</key>
            <dict>
              <key>NSStringLocalizedFormatKey</key><string>%#@boats@</string>
              <key>boats</key>
              <dict>
                <key>NSStringFormatSpecTypeKey</key><string>NSStringPluralRuleType</string>
                <key>NSStringFormatValueTypeKey</key><string>d</string>
        """
        + oneCategory
        + "      <key>other</key><string>"
        + other
        + "</string>\n"
        + "    </dict>\n"
        + "  </dict>\n"
        + "</dict>\n"
        + "</plist>\n";
  }

  @Test
  public void portableConverterReusesExistingJsonImportDataset() throws Exception {
    assertPortableImportMatchesExistingDataset("importJson", List.of("-ft", "JSON"));
  }

  @Test
  public void portableConverterReusesExistingGettextPluralImportDataset() throws Exception {
    assertPortableImportMatchesExistingDataset(
        "importPoPlural", List.of("-lm", "fr:fr-FR,fr-CA:fr-CA,ja:ja-JP,ru-RU:ru-RU,hr-HR:hr-HR"));
  }

  @Test
  public void portableImportAndPullPreserveDistinctRussianApplePluralSelectors() throws Exception {
    Repository repository = createTestRepoUsingRepoService();
    repositoryService.addRepositoryLocale(repository, "ru-RU", null, true);
    Path directory = getTargetTestDir().toPath();
    Path source = directory.resolve("source");
    Path translations = directory.resolve("translations");
    Path output = directory.resolve("output");
    Path sourceAsset = source.resolve("en.lproj/Localizable.stringsdict");
    Path translatedAsset = translations.resolve("ru-RU.lproj/Localizable.stringsdict");
    Files.createDirectories(sourceAsset.getParent());
    Files.createDirectories(translatedAsset.getParent());
    Files.createDirectories(output);
    Files.writeString(
        sourceAsset,
        Files.readString(findConformanceFixture("fixtures/apple/multiple.stringsdict")));
    Files.writeString(
        translatedAsset,
        Files.readString(
            findConformanceFixture(
                "fixtures/workflow/apple-russian-multiple-plurals.localized.stringsdict")));

    runPortableCommand("push", repository.getName(), source, null, List.of());
    List<String> russianOnly = List.of("-lm", "ru-RU:ru-RU", "-lmt", "MAP_ONLY");
    runPortableCommand("import", repository.getName(), source, translations, russianOnly);
    runPortableCommand("pull", repository.getName(), source, output, russianOnly);

    assertArrayEquals(
        Files.readAllBytes(translatedAsset),
        Files.readAllBytes(output.resolve("ru-RU.lproj/Localizable.stringsdict")));
    Locale russian = localeService.findByBcp47Tag("ru-RU");
    assertEquals(
        "eight selector-owned categories plus NSStringLocalizedFormatKey",
        9,
        tmTextUnitCurrentVariantRepository
            .findByTmTextUnit_Tm_IdAndLocale_Id(repository.getTm().getId(), russian.getId())
            .size());
  }

  @Test
  public void portableJapanesePluralImportCreatesOnlyTheLocaleOwnedAndroidForm() throws Exception {
    Repository repository = createTestRepoUsingRepoService();
    Path directory = getTargetTestDir().toPath();
    Path source = directory.resolve("source");
    Path translations = directory.resolve("translations");
    Path output = directory.resolve("output");
    Path sourceAsset = source.resolve("res/values/strings.xml");
    Path translatedAsset = translations.resolve("res/values-ja-rJP/strings.xml");
    Files.createDirectories(sourceAsset.getParent());
    Files.createDirectories(translatedAsset.getParent());
    Files.createDirectories(output);
    Files.writeString(
        sourceAsset,
        """
        <resources><plurals name="boats">
          <item quantity="one">%d boat</item>
          <item quantity="other">%d boats</item>
        </plurals></resources>
        """);
    Files.writeString(
        translatedAsset,
        """
        <resources><plurals name="boats">
          <item quantity="other">%d 艇</item>
        </plurals></resources>
        """);

    runPortableCommand("push", repository.getName(), source, null, List.of());
    List<String> japaneseOnly = List.of("-lm", "ja-JP:ja-JP", "-lmt", "MAP_ONLY");
    runPortableCommand("import", repository.getName(), source, translations, japaneseOnly);
    runPortableCommand("pull", repository.getName(), source, output, japaneseOnly);

    Locale japanese = localeService.findByBcp47Tag("ja-JP");
    var imported =
        tmTextUnitCurrentVariantRepository.findByTmTextUnit_Tm_IdAndLocale_Id(
            repository.getTm().getId(), japanese.getId());
    assertEquals(1, imported.size());
    LocalizationCatalog localized =
        LocalizationFileConverters.parse(
            LocalizationFileFormat.ANDROID,
            Files.readAllBytes(output.resolve("res/values-ja-rJP/strings.xml")));
    assertEquals(java.util.Set.of("other"), localized.messages().get("boats").variants().keySet());
    assertEquals("{arg0} 艇", localized.messages().get("boats").variants().get("other"));
  }

  @Test
  public void portableImportRunsEmptyTargetsThroughIntegrityAndStatusHandling() throws Exception {
    Repository repository = createTestRepoUsingRepoService();
    AssetIntegrityChecker checker = new AssetIntegrityChecker();
    checker.setAssetExtension("properties");
    checker.setIntegrityCheckerType(IntegrityCheckerType.EMPTY_TARGET_NOT_EMPTY_SOURCE);
    repositoryService.updateAssetIntegrityCheckers(repository, java.util.Set.of(checker));
    Path directory = getTargetTestDir().toPath();
    Path source = directory.resolve("source");
    Path translations = directory.resolve("translations");
    Files.createDirectories(source);
    Files.createDirectories(translations);
    Files.writeString(source.resolve("demo.properties"), "greeting=Hello\n");
    Files.writeString(translations.resolve("demo_fr-FR.properties"), "greeting=\n");

    runPortableCommand("push", repository.getName(), source, null, List.of());
    runPortableCommand(
        "import",
        repository.getName(),
        source,
        translations,
        List.of("-lm", "fr-FR:fr-FR", "-lmt", "MAP_ONLY"));

    Locale french = localeService.findByBcp47Tag("fr-FR");
    List<TMTextUnitVariant> variants =
        tmTextUnitVariantRepository.findAllByLocale_IdAndTmTextUnit_Tm_id(
            french.getId(), repository.getTm().getId());
    assertEquals(1, variants.size());
    assertEquals("", variants.getFirst().getContent());
    assertEquals(TMTextUnitVariant.Status.TRANSLATION_NEEDED, variants.getFirst().getStatus());
    assertFalse(variants.getFirst().isIncludedInLocalizedFile());
  }

  private static Path findConformanceFixture(String relativePath) {
    Path current = Path.of("").toAbsolutePath();
    while (current != null) {
      Path fixture = current.resolve("file-formats/conformance").resolve(relativePath);
      if (Files.isRegularFile(fixture)) {
        return fixture;
      }
      current = current.getParent();
    }
    throw new IllegalStateException("Could not locate conformance fixture: " + relativePath);
  }

  private void assertPortableImportMatchesExistingDataset(
      String dataset, List<String> formatOptions) throws Exception {
    Repository repository = createTestRepoUsingRepoService();
    if ("importPoPlural".equals(dataset) || "importAndroidStringsPlural".equals(dataset)) {
      repositoryService.addRepositoryLocale(repository, "ru-RU");
    }
    if ("importPoPlural".equals(dataset)) {
      repositoryService.addRepositoryLocale(repository, "hr-HR");
    }
    Path fixture =
        Path.of(
            "src/test/resources/com/box/l10n/mojito/cli/command/ImportLocalizedAssetCommandTest_IO",
            dataset);
    Path source = fixture.resolve("input/source");
    Path translated = fixture.resolve("input/translations");
    Path output = getTargetTestDir().toPath();

    runPortableCommand("push", repository.getName(), source, null, formatOptions);
    runPortableCommand("import", repository.getName(), source, translated, formatOptions);
    runPortableCommand("pull", repository.getName(), source, output, formatOptions);

    Path expected = fixture.resolve("expected");
    List<Path> files;
    try (var paths = Files.walk(expected)) {
      files = paths.filter(Files::isRegularFile).toList();
    }
    assertTrue("Existing import dataset must contain expected localized files", !files.isEmpty());
    for (Path file : files) {
      Path actual = output.resolve(expected.relativize(file));
      if ("importAndroidStringsPlural".equals(dataset)) {
        LocalizationCatalog expectedCatalog =
            LocalizationFileConverters.parse(
                LocalizationFileFormat.ANDROID, Files.readAllBytes(file));
        LocalizationCatalog actualCatalog =
            LocalizationFileConverters.parse(
                LocalizationFileFormat.ANDROID, Files.readAllBytes(actual));
        assertEquals(
            file.toString(),
            expectedCatalog.messages().keySet(),
            actualCatalog.messages().keySet());
        for (var entry : expectedCatalog.messages().entrySet()) {
          var expectedMessage = entry.getValue();
          var actualMessage = actualCatalog.messages().get(entry.getKey());
          assertEquals(
              entry.getKey(), expectedMessage.defaultMessage(), actualMessage.defaultMessage());
          assertEquals(entry.getKey(), expectedMessage.description(), actualMessage.description());
          assertEquals(entry.getKey(), expectedMessage.variants(), actualMessage.variants());
          assertEquals(entry.getKey(), expectedMessage.metadata(), actualMessage.metadata());
          assertEquals(
              entry.getKey(),
              androidPlaceholderSemantics(expectedMessage.placeholders()),
              androidPlaceholderSemantics(actualMessage.placeholders()));
        }
      } else {
        assertArrayEquals(
            expected.relativize(file).toString(),
            Files.readAllBytes(file),
            Files.readAllBytes(actual));
      }
    }
  }

  private static List<LocalizationPlaceholder> androidPlaceholderSemantics(
      List<LocalizationPlaceholder> placeholders) {
    return placeholders == null
        ? null
        : placeholders.stream()
            .map(
                placeholder ->
                    new LocalizationPlaceholder(
                        placeholder.name(),
                        null,
                        placeholder.kind(),
                        placeholder.position(),
                        placeholder.example()))
            .toList();
  }

  private void runPortableCommand(
      String command, String repository, Path source, Path target, List<String> formatOptions) {
    List<String> arguments = new ArrayList<>();
    arguments.addAll(List.of(command, "-r", repository, "-s", source.toString()));
    if (target != null) {
      arguments.addAll(List.of("-t", target.toString()));
    }
    arguments.addAll(formatOptions);
    arguments.addAll(List.of("--converter", "portable"));
    getL10nJCommander().run(arguments.toArray(String[]::new));
  }

  @Test
  public void importAndroidStrings() throws Exception {

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
            "import",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getInputResourcesTestDir("translations").getAbsolutePath());

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getTargetTestDir().getAbsolutePath());

    checkExpectedGeneratedResources();
  }

  @Test
  public void importAndroidStringsPlural() throws Exception {

    Repository repository = createTestRepoUsingRepoService();
    repositoryService.addRepositoryLocale(repository, "ru-RU");

    getL10nJCommander()
        .run(
            "push",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath());

    getL10nJCommander()
        .run(
            "import",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getInputResourcesTestDir("translations").getAbsolutePath());

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getTargetTestDir().getAbsolutePath());

    checkExpectedGeneratedResources();
  }

  @Test
  public void importAndroidStringsPostProcessing() throws Exception {

    Repository repository = createTestRepoUsingRepoService();
    repositoryService.addRepositoryLocale(repository, "ru-RU");

    getL10nJCommander()
        .run(
            "push",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath());

    getL10nJCommander()
        .run(
            "import",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getInputResourcesTestDir("translations").getAbsolutePath());

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
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getTargetTestDir("removeUntranslated").getAbsolutePath(),
            "--inheritance-mode",
            "REMOVE_UNTRANSLATED");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getTargetTestDir("removeUntranslatedAndDescription").getAbsolutePath(),
            "--inheritance-mode",
            "REMOVE_UNTRANSLATED",
            "-fo",
            "removeDescription=true");

    checkExpectedGeneratedResources();
  }

  @Test
  public void importMacStrings() throws Exception {

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
            "import",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getInputResourcesTestDir("translations").getAbsolutePath());

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getTargetTestDir().getAbsolutePath());

    checkExpectedGeneratedResources();
  }

  @Test
  public void importMacStringsdict() throws Exception {

    Repository repository = createTestRepoUsingRepoService();
    repositoryService.addRepositoryLocale(repository, "ru-RU");

    getL10nJCommander()
        .run(
            "push",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath());

    getL10nJCommander()
        .run(
            "import",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getInputResourcesTestDir("translations").getAbsolutePath());

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getTargetTestDir().getAbsolutePath());

    checkExpectedGeneratedResources();
  }

  @Test
  public void importPo() throws Exception {

    Repository repository = createTestRepoUsingRepoService();
    repositoryService.addRepositoryLocale(repository, "ar-SA");

    getL10nJCommander()
        .run(
            "push",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath());

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
            "ar:ar-SA,fr:fr-FR,fr-CA:fr-CA,ja:ja-JP");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getTargetTestDir().getAbsolutePath(),
            "-lm",
            "ar:ar-SA,fr:fr-FR,fr-CA:fr-CA,ja:ja-JP");

    checkExpectedGeneratedResources();
  }

  @Test
  public void importPoPlural() throws Exception {

    Repository repository = createTestRepoUsingRepoService();
    repositoryService.addRepositoryLocale(repository, "ru-RU");
    repositoryService.addRepositoryLocale(repository, "hr-HR");

    getL10nJCommander()
        .run(
            "push",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath());

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
            "fr:fr-FR,fr-CA:fr-CA,ja:ja-JP,ru-RU:ru-RU,hr-HR:hr-HR");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getTargetTestDir().getAbsolutePath(),
            "-lm",
            "fr:fr-FR,fr-CA:fr-CA,ja:ja-JP,ru-RU:ru-RU,hr-HR:hr-HR");

    checkExpectedGeneratedResources();
  }

  @Test
  public void importProperties() throws Exception {

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
            "import",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getInputResourcesTestDir("translations").getAbsolutePath());

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getTargetTestDir().getAbsolutePath());

    checkExpectedGeneratedResources();
  }

  @Test
  public void importPropertiesJava() throws Exception {

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

    getL10nJCommander()
        .run(
            "import",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getInputResourcesTestDir("translations").getAbsolutePath(),
            "-ft",
            "PROPERTIES_JAVA");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-ft",
            "PROPERTIES_JAVA",
            "-t",
            getTargetTestDir().getAbsolutePath());

    checkExpectedGeneratedResources();
  }

  @Test
  public void importPropertiesNoBaseName() throws Exception {

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

    getL10nJCommander()
        .run(
            "import",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getInputResourcesTestDir("translations").getAbsolutePath(),
            "-ft",
            "PROPERTIES_NOBASENAME");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-ft",
            "PROPERTIES_NOBASENAME",
            "-t",
            getTargetTestDir().getAbsolutePath());

    checkExpectedGeneratedResources();
  }

  @Test
  public void importPropertiesNoBasenameMultiDirectory() throws Exception {

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

    getL10nJCommander()
        .run(
            "import",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getInputResourcesTestDir("translations").getAbsolutePath(),
            "-ft",
            "PROPERTIES_NOBASENAME");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getTargetTestDir().getAbsolutePath(),
            "-ft",
            "PROPERTIES_NOBASENAME");

    checkExpectedGeneratedResources();
  }

  @Test
  public void importResw() throws Exception {

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
            "import",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getInputResourcesTestDir("translations").getAbsolutePath(),
            "-lm",
            "fr:fr-FR,fr-CA:fr-CA,ja:ja-JP");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getTargetTestDir().getAbsolutePath(),
            "-lm",
            "fr:fr-FR,fr-CA:fr-CA,ja:ja-JP");

    checkExpectedGeneratedResources();
  }

  @Test
  public void importResx() throws Exception {

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
            "import",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getInputResourcesTestDir("translations").getAbsolutePath());

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getTargetTestDir().getAbsolutePath());

    checkExpectedGeneratedResources();
  }

  @Test
  public void importJson() throws Exception {

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

    getL10nJCommander()
        .run(
            "import",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getInputResourcesTestDir("translations").getAbsolutePath(),
            "-ft",
            "JSON");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getTargetTestDir().getAbsolutePath(),
            "-ft",
            "JSON");

    checkExpectedGeneratedResources();
  }

  @Test
  public void importJsonNobasename() throws Exception {

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

    getL10nJCommander()
        .run(
            "import",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getInputResourcesTestDir("translations").getAbsolutePath(),
            "-ft",
            "JSON_NOBASENAME");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getTargetTestDir().getAbsolutePath(),
            "-ft",
            "JSON_NOBASENAME");

    checkExpectedGeneratedResources();
  }

  @Test
  public void importJsonWithNote() throws Exception {

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

    getL10nJCommander()
        .run(
            "import",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getInputResourcesTestDir("translations").getAbsolutePath(),
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
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getTargetTestDir().getAbsolutePath(),
            "-fo",
            "noteKeyPattern=note",
            "extractAllPairs=false",
            "exceptions=string",
            "-ft",
            "JSON");

    checkExpectedGeneratedResources();
  }

  @Test
  public void importJsonDefaultFormatJs() throws Exception {

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
            "import",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getInputResourcesTestDir("translations").getAbsolutePath(),
            "-ft",
            "FORMATJS_JSON_NOBASENAME");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getTargetTestDir().getAbsolutePath(),
            "-ft",
            "FORMATJS_JSON_NOBASENAME");

    checkExpectedGeneratedResources();
  }

  @Test
  public void importJsonDefaultFormatJsCompiled() throws Exception {

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
            "import",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getInputResourcesTestDir("translations").getAbsolutePath(),
            "-ft",
            "JSON_NOBASENAME");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getTargetTestDir().getAbsolutePath(),
            "-ft",
            "JSON_NOBASENAME",
            "-fo",
            "noteKeyPattern=description",
            "extractAllPairs=false",
            "exceptions=defaultMessage",
            "removeKeySuffix=/defaultMessage");

    checkExpectedGeneratedResources();
  }

  @Test
  public void importJsonI18NextParser() throws Exception {

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

    getL10nJCommander()
        .run(
            "import",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getInputResourcesTestDir("translations").getAbsolutePath(),
            "-ft",
            "I18NEXT_PARSER_JSON");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getTargetTestDir().getAbsolutePath(),
            "-ft",
            "I18NEXT_PARSER_JSON");

    checkExpectedGeneratedResources();
  }

  @Test
  public void importJsonVSCodeExtension() throws Exception {

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

    getL10nJCommander()
        .run(
            "import",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getInputResourcesTestDir("translations").getAbsolutePath(),
            "-ft",
            "VSCODE_EXTENSION_JSON");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getTargetTestDir().getAbsolutePath(),
            "-ft",
            "VSCODE_EXTENSION_JSON");

    checkExpectedGeneratedResources();
  }

  @Test
  public void importXcodeXliff() throws Exception {

    Repository repository = createTestRepoUsingRepoService();

    getL10nJCommander()
        .run(
            "push",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getInputResourcesTestDir("translations").getAbsolutePath(),
            "-ft",
            "XCODE_XLIFF");

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
            "fr:fr-FR,fr-CA:fr-CA,ja:ja-JP",
            "-ft",
            "XCODE_XLIFF");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getTargetTestDir().getAbsolutePath(),
            "-lm",
            "fr:fr-FR,fr-CA:fr-CA,ja:ja-JP",
            "-ft",
            "XCODE_XLIFF");

    checkExpectedGeneratedResources();
  }

  @Test
  public void importXtb() throws Exception {

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

    getL10nJCommander()
        .run(
            "import",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getInputResourcesTestDir("translations").getAbsolutePath(),
            "-sl",
            "en-US");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getTargetTestDir().getAbsolutePath(),
            "-sl",
            "en-US");

    checkExpectedGeneratedResources();
  }

  @Test
  public void importCsv() throws Exception {

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
            "import",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getInputResourcesTestDir("translations").getAbsolutePath());

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getTargetTestDir().getAbsolutePath());

    checkExpectedGeneratedResources();
  }

  @Test
  public void importCsvAdobeMagento() throws Exception {

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

    getL10nJCommander()
        .run(
            "import",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getInputResourcesTestDir("translations").getAbsolutePath(),
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
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getTargetTestDir().getAbsolutePath(),
            "-ft",
            "CSV_ADOBE_MAGENTO",
            "-sl",
            "en_US");

    checkExpectedGeneratedResources();
  }

  @Test
  public void importUnused() throws Exception {
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
            "push",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source2").getAbsolutePath());

    getL10nJCommander()
        .run(
            "import",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source2").getAbsolutePath(),
            "-t",
            getInputResourcesTestDir("translations").getAbsolutePath());

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source2").getAbsolutePath(),
            "-t",
            getTargetTestDir().getAbsolutePath());

    checkExpectedGeneratedResources();
  }

  @Test
  public void importDifferentSourceLocale() throws Exception {
    String repoName = testIdWatcher.getEntityName("repository");

    Locale frFRLocale = localeService.findByBcp47Tag("fr-FR");

    Repository repository =
        repositoryService.createRepository(repoName, repoName + " description", frFRLocale, false);

    repositoryService.addRepositoryLocale(repository, "en", "fr-FR", true);
    repositoryService.addRepositoryLocale(repository, "en-US", "en", false);
    repositoryService.addRepositoryLocale(repository, "ja-JP");

    getL10nJCommander()
        .run(
            "push",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath());

    getL10nJCommander()
        .run(
            "import",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getInputResourcesTestDir("translations").getAbsolutePath());

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getTargetTestDir("withNoMapping").getAbsolutePath());

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getTargetTestDir("withMapping").getAbsolutePath(),
            "-lm",
            "en-US:en-US,en:en,fr-FR:fr-FR",
            "-lmt",
            "MAP_ONLY");

    checkExpectedGeneratedResources();
  }

  @Test
  public void importYaml() throws Exception {

    Repository repository = createTestRepoUsingRepoService();

    getL10nJCommander()
        .run(
            "push",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-ft",
            "YAML");

    getL10nJCommander()
        .run(
            "import",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getInputResourcesTestDir("translations").getAbsolutePath(),
            "-ft",
            "YAML");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getTargetTestDir().getAbsolutePath(),
            "-ft",
            "YAML");

    checkExpectedGeneratedResources();
  }

  @Test
  public void importYamlWithExtractFields() throws Exception {

    Repository repository = createTestRepoUsingRepoService();

    getL10nJCommander()
        .run(
            "push",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-ft",
            "YAML",
            "-fo",
            "extractAllPairs=false",
            "exceptions=title");

    getL10nJCommander()
        .run(
            "import",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getInputResourcesTestDir("translations").getAbsolutePath(),
            "-ft",
            "YAML",
            "-fo",
            "extractAllPairs=false",
            "exceptions=title");

    getL10nJCommander()
        .run(
            "pull",
            "-r",
            repository.getName(),
            "-s",
            getInputResourcesTestDir("source").getAbsolutePath(),
            "-t",
            getTargetTestDir().getAbsolutePath(),
            "-ft",
            "YAML");

    checkExpectedGeneratedResources();
  }
}
