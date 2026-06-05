package com.box.l10n.mojito.entity.cms;

import com.box.l10n.mojito.entity.AuditableEntity;
import com.box.l10n.mojito.entity.BaseEntity;
import com.box.l10n.mojito.entity.security.user.User;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MappedSuperclass;
import org.hibernate.envers.Audited;
import org.hibernate.envers.RelationTargetAuditMode;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.LastModifiedBy;

@MappedSuperclass
@Audited(targetAuditMode = RelationTargetAuditMode.NOT_AUDITED)
public abstract class CmsAuditableEntity extends AuditableEntity {

  @CreatedBy
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = BaseEntity.CreatedByUserColumnName, nullable = false)
  private User createdByUser;

  @LastModifiedBy
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "last_modified_by_user_id", nullable = false)
  private User lastModifiedByUser;

  public User getCreatedByUser() {
    return createdByUser;
  }

  public void setCreatedByUser(User createdByUser) {
    this.createdByUser = createdByUser;
  }

  public User getLastModifiedByUser() {
    return lastModifiedByUser;
  }

  public void setLastModifiedByUser(User lastModifiedByUser) {
    this.lastModifiedByUser = lastModifiedByUser;
  }
}
