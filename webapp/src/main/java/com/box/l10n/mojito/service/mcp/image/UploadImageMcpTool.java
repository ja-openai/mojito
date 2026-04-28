package com.box.l10n.mojito.service.mcp.image;

import com.box.l10n.mojito.entity.Image;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.image.ImageService;
import com.box.l10n.mojito.service.mcp.McpToolDescriptor;
import com.box.l10n.mojito.service.mcp.McpToolParameter;
import com.box.l10n.mojito.service.mcp.TypedMcpToolHandler;
import com.box.l10n.mojito.util.ImageBytes;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriUtils;

@Component
public class UploadImageMcpTool extends TypedMcpToolHandler<UploadImageMcpTool.Input> {

  private static final int MAX_IMAGE_SIZE_BYTES = 20 * 1024 * 1024;
  private static final Pattern DATA_URL_PATTERN =
      Pattern.compile("^data:([^;,]+);base64,(.*)$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

  private static final McpToolDescriptor DESCRIPTOR =
      new McpToolDescriptor(
          "image.upload",
          "Upload image",
          "Upload an image to Mojito image storage and return an imageKey that can be used as glossary screenshot evidence.",
          false,
          false,
          List.of(
              new McpToolParameter(
                  "imageKey",
                  "Optional storage key. If omitted, one is generated from filename/content type.",
                  false),
              new McpToolParameter(
                  "filename",
                  "Optional original filename used when generating an imageKey and inferring content type.",
                  false),
              new McpToolParameter(
                  "dataUrl",
                  "Image data URL, for example data:image/png;base64,... Exactly one of dataUrl or contentBase64 is required.",
                  false),
              new McpToolParameter(
                  "contentBase64",
                  "Base64-encoded image bytes. Exactly one of dataUrl or contentBase64 is required.",
                  false),
              new McpToolParameter(
                  "overwrite",
                  "Defaults to false. Set true to replace an existing image with the same imageKey.",
                  false,
                  Boolean.class)));

  private final ImageService imageService;

  public UploadImageMcpTool(
      @Qualifier("fail_on_unknown_properties_false") ObjectMapper objectMapper,
      ImageService imageService) {
    super(objectMapper, Input.class, DESCRIPTOR);
    this.imageService = Objects.requireNonNull(imageService);
  }

  public record Input(
      String imageKey, String filename, String dataUrl, String contentBase64, Boolean overwrite) {}

  public record UploadImageResult(
      String imageKey, String url, String contentType, long sizeBytes, boolean overwritten) {}

  private record ParsedImage(byte[] content, String declaredContentType) {}

  @Override
  protected Object execute(Input input) {
    ParsedImage parsedImage = parseImage(input);
    String imageKey = resolveImageKey(input, parsedImage);
    String contentType = resolveContentType(imageKey, parsedImage);
    validateImageContent(imageKey, contentType, parsedImage.content());

    Optional<Image> existingImage = imageService.getImage(imageKey);
    boolean overwritten = existingImage.isPresent();
    if (overwritten && !Boolean.TRUE.equals(input.overwrite())) {
      throw new IllegalArgumentException(
          "Image already exists for imageKey: " + imageKey + ". Set overwrite=true to replace it.");
    }

    imageService.uploadImage(imageKey, parsedImage.content());
    return new UploadImageResult(
        imageKey,
        "/api/images/" + UriUtils.encodePathSegment(imageKey, StandardCharsets.UTF_8),
        contentType,
        parsedImage.content().length,
        overwritten);
  }

  private ParsedImage parseImage(Input input) {
    if (input == null) {
      throw new IllegalArgumentException("arguments are required");
    }

    String dataUrl = normalizeOptional(input.dataUrl());
    String contentBase64 = normalizeOptional(input.contentBase64());
    if ((dataUrl == null && contentBase64 == null) || (dataUrl != null && contentBase64 != null)) {
      throw new IllegalArgumentException("Exactly one of dataUrl or contentBase64 is required");
    }

    if (dataUrl != null) {
      Matcher matcher = DATA_URL_PATTERN.matcher(dataUrl);
      if (!matcher.matches()) {
        throw new IllegalArgumentException("dataUrl must be a base64 image data URL");
      }
      return new ParsedImage(
          decodeBase64(matcher.group(2)), normalizeContentType(matcher.group(1)));
    }

    return new ParsedImage(decodeBase64(contentBase64), null);
  }

  private byte[] decodeBase64(String value) {
    try {
      return Base64.getMimeDecoder().decode(value);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Image content is not valid base64", e);
    }
  }

  private String resolveImageKey(Input input, ParsedImage parsedImage) {
    String providedImageKey = normalizeOptional(input.imageKey());
    if (providedImageKey != null) {
      return validateImageKey(providedImageKey);
    }

    String filename = normalizeOptional(input.filename());
    String baseName = sanitizeBaseName(filename == null ? "image" : filename);
    String extension =
        ImageBytes.imageExtensionForContentType(parsedImage.declaredContentType()).orElse(null);
    if (extension == null) {
      extension =
          ImageBytes.detectImageContentType(parsedImage.content())
              .flatMap(ImageBytes::imageExtensionForContentType)
              .orElse(null);
    }
    if (extension == null) {
      extension = extensionFromFilename(filename);
    }
    if (extension == null) {
      extension =
          ImageBytes.imageExtensionForContentType(
                  ImageBytes.fromBytes(baseName, parsedImage.content()).contentType())
              .orElse(null);
    }
    if (extension == null) {
      extension = "img";
    }

    return baseName + "-" + UUID.randomUUID().toString().substring(0, 8) + "." + extension;
  }

  private String resolveContentType(String imageKey, ParsedImage parsedImage) {
    if (parsedImage.declaredContentType() != null) {
      return parsedImage.declaredContentType();
    }
    return ImageBytes.detectImageContentType(parsedImage.content())
        .orElseGet(() -> ImageBytes.fromBytes(imageKey, parsedImage.content()).contentType());
  }

  private void validateImageContent(String imageKey, String contentType, byte[] content) {
    if (content.length == 0) {
      throw new IllegalArgumentException("Image content must not be empty");
    }
    if (content.length > MAX_IMAGE_SIZE_BYTES) {
      throw new IllegalArgumentException(
          "Image content is too large. Maximum size is " + MAX_IMAGE_SIZE_BYTES + " bytes");
    }
    if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("image/")) {
      throw new IllegalArgumentException("Only image uploads are supported");
    }
    String detectedContentType = ImageBytes.detectImageContentType(content).orElse(null);
    if (detectedContentType == null) {
      throw new IllegalArgumentException("Only image uploads are supported");
    }
    if (!ImageBytes.normalizeImageContentType(contentType)
        .equals(ImageBytes.normalizeImageContentType(detectedContentType))) {
      throw new IllegalArgumentException("Image content does not match the declared content type");
    }
    String storageContentType = ImageBytes.fromBytes(imageKey, content).contentType();
    if (storageContentType.toLowerCase(Locale.ROOT).startsWith("image/")
        && !ImageBytes.normalizeImageContentType(storageContentType)
            .equals(ImageBytes.normalizeImageContentType(detectedContentType))) {
      throw new IllegalArgumentException("Image key extension does not match image content");
    }
  }

  private String validateImageKey(String imageKey) {
    if (imageKey.startsWith("/") || imageKey.contains("\\") || imageKey.contains("..")) {
      throw new IllegalArgumentException("imageKey must be a relative image storage key");
    }
    for (int i = 0; i < imageKey.length(); i++) {
      if (Character.isISOControl(imageKey.charAt(i))) {
        throw new IllegalArgumentException("imageKey must not contain control characters");
      }
    }
    return imageKey;
  }

  private static String normalizeOptional(String value) {
    if (value == null || value.trim().isEmpty()) {
      return null;
    }
    return value.trim();
  }

  private static String normalizeContentType(String value) {
    String normalized = normalizeOptional(value);
    return normalized == null ? null : normalized.toLowerCase(Locale.ROOT);
  }

  private static String sanitizeBaseName(String filename) {
    String name = filename;
    int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
    if (slash >= 0) {
      name = name.substring(slash + 1);
    }
    int dot = name.lastIndexOf('.');
    if (dot > 0) {
      name = name.substring(0, dot);
    }
    String sanitized =
        name.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9._-]+", "-")
            .replaceAll("-+", "-")
            .replaceAll("^-+|-+$", "");
    if (sanitized.isBlank()) {
      return "image";
    }
    return sanitized.length() > 80 ? sanitized.substring(0, 80) : sanitized;
  }

  private static String extensionFromFilename(String filename) {
    String normalized = normalizeOptional(filename);
    if (normalized == null) {
      return null;
    }
    int dot = normalized.lastIndexOf('.');
    if (dot < 0 || dot == normalized.length() - 1) {
      return null;
    }
    String extension = normalized.substring(dot + 1).toLowerCase(Locale.ROOT);
    return ImageBytes.imageContentTypeForExtension(extension).isPresent() ? extension : null;
  }
}
