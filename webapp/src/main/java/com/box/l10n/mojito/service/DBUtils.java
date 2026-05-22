package com.box.l10n.mojito.service;

import java.util.Objects;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class DBUtils {

  @Value("${spring.datasource.url}")
  String url;

  @Value("${l10n.org.quartz.dataSource.myDS.driver:RAMJobStore}")
  String quartzDatasourceDriver;

  final Environment environment;

  public DBUtils(Environment environment) {
    this.environment = environment;
  }

  public boolean isMysql() {
    return url.contains("mysql");
  }

  public boolean isPostgres() {
    return url.contains("postgresql");
  }

  public boolean isHsql() {
    return url.contains("hsqldb");
  }

  public boolean isQuartzMysql() {
    return quartzDatasourceDrivers().anyMatch(driver -> driver.contains("mysql"));
  }

  public boolean isQuartzPostgres() {
    return quartzDatasourceDrivers().anyMatch(driver -> driver.contains("postgresql"));
  }

  Stream<String> quartzDatasourceDrivers() {
    return Stream.concat(Stream.of(quartzDatasourceDriver), multiQuartzDatasourceDrivers())
        .filter(Objects::nonNull);
  }

  Stream<String> multiQuartzDatasourceDrivers() {
    if (!(environment instanceof ConfigurableEnvironment configurableEnvironment)) {
      return Stream.empty();
    }

    return StreamSupport.stream(configurableEnvironment.getPropertySources().spliterator(), false)
        .filter(EnumerablePropertySource.class::isInstance)
        .map(EnumerablePropertySource.class::cast)
        .flatMap(propertySource -> Stream.of(propertySource.getPropertyNames()))
        .filter(DBUtils::isMultiQuartzDatasourceDriverProperty)
        .map(environment::getProperty)
        .filter(Objects::nonNull);
  }

  static boolean isMultiQuartzDatasourceDriverProperty(String propertyName) {
    return propertyName.startsWith("l10n.org.multi-quartz.schedulers.")
        && propertyName.contains(".quartz.dataSource.")
        && propertyName.endsWith(".driver");
  }
}
