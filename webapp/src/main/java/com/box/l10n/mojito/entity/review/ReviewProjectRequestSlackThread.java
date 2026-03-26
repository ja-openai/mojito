package com.box.l10n.mojito.entity.review;

import com.box.l10n.mojito.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(
    name = "review_project_request_slack_thread",
    indexes = {
      @Index(
          name = "UK__RPR_SLACK_THREAD__REQUEST_ID",
          columnList = "review_project_request_id",
          unique = true)
    })
public class ReviewProjectRequestSlackThread extends BaseEntity {

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "review_project_request_id",
      foreignKey = @ForeignKey(name = "FK__RPR_SLACK_THREAD__REQUEST"))
  private ReviewProjectRequest reviewProjectRequest;

  @Column(name = "slack_client_id", nullable = false, length = 255)
  private String slackClientId;

  @Column(name = "slack_channel_id", nullable = false, length = 64)
  private String slackChannelId;

  @Column(name = "thread_ts", nullable = false, length = 64)
  private String threadTs;

  public ReviewProjectRequest getReviewProjectRequest() {
    return reviewProjectRequest;
  }

  public void setReviewProjectRequest(ReviewProjectRequest reviewProjectRequest) {
    this.reviewProjectRequest = reviewProjectRequest;
  }

  public String getSlackClientId() {
    return slackClientId;
  }

  public void setSlackClientId(String slackClientId) {
    this.slackClientId = slackClientId;
  }

  public String getSlackChannelId() {
    return slackChannelId;
  }

  public void setSlackChannelId(String slackChannelId) {
    this.slackChannelId = slackChannelId;
  }

  public String getThreadTs() {
    return threadTs;
  }

  public void setThreadTs(String threadTs) {
    this.threadTs = threadTs;
  }
}
