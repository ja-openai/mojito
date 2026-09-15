package com.box.l10n.mojito.service.review.feedback;

import static org.junit.Assert.*;

import com.box.l10n.mojito.entity.review.ReviewFeedbackEvent;
import org.hibernate.cfg.Configuration;
import org.junit.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

public class ReviewFeedbackPersistenceTest {
  @Test
  public void migrationMatchesEntityAndEvidenceRollsBackWithTransaction() {
    String url = "jdbc:hsqldb:mem:feedback-" + java.util.UUID.randomUUID() + ";sql.syntax_mys=true";
    var source = new DriverManagerDataSource(url, "sa", "");
    new ResourceDatabasePopulator(
            new ClassPathResource("db/migration/V120__Review_Feedback_Events.sql"))
        .execute(source);
    try (var factory =
        new Configuration()
            .addAnnotatedClass(ReviewFeedbackEvent.class)
            .setProperty("hibernate.connection.url", url)
            .setProperty("hibernate.connection.driver_class", "org.hsqldb.jdbc.JDBCDriver")
            .setProperty("hibernate.connection.username", "sa")
            .setProperty("hibernate.connection.password", "")
            .setProperty("hibernate.hbm2ddl.auto", "validate")
            .setProperty("hibernate.type.preferred_instant_jdbc_type", "TIMESTAMP")
            .buildSessionFactory()) {
      try (var session = factory.openSession()) {
        var tx = session.beginTransaction();
        session.persist(event("rolled-back"));
        session.flush();
        tx.rollback();
      }
      try (var session = factory.openSession()) {
        var tx = session.beginTransaction();
        assertEquals(
            Long.valueOf(0),
            session
                .createQuery("select count(e) from ReviewFeedbackEvent e", Long.class)
                .getSingleResult());
        session.persist(event("accepted"));
        tx.commit();
      }
      try (var session = factory.openSession()) {
        var tx = session.beginTransaction();
        assertEquals(
            Long.valueOf(1),
            session
                .createQuery("select count(e) from ReviewFeedbackEvent e", Long.class)
                .getSingleResult());
        assertThrows(
            org.hibernate.exception.ConstraintViolationException.class,
            () -> session.persist(event("accepted")));
        tx.rollback();
      }
    }
  }

  private ReviewFeedbackEvent event(String key) {
    return new ReviewFeedbackEvent(
        key,
        null,
        2L,
        3L,
        "bg",
        "model",
        "prompt",
        "QUOTE_STYLE",
        "pattern",
        "source",
        "baseline",
        "final",
        true,
        "{}");
  }
}
