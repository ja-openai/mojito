package com.box.l10n.mojito.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.List;

@Entity
@Table(name = "repository_generation_settings")
public class RepositoryGenerationSettings {

  @Id
  @Column(name = "repository_id")
  private Long repositoryId;

  @Convert(converter = GenerationExcludedLocaleTagsConverter.class)
  @Column(name = "excluded_locale_tags_json", length = Integer.MAX_VALUE)
  private List<String> excludedLocaleTags = List.of();

  public Long getRepositoryId() {
    return repositoryId;
  }

  public void setRepositoryId(Long repositoryId) {
    this.repositoryId = repositoryId;
  }

  public List<String> getExcludedLocaleTags() {
    return excludedLocaleTags;
  }

  public void setExcludedLocaleTags(List<String> excludedLocaleTags) {
    this.excludedLocaleTags =
        excludedLocaleTags == null ? List.of() : List.copyOf(excludedLocaleTags);
  }
}
