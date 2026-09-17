package com.box.l10n.mojito.service.content;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.box.l10n.mojito.entity.Asset;
import com.box.l10n.mojito.entity.Locale;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.RepositoryLocale;
import com.box.l10n.mojito.entity.TMTextUnit;
import com.box.l10n.mojito.entity.TMTextUnitCurrentVariant;
import com.box.l10n.mojito.entity.TMTextUnitVariant;
import com.box.l10n.mojito.service.assetintegritychecker.integritychecker.IntegrityCheckException;
import com.box.l10n.mojito.service.repository.RepositoryLocaleRepository;
import com.box.l10n.mojito.service.security.user.UserService;
import com.box.l10n.mojito.service.tm.TMService;
import com.box.l10n.mojito.service.tm.TMTextUnitCurrentVariantRepository;
import com.box.l10n.mojito.service.tm.TMTextUnitIntegrityCheckService;
import com.box.l10n.mojito.test.ThreadBoundTransactionAdvice;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.server.ResponseStatusException;

public class ContentTranslationCandidateServiceTest {
  private ThreadBoundTransactionAdvice transactionAdvice;
  private static final String SOURCE_MD5 = "0123456789abcdef0123456789abcdef";
  private final UserService users = mock(UserService.class);
  private final RepositoryLocaleRepository locales = mock(RepositoryLocaleRepository.class);
  private final EntityManager entityManager = mock(EntityManager.class);
  private final TMTextUnitCurrentVariantRepository currents =
      mock(TMTextUnitCurrentVariantRepository.class);
  private final ContentTranslationCandidateQueries queries =
      mock(ContentTranslationCandidateQueries.class);
  private final TMTextUnitIntegrityCheckService integrity =
      mock(TMTextUnitIntegrityCheckService.class);
  private final TMService tm = mock(TMService.class);
  private final ContentTranslationCandidateService service =
      new ContentTranslationCandidateService(
          users, locales, entityManager, currents, queries, integrity, tm);
  private final TMTextUnit unit = new TMTextUnit();
  private final RepositoryLocale targetLocale = new RepositoryLocale();

  @Before
  public void setUp() {
    var transactions = mock(PlatformTransactionManager.class);
    when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    transactionAdvice = new ThreadBoundTransactionAdvice(transactions);
    when(users.isCurrentUserAdminOrPm()).thenReturn(true);
    Locale en = new Locale();
    en.setId(10L);
    en.setBcp47Tag("en");
    Locale fr = new Locale();
    fr.setId(11L);
    fr.setBcp47Tag("fr");
    Repository repository = new Repository();
    repository.setId(1L);
    repository.setSourceLocale(en);
    Asset asset = new Asset();
    asset.setId(2L);
    asset.setPath("content/index.mdx");
    asset.setRepository(repository);
    unit.setId(3L);
    unit.setAsset(asset);
    unit.setName("title");
    unit.setContent("A quiet page");
    targetLocale.setLocale(fr);
    targetLocale.setParentLocale(new RepositoryLocale());
    targetLocale.setRepository(repository);
    when(locales.findByRepositoryIdAndLocaleId(1L, 11L)).thenReturn(targetLocale);
    when(entityManager.find(TMTextUnit.class, 3L, LockModeType.PESSIMISTIC_WRITE)).thenReturn(unit);
    when(queries.lockCurrentExtraction(1L, 4L, 2L))
        .thenReturn(new ContentTranslationCandidateQueries.ExtractionSnapshot(5L, SOURCE_MD5));
    when(queries.containsUnit(5L, 2L, 3L)).thenReturn(true);
    when(tm.addTMTextUnitCurrentVariant(
            eq(3L),
            eq(11L),
            anyString(),
            anyString(),
            eq(TMTextUnitVariant.Status.REVIEW_NEEDED),
            eq(true)))
        .thenAnswer(
            invocation -> {
              TMTextUnitVariant variant = new TMTextUnitVariant();
              variant.setId(20L);
              variant.setContent(invocation.getArgument(2));
              variant.setStatus(TMTextUnitVariant.Status.REVIEW_NEEDED);
              TMTextUnitCurrentVariant saved = new TMTextUnitCurrentVariant();
              saved.setId(21L);
              saved.setTmTextUnitVariant(variant);
              return saved;
            });
  }

  @After
  public void restoreTransactions() {
    transactionAdvice.close();
  }

  private ContentTranslationCandidate request() {
    return new ContentTranslationCandidate(
        4L,
        2L,
        3L,
        11L,
        unit.getName(),
        unit.getContent(),
        5L,
        SOURCE_MD5,
        null,
        "Une page tranquille");
  }

  @Test
  public void readsExactSourceRevisionWithoutReadingTargetsOrWriting() {
    var source =
        new ContentTranslationCandidate.Source(1L, 4L, 2L, "content/index.mdx", 5L, SOURCE_MD5);
    when(queries.findCurrentSource(1L, 4L, 2L)).thenReturn(source);
    assertThat(service.source(1L, 4L, 2L)).isEqualTo(source);
    verifyNoInteractions(entityManager, currents, locales, integrity, tm);
  }

  @Test
  public void rejectsUnauthorizedAndInvalidSourceLookupsBeforeReading() {
    when(users.isCurrentUserAdminOrPm()).thenReturn(false);
    assertThatThrownBy(() -> service.source(1L, 4L, 2L)).isInstanceOf(AccessDeniedException.class);
    when(users.isCurrentUserAdminOrPm()).thenReturn(true);
    assertThatThrownBy(() -> service.source(1L, 0L, 2L))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    verifyNoInteractions(queries, entityManager, tm);
  }

  @Test
  public void rejectsMissingOrUnsupportedSourceRevision() {
    assertThatThrownBy(() -> service.source(1L, 4L, 2L))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    verifyNoInteractions(entityManager, tm);
  }

  @Test
  public void savesOnlyAfterLockedAbsentBaselineAndIntegrityChecks() {
    var result = service.create(1L, request());
    assertThat(result.tmTextUnitVariantId()).isEqualTo(20L);
    assertThat(result.status()).isEqualTo(TMTextUnitVariant.Status.REVIEW_NEEDED);
    var order = inOrder(users, entityManager, currents, queries, integrity, tm);
    order.verify(users).checkUserCanEditLocale(11L);
    order.verify(entityManager).find(TMTextUnit.class, 3L, LockModeType.PESSIMISTIC_WRITE);
    order.verify(entityManager).refresh(unit, LockModeType.PESSIMISTIC_WRITE);
    order.verify(currents).findForUpdateByLocaleIdAndTmTextUnitId(11L, 3L);
    order.verify(queries).hasTargetHistory(3L, 11L);
    order.verify(queries).lockCurrentExtraction(1L, 4L, 2L);
    order.verify(queries).containsUnit(5L, 2L, 3L);
    order.verify(integrity).checkTMTextUnitIntegrity(3L, "Une page tranquille", 11L);
    order
        .verify(tm)
        .addTMTextUnitCurrentVariant(
            3L,
            11L,
            "Une page tranquille",
            "Generated translation candidate",
            TMTextUnitVariant.Status.REVIEW_NEEDED,
            true);
    order.verify(entityManager).flush();
  }

  @Test
  public void deniesNonManagersAndLocalePermissionsBeforeReadingOrWriting() {
    when(users.isCurrentUserAdminOrPm()).thenReturn(false);
    assertThatThrownBy(() -> service.create(1L, request()))
        .isInstanceOf(AccessDeniedException.class);
    verifyNoInteractions(entityManager, queries, tm);
    when(users.isCurrentUserAdminOrPm()).thenReturn(true);
    doThrow(new AccessDeniedException("locale")).when(users).checkUserCanEditLocale(11L);
    assertThatThrownBy(() -> service.create(1L, request()))
        .isInstanceOf(AccessDeniedException.class);
    verifyNoInteractions(entityManager, queries, tm);
  }

  @Test
  public void rejectsUnknownAndSourceLocales() {
    when(locales.findByRepositoryIdAndLocaleId(1L, 11L)).thenReturn(null);
    fails(HttpStatus.BAD_REQUEST, request());
    when(locales.findByRepositoryIdAndLocaleId(1L, 11L)).thenReturn(targetLocale);
    targetLocale.setParentLocale(null);
    fails(HttpStatus.BAD_REQUEST, request());
    verifyNoInteractions(entityManager, queries, tm);
  }

  @Test
  public void rejectsCurrentRowsIncludingBlankAndNullVariants() {
    var current = new TMTextUnitCurrentVariant();
    when(currents.findForUpdateByLocaleIdAndTmTextUnitId(11L, 3L)).thenReturn(current);
    fails(HttpStatus.CONFLICT, request());
    var blank = new TMTextUnitVariant();
    blank.setContent("");
    current.setTmTextUnitVariant(blank);
    fails(HttpStatus.CONFLICT, request());
    verify(entityManager, times(2)).refresh(current, LockModeType.PESSIMISTIC_WRITE);
    verifyNoInteractions(tm);
  }

  @Test
  public void rejectsHistoricalHumanWorkEvenWhenCurrentPointerWasRemoved() {
    when(queries.hasTargetHistory(3L, 11L)).thenReturn(true);
    fails(HttpStatus.CONFLICT, request());
    verifyNoInteractions(tm);
  }

  @Test
  public void rejectsChangedSourceIdentityAndCrossRepositoryIds() {
    var expected = request();
    unit.setName("different");
    fails(HttpStatus.CONFLICT, expected);
    unit.setName(expected.name());
    unit.setContent("new source");
    fails(HttpStatus.CONFLICT, expected);
    unit.setContent(expected.expectedSource());
    unit.getAsset().getRepository().setId(9L);
    fails(HttpStatus.CONFLICT, expected);
    verifyNoInteractions(tm);
  }

  @Test
  public void rejectsChangedExtractionAndRemovedMembership() {
    when(queries.lockCurrentExtraction(1L, 4L, 2L))
        .thenReturn(new ContentTranslationCandidateQueries.ExtractionSnapshot(6L, SOURCE_MD5));
    fails(HttpStatus.CONFLICT, request());
    when(queries.lockCurrentExtraction(1L, 4L, 2L))
        .thenReturn(
            new ContentTranslationCandidateQueries.ExtractionSnapshot(
                5L, "abcdef0123456789abcdef0123456789"));
    fails(HttpStatus.CONFLICT, request());
    when(queries.lockCurrentExtraction(1L, 4L, 2L))
        .thenReturn(new ContentTranslationCandidateQueries.ExtractionSnapshot(5L, SOURCE_MD5));
    when(queries.containsUnit(5L, 2L, 3L)).thenReturn(false);
    fails(HttpStatus.CONFLICT, request());
    verifyNoInteractions(tm);
  }

  @Test
  public void generatedCandidatesCannotBypassConfiguredIntegrityAsAdmin() {
    doThrow(new IntegrityCheckException("protected token"))
        .when(integrity)
        .checkTMTextUnitIntegrity(anyLong(), anyString(), anyLong());
    fails(HttpStatus.UNPROCESSABLE_ENTITY, request());
    verifyNoInteractions(tm);
  }

  @Test
  public void rejectsExplicitOverwriteBaselineAndOversizedOrBlankTargets() {
    for (String target : new String[] {"", " ", "x".repeat(10001)})
      fails(
          HttpStatus.BAD_REQUEST,
          new ContentTranslationCandidate(
              4L, 2L, 3L, 11L, "title", unit.getContent(), 5L, SOURCE_MD5, null, target));
    fails(
        HttpStatus.BAD_REQUEST,
        new ContentTranslationCandidate(
            4L, 2L, 3L, 11L, "title", unit.getContent(), 5L, SOURCE_MD5, 20L, "Bonjour"));
    verifyNoInteractions(tm);
  }

  @Test
  public void requiresAValidAssetContentHash() {
    for (String hash : new String[] {null, "", "wrong"})
      fails(
          HttpStatus.BAD_REQUEST,
          new ContentTranslationCandidate(
              4L, 2L, 3L, 11L, "title", unit.getContent(), 5L, hash, null, "Bonjour"));
    verifyNoInteractions(tm);
  }

  @Test
  public void jsonRequestRequiresAnExplicitAbsentVariantBaseline() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    String json = mapper.writeValueAsString(request());
    assertThat(mapper.readValue(json, ContentTranslationCandidate.class).expectedVariantId())
        .isNull();
    assertThatThrownBy(
            () ->
                mapper.readValue(
                    json.replace("\"expectedVariantId\":null,", ""),
                    ContentTranslationCandidate.class))
        .hasMessageContaining("expectedVariantId");
  }

  @Test
  public void mandatoryMdxChecksRejectExecutableStructureLinksAndNewlines() {
    unit.setContent("Read [the guide](guide.html) and `sample`");
    ContentTranslationCandidateService.validateContent(
        unit, "Lisez [le guide](guide.html) et `sample`", "fr");
    for (String target :
        new String[] {
          "Read [the guide](evil.html) and `sample`", "<Component />", "{execute()}", "Two\nlines"
        }) {
      assertThatThrownBy(
              () -> ContentTranslationCandidateService.validateContent(unit, target, "fr"))
          .isInstanceOf(ResponseStatusException.class);
    }
  }

  @Test
  public void mandatoryMf2ChecksPreserveDateArgumentAndFunctionContract() {
    unit.getAsset().setPath("content/messages.mf2.json");
    unit.setContent("Your session is on {$date :date dateStyle=long timeZone=UTC}.");
    ContentTranslationCandidateService.validateContent(
        unit, "Votre séance est le {$date :date dateStyle=long timeZone=UTC}.", "fr");
    for (String target :
        new String[] {
          "Missing argument",
          "Invalid {$date",
          "Date {$other :date dateStyle=long timeZone=UTC}",
          "Date {$date :number}"
        }) {
      assertThatThrownBy(
              () -> ContentTranslationCandidateService.validateContent(unit, target, "fr"))
          .isInstanceOf(ResponseStatusException.class);
    }
  }

  @Test
  public void requiresFrenchPluralCategoriesAndKeepsTheNumericArgument() {
    unit.getAsset().setPath("content/messages.mf2.json");
    unit.setContent(
        ".input {$count :integer}\n.match $count\none {{You have {$count} session.}}\n* {{You have {$count} sessions.}}");
    String french =
        ".input {$count :integer}\n.match $count\none {{Vous avez {$count} séance.}}\nmany {{Vous avez {$count} séances.}}\n* {{Vous avez {$count} séances.}}";
    ContentTranslationCandidateService.validateContent(unit, french, "fr");
    assertThatThrownBy(
            () ->
                ContentTranslationCandidateService.validateContent(
                    unit, french.replace("many {{Vous avez {$count} séances.}}\n", ""), "fr"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("mf2-plural-category-missing");
  }

  private void fails(HttpStatus status, ContentTranslationCandidate request) {
    assertThatThrownBy(() -> service.create(1L, request))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            exception -> assertThat(exception.getStatusCode()).isEqualTo(status));
  }
}
