package com.box.l10n.mojito.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class ImageBytesTest {

  @Test
  public void detectImageContentTypeDetectsImageSignatures() {
    assertEquals("image/png", ImageBytes.detectImageContentType(pngBytes()).get());
    assertEquals("image/bmp", ImageBytes.detectImageContentType(new byte[] {'B', 'M'}).get());
    assertEquals(
        "image/svg+xml",
        ImageBytes.detectImageContentType("<svg></svg>".getBytes(StandardCharsets.UTF_8)).get());
  }

  @Test
  public void detectImageContentTypeReturnsEmptyForUnknownBytes() {
    assertTrue(
        ImageBytes.detectImageContentType("not an image".getBytes(StandardCharsets.UTF_8))
            .isEmpty());
  }

  @Test
  public void imageExtensionForContentTypeNormalizesJpeg() {
    assertEquals("jpg", ImageBytes.imageExtensionForContentType("image/jpg").get());
    assertEquals("jpg", ImageBytes.imageExtensionForContentType("image/jpeg").get());
  }

  private static byte[] pngBytes() {
    return new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
  }
}
