package com.box.l10n.mojito.service.tm;

import com.box.l10n.mojito.queue.AssetLocalizeFanoutStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Accepted parents survive producer/fanout rollback; leave compatible consumers enabled to drain.
 */
@Component
@Profile("!disablescheduling")
@ConditionalOnExpression(
    "${l10n.org.async-job-queue.enabled:false} && "
        + "${l10n.org.async-job-queue.asset-localize.enabled:false} && "
        + "${l10n.org.async-job-queue.queues.assetlocalize.consumer-enabled:true} && "
        + "'${l10n.org.async-job-queue.store:in-memory}' == 'jdbc'")
public class AssetLocalizeFanoutReconciler {
  private static final Logger logger = LoggerFactory.getLogger(AssetLocalizeFanoutReconciler.class);
  private final AssetLocalizeFanoutStore store;
  private final AssetLocalizeFanoutService service;

  public AssetLocalizeFanoutReconciler(
      AssetLocalizeFanoutStore store, AssetLocalizeFanoutService service) {
    this.store = store;
    this.service = service;
  }

  @Scheduled(
      fixedDelayString =
          "${l10n.org.async-job-queue.asset-localize.fanout-reconcile-interval-ms:1000}")
  public void reconcile() {
    for (long parentId : store.dueParents(10)) {
      try {
        if (store.claimAttempt(parentId)) {
          service.resume(parentId);
        }
      } catch (RuntimeException failure) {
        // Keep pending/accepted state intact. Retry after the persisted lease, including restart.
        logger.warn(
            "Durable asset fanout {} remains pending after reconciliation failure ({})",
            parentId,
            failure.getClass().getSimpleName());
      }
    }
  }
}
