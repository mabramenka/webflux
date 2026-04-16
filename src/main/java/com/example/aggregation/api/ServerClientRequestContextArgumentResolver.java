package com.example.aggregation.api;

import com.example.aggregation.client.ClientRequestContext;
import lombok.RequiredArgsConstructor;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.BindingContext;
import org.springframework.web.reactive.result.method.HandlerMethodArgumentResolver;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
public class ServerClientRequestContextArgumentResolver implements HandlerMethodArgumentResolver {

    private final ClientRequestContextFactory clientRequestContextFactory;

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType() == ClientRequestContext.class;
    }

    @Override
    public Mono<Object> resolveArgument(
            MethodParameter parameter, BindingContext bindingContext, ServerWebExchange exchange) {
        return Mono.just(clientRequestContextFactory.from(
                exchange.getRequest().getHeaders(), exchange.getRequest().getQueryParams()));
    }
}
