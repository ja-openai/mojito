package com.box.l10n.mojito.service.review;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import org.junit.Test;
import org.springframework.data.jpa.repository.Query;

public class ReviewProjectRepositoryTest {

  @Test
  public void recomputeDecidedCountsScopesAggregateToRequest() throws Exception {
    String sql = queryValue("recomputeDecidedCountsByRequestId", Long.class);
    int aggregateStart = sql.indexOf("from ( select distinct rptu.id");
    int requestFilter = sql.indexOf("rp_inner.review_project_request_id = :requestId");
    int aggregateEnd = sql.indexOf(") decided_units", aggregateStart);

    assertTrue(
        "Expected request filter inside decided_units aggregate",
        aggregateStart >= 0 && requestFilter > aggregateStart && requestFilter < aggregateEnd);
    assertTrue(
        "Expected outer update to stay scoped to request",
        sql.contains("where rp.review_project_request_id = :requestId"));
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
