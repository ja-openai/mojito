package com.box.l10n.mojito.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.quartz.QuartzJobInfo;
import com.box.l10n.mojito.rest.asset.LocalizedAssetBody;
import com.box.l10n.mojito.service.assetExtraction.ServiceTestBase;
import com.box.l10n.mojito.service.pollableTask.ExceptionHolder;
import com.box.l10n.mojito.service.pollableTask.PollableTaskBlobStorage;
import com.box.l10n.mojito.service.pollableTask.PollableTaskExceptionUtils;
import com.box.l10n.mojito.service.pollableTask.PollableTaskService;
import com.box.l10n.mojito.service.tm.AssetLocalizeAsyncJobPayload;
import com.box.l10n.mojito.service.tm.AssetLocalizeAsyncJobSubmissionService;
import com.box.l10n.mojito.service.tm.GenerateLocalizedAssetJob;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicReference;
import org.assertj.core.api.SoftAssertions;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Real HSQL task persistence and translator HTTP serialization. Fatal input-acknowledgement cases
 * also write real input blobs before a service-boundary fault; no case enqueues work.
 */
public class AssetLocalizeAsyncJobSubmissionPrivacyIntegrationTest extends ServiceTestBase {

  private static final String TASK_MESSAGE = "submission privacy";
  private static final String PRIVATE_MARKER = "synthetic-private-preparation-diagnostic";
  private static final String PRIVATE_SOURCE = "synthetic-private-source-text";

  @Autowired PollableTaskService pollableTaskService;
  @Autowired PollableTaskBlobStorage storedInputs;
  @Autowired ObjectMapper originalMapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired WebApplicationContext webApplicationContext;

  private final PollableTaskBlobStorage inputs = mock(PollableTaskBlobStorage.class);
  private final AsyncJobQueueSubmissionService queue = mock(AsyncJobQueueSubmissionService.class);
  private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();

  private PollableTask task;
  private PollableTaskService completion;
  private QuartzJobInfo<LocalizedAssetBody, LocalizedAssetBody> job;
  private MockMvc mvc;

  @Before
  public void setUpSubmission() {
    task =
        pollableTaskService.createPollableTask(
            null, GenerateLocalizedAssetJob.class.getCanonicalName(), TASK_MESSAGE, 0, 3600);
    completion = mock(PollableTaskService.class, delegatesTo(pollableTaskService));
    doReturn(task)
        .when(completion)
        .createPollableTask(
            null, GenerateLocalizedAssetJob.class.getCanonicalName(), TASK_MESSAGE, 0, 3600);
    job =
        QuartzJobInfo.newBuilder(GenerateLocalizedAssetJob.class)
            .withInput(new LocalizedAssetBody())
            .withMessage(TASK_MESSAGE)
            .build();
    mvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
  }

  @After
  public void closeMetrics() {
    metrics.close();
  }

  @Test
  public void wrappedFatalInputAcknowledgementPreservesPendingTaskAndCommittedInput() {
    assertFatalInputAcknowledgement(false);
  }

  @Test
  public void suppressedFatalInputAcknowledgementPreservesPendingTaskAndCommittedInput() {
    assertFatalInputAcknowledgement(true);
  }

  private void assertFatalInputAcknowledgement(boolean suppressed) {
    Error fatal = new OutOfMemoryError("synthetic wrapped preparation fatal");
    RuntimeException wrapper = new IllegalStateException("input acknowledgement failed");
    if (suppressed) {
      wrapper.addSuppressed(fatal);
    } else {
      wrapper.initCause(fatal);
    }
    var pendingTask = jdbc.queryForMap("SELECT * FROM pollable_task WHERE id = ?", task.getId());
    AtomicReference<byte[]> committedInput = new AtomicReference<>();
    doAnswer(
            invocation -> {
              storedInputs.saveInput(task.getId(), job.getInput());
              committedInput.set(storedInputs.getInputBytes(task.getId()));
              // The blob service has returned; this is not an injected JDBC/network commit fault.
              throw wrapper;
            })
        .when(inputs)
        .saveInput(task.getId(), job.getInput());
    var service =
        new AssetLocalizeAsyncJobSubmissionService(
            completion, inputs, new PollableTaskExceptionUtils(), queue, originalMapper, metrics);

    Throwable thrown =
        org.assertj.core.api.Assertions.catchThrowable(() -> service.scheduleJob(job));

    SoftAssertions checks = new SoftAssertions();
    checks.assertThat(thrown).isSameAs(fatal);
    checks
        .assertThat(jdbc.queryForMap("SELECT * FROM pollable_task WHERE id = ?", task.getId()))
        .as("no definitive task failure for a JVM-fatal preparation error")
        .isEqualTo(pendingTask);
    checks.assertThat(committedInput.get()).isNotNull();
    checks
        .assertThat(storedInputs.getInputBytes(task.getId()))
        .containsExactly(committedInput.get());
    checks.assertThat(metrics.getMeters()).isEmpty();
    checks.assertAll();
    verify(inputs).saveInput(task.getId(), job.getInput());
    verify(completion, never()).finishTask(eq(task.getId()), any(), any(), any());
    verifyNoInteractions(queue);
    assertThat(wrapper.getCause()).isSameAs(suppressed ? null : fatal);
    assertThat(wrapper.getSuppressed())
        .containsExactly(suppressed ? new Throwable[] {fatal} : new Throwable[0]);
  }

  @Test
  public void inputSaveFailureKeepsDiagnosticsOutOfPersistedTaskAndTranslatorResponses()
      throws Exception {
    RuntimeException failure = preparationFailure("input save failed");
    doThrow(failure).when(inputs).saveInput(task.getId(), job.getInput());

    assertPreparationFailure(originalMapper, failure);
  }

  @Test
  public void payloadSerializationFailureKeepsDiagnosticsOutOfPersistedTaskAndTranslatorResponses()
      throws Exception {
    RuntimeException failure = preparationFailure("payload serialization failed");
    ObjectMapper payloadMapper = mock(ObjectMapper.class);
    when(payloadMapper.writeValueAsStringUnchecked(any(AssetLocalizeAsyncJobPayload.class)))
        .thenThrow(failure);

    assertPreparationFailure(payloadMapper, failure);
  }

  @Test
  public void lostFinishAcknowledgementKeepsCommittedTaskSafeWithoutImmediateRetry()
      throws Exception {
    RuntimeException failure =
        preparationFailure("input save failed before finish acknowledgement loss");
    doThrow(failure).when(inputs).saveInput(task.getId(), job.getInput());
    doAnswer(
            invocation -> {
              // Service-boundary fault after a committed task update, not an in-JDBC fault.
              pollableTaskService.finishTask(
                  invocation.getArgument(0),
                  invocation.getArgument(1),
                  invocation.getArgument(2),
                  invocation.getArgument(3));
              throw new IllegalStateException("synthetic task-finish acknowledgement loss");
            })
        .when(completion)
        .finishTask(eq(task.getId()), isNull(), any(ExceptionHolder.class), isNull());

    assertPreparationFailure(originalMapper, failure);
  }

  private RuntimeException preparationFailure(String operation) {
    return new RuntimeException(
        operation + ": " + PRIVATE_MARKER,
        new SQLException("synthetic SQL diagnostic: source=\"" + PRIVATE_SOURCE + "\""));
  }

  private void assertPreparationFailure(ObjectMapper payloadMapper, RuntimeException failure)
      throws Exception {
    var service =
        new AssetLocalizeAsyncJobSubmissionService(
            completion, inputs, new PollableTaskExceptionUtils(), queue, payloadMapper, metrics);

    assertThatThrownBy(() -> service.scheduleJob(job)).isSameAs(failure);

    verify(completion)
        .createPollableTask(
            null, GenerateLocalizedAssetJob.class.getCanonicalName(), TASK_MESSAGE, 0, 3600);
    verify(inputs).saveInput(task.getId(), job.getInput());
    verifyNoInteractions(queue);
    ArgumentCaptor<ExceptionHolder> holder = ArgumentCaptor.forClass(ExceptionHolder.class);
    verify(completion, times(1)).finishTask(eq(task.getId()), isNull(), holder.capture(), isNull());

    PollableTask finished = pollableTaskService.getPollableTask(task.getId());
    assertThat(finished.getFinishedDate()).isNotNull();
    String persistedStack =
        jdbc.queryForObject(
            "SELECT error_stacks FROM pollable_task WHERE id = ?", String.class, task.getId());
    assertThat(persistedStack).isEqualTo(finished.getErrorStack());
    String taskResponse = response("/api/pollableTasks/" + task.getId());
    String inspectionResponse = response("/api/pollableTasks/" + task.getId() + "/inspection");

    SoftAssertions privacy = new SoftAssertions();
    // Check both translator surfaces before the generic message so baseline proves disclosure.
    privacy
        .assertThat(taskResponse)
        .as("translator GET /api/pollableTasks/%s", task.getId())
        .doesNotContain(PRIVATE_MARKER, PRIVATE_SOURCE, "java.sql.SQLException", "Caused by:");
    privacy
        .assertThat(inspectionResponse)
        .as("translator GET /api/pollableTasks/%s/inspection", task.getId())
        .doesNotContain(PRIVATE_MARKER, PRIVATE_SOURCE, "java.sql.SQLException", "Caused by:");
    privacy
        .assertThat(persistedStack)
        .as("committed pollable_task.error_stacks")
        .doesNotContain(PRIVATE_MARKER, PRIVATE_SOURCE, "java.sql.SQLException", "Caused by:");

    String safeMessage = "Asset localization preparation failed for pollable task: " + task.getId();
    privacy.assertThat(holder.getValue().isExpected()).isFalse();
    privacy.assertThat(holder.getValue().getException()).hasMessage(safeMessage).hasNoCause();
    privacy.assertThat(persistedStack).contains(safeMessage);

    var expectedError =
        originalMapper.readTreeUnchecked(
            "{\"expected\":false,\"message\":\"An unexpected error happened, task="
                + task.getId()
                + "\",\"type\":\"unexpected\"}");
    privacy
        .assertThat(originalMapper.readTreeUnchecked(finished.getErrorMessage()))
        .as("persisted unexpected classification")
        .isEqualTo(expectedError);
    var taskJson = originalMapper.readTreeUnchecked(taskResponse);
    privacy.assertThat(taskJson.path("errorMessage")).isEqualTo(expectedError);
    privacy.assertThat(taskJson.path("errorStack").asText()).isEqualTo(persistedStack);
    var inspection = originalMapper.readTreeUnchecked(inspectionResponse);
    privacy.assertThat(inspection.path("status").asText()).isEqualTo("FAILED");
    privacy.assertThat(inspection.path("error").path("expected").asBoolean(true)).isFalse();
    privacy
        .assertThat(inspection.path("error").path("reportedType").asText())
        .isEqualTo("unexpected");
    privacy
        .assertThat(inspection.path("error").path("exceptionMessage").asText())
        .isEqualTo(safeMessage);
    privacy
        .assertThat(inspection.path("error").path("stackTrace").asText())
        .isEqualTo(persistedStack);
    privacy.assertAll();
  }

  private String response(String path) throws Exception {
    return mvc.perform(get(path).with(user("submission-reader").roles("TRANSLATOR")))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }
}
