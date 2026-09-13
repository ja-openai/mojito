package com.box.l10n.mojito.security;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.box.l10n.mojito.rest.admin.AssetLocalizeAsyncJobRepairWS;
import com.box.l10n.mojito.service.tm.AssetLocalizeAsyncJobRepairService;
import com.box.l10n.mojito.service.tm.AssetLocalizeAsyncJobRepairService.RepairResult;
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

/** Repair HTTP authorization, not authentication-provider or database tests. */
@RunWith(SpringRunner.class)
@WebAppConfiguration
@ContextConfiguration(
    classes = AssetLocalizeAsyncJobRepairAuthorizationTest.TestConfiguration.class)
@TestPropertySource(
    properties = {
      "l10n.org.async-job-queue.enabled=true",
      "l10n.org.async-job-queue.asset-localize.enabled=true"
    })
public class AssetLocalizeAsyncJobRepairAuthorizationTest {

  private static final String REPAIR_PATH =
      "/api/admin/async-job-queue/assetlocalize/jobs/42/pollable-task/repair";

  @Autowired WebApplicationContext context;
  @Autowired AssetLocalizeAsyncJobRepairService repair;

  private MockMvc mvc;

  @Before
  public void setUp() {
    reset(repair);
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  public void anonymousRequestsCannotRepairJobs() throws Exception {
    mvc.perform(post(REPAIR_PATH)).andExpect(status().isForbidden());
    verifyNoInteractions(repair);
  }

  @Test
  public void nonAdministratorsCannotRepairJobs() throws Exception {
    for (String role : List.of("USER", "TRANSLATOR", "PM")) {
      mvc.perform(post(REPAIR_PATH).with(user("caller").roles(role)))
          .andExpect(status().isForbidden());
    }
    verifyNoInteractions(repair);
  }

  @Test
  public void administratorsCanInvokeRepairExactlyOnce() throws Exception {
    when(repair.repairTerminalPollableTask("42"))
        .thenReturn(new RepairResult("42", 73L, "done", "finished"));

    mvc.perform(post(REPAIR_PATH).with(user("admin").roles("ADMIN")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.asyncJobId").value("42"))
        .andExpect(jsonPath("$.pollableTaskId").value(73));
    verify(repair).repairTerminalPollableTask("42");
    verifyNoMoreInteractions(repair);
  }

  @Test
  public void administratorsCannotInvokeRepairWithGet() throws Exception {
    mvc.perform(get(REPAIR_PATH).with(user("admin").roles("ADMIN")))
        .andExpect(status().isMethodNotAllowed());
    verifyNoInteractions(repair);
  }

  @Configuration
  @EnableWebMvc
  @EnableWebSecurity
  @Import(AssetLocalizeAsyncJobRepairWS.class)
  static class TestConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
      // Isolate the production authorization rules: CSRF must not mask a missing role guard.
      http.csrf(csrf -> csrf.disable());
      WebSecurityConfig.setAuthorizationRequests(http, List.of());
      return http.build();
    }

    @Bean
    AssetLocalizeAsyncJobRepairService repairService() {
      return mock(AssetLocalizeAsyncJobRepairService.class);
    }
  }
}
