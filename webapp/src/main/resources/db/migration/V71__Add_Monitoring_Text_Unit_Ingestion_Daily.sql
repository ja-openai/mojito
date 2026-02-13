CREATE TABLE monitoring_text_unit_ingestion_daily (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_date datetime DEFAULT NULL,
    last_modified_date datetime DEFAULT NULL,
    day_utc date NOT NULL,
    repository_id bigint(20) NOT NULL,
    string_count bigint(20) NOT NULL,
    word_count bigint(20) NOT NULL,
    computed_at datetime NOT NULL,
    PRIMARY KEY (id)
);

ALTER TABLE monitoring_text_unit_ingestion_daily
    ADD CONSTRAINT UK__MTUID__DAY__REPOSITORY__ID UNIQUE (day_utc, repository_id);
ALTER TABLE monitoring_text_unit_ingestion_daily
    ADD CONSTRAINT FK__MTUID__REPOSITORY__ID FOREIGN KEY (repository_id) REFERENCES repository (id);

CREATE INDEX I__MTUID__DAY_UTC ON monitoring_text_unit_ingestion_daily (day_utc);
CREATE INDEX I__MTUID__REPOSITORY__ID ON monitoring_text_unit_ingestion_daily (repository_id);

CREATE TABLE monitoring_text_unit_ingestion_state (
    id tinyint NOT NULL,
    latest_computed_day date DEFAULT NULL,
    last_computed_at datetime DEFAULT NULL,
    PRIMARY KEY (id)
);

INSERT INTO monitoring_text_unit_ingestion_state (id, latest_computed_day, last_computed_at)
VALUES (1, NULL, NULL);
