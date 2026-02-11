package com.box.l10n.mojito.entity.review;

import com.box.l10n.mojito.entity.AuditableEntity;
import com.box.l10n.mojito.entity.BaseEntity;
import com.box.l10n.mojito.entity.security.user.User;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.annotation.CreatedBy;

@Entity
@Table(name = "review_project_request")
public class ReviewProjectRequest extends AuditableEntity {

  @Column(name = "name", length = 255)
  private String name;

  @Column(name = "notes", length = Integer.MAX_VALUE)
  private String notes;

  @CreatedBy
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(
      name = BaseEntity.CreatedByUserColumnName,
      foreignKey = @ForeignKey(name = "FK__REVIEW_PROJECT_REQUEST__CREATED_BY_USER"))
  @JsonIgnore
  private User createdByUser;

  @OneToMany(mappedBy = "reviewProjectRequest", fetch = FetchType.LAZY)
  private List<ReviewProjectRequestScreenshot> screenshots = new ArrayList<>();

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getNotes() {
    return notes;
  }

  public void setNotes(String notes) {
    this.notes = notes;
  }

  public List<ReviewProjectRequestScreenshot> getScreenshots() {
    return screenshots;
  }

  public void setScreenshots(List<ReviewProjectRequestScreenshot> screenshots) {
    this.screenshots = screenshots;
  }

  public User getCreatedByUser() {
    return createdByUser;
  }

  public void setCreatedByUser(User createdByUser) {
    this.createdByUser = createdByUser;
  }
}
