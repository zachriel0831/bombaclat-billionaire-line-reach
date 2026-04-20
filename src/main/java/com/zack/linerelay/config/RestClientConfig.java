package com.zack.linerelay.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    @Bean
    public RestClient lineRestClient(LineProperties props) {
        return RestClient.builder()
                .baseUrl(props.apiBase())
                .defaultHeader("Authorization", "Bearer " + props.channelAccessToken())
                .defaultHeader("Content-Type", "application/json")
                .build();
    }
}
