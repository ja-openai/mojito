package com.box.l10n.mojito.service.tm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import com.box.l10n.mojito.entity.Asset;
import com.box.l10n.mojito.entity.Locale;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.RepositoryLocale;
import com.box.l10n.mojito.okapi.InheritanceMode;
import com.box.l10n.mojito.okapi.Status;
import com.box.l10n.mojito.rest.asset.AssetWithIdNotFoundException;
import com.box.l10n.mojito.rest.asset.LocalizedAssetBody;
import com.box.l10n.mojito.service.asset.AssetRepository;
import com.box.l10n.mojito.service.repository.RepositoryLocaleRepository;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.slf4j.LoggerFactory;

@RunWith(MockitoJUnitRunner.class)
public class LocalizedAssetGenerationServiceTest {

  @Mock AssetRepository assetRepository;
  @Mock RepositoryLocaleRepository repositoryLocaleRepository;
  @Mock TMService tmService;
  @Mock Asset asset;
  @Mock Repository repository;
  @Mock RepositoryLocale repositoryLocale;
  @Mock Locale locale;

  LocalizedAssetGenerationService localizedAssetGenerationService;
  SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

  @Before
  public void setUp() {
    localizedAssetGenerationService =
        new LocalizedAssetGenerationService(
            assetRepository, repositoryLocaleRepository, tmService, meterRegistry);
  }

  @After
  public void tearDown() {
    meterRegistry.close();
  }

  @Test
  public void generateUsesSharedTmPathAndRepositoryLocaleTag() throws Exception {
    assertSharedTmPathAndRepositoryLocaleTag();
    assertThat(
            meterRegistry
                .get("GenerateLocalizedAssetJob.call")
                .tag("repositoryName", "repo")
                .tag("bcp47Tag", "fr-FR")
                .timer()
                .count())
        .isEqualTo(1);
  }

  @Test
  public void generationTimerCollisionDoesNotDiscardSuccessfulBusinessWork() throws Exception {
    meterRegistry.gauge(
        "GenerateLocalizedAssetJob.call",
        Tags.of("repositoryName", "repo", "bcp47Tag", "fr-FR"),
        1);

    assertSharedTmPathAndRepositoryLocaleTag();
  }

  @Test
  public void nonFatalGenerationTimerErrorDoesNotDiscardBusinessWork() throws Exception {
    meterRegistry
        .config()
        .onMeterAdded(
            meter -> {
              if (meter.getId().getName().equals("GenerateLocalizedAssetJob.call")) {
                throw new AssertionError("metric provider unavailable");
              }
            });

    assertSharedTmPathAndRepositoryLocaleTag();
  }

  @Test
  public void fatalGenerationTimerErrorStillPropagates() {
    OutOfMemoryError fatal = new OutOfMemoryError("synthetic timer failure");
    meterRegistry
        .config()
        .onMeterAdded(
            meter -> {
              if (meter.getId().getName().equals("GenerateLocalizedAssetJob.call")) {
                throw fatal;
              }
            });

    assertThatThrownBy(this::assertSharedTmPathAndRepositoryLocaleTag).isSameAs(fatal);
  }

  @Test
  public void timerCollisionPreservesOriginalGenerationFailure() throws Exception {
    RuntimeException failure = new IllegalStateException("generation failed");
    LocalizedAssetBody input = stubSharedTmPath(failure);
    meterRegistry.gauge(
        "GenerateLocalizedAssetJob.call",
        Tags.of("repositoryName", "repo", "bcp47Tag", "fr-FR"),
        1);

    assertThatThrownBy(() -> localizedAssetGenerationService.generate(input)).isSameAs(failure);
    assertThat(input.getContent()).isEqualTo("source");
  }

  @Test
  public void timerAndLoggerFailuresPreserveSuccessfulGeneration() throws Throwable {
    conflictWithGenerationTimer();

    assertThat(
            withFailedDiagnosticLogging(
                new IllegalStateException("logger failed"),
                this::assertSharedTmPathAndRepositoryLocaleTag))
        .isEqualTo(1);
  }

  @Test
  public void timerAndLoggerFailuresPreserveOriginalGenerationFailure() throws Throwable {
    RuntimeException original = new IllegalStateException("generation failed");
    LocalizedAssetBody input = stubSharedTmPath(original);
    conflictWithGenerationTimer();

    assertThat(
            withFailedDiagnosticLogging(
                new AssertionError("logger failed"),
                () ->
                    assertThatThrownBy(() -> localizedAssetGenerationService.generate(input))
                        .isSameAs(original)))
        .isEqualTo(1);
    assertThat(input.getContent()).isEqualTo("source");
    assertThat(input.getBcp47Tag()).isNull();
  }

  @Test(timeout = 5_000)
  public void cyclicNonFatalLoggerFailureDoesNotDiscardSuccessfulGeneration() throws Throwable {
    conflictWithGenerationTimer();
    IllegalStateException failure = new IllegalStateException("logger failed");
    IllegalStateException cycle = new IllegalStateException("wrapper", failure);
    failure.initCause(cycle);

    assertThat(withFailedDiagnosticLogging(failure, this::assertSharedTmPathAndRepositoryLocaleTag))
        .isEqualTo(1);
  }

  @Test
  public void nestedFatalGenerationTimerErrorStillPropagates() {
    OutOfMemoryError fatal = new OutOfMemoryError("synthetic timer failure");
    meterRegistry
        .config()
        .onMeterAdded(
            meter -> {
              throw new IllegalStateException("metric wrapper", fatal);
            });

    assertThatThrownBy(this::assertSharedTmPathAndRepositoryLocaleTag).isSameAs(fatal);
  }

  @Test
  public void suppressedFatalGenerationLoggerErrorStillPropagates() throws Throwable {
    conflictWithGenerationTimer();
    OutOfMemoryError fatal = new OutOfMemoryError("synthetic logger failure");
    IllegalStateException failure = new IllegalStateException("logger wrapper");
    failure.addSuppressed(fatal);

    assertThat(
            withFailedDiagnosticLogging(
                failure,
                () ->
                    assertThatThrownBy(this::assertSharedTmPathAndRepositoryLocaleTag)
                        .isSameAs(fatal)))
        .isEqualTo(1);
  }

  @Test
  @SuppressWarnings("removal")
  public void threadDeathSubclassInGenerationTimerStillPropagates() {
    ThreadDeath fatal = new ThreadDeath() {};
    meterRegistry
        .config()
        .onMeterAdded(
            meter -> {
              throw fatal;
            });

    assertThatThrownBy(this::assertSharedTmPathAndRepositoryLocaleTag).isSameAs(fatal);
  }

  private void conflictWithGenerationTimer() {
    meterRegistry.gauge(
        "GenerateLocalizedAssetJob.call",
        Tags.of("repositoryName", "repo", "bcp47Tag", "fr-FR"),
        1);
  }

  private int withFailedDiagnosticLogging(Throwable failure, ThrowingCallable action)
      throws Throwable {
    Logger logger = (Logger) LoggerFactory.getLogger(LocalizedAssetGenerationService.class);
    Level previousLevel = logger.getLevel();
    Thread caller = Thread.currentThread();
    AtomicInteger warnings = new AtomicInteger();
    @SuppressWarnings("unchecked")
    Appender<ILoggingEvent> appender = mock(Appender.class);
    doAnswer(
            invocation -> {
              ILoggingEvent event = invocation.getArgument(0);
              if (Thread.currentThread() == caller
                  && event.getLevel() == Level.WARN
                  && event
                      .getMessage()
                      .equals("Failed to record localized asset generation metric")) {
                warnings.incrementAndGet();
                throw failure;
              }
              return null;
            })
        .when(appender)
        .doAppend(any(ILoggingEvent.class));
    try {
      logger.setLevel(Level.WARN);
      logger.addAppender(appender);
      action.call();
      return warnings.get();
    } finally {
      logger.detachAppender(appender);
      logger.setLevel(previousLevel);
    }
  }

  private LocalizedAssetBody stubSharedTmPath(RuntimeException failure) throws Exception {
    LocalizedAssetBody input = localizedAssetBody(null);
    when(assetRepository.findById(10L)).thenReturn(Optional.of(asset));
    when(asset.getRepository()).thenReturn(repository);
    when(repository.getId()).thenReturn(20L);
    when(repository.getName()).thenReturn("repo");
    when(repositoryLocaleRepository.findByRepositoryIdAndLocaleId(20L, 30L))
        .thenReturn(repositoryLocale);
    when(repositoryLocale.getLocale()).thenReturn(locale);
    when(locale.getBcp47Tag()).thenReturn("fr-FR");
    when(tmService.generateLocalized(
            asset,
            "source",
            repositoryLocale,
            null,
            null,
            List.of("opt"),
            Status.ACCEPTED,
            InheritanceMode.REMOVE_UNTRANSLATED,
            "pull-run",
            false,
            List.of()))
        .thenAnswer(
            invocation -> {
              if (failure != null) {
                throw failure;
              }
              return "localized";
            });
    return input;
  }

  private void assertSharedTmPathAndRepositoryLocaleTag() throws Exception {
    LocalizedAssetBody input = stubSharedTmPath(null);
    LocalizedAssetBody output = localizedAssetGenerationService.generate(input);

    assertThat(output).isSameAs(input);
    assertThat(output.getContent()).isEqualTo("localized");
    assertThat(output.getBcp47Tag()).isEqualTo("fr-FR");
    verify(tmService)
        .generateLocalized(
            asset,
            "source",
            repositoryLocale,
            null,
            null,
            List.of("opt"),
            Status.ACCEPTED,
            InheritanceMode.REMOVE_UNTRANSLATED,
            "pull-run",
            false,
            List.of());
  }

  @Test
  public void generatePreservesExplicitOutputTagAndSourceLessPullOptions() throws Exception {
    LocalizedAssetBody input = localizedAssetBody("fr");
    input.setPullWithNoSource(true);
    input.setPullWithNoSourceBranches(Arrays.asList(null, "feature"));
    when(assetRepository.findById(10L)).thenReturn(Optional.of(asset));
    when(asset.getRepository()).thenReturn(repository);
    when(repository.getId()).thenReturn(20L);
    when(repository.getName()).thenReturn("repo");
    when(repositoryLocaleRepository.findByRepositoryIdAndLocaleId(20L, 30L))
        .thenReturn(repositoryLocale);
    when(repositoryLocale.getLocale()).thenReturn(locale);
    when(locale.getBcp47Tag()).thenReturn("fr-FR");
    when(tmService.generateLocalized(
            asset,
            "source",
            repositoryLocale,
            "fr",
            null,
            List.of("opt"),
            Status.ACCEPTED,
            InheritanceMode.REMOVE_UNTRANSLATED,
            "pull-run",
            true,
            Arrays.asList(null, "feature")))
        .thenReturn("localized");

    LocalizedAssetBody output = localizedAssetGenerationService.generate(input);

    assertThat(output.getContent()).isEqualTo("localized");
    assertThat(output.getBcp47Tag()).isEqualTo("fr");
  }

  @Test
  public void generateRejectsMissingAsset() {
    LocalizedAssetBody input = localizedAssetBody(null);
    when(assetRepository.findById(10L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> localizedAssetGenerationService.generate(input))
        .isInstanceOf(AssetWithIdNotFoundException.class);
  }

  @Test
  public void missingRepositoryLocaleStillFailsBeforeBusinessWorkWithAnExplicitOutputTag() {
    assertMissingLocaleFailsBeforeBusinessWork(false);
  }

  @Test
  public void missingLocaleStillFailsBeforeBusinessWorkWithAnExplicitOutputTag() {
    assertMissingLocaleFailsBeforeBusinessWork(true);
  }

  private void assertMissingLocaleFailsBeforeBusinessWork(boolean hasRepositoryLocale) {
    when(assetRepository.findById(10L)).thenReturn(Optional.of(asset));
    when(asset.getRepository()).thenReturn(repository);
    when(repository.getId()).thenReturn(20L);
    when(repository.getName()).thenReturn("repo");
    when(repositoryLocaleRepository.findByRepositoryIdAndLocaleId(20L, 30L))
        .thenReturn(hasRepositoryLocale ? repositoryLocale : null);

    assertThatThrownBy(() -> localizedAssetGenerationService.generate(localizedAssetBody("fr")))
        .isInstanceOf(NullPointerException.class);
    verifyNoInteractions(tmService);
  }

  private LocalizedAssetBody localizedAssetBody(String outputBcp47Tag) {
    LocalizedAssetBody localizedAssetBody = new LocalizedAssetBody();
    localizedAssetBody.setAssetId(10L);
    localizedAssetBody.setLocaleId(30L);
    localizedAssetBody.setContent("source");
    localizedAssetBody.setOutputBcp47tag(outputBcp47Tag);
    localizedAssetBody.setFilterOptions(List.of("opt"));
    localizedAssetBody.setStatus(Status.ACCEPTED);
    localizedAssetBody.setInheritanceMode(InheritanceMode.REMOVE_UNTRANSLATED);
    localizedAssetBody.setPullRunName("pull-run");
    return localizedAssetBody;
  }
}
