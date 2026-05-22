package com.box.l10n.mojito.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;
import org.springframework.mock.env.MockEnvironment;

public class DBUtilsTest {

  @Test
  public void detectsSingleQuartzMysqlDatasourceDriver() {
    DBUtils dbUtils = new DBUtils(new MockEnvironment());
    dbUtils.quartzDatasourceDriver = "com.mysql.jdbc.Driver";

    assertThat(dbUtils.isQuartzMysql()).isTrue();
    assertThat(dbUtils.isQuartzPostgres()).isFalse();
  }

  @Test
  public void detectsMultiQuartzPostgresDatasourceDriver() {
    MockEnvironment environment =
        new MockEnvironment()
            .withProperty(
                "l10n.org.multi-quartz.schedulers.default.quartz.dataSource.myDS.driver",
                "org.postgresql.Driver");
    DBUtils dbUtils = new DBUtils(environment);
    dbUtils.quartzDatasourceDriver = "RAMJobStore";

    assertThat(dbUtils.isQuartzPostgres()).isTrue();
    assertThat(dbUtils.isQuartzMysql()).isFalse();
  }

  @Test
  public void ignoresNonDatasourceDriverMultiQuartzProperties() {
    assertThat(
            DBUtils.isMultiQuartzDatasourceDriverProperty(
                "l10n.org.multi-quartz.schedulers.default.quartz.jobStore.driverDelegateClass"))
        .isFalse();
    assertThat(
            DBUtils.isMultiQuartzDatasourceDriverProperty(
                "l10n.org.multi-quartz.schedulers.default.quartz.dataSource.myDS.driver"))
        .isTrue();
  }
}
