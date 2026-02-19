package com.box.l10n.mojito.entity;

import com.box.l10n.mojito.entity.security.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
    name = "team_pm_pool",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "UK__TEAM_PM_POOL__TEAM_PM",
          columnNames = {"team_id", "pm_user_id"}),
      @UniqueConstraint(
          name = "UK__TEAM_PM_POOL__TEAM_POSITION",
          columnNames = {"team_id", "position"})
    })
public class TeamPmPool extends AuditableEntity {

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "team_id", foreignKey = @ForeignKey(name = "FK__TEAM_PM_POOL__TEAM"))
  private Team team;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "pm_user_id", foreignKey = @ForeignKey(name = "FK__TEAM_PM_POOL__PM_USER"))
  private User pmUser;

  @Column(name = "position", nullable = false)
  private Integer position;

  public Team getTeam() {
    return team;
  }

  public void setTeam(Team team) {
    this.team = team;
  }

  public User getPmUser() {
    return pmUser;
  }

  public void setPmUser(User pmUser) {
    this.pmUser = pmUser;
  }

  public Integer getPosition() {
    return position;
  }

  public void setPosition(Integer position) {
    this.position = position;
  }
}
