package com.box.l10n.mojito.service.oaitranslate;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.AiTranslateAutomationConfigEntity;
import com.box.l10n.mojito.json.ObjectMapper;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.mockito.Mockito;

public class AiTranslateAutomationConfigServiceTest {

  private final AiTranslateAutomationConfigRepository repository =
      Mockito.mock(AiTranslateAutomationConfigRepository.class);

  private final AiTranslateAutomationConfigService service =
      new AiTranslateAutomationConfigService(repository, new ObjectMapper());

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
                " 0 0 * * * ? "));

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
                true, List.of(), Arrays.asList(7L, 3L, 7L, null), 100, null));

    verify(repository).save(entity);
    assertEquals("[]", entity.getRepositoryIdsJson());
    assertEquals("[3,7]", entity.getExcludedRepositoryIdsJson());
    assertEquals(List.of(), updated.repositoryIds());
    assertEquals(List.of(3L, 7L), updated.excludedRepositoryIds());
  }
}
