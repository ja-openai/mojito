package com.box.l10n.mojito.entity;

import com.box.l10n.mojito.entity.security.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.ZonedDateTime;

@Entity
@Table(
    name = "team_slack_user_mapping",
    indexes = {
      @Index(name = "I__TEAM_SLACK_USER_MAPPING__TEAM", columnList = "team_id"),
      @Index(name = "I__TEAM_SLACK_USER_MAPPING__SLACK_USER", columnList = "slack_user_id")
    },
    uniqueConstraints = {
      @UniqueConstraint(
          name = "UK__TEAM_SLACK_USER_MAPPING__TEAM_MOJITO_USER",
          columnNames = {"team_id", "mojito_user_id"})
    })
public class TeamSlackUserMapping extends AuditableEntity {

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "team_id",
      foreignKey = @ForeignKey(name = "FK__TEAM_SLACK_USER_MAPPING__TEAM"))
  private Team team;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "mojito_user_id",
      foreignKey = @ForeignKey(name = "FK__TEAM_SLACK_USER_MAPPING__MOJITO_USER"))
  private User mojitoUser;

  @Column(name = "slack_user_id", nullable = false, length = 64)
  private String slackUserId;

  @Column(name = "slack_username", length = 255)
  private String slackUsername;

  @Column(name = "match_source", length = 32)
  private String matchSource;

  @Column(name = "last_verified_at")
  private ZonedDateTime lastVerifiedAt;

  public Team getTeam() {
    return team;
  }

  public void setTeam(Team team) {
    this.team = team;
  }

  public User getMojitoUser() {
    return mojitoUser;
  }

  public void setMojitoUser(User mojitoUser) {
    this.mojitoUser = mojitoUser;
  }

  public String getSlackUserId() {
    return slackUserId;
  }

  public void setSlackUserId(String slackUserId) {
    this.slackUserId = slackUserId;
  }

  public String getSlackUsername() {
    return slackUsername;
  }

  public void setSlackUsername(String slackUsername) {
    this.slackUsername = slackUsername;
  }

  public String getMatchSource() {
    return matchSource;
  }

  public void setMatchSource(String matchSource) {
    this.matchSource = matchSource;
  }

  public ZonedDateTime getLastVerifiedAt() {
    return lastVerifiedAt;
  }

  public void setLastVerifiedAt(ZonedDateTime lastVerifiedAt) {
    this.lastVerifiedAt = lastVerifiedAt;
  }
}
