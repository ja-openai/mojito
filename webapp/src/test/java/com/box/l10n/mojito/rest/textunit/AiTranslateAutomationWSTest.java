package com.box.l10n.mojito.rest.textunit;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.box.l10n.mojito.entity.AiTranslateAutomationConfigEntity;
import com.box.l10n.mojito.entity.Locale;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.locale.LocaleService;
import com.box.l10n.mojito.service.oaitranslate.AiTranslateAutomationConfigRepository;
import com.box.l10n.mojito.service.oaitranslate.AiTranslateAutomationConfigService;
import com.box.l10n.mojito.service.oaitranslate.AiTranslateAutomationCronSchedulerService;
import com.box.l10n.mojito.service.repository.RepositoryRepository;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

public class AiTranslateAutomationWSTest {

  private final AiTranslateAutomationConfigRepository configs =
      mock(AiTranslateAutomationConfigRepository.class);
  private final RepositoryRepository repositories = mock(RepositoryRepository.class);
  private final LocaleService locales = mock(LocaleService.class);
  private final AiTranslateAutomationCronSchedulerService cron =
      mock(AiTranslateAutomationCronSchedulerService.class);
  private final AiTranslateAutomationConfigEntity entity = new AiTranslateAutomationConfigEntity();
  private MockMvc mvc;

  @Before
  public void setUp() {
    when(configs.findFirstByOrderByIdAsc()).thenReturn(entity);
    Repository repository = new Repository();
    repository.setId(7L);
    when(repositories.findNoGraphById(7L)).thenReturn(Optional.of(repository));
    Locale french = new Locale();
    french.setBcp47Tag("fr-FR");
    when(locales.findByBcp47Tag("fr-FR")).thenReturn(french);
    mvc =
        MockMvcBuilders.standaloneSetup(
                new AiTranslateAutomationWS(
                    new AiTranslateAutomationConfigService(
                        configs, new ObjectMapper(), repositories, locales),
                    cron,
                    null,
                    null,
                    null,
                    null))
            .build();
  }

  @Test
  public void localeExclusionsRoundTripThroughExistingConfigEndpoint() throws Exception {
    mvc.perform(
            put("/api/ai-translate/automation")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"enabled\":true,\"sourceTextMaxCountPerLocale\":100,\"excludedLocaleTagsByRepositoryId\":{\"7\":[\"fr-FR\"]}}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.excludedLocaleTagsByRepositoryId['7'][0]").value("fr-FR"));
    mvc.perform(get("/api/ai-translate/automation"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.excludedLocaleTagsByRepositoryId['7'][0]").value("fr-FR"));
  }

  @Test
  public void missingFieldPreservesPolicyAndExplicitEmptyMapClearsIt() throws Exception {
    entity.setExcludedLocaleTagsByRepositoryIdJson("{\"7\":[\"fr-FR\"]}");
    mvc.perform(
            put("/api/ai-translate/automation")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true,\"sourceTextMaxCountPerLocale\":100}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.excludedLocaleTagsByRepositoryId['7'][0]").value("fr-FR"));
    mvc.perform(
            put("/api/ai-translate/automation")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true,\"excludedLocaleTagsByRepositoryId\":{}}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.excludedLocaleTagsByRepositoryId").isEmpty());
  }

  @Test
  public void invalidLocaleReturnsBadRequestWithoutRescheduling() throws Exception {
    mvc.perform(
            put("/api/ai-translate/automation")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"enabled\":true,\"excludedLocaleTagsByRepositoryId\":{\"7\":[\"unknown\"]}}"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(cron);
  }
}
