package com.box.l10n.mojito.fileformat;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/** Original byte-order marks and byte offsets shared by reversible resource skeletons. */
record SourceSkeletonEncoding(String name, Charset charset, byte[] bom) {

  static SourceSkeletonEncoding detect(byte[] bytes) {
    return detect(bytes, null);
  }

  static SourceSkeletonEncoding detect(byte[] bytes, Charset declared) {
    if (bytes.length >= 3
        && bytes[0] == (byte) 0xef
        && bytes[1] == (byte) 0xbb
        && bytes[2] == (byte) 0xbf) {
      return named("UTF-8-BOM");
    }
    if (bytes.length >= 2 && bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xfe) {
      return named("UTF-16LE-BOM");
    }
    if (bytes.length >= 2 && bytes[0] == (byte) 0xfe && bytes[1] == (byte) 0xff) {
      return named("UTF-16BE-BOM");
    }
    if (StandardCharsets.UTF_16LE.equals(declared)) {
      return named("UTF-16LE");
    }
    if (StandardCharsets.UTF_16BE.equals(declared)) {
      return named("UTF-16BE");
    }
    if (StandardCharsets.ISO_8859_1.equals(declared)) {
      return named("ISO-8859-1");
    }
    if (StandardCharsets.US_ASCII.equals(declared)) {
      return named("US-ASCII");
    }
    return named("UTF-8");
  }

  static SourceSkeletonEncoding named(String name) {
    return switch (name) {
      case "UTF-8" -> new SourceSkeletonEncoding(name, StandardCharsets.UTF_8, new byte[0]);
      case "UTF-16LE" -> new SourceSkeletonEncoding(name, StandardCharsets.UTF_16LE, new byte[0]);
      case "UTF-16BE" -> new SourceSkeletonEncoding(name, StandardCharsets.UTF_16BE, new byte[0]);
      case "ISO-8859-1" ->
          new SourceSkeletonEncoding(name, StandardCharsets.ISO_8859_1, new byte[0]);
      case "CP1252" ->
          new SourceSkeletonEncoding(name, Charset.forName("windows-1252"), new byte[0]);
      case "US-ASCII" -> new SourceSkeletonEncoding(name, StandardCharsets.US_ASCII, new byte[0]);
      case "UTF-8-BOM" ->
          new SourceSkeletonEncoding(
              name, StandardCharsets.UTF_8, new byte[] {(byte) 0xef, (byte) 0xbb, (byte) 0xbf});
      case "UTF-16LE-BOM" ->
          new SourceSkeletonEncoding(
              name, StandardCharsets.UTF_16LE, new byte[] {(byte) 0xff, (byte) 0xfe});
      case "UTF-16BE-BOM" ->
          new SourceSkeletonEncoding(
              name, StandardCharsets.UTF_16BE, new byte[] {(byte) 0xfe, (byte) 0xff});
      default ->
          throw new LocalizationParseException(
              "INVALID_SKELETON", "Unsupported original source encoding");
    };
  }

  int offset(String value, int index) {
    return bom.length + value.substring(0, index).getBytes(charset).length;
  }

  byte[] encode(String source) {
    byte[] value = source.getBytes(charset);
    byte[] result = new byte[bom.length + value.length];
    System.arraycopy(bom, 0, result, 0, bom.length);
    System.arraycopy(value, 0, result, bom.length, value.length);
    return result;
  }

  String decode(byte[] source, int start, int end) {
    return new String(source, start, end - start, charset);
  }
}
