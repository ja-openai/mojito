package com.box.l10n.mojito.rest.cms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.box.l10n.mojito.rest.WSTestBase;
import com.box.l10n.mojito.rest.client.UserClient;
import com.box.l10n.mojito.rest.entity.Role;
import com.box.l10n.mojito.rest.resttemplate.CookieStoreRestTemplate;
import com.box.l10n.mojito.rest.resttemplate.CredentialProvider;
import com.box.l10n.mojito.rest.resttemplate.FormLoginAuthenticationCsrfTokenInterceptor;
import java.util.UUID;
import org.junit.After;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;

public class CmsContentSecurityWSTest extends WSTestBase {

  @Autowired
  FormLoginAuthenticationCsrfTokenInterceptor formLoginAuthenticationCsrfTokenInterceptor;

  @Autowired CredentialProvider credentialProvider;

  @Autowired UserClient userClient;

  private String nonAdminUsername;
  private String cmsDeliveryUsername;

  @After
  public void restoreAdminSession() throws Exception {
    formLoginAuthenticationCsrfTokenInterceptor.resetAuthentication();
    formLoginAuthenticationCsrfTokenInterceptor.setCredentialProvider(credentialProvider);
    if (nonAdminUsername != null) {
      userClient.deleteUserByUsername(nonAdminUsername);
    }
    if (cmsDeliveryUsername != null) {
      userClient.deleteUserByUsername(cmsDeliveryUsername);
    }
  }

  @Test
  public void nonAdminCannotReadContentCmsAdminRoutes() throws Exception {
    nonAdminUsername = "cms_" + UUID.randomUUID();
    String nonAdminPassword = "test";
    userClient.createUser(
        nonAdminUsername, nonAdminPassword, Role.ROLE_USER, "Mojito", "CMS", "CMS Mojito");
    formLoginAuthenticationCsrfTokenInterceptor.setCredentialProvider(
        credentials(nonAdminUsername, nonAdminPassword));
    formLoginAuthenticationCsrfTokenInterceptor.resetAuthentication();
    authenticatedRestTemplate.getForEntity("/api/users/session", String.class);

    CookieStoreRestTemplate nonAdminRestTemplate = new CookieStoreRestTemplate();
    nonAdminRestTemplate.setCookieStoreAndUpdateRequestFactory(
        authenticatedRestTemplate.getRestTemplate().getCookieStore());

    assertCmsRouteForbidden(nonAdminRestTemplate, "/api/content-cms");
    assertCmsRouteForbidden(nonAdminRestTemplate, "/api/content-cms/projects");
    assertCmsRouteForbidden(nonAdminRestTemplate, "/api/content-cms/projects/1/publish-snapshots");
    assertCmsRouteForbidden(
        nonAdminRestTemplate, "/api/content-cms/projects/growth-email/publish-snapshots/latest");
    assertCmsRouteForbidden(
        nonAdminRestTemplate,
        "/api/content-cms/projects/growth-email/publish-snapshots/1/artifact");
    assertCmsHeadRouteForbidden(
        nonAdminRestTemplate, "/api/content-cms/projects/growth-email/publish-snapshots/latest");
    assertCmsHeadRouteForbidden(
        nonAdminRestTemplate,
        "/api/content-cms/projects/growth-email/publish-snapshots/1/artifact");
  }

  @Test
  public void cmsDeliveryCanOnlyReachSnapshotDeliveryRoutes() throws Exception {
    String missingProjectKey = "cms-delivery-" + UUID.randomUUID();
    String latestSnapshotPath =
        "/api/content-cms/projects/" + missingProjectKey + "/publish-snapshots/latest";
    String artifactPath =
        "/api/content-cms/projects/" + missingProjectKey + "/publish-snapshots/1/artifact";
    cmsDeliveryUsername = "cms_delivery_" + UUID.randomUUID();
    String cmsDeliveryPassword = "test";
    userClient.createUser(
        cmsDeliveryUsername,
        cmsDeliveryPassword,
        Role.ROLE_CMS_DELIVERY,
        "Mojito",
        "CMS",
        "CMS Mojito");
    CookieStoreRestTemplate cmsDeliveryRestTemplate =
        authenticatedRestTemplate(cmsDeliveryUsername, cmsDeliveryPassword, latestSnapshotPath);

    assertCmsRouteForbidden(cmsDeliveryRestTemplate, "/api/content-cms");
    assertCmsRouteForbidden(cmsDeliveryRestTemplate, "/api/content-cms/projects");
    assertCmsRouteForbidden(
        cmsDeliveryRestTemplate, "/api/content-cms/projects/1/publish-snapshots");
    assertCmsRouteForbidden(cmsDeliveryRestTemplate, "/api/users/session");
    assertCmsRouteForbidden(cmsDeliveryRestTemplate, "/api/users/me");
    assertCmsRouteForbidden(cmsDeliveryRestTemplate, "/api/textunits/search");
    assertCmsRouteForbidden(cmsDeliveryRestTemplate, "/api/mcp");
    assertCmsPostRouteForbidden(cmsDeliveryRestTemplate, "/api/mcp");
    assertCmsRouteAllowedPastSecurity(cmsDeliveryRestTemplate, "/api/csrf-token");
    assertCmsRouteAllowedPastSecurity(cmsDeliveryRestTemplate, latestSnapshotPath);
    assertCmsRouteAllowedPastSecurity(cmsDeliveryRestTemplate, artifactPath);
    assertCmsHeadRouteAllowedPastSecurity(cmsDeliveryRestTemplate, latestSnapshotPath);
    assertCmsHeadRouteAllowedPastSecurity(cmsDeliveryRestTemplate, artifactPath);
    assertCmsPostRouteForbidden(cmsDeliveryRestTemplate, latestSnapshotPath);
    assertCmsPostRouteForbidden(cmsDeliveryRestTemplate, artifactPath);
  }

  @Test
  public void unauthenticatedCmsDeliveryRoutesReturnApiUnauthorized() {
    String latestSnapshotPath =
        "/api/content-cms/projects/cms-delivery-missing/publish-snapshots/latest";
    String artifactPath =
        "/api/content-cms/projects/cms-delivery-missing/publish-snapshots/1/artifact";
    CookieStoreRestTemplate unauthenticatedRestTemplate = new CookieStoreRestTemplate();

    assertCmsRouteUnauthorized(unauthenticatedRestTemplate, latestSnapshotPath);
    assertCmsHeadRouteUnauthorized(unauthenticatedRestTemplate, latestSnapshotPath);
    assertCmsRouteUnauthorized(unauthenticatedRestTemplate, artifactPath);
    assertCmsHeadRouteUnauthorized(unauthenticatedRestTemplate, artifactPath);
  }

  private void assertCmsRouteForbidden(
      CookieStoreRestTemplate nonAdminRestTemplate, String resourcePath) {
    assertThatThrownBy(
            () ->
                nonAdminRestTemplate.getForEntity(
                    authenticatedRestTemplate.getURIForResource(resourcePath), String.class))
        .isInstanceOfSatisfying(
            HttpClientErrorException.Forbidden.class,
            exception -> {
              assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
              assertCmsNoStoreIfCmsRoute(resourcePath, exception.getResponseHeaders());
            });
  }

  private void assertCmsRouteUnauthorized(
      CookieStoreRestTemplate unauthenticatedRestTemplate, String resourcePath) {
    assertThatThrownBy(
            () ->
                unauthenticatedRestTemplate.getForEntity(
                    authenticatedRestTemplate.getURIForResource(resourcePath), String.class))
        .isInstanceOfSatisfying(
            HttpClientErrorException.Unauthorized.class,
            exception -> {
              assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
              assertCmsNoStoreIfCmsRoute(resourcePath, exception.getResponseHeaders());
            });
  }

  private void assertCmsPostRouteForbidden(
      CookieStoreRestTemplate cmsDeliveryRestTemplate, String resourcePath) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.add(
        FormLoginAuthenticationCsrfTokenInterceptor.CSRF_HEADER_NAME,
        cmsDeliveryRestTemplate.getForObject(
            authenticatedRestTemplate.getURIForResource("/api/csrf-token"), String.class));
    assertThatThrownBy(
            () ->
                cmsDeliveryRestTemplate.postForEntity(
                    authenticatedRestTemplate.getURIForResource(resourcePath),
                    new HttpEntity<>("{}", headers),
                    String.class))
        .isInstanceOfSatisfying(
            HttpClientErrorException.Forbidden.class,
            exception -> {
              assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
              assertCmsNoStoreIfCmsRoute(resourcePath, exception.getResponseHeaders());
            });
  }

  private void assertCmsRouteAllowedPastSecurity(
      CookieStoreRestTemplate cmsDeliveryRestTemplate, String resourcePath) {
    try {
      cmsDeliveryRestTemplate.getForEntity(
          authenticatedRestTemplate.getURIForResource(resourcePath), String.class);
    } catch (HttpClientErrorException exception) {
      assertThat(exception.getStatusCode()).isNotEqualTo(HttpStatus.FORBIDDEN);
      assertCmsNoStoreIfCmsRoute(resourcePath, exception.getResponseHeaders());
    }
  }

  private void assertCmsHeadRouteForbidden(
      CookieStoreRestTemplate nonAdminRestTemplate, String resourcePath) {
    assertThatThrownBy(
            () ->
                nonAdminRestTemplate.headForHeaders(
                    authenticatedRestTemplate.getURIForResource(resourcePath)))
        .isInstanceOfSatisfying(
            HttpClientErrorException.Forbidden.class,
            exception -> {
              assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
              assertCmsNoStoreIfCmsRoute(resourcePath, exception.getResponseHeaders());
            });
  }

  private void assertCmsHeadRouteUnauthorized(
      CookieStoreRestTemplate unauthenticatedRestTemplate, String resourcePath) {
    assertThatThrownBy(
            () ->
                unauthenticatedRestTemplate.headForHeaders(
                    authenticatedRestTemplate.getURIForResource(resourcePath)))
        .isInstanceOfSatisfying(
            HttpClientErrorException.Unauthorized.class,
            exception -> {
              assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
              assertCmsNoStoreIfCmsRoute(resourcePath, exception.getResponseHeaders());
            });
  }

  private void assertCmsHeadRouteAllowedPastSecurity(
      CookieStoreRestTemplate cmsDeliveryRestTemplate, String resourcePath) {
    try {
      cmsDeliveryRestTemplate.headForHeaders(
          authenticatedRestTemplate.getURIForResource(resourcePath));
    } catch (HttpClientErrorException exception) {
      assertThat(exception.getStatusCode()).isNotEqualTo(HttpStatus.FORBIDDEN);
      assertCmsNoStoreIfCmsRoute(resourcePath, exception.getResponseHeaders());
    }
  }

  private CookieStoreRestTemplate authenticatedRestTemplate(String username, String password)
      throws Exception {
    return authenticatedRestTemplate(username, password, "/api/users/session");
  }

  private void assertCmsNoStoreIfCmsRoute(String resourcePath, HttpHeaders headers) {
    if (resourcePath.equals("/api/content-cms") || resourcePath.startsWith("/api/content-cms/")) {
      assertThat(headers).isNotNull();
      assertThat(headers.getCacheControl()).isEqualTo("no-store");
    }
  }

  private CookieStoreRestTemplate authenticatedRestTemplate(
      String username, String password, String authenticatedResourcePath) throws Exception {
    formLoginAuthenticationCsrfTokenInterceptor.setCredentialProvider(
        credentials(username, password));
    formLoginAuthenticationCsrfTokenInterceptor.resetAuthentication();
    try {
      authenticatedRestTemplate.getForEntity(authenticatedResourcePath, String.class);
    } catch (HttpClientErrorException exception) {
      assertThat(exception.getStatusCode()).isNotEqualTo(HttpStatus.FORBIDDEN);
    }
    CookieStoreRestTemplate userRestTemplate = new CookieStoreRestTemplate();
    userRestTemplate.setCookieStoreAndUpdateRequestFactory(
        authenticatedRestTemplate.getRestTemplate().getCookieStore());
    return userRestTemplate;
  }

  private CredentialProvider credentials(String username, String password) {
    return new CredentialProvider() {
      @Override
      public String getUsername() {
        return username;
      }

      @Override
      public String getPassword() {
        return password;
      }
    };
  }
}
