package com.box.l10n.mojito.entity.review;

import com.box.l10n.mojito.entity.AuditableEntity;
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
    name = "review_automation",
    indexes = {@Index(name = "UK__REVIEW_AUTOMATION__NAME", columnList = "name", unique = true)})
public class ReviewAutomation extends AuditableEntity {

  public static final int NAME_MAX_LENGTH = 255;
  public static final int CRON_EXPRESSION_MAX_LENGTH = 255;
  public static final int TIME_ZONE_MAX_LENGTH = 100;

  @Column(name = "name", nullable = false, length = NAME_MAX_LENGTH)
  private String name;

  @Column(name = "enabled", nullable = false)
  private Boolean enabled = true;

  @Column(name = "cron_expression", nullable = false, length = CRON_EXPRESSION_MAX_LENGTH)
  private String cronExpression;

  @Column(name = "time_zone", nullable = false, length = TIME_ZONE_MAX_LENGTH)
  private String timeZone;

  @Column(name = "max_word_count_per_project", nullable = false)
  private Integer maxWordCountPerProject;

  @ManyToMany(fetch = FetchType.LAZY)
  @JoinTable(
      name = "review_automation_feature",
      joinColumns =
          @JoinColumn(
              name = "review_automation_id",
              foreignKey =
                  @jakarta.persistence.ForeignKey(
                      name = "FK__REVIEW_AUTOMATION_FEATURE__AUTOMATION")),
      inverseJoinColumns =
          @JoinColumn(
              name = "review_feature_id",
              foreignKey =
                  @jakarta.persistence.ForeignKey(name = "FK__REVIEW_AUTOMATION_FEATURE__FEATURE")),
      uniqueConstraints = {
        @UniqueConstraint(
            name = "UK__REVIEW_AUTOMATION_FEATURE__AUTOMATION_FEATURE",
            columnNames = {"review_automation_id", "review_feature_id"})
      })
  private Set<ReviewFeature> features = new LinkedHashSet<>();

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

  public String getCronExpression() {
    return cronExpression;
  }

  public void setCronExpression(String cronExpression) {
    this.cronExpression = cronExpression;
  }

  public String getTimeZone() {
    return timeZone;
  }

  public void setTimeZone(String timeZone) {
    this.timeZone = timeZone;
  }

  public Integer getMaxWordCountPerProject() {
    return maxWordCountPerProject;
  }

  public void setMaxWordCountPerProject(Integer maxWordCountPerProject) {
    this.maxWordCountPerProject = maxWordCountPerProject;
  }

  public Set<ReviewFeature> getFeatures() {
    return features;
  }

  public void setFeatures(Set<ReviewFeature> features) {
    this.features = features;
  }
}
