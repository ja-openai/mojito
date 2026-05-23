package com.box.l10n.mojito.rest.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.service.tm.AssetLocalizeAsyncJobRepairService;
import com.box.l10n.mojito.service.tm.AssetLocalizeAsyncJobRepairService.AssetLocalizeAsyncJobNotFoundException;
import com.box.l10n.mojito.service.tm.AssetLocalizeAsyncJobRepairService.AssetLocalizePollableTaskNotFoundException;
import com.box.l10n.mojito.service.tm.AssetLocalizeAsyncJobRepairService.RepairResult;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@RunWith(MockitoJUnitRunner.class)
public class AssetLocalizeAsyncJobRepairWSTest {

  @Mock AssetLocalizeAsyncJobRepairService repairService;

  AssetLocalizeAsyncJobRepairWS ws;

  @Before
  public void setUp() {
    ws = new AssetLocalizeAsyncJobRepairWS(repairService);
  }

  @Test
  public void repairsPollableTask() {
    RepairResult expected = new RepairResult("1", 42L, "failed", "repaired");
    when(repairService.repairTerminalPollableTask("1")).thenReturn(expected);

    assertThat(ws.repairPollableTask("1")).isEqualTo(expected);
  }

  @Test
  public void mapsMissingAsyncJobToNotFound() {
    when(repairService.repairTerminalPollableTask("1"))
        .thenThrow(new AssetLocalizeAsyncJobNotFoundException("job not found"));

    assertThatThrownBy(() -> ws.repairPollableTask("1"))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            exception ->
                assertThat(((ResponseStatusException) exception).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND));
  }

  @Test
  public void mapsMissingPollableTaskToNotFound() {
    when(repairService.repairTerminalPollableTask("1"))
        .thenThrow(new AssetLocalizePollableTaskNotFoundException("pollable task not found"));

    assertThatThrownBy(() -> ws.repairPollableTask("1"))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            exception ->
                assertThat(((ResponseStatusException) exception).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND));
  }

  @Test
  public void mapsNonTerminalJobToConflict() {
    when(repairService.repairTerminalPollableTask("1")).thenThrow(new IllegalStateException());

    assertThatThrownBy(() -> ws.repairPollableTask("1"))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            exception ->
                assertThat(((ResponseStatusException) exception).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT));
  }

  @Test
  public void mapsInvalidRequestToBadRequest() {
    when(repairService.repairTerminalPollableTask("bad"))
        .thenThrow(new IllegalArgumentException("bad id"));

    assertThatThrownBy(() -> ws.repairPollableTask("bad"))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            exception ->
                assertThat(((ResponseStatusException) exception).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST));
  }
}
