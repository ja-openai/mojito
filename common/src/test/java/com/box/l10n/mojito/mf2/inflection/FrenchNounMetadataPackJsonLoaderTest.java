package com.box.l10n.mojito.mf2.inflection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.box.l10n.mojito.mf2.inflection.FrenchNounMetadataPackJsonLoader.FrenchNounMetadataPack;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class FrenchNounMetadataPackJsonLoaderTest {

  private final FrenchNounMetadataPackJsonLoader loader = new FrenchNounMetadataPackJsonLoader();

  @Test
  public void rejectNullObjectMapper() {
    assertThatThrownBy(() -> new FrenchNounMetadataPackJsonLoader(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("objectMapper");
  }

  @Test
  public void rejectNullJson() {
    assertThatThrownBy(() -> loader.load((String) null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("json");
  }

  @Test
  public void loadsGeneratedMetadataRowsAndExposesMorphology() {
    FrenchNounMetadataPack pack = loader.load(frenchNounMetadataPackJson());

    assertThat(pack.locale()).isEqualTo("fr");
    assertThat(pack.provenance().license()).isEqualTo("Unicode-3.0");
    assertThat(pack.provenance().sources()).hasSize(1);
    assertThat(pack.provenance().sources().get(0).sha256())
        .isEqualTo("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
    assertThat(pack.rows()).hasSize(3);
    assertThat(pack.ambiguousRows()).hasSize(1);
    assertThat(pack.summary().binaryLowerBoundBytes()).isEqualTo(58);
    assertThat(pack.find("abaca")).isPresent();
    assertThat(pack.find("abaca").orElseThrow().toMorphology().gender()).isEqualTo("masculine");
    assertThat(pack.find("\u00e9cole").orElseThrow().toMorphology().startsWithVowelSound())
        .isTrue();
    assertThat(pack.find("livres").orElseThrow().number()).isEqualTo("plural");
    assertThat(pack.findAmbiguous("livre")).isPresent();
    assertThat(pack.findAmbiguous("livre").orElseThrow().reasons())
        .containsExactly("multiple-genders");
    assertThat(pack.findAmbiguous("livre").orElseThrow().analyses())
        .extracting("gender")
        .containsExactly("feminine", "masculine");
    assertThat(pack.find("missing")).isEmpty();
  }

  @Test
  public void loadedMetadataPackCollectionsAreImmutable() {
    FrenchNounMetadataPack pack = loader.load(frenchNounMetadataPackJson());

    assertThatThrownBy(() -> pack.strings().add("new"))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> pack.rows().clear()).isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> pack.rowsBySurface().put("new", pack.rows().get(0)))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> pack.findAmbiguous("livre").orElseThrow().reasons().add("new"))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> pack.provenance().sources().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  public void loadsPythonGeneratedFixture() {
    FrenchNounMetadataPack pack =
        loader.load(
            readResource("com/box/l10n/mojito/mf2/inflection/fr_noun_metadata_pack_fixture.json"));

    assertThat(pack.provenance().license()).isEqualTo("Unicode-3.0");
    assertThat(pack.provenance().sources()).hasSize(2);
    assertThat(pack.provenance().sources().get(0).sha256())
        .isEqualTo("21e1a3d385db927d10d175a322ad72f55cac455ab6c960564c90fe6ac23fc53b");
    assertThat(pack.rows()).hasSize(12);
    assertThat(pack.ambiguousRows()).hasSize(4);
    assertThat(pack.find("abaissement").orElseThrow().gender()).isEqualTo("masculine");
    assertThat(pack.findAmbiguous("absinthe").orElseThrow().analyses())
        .extracting("gender")
        .containsExactly("feminine", "masculine");
  }

  @Test
  public void rejectsUnexpectedSchema() {
    String json =
        frenchNounMetadataPackJson()
            .replace(
                FrenchNounMetadataPackJsonLoader.EXPECTED_SCHEMA,
                "mojito-mf2-inflection/fr-noun-metadata-sample-pack/v999");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected French noun metadata schema");
  }

  @Test
  public void rejectsOutOfBoundsSurfaceIndex() {
    String json =
        """
        {
          "schema": "mojito-mf2-inflection/fr-noun-metadata-sample-pack/v0",
          "locale": "fr",
          "strings": ["abaca"],
          "rows": [
            {
              "surface": 9,
              "featureBits": 5,
              "gender": "masculine",
              "number": "singular",
              "elides": false,
              "inflectionPattern": "29"
            }
          ],
          "summary": {
            "rows": 1,
            "strings": 1,
            "stringPoolBytes": 6,
            "jsonBytes": 100,
            "binaryLowerBoundBytes": 14
          }
        }
        """;

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("String index out of bounds");
  }

  @Test
  public void rejectsDuplicateSurfaces() {
    String json =
        """
        {
          "schema": "mojito-mf2-inflection/fr-noun-metadata-sample-pack/v0",
          "locale": "fr",
          "strings": ["abaca"],
          "rows": [
            {
              "surface": 0,
              "featureBits": 5,
              "gender": "masculine",
              "number": "singular",
              "elides": false,
              "inflectionPattern": "29"
            },
            {
              "surface": 0,
              "featureBits": 5,
              "gender": "masculine",
              "number": "singular",
              "elides": false,
              "inflectionPattern": "29"
            }
          ],
          "summary": {
            "rows": 2,
            "strings": 1,
            "stringPoolBytes": 6,
            "jsonBytes": 100,
            "binaryLowerBoundBytes": 22
          }
        }
        """;

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Duplicate noun metadata surface");
  }

  @Test
  public void rejectsFeatureBitsThatDoNotMatchDebugMetadata() {
    String json =
        """
        {
          "schema": "mojito-mf2-inflection/fr-noun-metadata-sample-pack/v0",
          "locale": "fr",
          "strings": ["abaca"],
          "rows": [
            {
              "surface": 0,
              "featureBits": 6,
              "gender": "masculine",
              "number": "singular",
              "elides": false,
              "inflectionPattern": "29"
            }
          ],
          "summary": {
            "rows": 1,
            "strings": 1,
            "stringPoolBytes": 6,
            "jsonBytes": 100,
            "binaryLowerBoundBytes": 14
          }
        }
        """;

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Feature bits do not match row metadata");
  }

  @Test
  public void rejectsSummaryCountsThatDoNotMatchPayload() {
    String json =
        """
        {
          "schema": "mojito-mf2-inflection/fr-noun-metadata-sample-pack/v0",
          "locale": "fr",
          "strings": ["abaca"],
          "rows": [
            {
              "surface": 0,
              "featureBits": 5,
              "gender": "masculine",
              "number": "singular",
              "elides": false,
              "inflectionPattern": "29"
            }
          ],
          "summary": {
            "rows": 2,
            "strings": 1,
            "stringPoolBytes": 6,
            "jsonBytes": 100,
            "binaryLowerBoundBytes": 14
          }
        }
        """;

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Summary row count does not match");
  }

  private String frenchNounMetadataPackJson() {
    return """
        {
          "schema": "mojito-mf2-inflection/fr-noun-metadata-sample-pack/v0",
          "locale": "fr",
          "provenance": {
            "license": "Unicode-3.0",
            "generator": "dev-docs/experiments/mf2-inflection/fr_noun_pack_report.py",
            "sources": [
              {
                "path": "inflection/resources/org/unicode/inflection/dictionary/dictionary_fr.lst",
                "byteSize": 17255961,
                "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
              }
            ]
          },
          "strings": ["abaca", "\u00e9cole", "livres", "livre"],
          "rows": [
            {
              "surface": 0,
              "featureBits": 5,
              "gender": "masculine",
              "number": "singular",
              "elides": false,
              "inflectionPattern": "29"
            },
            {
              "surface": 1,
              "featureBits": 22,
              "gender": "feminine",
              "number": "singular",
              "elides": true,
              "inflectionPattern": "b1"
            },
            {
              "surface": 2,
              "featureBits": 10,
              "gender": "feminine",
              "number": "plural",
              "elides": false,
              "inflectionPattern": null
            }
          ],
          "ambiguousRows": [
            {
              "surface": 3,
              "reasons": ["multiple-genders"],
              "analyses": [
                {
                  "gender": "feminine",
                  "number": "singular",
                  "elides": false,
                  "inflectionPattern": null
                },
                {
                  "gender": "masculine",
                  "number": "singular",
                  "elides": false,
                  "inflectionPattern": null
                }
              ]
            }
          ],
          "summary": {
            "rows": 3,
            "ambiguousRows": 1,
            "strings": 4,
            "stringPoolBytes": 34,
            "jsonBytes": 650,
            "binaryLowerBoundBytes": 58
          }
        }
        """;
  }

  private String readResource(String path) {
    try (InputStream inputStream =
        Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
      assertThat(inputStream).as("resource %s", path).isNotNull();
      return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
