# LINE Stock Analysis Template

LINE relay 在收到 `股價分析 <代號或名稱>` 指令時,把整段 trimmed query 丟給中台 `news-platform-api` `/api/stock-signals/generate`。中台模型必須回傳一段適合直接貼入 LINE 對話窗的 `lineMessage`。本檔是**輸出主軸**(分析骨架)的合約;若中台未提供 `lineMessage`,LINE relay 會用同樣的順序把 `GeneratedStockSignalResponse` 各欄位組成 fallback 回覆。

## Trigger contract

- 指令: `股價分析 <代號或名稱>`
- 觸發條件: 前綴與內容之間必須有半形或全形空白
- 內容: 後面所有字會被 trim 後當成 `ticker` 欄位送往中台,**不**做大小寫或字元正規化(支援 `Rocket Lab (RKLB)` 這類名稱)
- 不會走本地 Redis cache;每次都打中台
- 仍受 `STOCK_QUERY` Redis 配額(預設 3 / 目標 / 日)

## Required output structure (LINE 主軸)

模型輸出必須依序包含以下段落,每段落 1–2 句、可直接貼入 LINE。建議全形冒號、不要 Markdown 與表格。

1. 標題列: `[即時股票分析] <ticker> <name>` — 名稱可省,若僅有名稱輸入,需回填正規 ticker
2. 方向 / 策略 / 信心: 用 `方向：long|short|watch / 策略：swing|day|position|watch / 信心：high|medium|low`
3. 進場: 觸發點、價格區間或等待條件
4. 停利 / 出場: 分批策略或關鍵壓力位
5. 失效 / 停損: 量價或假設失效條件
6. 風險: 個股、產業、總經三類至少一項
7. 模型分析: 1–2 句講清楚這檔的多空主軸與可觀察訊號
8. 原因: 把模型分析的關鍵驅動因子(財報、訂單、政策)用一句話收斂
9. 來源: `中台即時模型` 加 `/ 模型：<model>` 後綴(由 relay 自動帶入)
10. 免責: 一句免責聲明

## Style guide

- 繁體中文,口吻像投資決策備忘錄,不像新聞稿
- 每段 1–2 句,避免長句與多重條件
- 不使用 `**粗體**`、`# 標題`、`- 列點`、`|表格|`
- 可用全形冒號 `：` 與全形括號;名稱可帶 `(RKLB)` 形式
- 每段需具操作含義(進場價、停損條件、可監看的訊號),避免空泛
- 全文以可控制在 LINE 單則訊息(< 4500 字)為目標,過長者請壓縮原因與模型分析

## Required fields in `GeneratedStockSignalResponse`

當中台不送 `lineMessage` 時,LINE relay 會以下列欄位 fallback 組訊。模型應盡量都填寫:

- `ticker`, `name`, `market`
- `direction` (`long` / `short` / `watch`)
- `strategyType` (`swing` / `day` / `position` / `watch`)
- `confidence` (`high` / `medium` / `low`)
- `entry`, `takeProfit`, `stopLoss`, `risk`
- `modelAnalysis`, `rationale`
- `model`, `promptVersion`, `disclaimer`

缺欄位時,fallback 會塞固定字串(例如「等待更多即時資料確認。」、「資料不足,請控制部位。」、「僅供研究參考,非投資建議。」)。出現過多 fallback 字串代表模型輸出不完整,應在中台側補強 prompt。

## Example output

```
[即時股票分析] RKLB Rocket Lab
方向：long / 策略：swing / 信心：medium
進場：拉回 17.5–18.0 分批承接,跌破 16.8 暫停加碼。
停利/出場：第一目標 22.5,觸及後減半,剩餘觀察 25.0。
失效/停損：日收破 16.5 或 Neutron 試射時程明確延後一季以上。
風險：航太代工受國防預算與發射成功率影響,單次任務失敗會放大波動。
模型分析:Electron 任務節奏穩定且 Neutron 進度推進,合約金額維持高檔。
原因:多筆政府與商業客戶簽約 + 衛星製造產線稼動率提升。
來源:中台即時模型 / 模型:gpt-5.4-mini
僅供研究參考,非投資建議。
```

## Hard rules

- 必須有方向 / 進場 / 停利 / 停損 / 風險 / 模型分析六項
- 不可只列新聞或財報數字
- 不可只給「持續觀察」這類空泛結論;若資料不足,需要寫「等待 X 訊號明朗再操作」並指明 X
- 不可包含 Markdown 語法或表格符號
- 不可省略免責聲明
