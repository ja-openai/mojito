package com.box.l10n.mojito.service.monitoring;

import com.box.l10n.mojito.entity.monitoring.MonitoringTextUnitIngestionState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

@RepositoryRestResource(exported = false)
public interface MonitoringTextUnitIngestionStateRepository
    extends JpaRepository<MonitoringTextUnitIngestionState, Integer> {}
