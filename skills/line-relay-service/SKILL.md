---
name: line-relay-service
description: Maintain, debug, document, and operate this Spring Boot LINE relay service. Use when working in the line-relay-service repository on LINE webhook intake, HMAC signature verification, t_bot_group_info/t_bot_user_info target sync, t_market_analyses polling, scheduled Taiwan pre-open pushes, runtime push mode commands, ngrok/local startup, or LINE Messaging API delivery failures.
---

# Line Relay Service

## Quick Orientation

This repo is a Java 21 / Spring Boot service that receives LINE webhook events at `/webhook`, records active LINE users/groups in MySQL, reads market-analysis rows from `t_market_analyses`, and pushes summaries through LINE Messaging API.

For the full human-facing flow, read `docs/LINE_RELAY_FLOW.md`. For a compact maintainer map, read `references/service-map.md` when changing business flow or debugging production-like behavior.

## Default Workflow

1. Inspect `src/main/resources/application.yml` and local `.env` behavior first. The app imports `optional:file:.env[.properties]`; do not print secret values.
2. Confirm which process owns the webhook path before debugging LINE delivery. The Java service listens on `/webhook`; older Python relay processes may listen on `/callback`.
3. Use these smoke checks:

```powershell
Invoke-RestMethod http://localhost:8080/health
Invoke-RestMethod http://localhost:8080/admin/list-targets
```

4. Run tests with JDK 21:

```powershell
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
.\mvnw.cmd test
```

5. If packaging for `java -jar`, run:

```powershell
.\mvnw.cmd package -DskipTests
```

6. To restart in a new visible PowerShell window with logs streaming and tee'd to `logs/line-relay.log`, use the launcher script:

```powershell
Start-Process powershell -ArgumentList '-NoExit','-NoProfile','-ExecutionPolicy','Bypass','-File','d:\work_space\claude-box\workspace\line-relay-service\scripts\run-line-relay.ps1'
```

Do NOT try to inline the JDK env + `mvnw spring-boot:run` directly through `Start-Process cmd /k "…"` or `Start-Process powershell -Command "…"`. Embedded quotes / `&&` / `$env:` / `|` get mangled by `-ArgumentList` quoting and the child window silently no-ops, leaving only `conhost.exe` as a child and no `logs\line-relay.log`. Always go through the on-disk launcher.

## Important Runtime Rules

- `LINE_PUSH_ENABLED` controls normal scheduled/admin pushes.
- Market-analysis LINE links should use the public frontend base:
  `LINE_PUBLIC_ANALYSIS_BASE_URL=https://011b-220-141-219-53.ngrok-free.app/analyses`.
- Before pushing analysis after an ngrok outage, verify the fixed frontend URL and
  the exact detail URL, then confirm the analysis row is not garbled and has
  `push_enabled = 1`.
- `LINE_PUSH_TEST_ONLY=true` sends only to active `t_bot_user_info` rows where `test_account = 1`; groups are excluded.
- `LINE_PUSH_RATE_LIMIT_ENABLED=true` turns on Redis-backed per-target daily caps before the LINE API call is made.
- Redis caps are keyed by `PushMessageType`: `PUBLIC_ANALYSIS` defaults to 2/day and `STOCK_QUERY` defaults to 3/day.
- Market-analysis pushes must pass the `garbled_summary` quality gate before target resolution. Rows whose `summary_text` is mostly `?` or Unicode replacement characters are skipped and must be repaired/regenerated first.
- Webhook commands are runtime-only and reset on restart:
  - `測試西卡卡` enables push and forces test-only mode.
  - `關閉西卡卡` disables normal pushes.
  - `西卡卡推送` immediately pushes the latest `t_market_analyses` row to active test users only, bypassing the normal push toggle and not marking anything as pushed.
  - `股票 <代號>`, `個股 <代號>`, `查股 <代號>`, and `西卡卡股票 <代號>` reply with the latest active `t_trade_signals` row.
  - `股價分析 <代號或名稱>` (half-width or full-width space required between prefix and content) sends the entire trimmed remainder as a free-form query (e.g. `股價分析 Rocket Lab (RKLB)`) to `news-platform-api` `/api/stock-signals/generate`. The local Redis cache is bypassed for this route because the cache key normalizer would collapse "Rocket Lab (RKLB)" and "RKLB" together. The reply uses the same `STOCK_QUERY` Redis quota and renders with the stock-analysis template under `skills/line-brief-format-skill/line-stock-analysis.md`.
- Schedule-specific rules:
  - TW open + relevant U.S. close session open: weekdays push `pre_tw_open`; Saturdays push `us_close` after the Codex/data-collecting guard has had time to write the row.
  - TW closed + relevant U.S. close session open: push `us_close`.
  - TW open + relevant U.S. close session closed: push `pre_tw_open`; upstream normally has no `us_close` context for that day.
  - TW closed + relevant U.S. close session closed: push `macro_daily`.
  - Sundays push only the weekly summary: `weekly_tw_preopen`.
  - The 00:00 Taipei cleanup disables old unpushed rows with `pushed = 0` by setting `push_enabled = 0`.
  - Normal scheduled/admin pushes mark `pushed = 1` after at least one successful delivery.
- Redis-specific rule:
  - The global push cap is keyed by LINE target ID, Taipei date, and `PushMessageType`, so public analysis and stock-query replies do not consume each other's quota.
- LINE Console local testing should use `ngrok http 8080` and webhook URL `https://<ngrok>/webhook`.
- `/admin/*` needs `X-Line-Admin-Key`; if local `.env` has no admin key, use a
  temporary process env key for manual ops rather than printing or committing it.

## Coding Guidance

- Keep webhook signature verification in `WebhookController` before JSON parsing.
- Keep event state extraction and command handling in `WebhookEventProcessor`; avoid pushing directly from the controller.
- Keep target filtering in `BotTargetRepository`; this centralizes `test_account` and active-row behavior.
- Keep LINE HTTP calls inside `LinePushClient`; callers should not build raw LINE request JSON.
- When adding scheduled pushes, add a named slot constant in `MarketAnalysisScheduler`, reuse `MarketAnalysisPoller.pollOnce`, and cover with tests.
- When changing `t_market_analyses` selection, remember normal queries ignore rows with `push_enabled = 0`.
- When changing SQL behavior, update H2 schema/data tests under `src/test/resources`.

## Troubleshooting Checklist

- No webhook logs: verify LINE Console URL is `/webhook`, ngrok forwards to `8080`, and signature uses the same channel secret.
- Webhook returns 401: check `LINE_CHANNEL_SECRET`; do not parse or reserialize the body before verifying.
- Push returns 403: check channel access token, token reissue state, Messaging API permissions, and whether the token belongs to the same channel receiving the webhook.
- Targets empty: check `LINE_RELAY_MYSQL_ENABLED`, DB credentials, `active`, and `test_account`.
- Push skipped with `garbled_summary`: inspect the selected `t_market_analyses.summary_text`; repair or regenerate it before pushing.
- Scheduled push did not run: check `LINE_SCHEDULE_ENABLED`, cron values, `LINE_SCHEDULE_ZONE`, and whether the delivery cron fired before the analysis guard finished writing the row.
