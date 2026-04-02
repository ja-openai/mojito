package com.box.l10n.mojito.service.oaireview;

import com.box.l10n.mojito.quartz.QuartzSchedulerManager;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties("l10n.ai-review")
public class AiReviewConfigurationProperties {
  String openaiClientToken;
  String schedulerName = QuartzSchedulerManager.DEFAULT_SCHEDULER_NAME;
  String modelName = "gpt-5.4";
  ResponsesProperties responses = new ResponsesProperties();
  TimeoutProperties timeout = new TimeoutProperties();

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

  public TimeoutProperties getTimeout() {
    return timeout;
  }

  public void setTimeout(TimeoutProperties timeout) {
    this.timeout = timeout;
  }

  public static class ResponsesProperties {
    String reasoningEffort = "medium";
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

  public static class TimeoutProperties {
    int baseSeconds = 15;
    int perAdditionalMessageSeconds = 2;
    int per1000TextCharsSeconds = 2;
    int minSeconds = 15;
    int maxSeconds = 300;

    public int getBaseSeconds() {
      return baseSeconds;
    }

    public void setBaseSeconds(int baseSeconds) {
      this.baseSeconds = baseSeconds;
    }

    public int getPerAdditionalMessageSeconds() {
      return perAdditionalMessageSeconds;
    }

    public void setPerAdditionalMessageSeconds(int perAdditionalMessageSeconds) {
      this.perAdditionalMessageSeconds = perAdditionalMessageSeconds;
    }

    public int getPer1000TextCharsSeconds() {
      return per1000TextCharsSeconds;
    }

    public void setPer1000TextCharsSeconds(int per1000TextCharsSeconds) {
      this.per1000TextCharsSeconds = per1000TextCharsSeconds;
    }

    public int getMinSeconds() {
      return minSeconds;
    }

    public void setMinSeconds(int minSeconds) {
      this.minSeconds = minSeconds;
    }

    public int getMaxSeconds() {
      return maxSeconds;
    }

    public void setMaxSeconds(int maxSeconds) {
      this.maxSeconds = maxSeconds;
    }

    public Duration resolveRequestTimeout(int messageCount, int textCharCount) {
      int timeoutSeconds =
          baseSeconds
              + (Math.max(0, messageCount - 1) * perAdditionalMessageSeconds)
              + (ceilDiv(textCharCount, 1000) * per1000TextCharsSeconds);

      if (maxSeconds > 0) {
        timeoutSeconds = Math.min(timeoutSeconds, maxSeconds);
      }
      if (minSeconds > 0) {
        timeoutSeconds = Math.max(timeoutSeconds, minSeconds);
      }

      return Duration.ofSeconds(timeoutSeconds);
    }

    private int ceilDiv(int dividend, int divisor) {
      if (dividend <= 0 || divisor <= 0) {
        return 0;
      }
      return (dividend + divisor - 1) / divisor;
    }
  }
}
