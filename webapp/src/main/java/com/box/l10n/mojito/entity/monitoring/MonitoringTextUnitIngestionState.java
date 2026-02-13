package com.box.l10n.mojito.entity.monitoring;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.ZonedDateTime;

@Entity
@Table(name = "monitoring_text_unit_ingestion_state")
public class MonitoringTextUnitIngestionState implements Serializable {

  @Id
  @Column(name = "id", nullable = false)
  Integer id;

  @Column(name = "latest_computed_day")
  LocalDate latestComputedDay;

  @Column(name = "last_computed_at")
  ZonedDateTime lastComputedAt;

  public Integer getId() {
    return id;
  }

  public void setId(Integer id) {
    this.id = id;
  }

  public LocalDate getLatestComputedDay() {
    return latestComputedDay;
  }

  public void setLatestComputedDay(LocalDate latestComputedDay) {
    this.latestComputedDay = latestComputedDay;
  }

  public ZonedDateTime getLastComputedAt() {
    return lastComputedAt;
  }

  public void setLastComputedAt(ZonedDateTime lastComputedAt) {
    this.lastComputedAt = lastComputedAt;
  }
}
