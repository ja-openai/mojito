package com.box.l10n.mojito.rest.resttemplate;

import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.config.TlsConfig;
import org.apache.hc.client5.http.cookie.BasicCookieStore;
import org.apache.hc.client5.http.cookie.CookieStore;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.util.Timeout;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * A Rest Template with {@link HttpComponentsClientHttpRequestFactory} that exposes the cookie
 * store.
 *
 * @author wyau
 */
@Component
public class CookieStoreRestTemplate extends RestTemplate {

  CookieStore cookieStore;

  public CookieStoreRestTemplate() {
    super();
    cookieStore = new BasicCookieStore();
    setCookieStoreAndUpdateRequestFactory(cookieStore);
  }

  public void setCookieStoreAndUpdateRequestFactory(CookieStore cookieStore) {
    this.cookieStore = cookieStore;
    HttpClient hc =
        HttpClientBuilder.create()
            .setDefaultCookieStore(cookieStore)
            // GET retries are owned by AuthenticatedRestTemplate, including body-read failures.
            // Apache's default status retries can also replay writes.
            .disableAutomaticRetries()
            .setConnectionManager(
                PoolingHttpClientConnectionManagerBuilder.create()
                    .setDefaultConnectionConfig(
                        ConnectionConfig.custom().setConnectTimeout(Timeout.ofSeconds(10)).build())
                    .setDefaultTlsConfig(
                        TlsConfig.custom().setHandshakeTimeout(Timeout.ofSeconds(10)).build())
                    .build())
            // we have to turn off auto redirect in the rest template because
            // when session expires, it will return a 302 and resttemplate
            // will automatically redirect to /login even before returning
            // the ClientHttpResponse in the interceptor
            .disableRedirectHandling()
            .build();

    HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory(hc);
    factory.setHttpContextFactory(
        (method, uri) -> {
          if (method != HttpMethod.GET) {
            return null;
          }
          HttpClientContext context = HttpClientContext.create();
          context.setRequestConfig(
              RequestConfig.custom()
                  .setConnectionRequestTimeout(Timeout.ofSeconds(10))
                  .setResponseTimeout(Timeout.ofSeconds(30))
                  .build());
          return context;
        });
    setRequestFactory(factory);
  }

  public CookieStore getCookieStore() {
    return cookieStore;
  }
}
