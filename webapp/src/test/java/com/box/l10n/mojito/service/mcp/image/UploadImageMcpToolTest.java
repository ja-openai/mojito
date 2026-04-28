package com.box.l10n.mojito.service.mcp.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.Image;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.image.ImageService;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import org.junit.Test;
import org.mockito.Mockito;

public class UploadImageMcpToolTest {

  private final ImageService imageService = Mockito.mock(ImageService.class);
  private final UploadImageMcpTool tool =
      new UploadImageMcpTool(ObjectMapper.withNoFailOnUnknownProperties(), imageService);

  @Test
  public void executeUploadsDataUrlWithProvidedImageKey() {
    byte[] content = pngBytes();
    when(imageService.getImage("settings.png")).thenReturn(Optional.empty());

    Object result =
        tool.execute(
            new UploadImageMcpTool.Input(
                "settings.png",
                null,
                "data:image/png;base64," + Base64.getEncoder().encodeToString(content),
                null,
                null));

    verify(imageService).uploadImage(eq("settings.png"), aryEq(content));
    assertThat(result)
        .isEqualTo(
            new UploadImageMcpTool.UploadImageResult(
                "settings.png", "/api/images/settings.png", "image/png", content.length, false));
  }

  @Test
  public void executeGeneratesImageKeyFromFilename() {
    byte[] content = pngBytes();

    Object result =
        tool.execute(
            new UploadImageMcpTool.Input(
                null,
                "Product Settings Screenshot.png",
                null,
                Base64.getEncoder().encodeToString(content),
                null));

    UploadImageMcpTool.UploadImageResult uploadResult =
        (UploadImageMcpTool.UploadImageResult) result;
    assertThat(uploadResult.imageKey()).startsWith("product-settings-screenshot-").endsWith(".png");
    assertThat(uploadResult.contentType()).isEqualTo("image/png");
    assertThat(uploadResult.sizeBytes()).isEqualTo(content.length);
    assertThat(uploadResult.overwritten()).isFalse();
    verify(imageService).uploadImage(eq(uploadResult.imageKey()), aryEq(content));
  }

  @Test
  public void executeRejectsExistingImageUnlessOverwriteIsTrue() {
    byte[] content = pngBytes();
    when(imageService.getImage("settings.png")).thenReturn(Optional.of(new Image()));

    assertThatThrownBy(
            () ->
                tool.execute(
                    new UploadImageMcpTool.Input(
                        "settings.png",
                        null,
                        "data:image/png;base64," + Base64.getEncoder().encodeToString(content),
                        null,
                        false)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(
            "Image already exists for imageKey: settings.png. Set overwrite=true to replace it.");

    verify(imageService, never()).uploadImage(eq("settings.png"), aryEq(content));
  }

  @Test
  public void executeSupportsExplicitOverwrite() {
    byte[] content = pngBytes();
    when(imageService.getImage("settings.png")).thenReturn(Optional.of(new Image()));

    Object result =
        tool.execute(
            new UploadImageMcpTool.Input(
                "settings.png",
                null,
                "data:image/png;base64," + Base64.getEncoder().encodeToString(content),
                null,
                true));

    verify(imageService).uploadImage(eq("settings.png"), aryEq(content));
    assertThat(result)
        .isEqualTo(
            new UploadImageMcpTool.UploadImageResult(
                "settings.png", "/api/images/settings.png", "image/png", content.length, true));
  }

  @Test
  public void executeRejectsNonImageContent() {
    String base64 =
        Base64.getEncoder().encodeToString("not an image".getBytes(StandardCharsets.UTF_8));

    assertThatThrownBy(
            () -> tool.execute(new UploadImageMcpTool.Input("note.txt", null, null, base64, null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Only image uploads are supported");
  }

  @Test
  public void executeRejectsNonImageContentEvenWhenImageKeyHasImageExtension() {
    String base64 =
        Base64.getEncoder().encodeToString("not an image".getBytes(StandardCharsets.UTF_8));

    assertThatThrownBy(
            () -> tool.execute(new UploadImageMcpTool.Input("fake.png", null, null, base64, null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Only image uploads are supported");
  }

  @Test
  public void executeRejectsDataUrlWhenDeclaredContentTypeDoesNotMatchBytes() {
    byte[] content = pngBytes();

    assertThatThrownBy(
            () ->
                tool.execute(
                    new UploadImageMcpTool.Input(
                        "settings.jpg",
                        null,
                        "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(content),
                        null,
                        null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Image content does not match the declared content type");
  }

  @Test
  public void executeRejectsImageKeyExtensionThatDoesNotMatchBytes() {
    byte[] content = pngBytes();

    assertThatThrownBy(
            () ->
                tool.execute(
                    new UploadImageMcpTool.Input(
                        "settings.jpg",
                        null,
                        null,
                        Base64.getEncoder().encodeToString(content),
                        null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Image key extension does not match image content");
  }

  @Test
  public void executeRejectsUnsupportedImageFormats() {
    byte[] content = new byte[] {0, 0, 0, 0, 'f', 't', 'y', 'p', 'a', 'v', 'i', 'f'};

    assertThatThrownBy(
            () ->
                tool.execute(
                    new UploadImageMcpTool.Input(
                        "settings.avif",
                        null,
                        "data:image/avif;base64," + Base64.getEncoder().encodeToString(content),
                        null,
                        null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Only image uploads are supported");
  }

  @Test
  public void executeRequiresExactlyOneContentField() {
    assertThatThrownBy(
            () -> tool.execute(new UploadImageMcpTool.Input("a.png", null, null, null, null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Exactly one of dataUrl or contentBase64 is required");
  }

  private static byte[] pngBytes() {
    return new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
  }
}
