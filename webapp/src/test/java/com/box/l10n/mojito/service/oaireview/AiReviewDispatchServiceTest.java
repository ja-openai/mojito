package com.box.l10n.mojito.service.oaireview;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.rest.textunit.AiReviewChatJobsWS;
import com.box.l10n.mojito.rest.textunit.AiReviewChatWS;
import com.box.l10n.mojito.rest.textunit.AiReviewChatWS.AiReviewChatMessage;
import com.box.l10n.mojito.rest.textunit.AiReviewChatWS.AiReviewChatRequest;
import com.box.l10n.mojito.rest.textunit.AiReviewChatWS.AiReviewChatResponse;
import com.box.l10n.mojito.service.oaireview.AiReviewExecutionStore.Claim;
import com.box.l10n.mojito.service.oaireview.AiReviewExecutionStore.Disposition;
import com.box.l10n.mojito.service.oaireview.AiReviewInteractiveService.Prepared;
import com.box.l10n.mojito.service.oaireview.AiReviewInteractiveService.Settings;
import com.box.l10n.mojito.service.pollableTask.PollableTaskBlobStorage;
import com.box.l10n.mojito.service.pollableTask.PollableTaskService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.quartz.Job;
import org.quartz.JobBuilder;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.Scheduler;
import org.quartz.TriggerBuilder;
import org.quartz.impl.StdSchedulerFactory;

/** Direct asynchronous submission; database fencing is covered by AiReviewExecutionStoreTest. */
public class AiReviewDispatchServiceTest {
  private final ObjectMapper mapper = ObjectMapper.withNoFailOnUnknownProperties();
  private final Map<Long, ControlledTask> tasks = new ConcurrentHashMap<>();
  private final List<AiReviewDispatchService> dispatchers = new ArrayList<>();
  private final AiReviewExecutionStore store = mock(AiReviewExecutionStore.class);
  private final AiReviewChatWS chat = mock(AiReviewChatWS.class);
  private final AiReviewInteractiveService interactive = mock(AiReviewInteractiveService.class);
  private final PollableTaskBlobStorage blobs = mock(PollableTaskBlobStorage.class);
  private final PollableTaskService pollableTasks = mock(PollableTaskService.class);
  private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
  private AiReviewDispatchService dispatcher;
  private AiReviewChatJobsWS controller;
  private Scheduler defaultScheduler;

  @Before
  public void setUpDispatch() {
    when(interactive.enforceExecutionPolicy(any())).thenAnswer(i -> i.getArgument(0));
    when(chat.prepare(any())).thenReturn(prepared());
    when(store.tryClaim(anyLong(), anyString(), any(Settings.class)))
        .thenAnswer(
            i -> {
              ControlledTask task = taskFor(i.getArgument(0, Long.class));
              Disposition disposition =
                  task.finished.getCount() == 0
                      ? Disposition.DONE
                      : task.result.get() != null
                          ? Disposition.FINISH
                          : task.claimed.getAndSet(true) ? Disposition.WAIT : Disposition.START;
              return new Claim(disposition, task.token, task.deadline);
            });
    when(store.isActive(anyLong(), anyString()))
        .thenAnswer(
            i -> {
              ControlledTask task = taskFor(i.getArgument(0, Long.class));
              return task.result.get() == null && task.finished.getCount() != 0;
            });
    doAnswer(
            i -> {
              ControlledTask task = taskFor(i.getArgument(0, Long.class));
              assertEquals(task.token, i.getArgument(1));
              task.result.compareAndSet(null, i.getArgument(2));
              task.staged.countDown();
              return null;
            })
        .when(store)
        .stageResult(anyLong(), anyString(), any());
    when(store.releaseCapacity(anyString()))
        .thenAnswer(
            i -> {
              ControlledTask task =
                  tasks.values().stream()
                      .filter(t -> t.token.equals(i.getArgument(0)))
                      .findFirst()
                      .orElseThrow();
              assertTrue(task.httpSettled.isDone());
              task.released.countDown();
              return true;
            });
    when(store.finishStaged(anyLong()))
        .thenAnswer(
            i -> {
              ControlledTask task = taskFor(i.getArgument(0, Long.class));
              if (task.result.get() == null || !task.outputAvailable.get()) {
                task.finishAttempt.countDown();
                return false;
              }
              task.task.setFinishedDate(ZonedDateTime.now());
              task.finishAttempt.countDown();
              task.finished.countDown();
              return true;
            });
    when(store.getStagedResult(anyLong()))
        .thenAnswer(i -> Optional.ofNullable(taskFor(i.getArgument(0, Long.class)).result.get()));
    when(store.unfinishedIds(anyLong(), anyInt()))
        .thenAnswer(
            i ->
                tasks.values().stream()
                    .filter(t -> t.id > i.getArgument(0, Long.class) && t.finished.getCount() != 0)
                    .map(t -> t.id)
                    .sorted()
                    .limit(i.getArgument(1, Integer.class))
                    .toList());
    doAnswer(
            i -> {
              taskFor(i.getArgument(0, Long.class))
                  .result
                  .compareAndSet(
                      null, AiReviewExecutionStore.error(429, "AI review is busy. Please retry."));
              return null;
            })
        .when(store)
        .rejectBusy(anyLong());
    doAnswer(
            i -> {
              taskFor(i.getArgument(0, Long.class))
                  .result
                  .compareAndSet(
                      null, AiReviewExecutionStore.error(409, "AI review was cancelled."));
              return null;
            })
        .when(store)
        .cancel(anyLong());
    when(chat.chatPreparedCall(any(), anyLong(), any(), any()))
        .thenAnswer(
            i -> {
              ControlledTask task = taskFor(i.getArgument(1, Long.class));
              assertEquals(task.deadline, i.getArgument(2));
              return new AiReviewChatWS.ReviewCall(task.provider, task.httpSettled);
            });
    when(pollableTasks.getPollableTask(anyLong()))
        .thenAnswer(i -> taskFor(i.getArgument(0, Long.class)).task);
    when(blobs.findOutputJson(anyLong()))
        .thenAnswer(
            i ->
                Optional.of(
                    mapper.writeValueAsStringUnchecked(
                        taskFor(i.getArgument(0, Long.class)).result.get())));
    dispatcher = newDispatcher();
    controller =
        new AiReviewChatJobsWS(
            chat,
            pollableTasks,
            blobs,
            mock(AiReviewChatJobAccess.class),
            mapper,
            dispatcher,
            new AiReviewExecutionProperties());
  }

  @After
  public void stopDispatch() throws Exception {
    for (ControlledTask task : tasks.values()) {
      task.outputAvailable.set(true);
      task.provider.complete(response());
      task.httpSettled.complete(null);
    }
    dispatchers.forEach(AiReviewDispatchService::stop);
    if (defaultScheduler != null) defaultScheduler.shutdown(true);
    metrics.close();
  }

  @Test
  public void controllerReturnsTaskIdsFor128PendingProvidersAndQuartzStaysResponsive()
      throws Exception {
    int requestCount = 128; // More reviewers than dispatch/completion threads.
    for (long id = 1; id <= requestCount; id++) task(id);
    AtomicLong nextId = new AtomicLong();
    when(pollableTasks.createPollableTask(any(), anyString(), any(), anyInt(), anyLong()))
        .thenAnswer(i -> taskFor(nextId.incrementAndGet()).task);
    var caller = Executors.newSingleThreadExecutor();
    try {
      List<Long> ids =
          caller
              .submit(
                  () -> {
                    List<Long> submitted = new ArrayList<>();
                    for (int i = 0; i < requestCount; i++)
                      submitted.add(controller.start(prepared().request()).taskId());
                    return submitted;
                  })
              .get(2, TimeUnit.SECONDS);
      assertEquals(requestCount, ids.size());
      assertEquals(Long.valueOf(1), ids.getFirst());
      assertEquals(Long.valueOf(requestCount), ids.getLast());
    } finally {
      caller.shutdownNow();
    }

    await(defaultQuartzProbe(), "Ordinary Quartz work did not remain responsive");
    for (ControlledTask task : tasks.values()) {
      assertFalse(task.provider.isDone());
      assertNull(task.task.getFinishedDate());
      assertEquals("pending", controller.get(task.id).status());
    }
    verify(store, never()).stageResult(anyLong(), anyString(), any());
    verify(store, never()).finishStaged(anyLong());
    verify(chat, times(requestCount)).chatPreparedCall(any(), anyLong(), any(), any());

    for (ControlledTask task : tasks.values()) {
      task.provider.complete(response());
      task.httpSettled.complete(null);
    }
    for (ControlledTask task : tasks.values()) {
      await(task.finished, "Provider result was not materialized");
      assertEquals("completed", controller.get(task.id).status());
      assertEquals(response(), controller.get(task.id).response());
      await(task.released, "Capacity was not released after HTTP settled");
    }
  }

  @Test
  public void taskRemainsPendingUntilOutputIsStoredAndCleanupRetriesStorageOnly() throws Exception {
    ControlledTask task = task(41);
    task.outputAvailable.set(false);
    assertTrue(dispatcher.start(task.id, prepared()));
    task.provider.complete(response());
    task.httpSettled.complete(null);
    await(task.finishAttempt, "Completion did not attempt output persistence");

    assertNotNull(task.result.get());
    assertNull(task.task.getFinishedDate());
    assertEquals("pending", controller.get(task.id).status());
    dispatcher.stop();
    AiReviewDispatchService replacement = newDispatcher();
    task.outputAvailable.set(true);
    replacement.cleanup();

    await(task.finished, "Cleanup did not materialize the already staged result");
    assertEquals("completed", controller.get(task.id).status());
    verify(chat, times(1)).chatPreparedCall(any(), eq(task.id), any(), any());
    verify(store, times(1)).stageResult(eq(task.id), eq(task.token), any());
  }

  @Test
  public void completedReviewIsAvailableWhileCapacityReleaseRetries() throws Exception {
    ControlledTask task = task(42);
    CountDownLatch released = new CountDownLatch(1);
    AtomicBoolean firstRelease = new AtomicBoolean(true);
    doAnswer(
            invocation -> {
              assertEquals("completed", controller.get(task.id).status());
              if (firstRelease.getAndSet(false)) return false;
              released.countDown();
              return true;
            })
        .when(store)
        .releaseCapacity(task.token);

    assertTrue(dispatcher.start(task.id, prepared()));
    task.provider.complete(response());
    task.httpSettled.complete(null);

    await(task.finished, "Capacity accounting blocked the completed response");
    assertEquals("completed", controller.get(task.id).status());
    await(released, "Deferred capacity release was not retried");
    verify(chat, times(1)).chatPreparedCall(any(), eq(task.id), any(), any());
  }

  @Test
  public void fullCapacityProducesBusyResultWithoutQueuingProviderWork() throws Exception {
    ControlledTask task = task(51);
    doReturn(new Claim(Disposition.WAIT, null, task.deadline))
        .when(store)
        .tryClaim(eq(task.id), anyString(), any(Settings.class));

    assertFalse(dispatcher.start(task.id, prepared()));

    await(task.finished, "Busy rejection was not materialized");
    assertEquals("failed", controller.get(task.id).status());
    assertEquals(429, controller.get(task.id).error().status());
    dispatcher.cleanup();
    verify(store).rejectBusy(task.id);
    verify(chat, never()).chatPreparedCall(any(), anyLong(), any(), any());
  }

  @Test
  public void executionPolicyIsAppliedBeforeClaimAndProviderCall() {
    ControlledTask task = task(60);
    Prepared effective = prepared();
    Prepared original =
        new Prepared(
            effective.request(),
            effective.userId(),
            new Settings("ultra", "selected-model", "max", "low", "priority"));
    doReturn(effective).when(interactive).enforceExecutionPolicy(original);

    assertTrue(dispatcher.start(task.id, original));

    var order = inOrder(interactive, store, chat);
    order.verify(interactive).enforceExecutionPolicy(original);
    order.verify(store).tryClaim(eq(task.id), anyString(), eq(effective.settings()));
    order.verify(chat).chatPreparedCall(eq(effective), eq(task.id), eq(task.deadline), any());
  }

  @Test
  public void duplicateAdmissionNeverResendsAnAlreadyStartedProvider() {
    ControlledTask task = task(61);
    assertTrue(dispatcher.start(task.id, prepared()));

    assertFalse(dispatcher.start(task.id, prepared()));

    verify(chat, times(1)).chatPreparedCall(any(), eq(task.id), any(), any());
    verify(store, never()).rejectBusy(anyLong());
    assertFalse(task.provider.isDone());
  }

  @Test
  public void cancellationReleasesCapacityOnlyAfterUnderlyingHttpCancellationSettles()
      throws Exception {
    ControlledTask task = task(71);
    assertTrue(dispatcher.start(task.id, prepared()));

    dispatcher.cancel(task.id);

    await(task.finished, "Cancellation was not materialized");
    assertTrue(task.provider.isCancelled());
    verify(store, never()).stageResult(eq(task.id), anyString(), any());
    task.httpSettled.complete(null);
    await(task.staged, "HTTP cancellation did not release capacity");
    await(task.released, "HTTP cancellation did not release capacity");
    assertEquals(409, controller.get(task.id).error().status());
    verify(store).cancel(task.id);
    verify(store).stageResult(eq(task.id), eq(task.token), any());
  }

  @Test
  public void cleanupFailsAnInterruptedExpiredRequestWithoutRestartingProviderWork() {
    ControlledTask task = task(81);
    task.claimed.set(true); // Persisted attempt belongs to a process that no longer exists.
    expireWithTimeout(task);

    newDispatcher().cleanup();

    assertNotNull(task.task.getFinishedDate());
    assertEquals(504, controller.get(task.id).error().status());
    verify(chat, never()).chatPreparedCall(any(), anyLong(), any(), any());
    verify(store, never()).tryClaim(anyLong(), anyString(), any(Settings.class));
  }

  @Test
  public void cleanupReachesExpiredRequestsBehindAFullPageOfPendingCalls() {
    for (long id = 1; id <= 101; id++) task(id).claimed.set(true);
    expireWithTimeout(taskFor(101));

    dispatcher.cleanup();
    assertNull(taskFor(101).task.getFinishedDate());
    dispatcher.cleanup();

    assertNotNull(taskFor(101).task.getFinishedDate());
    verify(store).unfinishedIds(0, 100);
    verify(store).unfinishedIds(100, 100);
    verify(chat, never()).chatPreparedCall(any(), anyLong(), any(), any());
  }

  private void expireWithTimeout(ControlledTask task) {
    doAnswer(
            i -> {
              task.result.compareAndSet(
                  null,
                  AiReviewExecutionStore.error(
                      504, "AI review timed out or was interrupted. Please retry."));
              return null;
            })
        .when(store)
        .expire(task.id);
  }

  private AiReviewDispatchService newDispatcher() {
    AiReviewDispatchService service =
        new AiReviewDispatchService(store, chat, interactive, blobs, mapper, metrics);
    dispatchers.add(service);
    return service;
  }

  private CountDownLatch defaultQuartzProbe() throws Exception {
    Properties properties = new Properties();
    String name = "default-" + UUID.randomUUID();
    properties.setProperty("org.quartz.scheduler.instanceName", name);
    properties.setProperty("org.quartz.scheduler.instanceId", name);
    properties.setProperty("org.quartz.scheduler.skipUpdateCheck", "true");
    properties.setProperty("org.quartz.scheduler.makeSchedulerThreadDaemon", "true");
    properties.setProperty("org.quartz.threadPool.class", "org.quartz.simpl.SimpleThreadPool");
    properties.setProperty("org.quartz.threadPool.threadCount", "1");
    properties.setProperty("org.quartz.threadPool.makeThreadsDaemons", "true");
    properties.setProperty("org.quartz.jobStore.class", "org.quartz.simpl.RAMJobStore");
    defaultScheduler = new StdSchedulerFactory(properties).getScheduler();
    CountDownLatch completed = new CountDownLatch(1);
    JobDataMap data = new JobDataMap();
    data.put("probeLatch", completed);
    var job = JobBuilder.newJob(ProbeJob.class).usingJobData(data).build();
    defaultScheduler.scheduleJob(job, TriggerBuilder.newTrigger().forJob(job).startNow().build());
    defaultScheduler.start();
    return completed;
  }

  private ControlledTask task(long id) {
    ControlledTask task = new ControlledTask(id);
    tasks.put(id, task);
    return task;
  }

  private ControlledTask taskFor(long id) {
    ControlledTask task = tasks.get(id);
    if (task == null) throw new AssertionError("Unknown task " + id);
    return task;
  }

  private static void await(CountDownLatch latch, String message) throws InterruptedException {
    assertTrue(message, latch.await(5, TimeUnit.SECONDS));
  }

  private static Prepared prepared() {
    return new Prepared(
        new AiReviewChatRequest(
            "Source",
            "Target",
            "fr",
            "Description",
            42L,
            List.of(new AiReviewChatMessage("user", "Review this translation."))),
        17L,
        new Settings("balanced", "selected-model", "low", "low", "priority"));
  }

  private static AiReviewChatResponse response() {
    return new AiReviewChatResponse(
        new AiReviewChatMessage("assistant", "Review complete"), List.of(), null);
  }

  public static class ProbeJob implements Job {
    @Override
    public void execute(JobExecutionContext context) {
      ((CountDownLatch) context.getMergedJobDataMap().get("probeLatch")).countDown();
    }
  }

  private static class ControlledTask {
    final long id;
    final String token;
    final Instant deadline = Instant.now().plusSeconds(60);
    final PollableTask task = new PollableTask();
    final CompletableFuture<AiReviewChatResponse> provider = new CompletableFuture<>();
    final CompletableFuture<Void> httpSettled = new CompletableFuture<>();
    final AtomicBoolean claimed = new AtomicBoolean();
    final AtomicBoolean outputAvailable = new AtomicBoolean(true);
    final AtomicReference<AiReviewChatJob.Result> result = new AtomicReference<>();
    final CountDownLatch staged = new CountDownLatch(1);
    final CountDownLatch released = new CountDownLatch(1);
    final CountDownLatch finishAttempt = new CountDownLatch(1);
    final CountDownLatch finished = new CountDownLatch(1);

    ControlledTask(long id) {
      this.id = id;
      token = "attempt-" + id;
      task.setId(id);
      task.setName(AiReviewConfiguredChatJob.class.getCanonicalName());
      task.setCreatedDate(ZonedDateTime.now());
      task.setTimeout(60L);
    }
  }
}
