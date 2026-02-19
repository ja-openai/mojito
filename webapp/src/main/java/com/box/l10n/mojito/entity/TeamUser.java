package com.box.l10n.mojito.entity;

import com.box.l10n.mojito.entity.security.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
    name = "team_user",
    indexes = {@Index(name = "I__TEAM_USER__USER_ROLE", columnList = "user_id, role")},
    uniqueConstraints = {
      @UniqueConstraint(
          name = "UK__TEAM_USER__TEAM_USER_ROLE",
          columnNames = {"team_id", "user_id", "role"})
    })
public class TeamUser extends AuditableEntity {

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "team_id", foreignKey = @ForeignKey(name = "FK__TEAM_USER__TEAM"))
  private Team team;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", foreignKey = @ForeignKey(name = "FK__TEAM_USER__USER"))
  private User user;

  @Enumerated(EnumType.STRING)
  @Column(name = "role", nullable = false, length = 32)
  private TeamUserRole role;

  public Team getTeam() {
    return team;
  }

  public void setTeam(Team team) {
    this.team = team;
  }

  public User getUser() {
    return user;
  }

  public void setUser(User user) {
    this.user = user;
  }

  public TeamUserRole getRole() {
    return role;
  }

  public void setRole(TeamUserRole role) {
    this.role = role;
  }
}
