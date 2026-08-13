package com.box.l10n.mojito.service.oaitranslate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.entity.TMTextUnitVariant;
import com.box.l10n.mojito.service.assetExtraction.ServiceTestBase;
import com.box.l10n.mojito.service.blobstorage.Retention;
import com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage;
import com.box.l10n.mojito.service.repository.RepositoryNameAlreadyUsedException;
import com.box.l10n.mojito.service.repository.RepositoryService;
import com.box.l10n.mojito.service.tm.TMTestData;
import com.box.l10n.mojito.service.tm.search.StatusFilter;
import com.box.l10n.mojito.test.TestIdWatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

public class AiTranslateServiceTest extends ServiceTestBase {

  static Logger logger = LoggerFactory.getLogger(AiTranslateServiceTest.class);

  @Rule public TestIdWatcher testIdWatcher = new TestIdWatcher();

  @Autowired AiTranslateService aiTranslateService;

  @Autowired AiTranslateConfigurationProperties aiTranslateConfigurationProperties;

  @Autowired RepositoryService repositoryService;

  @Test
  public void noBatchOutputReportsUseCorrectedPrefixAndConfiguredRetention() {
    StructuredBlobStorage originalStructuredBlobStorage = aiTranslateService.structuredBlobStorage;
    StructuredBlobStorage structuredBlobStorage = mock(StructuredBlobStorage.class);
    PollableTask pollableTask = new PollableTask();
    pollableTask.setId(42L);

    try {
      aiTranslateService.structuredBlobStorage = structuredBlobStorage;

      aiTranslateService.putReportContent(pollableTask, List.of("42/locale/fr-FR"));
      aiTranslateService.putReportContentLocale(
          pollableTask, "fr-FR", List.of(), new ArrayList<>());

      verify(structuredBlobStorage)
          .put(
              eq(StructuredBlobStorage.Prefix.AI_TRANSLATE_NO_BATCH_OUTPUT),
              eq("42/report"),
              anyString(),
              eq(Retention.MIN_1_DAY));
      verify(structuredBlobStorage)
          .put(
              eq(StructuredBlobStorage.Prefix.AI_TRANSLATE_NO_BATCH_OUTPUT),
              eq("42/locale/fr-FR"),
              anyString(),
              eq(Retention.MIN_1_DAY));
    } finally {
      aiTranslateService.structuredBlobStorage = originalStructuredBlobStorage;
    }
  }

  @Test
  public void noBatchOutputReportsReadCorrectedPrefixWithoutLegacyLookup() {
    StructuredBlobStorage originalStructuredBlobStorage = aiTranslateService.structuredBlobStorage;
    StructuredBlobStorage structuredBlobStorage = mock(StructuredBlobStorage.class);
    when(structuredBlobStorage.getString(
            StructuredBlobStorage.Prefix.AI_TRANSLATE_NO_BATCH_OUTPUT, "42/report"))
        .thenReturn(Optional.of("{\"reportLocaleUrls\":[\"42/locale/fr-FR\"]}"));
    when(structuredBlobStorage.getString(
            StructuredBlobStorage.Prefix.AI_TRANSLATE_NO_BATCH_OUTPUT, "42/locale/fr-FR"))
        .thenReturn(Optional.of("corrected-locale-report"));

    try {
      aiTranslateService.structuredBlobStorage = structuredBlobStorage;

      assertThat(aiTranslateService.getReportContent(42L).reportLocaleUrls())
          .containsExactly("42/locale/fr-FR");
      assertThat(aiTranslateService.getReportContentLocale(42L, "fr-FR"))
          .isEqualTo("corrected-locale-report");
      verify(structuredBlobStorage, never())
          .getString(eq(StructuredBlobStorage.Prefix.AI_TRANSALATE_NO_BATCH_OUTPUT), anyString());
    } finally {
      aiTranslateService.structuredBlobStorage = originalStructuredBlobStorage;
    }
  }

  @Test
  public void noBatchOutputReportsFallBackToLegacyPrefix() {
    StructuredBlobStorage originalStructuredBlobStorage = aiTranslateService.structuredBlobStorage;
    StructuredBlobStorage structuredBlobStorage = mock(StructuredBlobStorage.class);
    when(structuredBlobStorage.getString(
            StructuredBlobStorage.Prefix.AI_TRANSLATE_NO_BATCH_OUTPUT, "42/report"))
        .thenReturn(Optional.empty());
    when(structuredBlobStorage.getString(
            StructuredBlobStorage.Prefix.AI_TRANSALATE_NO_BATCH_OUTPUT, "42/report"))
        .thenReturn(Optional.of("{\"reportLocaleUrls\":[\"42/locale/fr-FR\"]}"));
    when(structuredBlobStorage.getString(
            StructuredBlobStorage.Prefix.AI_TRANSLATE_NO_BATCH_OUTPUT, "42/locale/fr-FR"))
        .thenReturn(Optional.empty());
    when(structuredBlobStorage.getString(
            StructuredBlobStorage.Prefix.AI_TRANSALATE_NO_BATCH_OUTPUT, "42/locale/fr-FR"))
        .thenReturn(Optional.of("legacy-locale-report"));

    try {
      aiTranslateService.structuredBlobStorage = structuredBlobStorage;

      assertThat(aiTranslateService.getReportContent(42L).reportLocaleUrls())
          .containsExactly("42/locale/fr-FR");
      assertThat(aiTranslateService.getReportContentLocale(42L, "fr-FR"))
          .isEqualTo("legacy-locale-report");
    } finally {
      aiTranslateService.structuredBlobStorage = originalStructuredBlobStorage;
    }
  }

  @Test
  public void noBatchOutputReportsSupportMixedPrefixRuns() {
    StructuredBlobStorage originalStructuredBlobStorage = aiTranslateService.structuredBlobStorage;
    StructuredBlobStorage structuredBlobStorage = mock(StructuredBlobStorage.class);
    when(structuredBlobStorage.getString(
            StructuredBlobStorage.Prefix.AI_TRANSLATE_NO_BATCH_OUTPUT, "42/report"))
        .thenReturn(Optional.of("{\"reportLocaleUrls\":[\"42/locale/fr-FR\"]}"));
    when(structuredBlobStorage.getString(
            StructuredBlobStorage.Prefix.AI_TRANSLATE_NO_BATCH_OUTPUT, "42/locale/fr-FR"))
        .thenReturn(Optional.empty());
    when(structuredBlobStorage.getString(
            StructuredBlobStorage.Prefix.AI_TRANSALATE_NO_BATCH_OUTPUT, "42/locale/fr-FR"))
        .thenReturn(Optional.of("legacy-locale-report"));
    when(structuredBlobStorage.getString(
            StructuredBlobStorage.Prefix.AI_TRANSLATE_NO_BATCH_OUTPUT, "43/report"))
        .thenReturn(Optional.empty());
    when(structuredBlobStorage.getString(
            StructuredBlobStorage.Prefix.AI_TRANSALATE_NO_BATCH_OUTPUT, "43/report"))
        .thenReturn(Optional.of("{\"reportLocaleUrls\":[\"43/locale/de-DE\"]}"));
    when(structuredBlobStorage.getString(
            StructuredBlobStorage.Prefix.AI_TRANSLATE_NO_BATCH_OUTPUT, "43/locale/de-DE"))
        .thenReturn(Optional.of("corrected-locale-report"));

    try {
      aiTranslateService.structuredBlobStorage = structuredBlobStorage;

      assertThat(aiTranslateService.getReportContent(42L).reportLocaleUrls())
          .containsExactly("42/locale/fr-FR");
      assertThat(aiTranslateService.getReportContentLocale(42L, "fr-FR"))
          .isEqualTo("legacy-locale-report");
      assertThat(aiTranslateService.getReportContent(43L).reportLocaleUrls())
          .containsExactly("43/locale/de-DE");
      assertThat(aiTranslateService.getReportContentLocale(43L, "de-DE"))
          .isEqualTo("corrected-locale-report");
    } finally {
      aiTranslateService.structuredBlobStorage = originalStructuredBlobStorage;
    }
  }

  @Test
  public void noBatchOutputReportsPreserveMissingAndStorageFailureBehavior() {
    StructuredBlobStorage originalStructuredBlobStorage = aiTranslateService.structuredBlobStorage;
    StructuredBlobStorage structuredBlobStorage = mock(StructuredBlobStorage.class);
    when(structuredBlobStorage.getString(
            StructuredBlobStorage.Prefix.AI_TRANSLATE_NO_BATCH_OUTPUT, "42/report"))
        .thenReturn(Optional.empty());
    when(structuredBlobStorage.getString(
            StructuredBlobStorage.Prefix.AI_TRANSALATE_NO_BATCH_OUTPUT, "42/report"))
        .thenReturn(Optional.empty());
    IllegalStateException storageFailure = new IllegalStateException("storage unavailable");
    when(structuredBlobStorage.getString(
            StructuredBlobStorage.Prefix.AI_TRANSLATE_NO_BATCH_OUTPUT, "43/report"))
        .thenThrow(storageFailure);

    try {
      aiTranslateService.structuredBlobStorage = structuredBlobStorage;

      assertThatThrownBy(() -> aiTranslateService.getReportContent(42L))
          .isInstanceOf(NoSuchElementException.class);
      assertThatThrownBy(() -> aiTranslateService.getReportContent(43L)).isSameAs(storageFailure);
      verify(structuredBlobStorage, never())
          .getString(StructuredBlobStorage.Prefix.AI_TRANSALATE_NO_BATCH_OUTPUT, "43/report");
    } finally {
      aiTranslateService.structuredBlobStorage = originalStructuredBlobStorage;
    }
  }

  @Test
  public void aiTranslateBatch() throws ExecutionException, InterruptedException {
    Assume.assumeNotNull(aiTranslateConfigurationProperties.getOpenaiClientToken());

    TMTestData tmTestData = new TMTestData(testIdWatcher);
    aiTranslateService
        .aiTranslateAsync(
            new AiTranslateService.AiTranslateInput(
                tmTestData.repository.getName(),
                null,
                100,
                null,
                true,
                null,
                null,
                null,
                AiTranslateType.WITH_REVIEW.name(),
                StatusFilter.FOR_TRANSLATION.name(),
                TMTextUnitVariant.Status.REVIEW_NEEDED.name(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                false,
                false,
                false,
                null))
        .get();
  }

  @Test
  public void aiTranslateNoBatch()
      throws ExecutionException, InterruptedException, RepositoryNameAlreadyUsedException {
    Assume.assumeNotNull(aiTranslateConfigurationProperties.getOpenaiClientToken());

    TMTestData tmTestData = new TMTestData(testIdWatcher);

    aiTranslateService
        .aiTranslateAsync(
            new AiTranslateService.AiTranslateInput(
                tmTestData.repository.getName(),
                null,
                100,
                null,
                false,
                null,
                null,
                null,
                AiTranslateType.WITH_REVIEW.name(),
                StatusFilter.FOR_TRANSLATION.name(),
                TMTextUnitVariant.Status.REVIEW_NEEDED.name(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                false,
                false,
                false,
                null))
        .get();
  }
}
