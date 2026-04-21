# line-relay-service

Spring Boot 3 / Java 21 service that owns LINE webhook intake and outbound push for the news-collector stack. Phase 1 scope: webhook signature verification, event logging, and a `LinePushClient` for outbound messages. Database integration, dispatcher, and queue come in Phase 2–3.

## Requirements

- **JDK 21** (Amazon Corretto 21 recommended). `JAVA_HOME` must point to the JDK 21 installation before running the wrapper.
- Maven wrapper is included — no need to install Maven.

## Configuration

Set these environment variables (or put them in a `.env` loaded by your process manager):

| Variable | Required | Description |
|---|---|---|
| `LINE_CHANNEL_SECRET` | yes | LINE channel secret, used for webhook HMAC-SHA256 signature verification |
| `LINE_CHANNEL_ACCESS_TOKEN` | yes | LINE Messaging API bearer token for outbound pushes |
| `LINE_API_BASE` | no | Defaults to `https://api.line.me` |
| `PORT` | no | Defaults to `8080` |
| `LINE_PUSH_ENABLED` | no | Master push toggle. Default `false` — `LinePushClient` logs payloads and returns without calling LINE. Flip to `true` to actually send. |
| `LINE_RELAY_MYSQL_ENABLED` | no | Gate for MySQL-backed features (poller, repositories, `/admin/*`). Default `false`. |
| `LINE_RELAY_MYSQL_URL` | when MySQL on | JDBC URL, e.g. `jdbc:mysql://host:3306/news_relay?useSSL=false&serverTimezone=UTC` |
| `LINE_RELAY_MYSQL_USER` / `_PASSWORD` | when MySQL on | DB credentials |
| `LINE_RELAY_MYSQL_ANALYSIS_TABLE` | no | Defaults to `t_market_analyses` |
| `LINE_RELAY_MYSQL_GROUP_TABLE` | no | Defaults to `t_bot_group_info` |
| `LINE_RELAY_MYSQL_USER_TABLE` | no | Defaults to `t_bot_user_info` |

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

## Endpoints

### `GET /health`

Liveness probe. Returns JSON `{ "status": "ok", ... }`.

### `POST /webhook`

LINE webhook receiver.

- Verifies `X-Line-Signature` against the request body using HMAC-SHA256 and `LINE_CHANNEL_SECRET`.
- Returns `401 invalid_signature` on mismatch, `400 invalid_body` on malformed JSON, `200 accepted` on success.
- Parses and logs each event (`message`, `follow`, `unfollow`, `join`, `leave`, `memberJoined`, `memberLeft`). Events are not persisted yet — that's Phase 2.

### Admin endpoints (registered only when `LINE_RELAY_MYSQL_ENABLED=true`)

#### `GET /admin/list-targets`

Lists active groups + users the service would push to. Returns counts and the full list. Useful to verify the DB sees the same roster as the Python side.

#### `POST /admin/poll-market-analysis?date=YYYY-MM-DD&slot=pre_tw_open`

Fetches the latest `t_market_analyses` row matching `(date, slot)` and logs the resolved targets.

- If `LINE_PUSH_ENABLED=false` (default), the endpoint **does not call LINE** — it logs the summary preview and the target list.
- If `LINE_PUSH_ENABLED=true`, it pushes the summary to every active group + user.
- Parameters default to today's date and `pre_tw_open` when omitted.

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
- `WebhookController` — rejects missing/bad signature, accepts valid signature
- `LinePushClient` — payload shape, text truncation, multicast batching at 500, HTTP error propagation, toggle-off short-circuit
- `MarketAnalysisRepository` / `BotTargetRepository` — H2-backed: latest-row selection by `updated_at`, `active=1` filter
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
│   └── WebhookController.java      # POST /webhook
├── push/
│   ├── LinePushClient.java         # Outbound push / multicast (toggle-gated)
│   └── dto/                        # Request DTOs
├── db/                             # MySQL-gated (line.mysql.enabled=true)
│   ├── MarketAnalysis.java         # Row record
│   ├── MarketAnalysisRepository.java  # findLatest(date, slot)
│   ├── BotTarget.java              # (type, id) record
│   └── BotTargetRepository.java    # listActiveTargets()
├── market/
│   └── MarketAnalysisPoller.java   # Orchestrates fetch → targets → push
└── admin/
    └── AdminController.java        # /admin/list-targets, /admin/poll-market-analysis
```

## Roadmap

- **Phase 2 (current)** — MySQL read integration: fetch `t_market_analyses` + active targets from `t_bot_group_info` / `t_bot_user_info`, push behind `LINE_PUSH_ENABLED` toggle, exposed via `/admin/poll-market-analysis`. Webhook write-back + LINE URL cutover still pending.
- **Phase 3** — `t_push_queue` polling dispatcher; Python `weekly_summary` / `market_analysis` stop direct LINE calls.
- **Phase 4** — Remove `LinePushClient` and webhook service from the Python repo.

## Runtime verification checklist

To confirm push actually works end-to-end without hardcoding it on:

1. `LINE_RELAY_MYSQL_ENABLED=true` + MySQL env vars set, `LINE_PUSH_ENABLED=false`.
2. `GET /admin/list-targets` → confirm expected groups + users appear.
3. `POST /admin/poll-market-analysis` → confirm logs show the summary preview and every resolved target (no HTTP call to LINE yet).
4. Flip `LINE_PUSH_ENABLED=true`, restart, `POST /admin/poll-market-analysis` again → confirm LINE delivery, then flip back to `false`.
