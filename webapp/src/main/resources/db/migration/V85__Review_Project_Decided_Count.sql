ALTER TABLE review_project
    ADD COLUMN decided_count bigint(20) NOT NULL DEFAULT 0;

ALTER TABLE review_project_text_unit_decision
    ADD COLUMN version bigint(20) DEFAULT 0;

UPDATE review_project rp
LEFT JOIN (
    SELECT rptu.review_project_id, COUNT(rptud.id) AS decided_count
    FROM review_project_text_unit rptu
    JOIN review_project_text_unit_decision rptud
        ON rptud.review_project_text_unit_id = rptu.id
        AND rptud.decision_state = 'DECIDED'
    GROUP BY rptu.review_project_id
) decided_counts
    ON decided_counts.review_project_id = rp.id
SET rp.decided_count = COALESCE(decided_counts.decided_count, 0);
