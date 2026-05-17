package com.box.l10n.mojito.react;

import com.box.l10n.mojito.rest.security.CsrfTokenController;
import com.box.l10n.mojito.rest.security.UserProfile;
import com.box.l10n.mojito.rest.security.UserProfileMapper;
import com.box.l10n.mojito.security.AuditorAwareImpl;
import jakarta.servlet.http.HttpServletRequest;
import java.util.IllformedLocaleException;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/** Exposes runtime frontend configuration that used to be embedded in the legacy index template. */
@RestController
public class FrontendConfigController {

  public static final String CONFIG_PATH = "/api/frontend/config";

  static Logger logger = LoggerFactory.getLogger(FrontendConfigController.class);

  @Autowired CsrfTokenController csrfTokenController;

  @Autowired ReactStaticAppConfig reactStaticAppConfig;

  @Autowired AuditorAwareImpl auditorAwareImpl;

  @Autowired UserProfileMapper userProfileMapper;

  @Value("${server.contextPath:}")
  String contextPath = "";

  @RequestMapping(method = RequestMethod.GET, value = CONFIG_PATH)
  public ReactAppConfig getConfig(
      HttpServletRequest httpServletRequest,
      @CookieValue(value = "locale", required = false, defaultValue = "en")
          String localeCookieValue) {

    ReactAppConfig reactAppConfig = new ReactAppConfig(reactStaticAppConfig, getUserProfile());
    reactAppConfig.setLocale(getValidLocaleFromCookie(localeCookieValue));
    reactAppConfig.setIct(httpServletRequest.getHeaders("X-Mojito-Ict").hasMoreElements());
    reactAppConfig.setCsrfToken(csrfTokenController.getCsrfToken(httpServletRequest));
    reactAppConfig.setContextPath(contextPath);

    return reactAppConfig;
  }

  UserProfile getUserProfile() {
    return auditorAwareImpl
        .getCurrentAuditor()
        .map(userProfileMapper::toUserProfile)
        .orElse(new UserProfile());
  }

  /**
   * Get a valid locale from the cookie value.
   *
   * @param localeCookieValue value from the locale cookie
   * @return a valid locale.
   */
  String getValidLocaleFromCookie(String localeCookieValue) {
    String validLocale;

    try {
      Locale localeFromCookie = new Locale.Builder().setLanguageTag(localeCookieValue).build();
      validLocale = localeFromCookie.toLanguageTag();
    } catch (NullPointerException | IllformedLocaleException e) {
      logger.debug("Invalid localeCookieValue, fallback to en");
      validLocale = "en";
    }

    return validLocale;
  }
}
