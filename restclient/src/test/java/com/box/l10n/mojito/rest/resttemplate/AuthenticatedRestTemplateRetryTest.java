package com.box.l10n.mojito.rest.resttemplate;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.box.l10n.mojito.rest.client.AssetClient;
import com.box.l10n.mojito.rest.client.PollableTaskClient;
import com.box.l10n.mojito.rest.client.exception.PollableTaskExecutionException;
import com.box.l10n.mojito.rest.entity.LocalizedAssetBody;
import com.box.l10n.mojito.rest.entity.PollableTask;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClientException;

/** Exercises the real HTTP stack so hidden Apache retries cannot multiply the policy. */
public class AuthenticatedRestTemplateRetryTest {
  WireMockServer server;
  AuthenticatedRestTemplate client;
  GetRequestRetryTest.FakeRetry retry;

  @Before
  public void before() {
    server = new WireMockServer(0);
    server.start();
    client =
        new AuthenticatedRestTemplate() {
          @Override
          public String getURIForResource(String path) {
            return server.baseUrl() + path;
          }
        };
    client.restTemplate = new CookieStoreRestTemplate();
    retry = new GetRequestRetryTest.FakeRetry();
    client.getRequestRetry = retry;
  }

  @After
  public void after() throws Exception {
    ((org.springframework.http.client.HttpComponentsClientHttpRequestFactory)
            client.restTemplate.getRequestFactory())
        .destroy();
    server.stop();
  }

  @Test
  public void retriesEveryGetOverloadWithoutChangingQuery() {
    recover("/plain", "ok");
    recover("/query?name=a%20b", "ok");
    recover("/entity", "ok");
    recover("/generic?name=a%20b", "ok");
    assertThat(client.getForObject("/plain", String.class)).isEqualTo("ok");
    assertThat(
            client.getForObjectWithQueryStringParams("/query", String.class, Map.of("name", "a b")))
        .isEqualTo("ok");
    assertThat(client.getForEntity("/entity", String.class).getBody()).isEqualTo("ok");
    assertThat(
            client
                .getForEntityWithQueryParams(
                    "/generic", new ParameterizedTypeReference<String>() {}, Map.of("name", "a b"))
                .getBody())
        .isEqualTo("ok");
    assertThat(retry.delays).hasSize(4);
    assertThat(server.getAllServeEvents()).hasSize(8);
  }

  @Test
  public void resumesTheSameTaskOutputWithoutResubmittingLocalization() throws Exception {
    server.stubFor(
        post(urlEqualTo("/api/assets/1/localized/parallel")).willReturn(okJson("{\"id\":123}")));
    recover("/api/pollableTasks/123", "{\"id\":123,\"allFinished\":true,\"subTasks\":[]}");
    recover("/api/pollableTasks/123/output", "{\"output\":\"ready\"}");
    AssetClient assets = new AssetClient();
    PollableTaskClient tasks = new PollableTaskClient();
    ReflectionTestUtils.setField(assets, "authenticatedRestTemplate", client);
    ReflectionTestUtils.setField(tasks, "authenticatedRestTemplate", client);

    PollableTask task =
        assets.getLocalizedAssetForContentParallel(
            1L,
            "source",
            List.of(),
            Map.of(),
            null,
            List.of(),
            LocalizedAssetBody.Status.ALL,
            LocalizedAssetBody.InheritanceMode.USE_PARENT,
            null);
    tasks.waitForPollableTask(task.getId(), 1_000);
    assertThat(tasks.getPollableTaskOutput(task.getId())).isEqualTo("{\"output\":\"ready\"}");

    server.verify(1, postRequestedFor(urlEqualTo("/api/assets/1/localized/parallel")));
    server.verify(2, getRequestedFor(urlEqualTo("/api/pollableTasks/123")));
    server.verify(2, getRequestedFor(urlEqualTo("/api/pollableTasks/123/output")));
  }

  @Test
  public void resetConnectionRecoversThroughTheSameGetPolicy() {
    server.stubFor(
        get(urlEqualTo("/reset"))
            .inScenario("reset")
            .whenScenarioStateIs(Scenario.STARTED)
            .willSetStateTo("ready")
            .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
    server.stubFor(
        get(urlEqualTo("/reset"))
            .inScenario("reset")
            .whenScenarioStateIs("ready")
            .willReturn(ok("body")));
    assertThat(client.getForObject("/reset", String.class)).isEqualTo("body");
    assertThat(retry.delays).hasSize(1);
    server.verify(2, getRequestedFor(urlEqualTo("/reset")));
  }

  @Test
  public void malformedJsonIsNotRetried() {
    server.stubFor(get(urlEqualTo("/invalid")).willReturn(okJson("{broken")));
    assertThatThrownBy(() -> client.getForObject("/invalid", PollableTask.class))
        .isInstanceOf(RestClientException.class);
    server.verify(1, getRequestedFor(urlEqualTo("/invalid")));
    assertThat(retry.delays).isEmpty();
  }

  @Test
  public void terminalHttpErrorsAreNotRetried() {
    for (int status : List.of(400, 401, 403, 404, 422, 500)) {
      String path = "/failure/" + status;
      server.stubFor(
          get(urlEqualTo(path))
              .willReturn(aResponse().withStatus(status).withBody("original error")));
      assertThatThrownBy(() -> client.getForObject(path, String.class))
          .isInstanceOf(RestClientException.class)
          .hasMessageContaining("original error");
      server.verify(1, getRequestedFor(urlEqualTo(path)));
    }
    assertThat(retry.delays).isEmpty();
  }

  @Test
  public void stringUrlsRetainRestTemplateEncoding() {
    recover("/path%20with%20spaces", "ok");
    assertThat(client.getForObject("/path with spaces", String.class)).isEqualTo("ok");
    server.verify(2, getRequestedFor(urlEqualTo("/path%20with%20spaces")));
  }

  @Test
  public void truncatedOutputBodyIsRetried() throws Exception {
    var requests = new java.util.concurrent.atomic.AtomicInteger();
    var httpServer =
        com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    httpServer.createContext(
        "/output",
        exchange -> {
          byte[] body = "complete output".getBytes(java.nio.charset.StandardCharsets.UTF_8);
          boolean incomplete = requests.getAndIncrement() == 0;
          exchange.sendResponseHeaders(200, incomplete ? body.length + 100 : body.length);
          try {
            exchange.getResponseBody().write(body);
          } finally {
            exchange.close();
          }
        });
    httpServer.start();
    try {
      String url = "http://127.0.0.1:" + httpServer.getAddress().getPort() + "/output";
      assertThat(retry.execute(url, () -> client.restTemplate.getForObject(url, String.class)))
          .isEqualTo("complete output");
      assertThat(requests).hasValue(2);
      assertThat(retry.delays).hasSize(1);
    } finally {
      httpServer.stop(0);
    }
  }

  @Test
  public void failedServerTaskRemainsTerminal() {
    server.stubFor(
        get(urlEqualTo("/api/pollableTasks/123"))
            .willReturn(
                okJson(
                    "{\"id\":123,\"allFinished\":true,\"subTasks\":[],\"errorMessage\":{\"message\":\"invalid translation\"}}")));
    PollableTaskClient tasks = new PollableTaskClient();
    ReflectionTestUtils.setField(tasks, "authenticatedRestTemplate", client);
    assertThatThrownBy(() -> tasks.waitForPollableTask(123L))
        .isInstanceOf(PollableTaskExecutionException.class)
        .hasMessage("invalid translation");
    server.verify(1, getRequestedFor(urlEqualTo("/api/pollableTasks/123")));
    assertThat(retry.delays).isEmpty();
  }

  @Test
  public void writesAreNeverReplayedOnTransientStatuses() {
    for (int status : List.of(429, 503)) {
      String path = "/write/" + status;
      server.stubFor(any(urlEqualTo(path)).willReturn(aResponse().withStatus(status)));
      assertThatThrownBy(() -> client.postForObject(path, "body", String.class))
          .isInstanceOf(RestClientException.class);
      assertThatThrownBy(() -> client.postForEntity(path, "body", String.class))
          .isInstanceOf(RestClientException.class);
      assertThatThrownBy(() -> client.putForObject(path, "body", String.class))
          .isInstanceOf(RestClientException.class);
      assertThatThrownBy(() -> client.put(path, "body")).isInstanceOf(RestClientException.class);
      assertThatThrownBy(() -> client.patch(path, "body")).isInstanceOf(RestClientException.class);
      assertThatThrownBy(() -> client.delete(path)).isInstanceOf(RestClientException.class);
      assertThatThrownBy(() -> client.deleteForObject(path, HttpEntity.EMPTY, String.class))
          .isInstanceOf(RestClientException.class);
      server.verify(2, postRequestedFor(urlEqualTo(path)));
      server.verify(2, putRequestedFor(urlEqualTo(path)));
      server.verify(1, patchRequestedFor(urlEqualTo(path)));
      server.verify(2, deleteRequestedFor(urlEqualTo(path)));
    }
    assertThat(retry.delays).isEmpty();
  }

  private void recover(String path, String body) {
    server.stubFor(
        get(urlEqualTo(path))
            .inScenario(path)
            .whenScenarioStateIs(Scenario.STARTED)
            .willSetStateTo("ready")
            .willReturn(aResponse().withStatus(503)));
    server.stubFor(
        get(urlEqualTo(path))
            .inScenario(path)
            .whenScenarioStateIs("ready")
            .willReturn(okJson(body)));
  }
}
