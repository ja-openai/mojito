package com.box.l10n.mojito.service.oaitranslate;

import com.box.l10n.mojito.quartz.QuartzSchedulerManager;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties("l10n.ai-translate")
public class AiTranslateConfigurationProperties {
  String openaiClientToken;
  String schedulerName = QuartzSchedulerManager.DEFAULT_SCHEDULER_NAME;
  String modelName = "gpt-4o-2024-08-06";
  PoolProperties pool = new PoolProperties();
  RetryProperties retry = new RetryProperties();
  NoBatchProperties noBatch = new NoBatchProperties();

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

  public PoolProperties getPool() {
    return pool;
  }

  public void setPool(PoolProperties pool) {
    this.pool = pool;
  }

  public RetryProperties getRetry() {
    return retry;
  }

  public void setRetry(RetryProperties retry) {
    this.retry = retry;
  }

  public NoBatchProperties getNoBatch() {
    return noBatch;
  }

  public void setNoBatch(NoBatchProperties noBatch) {
    this.noBatch = noBatch;
  }

  public static class NoBatchProperties {
    TimeoutProperties timeout = new TimeoutProperties();

    public TimeoutProperties getTimeout() {
      return timeout;
    }

    public void setTimeout(TimeoutProperties timeout) {
      this.timeout = timeout;
    }

    public static class TimeoutProperties {
      int baseSeconds = 15;
      int perAdditionalTextUnitSeconds = 2;
      int per1000SourceCharsSeconds = 2;
      int screenshotPenaltySeconds = 5;
      int minSeconds = 15;
      int maxSeconds = 60;

      public int getBaseSeconds() {
        return baseSeconds;
      }

      public void setBaseSeconds(int baseSeconds) {
        this.baseSeconds = baseSeconds;
      }

      public int getPerAdditionalTextUnitSeconds() {
        return perAdditionalTextUnitSeconds;
      }

      public void setPerAdditionalTextUnitSeconds(int perAdditionalTextUnitSeconds) {
        this.perAdditionalTextUnitSeconds = perAdditionalTextUnitSeconds;
      }

      public int getPer1000SourceCharsSeconds() {
        return per1000SourceCharsSeconds;
      }

      public void setPer1000SourceCharsSeconds(int per1000SourceCharsSeconds) {
        this.per1000SourceCharsSeconds = per1000SourceCharsSeconds;
      }

      public int getScreenshotPenaltySeconds() {
        return screenshotPenaltySeconds;
      }

      public void setScreenshotPenaltySeconds(int screenshotPenaltySeconds) {
        this.screenshotPenaltySeconds = screenshotPenaltySeconds;
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
    }
  }

  public static class PoolProperties {
    int maxConnections = 20;
    int maxPendingAcquires = 100;
    int acquireTimeoutSeconds = 1;

    public int getMaxConnections() {
      return maxConnections;
    }

    public void setMaxConnections(int maxConnections) {
      this.maxConnections = maxConnections;
    }

    public int getMaxPendingAcquires() {
      return maxPendingAcquires;
    }

    public void setMaxPendingAcquires(int maxPendingAcquires) {
      this.maxPendingAcquires = maxPendingAcquires;
    }

    public int getAcquireTimeoutSeconds() {
      return acquireTimeoutSeconds;
    }

    public void setAcquireTimeoutSeconds(int acquireTimeoutSeconds) {
      this.acquireTimeoutSeconds = acquireTimeoutSeconds;
    }
  }

  public static class RetryProperties {
    int maxAttempts = 5;
    int initialBackoffMillis = 500;
    int maxBackoffSeconds = 5;

    public int getMaxAttempts() {
      return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
      this.maxAttempts = maxAttempts;
    }

    public int getInitialBackoffMillis() {
      return initialBackoffMillis;
    }

    public void setInitialBackoffMillis(int initialBackoffMillis) {
      this.initialBackoffMillis = initialBackoffMillis;
    }

    public int getMaxBackoffSeconds() {
      return maxBackoffSeconds;
    }

    public void setMaxBackoffSeconds(int maxBackoffSeconds) {
      this.maxBackoffSeconds = maxBackoffSeconds;
    }
  }
}
