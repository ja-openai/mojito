package com.box.l10n.mojito.service.searchindex;

import static org.assertj.core.api.Assertions.assertThat;

import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.assetExtraction.ServiceTestBase;
import com.box.l10n.mojito.service.pollableTask.PollableTaskService;
import com.box.l10n.mojito.service.tm.TMTextUnitVariantRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

public class SearchIndexRepositoryTest extends ServiceTestBase {

  @Autowired private TMTextUnitVariantRepository tmTextUnitVariantRepository;
  @Autowired private SearchIndexConfigurationProperties searchIndexConfigurationProperties;
  @Autowired private SearchIndexService searchIndexService;
  @Autowired private SearchIndexReindexJobService searchIndexReindexJobService;
  @Autowired private PollableTaskService pollableTaskService;

  @Test
  public void queriesRepositoryScopedSearchIndexProjection() {
    long allRepositoryCount = tmTextUnitVariantRepository.countSearchIndexRows(List.of(-1L), true);

    assertThat(
            tmTextUnitVariantRepository.findSearchIndexRows(
                List.of(Long.MAX_VALUE), false, null, PageRequest.of(0, 25)))
        .isEmpty();

    assertThat(
            tmTextUnitVariantRepository.findSearchIndexRows(
                List.of(-1L), true, null, PageRequest.of(0, 25)))
        .hasSize((int) Math.min(allRepositoryCount, 25));

    assertThat(tmTextUnitVariantRepository.countSearchIndexRows(List.of(Long.MAX_VALUE), false))
        .isZero();
  }

  @Test
  public void reportsConfiguredSearchAvailabilityWithoutBlockingStartup() {
    SearchIndexService.SearchIndexStatus status =
        searchIndexConfigurationProperties.isEnabled()
            ? searchIndexService.bootstrapIndex()
            : searchIndexService.getStatus();

    assertThat(status.enabled()).isEqualTo(searchIndexConfigurationProperties.isEnabled());
    if (searchIndexConfigurationProperties.isEnabled()) {
      assertThat(status.reachable()).as("Search index status: %s", status).isTrue();
      assertThat(status.indexExists()).isTrue();
      assertThat(status.clusterStatus()).isEqualTo("green");
    }
  }

  @Test
  public void appliesLanguageSpecificTextAnalysisWhenSearchIsEnabled() throws Exception {
    if (!searchIndexConfigurationProperties.isEnabled()) {
      return;
    }

    searchIndexService.bootstrapIndex();

    assertThat(analyze("sourceLocalized.en", "running")).containsExactly("run");
    assertThat(analyze("targetLocalized.fr", "chevaux")).containsExactly("cheval");
    assertThat(analyze("targetLocalized.pt_br", "ações")).containsExactly("aco");
    assertThat(analyze("targetLocalized.tr", "İSTANBUL")).containsExactly("istanbul");
    assertThat(analyze("targetLocalized.th", "ภาษาไทย")).containsExactly("ภาษา", "ไทย");
    assertThat(analyze("targetLocalized.ja", "東京都に行きます")).contains("東京").doesNotContain("京都");
    assertThat(analyze("targetLocalized.ko", "한국어 번역")).contains("번역");
    assertThat(analyze("targetLocalized.zh_hans", "中文翻译")).contains("中文", "翻译");
    assertThat(analyze("targetLocalized.zh_hant", "繁體中文翻譯")).contains("中文");
    assertThat(analyze("target", "café")).containsExactly("cafe");
  }

  @Test
  public void runsReindexAsAnObservableQuartzPollableTask() throws Exception {
    if (!searchIndexConfigurationProperties.isEnabled()) {
      return;
    }

    PollableTask scheduledTask =
        searchIndexReindexJobService.scheduleReindex(
            new SearchIndexService.SearchIndexReindexRequest(List.of(Long.MAX_VALUE), 5, 2));
    PollableTask finishedTask =
        pollableTaskService.waitForPollableTask(scheduledTask.getId(), 10_000, 100);

    assertThat(finishedTask.isAllFinished()).isTrue();
    assertThat(finishedTask.getErrorMessage()).isNull();
    JsonNode progress = new ObjectMapper().readTreeUnchecked(finishedTask.getMessage());
    assertThat(progress.path("status").asText()).isEqualTo("COMPLETED");
    assertThat(progress.path("totalDocuments").asLong()).isZero();
    assertThat(searchIndexReindexJobService.getActiveReindexTask()).isEmpty();
  }

  private List<String> analyze(String field, String text) throws IOException, InterruptedException {
    ObjectMapper objectMapper = new ObjectMapper();
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(
                URI.create(
                    searchIndexConfigurationProperties.getBaseUrl()
                        + "/"
                        + searchIndexConfigurationProperties.getIndexName()
                        + "/_analyze"))
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    objectMapper.writeValueAsStringUnchecked(Map.of("field", field, "text", text))))
            .build();
    HttpResponse<String> response =
        HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).as("Analyzer response: %s", response.body()).isEqualTo(200);

    List<String> tokens = new ArrayList<>();
    JsonNode tokenNodes = objectMapper.readTreeUnchecked(response.body()).path("tokens");
    tokenNodes.forEach(token -> tokens.add(token.path("token").asText()));
    return tokens;
  }
}
