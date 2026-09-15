-- Only new intake receives trustworthy deterministic keys. No legacy text hashing/backfill.
ALTER TABLE agent_review_proposal ADD COLUMN intake_fingerprint VARCHAR(64) NULL;
ALTER TABLE agent_review_proposal ADD COLUMN active_intake_fingerprint VARCHAR(64) NULL;
CREATE UNIQUE INDEX UK__AGENT_REVIEW_PROPOSAL__ACTIVE_INTAKE ON agent_review_proposal(active_intake_fingerprint);
ALTER TABLE translation_incident ADD COLUMN intake_fingerprint VARCHAR(64) NULL;
ALTER TABLE translation_incident ADD COLUMN active_intake_fingerprint VARCHAR(64) NULL;
ALTER TABLE translation_incident ADD COLUMN review_team_id BIGINT NULL;
ALTER TABLE translation_incident ADD COLUMN selected_source_comment LONGTEXT NULL;
CREATE UNIQUE INDEX UK__TRANSLATION_INCIDENT__ACTIVE_INTAKE ON translation_incident(active_intake_fingerprint);

CREATE INDEX I__AGENT_REVIEW_PROPOSAL__STRING_STATE ON agent_review_proposal(tm_text_unit_id,locale_id,disposition,id);
CREATE INDEX I__TRANSLATION_INCIDENT__STRING_STATE ON translation_incident(selected_tm_text_unit_id,resolved_locale_id,status,id);
CREATE TABLE agent_review_submission (
  id VARCHAR(64) NOT NULL PRIMARY KEY,
  request_fingerprint VARCHAR(64) NOT NULL,
  proposal_id BIGINT NOT NULL
);
