package com.box.l10n.mojito.service.tm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.box.l10n.mojito.entity.Asset;
import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.RepositoryGenerationSettings;
import com.box.l10n.mojito.entity.RepositoryLocale;
import com.box.l10n.mojito.queue.AssetLocalizeFanoutStore;
import com.box.l10n.mojito.rest.asset.LocaleInfo;
import com.box.l10n.mojito.rest.asset.MultiLocalizedAssetBody;
import com.box.l10n.mojito.service.asset.AssetRepository;
import com.box.l10n.mojito.service.locale.LocaleService;
import com.box.l10n.mojito.service.pollableTask.PollableTaskBlobStorage;
import com.box.l10n.mojito.service.pollableTask.PollableTaskService;
import com.box.l10n.mojito.service.repository.GlobalGenerationService;
import com.box.l10n.mojito.service.repository.RepositoryGenerationService;
import com.box.l10n.mojito.service.repository.RepositoryGenerationSettingsRepository;
import com.box.l10n.mojito.service.repository.RepositoryLocaleRepository;
import com.box.l10n.mojito.service.repository.RepositoryRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

public class AssetLocalizeFanoutServiceTest {
  private final AssetRepository assets = mock(AssetRepository.class);
  private final RepositoryLocaleRepository locales = mock(RepositoryLocaleRepository.class);
  private final AssetLocalizeFanoutInput inputs = mock(AssetLocalizeFanoutInput.class);
  private final AssetLocalizeFanoutStore store = mock(AssetLocalizeFanoutStore.class);
  private final PollableTaskService tasks = mock(PollableTaskService.class);
  private final PollableTaskBlobStorage taskBlobs = mock(PollableTaskBlobStorage.class);
  private final Repository repository = new Repository();
  private final GlobalGenerationService global = mock(GlobalGenerationService.class);
  private final RepositoryGenerationSettingsRepository settings =
      mock(RepositoryGenerationSettingsRepository.class);
  private final AssetLocalizeFanoutService service =
      new AssetLocalizeFanoutService(
          store,
          inputs,
          tasks,
          taskBlobs,
          assets,
          locales,
          mock(AssetLocalizeAsyncJobRepairService.class),
          new RepositoryGenerationService(
              mock(RepositoryRepository.class), mock(LocaleService.class), global, settings));
  private final MultiLocalizedAssetBody input = new MultiLocalizedAssetBody();

  @Before
  public void setup() {
    input.setAssetId(1L);
    repository.setId(2L);
    Asset asset = new Asset();
    asset.setRepository(repository);
    when(assets.findById(1L)).thenReturn(Optional.of(asset));
    for (long id : List.of(3L, 4L)) {
      RepositoryLocale configured = new RepositoryLocale();
      com.box.l10n.mojito.entity.Locale locale = new com.box.l10n.mojito.entity.Locale();
      locale.setBcp47Tag("locale-" + id);
      configured.setLocale(locale);
      when(locales.findByRepositoryIdAndLocaleId(2L, id)).thenReturn(configured);
    }
    input.setLocaleInfos(List.of(locale(3L, null), locale(4L, "override")));
  }

  private LocaleInfo locale(long id, String output) {
    LocaleInfo locale = new LocaleInfo();
    locale.setLocaleId(id);
    locale.setOutputBcp47tag(output);
    return locale;
  }

  private void excludeLocales(Repository configuredRepository, List<String> localeTags) {
    RepositoryGenerationSettings saved = new RepositoryGenerationSettings();
    saved.setRepositoryId(configuredRepository.getId());
    saved.setExcludedLocaleTags(localeTags);
    when(settings.findById(configuredRepository.getId())).thenReturn(Optional.of(saved));
  }

  @Test
  public void freezesOrderedResolvedTagsWithOneLookupPerSlot() {
    AssetLocalizeFanoutInput.Manifest frozen = service.freeze(input);
    assertThat(frozen.slots())
        .containsExactly(
            new AssetLocalizeFanoutInput.Slot(3L, "locale-3", null),
            new AssetLocalizeFanoutInput.Slot(4L, "override", "override"));
    verify(locales).findByRepositoryIdAndLocaleId(2L, 3L);
    verify(locales).findByRepositoryIdAndLocaleId(2L, 4L);
    verifyNoInteractions(inputs, store);
  }

  @Test
  public void excludedLocaleHasNoDurableSlotEvenWithAnExplicitAlias() {
    excludeLocales(repository, List.of("locale-4"));

    assertThat(service.freeze(input).slots())
        .containsExactly(new AssetLocalizeFanoutInput.Slot(3L, "locale-3", null));
    assertThat(input.getLocaleInfos()).hasSize(2);
    verifyNoInteractions(inputs, store);
  }

  @Test
  public void allExcludedLocalesCompleteWithAnEmptyLegacyOutputMap() {
    excludeLocales(repository, List.of("locale-3"));
    when(global.getExcludedLocaleTags()).thenReturn(List.of("locale-4"));
    AssetLocalizeFanoutInput.Reference reference =
        new AssetLocalizeFanoutInput.Reference("empty-plan", "hash", 0);
    when(inputs.stage(any())).thenReturn(reference);
    when(store.register(reference)).thenReturn(7L);
    PollableTask parentTask = new PollableTask();
    parentTask.setId(7L);
    when(tasks.getPollableTask(7L)).thenReturn(parentTask);

    assertThat(service.schedule(input)).isSameAs(parentTask);
    ArgumentCaptor<AssetLocalizeFanoutInput.Manifest> manifests =
        ArgumentCaptor.forClass(AssetLocalizeFanoutInput.Manifest.class);
    verify(inputs).stage(manifests.capture());
    AssetLocalizeFanoutInput.Manifest manifest = manifests.getValue();
    assertThat(manifest.slots()).isEmpty();

    AssetLocalizeFanoutStore.Parent parent =
        new AssetLocalizeFanoutStore.Parent(7L, reference, "PENDING");
    when(store.find(7L)).thenReturn(Optional.of(parent));
    when(inputs.read(reference)).thenReturn(manifest);
    when(store.mappings(parent)).thenReturn(Map.of());
    service.resume(7L);

    assertThat(input.getGenerateLocalizedAssetJobIds()).isEmpty();
    verify(store).accept(parent, manifest);
    verify(taskBlobs).saveOutput(7L, input);
    verify(store).finish(7L);
    verify(store).completeIfChildrenFinished(7L);
    verify(global).getExcludedLocaleTags();
  }

  @Test
  public void globalExclusionsApplyAcrossRepositoriesWithoutLocalConfiguration() {
    when(global.getExcludedLocaleTags()).thenReturn(List.of("locale-4"));
    assertThat(service.freeze(input).slots())
        .containsExactly(new AssetLocalizeFanoutInput.Slot(3L, "locale-3", null));
    Repository other = new Repository();
    other.setId(8L);
    Asset otherAsset = new Asset();
    otherAsset.setRepository(other);
    when(assets.findById(9L)).thenReturn(Optional.of(otherAsset));
    input.setAssetId(9L);
    for (long id : List.of(3L, 4L)) {
      RepositoryLocale configured = locales.findByRepositoryIdAndLocaleId(2L, id);
      when(locales.findByRepositoryIdAndLocaleId(8L, id)).thenReturn(configured);
    }

    assertThat(service.freeze(input).slots())
        .containsExactly(new AssetLocalizeFanoutInput.Slot(3L, "locale-3", null));
    verify(global, times(2)).getExcludedLocaleTags();
  }

  @Test
  public void globalExclusionsCanOmitAllRequestedLocalesIncludingTheSource() {
    when(global.getExcludedLocaleTags()).thenReturn(List.of("locale-3", "locale-4"));
    repository.setSourceLocale(locales.findByRepositoryIdAndLocaleId(2L, 3L).getLocale());

    assertThat(service.freeze(input).slots()).isEmpty();
    verify(global).getExcludedLocaleTags();
  }

  @Test
  public void exclusionsDoNotAffectAnotherRepositoryOrMatchOutputAliases() {
    excludeLocales(repository, List.of("locale-3"));
    Repository other = new Repository();
    other.setId(8L);
    excludeLocales(other, List.of("override"));
    Asset otherAsset = new Asset();
    otherAsset.setRepository(other);
    when(assets.findById(9L)).thenReturn(Optional.of(otherAsset));
    for (long id : List.of(3L, 4L)) {
      RepositoryLocale configured = locales.findByRepositoryIdAndLocaleId(2L, id);
      when(locales.findByRepositoryIdAndLocaleId(8L, id)).thenReturn(configured);
    }
    input.setAssetId(9L);

    assertThat(service.freeze(input).slots())
        .containsExactly(
            new AssetLocalizeFanoutInput.Slot(3L, "locale-3", null),
            new AssetLocalizeFanoutInput.Slot(4L, "override", "override"));
    verify(settings).findById(8L);
    verify(settings, never()).findById(2L);
  }

  @Test
  public void excludedLocalesStillValidateOutputTagsBeforeAdmission() {
    excludeLocales(repository, List.of("locale-4"));
    for (String tag : List.of("locale-3", "invalid\0")) {
      input.setLocaleInfos(List.of(locale(3L, null), locale(4L, tag)));
      assertThatThrownBy(() -> service.schedule(input))
          .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
    verifyNoInteractions(inputs, store);
  }

  @Test
  public void invalidLaterLocaleDoesNotUploadOrRegisterEarlierChildren() {
    when(locales.findByRepositoryIdAndLocaleId(2L, 4L)).thenReturn(null);
    assertThatThrownBy(() -> service.schedule(input))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    verifyNoInteractions(inputs, store);
  }

  @Test
  public void duplicateOutputTagsAreRejectedBeforeAdmission() {
    input.setLocaleInfos(List.of(locale(3L, "same"), locale(4L, "same")));
    assertThatThrownBy(() -> service.schedule(input))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    verifyNoInteractions(inputs, store);
  }

  @Test
  public void malformedLaterOutputTagIsRejectedBeforeUploadOrRegistration() {
    for (String tag : List.of("tag\0", "tag\uD800", "tag\uDC00", "tag\uD800x")) {
      input.setLocaleInfos(List.of(locale(3L, null), locale(4L, tag)));
      assertThatThrownBy(() -> service.schedule(input))
          .isInstanceOfSatisfying(
              org.springframework.web.server.ResponseStatusException.class,
              error -> assertThat(error.getStatusCode().value()).isEqualTo(400));
    }
    verifyNoInteractions(inputs, store);
  }

  @Test
  public void validUnicodeOutputTagRemainsUnchanged() {
    String tag = "custom-\u65E5\u672C-\uD83D\uDE80";
    input.setLocaleInfos(List.of(locale(3L, tag)));
    assertThat(service.freeze(input).slots())
        .containsExactly(new AssetLocalizeFanoutInput.Slot(3L, tag, tag));
    verifyNoInteractions(inputs, store);
  }

  @Test
  public void boundedPlanRejectsTooManyLocalesAndTrackedRequests() {
    input.setLocaleInfos(java.util.Collections.nCopies(1001, locale(3L, "de")));
    assertThatThrownBy(() -> service.schedule(input))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    input.setLocaleInfos(List.of());
    input.setPullRunName("");
    assertThatThrownBy(() -> service.schedule(input))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    verifyNoInteractions(inputs, store);
  }

  @Test
  public void unknownRegistrationMustNotReturnAutomaticallyRetriedHttp503() {
    AssetLocalizeFanoutInput.Reference reference =
        new AssetLocalizeFanoutInput.Reference("fixture", "hash", 2);
    when(inputs.stage(any())).thenReturn(reference);
    when(store.register(reference)).thenThrow(new IllegalStateException("commit reply lost"));
    when(store.findByInputName("fixture")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.schedule(input))
        .isInstanceOfSatisfying(
            org.springframework.web.server.ResponseStatusException.class,
            error -> {
              assertThat(error.getStatusCode().value()).isEqualTo(500);
              org.apache.hc.client5.http.impl.DefaultHttpRequestRetryStrategy strategy =
                  new org.apache.hc.client5.http.impl.DefaultHttpRequestRetryStrategy();
              org.apache.hc.client5.http.protocol.HttpClientContext context =
                  org.apache.hc.client5.http.protocol.HttpClientContext.create();
              assertThat(
                      strategy.retryRequest(
                          new org.apache.hc.core5.http.message.BasicHttpResponse(503), 1, context))
                  .isTrue();
              assertThat(
                      strategy.retryRequest(
                          new org.apache.hc.core5.http.message.BasicHttpResponse(
                              error.getStatusCode().value()),
                          1,
                          context))
                  .isFalse();
            });
    verify(store).register(reference);
  }
}
