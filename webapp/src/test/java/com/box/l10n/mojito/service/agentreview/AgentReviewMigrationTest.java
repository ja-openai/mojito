package com.box.l10n.mojito.service.agentreview;

import static org.junit.Assert.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** Executes the production migration, including durable lineage and database retry constraints. */
public class AgentReviewMigrationTest {
  @Test
  public void migrationPreservesHistoryAndRejectsDuplicateSubmissionsRevisionsAndResponses()
      throws Exception {
    String url =
        "jdbc:hsqldb:mem:agent-review-" + UUID.randomUUID() + ";sql.syntax_mys=true;shutdown=true";
    try (Connection connection = DriverManager.getConnection(url, "sa", "");
        Statement sql = connection.createStatement()) {
      ScriptUtils.executeSqlScript(
          connection, new ClassPathResource("db/migration/V111__Agent_Review.sql"));
      sql.execute("create table review_project (id bigint primary key)");
      sql.execute("insert into review_project values (42)");
      sql.execute(
          """
          insert into agent_review_run
            (id,request_key,request_fingerprint,input_fingerprint,requested_by_user_id,review_type,team_id,
             repository_ids_json,locale_ids_json,method_version,configuration_version,manifest_sha256,
             status,planned_group_count,completed_group_count,failed_group_count,reviewed_item_count,
             revision,claim_generation,due_date_offset_days,max_word_count_per_project,assign_translator)
          values (1,'request','hash','inputs',4,'TRANSLATION_QUALITY',5,'[2]','[3]','v1','v1','blob',
             'RUNNING',1,0,0,0,0,0,7,1500,true)
          """);
      sql.execute(proposal(10, "submission", "finding", 1));
      assertThrows(
          SQLException.class, () -> sql.execute(proposal(11, "submission", "other-finding", 1)));
      assertThrows(
          SQLException.class,
          () -> sql.execute(proposal(12, "different-submission", "finding", 1)));
      sql.execute(proposal(13, "revision", "finding", 2));
      sql.execute(feedback(20, "human", "HUMAN", null));
      sql.execute(feedback(21, "response", "AGENT", 20L));
      assertThrows(
          SQLException.class, () -> sql.execute(feedback(22, "another-response", "AGENT", 20L)));
      assertThrows(SQLException.class, () -> sql.execute(feedback(23, "human", "HUMAN", null)));
      // Project links are historical scalar identities. Deleting a review must not delete evidence.
      sql.execute("delete from review_project where id=42");
      try (var results =
          sql.executeQuery(
              "select review_project_id, source, proposed_target from agent_review_proposal where id=10")) {
        assertTrue(results.next());
        assertEquals(42, results.getLong(1));
        assertEquals("保存 😀", results.getString(2));
        assertEquals("Enregistrer", results.getString(3));
      }
      assertThrows(
          SQLException.class, () -> sql.execute("delete from agent_review_proposal where id=10"));
      assertThrows(
          SQLException.class, () -> sql.execute("delete from agent_review_run where id=1"));
      try (var results = sql.executeQuery("select count(*) from agent_review_feedback")) {
        assertTrue(results.next());
        assertEquals(2, results.getInt(1));
      }
    }
  }

  private String proposal(long id, String key, String finding, int revision) {
    return "insert into agent_review_proposal (id,run_id,submission_key,request_fingerprint,finding_id,proposal_revision,group_key,repository_id,locale_id,tm_text_unit_id,source,proposed_target,category,readiness,disposition,rationale,producer_identity,review_project_id,version) values ("
        + id
        + ",1,'"
        + key
        + "','hash','"
        + finding
        + "',"
        + revision
        + ",'fr/settings',2,3,6,'保存 😀','Enregistrer','OBVIOUS_ERROR','READY','ROUTED','Meaning mismatch','worker',42,0)";
  }

  private String feedback(long id, String key, String actor, Long respondsTo) {
    return "insert into agent_review_feedback (id,proposal_id,request_key,request_fingerprint,actor_type,actor_user_id,actor_identity,action,follow_up_requested,responds_to_feedback_id) values ("
        + id
        + ",10,'"
        + key
        + "','hash','"
        + actor
        + "',4,'reviewer','CHALLENGE',false,"
        + respondsTo
        + ")";
  }
}
