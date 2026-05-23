CREATE TABLE async_job_queue (
  id BIGSERIAL PRIMARY KEY,
  queue_name VARCHAR(64) NOT NULL,
  status VARCHAR(16) NOT NULL,
  available_at TIMESTAMPTZ(6) NOT NULL,
  lease_until TIMESTAMPTZ(6) NULL,
  worker_id VARCHAR(128) NULL,
  lease_token VARCHAR(64) NULL,
  job_data TEXT NOT NULL,
  attempt_count INTEGER NOT NULL DEFAULT 0,
  last_error TEXT NULL,
  created_date TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_date TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT C__ASYNC_JOB_QUEUE__ID_POSITIVE
    CHECK (id > 0),
  CONSTRAINT C__ASYNC_JOB_QUEUE__QUEUE_NAME
    CHECK (queue_name ~ '^[A-Za-z0-9._-]+$'),
  CONSTRAINT C__ASYNC_JOB_QUEUE__STATUS
    CHECK (status IN ('queued', 'running', 'done', 'failed')),
  CONSTRAINT C__ASYNC_JOB_QUEUE__ATTEMPT_RANGE
    CHECK (attempt_count BETWEEN 0 AND 101),
  CONSTRAINT C__ASYNC_JOB_QUEUE__JOB_DATA_LENGTH
    CHECK (CHAR_LENGTH(job_data) <= 1000000),
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
