package com.box.l10n.mojito.security;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

@EnableWebSecurity
@Configuration
@Conditional(WebSecurityFallbackConfig.FallbackSecurityCondition.class)
public class WebSecurityFallbackConfig {

  /** logger */
  static Logger logger = LoggerFactory.getLogger(WebSecurityFallbackConfig.class);

  @Bean
  @Order(99)
  SecurityFilterChain securityFallbackBlock(HttpSecurity http, SecurityConfig securityConfig)
      throws Exception {
    WebSecurityJWTConfig.applyStatelessSharedConfig(http, securityConfig);
    HttpStatusEntryPoint httpStatusEntryPoint = new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED);
    http.exceptionHandling(
        e -> {
          e.authenticationEntryPoint(httpStatusEntryPoint);
        });
    return http.build();
  }

  static class FallbackSecurityCondition implements Condition {

    private static final String KEY = "l10n.security.authenticationType";
    private static final Set<String> FALLBACK_AUTH_TYPES = Set.of("HEADER", "JWT");
    private static final Set<String> INTERACTIVE_AUTH_TYPES =
        Set.of("AD", "DATABASE", "LDAP", "OAUTH2");

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
      Set<String> types =
          Arrays.stream(context.getEnvironment().getProperty(KEY, "").split(","))
              .map(String::trim)
              .filter(type -> !type.isEmpty())
              .map(String::toUpperCase)
              .collect(Collectors.toSet());

      return types.stream().anyMatch(FALLBACK_AUTH_TYPES::contains)
          && types.stream().noneMatch(INTERACTIVE_AUTH_TYPES::contains);
    }
  }
}
