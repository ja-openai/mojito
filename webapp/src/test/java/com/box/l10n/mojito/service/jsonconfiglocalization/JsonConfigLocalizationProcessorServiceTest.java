package com.box.l10n.mojito.service.jsonconfiglocalization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.Locale;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.RepositoryLocale;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.repository.RepositoryRepository;
import com.box.l10n.mojito.service.tm.search.TextUnitDTO;
import com.box.l10n.mojito.service.tm.search.TextUnitSearcher;
import com.box.l10n.mojito.service.tm.search.TextUnitSearcherParameters;
import java.util.List;
import java.util.Optional;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

public class JsonConfigLocalizationProcessorServiceTest {

  private final ObjectMapper objectMapper = ObjectMapper.withNoFailOnUnknownProperties();
  private final TestJsonConfigLocalizationService jsonConfigLocalizationService =
      new TestJsonConfigLocalizationService();
  private final RepositoryRepository repositoryRepository =
      Mockito.mock(RepositoryRepository.class);
  private final TextUnitSearcher textUnitSearcher = Mockito.mock(TextUnitSearcher.class);
  private final JsonConfigLocalizationProcessorService service =
      new JsonConfigLocalizationProcessorService(
          objectMapper,
          jsonConfigLocalizationService,
          repositoryRepository,
          null,
          textUnitSearcher);

  @Test
  public void exportForRepositoryKeepsSourceLocaleFieldsInLocalizedConfig() throws Exception {
    Repository repository = repository();
    when(repositoryRepository.findById(7L)).thenReturn(Optional.of(repository));
    jsonConfigLocalizationService.setup =
        new JsonConfigLocalizationService.JsonConfigLocalization(
            1L,
            null,
            null,
            "worldcup",
            new JsonConfigLocalizationService.RepositoryRef(7L, "worldcup", "en", 1),
            "json-config-localization/strings.json",
            JsonConfigLocalizationService.PROVIDER_GENERIC_JSON,
            null,
            null,
            null,
            """
            {
              "hotTakes": [
                {
                  "id": "usa_brazil_blink",
                  "translations": {
                    "en-US": {
                      "title": "Original title",
                      "body": "Original body"
                    }
                  }
                }
              ]
            }
            """,
            """
            {
              "collectionKey": "hotTakes",
              "itemIdField": "id",
              "translationsField": "translations",
              "sourceLocaleTag": "en-US",
              "translatableFields": ["title", "body"]
            }
            """,
            "{\"fr\":\"fr-FR\"}",
            false,
            null,
            null,
            null);
    when(textUnitSearcher.search(any(TextUnitSearcherParameters.class)))
        .thenReturn(List.of(sourceTextUnit("usa_brazil_blink.title", "Edited title", 11L)))
        .thenReturn(List.of(targetTextUnit(11L, "fr", "Titre traduit")));

    JsonConfigLocalizationProcessorService.ExportResult result = service.exportForRepository(7L);

    assertThat(objectMapper.readTree(result.json()))
        .isEqualTo(
            objectMapper.readTree(
                """
                {
                  "hotTakes": [
                    {
                      "id": "usa_brazil_blink",
                      "translations": {
                        "en-US": {
                          "title": "Edited title",
                          "body": "Original body"
                        },
                        "fr-FR": {
                          "title": "Titre traduit"
                        }
                      }
                    }
                  ]
                }
                """));
    ArgumentCaptor<TextUnitSearcherParameters> parametersCaptor =
        ArgumentCaptor.forClass(TextUnitSearcherParameters.class);
    Mockito.verify(textUnitSearcher, Mockito.times(2)).search(parametersCaptor.capture());
    TextUnitSearcherParameters sourceSearchParameters = parametersCaptor.getAllValues().get(0);
    assertThat(sourceSearchParameters.getLocaleTags()).containsExactly("en");
    assertThat(sourceSearchParameters.isForRootLocale()).isTrue();
    assertThat(sourceSearchParameters.isRootLocaleExcluded()).isFalse();
  }

  @Test
  public void exportForRepositoryWarnsAboutActiveStringsOutsideSourceConfig() {
    Repository repository = repository();
    when(repositoryRepository.findById(7L)).thenReturn(Optional.of(repository));
    jsonConfigLocalizationService.setup =
        new JsonConfigLocalizationService.JsonConfigLocalization(
            1L,
            null,
            null,
            "worldcup",
            new JsonConfigLocalizationService.RepositoryRef(7L, "worldcup", "en", 1),
            "json-config-localization/strings.json",
            JsonConfigLocalizationService.PROVIDER_GENERIC_JSON,
            null,
            null,
            null,
            """
            {
              "hotTakes": [
                {
                  "translations": {
                    "en-US": {
                      "title": "Original title"
                    }
                  }
                }
              ]
            }
            """,
            """
            {
              "collectionKey": "hotTakes",
              "itemIdField": "id",
              "translationsField": "translations",
              "sourceLocaleTag": "en-US",
              "translatableFields": ["title"]
            }
            """,
            "{}",
            false,
            null,
            null,
            null);
    when(textUnitSearcher.search(any(TextUnitSearcherParameters.class)))
        .thenReturn(
            List.of(
                sourceTextUnit("hotTakes.0.title", "Original title", 11L),
                sourceTextUnit("item_1.title", "Old title", 12L)))
        .thenReturn(List.of());

    JsonConfigLocalizationProcessorService.ExportResult result = service.exportForRepository(7L);

    assertThat(result.warnings())
        .contains(
            "Active strings not present in Config JSON were not exported: item_1.title. Edit Config and extract again, or remove them from the bundle.");
  }

  @Test
  public void exportForRepositorySupportsFormatJsMessageMap() throws Exception {
    Repository repository = repository();
    when(repositoryRepository.findById(7L)).thenReturn(Optional.of(repository));
    jsonConfigLocalizationService.setup =
        new JsonConfigLocalizationService.JsonConfigLocalization(
            1L,
            null,
            null,
            "notifications",
            new JsonConfigLocalizationService.RepositoryRef(7L, "notifications", "en", 1),
            "json-config-localization/strings.json",
            JsonConfigLocalizationService.PROVIDER_GENERIC_JSON,
            null,
            null,
            null,
            """
            {
              "message.1": {
                "defaultMessage": "Saved notification",
                "description": "Shown after save"
              }
            }
            """,
            "{}",
            "{\"fr\":\"fr-FR\"}",
            false,
            null,
            null,
            null);
    when(textUnitSearcher.search(any(TextUnitSearcherParameters.class)))
        .thenReturn(List.of(sourceTextUnit("message.1", "Edited notification", 11L)))
        .thenReturn(List.of(targetTextUnit(11L, "fr", "Notification modifiee")));

    JsonConfigLocalizationProcessorService.ExportResult result = service.exportForRepository(7L);

    assertThat(objectMapper.readTree(result.json()))
        .isEqualTo(
            objectMapper.readTree(
                """
                {
                  "en": {
                    "message.1": "Edited notification"
                  },
                  "fr-FR": {
                    "message.1": "Notification modifiee"
                  }
                }
                """));
  }

  @Test
  public void exportForRepositorySupportsMultilingualFormatJsMessageMap() throws Exception {
    Repository repository = repository();
    when(repositoryRepository.findById(7L)).thenReturn(Optional.of(repository));
    jsonConfigLocalizationService.setup =
        new JsonConfigLocalizationService.JsonConfigLocalization(
            1L,
            null,
            null,
            "notifications",
            new JsonConfigLocalizationService.RepositoryRef(7L, "notifications", "en", 1),
            "json-config-localization/strings.json",
            JsonConfigLocalizationService.PROVIDER_GENERIC_JSON,
            null,
            null,
            null,
            """
            {
              "messages": {
                "message.1": {
                  "defaultMessage": "Saved notification",
                  "description": "Shown after save",
                  "translations": {
                    "fr-FR": "Ancienne notification"
                  }
                }
              }
            }
            """,
            """
            {
              "format": "FORMATJS_MULTILINGUAL_MAP",
              "collectionKey": "messages",
              "itemIdField": "id",
              "translationsField": "translations",
              "sourceLocaleTag": "en-US",
              "translatableFields": [],
              "sourceField": "defaultMessage",
              "commentField": "description"
            }
            """,
            "{\"fr\":\"fr-FR\"}",
            false,
            null,
            null,
            null);
    TextUnitDTO sourceTextUnit = sourceTextUnit("message.1", "Edited notification", 11L);
    sourceTextUnit.setComment("Updated copy");
    when(textUnitSearcher.search(any(TextUnitSearcherParameters.class)))
        .thenReturn(List.of(sourceTextUnit))
        .thenReturn(List.of(targetTextUnit(11L, "fr", "Notification modifiee")));

    JsonConfigLocalizationProcessorService.ExportResult result = service.exportForRepository(7L);

    assertThat(objectMapper.readTree(result.json()))
        .isEqualTo(
            objectMapper.readTree(
                """
                {
                  "messages": {
                    "message.1": {
                      "defaultMessage": "Edited notification",
                      "description": "Updated copy",
                      "translations": {
                        "fr-FR": "Notification modifiee"
                      }
                    }
                  }
                }
                """));
  }

  @Test
  public void exportForRepositorySupportsWildcardMultilingualFormatJsMessageMaps()
      throws Exception {
    Repository repository = repository();
    when(repositoryRepository.findById(7L)).thenReturn(Optional.of(repository));
    jsonConfigLocalizationService.setup =
        new JsonConfigLocalizationService.JsonConfigLocalization(
            1L,
            null,
            null,
            "notifications",
            new JsonConfigLocalizationService.RepositoryRef(7L, "notifications", "en", 1),
            "json-config-localization/strings.json",
            JsonConfigLocalizationService.PROVIDER_GENERIC_JSON,
            null,
            null,
            null,
            """
            {
              "surface": {
                "checkout": {
                  "messages": {
                    "checkout.pay": {
                      "defaultMessage": "Pay now",
                      "description": "Primary checkout button.",
                      "translations": {}
                    }
                  }
                },
                "profile": {
                  "settings": {
                    "messages": {
                      "profile.saved": {
                        "defaultMessage": "Profile saved",
                        "description": "Toast after profile changes are saved.",
                        "translations": {}
                      }
                    }
                  }
                }
              }
            }
            """,
            """
            {
              "format": "FORMATJS_MULTILINGUAL_MAP",
              "collectionKey": "surface.**.messages",
              "itemIdField": "id",
              "translationsField": "translations",
              "sourceLocaleTag": "en-US",
              "translatableFields": [],
              "sourceField": "defaultMessage",
              "commentField": "description"
            }
            """,
            "{\"fr\":\"fr-FR\"}",
            false,
            null,
            null,
            null);
    TextUnitDTO checkoutSourceTextUnit = sourceTextUnit("checkout.pay", "Pay now", 11L);
    checkoutSourceTextUnit.setComment("Primary checkout button.");
    TextUnitDTO profileSourceTextUnit = sourceTextUnit("profile.saved", "Profile saved", 12L);
    profileSourceTextUnit.setComment("Toast after profile changes are saved.");
    when(textUnitSearcher.search(any(TextUnitSearcherParameters.class)))
        .thenReturn(List.of(checkoutSourceTextUnit, profileSourceTextUnit))
        .thenReturn(
            List.of(
                targetTextUnit(11L, "fr", "Payer maintenant"),
                targetTextUnit(12L, "fr", "Profil enregistre")));

    JsonConfigLocalizationProcessorService.ExportResult result = service.exportForRepository(7L);

    assertThat(objectMapper.readTree(result.json()))
        .isEqualTo(
            objectMapper.readTree(
                """
                {
                  "surface": {
                    "checkout": {
                      "messages": {
                        "checkout.pay": {
                          "defaultMessage": "Pay now",
                          "description": "Primary checkout button.",
                          "translations": {
                            "fr-FR": "Payer maintenant"
                          }
                        }
                      }
                    },
                    "profile": {
                      "settings": {
                        "messages": {
                          "profile.saved": {
                            "defaultMessage": "Profile saved",
                            "description": "Toast after profile changes are saved.",
                            "translations": {
                              "fr-FR": "Profil enregistre"
                            }
                          }
                        }
                      }
                    }
                  }
                }
                """));
  }

  @Test
  public void exportForRepositoryKeepsEmptySourceTextInLocaleMap() throws Exception {
    Repository repository = repository();
    when(repositoryRepository.findById(7L)).thenReturn(Optional.of(repository));
    jsonConfigLocalizationService.setup =
        new JsonConfigLocalizationService.JsonConfigLocalization(
            1L,
            null,
            null,
            "notifications",
            new JsonConfigLocalizationService.RepositoryRef(7L, "notifications", "en", 1),
            "json-config-localization/strings.json",
            JsonConfigLocalizationService.PROVIDER_GENERIC_JSON,
            null,
            null,
            null,
            """
            {
              "message.1": {
                "defaultMessage": "",
                "description": "Shown after save"
              }
            }
            """,
            "{}",
            "{}",
            false,
            null,
            null,
            null);
    when(textUnitSearcher.search(any(TextUnitSearcherParameters.class)))
        .thenReturn(List.of(sourceTextUnit("message.1", "", 11L)))
        .thenReturn(List.of());

    JsonConfigLocalizationProcessorService.ExportResult result = service.exportForRepository(7L);

    assertThat(objectMapper.readTree(result.json()))
        .isEqualTo(
            objectMapper.readTree(
                """
                {
                  "en": {
                    "message.1": ""
                  }
                }
                """));
    assertThat(result.warnings())
        .doesNotContain("Missing source text for active string message.1.");
  }

  @Test
  public void exportForRepositoryTreatsNullSourceTextAsEmptyInLocaleMap() throws Exception {
    Repository repository = repository();
    when(repositoryRepository.findById(7L)).thenReturn(Optional.of(repository));
    jsonConfigLocalizationService.setup =
        new JsonConfigLocalizationService.JsonConfigLocalization(
            1L,
            null,
            null,
            "notifications",
            new JsonConfigLocalizationService.RepositoryRef(7L, "notifications", "en", 1),
            "json-config-localization/strings.json",
            JsonConfigLocalizationService.PROVIDER_GENERIC_JSON,
            null,
            null,
            null,
            """
            {
              "message.1": {
                "defaultMessage": "",
                "description": "Shown after save"
              }
            }
            """,
            "{}",
            "{}",
            false,
            null,
            null,
            null);
    when(textUnitSearcher.search(any(TextUnitSearcherParameters.class)))
        .thenReturn(List.of(sourceTextUnit("message.1", null, 11L)))
        .thenReturn(List.of());

    JsonConfigLocalizationProcessorService.ExportResult result = service.exportForRepository(7L);

    assertThat(objectMapper.readTree(result.json()))
        .isEqualTo(
            objectMapper.readTree(
                """
                {
                  "en": {
                    "message.1": ""
                  }
                }
                """));
    assertThat(result.warnings())
        .doesNotContain("Missing source text for active string message.1.");
  }

  @Test
  public void exportForRepositoryWritesEmptySourceTextInEmbeddedConfig() throws Exception {
    Repository repository = repository();
    when(repositoryRepository.findById(7L)).thenReturn(Optional.of(repository));
    jsonConfigLocalizationService.setup =
        new JsonConfigLocalizationService.JsonConfigLocalization(
            1L,
            null,
            null,
            "worldcup",
            new JsonConfigLocalizationService.RepositoryRef(7L, "worldcup", "en", 1),
            "json-config-localization/strings.json",
            JsonConfigLocalizationService.PROVIDER_GENERIC_JSON,
            null,
            null,
            null,
            """
            {
              "hotTakes": [
                {
                  "id": "usa_brazil_blink",
                  "translations": {
                    "en-US": {
                      "title": "Original title"
                    }
                  }
                }
              ]
            }
            """,
            """
            {
              "collectionKey": "hotTakes",
              "itemIdField": "id",
              "translationsField": "translations",
              "sourceLocaleTag": "en-US",
              "translatableFields": ["title"]
            }
            """,
            "{}",
            false,
            null,
            null,
            null);
    when(textUnitSearcher.search(any(TextUnitSearcherParameters.class)))
        .thenReturn(List.of(sourceTextUnit("usa_brazil_blink.title", null, 11L)))
        .thenReturn(List.of());

    JsonConfigLocalizationProcessorService.ExportResult result = service.exportForRepository(7L);

    assertThat(objectMapper.readTree(result.json()))
        .isEqualTo(
            objectMapper.readTree(
                """
                {
                  "hotTakes": [
                    {
                      "id": "usa_brazil_blink",
                      "translations": {
                        "en-US": {
                          "title": ""
                        }
                      }
                    }
                  ]
                }
                """));
    assertThat(result.warnings())
        .doesNotContain("Missing source text for active string usa_brazil_blink.title.");
  }

  @Test
  public void exportForRepositoryInfersFormatJsMapWhenEmbeddedMappingDoesNotMatchSource()
      throws Exception {
    Repository repository = repository();
    when(repositoryRepository.findById(7L)).thenReturn(Optional.of(repository));
    jsonConfigLocalizationService.setup =
        new JsonConfigLocalizationService.JsonConfigLocalization(
            1L,
            null,
            null,
            "notifications",
            new JsonConfigLocalizationService.RepositoryRef(7L, "notifications", "en", 1),
            "json-config-localization/strings.json",
            JsonConfigLocalizationService.PROVIDER_GENERIC_JSON,
            null,
            null,
            null,
            """
            {
              "message.1": {
                "defaultMessage": "Saved notification",
                "description": "Shown after save"
              }
            }
            """,
            """
            {
              "format": "EMBEDDED_TRANSLATIONS",
              "collectionKey": "messages",
              "itemIdField": "id",
              "translationsField": "translations",
              "sourceLocaleTag": "en-US",
              "translatableFields": ["text"]
            }
            """,
            "{\"fr\":\"fr-FR\"}",
            false,
            null,
            null,
            null);
    when(textUnitSearcher.search(any(TextUnitSearcherParameters.class)))
        .thenReturn(List.of(sourceTextUnit("message.1", "Edited notification", 11L)))
        .thenReturn(List.of(targetTextUnit(11L, "fr", "Notification modifiee")));

    JsonConfigLocalizationProcessorService.ExportResult result = service.exportForRepository(7L);

    assertThat(result.warnings()).contains("Inferred FormatJS message map from config.");
    assertThat(objectMapper.readTree(result.json()))
        .isEqualTo(
            objectMapper.readTree(
                """
                {
                  "en": {
                    "message.1": "Edited notification"
                  },
                  "fr-FR": {
                    "message.1": "Notification modifiee"
                  }
                }
                """));
  }

  @Test
  public void extractUsesCollectionIndexFallbackWhenItemsHaveNoStableId() {
    JsonConfigLocalizationProcessorService.ExtractionResult result =
        service.extract(
            new JsonConfigLocalizationProcessorService.ExtractionInput(
                "",
                """
                {
                  "hotTakes": [
                    {
                      "translations": {
                        "en-US": {
                          "title": "USA can make Brazil blink",
                          "body": "The upset is not Haiti beating Brazil."
                        }
                      }
                    }
                  ]
                }
                """,
                new JsonConfigLocalizationProcessorService.SourceConfigProfile(
                    "hotTakes", "id", "translations", "en-US", List.of("title", "body"))));

    assertThat(result.strings())
        .extracting(
            JsonConfigLocalizationProcessorService.JsonConfigString::stringId,
            JsonConfigLocalizationProcessorService.JsonConfigString::comment)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("hotTakes.0.body", ""),
            org.assertj.core.groups.Tuple.tuple("hotTakes.0.title", ""));
  }

  @Test
  public void extractAllowsEmptyEmbeddedSourceWhenSchemaDoesNotRequireMinLength() {
    JsonConfigLocalizationProcessorService.ExtractionResult result =
        service.extract(
            new JsonConfigLocalizationProcessorService.ExtractionInput(
                """
                {
                  "$schema": "https://json-schema.org/draft/2020-12/schema",
                  "type": "object",
                  "properties": {
                    "messages": {
                      "type": "array",
                      "items": {
                        "type": "object",
                        "properties": {
                          "id": { "type": "string" },
                          "translations": {
                            "type": "object",
                            "properties": {
                              "en-US": {
                                "type": "object",
                                "properties": {
                                  "text": { "type": "string" }
                                },
                                "required": ["text"]
                              }
                            }
                          }
                        },
                        "required": ["id", "translations"]
                      }
                    }
                  }
                }
                """,
                """
                {
                  "messages": [
                    {
                      "id": "message.1",
                      "translations": {
                        "en-US": {
                          "text": ""
                        }
                      }
                    }
                  ]
                }
                """,
                new JsonConfigLocalizationProcessorService.SourceConfigProfile(
                    "messages", "id", "translations", "en-US", List.of("text"))));

    assertThat(result.strings())
        .extracting(
            JsonConfigLocalizationProcessorService.JsonConfigString::stringId,
            JsonConfigLocalizationProcessorService.JsonConfigString::source)
        .containsExactly(org.assertj.core.groups.Tuple.tuple("message.1.text", ""));
    assertThat(result.warnings()).noneMatch(warning -> warning.contains("missing non-empty"));
  }

  @Test
  public void extractSkipsEmptyEmbeddedSourceWhenSchemaRequiresMinLength() {
    JsonConfigLocalizationProcessorService.ExtractionResult result =
        service.extract(
            new JsonConfigLocalizationProcessorService.ExtractionInput(
                """
                {
                  "$schema": "https://json-schema.org/draft/2020-12/schema",
                  "type": "object",
                  "properties": {
                    "messages": {
                      "type": "array",
                      "items": {
                        "type": "object",
                        "properties": {
                          "id": { "type": "string" },
                          "translations": {
                            "type": "object",
                            "properties": {
                              "en-US": {
                                "type": "object",
                                "properties": {
                                  "text": { "type": "string", "minLength": 1 }
                                },
                                "required": ["text"]
                              }
                            }
                          }
                        },
                        "required": ["id", "translations"]
                      }
                    }
                  }
                }
                """,
                """
                {
                  "messages": [
                    {
                      "id": "message.1",
                      "translations": {
                        "en-US": {
                          "text": ""
                        }
                      }
                    }
                  ]
                }
                """,
                new JsonConfigLocalizationProcessorService.SourceConfigProfile(
                    "messages", "id", "translations", "en-US", List.of("text"))));

    assertThat(result.strings()).isEmpty();
    assertThat(result.warnings()).contains("message.1.text is missing non-empty text; skipped.");
  }

  @Test
  public void extractSupportsFormatJsMessageMapWithoutCollectionKey() throws Exception {
    JsonConfigLocalizationProcessorService.SourceConfigProfile profile =
        objectMapper.readValue(
            """
            {
              "format": "FORMATJS_MAP",
              "collectionKey": "",
              "itemIdField": "id",
              "translationsField": "translations",
              "sourceLocaleTag": "en-US",
              "translatableFields": [],
              "sourceField": "defaultMessage",
              "commentField": "description"
            }
            """,
            JsonConfigLocalizationProcessorService.SourceConfigProfile.class);

    JsonConfigLocalizationProcessorService.ExtractionResult result =
        service.extract(
            new JsonConfigLocalizationProcessorService.ExtractionInput(
                "",
                """
                {
                  "message.1": {
                    "defaultMessage": "Saved notification",
                    "description": "Shown after save"
                  }
                }
                """,
                profile));

    assertThat(result.profile().format())
        .isEqualTo(JsonConfigLocalizationProcessorService.SourceConfigFormat.FORMATJS_MAP);
    assertThat(result.profile().collectionKey()).isEmpty();
    assertThat(result.strings())
        .extracting(
            JsonConfigLocalizationProcessorService.JsonConfigString::stringId,
            JsonConfigLocalizationProcessorService.JsonConfigString::source,
            JsonConfigLocalizationProcessorService.JsonConfigString::comment)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(
                "message.1", "Saved notification", "Shown after save"));
  }

  @Test
  public void extractAllowsEmptyFlatSourceWhenSchemaDoesNotRequireMinLength() {
    JsonConfigLocalizationProcessorService.ExtractionResult result =
        service.extract(
            new JsonConfigLocalizationProcessorService.ExtractionInput(
                """
                {
                  "$schema": "https://json-schema.org/draft/2020-12/schema",
                  "type": "object",
                  "properties": {
                    "messages": {
                      "type": "array",
                      "items": {
                        "type": "object",
                        "properties": {
                          "id": { "type": "string" },
                          "source": { "type": "string" },
                          "description": { "type": "string" }
                        },
                        "required": ["id", "source"]
                      }
                    }
                  }
                }
                """,
                """
                {
                  "messages": [
                    {
                      "id": "message.1",
                      "source": "",
                      "description": "Draft copy"
                    }
                  ]
                }
                """,
                new JsonConfigLocalizationProcessorService.SourceConfigProfile(
                    JsonConfigLocalizationProcessorService.SourceConfigFormat.FLAT_SOURCE_ARRAY,
                    "messages",
                    "id",
                    "translations",
                    "en-US",
                    List.of(),
                    "source",
                    "description")));

    assertThat(result.strings())
        .extracting(
            JsonConfigLocalizationProcessorService.JsonConfigString::stringId,
            JsonConfigLocalizationProcessorService.JsonConfigString::source,
            JsonConfigLocalizationProcessorService.JsonConfigString::comment)
        .containsExactly(org.assertj.core.groups.Tuple.tuple("message.1", "", "Draft copy"));
    assertThat(result.warnings()).noneMatch(warning -> warning.contains("missing non-empty"));
  }

  @Test
  public void extractSkipsEmptyFlatSourceWhenSchemaRequiresMinLength() {
    JsonConfigLocalizationProcessorService.ExtractionResult result =
        service.extract(
            new JsonConfigLocalizationProcessorService.ExtractionInput(
                """
                {
                  "$schema": "https://json-schema.org/draft/2020-12/schema",
                  "type": "object",
                  "properties": {
                    "messages": {
                      "type": "array",
                      "items": {
                        "type": "object",
                        "properties": {
                          "id": { "type": "string" },
                          "source": { "type": "string", "minLength": 1 },
                          "description": { "type": "string" }
                        },
                        "required": ["id", "source"]
                      }
                    }
                  }
                }
                """,
                """
                {
                  "messages": [
                    {
                      "id": "message.1",
                      "source": "",
                      "description": "Draft copy"
                    }
                  ]
                }
                """,
                new JsonConfigLocalizationProcessorService.SourceConfigProfile(
                    JsonConfigLocalizationProcessorService.SourceConfigFormat.FLAT_SOURCE_ARRAY,
                    "messages",
                    "id",
                    "translations",
                    "en-US",
                    List.of(),
                    "source",
                    "description")));

    assertThat(result.strings()).isEmpty();
    assertThat(result.warnings()).contains("message.1 is missing non-empty source; skipped.");
  }

  @Test
  public void extractAllowsEmptyFormatJsSourceWhenSchemaDoesNotRequireMinLength() {
    JsonConfigLocalizationProcessorService.ExtractionResult result =
        service.extract(
            new JsonConfigLocalizationProcessorService.ExtractionInput(
                """
                {
                  "$schema": "https://json-schema.org/draft/2020-12/schema",
                  "type": "object",
                  "additionalProperties": {
                    "type": "object",
                    "properties": {
                      "defaultMessage": { "type": "string" },
                      "description": { "type": "string" }
                    },
                    "required": ["defaultMessage"]
                  }
                }
                """,
                """
                {
                  "message.1": {
                    "defaultMessage": "",
                    "description": "Draft copy"
                  }
                }
                """,
                new JsonConfigLocalizationProcessorService.SourceConfigProfile(
                    JsonConfigLocalizationProcessorService.SourceConfigFormat.FORMATJS_MAP,
                    "",
                    "id",
                    "translations",
                    "en-US",
                    List.of(),
                    "defaultMessage",
                    "description")));

    assertThat(result.strings())
        .extracting(
            JsonConfigLocalizationProcessorService.JsonConfigString::stringId,
            JsonConfigLocalizationProcessorService.JsonConfigString::source,
            JsonConfigLocalizationProcessorService.JsonConfigString::comment)
        .containsExactly(org.assertj.core.groups.Tuple.tuple("message.1", "", "Draft copy"));
    assertThat(result.warnings()).noneMatch(warning -> warning.contains("missing non-empty"));
  }

  @Test
  public void extractSupportsMultilingualFormatJsMessageMap() {
    JsonConfigLocalizationProcessorService.ExtractionResult result =
        service.extract(
            new JsonConfigLocalizationProcessorService.ExtractionInput(
                "",
                """
                {
                  "messages": {
                    "message.1": {
                      "defaultMessage": "",
                      "description": "Draft copy",
                      "translations": {
                        "fr-FR": "Brouillon"
                      }
                    }
                  }
                }
                """,
                new JsonConfigLocalizationProcessorService.SourceConfigProfile(
                    JsonConfigLocalizationProcessorService.SourceConfigFormat
                        .FORMATJS_MULTILINGUAL_MAP,
                    "messages",
                    "id",
                    "translations",
                    "en-US",
                    List.of(),
                    "defaultMessage",
                    "description")));

    assertThat(result.profile().format())
        .isEqualTo(
            JsonConfigLocalizationProcessorService.SourceConfigFormat.FORMATJS_MULTILINGUAL_MAP);
    assertThat(result.profile().collectionKey()).isEqualTo("messages");
    assertThat(result.strings())
        .extracting(
            JsonConfigLocalizationProcessorService.JsonConfigString::stringId,
            JsonConfigLocalizationProcessorService.JsonConfigString::source,
            JsonConfigLocalizationProcessorService.JsonConfigString::comment)
        .containsExactly(org.assertj.core.groups.Tuple.tuple("message.1", "", "Draft copy"));
  }

  @Test
  public void extractSupportsWildcardMultilingualFormatJsMessageMaps() {
    JsonConfigLocalizationProcessorService.ExtractionResult result =
        service.extract(
            new JsonConfigLocalizationProcessorService.ExtractionInput(
                "",
                """
                {
                  "surface": {
                    "checkout": {
                      "messages": {
                        "checkout.pay": {
                          "defaultMessage": "Pay now",
                          "description": "Primary checkout button.",
                          "translations": {
                            "fr-FR": "Payer maintenant"
                          }
                        }
                      }
                    },
                    "profile": {
                      "settings": {
                        "messages": {
                          "profile.saved": {
                            "defaultMessage": "Profile saved",
                            "description": "Toast after profile changes are saved.",
                            "translations": {
                              "fr-FR": "Profil enregistre"
                            }
                          }
                        }
                      }
                    }
                  }
                }
                """,
                new JsonConfigLocalizationProcessorService.SourceConfigProfile(
                    JsonConfigLocalizationProcessorService.SourceConfigFormat
                        .FORMATJS_MULTILINGUAL_MAP,
                    "surface.**.messages",
                    "id",
                    "translations",
                    "en-US",
                    List.of(),
                    "defaultMessage",
                    "description")));

    assertThat(result.strings())
        .extracting(
            JsonConfigLocalizationProcessorService.JsonConfigString::stringId,
            JsonConfigLocalizationProcessorService.JsonConfigString::source,
            JsonConfigLocalizationProcessorService.JsonConfigString::comment)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(
                "checkout.pay", "Pay now", "Primary checkout button."),
            org.assertj.core.groups.Tuple.tuple(
                "profile.saved", "Profile saved", "Toast after profile changes are saved."));
  }

  @Test
  public void extractInfersFormatJsMessageMapWhenEmbeddedMappingIsStale() {
    JsonConfigLocalizationProcessorService.ExtractionResult result =
        service.extract(
            new JsonConfigLocalizationProcessorService.ExtractionInput(
                "",
                """
                {
                  "message.1": {
                    "defaultMessage": "Saved notification",
                    "description": "Shown after save"
                  }
                }
                """,
                new JsonConfigLocalizationProcessorService.SourceConfigProfile(
                    JsonConfigLocalizationProcessorService.SourceConfigFormat.EMBEDDED_TRANSLATIONS,
                    "messages",
                    "id",
                    "translations",
                    "en-US",
                    List.of("text"),
                    "defaultMessage",
                    "description")));

    assertThat(result.profile().format())
        .isEqualTo(JsonConfigLocalizationProcessorService.SourceConfigFormat.FORMATJS_MAP);
    assertThat(result.profile().collectionKey()).isEmpty();
    assertThat(result.warnings()).contains("Inferred FormatJS message map from config.");
    assertThat(result.strings())
        .extracting(
            JsonConfigLocalizationProcessorService.JsonConfigString::stringId,
            JsonConfigLocalizationProcessorService.JsonConfigString::source,
            JsonConfigLocalizationProcessorService.JsonConfigString::comment)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(
                "message.1", "Saved notification", "Shown after save"));
  }

  @Test
  public void extractInfersFlatSourceArrayWhenEmbeddedMappingIsStale() {
    JsonConfigLocalizationProcessorService.ExtractionResult result =
        service.extract(
            new JsonConfigLocalizationProcessorService.ExtractionInput(
                "",
                """
                {
                  "messages": [
                    {
                      "id": "message.1",
                      "source": "Saved notification",
                      "description": "Shown after save"
                    }
                  ]
                }
                """,
                new JsonConfigLocalizationProcessorService.SourceConfigProfile(
                    JsonConfigLocalizationProcessorService.SourceConfigFormat.EMBEDDED_TRANSLATIONS,
                    "messages",
                    "id",
                    "translations",
                    "en-US",
                    List.of("text"),
                    "source",
                    "description")));

    assertThat(result.profile().format())
        .isEqualTo(JsonConfigLocalizationProcessorService.SourceConfigFormat.FLAT_SOURCE_ARRAY);
    assertThat(result.profile().collectionKey()).isEqualTo("messages");
    assertThat(result.warnings()).contains("Inferred flat source array from config.");
    assertThat(result.strings())
        .extracting(
            JsonConfigLocalizationProcessorService.JsonConfigString::stringId,
            JsonConfigLocalizationProcessorService.JsonConfigString::source,
            JsonConfigLocalizationProcessorService.JsonConfigString::comment)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(
                "message.1", "Saved notification", "Shown after save"));
  }

  @Test
  public void extractForRepositoryRejectsActiveStringIdsOutsideSourceConfig() {
    assertThatThrownBy(
            () ->
                service.extractForRepository(
                    7L,
                    new JsonConfigLocalizationProcessorService.ExtractForRepositoryInput(
                        "worldcup",
                        "json-config-localization/strings.json",
                        JsonConfigLocalizationService.PROVIDER_GENERIC_JSON,
                        null,
                        "",
                        """
                        {
                          "hotTakes": [
                            {
                              "id": "usa_brazil_blink",
                              "translations": {
                                "en-US": {
                                  "title": "USA can make Brazil blink",
                                  "body": "Original body"
                                }
                              }
                            }
                          ]
                        }
                        """,
                        new JsonConfigLocalizationProcessorService.SourceConfigProfile(
                            "hotTakes", "id", "translations", "en-US", List.of("title", "body")),
                        List.of(
                            new JsonConfigLocalizationProcessorService.JsonConfigString(
                                "icanaddmystring",
                                "This cannot map back into the source config.",
                                "",
                                true,
                                false)),
                        null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("icanaddmystring");
  }

  private Repository repository() {
    Repository repository = new Repository();
    repository.setName("worldcup");
    Locale sourceLocale = locale("en");
    repository.setSourceLocale(sourceLocale);
    RepositoryLocale rootRepositoryLocale =
        new RepositoryLocale(repository, sourceLocale, false, null);
    repository.getRepositoryLocales().add(rootRepositoryLocale);
    repository
        .getRepositoryLocales()
        .add(new RepositoryLocale(repository, locale("fr"), true, rootRepositoryLocale));
    return repository;
  }

  private Locale locale(String bcp47Tag) {
    Locale locale = new Locale();
    locale.setBcp47Tag(bcp47Tag);
    return locale;
  }

  private TextUnitDTO sourceTextUnit(String name, String source, Long tmTextUnitId) {
    TextUnitDTO textUnit = new TextUnitDTO();
    textUnit.setName(name);
    textUnit.setSource(source);
    textUnit.setTmTextUnitId(tmTextUnitId);
    textUnit.setLastSuccessfulAssetExtractionId(33L);
    textUnit.setAssetExtractionId(33L);
    return textUnit;
  }

  private TextUnitDTO targetTextUnit(Long tmTextUnitId, String targetLocale, String target) {
    TextUnitDTO textUnit = new TextUnitDTO();
    textUnit.setTmTextUnitId(tmTextUnitId);
    textUnit.setTargetLocale(targetLocale);
    textUnit.setTarget(target);
    return textUnit;
  }

  private static class TestJsonConfigLocalizationService extends JsonConfigLocalizationService {
    JsonConfigLocalization setup;

    TestJsonConfigLocalizationService() {
      super(null, null, null, null, null);
    }

    @Override
    public JsonConfigLocalization getByRepositoryId(Long repositoryId) {
      return setup;
    }

    @Override
    public JsonConfigLocalization upsertForRepository(
        Long repositoryId, JsonConfigLocalizationInput input) {
      return setup;
    }
  }
}
