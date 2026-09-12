package com.box.l10n.mojito.security;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.box.l10n.mojito.queue.AsyncJobQueueInspectionService;
import com.box.l10n.mojito.queue.AsyncJobQueueInspectionService.AsyncJobDetails;
import com.box.l10n.mojito.queue.AsyncJobQueueInspectionService.AsyncJobExpiredLeaseStatusSummary;
import com.box.l10n.mojito.queue.AsyncJobQueueInspectionService.AsyncJobReadyStatusSummary;
import com.box.l10n.mojito.queue.AsyncJobQueueInspectionService.AsyncJobStatusCountSummary;
import com.box.l10n.mojito.queue.AsyncJobQueueInspectionService.AsyncJobSummary;
import com.box.l10n.mojito.rest.admin.AsyncJobQueueAdminWS;
import java.time.Instant;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/** HTTP authorization and response contracts, not authentication-provider or database tests. */
@RunWith(SpringRunner.class)
@WebAppConfiguration
@ContextConfiguration(classes = AsyncJobQueueAdminAuthorizationTest.TestConfiguration.class)
@TestPropertySource(properties = "l10n.org.async-job-queue.enabled=true")
public class AsyncJobQueueAdminAuthorizationTest {

  private static final String QUEUE = "test-queue";
  private static final String QUEUE_PATH = "/api/admin/async-job-queue/queues/" + QUEUE;
  private static final String JOB_PATH = QUEUE_PATH + "/jobs/42";
  private static final List<String> READ_PATHS =
      List.of(
          QUEUE_PATH + "/status-counts",
          QUEUE_PATH + "/ready-status",
          QUEUE_PATH + "/expired-lease-status",
          QUEUE_PATH + "/jobs",
          JOB_PATH);
  private static final String PAYLOAD = "{\"secret\":\"queue-payload-sentinel\"}";

  @Autowired WebApplicationContext context;
  @Autowired AsyncJobQueueInspectionService inspection;

  private MockMvc mvc;

  @Before
  public void setUp() {
    reset(inspection);
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  public void anonymousRequestsCannotInspectJobs() throws Exception {
    for (String path : READ_PATHS) {
      mvc.perform(get(path)).andExpect(status().isForbidden());
    }
    verifyNoInteractions(inspection);
  }

  @Test
  public void nonAdministratorsCannotInspectJobs() throws Exception {
    for (String role : List.of("USER", "TRANSLATOR", "PM")) {
      for (String path : READ_PATHS) {
        mvc.perform(get(path).with(user("caller").roles(role))).andExpect(status().isForbidden());
      }
    }
    verifyNoInteractions(inspection);
  }

  @Test
  public void administratorsCanInspectWithoutPayloadOrPreviewDisclosure() throws Exception {
    Instant now = Instant.parse("2026-09-12T00:00:00Z");
    when(inspection.countJobsByStatus(QUEUE))
        .thenReturn(List.of(new AsyncJobStatusCountSummary("failed", 1)));
    when(inspection.readyStatus(QUEUE))
        .thenReturn(new AsyncJobReadyStatusSummary(QUEUE, 2, now.minusSeconds(1), now, 1000));
    when(inspection.expiredLeaseStatus(QUEUE))
        .thenReturn(
            new AsyncJobExpiredLeaseStatusSummary(QUEUE, 3, now.minusSeconds(2), now, 2000));
    when(inspection.findJobs(QUEUE, "failed", 7))
        .thenReturn(
            List.of(
                new AsyncJobSummary(
                    "42",
                    QUEUE,
                    "failed",
                    now,
                    null,
                    null,
                    5,
                    "handler failed",
                    PAYLOAD.length(),
                    PAYLOAD,
                    now,
                    now)));
    when(inspection.getJob(QUEUE, "42"))
        .thenReturn(
            new AsyncJobDetails(
                "42", QUEUE, "failed", now, null, null, 5, "handler failed", PAYLOAD, now, now));

    mvc.perform(get(READ_PATHS.get(0)).with(user("admin").roles("ADMIN")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].count").value(1));
    mvc.perform(get(READ_PATHS.get(1)).with(user("admin").roles("ADMIN")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.count").value(2));
    mvc.perform(get(READ_PATHS.get(2)).with(user("admin").roles("ADMIN")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.count").value(3));
    mvc.perform(get(QUEUE_PATH + "/jobs").param("limit", "7").with(user("admin").roles("ADMIN")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value("42"))
        .andExpect(jsonPath("$[0].jobDataLength").value(PAYLOAD.length()))
        .andExpect(jsonPath("$[0].jobData").doesNotExist())
        .andExpect(jsonPath("$[0].jobDataPreview").doesNotExist())
        .andExpect(content().string(not(containsString("queue-payload-sentinel"))));
    mvc.perform(get(JOB_PATH).with(user("admin").roles("ADMIN")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("42"))
        .andExpect(jsonPath("$.lastError").value("handler failed"))
        .andExpect(jsonPath("$.jobDataLength").value(PAYLOAD.length()))
        .andExpect(jsonPath("$.jobData").doesNotExist())
        .andExpect(jsonPath("$.jobDataPreview").doesNotExist())
        .andExpect(content().string(not(containsString("queue-payload-sentinel"))));

    verify(inspection).countJobsByStatus(QUEUE);
    verify(inspection).readyStatus(QUEUE);
    verify(inspection).expiredLeaseStatus(QUEUE);
    verify(inspection).findJobs(QUEUE, "failed", 7);
    verify(inspection).getJob(QUEUE, "42");
    verifyNoMoreInteractions(inspection);
  }

  @Test
  public void administratorsHaveNoRawReplayOrDeleteHttpOperation() throws Exception {
    mvc.perform(post(JOB_PATH + "/requeue").with(user("admin").roles("ADMIN")))
        .andExpect(status().isNotFound());
    mvc.perform(delete(JOB_PATH).with(user("admin").roles("ADMIN")))
        .andExpect(status().isMethodNotAllowed());
    verifyNoInteractions(inspection);
  }

  @Configuration
  @EnableWebMvc
  @EnableWebSecurity
  @Import(AsyncJobQueueAdminWS.class)
  static class TestConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
      // Isolate the production authorization rules: CSRF must not mask a missing role guard.
      http.csrf(csrf -> csrf.disable());
      WebSecurityConfig.setAuthorizationRequests(http, List.of());
      return http.build();
    }

    @Bean
    AsyncJobQueueInspectionService inspectionService() {
      return mock(AsyncJobQueueInspectionService.class);
    }
  }
}
