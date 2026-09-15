-- Only new guarded human decisions have a trustworthy exact-state receipt.
ALTER TABLE agent_review_feedback ADD COLUMN reviewed_state_fingerprint VARCHAR(64) NULL;
CREATE INDEX I__AGENT_REVIEW_FEEDBACK__REVIEWED_STATE ON agent_review_feedback (reviewed_state_fingerprint);
