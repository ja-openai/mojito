package com.box.l10n.mojito.service.tm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.box.l10n.mojito.entity.Asset;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.RepositoryLocale;
import com.box.l10n.mojito.queue.AssetLocalizeFanoutStore;
import com.box.l10n.mojito.rest.asset.LocaleInfo;
import com.box.l10n.mojito.rest.asset.MultiLocalizedAssetBody;
import com.box.l10n.mojito.service.asset.AssetRepository;
import com.box.l10n.mojito.service.pollableTask.PollableTaskBlobStorage;
import com.box.l10n.mojito.service.pollableTask.PollableTaskService;
import com.box.l10n.mojito.service.repository.RepositoryLocaleRepository;
import java.util.List;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;

public class AssetLocalizeFanoutServiceTest {
  private final AssetRepository assets = mock(AssetRepository.class);
  private final RepositoryLocaleRepository locales = mock(RepositoryLocaleRepository.class);
  private final AssetLocalizeFanoutInput inputs = mock(AssetLocalizeFanoutInput.class);
  private final AssetLocalizeFanoutStore store = mock(AssetLocalizeFanoutStore.class);
  private final AssetLocalizeFanoutService service =
      new AssetLocalizeFanoutService(
          store,
          inputs,
          mock(PollableTaskService.class),
          mock(PollableTaskBlobStorage.class),
          assets,
          locales,
          mock(AssetLocalizeAsyncJobRepairService.class));
  private final MultiLocalizedAssetBody input = new MultiLocalizedAssetBody();

  @Before
  public void setup() {
    input.setAssetId(1L);
    Repository repository = new Repository();
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
