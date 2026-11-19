package com.box.l10n.mojito.service.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.box.l10n.mojito.entity.Locale;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.RepositoryLocale;
import com.box.l10n.mojito.rest.repository.RepositoryTargetLocalesWS;
import com.box.l10n.mojito.service.locale.LocaleService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.Before;
import org.junit.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/** HTTP validation and transaction boundaries; Hibernate persistence is tested separately. */
public class RepositoryTargetLocalesTest {

  private static final String PATH = "/api/repositories/7/target-locales";
  private final RepositoryRepository repositories = mock(RepositoryRepository.class);
  private final LocaleService locales = mock(LocaleService.class);
  private final RepositoryService repositoryService = mock(RepositoryService.class);
  private final Repository repository = new Repository();
  private final Map<String, Locale> catalog = new LinkedHashMap<>();
  private final RepositoryTargetLocalesService targetLocales =
      new RepositoryTargetLocalesService(repositories, locales, repositoryService);
  private MockMvc mvc;

  @Before
  public void setup() {
    for (String tag : List.of("en", "fr", "fr-CA", "ja")) {
      Locale locale = new Locale();
      locale.setBcp47Tag(tag);
      catalog.put(tag, locale);
    }
    when(locales.findByBcp47Tag(anyString()))
        .thenAnswer(
            invocation ->
                catalog.values().stream()
                    .filter(
                        locale -> locale.getBcp47Tag().equalsIgnoreCase(invocation.getArgument(0)))
                    .findFirst()
                    .orElse(null));
    repository.setId(7L);
    repository.setSourceLocale(catalog.get("en"));
    RepositoryLocale root = addExisting("en", null, false);
    RepositoryLocale french = addExisting("fr", root, true);
    addExisting("fr-CA", french, false);
    when(repositories.findNoGraphById(7L)).thenReturn(Optional.of(repository));
    when(repositories.findNoGraphByIdForUpdate(7L)).thenReturn(Optional.of(repository));
    mvc = MockMvcBuilders.standaloneSetup(new RepositoryTargetLocalesWS(targetLocales)).build();
  }

  @Test
  public void readsOnlyTargetsAlongsideTheUnchangedSource() throws Exception {
    mvc.perform(get(PATH))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sourceLocaleTag").value("en"))
        .andExpect(jsonPath("$.targetLocaleTags").value(contains("fr", "fr-CA")));
    verifyNoInteractions(repositoryService);
  }

  @Test
  public void canonicalizesAndDeduplicatesTargetsBeforeUpdatingTheLockedRepository()
      throws Exception {
    mvc.perform(
            put(PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content("[\" fr \",\"FR-ca\",\"ja\",\"ja\"]"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sourceLocaleTag").value("en"))
        .andExpect(jsonPath("$.targetLocaleTags").value(contains("fr", "fr-CA", "ja")));
    verify(repositories).findNoGraphByIdForUpdate(7L);
    verify(repositoryService)
        .updateRepositoryTargetLocales(repository, Set.of("fr", "fr-CA", "ja"));
  }

  @Test
  public void allowsRemovingAParentTogetherWithItsChildren() throws Exception {
    mvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content("[\"ja\"]"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.targetLocaleTags").value(contains("ja")));
    verify(repositoryService).updateRepositoryTargetLocales(repository, Set.of("ja"));
  }

  @Test
  public void forwardsAnEmptySelectionAsAnExplicitClear() throws Exception {
    mvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content("[]"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sourceLocaleTag").value("en"))
        .andExpect(jsonPath("$.targetLocaleTags").isEmpty());
    verify(repositoryService).updateRepositoryTargetLocales(repository, Set.of());
  }

  @Test
  public void rejectsRemovingAnInheritedParentWithAnActionableResponseBeforeAnyMutation()
      throws Exception {
    mvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content("[\"fr-CA\",\"ja\"]"))
        .andExpect(status().isBadRequest())
        .andExpect(content().string(containsString("Keep fr selected or remove fr-CA too.")));
    verifyNoInteractions(repositoryService);
  }

  @Test
  public void rejectsInvalidTargetsAndDoesNotRewriteAnUnchangedSelection() throws Exception {
    for (String input : List.of("null", "[null]", "[\" \"]", "[\"en\"]", "[\"ja\",\"unknown\"]")) {
      mvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content(input))
          .andExpect(status().isBadRequest());
    }
    mvc.perform(
            put(PATH).contentType(MediaType.APPLICATION_JSON).content("[\"FR-ca\",\"fr\",\"fr\"]"))
        .andExpect(status().isOk());
    verifyNoInteractions(repositoryService);
  }

  @Test
  public void rollsBackChangesWhenTheUpdateThrowsACheckedCreationFailure() throws Exception {
    var dataSource =
        new DriverManagerDataSource(
            "jdbc:hsqldb:mem:target-locales-" + UUID.randomUUID(), "sa", "");
    var jdbc = new JdbcTemplate(dataSource);
    try {
      jdbc.execute("create table target_change_marker (id bigint primary key)");
      jdbc.update("insert into target_change_marker values (1)");
      doAnswer(
              invocation -> {
                jdbc.update("delete from target_change_marker");
                throw new RepositoryLocaleCreationException("Could not add target locale");
              })
          .when(repositoryService)
          .updateRepositoryTargetLocales(repository, Set.of("ja"));
      var transactions = new TransactionInterceptor();
      transactions.setTransactionManager(new DataSourceTransactionManager(dataSource));
      transactions.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
      var proxy = new ProxyFactory(targetLocales);
      proxy.setProxyTargetClass(true);
      proxy.addAdvice(transactions);
      var transactionalMvc =
          MockMvcBuilders.standaloneSetup(
                  new RepositoryTargetLocalesWS((RepositoryTargetLocalesService) proxy.getProxy()))
              .build();

      transactionalMvc
          .perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content("[\"ja\"]"))
          .andExpect(status().isBadRequest())
          .andExpect(content().string("Could not add target locale"));
      verify(repositoryService).updateRepositoryTargetLocales(repository, Set.of("ja"));
      assertThat(jdbc.queryForObject("select count(*) from target_change_marker", Integer.class))
          .isEqualTo(1);
    } finally {
      jdbc.execute("shutdown");
    }
  }

  @Test
  public void missingAndDeletedRepositoriesAreNotEditable() throws Exception {
    mvc.perform(get("/api/repositories/9/target-locales")).andExpect(status().isNotFound());
    mvc.perform(
            put("/api/repositories/9/target-locales")
                .contentType(MediaType.APPLICATION_JSON)
                .content("[]"))
        .andExpect(status().isNotFound());
    repository.setDeleted(true);
    mvc.perform(get(PATH)).andExpect(status().isNotFound());
    mvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content("[]"))
        .andExpect(status().isNotFound());
    verifyNoInteractions(repositoryService);
  }

  private RepositoryLocale addExisting(
      String tag, RepositoryLocale parent, boolean fullyTranslated) {
    RepositoryLocale locale =
        new RepositoryLocale(repository, catalog.get(tag), fullyTranslated, parent);
    repository.getRepositoryLocales().add(locale);
    return locale;
  }
}
