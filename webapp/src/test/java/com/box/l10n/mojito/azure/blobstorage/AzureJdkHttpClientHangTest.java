package com.box.l10n.mojito.azure.blobstorage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.azure.core.http.HttpClient;
import com.azure.core.http.HttpHeaders;
import com.azure.core.http.HttpMethod;
import com.azure.core.http.HttpPipeline;
import com.azure.core.http.HttpPipelineBuilder;
import com.azure.core.http.HttpRequest;
import com.azure.core.http.HttpResponse;
import com.azure.core.http.jdk.httpclient.JdkHttpClientBuilder;
import com.azure.core.http.jdk.httpclient.JdkHttpClientProvider;
import com.azure.core.util.BinaryData;
import com.azure.core.util.Configuration;
import com.azure.core.util.Context;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobClientBuilder;
import com.azure.storage.blob.options.BlobParallelUploadOptions;
import com.azure.storage.common.policy.RequestRetryOptions;
import com.azure.storage.common.policy.RetryPolicyType;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class AzureJdkHttpClientHangTest {

  private static final Duration TRANSPORT_TIMEOUT = Duration.ofMillis(40);
  private static final Duration OPERATION_TIMEOUT = Duration.ofMillis(120);

  @Test
  public void defaultJdkProviderCreatesIndependentHttpClients() {
    JdkHttpClientProvider provider = new JdkHttpClientProvider();

    assertNotSame(provider.createInstance(), provider.createInstance());
  }

  @Test(timeout = 5000)
  public void transportTimeoutDoesNotProtectRequestsWaitingForExecutorDispatch() throws Exception {
    HoldingExecutor transportExecutor = new HoldingExecutor();
    BlobClient blobClient = blobClient(transportExecutor, null);
    ExecutorService callerExecutor = newDaemonExecutor("blob-caller");

    try {
      Future<?> blockedRead = callerExecutor.submit(blobClient::downloadContent);
      assertTrue(transportExecutor.submitted.await(1, TimeUnit.SECONDS));

      assertThrows(TimeoutException.class, () -> blockedRead.get(250, TimeUnit.MILLISECONDS));
      assertEquals(1, transportExecutor.submissions.get());

      blockedRead.cancel(true);
    } finally {
      callerExecutor.shutdownNow();
    }
  }

  @Test(timeout = 5000)
  public void operationDeadlineProtectsReadsBeforeTransportDispatch() {
    HoldingExecutor transportExecutor = new HoldingExecutor();
    BlobClient blobClient = blobClient(transportExecutor, null);

    RuntimeException failure =
        assertThrows(
            RuntimeException.class,
            () ->
                blobClient.downloadContentWithResponse(
                    null, null, OPERATION_TIMEOUT, Context.NONE));

    assertTrue(rootCause(failure) instanceof TimeoutException);
    assertTrue(transportExecutor.submissions.get() > 0);
  }

  @Test(timeout = 5000)
  public void operationDeadlineProtectsWritesBeforeTransportDispatch() {
    HoldingExecutor transportExecutor = new HoldingExecutor();
    BlobClient blobClient = blobClient(transportExecutor, null);
    BlobParallelUploadOptions options =
        new BlobParallelUploadOptions(BinaryData.fromString("synthetic"));

    RuntimeException failure =
        assertThrows(
            RuntimeException.class,
            () -> blobClient.uploadWithResponse(options, OPERATION_TIMEOUT, Context.NONE));

    assertTrue(rootCause(failure) instanceof TimeoutException);
    assertTrue(transportExecutor.submissions.get() > 0);
  }

  @Test(timeout = 5000)
  public void boundedAttemptDeadlineDoesNotRequireRetrying() {
    HoldingExecutor transportExecutor = new HoldingExecutor();
    RequestRetryOptions retryOptions =
        new RequestRetryOptions(
            RetryPolicyType.FIXED,
            1,
            Duration.ofSeconds(1),
            Duration.ofMillis(1),
            Duration.ofMillis(1),
            null);
    BlobClient blobClient = blobClient(transportExecutor, retryOptions);

    RuntimeException failure = assertThrows(RuntimeException.class, blobClient::downloadContent);

    assertTrue(rootCause(failure) instanceof TimeoutException);
    assertEquals(1, retryOptions.getMaxTries());
    assertEquals(1, transportExecutor.submissions.get());
  }

  @Test(timeout = 5000)
  public void nestedSynchronousTransportCanDeadlockWithOnlyOneRequest() throws Exception {
    ExecutorService sharedExecutor = newDaemonExecutor("shared-identity-and-transport");
    AtomicInteger transportExecutions = new AtomicInteger();
    CountDownLatch transportSubmitted = new CountDownLatch(1);
    HttpPipeline pipeline =
        new HttpPipelineBuilder()
            .httpClient(syntheticTransport(sharedExecutor, transportSubmitted, transportExecutions))
            .build();

    try {
      Future<Integer> blockedTokenRequest =
          sharedExecutor.submit(
              () -> pipeline.sendSync(syntheticRequest(), Context.NONE).getStatusCode());
      assertTrue(transportSubmitted.await(1, TimeUnit.SECONDS));

      assertThrows(
          TimeoutException.class, () -> blockedTokenRequest.get(250, TimeUnit.MILLISECONDS));
      assertEquals(0, transportExecutions.get());

      blockedTokenRequest.cancel(true);
    } finally {
      sharedExecutor.shutdownNow();
    }
  }

  @Test(timeout = 5000)
  public void separateTransportExecutorPreventsNestedSynchronousDeadlock() throws Exception {
    ExecutorService identityExecutor = newDaemonExecutor("identity");
    ExecutorService transportExecutor = newDaemonExecutor("transport");
    AtomicInteger transportExecutions = new AtomicInteger();
    CountDownLatch transportSubmitted = new CountDownLatch(1);
    HttpPipeline pipeline =
        new HttpPipelineBuilder()
            .httpClient(
                syntheticTransport(transportExecutor, transportSubmitted, transportExecutions))
            .build();

    try {
      Future<Integer> completedTokenRequest =
          identityExecutor.submit(
              () -> pipeline.sendSync(syntheticRequest(), Context.NONE).getStatusCode());

      assertEquals(Integer.valueOf(200), completedTokenRequest.get(1, TimeUnit.SECONDS));
      assertEquals(1, transportExecutions.get());
    } finally {
      identityExecutor.shutdownNow();
      transportExecutor.shutdownNow();
    }
  }

  private BlobClient blobClient(Executor transportExecutor, RequestRetryOptions retryOptions) {
    HttpClient transport =
        new JdkHttpClientBuilder()
            .configuration(Configuration.NONE)
            .executor(transportExecutor)
            .connectionTimeout(TRANSPORT_TIMEOUT)
            .responseTimeout(TRANSPORT_TIMEOUT)
            .readTimeout(TRANSPORT_TIMEOUT)
            .writeTimeout(TRANSPORT_TIMEOUT)
            .build();
    BlobClientBuilder builder =
        new BlobClientBuilder()
            .endpoint("http://synthetic.invalid/container/blob")
            .setAnonymousAccess()
            .configuration(Configuration.NONE)
            .httpClient(transport);

    if (retryOptions != null) {
      builder.retryOptions(retryOptions);
    }

    return builder.buildClient();
  }

  private HttpClient syntheticTransport(
      Executor transportExecutor,
      CountDownLatch transportSubmitted,
      AtomicInteger transportExecutions) {
    return request -> {
      transportSubmitted.countDown();
      return Mono.fromFuture(
          CompletableFuture.supplyAsync(
              () -> {
                transportExecutions.incrementAndGet();
                return new SyntheticHttpResponse(request);
              },
              transportExecutor));
    };
  }

  private HttpRequest syntheticRequest() throws Exception {
    return new HttpRequest(HttpMethod.GET, new URL("http://synthetic.invalid/token"));
  }

  private ExecutorService newDaemonExecutor(String name) {
    return Executors.newSingleThreadExecutor(
        runnable -> {
          Thread thread = new Thread(runnable, name);
          thread.setDaemon(true);
          return thread;
        });
  }

  private Throwable rootCause(Throwable throwable) {
    while (throwable.getCause() != null && throwable.getCause() != throwable) {
      throwable = throwable.getCause();
    }

    return throwable;
  }

  private static final class HoldingExecutor implements Executor {

    private final CountDownLatch submitted = new CountDownLatch(1);
    private final AtomicInteger submissions = new AtomicInteger();

    @Override
    public void execute(Runnable ignored) {
      submissions.incrementAndGet();
      submitted.countDown();
    }
  }

  private static final class SyntheticHttpResponse extends HttpResponse {

    private SyntheticHttpResponse(HttpRequest request) {
      super(request);
    }

    @Override
    public int getStatusCode() {
      return 200;
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
