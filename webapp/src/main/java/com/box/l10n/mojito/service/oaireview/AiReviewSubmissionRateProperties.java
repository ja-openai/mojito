package com.box.l10n.mojito.service.oaireview;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Observation only: these settings cannot reject a review. */
@Component
@ConfigurationProperties("l10n.ai-review.submission-rate")
public class AiReviewSubmissionRateProperties {
  private boolean enabled = true;
  private double requestsPerSecond = 3;
  private int burst = 10;

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public double getRequestsPerSecond() {
    return requestsPerSecond;
  }

  public void setRequestsPerSecond(double value) {
    if (!Double.isFinite(value) || value <= 0 || value > 1000)
      throw new IllegalArgumentException("Invalid AI review submission rate");
    requestsPerSecond = value;
  }

  public int getBurst() {
    return burst;
  }

  public void setBurst(int value) {
    if (value < 1 || value > 10000)
      throw new IllegalArgumentException("Invalid AI review submission burst");
    burst = value;
  }
}
