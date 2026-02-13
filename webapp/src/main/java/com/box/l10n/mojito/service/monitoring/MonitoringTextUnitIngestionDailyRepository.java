package com.box.l10n.mojito.service.monitoring;

import com.box.l10n.mojito.entity.monitoring.MonitoringTextUnitIngestionDaily;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

@RepositoryRestResource(exported = false)
public interface MonitoringTextUnitIngestionDailyRepository
    extends JpaRepository<MonitoringTextUnitIngestionDaily, Long> {

  @Query(
      "select row from MonitoringTextUnitIngestionDaily row"
          + " order by row.dayUtc asc, row.repository.id asc")
  List<MonitoringTextUnitIngestionDaily> findAllOrdered();

  @Query(
      "select row from MonitoringTextUnitIngestionDaily row"
          + " where row.repository.id = :repositoryId"
          + " order by row.dayUtc asc, row.repository.id asc")
  List<MonitoringTextUnitIngestionDaily> findAllByRepositoryIdOrdered(
      @Param("repositoryId") Long repositoryId);
}
