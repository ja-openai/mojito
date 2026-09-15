-- Immutable translation review evidence. Scalar IDs preserve history after project deletion.
CREATE TABLE review_feedback_event (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 event_key VARCHAR(64) NOT NULL,
 project_id BIGINT NULL,
 text_unit_id BIGINT NOT NULL,
 reviewer_id BIGINT NOT NULL,
 locale_tag VARCHAR(64) NOT NULL,
 model VARCHAR(255) NOT NULL,
 prompt_version VARCHAR(255) NOT NULL,
 category VARCHAR(64) NOT NULL,
 pattern_key VARCHAR(64) NOT NULL,
 source_hash VARCHAR(64) NOT NULL,
 baseline_hash VARCHAR(64) NOT NULL,
 final_hash VARCHAR(64) NOT NULL,
 ai_baseline BOOLEAN NOT NULL,
 payload_json LONGTEXT NOT NULL,
 created_at DATETIME(6) NOT NULL
);
CREATE UNIQUE INDEX UK__REVIEW_FEEDBACK_EVENT__KEY ON review_feedback_event(event_key);
CREATE INDEX I__REVIEW_FEEDBACK_EVENT__AI ON review_feedback_event(ai_baseline,id);
