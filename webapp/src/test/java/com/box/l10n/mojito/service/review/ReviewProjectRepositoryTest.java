package com.box.l10n.mojito.service.review;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import org.junit.Test;
import org.springframework.data.jpa.repository.Query;

public class ReviewProjectRepositoryTest {

  @Test
  public void recomputeDecidedCountsScopesToRequestAndProjectRows() throws Exception {
    String sql = queryValue("recomputeDecidedCountsByRequestId", Long.class);

    assertTrue(
        "Expected outer update to stay scoped to request",
        sql.contains("where rp.review_project_request_id = :requestId"));
    assertTrue(
        "Expected decided-count subquery to stay scoped to the current project row",
        sql.contains("where rptu.review_project_id = rp.id"));
    assertTrue(
        "Expected decided-word-count subquery to stay scoped to the current project row",
        sql.indexOf("where rptu.review_project_id = rp.id")
            != sql.lastIndexOf("where rptu.review_project_id = rp.id"));
  }

  @Test
  public void incrementDecidedProgressUpdatesCountersTogether() throws Exception {
    String sql = queryValue("incrementDecidedProgress", Long.class, Long.class);

    assertTrue(sql.contains("rp.decidedCount = rp.decidedCount + 1,"));
    assertTrue(sql.contains("rp.decidedWordCount = rp.decidedWordCount + :wordCount"));
  }

  @Test
  public void decrementDecidedProgressUpdatesCountersTogether() throws Exception {
    String sql = queryValue("decrementDecidedProgress", Long.class, Long.class);

    assertTrue(sql.contains("rp.decidedCount = case"));
    assertTrue(sql.contains("rp.decidedWordCount = case"));
    assertTrue(sql.contains("when rp.decidedWordCount > :wordCount"));
  }

  private String queryValue(String methodName, Class<?>... parameterTypes) throws Exception {
    Method method = ReviewProjectRepository.class.getMethod(methodName, parameterTypes);
    Query query = method.getAnnotation(Query.class);

    assertNotNull(query);

    return query.value().replaceAll("\\s+", " ").trim();
  }
}
