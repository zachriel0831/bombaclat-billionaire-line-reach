package com.zack.linerelay.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Spring bean configuration for outbound HTTP clients.
 */
@Configuration
public class RestClientConfig {

    /**
     * Shared LINE API client. Keeping the base URL and bearer token here avoids
     * repeating authentication headers in each push operation.
     */
    @Bean
    public RestClient lineRestClient(LineProperties props) {
        return RestClient.builder()
                .baseUrl(props.apiBase())
                .defaultHeader("Authorization", "Bearer " + props.channelAccessToken())
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

}
