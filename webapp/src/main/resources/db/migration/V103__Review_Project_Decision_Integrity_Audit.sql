alter table review_project_text_unit_decision
    add index I__RPTUD__STATE_MODIFIED_ID (decision_state, last_modified_date, id),
    algorithm=inplace,
    lock=none;
