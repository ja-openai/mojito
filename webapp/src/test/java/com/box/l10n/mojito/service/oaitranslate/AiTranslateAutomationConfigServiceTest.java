package com.box.l10n.mojito.service.oaitranslate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.AiTranslateAutomationConfigEntity;
import com.box.l10n.mojito.entity.Locale;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.locale.LocaleService;
import com.box.l10n.mojito.service.repository.RepositoryRepository;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.Test;
import org.mockito.Mockito;

public class AiTranslateAutomationConfigServiceTest {

  private final AiTranslateAutomationConfigRepository repository =
      Mockito.mock(AiTranslateAutomationConfigRepository.class);
  private final RepositoryRepository repositories = Mockito.mock(RepositoryRepository.class);
  private final LocaleService locales = Mockito.mock(LocaleService.class);

  private final AiTranslateAutomationConfigService service =
      new AiTranslateAutomationConfigService(repository, new ObjectMapper(), repositories, locales);

  @Test
  public void updateConfigStoresOneRepositoryScopeMode() {
    AiTranslateAutomationConfigEntity entity = new AiTranslateAutomationConfigEntity();
    entity.setRepositoryIdsJson("[4,2]");
    when(repository.findFirstByOrderByIdAsc()).thenReturn(entity);

    AiTranslateAutomationConfigService.Config updated =
        service.updateConfig(
            new AiTranslateAutomationConfigService.Config(
                true,
                Arrays.asList(99L, 2L, 99L, null),
                Arrays.asList(7L, 3L, 7L, null),
                0,
                " 0 0 * * * ? ",
                Map.of()));

    verify(repository).save(entity);
    assertEquals("[2,99]", entity.getRepositoryIdsJson());
    assertEquals("[]", entity.getExcludedRepositoryIdsJson());
    assertEquals(List.of(2L, 99L), updated.repositoryIds());
    assertEquals(List.of(), updated.excludedRepositoryIds());
    assertEquals(1, updated.sourceTextMaxCountPerLocale());
    assertEquals("0 0 * * * ?", updated.cronExpression());
  }

  @Test
  public void updateConfigStoresExclusionsWhenIncludedRepositoriesAreEmpty() {
    AiTranslateAutomationConfigEntity entity = new AiTranslateAutomationConfigEntity();
    entity.setRepositoryIdsJson("[4,2]");
    when(repository.findFirstByOrderByIdAsc()).thenReturn(entity);

    AiTranslateAutomationConfigService.Config updated =
        service.updateConfig(
            new AiTranslateAutomationConfigService.Config(
                true, List.of(), Arrays.asList(7L, 3L, 7L, null), 100, null, Map.of()));

    verify(repository).save(entity);
    assertEquals("[]", entity.getRepositoryIdsJson());
    assertEquals("[3,7]", entity.getExcludedRepositoryIdsJson());
    assertEquals(List.of(), updated.repositoryIds());
    assertEquals(List.of(3L, 7L), updated.excludedRepositoryIds());
  }

  @Test
  public void defaultsToNoLocaleExclusionsForMissingAndExistingConfig() {
    assertEquals(Map.of(), service.getConfig().excludedLocaleTagsByRepositoryId());
    when(repository.findFirstByOrderByIdAsc()).thenReturn(new AiTranslateAutomationConfigEntity());
    assertEquals(Map.of(), service.getConfig().excludedLocaleTagsByRepositoryId());
  }

  @Test
  public void normalizesLocaleTagsAndKeepsRepositoryScopesSeparate() {
    AiTranslateAutomationConfigEntity entity = new AiTranslateAutomationConfigEntity();
    when(repository.findFirstByOrderByIdAsc()).thenReturn(entity);
    addRepository(7L);
    addRepository(8L);
    addLocale("fr-FR", "FR-fr");
    addLocale("ja-JP");

    var updated =
        service.updateConfig(
            config(Map.of(7L, List.of("ja-JP", " FR-fr ", "fr-FR"), 8L, List.of("fr-FR"))));

    assertEquals(
        Map.of(7L, List.of("fr-FR", "ja-JP"), 8L, List.of("fr-FR")),
        updated.excludedLocaleTagsByRepositoryId());
    assertEquals(
        "{\"7\":[\"fr-FR\",\"ja-JP\"],\"8\":[\"fr-FR\"]}",
        entity.getExcludedLocaleTagsByRepositoryIdJson());
    assertEquals(
        updated.excludedLocaleTagsByRepositoryId(),
        service.getConfig().excludedLocaleTagsByRepositoryId());
    verify(repository).save(entity);
  }

  @Test
  public void canonicalLanguageDoesNotExpandToRegionalLocales() {
    addRepository(7L);
    addLocale("fr");
    assertEquals(
        Map.of(7L, List.of("fr")),
        service.updateConfig(config(Map.of(7L, List.of("fr")))).excludedLocaleTagsByRepositoryId());
  }

  @Test
  public void missingMapPreservesPolicyForOlderClientsAndEmptyMapClearsIt() {
    AiTranslateAutomationConfigEntity entity = new AiTranslateAutomationConfigEntity();
    entity.setExcludedLocaleTagsByRepositoryIdJson("{\"7\":[\"fr-FR\"]}");
    when(repository.findFirstByOrderByIdAsc()).thenReturn(entity);

    assertEquals(
        Map.of(7L, List.of("fr-FR")),
        service.updateConfig(config(null)).excludedLocaleTagsByRepositoryId());
    assertEquals("{\"7\":[\"fr-FR\"]}", entity.getExcludedLocaleTagsByRepositoryIdJson());
    assertEquals(
        Map.of(), service.updateConfig(config(Map.of())).excludedLocaleTagsByRepositoryId());
    assertEquals("{}", entity.getExcludedLocaleTagsByRepositoryIdJson());
  }

  @Test
  public void emptyLocaleListRemovesRepositoryExclusion() {
    addRepository(7L);
    assertEquals(
        Map.of(),
        service.updateConfig(config(Map.of(7L, List.of()))).excludedLocaleTagsByRepositoryId());
  }

  @Test
  public void unchangedStaleRepositoryRuleCanBePreservedOrRemovedButNotChanged() {
    AiTranslateAutomationConfigEntity entity = new AiTranslateAutomationConfigEntity();
    entity.setExcludedLocaleTagsByRepositoryIdJson("{\"7\":[\"fr-FR\"]}");
    when(repository.findFirstByOrderByIdAsc()).thenReturn(entity);
    Map<Long, List<String>> savedRule = Map.of(7L, List.of("fr-FR"));

    assertEquals(
        savedRule, service.updateConfig(config(savedRule)).excludedLocaleTagsByRepositoryId());
    Repository deleted = addRepository(7L);
    deleted.setDeleted(true);
    assertEquals(
        savedRule, service.updateConfig(config(savedRule)).excludedLocaleTagsByRepositoryId());
    assertThrows(
        IllegalArgumentException.class,
        () -> service.updateConfig(config(Map.of(7L, List.of("ja-JP")))));
    assertEquals(
        Map.of(), service.updateConfig(config(Map.of())).excludedLocaleTagsByRepositoryId());
  }

  @Test
  public void rejectsMissingAndDeletedRepositories() {
    assertThrows(
        IllegalArgumentException.class,
        () -> service.updateConfig(config(Map.of(7L, List.of("fr-FR")))));
    Repository deleted = addRepository(7L);
    deleted.setDeleted(true);
    assertThrows(
        IllegalArgumentException.class,
        () -> service.updateConfig(config(Map.of(7L, List.of("fr-FR")))));
    verify(repository, never()).save(any());
  }

  @Test
  public void rejectsUnknownAndBlankLocalesWithoutChangingSavedConfig() {
    AiTranslateAutomationConfigEntity entity = new AiTranslateAutomationConfigEntity();
    entity.setExcludedLocaleTagsByRepositoryIdJson("{\"7\":[\"ja-JP\"]}");
    when(repository.findFirstByOrderByIdAsc()).thenReturn(entity);
    addRepository(7L);
    addLocale("fr-FR");

    assertThrows(
        IllegalArgumentException.class,
        () -> service.updateConfig(config(Map.of(7L, List.of("fr-FR", "unknown")))));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.updateConfig(config(Map.of(7L, List.of(" ")))));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.updateConfig(config(Map.of(7L, Arrays.asList("fr-FR", null)))));
    Map<Long, List<String>> nullList = new HashMap<>();
    nullList.put(7L, null);
    assertThrows(IllegalArgumentException.class, () -> service.updateConfig(config(nullList)));

    assertEquals("{\"7\":[\"ja-JP\"]}", entity.getExcludedLocaleTagsByRepositoryIdJson());
    verify(repository, never()).save(any());
  }

  @Test
  public void exposesSavedConfigModificationDate() {
    assertNull(service.getLastModifiedDate());
    AiTranslateAutomationConfigEntity entity = new AiTranslateAutomationConfigEntity();
    ZonedDateTime modified = ZonedDateTime.parse("2026-09-23T12:00:00Z");
    entity.setLastModifiedDate(modified);
    when(repository.findFirstByOrderByIdAsc()).thenReturn(entity);
    assertEquals(modified, service.getLastModifiedDate());
  }

  private Repository addRepository(Long id) {
    Repository repo = new Repository();
    repo.setId(id);
    when(repositories.findNoGraphById(id)).thenReturn(Optional.of(repo));
    return repo;
  }

  private void addLocale(String canonicalTag, String... aliases) {
    Locale locale = new Locale();
    locale.setBcp47Tag(canonicalTag);
    when(locales.findByBcp47Tag(canonicalTag)).thenReturn(locale);
    for (String alias : aliases) {
      when(locales.findByBcp47Tag(alias)).thenReturn(locale);
    }
  }

  private AiTranslateAutomationConfigService.Config config(
      Map<Long, List<String>> localeExclusions) {
    return new AiTranslateAutomationConfigService.Config(
        true, List.of(), List.of(), 100, null, localeExclusions);
  }
}
