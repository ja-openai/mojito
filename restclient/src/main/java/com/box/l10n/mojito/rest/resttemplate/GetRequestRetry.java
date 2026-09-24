package com.box.l10n.mojito.rest.resttemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.io.EOFException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.net.ssl.SSLException;
import org.apache.hc.core5.http.ConnectionClosedException;
import org.apache.hc.core5.http.NoHttpResponseException;
import org.apache.hc.core5.http.TruncatedChunkException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

/** One retry budget for a GET, including reading its response body. Never wraps writes. */
class GetRequestRetry {
  private static final Logger logger = LoggerFactory.getLogger(GetRequestRetry.class);
  static final long BUDGET_MILLIS = 180_000;
  static final int MAX_ATTEMPTS = 20;

  <T> T execute(String uri, Supplier<T> request) {
    String path = UriComponentsBuilder.fromUriString(uri).build().getPath();
    long started = nanoTime();
    long backoff = 2_000;
    for (int attempt = 1; ; attempt++) {
      try {
        return request.get();
      } catch (RestClientException failure) {
        if (!isRetryable(failure) || Thread.currentThread().isInterrupted()) {
          throw failure;
        }
        long remaining = BUDGET_MILLIS - elapsedMillis(started);
        long delay = Math.max(jitter(backoff), retryAfterMillis(failure));
        if (attempt >= MAX_ATTEMPTS || delay >= remaining) {
          logger.warn("GET {} failed after {} attempts; retry budget exhausted", path, attempt);
          throw failure;
        }
        logger.warn(
            "GET {} failed ({}), attempt {}/{}; retrying in {} ms ({} ms remaining)",
            path,
            failure instanceof RestClientResponseException response
                ? response.getStatusCode().value()
                : "connection failure",
            attempt,
            MAX_ATTEMPTS,
            delay,
            remaining);
        try {
          sleep(delay);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw failure;
        }
        // A delayed scheduler wakeup must not start a request after the budget expires.
        if (elapsedMillis(started) >= BUDGET_MILLIS) {
          throw failure;
        }
        backoff = Math.min(backoff * 2, 20_000);
      }
    }
  }

  private boolean isRetryable(RestClientException failure) {
    if (failure instanceof RestClientResponseException response) {
      return switch (response.getStatusCode().value()) {
        case 429, 502, 503, 504 -> true;
        default -> false;
      };
    }
    // Body-read failures are wrapped in RestClientException by Spring's message converters.
    // Parsing/validation and TLS failures must not become transport retries.
    boolean transportFailure = false;
    for (Throwable cause = failure.getCause(); cause != null; cause = cause.getCause()) {
      if (cause instanceof JsonProcessingException || cause instanceof SSLException) {
        return false;
      }
      transportFailure |=
          cause instanceof SocketException
              || cause instanceof SocketTimeoutException
              || cause instanceof UnknownHostException
              || cause instanceof EOFException
              || cause instanceof NoHttpResponseException
              || cause instanceof ConnectionClosedException
              || cause instanceof TruncatedChunkException;
    }
    return transportFailure;
  }

  private long retryAfterMillis(RestClientException failure) {
    if (!(failure instanceof RestClientResponseException response)
        || response.getResponseHeaders() == null) {
      return 0;
    }
    String value = response.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER);
    if (value == null) {
      return 0;
    }
    try {
      long seconds = Long.parseLong(value.trim());
      // An unreasonably large Retry-After exhausts this request's budget; never shorten it.
      return seconds <= 0 ? 0 : Math.min(seconds, BUDGET_MILLIS) * 1_000;
    } catch (NumberFormatException ignored) {
      if (value.trim().matches("[0-9]+")) {
        return BUDGET_MILLIS;
      }
      try {
        return Math.max(
            0,
            ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant()
                    .toEpochMilli()
                - currentTimeMillis());
      } catch (DateTimeParseException ignoredDate) {
        return 0;
      }
    }
  }

  private long elapsedMillis(long started) {
    return TimeUnit.NANOSECONDS.toMillis(nanoTime() - started);
  }

  long nanoTime() {
    return System.nanoTime();
  }

  long currentTimeMillis() {
    return System.currentTimeMillis();
  }

  long jitter(long backoff) {
    return ThreadLocalRandom.current().nextLong(backoff / 2, backoff + 1);
  }

  void sleep(long millis) throws InterruptedException {
    Thread.sleep(millis);
  }
}
