package com.box.l10n.mojito.rest.repository;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.box.l10n.mojito.entity.Locale;
import com.box.l10n.mojito.service.locale.LocaleService;
import com.box.l10n.mojito.service.repository.GlobalGenerationService;
import java.util.List;
import java.util.UUID;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

public class GlobalGenerationWSTest {

  private static final String PATH = "/api/generation-excluded-locales";

  private final LocaleService locales = mock(LocaleService.class);
  private JdbcTemplate jdbc;
  private GlobalGenerationService service;
  private MockMvc mvc;

  @Before
  public void setUp() {
    var dataSource =
        new DriverManagerDataSource(
            "jdbc:hsqldb:mem:global-generation-api-" + UUID.randomUUID() + ";sql.syntax_mys=true",
            "sa",
            "");
    jdbc = new JdbcTemplate(dataSource);
    new ResourceDatabasePopulator(
            new ClassPathResource("db/migration/V129__Generation_Settings.sql"))
        .execute(dataSource);
    service = new GlobalGenerationService(jdbc, locales);
    when(locales.findByBcp47Tag("en")).thenReturn(locale("en"));
    when(locales.findByBcp47Tag("fr-FR")).thenReturn(locale("fr-FR"));
    when(locales.findByBcp47Tag("FR-fr")).thenReturn(locale("fr-FR"));
    when(locales.findByBcp47Tag("ja-JP")).thenReturn(locale("ja-JP"));
    mvc = MockMvcBuilders.standaloneSetup(new GlobalGenerationWS(service)).build();
  }

  @After
  public void tearDown() {
    jdbc.execute("shutdown");
  }

  @Test
  public void exclusionsDefaultToEmptyAndSavedTagsAreCanonicalizedAndDeduplicated()
      throws Exception {
    mvc.perform(get(PATH)).andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
    mvc.perform(
            put(PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content("[\" FR-fr \",\"fr-FR\",\"ja-JP\"]"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0]").value("fr-FR"))
        .andExpect(jsonPath("$[1]").value("ja-JP"));
    mvc.perform(get(PATH))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0]").value("fr-FR"))
        .andExpect(jsonPath("$[1]").value("ja-JP"));
    assertEquals(List.of("fr-FR", "ja-JP"), service.getExcludedLocaleTags());
  }

  @Test
  public void emptyListClearsGlobalExclusions() throws Exception {
    service.updateExcludedLocaleTags(List.of("fr-FR"));
    mvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content("[]"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isEmpty());
    mvc.perform(get(PATH)).andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
    assertEquals(List.of(), service.getExcludedLocaleTags());
  }

  @Test
  public void anyKnownLocaleCanBeExcludedWithoutRepositoryConfiguration() throws Exception {
    mvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content("[\"en\"]"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0]").value("en"));
    assertEquals(List.of("en"), service.getExcludedLocaleTags());
  }

  @Test
  public void invalidInputsDoNotPartiallyReplaceExistingExclusions() throws Exception {
    service.updateExcludedLocaleTags(List.of("ja-JP"));
    for (String payload :
        List.of("null", "[null]", "[\" \"]", "[\"unknown\"]", "[\"fr-FR\",\"unknown\"]")) {
      mvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content(payload))
          .andExpect(status().isBadRequest());
      assertEquals(List.of("ja-JP"), service.getExcludedLocaleTags());
    }
    assertThrows(IllegalArgumentException.class, () -> service.updateExcludedLocaleTags(null));
    assertEquals(List.of("ja-JP"), service.getExcludedLocaleTags());
  }

  private Locale locale(String tag) {
    Locale locale = new Locale();
    locale.setBcp47Tag(tag);
    return locale;
  }
}
