package com.box.l10n.mojito.service.tm;

import com.box.l10n.mojito.entity.Asset;
import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.entity.RepositoryLocale;
import com.box.l10n.mojito.queue.AssetLocalizeFanoutStore;
import com.box.l10n.mojito.rest.asset.LocaleInfo;
import com.box.l10n.mojito.rest.asset.MultiLocalizedAssetBody;
import com.box.l10n.mojito.service.asset.AssetRepository;
import com.box.l10n.mojito.service.pollableTask.PollableTaskBlobStorage;
import com.box.l10n.mojito.service.pollableTask.PollableTaskService;
import com.box.l10n.mojito.service.repository.RepositoryLocaleRepository;
import com.box.l10n.mojito.service.tm.AssetLocalizeFanoutInput.Manifest;
import com.box.l10n.mojito.service.tm.AssetLocalizeFanoutInput.Reference;
import com.box.l10n.mojito.service.tm.AssetLocalizeFanoutInput.Slot;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

/**
 * Existing HTTP contract, with durable internal parent admission rather than a new HTTP protocol.
 */
@Service
@ConditionalOnExpression(
    "${l10n.org.async-job-queue.enabled:false} && "
        + "${l10n.org.async-job-queue.asset-localize.enabled:false} && "
        + "'${l10n.org.async-job-queue.store:in-memory}' == 'jdbc'")
public class AssetLocalizeFanoutService {
  static final int MAX_LOCALES = 1000;
  private final AssetLocalizeFanoutStore store;
  private final AssetLocalizeFanoutInput inputs;
  private final PollableTaskService tasks;
  private final PollableTaskBlobStorage taskBlobs;
  private final AssetRepository assets;
  private final RepositoryLocaleRepository locales;
  private final AssetLocalizeAsyncJobRepairService repair;

  public AssetLocalizeFanoutService(
      AssetLocalizeFanoutStore store,
      AssetLocalizeFanoutInput inputs,
      PollableTaskService tasks,
      PollableTaskBlobStorage taskBlobs,
      AssetRepository assets,
      RepositoryLocaleRepository locales,
      AssetLocalizeAsyncJobRepairService repair) {
    this.store = store;
    this.inputs = inputs;
    this.tasks = tasks;
    this.taskBlobs = taskBlobs;
    this.assets = assets;
    this.locales = locales;
    this.repair = repair;
  }

  public PollableTask schedule(MultiLocalizedAssetBody input) {
    requireNoTransaction();
    Manifest manifest = freeze(input);
    Reference reference = inputs.stage(manifest);
    long taskId;
    try {
      taskId = store.register(reference);
    } catch (RuntimeException uncertain) {
      // A lost registration reply can still leave accepted work. Resolve by this attempt's UUID.
      // An absent/unavailable read is not proof of rejection; never replay HTTP or guess a task ID.
      try {
        taskId = store.findByInputName(reference.name()).orElseThrow().taskId();
      } catch (RuntimeException unresolved) {
        // The existing HttpClient retries repeatable POST responses on 429/503. This unkeyed
        // legacy endpoint must use a non-retryable status when a commit may have succeeded.
        throw new ResponseStatusException(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "Parallel localization admission outcome is unknown; do not automatically retry");
      }
    }
    return tasks.getPollableTask(taskId);
  }

  Manifest freeze(MultiLocalizedAssetBody input) {
    if (input.getPullRunName() != null
        || input.getLocaleInfos() == null
        || input.getLocaleInfos().size() > MAX_LOCALES) {
      throw invalid();
    }
    Asset asset = assets.findById(input.getAssetId()).orElseThrow(this::invalid);
    List<Slot> slots = new ArrayList<>();
    Set<String> outputTags = new HashSet<>();
    for (LocaleInfo locale : input.getLocaleInfos()) {
      if (locale == null || locale.getLocaleId() == null || locale.getLocaleId() <= 0) {
        throw invalid();
      }
      RepositoryLocale repositoryLocale =
          locales.findByRepositoryIdAndLocaleId(
              asset.getRepository().getId(), locale.getLocaleId());
      if (repositoryLocale == null) {
        throw invalid();
      }
      String tag =
          locale.getOutputBcp47tag() == null
              ? repositoryLocale.getLocale().getBcp47Tag()
              : locale.getOutputBcp47tag();
      if (!AssetLocalizeFanoutInput.isValidOutputTag(tag) || !outputTags.add(tag)) {
        throw invalid();
      }
      slots.add(new Slot(locale.getLocaleId(), tag, locale.getOutputBcp47tag()));
    }
    input.getGenerateLocalizedAssetJobIds().clear();
    // stage() serializes immediately; later processing always reads that immutable verified copy.
    return new Manifest(1, input, List.copyOf(slots));
  }

  public void resume(long parentId) {
    requireNoTransaction();
    AssetLocalizeFanoutStore.Parent parent = store.find(parentId).orElseThrow();
    if (parent.state().equals("COMPLETED")) {
      return;
    }
    if (!parent.state().equals("FINISHED")) {
      Manifest manifest = inputs.read(parent.input());
      store.accept(parent, manifest);
      MultiLocalizedAssetBody output = manifest.input();
      output.getGenerateLocalizedAssetJobIds().clear();
      store.mappings(parent).forEach(output::addGenerateLocalizedAddedJobIdToMap);
      taskBlobs.saveOutput(parentId, output);
      store.finish(parentId);
    }
    // Queue terminal commits can outlive their callback. Repair only recorded winners, never
    // rerun generation or invent a replacement task when a callback was lost during restart.
    for (long jobId : store.terminalChildrenNeedingRepair(parentId)) {
      repair.repairTerminalPollableTask(Long.toString(jobId));
    }
    store.completeIfChildrenFinished(parentId);
  }

  private void requireNoTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("Asset fanout orchestration requires no ambient transaction");
    }
  }

  private ResponseStatusException invalid() {
    return new ResponseStatusException(
        HttpStatus.BAD_REQUEST,
        "Invalid parallel localization plan (configured locales and unique output tags required; maximum 1000 locales)");
  }
}
