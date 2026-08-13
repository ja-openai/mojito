package com.box.l10n.mojito.service.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.Image;
import com.box.l10n.mojito.service.blobstorage.BlobStorage;
import com.box.l10n.mojito.service.blobstorage.BlobStorageRouter;
import com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage;
import com.box.l10n.mojito.service.blobstorage.database.DatabaseBlobStorage;
import java.util.List;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;
import org.springframework.data.domain.Pageable;

public class ImageMigrationServiceTest {

  private ImageRepository imageRepository;
  private BlobStorageImageService blobStorageImageService;
  private BlobStorageRouter blobStorageRouter;
  private ImageMigrationService imageMigrationService;

  @Before
  public void setUp() {
    imageRepository = mock(ImageRepository.class);
    blobStorageImageService = mock(BlobStorageImageService.class);
    blobStorageRouter = mock(BlobStorageRouter.class);
    when(blobStorageRouter.getBlobStorage(StructuredBlobStorage.Prefix.IMAGE))
        .thenReturn(mock(BlobStorage.class));
    imageMigrationService =
        new ImageMigrationService(imageRepository, blobStorageImageService, blobStorageRouter);
  }

  @Test
  public void uploadsDatabaseImagesWithoutDeletingThemByDefault() {
    Image image = image(1L, "first.png", new byte[] {1, 2, 3});
    when(imageRepository.findByIdGreaterThanOrderByIdAsc(eq(0L), any(Pageable.class)))
        .thenReturn(List.of(image));
    when(imageRepository.findByIdGreaterThanOrderByIdAsc(eq(1L), any(Pageable.class)))
        .thenReturn(List.of());

    ImageMigrationService.Result result = imageMigrationService.migrateImages(25, false);

    assertThat(result).isEqualTo(new ImageMigrationService.Result(1, 1, 0, 0));
    verify(blobStorageImageService).uploadImage("first.png", image.getContent());
    verify(imageRepository, never()).delete(any(Image.class));
  }

  @Test
  public void skipsExistingImagesAndFindsLaterImages() {
    Image existing = image(1L, "existing.png", new byte[] {1});
    Image missing = image(2L, "missing.png", new byte[] {2});
    when(imageRepository.findByIdGreaterThanOrderByIdAsc(eq(0L), any(Pageable.class)))
        .thenReturn(List.of(existing));
    when(imageRepository.findByIdGreaterThanOrderByIdAsc(eq(1L), any(Pageable.class)))
        .thenReturn(List.of(missing));
    when(blobStorageImageService.imageExists("existing.png")).thenReturn(true);

    ImageMigrationService.Result result = imageMigrationService.migrateImages(1, false);

    assertThat(result).isEqualTo(new ImageMigrationService.Result(2, 1, 0, 0));
    verify(blobStorageImageService, never()).uploadImage(eq("existing.png"), any(byte[].class));
    verify(blobStorageImageService).uploadImage("missing.png", missing.getContent());
  }

  @Test
  public void deletesSourceOnlyAfterVerifyingRemoteBytes() {
    Image image = image(1L, "verified.png", new byte[] {1, 2, 3});
    when(imageRepository.findByIdGreaterThanOrderByIdAsc(eq(0L), any(Pageable.class)))
        .thenReturn(List.of(image));
    when(blobStorageImageService.getImage("verified.png")).thenReturn(Optional.of(image));

    ImageMigrationService.Result result = imageMigrationService.migrateImages(1, true);

    assertThat(result).isEqualTo(new ImageMigrationService.Result(1, 1, 1, 0));
    verify(blobStorageImageService).uploadImage("verified.png", image.getContent());
    verify(imageRepository).delete(image);
  }

  @Test
  public void countsAnUploadAndDeleteAsOneMigratedImage() {
    Image first = image(1L, "first.png", new byte[] {1});
    Image second = image(2L, "second.png", new byte[] {2});
    when(imageRepository.findByIdGreaterThanOrderByIdAsc(eq(0L), any(Pageable.class)))
        .thenReturn(List.of(first, second));
    when(blobStorageImageService.getImage("first.png")).thenReturn(Optional.of(first));
    when(blobStorageImageService.getImage("second.png")).thenReturn(Optional.of(second));

    ImageMigrationService.Result result = imageMigrationService.migrateImages(2, true);

    assertThat(result).isEqualTo(new ImageMigrationService.Result(2, 2, 2, 0));
    verify(imageRepository, times(2)).delete(any(Image.class));
  }

  @Test
  public void deletesExistingRemoteImagesAfterVerifyingThem() {
    Image image = image(1L, "existing.png", new byte[] {1, 2, 3});
    when(imageRepository.findByIdGreaterThanOrderByIdAsc(eq(0L), any(Pageable.class)))
        .thenReturn(List.of(image));
    when(blobStorageImageService.imageExists("existing.png")).thenReturn(true);
    when(blobStorageImageService.getImage("existing.png")).thenReturn(Optional.of(image));

    ImageMigrationService.Result result = imageMigrationService.migrateImages(1, true);

    assertThat(result).isEqualTo(new ImageMigrationService.Result(1, 0, 1, 0));
    verify(blobStorageImageService, never()).uploadImage(any(String.class), any(byte[].class));
    verify(imageRepository).delete(image);
  }

  @Test
  public void preservesSourceWhenRemoteVerificationFails() {
    Image source = image(1L, "mismatch.png", new byte[] {1, 2, 3});
    Image remote = image(1L, "mismatch.png", new byte[] {9, 9, 9});
    when(imageRepository.findByIdGreaterThanOrderByIdAsc(eq(0L), any(Pageable.class)))
        .thenReturn(List.of(source));
    when(imageRepository.findByIdGreaterThanOrderByIdAsc(eq(1L), any(Pageable.class)))
        .thenReturn(List.of());
    when(blobStorageImageService.getImage("mismatch.png")).thenReturn(Optional.of(remote));

    ImageMigrationService.Result result = imageMigrationService.migrateImages(25, true);

    assertThat(result).isEqualTo(new ImageMigrationService.Result(1, 1, 0, 1));
    verify(imageRepository, never()).delete(any(Image.class));
  }

  @Test
  public void refusesDatabaseBackedImageMigration() {
    when(blobStorageRouter.getBlobStorage(StructuredBlobStorage.Prefix.IMAGE))
        .thenReturn(mock(DatabaseBlobStorage.class));

    assertThatThrownBy(
            () ->
                new ImageMigrationService(
                    imageRepository, blobStorageImageService, blobStorageRouter))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("remote backend");
  }

  private Image image(Long id, String name, byte[] content) {
    Image image = new Image();
    image.setId(id);
    image.setName(name);
    image.setContent(content);
    return image;
  }
}
