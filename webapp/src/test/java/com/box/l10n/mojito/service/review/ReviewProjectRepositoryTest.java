package com.box.l10n.mojito.service.review;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import org.junit.Test;
import org.springframework.data.jpa.repository.Query;

public class ReviewProjectRepositoryTest {

  @Test
  public void recomputeDecidedCountsScopesAggregateToRequest() throws Exception {
    Method method =
        ReviewProjectRepository.class.getMethod("recomputeDecidedCountsByRequestId", Long.class);
    Query query = method.getAnnotation(Query.class);

    assertNotNull(query);

    String sql = query.value().replaceAll("\\s+", " ").trim();
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
}
