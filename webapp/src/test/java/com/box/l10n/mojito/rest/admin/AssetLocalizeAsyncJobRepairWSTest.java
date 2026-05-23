package com.box.l10n.mojito.rest.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.service.tm.AssetLocalizeAsyncJobRepairService;
import com.box.l10n.mojito.service.tm.AssetLocalizeAsyncJobRepairService.AssetLocalizeAsyncJobInvalidPayloadException;
import com.box.l10n.mojito.service.tm.AssetLocalizeAsyncJobRepairService.AssetLocalizeAsyncJobLookupException;
import com.box.l10n.mojito.service.tm.AssetLocalizeAsyncJobRepairService.AssetLocalizeAsyncJobNotFoundException;
import com.box.l10n.mojito.service.tm.AssetLocalizeAsyncJobRepairService.AssetLocalizePollableTaskLookupException;
import com.box.l10n.mojito.service.tm.AssetLocalizeAsyncJobRepairService.AssetLocalizePollableTaskNotFoundException;
import com.box.l10n.mojito.service.tm.AssetLocalizeAsyncJobRepairService.AssetLocalizePollableTaskRepairException;
import com.box.l10n.mojito.service.tm.AssetLocalizeAsyncJobRepairService.RepairResult;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
  public void repairControllerOnlyExposesNarrowPollableRepairPostWithoutRequestBody()
      throws Exception {
    Method repairMethod =
        AssetLocalizeAsyncJobRepairWS.class.getDeclaredMethod("repairPollableTask", String.class);
    PostMapping postMapping = repairMethod.getAnnotation(PostMapping.class);
    List<String> mutatingHandlers =
        Arrays.stream(AssetLocalizeAsyncJobRepairWS.class.getDeclaredMethods())
            .filter(
                method ->
                    method.isAnnotationPresent(PostMapping.class)
                        || method.isAnnotationPresent(PutMapping.class)
                        || method.isAnnotationPresent(PatchMapping.class)
                        || method.isAnnotationPresent(DeleteMapping.class))
            .map(Method::getName)
            .toList();
    List<String> getHandlers =
        Arrays.stream(AssetLocalizeAsyncJobRepairWS.class.getDeclaredMethods())
            .filter(method -> method.isAnnotationPresent(GetMapping.class))
            .map(Method::getName)
            .toList();
    boolean acceptsRequestBody =
        Arrays.stream(repairMethod.getParameterAnnotations())
            .flatMap(Arrays::stream)
            .map(Annotation::annotationType)
            .anyMatch(RequestBody.class::equals);

    assertThat(mutatingHandlers).containsExactly("repairPollableTask");
    assertThat(getHandlers).isEmpty();
    assertThat(postMapping.value()).containsExactly("/{asyncJobId}/pollable-task/repair");
    assertThat(acceptsRequestBody).isFalse();
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
  public void mapsInvalidPersistedPayloadToConflict() {
    when(repairService.repairTerminalPollableTask("1"))
        .thenThrow(
            new AssetLocalizeAsyncJobInvalidPayloadException(
                "invalid payload", new IllegalArgumentException("bad json")));

    assertThatThrownBy(() -> ws.repairPollableTask("1"))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            exception ->
                assertThat(((ResponseStatusException) exception).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT));
  }

  @Test
  public void mapsAsyncJobLookupFailureToInternalServerError() {
    when(repairService.repairTerminalPollableTask("1"))
        .thenThrow(
            new AssetLocalizeAsyncJobLookupException(
                "lookup failed", new IllegalStateException("database unavailable")));

    assertThatThrownBy(() -> ws.repairPollableTask("1"))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            exception ->
                assertThat(((ResponseStatusException) exception).getStatusCode())
                    .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR));
  }

  @Test
  public void mapsPollableTaskLookupFailureToInternalServerError() {
    when(repairService.repairTerminalPollableTask("1"))
        .thenThrow(
            new AssetLocalizePollableTaskLookupException(
                "lookup failed", new IllegalStateException("database unavailable")));

    assertThatThrownBy(() -> ws.repairPollableTask("1"))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            exception ->
                assertThat(((ResponseStatusException) exception).getStatusCode())
                    .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR));
  }

  @Test
  public void mapsPollableTaskRepairFailureToInternalServerError() {
    when(repairService.repairTerminalPollableTask("1"))
        .thenThrow(
            new AssetLocalizePollableTaskRepairException(
                "repair failed", new IllegalStateException("database unavailable")));

    assertThatThrownBy(() -> ws.repairPollableTask("1"))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            exception ->
                assertThat(((ResponseStatusException) exception).getStatusCode())
                    .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR));
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
