package com.zack.linerelay;

import com.zack.linerelay.config.LineProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(LineProperties.class)
public class LineRelayApplication {

    public static void main(String[] args) {
        SpringApplication.run(LineRelayApplication.class, args);
    }
}
