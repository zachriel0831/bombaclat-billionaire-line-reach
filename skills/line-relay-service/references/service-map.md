# Service Map

## Entry Points

- `LineRelayApplication` enables Spring Boot configuration properties and scheduling.
- `GET /health` returns a simple liveness response.
- `POST /webhook` verifies LINE signatures, parses events, and delegates event handling.
- `GET /admin/list-targets` lists resolved push targets when MySQL features are enabled.
- `POST /admin/poll-market-analysis` manually runs the date/slot market-analysis push path.

## Data Flow

1. LINE sends webhook JSON to `/webhook` with `X-Line-Signature`.
2. `SignatureVerifier` computes HMAC-SHA256 over the raw body and compares with the header.
3. `WebhookEventProcessor` extracts user/group state and runtime commands.
4. `BotTargetRepository` upserts users/groups and later resolves active targets.
5. `MarketAnalysisRepository` fetches market-analysis rows from `t_market_analyses`.
6. `MacroReleaseCalendarRepository` reads market release reminders from `t_macro_release_calendar`.
7. `StockQueryService` handles stock commands by reading `TradeSignalRepository` from `t_trade_signals`.
8. `MarketAnalysisPoller` rejects likely mojibake summaries with `garbled_summary`, then builds text and calls `LinePushClient`.
9. `MacroCalendarReminderService` sends one aggregated reminder for tomorrow's U.S. macro releases and watched heavyweight earnings rows.
10. `LinePushClient` enforces the optional Redis daily cap by message type, then sends to LINE `/v2/bot/message/push` or `/v2/bot/message/multicast`.

## Tables

- `t_market_analyses`: source rows for pushed summaries; latest rows are selected by `updated_at DESC, id DESC`, normal push selection ignores `push_enabled = 0`, likely mojibake `summary_text` values are skipped with `garbled_summary`, and successful scheduled/admin sends mark `pushed = 1`.
- `t_trade_signals`: source rows for stock-query replies; only `pending_review`, `new`, and `watch` statuses are returned.
- `t_macro_release_calendar`: market release-calendar rows prepared by `data-collecting`; U.S. macro rows use normal indicator codes and heavyweight earnings rows use `indicator_code=earnings_<symbol>`. Java reads `reminder_date_taipei = today AND reminder_pushed = 0`, groups macro and earnings rows, and marks sent rows after at least one target receives the reminder.
- `t_bot_user_info`: LINE users; `active = 1` marks pushable users; `test_account = 1` marks safe test recipients.
- `t_bot_group_info`: LINE groups/rooms; only used when test-only mode is off.
- Redis keys: `line:push:rate-limit:<YYYY-MM-DD>:<PushMessageType>:<targetId>` by default when global push caps are enabled.
- Redis daily caps: `PUBLIC_ANALYSIS = 2`, `STOCK_QUERY = 3`, `MACRO_CALENDAR = 3`.

## Known Local Pitfall

The old Python relay can run on port `18090` and expose `/callback`. This Java service runs on `8080` and exposes `/webhook`. If ngrok points to `18090` or LINE Console uses `/callback`, command messages will not hit the Java processor.

When a market-analysis row reaches LINE as repeated `?`, treat it as data corruption in `t_market_analyses.summary_text` first. The relay should now skip new rows with the same signature before any target lookup or LINE API call.

## Schedule Rules

- TW open + relevant U.S. close session open: weekdays use `pre_tw_open`; Saturday `05:30 Asia/Taipei` uses `us_close`.
- TW closed + relevant U.S. close session open: `us_close`.
- TW open + relevant U.S. close session closed: `pre_tw_open`.
- TW closed + relevant U.S. close session closed: `macro_daily`.
- Sunday `05:10 Asia/Taipei`: `weekly_tw_preopen` only.
- Daily `08:00 Asia/Taipei`: market release-calendar reminder for tomorrow Taiwan-time CPI/PPI/nonfarm payrolls/retail sales releases and watched `earnings_<symbol>` rows.
- `00:00 Asia/Taipei`: set `push_enabled = 0` for rows before today where `pushed = 0`.
- Local Codex-guard mode: keep LINE delivery after the guard window; if the guard repairs `t_market_analyses` after the delivery cron, the poller sees `no_analysis` and will not retry automatically.
