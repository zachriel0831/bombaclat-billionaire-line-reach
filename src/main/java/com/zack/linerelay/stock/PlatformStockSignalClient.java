package com.zack.linerelay.stock;

import com.zack.linerelay.config.LineProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class PlatformStockSignalClient implements StockSignalClient {

    private final LineProperties props;
    private final RestClient restClient;

    public PlatformStockSignalClient(LineProperties props, @Qualifier("platformRestClient") RestClient restClient) {
        this.props = props;
        this.restClient = restClient;
    }

    @Override
    public GeneratedStockSignalResponse generate(String ticker) {
        if (!props.platform().enabled()) {
            throw new StockSignalUnavailableException("platform stock signal generation is disabled");
        }
        return restClient.post()
                .uri(props.platform().stockSignalPath())
                .body(new StockSignalGenerationRequest(ticker, "line", "zh-TW"))
                .retrieve()
                .body(GeneratedStockSignalResponse.class);
    }
}
