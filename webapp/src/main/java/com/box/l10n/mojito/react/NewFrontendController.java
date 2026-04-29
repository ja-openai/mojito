package com.box.l10n.mojito.react;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Serves the new frontend from the web root while redirecting legacy /n links to their root-path
 * equivalents.
 */
@Controller
public class NewFrontendController {

  @RequestMapping({
    "/",
    "/repositories",
    "/review-projects",
    "/review-projects/{path:[^.]*}",
    "/review-projects/{path:[^.]*}/**",
    "/glossaries",
    "/glossaries/{path:[^.]*}",
    "/glossaries/{path:[^.]*}/**",
    "/translation-incidents",
    "/workbench",
    "/monitoring",
    "/statistics",
    "/screenshots",
    "/ai-translate",
    "/text-units/{path:[^.]*}",
    "/text-units/{path:[^.]*}/**",
    "/settings",
    "/settings/{path:^(?!user-management$|box$)[^.]*$}",
    "/settings/{path:^(?!user-management$|box$)[^.]*$}/**",
    "/tools/{path:[^.]*$}",
    "/tools/{path:[^.]*$}/**"
  })
  public String forwardNewApp() {
    return "forward:/index.html";
  }

  @RequestMapping({"/n", "/n/", "/n/**"})
  public String redirectLegacyPrefix(HttpServletRequest request) {
    return "redirect:"
        + getLegacyRedirectTarget(
            request.getRequestURI(), request.getContextPath(), request.getQueryString());
  }

  String getLegacyRedirectTarget(String requestUri, String contextPath, String queryString) {
    String normalizedRequestUri = requestUri == null ? "" : requestUri.trim();
    String normalizedContextPath = contextPath == null ? "" : contextPath.trim();
    if (StringUtils.hasText(normalizedContextPath)
        && normalizedRequestUri.startsWith(normalizedContextPath)) {
      normalizedRequestUri = normalizedRequestUri.substring(normalizedContextPath.length());
    }
    String targetPath =
        normalizedRequestUri.startsWith("/n")
            ? normalizedRequestUri.substring(2)
            : normalizedRequestUri;

    if (!StringUtils.hasText(targetPath) || "/".equals(targetPath)) {
      targetPath = "/";
    }

    if (!targetPath.startsWith("/")) {
      targetPath = "/" + targetPath;
    }

    return StringUtils.hasText(queryString) ? targetPath + "?" + queryString : targetPath;
  }
}
