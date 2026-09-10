package com.box.l10n.mojito.rest.textunit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.Asset;
import com.box.l10n.mojito.entity.AssetIntegrityChecker;
import com.box.l10n.mojito.entity.Locale;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.TMTextUnit;
import com.box.l10n.mojito.entity.TMTextUnitCurrentVariant;
import com.box.l10n.mojito.entity.TMTextUnitVariant;
import com.box.l10n.mojito.entity.security.user.Authority;
import com.box.l10n.mojito.entity.security.user.User;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.security.AuditorAwareImpl;
import com.box.l10n.mojito.security.Role;
import com.box.l10n.mojito.service.assetintegritychecker.AssetIntegrityCheckerRepository;
import com.box.l10n.mojito.service.assetintegritychecker.integritychecker.IntegrityCheckException;
import com.box.l10n.mojito.service.assetintegritychecker.integritychecker.IntegrityCheckerFactory;
import com.box.l10n.mojito.service.assetintegritychecker.integritychecker.IntegrityCheckerType;
import com.box.l10n.mojito.service.assetintegritychecker.integritychecker.TranslationIntegrityCheckerException;
import com.box.l10n.mojito.service.locale.LocaleRepository;
import com.box.l10n.mojito.service.security.user.UserRepository;
import com.box.l10n.mojito.service.security.user.UserService;
import com.box.l10n.mojito.service.tm.TMService;
import com.box.l10n.mojito.service.tm.TMTextUnitIntegrityCheckService;
import com.box.l10n.mojito.service.tm.TMTextUnitRepository;
import com.box.l10n.mojito.service.tm.search.TextUnitDTO;
import com.box.l10n.mojito.service.tm.search.TextUnitSearcherParameters;
import com.box.l10n.mojito.service.tm.search.TextUnitTextSearch;
import com.box.l10n.mojito.service.tm.search.TextUnitTextSearchBooleanOperator;
import com.box.l10n.mojito.service.tm.search.TextUnitTextSearchField;
import com.box.l10n.mojito.service.tm.search.TextUnitTextSearchPredicate;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import org.junit.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

public class TextUnitWSSearchValidationTest {

  TextUnitWS textUnitWS = new TextUnitWS();

  @Test
  public void mapsStableOrderingOnlyWhenRequested() throws Exception {
    assertFalse(
        textUnitWS
            .textUnitSearchBodyToTextUnitSearcherParameters(new TextUnitSearchBody())
            .isOrderedByTextUnitID());
    TextUnitSearchBody body =
        new ObjectMapper().readValue("{\"orderedByTextUnitId\":true}", TextUnitSearchBody.class);

    assertTrue(
        textUnitWS.textUnitSearchBodyToTextUnitSearcherParameters(body).isOrderedByTextUnitID());
  }

  @Test
  public void mapsTranslationCreatedDates() throws Exception {
    TextUnitSearchBody body = new TextUnitSearchBody();
    body.setRepositoryIds(new ArrayList<>(Arrays.asList(1L)));
    body.setLocaleTags(new ArrayList<>(Arrays.asList("en")));
    body.setTmTextUnitVariantCreatedBefore(ZonedDateTime.parse("2024-01-01T00:00:00Z"));
    body.setTmTextUnitVariantCreatedAfter(ZonedDateTime.parse("2023-12-01T00:00:00Z"));

    TextUnitSearcherParameters parameters =
        textUnitWS.textUnitSearchBodyToTextUnitSearcherParameters(body);

    assertEquals(
        ZonedDateTime.parse("2024-01-01T00:00:00Z"),
        parameters.getTmTextUnitVariantCreatedBefore());
    assertEquals(
        ZonedDateTime.parse("2023-12-01T00:00:00Z"), parameters.getTmTextUnitVariantCreatedAfter());
  }

  @Test
  public void mapsCompoundTextSearch() throws Exception {
    TextUnitSearchBody body = new TextUnitSearchBody();
    body.setRepositoryIds(new ArrayList<>(Arrays.asList(1L)));
    body.setLocaleTags(new ArrayList<>(Arrays.asList("en")));

    TextUnitTextSearchPredicate sourcePredicate = new TextUnitTextSearchPredicate();
    sourcePredicate.setField(TextUnitTextSearchField.SOURCE);
    sourcePredicate.setValue("source text");

    TextUnitTextSearchPredicate targetPredicate = new TextUnitTextSearchPredicate();
    targetPredicate.setField(TextUnitTextSearchField.TARGET);
    targetPredicate.setValue("target text");

    TextUnitTextSearchPredicate commentPredicate = new TextUnitTextSearchPredicate();
    commentPredicate.setField(TextUnitTextSearchField.COMMENT);
    commentPredicate.setValue("comment text");

    TextUnitTextSearch textSearch = new TextUnitTextSearch();
    textSearch.setOperator(TextUnitTextSearchBooleanOperator.OR);
    textSearch.setPredicates(Arrays.asList(sourcePredicate, targetPredicate, commentPredicate));
    body.setTextSearch(textSearch);

    TextUnitSearcherParameters parameters =
        textUnitWS.textUnitSearchBodyToTextUnitSearcherParameters(body);

    assertEquals(TextUnitTextSearchBooleanOperator.OR, parameters.getTextSearch().getOperator());
    assertEquals(3, parameters.getTextSearch().getPredicates().size());
    assertEquals(
        TextUnitTextSearchField.SOURCE,
        parameters.getTextSearch().getPredicates().get(0).getField());
    assertEquals("target text", parameters.getTextSearch().getPredicates().get(1).getValue());
    assertEquals(
        TextUnitTextSearchField.COMMENT,
        parameters.getTextSearch().getPredicates().get(2).getField());
  }

  @Test
  public void checkTMTextUnitPassesRequestedLocale() {
    textUnitWS.meterRegistry = new SimpleMeterRegistry();
    var service = mock(TMTextUnitIntegrityCheckService.class);
    textUnitWS.tmTextUnitIntegrityCheckService = service;
    TextUnitCheckBody body = new TextUnitCheckBody();
    body.setTmTextUnitId(321L);
    body.setContent("Bonjour");
    body.setLocaleId(27L);
    assertEquals(Boolean.TRUE, textUnitWS.checkTMTextUnit(body).getCheckResult());
    verify(service).checkTMTextUnitIntegrity(321L, "Bonjour", 27L);
  }

  @Test
  public void checkTMTextUnitRecordsSuccessMetric() {
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    TMTextUnitIntegrityCheckService integrityCheckService =
        mock(TMTextUnitIntegrityCheckService.class);
    textUnitWS.meterRegistry = meterRegistry;
    textUnitWS.tmTextUnitIntegrityCheckService = integrityCheckService;
    TextUnitCheckBody body = new TextUnitCheckBody();
    body.setTmTextUnitId(321L);
    body.setContent("Bonjour");

    TMTextUnitIntegrityCheckResult result = textUnitWS.checkTMTextUnit(body);

    assertEquals(Boolean.TRUE, result.getCheckResult());
    assertEquals(1.0, integrityCheckDurationCount(meterRegistry, "success"), 0.0);
    verify(integrityCheckService).checkTMTextUnitIntegrity(321L, "Bonjour", null);
  }

  @Test
  public void checkTMTextUnitRecordsFailureMetric() {
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    TMTextUnitIntegrityCheckService integrityCheckService =
        mock(TMTextUnitIntegrityCheckService.class);
    doThrow(new IntegrityCheckException("Missing placeholder"))
        .when(integrityCheckService)
        .checkTMTextUnitIntegrity(321L, "Bonjour", null);
    textUnitWS.meterRegistry = meterRegistry;
    textUnitWS.tmTextUnitIntegrityCheckService = integrityCheckService;
    TextUnitCheckBody body = new TextUnitCheckBody();
    body.setTmTextUnitId(321L);
    body.setContent("Bonjour");

    TMTextUnitIntegrityCheckResult result = textUnitWS.checkTMTextUnit(body);

    assertEquals(Boolean.FALSE, result.getCheckResult());
    assertEquals("Missing placeholder", result.getFailureDetail());
    assertEquals(1.0, integrityCheckDurationCount(meterRegistry, "failure"), 0.0);
    verify(integrityCheckService).checkTMTextUnitIntegrity(321L, "Bonjour", null);
  }

  @Test
  public void addTextUnitValidatesBeforeSavingForUsersAndTranslators() {
    for (Role role : Arrays.asList(Role.ROLE_USER, Role.ROLE_TRANSLATOR)) {
      TMTextUnitIntegrityCheckService integrityCheckService =
          mock(TMTextUnitIntegrityCheckService.class);
      TMService tmService = mock(TMService.class);
      doThrow(new IntegrityCheckException("Missing placeholder"))
          .when(integrityCheckService)
          .checkTMTextUnitIntegrity(321L, "Bonjour", 12L);
      textUnitWS.tmTextUnitIntegrityCheckService = integrityCheckService;
      textUnitWS.tmService = tmService;
      textUnitWS.userService = userServiceWithRole(role, true);
      TextUnitDTO textUnit = new TextUnitDTO();
      textUnit.setTmTextUnitId(321L);
      textUnit.setLocaleId(12L);
      textUnit.setTarget("Bonjour");

      ResponseStatusException exception =
          assertThrows(ResponseStatusException.class, () -> textUnitWS.addTextUnit(textUnit));

      assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, exception.getStatusCode());
      assertEquals("Missing placeholder", exception.getReason());
      verify(integrityCheckService).checkTMTextUnitIntegrity(321L, "Bonjour", 12L);
      verifyNoInteractions(tmService);
    }
  }

  @Test
  public void addTextUnitAllowsAdminAndPmIntegrityOverridesWithRequestedStatus() {
    for (Role role : Arrays.asList(Role.ROLE_ADMIN, Role.ROLE_PM)) {
      for (TMTextUnitVariant.Status status :
          Arrays.asList(
              TMTextUnitVariant.Status.APPROVED, TMTextUnitVariant.Status.TRANSLATION_NEEDED)) {
        boolean includedInLocalizedFile = status == TMTextUnitVariant.Status.APPROVED;
        TMTextUnitIntegrityCheckService integrityCheckService =
            mock(TMTextUnitIntegrityCheckService.class);
        doThrow(new IntegrityCheckException("Missing placeholder"))
            .when(integrityCheckService)
            .checkTMTextUnitIntegrity(321L, "Caf\u00e9", 12L);
        TMService tmService = mock(TMService.class);
        TMTextUnitVariant variant = new TMTextUnitVariant();
        variant.setId(456L);
        TMTextUnitCurrentVariant currentVariant = new TMTextUnitCurrentVariant();
        currentVariant.setId(789L);
        currentVariant.setTmTextUnitVariant(variant);
        when(tmService.addTMTextUnitCurrentVariant(
                321L, 12L, "Caf\u00e9", "Reviewed manually", status, includedInLocalizedFile))
            .thenReturn(currentVariant);
        textUnitWS.tmTextUnitIntegrityCheckService = integrityCheckService;
        textUnitWS.tmService = tmService;
        textUnitWS.userService = userServiceWithRole(role, true);
        TextUnitDTO textUnit = new TextUnitDTO();
        textUnit.setTmTextUnitId(321L);
        textUnit.setLocaleId(12L);
        textUnit.setTarget("Cafe\u0301");
        textUnit.setTargetComment("Reviewed manually");
        textUnit.setStatus(status);
        textUnit.setIncludedInLocalizedFile(includedInLocalizedFile);

        TextUnitDTO saved = textUnitWS.addTextUnit(textUnit);

        assertEquals("Caf\u00e9", saved.getTarget());
        assertEquals(status, saved.getStatus());
        assertEquals(includedInLocalizedFile, saved.isIncludedInLocalizedFile());
        assertEquals(Long.valueOf(456L), saved.getTmTextUnitVariantId());
        assertEquals(Long.valueOf(789L), saved.getTmTextUnitCurrentVariantId());
        verify(tmService)
            .addTMTextUnitCurrentVariant(
                321L, 12L, "Caf\u00e9", "Reviewed manually", status, includedInLocalizedFile);
        verifyNoInteractions(integrityCheckService);
      }
    }
  }

  @Test
  public void addTextUnitReturnsStoredVariantCreatorInsteadOfRequestAttribution() {
    for (String username : Arrays.asList("original-saver", null)) {
      TMService tmService = mock(TMService.class);
      TMTextUnitVariant variant = new TMTextUnitVariant();
      variant.setId(456L);
      if (username != null) {
        User creator = new User();
        creator.setUsername(username);
        variant.setCreatedByUser(creator);
      }
      User markerCreator = new User();
      markerCreator.setUsername("current-marker-creator");
      TMTextUnitCurrentVariant currentVariant = new TMTextUnitCurrentVariant();
      currentVariant.setId(789L);
      currentVariant.setCreatedByUser(markerCreator);
      currentVariant.setTmTextUnitVariant(variant);
      when(tmService.addTMTextUnitCurrentVariant(
              321L, 12L, "Bonjour", null, TMTextUnitVariant.Status.APPROVED, true))
          .thenReturn(currentVariant);
      textUnitWS.tmService = tmService;
      textUnitWS.userService = userServiceWithRole(Role.ROLE_ADMIN, true);
      TextUnitDTO textUnit = new TextUnitDTO();
      textUnit.setTmTextUnitId(321L);
      textUnit.setLocaleId(12L);
      textUnit.setTarget("Bonjour");
      textUnit.setStatus(TMTextUnitVariant.Status.APPROVED);
      textUnit.setIncludedInLocalizedFile(true);
      textUnit.setTranslationCreatedByUsername("client-supplied-creator");

      TextUnitDTO saved = textUnitWS.addTextUnit(textUnit);

      assertEquals(username, saved.getTranslationCreatedByUsername());
      assertEquals(Long.valueOf(456L), saved.getTmTextUnitVariantId());
    }
  }

  @Test
  public void addTextUnitIntegrityOverrideStillRequiresLocaleAccess() {
    textUnitWS.userService = userServiceWithRole(Role.ROLE_ADMIN, false);
    textUnitWS.tmTextUnitIntegrityCheckService = mock(TMTextUnitIntegrityCheckService.class);
    textUnitWS.tmService = mock(TMService.class);
    TextUnitDTO textUnit = new TextUnitDTO();
    textUnit.setTmTextUnitId(321L);
    textUnit.setLocaleId(12L);
    textUnit.setTarget("Bonjour");

    assertThrows(AccessDeniedException.class, () -> textUnitWS.addTextUnit(textUnit));

    verifyNoInteractions(textUnitWS.tmTextUnitIntegrityCheckService, textUnitWS.tmService);
  }

  @Test
  public void addTextUnitUsesConfiguredFormatJsCheckerBeforeSaving() {
    Repository repository = new Repository();
    Asset asset = new Asset();
    asset.setRepository(repository);
    asset.setPath("messages.json");
    TMTextUnit tmTextUnit = new TMTextUnit();
    tmTextUnit.setAsset(asset);
    tmTextUnit.setContent("Hello {name}");

    AssetIntegrityChecker configuredChecker = new AssetIntegrityChecker();
    configuredChecker.setRepository(repository);
    configuredChecker.setAssetExtension("json");
    configuredChecker.setIntegrityCheckerType(IntegrityCheckerType.FORMATJS);
    AssetIntegrityCheckerRepository checkerRepository = mock(AssetIntegrityCheckerRepository.class);
    when(checkerRepository.findByRepositoryAndAssetExtension(repository, "json"))
        .thenReturn(Set.of(configuredChecker));

    IntegrityCheckerFactory checkerFactory = new IntegrityCheckerFactory();
    ReflectionTestUtils.setField(
        checkerFactory, "assetIntegrityCheckerRepository", checkerRepository);
    TMTextUnitRepository textUnitRepository = mock(TMTextUnitRepository.class);
    when(textUnitRepository.findById(321L)).thenReturn(Optional.of(tmTextUnit));
    TMTextUnitIntegrityCheckService integrityCheckService = new TMTextUnitIntegrityCheckService();
    ReflectionTestUtils.setField(integrityCheckService, "integrityCheckerFactory", checkerFactory);
    ReflectionTestUtils.setField(integrityCheckService, "tmTextUnitRepository", textUnitRepository);
    Locale locale = new Locale();
    locale.setBcp47Tag("fr");
    LocaleRepository localeRepository = mock(LocaleRepository.class);
    when(localeRepository.findById(12L)).thenReturn(Optional.of(locale));
    ReflectionTestUtils.setField(integrityCheckService, "localeRepository", localeRepository);

    TMService tmService = mock(TMService.class);
    textUnitWS.tmTextUnitIntegrityCheckService = integrityCheckService;
    textUnitWS.tmService = tmService;
    textUnitWS.userService = mock(UserService.class);
    TextUnitDTO textUnit = new TextUnitDTO();
    textUnit.setTmTextUnitId(321L);
    textUnit.setLocaleId(12L);
    textUnit.setTarget("Bonjour {other}");

    ResponseStatusException exception =
        assertThrows(ResponseStatusException.class, () -> textUnitWS.addTextUnit(textUnit));

    assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, exception.getStatusCode());
    assertTrue(exception.getCause() instanceof TranslationIntegrityCheckerException);
    assertTrue(exception.getReason().contains("FORMATJS translation integrity rejected target"));
    verify(checkerRepository).findByRepositoryAndAssetExtension(repository, "json");
    verifyNoInteractions(tmService);
  }

  @Test
  public void addTextUnitUsesPersistedTargetLocaleForConfiguredMf2PluralValidation() {
    String message =
        ".input {$count :number}\n.match $count\none {{One item}}\n* {{{ $count } items}}";
    Repository repository = new Repository();
    Asset asset = new Asset();
    asset.setRepository(repository);
    asset.setPath("messages.mf2");
    TMTextUnit source = new TMTextUnit();
    source.setAsset(asset);
    source.setContent(message);
    AssetIntegrityChecker configuredChecker = new AssetIntegrityChecker();
    configuredChecker.setRepository(repository);
    configuredChecker.setAssetExtension("mf2");
    configuredChecker.setIntegrityCheckerType(IntegrityCheckerType.MF2);
    AssetIntegrityCheckerRepository checkerRepository = mock(AssetIntegrityCheckerRepository.class);
    when(checkerRepository.findByRepositoryAndAssetExtension(repository, "mf2"))
        .thenReturn(Set.of(configuredChecker));
    IntegrityCheckerFactory checkerFactory = new IntegrityCheckerFactory();
    ReflectionTestUtils.setField(
        checkerFactory, "assetIntegrityCheckerRepository", checkerRepository);
    TMTextUnitRepository textUnitRepository = mock(TMTextUnitRepository.class);
    when(textUnitRepository.findById(321L)).thenReturn(Optional.of(source));
    Locale english = new Locale();
    english.setBcp47Tag("en");
    Locale arabic = new Locale();
    arabic.setBcp47Tag("ar");
    LocaleRepository localeRepository = mock(LocaleRepository.class);
    when(localeRepository.findById(12L)).thenReturn(Optional.of(english));
    when(localeRepository.findById(13L)).thenReturn(Optional.of(arabic));
    TMTextUnitIntegrityCheckService integrityCheckService = new TMTextUnitIntegrityCheckService();
    ReflectionTestUtils.setField(integrityCheckService, "integrityCheckerFactory", checkerFactory);
    ReflectionTestUtils.setField(integrityCheckService, "tmTextUnitRepository", textUnitRepository);
    ReflectionTestUtils.setField(integrityCheckService, "localeRepository", localeRepository);
    TMService tmService = mock(TMService.class);
    TMTextUnitVariant variant = new TMTextUnitVariant();
    variant.setId(456L);
    TMTextUnitCurrentVariant currentVariant = new TMTextUnitCurrentVariant();
    currentVariant.setId(789L);
    currentVariant.setTmTextUnitVariant(variant);
    when(tmService.addTMTextUnitCurrentVariant(
            321L, 12L, message, null, TMTextUnitVariant.Status.APPROVED, true))
        .thenReturn(currentVariant);
    textUnitWS.tmTextUnitIntegrityCheckService = integrityCheckService;
    textUnitWS.tmService = tmService;
    textUnitWS.userService = userServiceWithRole(Role.ROLE_TRANSLATOR, true);
    TextUnitDTO textUnit = new TextUnitDTO();
    textUnit.setTmTextUnitId(321L);
    textUnit.setLocaleId(12L);
    textUnit.setTarget(message);
    textUnit.setStatus(TMTextUnitVariant.Status.APPROVED);
    textUnit.setIncludedInLocalizedFile(true);

    assertEquals(Long.valueOf(456L), textUnitWS.addTextUnit(textUnit).getTmTextUnitVariantId());
    textUnit.setLocaleId(13L);
    ResponseStatusException failure =
        assertThrows(ResponseStatusException.class, () -> textUnitWS.addTextUnit(textUnit));

    assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, failure.getStatusCode());
    assertTrue(failure.getReason().contains("mf2-plural-category-missing"));
    verify(localeRepository).findById(12L);
    verify(localeRepository).findById(13L);
    verify(tmService)
        .addTMTextUnitCurrentVariant(
            321L, 12L, message, null, TMTextUnitVariant.Status.APPROVED, true);
    verifyNoMoreInteractions(tmService);
  }

  private double integrityCheckDurationCount(SimpleMeterRegistry meterRegistry, String result) {
    return meterRegistry
        .find("TextUnitWS.integrityCheckDuration")
        .tag("result", result)
        .timer()
        .count();
  }

  private UserService userServiceWithRole(Role role, boolean canTranslateAllLocales) {
    User user = new User();
    user.setUsername("test-user");
    user.setCanTranslateAllLocales(canTranslateAllLocales);
    Authority authority = new Authority();
    authority.setAuthority(role.name());
    user.setAuthorities(Set.of(authority));
    AuditorAwareImpl auditor = mock(AuditorAwareImpl.class);
    when(auditor.getCurrentAuditor()).thenReturn(Optional.of(user));
    UserRepository userRepository = mock(UserRepository.class);
    when(userRepository.findByUsername(user.getUsername())).thenReturn(user);
    UserService userService = new UserService();
    ReflectionTestUtils.setField(userService, "auditorAwareImpl", auditor);
    ReflectionTestUtils.setField(userService, "userRepository", userRepository);
    return userService;
  }
}
