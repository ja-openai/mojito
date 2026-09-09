package com.box.l10n.mojito.service.oaireview;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.junit.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

public class AiReviewRequestUsageMigrationTest {

  @Test
  public void transcriptMigrationPreservesLegacyRowsAndAllowsLargeUnicodePayloads()
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
              (id, user_id, surface, request_type, profile_id, model_name, status, started_at)
          values (1, 1, 'review_project', 'automatic', 'version_b', 'model-name', 'started', current_timestamp)
          """);
      ScriptUtils.executeSqlScript(
          connection,
          new ClassPathResource("db/migration/V110__AI_Review_Request_Transcripts.sql"));
      statement.execute("delete from user where id = 1");

      String requestJson =
          "{\"messages\":[{\"role\":\"user\",\"content\":\""
              + "Перевірте 😀 修正 ".repeat(8000)
              + "\"},{\"role\":\"assistant\",\"content\":\"Змін не потрібно\"}]}";
      String responseJson =
          "{\"message\":{\"role\":\"assistant\",\"content\":\""
              + "Пояснення 😀 修正 ".repeat(8000)
              + "\"},\"suggestions\":[]}";
      assertTrue(requestJson.getBytes(StandardCharsets.UTF_8).length > 65535);
      assertTrue(responseJson.getBytes(StandardCharsets.UTF_8).length > 65535);
      try (PreparedStatement insert =
          connection.prepareStatement(
              """
              insert into ai_review_request_usage
                  (id, surface, request_type, profile_id, model_name, status, started_at,
                   request_json, response_json)
              values (2, 'text_unit_detail', 'follow_up', 'balanced', 'model-name', 'completed',
                      current_timestamp, ?, ?)
              """)) {
        insert.setString(1, requestJson);
        insert.setString(2, responseJson);
        assertEquals(1, insert.executeUpdate());
      }

      try (ResultSet result =
          statement.executeQuery(
              "select user_id, request_type, request_json, response_json"
                  + " from ai_review_request_usage order by id")) {
        assertTrue(result.next());
        assertNull(result.getObject("user_id"));
        assertEquals("automatic", result.getString("request_type"));
        assertNull(result.getString("request_json"));
        assertNull(result.getString("response_json"));

        assertTrue(result.next());
        assertNull(result.getObject("user_id"));
        assertEquals("follow_up", result.getString("request_type"));
        assertEquals(requestJson, result.getString("request_json"));
        assertEquals(responseJson, result.getString("response_json"));
        assertFalse(result.next());
      }
    }
  }
}
