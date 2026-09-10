package com.box.l10n.mojito.service.oaireview;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Cluster-wide provider admission is deliberately independent of Quartz's thread pools. */
@Component
@ConfigurationProperties("l10n.ai-review.execution")
public class AiReviewExecutionProperties {
  private int maxInFlight = 400;
  private int maxInFlightPerUser = 3;
  private long timeoutSeconds = 180;

  public int getMaxInFlight() {
    return maxInFlight;
  }

  public void setMaxInFlight(int value) {
    if (value < 1 || value > 1000) throw new IllegalArgumentException("Invalid review capacity");
    maxInFlight = value;
  }

  public int getMaxInFlightPerUser() {
    return maxInFlightPerUser;
  }

  public void setMaxInFlightPerUser(int value) {
    if (value < 1 || value > 1000)
      throw new IllegalArgumentException("Invalid per-user review capacity");
    maxInFlightPerUser = value;
  }

  public long getTimeoutSeconds() {
    return timeoutSeconds;
  }

  public void setTimeoutSeconds(long value) {
    if (value < 1 || value > 900) throw new IllegalArgumentException("Invalid review deadline");
    timeoutSeconds = value;
  }
}
