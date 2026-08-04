package com.box.l10n.mojito.rest.monitoring;

import com.box.l10n.mojito.service.monitoring.AzureBlobStorageMonitoringService;
import com.box.l10n.mojito.service.monitoring.AzureBlobStorageMonitoringService.AzureStorageSnapshot;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/monitoring/azure-storage")
public class AzureBlobStorageMonitoringWS {

  private final AzureBlobStorageMonitoringService azureBlobStorageMonitoringService;

  public AzureBlobStorageMonitoringWS(
      AzureBlobStorageMonitoringService azureBlobStorageMonitoringService) {
    this.azureBlobStorageMonitoringService = azureBlobStorageMonitoringService;
  }

  @GetMapping
  public AzureStorageSnapshot getStatus() {
    return azureBlobStorageMonitoringService.getStatus();
  }

  @PostMapping("/probe")
  public AzureStorageSnapshot runProbe() {
    return azureBlobStorageMonitoringService.runProbe();
  }
}
