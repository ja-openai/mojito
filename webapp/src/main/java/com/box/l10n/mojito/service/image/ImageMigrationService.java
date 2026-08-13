package com.box.l10n.mojito.service.image;

import com.box.l10n.mojito.entity.Image;
import com.box.l10n.mojito.service.blobstorage.BlobStorageRouter;
import com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage;
import com.box.l10n.mojito.service.blobstorage.database.DatabaseBlobStorage;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(value = "l10n.image-service.migration.enabled", havingValue = "true")
public class ImageMigrationService {

  private static final Logger logger = LoggerFactory.getLogger(ImageMigrationService.class);

  private final ImageRepository imageRepository;
  private final BlobStorageImageService blobStorageImageService;

  public ImageMigrationService(
      ImageRepository imageRepository,
      BlobStorageImageService blobStorageImageService,
      BlobStorageRouter blobStorageRouter) {
    if (blobStorageRouter.getBlobStorage(StructuredBlobStorage.Prefix.IMAGE)
        instanceof DatabaseBlobStorage) {
      throw new IllegalStateException(
          "Image migration requires the image blob-storage prefix to use a remote backend");
    }
    this.imageRepository = imageRepository;
    this.blobStorageImageService = blobStorageImageService;
  }

  public Result migrateImages(int batchSize, boolean deleteSource) {
    int normalizedBatchSize = Math.max(1, batchSize);
    long lastImageId = 0;
    int scanned = 0;
    int uploaded = 0;
    int deleted = 0;
    int failed = 0;
    int completed = 0;

    while (completed < normalizedBatchSize) {
      List<Image> images =
          imageRepository.findByIdGreaterThanOrderByIdAsc(
              lastImageId, PageRequest.of(0, normalizedBatchSize));
      if (images.isEmpty()) {
        break;
      }

      for (Image image : images) {
        lastImageId = image.getId();
        scanned++;
        boolean changed = false;

        try {
          boolean exists = blobStorageImageService.imageExists(image.getName());
          if (!exists) {
            blobStorageImageService.uploadImage(image.getName(), image.getContent());
            uploaded++;
            changed = true;
          }

          if (deleteSource) {
            Image storedImage =
                blobStorageImageService
                    .getImage(image.getName())
                    .orElseThrow(
                        () ->
                            new IllegalStateException(
                                "Migrated image is missing from blob storage: " + image.getName()));
            if (!Arrays.equals(image.getContent(), storedImage.getContent())) {
              throw new IllegalStateException(
                  "Migrated image content does not match the source: " + image.getName());
            }
            imageRepository.delete(image);
            deleted++;
            changed = true;
          }
        } catch (RuntimeException exception) {
          failed++;
          logger.error("Failed to migrate image {}", image.getName(), exception);
        }

        if (changed) {
          completed++;
        }

        if (completed >= normalizedBatchSize) {
          break;
        }
      }
    }

    return new Result(scanned, uploaded, deleted, failed);
  }

  public record Result(int scanned, int uploaded, int deleted, int failed) {}
}
