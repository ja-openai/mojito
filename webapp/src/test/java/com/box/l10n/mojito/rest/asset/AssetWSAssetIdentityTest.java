package com.box.l10n.mojito.rest.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.box.l10n.mojito.entity.Asset;
import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.quartz.QuartzJobInfo;
import com.box.l10n.mojito.quartz.QuartzPollableTaskScheduler;
import com.box.l10n.mojito.service.asset.AssetRepository;
import com.box.l10n.mojito.service.pollableTask.PollableFuture;
import com.box.l10n.mojito.service.tm.AssetLocalizeAsyncJobSubmissionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

@RunWith(Parameterized.class)
public class AssetWSAssetIdentityTest {

  enum Route {
    QUARTZ,
    QUEUE,
    TRACKED_QUARTZ,
    PARALLEL
  }

  @Parameterized.Parameters(name = "{0}")
  public static Route[] routes() {
    return Route.values();
  }

  @Parameterized.Parameter public Route route;

  private static final long ASSET_ID = 1024L;
  private final ObjectMapper mapper = new ObjectMapper();
  private final AssetRepository assets = mock(AssetRepository.class);
  private final QuartzPollableTaskScheduler quartz = mock(QuartzPollableTaskScheduler.class);
  private final AssetLocalizeAsyncJobSubmissionService queue =
      mock(AssetLocalizeAsyncJobSubmissionService.class);
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final AssetWS controller = new AssetWS();
  private QuartzJobInfo<?, ?> scheduled;
  private MockMvc mvc;

  @Before
  public void setUp() {
    controller.assetRepository = assets;
    controller.quartzPollableTaskScheduler = quartz;
    controller.assetLocalizeAsyncJobSubmissionService = queue;
    controller.meterRegistry = meters;
    controller.schedulerName = "server-scheduler";
    controller.asyncJobQueueEnabled = route != Route.QUARTZ;
    controller.asyncJobQueueAssetLocalizeEnabled = route != Route.QUARTZ;
    mvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
            .build();

    Repository repository = new Repository();
    repository.setId(2048L);
    repository.setName("identity-test");
    Asset asset = new Asset();
    asset.setId(ASSET_ID);
    asset.setRepository(repository);
    when(assets.getReferenceById(ASSET_ID)).thenReturn(asset);
    PollableFuture<?> future = mock(PollableFuture.class);
    // Keep legacy PollableTask serialization advice outside this controller-boundary fixture.
    PollableTask task = mock(PollableTask.class);
    when(task.getId()).thenReturn(4096L);
    when(future.getPollableTask()).thenReturn(task);
    doAnswer(
            invocation -> {
              scheduled = invocation.getArgument(0);
              return future;
            })
        .when(quartz)
        .scheduleJob(any());
    doAnswer(
            invocation -> {
              scheduled = invocation.getArgument(0);
              return future;
            })
        .when(queue)
        .scheduleJob(any());
  }

  @After
  public void closeMeters() {
    meters.close();
  }

  @Test
  public void omittedBodyIdUsesPathAsset() throws Exception {
    assertAccepted(body());
  }

  @Test
  public void nullBodyIdUsesPathAsset() throws Exception {
    assertAccepted(body().putNull("assetId"));
  }

  @Test
  public void matchingBodyIdUsesPathAsset() throws Exception {
    assertAccepted(body().put("assetId", ASSET_ID));
  }

  @Test
  public void conflictingBodyIdReturnsBadRequestBeforeAdmission() throws Exception {
    mvc.perform(
            post(path())
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(body().put("assetId", ASSET_ID + 1))))
        .andExpect(status().isBadRequest());

    assertNoAdmission();
  }

  @Test
  public void conflictingBodyIdDoesNotMutateInputOrResolveAsset() throws Exception {
    ObjectNode json = body().put("assetId", ASSET_ID + 1);
    Object input =
        route == Route.PARALLEL
            ? mapper.treeToValue(json, MultiLocalizedAssetBody.class)
            : mapper.treeToValue(json, LocalizedAssetBody.class);
    JsonNode before = mapper.valueToTree(input);

    assertThatThrownBy(
            () -> {
              if (input instanceof MultiLocalizedAssetBody multi) {
                controller.getLocalizedAssetForContentParallel(ASSET_ID, multi);
              } else {
                controller.getLocalizedAssetForContentAsync(ASSET_ID, (LocalizedAssetBody) input);
              }
            })
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));

    assertThat(mapper.<JsonNode>valueToTree(input)).isEqualTo(before);
    assertNoAdmission();
  }

  private void assertAccepted(ObjectNode input) throws Exception {
    mvc.perform(
            post(path())
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(input)))
        .andExpect(status().isOk());

    verify(assets).getReferenceById(ASSET_ID);
    if (route == Route.QUEUE) {
      verify(queue).scheduleJob(any());
      verifyNoInteractions(quartz);
    } else {
      verify(quartz).scheduleJob(any());
      verifyNoInteractions(queue);
    }
    assertThat(scheduled).isNotNull();
    JsonNode scheduledInput = mapper.valueToTree(scheduled.getInput());
    assertThat(scheduledInput.path("assetId").longValue()).isEqualTo(ASSET_ID);
    assertThat(scheduledInput.path("pullRunName")).isEqualTo(input.path("pullRunName"));
    assertThat(scheduled.getScheduler()).isEqualTo("server-scheduler");
  }

  private void assertNoAdmission() {
    verifyNoInteractions(assets, quartz, queue);
    assertThat(meters.getMeters()).isEmpty();
    assertThat(scheduled).isNull();
  }

  private String path() {
    return "/api/assets/" + ASSET_ID + "/localized" + (route == Route.PARALLEL ? "/parallel" : "");
  }

  private ObjectNode body() {
    ObjectNode body = mapper.createObjectNode();
    body.put(route == Route.PARALLEL ? "sourceContent" : "content", "source");
    if (route == Route.TRACKED_QUARTZ) {
      body.put("pullRunName", "tracked-run");
    } else {
      body.putNull("pullRunName");
    }
    return body;
  }
}
