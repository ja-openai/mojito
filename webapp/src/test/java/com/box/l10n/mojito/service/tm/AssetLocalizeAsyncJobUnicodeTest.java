package com.box.l10n.mojito.service.tm;

import static org.assertj.core.api.Assertions.assertThat;

import com.box.l10n.mojito.rest.asset.LocalizedAssetBody;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

public class AssetLocalizeAsyncJobUnicodeTest {

  private static final String[] STRING_FIELDS = {
    "content", "bcp47Tag", "outputBcp47tag", "pullRunName"
  };
  private static final String[] LIST_FIELDS = {"filterOptions", "pullWithNoSourceBranches"};

  @Test
  public void rejectsNullBody() {
    assertThat(AssetLocalizeAsyncJobUnicode.isWellFormed(null)).isFalse();
  }

  @Test
  public void rejectsUnpairedSurrogatesInEveryStringAndListEntry() {
    for (String[] sample :
        new String[][] {
          {"isolated first high", "\ud800"},
          {"isolated last high", "\udbff"},
          {"isolated first low", "\udc00"},
          {"isolated last low", "\udfff"},
          {"reversed pair", "\ude00\ud83d"},
          {"two highs", "\ud800\udbff"},
          {"two lows", "\udc00\udfff"},
          {"high before text", "\ud83dtext"},
          {"low after text", "text\ude00"},
          {"high within text", "before\ud83dafter"},
          {"low within text", "before\ude00after"},
          {"interrupted pair", "\ud83dx\ude00"},
          {"truncated final pair", "text\ud83d\ude00\ud83d"},
          {"extra low after pair", "\ud83d\ude00\ude00"},
          {"extra high before pair", "\ud83d\ud83d\ude00"},
          {"extra low before pair", "\ude00\ud83d\ude00"}
        }) {
      assertForEveryField(sample[0], sample[1], false);
    }
  }

  @Test
  public void acceptsValidUnicodeInEveryStringAndListEntry() {
    for (String[] sample :
        new String[][] {
          {"empty", ""},
          {"ASCII", "plain text"},
          {"BMP", "fran\u00e7ais \u4e2d\u6587"},
          {"supplementary", "\ud83d\ude00"},
          {"supplementary boundaries", "\ud800\udc00\udbff\udfff"},
          {"adjacent pairs", "\ud83d\ude00\ud83d\ude01"},
          {"mixed", "before\ud83d\ude00\u00e9\ud800\udc00after"},
          {"literal replacement character", "\ufffd"},
          {"literal BOM", "\ufefftext\ufeff"},
          {"controls", "\u0000\t\r\n\u001f\u007f\u0085"},
          {"BMP boundaries and noncharacters", "\ud7ff\ue000\ufffe\uffff"}
        }) {
      assertForEveryField(sample[0], sample[1], true);
    }
  }

  @Test
  public void preservesNullStringsListsAndEntries() {
    assertThat(AssetLocalizeAsyncJobUnicode.isWellFormed(new LocalizedAssetBody())).isTrue();
    assertForEveryField("null string or entry", null, true);

    for (String field : LIST_FIELDS) {
      for (List<String> value :
          Arrays.asList(null, List.<String>of(), Arrays.<String>asList(null, null))) {
        LocalizedAssetBody body = validBody();
        // The branch-list setter normalizes null, so also exercise an actual null field.
        ReflectionTestUtils.setField(body, field, value);
        assertThat(AssetLocalizeAsyncJobUnicode.isWellFormed(body))
            .as("%s with %s", field, value)
            .isTrue();
      }
    }

    LocalizedAssetBody body = new LocalizedAssetBody();
    for (String field : LIST_FIELDS) {
      ReflectionTestUtils.setField(body, field, null);
    }
    assertThat(AssetLocalizeAsyncJobUnicode.isWellFormed(body)).isTrue();
  }

  @Test
  public void newStringOrListFieldsRequireUnicodeValidationReview() {
    assertThat(
            Arrays.stream(LocalizedAssetBody.class.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .filter(field -> field.getType() == String.class)
                .map(Field::getName)
                .toList())
        .as("Review Unicode validation and extend STRING_FIELDS for new string fields")
        .containsExactlyInAnyOrder(STRING_FIELDS);
    assertThat(
            Arrays.stream(LocalizedAssetBody.class.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .filter(field -> List.class.isAssignableFrom(field.getType()))
                .map(Field::getName)
                .toList())
        .as("Review Unicode validation and extend LIST_FIELDS for new list fields")
        .containsExactlyInAnyOrder(LIST_FIELDS);
  }

  private void assertForEveryField(String description, String value, boolean expected) {
    for (String field : STRING_FIELDS) {
      LocalizedAssetBody body = validBody();
      ReflectionTestUtils.setField(body, field, value);
      assertThat(AssetLocalizeAsyncJobUnicode.isWellFormed(body))
          .as("%s: %s", field, description)
          .isEqualTo(expected);
    }
    for (String field : LIST_FIELDS) {
      for (int index = 0; index < 5; index++) {
        LocalizedAssetBody body = validBody();
        List<String> entries = Arrays.asList(null, "before", null, "after", null);
        entries.set(index, value);
        ReflectionTestUtils.setField(body, field, entries);
        assertThat(AssetLocalizeAsyncJobUnicode.isWellFormed(body))
            .as("%s[%s]: %s", field, index, description)
            .isEqualTo(expected);
      }
    }
  }

  private LocalizedAssetBody validBody() {
    LocalizedAssetBody body = new LocalizedAssetBody("fr", "source");
    body.setOutputBcp47tag("fr-FR");
    body.setPullRunName("run");
    body.setFilterOptions(List.of("option"));
    body.setPullWithNoSourceBranches(List.of("main"));
    return body;
  }
}
