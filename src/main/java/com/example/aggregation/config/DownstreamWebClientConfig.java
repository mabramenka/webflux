package com.example.aggregation.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class DownstreamWebClientConfig {

    @Bean
    public WebClient mainWebClient(WebClient.Builder builder, @Value("${downstream.main.base-url}") String baseUrl) {
        return builder.baseUrl(baseUrl).build();
    }

    @Bean
    public WebClient profileWebClient(WebClient.Builder builder, @Value("${downstream.profile.base-url}") String baseUrl) {
        return builder.baseUrl(baseUrl).build();
    }

    @Bean
    public WebClient pricingWebClient(WebClient.Builder builder, @Value("${downstream.pricing.base-url}") String baseUrl) {
        return builder.baseUrl(baseUrl).build();
    }
}
