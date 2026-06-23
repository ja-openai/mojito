package com.box.l10n.mojito.rest.cms;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CmsContentCacheControlFilter extends OncePerRequestFilter {

  private static final String CMS_API_PATH = "/api/content-cms";

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String cmsApiPath = request.getContextPath() + CMS_API_PATH;
    String requestUri = request.getRequestURI();
    return !requestUri.equals(cmsApiPath) && !requestUri.startsWith(cmsApiPath + "/");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    filterChain.doFilter(request, new CmsCacheControlResponse(response));
  }

  private static class CmsCacheControlResponse extends HttpServletResponseWrapper {

    private boolean defaultCacheControl = true;

    CmsCacheControlResponse(HttpServletResponse response) {
      super(response);
      // Security can commit CMS failures before MVC returns, but artifact handlers still need to
      // replace this default with their immutable cache policy.
      response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue());
    }

    @Override
    public boolean containsHeader(String name) {
      return !isDefaultCacheControl(name) && super.containsHeader(name);
    }

    @Override
    public void setHeader(String name, String value) {
      clearDefaultCacheControl(name);
      super.setHeader(name, value);
    }

    @Override
    public void addHeader(String name, String value) {
      if (isCacheControlHeader(name)) {
        setHeader(name, value);
        return;
      }
      super.addHeader(name, value);
    }

    private boolean isDefaultCacheControl(String name) {
      return defaultCacheControl && isCacheControlHeader(name);
    }

    private void clearDefaultCacheControl(String name) {
      if (isCacheControlHeader(name)) {
        defaultCacheControl = false;
      }
    }

    private boolean isCacheControlHeader(String name) {
      return HttpHeaders.CACHE_CONTROL.equalsIgnoreCase(name);
    }
  }
}
