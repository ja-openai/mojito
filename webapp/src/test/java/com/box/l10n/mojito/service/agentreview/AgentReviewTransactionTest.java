package com.box.l10n.mojito.service.agentreview;

import static com.box.l10n.mojito.service.agentreview.AgentReviewContracts.*;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.box.l10n.mojito.entity.Asset;
import com.box.l10n.mojito.entity.Locale;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.TMTextUnit;
import com.box.l10n.mojito.entity.TMTextUnitVariant;
import com.box.l10n.mojito.entity.agentreview.*;
import com.box.l10n.mojito.entity.security.user.User;
import com.box.l10n.mojito.service.blobstorage.Retention;
import com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage;
import com.box.l10n.mojito.service.repository.RepositoryLocaleRepository;
import com.box.l10n.mojito.service.repository.RepositoryRepository;
import com.box.l10n.mojito.service.security.user.UserService;
import com.box.l10n.mojito.service.team.TeamRepository;
import com.box.l10n.mojito.service.team.TeamService;
import com.box.l10n.mojito.service.tm.TMTextUnitRepository;
import com.box.l10n.mojito.service.tm.TMTextUnitVariantRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.aspectj.AnnotationTransactionAspect;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Real Spring transactions, JPA locks/versioning, and the production migration on isolated HSQL.
 */
@RunWith(SpringRunner.class)
@ContextConfiguration(classes = AgentReviewTransactionTest.Config.class)
public class AgentReviewTransactionTest {
  @Autowired private AgentReviewService service;
  @Autowired private AgentReviewRunRepository runs;
  @Autowired private AgentReviewProposalRepository proposals;
  @Autowired private AgentReviewFeedbackRepository feedback;
  @Autowired private TMTextUnitRepository textUnits;
  @Autowired private TMTextUnitVariantRepository variants;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private ObjectMapper mapper;
  @Autowired private DataSource dataSource;
  private TransactionTemplate transaction;
  private long runId;
  private Claim claim;
  private final Clock clock = Clock.fixed(Instant.parse("2026-09-10T00:00:00Z"), ZoneOffset.UTC);

  @Before
  public void setup() throws Exception {
    AnnotationTransactionAspect.aspectOf().setTransactionManager(transactionManager);
    transaction = new TransactionTemplate(transactionManager);
    ReflectionTestUtils.setField(service, "clock", clock);
    Repository repository = new Repository();
    repository.setId(2L);
    Asset asset = new Asset();
    asset.setRepository(repository);
    TMTextUnit unit = new TMTextUnit();
    unit.setId(6L);
    unit.setAsset(asset);
    unit.setContent("Save");
    when(textUnits.findById(6L)).thenReturn(Optional.of(unit));
    Locale locale = new Locale();
    locale.setId(3L);
    TMTextUnitVariant variant = new TMTextUnitVariant();
    variant.setId(7L);
    variant.setTmTextUnit(unit);
    variant.setLocale(locale);
    variant.setContent("Ancien");
    variant.setStatus(TMTextUnitVariant.Status.APPROVED);
    variant.setIncludedInLocalizedFile(true);
    when(variants.findById(7L)).thenReturn(Optional.of(variant));
    // Scope setup is independent of legacy TM identity creation; the core tables/transactions are
    // real.
    runId =
        transaction.execute(
            status -> {
              AgentReviewRun run = new AgentReviewRun();
              run.setRequestKey(UUID.randomUUID().toString());
              run.setRequestFingerprint("a".repeat(64));
              run.setInputFingerprint("b".repeat(64));
              run.setRequestedByUserId(4L);
              run.setReviewType("TRANSLATION_QUALITY");
              run.setTeamId(5L);
              run.setRepositoryIdsJson("[2]");
              run.setLocaleIdsJson("[3]");
              run.setMethodVersion("v1");
              run.setConfigurationVersion("v1");
              run.setManifestSha256("pending");
              run.setStatus(RunStatus.RUNNING);
              run.setPlannedGroupCount(1);
              run.setDueDateOffsetDays(7);
              run.setMaxWordCountPerProject(1500);
              run.setAssignTranslator(true);
              return runs.saveAndFlush(run).getId();
            });
    RunView claimed = service.claimRun(runId, new ClaimRequest("worker", 0L, 300));
    claim = new Claim(claimed.claimOwner(), claimed.claimGeneration());
    String scope =
        mapper.writeValueAsString(
            new RunManifest(
                List.of(new Group("fr/settings", 2L, 3L, "settings", List.of(6L), "snapshot-v1")),
                "{}"));
    String hash = upload(scope);
    transaction.executeWithoutResult(
        status -> runs.findForUpdateById(runId).orElseThrow().setManifestSha256(hash));
  }

  @Test
  public void competingCoordinatorClaimsHaveExactlyOneWinnerUnderDatabaseLock() throws Exception {
    transaction.executeWithoutResult(
        status -> {
          AgentReviewRun run = runs.findForUpdateById(runId).orElseThrow();
          run.setLeaseExpiresAt(null);
          run.setClaimOwner(null);
          run.setClaimGeneration(0);
        });
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    try (var workers = Executors.newFixedThreadPool(2)) {
      var a = workers.submit(() -> claimAfterStart("a", ready, start));
      var b = workers.submit(() -> claimAfterStart("b", ready, start));
      assertTrue(ready.await(5, TimeUnit.SECONDS));
      start.countDown();
      assertEquals(1, a.get(10, TimeUnit.SECONDS) + b.get(10, TimeUnit.SECONDS));
      assertEquals(1, runs.findById(runId).orElseThrow().getClaimGeneration());
    }
  }

  @Test
  public void expiredOwnerCannotCommitAfterAnotherMachineTakesOver() {
    AgentReviewProposal saved =
        service.submitProposal(runId, proposal("one", claim, null, null, Category.OBVIOUS_ERROR));
    ReflectionTestUtils.setField(service, "clock", Clock.offset(clock, Duration.ofMinutes(6)));
    RunView takeover = service.claimRun(runId, new ClaimRequest("devbox", 1L, 300));
    assertEquals(2, takeover.claimGeneration());
    assertEquals(
        409,
        assertThrows(
                ResponseStatusException.class,
                () ->
                    service.submitProposal(
                        runId, proposal("two", claim, null, null, Category.OBVIOUS_ERROR)))
            .getStatusCode()
            .value());
    Claim next = new Claim("devbox", 2);
    assertEquals(
        saved.getId(),
        service
            .submitProposal(runId, proposal("one", next, null, null, Category.OBVIOUS_ERROR))
            .getId());
    assertEquals(1, proposals.findByRunIdOrderByIdAsc(runId).size());
  }

  @Test
  public void independentBulkTransactionsRetainOtherItemsAfterFailure() {
    List<SubmissionResult> results =
        service.submitProposals(
            runId,
            List.of(
                proposal("one", claim, null, null, Category.OBVIOUS_ERROR),
                proposal("invalid", claim, null, null, Category.OPTIONAL_IMPROVEMENT),
                proposal("three", claim, null, null, Category.OBVIOUS_ERROR)));
    assertNotNull(results.get(0).proposalId());
    assertNotNull(results.get(1).errorCode());
    assertNotNull(results.get(2).proposalId());
    assertEquals(2, proposals.findByRunIdOrderByIdAsc(runId).size());
  }

  @Test
  public void guardedSaveAndFeedbackRollBackTogetherAndRetryCanCommit() {
    AgentReviewProposal proposal =
        service.submitProposal(runId, proposal("one", claim, null, null, Category.OBVIOUS_ERROR));
    long version = proposal.getVersion();
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    long savedRowId = proposal.getId();
    HumanFeedbackRequest request =
        new HumanFeedbackRequest(
            "accepted",
            proposal.getId(),
            version,
            FeedbackAction.ACCEPT,
            OriginalAssessment.BAD,
            SuggestionAssessment.GOOD,
            null,
            false,
            "Enregistrer",
            8L,
            "a".repeat(64));
    assertThrows(
        IllegalStateException.class,
        () ->
            transaction.executeWithoutResult(
                status -> {
                  jdbc.update("insert into guarded_save_marker (id) values (?)", savedRowId);
                  service.appendHumanFeedback(request);
                  throw new IllegalStateException(
                      "Simulated failure after the guarded translation save");
                }));
    assertEquals(
        0,
        jdbc.queryForObject(
                "select count(*) from guarded_save_marker where id=?", Integer.class, savedRowId)
            .intValue());
    assertTrue(feedback.findByProposalIdOrderByIdAsc(proposal.getId()).isEmpty());
    AgentReviewProposal rolledBack = proposals.findById(proposal.getId()).orElseThrow();
    assertEquals(Disposition.OPEN, rolledBack.getDisposition());
    assertEquals(version, rolledBack.getVersion());
    transaction.executeWithoutResult(
        status -> {
          jdbc.update("insert into guarded_save_marker (id) values (?)", savedRowId);
          service.appendHumanFeedback(request);
        });
    assertEquals(1, feedback.findByProposalIdOrderByIdAsc(proposal.getId()).size());
    assertEquals(
        Disposition.RESOLVED, proposals.findById(proposal.getId()).orElseThrow().getDisposition());
    assertTrue(proposals.findById(proposal.getId()).orElseThrow().getVersion() > version);
  }

  @Test
  public void pendingFeedbackQueryOnlyReturnsLatestUnansweredHumanRound() {
    AgentReviewProposal proposal =
        service.submitProposal(runId, proposal("one", claim, null, null, Category.OBVIOUS_ERROR));
    AgentReviewFeedback first =
        transaction.execute(status -> service.appendHumanFeedback(human(proposal, "first")));
    AgentReviewProposal updated = proposals.findById(proposal.getId()).orElseThrow();
    AgentReviewFeedback second =
        transaction.execute(status -> service.appendHumanFeedback(human(updated, "second")));
    assertEquals(
        List.of(second.getId()),
        service.pendingFeedback(runId, 0, 100).stream().map(AgentReviewFeedback::getId).toList());
    assertEquals(
        409,
        assertThrows(
                ResponseStatusException.class,
                () ->
                    service.respondToFeedback(
                        runId,
                        new ResponseRequest(
                            claim,
                            "old",
                            first.getId(),
                            FeedbackAction.CHALLENGE,
                            "verifier",
                            "Old reply",
                            "{}")))
            .getStatusCode()
            .value());
    service.respondToFeedback(
        runId,
        new ResponseRequest(
            claim,
            "reply",
            second.getId(),
            FeedbackAction.CONTEXT_REQUEST,
            "verifier",
            "Need screenshot",
            "{}"));
    assertTrue(service.pendingFeedback(runId, 0, 100).isEmpty());
    assertEquals(3, service.feedbackHistory(proposal.getId(), 0, 100).size());
  }

  @Test
  public void everyNewFeedbackRoundAndTextualReplyChangesTheProposalVersion() {
    AgentReviewProposal initial =
        service.submitProposal(runId, proposal("one", claim, null, null, Category.OBVIOUS_ERROR));
    HumanFeedbackRequest firstDefer =
        new HumanFeedbackRequest(
            "defer-one",
            initial.getId(),
            initial.getVersion(),
            FeedbackAction.DEFER,
            null,
            null,
            null,
            false,
            null,
            null,
            "a".repeat(64));
    transaction.executeWithoutResult(status -> service.appendHumanFeedback(firstDefer));
    AgentReviewProposal first = proposals.findById(initial.getId()).orElseThrow();
    assertTrue(first.getVersion() > initial.getVersion());
    HumanFeedbackRequest secondDefer =
        new HumanFeedbackRequest(
            "defer-two",
            initial.getId(),
            first.getVersion(),
            FeedbackAction.DEFER,
            null,
            null,
            "Waiting for screenshot",
            false,
            null,
            null,
            "a".repeat(64));
    transaction.executeWithoutResult(status -> service.appendHumanFeedback(secondDefer));
    AgentReviewProposal second = proposals.findById(initial.getId()).orElseThrow();
    assertEquals(Disposition.ROUTED, second.getDisposition());
    assertTrue(second.getVersion() > first.getVersion());
    // An exact replay has no new judgment and must not bump the version again.
    transaction.executeWithoutResult(status -> service.appendHumanFeedback(secondDefer));
    assertEquals(
        second.getVersion(), proposals.findById(initial.getId()).orElseThrow().getVersion());
    AgentReviewFeedback requested =
        transaction.execute(status -> service.appendHumanFeedback(human(second, "revise")));
    AgentReviewProposal beforeReply = proposals.findById(initial.getId()).orElseThrow();
    service.respondToFeedback(
        runId,
        new ResponseRequest(
            claim,
            "challenge",
            requested.getId(),
            FeedbackAction.CHALLENGE,
            "verifier",
            "Please reconsider the screenshot evidence",
            "{}"));
    AgentReviewProposal afterReply = proposals.findById(initial.getId()).orElseThrow();
    assertEquals(Disposition.FOLLOW_UP, afterReply.getDisposition());
    assertTrue(afterReply.getVersion() > beforeReply.getVersion());
    assertEquals(
        409,
        assertThrows(
                ResponseStatusException.class,
                () ->
                    transaction.executeWithoutResult(
                        status ->
                            service.appendHumanFeedback(human(beforeReply, "stale-decision"))))
            .getStatusCode()
            .value());
  }

  @Test
  public void preparedInputsSurviveResumeWithoutCountingAsCompletedOrFailed() {
    String inputs = upload("{\"source\":\"Save\",\"target\":\"Ancien\"}");
    service.checkpoint(
        runId,
        new CheckpointRequest(
            claim, 0, "fr/settings", GroupStatus.IN_PROGRESS, 0, inputs, "Prepared inputs"));
    RunView persisted = service.getRun(runId);
    assertEquals(0, persisted.completedGroupCount());
    assertEquals(0, persisted.failedGroupCount());
    assertEquals(inputs, persisted.checkpoint().groups().get("fr/settings").artifactSha256());
    assertEquals(
        400,
        assertThrows(
                ResponseStatusException.class,
                () -> service.finishRun(runId, new FinishRequest(claim, 1, false)))
            .getStatusCode()
            .value());
    service.checkpoint(
        runId,
        new CheckpointRequest(
            claim, 1, "fr/settings", GroupStatus.COMPLETED, 1, inputs, "No findings"));
    assertEquals(
        RunStatus.COMPLETED, service.finishRun(runId, new FinishRequest(claim, 2, false)).status());
    assertTrue(proposals.findByRunIdOrderByIdAsc(runId).isEmpty());
  }

  private int claimAfterStart(String owner, CountDownLatch ready, CountDownLatch start)
      throws Exception {
    ready.countDown();
    if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("Start barrier timed out");
    try {
      service.claimRun(runId, new ClaimRequest(owner, 0L, 300));
      return 1;
    } catch (ResponseStatusException e) {
      assertEquals(409, e.getStatusCode().value());
      return 0;
    }
  }

  private HumanFeedbackRequest human(AgentReviewProposal proposal, String key) {
    return new HumanFeedbackRequest(
        key,
        proposal.getId(),
        proposal.getVersion(),
        FeedbackAction.REQUEST_REVISION,
        OriginalAssessment.BAD,
        SuggestionAssessment.INCORRECT,
        "Fix still changes meaning",
        true,
        null,
        null,
        "a".repeat(64));
  }

  private SubmitProposalRequest proposal(
      String key, Claim claim, Long previous, Long respondsTo, Category category) {
    return new SubmitProposalRequest(
        claim,
        key,
        "fr/settings",
        6L,
        "Save",
        null,
        7L,
        "Ancien",
        "APPROVED",
        true,
        "Enregistrer",
        category,
        Readiness.READY,
        "Wrong meaning",
        "{}",
        "worker-fr",
        "verifier-fr",
        "Checked context",
        null,
        previous,
        respondsTo);
  }

  private String upload(String value) {
    return service
        .uploadArtifact(
            runId,
            new ArtifactRequest(
                claim,
                "application/json",
                Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8))))
        .sha256();
  }

  @Configuration
  @EnableTransactionManagement
  @EnableJpaRepositories(basePackageClasses = AgentReviewRunRepository.class)
  static class Config {
    @Bean
    DataSource dataSource() {
      DriverManagerDataSource source =
          new DriverManagerDataSource(
              "jdbc:hsqldb:mem:agent-review-tx-" + UUID.randomUUID() + ";sql.syntax_mys=true",
              "sa",
              "");
      DatabasePopulatorUtils.execute(
          new ResourceDatabasePopulator(
              new ClassPathResource("db/migration/V111__Agent_Review.sql")),
          source);
      new JdbcTemplate(source).execute("create table guarded_save_marker (id bigint primary key)");
      return source;
    }

    @Bean
    LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
      LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
      factory.setDataSource(dataSource);
      factory.setPackagesToScan("com.box.l10n.mojito.entity.agentreview");
      factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
      Properties properties = new Properties();
      properties.put("hibernate.hbm2ddl.auto", "none");
      properties.put("hibernate.jdbc.time_zone", "UTC");
      factory.setJpaProperties(properties);
      return factory;
    }

    @Bean
    PlatformTransactionManager transactionManager(EntityManagerFactory factory) {
      return new JpaTransactionManager(factory);
    }

    @Bean
    EntityManager entityManager(EntityManagerFactory factory) {
      return SharedEntityManagerCreator.createSharedEntityManager(factory);
    }

    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper().findAndRegisterModules();
    }

    @Bean
    TMTextUnitRepository textUnits() {
      return mock(TMTextUnitRepository.class);
    }

    @Bean
    TMTextUnitVariantRepository variants() {
      return mock(TMTextUnitVariantRepository.class);
    }

    @Bean
    RepositoryRepository repositories() {
      return mock(RepositoryRepository.class);
    }

    @Bean
    RepositoryLocaleRepository repositoryLocales() {
      return mock(RepositoryLocaleRepository.class);
    }

    @Bean
    TeamRepository teams() {
      return mock(TeamRepository.class);
    }

    @Bean
    TeamService teamService() {
      TeamService service = mock(TeamService.class);
      when(service.getCurrentUserIdOrThrow()).thenReturn(4L);
      return service;
    }

    private UserService userService() {
      UserService service = mock(UserService.class);
      when(service.isCurrentUserAdminOrPm()).thenReturn(true);
      when(service.isCurrentUserTranslationRole()).thenReturn(true);
      User user = new User();
      user.setId(4L);
      user.setUsername("reviewer");
      when(service.getCurrentUser()).thenReturn(Optional.of(user));
      return service;
    }

    @Bean
    StructuredBlobStorage blobs() {
      Map<String, String> content = new ConcurrentHashMap<>();
      StructuredBlobStorage storage = mock(StructuredBlobStorage.class);
      doAnswer(
              i -> {
                content.put(i.getArgument(1), i.getArgument(2));
                return null;
              })
          .when(storage)
          .put(
              eq(StructuredBlobStorage.Prefix.AGENT_REVIEW),
              anyString(),
              anyString(),
              eq(Retention.PERMANENT));
      when(storage.getString(eq(StructuredBlobStorage.Prefix.AGENT_REVIEW), anyString()))
          .thenAnswer(i -> Optional.ofNullable(content.get(i.getArgument(1))));
      return storage;
    }

    @Bean
    AgentReviewService service(
        AgentReviewRunRepository runs,
        AgentReviewProposalRepository proposals,
        AgentReviewFeedbackRepository feedback,
        RepositoryRepository repositories,
        RepositoryLocaleRepository repositoryLocales,
        TMTextUnitRepository textUnits,
        TMTextUnitVariantRepository variants,
        TeamRepository teams,
        TeamService teamService,
        StructuredBlobStorage blobs,
        ObjectMapper mapper,
        EntityManager entityManager,
        PlatformTransactionManager tx) {
      return new AgentReviewService(
          runs,
          proposals,
          feedback,
          repositories,
          repositoryLocales,
          textUnits,
          variants,
          teams,
          teamService,
          userService(),
          blobs,
          mapper,
          entityManager,
          tx);
    }
  }
}
