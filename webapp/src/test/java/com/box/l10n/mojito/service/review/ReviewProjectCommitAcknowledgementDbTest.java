package com.box.l10n.mojito.service.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.box.l10n.mojito.entity.Asset;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.TMTextUnitVariant;
import com.box.l10n.mojito.entity.review.ReviewProject;
import com.box.l10n.mojito.entity.review.ReviewProjectTextUnit;
import com.box.l10n.mojito.entity.review.ReviewProjectTextUnitDecision.DecisionState;
import com.box.l10n.mojito.rest.review.ReviewProjectTextUnitDecisionRequest;
import com.box.l10n.mojito.rest.review.ReviewProjectWS;
import com.box.l10n.mojito.service.asset.AssetService;
import com.box.l10n.mojito.service.assetExtraction.ServiceTestBase;
import com.box.l10n.mojito.service.locale.LocaleService;
import com.box.l10n.mojito.service.repository.RepositoryService;
import com.box.l10n.mojito.service.repository.statistics.RepositoryStatisticsUpdatedReactor;
import com.box.l10n.mojito.service.tm.TMService;
import com.box.l10n.mojito.service.tm.TMTextUnitCurrentVariantRepository;
import com.box.l10n.mojito.test.TestIdWatcher;
import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.time.ZonedDateTime;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.hibernate.Session;
import org.junit.Rule;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public class ReviewProjectCommitAcknowledgementDbTest extends ServiceTestBase {
  @Rule public TestIdWatcher testIdWatcher = new TestIdWatcher();

  @Autowired private RepositoryService repositoryService;
  @Autowired private AssetService assetService;
  @Autowired private LocaleService localeService;
  @Autowired private TMService tmService;
  @Autowired private ReviewProjectRepository projectRepository;
  @Autowired private ReviewProjectTextUnitRepository rowRepository;
  @Autowired private ReviewProjectTextUnitDecisionRepository decisionRepository;
  @Autowired private TMTextUnitCurrentVariantRepository currentRepository;
  @Autowired private ReviewProjectService reviewProjectService;
  @Autowired private ReviewProjectWS reviewProjectWS;
  @Autowired private EntityManager entityManager;
  @Autowired private PlatformTransactionManager transactionManager;
  @MockitoSpyBean private RepositoryStatisticsUpdatedReactor statisticsReactor;
  @MockitoSpyBean private JdbcTemplate jdbcTemplate;

  @Test
  public void firstDecisionAcknowledgesCommitWithoutPreloadingTheAssetRepository()
      throws Exception {
    assertCommitAcknowledged(fixture(true));
  }

  @Test
  public void firstTranslationAcknowledgesCommitWithoutPreloadingTheAssetRepository()
      throws Exception {
    assertCommitAcknowledged(fixture(false));
  }

  @Test
  public void statisticsNotificationFailureDoesNotTurnACommittedSaveIntoAnHttpFailure()
      throws Exception {
    Fixture fixture = fixture(true);
    doThrow(new IllegalStateException("Synthetic statistics notification failure"))
        .when(statisticsReactor)
        .generateEvent(fixture.repositoryId());
    assertCommitAcknowledged(fixture);
  }

  @Test
  public void postCommitLookupReusesTheCommittingJdbcConnection() throws Exception {
    Fixture fixture = fixture(true);
    var request = decisionRequest(fixture);
    var committingConnection = new AtomicReference<Connection>();
    var observedLookups = new AtomicInteger();
    doAnswer(
            invocation -> {
              Connection observed =
                  jdbcTemplate.execute(
                      (ConnectionCallback<Connection>) DataSourceUtils::getTargetConnection);
              assertThat(observed)
                  .as("The post-commit lookup must reuse the transaction's connection")
                  .isSameAs(committingConnection.get());
              observedLookups.incrementAndGet();
              return invocation.callRealMethod();
            })
        .when(jdbcTemplate)
        .query(
            eq(
                "select a.repository_id from tm_text_unit tu join asset a on a.id = tu.asset_id where tu.id = ?"),
            org.mockito.ArgumentMatchers.<ResultSetExtractor<Long>>any(),
            anyLong());

    var response =
        new TransactionTemplate(transactionManager)
            .execute(
                status -> {
                  committingConnection.set(
                      entityManager
                          .unwrap(Session.class)
                          .doReturningWork(DataSourceUtils::getTargetConnection));
                  try {
                    return reviewProjectWS.saveDecision(fixture.rowId(), request);
                  } catch (Exception failure) {
                    throw new RuntimeException(failure);
                  }
                });

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(observedLookups.get()).isPositive();
  }

  private void assertCommitAcknowledged(Fixture fixture) throws Exception {
    var request = decisionRequest(fixture);
    clearInvocations(statisticsReactor);

    // No outer transaction, no warmed entity graph: use the same service transaction boundary
    // as the HTTP controller. The progress update clears its persistence context before commit.
    var response = reviewProjectWS.saveDecision(fixture.rowId(), request);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(
            currentRepository
                .findByLocale_IdAndTmTextUnit_Id(fixture.localeId(), fixture.tmTextUnitId())
                .getTmTextUnitVariant()
                .getContent())
        .isEqualTo(request.getTarget());
    assertThat(
            decisionRepository
                .findByReviewProjectTextUnitId(fixture.rowId())
                .orElseThrow()
                .getDecisionState())
        .isEqualTo(DecisionState.DECIDED);
    verify(statisticsReactor, atLeastOnce()).generateEvent(fixture.repositoryId());
  }

  private ReviewProjectTextUnitDecisionRequest decisionRequest(Fixture fixture) {
    var before =
        reviewProjectService
            .getProjectDetail(fixture.projectId())
            .reviewProjectTextUnits()
            .getFirst();
    var request = new ReviewProjectTextUnitDecisionRequest();
    request.setTarget("Exact successfully committed target");
    request.setStatus("APPROVED");
    request.setIncludedInLocalizedFile(true);
    request.setDecisionState(DecisionState.DECIDED);
    request.setExpectedCurrentTmTextUnitVariantId(before.currentTmTextUnitVariant().id());
    request.setExpectedReviewStateRevision(before.reviewStateRevision());
    return request;
  }

  private Fixture fixture(boolean translated) throws Exception {
    Repository repository =
        repositoryService.createRepository(testIdWatcher.getEntityName("repository"));
    Asset asset = assetService.createAssetWithContent(repository.getId(), "messages.json", "{}");
    var textUnit =
        tmService.addTMTextUnit(
            repository.getTm().getId(), asset.getId(), "greeting", "Hello", null);
    var locale = localeService.findByBcp47Tag("fr-FR");
    TMTextUnitVariant original =
        translated
            ? tmService.addCurrentTMTextUnitVariant(
                textUnit.getId(),
                locale.getId(),
                "Original",
                TMTextUnitVariant.Status.APPROVED,
                true)
            : null;
    var project = new ReviewProject();
    project.setLocale(locale);
    project.setDueDate(ZonedDateTime.now().plusDays(1));
    project = projectRepository.saveAndFlush(project);
    var row = new ReviewProjectTextUnit();
    row.setReviewProject(project);
    row.setTmTextUnit(textUnit);
    row.setTmTextUnitVariant(original);
    row = rowRepository.saveAndFlush(row);
    return new Fixture(
        repository.getId(), project.getId(), row.getId(), textUnit.getId(), locale.getId());
  }

  private record Fixture(
      Long repositoryId, Long projectId, Long rowId, Long tmTextUnitId, Long localeId) {}
}
