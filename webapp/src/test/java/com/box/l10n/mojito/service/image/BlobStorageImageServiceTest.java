package com.box.l10n.mojito.service.image;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.Image;
import com.box.l10n.mojito.service.blobstorage.BlobStorage;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class BlobStorageImageServiceTest {

  @Mock BlobStorage blobStorage;

  BlobStorageImageService blobStorageImageService;

  byte[] imageContent = new byte[] {1, 2, 3, 4, 5};

  @Before
  public void setup() {
    MockitoAnnotations.initMocks(this);
    blobStorageImageService = new BlobStorageImageService(blobStorage, "image");
  }

  @Test
  public void testGetImageFromBlobStorage() {
    when(blobStorage.getBytes(anyString())).thenReturn(Optional.of(imageContent));
    Optional<Image> image = blobStorageImageService.getImage("testImage");
    verify(blobStorage, times(1)).getBytes("image/testImage");
    assertEquals("testImage", image.get().getName());
    assertEquals(imageContent, image.get().getContent());
  }

  @Test
  public void testImageNotAvailableInBlobStorage() {
    when(blobStorage.getBytes(anyString())).thenReturn(Optional.empty());
    Optional<Image> image = blobStorageImageService.getImage("testImage");
    verify(blobStorage, times(1)).getBytes("image/testImage");
    assertFalse(image.isPresent());
  }

  @Test
  public void testUploadImageToBlobStorage() {
    blobStorageImageService.uploadImage("testImage", imageContent);
    verify(blobStorage, times(1)).put("image/testImage", imageContent);
  }
}
