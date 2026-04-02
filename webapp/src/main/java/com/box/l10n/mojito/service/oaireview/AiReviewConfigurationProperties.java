package com.box.l10n.mojito.service.oaireview;

import com.box.l10n.mojito.quartz.QuartzSchedulerManager;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties("l10n.ai-review")
public class AiReviewConfigurationProperties {
  String openaiClientToken;
  String schedulerName = QuartzSchedulerManager.DEFAULT_SCHEDULER_NAME;
  String modelName = "gpt-5.4";
  ResponsesProperties responses = new ResponsesProperties();

  public String getOpenaiClientToken() {
    return openaiClientToken;
  }

  public void setOpenaiClientToken(String openaiClientToken) {
    this.openaiClientToken = openaiClientToken;
  }

  public String getSchedulerName() {
    return schedulerName;
  }

  public void setSchedulerName(String schedulerName) {
    this.schedulerName = schedulerName;
  }

  public String getModelName() {
    return modelName;
  }

  public void setModelName(String modelName) {
    this.modelName = modelName;
  }

  public ResponsesProperties getResponses() {
    return responses;
  }

  public void setResponses(ResponsesProperties responses) {
    this.responses = responses;
  }

  public static class ResponsesProperties {
    String reasoningEffort = "none";
    String textVerbosity = "low";

    public String getReasoningEffort() {
      return reasoningEffort;
    }

    public void setReasoningEffort(String reasoningEffort) {
      this.reasoningEffort = reasoningEffort;
    }

    public String getTextVerbosity() {
      return textVerbosity;
    }

    public void setTextVerbosity(String textVerbosity) {
      this.textVerbosity = textVerbosity;
    }
  }
}
