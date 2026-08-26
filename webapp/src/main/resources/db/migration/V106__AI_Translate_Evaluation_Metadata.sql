alter table ai_translate_text_unit_attempt
    add column prompt_fingerprint varchar(64) DEFAULT NULL,
    add column reasoning_effort varchar(32) DEFAULT NULL,
    add column text_verbosity varchar(32) DEFAULT NULL;

create index I__AITTA__EVALUATION_COHORT
    on ai_translate_text_unit_attempt(prompt_fingerprint, model, locale_id, created_date);
