package com.box.l10n.mojito.service.searchindex;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties("l10n.search-index")
public class SearchIndexConfigurationProperties {
  boolean enabled = false;
  String baseUrl = "http://localhost:9200";
  String indexName = "tm-text-unit-variants-v1";
  String username;
  String password;
  int connectTimeoutSeconds = 2;
  int requestTimeoutSeconds = 10;
  int embeddingDimensions = 1536;
  IndexingProperties indexing = new IndexingProperties();
  SearchProperties search = new SearchProperties();

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public String getBaseUrl() {
    return baseUrl;
  }

  public void setBaseUrl(String baseUrl) {
    this.baseUrl = baseUrl;
  }

  public String getIndexName() {
    return indexName;
  }

  public void setIndexName(String indexName) {
    this.indexName = indexName;
  }

  public String getUsername() {
    return username;
  }

  public void setUsername(String username) {
    this.username = username;
  }

  public String getPassword() {
    return password;
  }

  public void setPassword(String password) {
    this.password = password;
  }

  public int getConnectTimeoutSeconds() {
    return connectTimeoutSeconds;
  }

  public void setConnectTimeoutSeconds(int connectTimeoutSeconds) {
    this.connectTimeoutSeconds = connectTimeoutSeconds;
  }

  public int getRequestTimeoutSeconds() {
    return requestTimeoutSeconds;
  }

  public void setRequestTimeoutSeconds(int requestTimeoutSeconds) {
    this.requestTimeoutSeconds = requestTimeoutSeconds;
  }

  public int getEmbeddingDimensions() {
    return embeddingDimensions;
  }

  public void setEmbeddingDimensions(int embeddingDimensions) {
    this.embeddingDimensions = embeddingDimensions;
  }

  public IndexingProperties getIndexing() {
    return indexing;
  }

  public void setIndexing(IndexingProperties indexing) {
    this.indexing = indexing;
  }

  public SearchProperties getSearch() {
    return search;
  }

  public void setSearch(SearchProperties search) {
    this.search = search;
  }

  public static class IndexingProperties {
    int pageSize = 500;
    int bulkSize = 200;

    public int getPageSize() {
      return pageSize;
    }

    public void setPageSize(int pageSize) {
      this.pageSize = pageSize;
    }

    public int getBulkSize() {
      return bulkSize;
    }

    public void setBulkSize(int bulkSize) {
      this.bulkSize = bulkSize;
    }
  }

  public static class SearchProperties {
    int defaultLimit = 20;
    int maxLimit = 100;

    public int getDefaultLimit() {
      return defaultLimit;
    }

    public void setDefaultLimit(int defaultLimit) {
      this.defaultLimit = defaultLimit;
    }

    public int getMaxLimit() {
      return maxLimit;
    }

    public void setMaxLimit(int maxLimit) {
      this.maxLimit = maxLimit;
    }
  }
}
