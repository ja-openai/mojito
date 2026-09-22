-- Closed, undecided review assignments can return to the incident queue.
-- Keep each range read bounded independently of its assignment pointer.
CREATE INDEX I__TRANSLATION_INCIDENT__OPEN_SEEK
    ON translation_incident (status, id);
CREATE INDEX I__TRANSLATION_INCIDENT__TYPED_OPEN_SEEK
    ON translation_incident (status, review_type, id);
