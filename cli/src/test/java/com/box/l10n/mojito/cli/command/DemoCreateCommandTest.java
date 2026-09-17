package com.box.l10n.mojito.cli.command;

import com.box.l10n.mojito.cli.CLITestBase;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.RepositoryLocale;
import com.box.l10n.mojito.service.repository.RepositoryRepository;
import com.box.l10n.mojito.service.repository.RepositoryService;
import com.box.l10n.mojito.service.tm.search.TextUnitDTO;
import com.box.l10n.mojito.service.tm.search.TextUnitSearcher;
import com.box.l10n.mojito.service.tm.search.TextUnitSearcherParameters;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * @author jaurambault
 */
public class DemoCreateCommandTest extends CLITestBase {

  /** logger */
  static Logger logger = LoggerFactory.getLogger(DemoCreateCommandTest.class);

  protected static final String COMMAND_ERROR_MESSAGE = "Error creating repository";

  @Autowired RepositoryRepository repositoryRepository;

  @Autowired RepositoryService repositoryService;

  @Autowired TextUnitSearcher textUnitSearcher;

  @Test
  public void testDemoCreate() throws Exception {

    String testRepoName = testIdWatcher.getEntityName("repository");

    logger.debug("Creating repo with name: {}", testRepoName);

    L10nJCommander command = getL10nJCommander();
    command.run(
        "demo-create", "-n", testRepoName, "-o", getTargetTestDir("outputDir").getAbsolutePath());
    Assert.assertEquals("Demo creation should succeed", 0, command.getExitCode());

    Repository repository = repositoryRepository.findByName(testRepoName);
    Assert.assertEquals("en", repository.getSourceLocale().getBcp47Tag());
    Map<String, RepositoryLocale> locales =
        repository.getRepositoryLocales().stream()
            .collect(
                Collectors.toMap(locale -> locale.getLocale().getBcp47Tag(), Function.identity()));
    Assert.assertEquals(
        Set.of(
            "en", "da", "de", "en-GB", "en-CA", "en-AU", "es", "fi", "fr", "fr-CA", "it", "ja",
            "ko", "nb", "nl", "pl", "pt-BR", "ru", "sv", "tr", "zh-Hans", "zh-Hant"),
        locales.keySet());
    Assert.assertEquals("fr", locales.get("fr-CA").getParentLocale().getLocale().getBcp47Tag());
    for (String locale : List.of("en-CA", "en-AU")) {
      Assert.assertEquals("en-GB", locales.get(locale).getParentLocale().getLocale().getBcp47Tag());
    }
    for (String locale : List.of("en-GB", "en-CA", "en-AU", "fr-CA")) {
      Assert.assertFalse(locales.get(locale).isToBeFullyTranslated());
    }

    TextUnitSearcherParameters searchParameters = new TextUnitSearcherParameters();
    searchParameters.setRepositoryIds(repository.getId());

    List<TextUnitDTO> search = textUnitSearcher.search(searchParameters);
    Assert.assertEquals("Number of translations added not correct", 1575, search.size());
    for (String locale :
        List.of(
            "da", "de", "es", "fi", "fr", "it", "ja", "ko", "nb", "nl", "pl", "pt-BR", "ru", "sv",
            "tr", "zh-Hans", "zh-Hant")) {
      Assert.assertEquals(
          "Every existing translation should be imported under " + locale,
          75,
          search.stream()
              .filter(row -> locale.equals(row.getTargetLocale()) && row.getTarget() != null)
              .count());
    }
    Map<String, String> emailTranslations =
        search.stream()
            .filter(row -> "ContactInfo.customSupportEmail".equals(row.getName()))
            .filter(row -> row.getTarget() != null)
            .collect(Collectors.toMap(TextUnitDTO::getTargetLocale, TextUnitDTO::getTarget));
    Assert.assertEquals("E-mail", emailTranslations.get("fr"));
    Assert.assertEquals("E-Mail-Adresse", emailTranslations.get("de"));
    Assert.assertEquals("Correo electrónico", emailTranslations.get("es"));
    Assert.assertEquals("メール", emailTranslations.get("ja"));
    Assert.assertEquals("电邮", emailTranslations.get("zh-Hans"));
    Assert.assertEquals("電子郵件", emailTranslations.get("zh-Hant"));

    checkExpectedGeneratedResources();
  }
}
