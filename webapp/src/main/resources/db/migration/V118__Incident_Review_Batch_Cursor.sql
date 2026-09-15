-- A saved finite sweep prevents permanently ineligible incidents from starving later work.
CREATE TABLE incident_review_batch_cursor (
    id BIGINT NOT NULL AUTO_INCREMENT,
    created_date DATETIME DEFAULT NULL,
    last_modified_date DATETIME DEFAULT NULL,
    team_id BIGINT NOT NULL,
    scope_fingerprint VARCHAR(64) NOT NULL,
    last_scanned_incident_id BIGINT NOT NULL DEFAULT 0,
    sweep_upper_bound_id BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY UK__INCIDENT_REVIEW_BATCH_CURSOR__SCOPE (team_id, scope_fingerprint)
);

CREATE INDEX I__TRANSLATION_INCIDENT__BATCH_SEEK
    ON translation_incident (status, resolution_review_project_id, id);
CREATE INDEX I__TRANSLATION_INCIDENT__TYPED_BATCH_SEEK
    ON translation_incident (status, resolution_review_project_id, review_type, id);
