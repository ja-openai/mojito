package com.box.l10n.mojito.fileformat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public class SourceSkeletonEncodingTest {

  private static final String MULTIBYTE_SAMPLE = "signal caf\u00e9 \u6771\u4eac \ud83e\udded";

  @Test
  public void utf8OffsetsPreserveAsciiMultibyteAndSupplementaryCharacters() {
    assertAllOffsets("UTF-8", MULTIBYTE_SAMPLE);
    assertAllOffsets("UTF-8-BOM", MULTIBYTE_SAMPLE);
  }

  @Test
  public void utf8OffsetsMatchJavaReplacementForMalformedSurrogates() {
    assertAllOffsets("UTF-8", "\ud83e broken \udded");
    assertAllOffsets("UTF-8-BOM", "\ud83e broken \udded");
  }

  @Test
  public void unicodeAndSingleByteEncodingsPreserveOriginalOffsets() {
    assertAllOffsets("UTF-16LE", MULTIBYTE_SAMPLE);
    assertAllOffsets("UTF-16BE", MULTIBYTE_SAMPLE);
    assertAllOffsets("UTF-16LE-BOM", MULTIBYTE_SAMPLE);
    assertAllOffsets("UTF-16BE-BOM", MULTIBYTE_SAMPLE);
    assertAllOffsets("ISO-8859-1", "signal caf\u00e9");
    assertAllOffsets("CP1252", "signal caf\u00e9 \u20ac");
    assertAllOffsets("US-ASCII", "signal 123");
  }

  @Test
  public void changingSourceRebuildsUtf8OffsetIndex() {
    SourceSkeletonEncoding encoding = SourceSkeletonEncoding.named("UTF-8");

    assertEquals(5, encoding.offset("caf\u00e9", 4));
    assertEquals(6, encoding.offset("\u6771\u4eaca", 2));
  }

  @Test
  public void invalidOffsetsFailBeforeReturningSyntheticBytePositions() {
    SourceSkeletonEncoding utf16 = SourceSkeletonEncoding.named("UTF-16LE");
    SourceSkeletonEncoding utf8 = SourceSkeletonEncoding.named("UTF-8");

    assertThrows(StringIndexOutOfBoundsException.class, () -> utf16.offset("abc", -1));
    assertThrows(StringIndexOutOfBoundsException.class, () -> utf16.offset("abc", 4));
    assertThrows(StringIndexOutOfBoundsException.class, () -> utf8.offset("abc", -1));
    assertThrows(StringIndexOutOfBoundsException.class, () -> utf8.offset("abc", 4));
  }

  private static void assertAllOffsets(String name, String source) {
    SourceSkeletonEncoding encoding = SourceSkeletonEncoding.named(name);
    for (int index = 0; index <= source.length(); index++) {
      int expected =
          encoding.bom().length + source.substring(0, index).getBytes(encoding.charset()).length;
      assertEquals(name + " offset " + index, expected, encoding.offset(source, index));
    }
  }
}
