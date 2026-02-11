ALTER TABLE review_project_request
    ADD COLUMN created_by_user_id bigint(20) DEFAULT NULL;

UPDATE review_project_request request
LEFT JOIN (
    SELECT
        review_project_request_id,
        MIN(created_by_user_id) AS created_by_user_id
    FROM review_project
    WHERE review_project_request_id IS NOT NULL
      AND created_by_user_id IS NOT NULL
    GROUP BY review_project_request_id
) review_project_creator
    ON review_project_creator.review_project_request_id = request.id
SET request.created_by_user_id = review_project_creator.created_by_user_id
WHERE request.created_by_user_id IS NULL;

ALTER TABLE review_project_request
    ADD CONSTRAINT FK__REVIEW_PROJECT_REQUEST__CREATED_BY_USER
        FOREIGN KEY (created_by_user_id) REFERENCES user (id);
