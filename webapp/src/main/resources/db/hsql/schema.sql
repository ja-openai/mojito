DROP TABLE IF EXISTS SPRING_SESSION_V2_ATTRIBUTES;
DROP TABLE IF EXISTS SPRING_SESSION_V2;

CREATE TABLE SPRING_SESSION_V2 (
                                   PRIMARY_ID CHAR(36) NOT NULL,
                                   SESSION_ID CHAR(36) NOT NULL,
                                   CREATION_TIME BIGINT NOT NULL,
                                   LAST_ACCESS_TIME BIGINT NOT NULL,
                                   MAX_INACTIVE_INTERVAL INT NOT NULL,
                                   EXPIRY_TIME BIGINT NOT NULL,
                                   PRINCIPAL_NAME VARCHAR(100),
                                   CONSTRAINT SPRING_SESSION_V2_PK PRIMARY KEY (PRIMARY_ID)
);

CREATE UNIQUE INDEX SPRING_SESSION_V2_IX1 ON SPRING_SESSION_V2 (SESSION_ID);
CREATE INDEX SPRING_SESSION_V2_IX2 ON SPRING_SESSION_V2 (EXPIRY_TIME);
CREATE INDEX SPRING_SESSION_V2_IX3 ON SPRING_SESSION_V2 (PRINCIPAL_NAME);

CREATE TABLE SPRING_SESSION_V2_ATTRIBUTES (
                                              SESSION_PRIMARY_ID CHAR(36) NOT NULL,
                                              ATTRIBUTE_NAME VARCHAR(200) NOT NULL,
                                              ATTRIBUTE_BYTES LONGVARBINARY NOT NULL,
                                              CONSTRAINT SPRING_SESSION_V2_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
                                              CONSTRAINT SPRING_SESSION_V2_ATTRIBUTES_FK FOREIGN KEY (SESSION_PRIMARY_ID) REFERENCES SPRING_SESSION_V2(PRIMARY_ID) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS asset_localize_fanout (
    parent_task_id BIGINT NOT NULL PRIMARY KEY,
    input_blob_name VARCHAR(128) NOT NULL UNIQUE,
    input_sha256 CHAR(64) NOT NULL,
    slot_count INTEGER NOT NULL,
    state VARCHAR(16) NOT NULL,
    next_attempt_at TIMESTAMP(6) NOT NULL,
    created_date TIMESTAMP(6) NOT NULL,
    updated_date TIMESTAMP(6) NOT NULL
);
CREATE INDEX IF NOT EXISTS I__ASSET_FANOUT__DUE ON asset_localize_fanout (state, next_attempt_at, parent_task_id);

CREATE TABLE IF NOT EXISTS asset_localize_fanout_child (
    parent_task_id BIGINT NOT NULL,
    slot_ordinal INTEGER NOT NULL,
    child_task_id BIGINT NOT NULL UNIQUE,
    queue_job_id BIGINT NOT NULL UNIQUE,
    output_tag VARCHAR(255) NOT NULL,
    PRIMARY KEY (parent_task_id, slot_ordinal),
    CONSTRAINT FK__ASSET_FANOUT_CHILD__PARENT FOREIGN KEY (parent_task_id)
        REFERENCES asset_localize_fanout (parent_task_id)
);
