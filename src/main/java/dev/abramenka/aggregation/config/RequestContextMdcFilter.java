package dev.abramenka.aggregation.config;

import dev.abramenka.aggregation.model.ForwardedHeaders;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

@Component
public class RequestContextMdcFilter implements WebFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        HttpHeaders headers = exchange.getRequest().getHeaders();
        String requestId = firstNonBlank(headers.getFirst(ForwardedHeaders.REQUEST_ID_HEADER));
        String effectiveRequestId =
                requestId != null ? requestId : UUID.randomUUID().toString();
        String correlationId = firstNonBlank(headers.getFirst(ForwardedHeaders.CORRELATION_ID_HEADER));

        exchange.getResponse().getHeaders().set(ForwardedHeaders.REQUEST_ID_HEADER, effectiveRequestId);

        Context context = Context.of(MdcPropagationConfig.REQUEST_ID_KEY, effectiveRequestId);
        if (correlationId != null) {
            context = context.put(MdcPropagationConfig.CORRELATION_ID_KEY, correlationId);
        }
        return chain.filter(exchange).contextWrite(context);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    private static @Nullable String firstNonBlank(@Nullable String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
