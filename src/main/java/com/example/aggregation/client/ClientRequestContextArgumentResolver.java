package com.example.aggregation.client;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.core.MethodParameter;
import org.springframework.web.service.invoker.HttpRequestValues;
import org.springframework.web.service.invoker.HttpServiceArgumentResolver;

public final class ClientRequestContextArgumentResolver implements HttpServiceArgumentResolver {

    private static final String DETOKENIZE_QUERY_PARAM = "detokenize";

    @Override
    public boolean resolve(@Nullable Object argument, MethodParameter parameter, HttpRequestValues.@NonNull Builder requestValues) {
        if (!ClientRequestContext.class.equals(parameter.getParameterType())) {
            return false;
        }
        if (argument == null) {
            return true;
        }

        ClientRequestContext context = (ClientRequestContext) argument;
        context.headers().asMap().forEach(requestValues::addHeader);
        if (context.detokenize() != null) {
            requestValues.addRequestParameter(DETOKENIZE_QUERY_PARAM, context.detokenize().toString());
        }
        return true;
    }
}
