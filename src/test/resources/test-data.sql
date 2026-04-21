INSERT INTO t_market_analyses (analysis_date, analysis_slot, scheduled_time_local, model, prompt_version, summary_text, raw_json, updated_at) VALUES
  ('2026-04-20', 'pre_tw_open', '07:30', 'gpt-5', 'v1', 'Earlier draft', '{}', TIMESTAMP '2026-04-20 07:30:00');

INSERT INTO t_market_analyses (analysis_date, analysis_slot, scheduled_time_local, model, prompt_version, summary_text, raw_json, updated_at) VALUES
  ('2026-04-20', 'pre_tw_open', '07:30', 'gpt-5', 'v2', 'Latest pre-open summary', '{"k":"v"}', TIMESTAMP '2026-04-20 07:45:00');

INSERT INTO t_market_analyses (analysis_date, analysis_slot, scheduled_time_local, model, prompt_version, summary_text, raw_json, updated_at) VALUES
  ('2026-04-20', 'post_us_close', '05:00', 'gpt-5', 'v1', 'Post close summary', '{}', TIMESTAMP '2026-04-20 05:00:00');

INSERT INTO t_bot_group_info (group_id, test_account, active) VALUES ('G_ACTIVE_1', 0, 1);
INSERT INTO t_bot_group_info (group_id, test_account, active) VALUES ('G_ACTIVE_2', 0, 1);
INSERT INTO t_bot_group_info (group_id, test_account, active) VALUES ('G_INACTIVE', 0, 0);

INSERT INTO t_bot_user_info (user_id, test_account, active) VALUES ('U_ACTIVE_1', 0, 1);
INSERT INTO t_bot_user_info (user_id, test_account, active) VALUES ('U_TEST', 1, 1);
INSERT INTO t_bot_user_info (user_id, test_account, active) VALUES ('U_INACTIVE', 0, 0);
