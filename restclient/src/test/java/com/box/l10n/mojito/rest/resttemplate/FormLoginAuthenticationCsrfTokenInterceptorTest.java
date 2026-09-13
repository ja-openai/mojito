package com.box.l10n.mojito.rest.resttemplate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.apache.hc.client5.http.cookie.BasicCookieStore;
import org.junit.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.security.web.csrf.DefaultCsrfToken;

public class FormLoginAuthenticationCsrfTokenInterceptorTest {

  @Test
  public void postRetryReplacesTheExpiredSessionsCsrfToken() throws Exception {
    String headerName = FormLoginAuthenticationCsrfTokenInterceptor.CSRF_HEADER_NAME;
    FormLoginAuthenticationCsrfTokenInterceptor interceptor =
        new FormLoginAuthenticationCsrfTokenInterceptor() {
          @Override
          protected boolean doesSessionIdInCookieStoreExistAndMatchLatestSessionId() {
            return true;
          }

          @Override
          protected synchronized void startAuthenticationFlow() {
            latestCsrfToken = new DefaultCsrfToken(headerName, "_csrf", "fresh-token");
          }
        };
    interceptor.cookieStore = new BasicCookieStore();
    interceptor.latestCsrfToken = new DefaultCsrfToken(headerName, "_csrf", "expired-token");
    MockClientHttpRequest request =
        new MockClientHttpRequest(
            HttpMethod.POST, URI.create("http://localhost/api/textunitsBatch"));
    byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
    List<List<String>> sentTokens = new ArrayList<>();

    ClientHttpResponse response =
        interceptor.intercept(
            request,
            body,
            (retriedRequest, retriedBody) -> {
              assertSame(body, retriedBody);
              sentTokens.add(List.copyOf(retriedRequest.getHeaders().get(headerName)));
              // The server reads the first header, so retaining the expired token rejects a
              // correctly reauthenticated POST even when the fresh token is also present.
              HttpStatus status =
                  "fresh-token".equals(retriedRequest.getHeaders().getFirst(headerName))
                      ? HttpStatus.OK
                      : HttpStatus.FORBIDDEN;
              return new MockClientHttpResponse(new byte[0], status);
            });

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(List.of(List.of("expired-token"), List.of("fresh-token")), sentTokens);
  }
}
