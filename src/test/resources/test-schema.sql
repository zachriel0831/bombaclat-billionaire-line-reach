-- Minimal H2 schema that mirrors the MySQL tables used by repository tests.
CREATE TABLE IF NOT EXISTS t_market_analyses (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  analysis_date VARCHAR(32) NOT NULL,
  analysis_slot VARCHAR(64) NOT NULL,
  scheduled_time_local VARCHAR(64),
  model VARCHAR(64),
  prompt_version VARCHAR(64),
  summary_text CLOB,
  raw_json CLOB,
  structured_json CLOB,
  pushed TINYINT DEFAULT 0,
  push_enabled TINYINT DEFAULT 1,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Group targets are disabled in test-only mode but still need coverage for
-- normal target resolution and webhook state updates.
CREATE TABLE IF NOT EXISTS t_bot_group_info (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  group_id VARCHAR(128) NOT NULL,
  test_account TINYINT DEFAULT 0,
  active TINYINT DEFAULT 1,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uq_group_id UNIQUE (group_id)
);

-- User targets include `test_account`; this is the safety flag for local/manual
-- push testing.
CREATE TABLE IF NOT EXISTS t_bot_user_info (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id VARCHAR(128) NOT NULL,
  test_account TINYINT DEFAULT 0,
  active TINYINT DEFAULT 1,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uq_user_id UNIQUE (user_id)
);

-- Structured stock ideas generated upstream from market analyses.
CREATE TABLE IF NOT EXISTS t_trade_signals (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  signal_key VARCHAR(64) NOT NULL,
  idempotency_key CHAR(40) NOT NULL,
  analysis_id BIGINT NOT NULL,
  analysis_date VARCHAR(16) NOT NULL,
  analysis_slot VARCHAR(32) NOT NULL,
  market VARCHAR(16) DEFAULT 'TW',
  ticker VARCHAR(32) NOT NULL,
  name VARCHAR(128),
  signal_type VARCHAR(32) DEFAULT 'analysis_stock_watch',
  strategy_type VARCHAR(32) DEFAULT 'watch',
  direction VARCHAR(16) NOT NULL,
  confidence VARCHAR(16),
  entry_zone CLOB,
  invalidation CLOB,
  take_profit_zone CLOB,
  holding_horizon VARCHAR(64),
  rationale CLOB,
  risk_notes CLOB,
  source_event_ids CLOB,
  status VARCHAR(24) DEFAULT 'pending_review',
  raw_json CLOB,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uq_trade_signal_key UNIQUE (signal_key),
  CONSTRAINT uq_trade_signal_idempotency UNIQUE (idempotency_key)
);

CREATE TABLE IF NOT EXISTS t_macro_release_calendar (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  event_key CHAR(40) NOT NULL,
  source_id VARCHAR(64) NOT NULL,
  source_name VARCHAR(128) NOT NULL,
  indicator_code VARCHAR(64) NOT NULL,
  indicator_name VARCHAR(128) NOT NULL,
  period_label VARCHAR(64) NOT NULL,
  release_title CLOB NOT NULL,
  release_at_utc TIMESTAMP NOT NULL,
  release_at_taipei TIMESTAMP NOT NULL,
  release_timezone VARCHAR(64) DEFAULT 'America/New_York',
  importance TINYINT DEFAULT 3,
  reminder_date_taipei DATE NOT NULL,
  reminder_pushed TINYINT DEFAULT 0,
  reminder_pushed_at TIMESTAMP,
  reminder_push_status VARCHAR(32),
  reminder_push_error CLOB,
  source_url CLOB NOT NULL,
  raw_json CLOB,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uq_macro_release_event_key UNIQUE (event_key)
);
