package com.box.l10n.mojito.rest.admin;

import com.box.l10n.mojito.service.tm.AssetLocalizeAsyncJobRepairService;
import com.box.l10n.mojito.service.tm.AssetLocalizeAsyncJobRepairService.AssetLocalizeAsyncJobInvalidPayloadException;
import com.box.l10n.mojito.service.tm.AssetLocalizeAsyncJobRepairService.AssetLocalizeAsyncJobLookupException;
import com.box.l10n.mojito.service.tm.AssetLocalizeAsyncJobRepairService.AssetLocalizeAsyncJobNotFoundException;
import com.box.l10n.mojito.service.tm.AssetLocalizeAsyncJobRepairService.AssetLocalizePollableTaskNotFoundException;
import com.box.l10n.mojito.service.tm.AssetLocalizeAsyncJobRepairService.AssetLocalizePollableTaskRepairException;
import com.box.l10n.mojito.service.tm.AssetLocalizeAsyncJobRepairService.RepairResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Admin-only repair endpoint for terminal assetlocalize queue rows with open pollable tasks. */
@RestController
@RequestMapping("/api/admin/async-job-queue/assetlocalize/jobs")
@ConditionalOnProperty(
    name = {"l10n.org.async-job-queue.enabled", "l10n.org.async-job-queue.asset-localize.enabled"},
    havingValue = "true")
public class AssetLocalizeAsyncJobRepairWS {

  private final AssetLocalizeAsyncJobRepairService repairService;

  public AssetLocalizeAsyncJobRepairWS(AssetLocalizeAsyncJobRepairService repairService) {
    this.repairService = repairService;
  }

  @PostMapping("/{asyncJobId}/pollable-task/repair")
  public RepairResult repairPollableTask(@PathVariable String asyncJobId) {
    try {
      return repairService.repairTerminalPollableTask(asyncJobId);
    } catch (AssetLocalizeAsyncJobNotFoundException
        | AssetLocalizePollableTaskNotFoundException exception) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage(), exception);
    } catch (AssetLocalizeAsyncJobLookupException
        | AssetLocalizePollableTaskRepairException exception) {
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR, exception.getMessage(), exception);
    } catch (AssetLocalizeAsyncJobInvalidPayloadException exception) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage(), exception);
    } catch (IllegalStateException exception) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage(), exception);
    } catch (IllegalArgumentException exception) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
    }
  }
}
