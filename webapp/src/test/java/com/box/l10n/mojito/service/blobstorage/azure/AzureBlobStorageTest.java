package com.box.l10n.mojito.service.blobstorage.azure;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.azure.core.http.rest.Response;
import com.azure.core.util.BinaryData;
import com.azure.core.util.Context;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobErrorCode;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.blob.options.BlobParallelUploadOptions;
import com.box.l10n.mojito.service.blobstorage.Retention;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

public class AzureBlobStorageTest {

  BlobContainerClient blobContainerClient;
  BlobClient blobClient;
  AzureBlobStorage azureBlobStorage;

  @Before
  public void before() {
    blobContainerClient = mock(BlobContainerClient.class);
    blobClient = mock(BlobClient.class);

    AzureBlobStorageConfigurationProperties properties =
        new AzureBlobStorageConfigurationProperties();
    properties.setPrefix("prefix");

    azureBlobStorage = new AzureBlobStorage(blobContainerClient, properties);
    when(blobContainerClient.getBlobClient("prefix/name")).thenReturn(blobClient);
  }

  @Test
  public void testGetBytes() {
    byte[] content = "content".getBytes(StandardCharsets.UTF_8);
    when(blobClient.downloadContent()).thenReturn(BinaryData.fromBytes(content));

    Optional<byte[]> bytes = azureBlobStorage.getBytes("name");

    assertArrayEquals(content, bytes.get());
  }

  @Test
  public void testGetBytesNotFound() {
    BlobStorageException blobStorageException = blobNotFound();
    when(blobClient.downloadContent()).thenThrow(blobStorageException);

    Optional<byte[]> bytes = azureBlobStorage.getBytes("name");

    assertFalse(bytes.isPresent());
  }

  @Test
  public void testPutString() {
    ArgumentCaptor<BlobParallelUploadOptions> optionsCaptor =
        ArgumentCaptor.forClass(BlobParallelUploadOptions.class);
    when(blobClient.uploadWithResponse(optionsCaptor.capture(), eq(null), eq(Context.NONE)))
        .thenReturn(mock(Response.class));

    azureBlobStorage.put("name", "content", Retention.MIN_1_DAY);

    BlobParallelUploadOptions options = optionsCaptor.getValue();
    assertEquals("MIN_1_DAY", options.getTags().get("retention"));
    assertEquals("text/plain", options.getHeaders().getContentType());
    assertEquals("UTF-8", options.getHeaders().getContentEncoding());
  }

  @Test
  public void testPutBytes() {
    ArgumentCaptor<BlobParallelUploadOptions> optionsCaptor =
        ArgumentCaptor.forClass(BlobParallelUploadOptions.class);
    when(blobClient.uploadWithResponse(optionsCaptor.capture(), eq(null), eq(Context.NONE)))
        .thenReturn(mock(Response.class));

    azureBlobStorage.put("name", "content".getBytes(StandardCharsets.UTF_8), Retention.PERMANENT);

    BlobParallelUploadOptions options = optionsCaptor.getValue();
    assertEquals("PERMANENT", options.getTags().get("retention"));
  }

  @Test
  public void testDelete() {
    azureBlobStorage.delete("name");

    verify(blobClient).deleteIfExists();
  }

  @Test
  public void testExists() {
    when(blobClient.exists()).thenReturn(true);

    azureBlobStorage.exists("name");

    verify(blobClient).exists();
  }

  @Test
  public void testGetAzureUrl() {
    when(blobClient.getBlobUrl())
        .thenReturn("https://example.blob.core.windows.net/container/prefix/name");

    assertEquals(
        "https://example.blob.core.windows.net/container/prefix/name",
        azureBlobStorage.getAzureUrl("name"));
  }

  @Test
  public void testFullName() {
    azureBlobStorage.exists("name");

    verify(blobContainerClient).getBlobClient("prefix/name");
  }

  BlobStorageException blobNotFound() {
    BlobStorageException blobStorageException = mock(BlobStorageException.class);
    when(blobStorageException.getErrorCode()).thenReturn(BlobErrorCode.BLOB_NOT_FOUND);
    return blobStorageException;
  }
}
