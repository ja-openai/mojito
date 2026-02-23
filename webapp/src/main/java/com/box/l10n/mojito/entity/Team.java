package com.box.l10n.mojito.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

@Entity
@Table(
    name = "team",
    indexes = {@Index(name = "UK__TEAM__NAME", columnList = "name", unique = true)})
public class Team extends AuditableEntity {

  public static final int NAME_MAX_LENGTH = 255;

  @Column(name = "name", nullable = false, length = NAME_MAX_LENGTH)
  private String name;

  @Column(name = "enabled", nullable = false)
  private Boolean enabled = true;

  @Column(name = "slack_notifications_enabled", nullable = false)
  private Boolean slackNotificationsEnabled = false;

  @Column(name = "slack_client_id", length = 255)
  private String slackClientId;

  @Column(name = "slack_channel_id", length = 64)
  private String slackChannelId;

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public Boolean getEnabled() {
    return enabled;
  }

  public void setEnabled(Boolean enabled) {
    this.enabled = enabled;
  }

  public Boolean getSlackNotificationsEnabled() {
    return slackNotificationsEnabled;
  }

  public void setSlackNotificationsEnabled(Boolean slackNotificationsEnabled) {
    this.slackNotificationsEnabled = slackNotificationsEnabled;
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
}
