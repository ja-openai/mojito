package com.box.l10n.mojito.entity.agentreview;

import com.box.l10n.mojito.entity.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/** A finite incident sweep shared by equivalent manual and scheduled selections. */
@Entity
@Table(
    name = "incident_review_batch_cursor",
    indexes =
        @Index(
            name = "UK__INCIDENT_REVIEW_BATCH_CURSOR__SCOPE",
            columnList = "team_id,scope_fingerprint",
            unique = true))
public class IncidentReviewBatchCursor extends AuditableEntity {
  @Column(name = "team_id", nullable = false)
  private Long teamId;

  @Column(name = "scope_fingerprint", nullable = false, length = 64)
  private String scopeFingerprint;

  @Column(name = "last_scanned_incident_id", nullable = false)
  private long lastScannedIncidentId;

  @Column(name = "sweep_upper_bound_id", nullable = false)
  private long sweepUpperBoundId;

  public Long getTeamId() {
    return teamId;
  }

  public void setTeamId(Long teamId) {
    this.teamId = teamId;
  }

  public String getScopeFingerprint() {
    return scopeFingerprint;
  }

  public void setScopeFingerprint(String scopeFingerprint) {
    this.scopeFingerprint = scopeFingerprint;
  }

  public long getLastScannedIncidentId() {
    return lastScannedIncidentId;
  }

  public void setLastScannedIncidentId(long lastScannedIncidentId) {
    this.lastScannedIncidentId = lastScannedIncidentId;
  }

  public long getSweepUpperBoundId() {
    return sweepUpperBoundId;
  }

  public void setSweepUpperBoundId(long sweepUpperBoundId) {
    this.sweepUpperBoundId = sweepUpperBoundId;
  }
}
