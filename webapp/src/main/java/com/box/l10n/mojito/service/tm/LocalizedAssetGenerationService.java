package com.box.l10n.mojito.service.tm;

import com.box.l10n.mojito.entity.Asset;
import com.box.l10n.mojito.entity.RepositoryLocale;
import com.box.l10n.mojito.okapi.asset.UnsupportedAssetFilterTypeException;
import com.box.l10n.mojito.rest.asset.AssetWithIdNotFoundException;
import com.box.l10n.mojito.rest.asset.LocalizedAssetBody;
import com.box.l10n.mojito.service.NormalizationUtils;
import com.box.l10n.mojito.service.asset.AssetRepository;
import com.box.l10n.mojito.service.repository.RepositoryLocaleRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import net.sf.okapi.common.exceptions.OkapiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Shared localized-asset generation path for Quartz and durable async queue execution. */
@Service
public class LocalizedAssetGenerationService {

  static Logger logger = LoggerFactory.getLogger(LocalizedAssetGenerationService.class);

  private final AssetRepository assetRepository;
  private final RepositoryLocaleRepository repositoryLocaleRepository;
  private final TMService tmService;
  private final MeterRegistry meterRegistry;

  public LocalizedAssetGenerationService(
      AssetRepository assetRepository,
      RepositoryLocaleRepository repositoryLocaleRepository,
      TMService tmService,
      MeterRegistry meterRegistry) {
    this.assetRepository = assetRepository;
    this.repositoryLocaleRepository = repositoryLocaleRepository;
    this.tmService = tmService;
    this.meterRegistry = meterRegistry;
  }

  public LocalizedAssetBody generate(LocalizedAssetBody localizedAssetBody)
      throws AssetWithIdNotFoundException {
    Long assetId = localizedAssetBody.getAssetId();
    logger.debug("Localizing content payload with asset id = {}, and locale = {}", assetId);

    Asset asset = assetRepository.findById(assetId).orElse(null);
    if (asset == null) {
      throw new AssetWithIdNotFoundException(assetId);
    }

    RepositoryLocale repositoryLocale =
        repositoryLocaleRepository.findByRepositoryIdAndLocaleId(
            asset.getRepository().getId(), localizedAssetBody.getLocaleId());

    String repositoryName = asset.getRepository().getName();
    String localeTag = repositoryLocale.getLocale().getBcp47Tag();
    long startNanos = System.nanoTime();
    try {
      String normalizedContent = NormalizationUtils.normalize(localizedAssetBody.getContent());

      String generatedLocalizedContent;
      try {
        generatedLocalizedContent =
            tmService.generateLocalized(
                asset,
                normalizedContent,
                repositoryLocale,
                localizedAssetBody.getOutputBcp47tag(),
                localizedAssetBody.getFilterConfigIdOverride(),
                localizedAssetBody.getFilterOptions(),
                localizedAssetBody.getStatus(),
                localizedAssetBody.getInheritanceMode(),
                localizedAssetBody.getPullRunName(),
                localizedAssetBody.isPullWithNoSource(),
                localizedAssetBody.getPullWithNoSourceBranches());
      } catch (UnsupportedAssetFilterTypeException e) {
        throw new OkapiException(e);
      }

      localizedAssetBody.setContent(generatedLocalizedContent);

      if (localizedAssetBody.getOutputBcp47tag() != null) {
        localizedAssetBody.setBcp47Tag(localizedAssetBody.getOutputBcp47tag());
      } else {
        localizedAssetBody.setBcp47Tag(localeTag);
      }

      return localizedAssetBody;
    } finally {
      long durationNanos = System.nanoTime() - startNanos;
      recordMetric(
          () ->
              meterRegistry
                  .timer(
                      "GenerateLocalizedAssetJob.call",
                      "repositoryName",
                      repositoryName,
                      "bcp47Tag",
                      localeTag)
                  .record(durationNanos, java.util.concurrent.TimeUnit.NANOSECONDS));
    }
  }

  private void recordMetric(Runnable recording) {
    try {
      recording.run();
    } catch (Throwable failure) {
      rethrowJvmFatal(failure);
      try {
        logger.warn("Failed to record localized asset generation metric", failure);
      } catch (Throwable loggingFailure) {
        rethrowJvmFatal(loggingFailure);
        // Diagnostics must not discard generated output or replace a generation failure.
      }
    }
  }

  private static void rethrowJvmFatal(Throwable failure) {
    if (isJvmFatal(failure)) {
      throw (Error) failure;
    }
    Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
    ArrayDeque<Throwable> pending = new ArrayDeque<>();
    pending.add(failure);
    while (!pending.isEmpty()) {
      Throwable current = pending.removeFirst();
      if (!visited.add(current)) {
        continue;
      }
      if (isJvmFatal(current)) {
        throw (Error) current;
      }
      Throwable cause = current.getCause();
      if (cause != null) {
        pending.addLast(cause);
      }
      for (Throwable suppressed : current.getSuppressed()) {
        pending.addLast(suppressed);
      }
    }
  }

  @SuppressWarnings("removal")
  private static boolean isJvmFatal(Throwable throwable) {
    return throwable instanceof VirtualMachineError || throwable instanceof ThreadDeath;
  }
}
