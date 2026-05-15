package com.box.l10n.mojito.cli.command.jenkinsstats;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record JenkinsJobResult(
    @JsonProperty("jobName") String jobName,
    @JsonProperty("duration") long duration,
    @JsonProperty("id") long id,
    @JsonProperty("number") long number,
    @JsonProperty("result") String result,
    @JsonProperty("timestamp") long timestamp) {

  JenkinsJobResult withJobName(String jobName) {
    return new JenkinsJobResult(jobName, duration, id, number, result, timestamp);
  }
}
