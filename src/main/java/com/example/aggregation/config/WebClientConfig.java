package com.example.aggregation.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration(proxyBeanMethods = false)
public class WebClientConfig {

    @Bean
    public WebClient accountGroupWebClient(WebClient.Builder builder, @Value("${client.account-group.base-url}") String baseUrl) {
        return builder.clone().baseUrl(baseUrl).build();
    }

    @Bean
    public WebClient accountWebClient(WebClient.Builder builder, @Value("${client.account.base-url}") String baseUrl) {
        return builder.clone().baseUrl(baseUrl).build();
    }

    @Bean
    public WebClient ownersWebClient(WebClient.Builder builder, @Value("${client.owners.base-url}") String baseUrl) {
        return builder.clone().baseUrl(baseUrl).build();
    }
}
