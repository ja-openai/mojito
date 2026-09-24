package com.box.l10n.mojito.rest.resttemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonParseException;
import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLHandshakeException;
import org.junit.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

public class GetRequestRetryTest {
  private static final String URI_WITH_SECRET =
      "http://localhost/api/tasks/123/output?token=secret";

  @Test
  public void recoversAcrossTransientStatuses() {
    FakeRetry retry = new FakeRetry();
    int[] statuses = {502, 503, 504, 429};
    AtomicInteger attempts = new AtomicInteger();

    assertThat(
            retry.execute(
                URI_WITH_SECRET,
                () -> {
                  int attempt = attempts.getAndIncrement();
                  if (attempt < statuses.length) {
                    throw response(statuses[attempt], null);
                  }
                  return "output";
                }))
        .isEqualTo("output");
    assertThat(retry.delays).containsExactly(1_000L, 2_000L, 4_000L, 8_000L);
  }

  @Test
  public void allowsRecoveryAfterTwoMinuteInterruption() {
    FakeRetry retry = new FakeRetry();
    assertThat(
            retry.execute(
                URI_WITH_SECRET,
                () -> {
                  if (retry.elapsed < 140_000) {
                    throw response(503, null);
                  }
                  return "output";
                }))
        .isEqualTo("output");
    assertThat(retry.elapsed).isBetween(140_000L, GetRequestRetry.BUDGET_MILLIS);
  }

  @Test
  public void stopsAtElapsedBudgetAndPreservesLastResponse() {
    FakeRetry retry = new FakeRetry();
    List<RestClientResponseException> failures = new ArrayList<>();
    assertThatThrownBy(
            () ->
                retry.execute(
                    URI_WITH_SECRET,
                    () -> {
                      retry.elapsed += 30_000;
                      RestClientResponseException failure = response(503, null);
                      failures.add(failure);
                      throw failure;
                    }))
        .isSameAs(failures.getLast())
        .hasMessage("server error");
    assertThat(failures).hasSize(6);
    assertThat(retry.delays).hasSize(5);
    assertThat(failures.getLast().getResponseBodyAsString()).isEqualTo("original error body");
  }

  @Test
  public void capsAttemptsEvenIfClockDoesNotAdvance() {
    FakeRetry retry =
        new FakeRetry() {
          @Override
          void sleep(long millis) {
            delays.add(millis);
          }
        };
    AtomicInteger attempts = new AtomicInteger();
    RestClientResponseException failure = response(503, null);
    assertThatThrownBy(
            () ->
                retry.execute(
                    URI_WITH_SECRET,
                    () -> {
                      attempts.incrementAndGet();
                      throw failure;
                    }))
        .isSameAs(failure);
    assertThat(attempts).hasValue(GetRequestRetry.MAX_ATTEMPTS);
    assertThat(retry.delays).hasSize(GetRequestRetry.MAX_ATTEMPTS - 1);
  }

  @Test
  public void honorsRetryAfterSecondsAndDateAndIgnoresInvalidHeader() {
    for (String value :
        List.of(
            "7",
            DateTimeFormatter.RFC_1123_DATE_TIME.format(
                Instant.ofEpochMilli(FakeRetry.EPOCH + 7_000).atZone(ZoneOffset.UTC)),
            "invalid")) {
      FakeRetry retry = new FakeRetry();
      AtomicInteger attempts = new AtomicInteger();
      retry.execute(
          URI_WITH_SECRET,
          () -> {
            if (attempts.getAndIncrement() == 0) {
              throw response(429, value);
            }
            return "ok";
          });
      assertThat(retry.delays).containsExactly(value.equals("invalid") ? 1_000L : 7_000L);
    }
  }

  @Test
  public void neverShortensRetryAfterToFitBudget() {
    for (String value : List.of("181", "999999999999999999999999999999")) {
      FakeRetry retry = new FakeRetry();
      RestClientResponseException failure = response(429, value);
      assertThatThrownBy(
              () ->
                  retry.execute(
                      URI_WITH_SECRET,
                      () -> {
                        throw failure;
                      }))
          .isSameAs(failure);
      assertThat(retry.delays).isEmpty();
    }
  }

  @Test
  public void permanentHttpAndParsingErrorsArePreservedWithoutRetry() {
    List<RestClientException> failures = new ArrayList<>();
    for (int status : List.of(400, 401, 403, 404, 408, 422, 500, 501)) {
      failures.add(response(status, null));
    }
    failures.add(new RestClientException("invalid translation"));
    failures.add(new ResourceAccessException("TLS", new SSLHandshakeException("bad certificate")));
    failures.add(
        new ResourceAccessException("unknown IO", new IOException("not a connection failure")));
    failures.add(
        new RestClientException(
            "invalid JSON",
            new JsonParseException(null, "bad JSON", new SocketException("nested"))));
    for (RestClientException failure : failures) {
      FakeRetry retry = new FakeRetry();
      assertThatThrownBy(
              () ->
                  retry.execute(
                      URI_WITH_SECRET,
                      () -> {
                        throw failure;
                      }))
          .isSameAs(failure);
      assertThat(retry.delays).isEmpty();
    }
  }

  @Test
  public void recoversFromConnectionAndBodyReadFailures() {
    for (IOException cause :
        List.of(
            new ConnectException("refused"),
            new SocketException("reset"),
            new SocketTimeoutException("timeout"),
            new EOFException("truncated"))) {
      FakeRetry retry = new FakeRetry();
      AtomicInteger attempts = new AtomicInteger();
      assertThat(
              retry.execute(
                  URI_WITH_SECRET,
                  () -> {
                    if (attempts.getAndIncrement() == 0) {
                      throw new RestClientException("read failed", cause);
                    }
                    return "complete body";
                  }))
          .isEqualTo("complete body");
      assertThat(attempts).hasValue(2);
    }
  }

  @Test
  public void interruptionPreservesFailureAndInterruptFlag() {
    FakeRetry retry =
        new FakeRetry() {
          @Override
          void sleep(long millis) throws InterruptedException {
            throw new InterruptedException();
          }
        };
    RestClientResponseException failure = response(503, null);
    try {
      assertThatThrownBy(
              () ->
                  retry.execute(
                      URI_WITH_SECRET,
                      () -> {
                        throw failure;
                      }))
          .isSameAs(failure);
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  public void schedulerDelayCannotStartAttemptPastDeadline() {
    FakeRetry retry =
        new FakeRetry() {
          @Override
          void sleep(long millis) {
            elapsed = GetRequestRetry.BUDGET_MILLIS;
          }
        };
    AtomicInteger attempts = new AtomicInteger();
    assertThatThrownBy(
            () ->
                retry.execute(
                    URI_WITH_SECRET,
                    () -> {
                      attempts.incrementAndGet();
                      throw response(503, null);
                    }))
        .isInstanceOf(RestClientResponseException.class);
    assertThat(attempts).hasValue(1);
  }

  @Test
  public void jitterIsPositiveAndWithinCappedBackoff() {
    GetRequestRetry retry = new GetRequestRetry();
    for (int i = 0; i < 100; i++) {
      assertThat(retry.jitter(20_000)).isBetween(10_000L, 20_000L);
    }
  }

  private static RestClientResponseException response(int status, String retryAfter) {
    HttpHeaders headers = new HttpHeaders();
    if (retryAfter != null) {
      headers.set(HttpHeaders.RETRY_AFTER, retryAfter);
    }
    return new RestClientResponseException(
        "server error",
        status,
        "status",
        headers,
        "original error body".getBytes(StandardCharsets.UTF_8),
        StandardCharsets.UTF_8);
  }

  static class FakeRetry extends GetRequestRetry {
    static final long EPOCH = 1_700_000_000_000L;
    long elapsed;
    List<Long> delays = new ArrayList<>();

    @Override
    long nanoTime() {
      return elapsed * 1_000_000;
    }

    @Override
    long currentTimeMillis() {
      return EPOCH + elapsed;
    }

    @Override
    long jitter(long backoff) {
      return backoff / 2;
    }

    @Override
    void sleep(long millis) throws InterruptedException {
      delays.add(millis);
      elapsed += millis;
    }
  }
}
