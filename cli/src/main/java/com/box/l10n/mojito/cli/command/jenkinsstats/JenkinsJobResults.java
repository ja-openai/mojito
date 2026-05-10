package com.box.l10n.mojito.cli.command.jenkinsstats;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
public record JenkinsJobResults(
    @JsonProperty("displayName") String displayName,
    @JsonProperty("builds") List<JenkinsJobResult> builds) {

  public JenkinsJobResults {
    Objects.requireNonNull(displayName);
    builds = List.copyOf(Objects.requireNonNull(builds));
  }
}
