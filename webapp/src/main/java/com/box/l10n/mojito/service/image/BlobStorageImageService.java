package com.box.l10n.mojito.service.image;

import static org.slf4j.LoggerFactory.getLogger;

import com.box.l10n.mojito.entity.Image;
import com.box.l10n.mojito.service.blobstorage.BlobStorage;
import java.util.Optional;
import org.slf4j.Logger;

/** Service to upload and retrieve images from the configured {@link BlobStorage}. */
public class BlobStorageImageService implements ImageService {

  static Logger logger = getLogger(BlobStorageImageService.class);

  BlobStorage blobStorage;

  String pathPrefix;

  public BlobStorageImageService(BlobStorage blobStorage, String pathPrefix) {
    this.blobStorage = blobStorage;
    this.pathPrefix = pathPrefix;
  }

  public Optional<Image> getImage(String name) {
    logger.debug("Get image from blob storage with name: {}", name);

    return blobStorage
        .getBytes(getPath(name))
        .map(
            bytes -> {
              Image image = new Image();
              image.setName(name);
              image.setContent(bytes);
              return image;
            });
  }

  public void uploadImage(String name, byte[] content) {
    logger.debug("Upload image to blob storage with name: {}", name);
    blobStorage.put(getPath(name), content);
  }

  String getPath(String name) {
    return pathPrefix + "/" + name;
  }
}
