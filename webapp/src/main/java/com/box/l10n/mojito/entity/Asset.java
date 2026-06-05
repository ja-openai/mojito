package com.box.l10n.mojito.entity;

import com.box.l10n.mojito.entity.security.user.User;
import com.box.l10n.mojito.rest.View;
import com.fasterxml.jackson.annotation.JsonManagedReference;
import com.fasterxml.jackson.annotation.JsonView;
import jakarta.persistence.*;
import java.util.Set;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.Check;
import org.springframework.data.annotation.CreatedBy;

/**
 * Entity that describes an asset. It contains the information about the repository’s assets:
 * <li>location of the resource bundles or images to localize in the remote codebase
 * <li>pointer to the current extraction of the asset
 *
 * @author aloison
 */
@Entity
@Check(
    name = "CK__ASSET__CMS_MANAGED_VIRTUAL",
    constraints = "cms_managed = false or \"virtual\" = true")
@Table(
    name = "asset",
    indexes = {
      @Index(
          name = "UK__ASSET__REPOSITORY_ID__PATH",
          columnList = "repository_id, path",
          unique = true),
      @Index(
          name = "UK__ASSET__ID__REPOSITORY_ID",
          columnList = "id, repository_id",
          unique = true),
      @Index(
          name = "UK__ASSET__ID__REPOSITORY_ID__VIRTUAL",
          columnList = "id, repository_id, `virtual`",
          unique = true),
      @Index(
          name = "UK__ASSET__ID__REPOSITORY_ID__VIRTUAL__CMS_MANAGED",
          columnList = "id, repository_id, `virtual`, cms_managed",
          unique = true),
      @Index(
          name = "UK__ASSET__ID__REPOSITORY_ID__VIRTUAL__CMS_MANAGED__DELETED",
          columnList = "id, repository_id, `virtual`, cms_managed, deleted",
          unique = true),
    })
@BatchSize(size = 1000)
@NamedEntityGraph(
    name = "Asset.legacy",
    attributeNodes = {
      @NamedAttributeNode(value = "repository", subgraph = "Asset.legacy.repository"),
      @NamedAttributeNode(
          value = "lastSuccessfulAssetExtraction",
          subgraph = "Asset.legacy.lastSuccessfulAssetExtraction"),
      @NamedAttributeNode("createdByUser")
    },
    subgraphs = {
      @NamedSubgraph(
          name = "Asset.legacy.lastSuccessfulAssetExtraction",
          attributeNodes = {}),
      @NamedSubgraph(
          name = "Asset.legacy.repository",
          attributeNodes = {
            @NamedAttributeNode(
                value = "repositoryLocales",
                subgraph = "Asset.legacy.repository.repositoryLocales"),
          }),
      @NamedSubgraph(
          name = "Asset.legacy.repository.repositoryLocales",
          attributeNodes = {
            @NamedAttributeNode("locale"),
            @NamedAttributeNode("parentLocale"),
          }),
    })
public class Asset extends AuditableEntity {

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "repository_id", foreignKey = @ForeignKey(name = "FK__ASSET__REPOSITORY__ID"))
  @JsonView(View.AssetSummary.class)
  private Repository repository;

  @Basic(optional = false)
  @Column(name = "path")
  @JsonView(View.AssetSummary.class)
  private String path;

  @Column(name = "`virtual`", nullable = false)
  @JsonView(View.AssetSummary.class)
  private Boolean virtual = false;

  @Column(name = "cms_managed", nullable = false)
  private Boolean cmsManaged = false;

  @OneToOne(fetch = FetchType.LAZY)
  @JoinColumn(
      name = "last_successful_asset_extraction_id",
      foreignKey = @ForeignKey(name = "FK__ASSET__ASSET_EXTRACTION__ID"))
  @JsonManagedReference("asset")
  @JsonView(View.AssetSummary.class)
  private AssetExtraction lastSuccessfulAssetExtraction;

  @CreatedBy
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(
      name = BaseEntity.CreatedByUserColumnName,
      foreignKey = @ForeignKey(name = "FK__ASSET__USER__ID"))
  @JsonView(View.AssetSummary.class)
  private User createdByUser;

  /** To mark an Asset as deleted so it does not get processed anymore. */
  @Column(name = "deleted", nullable = false)
  @JsonView(View.AssetSummary.class)
  private Boolean deleted = false;

  @OneToMany(mappedBy = "asset")
  private Set<AssetExtractionByBranch> assetExtractionByBranches;

  public User getCreatedByUser() {
    return createdByUser;
  }

  public void setCreatedByUser(User createdByUser) {
    this.createdByUser = createdByUser;
  }

  public Repository getRepository() {
    return repository;
  }

  public void setRepository(Repository repository) {
    this.repository = repository;
  }

  public String getPath() {
    return path;
  }

  public void setPath(String path) {
    this.path = path;
  }

  public AssetExtraction getLastSuccessfulAssetExtraction() {
    return lastSuccessfulAssetExtraction;
  }

  public void setLastSuccessfulAssetExtraction(AssetExtraction lastSuccessfulAssetExtraction) {
    this.lastSuccessfulAssetExtraction = lastSuccessfulAssetExtraction;
  }

  public Boolean getDeleted() {
    return deleted;
  }

  public void setDeleted(Boolean deleted) {
    this.deleted = deleted;
  }

  public Boolean getVirtual() {
    return virtual;
  }

  public void setVirtual(Boolean virtual) {
    this.virtual = virtual;
  }

  public Boolean getCmsManaged() {
    return cmsManaged;
  }

  public void setCmsManaged(Boolean cmsManaged) {
    this.cmsManaged = cmsManaged;
  }

  public Set<AssetExtractionByBranch> getAssetExtractionByBranches() {
    return assetExtractionByBranches;
  }

  public void setAssetExtractionByBranches(Set<AssetExtractionByBranch> assetExtractionByBranches) {
    this.assetExtractionByBranches = assetExtractionByBranches;
  }
}
