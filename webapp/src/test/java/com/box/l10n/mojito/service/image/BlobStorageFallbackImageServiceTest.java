package com.box.l10n.mojito.service.image;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.Image;
import com.box.l10n.mojito.service.blobstorage.BlobStorage;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

@RunWith(SpringJUnit4ClassRunner.class)
@SpringBootTest(
    classes = {
      BlobStorageFallbackImageServiceTest.class,
      BlobStorageFallbackImageServiceTest.BlobStorageFallbackImageServiceTestConfiguration.class
    },
    properties = {"l10n.image-service.storage.type=blobStorageFallback"})
public class BlobStorageFallbackImageServiceTest {

  @TestConfiguration
  static class BlobStorageFallbackImageServiceTestConfiguration {

    @Bean
    public ImageRepository imageRepository() {
      return Mockito.mock(ImageRepository.class);
    }

    @Bean
    public BlobStorage blobStorage() {
      return Mockito.mock(BlobStorage.class);
    }

    @Bean("databaseImageService")
    public DatabaseImageService databaseImageService(ImageRepository imageRepository) {
      return Mockito.spy(new DatabaseImageService(imageRepository));
    }

    @Bean("blobStorageImageService")
    public BlobStorageImageService blobStorageImageService(
        BlobStorage blobStorage,
        @Value("${l10n.image-service.storage.blob-storage.prefix:image}") String pathPrefix) {
      return Mockito.spy(new BlobStorageImageService(blobStorage, pathPrefix));
    }

    @Bean
    public BlobStorageUploadImageAsyncTask blobStorageUploadImageAsyncTask(
        @Qualifier("blobStorageImageService") BlobStorageImageService blobStorageImageService) {
      return Mockito.spy(new BlobStorageUploadImageAsyncTask(blobStorageImageService));
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

  @Autowired BlobStorage blobStorage;

  @Autowired ImageRepository imageRepositoryMock;

  @Autowired BlobStorageUploadImageAsyncTask blobStorageUploadImageAsyncTaskSpy;

  @Autowired BlobStorageImageService blobStorageImageService;

  @Autowired ImageService blobStorageFallbackImageService;

  byte[] imageBytes = new byte[] {1, 2, 3, 4, 5};

  Optional<byte[]> imageContent;

  @Before
  public void setup() {
    imageContent = Optional.of(imageBytes);
    Mockito.reset(
        blobStorage,
        imageRepositoryMock,
        blobStorageUploadImageAsyncTaskSpy,
        blobStorageImageService);
  }

  @Test
  public void testGetImageFromBlobStorage() {
    when(blobStorage.getBytes(anyString())).thenReturn(imageContent);
    Optional<Image> image = blobStorageFallbackImageService.getImage("testName");
    assertEquals("testName", image.get().getName());
    assertEquals(imageBytes, image.get().getContent());
    verify(blobStorageImageService, times(1)).getImage("testName");
    verify(blobStorage, times(1)).getBytes("image/testName");
    verifyNoInteractions(imageRepositoryMock, blobStorageUploadImageAsyncTaskSpy);
  }

  @Test
  public void testUploadImageToBlobStorage() {
    blobStorageFallbackImageService.uploadImage("testImage", imageBytes);
    verify(blobStorage, times(1)).put("image/testImage", imageBytes);
    verifyNoInteractions(imageRepositoryMock, blobStorageUploadImageAsyncTaskSpy);
  }

  @Test
  public void testDatabaseCheckedForImageIfNotInBlobStorage() {
    when(blobStorage.getBytes(anyString())).thenReturn(Optional.empty());
    when(imageRepositoryMock.findByName("test")).thenReturn(Optional.empty());
    Optional<Image> image = blobStorageFallbackImageService.getImage("test");
    assertFalse(image.isPresent());
    verify(blobStorageImageService, times(1)).getImage("test");
    verify(blobStorage, times(1)).getBytes("image/test");
    verify(imageRepositoryMock, times(1)).findByName("test");
    verifyNoInteractions(blobStorageUploadImageAsyncTaskSpy);
  }

  @Test
  public void testImageUploadedToBlobStorageIfFoundInDB() {
    Image image = new Image();
    image.setName("test");
    image.setContent(imageBytes);
    when(blobStorage.getBytes(anyString())).thenReturn(Optional.empty());
    when(imageRepositoryMock.findByName("test")).thenReturn(Optional.of(image));
    Optional<Image> retrievedImage = blobStorageFallbackImageService.getImage("test");
    assertTrue(retrievedImage.isPresent());
    assertEquals("test", image.getName());
    assertEquals(imageBytes, retrievedImage.get().getContent());
    verify(blobStorageImageService, times(1)).getImage("test");
    verify(blobStorage, times(1)).getBytes("image/test");
    verify(imageRepositoryMock, times(1)).findByName("test");
    verify(blobStorageUploadImageAsyncTaskSpy, times(1))
        .uploadImageToBlobStorage("test", retrievedImage.get().getContent());
    verify(blobStorage, timeout(3000).times(1))
        .put("image/test", retrievedImage.get().getContent());
  }
}
