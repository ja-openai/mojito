package com.box.l10n.mojito.entity.glossary;

import com.box.l10n.mojito.entity.AuditableEntity;
import com.box.l10n.mojito.entity.Repository;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.LinkedHashSet;
import java.util.Set;

@Entity
@Table(
    name = "glossary",
    indexes = {
      @Index(name = "UK__GLOSSARY__NAME", columnList = "name", unique = true),
      @Index(name = "I__GLOSSARY__BACKING_REPOSITORY__ID", columnList = "backing_repository_id")
    })
public class Glossary extends AuditableEntity {

  public static final int NAME_MAX_LENGTH = 255;
  public static final int DESCRIPTION_MAX_LENGTH = 1024;
  public static final int ASSET_PATH_MAX_LENGTH = 255;
  public static final String SCOPE_MODE_GLOBAL = "GLOBAL";
  public static final String SCOPE_MODE_SELECTED_REPOSITORIES = "SELECTED_REPOSITORIES";

  @Column(name = "name", nullable = false, length = NAME_MAX_LENGTH)
  private String name;

  @Column(name = "description", length = DESCRIPTION_MAX_LENGTH)
  private String description;

  @Column(name = "enabled", nullable = false)
  private Boolean enabled = true;

  @Column(name = "priority", nullable = false)
  private Integer priority = 0;

  @Column(name = "scope_mode", nullable = false, length = 32)
  private String scopeMode = SCOPE_MODE_GLOBAL;

  @Column(name = "asset_path", nullable = false, length = ASSET_PATH_MAX_LENGTH)
  private String assetPath = "glossary";

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "backing_repository_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "FK__GLOSSARY__BACKING_REPOSITORY"))
  private Repository backingRepository;

  @ManyToMany(fetch = FetchType.LAZY)
  @JoinTable(
      name = "glossary_repository",
      joinColumns =
          @JoinColumn(
              name = "glossary_id",
              foreignKey = @ForeignKey(name = "FK__GLOSSARY_REPOSITORY__GLOSSARY")),
      inverseJoinColumns =
          @JoinColumn(
              name = "repository_id",
              foreignKey = @ForeignKey(name = "FK__GLOSSARY_REPOSITORY__REPOSITORY")),
      uniqueConstraints = {
        @UniqueConstraint(
            name = "UK__GLOSSARY_REPOSITORY__GLOSSARY_REPOSITORY",
            columnNames = {"glossary_id", "repository_id"})
      })
  private Set<Repository> repositories = new LinkedHashSet<>();

  @ManyToMany(fetch = FetchType.LAZY)
  @JoinTable(
      name = "glossary_excluded_repository",
      joinColumns =
          @JoinColumn(
              name = "glossary_id",
              foreignKey = @ForeignKey(name = "FK__GLOSSARY_EXCLUDED_REPOSITORY__GLOSSARY")),
      inverseJoinColumns =
          @JoinColumn(
              name = "repository_id",
              foreignKey = @ForeignKey(name = "FK__GLOSSARY_EXCLUDED_REPOSITORY__REPOSITORY")),
      uniqueConstraints = {
        @UniqueConstraint(
            name = "UK__GLOSSARY_EXCLUDED_REPOSITORY__GLOSSARY_REPOSITORY",
            columnNames = {"glossary_id", "repository_id"})
      })
  private Set<Repository> excludedRepositories = new LinkedHashSet<>();

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public Boolean getEnabled() {
    return enabled;
  }

  public void setEnabled(Boolean enabled) {
    this.enabled = enabled;
  }

  public Integer getPriority() {
    return priority;
  }

  public void setPriority(Integer priority) {
    this.priority = priority;
  }

  public String getScopeMode() {
    return scopeMode;
  }

  public void setScopeMode(String scopeMode) {
    this.scopeMode = scopeMode;
  }

  public String getAssetPath() {
    return assetPath;
  }

  public void setAssetPath(String assetPath) {
    this.assetPath = assetPath;
  }

  public Repository getBackingRepository() {
    return backingRepository;
  }

  public void setBackingRepository(Repository backingRepository) {
    this.backingRepository = backingRepository;
  }

  public Set<Repository> getRepositories() {
    return repositories;
  }

  public void setRepositories(Set<Repository> repositories) {
    this.repositories = repositories;
  }

  public Set<Repository> getExcludedRepositories() {
    return excludedRepositories;
  }

  public void setExcludedRepositories(Set<Repository> excludedRepositories) {
    this.excludedRepositories = excludedRepositories;
  }
}
