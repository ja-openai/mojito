package com.box.l10n.mojito.service.tm;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.box.l10n.mojito.entity.Locale;
import com.box.l10n.mojito.entity.RepositoryLocale;
import com.box.l10n.mojito.entity.TMTextUnitCurrentVariant;
import com.box.l10n.mojito.entity.TMTextUnitVariant;
import com.box.l10n.mojito.service.content.ContentTranslationCandidate;
import com.box.l10n.mojito.service.content.ContentTranslationCandidateQueries;
import com.box.l10n.mojito.service.content.ContentTranslationCandidateService;
import com.box.l10n.mojito.service.repository.RepositoryLocaleRepository;
import com.box.l10n.mojito.service.security.user.UserService;
import com.box.l10n.mojito.service.tm.search.TextUnitDTO;
import com.box.l10n.mojito.test.ThreadBoundTransactionAdvice;
import jakarta.persistence.EntityManager;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Real parent/current locks and committed visibility in HSQL; no external services. */
public class TMTextUnitSaveConcurrencyTest {
  private static final String MD5 = "0123456789abcdef0123456789abcdef";
  private LocalContainerEntityManagerFactoryBean factory;
  private JpaTransactionManager transactions;
  private JdbcTemplate jdbc;

  @Before
  public void setup() {
    var source =
        new DriverManagerDataSource(
            "jdbc:hsqldb:mem:save-race-" + UUID.randomUUID() + ";sql.syntax_mys=true", "sa", "");
    jdbc = new JdbcTemplate(source);
    factory = new LocalContainerEntityManagerFactoryBean();
    factory.setDataSource(source);
    factory.setPackagesToScan("com.box.l10n.mojito.entity");
    factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
    factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop"));
    factory.afterPropertiesSet();
    transactions = new JpaTransactionManager(factory.getObject());
    transactions.setDataSource(source);
    transactions.afterPropertiesSet();
    jdbc.update("insert into locale (id, bcp47_tag) values (10, 'en'), (11, 'fr')");
    jdbc.update("insert into tm (id) values (1)");
    jdbc.update("insert into repository_statistic (id) values (1)");
    jdbc.update(
        "insert into repository (id, name, source_locale_id, tm_id, repository_statistic_id, deleted, checkSLA, hidden) values (1, 'demo', 10, 1, 1, false, false, false)");
    jdbc.update(
        "insert into asset (id, repository_id, path, deleted, `virtual`) values (2, 1, 'page.mdx', false, false)");
    jdbc.update(
        "insert into tm_text_unit (id, asset_id, tm_id, name, content) values (3, 2, 1, 'title', 'A quiet page')");
  }

  @After
  public void close() {
    if (factory != null) factory.destroy();
    if (jdbc != null) jdbc.execute("shutdown");
  }

  @Test
  public void concurrentFirstEditorsPreserveFirstCommitAndRejectTheStaleAbsentBaseline()
      throws Exception {
    race(false, false);
  }

  @Test
  public void candidateCommittedDuringHumanFirstSaveReturnsAConflictToTheHuman() throws Exception {
    race(true, false);
  }

  @Test
  public void humanCommittedDuringCandidateImportCannotBeOverwrittenByTheCandidate()
      throws Exception {
    race(false, true);
  }

  @Test
  public void concurrentRecreationAfterDeletionPreservesTheFirstNewTarget() throws Exception {
    jdbc.update(
        "insert into tm_text_unit_current_variant (id, tm_id, asset_id, tm_text_unit_id, locale_id) values (50, 1, 2, 3, 11)");
    race(false, false);
  }

  @Test
  public void guardStillRequiresAnExistingSpringManagedTransaction() {
    try (var ignored = new ThreadBoundTransactionAdvice(transactions)) {
      var guard =
          new TMTextUnitSaveGuardService(
              mock(UserService.class),
              mock(EntityManager.class),
              mock(TMTextUnitCurrentVariantRepository.class));
      assertThatThrownBy(() -> guard.save(3L, 11L, null, TextUnitDTO::new))
          .isInstanceOf(IllegalTransactionStateException.class);
    }
  }

  private void race(boolean firstCandidate, boolean secondCandidate) throws Exception {
    var firstInsideWrite = new CountDownLatch(1);
    var releaseFirst = new CountDownLatch(1);
    var secondStarted = new CountDownLatch(1);
    var fixtureThreads = new HashSet<Thread>();
    var workers =
        (ThreadPoolExecutor)
            Executors.newFixedThreadPool(
                2,
                task -> {
                  var thread = new Thread(task, "translation-save-race");
                  fixtureThreads.add(thread);
                  return thread;
                });
    workers.prestartAllCoreThreads();
    try (var advice = new ThreadBoundTransactionAdvice(transactions, fixtureThreads);
        workers) {
      var first =
          workers.submit(
              () ->
                  save(
                      firstCandidate,
                      () -> {
                        firstInsideWrite.countDown();
                        await(releaseFirst);
                      }));
      try {
        assertThat(firstInsideWrite.await(10, TimeUnit.SECONDS)).isTrue();
        var second =
            workers.submit(
                () -> {
                  secondStarted.countDown();
                  return save(secondCandidate, () -> {});
                });
        assertThat(secondStarted.await(10, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> second.get(200, TimeUnit.MILLISECONDS))
            .isInstanceOf(TimeoutException.class);
        releaseFirst.countDown();
        assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(200);
        assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(409);
      } finally {
        releaseFirst.countDown();
      }
    }
    assertThat(jdbc.queryForObject("select count(*) from tm_text_unit_variant", Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select tm_text_unit_variant_id from tm_text_unit_current_variant", Long.class))
        .isEqualTo(100L);
  }

  private int save(boolean candidate, Runnable beforeWrite) {
    var transaction = new TransactionTemplate(transactions);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    try {
      return transaction.execute(
          status -> {
            EntityManager entityManager =
                EntityManagerFactoryUtils.getTransactionalEntityManager(factory.getObject());
            assertThat(entityManager).isNotNull();
            var users = mock(UserService.class);
            when(users.isCurrentUserAdminOrPm()).thenReturn(true);
            var currents =
                new JpaRepositoryFactory(entityManager)
                    .getRepository(TMTextUnitCurrentVariantRepository.class);
            if (candidate) {
              candidate(entityManager, currents, users, beforeWrite)
                  .create(
                      1L,
                      new ContentTranslationCandidate(
                          4L,
                          2L,
                          3L,
                          11L,
                          "title",
                          "A quiet page",
                          5L,
                          MD5,
                          null,
                          "Une page calme"));
            } else {
              new TMTextUnitSaveGuardService(users, entityManager, currents)
                  .save(
                      3L,
                      11L,
                      null,
                      () -> {
                        beforeWrite.run();
                        write(entityManager);
                        return new TextUnitDTO();
                      });
            }
            return 200;
          });
    } catch (ResponseStatusException conflict) {
      return conflict.getStatusCode().value();
    }
  }

  private ContentTranslationCandidateService candidate(
      EntityManager entities,
      TMTextUnitCurrentVariantRepository currents,
      UserService users,
      Runnable beforeWrite) {
    var locales = mock(RepositoryLocaleRepository.class);
    var locale = new Locale();
    locale.setId(11L);
    locale.setBcp47Tag("fr");
    var configured = new RepositoryLocale();
    configured.setLocale(locale);
    configured.setParentLocale(new RepositoryLocale());
    when(locales.findByRepositoryIdAndLocaleId(1L, 11L)).thenReturn(configured);
    var queries = mock(ContentTranslationCandidateQueries.class);
    when(queries.lockCurrentExtraction(1L, 4L, 2L))
        .thenReturn(new ContentTranslationCandidateQueries.ExtractionSnapshot(5L, MD5));
    when(queries.containsUnit(5L, 2L, 3L)).thenReturn(true);
    var tm = mock(TMService.class);
    when(tm.addTMTextUnitCurrentVariant(
            anyLong(), anyLong(), anyString(), anyString(), any(), anyBoolean()))
        .thenAnswer(
            ignored -> {
              beforeWrite.run();
              write(entities);
              var variant = new TMTextUnitVariant();
              variant.setId(100L);
              variant.setStatus(TMTextUnitVariant.Status.REVIEW_NEEDED);
              var current = new TMTextUnitCurrentVariant();
              current.setTmTextUnitVariant(variant);
              return current;
            });
    return new ContentTranslationCandidateService(
        users,
        locales,
        entities,
        currents,
        queries,
        mock(TMTextUnitIntegrityCheckService.class),
        tm);
  }

  private static void write(EntityManager entities) {
    entities
        .createNativeQuery(
            "insert into tm_text_unit_variant (id, tm_text_unit_id, locale_id, content, status, included_in_localized_file) values (100, 3, 11, 'Une page calme', 'REVIEW_NEEDED', true)")
        .executeUpdate();
    int updated =
        entities
            .createNativeQuery(
                "update tm_text_unit_current_variant set tm_text_unit_variant_id=100 where tm_text_unit_id=3 and locale_id=11")
            .executeUpdate();
    if (updated == 0)
      entities
          .createNativeQuery(
              "insert into tm_text_unit_current_variant (id, tm_id, asset_id, tm_text_unit_id, locale_id, tm_text_unit_variant_id) values (50, 1, 2, 3, 11, 100)")
          .executeUpdate();
  }

  private static void await(CountDownLatch latch) {
    try {
      assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(interrupted);
    }
  }
}
