package com.jobx.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebClientConfig {

    // ATS boards returned with content=true can exceed the 256KB default,
    // e.g. Razorpay/PhonePe Greenhouse boards with full job descriptions inline.
    private static final int MAX_IN_MEMORY_SIZE = 10 * 1024 * 1024;

    @Bean
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY_SIZE));
    }
}
