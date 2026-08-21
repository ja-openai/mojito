package com.box.l10n.mojito.service.searchindex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.TMTextUnitVariant;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.searchindex.SearchIndexService.SearchIndexReindexProgress;
import com.box.l10n.mojito.service.searchindex.SearchIndexService.SearchIndexReindexRequest;
import com.box.l10n.mojito.service.searchindex.SearchIndexService.SearchIndexReindexResult;
import com.box.l10n.mojito.service.searchindex.SearchIndexService.SearchIndexSearchRequest;
import com.box.l10n.mojito.service.searchindex.SearchIndexService.SearchIndexSearchResult;
import com.box.l10n.mojito.service.searchindex.SearchIndexService.SearchIndexStatus;
import com.box.l10n.mojito.service.tm.TMTextUnitVariantRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@RunWith(MockitoJUnitRunner.class)
public class SearchIndexServiceTest {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final List<HttpRequest> requests = new ArrayList<>();

  @Mock private HttpClient httpClient;
  @Mock private TMTextUnitVariantRepository tmTextUnitVariantRepository;

  private SearchIndexConfigurationProperties properties;
  private SearchIndexService service;

  @Before
  public void setUp() {
    properties = new SearchIndexConfigurationProperties();
    properties.setEnabled(true);
    service = new SearchIndexService(properties, tmTextUnitVariantRepository, httpClient);
  }

  @Test
  public void reportsDisabledSearchWithoutConnecting() {
    properties.setEnabled(false);

    SearchIndexStatus status = service.getStatus();

    assertThat(status.enabled()).isFalse();
    assertThat(status.reachable()).isFalse();
    assertThat(status.indexName()).isEqualTo("tm-text-unit-variants-v1");
    assertThat(status.detail()).isEqualTo("Search index is disabled");
    verifyNoInteractions(httpClient, tmTextUnitVariantRepository);
  }

  @Test
  public void reportsClusterAndExistingIndexMetrics() throws Exception {
    mockServer(
        request ->
            switch (request.uri().getPath()) {
              case "/_cluster/health" -> response(200, "{\"status\":\"green\"}");
              case "/tm-text-unit-variants-v1" -> response(200, null);
              case "/tm-text-unit-variants-v1/_count" -> response(200, "{\"count\":7}");
              default -> throw new AssertionError(request.uri());
            });

    SearchIndexStatus status = service.getStatus();

    assertThat(status.enabled()).isTrue();
    assertThat(status.reachable()).isTrue();
    assertThat(status.indexExists()).isTrue();
    assertThat(status.clusterStatus()).isEqualTo("green");
    assertThat(status.documentCount()).isEqualTo(7);
  }

  @Test
  public void reportsUnexpectedIndexAccessFailureAsUnavailable() throws Exception {
    mockServer(
        request ->
            request.uri().getPath().equals("/_cluster/health")
                ? response(200, "{\"status\":\"green\"}")
                : response(403, null));

    SearchIndexStatus status = service.getStatus();

    assertThat(status.reachable()).isFalse();
    assertThat(status.indexExists()).isFalse();
    assertThat(status.detail()).contains("403");
  }

  @Test
  public void reportsConnectionFailureWithoutThrowing() throws Exception {
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenThrow(new IOException("connection refused"));

    SearchIndexStatus status = service.getStatus();

    assertThat(status.reachable()).isFalse();
    assertThat(status.detail()).isEqualTo("connection refused");
  }

  @Test
  public void authenticatesEveryRequestWithConfiguredBasicCredentials() throws Exception {
    properties.setBaseUrl("https://localhost:9200");
    properties.setUsername("mojito");
    properties.setPassword("search-password");
    mockServer(
        request ->
            switch (request.uri().getPath()) {
              case "/_cluster/health" -> response(200, "{\"status\":\"green\"}");
              case "/tm-text-unit-variants-v1" -> response(200, null);
              case "/tm-text-unit-variants-v1/_count" -> response(200, "{\"count\":7}");
              default -> throw new AssertionError(request.uri());
            });

    service.getStatus();

    assertThat(requests).hasSize(3);
    assertThat(requests)
        .allSatisfy(
            request ->
                assertThat(request.headers().firstValue("Authorization"))
                    .contains("Basic bW9qaXRvOnNlYXJjaC1wYXNzd29yZA=="));
  }

  @Test
  public void reportsIncompleteAuthenticationConfigurationWithoutConnecting() {
    properties.setUsername("mojito");

    SearchIndexStatus status = service.getStatus();

    assertThat(status.reachable()).isFalse();
    assertThat(status.detail()).contains("username and password must either both be configured");
    verifyNoInteractions(httpClient, tmTextUnitVariantRepository);
  }

  @Test
  public void refusesToSendCredentialsOverPlainHttp() {
    properties.setUsername("mojito");
    properties.setPassword("search-password");

    SearchIndexStatus status = service.getStatus();

    assertThat(status.reachable()).isFalse();
    assertThat(status.detail()).isEqualTo("Search index credentials require an HTTPS base URL");
    verifyNoInteractions(httpClient, tmTextUnitVariantRepository);
  }

  @Test
  public void bootstrapsVectorReadyTranslationMemoryMapping() throws Exception {
    AtomicBoolean indexExists = new AtomicBoolean();
    mockServer(
        request -> {
          String path = request.uri().getPath();
          if (path.equals("/_cluster/health")) {
            return response(200, "{\"status\":\"green\"}");
          }
          if (path.equals("/tm-text-unit-variants-v1/_count")) {
            return response(200, "{\"count\":0}");
          }
          if (request.method().equals("PUT")) {
            indexExists.set(true);
            return response(200, "{\"acknowledged\":true}");
          }
          return response(indexExists.get() ? 200 : 404, null);
        });

    SearchIndexStatus status = service.bootstrapIndex();

    assertThat(status.indexExists()).isTrue();
    HttpRequest createRequest =
        requests.stream()
            .filter(request -> request.method().equals("PUT"))
            .findFirst()
            .orElseThrow();
    JsonNode mapping = objectMapper.readTreeUnchecked(requestBody(createRequest));
    assertThat(mapping.path("settings").path("index.knn").asBoolean()).isTrue();
    assertThat(mapping.path("settings").path("number_of_replicas").asInt()).isZero();
    assertThat(
            mapping
                .path("settings")
                .path("analysis")
                .path("analyzer")
                .path("mojito_folded")
                .path("filter"))
        .hasSize(2);
    assertThat(mapping.path("mappings").path("properties").path("source").path("type").asText())
        .isEqualTo("text");
    assertThat(
            mapping
                .path("mappings")
                .path("properties")
                .path("sourceLocalized")
                .path("properties")
                .path("en")
                .path("analyzer")
                .asText())
        .isEqualTo("english");
    assertThat(
            mapping
                .path("mappings")
                .path("properties")
                .path("targetLocalized")
                .path("properties")
                .path("ja")
                .path("analyzer")
                .asText())
        .isEqualTo("kuromoji");
    assertThat(
            mapping
                .path("mappings")
                .path("properties")
                .path("targetLocalized")
                .path("properties")
                .path("ko")
                .path("analyzer")
                .asText())
        .isEqualTo("nori");
    assertThat(
            mapping
                .path("mappings")
                .path("properties")
                .path("targetLocalized")
                .path("properties")
                .path("zh_hans")
                .path("analyzer")
                .asText())
        .isEqualTo("smartcn");
    assertThat(
            mapping
                .path("mappings")
                .path("properties")
                .path("targetLocalized")
                .path("properties")
                .path("zh_hant")
                .path("analyzer")
                .asText())
        .isEqualTo("icu_analyzer");
    assertThat(
            mapping
                .path("mappings")
                .path("properties")
                .path("targetLocalized")
                .path("properties")
                .path("pt_br")
                .path("analyzer")
                .asText())
        .isEqualTo("brazilian");
    assertThat(mapping.path("mappings").path("properties").path("embedding").path("type").asText())
        .isEqualTo("knn_vector");
    assertThat(
            mapping.path("mappings").path("properties").path("embedding").path("dimension").asInt())
        .isEqualTo(1536);
  }

  @Test
  public void reindexesRepositoryVariantsWithNdjsonAndTracksPartialFailures() throws Exception {
    SearchIndexVariantRow first = variant(11L);
    SearchIndexVariantRow second = variant(12L);
    when(tmTextUnitVariantRepository.findSearchIndexRows(
            eq(List.of(17L, 19L)), eq(false), isNull(), any(Pageable.class)))
        .thenReturn(List.of(first, second));
    when(tmTextUnitVariantRepository.findSearchIndexRows(
            eq(List.of(17L, 19L)), eq(false), eq(12L), any(Pageable.class)))
        .thenReturn(List.of());
    when(tmTextUnitVariantRepository.countSearchIndexRows(List.of(17L, 19L), false)).thenReturn(2L);

    mockServer(
        request ->
            switch (request.uri().getPath()) {
              case "/_cluster/health" -> response(200, "{\"status\":\"green\"}");
              case "/tm-text-unit-variants-v1" -> response(200, null);
              case "/tm-text-unit-variants-v1/_count" -> response(200, "{\"count\":0}");
              case "/_bulk" ->
                  response(
                      200,
                      """
                      {"items":[{"index":{"status":201}},
                      {"index":{"status":400,"error":{"reason":"invalid document"}}}]}
                      """);
              case "/tm-text-unit-variants-v1/_refresh" -> response(200, "{\"_shards\":{}}");
              default -> throw new AssertionError(request.uri());
            });

    List<SearchIndexReindexProgress> progressUpdates = new ArrayList<>();
    SearchIndexReindexResult result =
        service.reindex(
            new SearchIndexReindexRequest(List.of(17L, 19L, 17L), 5, 2), progressUpdates::add);

    assertThat(result.repositoryIds()).containsExactly(17L, 19L);
    assertThat(result.scannedDocuments()).isEqualTo(2);
    assertThat(result.indexedDocuments()).isEqualTo(1);
    assertThat(result.failedDocuments()).isEqualTo(1);
    assertThat(result.lastProcessedVariantId()).isEqualTo(12L);
    assertThat(result.detail()).contains("invalid document");
    assertThat(progressUpdates)
        .extracting(SearchIndexReindexProgress::status)
        .containsExactly("RUNNING", "RUNNING", "COMPLETED");
    assertThat(progressUpdates.getFirst().totalDocuments()).isEqualTo(2L);
    assertThat(progressUpdates.getLast().indexedDocuments()).isEqualTo(1L);
    assertThat(progressUpdates.getLast().failedDocuments()).isEqualTo(1L);

    HttpRequest bulkRequest =
        requests.stream()
            .filter(request -> request.uri().getPath().equals("/_bulk"))
            .findFirst()
            .orElseThrow();
    assertThat(bulkRequest.headers().firstValue("Content-Type")).contains("application/x-ndjson");
    assertThat(requestBody(bulkRequest))
        .contains(
            "\"_id\":11",
            "\"_id\":12",
            "\"sourceLocaleTag\":\"en-US\"",
            "\"sourceLanguage\":\"en\"",
            "\"targetLanguage\":\"fr\"",
            "\"sourceLocalized\":{\"en\":\"Checkout\"}",
            "\"targetLocalized\":{\"fr\":\"Paiement\"}");
    verify(tmTextUnitVariantRepository)
        .findSearchIndexRows(eq(List.of(17L, 19L)), eq(false), eq(12L), any(Pageable.class));
  }

  @Test
  public void createsQueuedReindexProgressWithoutQueryingTheDatabase() {
    SearchIndexReindexProgress progress =
        service.queuedReindexProgress(new SearchIndexReindexRequest(List.of(17L, 17L), 3, 10));

    assertThat(progress.status()).isEqualTo("QUEUED");
    assertThat(progress.repositoryIds()).containsExactly(17L);
    assertThat(progress.pageSize()).isEqualTo(3);
    assertThat(progress.bulkSize()).isEqualTo(3);
    assertThat(progress.totalDocuments()).isZero();
    verifyNoInteractions(tmTextUnitVariantRepository, httpClient);
  }

  @Test
  public void runsBoundedFuzzySearchWithRepositoryAndLocaleFilters() throws Exception {
    mockServer(
        request ->
            response(
                200,
                """
                {"hits":{"hits":[{"_score":4.5,"_source":{
                  "tmTextUnitVariantId":11,"tmTextUnitId":21,"repositoryId":17,
                  "repositoryName":"checkout","sourceLocaleTag":"en-US","localeTag":"fr",
                  "name":"checkout.button",
                  "source":"Checkout","target":"Paiement","status":"APPROVED",
                  "isCurrent":true,"assetDeleted":false
                }}]}}
                """));

    SearchIndexSearchResult result =
        service.search(
            new SearchIndexSearchRequest(
                "checkout", List.of(17L, 19L), List.of("fr", "ja"), true, 250));

    assertThat(result.limit()).isEqualTo(100);
    assertThat(result.hits())
        .singleElement()
        .satisfies(
            hit -> {
              assertThat(hit.sourceLocaleTag()).isEqualTo("en-US");
              assertThat(hit.target()).isEqualTo("Paiement");
            });
    JsonNode searchBody = objectMapper.readTreeUnchecked(requestBody(requests.getFirst()));
    assertThat(searchBody.path("size").asInt()).isEqualTo(100);
    JsonNode multiMatch =
        searchBody.path("query").path("bool").path("must").get(0).path("multi_match");
    assertThat(multiMatch.path("query").asText()).isEqualTo("checkout");
    assertThat(multiMatch.path("fuzziness").asText()).isEqualTo("AUTO");
    assertThat(multiMatch.path("fields"))
        .anyMatch(field -> field.asText().equals("sourceLocalized.*^5"))
        .anyMatch(field -> field.asText().equals("targetLocalized.fr^5"))
        .anyMatch(field -> field.asText().equals("targetLocalized.ja^5"));
    JsonNode filters = searchBody.path("query").path("bool").path("filter");
    assertThat(filters).hasSize(3);
    assertThat(filters)
        .anyMatch(
            filter ->
                filter.path("terms").path("repositoryId").size() == 2
                    && filter.path("terms").path("repositoryId").get(0).asLong() == 17L
                    && filter.path("terms").path("repositoryId").get(1).asLong() == 19L)
        .anyMatch(
            filter ->
                filter.path("terms").path("localeTag").size() == 2
                    && filter.path("terms").path("localeTag").get(0).asText().equals("fr")
                    && filter.path("terms").path("localeTag").get(1).asText().equals("ja"));
  }

  @Test
  public void normalizesRegionalLocaleFiltersAndUsesBrazilianAnalyzer() throws Exception {
    mockServer(request -> response(200, "{\"hits\":{\"hits\":[]}}"));

    service.search(
        new SearchIndexSearchRequest("café", null, List.of("PT_br", "pt-BR"), true, null));

    JsonNode searchBody = objectMapper.readTreeUnchecked(requestBody(requests.getFirst()));
    JsonNode filters = searchBody.path("query").path("bool").path("filter");
    assertThat(filters)
        .anyMatch(
            filter ->
                filter.path("terms").path("localeTag").size() == 1
                    && filter.path("terms").path("localeTag").get(0).asText().equals("pt-br"));
    JsonNode fields =
        searchBody
            .path("query")
            .path("bool")
            .path("must")
            .get(0)
            .path("multi_match")
            .path("fields");
    assertThat(fields).anyMatch(field -> field.asText().equals("targetLocalized.pt_br^5"));
  }

  @Test
  public void routesChineseSearchesToScriptSpecificAnalyzers() throws Exception {
    mockServer(request -> response(200, "{\"hits\":{\"hits\":[]}}"));

    service.search(
        new SearchIndexSearchRequest("中文", null, List.of("zh-Hant-TW", "zh-CN"), true, null));

    JsonNode fields =
        objectMapper
            .readTreeUnchecked(requestBody(requests.getFirst()))
            .path("query")
            .path("bool")
            .path("must")
            .get(0)
            .path("multi_match")
            .path("fields");
    assertThat(fields)
        .anyMatch(field -> field.asText().equals("targetLocalized.zh_hant^5"))
        .anyMatch(field -> field.asText().equals("targetLocalized.zh_hans^5"));
  }

  @Test
  public void usesFoldedFallbackForLanguagesWithoutInstalledAnalyzers() throws Exception {
    mockServer(request -> response(200, "{\"hits\":{\"hits\":[]}}"));

    service.search(new SearchIndexSearchRequest("ትርጉም", null, List.of("am"), true, null));

    JsonNode fields =
        objectMapper
            .readTreeUnchecked(requestBody(requests.getFirst()))
            .path("query")
            .path("bool")
            .path("must")
            .get(0)
            .path("multi_match")
            .path("fields");
    assertThat(fields).anyMatch(field -> field.asText().equals("targetLocalized.default^5"));
  }

  @Test
  public void rejectsEmptySearchQueries() {
    assertThatThrownBy(
            () -> service.search(new SearchIndexSearchRequest("  ", null, null, null, null)))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    verifyNoInteractions(httpClient);
  }

  private void mockServer(RequestHandler handler) throws Exception {
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenAnswer(
            invocation -> {
              HttpRequest request = invocation.getArgument(0);
              requests.add(request);
              return handler.respond(request);
            });
  }

  private HttpResponse<String> response(int status, String body) {
    @SuppressWarnings("unchecked")
    HttpResponse<String> response = mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(status);
    if (body != null) {
      when(response.body()).thenReturn(body);
    }
    return response;
  }

  private SearchIndexVariantRow variant(long id) {
    return new SearchIndexVariantRow(
        id,
        21L,
        17L,
        "checkout",
        "en-US",
        31L,
        "checkout.json",
        41L,
        "fr",
        "checkout.button",
        "Checkout",
        "Paiement",
        "source comment",
        "target comment",
        TMTextUnitVariant.Status.APPROVED,
        true,
        true,
        false,
        ZonedDateTime.parse("2026-08-05T12:00:00Z"),
        61L,
        null,
        null,
        null,
        false);
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

  @FunctionalInterface
  private interface RequestHandler {
    HttpResponse<String> respond(HttpRequest request);
  }
}
