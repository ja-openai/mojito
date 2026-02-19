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
    name = "team_locale_pool",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "UK__TEAM_LOCALE_POOL__TEAM_LOCALE_TRANSLATOR",
          columnNames = {"team_id", "locale_id", "translator_user_id"}),
      @UniqueConstraint(
          name = "UK__TEAM_LOCALE_POOL__TEAM_LOCALE_POSITION",
          columnNames = {"team_id", "locale_id", "position"})
    })
public class TeamLocalePool extends AuditableEntity {

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "team_id", foreignKey = @ForeignKey(name = "FK__TEAM_LOCALE_POOL__TEAM"))
  private Team team;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "locale_id", foreignKey = @ForeignKey(name = "FK__TEAM_LOCALE_POOL__LOCALE"))
  private Locale locale;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "translator_user_id",
      foreignKey = @ForeignKey(name = "FK__TEAM_LOCALE_POOL__TRANSLATOR_USER"))
  private User translatorUser;

  @Column(name = "position", nullable = false)
  private Integer position;

  public Team getTeam() {
    return team;
  }

  public void setTeam(Team team) {
    this.team = team;
  }

  public Locale getLocale() {
    return locale;
  }

  public void setLocale(Locale locale) {
    this.locale = locale;
  }

  public User getTranslatorUser() {
    return translatorUser;
  }

  public void setTranslatorUser(User translatorUser) {
    this.translatorUser = translatorUser;
  }

  public Integer getPosition() {
    return position;
  }

  public void setPosition(Integer position) {
    this.position = position;
  }
}
