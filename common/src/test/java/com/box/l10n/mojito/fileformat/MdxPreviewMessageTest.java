package com.box.l10n.mojito.fileformat;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class MdxPreviewMessageTest {
  private static final String PREVIEW =
      "<PreviewMessage resource=\"./messages.mf2.json\" name=\"calendar.summary\" args='{\"count\":2,\"date\":\"2026-09-16T12:00:00Z\",\"enabled\":true}' />";

  @Test
  public void preservesReferenceExactlyWithoutExtractingCatalogTextIntoPage() {
    String source = "{/* mojito-id: title */}\n# Hello\n\n" + PREVIEW + "\n";
    var catalog =
        LocalizationFileConverters.parse(
            LocalizationFileFormat.MDX, source.getBytes(StandardCharsets.UTF_8));
    assertEquals(List.of("title"), List.copyOf(catalog.messages().keySet()));
    assertArrayEquals(
        source.replace("Hello", "Bonjour").getBytes(StandardCharsets.UTF_8),
        LocalizationFileConverters.renderSkeleton(
            LocalizationFileConverters.extractSkeleton(
                LocalizationFileFormat.MDX, source.getBytes(StandardCharsets.UTF_8)),
            Map.of("title", "Bonjour")));
    var block = MdxDocument.parse(source.getBytes(StandardCharsets.UTF_8)).blocks().getLast();
    assertEquals("preview-message", block.type());
    assertFalse(block.translatable());
    var parsed = MdxPreviewMessage.parse(block.source());
    assertEquals("calendar.summary", parsed.name());
    assertEquals(
        Map.of("count", 2, "date", "2026-09-16T12:00:00Z", "enabled", true), parsed.args());
  }

  @Test
  public void rejectsExecutableAmbiguousAndUnboundedArguments() {
    for (String source :
        List.of(
            PREVIEW.replace("args='", "args={"),
            PREVIEW.replace("/>", "></PreviewMessage>"),
            PREVIEW.replace(" />", " name='duplicate' />"),
            PREVIEW.replace("{\"count\":2", "{\"count\":null"),
            PREVIEW.replace("{\"count\":2", "{\"count\":{}"),
            PREVIEW.replace("{\"count\":2", "{\"count\":[]"),
            PREVIEW.replace("{\"count\":2", "{\"count\":2,\"count\":3"),
            PREVIEW.replace("{\"count\":2", "{\"__proto__\":2"),
            PREVIEW.replace("{\"count\":2", "{\"count\":1e9999"),
            PREVIEW.replace("2026-09-16T12:00:00Z", "x".repeat(2049)),
            PREVIEW.replace("2026-09-16T12:00:00Z", "&quot;"),
            "import PreviewMessage from './unsafe'\n\n" + PREVIEW)) {
      assertThrows(
          source,
          LocalizationParseException.class,
          () -> MdxDocument.parse(source.getBytes(StandardCharsets.UTF_8)));
    }
  }

  @Test
  public void rejectsNestedCatalogsDuplicateNamesAndTrailingData() {
    assertEquals(
        Map.of("a", "Hello {$name}"), MdxPreviewMessage.parseCatalog("{\"a\":\"Hello {$name}\"}"));
    for (String source :
        List.of(
            "[]",
            "null",
            "{\"a\":{\"b\":\"c\"}}",
            "{\"a\":3}",
            "{\"a\":\"\"}",
            "{\"a\":\"x\",\"a\":\"y\"}",
            "{\"a\":\"x\"} {}")) {
      assertThrows(LocalizationParseException.class, () -> MdxPreviewMessage.parseCatalog(source));
    }
  }
}
