# Session Handoff - 2026-05-04

這份文件用來把目前 Codex session 的上下文交接給下一個新 session。新 session 請先讀本檔，再看實際程式碼與 `git status`，以 repo 內容為準。

## Repo

主要 repo：

```text
D:\work_space\claude-box\workspace\line-relay-service
```

相關資料 repo：

```text
D:\work_space\stock\data-collecting
```

## 重要提醒

- 不要把 LINE channel secret、LINE channel access token、DB 密碼、Redis 密碼寫進文件或 commit。
- 目前 worktree 有大量既有變更，新 session 不要任意 revert。
- 服務啟動、ngrok、LINE Console webhook URL 都可能是本機運行狀態，請重新確認，不要只憑這份文件判斷。
- 若要改資料表欄位，請同時確認實際 DB schema、測試 schema、文件與兩個 repo 的寫入邏輯。

## 系統邊界

### line-relay-service

Java / Spring Boot 服務，主要負責：

- LINE webhook callback。
- LINE push / multicast。
- LINE 文字指令處理。
- 從 MySQL 讀取推送目標。
- 從 MySQL 讀取市場分析與個股訊號。
- Redis 推送限頻。

目前已知讀取資料表：

- `t_market_analyses`
- `t_bot_user_info`
- `t_bot_group_info`
- `t_trade_signals`

目前已知 HTTP 端點：

- `GET /health`
- `POST /webhook`
- `GET /admin/list-targets`
- `POST /admin/poll-market-analysis`

注意：`/admin/*` 不應直接公開給外部流量。

### data-collecting

資料收集與分析 repo，主要負責：

- 抓新聞與市場資料。
- 寫入 `t_relay_events`。
- 產生 `t_market_analyses`。
- 從分析結果衍生 `t_trade_signals`。

它的 HTTP server 偏內部操作與資料寫入，不建議直接當公開前端 API。

## LINE 推送與指令

目前討論與實作方向：

- `LINE_PUSH_ENABLED=true` 時允許推送。
- 測試模式只推 `t_bot_user_info.test_account = 1` 的測試帳號。
- 正式模式除了測試帳號外，也可以推一般啟用帳號與群組。
- `t_bot_user_info` / `t_bot_group_info` 是 LINE 推送目標來源。
- LINE webhook 會處理加入好友、群組與訊息事件，並印出傳入訊息日誌以便 debug。

關鍵字：

- `測試西卡卡`：切換為測試模式。
- `關閉西卡卡`：關閉所有推送開關。
- `西卡卡推送`：立即抓 `t_market_analyses` 最新一筆，推送到測試帳號，不標記 `pushed`。

LINE 不能任意「收回」已推送訊息。Messaging API 通常只能 push/reply，不等同於使用者端的收回功能。

## 市場分析推送規則

最新需求方向請以程式碼為準，但目前討論過的目標邏輯是：

- 週一到週五：推台股早盤分析。
- 週六：推美股收盤分析。
- 週日：推周總結。
- 捨棄一般「美股盤後分析每日推送」，目前一天只推一次主要分析。

較複雜的休市邏輯：

- TW 休市、US 有交易：只做 `us_close`。
- US 休市、TW 有交易：只做台股分析，模型沒有 `us_close` 屬正常。
- TW / US 都休市：只做 `macro_daily`。
- Sunday：只做 weekly summary。

上游 `data-collecting` 也需要同步這些規則：

- TW 休、US 沒休時，上游寫入應讓可推送資料 `pushed_enabled = 1`。
- 兩個市場都休市時，要分析周總結或 macro 類內容，並讓可推送資料 `pushed_enabled = 1`。
- 兩個 repo 的描述檔與中文註解都要保持一致。

## 定時任務

曾新增或討論過：

- 台股早盤推送。
- 美股收盤推送。
- 週日周總結補發。
- 每天凌晨 `00:00` 檢查 `t_market_analyses`，若有舊的 `pushed = 0`，將 `pushed_enabled` 改為不可推送，避免舊訊息之後誤推。

已遇過的 DB 問題：

```text
Unknown column 'pushed_enabled' in 'where clause'
```

表示實際 DB 當時缺少 `t_market_analyses.pushed_enabled` 欄位。若新 session 又看到這個錯，先確認 migration / DDL 是否已在目標資料庫執行。

## Redis 限頻

需求方向：

- 使用 Redis 做全局限頻。
- 以 group id 或 user id 作為限頻 key。
- 加上 type enum 區分訊息類型。

目前訊息類型概念：

- 股票消息：用戶詢問個股狀態、進出場點、原因。
- 公共消息：原本市場分析推送。

限頻：

- 股票消息：同一 user id 或 group id 每天最多 3 次。
- 公共消息：同一 user id 或 group id 每天最多 2 次。

新 session 請檢查以下類別是否已存在並以實作為準：

- `PushMessageType`
- `PushRateLimiter`
- `RedisPushRateLimiter`
- `NoopPushRateLimiter`
- `PushRateLimitProperties`

## 個股查詢

股票消息入口用途：

- 讓用戶詢問個股狀態。
- 回答入場點、出場點與原因。
- 資料來源方向是 `t_trade_signals`。

這類訊息不應和公共分析推送共用同一個限頻類型。

## 目前服務運行注意事項

- 使用者希望用可見的 `cmd` 視窗啟動 Java 服務，方便看日誌。
- ngrok 需要另外啟動，且 LINE Console webhook URL 必須指向目前 ngrok 的 `/callback` 或服務實際 webhook path。
- 若 LINE 訊息沒有收到，常見原因：
  - ngrok 沒起。
  - LINE Console webhook URL 還指到舊 ngrok。
  - LINE channel secret / access token 不一致。
  - LINE Console 的 Use webhook 沒開。
  - 服務沒正常啟動或 callback path 不一致。
  - 推送目標沒有 active 或測試模式只推 `test_account = 1`。
  - Redis 限頻擋掉。
  - `pushed_enabled` / `pushed` 狀態不符合。

## 前端新聞平台新需求

使用者想做一個前端顯示頁：

1. 整理展示 `t_relay_events` 的新聞，變成可過濾雜訊的新聞平台。
2. 每日與每周分析文章要有更好的展示頁，不要都擠在 LINE。
3. 未來可能對外公開，讓人留言並參考。

已確認的架構建議：

需要一個新的中台 / Content API，而且建議獨立於 `line-relay-service` 與 `data-collecting`。

建議架構：

```text
data-collecting
  -> MySQL: t_relay_events / t_market_analyses / t_trade_signals

line-relay-service
  -> MySQL
  -> LINE 推送

new content-api / news-platform-api
  -> MySQL
  -> 提供前端查詢、篩選、留言、公開展示 API

frontend
  -> content-api
```

不要讓前端直接連 DB，也不要直接公開現有 `data-collecting` 或 `line-relay-service` 的內部 API。

## Content API 初步規格方向

建議服務名稱：

```text
market-content-service
```

初步 API：

```text
GET  /api/events
GET  /api/events/{id}
GET  /api/analyses
GET  /api/analyses/{id}
GET  /api/digest/today
GET  /api/digest/week
POST /api/comments
GET  /api/comments
```

新聞篩選欄位方向：

```text
dateFrom
dateTo
source
region
category
ticker
keyword
importance
sentiment
hideNoise
```

未來可能新增表：

```text
t_public_comments
t_public_users
t_event_labels
t_event_noise_rules
t_article_views
t_reactions
```

建議階段：

1. 先做 read-only Content API，讀 `t_relay_events` 與 `t_market_analyses`。
2. 做前端新聞列表、篩選器、文章詳情、每日/每周分析頁。
3. 再加登入、留言、審核、限流。
4. 最後做雜訊規則、收藏、訂閱、SEO。

## 新 Session 開始建議流程

請在新 session 貼：

```text
請先讀 docs/SESSION_HANDOFF_2026-05-04.md，並用它作為目前上下文。
接著執行 git status，確認目前 repo 狀態。
之後我們要繼續設計前端新聞平台與 content-api 中台。
```

新 session 第一輪建議執行：

```powershell
cd D:\work_space\claude-box\workspace\line-relay-service
git status --short
Get-ChildItem docs
```

如果要同時檢查資料 repo：

```powershell
cd D:\work_space\stock\data-collecting
git status --short
```

