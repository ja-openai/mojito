package com.box.l10n.mojito.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

  @Autowired WebApplicationContext applicationContext;

  private MockMvc mockMvc;

  @Before
  public void setup() {
    mockMvc =
        MockMvcBuilders.webAppContextSetup(applicationContext).apply(springSecurity()).build();
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
