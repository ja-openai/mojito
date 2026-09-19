package com.box.l10n.mojito.queue;

import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.tm.AssetLocalizeAsyncJobPayload;
import com.box.l10n.mojito.service.tm.AssetLocalizeAsyncJobSubmissionService;
import com.box.l10n.mojito.service.tm.AssetLocalizeFanoutInput.Manifest;
import com.box.l10n.mojito.service.tm.AssetLocalizeFanoutInput.Reference;
import com.box.l10n.mojito.service.tm.GenerateLocalizedAssetJob;
import com.box.l10n.mojito.service.tm.GenerateMultiLocalizedAssetJob;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Mojito-specific admission adapter; the generic queue's public enqueue stays independent. */
@Component
@ConditionalOnExpression(
    "${l10n.org.async-job-queue.enabled:false} && "
        + "${l10n.org.async-job-queue.asset-localize.enabled:false} && "
        + "'${l10n.org.async-job-queue.store:in-memory}' == 'jdbc'")
public class AssetLocalizeFanoutStore {
  public record Parent(long taskId, Reference input, String state) {}

  private final JdbcTemplate jdbc;
  private final EntityManagerFactory entityManagerFactory;
  private final JdbcAsyncJobStore queue;
  private final ObjectMapper mapper;
  private final TransactionTemplate transaction;

  public AssetLocalizeFanoutStore(
      JdbcTemplate jdbc,
      EntityManagerFactory entityManagerFactory,
      PlatformTransactionManager manager,
      AsyncJobStore queue,
      ObjectMapper mapper) {
    if (!(queue instanceof JdbcAsyncJobStore jdbcQueue)
        || !(manager instanceof JpaTransactionManager jpaManager)
        || jpaManager.getEntityManagerFactory() != entityManagerFactory
        || jpaManager.getDataSource() != jdbc.getDataSource()) {
      throw new IllegalArgumentException(
          "Durable fanout requires the shared JPA/JDBC queue manager");
    }
    this.jdbc = jdbc;
    this.entityManagerFactory = entityManagerFactory;
    this.queue = jdbcQueue;
    this.mapper = mapper;
    transaction = new TransactionTemplate(manager);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  public long register(Reference input) {
    return transaction.execute(
        status -> {
          EntityManager em = entityManager();
          PollableTask parent = task(GenerateMultiLocalizedAssetJob.class.getName());
          parent.setExpectedSubTaskNumber(input.count());
          em.persist(parent);
          em.flush();
          Object now = queue.databaseTimestampAfterSeconds(0);
          jdbc.update(
              """
          INSERT INTO asset_localize_fanout
            (parent_task_id, input_blob_name, input_sha256, slot_count, state, next_attempt_at,
             created_date, updated_date)
          VALUES (?, ?, ?, ?, 'PENDING', ?, ?, ?)
          """,
              parent.getId(),
              input.name(),
              input.sha256(),
              input.count(),
              now,
              now,
              now);
          return parent.getId();
        });
  }

  public Optional<Parent> findByInputName(String name) {
    return find("input_blob_name = ?", name);
  }

  public Optional<Parent> find(long taskId) {
    return find("parent_task_id = ?", taskId);
  }

  private Optional<Parent> find(String predicate, Object key) {
    return jdbc
        .query(
            "SELECT parent_task_id, input_blob_name, input_sha256, slot_count, state FROM asset_localize_fanout WHERE "
                + predicate,
            (rs, row) ->
                new Parent(
                    rs.getLong(1),
                    new Reference(rs.getString(2), rs.getString(3), rs.getInt(4)),
                    rs.getString(5)),
            key)
        .stream()
        .findFirst();
  }

  /** Either every child is accepted with its mapping or none is. A failed commit is unknown. */
  public void accept(Parent expected, Manifest manifest) {
    transaction.executeWithoutResult(
        status -> {
          Parent parent = lock(expected.taskId());
          if (!parent.input().equals(expected.input())) {
            throw new IllegalStateException("Asset fanout input identity changed");
          }
          if (!parent.state().equals("PENDING")) {
            return;
          }
          if (manifest.slots().size() != parent.input().count()) {
            throw new IllegalArgumentException("Asset fanout child count changed");
          }
          // Predicate is evaluated against the current row after competing writers settle,
          // including
          // MVCC engines whose earlier SELECT can retain its statement snapshot while waiting.
          if (jdbc.update(
                  "UPDATE asset_localize_fanout SET state = 'ACCEPTING' WHERE parent_task_id = ? AND state = 'PENDING'",
                  parent.taskId())
              != 1) {
            return;
          }
          EntityManager em = entityManager();
          PollableTask parentTask = em.find(PollableTask.class, parent.taskId());
          if (parentTask == null || parentTask.getFinishedDate() != null) {
            throw new IllegalStateException("Asset fanout parent cannot accept children");
          }
          for (int ordinal = 0; ordinal < manifest.slots().size(); ordinal++) {
            PollableTask child = task(GenerateLocalizedAssetJob.class.getName());
            child.setParentTask(parentTask);
            child.setCreatedByUser(parentTask.getCreatedByUser());
            em.persist(child);
            em.flush();
            AsyncJobId job =
                queue.enqueueNowInCurrentTransaction(
                    AssetLocalizeAsyncJobSubmissionService.QUEUE_NAME,
                    mapper.writeValueAsStringUnchecked(
                        new AssetLocalizeAsyncJobPayload(child.getId())));
            jdbc.update(
                """
            INSERT INTO asset_localize_fanout_child
              (parent_task_id, slot_ordinal, child_task_id, queue_job_id, output_tag)
            VALUES (?, ?, ?, ?, ?)
            """,
                parent.taskId(),
                ordinal,
                child.getId(),
                Long.parseLong(job.value()),
                manifest.slots().get(ordinal).outputTag());
          }
          jdbc.update(
              "UPDATE asset_localize_fanout SET state = 'ACCEPTED', updated_date = ? WHERE parent_task_id = ?",
              queue.databaseTimestampAfterSeconds(0),
              parent.taskId());
        });
  }

  public Map<String, Long> mappings(Parent parent) {
    Map<String, Long> result = new LinkedHashMap<>();
    List<Integer> ordinals =
        jdbc.query(
            "SELECT slot_ordinal, output_tag, child_task_id FROM asset_localize_fanout_child WHERE parent_task_id = ? ORDER BY slot_ordinal",
            (rs, row) -> {
              if (result.put(rs.getString(2), rs.getLong(3)) != null) {
                throw new IllegalStateException("Duplicate durable asset output tag");
              }
              return rs.getInt(1);
            },
            parent.taskId());
    if (ordinals.size() != parent.input().count()) {
      throw new IllegalStateException("Incomplete durable asset fanout mapping");
    }
    for (int ordinal = 0; ordinal < ordinals.size(); ordinal++) {
      if (ordinals.get(ordinal) != ordinal) {
        throw new IllegalStateException("Invalid durable asset fanout slot");
      }
    }
    return result;
  }

  /** Called only after immutable parent output has been published successfully. */
  public void finish(long taskId) {
    transaction.executeWithoutResult(
        status -> {
          Parent parent = lock(taskId);
          if (parent.state().equals("FINISHED") || parent.state().equals("COMPLETED")) {
            return;
          }
          if (!parent.state().equals("ACCEPTED")) {
            throw new IllegalStateException("Cannot finish unaccepted asset fanout");
          }
          PollableTask task = entityManager().find(PollableTask.class, taskId);
          if (task == null || task.getErrorMessage() != null || task.getErrorStack() != null) {
            throw new IllegalStateException("Asset fanout parent requires operator reconciliation");
          }
          if (task.getFinishedDate() == null) {
            task.setFinishedDate(ZonedDateTime.now());
          }
          jdbc.update(
              "UPDATE asset_localize_fanout SET state = 'FINISHED', updated_date = ? WHERE parent_task_id = ?",
              queue.databaseTimestampAfterSeconds(0),
              taskId);
        });
  }

  public List<Long> dueParents(int limit) {
    return jdbc.queryForList(
        "SELECT parent_task_id FROM asset_localize_fanout WHERE state IN ('PENDING', 'ACCEPTED', 'FINISHED') AND next_attempt_at <= ? ORDER BY next_attempt_at, parent_task_id LIMIT ?",
        Long.class,
        queue.databaseTimestampAfterSeconds(0),
        limit);
  }

  public boolean claimAttempt(long parentId) {
    return transaction.execute(
        status ->
            jdbc.update(
                    "UPDATE asset_localize_fanout SET next_attempt_at = ?, updated_date = ? WHERE parent_task_id = ? AND state IN ('PENDING', 'ACCEPTED', 'FINISHED') AND next_attempt_at <= ?",
                    queue.databaseTimestampAfterSeconds(30),
                    queue.databaseTimestampAfterSeconds(0),
                    parentId,
                    queue.databaseTimestampAfterSeconds(0))
                == 1);
  }

  public List<Long> terminalChildrenNeedingRepair(long parentId) {
    return jdbc.queryForList(
        """
        SELECT c.queue_job_id FROM asset_localize_fanout_child c
        JOIN async_job_queue q ON q.id = c.queue_job_id
        JOIN pollable_task t ON t.id = c.child_task_id
        WHERE c.parent_task_id = ? AND q.status IN ('done', 'failed') AND t.finished_date IS NULL
        ORDER BY c.slot_ordinal
        """,
        Long.class,
        parentId);
  }

  public void completeIfChildrenFinished(long parentId) {
    transaction.executeWithoutResult(
        status -> {
          Parent parent = lock(parentId);
          if (!parent.state().equals("FINISHED")) return;
          int finished =
              jdbc.queryForObject(
                  """
          SELECT COUNT(*) FROM asset_localize_fanout_child c
          JOIN async_job_queue q ON q.id = c.queue_job_id
          JOIN pollable_task t ON t.id = c.child_task_id
          WHERE c.parent_task_id = ? AND q.status IN ('done', 'failed') AND t.finished_date IS NOT NULL
          """,
                  Integer.class,
                  parentId);
          if (finished == parent.input().count()) {
            jdbc.update(
                "UPDATE asset_localize_fanout SET state = 'COMPLETED', updated_date = ? WHERE parent_task_id = ?",
                queue.databaseTimestampAfterSeconds(0),
                parentId);
          }
        });
  }

  private Parent lock(long parentId) {
    // Connector/J can report either matched or changed rows for a no-op UPDATE.
    // The following primary lookup establishes existence after taking the write lock.
    jdbc.update(
        "UPDATE asset_localize_fanout SET updated_date = updated_date WHERE parent_task_id = ?",
        parentId);
    return find(parentId).orElseThrow();
  }

  private EntityManager entityManager() {
    return Objects.requireNonNull(
        EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagerFactory));
  }

  private PollableTask task(String name) {
    PollableTask task = new PollableTask();
    task.setName(name);
    task.setCreatedDate(ZonedDateTime.now());
    task.setLastModifiedDate(task.getCreatedDate());
    // Durable queue/reconciler owns completion; SQL timeout comparisons exclude null deadlines.
    task.setTimeout(null);
    return task;
  }
}
