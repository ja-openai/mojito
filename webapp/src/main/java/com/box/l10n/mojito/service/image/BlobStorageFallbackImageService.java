package com.box.l10n.mojito.service.image;

import static org.slf4j.LoggerFactory.getLogger;

import com.box.l10n.mojito.entity.Image;
import java.util.Optional;
import org.slf4j.Logger;

/**
 * Service that retrieves images from blob storage and falls back to the Mojito DB if an image is
 * not available in blob storage.
 */
public class BlobStorageFallbackImageService implements ImageService {

  static Logger logger = getLogger(BlobStorageFallbackImageService.class);

  BlobStorageImageService blobStorageImageService;

  DatabaseImageService databaseImageService;

  BlobStorageUploadImageAsyncTask blobStorageUploadImageAsyncTask;

  public BlobStorageFallbackImageService(
      BlobStorageImageService blobStorageImageService,
      DatabaseImageService databaseImageService,
      BlobStorageUploadImageAsyncTask blobStorageUploadImageAsyncTask) {
    this.blobStorageImageService = blobStorageImageService;
    this.databaseImageService = databaseImageService;
    this.blobStorageUploadImageAsyncTask = blobStorageUploadImageAsyncTask;
  }

  @Override
  public Optional<Image> getImage(String name) {
    logger.debug("Attempt image retrieval from blob storage with name: {}", name);
    Image image =
        blobStorageImageService
            .getImage(name)
            .orElseGet(
                () ->
                    databaseImageService
                        .getImage(name)
                        .map(
                            img -> {
                              logger.debug(
                                  "Found image {} in database, triggering async upload to blob storage",
                                  img.getName());
                              blobStorageUploadImageAsyncTask.uploadImageToBlobStorage(
                                  img.getName(), img.getContent());
                              return img;
                            })
                        .orElse(null));
    return Optional.ofNullable(image);
  }

  @Override
  public void uploadImage(String name, byte[] content) {
    blobStorageImageService.uploadImage(name, content);
  }
}
