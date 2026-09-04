package com.box.l10n.mojito.rest.textunit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.box.l10n.mojito.entity.Asset;
import com.box.l10n.mojito.entity.AssetIntegrityChecker;
import com.box.l10n.mojito.entity.Locale;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.TMTextUnit;
import com.box.l10n.mojito.entity.TMTextUnitVariant;
import com.box.l10n.mojito.entity.security.user.User;
import com.box.l10n.mojito.rest.WSTestBase;
import com.box.l10n.mojito.rest.resttemplate.CookieStoreRestTemplate;
import com.box.l10n.mojito.rest.resttemplate.CredentialProvider;
import com.box.l10n.mojito.rest.resttemplate.FormLoginAuthenticationCsrfTokenInterceptor;
import com.box.l10n.mojito.security.Role;
import com.box.l10n.mojito.service.asset.AssetService;
import com.box.l10n.mojito.service.assetintegritychecker.AssetIntegrityCheckerRepository;
import com.box.l10n.mojito.service.assetintegritychecker.integritychecker.IntegrityCheckerType;
import com.box.l10n.mojito.service.locale.LocaleService;
import com.box.l10n.mojito.service.repository.RepositoryService;
import com.box.l10n.mojito.service.security.user.UserService;
import com.box.l10n.mojito.service.tm.TMService;
import com.box.l10n.mojito.service.tm.search.TextUnitDTO;
import com.box.l10n.mojito.test.TestIdWatcher;
import java.util.UUID;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.HttpClientErrorException;

/** Real HTTP, authentication, configured checker, and committed database regression coverage. */
public class TextUnitWSIntegrityOverrideWSTest extends WSTestBase {

  // Same literal-percentage shape as the incident, without product-specific text.
  private static final String PERCENTAGE_SOURCE = "Progress is 100% so you can continue.";
  private static final String PERCENTAGE_TARGET = "პროგრესი 100%-ია და შეგიძლიათ გააგრძელოთ.";
  private static final String CHECK_FAILURE =
      "PrintfLike placeholders are different in source and target";
  private static final String PASSWORD = "test-override-password";

  @Autowired RepositoryService repositoryService;
  @Autowired AssetService assetService;
  @Autowired LocaleService localeService;
  @Autowired TMService tmService;
  @Autowired UserService userService;
  @Autowired AssetIntegrityCheckerRepository checkerRepository;
  @Autowired JdbcTemplate jdbcTemplate;
  @Autowired CredentialProvider credentialProvider;
  @Autowired FormLoginAuthenticationCsrfTokenInterceptor authenticationInterceptor;

  @Rule public TestIdWatcher testIdWatcher = new TestIdWatcher();

  @After
  public void restoreDefaultHttpCredentials() {
    authenticationInterceptor.setCredentialProvider(credentialProvider);
    authenticationInterceptor.resetAuthentication();
  }

  @Test
  public void adminCanAcceptRejectedTranslationDespitePrintfFalsePositive() throws Exception {
    assertPercentageOverride(Role.ROLE_ADMIN);
  }

  @Test
  public void pmCanAcceptRejectedTranslationDespitePrintfFalsePositive() throws Exception {
    assertPercentageOverride(Role.ROLE_PM);
  }

  @Test
  public void adminCanSaveEditedTranslationAfterFailedIntegrityCheck() throws Exception {
    Fixture fixture = createFixture("Hello %s", "Bonjour", Role.ROLE_ADMIN, true);
    logInAs(fixture.user());
    assertCheckFails(fixture, "Salut");

    assertSaved(fixture, "Salut", save(fixture, "Salut"));
  }

  @Test
  public void translatorCannotOverridePrintfFalsePositive() throws Exception {
    assertTranslatorRejected(PERCENTAGE_SOURCE, PERCENTAGE_TARGET);
  }

  @Test
  public void translatorCannotSaveAMissingPlaceholder() throws Exception {
    assertTranslatorRejected("Hello %s", "Bonjour");
  }

  @Test
  public void translatorCanSaveTranslationThatPassesIntegrityChecks() throws Exception {
    Fixture fixture = createFixture("Hello %s", "Bonjour", Role.ROLE_TRANSLATOR, true);
    logInAs(fixture.user());
    assertEquals(Boolean.TRUE, check(fixture, "Bonjour %s").getCheckResult());

    assertSaved(fixture, "Bonjour %s", save(fixture, "Bonjour %s"));
  }

  @Test
  public void adminOverrideStillRequiresLocalePermission() throws Exception {
    Fixture fixture = createFixture(PERCENTAGE_SOURCE, PERCENTAGE_TARGET, Role.ROLE_ADMIN, false);
    logInAs(fixture.user());

    assertSaveRejectedWithoutWrites(fixture, PERCENTAGE_TARGET, HttpStatus.FORBIDDEN);
  }

  @Test
  public void ordinaryUserCannotWriteTranslations() throws Exception {
    Fixture fixture = createFixture("Hello %s", "Bonjour", Role.ROLE_USER, true);
    logInAs(fixture.user());

    assertSaveRejectedWithoutWrites(fixture, "Bonjour %s", HttpStatus.FORBIDDEN);
  }

  private void assertPercentageOverride(Role role) throws Exception {
    Fixture fixture = createFixture(PERCENTAGE_SOURCE, PERCENTAGE_TARGET, role, true);
    logInAs(fixture.user());
    assertCheckFails(fixture, PERCENTAGE_TARGET);

    assertSaved(fixture, PERCENTAGE_TARGET, save(fixture, PERCENTAGE_TARGET));
  }

  private void assertTranslatorRejected(String source, String target) throws Exception {
    Fixture fixture = createFixture(source, target, Role.ROLE_TRANSLATOR, true);
    logInAs(fixture.user());
    assertCheckFails(fixture, target);

    HttpClientErrorException exception =
        assertSaveRejectedWithoutWrites(fixture, target, HttpStatus.UNPROCESSABLE_ENTITY);
    assertTrue(exception.getResponseBodyAsString().contains(CHECK_FAILURE));
  }

  private Fixture createFixture(String source, String target, Role role, boolean allLocales)
      throws Exception {
    Repository repository =
        repositoryService.createRepository(testIdWatcher.getEntityName("repository"));
    repositoryService.addRepositoryLocale(repository, "ka");
    Locale locale = localeService.findByBcp47Tag("ka");
    Asset asset = assetService.createAsset(repository.getId(), "Localizable.strings", false);
    TMTextUnit textUnit =
        tmService.addTMTextUnit(
            repository.getTm().getId(), asset.getId(), "progress", source, null);
    tmService.addTMTextUnitCurrentVariant(
        textUnit.getId(),
        locale.getId(),
        target,
        null,
        TMTextUnitVariant.Status.TRANSLATION_NEEDED,
        false);

    AssetIntegrityChecker checker = new AssetIntegrityChecker();
    checker.setRepository(repository);
    checker.setAssetExtension("strings");
    checker.setIntegrityCheckerType(IntegrityCheckerType.PRINTF_LIKE);
    checkerRepository.saveAndFlush(checker);

    User user =
        userService.createUserWithRole(
            "override-" + UUID.randomUUID(),
            PASSWORD,
            role,
            null,
            null,
            null,
            null,
            allLocales,
            false);
    return new Fixture(textUnit.getId(), locale.getId(), user, target);
  }

  private void logInAs(User user) {
    authenticationInterceptor.setCredentialProvider(
        new CredentialProvider() {
          @Override
          public String getUsername() {
            return user.getUsername();
          }

          @Override
          public String getPassword() {
            return PASSWORD;
          }
        });
    authenticationInterceptor.resetAuthentication();
  }

  private TMTextUnitIntegrityCheckResult check(Fixture fixture, String target) {
    TextUnitCheckBody body = new TextUnitCheckBody();
    body.setTmTextUnitId(fixture.textUnitId());
    body.setContent(target);
    return authenticatedRestTemplate.postForObject(
        "/api/textunits/check", body, TMTextUnitIntegrityCheckResult.class);
  }

  private void assertCheckFails(Fixture fixture, String target) {
    TMTextUnitIntegrityCheckResult result = check(fixture, target);
    assertEquals(Boolean.FALSE, result.getCheckResult());
    assertEquals(CHECK_FAILURE, result.getFailureDetail());
  }

  private ResponseEntity<TextUnitDTO> save(Fixture fixture, String target) {
    TextUnitDTO request = new TextUnitDTO();
    request.setTmTextUnitId(fixture.textUnitId());
    request.setLocaleId(fixture.localeId());
    request.setTarget(target);
    request.setStatus(TMTextUnitVariant.Status.APPROVED);
    request.setIncludedInLocalizedFile(true);
    String csrfToken = authenticatedRestTemplate.getForObject("/api/csrf-token", String.class);
    CookieStoreRestTemplate client = new CookieStoreRestTemplate();
    client.setCookieStoreAndUpdateRequestFactory(
        authenticatedRestTemplate.getRestTemplate().getCookieStore());
    client.setMessageConverters(authenticatedRestTemplate.getRestTemplate().getMessageConverters());
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set(FormLoginAuthenticationCsrfTokenInterceptor.CSRF_HEADER_NAME, csrfToken);
    // Keep the real session and CSRF protection, without the login interceptor swallowing 403s.
    return client.postForEntity(
        authenticatedRestTemplate.getURIForResource("/api/textunits"),
        new HttpEntity<>(request, headers),
        TextUnitDTO.class);
  }

  private void assertSaved(Fixture fixture, String target, ResponseEntity<TextUnitDTO> response) {
    assertEquals(HttpStatus.OK, response.getStatusCode());
    PersistedVariant current = readCurrent(fixture);
    assertEquals(target, current.target());
    assertEquals("APPROVED", current.status());
    assertTrue(current.included());
    assertEquals(fixture.user().getId().longValue(), current.createdBy());
    assertEquals(response.getBody().getTmTextUnitVariantId().longValue(), current.variantId());
    assertEquals(
        response.getBody().getTmTextUnitCurrentVariantId().longValue(), current.currentId());
    assertEquals(2L, historyCount(fixture));
    Long rejectedVariantId =
        jdbcTemplate.queryForObject(
            "select id from tm_text_unit_variant where tm_text_unit_id = ? and locale_id = ?"
                + " and status = 'TRANSLATION_NEEDED' and included_in_localized_file = false",
            Long.class,
            fixture.textUnitId(),
            fixture.localeId());
    assertNotEquals(rejectedVariantId.longValue(), current.variantId());
    assertEquals(
        fixture.originalTarget(),
        jdbcTemplate.queryForObject(
            "select content from tm_text_unit_variant where id = ?",
            String.class,
            rejectedVariantId));
  }

  private HttpClientErrorException assertSaveRejectedWithoutWrites(
      Fixture fixture, String target, HttpStatus expectedStatus) {
    PersistedVariant before = readCurrent(fixture);
    long historyBefore = historyCount(fixture);

    HttpClientErrorException exception =
        assertThrows(HttpClientErrorException.class, () -> save(fixture, target));

    assertEquals(expectedStatus, exception.getStatusCode());
    assertEquals(before, readCurrent(fixture));
    assertEquals(historyBefore, historyCount(fixture));
    assertFalse(before.included());
    return exception;
  }

  private PersistedVariant readCurrent(Fixture fixture) {
    return jdbcTemplate.queryForObject(
        "select c.id as current_id, v.id as variant_id, v.content, v.status,"
            + " v.included_in_localized_file, v.created_by_user_id"
            + " from tm_text_unit_current_variant c"
            + " join tm_text_unit_variant v on v.id = c.tm_text_unit_variant_id"
            + " where c.tm_text_unit_id = ? and c.locale_id = ?",
        (result, row) ->
            new PersistedVariant(
                result.getLong("current_id"),
                result.getLong("variant_id"),
                result.getString("content"),
                result.getString("status"),
                result.getBoolean("included_in_localized_file"),
                result.getLong("created_by_user_id")),
        fixture.textUnitId(),
        fixture.localeId());
  }

  private long historyCount(Fixture fixture) {
    return jdbcTemplate.queryForObject(
        "select count(*) from tm_text_unit_variant where tm_text_unit_id = ? and locale_id = ?",
        Long.class,
        fixture.textUnitId(),
        fixture.localeId());
  }

  private record Fixture(Long textUnitId, Long localeId, User user, String originalTarget) {}

  private record PersistedVariant(
      long currentId,
      long variantId,
      String target,
      String status,
      boolean included,
      long createdBy) {}
}
