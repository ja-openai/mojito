package com.box.l10n.mojito.mf2.inflection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.box.l10n.mojito.mf2.inflection.LowInflectionLocaleAuditJsonLoader.LocaleReport;
import com.box.l10n.mojito.mf2.inflection.LowInflectionLocaleAuditJsonLoader.LowInflectionLocaleAudit;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class LowInflectionLocaleAuditJsonLoaderTest {

  private final LowInflectionLocaleAuditJsonLoader loader =
      new LowInflectionLocaleAuditJsonLoader();

  @Test
  public void rejectNullObjectMapper() {
    assertThatThrownBy(() -> new LowInflectionLocaleAuditJsonLoader(null))
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
    LowInflectionLocaleAudit audit = loader.load(lowInflectionAuditJson());

    assertThat(audit.schema()).isEqualTo(LowInflectionLocaleAuditJsonLoader.EXPECTED_SCHEMA);
    assertThat(audit.provenance().license()).isEqualTo("Unicode-3.0");
    assertThat(audit.summary().localeCount()).isEqualTo(9);
    assertThat(audit.summary().locales())
        .containsExactly("en", "id", "ja", "ko", "ms", "th", "vi", "zh", "yue");
    assertThat(audit.summary().modeCounts())
        .containsEntry("data-materialization-required", 2)
        .containsEntry("profile-only-noop", 7);
    assertThat(audit.summary().dataMaterializationRequiredLocales()).containsExactly("en", "ko");
    assertThat(audit.summary().profileOnlyNoopLocales())
        .containsExactly("id", "ja", "ms", "th", "vi", "zh", "yue");
    assertThat(audit.summary().pronounInventoryLocales())
        .containsExactly("en", "id", "ja", "ko", "ms", "th", "vi", "zh", "yue");

    LocaleReport english = find(audit, "en");
    assertThat(english.dataState().dictionary()).isEqualTo("git-lfs-pointer");
    assertThat(english.dataState().inflectional()).isEqualTo("git-lfs-pointer");
    assertThat(english.pronouns().rows()).isEqualTo(38);
    assertThat(english.recommendation().mode()).isEqualTo("data-materialization-required");
    assertThat(english.recommendation().runtimeTermInflection()).isFalse();
    assertThat(english.sources().dictionary().gitLfsObjectSize()).isEqualTo(5091040);
    assertThat(english.sources().dictionary().gitLfsOidSha256())
        .isEqualTo("c66d69e97eefa5046d1127d8c35365f72d619fe8d5a2b208bddc83483d5aecaf");
    assertEnglishPronounProfile(english);

    LocaleReport korean = find(audit, "ko");
    assertThat(korean.dataState().dictionary()).isEqualTo("git-lfs-pointer");
    assertThat(korean.dataState().inflectional()).isEqualTo("missing");
    assertThat(korean.pronouns().rows()).isEqualTo(48);
    assertThat(korean.sources().dictionary().gitLfsObjectSize()).isEqualTo(19262);
    assertKoreanPronounProfile(korean);

    LocaleReport chinese = find(audit, "zh");
    assertThat(chinese.dataState().dictionary()).isEqualTo("empty-placeholder");
    assertThat(chinese.pronouns().rows()).isEqualTo(5);
    assertThat(chinese.recommendation().mode()).isEqualTo("profile-only-noop");

    LocaleReport cantonese = find(audit, "yue");
    assertThat(cantonese.dataState().dictionary()).isEqualTo("missing");
    assertThat(cantonese.pronouns().rows()).isEqualTo(3);
    assertThat(cantonese.recommendation().mode()).isEqualTo("profile-only-noop");
  }

  @Test
  public void loadedCollectionsAreImmutable() {
    LowInflectionLocaleAudit audit = loader.load(lowInflectionAuditJson());

    assertThatThrownBy(() -> audit.locales().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> audit.summary().modeCounts().put("manual-review", 1))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> find(audit, "en").pronouns().features().put("other", 1))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> find(audit, "en").pronouns().samples().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  public void rejectUnexpectedSchema() {
    String json =
        lowInflectionAuditJson()
            .replace(
                LowInflectionLocaleAuditJsonLoader.EXPECTED_SCHEMA,
                "mojito-mf2-inflection/low-inflection-locale-audit/v999");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected low-inflection locale audit schema");
  }

  @Test
  public void rejectModeCountMismatch() {
    String json =
        lowInflectionAuditJson()
            .replace(
                "\"data-materialization-required\": 2", "\"data-materialization-required\": 3");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Low-inflection mode counts do not match reports");
  }

  @Test
  public void rejectPronounInventoryMismatch() {
    String json =
        lowInflectionAuditJson()
            .replace(
                "\"pronounInventoryLocales\": [\n      \"en\",",
                "\"pronounInventoryLocales\": [\n      \"id\",");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Low-inflection pronoun inventory locales do not match reports");
  }

  @Test
  public void rejectSourceStateMismatch() {
    String json =
        lowInflectionAuditJson()
            .replaceFirst(
                "\"dictionary\": \"git-lfs-pointer\"", "\"dictionary\": \"materialized\"");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Low-inflection dictionary state does not match source metadata");
  }

  @Test
  public void rejectRuntimeTermInflection() {
    String json =
        lowInflectionAuditJson()
            .replaceFirst("\"runtimeTermInflection\": false", "\"runtimeTermInflection\": true");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Low-inflection audit must not enable term inflection");
  }

  @Test
  public void rejectProfileOnlyModeDisabled() {
    String json =
        lowInflectionAuditJson().replaceFirst("\"profileOnly\": true", "\"profileOnly\": false");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "Low-inflection profile-only recommendation mode must be profile-only");
  }

  @Test
  public void rejectUnsupportedRecommendationMode() {
    String json =
        lowInflectionAuditJson()
            .replaceFirst("\"mode\": \"data-materialization-required\"", "\"mode\": \"render\"");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported low-inflection mode value");
  }

  @Test
  public void rejectMissingSourceWithFileMetadata() {
    String json =
        lowInflectionAuditJson()
            .replace(
                "\"exists\": false,\n"
                    + "          \"gitLfsPointer\": false,\n"
                    + "          \"path\": \"inflection/resources/org/unicode/inflection/dictionary/dictionary_yue.lst\"",
                "\"byteSize\": 0,\n"
                    + "          \"exists\": false,\n"
                    + "          \"gitLfsPointer\": false,\n"
                    + "          \"path\": \"inflection/resources/org/unicode/inflection/dictionary/dictionary_yue.lst\"");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("missing source must not carry file metadata");
  }

  @Test
  public void rejectGitLfsSourceWithoutObjectMetadata() {
    String json =
        lowInflectionAuditJson().replaceFirst("        \"gitLfsObjectSize\": 5091040,\n", "");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Git LFS source requires oid and object size");
  }

  @Test
  public void rejectNonGitLfsSourceWithLfsMetadata() {
    String json =
        lowInflectionAuditJson()
            .replaceFirst(
                "\"gitLfsPointer\": false,\n"
                    + "          \"path\": \"inflection/resources/org/unicode/inflection/inflection/pronoun_en.csv\"",
                "\"gitLfsObjectSize\": 1,\n"
                    + "          \"gitLfsPointer\": false,\n"
                    + "          \"path\": \"inflection/resources/org/unicode/inflection/inflection/pronoun_en.csv\"");

    assertThatThrownBy(() -> loader.load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("non-Git LFS source must not carry LFS metadata");
  }

  private void assertEnglishPronounProfile(LocaleReport english) {
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
    assertThat(english.pronouns().samples().get(0).line()).isEqualTo(1);
    assertThat(english.pronouns().samples().get(0).value()).isEqualTo("they");
    assertThat(english.pronouns().samples().get(0).features())
        .containsExactly("third", "plural", "nominative");
  }

  private void assertKoreanPronounProfile(LocaleReport korean) {
    assertThat(korean.pronouns().uniqueValues()).isEqualTo(30);
    assertThat(korean.pronouns().cases())
        .containsEntry("accusative", 16)
        .containsEntry("genitive", 16)
        .containsEntry("nominative", 16);
    assertThat(korean.pronouns().genders())
        .containsEntry("feminine", 6)
        .containsEntry("masculine", 6);
    assertThat(korean.pronouns().numbers())
        .containsEntry("plural", 21)
        .containsEntry("singular", 27);
    assertThat(korean.pronouns().persons())
        .containsEntry("first", 15)
        .containsEntry("second", 15)
        .containsEntry("third", 18);
    assertThat(korean.pronouns().registers())
        .containsEntry("casual", 21)
        .containsEntry("formal", 27);
    assertThat(korean.pronouns().samples().get(0).line()).isEqualTo(1);
    assertThat(korean.pronouns().samples().get(0).value()).isEqualTo("그");
    assertThat(korean.pronouns().samples().get(0).features())
        .containsExactly("third", "singular", "nominative", "casual", "masculine");
  }

  private LocaleReport find(LowInflectionLocaleAudit audit, String locale) {
    return audit.locales().stream()
        .filter(report -> locale.equals(report.locale()))
        .findFirst()
        .orElseThrow();
  }

  private String lowInflectionAuditJson() {
    return readResource(
        "com/box/l10n/mojito/mf2/inflection/low_inflection_locale_audit_fixture.json");
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
