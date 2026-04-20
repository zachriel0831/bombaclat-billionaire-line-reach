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

## Tests

```bash
./mvnw test
```

Covers:
- `SignatureVerifier` — correct signature, tampered body, wrong secret, null/blank signature
- `WebhookController` — rejects missing/bad signature, accepts valid signature
- `LinePushClient` — payload shape, text truncation, multicast batching at 500, HTTP error propagation

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
└── push/
    ├── LinePushClient.java         # Outbound push / multicast
    └── dto/                        # Request DTOs
```

## Roadmap

- **Phase 2** — MySQL integration; webhook persists `t_bot_group_info`, `t_bot_user_info`; LINE webhook URL cuts over from Python to Java.
- **Phase 3** — `t_push_queue` polling dispatcher; Python `weekly_summary` / `market_analysis` stop direct LINE calls.
- **Phase 4** — Remove `LinePushClient` and webhook service from the Python repo.
