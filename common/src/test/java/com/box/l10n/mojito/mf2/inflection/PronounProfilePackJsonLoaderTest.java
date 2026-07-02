package com.box.l10n.mojito.mf2.inflection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.box.l10n.mojito.mf2.inflection.PronounProfilePackJsonLoader.LocaleProfile;
import com.box.l10n.mojito.mf2.inflection.PronounProfilePackJsonLoader.PronounProfilePack;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class PronounProfilePackJsonLoaderTest {

  private final PronounProfilePackJsonLoader loader = new PronounProfilePackJsonLoader();

  @Test
  public void rejectNullObjectMapper() {
    assertThatThrownBy(() -> new PronounProfilePackJsonLoader(null))
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
  public void loadGeneratedFixture() {
    PronounProfilePack pack = loader.load(pronounProfilePackJson());

    assertThat(pack.schema()).isEqualTo(PronounProfilePackJsonLoader.EXPECTED_SCHEMA);
    assertThat(pack.provenance().license()).isEqualTo("Unicode-3.0");
    assertThat(pack.provenance().sourceAuditSchema())
        .isEqualTo(LowInflectionLocaleAuditJsonLoader.EXPECTED_SCHEMA);
    assertThat(pack.summary().localeCount()).isEqualTo(9);
    assertThat(pack.summary().profileOnlyLocales())
        .containsExactly("en", "id", "ja", "ko", "ms", "th", "vi", "zh", "yue");
    assertThat(pack.summary().dataMaterializationRequiredLocales()).containsExactly("en", "ko");
    assertThat(pack.summary().profileOnlyNoopLocales())
        .containsExactly("id", "ja", "ms", "th", "vi", "zh", "yue");
    assertThat(pack.summary().pronounInventoryLocales())
        .containsExactly("en", "id", "ja", "ko", "ms", "th", "vi", "zh", "yue");
    assertThat(pack.summary().runtimeTermInflection()).isFalse();
    assertThat(pack.summary().totalPronounRows()).isEqualTo(162);
    assertThat(pack.summary().totalUniquePronounValues()).isEqualTo(137);

    LocaleProfile english = find(pack, "en");
    assertThat(english.mode()).isEqualTo("data-materialization-required");
    assertThat(english.dictionaryState()).isEqualTo("git-lfs-pointer");
    assertThat(english.inflectionalState()).isEqualTo("git-lfs-pointer");
    assertThat(english.pronounSource().byteSize()).isEqualTo(1518);
    assertThat(english.pronounSource().sha256())
        .isEqualTo("fa5b5b5a7d414e14582a856ba80520143d880ff6539f53e6f7e472603b02cd6f");
    assertEnglishPronounProfile(english);

    LocaleProfile korean = find(pack, "ko");
    assertThat(korean.mode()).isEqualTo("data-materialization-required");
    assertThat(korean.dictionaryState()).isEqualTo("git-lfs-pointer");
    assertThat(korean.inflectionalState()).isEqualTo("missing");
    assertThat(korean.pronounSource().byteSize()).isEqualTo(2221);
    assertKoreanPronounProfile(korean);

    LocaleProfile chinese = find(pack, "zh");
    assertThat(chinese.mode()).isEqualTo("profile-only-noop");
    assertThat(chinese.dictionaryState()).isEqualTo("empty-placeholder");
    assertThat(chinese.pronouns().rows()).isEqualTo(5);

    LocaleProfile cantonese = find(pack, "yue");
    assertThat(cantonese.mode()).isEqualTo("profile-only-noop");
    assertThat(cantonese.dictionaryState()).isEqualTo("missing");
    assertThat(cantonese.pronouns().rows()).isEqualTo(3);
  }

  @Test
  public void loadedCollectionsAreImmutable() {
    PronounProfilePack pack = loader.load(pronounProfilePackJson());

    assertThatThrownBy(() -> pack.locales().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> pack.summary().profileOnlyLocales().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> find(pack, "en").pronouns().features().put("other", 1))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> find(pack, "en").pronouns().samples().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  public void rejectUnexpectedSchema() {
    String json =
        pronounProfilePackJson()
            .replace(
                PronounProfilePackJsonLoader.EXPECTED_SCHEMA,
                "mojito-mf2-inflection/pronoun-profile-pack/v999");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected pronoun profile pack schema");
  }

  @Test
  public void rejectSummaryRuntimeTermInflection() {
    String json =
        pronounProfilePackJson()
            .replace(
                "    \"runtimeTermInflection\": false,\n    \"totalPronounRows\": 162",
                "    \"runtimeTermInflection\": true,\n    \"totalPronounRows\": 162");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Pronoun profile pack must not enable term inflection");
  }

  @Test
  public void rejectLocaleRuntimeTermInflection() {
    String json =
        pronounProfilePackJson()
            .replaceFirst("\"runtimeTermInflection\": false", "\"runtimeTermInflection\": true");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Pronoun profile locale must not enable term inflection");
  }

  @Test
  public void rejectRowTotalMismatch() {
    String json =
        pronounProfilePackJson().replace("\"totalPronounRows\": 162", "\"totalPronounRows\": 163");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Pronoun profile row total does not match profiles");
  }

  @Test
  public void rejectProfileOnlySummaryMismatch() {
    String json =
        pronounProfilePackJson().replaceFirst("\"profileOnly\": true", "\"profileOnly\": false");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Pronoun profile-only locales do not match profiles");
  }

  @Test
  public void rejectMaterializationRequiredWithoutLfsPointer() {
    String json =
        pronounProfilePackJson()
            .replaceFirst(
                "\"dictionaryState\": \"git-lfs-pointer\"", "\"dictionaryState\": \"materialized\"")
            .replaceFirst(
                "\"inflectionalState\": \"git-lfs-pointer\"",
                "\"inflectionalState\": \"materialized\"");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Pronoun profile materialization-required mode needs an LFS pointer");
  }

  @Test
  public void rejectNoopWithMaterializedDictionary() {
    String json =
        pronounProfilePackJson()
            .replace(
                "\"dictionaryState\": \"empty-placeholder\",\n"
                    + "      \"inflectionalState\": \"missing\",\n"
                    + "      \"locale\": \"id\"",
                "\"dictionaryState\": \"materialized\",\n"
                    + "      \"inflectionalState\": \"missing\",\n"
                    + "      \"locale\": \"id\"");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Pronoun profile no-op mode requires missing or empty dictionary");
  }

  @Test
  public void rejectMissingPronounSourceWithMetadata() {
    String json = pronounProfilePackJson().replaceFirst("\"exists\": true", "\"exists\": false");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("missing pronoun source must not carry file metadata");
  }

  @Test
  public void rejectPronounSourceGitLfsPointer() {
    String json =
        pronounProfilePackJson()
            .replaceFirst("\"gitLfsPointer\": false", "\"gitLfsPointer\": true");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Git LFS pronoun source requires oid and object size");
  }

  @Test
  public void rejectUnsupportedMode() {
    String json =
        pronounProfilePackJson()
            .replaceFirst("\"mode\": \"data-materialization-required\"", "\"mode\": \"render\"");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported pronoun profile mode value");
  }

  private void assertEnglishPronounProfile(LocaleProfile english) {
    assertThat(english.pronouns().rows()).isEqualTo(38);
    assertThat(english.pronouns().uniqueValues()).isEqualTo(32);
    assertThat(english.pronouns().cases())
        .containsEntry("accusative", 8)
        .containsEntry("genitive", 12)
        .containsEntry("nominative", 9);
    assertThat(english.pronouns().genders())
        .containsEntry("feminine", 5)
        .containsEntry("gender", 19)
        .containsEntry("masculine", 4)
        .containsEntry("neuter", 4);
    assertThat(english.pronouns().numbers())
        .containsEntry("plural", 10)
        .containsEntry("singular", 23);
    assertThat(english.pronouns().persons())
        .containsEntry("first", 10)
        .containsEntry("second", 7)
        .containsEntry("third", 21);
    assertThat(english.pronouns().registers()).isEmpty();
    assertThat(english.pronouns().samples().get(0).value()).isEqualTo("they");
    assertThat(english.pronouns().samples().get(0).features())
        .containsExactly("third", "plural", "nominative");
  }

  private void assertKoreanPronounProfile(LocaleProfile korean) {
    assertThat(korean.pronouns().rows()).isEqualTo(48);
    assertThat(korean.pronouns().uniqueValues()).isEqualTo(30);
    assertThat(korean.pronouns().cases())
        .containsEntry("accusative", 16)
        .containsEntry("genitive", 16)
        .containsEntry("nominative", 16);
    assertThat(korean.pronouns().numbers())
        .containsEntry("plural", 21)
        .containsEntry("singular", 27);
    assertThat(korean.pronouns().registers())
        .containsEntry("casual", 21)
        .containsEntry("formal", 27);
    assertThat(korean.pronouns().samples().get(0).value()).isEqualTo("그");
    assertThat(korean.pronouns().samples().get(0).features())
        .containsExactly("third", "singular", "nominative", "casual", "masculine");
  }

  private LocaleProfile find(PronounProfilePack pack, String locale) {
    return pack.locales().stream()
        .filter(profile -> locale.equals(profile.locale()))
        .findFirst()
        .orElseThrow();
  }

  private String pronounProfilePackJson() {
    return readResource("com/box/l10n/mojito/mf2/inflection/pronoun_profile_pack_fixture.json");
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
