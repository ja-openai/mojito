package com.box.l10n.mojito.entity.security.user;

import com.box.l10n.mojito.entity.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@Entity
@Table(
    name = "user_preferences",
    indexes =
        @Index(name = "UK__USER_PREFERENCES__USER__ID", columnList = "user_id", unique = true))
public class UserPreferencesEntity extends AuditableEntity {

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(
      name = "user_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "FK__USER_PREFERENCES__USER__ID"))
  @OnDelete(action = OnDeleteAction.CASCADE)
  private User user;

  @Column(name = "preferences_json", nullable = false, length = Integer.MAX_VALUE)
  private String preferencesJson;

  public User getUser() {
    return user;
  }

  public void setUser(User user) {
    this.user = user;
  }

  public String getPreferencesJson() {
    return preferencesJson;
  }

  public void setPreferencesJson(String preferencesJson) {
    this.preferencesJson = preferencesJson;
  }
}
