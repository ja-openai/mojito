package com.box.l10n.mojito.rest.repository;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.box.l10n.mojito.entity.Locale;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.RepositoryGenerationSettings;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.rest.View;
import com.box.l10n.mojito.service.locale.LocaleService;
import com.box.l10n.mojito.service.repository.GlobalGenerationService;
import com.box.l10n.mojito.service.repository.RepositoryGenerationService;
import com.box.l10n.mojito.service.repository.RepositoryGenerationSettingsRepository;
import com.box.l10n.mojito.service.repository.RepositoryRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

public class RepositoryGenerationWSTest {

  private static final String PATH = "/api/repositories/7/generation-excluded-locales";

  private final RepositoryRepository repositories = mock(RepositoryRepository.class);
  private final LocaleService locales = mock(LocaleService.class);
  private final Repository repository = new Repository();
  private final RepositoryGenerationSettingsRepository settingsRepository =
      mock(RepositoryGenerationSettingsRepository.class);
  private final Map<Long, RepositoryGenerationSettings> settingsRows = new HashMap<>();
  private final RepositoryGenerationService service =
      new RepositoryGenerationService(
          repositories, locales, mock(GlobalGenerationService.class), settingsRepository);
  private MockMvc mvc;

  @Before
  public void setUp() {
    repository.setId(7L);
    repository.setName("example-project");
    repository.setDescription("Existing repository metadata");
    Locale english = locale("en");
    repository.setSourceLocale(english);
    when(repositories.findNoGraphById(7L)).thenReturn(Optional.of(repository));
    when(locales.findByBcp47Tag("en")).thenReturn(english);
    when(locales.findByBcp47Tag("fr-FR")).thenReturn(locale("fr-FR"));
    when(locales.findByBcp47Tag("FR-fr")).thenReturn(locale("fr-FR"));
    when(locales.findByBcp47Tag("ja-JP")).thenReturn(locale("ja-JP"));
    when(settingsRepository.findById(any(Long.class)))
        .thenAnswer(invocation -> Optional.ofNullable(settingsRows.get(invocation.getArgument(0))));
    when(settingsRepository.save(any(RepositoryGenerationSettings.class)))
        .thenAnswer(
            invocation -> {
              RepositoryGenerationSettings saved = invocation.getArgument(0);
              settingsRows.put(saved.getRepositoryId(), saved);
              return saved;
            });
    mvc = MockMvcBuilders.standaloneSetup(new RepositoryGenerationWS(service)).build();
  }

  @After
  public void neverSavesTheRepositoryEntity() {
    verify(repositories, never()).save(any());
  }

  @Test
  public void existingRepositoryWithoutSettingsReturnsAnEmptyList() throws Exception {
    mvc.perform(get(PATH)).andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
    assertEquals(Map.of(), settingsRows);
    verify(settingsRepository, never()).save(any());
  }

  @Test
  public void exclusionsCanBeSavedBeforeLocaleOnboardingAndAreCanonicalizedAndDeduplicated()
      throws Exception {
    assertEquals(0, repository.getRepositoryLocales().size());
    ObjectMapper mapper = new ObjectMapper();
    String originalRepositoryJson = mapper.writeValueAsString(repository);
    mvc.perform(
            put(PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content("[\" FR-fr \",\"fr-FR\",\"ja-JP\"]"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0]").value("fr-FR"))
        .andExpect(jsonPath("$[1]").value("ja-JP"));
    verify(settingsRepository).save(any(RepositoryGenerationSettings.class));
    assertEquals(originalRepositoryJson, mapper.writeValueAsString(repository));
    mvc.perform(get(PATH))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0]").value("fr-FR"))
        .andExpect(jsonPath("$[1]").value("ja-JP"));
  }

  @Test
  public void emptyListClearsOnlyTheSelectedRepository() throws Exception {
    storeSettings(7L, List.of("fr-FR"));
    Repository other = new Repository();
    other.setId(8L);
    storeSettings(8L, List.of("ja-JP"));
    when(repositories.findNoGraphById(8L)).thenReturn(Optional.of(other));

    mvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content("[]"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isEmpty());
    assertEquals(List.of(), settingsRows.get(7L).getExcludedLocaleTags());
    assertEquals(List.of("ja-JP"), service.getExcludedLocaleTags(8L));
  }

  @Test
  public void invalidInputsDoNotChangeOrSaveExistingExclusions() throws Exception {
    storeSettings(7L, List.of("ja-JP"));
    for (String payload :
        List.of(
            "null", "[null]", "[\" \"]", "[\"unknown\"]", "[\"en\"]", "[\"fr-FR\",\"unknown\"]")) {
      mvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content(payload))
          .andExpect(status().isBadRequest());
      assertEquals(List.of("ja-JP"), settingsRows.get(7L).getExcludedLocaleTags());
    }
    assertThrows(IllegalArgumentException.class, () -> service.updateExcludedLocaleTags(7L, null));
    verify(settingsRepository, never()).save(any());
  }

  @Test
  public void validationReportsTheFirstInvalidLocaleWithoutWritingSettings() {
    assertEquals(
        "The source locale cannot be excluded from generation",
        assertThrows(
                IllegalArgumentException.class,
                () -> service.updateExcludedLocaleTags(7L, List.of("en", "unknown")))
            .getMessage());
    assertEquals(
        "Unknown excluded locale: unknown",
        assertThrows(
                IllegalArgumentException.class,
                () -> service.updateExcludedLocaleTags(7L, List.of("unknown", "en")))
            .getMessage());
    verifyNoInteractions(settingsRepository);
  }

  @Test
  public void missingAndDeletedRepositoriesReturnNotFound() throws Exception {
    when(repositories.findNoGraphById(7L)).thenReturn(Optional.empty());
    mvc.perform(get(PATH)).andExpect(status().isNotFound());
    mvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content("[]"))
        .andExpect(status().isNotFound());

    repository.setDeleted(true);
    when(repositories.findNoGraphById(7L)).thenReturn(Optional.of(repository));
    mvc.perform(get(PATH)).andExpect(status().isNotFound());
    mvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content("[]"))
        .andExpect(status().isNotFound());
    verifyNoInteractions(settingsRepository);
  }

  @Test
  public void repositoryJsonOmitsExclusionsAndOrdinaryUpdatesCannotOverwriteThem()
      throws Exception {
    storeSettings(7L, List.of("fr-FR"));
    ObjectMapper mapper = ObjectMapper.withNoFailOnUnknownProperties();
    String json = mapper.writerWithView(View.Repository.class).writeValueAsString(repository);
    assertFalse(mapper.readTree(json).has("generationExcludedLocaleTags"));
    assertFalse(
        mapper.readTree(mapper.writeValueAsString(repository)).has("generationExcludedLocaleTags"));

    mapper.readerForUpdating(repository).readValue("{\"description\":\"Updated\"}");
    assertEquals(List.of("fr-FR"), settingsRows.get(7L).getExcludedLocaleTags());
    mapper.readerForUpdating(repository).readValue("{\"generationExcludedLocaleTags\":[]}");
    assertEquals(List.of("fr-FR"), settingsRows.get(7L).getExcludedLocaleTags());
    verifyNoInteractions(settingsRepository);
  }

  private void storeSettings(Long repositoryId, List<String> tags) {
    RepositoryGenerationSettings settings = new RepositoryGenerationSettings();
    settings.setRepositoryId(repositoryId);
    settings.setExcludedLocaleTags(tags);
    settingsRows.put(repositoryId, settings);
  }

  private Locale locale(String tag) {
    Locale locale = new Locale();
    locale.setBcp47Tag(tag);
    return locale;
  }
}
