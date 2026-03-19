package com.box.l10n.mojito.entity.review;

import com.box.l10n.mojito.entity.AuditableEntity;
import com.box.l10n.mojito.entity.Repository;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.LinkedHashSet;
import java.util.Set;

@Entity
@Table(
    name = "review_feature",
    indexes = {@Index(name = "UK__REVIEW_FEATURE__NAME", columnList = "name", unique = true)})
public class ReviewFeature extends AuditableEntity {

  public static final int NAME_MAX_LENGTH = 255;

  @Column(name = "name", nullable = false, length = NAME_MAX_LENGTH)
  private String name;

  @Column(name = "enabled", nullable = false)
  private Boolean enabled = true;

  @ManyToMany(fetch = FetchType.LAZY)
  @JoinTable(
      name = "review_feature_repository",
      joinColumns =
          @JoinColumn(
              name = "review_feature_id",
              foreignKey =
                  @jakarta.persistence.ForeignKey(name = "FK__REVIEW_FEATURE_REPOSITORY__FEATURE")),
      inverseJoinColumns =
          @JoinColumn(
              name = "repository_id",
              foreignKey =
                  @jakarta.persistence.ForeignKey(
                      name = "FK__REVIEW_FEATURE_REPOSITORY__REPOSITORY")),
      uniqueConstraints = {
        @UniqueConstraint(
            name = "UK__REVIEW_FEATURE_REPOSITORY__FEATURE_REPOSITORY",
            columnNames = {"review_feature_id", "repository_id"})
      })
  private Set<Repository> repositories = new LinkedHashSet<>();

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

  public Set<Repository> getRepositories() {
    return repositories;
  }

  public void setRepositories(Set<Repository> repositories) {
    this.repositories = repositories;
  }
}
