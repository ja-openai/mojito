package com.box.l10n.mojito.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

@RunWith(SpringRunner.class)
@WebAppConfiguration
@ContextConfiguration(classes = WebSecurityConfigAuthorizationTest.TestConfiguration.class)
public class WebSecurityConfigAuthorizationTest {

  private static final String REPORT_PATH = "/api/admin/linguist-time-spent";
  private static final String RESULT_PATH = REPORT_PATH + "/report/results/test-result";
  private static final String RECOMPUTE_PATH = REPORT_PATH + "/recompute";
  private static final String RECOMPUTE_RESULT_PATH = RECOMPUTE_PATH + "/results/test-result";
  private static final String TRANSLATION_CORRECTIONS_PATH =
      "/api/admin/translation-corrections/apply";
  private static final String PREFERENCES_PATH = "/api/users/me/preferences";
  private static final String AI_REVIEW_JOBS_PATH = "/api/ai/review/jobs";
  private static final String AGENT_PROPOSAL_PATH = "/api/agent-reviews/projects/7/proposals/901";
  private static final List<String> HUMAN_REVIEW_ACTIONS =
      List.of("review-again", "reopen", "reopen-and-save");

  @Autowired WebApplicationContext applicationContext;

  private MockMvc mockMvc;

  @Before
  public void setup() {
    mockMvc =
        MockMvcBuilders.webAppContextSetup(applicationContext).apply(springSecurity()).build();
  }

  @Test
  public void authenticatedTranslatorsCanStartAndPollAiReviewJobs() throws Exception {
    for (String role : List.of("USER", "TRANSLATOR", "PM", "ADMIN")) {
      mockMvc
          .perform(post(AI_REVIEW_JOBS_PATH).with(user("test").roles(role)))
          .andExpect(status().isOk());
      mockMvc
          .perform(get(AI_REVIEW_JOBS_PATH + "/91").with(user("test").roles(role)))
          .andExpect(status().isOk());
    }
    mockMvc.perform(post(AI_REVIEW_JOBS_PATH)).andExpect(status().isForbidden());
    mockMvc.perform(get(AI_REVIEW_JOBS_PATH + "/91")).andExpect(status().isForbidden());
  }

  @Test
  public void projectManagersCannotReadGlobalLinguistReports() throws Exception {
    mockMvc
        .perform(get(REPORT_PATH).with(user("pm").roles("PM")))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(get(RESULT_PATH).with(user("pm").roles("PM")))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(post(RECOMPUTE_PATH).with(user("pm").roles("PM")))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(get(RECOMPUTE_RESULT_PATH).with(user("pm").roles("PM")))
        .andExpect(status().isForbidden());
  }

  @Test
  public void administratorsCanReadGlobalLinguistReports() throws Exception {
    mockMvc.perform(get(REPORT_PATH).with(user("admin").roles("ADMIN"))).andExpect(status().isOk());
    mockMvc.perform(get(RESULT_PATH).with(user("admin").roles("ADMIN"))).andExpect(status().isOk());
    mockMvc
        .perform(post(RECOMPUTE_PATH).with(user("admin").roles("ADMIN")))
        .andExpect(status().isOk());
    mockMvc
        .perform(get(RECOMPUTE_RESULT_PATH).with(user("admin").roles("ADMIN")))
        .andExpect(status().isOk());
  }

  @Test
  public void onlyAdministratorsCanApplyGuardedTranslationCorrections() throws Exception {
    mockMvc
        .perform(post(TRANSLATION_CORRECTIONS_PATH).with(user("pm").roles("PM")))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(post(TRANSLATION_CORRECTIONS_PATH).with(user("translator").roles("TRANSLATOR")))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(post(TRANSLATION_CORRECTIONS_PATH).with(user("admin").roles("ADMIN")))
        .andExpect(status().isOk());
  }

  @Test
  public void allAuthenticatedRolesCanReadAndSaveTheirPreferences() throws Exception {
    for (String role : List.of("USER", "TRANSLATOR", "PM", "ADMIN")) {
      mockMvc
          .perform(get(PREFERENCES_PATH).with(user("test").roles(role)))
          .andExpect(status().isOk());
      mockMvc
          .perform(patch(PREFERENCES_PATH).with(user("test").roles(role)))
          .andExpect(status().isOk());
    }
  }

  @Test
  public void preferenceRouteDoesNotOpenUserManagementOrAnonymousAccess() throws Exception {
    mockMvc.perform(get(PREFERENCES_PATH)).andExpect(status().isForbidden());
    mockMvc.perform(patch(PREFERENCES_PATH)).andExpect(status().isForbidden());
    for (String role : List.of("USER", "TRANSLATOR")) {
      mockMvc
          .perform(patch("/api/users/123").with(user("test").roles(role)))
          .andExpect(status().isForbidden());
      mockMvc
          .perform(get("/api/users/123/preferences").with(user("test").roles(role)))
          .andExpect(status().isForbidden());
    }
  }

  @Test
  public void translationRolesCanReopenAndSaveCompletedReviews() throws Exception {
    for (String role : List.of("TRANSLATOR", "PM", "ADMIN")) {
      for (String action : HUMAN_REVIEW_ACTIONS) {
        // Reason-only edits to completed reviews use reopen-and-save instead of /decision.
        mockMvc
            .perform(post(AGENT_PROPOSAL_PATH + "/" + action).with(user("test").roles(role)))
            .andExpect(status().isOk());
      }
    }
  }

  @Test
  public void humanReReviewRequiresAnAuthenticatedTranslationRole() throws Exception {
    for (String action : HUMAN_REVIEW_ACTIONS) {
      String path = AGENT_PROPOSAL_PATH + "/" + action;
      mockMvc.perform(post(path)).andExpect(status().isForbidden());
      mockMvc
          .perform(post(path).with(user("reader").roles("USER")))
          .andExpect(status().isForbidden());
    }
  }

  @Test
  public void humanReReviewAccessDoesNotOpenAgentRunManagementOrOtherProposalActions()
      throws Exception {
    for (String path :
        List.of(
            "/api/agent-reviews/runs",
            "/api/agent-reviews/runs/9/proposals",
            "/api/agent-reviews/runs/9/route",
            AGENT_PROPOSAL_PATH + "/other-action")) {
      for (String role : List.of("USER", "TRANSLATOR")) {
        mockMvc
            .perform(post(path).with(user("test").roles(role)))
            .andExpect(status().isForbidden());
      }
      for (String role : List.of("PM", "ADMIN")) {
        mockMvc.perform(post(path).with(user("test").roles(role))).andExpect(status().isOk());
      }
    }
  }

  @Configuration
  @EnableWebMvc
  @EnableWebSecurity
  static class TestConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
      http.csrf(csrf -> csrf.disable());
      WebSecurityConfig.setAuthorizationRequests(http, List.of());
      return http.build();
    }

    @Bean
    LinguistTimeSpentStubController linguistTimeSpentStubController() {
      return new LinguistTimeSpentStubController();
    }
  }

  @RestController
  static class LinguistTimeSpentStubController {

    @PostMapping({
      AGENT_PROPOSAL_PATH + "/review-again",
      AGENT_PROPOSAL_PATH + "/reopen",
      AGENT_PROPOSAL_PATH + "/reopen-and-save",
      AGENT_PROPOSAL_PATH + "/other-action",
      "/api/agent-reviews/runs",
      "/api/agent-reviews/runs/9/proposals",
      "/api/agent-reviews/runs/9/route"
    })
    String agentReviewAction() {
      return "ok";
    }

    @PostMapping(AI_REVIEW_JOBS_PATH)
    String startReview() {
      return "ok";
    }

    @GetMapping(AI_REVIEW_JOBS_PATH + "/{taskId}")
    String reviewStatus(@PathVariable String taskId) {
      return taskId;
    }

    @GetMapping(PREFERENCES_PATH)
    String getPreferences() {
      return "ok";
    }

    @PatchMapping(PREFERENCES_PATH)
    String patchPreferences() {
      return "ok";
    }

    @GetMapping(REPORT_PATH)
    String report() {
      return "ok";
    }

    @GetMapping(REPORT_PATH + "/report/results/{resultId}")
    String result(@PathVariable String resultId) {
      return resultId;
    }

    @PostMapping(RECOMPUTE_PATH)
    String recompute() {
      return "ok";
    }

    @GetMapping(RECOMPUTE_PATH + "/results/{resultId}")
    String recomputeResult(@PathVariable String resultId) {
      return resultId;
    }

    @PostMapping(TRANSLATION_CORRECTIONS_PATH)
    String applyTranslationCorrections() {
      return "ok";
    }
  }
}
