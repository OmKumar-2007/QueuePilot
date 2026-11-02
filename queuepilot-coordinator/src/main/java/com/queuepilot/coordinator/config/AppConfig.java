package com.queuepilot.coordinator.config;

import com.queuepilot.core.job.RetryPolicy;
import com.queuepilot.core.schema.InputValidator;
import com.queuepilot.core.scheduling.QueueRotation;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;

/** Beans for the framework-free core classes, plus an injectable clock so time can be controlled in tests. */
@Configuration
public class AppConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public RetryPolicy retryPolicy(QueuePilotProperties properties) {
        return new RetryPolicy(Duration.ofMillis(properties.retryBaseDelayMs()), Duration.ofMillis(properties.retryMaxDelayMs()));
    }

    @Bean
    public InputValidator inputValidator() {
        return new InputValidator();
    }

    @Bean
    public QueueRotation queueRotation() {
        return new QueueRotation();
    }
}
