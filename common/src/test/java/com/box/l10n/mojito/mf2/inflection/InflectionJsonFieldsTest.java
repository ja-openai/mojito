package com.box.l10n.mojito.mf2.inflection;

import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.integerMap;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.optionalArray;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.optionalBoolean;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.optionalInt;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.optionalLong;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.optionalObject;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.optionalStringIndex;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.optionalText;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.requireSha256Hex;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.requiredAllowedText;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.requiredArray;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.requiredBoolean;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.requiredDouble;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.requiredInt;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.requiredIntValue;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.requiredLong;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.requiredObject;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.requiredObjectRoot;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.requiredSha256Hex;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.requiredStringIndex;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.requiredText;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.requiredTextValue;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.textArray;
import static com.box.l10n.mojito.mf2.inflection.InflectionJsonFields.unmodifiableLinkedMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.box.l10n.mojito.json.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

public class InflectionJsonFieldsTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  public void readsTypedFields() {
    JsonNode node =
        json(
            """
            {
              "array": ["a", "b"],
              "object": {"key": "value"},
              "int": 7,
              "long": 3000000000,
              "double": 0.75,
              "boolean": true,
              "text": "value"
            }
            """);

    assertThat(requiredArray(node, "array")).hasSize(2);
    assertThat(requiredObjectRoot(node, "test artifact")).isSameAs(node);
    assertThat(optionalArray(node, "array")).hasSize(2);
    assertThat(requiredObject(node, "object").get("key").asText()).isEqualTo("value");
    assertThat(optionalObject(node, "object").get("key").asText()).isEqualTo("value");
    assertThat(requiredInt(node, "int")).isEqualTo(7);
    assertThat(requiredIntValue(node.get("int"), "int")).isEqualTo(7);
    assertThat(optionalInt(node, "int")).isEqualTo(7);
    assertThat(optionalInt(node, "missing")).isNull();
    assertThat(optionalInt(node, "int", 1)).isEqualTo(7);
    assertThat(optionalInt(node, "missing", 1)).isEqualTo(1);
    assertThat(requiredLong(node, "long")).isEqualTo(3000000000L);
    assertThat(optionalLong(node, "long")).isEqualTo(3000000000L);
    assertThat(requiredDouble(node, "double")).isEqualTo(0.75);
    assertThat(requiredBoolean(node, "boolean")).isTrue();
    assertThat(optionalBoolean(node, "boolean")).isTrue();
    assertThat(requiredText(node, "text")).isEqualTo("value");
    assertThat(requiredTextValue(node.get("text"), "text")).isEqualTo("value");
    assertThat(optionalArray(node, "missing")).isNull();
    assertThat(optionalObject(node, "missing")).isNull();
    assertThat(optionalBoolean(node, "missing")).isNull();
    assertThat(optionalText(node, "missing")).isNull();
    assertThat(optionalLong(node, "missing")).isNull();
  }

  @Test
  public void rejectsWrongTypedFields() {
    JsonNode node =
        json(
            """
            {
              "array": {},
              "object": [],
              "int": "1",
              "long": "2",
              "double": "0.5",
              "boolean": "true",
              "text": ""
            }
            """);

    assertThatThrownBy(() -> requiredArray(node, "array"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected array field: array");
    assertThatThrownBy(() -> requiredObjectRoot(json("[]"), "test artifact"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected object root: test artifact");
    assertThatThrownBy(() -> optionalArray(node, "array"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected array field: array");
    assertThatThrownBy(() -> requiredObject(node, "object"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected object field: object");
    assertThatThrownBy(() -> optionalObject(node, "object"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected object field: object");
    assertThatThrownBy(() -> requiredInt(node, "int"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected int field: int");
    assertThatThrownBy(() -> requiredIntValue(node.get("int"), "int"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected int field: int");
    assertThatThrownBy(() -> optionalInt(node, "int"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected int field: int");
    assertThatThrownBy(() -> optionalInt(node, "int", 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected int field: int");
    assertThatThrownBy(() -> requiredLong(node, "long"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected long field: long");
    assertThatThrownBy(() -> optionalLong(node, "long"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected long field: long");
    assertThatThrownBy(() -> requiredDouble(node, "double"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected numeric field: double");
    assertThatThrownBy(() -> requiredBoolean(node, "boolean"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected boolean field: boolean");
    assertThatThrownBy(() -> optionalBoolean(node, "boolean"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected boolean field: boolean");
    assertThatThrownBy(() -> requiredText(node, "text"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected nonblank text field: text");
    assertThatThrownBy(() -> requiredTextValue(node.get("text"), "text"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected nonblank text field: text");
  }

  @Test
  public void rejectsFractionalIntegerFields() {
    JsonNode node = json("{\"int\":1.5,\"long\":2.5,\"values\":{\"a\":1.5}}");

    assertThatThrownBy(() -> requiredInt(node, "int"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected int field: int");
    assertThatThrownBy(() -> requiredIntValue(node.get("int"), "int"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected int field: int");
    assertThatThrownBy(() -> optionalInt(node, "int"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected int field: int");
    assertThatThrownBy(() -> optionalInt(node, "int", 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected int field: int");
    assertThatThrownBy(() -> requiredLong(node, "long"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected long field: long");
    assertThatThrownBy(() -> optionalLong(node, "long"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected long field: long");
    assertThatThrownBy(() -> integerMap(requiredObject(node, "values"), "values", null, "audit"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected non-negative audit count: values");
  }

  @Test
  public void validatesAllowedTextAndTextArrays() {
    JsonNode node = json("{\"mode\":\"profile-only-noop\",\"values\":[\"a\",\"b\"]}");

    assertThat(requiredAllowedText(node, "mode", Set.of("profile-only-noop"), "audit"))
        .isEqualTo("profile-only-noop");
    assertThat(textArray(requiredArray(node, "values"), "values", Set.of("a", "b"), "audit"))
        .containsExactly("a", "b");

    assertThatThrownBy(() -> requiredAllowedText(node, "mode", Set.of("other"), "audit"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported audit mode value");
    assertThatThrownBy(
            () -> textArray(requiredArray(node, "values"), "values", Set.of("a"), "audit"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported audit values value");
    assertThatThrownBy(
            () ->
                textArray(
                    requiredArray(json("{\"values\":[\"\"]}"), "values"), "values", null, "audit"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected nonblank text array value: values");
  }

  @Test
  public void validatesStringIndexes() {
    JsonNode node = json("{\"surface\":1,\"missing\":null}");

    assertThat(requiredStringIndex(node, "surface", List.of("a", "b"))).isEqualTo(1);
    assertThat(optionalStringIndex(node, "surface", List.of("a", "b"))).isEqualTo(1);
    assertThat(optionalStringIndex(node, "missing", List.of("a", "b"))).isNull();
    assertThat(optionalStringIndex(json("{}"), "missing", List.of("a", "b"))).isNull();
    assertThatThrownBy(() -> requiredStringIndex(node, "surface", List.of("a")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("String index out of bounds for field surface: 1");
    assertThatThrownBy(() -> optionalStringIndex(node, "surface", List.of("a")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("String index out of bounds for field surface: 1");
    assertThatThrownBy(
            () -> optionalStringIndex(json("{\"surface\":1.5}"), "surface", List.of("a", "b")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected int field: surface");
  }

  @Test
  public void validatesSha256HexDigests() {
    String digest = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    JsonNode node = json("{\"sha256\":\"" + digest + "\"}");

    assertThat(requiredSha256Hex(node, "sha256")).isEqualTo(digest);
    assertThat(requireSha256Hex(digest, "sha256")).isEqualTo(digest);

    assertThatThrownBy(() -> requiredSha256Hex(json("{\"sha256\":\"abc123\"}"), "sha256"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("sha256 must be a 64-character lowercase SHA-256 hex digest");
    assertThatThrownBy(
            () ->
                requireSha256Hex(
                    "0123456789ABCDEF0123456789abcdef0123456789abcdef0123456789abcdef", "sha256"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("sha256 must be a 64-character lowercase SHA-256 hex digest");
  }

  @Test
  public void validatesIntegerMaps() {
    JsonNode values = requiredObject(json("{\"values\":{\"a\":2,\"b\":0}}"), "values");

    assertThat(integerMap(values, "values", Set.of("a", "b"), "audit"))
        .containsEntry("a", 2)
        .containsEntry("b", 0);
    assertThatThrownBy(() -> integerMap(values, "values", Set.of("a"), "audit"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported audit values key: b");
    assertThatThrownBy(
            () ->
                integerMap(
                    requiredObject(json("{\"values\":{\"a\":-1}}"), "values"),
                    "values",
                    null,
                    "audit"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected non-negative audit count: values");
  }

  @Test
  public void copiesMapsAsImmutableLinkedMaps() {
    Map<String, String> values = new LinkedHashMap<>();
    values.put("first", "1");
    values.put("second", "2");

    Map<String, String> copied = unmodifiableLinkedMap(values);

    assertThat(copied.keySet()).containsExactly("first", "second");
    assertThatThrownBy(() -> copied.put("third", "3"))
        .isInstanceOf(UnsupportedOperationException.class);

    Map<String, String> withNull = new LinkedHashMap<>();
    withNull.put("first", null);
    assertThatThrownBy(() -> unmodifiableLinkedMap(withNull))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("map value");
  }

  private JsonNode json(String json) {
    return objectMapper.readTreeUnchecked(json);
  }
}
