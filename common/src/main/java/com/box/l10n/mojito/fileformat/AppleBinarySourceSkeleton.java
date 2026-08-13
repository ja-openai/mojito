package com.box.l10n.mojito.fileformat;

import com.box.l10n.mojito.fileformat.AppleStringsdictSourceSkeleton.SlotIdentity;
import com.box.l10n.mojito.fileformat.LocalizationSourceSkeleton.LocalizationSourceSlot;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Lossless CoreFoundation object ownership with an independently rebuilt offset table. */
final class AppleBinarySourceSkeleton {

  private static final String ENCODING = "BINARY_PLIST";

  private final byte[] source;
  private final Map<List<String>, SlotIdentity> expected;
  private final int referenceWidth;
  private final int[] offsets;
  private final int[] references;
  private final Set<Integer> keyObjects = new HashSet<>();
  private final List<OwnedObject> owned = new ArrayList<>();
  private final Set<String> assigned = new HashSet<>();
  private final Map<Integer, Integer> referenceSites = new HashMap<>();
  private final Set<Integer> containers = new HashSet<>();

  private AppleBinarySourceSkeleton(byte[] source, Map<List<String>, SlotIdentity> expected) {
    this.source = source;
    this.expected = expected;
    int trailer = source.length - 32;
    int offsetWidth = Byte.toUnsignedInt(source[trailer + 6]);
    referenceWidth = Byte.toUnsignedInt(source[trailer + 7]);
    int count = (int) unsigned(source, trailer + 8, 8);
    int objectEnd = (int) unsigned(source, trailer + 24, 8);
    offsets = new int[count];
    references = new int[count];
    for (int index = 0; index < count; index++) {
      offsets[index] = (int) unsigned(source, objectEnd + index * offsetWidth, offsetWidth);
    }
  }

  static LocalizationSourceSkeleton extract(LocalizationFileFormat format, byte[] source) {
    LocalizationCatalog catalog = LocalizationFileConverters.parse(format, source);
    Map<List<String>, SlotIdentity> expected = new HashMap<>();
    if (format == LocalizationFileFormat.APPLE_STRINGSDICT) {
      expected.putAll(AppleStringsdictSourceSkeleton.expectedPaths(catalog));
    } else {
      for (String id : catalog.messages().keySet()) {
        expected.put(List.of(id), new SlotIdentity(id, null, null));
      }
    }
    AppleBinarySourceSkeleton layout = new AppleBinarySourceSkeleton(source, expected);
    int root = (int) unsigned(source, source.length - 16, 8);
    layout.visit(root, List.of(), false, -1, new HashSet<>());
    if (layout.owned.size() != expected.size()) {
      throw invalid("UNSUPPORTED_SKELETON_SOURCE", "Missing owned binary property-list value");
    }
    List<LocalizationSourceSlot> slots = new ArrayList<>();
    for (OwnedObject entry : layout.owned) {
      int object = entry.object();
      int start = layout.offsets[object];
      Length length = layout.length(start);
      int width = (source[start] & 0xf0) == 0x60 ? 2 : 1;
      int end = Math.addExact(length.content(), Math.multiplyExact(length.count(), width));
      for (int index = 0; index < layout.offsets.length; index++) {
        int offset = layout.offsets[index];
        if (index != object && offset >= start && offset < end) {
          throw invalid(
              "UNSUPPORTED_SKELETON_SOURCE", "Overlapping binary property-list object ownership");
        }
      }
      SlotIdentity identity = entry.identity();
      boolean shared = layout.references[object] != 1 || layout.keyObjects.contains(object);
      slots.add(
          shared
              ? new LocalizationSourceSlot(
                  identity.id(),
                  identity.selector(),
                  identity.variant(),
                  entry.referenceStart(),
                  entry.referenceStart() + layout.referenceWidth,
                  object)
              : new LocalizationSourceSlot(
                  identity.id(), identity.selector(), identity.variant(), start, end));
    }
    slots.sort(Comparator.comparingInt(LocalizationSourceSlot::start));
    return new LocalizationSourceSkeleton(
        1, format.id(), ENCODING, HexFormat.of().formatHex(source), slots);
  }

  static byte[] render(LocalizationSourceSkeleton skeleton, Map<String, String> translations) {
    if (skeleton.schemaVersion() != 1 || !ENCODING.equals(skeleton.encoding())) {
      throw invalid("INVALID_SKELETON", "Invalid binary property-list source skeleton");
    }
    LocalizationFileFormat format;
    byte[] source;
    try {
      format = LocalizationFileFormat.fromId(skeleton.sourceFormat());
      source = HexFormat.of().parseHex(skeleton.source());
    } catch (IllegalArgumentException exception) {
      throw invalid("INVALID_SKELETON", "Malformed binary property-list source skeleton");
    }
    if (format != LocalizationFileFormat.APPLE_STRINGS
        && format != LocalizationFileFormat.APPLE_STRINGSDICT) {
      throw invalid("INVALID_SKELETON", "Unsupported binary property-list source format");
    }
    LocalizationSourceSkeleton actual = extract(format, source);
    if (!actual.slots().equals(skeleton.slots())) {
      throw invalid("INVALID_SKELETON", "Binary property-list object ownership was changed");
    }
    Set<String> known = new HashSet<>();
    for (LocalizationSourceSlot slot : skeleton.slots()) {
      if (!known.add(slot.translationKey())) {
        throw invalid("INVALID_SKELETON", "Duplicated binary property-list value slot");
      }
    }
    if (!known.containsAll(translations.keySet())) {
      throw invalid("UNKNOWN_SKELETON_SLOT", "Translation has no binary property-list value");
    }
    if (translations.isEmpty()) {
      return source;
    }
    LocalizationCatalog catalog = LocalizationFileConverters.parse(format, source);
    int trailer = source.length - 32;
    int oldWidth = Byte.toUnsignedInt(source[trailer + 6]);
    int oldReferenceWidth = Byte.toUnsignedInt(source[trailer + 7]);
    int count = (int) unsigned(source, trailer + 8, 8);
    int oldEnd = (int) unsigned(source, trailer + 24, 8);
    int[] oldOffsets = new int[count];
    for (int index = 0; index < count; index++) {
      oldOffsets[index] = (int) unsigned(source, oldEnd + index * oldWidth, oldWidth);
    }

    Map<Integer, Replacement> replacements = new HashMap<>();
    Map<Integer, Integer> clonedReferences = new HashMap<>();
    List<byte[]> clones = new ArrayList<>();
    for (LocalizationSourceSlot slot : skeleton.slots()) {
      String translation = translations.get(slot.translationKey());
      if (translation != null) {
        LocalizationMessage message = catalog.messages().get(slot.id());
        if (message == null
            || format == LocalizationFileFormat.APPLE_STRINGSDICT
                && !AppleStringsdictSourceSkeleton.hasCategory(message, slot)) {
          throw invalid("INVALID_SKELETON", "Missing binary property-list message descriptor");
        }
        String nativeValue =
            format == LocalizationFileFormat.APPLE_STRINGSDICT
                ? AppleStringsdictWriter.restore(
                    translation,
                    message,
                    slot.selector() != null
                        ? slot.selector()
                        : message.metadata() != null
                                && message.metadata().get("pluralVariable")
                                    instanceof String variable
                            ? variable
                            : null,
                    slot.variant())
                : AppleStringsWriter.nativeValue(message, translation);
        int object =
            slot.appleObjectIndex() == null
                ? indexAt(oldOffsets, slot.start())
                : slot.appleObjectIndex();
        byte[] value = encodeString(nativeValue, source[oldOffsets[object]]);
        if (slot.appleObjectIndex() == null) {
          replacements.put(slot.start(), new Replacement(slot.end(), value));
        } else {
          clonedReferences.put(slot.start(), count + clones.size());
          clones.add(value);
        }
      }
    }
    int objectCount = Math.addExact(count, clones.size());
    if (objectCount > 65_536) {
      throw invalid("UNSAFE_APPLE_BINARY_PLIST", "Binary object cloning exceeds its object limit");
    }
    int referenceWidth = oldReferenceWidth;
    while (!representable(referenceWidth, objectCount)) {
      referenceWidth++;
    }
    AppleBinarySourceSkeleton ownership = new AppleBinarySourceSkeleton(source, Map.of());
    ownership.visit((int) unsigned(source, trailer + 16, 8), List.of(), false, -1, new HashSet<>());
    if (referenceWidth != oldReferenceWidth) {
      for (int index = 0; index < oldOffsets.length; index++) {
        int marker = source[oldOffsets[index]] & 0xf0;
        if ((marker == 0xd0 || marker == 0xa0) && !ownership.containers.contains(index)) {
          throw invalid(
              "UNSUPPORTED_SKELETON_SOURCE",
              "Unreachable binary containers cannot safely change their reference width");
        }
      }
    }
    for (Map.Entry<Integer, Integer> site : ownership.referenceSites.entrySet()) {
      Integer clone = clonedReferences.get(site.getKey());
      if (clone != null || referenceWidth != oldReferenceWidth) {
        ByteArrayOutputStream reference = new ByteArrayOutputStream(referenceWidth);
        writeInteger(reference, clone == null ? site.getValue() : clone, referenceWidth);
        replacements.put(
            site.getKey(),
            new Replacement(site.getKey() + oldReferenceWidth, reference.toByteArray()));
      }
    }
    List<Map.Entry<Integer, Replacement>> ordered = new ArrayList<>(replacements.entrySet());
    ordered.sort(Map.Entry.comparingByKey());
    ByteArrayOutputStream objects = new ByteArrayOutputStream(source.length);
    int cursor = 0;
    for (Map.Entry<Integer, Replacement> replacement : ordered) {
      int start = replacement.getKey();
      if (start < cursor || replacement.getValue().end() > oldEnd) {
        throw invalid("INVALID_SKELETON", "Overlapping binary structural ownership");
      }
      objects.write(source, cursor, start - cursor);
      objects.write(replacement.getValue().value(), 0, replacement.getValue().value().length);
      cursor = replacement.getValue().end();
    }
    objects.write(source, cursor, oldEnd - cursor);
    int[] cloneOffsets = new int[clones.size()];
    for (int index = 0; index < clones.size(); index++) {
      cloneOffsets[index] = objects.size();
      objects.write(clones.get(index), 0, clones.get(index).length);
    }
    int newEnd = objects.size();
    int width = oldWidth;
    while (width < 8 && !representable(width, newEnd)) {
      width++;
    }
    for (int index = 0; index < count; index++) {
      int old = oldOffsets[index];
      int delta = 0;
      for (Map.Entry<Integer, Replacement> replacement : ordered) {
        if (replacement.getKey() >= old) {
          break;
        }
        if (replacement.getValue().end() <= old) {
          delta +=
              replacement.getValue().value().length
                  - (replacement.getValue().end() - replacement.getKey());
        }
      }
      writeInteger(objects, old + delta, width);
    }
    for (int offset : cloneOffsets) {
      writeInteger(objects, offset, width);
    }
    byte[] originalTrailer = Arrays.copyOfRange(source, trailer, source.length);
    originalTrailer[6] = (byte) width;
    originalTrailer[7] = (byte) referenceWidth;
    writeTrailerInteger(originalTrailer, 8, objectCount);
    byte[] end = new byte[8];
    for (int index = 7; index >= 0; index--) {
      end[index] = (byte) ((long) newEnd >>> ((7 - index) * 8));
    }
    System.arraycopy(end, 0, originalTrailer, 24, 8);
    objects.write(originalTrailer, 0, originalTrailer.length);
    return objects.toByteArray();
  }

  private void visit(
      int index, List<String> path, boolean key, int referenceStart, Set<Integer> active) {
    references[index]++;
    if (key) {
      keyObjects.add(index);
    }
    int offset = offsets[index];
    int marker = Byte.toUnsignedInt(source[offset]);
    int type = marker & 0xf0;
    if (type == 0x50 || type == 0x60) {
      if (!key && expected.containsKey(path)) {
        SlotIdentity identity = expected.get(path);
        String translation = identity.id() + "#" + identity.selector() + "#" + identity.variant();
        if (!assigned.add(translation) || referenceStart < 0) {
          throw invalid(
              "UNSUPPORTED_SKELETON_SOURCE",
              "Repeated binary property-list paths have ambiguous translation ownership");
        }
        owned.add(new OwnedObject(index, identity, referenceStart));
      }
      return;
    }
    if (type != 0xd0 && type != 0xa0) {
      return;
    }
    if (!active.add(index)) {
      throw invalid("UNSUPPORTED_SKELETON_SOURCE", "Cyclic binary property-list object ownership");
    }
    containers.add(index);
    Length length = length(offset);
    if (type == 0xd0) {
      for (int position = 0; position < length.count(); position++) {
        int keyStart = length.content() + position * referenceWidth;
        int valueStart = length.content() + (length.count() + position) * referenceWidth;
        int keyIndex = reference(keyStart);
        int value = reference(valueStart);
        referenceSites.put(keyStart, keyIndex);
        referenceSites.put(valueStart, value);
        visit(keyIndex, path, true, keyStart, active);
        List<String> child = new ArrayList<>(path);
        child.add(string(keyIndex));
        visit(value, List.copyOf(child), false, valueStart, active);
      }
    } else {
      for (int position = 0; position < length.count(); position++) {
        int start = length.content() + position * referenceWidth;
        int value = reference(start);
        referenceSites.put(start, value);
        visit(value, path, false, start, active);
      }
    }
    active.remove(index);
  }

  private String string(int index) {
    int offset = offsets[index];
    Length length = length(offset);
    return (source[offset] & 0xf0) == 0x50
        ? new String(source, length.content(), length.count(), StandardCharsets.ISO_8859_1)
        : new String(source, length.content(), length.count() * 2, StandardCharsets.UTF_16BE);
  }

  private int reference(int position) {
    return (int) unsigned(source, position, referenceWidth);
  }

  private Length length(int offset) {
    int count = source[offset] & 0x0f;
    if (count < 15) {
      return new Length(count, offset + 1);
    }
    int marker = Byte.toUnsignedInt(source[offset + 1]);
    int width = 1 << (marker & 0x0f);
    return new Length((int) unsigned(source, offset + 2, width), offset + 2 + width);
  }

  private static byte[] encodeString(String value, byte originalMarker) {
    boolean latin = (originalMarker & 0xf0) == 0x50 && value.chars().allMatch(item -> item <= 127);
    byte[] content =
        value.getBytes(latin ? StandardCharsets.ISO_8859_1 : StandardCharsets.UTF_16BE);
    int units = latin ? content.length : content.length / 2;
    ByteArrayOutputStream encoded = new ByteArrayOutputStream(content.length + 10);
    int marker = latin ? 0x50 : 0x60;
    if (units < 15) {
      encoded.write(marker | units);
    } else {
      encoded.write(marker | 15);
      int width = units <= 0xff ? 1 : units <= 0xffff ? 2 : 4;
      encoded.write(0x10 | Integer.numberOfTrailingZeros(width));
      writeInteger(encoded, units, width);
    }
    encoded.write(content, 0, content.length);
    return encoded.toByteArray();
  }

  private static void writeInteger(ByteArrayOutputStream output, long value, int width) {
    for (int position = width - 1; position >= 0; position--) {
      output.write(position >= 8 ? 0 : (int) (value >>> (position * 8)) & 0xff);
    }
  }

  private static long unsigned(byte[] value, int start, int width) {
    long result = 0;
    for (int position = 0; position < width; position++) {
      result = result << 8 | Byte.toUnsignedLong(value[start + position]);
    }
    return result;
  }

  private static boolean representable(int width, long value) {
    return width >= 8 || value < 1L << (width * 8);
  }

  private static int indexAt(int[] offsets, int start) {
    for (int index = 0; index < offsets.length; index++) {
      if (offsets[index] == start) {
        return index;
      }
    }
    throw invalid("INVALID_SKELETON", "Binary value does not own an original string object");
  }

  private static void writeTrailerInteger(byte[] trailer, int offset, long value) {
    for (int index = 7; index >= 0; index--) {
      trailer[offset + index] = (byte) (value >>> ((7 - index) * 8));
    }
  }

  private static LocalizationParseException invalid(String code, String message) {
    return new LocalizationParseException(code, message);
  }

  private record Length(int count, int content) {}

  private record OwnedObject(int object, SlotIdentity identity, int referenceStart) {}

  private record Replacement(int end, byte[] value) {}
}
