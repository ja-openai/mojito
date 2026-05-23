CREATE TABLE async_job_queue (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  queue_name VARCHAR(64) NOT NULL,
  status VARCHAR(16) NOT NULL,
  available_at DATETIME(6) NOT NULL,
  lease_until DATETIME(6) NULL,
  worker_id VARCHAR(128) NULL,
  lease_token VARCHAR(64) NULL,
  job_data LONGTEXT NOT NULL,
  attempt_count INT NOT NULL DEFAULT 0,
  last_error TEXT NULL,
  created_date DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_date DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  CONSTRAINT C__ASYNC_JOB_QUEUE__QUEUE_NAME
    CHECK (REGEXP_LIKE(queue_name, '^[A-Za-z0-9._-]+$', 'c')),
  CONSTRAINT C__ASYNC_JOB_QUEUE__STATUS
    CHECK (status IN ('queued', 'running', 'done', 'failed')),
  CONSTRAINT C__ASYNC_JOB_QUEUE__ATTEMPT_NONNEGATIVE
    CHECK (attempt_count >= 0),
  CONSTRAINT C__ASYNC_JOB_QUEUE__LAST_ERROR_LENGTH
    CHECK (last_error IS NULL OR CHAR_LENGTH(last_error) <= 4000),
  CONSTRAINT C__ASYNC_JOB_QUEUE__FAILED_LAST_ERROR
    CHECK (status <> 'failed' OR (last_error IS NOT NULL AND TRIM(last_error) <> '')),
  CONSTRAINT C__ASYNC_JOB_QUEUE__RUNNING_LEASE_OWNER
    CHECK (
      (status = 'running' AND lease_until IS NOT NULL AND worker_id IS NOT NULL AND lease_token IS NOT NULL)
      OR (status <> 'running' AND lease_until IS NULL AND worker_id IS NULL AND lease_token IS NULL)
    ),
  CONSTRAINT C__ASYNC_JOB_QUEUE__LEASE_OWNER_NONBLANK
    CHECK (
      status <> 'running'
      OR (TRIM(worker_id) <> '' AND TRIM(lease_token) <> '')
    )
);

CREATE INDEX I__ASYNC_JOB_QUEUE__QNAME_STATUS_AVAILABLE_ID
  ON async_job_queue (queue_name, status, available_at, id);

CREATE INDEX I__ASYNC_JOB_QUEUE__QNAME_STATUS_LEASE_ID
  ON async_job_queue (queue_name, status, lease_until, id);

CREATE INDEX I__ASYNC_JOB_QUEUE__QNAME_STATUS_UPDATED_ID
  ON async_job_queue (queue_name, status, updated_date, id);
