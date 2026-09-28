package com.box.l10n.mojito.service.tm.importer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.Asset;
import com.box.l10n.mojito.entity.AssetIntegrityChecker;
import com.box.l10n.mojito.entity.Locale;
import com.box.l10n.mojito.entity.PluralForm;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.TMTextUnit;
import com.box.l10n.mojito.entity.TMTextUnitVariant.Status;
import com.box.l10n.mojito.service.assetintegritychecker.AssetIntegrityCheckerRepository;
import com.box.l10n.mojito.service.assetintegritychecker.integritychecker.HtmlTagIntegrityCheckerException;
import com.box.l10n.mojito.service.assetintegritychecker.integritychecker.IntegrityCheckException;
import com.box.l10n.mojito.service.assetintegritychecker.integritychecker.IntegrityCheckerFactory;
import com.box.l10n.mojito.service.assetintegritychecker.integritychecker.IntegrityCheckerType;
import com.box.l10n.mojito.service.assetintegritychecker.integritychecker.PluralIntegrityCheckerRelaxer;
import com.box.l10n.mojito.service.locale.LocaleRepository;
import com.box.l10n.mojito.service.tm.TMTextUnitIntegrityCheckService;
import com.box.l10n.mojito.service.tm.TMTextUnitRepository;
import com.box.l10n.mojito.service.tm.importer.TextUnitBatchImporterService.IntegrityChecksType;
import com.box.l10n.mojito.service.tm.search.TextUnitDTO;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** Exercises the configured save/review gate and batch-import status policy without a database. */
public class TemplateBracesIntegrityFlowTest {

  private static final String SOURCE =
      "Confirm {{type}} at {{{ verifyUrl }}} or cancel at {{{ noVerifyUrl }}}.";
  private static final String TARGET =
      "Confirmez {{type}} via {{{ verifyUrl }}} ou annulez via {{{ noVerifyUrl }}}.";

  @Test
  public void configuredSaveAndReviewValidationAcceptMixedBracesWithLocaleContext() {
    Fixture fixture = fixture(SOURCE, IntegrityCheckerType.MESSAGE_FORMAT_DOUBLE_BRACES);

    // Workbench saves use a locale id; Review Project saves pass the resolved locale tag.
    fixture.validation().checkTMTextUnitIntegrity(1L, TARGET, 2L);
    fixture.validation().checkTMTextUnitIntegrityForLocale(1L, TARGET, "fr");
    fixture.validation().checkTMTextUnitIntegrity(1L, TARGET);

    assertEquals(SOURCE, fixture.source().getContent());
  }

  @Test
  public void validImportKeepsTemplateTextAndRequestedStatus() {
    Fixture fixture = fixture(SOURCE, IntegrityCheckerType.MESSAGE_FORMAT_DOUBLE_BRACES);
    TextUnitForBatchMatcherImport candidate = candidate(SOURCE, TARGET);
    candidate.setStatus(Status.REVIEW_NEEDED);

    fixture
        .importer()
        .applyIntegrityChecks(
            fixture.asset(),
            List.of(candidate),
            IntegrityChecksType.ALWAYS_USE_INTEGRITY_CHECKER_STATUS,
            "fr");

    assertTrue(candidate.isIncludedInLocalizedFile());
    assertEquals(Status.REVIEW_NEEDED, candidate.getStatus());
    assertTrue(candidate.getTmTextUnitVariantComments().isEmpty());
    assertEquals(TARGET, candidate.getContent());
    assertEquals(SOURCE, candidate.getCurrentTextUnit().getSource());
  }

  @Test
  public void damagedPlaceholdersBlockSaveAndExcludeImportWithoutRewritingText() {
    Fixture fixture = fixture(SOURCE, IntegrityCheckerType.MESSAGE_FORMAT_DOUBLE_BRACES);
    for (String target :
        List.of(
            TARGET.replace("{{{ verifyUrl }}}", ""),
            TARGET.replace("verifyUrl", "differentUrl"),
            TARGET.replace("{{{ verifyUrl }}}", "{{ verifyUrl }}"),
            TARGET.replace("{{type}}", "{{{type}}}"),
            TARGET + " {{{ verifyUrl }}}",
            TARGET.replace("{{{ verifyUrl }}}", "{{{ verifyUrl }}"))) {
      assertThrows(
          IntegrityCheckException.class,
          () -> fixture.validation().checkTMTextUnitIntegrity(1L, target, 2L));
      assertThrows(
          IntegrityCheckException.class,
          () -> fixture.validation().checkTMTextUnitIntegrityForLocale(1L, target, "fr"));

      TextUnitForBatchMatcherImport candidate = candidate(SOURCE, target);
      fixture
          .importer()
          .applyIntegrityChecks(
              fixture.asset(),
              List.of(candidate),
              IntegrityChecksType.ALWAYS_USE_INTEGRITY_CHECKER_STATUS,
              "fr");

      assertFalse(target, candidate.isIncludedInLocalizedFile());
      assertEquals(Status.TRANSLATION_NEEDED, candidate.getStatus());
      assertFalse(candidate.getTmTextUnitVariantComments().isEmpty());
      assertEquals(target, candidate.getContent());
    }
  }

  @Test
  public void pluralRelaxationDoesNotAllowMissingTemplateVariables() {
    Fixture fixture = fixture(SOURCE, IntegrityCheckerType.MESSAGE_FORMAT_DOUBLE_BRACES);
    PluralForm pluralForm = new PluralForm();
    pluralForm.setName("one");
    fixture.source().setPluralForm(pluralForm);
    String target = TARGET.replace("{{{ verifyUrl }}}", "");

    assertThrows(
        IntegrityCheckException.class,
        () -> fixture.validation().checkTMTextUnitIntegrityForLocale(1L, target, "fr"));

    TextUnitForBatchMatcherImport candidate = candidate(SOURCE, target);
    candidate.getCurrentTextUnit().setPluralForm("one");
    fixture
        .importer()
        .applyIntegrityChecks(
            fixture.asset(),
            List.of(candidate),
            IntegrityChecksType.ALWAYS_USE_INTEGRITY_CHECKER_STATUS,
            "fr");

    assertFalse(candidate.isIncludedInLocalizedFile());
    assertEquals(Status.TRANSLATION_NEEDED, candidate.getStatus());
  }

  @Test
  public void aiImportPolicyStillPreservesPreviouslyExcludedSameTarget() {
    Fixture fixture = fixture(SOURCE, IntegrityCheckerType.MESSAGE_FORMAT_DOUBLE_BRACES);
    TextUnitForBatchMatcherImport candidate = candidate(SOURCE, TARGET);
    candidate.getCurrentTextUnit().setTarget(TARGET);
    candidate.getCurrentTextUnit().setStatus(Status.TRANSLATION_NEEDED);
    candidate.getCurrentTextUnit().setIncludedInLocalizedFile(false);
    candidate.setStatus(Status.APPROVED);

    fixture
        .importer()
        .applyIntegrityChecks(
            fixture.asset(),
            List.of(candidate),
            IntegrityChecksType.KEEP_STATUS_IF_SAME_TARGET_AND_NOT_INCLUDED,
            "fr");

    assertFalse(candidate.isIncludedInLocalizedFile());
    assertEquals(Status.TRANSLATION_NEEDED, candidate.getStatus());
    assertTrue(candidate.getTmTextUnitVariantComments().isEmpty());
    assertEquals(TARGET, candidate.getContent());
  }

  @Test
  public void otherConfiguredCheckerStillRejectsTargetAfterTemplateCheckPasses() {
    String source = "<b>" + SOURCE + "</b>";
    String target = "<i>" + TARGET + "</i>";
    Fixture fixture =
        fixture(
            source,
            IntegrityCheckerType.MESSAGE_FORMAT_DOUBLE_BRACES,
            IntegrityCheckerType.HTML_TAG);

    assertThrows(
        HtmlTagIntegrityCheckerException.class,
        () -> fixture.validation().checkTMTextUnitIntegrity(1L, target, 2L));

    TextUnitForBatchMatcherImport candidate = candidate(source, target);
    fixture
        .importer()
        .applyIntegrityChecks(
            fixture.asset(),
            List.of(candidate),
            IntegrityChecksType.ALWAYS_USE_INTEGRITY_CHECKER_STATUS,
            "fr");

    assertFalse(candidate.isIncludedInLocalizedFile());
    assertEquals(Status.TRANSLATION_NEEDED, candidate.getStatus());
    assertEquals(1, candidate.getTmTextUnitVariantComments().size());
    assertTrue(
        candidate.getTmTextUnitVariantComments().get(0).getContent().contains("HTML tag counts"));
  }

  @Test
  public void assetsWithoutConfiguredCheckerKeepTheirExistingPolicy() {
    Fixture fixture = fixture(SOURCE);
    String target = "Texte sans variables";
    fixture.validation().checkTMTextUnitIntegrity(1L, target, 2L);

    TextUnitForBatchMatcherImport candidate = candidate(SOURCE, target);
    fixture
        .importer()
        .applyIntegrityChecks(
            fixture.asset(),
            List.of(candidate),
            IntegrityChecksType.ALWAYS_USE_INTEGRITY_CHECKER_STATUS,
            "fr");

    assertTrue(candidate.isIncludedInLocalizedFile());
    assertEquals(Status.APPROVED, candidate.getStatus());
    assertTrue(candidate.getTmTextUnitVariantComments().isEmpty());
  }

  private TextUnitForBatchMatcherImport candidate(String source, String target) {
    TextUnitDTO current = new TextUnitDTO();
    current.setSource(source);
    current.setTargetLocale("fr");
    TextUnitForBatchMatcherImport candidate = new TextUnitForBatchMatcherImport();
    candidate.setCurrentTextUnit(current);
    candidate.setContent(target);
    return candidate;
  }

  private Fixture fixture(String source, IntegrityCheckerType... types) {
    Repository repository = new Repository();
    Asset asset = new Asset();
    asset.setRepository(repository);
    asset.setPath("email.json");
    AssetIntegrityCheckerRepository configuration = mock(AssetIntegrityCheckerRepository.class);
    Set<AssetIntegrityChecker> configuredCheckers = new HashSet<>();
    for (IntegrityCheckerType type : types) {
      AssetIntegrityChecker checker = new AssetIntegrityChecker();
      checker.setRepository(repository);
      checker.setAssetExtension("json");
      checker.setIntegrityCheckerType(type);
      configuredCheckers.add(checker);
    }
    when(configuration.findByRepositoryAndAssetExtension(repository, "json"))
        .thenReturn(configuredCheckers);
    IntegrityCheckerFactory factory = new IntegrityCheckerFactory();
    ReflectionTestUtils.setField(factory, "assetIntegrityCheckerRepository", configuration);

    TMTextUnit unit = new TMTextUnit();
    unit.setAsset(asset);
    unit.setContent(source);
    TMTextUnitRepository textUnits = mock(TMTextUnitRepository.class);
    when(textUnits.findById(1L)).thenReturn(Optional.of(unit));
    Locale locale = new Locale();
    locale.setBcp47Tag("fr");
    LocaleRepository locales = mock(LocaleRepository.class);
    when(locales.findById(2L)).thenReturn(Optional.of(locale));
    PluralIntegrityCheckerRelaxer relaxer = new PluralIntegrityCheckerRelaxer();
    TMTextUnitIntegrityCheckService validation = new TMTextUnitIntegrityCheckService();
    ReflectionTestUtils.setField(validation, "integrityCheckerFactory", factory);
    ReflectionTestUtils.setField(validation, "tmTextUnitRepository", textUnits);
    ReflectionTestUtils.setField(validation, "localeRepository", locales);
    ReflectionTestUtils.setField(validation, "pluralIntegrityCheckerRelaxer", relaxer);

    TextUnitBatchImporterService importer = new TextUnitBatchImporterService();
    importer.integrityCheckerFactory = factory;
    importer.pluralIntegrityCheckerRelaxer = relaxer;
    return new Fixture(asset, unit, validation, importer);
  }

  private record Fixture(
      Asset asset,
      TMTextUnit source,
      TMTextUnitIntegrityCheckService validation,
      TextUnitBatchImporterService importer) {}
}
