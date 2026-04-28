package com.box.l10n.mojito.entity.glossary.termindex;

import com.box.l10n.mojito.entity.AuditableEntity;
import com.box.l10n.mojito.entity.Repository;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.ZonedDateTime;

@Entity
@Table(
    name = "term_index_repository_cursor",
    indexes = {
      @Index(
          name = "UK__TERM_INDEX_REPOSITORY_CURSOR__REPOSITORY",
          columnList = "repository_id",
          unique = true)
    })
public class TermIndexRepositoryCursor extends AuditableEntity {

  public static final String STATUS_IDLE = "IDLE";
  public static final String STATUS_RUNNING = "RUNNING";
  public static final String STATUS_FAILED = "FAILED";

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "repository_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "FK__TERM_INDEX_REPOSITORY_CURSOR__REPOSITORY"))
  private Repository repository;

  @Column(name = "status", nullable = false, length = 32)
  private String status = STATUS_IDLE;

  @Column(name = "last_processed_created_at")
  private ZonedDateTime lastProcessedCreatedAt;

  @Column(name = "last_processed_tm_text_unit_id")
  private Long lastProcessedTmTextUnitId;

  @Column(name = "last_successful_scan_at")
  private ZonedDateTime lastSuccessfulScanAt;

  @Column(name = "lease_owner")
  private String leaseOwner;

  @Column(name = "lease_token", length = 64)
  private String leaseToken;

  @Column(name = "lease_expires_at")
  private ZonedDateTime leaseExpiresAt;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(
      name = "current_refresh_run_id",
      foreignKey = @ForeignKey(name = "FK__TERM_INDEX_REPOSITORY_CURSOR__CURRENT_REFRESH_RUN"))
  private TermIndexRefreshRun currentRefreshRun;

  @Column(name = "error_message", length = 2048)
  private String errorMessage;

  public Repository getRepository() {
    return repository;
  }

  public void setRepository(Repository repository) {
    this.repository = repository;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public ZonedDateTime getLastProcessedCreatedAt() {
    return lastProcessedCreatedAt;
  }

  public void setLastProcessedCreatedAt(ZonedDateTime lastProcessedCreatedAt) {
    this.lastProcessedCreatedAt = lastProcessedCreatedAt;
  }

  public Long getLastProcessedTmTextUnitId() {
    return lastProcessedTmTextUnitId;
  }

  public void setLastProcessedTmTextUnitId(Long lastProcessedTmTextUnitId) {
    this.lastProcessedTmTextUnitId = lastProcessedTmTextUnitId;
  }

  public ZonedDateTime getLastSuccessfulScanAt() {
    return lastSuccessfulScanAt;
  }

  public void setLastSuccessfulScanAt(ZonedDateTime lastSuccessfulScanAt) {
    this.lastSuccessfulScanAt = lastSuccessfulScanAt;
  }

  public String getLeaseOwner() {
    return leaseOwner;
  }

  public void setLeaseOwner(String leaseOwner) {
    this.leaseOwner = leaseOwner;
  }

  public String getLeaseToken() {
    return leaseToken;
  }

  public void setLeaseToken(String leaseToken) {
    this.leaseToken = leaseToken;
  }

  public ZonedDateTime getLeaseExpiresAt() {
    return leaseExpiresAt;
  }

  public void setLeaseExpiresAt(ZonedDateTime leaseExpiresAt) {
    this.leaseExpiresAt = leaseExpiresAt;
  }

  public TermIndexRefreshRun getCurrentRefreshRun() {
    return currentRefreshRun;
  }

  public void setCurrentRefreshRun(TermIndexRefreshRun currentRefreshRun) {
    this.currentRefreshRun = currentRefreshRun;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public void setErrorMessage(String errorMessage) {
    this.errorMessage = errorMessage;
  }
}
