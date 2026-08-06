# LINE Relay Service Flow

## Purpose

`line-relay-service` is the Java owner for LINE webhook intake and market-analysis push delivery. It keeps LINE user/group target rows current, reads prepared summaries from MySQL, and sends LINE push messages under explicit safety toggles.

If Redis rate limiting is enabled, the same service also enforces a shared per-target daily push cap before any LINE HTTP call is made. Quotas are split by `PushMessageType`: `PUBLIC_ANALYSIS` currently allows 2 per target per day and `MACRO_CALENDAR` allows 3.

## Local Startup

1. Put credentials and DB settings in `.env`. The app loads it through `spring.config.import=optional:file:.env[.properties]`.
2. Build or run with JDK 21.

```powershell
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
.\mvnw.cmd test
.\mvnw.cmd package -DskipTests
java -jar target\line-relay-service-0.1.0-SNAPSHOT.jar
```

3. Confirm startup:

```powershell
Invoke-RestMethod http://localhost:8080/health
Invoke-RestMethod http://localhost:8080/admin/list-targets
```

## Webhook Flow

1. LINE sends `POST /webhook` with raw JSON and `X-Line-Signature`.
2. `WebhookController` verifies the raw body before parsing JSON.
3. `WebhookEventProcessor` reads each event:
   - `follow`, `message`, `join`, `memberJoined` mark users/groups active.
   - `unfollow`, `leave` mark users/groups inactive.
   - `memberJoined` also records joined member user IDs.
4. If MySQL-backed repositories are available, `BotTargetRepository` upserts rows into `t_bot_user_info` and `t_bot_group_info`.

## Runtime Commands

Text messages are matched by prefix:

- `測試西卡卡`: set runtime mode to push enabled and test-only.
- `關閉西卡卡`: disable normal push delivery.
- `西卡卡推送`: fetch the latest `t_market_analyses` row and immediately push it to active test users only.
- Stock-query phrases such as `股票 2330`, `查股 NVDA`, and `股價分析 Rocket Lab (RKLB)` are ordinary chat text; there is no stock-analysis command handler.

The command state is in memory. Restarting the service reloads initial values from `.env` / `application.yml`.

## Stock Query Flow

Removed. `WebhookEventProcessor` logs stock-like chat text as a normal LINE message; there is no stock-query command handler, platform stock-signal client, stock-signal cache, or `STOCK_QUERY` quota.

## Market Push Flow

Scheduled or admin-triggered pushes use the same path:

1. `MarketAnalysisScheduler` or `AdminController` calls `MarketAnalysisPoller.pollOnce`.
2. `MarketAnalysisRepository.findLatest(date, slot)` selects the newest matching analysis where `push_enabled = 1`.
3. `MarketAnalysisPoller` rejects likely mojibake before target resolution. Summaries with repeated `?` blocks or high `?` / Unicode replacement-char density return `skipReason=garbled_summary` and do not call LINE.
4. `BotTargetRepository.listActiveTargets` applies current target rules.
5. `MarketAnalysisPoller` formats the LINE text. When `LINE_PUBLIC_ANALYSIS_BASE_URL` is set, it sends a short excerpt plus a detail URL like `/analyses/{id}`; daily reports that start with a standalone `今日一句話` heading combine that heading with the following sentence before trimming. Otherwise it falls back to `<date>` plus full summary text.
6. `LinePushClient.push(PUBLIC_ANALYSIS, ...)` sends to each resolved target when `LINE_PUSH_ENABLED=true`.
   - When Redis rate limiting is enabled, each LINE target ID is capped by Taipei business date and message type before the HTTP request is sent.
7. If at least one target receives the message, `MarketAnalysisRepository.markPushed` updates that row to `pushed = 1`.

Manual command push is different on purpose:

1. `西卡卡推送` calls `MarketAnalysisPoller.pushLatestToTestAccountsNow`.
2. The repository selects the newest row across all dates/slots.
3. The same `garbled_summary` quality gate runs before selecting test users.
4. Only `t_bot_user_info.active = 1 AND test_account = 1` users are selected.

## Inbound Protection

`LINE_SECURITY_ENABLED=true` adds the minimum public-edge guardrails:

- `POST /webhook` is rate limited per remote address before controller work. LINE HMAC signature verification remains the authentication layer.
- `/admin/*` requires `X-Line-Admin-Key` to match one value from `LINE_ADMIN_API_KEYS`.
- Missing admin keys fail closed with HTTP 503, invalid keys return 401, and rate-limited calls return 429.
5. `LinePushClient.pushIgnoringToggle(PUBLIC_ANALYSIS, ...)` sends even if normal push is off.
   - The master push toggle is bypassed here, but Redis rate limiting still applies.
6. No pushed flag or queue state is updated.

## Redis Quota Keys

The Redis key format is:

```text
line:push:rate-limit:<yyyy-MM-dd>:<PushMessageType>:<targetId>
```

Current daily limits:

- `PUBLIC_ANALYSIS`: `2`
- `MACRO_CALENDAR`: `3`

## Default Schedules

With `LINE_RELAY_MYSQL_ENABLED=true` and `LINE_SCHEDULE_ENABLED=true`:

- TW open + relevant U.S. close session open: `pre_tw_open` on weekdays; Saturday `05:30 Asia/Taipei` pushes `us_close`
- TW closed + relevant U.S. close session open: `us_close`
- TW open + relevant U.S. close session closed: `pre_tw_open`
- TW closed + relevant U.S. close session closed: `macro_daily`
- Sunday `05:10 Asia/Taipei`: `weekly_tw_preopen`; daily market-analysis push is skipped
- Daily `08:00 Asia/Taipei`: send one aggregated market release-calendar reminder for rows in `t_macro_release_calendar` whose `reminder_date_taipei` is today, grouping U.S. macro rows and `earnings_<symbol>` heavyweight earnings rows
- `00:00 Asia/Taipei`: disable stale rows where `analysis_date` is before today, `pushed = 0`, and `push_enabled = 1`.

Override using `LINE_SCHEDULE_US_CLOSE_CRON`, `LINE_SCHEDULE_PRE_TW_OPEN_CRON`, `LINE_SCHEDULE_WEEKLY_TW_PREOPEN_CRON`, `LINE_SCHEDULE_MACRO_CALENDAR_REMINDER_CRON`, `LINE_SCHEDULE_DISABLE_STALE_UNPUSHED_CRON`, `LINE_SCHEDULE_TW_MARKET_HOLIDAYS`, `LINE_SCHEDULE_US_MARKET_HOLIDAYS`, and `LINE_SCHEDULE_ZONE`. In local Codex-guard mode, schedule LINE delivery after the guard has time to create or repair the row; otherwise the poller can see `no_analysis`.

## LINE Console Setup

For this Java service:

```powershell
ngrok http 8080
```

Set LINE Console Webhook URL to:

```text
https://<ngrok-host>/webhook
```

Do not point this service to the older Python path:

```text
https://<ngrok-host>/callback
```

If ngrok forwards to `18090`, requests are going to the Python relay, not this Java service.

## Common Failures

- No `line_message_received` log: webhook URL or ngrok port is wrong.
- `401 invalid_signature`: channel secret mismatch, missing signature, or raw body changed before verification.
- Push 403 from LINE: access token is invalid, reissued, from another channel, or lacks Messaging API access.
- Redis rate limit errors: if `LINE_PUSH_RATE_LIMIT_ENABLED=true`, confirm Redis is reachable at the configured `SPRING_DATA_REDIS_*` host/port.
- No targets: test mode is on but no active `test_account = 1` user exists.
- `garbled_summary`: the selected `t_market_analyses.summary_text` already contains mojibake; repair or regenerate the row before pushing.
- Scheduled push appears skipped: no matching `analysis_date` / `analysis_slot` row exists, the latest row has `push_enabled = 0`, or the LINE cron fired before the Codex/data-collecting guard finished writing the row.
