package com.box.l10n.mojito.service.oaireview;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.junit.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

public class AiReviewRequestUsageMigrationTest {

  @Test
  public void migrationCreatesUsageTableAndAllowsUserDeletionWithoutDeletingUsage()
      throws Exception {
    String url =
        "jdbc:hsqldb:mem:review-usage-" + UUID.randomUUID() + ";sql.syntax_mys=true;shutdown=true";
    try (Connection connection = DriverManager.getConnection(url, "sa", "");
        Statement statement = connection.createStatement()) {
      statement.execute("create table user (id bigint primary key)");
      ScriptUtils.executeSqlScript(
          connection, new ClassPathResource("db/migration/V109__AI_Review_Request_Usage.sql"));
      statement.execute("insert into user (id) values (1)");
      statement.execute(
          """
          insert into ai_review_request_usage
              (user_id, surface, request_type, profile_id, model_name, status, started_at)
          values (1, 'review_project', 'automatic', 'version_b', 'model-name', 'started', current_timestamp)
          """);
      statement.execute("delete from user where id = 1");

      try (ResultSet result =
          statement.executeQuery("select user_id, request_type from ai_review_request_usage")) {
        assertTrue(result.next());
        assertNull(result.getObject("user_id"));
        assertEquals("automatic", result.getString("request_type"));
      }
    }
  }
}
