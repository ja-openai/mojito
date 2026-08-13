package com.box.l10n.mojito.azure.blobstorage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.azure.core.http.HttpClient;
import com.azure.core.http.HttpHeaders;
import com.azure.core.http.HttpMethod;
import com.azure.core.http.HttpPipeline;
import com.azure.core.http.HttpPipelineBuilder;
import com.azure.core.http.HttpRequest;
import com.azure.core.http.HttpResponse;
import com.azure.core.http.netty.NettyAsyncHttpClientBuilder;
import com.azure.core.util.BinaryData;
import com.azure.core.util.Configuration;
import com.azure.core.util.Context;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobClientBuilder;
import com.azure.storage.blob.options.BlobParallelUploadOptions;
import com.azure.storage.common.policy.RequestRetryOptions;
import com.azure.storage.common.policy.RequestRetryPolicy;
import com.azure.storage.common.policy.RetryPolicyType;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.HttpResources;
import reactor.netty.resources.ConnectionProvider;

public class AzureNettyHttpClientTest {

  private static final String NETTY_HTTP_CLIENT = "com.azure.core.http.netty.NettyAsyncHttpClient";
  private static final Duration OPERATION_TIMEOUT = Duration.ofMillis(120);

  @Test
  public void defaultAzureHttpClientUsesNetty() {
    assertEquals(NETTY_HTTP_CLIENT, HttpClient.createDefault().getClass().getName());
  }

  @Test
  public void blobClientUsesNettyWithoutAnExplicitTransport() {
    BlobClient client =
        new BlobClientBuilder()
            .endpoint("http://synthetic.invalid/container/blob")
            .setAnonymousAccess()
            .configuration(Configuration.NONE)
            .buildClient();

    assertEquals(NETTY_HTTP_CLIENT, client.getHttpPipeline().getHttpClient().getClass().getName());
  }

  @Test
  public void nettyUsesSharedReactorHttpResources() {
    assertSame(HttpResources.get(), HttpResources.get());
  }

  @Test
  public void nettySupportsBoundedConnectionPoolsAndTimeouts() {
    ConnectionProvider connectionProvider =
        ConnectionProvider.builder("azure-test")
            .maxConnections(4)
            .pendingAcquireMaxCount(8)
            .pendingAcquireTimeout(Duration.ofSeconds(1))
            .build();

    try {
      HttpClient client =
          new NettyAsyncHttpClientBuilder()
              .configuration(Configuration.NONE)
              .connectionProvider(connectionProvider)
              .connectTimeout(Duration.ofSeconds(3))
              .responseTimeout(Duration.ofSeconds(8))
              .readTimeout(Duration.ofSeconds(8))
              .writeTimeout(Duration.ofSeconds(8))
              .build();

      assertEquals(NETTY_HTTP_CLIENT, client.getClass().getName());
      assertEquals(4, connectionProvider.maxConnections());
    } finally {
      connectionProvider.dispose();
    }
  }

  @Test(timeout = 5000)
  public void transientStorageFailureIsRetriedOnce() throws Exception {
    AtomicInteger attempts = new AtomicInteger();
    HttpClient syntheticTransport =
        request ->
            Mono.fromSupplier(
                () ->
                    new SyntheticHttpResponse(
                        request, attempts.incrementAndGet() == 1 ? 503 : 200));
    RequestRetryOptions retryOptions =
        new RequestRetryOptions(
            RetryPolicyType.FIXED,
            2,
            Duration.ofSeconds(1),
            Duration.ofMillis(1),
            Duration.ofMillis(1),
            null);
    HttpPipeline pipeline =
        new HttpPipelineBuilder()
            .httpClient(syntheticTransport)
            .policies(new RequestRetryPolicy(retryOptions))
            .build();

    HttpResponse response =
        pipeline.sendSync(
            new HttpRequest(HttpMethod.GET, new URL("http://synthetic.invalid/blob")),
            Context.NONE);

    assertEquals(200, response.getStatusCode());
    assertEquals(2, attempts.get());
  }

  @Test(timeout = 5000)
  public void operationDeadlineBoundsAStalledRead() {
    BlobClient client = blobClientWithNeverCompletingTransport();

    RuntimeException failure =
        assertThrows(
            RuntimeException.class,
            () -> client.downloadContentWithResponse(null, null, OPERATION_TIMEOUT, Context.NONE));

    assertTrue(rootCause(failure) instanceof TimeoutException);
  }

  @Test(timeout = 5000)
  public void operationDeadlineBoundsAStalledWrite() {
    BlobClient client = blobClientWithNeverCompletingTransport();
    BlobParallelUploadOptions options =
        new BlobParallelUploadOptions(BinaryData.fromString("synthetic"));

    RuntimeException failure =
        assertThrows(
            RuntimeException.class,
            () -> client.uploadWithResponse(options, OPERATION_TIMEOUT, Context.NONE));

    assertTrue(rootCause(failure) instanceof TimeoutException);
  }

  private BlobClient blobClientWithNeverCompletingTransport() {
    return new BlobClientBuilder()
        .endpoint("http://synthetic.invalid/container/blob")
        .setAnonymousAccess()
        .configuration(Configuration.NONE)
        .httpClient(request -> Mono.never())
        .buildClient();
  }

  private Throwable rootCause(Throwable throwable) {
    while (throwable.getCause() != null && throwable.getCause() != throwable) {
      throwable = throwable.getCause();
    }

    return throwable;
  }

  private static final class SyntheticHttpResponse extends HttpResponse {

    private final int statusCode;

    private SyntheticHttpResponse(HttpRequest request, int statusCode) {
      super(request);
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
    public Flux<ByteBuffer> getBody() {
      return Flux.empty();
    }

    @Override
    public Mono<byte[]> getBodyAsByteArray() {
      return Mono.just(new byte[0]);
    }

    @Override
    public Mono<String> getBodyAsString() {
      return Mono.just("");
    }

    @Override
    public Mono<String> getBodyAsString(Charset charset) {
      return Mono.just("");
    }
  }
}
