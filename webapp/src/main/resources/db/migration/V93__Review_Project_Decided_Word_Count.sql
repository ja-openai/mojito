ALTER TABLE review_project
    ADD COLUMN decided_word_count bigint(20) NOT NULL DEFAULT 0;

UPDATE review_project rp
LEFT JOIN (
    SELECT
        decided_units.review_project_id,
        COUNT(*) AS decided_count,
        COALESCE(SUM(COALESCE(tu.word_count, 0)), 0) AS decided_word_count
    FROM (
        SELECT DISTINCT
            rptu.id,
            rptu.review_project_id,
            rptu.tm_text_unit_id
        FROM review_project_text_unit rptu
        JOIN review_project rp_inner
            ON rp_inner.id = rptu.review_project_id
        LEFT JOIN review_project_text_unit_decision rptud
            ON rptud.review_project_text_unit_id = rptu.id
        LEFT JOIN review_project_text_unit_feedback rptuf
            ON rptuf.review_project_text_unit_id = rptu.id
        WHERE (
            rp_inner.type IN ('TERMINOLOGY', 'TERM_CANDIDATE')
            AND rp_inner.terminology_phase = 'SPECIALIST_INPUT'
            AND rptuf.id IS NOT NULL
        ) OR (
            NOT (
                rp_inner.type IN ('TERMINOLOGY', 'TERM_CANDIDATE')
                AND COALESCE(rp_inner.terminology_phase, '') = 'SPECIALIST_INPUT'
            )
            AND rptud.decision_state = 'DECIDED'
        )
    ) decided_units
    JOIN tm_text_unit tu
        ON tu.id = decided_units.tm_text_unit_id
    GROUP BY decided_units.review_project_id
) decided_word_counts
    ON decided_word_counts.review_project_id = rp.id
SET
    rp.decided_count = COALESCE(decided_word_counts.decided_count, 0),
    rp.decided_word_count = COALESCE(decided_word_counts.decided_word_count, 0);
