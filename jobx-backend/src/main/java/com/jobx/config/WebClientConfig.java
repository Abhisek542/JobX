package com.jobx.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Configuration
public class WebClientConfig {

    // ATS boards returned with content=true can exceed the 256KB default,
    // e.g. Razorpay/PhonePe Greenhouse boards with full job descriptions inline.
    private static final int MAX_IN_MEMORY_SIZE = 10 * 1024 * 1024;

    @Value("${jobx.http.connect-timeout-ms:10000}")
    private int connectTimeoutMs;

    @Value("${jobx.http.response-timeout-ms:60000}")
    private long responseTimeoutMs;

    /**
     * Every fetcher calls .block() on this client from the scheduler thread.
     * With no timeouts, a board that accepts the TCP connection and then never
     * answers parks that thread forever — inside the fetch transaction, holding
     * a pooled DB connection — and no company later in the cycle is ever polled
     * again. The timeouts below are what stops one bad board from halting
     * discovery for every other company.
     *
     * The response timeout is deliberately generous: a full Greenhouse board
     * with content=true is megabytes of JSON over a possibly slow link. Tune via
     * the jobx.http.* properties rather than editing this.
     */
    @Bean
    public WebClient.Builder webClientBuilder() {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMs)
                .responseTimeout(Duration.ofMillis(responseTimeoutMs))
                .doOnConnected(connection -> connection
                        .addHandlerLast(new ReadTimeoutHandler(responseTimeoutMs, TimeUnit.MILLISECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(responseTimeoutMs, TimeUnit.MILLISECONDS)));

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY_SIZE));
    }
}
