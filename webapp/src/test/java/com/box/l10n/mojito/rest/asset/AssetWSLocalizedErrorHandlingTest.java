package com.box.l10n.mojito.rest.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.Asset;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.RepositoryLocale;
import com.box.l10n.mojito.okapi.asset.UnsupportedAssetFilterTypeException;
import com.box.l10n.mojito.service.asset.AssetRepository;
import com.box.l10n.mojito.service.repository.RepositoryLocaleRepository;
import com.box.l10n.mojito.service.tm.TMService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@RunWith(MockitoJUnitRunner.class)
public class AssetWSLocalizedErrorHandlingTest {

  @Mock AssetRepository assetRepository;

  @Mock RepositoryLocaleRepository repositoryLocaleRepository;

  @Mock TMService tmService;

  @Mock Asset asset;

  @Mock Repository repository;

  @Mock RepositoryLocale repositoryLocale;

  AssetWS assetWS;

  @Before
  public void setUp() {
    assetWS = new AssetWS();
    assetWS.assetRepository = assetRepository;
    assetWS.repositoryLocaleRepository = repositoryLocaleRepository;
    assetWS.tmService = tmService;
    assetWS.meterRegistry = new SimpleMeterRegistry();

    when(assetRepository.getReferenceById(11L)).thenReturn(asset);
    when(asset.getRepository()).thenReturn(repository);
    when(repository.getId()).thenReturn(7L);
    when(repositoryLocaleRepository.findByRepositoryIdAndLocaleId(7L, 22L))
        .thenReturn(repositoryLocale);
  }

  @Test
  public void optInIllegalArgumentBecomesBadRequestWithReason() throws Exception {
    LocalizedAssetBody body = body();
    body.setPullWithNoSource(true);
    IllegalArgumentException failure = new IllegalArgumentException("JSON root must be an object");
    failGeneration(body, failure);

    assertThatThrownBy(() -> assetWS.getLocalizedAssetForContent(11L, 22L, body))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            exception -> {
              assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
              assertThat(exception.getReason()).isEqualTo("JSON root must be an object");
              assertThat(exception.getCause()).isSameAs(failure);
            });
  }

  @Test
  public void branchListAlsoMakesUnsupportedFilterABadRequest() throws Exception {
    LocalizedAssetBody body = body();
    body.setPullWithNoSourceBranches(List.of("authoring/checkout"));
    UnsupportedAssetFilterTypeException failure =
        new UnsupportedAssetFilterTypeException("Only JSON assets are supported");
    failGeneration(body, failure);

    assertThatThrownBy(() -> assetWS.getLocalizedAssetForContent(11L, 22L, body))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            exception -> {
              assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
              assertThat(exception.getReason()).isEqualTo("Only JSON assets are supported");
              assertThat(exception.getCause()).isSameAs(failure);
            });
  }

  @Test
  public void legacyIllegalArgumentBehaviorIsUnchanged() throws Exception {
    LocalizedAssetBody body = body();
    IllegalArgumentException failure = new IllegalArgumentException("legacy failure");
    failGeneration(body, failure);

    assertThatThrownBy(() -> assetWS.getLocalizedAssetForContent(11L, 22L, body)).isSameAs(failure);
  }

  @Test
  public void legacyUnsupportedFilterBehaviorIsUnchanged() throws Exception {
    LocalizedAssetBody body = body();
    UnsupportedAssetFilterTypeException failure =
        new UnsupportedAssetFilterTypeException("legacy unsupported filter");
    failGeneration(body, failure);

    assertThatThrownBy(() -> assetWS.getLocalizedAssetForContent(11L, 22L, body)).isSameAs(failure);
  }

  private LocalizedAssetBody body() {
    LocalizedAssetBody body = new LocalizedAssetBody();
    body.setContent("source");
    return body;
  }

  private void failGeneration(LocalizedAssetBody body, Exception failure) throws Exception {
    doThrow(failure)
        .when(tmService)
        .generateLocalized(
            asset,
            "source",
            repositoryLocale,
            body.getOutputBcp47tag(),
            body.getFilterConfigIdOverride(),
            body.getFilterOptions(),
            body.getStatus(),
            body.getInheritanceMode(),
            body.getPullRunName(),
            body.isPullWithNoSource(),
            body.getPullWithNoSourceBranches());
  }
}
