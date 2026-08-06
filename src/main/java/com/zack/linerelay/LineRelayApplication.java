package com.zack.linerelay;

import com.zack.linerelay.config.LineProperties;
import com.zack.linerelay.config.LineSecurityProperties;
import com.zack.linerelay.config.PushRateLimitProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Application entry point for the LINE relay service.
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({
        LineProperties.class,
        LineSecurityProperties.class,
        PushRateLimitProperties.class
})
public class LineRelayApplication {

    /**
     * Bootstraps the LINE relay service. Scheduling is enabled globally here, while
     * individual scheduled jobs are still guarded by configuration properties.
     */
    public static void main(String[] args) {
        SpringApplication.run(LineRelayApplication.class, args);
    }
}
