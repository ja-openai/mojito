package com.box.l10n.mojito.service.image;

import static org.slf4j.LoggerFactory.getLogger;

import org.slf4j.Logger;
import org.springframework.scheduling.annotation.Async;

/** Async task to migrate an image from the database to blob storage. */
public class BlobStorageUploadImageAsyncTask {

  static Logger logger = getLogger(BlobStorageUploadImageAsyncTask.class);

  BlobStorageImageService blobStorageImageService;

  public BlobStorageUploadImageAsyncTask(BlobStorageImageService blobStorageImageService) {
    this.blobStorageImageService = blobStorageImageService;
  }

  @Async
  public void uploadImageToBlobStorage(String name, byte[] content) {
    logger.debug("Uploading image {} to blob storage", name);
    blobStorageImageService.uploadImage(name, content);
  }
}
