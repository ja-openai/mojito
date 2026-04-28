package com.box.l10n.mojito.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

public record ImageBytes(String filename, byte[] content, String contentType) {

  public ImageBytes {
    Objects.requireNonNull(filename, "filename must not be null");
    Objects.requireNonNull(content, "content must not be null");
    Objects.requireNonNull(contentType, "contentType must not be null");
    if (filename.isBlank()) throw new IllegalArgumentException("filename must not be blank");
    if (content.length == 0) throw new IllegalArgumentException("content must not be empty");
    if (contentType.isBlank()) throw new IllegalArgumentException("contentType must not be blank");
  }

  public static ImageBytes fromFile(Path path) throws IOException {
    byte[] bytes = Files.readAllBytes(path);
    String probed = Files.probeContentType(path);
    String contentType = probed != null ? probed : guessContentType(path, bytes);
    return new ImageBytes(path.getFileName().toString(), bytes, contentType);
  }

  public static ImageBytes fromFile(String filename) throws IOException {
    return fromFile(Path.of(filename));
  }

  public static ImageBytes fromBytes(String filename, byte[] content, String contentType) {
    return new ImageBytes(filename, content, contentType);
  }

  public static ImageBytes fromBytes(String filename, byte[] content) {
    return fromBytes(filename, content, guessContentType(Path.of(filename), content));
  }

  public String toDataUrl() {
    String base64 = Base64.getEncoder().encodeToString(content);
    return "data:" + contentType + ";base64," + base64;
  }

  public static Optional<String> imageContentTypeForExtension(String extension) {
    if (extension == null) {
      return Optional.empty();
    }
    return switch (extension.toLowerCase(Locale.ROOT)) {
      case "png" -> Optional.of("image/png");
      case "jpg", "jpeg" -> Optional.of("image/jpeg");
      case "gif" -> Optional.of("image/gif");
      case "webp" -> Optional.of("image/webp");
      case "bmp" -> Optional.of("image/bmp");
      case "svg" -> Optional.of("image/svg+xml");
      default -> Optional.empty();
    };
  }

  public static Optional<String> imageExtensionForContentType(String contentType) {
    return switch (normalizeImageContentType(contentType)) {
      case "image/png" -> Optional.of("png");
      case "image/jpeg" -> Optional.of("jpg");
      case "image/gif" -> Optional.of("gif");
      case "image/webp" -> Optional.of("webp");
      case "image/bmp" -> Optional.of("bmp");
      case "image/svg+xml" -> Optional.of("svg");
      default -> Optional.empty();
    };
  }

  public static String normalizeImageContentType(String contentType) {
    if (contentType == null) {
      return "";
    }
    String normalized = contentType.trim().toLowerCase(Locale.ROOT);
    if ("image/jpg".equals(normalized)) {
      return "image/jpeg";
    }
    return normalized;
  }

  public static Optional<String> detectImageContentType(byte[] bytes) {
    Objects.requireNonNull(bytes, "bytes must not be null");
    if (isPng(bytes)) {
      return Optional.of("image/png");
    }
    if (isJpeg(bytes)) {
      return Optional.of("image/jpeg");
    }
    if (isGif(bytes)) {
      return Optional.of("image/gif");
    }
    if (isWebp(bytes)) {
      return Optional.of("image/webp");
    }
    if (isBmp(bytes)) {
      return Optional.of("image/bmp");
    }
    if (isSvg(bytes)) {
      return Optional.of("image/svg+xml");
    }
    return Optional.empty();
  }

  private static String guessContentType(Path path, byte[] bytes) {
    String filename = path.getFileName().toString();
    String ext = "";
    int dot = filename.lastIndexOf('.');
    if (dot >= 0 && dot < filename.length() - 1) {
      ext = filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
    Optional<String> contentTypeFromExtension = imageContentTypeForExtension(ext);
    if (contentTypeFromExtension.isPresent()) {
      return contentTypeFromExtension.get();
    }
    if ("pdf".equals(ext)) {
      return "application/pdf";
    }

    return detectImageContentType(bytes).orElse("application/octet-stream");
  }

  private static boolean isPng(byte[] b) {
    return b.length >= 8
        && (b[0] & 0xFF) == 0x89
        && b[1] == 0x50 /* P */
        && b[2] == 0x4E /* N */
        && b[3] == 0x47 /* G */
        && b[4] == 0x0D
        && b[5] == 0x0A
        && b[6] == 0x1A
        && b[7] == 0x0A;
  }

  private static boolean isJpeg(byte[] b) {
    return b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8;
  }

  private static boolean isGif(byte[] b) {
    return b.length >= 6
        && b[0] == 'G'
        && b[1] == 'I'
        && b[2] == 'F'
        && b[3] == '8'
        && (b[4] == '7' || b[4] == '9')
        && b[5] == 'a';
  }

  private static boolean isWebp(byte[] b) {
    return b.length >= 12
        && b[0] == 'R'
        && b[1] == 'I'
        && b[2] == 'F'
        && b[3] == 'F'
        && b[8] == 'W'
        && b[9] == 'E'
        && b[10] == 'B'
        && b[11] == 'P';
  }

  private static boolean isBmp(byte[] b) {
    return b.length >= 2 && b[0] == 'B' && b[1] == 'M';
  }

  private static boolean isSvg(byte[] b) {
    String prefix =
        new String(b, 0, Math.min(b.length, 4096), StandardCharsets.UTF_8)
            .stripLeading()
            .toLowerCase(Locale.ROOT);
    return prefix.startsWith("<svg") || (prefix.startsWith("<?xml") && prefix.contains("<svg"));
  }
}
