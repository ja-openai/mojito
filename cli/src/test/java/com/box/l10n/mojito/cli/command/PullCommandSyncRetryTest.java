package com.box.l10n.mojito.cli.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.cli.console.ConsoleWriter;
import com.box.l10n.mojito.cli.filefinder.FileMatch;
import com.box.l10n.mojito.cli.filefinder.file.FileType;
import com.box.l10n.mojito.rest.client.AssetClient;
import com.box.l10n.mojito.rest.entity.Asset;
import com.box.l10n.mojito.rest.entity.Locale;
import com.box.l10n.mojito.rest.entity.LocalizedAssetBody;
import com.box.l10n.mojito.rest.entity.RepositoryLocale;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

@RunWith(MockitoJUnitRunner.class)
public class PullCommandSyncRetryTest {

  @Mock AssetClient assetClient;

  @Mock FileMatch sourceFileMatch;

  @Mock FileType fileType;

  PullCommand pullCommand;
  RepositoryLocale repositoryLocale;
  Asset asset;

  @Before
  public void setUp() {
    pullCommand = new PullCommand();
    pullCommand.assetClient = assetClient;
    pullCommand.consoleWriter = mock(ConsoleWriter.class, RETURNS_SELF);
    pullCommand.pullWithNoSourceBranches = List.of();

    when(sourceFileMatch.getFileType()).thenReturn(fileType);

    Locale locale = new Locale();
    locale.setId(22L);
    locale.setBcp47Tag("fr-FR");
    repositoryLocale = new RepositoryLocale();
    repositoryLocale.setLocale(locale);

    asset = new Asset();
    asset.setId(11L);
  }

  @Test
  public void clientErrorIsNotRetriedAndPreservesResponse() {
    String responseBody = "{\"message\":\"Requested branch is not active\"}";
    HttpClientErrorException clientError =
        HttpClientErrorException.create(
            HttpStatus.BAD_REQUEST,
            "Bad Request",
            HttpHeaders.EMPTY,
            responseBody.getBytes(StandardCharsets.UTF_8),
            StandardCharsets.UTF_8);
    localizedRequest().thenThrow(clientError);

    assertThatThrownBy(this::requestLocalizedAsset)
        .isInstanceOfSatisfying(
            CommandException.class,
            exception -> {
              assertThat(exception).hasMessageContaining("400").hasMessageContaining(responseBody);
              assertThat(exception.getCause()).isSameAs(clientError);
            });
    verifyLocalizedRequestCount(1);
  }

  @Test
  public void retryExhaustionRetainsLastCause() {
    RuntimeException lastFailure = new RuntimeException("temporary server failure");
    localizedRequest().thenThrow(lastFailure);

    assertThatThrownBy(this::requestLocalizedAsset)
        .isInstanceOfSatisfying(
            CommandException.class,
            exception -> {
              assertThat(exception).hasMessageContaining("retry count: 5");
              assertThat(exception.getCause()).isSameAs(lastFailure);
            });
    verifyLocalizedRequestCount(5);
  }

  @Test
  public void tooManyRequestsStillUsesRetryPath() {
    HttpClientErrorException clientError =
        HttpClientErrorException.create(
            HttpStatus.TOO_MANY_REQUESTS,
            "Too Many Requests",
            HttpHeaders.EMPTY,
            new byte[0],
            StandardCharsets.UTF_8);
    localizedRequest().thenThrow(clientError);

    assertThatThrownBy(this::requestLocalizedAsset)
        .isInstanceOfSatisfying(
            CommandException.class,
            exception -> {
              assertThat(exception).hasMessageContaining("retry count: 5");
              assertThat(exception.getCause()).isSameAs(clientError);
            });
    verifyLocalizedRequestCount(5);
  }

  private org.mockito.stubbing.OngoingStubbing<LocalizedAssetBody> localizedRequest() {
    return when(
        assetClient.getLocalizedAssetForContent(
            11L,
            22L,
            "source",
            null,
            null,
            List.of(),
            LocalizedAssetBody.Status.ALL,
            LocalizedAssetBody.InheritanceMode.USE_PARENT,
            null,
            false,
            List.of()));
  }

  private LocalizedAssetBody requestLocalizedAsset() {
    return pullCommand.getLocalizedAssetBodySync(
        sourceFileMatch, repositoryLocale, null, List.of(), asset, "source", null);
  }

  private void verifyLocalizedRequestCount(int count) {
    verify(assetClient, times(count))
        .getLocalizedAssetForContent(
            11L,
            22L,
            "source",
            null,
            null,
            List.of(),
            LocalizedAssetBody.Status.ALL,
            LocalizedAssetBody.InheritanceMode.USE_PARENT,
            null,
            false,
            List.of());
  }
}
