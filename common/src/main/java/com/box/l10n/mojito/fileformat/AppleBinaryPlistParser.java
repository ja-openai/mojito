package com.box.l10n.mojito.fileformat;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded, Foundation-compatible reader for binary property-list string dictionaries. */
final class AppleBinaryPlistParser {

  private static final int TRAILER_SIZE = 32;
  private static final int MAX_BYTES = 16 * 1024 * 1024;
  private static final int MAX_OBJECTS = 65_536;
  private static final int MAX_STRING_UNITS = 1_000_000;
  private static final int MAX_DICTIONARY_DEPTH = 64;

  private final byte[] source;
  private int offsetSize;
  private int referenceSize;
  private int objectCount;
  private int objectTableEnd;
  private int[] offsets;
  private int remainingVisits = MAX_OBJECTS;
  private boolean stringsdict;

  AppleBinaryPlistParser(byte[] source) {
    this.source = source;
  }

  static boolean matches(byte[] source) {
    return source.length >= 6
        && source[0] == 'b'
        && source[1] == 'p'
        && source[2] == 'l'
        && source[3] == 'i'
        && source[4] == 's'
        && source[5] == 't';
  }

  static boolean containsZero(byte[] source) {
    for (byte value : source) {
      if (value == 0) {
        return true;
      }
    }
    return false;
  }

  LocalizationCatalog parse() {
    int root = initialize();
    int marker = Byte.toUnsignedInt(source[offsets[root]]);
    if ((marker & 0xf0) != 0xd0) {
      throw invalidStrings("Apple binary strings require a top-level dictionary");
    }
    Length dictionary = length(offsets[root], marker);
    validateDictionary(dictionary);

    LocalizationCatalog catalog = new LocalizationCatalog(LocalizationFileFormat.APPLE_STRINGS);
    int count = (int) dictionary.value;
    for (int position = 0; position < count; position++) {
      int key = objectReference(dictionary.content + position * referenceSize);
      int value = objectReference(dictionary.content + (count + position) * referenceSize);
      String messageId = string(key, true);
      String message = string(value, false);
      catalog.add(messageId, AppleStringsParser.message(message, null));
    }
    return catalog;
  }

  LocalizationCatalog parseStringsdict() {
    stringsdict = true;
    int root = initialize();
    Map<String, Object> dictionary = dictionary(root, 0, new boolean[objectCount]);
    return new AppleStringsdictParser().parse(dictionary);
  }

  private int initialize() {
    if (source.length > MAX_BYTES) {
      throw unsafe("Binary property list exceeds the maximum input size");
    }
    if (source.length < TRAILER_SIZE + 9 || !matches(source) || source[6] != '0') {
      throw invalid("Invalid binary property-list header or truncated trailer");
    }

    int trailer = source.length - TRAILER_SIZE;
    offsetSize = Byte.toUnsignedInt(source[trailer + 6]);
    referenceSize = Byte.toUnsignedInt(source[trailer + 7]);
    if (offsetSize < 1 || referenceSize < 1) {
      throw invalid("Invalid binary property-list integer or reference width");
    }
    long declaredObjects = unsigned(trailer + 8, 8, source.length);
    if (Long.compareUnsigned(declaredObjects, MAX_OBJECTS) > 0) {
      throw unsafe("Binary property list exceeds the maximum object count");
    }
    if (declaredObjects == 0) {
      throw invalid("Binary property list requires at least one object");
    }
    objectCount = (int) declaredObjects;
    long topObject = unsigned(trailer + 16, 8, source.length);
    long tableOffset = unsigned(trailer + 24, 8, source.length);
    if (topObject < 0
        || topObject >= objectCount
        || tableOffset < 9
        || tableOffset > trailer
        || tableOffset + (long) objectCount * offsetSize != trailer
        || !canRepresent(referenceSize, objectCount)
        || !canRepresent(offsetSize, tableOffset)) {
      throw invalid("Invalid binary property-list trailer or offset table");
    }
    objectTableEnd = (int) tableOffset;
    offsets = new int[objectCount];
    for (int position = 0; position < objectCount; position++) {
      long offset = unsigned(objectTableEnd + position * offsetSize, offsetSize, trailer);
      if (offset < 8 || offset >= objectTableEnd) {
        throw invalid("Binary property-list object offset is outside its object table");
      }
      offsets[position] = (int) offset;
    }

    return (int) topObject;
  }

  private void validateDictionary(Length dictionary) {
    if (dictionary.value > MAX_OBJECTS) {
      throw unsafe("Binary property-list dictionary exceeds the maximum object count");
    }
    long referenceBytes = dictionary.value * 2L * referenceSize;
    if (referenceBytes > objectTableEnd - dictionary.content) {
      throw invalid("Truncated binary property-list dictionary references");
    }
  }

  private Map<String, Object> dictionary(int reference, int depth, boolean[] active) {
    if (depth > MAX_DICTIONARY_DEPTH) {
      throw unsafe("Binary property list exceeds the maximum dictionary nesting depth");
    }
    if (remainingVisits-- <= 0) {
      throw unsafe("Binary property list exceeds the maximum decoded object count");
    }
    if (active[reference]) {
      throw unsafe("Binary property list contains a cyclic dictionary reference");
    }
    int offset = offsets[reference];
    int marker = Byte.toUnsignedInt(source[offset]);
    if ((marker & 0xf0) != 0xd0) {
      throw invalidStrings("Apple binary stringsdict requires dictionary values");
    }
    Length dictionary = length(offset, marker);
    validateDictionary(dictionary);
    int count = (int) dictionary.value;
    active[reference] = true;
    try {
      Map<String, Object> result = new LinkedHashMap<>();
      for (int position = 0; position < count; position++) {
        int key = objectReference(dictionary.content + position * referenceSize);
        int value = objectReference(dictionary.content + (count + position) * referenceSize);
        String name = string(key, true);
        Object decoded = value(value, depth + 1, active);
        if (result.putIfAbsent(name, decoded) != null) {
          throw new LocalizationParseException(
              "DUPLICATE_MESSAGE_ID", "Duplicate binary property-list dictionary key");
        }
      }
      return result;
    } finally {
      active[reference] = false;
    }
  }

  private Object value(int reference, int depth, boolean[] active) {
    if (remainingVisits-- <= 0) {
      throw unsafe("Binary property list exceeds the maximum decoded object count");
    }
    int offset = offsets[reference];
    int marker = Byte.toUnsignedInt(source[offset]);
    return switch (marker & 0xf0) {
      case 0x50, 0x60 -> string(reference, false);
      case 0xd0 -> dictionary(reference, depth, active);
      case 0xa0 -> array(reference, depth, active);
      case 0x40 -> data(offset, marker);
      case 0x20 -> real(offset, marker);
      case 0x30 -> date(offset, marker);
      case 0x10 -> {
        int width = 1 << (marker & 0x0f);
        if (width > 16 || offset > objectTableEnd - width - 1) {
          throw invalid("Unsupported or truncated binary property-list integer");
        }
        byte[] bytes = java.util.Arrays.copyOfRange(source, offset + 1, offset + width + 1);
        java.math.BigInteger number =
            width < Long.BYTES
                ? new java.math.BigInteger(1, bytes)
                : new java.math.BigInteger(bytes);
        yield AppleStringsdictParser.integer(number.toString());
      }
      case 0x00 -> {
        if (marker == 0x08) {
          yield Boolean.FALSE;
        }
        if (marker == 0x09) {
          yield Boolean.TRUE;
        }
        throw invalidStrings("Unsupported binary property-list null or fill value");
      }
      case 0x70, 0x90, 0xe0, 0xf0 ->
          throw invalid("Unsupported binary property-list object marker");
      default -> throw invalidStrings("Unsupported binary stringsdict property-list value");
    };
  }

  private List<Object> array(int reference, int depth, boolean[] active) {
    if (depth > MAX_DICTIONARY_DEPTH) {
      throw unsafe("Binary property list exceeds the maximum collection nesting depth");
    }
    if (remainingVisits-- <= 0) {
      throw unsafe("Binary property list exceeds the maximum decoded object count");
    }
    if (active[reference]) {
      throw unsafe("Binary property list contains a cyclic array reference");
    }
    int offset = offsets[reference];
    Length array = length(offset, Byte.toUnsignedInt(source[offset]));
    if (array.value > MAX_OBJECTS || array.value * referenceSize > objectTableEnd - array.content) {
      throw invalid("Truncated or oversized binary property-list array");
    }
    active[reference] = true;
    try {
      List<Object> result = new ArrayList<>((int) array.value);
      for (int index = 0; index < array.value; index++) {
        result.add(
            value(objectReference(array.content + index * referenceSize), depth + 1, active));
      }
      return result;
    } finally {
      active[reference] = false;
    }
  }

  private AppleStringsdictParser.PlistData data(int offset, int marker) {
    Length data = length(offset, marker);
    if (data.value > MAX_STRING_UNITS || data.value > objectTableEnd - data.content) {
      throw invalid("Truncated or oversized binary property-list data");
    }
    return new AppleStringsdictParser.PlistData(
        java.util.Arrays.copyOfRange(source, data.content, data.content + (int) data.value));
  }

  private AppleStringsdictParser.PlistReal real(int offset, int marker) {
    int width = 1 << (marker & 0x0f);
    if ((width != Float.BYTES && width != Double.BYTES) || offset > objectTableEnd - width - 1) {
      throw invalid("Invalid binary property-list floating-point width");
    }
    long bits = unsigned(offset + 1, width, objectTableEnd);
    return new AppleStringsdictParser.PlistReal(
        width == Float.BYTES ? Float.intBitsToFloat((int) bits) : Double.longBitsToDouble(bits));
  }

  private AppleStringsdictParser.PlistDate date(int offset, int marker) {
    if (marker != 0x33 || offset > objectTableEnd - Double.BYTES - 1) {
      throw invalid("Invalid binary property-list date width");
    }
    double seconds = Double.longBitsToDouble(unsigned(offset + 1, Double.BYTES, objectTableEnd));
    if (!Double.isFinite(seconds) || seconds != Math.rint(seconds)) {
      throw new LocalizationParseException(
          "UNSUPPORTED_APPLE_PLIST_DATE_PRECISION", "Binary Apple date has fractional seconds");
    }
    try {
      return AppleStringsdictParser.date(
          Instant.ofEpochSecond(Math.addExact((long) seconds, 978307200L)).toString());
    } catch (ArithmeticException | java.time.DateTimeException exception) {
      throw invalid("Invalid binary property-list date value");
    }
  }

  private String string(int reference, boolean key) {
    int offset = offsets[reference];
    int marker = Byte.toUnsignedInt(source[offset]);
    int type = marker & 0xf0;
    if (type != 0x50 && type != 0x60) {
      if (type == 0x70 || type == 0x90 || type >= 0xe0) {
        throw invalid("Unsupported binary property-list object marker");
      }
      throw invalidStrings(
          key
              ? "Apple binary strings dictionary keys must be strings"
              : "Apple binary strings dictionary values must be strings");
    }
    Length length = length(offset, marker);
    if (length.value > MAX_STRING_UNITS) {
      throw unsafe("Binary property-list string exceeds the maximum character count");
    }
    long byteCount = length.value * (type == 0x60 ? 2L : 1L);
    if (byteCount > objectTableEnd - length.content) {
      throw invalid("Truncated binary property-list string");
    }
    if (type == 0x50) {
      // Foundation's nominal ASCII object accepts every byte as ISO-8859-1, including C1.
      return new String(source, length.content, (int) byteCount, StandardCharsets.ISO_8859_1);
    }
    try {
      return StandardCharsets.UTF_16BE
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(source, length.content, (int) byteCount))
          .toString();
    } catch (CharacterCodingException exception) {
      throw new LocalizationParseException(
          "INVALID_APPLE_BINARY_PLIST", "Malformed binary property-list UTF-16 string", exception);
    }
  }

  private Length length(int offset, int marker) {
    int compact = marker & 0x0f;
    if (compact < 0x0f) {
      return new Length(compact, offset + 1);
    }
    int integerOffset = offset + 1;
    if (integerOffset >= objectTableEnd) {
      throw invalid("Truncated binary property-list extended length");
    }
    int integerMarker = Byte.toUnsignedInt(source[integerOffset]);
    int exponent = integerMarker & 0x0f;
    if ((integerMarker & 0xf0) != 0x10) {
      throw invalid("Invalid binary property-list extended length integer");
    }
    int width = 1 << exponent;
    long result = unsigned(integerOffset + 1, width, objectTableEnd);
    if (result < 0) {
      throw invalid("Binary property-list extended length overflows");
    }
    return new Length(result, integerOffset + 1 + width);
  }

  private int objectReference(int position) {
    long reference = unsigned(position, referenceSize, objectTableEnd);
    if (reference < 0 || reference >= objectCount) {
      throw invalid("Binary property-list object reference is outside the offset table");
    }
    return (int) reference;
  }

  private long unsigned(int offset, int width, int limit) {
    if (width < 1 || offset < 0 || offset > limit - width) {
      throw invalid("Truncated binary property-list integer");
    }
    long result = 0;
    for (int position = 0; position < width; position++) {
      result = (result << 8) | Byte.toUnsignedLong(source[offset + position]);
    }
    return result;
  }

  private boolean canRepresent(int width, long value) {
    return width >= 8 || value < 1L << (8 * width);
  }

  private LocalizationParseException invalid(String message) {
    return new LocalizationParseException("INVALID_APPLE_BINARY_PLIST", message);
  }

  private LocalizationParseException invalidStrings(String message) {
    return new LocalizationParseException(
        stringsdict ? "INVALID_APPLE_STRINGSDICT" : "INVALID_APPLE_STRINGS", message);
  }

  private LocalizationParseException unsafe(String message) {
    return new LocalizationParseException("UNSAFE_APPLE_BINARY_PLIST", message);
  }

  private record Length(long value, int content) {}
}
