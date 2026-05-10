package com.box.l10n.mojito.cli.command.jenkinsstats;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import javax.annotation.Nullable;

@JsonIgnoreProperties(ignoreUnknown = true)
public record JenkinsJobResult(
    @JsonProperty("jobName") @Nullable String jobName,
    @JsonProperty("duration") long duration,
    @JsonProperty("id") long id,
    @JsonProperty("number") long number,
    @JsonProperty("result") @Nullable String result,
    @JsonProperty("timestamp") long timestamp) {

  JenkinsJobResult withJobName(String jobName) {
    return new JenkinsJobResult(jobName, duration, id, number, result, timestamp);
  }
}
