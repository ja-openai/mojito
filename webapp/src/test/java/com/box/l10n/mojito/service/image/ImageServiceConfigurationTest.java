package com.box.l10n.mojito.service.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.service.blobstorage.BlobStorage;
import com.box.l10n.mojito.service.blobstorage.BlobStorageRouter;
import com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage;
import org.junit.Before;
import org.junit.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

public class ImageServiceConfigurationTest {

  private final BlobStorage imageBlobStorage = mock(BlobStorage.class);
  private final BlobStorageRouter blobStorageRouter = mock(BlobStorageRouter.class);
  private final ImageRepository imageRepository = mock(ImageRepository.class);

  private final ApplicationContextRunner applicationContextRunner =
      new ApplicationContextRunner()
          .withUserConfiguration(ImageServiceConfiguration.class)
          .withBean(BlobStorageRouter.class, () -> blobStorageRouter)
          .withBean(ImageRepository.class, () -> imageRepository);

  @Before
  public void setUp() {
    when(blobStorageRouter.getBlobStorage(StructuredBlobStorage.Prefix.IMAGE))
        .thenReturn(imageBlobStorage);
  }

  @Test
  public void blobStorageImageServiceUsesImagePrefixRoute() {
    applicationContextRunner
        .withPropertyValues("l10n.image-service.storage.type=blobStorage")
        .run(
            context -> {
              context.getBean(ImageService.class).uploadImage("direct.png", new byte[] {1});
              verify(imageBlobStorage).put("image/direct.png", new byte[] {1});
            });
  }

  @Test
  public void fallbackImageServiceUsesImagePrefixRoute() {
    applicationContextRunner
        .withPropertyValues("l10n.image-service.storage.type=blobStorageFallback")
        .run(
            context -> {
              context.getBean(ImageService.class).uploadImage("fallback.png", new byte[] {2});
              verify(imageBlobStorage).put("image/fallback.png", new byte[] {2});
            });
  }

  @Test
  public void imagePrefixRouteAutomaticallyEnablesDatabaseFallback() {
    applicationContextRunner
        .withPropertyValues("l10n.blob-storage.routing.prefixes.image=azure")
        .run(
            context -> {
              assertThat(context.getBean(ImageService.class))
                  .isInstanceOf(BlobStorageFallbackImageService.class);
              context.getBean(ImageService.class).uploadImage("automatic.png", new byte[] {3});
              verify(imageBlobStorage).put("image/automatic.png", new byte[] {3});
            });
  }

  @Test
  public void remoteDefaultAutomaticallyEnablesDatabaseFallback() {
    applicationContextRunner
        .withPropertyValues("l10n.blob-storage.default-type=azure")
        .run(
            context ->
                assertThat(context.getBean(ImageService.class))
                    .isInstanceOf(BlobStorageFallbackImageService.class));
  }

  @Test
  public void databaseImagePrefixOverridesRemoteDefault() {
    applicationContextRunner
        .withPropertyValues(
            "l10n.blob-storage.default-type=azure",
            "l10n.blob-storage.routing.prefixes.image=database")
        .run(
            context ->
                assertThat(context.getBean(ImageService.class))
                    .isInstanceOf(DatabaseImageService.class));
  }

  @Test
  public void explicitDatabaseImageStorageOverridesRemoteImageRoute() {
    applicationContextRunner
        .withPropertyValues(
            "l10n.blob-storage.routing.prefixes.image=azure",
            "l10n.image-service.storage.type=database")
        .run(
            context ->
                assertThat(context.getBean(ImageService.class))
                    .isInstanceOf(DatabaseImageService.class));
  }

  @Test
  public void databaseRemainsDefaultWhenNoBlobRouteIsConfigured() {
    applicationContextRunner.run(
        context ->
            assertThat(context.getBean(ImageService.class))
                .isInstanceOf(DatabaseImageService.class));
  }

  @Test
  public void migrationUsesImagePrefixRouteWithoutSeparateImageStorageSetting() {
    applicationContextRunner
        .withUserConfiguration(ImageMigrationService.class)
        .withPropertyValues(
            "l10n.blob-storage.routing.prefixes.image=azure",
            "l10n.image-service.migration.enabled=true")
        .run(context -> assertThat(context).hasSingleBean(ImageMigrationService.class));
  }
}
