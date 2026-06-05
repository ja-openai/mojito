package com.box.l10n.mojito.service.jsonconfiglocalization;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("l10n.json-config-localization.statsig")
public class StatsigJsonConfigLocalizationProperties {

  private String baseUrl = "https://statsigapi.net/console/v1";
  private String apiVersion = "20240601";
  private String apiKey;
  private String consoleUrlTemplate;
  private boolean dryRunPush;

  public String getBaseUrl() {
    return baseUrl;
  }

  public void setBaseUrl(String baseUrl) {
    this.baseUrl = baseUrl;
  }

  public String getApiVersion() {
    return apiVersion;
  }

  public void setApiVersion(String apiVersion) {
    this.apiVersion = apiVersion;
  }

  public String getApiKey() {
    return apiKey;
  }

  public void setApiKey(String apiKey) {
    this.apiKey = apiKey;
  }

  public String getConsoleUrlTemplate() {
    return consoleUrlTemplate;
  }

  public void setConsoleUrlTemplate(String consoleUrlTemplate) {
    this.consoleUrlTemplate = consoleUrlTemplate;
  }

  public boolean isDryRunPush() {
    return dryRunPush;
  }

  public void setDryRunPush(boolean dryRunPush) {
    this.dryRunPush = dryRunPush;
  }

  public boolean isConfigured() {
    return apiKey != null && !apiKey.isBlank();
  }

  public String getConsoleUrl(String configId) {
    if (consoleUrlTemplate == null || consoleUrlTemplate.isBlank()) {
      return null;
    }
    if (configId == null || configId.isBlank()) {
      return null;
    }
    String encodedConfigId =
        URLEncoder.encode(configId.trim(), StandardCharsets.UTF_8).replace("+", "%20");
    return consoleUrlTemplate.trim().replace("{configId}", encodedConfigId);
  }
}
