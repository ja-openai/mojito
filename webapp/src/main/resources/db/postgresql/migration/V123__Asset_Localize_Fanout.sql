CREATE TABLE asset_localize_fanout (
    parent_task_id BIGINT NOT NULL PRIMARY KEY,
    input_blob_name VARCHAR(128) NOT NULL UNIQUE,
    input_sha256 CHAR(64) NOT NULL,
    slot_count INTEGER NOT NULL,
    state VARCHAR(16) NOT NULL,
    next_attempt_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    created_date TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_date TIMESTAMP(6) WITH TIME ZONE NOT NULL
);
CREATE INDEX I__ASSET_FANOUT__DUE ON asset_localize_fanout (state, next_attempt_at, parent_task_id);

CREATE TABLE asset_localize_fanout_child (
    parent_task_id BIGINT NOT NULL,
    slot_ordinal INTEGER NOT NULL,
    child_task_id BIGINT NOT NULL UNIQUE,
    queue_job_id BIGINT NOT NULL UNIQUE,
    output_tag VARCHAR(255) NOT NULL,
    PRIMARY KEY (parent_task_id, slot_ordinal),
    CONSTRAINT FK__ASSET_FANOUT_CHILD__PARENT FOREIGN KEY (parent_task_id)
        REFERENCES asset_localize_fanout (parent_task_id)
);
