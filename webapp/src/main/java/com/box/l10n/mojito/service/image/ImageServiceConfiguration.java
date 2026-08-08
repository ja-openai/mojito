package com.box.l10n.mojito.service.image;

import com.box.l10n.mojito.service.blobstorage.BlobStorageRouter;
import com.box.l10n.mojito.service.blobstorage.BlobStorageType;
import com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage;
import com.box.l10n.mojito.service.blobstorage.s3.S3BlobStorage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Configuration for {@link ImageService}
 *
 * <p>{@link DatabaseImageService} is the default implementation when the image blob-storage prefix
 * is database-backed. A remote image prefix automatically enables database read fallback.
 *
 * <p>{@link BlobStorageImageService} uses the backend configured for the image blob-storage prefix.
 * Use it with `l10n.image-service.storage.type=blobStorage`.
 *
 * <p>{@link S3ImageService} and {@link S3FallbackImageService} both require that configured {@link
 * S3BlobStorage} and {@link com.amazonaws.services.s3.AmazonS3} client instances are available in
 * the container.
 */
@Configuration
public class ImageServiceConfiguration {

  @ConditionalOnProperty(value = "l10n.image-service.storage.type", havingValue = "blobStorage")
  static class BlobStorageImageServiceConfiguration {

    @Bean
    public BlobStorageImageService blobStorageImageService(
        BlobStorageRouter blobStorageRouter,
        @Value("${l10n.image-service.storage.blob-storage.prefix:image}") String pathPrefix) {
      return new BlobStorageImageService(
          blobStorageRouter.getBlobStorage(StructuredBlobStorage.Prefix.IMAGE), pathPrefix);
    }
  }

  @Conditional(BlobStorageFallbackImageServiceEnabledCondition.class)
  static class BlobStorageFallbackImageServiceConfiguration {

    @Autowired ImageRepository imageRepository;

    @Bean
    @Qualifier("databaseImageService")
    public DatabaseImageService databaseImageService() {
      return new DatabaseImageService(imageRepository);
    }

    @Bean
    @Qualifier("blobStorageImageService")
    public BlobStorageImageService blobStorageImageService(
        BlobStorageRouter blobStorageRouter,
        @Value("${l10n.image-service.storage.blob-storage.prefix:image}") String pathPrefix) {
      return new BlobStorageImageService(
          blobStorageRouter.getBlobStorage(StructuredBlobStorage.Prefix.IMAGE), pathPrefix);
    }

    @Bean
    public BlobStorageUploadImageAsyncTask blobStorageUploadImageAsyncTask(
        @Qualifier("blobStorageImageService") BlobStorageImageService blobStorageImageService) {
      return new BlobStorageUploadImageAsyncTask(blobStorageImageService);
    }

    @Bean
    @Primary
    public ImageService blobStorageImageFallback(
        @Qualifier("blobStorageImageService") BlobStorageImageService blobStorageImageService,
        @Qualifier("databaseImageService") DatabaseImageService databaseImageService,
        BlobStorageUploadImageAsyncTask blobStorageUploadImageAsyncTask) {
      return new BlobStorageFallbackImageService(
          blobStorageImageService, databaseImageService, blobStorageUploadImageAsyncTask);
    }
  }

  @ConditionalOnProperty(value = "l10n.image-service.storage.type", havingValue = "s3Fallback")
  static class S3FallbackImageServiceConfiguration {

    @Autowired ImageRepository imageRepository;

    @Autowired S3BlobStorage s3BlobStorage;

    @Bean
    @Qualifier("databaseImageService")
    public DatabaseImageService databaseImageService() {
      return new DatabaseImageService(imageRepository);
    }

    @Bean
    @Qualifier("s3ImageService")
    public S3ImageService s3ImageService(
        S3BlobStorage s3BlobStorage,
        @Value("${l10n.image-service.storage.s3.prefix:image}") String s3PathPrefix) {
      return new S3ImageService(s3BlobStorage, s3PathPrefix);
    }

    @Bean
    public S3UploadImageAsyncTask s3UploadImageAsyncTask(S3ImageService s3ImageService) {
      return new S3UploadImageAsyncTask(s3ImageService);
    }

    @Bean
    @Primary
    public ImageService s3ImageFallback(
        @Qualifier("s3ImageService") S3ImageService s3ImageService,
        @Qualifier("databaseImageService") DatabaseImageService databaseImageService,
        S3UploadImageAsyncTask s3UploadImageAsyncTask) {
      return new S3FallbackImageService(
          s3ImageService, databaseImageService, s3UploadImageAsyncTask);
    }
  }

  @ConditionalOnProperty(value = "l10n.image-service.storage.type", havingValue = "s3")
  static class S3ImageServiceConfiguration {

    @Autowired S3BlobStorage s3BlobStorage;

    @Bean
    public ImageService s3ImageService(
        S3BlobStorage s3BlobStorage,
        @Value("${l10n.image-service.storage.s3.prefix:image}") String s3PathPrefix) {
      return new S3ImageService(s3BlobStorage, s3PathPrefix);
    }
  }

  @Conditional(DatabaseImageServiceEnabledCondition.class)
  static class DatabaseImageServiceConfiguration {

    @Autowired ImageRepository imageRepository;

    @Bean
    public ImageService databaseImageService() {
      return new DatabaseImageService(imageRepository);
    }
  }

  static class BlobStorageFallbackImageServiceEnabledCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
      String explicitImageStorageType =
          context.getEnvironment().getProperty("l10n.image-service.storage.type");
      if (explicitImageStorageType != null) {
        return "blobStorageFallback".equals(explicitImageStorageType);
      }
      return getImageBlobStorageType(context) != BlobStorageType.DATABASE;
    }
  }

  static class DatabaseImageServiceEnabledCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
      String explicitImageStorageType =
          context.getEnvironment().getProperty("l10n.image-service.storage.type");
      if (explicitImageStorageType != null) {
        return "database".equals(explicitImageStorageType);
      }
      return getImageBlobStorageType(context) == BlobStorageType.DATABASE;
    }
  }

  private static BlobStorageType getImageBlobStorageType(ConditionContext context) {
    Binder binder = Binder.get(context.getEnvironment());
    return binder
        .bind("l10n.blob-storage.routing.prefixes.image", BlobStorageType.class)
        .orElseGet(
            () ->
                binder
                    .bind("l10n.blob-storage.default-type", BlobStorageType.class)
                    .orElseGet(
                        () ->
                            binder
                                .bind("l10n.blob-storage.type", BlobStorageType.class)
                                .orElse(BlobStorageType.DATABASE)));
  }
}
