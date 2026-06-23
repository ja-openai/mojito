package com.box.l10n.mojito.service.jsonconfiglocalization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.jsonconfiglocalization.JsonConfigLocalizationProcessorService.ExportResult;
import com.box.l10n.mojito.service.jsonconfiglocalization.JsonConfigLocalizationService.JsonConfigLocalization;
import com.box.l10n.mojito.service.jsonconfiglocalization.JsonConfigLocalizationService.RepositoryRef;
import com.box.l10n.mojito.service.jsonconfiglocalization.StatsigJsonConfigLocalizationService.StatsigPushInput;
import com.box.l10n.mojito.service.jsonconfiglocalization.StatsigJsonConfigLocalizationService.StatsigPushResult;
import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class StatsigJsonConfigLocalizationServiceTest {

  private final ObjectMapper objectMapper = ObjectMapper.withNoFailOnUnknownProperties();

  @Mock private JsonConfigLocalizationService jsonConfigLocalizationService;
  @Mock private JsonConfigLocalizationProcessorService jsonConfigLocalizationProcessorService;
  @Mock private HttpClient httpClient;

  private StatsigJsonConfigLocalizationService service;
  private AtomicInteger patchCount;
  private List<String> patchBodies;

  @Before
  public void setup() throws Exception {
    patchCount = new AtomicInteger();
    patchBodies = new ArrayList<>();
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenAnswer(
            invocation -> {
              HttpRequest request = invocation.getArgument(0);
              if ("PATCH".equals(request.method())) {
                patchCount.incrementAndGet();
                patchBodies.add(requestBody(request));
                return response("{\"data\":{\"updated\":true}}");
              }
              return response(
                  """
                  {
                    "data": {
                      "name": "config-1",
                      "defaultValue": {
                        "message": "hello",
                        "items": [1, 2]
                      }
                    }
                  }
                  """);
            });

    StatsigJsonConfigLocalizationProperties properties =
        new StatsigJsonConfigLocalizationProperties();
    properties.setApiKey("console-test");
    properties.setBaseUrl("http://localhost/console/v1");
    service =
        new StatsigJsonConfigLocalizationService(
            objectMapper,
            jsonConfigLocalizationService,
            jsonConfigLocalizationProcessorService,
            properties,
            httpClient);
  }

  @Test
  public void pushSkipsPatchWhenStatsigConfigAlreadyMatchesExport() {
    when(jsonConfigLocalizationService.getByRepositoryId(7L)).thenReturn(localizationSetup());
    when(jsonConfigLocalizationProcessorService.exportForSetup(1L))
        .thenReturn(new ExportResult("{\"items\":[1,2],\"message\":\"hello\"}", List.of()));

    StatsigPushResult result = service.push(7L, new StatsigPushInput("config-1", false));

    assertThat(result.skipped()).isTrue();
    assertThat(result.warnings())
        .contains("Statsig config already matches Mojito output; skipped value push.");
    assertThat(patchCount.get()).isZero();
  }

  @Test
  public void pushPatchesStatsigWhenExportDiffersFromCurrentConfig() {
    when(jsonConfigLocalizationService.getByRepositoryId(7L)).thenReturn(localizationSetup());
    when(jsonConfigLocalizationProcessorService.exportForSetup(1L))
        .thenReturn(new ExportResult("{\"message\":\"updated\"}", List.of()));

    StatsigPushResult result = service.push(7L, new StatsigPushInput("config-1", false));

    assertThat(result.skipped()).isFalse();
    assertThat(patchCount.get()).isEqualTo(1);
  }

  @Test
  public void pushUpdatesStatsigSchemaBeforeDefaultValueWhenRequested() {
    when(jsonConfigLocalizationService.getByRepositoryId(7L))
        .thenReturn(localizationSetup("{\"type\":\"object\"}"));
    when(jsonConfigLocalizationProcessorService.exportForSetup(1L))
        .thenReturn(new ExportResult("{\"message\":\"updated\"}", List.of()));

    StatsigPushResult result = service.push(7L, new StatsigPushInput("config-1", false, true));

    assertThat(result.schemaUpdated()).isTrue();
    assertThat(result.skipped()).isFalse();
    assertThat(patchBodies).hasSize(2);
    assertThat(patchBodies.get(0)).contains("\"schema\":\"{\\\"type\\\":\\\"object\\\"}\"");
    assertThat(patchBodies.get(1)).contains("\"defaultValue\"");
  }

  @Test
  public void pushCanUpdateStatsigSchemaWithoutPushingConfig() {
    when(jsonConfigLocalizationService.getByRepositoryId(7L))
        .thenReturn(localizationSetup("{\"type\":\"object\"}"));

    StatsigPushResult result =
        service.push(7L, new StatsigPushInput("config-1", false, true, false));

    assertThat(result.schemaUpdated()).isTrue();
    assertThat(result.skipped()).isTrue();
    assertThat(result.warnings()).contains("Pushed Statsig schema from Mojito schema.");
    assertThat(patchBodies).hasSize(1);
    assertThat(patchBodies.get(0)).contains("\"schema\":\"{\\\"type\\\":\\\"object\\\"}\"");
    assertThat(patchBodies.get(0)).doesNotContain("\"defaultValue\"");
  }

  private JsonConfigLocalization localizationSetup() {
    return localizationSetup(null);
  }

  private JsonConfigLocalization localizationSetup(String schemaJson) {
    return new JsonConfigLocalization(
        1L,
        null,
        null,
        "config-1",
        new RepositoryRef(7L, "repo", "en", 1),
        "json-config-localization/strings.json",
        JsonConfigLocalizationService.PROVIDER_STATSIG,
        "config-1",
        null,
        schemaJson,
        "{}",
        "{}",
        "{}",
        false,
        null,
        null,
        null);
  }

  private HttpResponse<String> response(String body) {
    @SuppressWarnings("unchecked")
    HttpResponse<String> response = org.mockito.Mockito.mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(200);
    when(response.body()).thenReturn(body);
    return response;
  }

  private String requestBody(HttpRequest request) {
    Optional<HttpRequest.BodyPublisher> bodyPublisher = request.bodyPublisher();
    if (bodyPublisher.isEmpty()) {
      return "";
    }

    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    CompletableFuture<Void> complete = new CompletableFuture<>();
    bodyPublisher
        .get()
        .subscribe(
            new Flow.Subscriber<>() {
              private Flow.Subscription subscription;

              @Override
              public void onSubscribe(Flow.Subscription subscription) {
                this.subscription = subscription;
                subscription.request(1);
              }

              @Override
              public void onNext(ByteBuffer item) {
                byte[] bytes = new byte[item.remaining()];
                item.get(bytes);
                outputStream.writeBytes(bytes);
                subscription.request(1);
              }

              @Override
              public void onError(Throwable throwable) {
                complete.completeExceptionally(throwable);
              }

              @Override
              public void onComplete() {
                complete.complete(null);
              }
            });
    complete.join();
    return outputStream.toString(StandardCharsets.UTF_8);
  }
}
