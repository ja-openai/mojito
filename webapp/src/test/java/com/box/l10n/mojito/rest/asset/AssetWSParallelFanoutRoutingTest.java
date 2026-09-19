package com.box.l10n.mojito.rest.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.box.l10n.mojito.entity.Asset;
import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.quartz.QuartzPollableFutureTask;
import com.box.l10n.mojito.quartz.QuartzPollableTaskScheduler;
import com.box.l10n.mojito.service.asset.AssetRepository;
import com.box.l10n.mojito.service.tm.AssetLocalizeFanoutService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.Before;
import org.junit.Test;

public class AssetWSParallelFanoutRoutingTest {
  private final AssetWS api = new AssetWS();
  private final MultiLocalizedAssetBody input = new MultiLocalizedAssetBody();
  private final PollableTask task = new PollableTask();

  @Before
  public void setup() {
    api.asyncJobQueueEnabled = true;
    api.asyncJobQueueAssetLocalizeEnabled = true;
    api.asyncJobQueueAssetLocalizeProducerEnabled = true;
    api.asyncJobQueueAssetLocalizeFanoutEnabled = true;
    api.assetLocalizeFanoutService = mock(AssetLocalizeFanoutService.class);
    api.quartzPollableTaskScheduler = mock(QuartzPollableTaskScheduler.class);
    api.assetRepository = mock(AssetRepository.class);
    api.meterRegistry = new SimpleMeterRegistry();
    Repository repository = new Repository();
    repository.setId(2L);
    repository.setName("fixture");
    Asset asset = new Asset();
    asset.setRepository(repository);
    when(api.assetRepository.getReferenceById(1L)).thenReturn(asset);
    when(api.quartzPollableTaskScheduler.scheduleJob(any()))
        .thenReturn(new QuartzPollableFutureTask<>(task, MultiLocalizedAssetBody.class));
    when(api.assetLocalizeFanoutService.schedule(input)).thenReturn(task);
  }

  @Test
  public void enabledParallelRequestRegistersBeforeReturningTask() {
    assertThat(api.getLocalizedAssetForContentParallel(1L, input)).isSameAs(task);
    assertThat(input.getAssetId()).isEqualTo(1L);
    assertThat(
            api.meterRegistry.get("assetWS.getLocalizedAssetForContentParallel").counter().count())
        .isEqualTo(1);
    verify(api.assetLocalizeFanoutService).schedule(input);
    verifyNoInteractions(api.quartzPollableTaskScheduler);
  }

  @Test
  public void requestMetricFailurePrecedesAnyDurableAdmission() {
    api.meterRegistry =
        mock(
            io.micrometer.core.instrument.MeterRegistry.class,
            invocation -> {
              throw new IllegalStateException("metric failed");
            });
    assertThatThrownBy(() -> api.getLocalizedAssetForContentParallel(1L, input))
        .hasMessage("metric failed");
    verifyNoInteractions(api.assetLocalizeFanoutService, api.quartzPollableTaskScheduler);
  }

  @Test
  public void everyNonNullTrackingNameRemainsQuartz() {
    for (String name : new String[] {"tracked", "", " "}) {
      input.setPullRunName(name);
      assertThat(api.getLocalizedAssetForContentParallel(1L, input)).isSameAs(task);
    }
    verify(api.quartzPollableTaskScheduler, times(3)).scheduleJob(any());
    verifyNoInteractions(api.assetLocalizeFanoutService);
  }

  @Test
  public void producerRollbackStopsNewDurableParents() {
    api.asyncJobQueueAssetLocalizeProducerEnabled = false;
    api.getLocalizedAssetForContentParallel(1L, input);
    verify(api.quartzPollableTaskScheduler).scheduleJob(any());
    verifyNoInteractions(api.assetLocalizeFanoutService);
  }

  @Test
  public void fanoutFlagRemainsSeparateFromDirectEnablement() {
    api.asyncJobQueueAssetLocalizeFanoutEnabled = false;
    api.getLocalizedAssetForContentParallel(1L, input);
    verify(api.quartzPollableTaskScheduler).scheduleJob(any());
    verifyNoInteractions(api.assetLocalizeFanoutService);
  }

  @Test
  public void unavailableOrUncertainDurableServiceNeverFallsBackToQuartz() {
    when(api.assetLocalizeFanoutService.schedule(input))
        .thenThrow(new IllegalStateException("unknown"));
    assertThatThrownBy(() -> api.getLocalizedAssetForContentParallel(1L, input))
        .hasMessage("unknown");
    api.assetLocalizeFanoutService = null;
    assertThatThrownBy(() -> api.getLocalizedAssetForContentParallel(1L, input))
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(api.quartzPollableTaskScheduler);
  }
}
