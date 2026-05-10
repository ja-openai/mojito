package com.box.l10n.mojito.cli.command.jenkinsstats;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
public record JenkinsStatsConfig(
    @JsonProperty("jobs") List<String> jobs,
    @JsonProperty("authCookies") Map<String, String> authCookies) {

  public JenkinsStatsConfig {
    jobs = List.copyOf(Objects.requireNonNull(jobs));
    authCookies = Map.copyOf(Objects.requireNonNull(authCookies));
  }
}
