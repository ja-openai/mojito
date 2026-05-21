package com.box.l10n.mojito.service.image;

import static org.slf4j.LoggerFactory.getLogger;

import org.slf4j.Logger;
import org.springframework.core.task.AsyncTaskExecutor;

/**
 * Async task to upload an image to S3.
 *
 * @author maallen
 */
public class S3UploadImageAsyncTask {

  /** logger */
  static Logger logger = getLogger(S3UploadImageAsyncTask.class);

  S3ImageService s3ImageService;

  AsyncTaskExecutor asyncExecutor;

  public S3UploadImageAsyncTask(S3ImageService s3ImageService, AsyncTaskExecutor asyncExecutor) {
    this.s3ImageService = s3ImageService;
    this.asyncExecutor = asyncExecutor;
  }

  public void uploadImageToS3(String name, byte[] content) {
    asyncExecutor.execute(
        () -> {
          logger.debug("Uploading image {} to S3", name);
          s3ImageService.uploadImage(name, content);
        });
  }
}
