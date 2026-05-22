CREATE TABLE async_job_queue (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
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
  updated_date DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
);

CREATE INDEX I__ASYNC_JOB_QUEUE__QNAME_STATUS_AVAILABLE_ID
  ON async_job_queue (queue_name, status, available_at, id);

CREATE INDEX I__ASYNC_JOB_QUEUE__QNAME_STATUS_LEASE_ID
  ON async_job_queue (queue_name, status, lease_until, id);
