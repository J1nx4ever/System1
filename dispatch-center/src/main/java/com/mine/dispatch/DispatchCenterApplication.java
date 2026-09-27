package com.mine.dispatch;

import com.mine.dispatch.capability.production.DispatchProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@EnableConfigurationProperties(DispatchProperties.class)
@SpringBootApplication
public class DispatchCenterApplication {
    public static void main(String[] args) {
        SpringApplication.run(DispatchCenterApplication.class, args);
    }
}
