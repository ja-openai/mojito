alter table review_automation
    add column assign_translator bit not null after due_date_offset_days;
