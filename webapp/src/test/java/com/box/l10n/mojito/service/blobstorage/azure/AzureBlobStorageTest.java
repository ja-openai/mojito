package com.box.l10n.mojito.service.blobstorage.azure;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.azure.core.http.HttpHeaders;
import com.azure.core.http.HttpRequest;
import com.azure.core.http.HttpResponse;
import com.azure.core.util.Context;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.blob.options.BlobParallelUploadOptions;
import com.box.l10n.mojito.service.blobstorage.Retention;
import java.io.ByteArrayOutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

public class AzureBlobStorageTest {

  static final String PREFIX = "mojito";

  BlobContainerClient blobContainerClient = mock(BlobContainerClient.class);
  BlobClient blobClient = mock(BlobClient.class);
  AzureBlobStorage azureBlobStorage;

  @Before
  public void before() {
    AzureBlobStorageConfigurationProperties properties =
        new AzureBlobStorageConfigurationProperties();
    properties.setPrefix(PREFIX);
    properties.setContainer("container");

    when(blobContainerClient.getBlobClient("mojito/path/name")).thenReturn(blobClient);

    azureBlobStorage = new AzureBlobStorage(blobContainerClient, properties);
  }

  @Test
  public void getBytesReturnsEmptyWhenBlobDoesNotExist() {
    when(blobClient.downloadContent()).thenThrow(blobStorageException(404));

    Optional<byte[]> bytes = azureBlobStorage.getBytes("path/name");

    assertFalse(bytes.isPresent());
  }

  @Test
  public void getBytesReturnsContentWhenBlobExists() {
    byte[] content = "content".getBytes(StandardCharsets.UTF_8);
    when(blobClient.downloadContent())
        .thenReturn(com.azure.core.util.BinaryData.fromBytes(content));

    Optional<byte[]> bytes = azureBlobStorage.getBytes("path/name");

    assertTrue(bytes.isPresent());
    assertArrayEquals(content, bytes.get());
  }

  @Test
  public void putBytesUploadsAndTagsContent() {
    byte[] content = "content".getBytes(StandardCharsets.UTF_8);

    azureBlobStorage.put("path/name", content, Retention.MIN_1_DAY);

    ArgumentCaptor<BlobParallelUploadOptions> blobParallelUploadOptionsCaptor =
        ArgumentCaptor.forClass(BlobParallelUploadOptions.class);
    verify(blobClient)
        .uploadWithResponse(blobParallelUploadOptionsCaptor.capture(), isNull(), eq(Context.NONE));

    BlobParallelUploadOptions blobParallelUploadOptions =
        blobParallelUploadOptionsCaptor.getValue();
    assertArrayEquals(content, toByteArray(blobParallelUploadOptions.getDataFlux()));
    assertEquals(content.length, blobParallelUploadOptions.getLength());
    assertEquals("MIN_1_DAY", blobParallelUploadOptions.getTags().get("retention"));
  }

  @Test
  public void putStringUploadsTextAndHeaders() {
    String content = "コンテンツ";

    azureBlobStorage.put("path/name", content, Retention.PERMANENT);

    ArgumentCaptor<BlobParallelUploadOptions> blobParallelUploadOptionsCaptor =
        ArgumentCaptor.forClass(BlobParallelUploadOptions.class);
    verify(blobClient)
        .uploadWithResponse(blobParallelUploadOptionsCaptor.capture(), isNull(), eq(Context.NONE));

    BlobParallelUploadOptions blobParallelUploadOptions =
        blobParallelUploadOptionsCaptor.getValue();
    assertEquals(
        content,
        new String(toByteArray(blobParallelUploadOptions.getDataFlux()), StandardCharsets.UTF_8));
    assertEquals("PERMANENT", blobParallelUploadOptions.getTags().get("retention"));
    assertEquals("text/plain", blobParallelUploadOptions.getHeaders().getContentType());
    assertEquals(
        StandardCharsets.UTF_8.toString(),
        blobParallelUploadOptions.getHeaders().getContentEncoding());
  }

  @Test
  public void deleteUsesDeleteIfExists() {
    azureBlobStorage.delete("path/name");

    verify(blobClient).deleteIfExists();
  }

  @Test
  public void existsDelegatesToBlobClient() {
    when(blobClient.exists()).thenReturn(true);

    assertTrue(azureBlobStorage.exists("path/name"));
  }

  byte[] toByteArray(reactor.core.publisher.Flux<ByteBuffer> dataFlux) {
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    dataFlux
        .toIterable()
        .forEach(
            byteBuffer -> {
              ByteBuffer buffer = byteBuffer.duplicate();
              byte[] bytes = new byte[buffer.remaining()];
              buffer.get(bytes);
              outputStream.write(bytes, 0, bytes.length);
            });
    return outputStream.toByteArray();
  }

  BlobStorageException blobStorageException(int statusCode) {
    return new BlobStorageException("error", new TestHttpResponse(statusCode), null);
  }

  static class TestHttpResponse extends HttpResponse {

    int statusCode;

    TestHttpResponse(int statusCode) {
      super(new HttpRequest(null, getUrlUnchecked("http://localhost")));
      this.statusCode = statusCode;
    }

    @Override
    public int getStatusCode() {
      return statusCode;
    }

    @Override
    public String getHeaderValue(String name) {
      return null;
    }

    @Override
    public HttpHeaders getHeaders() {
      return new HttpHeaders();
    }

    @Override
    public reactor.core.publisher.Flux<ByteBuffer> getBody() {
      return reactor.core.publisher.Flux.empty();
    }

    @Override
    public reactor.core.publisher.Mono<byte[]> getBodyAsByteArray() {
      return reactor.core.publisher.Mono.empty();
    }

    @Override
    public reactor.core.publisher.Mono<String> getBodyAsString() {
      return reactor.core.publisher.Mono.empty();
    }

    @Override
    public reactor.core.publisher.Mono<String> getBodyAsString(java.nio.charset.Charset charset) {
      return reactor.core.publisher.Mono.empty();
    }
  }

  static URL getUrlUnchecked(String url) {
    try {
      return new URL(url);
    } catch (MalformedURLException e) {
      throw new RuntimeException(e);
    }
  }
}
