ALTER TABLE agent_review_run ADD COLUMN routing_policy VARCHAR(32) NOT NULL DEFAULT 'IMMEDIATE';
CREATE INDEX I__TRANSLATION_INCIDENT__REVIEW_BATCH ON translation_incident (status, resolution_review_project_id, selected_tm_text_unit_id);
ALTER TABLE translation_incident ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
