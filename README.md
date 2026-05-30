# line-relay-service

Spring Boot 3 / Java 21 service that owns LINE webhook intake and outbound push for the news-collector stack. Phase 1 scope: webhook signature verification, event logging, and a `LinePushClient` for outbound messages. Database integration, dispatcher, and queue come in Phase 2–3.

## Requirements

- **JDK 21** (Amazon Corretto 21 recommended). `JAVA_HOME` must point to the JDK 21 installation before running the wrapper.
- Maven wrapper is included — no need to install Maven.

## Maintainer Docs

- [LINE relay flow](docs/LINE_RELAY_FLOW.md) — startup, webhook, push, scheduling, and troubleshooting flow.
- [Repo skill](skills/line-relay-service/SKILL.md) — compact Codex/agent operating guide for this service.

## Configuration

Set these environment variables (or put them in a `.env` loaded by your process manager):

| Variable | Required | Description |
|---|---|---|
| `LINE_CHANNEL_SECRET` | yes | LINE channel secret, used for webhook HMAC-SHA256 signature verification |
| `LINE_CHANNEL_ACCESS_TOKEN` | yes | LINE Messaging API bearer token for outbound pushes |
| `LINE_API_BASE` | no | Defaults to `https://api.line.me` |
| `LINE_PUBLIC_ANALYSIS_BASE_URL` | no | Public frontend analyses list URL, e.g. `https://example.ngrok-free.app/analyses`; when set, LINE market-analysis pushes include a short excerpt and `/analyses/{id}` link. |
| `LINE_PLATFORM_ENABLED` | no | Enables real-time stock-query calls to `news-platform-api`. Default `true`. |
| `LINE_PLATFORM_BASE_URL` | no | Middle-office API base URL. Default `http://localhost:8081`. |
| `LINE_PLATFORM_STOCK_SIGNAL_PATH` | no | Real-time stock signal generation path. Default `/api/stock-signals/generate`. |
| `PORT` | no | Defaults to `8080` |
| `LINE_PUSH_ENABLED` | no | Master push toggle. Default `true` — set to `false` to log payloads without calling LINE. |
| `LINE_PUSH_TEST_ONLY` | no | Target safety toggle. Default `true` — only push active `t_bot_user_info` rows where `test_account = 1`. Set `false` to include all active groups and users. |
| `LINE_PUSH_RATE_LIMIT_ENABLED` | no | Enables Redis-backed per-target daily caps. |
| `LINE_PUSH_PUBLIC_ANALYSIS_DAILY_MAX_PER_TARGET` | no | Daily cap for public analysis pushes. Default `2`. |
| `LINE_PUSH_STOCK_QUERY_DAILY_MAX_PER_TARGET` | no | Daily cap for stock-query replies. Default `3`. |
| `LINE_PUSH_RATE_LIMIT_ZONE` | no | Business-date timezone for Redis counters. Default `Asia/Taipei`. |
| `LINE_PUSH_RATE_LIMIT_KEY_PREFIX` | no | Redis key prefix. Default `line:push:rate-limit`. |
| `LINE_STOCK_SIGNAL_CACHE_ENABLED` | no | Enables Redis cache for successful stock-query replies. Default `true`. |
| `LINE_STOCK_SIGNAL_CACHE_KEY_PREFIX` | no | Redis key prefix for cached stock-query replies. Default `line:stock-signal:cache`. |
| `LINE_STOCK_SIGNAL_CACHE_TTL` | no | Cache TTL for successful stock-query replies. Default `6h`. |
| `SPRING_DATA_REDIS_HOST` / `_PORT` / `_TIMEOUT` | when rate limit on | Redis connection settings. |
| `LINE_RELAY_MYSQL_ENABLED` | no | Gate for MySQL-backed features (poller, repositories, `/admin/*`). Default `false`. |
| `LINE_RELAY_MYSQL_URL` | when MySQL on | JDBC URL, e.g. `jdbc:mysql://host:3306/news_relay?useSSL=false&serverTimezone=UTC` |
| `LINE_RELAY_MYSQL_USER` / `_PASSWORD` | when MySQL on | DB credentials |
| `LINE_RELAY_MYSQL_ANALYSIS_TABLE` | no | Defaults to `t_market_analyses` |
| `LINE_RELAY_MYSQL_GROUP_TABLE` | no | Defaults to `t_bot_group_info` |
| `LINE_RELAY_MYSQL_USER_TABLE` | no | Defaults to `t_bot_user_info` |
| `LINE_RELAY_MYSQL_TRADE_SIGNAL_TABLE` | no | Defaults to `t_trade_signals`; retained for repository/admin/debug code. LINE stock-query replies call `news-platform-api` instead of reading this table directly. |
| `LINE_SCHEDULE_TW_MARKET_HOLIDAYS` | no | Comma-separated `YYYY-MM-DD` TW market holidays used by the public-analysis routing matrix. |
| `LINE_SCHEDULE_US_MARKET_HOLIDAYS` | no | Comma-separated `YYYY-MM-DD` U.S. market holidays. The checked U.S. date is Taiwan local date minus one day. |

A `.env.example` is included as a template. Do not commit a filled `.env`.

## Build & Run

```bash
# Build (runs tests)
./mvnw clean package

# Run locally
LINE_CHANNEL_SECRET=... LINE_CHANNEL_ACCESS_TOKEN=... ./mvnw spring-boot:run

# Or run the packaged jar
LINE_CHANNEL_SECRET=... LINE_CHANNEL_ACCESS_TOKEN=... \
  java -jar target/line-relay-service-0.1.0-SNAPSHOT.jar
```

Windows PowerShell:

```powershell
$env:LINE_CHANNEL_SECRET="..."
$env:LINE_CHANNEL_ACCESS_TOKEN="..."
./mvnw.cmd spring-boot:run
```

Local smoke run without real LINE credentials:

```powershell
$env:SPRING_PROFILES_ACTIVE="local"
./mvnw.cmd spring-boot:run
```

The `local` profile uses dummy LINE credentials and keeps `LINE_PUSH_ENABLED=false`.
Use real `LINE_CHANNEL_SECRET` and `LINE_CHANNEL_ACCESS_TOKEN` for webhook or push testing.

## Endpoints

### `GET /health`

Liveness probe. Returns JSON `{ "status": "ok", ... }`.

### `POST /webhook`

LINE webhook receiver.

- Verifies `X-Line-Signature` against the request body using HMAC-SHA256 and `LINE_CHANNEL_SECRET`.
- Returns `401 invalid_signature` on mismatch, `400 invalid_body` on malformed JSON, `200 accepted` on success.
- Parses each event (`message`, `follow`, `unfollow`, `join`, `leave`, `memberJoined`, `memberLeft`) and, when `LINE_RELAY_MYSQL_ENABLED=true`, upserts group/user state into `t_bot_group_info` / `t_bot_user_info`:
  - `follow` / `message` / `join` / `memberJoined` → `active = 1`
  - `unfollow` / `leave` → `active = 0`
  - `test_account` is preserved on updates (never downgraded).
- Text messages can also control runtime push mode by prefix:
  - `測試西卡卡` → enable push and force test-only targets.
  - `關閉西卡卡` → disable push.
  - `西卡卡推送` → immediately push the latest `t_market_analyses` row to active test users only, without marking it as pushed.
  - `股票 2330`, `個股 2330`, `查股 NVDA`, or `西卡卡股票 2330` → reply to the source user/group with the latest active `t_trade_signals` row, using the `STOCK_QUERY` Redis quota.
  - `股價分析 <代號或名稱>` (例如 `股價分析 Rocket Lab (RKLB)`、`股價分析 2330`) → 必須在前綴與內容之間有半形或全形空白才會匹配。後段整段 trim 後直接丟給 `news-platform-api`,跳過本地 Redis cache(避免名稱被 ticker key 正規化壓縮)。仍受 `STOCK_QUERY` 配額限制。
- Response body includes `events`, `users`, and `groups` counts for observability.
- Current stock-query behavior:
  - `股票 2330`, `個股 2330`, `查股 NVDA`, and `西卡卡股票 2330` first reuse a successful Redis-cached reply when present, otherwise call `news-platform-api` `/api/stock-signals/generate` for a fresh model response and cache that successful reply. They no longer read `t_trade_signals` directly.
  - `股價分析 <代號或名稱>` always calls `news-platform-api` (cache bypassed) so names like `Rocket Lab (RKLB)` can reach the platform without being normalized into a different ticker key.
- With MySQL disabled, events are logged only; no rows are written.

### Admin endpoints (registered only when `LINE_RELAY_MYSQL_ENABLED=true`)

#### `GET /admin/list-targets`

Lists the current push targets. With `LINE_PUSH_TEST_ONLY=true`, pushes are limited to active test users from `t_bot_user_info` (`active = 1 AND test_account = 1`); groups are not pushed. Set `LINE_PUSH_TEST_ONLY=false` to include all active groups and users.

#### `POST /admin/poll-market-analysis?date=YYYY-MM-DD&slot=pre_tw_open`

Fetches the latest `t_market_analyses` row matching `(date, slot)` and logs the resolved targets.

- If `LINE_PUSH_ENABLED=false`, the endpoint **does not call LINE** — it logs the summary preview and the target list.
- If `LINE_PUSH_ENABLED=true`, it pushes either the full summary or, when `LINE_PUBLIC_ANALYSIS_BASE_URL` is set, a short excerpt plus the public detail link to the targets selected by `LINE_PUSH_TEST_ONLY`.
- Parameters default to today's date and `pre_tw_open` when omitted.

### Scheduled market analysis pushes

When `LINE_RELAY_MYSQL_ENABLED=true` and `LINE_SCHEDULE_ENABLED=true`, the service pushes stored market analyses on this default Asia/Taipei schedule:

- TW open + relevant U.S. close session open: `pre_tw_open` on weekdays; Saturday `05:30` pushes `us_close`
- TW closed + relevant U.S. close session open: `us_close`
- TW open + relevant U.S. close session closed: `pre_tw_open`
- TW closed + relevant U.S. close session closed: `macro_daily`
- Sunday `05:10`: `weekly_tw_preopen`; no daily market-analysis push
- `00:00` daily: disables stale rows where `analysis_date` is before today, `pushed = 0`, and `push_enabled = 1`.

Successful scheduled/admin delivery marks the selected row as `pushed = 1` after at least one target receives it.

Override with `LINE_SCHEDULE_US_CLOSE_CRON`, `LINE_SCHEDULE_PRE_TW_OPEN_CRON`, `LINE_SCHEDULE_WEEKLY_TW_PREOPEN_CRON`, `LINE_SCHEDULE_DISABLE_STALE_UNPUSHED_CRON`, `LINE_SCHEDULE_TW_MARKET_HOLIDAYS`, `LINE_SCHEDULE_US_MARKET_HOLIDAYS`, or `LINE_SCHEDULE_ZONE`. Keep the LINE delivery cron after the Codex/data-collecting guard window so repaired rows exist before polling.

## Exposing the webhook

LINE requires HTTPS. For local development, expose the port via ngrok or cloudflared and set the webhook URL in the LINE Developer Console:

```bash
ngrok http 8080
# Webhook URL: https://<random>.ngrok-free.app/webhook
```

## Programmatic push

Inject `LinePushClient` and call:

```java
lineClient.push("U1234567890abcdef...", "Hello from Java service");
lineClient.multicast(List.of("U1...", "U2..."), "Broadcast message");
```

- `push(targetId, text)` — user, group, or room ID.
- `multicast(userIds, text)` — user IDs only (LINE restriction); auto-batches at 500 per request.
- Text is truncated to 5000 characters (LINE hard limit).
- When `LINE_PUSH_ENABLED=false` (default), both methods log `[PUSH_DISABLED]` and return without hitting LINE. `isPushEnabled()` reports the current state.

## Tests

```bash
./mvnw test
```

Covers:
- `SignatureVerifier` — correct signature, tampered body, wrong secret, null/blank signature
- `WebhookController` — rejects missing/bad signature, accepts valid signature, delegates to processor
- `WebhookEventProcessor` — follow/unfollow/join/leave/memberJoined/room fallback/batch state resolution/null-source tolerance/no-MySQL mode
- `LinePushClient` — payload shape, text truncation, multicast batching at 500, HTTP error propagation, toggle-off short-circuit
- `MarketAnalysisRepository` / `BotTargetRepository` — H2-backed: latest-row selection by `updated_at`, `active=1` filter, upsert insert/update/test_account preservation/blank-id noop
- `MarketAnalysisPoller` — Mockito-based: `no_analysis` / `no_targets` / toggle-off skip / toggle-on per-target push / partial-failure counting

## Project layout

```
src/main/java/com/zack/linerelay/
├── LineRelayApplication.java       # Spring Boot entrypoint
├── config/
│   ├── LineProperties.java         # @ConfigurationProperties for line.*
│   └── RestClientConfig.java       # Pre-configured RestClient for LINE API
├── health/
│   └── HealthController.java       # GET /health
├── webhook/
│   ├── SignatureVerifier.java      # HMAC-SHA256 verification
│   ├── WebhookController.java      # POST /webhook (thin; delegates parsing to processor)
│   └── WebhookEventProcessor.java  # event → bot target state upserts
├── push/
│   ├── LinePushClient.java         # Outbound push / multicast (toggle-gated)
│   └── dto/                        # Request DTOs
├── db/                             # MySQL-gated (line.mysql.enabled=true)
│   ├── MarketAnalysis.java         # Row record
│   ├── MarketAnalysisRepository.java  # findLatest(date, slot)
│   ├── BotTarget.java              # (type, id) record
│   └── BotTargetRepository.java    # listActiveTargets()
├── market/
│   └── MarketAnalysisPoller.java   # Orchestrates fetch → targets → push and stale-row cleanup
└── admin/
    └── AdminController.java        # /admin/list-targets, /admin/poll-market-analysis
```

## Roadmap

- **Current** — Java service owns webhook signature verification, target table upserts, active/test-only target resolution, scheduled market-analysis pushes, and runtime LINE commands.
- **Next** — Decide whether a durable `t_push_queue` dispatcher is still needed or whether direct scheduled/manual Java pushes are enough.
- **Later** — Remove duplicated LINE webhook/push responsibilities from the Python relay once LINE Console is permanently pointed at Java `/webhook`.

## Runtime verification checklist

To confirm push actually works end-to-end without hardcoding it on:

1. `LINE_RELAY_MYSQL_ENABLED=true` + MySQL env vars set, `LINE_PUSH_ENABLED=false`.
2. `GET /admin/list-targets` → confirm expected groups + users appear.
3. `POST /admin/poll-market-analysis` → confirm logs show the summary preview and every resolved target (no HTTP call to LINE yet).
4. Flip `LINE_PUSH_ENABLED=true`, restart, `POST /admin/poll-market-analysis` again → confirm LINE delivery, then flip back to `false`.
