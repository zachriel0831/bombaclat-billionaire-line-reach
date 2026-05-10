package com.zack.linerelay.stock;

import com.zack.linerelay.push.LinePushClient;
import com.zack.linerelay.push.PushMessageType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Handles LINE text commands that ask for one stock's real-time model signal.
 */
@Service
public class StockQueryService {

    private static final Logger log = LoggerFactory.getLogger(StockQueryService.class);
    private static final List<String> PREFIXES = List.of("西卡卡股票", "股票", "個股", "查股");
    private static final String STOCK_ANALYSIS_PREFIX = "股價分析";
    private static final String USAGE = "請用「股票 2330」、「查股 NVDA」或「股價分析 Rocket Lab (RKLB)」查詢個股訊號。";

    private final StockSignalClient stockSignalClient;
    private final StockSignalCache stockSignalCache;
    private final LinePushClient pushClient;

    public StockQueryService(StockSignalClient stockSignalClient, StockSignalCache stockSignalCache, LinePushClient pushClient) {
        this.stockSignalClient = stockSignalClient;
        this.stockSignalCache = stockSignalCache;
        this.pushClient = pushClient;
    }

    /**
     * Returns true when the text was recognized as a stock command, even when
     * the reply later gets skipped by the global push toggle or quota.
     */
    public boolean handle(String targetId, String text) {
        StockCommand command = parseCommand(text);
        if (!command.matched()) {
            return false;
        }
        if (targetId == null || targetId.isBlank()) {
            log.warn("stock_query skipped reason=missing_target text={}", text);
            return true;
        }

        String reply = command.freeForm()
                ? buildAnalysisReply(command.ticker())
                : buildReply(command.ticker());
        LinePushClient.PushAttempt attempt = pushClient.push(PushMessageType.STOCK_QUERY, targetId, reply);
        log.info("stock_query handled target={} ticker={} mode={} delivered={} skipped_by_toggle={} skipped_by_rate_limit={}",
                targetId, command.ticker().orElse("-"),
                command.freeForm() ? "analysis" : "ticker",
                attempt.delivered(), attempt.skippedByToggle(), attempt.skippedByRateLimit());
        return true;
    }

    private String buildAnalysisReply(Optional<String> queryOpt) {
        if (queryOpt.isEmpty()) {
            return USAGE;
        }
        String query = queryOpt.get();
        try {
            GeneratedStockSignalResponse generated = stockSignalClient.generate(query);
            return formatGenerated(generated, query);
        } catch (RestClientResponseException ex) {
            log.warn("stock_analysis model generation failed query={} status={} body={}",
                    query, ex.getStatusCode().value(), ex.getResponseBodyAsString());
            return unavailableReply(query);
        } catch (Exception ex) {
            log.warn("stock_analysis model generation unavailable query={} reason={}", query, ex.toString());
            return unavailableReply(query);
        }
    }

    private String buildReply(Optional<String> tickerOpt) {
        if (tickerOpt.isEmpty()) {
            return USAGE;
        }
        String ticker = tickerOpt.get();
        Optional<String> cached = stockSignalCache.get(ticker);
        if (cached.isPresent()) {
            log.info("stock_query cache_hit ticker={}", ticker);
            return cached.get();
        }
        try {
            GeneratedStockSignalResponse generated = stockSignalClient.generate(ticker);
            String reply = formatGenerated(generated, ticker);
            stockSignalCache.put(ticker, reply);
            return reply;
        } catch (RestClientResponseException ex) {
            log.warn("stock_query model generation failed ticker={} status={} body={}",
                    ticker, ex.getStatusCode().value(), ex.getResponseBodyAsString());
            return unavailableReply(ticker);
        } catch (Exception ex) {
            log.warn("stock_query model generation unavailable ticker={} reason={}", ticker, ex.toString());
            return unavailableReply(ticker);
        }
    }

    private String formatGenerated(GeneratedStockSignalResponse generated, String requestedTicker) {
        if (generated != null && notBlank(generated.lineMessage())) {
            return generated.lineMessage().trim();
        }
        if (generated == null) {
            return unavailableReply(requestedTicker);
        }
        String label = firstNonBlank(generated.ticker(), requestedTicker);
        if (notBlank(generated.name())) {
            label += " " + generated.name().trim();
        }
        String model = notBlank(generated.model()) ? " / 模型：" + generated.model().trim() : "";
        return """
                [即時股票分析] %s
                方向：%s / 策略：%s / 信心：%s
                進場：%s
                停利/出場：%s
                失效/停損：%s
                風險：%s
                模型分析：%s
                原因：%s
                來源：中台即時模型%s
                %s
                """.formatted(
                label,
                firstNonBlank(generated.direction(), "watch"),
                firstNonBlank(generated.strategyType(), "watch"),
                firstNonBlank(generated.confidence(), "low"),
                firstNonBlank(generated.entry(), "等待更多即時資料確認。"),
                firstNonBlank(generated.takeProfit(), "依量價與族群強弱分批調整。"),
                firstNonBlank(generated.stopLoss(), "若原假設失效或量價轉弱需降低部位。"),
                firstNonBlank(generated.risk(), "資料不足，請控制部位。"),
                firstNonBlank(generated.modelAnalysis(), "模型未提供分析摘要。"),
                firstNonBlank(generated.rationale(), "模型未提供理由。"),
                model,
                firstNonBlank(generated.disclaimer(), "僅供研究參考，非投資建議。")
        ).trim();
    }

    private String unavailableReply(String ticker) {
        return """
                [即時股票分析] %s
                中台即時模型分析暫時不可用，請稍後再試。
                目前此指令不再直接讀取舊 DB 訊號，以免回覆過期內容。
                """.formatted(ticker).trim();
    }

    private StockCommand parseCommand(String text) {
        if (text == null || text.isBlank()) {
            return StockCommand.notMatched();
        }
        String trimmed = text.trim();

        // 股價分析 takes the entire text after the prefix as a free-form query so
        // names like "Rocket Lab (RKLB)" reach the platform unchanged. A separator
        // is required between prefix and content; "股價分析RKLB" should not match.
        if (trimmed.startsWith(STOCK_ANALYSIS_PREFIX)) {
            String afterPrefix = trimmed.substring(STOCK_ANALYSIS_PREFIX.length());
            if (afterPrefix.isEmpty()) {
                return StockCommand.matchedFreeForm(Optional.empty());
            }
            if (!Character.isWhitespace(afterPrefix.codePointAt(0))) {
                return StockCommand.notMatched();
            }
            String query = afterPrefix.strip();
            return StockCommand.matchedFreeForm(query.isBlank() ? Optional.empty() : Optional.of(query));
        }

        for (String prefix : PREFIXES) {
            if (!trimmed.equals(prefix) && !trimmed.startsWith(prefix)) {
                continue;
            }
            String rest = trimSeparators(trimmed.substring(prefix.length()));
            if (rest.isBlank()) {
                return StockCommand.matched(Optional.empty());
            }
            String firstToken = rest.split("\\s+", 2)[0];
            String ticker = normalizeTicker(firstToken);
            return StockCommand.matched(ticker.isBlank() ? Optional.empty() : Optional.of(ticker));
        }
        return StockCommand.notMatched();
    }

    private String trimSeparators(String raw) {
        return raw.replaceFirst("^[\\s:：,，]+", "").trim();
    }

    private String normalizeTicker(String rawTicker) {
        if (rawTicker == null) {
            return "";
        }
        String ticker = rawTicker.trim().toUpperCase(Locale.ROOT);
        if (ticker.startsWith("$")) {
            ticker = ticker.substring(1);
        }
        if (ticker.endsWith(".TW")) {
            ticker = ticker.substring(0, ticker.length() - 3);
        } else if (ticker.endsWith(".TWO")) {
            ticker = ticker.substring(0, ticker.length() - 4);
        }
        return ticker.replaceAll("[^A-Z0-9._-]", "");
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (notBlank(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private record StockCommand(boolean matched, boolean freeForm, Optional<String> ticker) {
        static StockCommand matched(Optional<String> ticker) {
            return new StockCommand(true, false, ticker);
        }

        static StockCommand matchedFreeForm(Optional<String> query) {
            return new StockCommand(true, true, query);
        }

        static StockCommand notMatched() {
            return new StockCommand(false, false, Optional.empty());
        }
    }
}
