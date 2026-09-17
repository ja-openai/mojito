package com.box.l10n.mojito.fileformat;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Static MDX extraction and validated, source-preserving translation insertion. */
final class MdxSourceFormat {

  private MdxSourceFormat() {}

  static LocalizationCatalog parse(String source) {
    LocalizationCatalog catalog = new LocalizationCatalog(LocalizationFileFormat.MDX);
    for (MdxDocument.Block block : MdxDocument.parse(source).blocks()) {
      if (block.translatable()) {
        catalog.add(
            block.id(),
            LocalizationMessage.of(
                block.source(),
                null,
                null,
                null,
                Map.of(
                    "documentType", block.type(),
                    "documentDepth", block.depth(),
                    "documentLine", block.line())));
      }
    }
    return catalog;
  }

  static LocalizationSourceSkeleton extract(byte[] original) {
    SourceSkeletonEncoding encoding = SourceSkeletonEncoding.detect(original);
    MdxDocument document = MdxDocument.parse(original);
    List<LocalizationSourceSkeleton.LocalizationSourceSlot> slots = new ArrayList<>();
    for (MdxDocument.Segment segment : document.segments()) {
      slots.add(
          new LocalizationSourceSkeleton.LocalizationSourceSlot(
              segment.block().id(),
              null,
              encoding.offset(document.source(), segment.start()),
              encoding.offset(document.source(), segment.end())));
    }
    return new LocalizationSourceSkeleton(
        1, LocalizationFileFormat.MDX.id(), encoding.name(), document.source(), slots);
  }

  static byte[] render(LocalizationSourceSkeleton skeleton, Map<String, String> translations) {
    if (skeleton.schemaVersion() != 1
        || !LocalizationFileFormat.MDX.id().equals(skeleton.sourceFormat())) {
      throw invalidSkeleton("Unsupported MDX source skeleton");
    }
    SourceSkeletonEncoding encoding = SourceSkeletonEncoding.named(skeleton.encoding());
    byte[] original = encoding.encode(skeleton.source());
    if (!extract(original).slots().equals(skeleton.slots())) {
      throw invalidSkeleton("MDX source slots do not own their original text blocks");
    }
    Map<String, MdxDocument.Block> blocks = new LinkedHashMap<>();
    for (MdxDocument.Block block : MdxDocument.parse(original).blocks()) {
      if (block.translatable()) {
        blocks.put(block.id(), block);
      }
    }
    for (Map.Entry<String, String> entry : translations.entrySet()) {
      MdxDocument.Block block = blocks.get(entry.getKey());
      if (block == null) {
        throw new LocalizationParseException(
            "UNKNOWN_SKELETON_SLOT", "Translation has no MDX source block: " + entry.getKey());
      }
      if (entry.getValue() != null && !entry.getValue().equals(block.source())) {
        MdxDocument.validateTranslation(block, entry.getValue());
      }
    }
    ByteArrayOutputStream output = new ByteArrayOutputStream(original.length);
    int copied = 0;
    for (LocalizationSourceSkeleton.LocalizationSourceSlot slot : skeleton.slots()) {
      output.write(original, copied, slot.start() - copied);
      String translation = translations.get(slot.id());
      if (translation == null) {
        output.write(original, slot.start(), slot.end() - slot.start());
      } else {
        byte[] encoded = translation.getBytes(encoding.charset());
        output.write(encoded, 0, encoded.length);
      }
      copied = slot.end();
    }
    output.write(original, copied, original.length - copied);
    byte[] localized = output.toByteArray();
    List<MdxDocument.Block> expected = MdxDocument.parse(original).blocks();
    List<MdxDocument.Block> actual = MdxDocument.parse(localized).blocks();
    if (actual.size() != expected.size()) {
      throw invalidSkeleton("Translation changes MDX document structure");
    }
    for (int index = 0; index < expected.size(); index++) {
      MdxDocument.Block before = expected.get(index);
      MdxDocument.Block after = actual.get(index);
      String translated = before.translatable() ? translations.get(before.id()) : null;
      String expectedSource = translated == null ? before.source() : translated;
      if (!before.type().equals(after.type())
          || before.depth() != after.depth()
          || !java.util.Objects.equals(before.marker(), after.marker())
          || !expectedSource.equals(after.source())) {
        throw invalidSkeleton("Translation changes MDX document structure");
      }
    }
    return localized;
  }

  private static LocalizationParseException invalidSkeleton(String message) {
    return new LocalizationParseException("INVALID_SKELETON", message);
  }
}
