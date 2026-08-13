package com.box.l10n.mojito.service.searchindex;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.tm.TMTextUnitVariantRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SearchIndexService {

  private final SearchIndexConfigurationProperties properties;
  private final HttpClient httpClient;
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final TMTextUnitVariantRepository tmTextUnitVariantRepository;

  @Autowired
  public SearchIndexService(
      SearchIndexConfigurationProperties properties,
      TMTextUnitVariantRepository tmTextUnitVariantRepository) {
    this(
        properties,
        tmTextUnitVariantRepository,
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(properties.getConnectTimeoutSeconds()))
            .build());
  }

  SearchIndexService(
      SearchIndexConfigurationProperties properties,
      TMTextUnitVariantRepository tmTextUnitVariantRepository,
      HttpClient httpClient) {
    this.properties = properties;
    this.tmTextUnitVariantRepository = tmTextUnitVariantRepository;
    this.httpClient = httpClient;
  }

  public record SearchIndexStatus(
      boolean enabled,
      String baseUrl,
      String indexName,
      boolean reachable,
      boolean indexExists,
      String clusterStatus,
      Integer documentCount,
      String detail) {}

  public record SearchIndexReindexRequest(
      List<Long> repositoryIds, Integer pageSize, Integer bulkSize) {}

  public record SearchIndexReindexResult(
      String indexName,
      List<Long> repositoryIds,
      int pageSize,
      int bulkSize,
      long scannedDocuments,
      long indexedDocuments,
      long failedDocuments,
      Long lastProcessedVariantId,
      String detail) {}

  public record SearchIndexReindexProgress(
      String status,
      String indexName,
      List<Long> repositoryIds,
      int pageSize,
      int bulkSize,
      long totalDocuments,
      long scannedDocuments,
      long indexedDocuments,
      long failedDocuments,
      Long lastProcessedVariantId,
      String detail) {

    SearchIndexReindexProgress withStatus(String nextStatus, String nextDetail) {
      return new SearchIndexReindexProgress(
          nextStatus,
          indexName,
          repositoryIds,
          pageSize,
          bulkSize,
          totalDocuments,
          scannedDocuments,
          indexedDocuments,
          failedDocuments,
          lastProcessedVariantId,
          nextDetail);
    }
  }

  public record SearchIndexSearchRequest(
      String query,
      List<Long> repositoryIds,
      List<String> localeTags,
      Boolean currentOnly,
      Integer limit) {}

  public record SearchIndexSearchHit(
      double score,
      Long tmTextUnitVariantId,
      Long tmTextUnitId,
      Long repositoryId,
      String repositoryName,
      Long assetId,
      String assetPath,
      String sourceLocaleTag,
      String localeTag,
      String name,
      String source,
      String target,
      String status,
      boolean current,
      boolean assetDeleted) {}

  public record SearchIndexSearchResult(
      String indexName, int limit, boolean currentOnly, List<SearchIndexSearchHit> hits) {}

  public SearchIndexStatus getStatus() {
    if (!properties.isEnabled()) {
      return new SearchIndexStatus(
          false,
          properties.getBaseUrl(),
          properties.getIndexName(),
          false,
          false,
          null,
          null,
          "Search index is disabled");
    }

    try {
      JsonNode health = sendJson("GET", "/_cluster/health", null);
      int indexStatus = send("HEAD", "/" + properties.getIndexName(), null).statusCode();
      if (indexStatus != 200 && indexStatus != 404) {
        throw new IOException("Index check failed with status " + indexStatus);
      }
      boolean indexExists = indexStatus == 200;
      Integer documentCount = null;
      if (indexExists) {
        JsonNode count = sendJson("GET", "/" + properties.getIndexName() + "/_count", null);
        documentCount = count.path("count").isIntegralNumber() ? count.path("count").asInt() : null;
      }

      return new SearchIndexStatus(
          true,
          properties.getBaseUrl(),
          properties.getIndexName(),
          true,
          indexExists,
          health.path("status").asText(null),
          documentCount,
          null);
    } catch (IOException | InterruptedException ex) {
      if (ex instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      return new SearchIndexStatus(
          true,
          properties.getBaseUrl(),
          properties.getIndexName(),
          false,
          false,
          null,
          null,
          ex.getMessage());
    }
  }

  public SearchIndexStatus bootstrapIndex() {
    requireEnabled();
    try {
      HttpResponse<String> headResponse = send("HEAD", "/" + properties.getIndexName(), null);
      if (headResponse.statusCode() == 404) {
        sendJson("PUT", "/" + properties.getIndexName(), buildIndexDefinition());
      } else if (headResponse.statusCode() >= 300) {
        throw new IOException("Index check failed with status " + headResponse.statusCode());
      }
      return getStatus();
    } catch (IOException | InterruptedException ex) {
      if (ex instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw new ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_GATEWAY,
          "Failed to bootstrap search index: " + ex.getMessage(),
          ex);
    }
  }

  public SearchIndexReindexResult reindex(SearchIndexReindexRequest request) {
    return reindex(request, progress -> {});
  }

  SearchIndexReindexProgress queuedReindexProgress(SearchIndexReindexRequest request) {
    int pageSize = sanitizePageSize(request != null ? request.pageSize() : null);
    int bulkSize = sanitizeBulkSize(request != null ? request.bulkSize() : null, pageSize);
    return new SearchIndexReindexProgress(
        "QUEUED",
        properties.getIndexName(),
        normalizeRepositoryIds(request != null ? request.repositoryIds() : null),
        pageSize,
        bulkSize,
        0,
        0,
        0,
        0,
        null,
        null);
  }

  SearchIndexReindexResult reindex(
      SearchIndexReindexRequest request, Consumer<SearchIndexReindexProgress> progressCallback) {
    requireEnabled();
    bootstrapIndex();

    int pageSize = sanitizePageSize(request != null ? request.pageSize() : null);
    int bulkSize = sanitizeBulkSize(request != null ? request.bulkSize() : null, pageSize);
    List<Long> repositoryIds =
        normalizeRepositoryIds(request != null ? request.repositoryIds() : null);
    boolean allRepositories = repositoryIds.isEmpty();
    List<Long> repositoryQueryIds = allRepositories ? List.of(-1L) : repositoryIds;
    long totalDocuments =
        tmTextUnitVariantRepository.countSearchIndexRows(repositoryQueryIds, allRepositories);

    long scannedDocuments = 0;
    long indexedDocuments = 0;
    long failedDocuments = 0;
    Long lastProcessedVariantId = null;
    List<String> failureReasons = new ArrayList<>();

    progressCallback.accept(
        new SearchIndexReindexProgress(
            "RUNNING",
            properties.getIndexName(),
            repositoryIds,
            pageSize,
            bulkSize,
            totalDocuments,
            scannedDocuments,
            indexedDocuments,
            failedDocuments,
            lastProcessedVariantId,
            null));

    while (true) {
      List<SearchIndexVariantRow> rows =
          tmTextUnitVariantRepository.findSearchIndexRows(
              repositoryQueryIds,
              allRepositories,
              lastProcessedVariantId,
              PageRequest.of(0, pageSize));
      if (rows.isEmpty()) {
        break;
      }

      scannedDocuments += rows.size();
      for (int start = 0; start < rows.size(); start += bulkSize) {
        int end = Math.min(rows.size(), start + bulkSize);
        BulkResult bulkResult = bulkUpsert(rows.subList(start, end));
        indexedDocuments += bulkResult.indexedCount();
        failedDocuments += bulkResult.failedCount();
        if (failureReasons.size() < 5 && !bulkResult.failureReasons().isEmpty()) {
          failureReasons.addAll(
              bulkResult
                  .failureReasons()
                  .subList(
                      0, Math.min(5 - failureReasons.size(), bulkResult.failureReasons().size())));
        }

        lastProcessedVariantId = rows.get(end - 1).tmTextUnitVariantId();
        progressCallback.accept(
            new SearchIndexReindexProgress(
                "RUNNING",
                properties.getIndexName(),
                repositoryIds,
                pageSize,
                bulkSize,
                totalDocuments,
                scannedDocuments,
                indexedDocuments,
                failedDocuments,
                lastProcessedVariantId,
                failureReasons.isEmpty() ? null : String.join(" | ", failureReasons)));
      }
    }

    try {
      sendJson("POST", "/" + properties.getIndexName() + "/_refresh", Map.of());
    } catch (IOException | InterruptedException ex) {
      if (ex instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw new ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_GATEWAY,
          "Failed to refresh search index after reindex: " + ex.getMessage(),
          ex);
    }

    String detail = failureReasons.isEmpty() ? null : String.join(" | ", failureReasons);
    SearchIndexReindexResult result =
        new SearchIndexReindexResult(
            properties.getIndexName(),
            repositoryIds,
            pageSize,
            bulkSize,
            scannedDocuments,
            indexedDocuments,
            failedDocuments,
            lastProcessedVariantId,
            detail);
    progressCallback.accept(
        new SearchIndexReindexProgress(
            "COMPLETED",
            properties.getIndexName(),
            repositoryIds,
            pageSize,
            bulkSize,
            totalDocuments,
            scannedDocuments,
            indexedDocuments,
            failedDocuments,
            lastProcessedVariantId,
            detail));
    return result;
  }

  public SearchIndexSearchResult search(SearchIndexSearchRequest request) {
    requireEnabled();

    String query = request == null || request.query() == null ? "" : request.query().trim();
    if (query.isEmpty()) {
      throw new ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST, "query must not be empty");
    }

    int limit = sanitizeSearchLimit(request.limit());
    boolean currentOnly = request.currentOnly() == null || request.currentOnly();
    List<Long> repositoryIds = normalizeRepositoryIds(request.repositoryIds());
    List<String> localeTags = normalizeLocaleTags(request.localeTags());

    List<Object> filters = new ArrayList<>();
    if (currentOnly) {
      filters.add(Map.of("term", Map.of("isCurrent", true)));
    }
    if (!repositoryIds.isEmpty()) {
      filters.add(Map.of("terms", Map.of("repositoryId", repositoryIds)));
    }
    if (!localeTags.isEmpty()) {
      filters.add(Map.of("terms", Map.of("localeTag", localeTags)));
    }

    List<String> searchFields =
        new ArrayList<>(
            List.of(
                "name^4",
                "sourceLocalized.*^5",
                "source^2",
                "target^2",
                "comment",
                "targetComment",
                "repositoryName",
                "assetPath"));
    if (localeTags.isEmpty()) {
      searchFields.add("targetLocalized.*^5");
    } else {
      localeTags.stream()
          .map(SearchIndexLanguageAnalyzers::languageKey)
          .distinct()
          .map(language -> "targetLocalized." + language + "^5")
          .forEach(searchFields::add);
    }

    Map<String, Object> queryBody =
        Map.of(
            "size",
            limit,
            "query",
            Map.of(
                "bool",
                Map.of(
                    "must",
                    List.of(
                        Map.of(
                            "multi_match",
                            Map.of(
                                "query",
                                query,
                                "fields",
                                searchFields,
                                "fuzziness",
                                "AUTO",
                                "operator",
                                "and"))),
                    "filter",
                    filters)));

    try {
      JsonNode response = sendJson("POST", "/" + properties.getIndexName() + "/_search", queryBody);
      List<SearchIndexSearchHit> hits = new ArrayList<>();
      for (JsonNode hitNode : response.path("hits").path("hits")) {
        JsonNode sourceNode = hitNode.path("_source");
        hits.add(
            new SearchIndexSearchHit(
                hitNode.path("_score").asDouble(0d),
                longOrNull(sourceNode, "tmTextUnitVariantId"),
                longOrNull(sourceNode, "tmTextUnitId"),
                longOrNull(sourceNode, "repositoryId"),
                textOrNull(sourceNode, "repositoryName"),
                longOrNull(sourceNode, "assetId"),
                textOrNull(sourceNode, "assetPath"),
                textOrNull(sourceNode, "sourceLocaleTag"),
                textOrNull(sourceNode, "localeTag"),
                textOrNull(sourceNode, "name"),
                textOrNull(sourceNode, "source"),
                textOrNull(sourceNode, "target"),
                textOrNull(sourceNode, "status"),
                sourceNode.path("isCurrent").asBoolean(false),
                sourceNode.path("assetDeleted").asBoolean(false)));
      }
      return new SearchIndexSearchResult(properties.getIndexName(), limit, currentOnly, hits);
    } catch (IOException | InterruptedException ex) {
      if (ex instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw new ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_GATEWAY,
          "Failed to search index: " + ex.getMessage(),
          ex);
    }
  }

  void requireEnabled() {
    if (!properties.isEnabled()) {
      throw new ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST, "Search index is disabled");
    }
  }

  private static List<Long> normalizeRepositoryIds(List<Long> repositoryIds) {
    if (repositoryIds == null) {
      return List.of();
    }
    return repositoryIds.stream().filter(Objects::nonNull).distinct().toList();
  }

  private static List<String> normalizeLocaleTags(List<String> localeTags) {
    if (localeTags == null) {
      return List.of();
    }
    return localeTags.stream()
        .filter(Objects::nonNull)
        .filter(localeTag -> !localeTag.isBlank())
        .map(SearchIndexLanguageAnalyzers::normalizeLocaleTag)
        .distinct()
        .toList();
  }

  private HttpResponse<String> send(String method, String path, String jsonBody)
      throws IOException, InterruptedException {
    HttpRequest.Builder requestBuilder =
        HttpRequest.newBuilder()
            .uri(URI.create(properties.getBaseUrl() + path))
            .timeout(Duration.ofSeconds(properties.getRequestTimeoutSeconds()))
            .header("Accept", "application/json");

    if (jsonBody == null) {
      requestBuilder.method(method, HttpRequest.BodyPublishers.noBody());
    } else {
      requestBuilder
          .header(
              "Content-Type", path.equals("/_bulk") ? "application/x-ndjson" : "application/json")
          .method(method, HttpRequest.BodyPublishers.ofString(jsonBody));
    }

    return httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private JsonNode sendJson(String method, String path, Object body)
      throws IOException, InterruptedException {
    String jsonBody = body == null ? null : objectMapper.writeValueAsStringUnchecked(body);
    HttpResponse<String> response = send(method, path, jsonBody);
    if (response.statusCode() >= 300) {
      throw new IOException(
          "Search index request failed with status "
              + response.statusCode()
              + " for "
              + path
              + ": "
              + response.body());
    }
    return objectMapper.readTreeUnchecked(response.body());
  }

  private Map<String, Object> buildIndexDefinition() {
    Map<String, Object> settings = new LinkedHashMap<>();
    settings.put("index.knn", true);
    settings.put("number_of_replicas", 0);
    settings.put(
        "analysis",
        Map.of(
            "analyzer",
            Map.of(
                SearchIndexLanguageAnalyzers.FOLDED_ANALYZER,
                Map.of(
                    "type",
                    "custom",
                    "tokenizer",
                    "standard",
                    "filter",
                    List.of("lowercase", "asciifolding"))),
            "normalizer",
            Map.of("mojito_locale", Map.of("type", "custom", "filter", List.of("lowercase")))));

    Map<String, Object> propertiesMap = new LinkedHashMap<>();
    propertiesMap.put("tmTextUnitVariantId", Map.of("type", "long"));
    propertiesMap.put("tmTextUnitId", Map.of("type", "long"));
    propertiesMap.put("repositoryId", Map.of("type", "long"));
    propertiesMap.put("repositoryName", keywordTextField());
    propertiesMap.put("assetId", Map.of("type", "long"));
    propertiesMap.put("assetPath", keywordTextField());
    propertiesMap.put("localeId", Map.of("type", "long"));
    propertiesMap.put("sourceLocaleTag", normalizedLocaleField());
    propertiesMap.put("localeTag", normalizedLocaleField());
    propertiesMap.put("sourceLanguage", Map.of("type", "keyword"));
    propertiesMap.put("targetLanguage", Map.of("type", "keyword"));
    propertiesMap.put("name", keywordTextField());
    propertiesMap.put("source", foldedTextField());
    propertiesMap.put("target", foldedTextField());
    propertiesMap.put("sourceLocalized", SearchIndexLanguageAnalyzers.localizedTextMapping());
    propertiesMap.put("targetLocalized", SearchIndexLanguageAnalyzers.localizedTextMapping());
    propertiesMap.put("comment", foldedTextField());
    propertiesMap.put("targetComment", foldedTextField());
    propertiesMap.put("status", Map.of("type", "keyword"));
    propertiesMap.put("includedInLocalizedFile", Map.of("type", "boolean"));
    propertiesMap.put("isCurrent", Map.of("type", "boolean"));
    propertiesMap.put("tmTextUnitDeleted", Map.of("type", "boolean"));
    propertiesMap.put("assetDeleted", Map.of("type", "boolean"));
    propertiesMap.put("createdDate", Map.of("type", "date"));
    propertiesMap.put("lastModifiedDate", Map.of("type", "date"));
    propertiesMap.put("createdByUserId", Map.of("type", "long"));
    propertiesMap.put("sourceTmTextUnitId", Map.of("type", "long"));
    propertiesMap.put("sourceTmTextUnitVariantId", Map.of("type", "long"));
    propertiesMap.put("leveragingType", Map.of("type", "keyword"));
    propertiesMap.put("uniqueMatch", Map.of("type", "boolean"));
    propertiesMap.put(
        "embedding",
        Map.of("type", "knn_vector", "dimension", properties.getEmbeddingDimensions()));

    return Map.of("settings", settings, "mappings", Map.of("properties", propertiesMap));
  }

  private Map<String, Object> keywordTextField() {
    return Map.of(
        "type",
        "text",
        "analyzer",
        SearchIndexLanguageAnalyzers.FOLDED_ANALYZER,
        "fields",
        Map.of("keyword", Map.of("type", "keyword", "ignore_above", 512)));
  }

  private Map<String, Object> foldedTextField() {
    return Map.of("type", "text", "analyzer", SearchIndexLanguageAnalyzers.FOLDED_ANALYZER);
  }

  private Map<String, Object> normalizedLocaleField() {
    return Map.of("type", "keyword", "normalizer", "mojito_locale");
  }

  private int sanitizePageSize(Integer pageSize) {
    int configured = properties.getIndexing().getPageSize();
    int value = pageSize == null ? configured : pageSize;
    return Math.max(1, Math.min(5000, value));
  }

  private int sanitizeBulkSize(Integer bulkSize, int pageSize) {
    int configured = properties.getIndexing().getBulkSize();
    int value = bulkSize == null ? configured : bulkSize;
    return Math.max(1, Math.min(pageSize, value));
  }

  private int sanitizeSearchLimit(Integer limit) {
    int defaultLimit = properties.getSearch().getDefaultLimit();
    int maxLimit = properties.getSearch().getMaxLimit();
    int value = limit == null ? defaultLimit : limit;
    return Math.max(1, Math.min(maxLimit, value));
  }

  private BulkResult bulkUpsert(List<SearchIndexVariantRow> rows) {
    StringBuilder ndjson = new StringBuilder();
    for (SearchIndexVariantRow row : rows) {
      ndjson
          .append(
              objectMapper.writeValueAsStringUnchecked(
                  Map.of(
                      "index",
                      Map.of(
                          "_index", properties.getIndexName(), "_id", row.tmTextUnitVariantId()))))
          .append('\n')
          .append(objectMapper.writeValueAsStringUnchecked(toDocument(row)))
          .append('\n');
    }

    try {
      HttpResponse<String> response = send("POST", "/_bulk", ndjson.toString());
      if (response.statusCode() >= 300) {
        throw new IOException(
            "Bulk indexing failed with status " + response.statusCode() + ": " + response.body());
      }
      JsonNode bulkResponse = objectMapper.readTreeUnchecked(response.body());
      long failedCount = 0;
      List<String> failureReasons = new ArrayList<>();
      for (JsonNode itemNode : bulkResponse.path("items")) {
        JsonNode indexNode = itemNode.path("index");
        int status = indexNode.path("status").asInt();
        if (status >= 300) {
          failedCount++;
          if (failureReasons.size() < 5) {
            failureReasons.add(indexNode.path("error").toString());
          }
        }
      }
      return new BulkResult(rows.size() - failedCount, failedCount, failureReasons);
    } catch (IOException | InterruptedException ex) {
      if (ex instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw new ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_GATEWAY,
          "Failed to bulk index search documents: " + ex.getMessage(),
          ex);
    }
  }

  private Map<String, Object> toDocument(SearchIndexVariantRow row) {
    Map<String, Object> document = new LinkedHashMap<>();
    document.put("tmTextUnitVariantId", row.tmTextUnitVariantId());
    document.put("tmTextUnitId", row.tmTextUnitId());
    document.put("repositoryId", row.repositoryId());
    document.put("repositoryName", row.repositoryName());
    document.put("sourceLocaleTag", row.sourceLocaleTag());
    document.put("assetId", row.assetId());
    document.put("assetPath", row.assetPath());
    document.put("localeId", row.localeId());
    document.put("localeTag", row.localeTag());
    document.put("name", row.name());
    document.put("source", row.source());
    document.put("target", row.target());
    String sourceLanguage = SearchIndexLanguageAnalyzers.languageKey(row.sourceLocaleTag());
    String targetLanguage = SearchIndexLanguageAnalyzers.languageKey(row.localeTag());
    document.put("sourceLanguage", sourceLanguage);
    document.put("targetLanguage", targetLanguage);
    document.put("sourceLocalized", localizedText(row.source(), sourceLanguage));
    document.put("targetLocalized", localizedText(row.target(), targetLanguage));
    document.put("comment", row.comment());
    document.put("targetComment", row.targetComment());
    document.put("status", row.status() == null ? null : row.status().name());
    document.put("includedInLocalizedFile", row.includedInLocalizedFile());
    document.put("isCurrent", row.current());
    document.put("tmTextUnitDeleted", false);
    document.put("assetDeleted", row.assetDeleted());
    document.put("createdDate", row.createdDate());
    document.put("createdByUserId", row.createdByUserId());
    document.put("sourceTmTextUnitId", row.sourceTmTextUnitId());
    document.put("sourceTmTextUnitVariantId", row.sourceTmTextUnitVariantId());
    document.put("leveragingType", row.leveragingType());
    document.put("uniqueMatch", row.uniqueMatch());
    return document;
  }

  private Map<String, String> localizedText(String text, String language) {
    return text == null ? Map.of() : Map.of(language, text);
  }

  private Long longOrNull(JsonNode node, String field) {
    JsonNode value = node.path(field);
    return value.isIntegralNumber() ? value.asLong() : null;
  }

  private String textOrNull(JsonNode node, String field) {
    JsonNode value = node.path(field);
    return value.isTextual() ? value.asText() : null;
  }

  private record BulkResult(long indexedCount, long failedCount, List<String> failureReasons) {}
}
