package com.example.aggregation.client;

import org.jspecify.annotations.Nullable;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.service.invoker.HttpRequestValues;
import org.springframework.web.service.invoker.HttpServiceArgumentResolver;

@Component
public final class ClientRequestContextArgumentResolver implements HttpServiceArgumentResolver {

    @Override
    public boolean resolve(
            @Nullable Object argument, MethodParameter parameter, HttpRequestValues.Builder requestValues) {
        if (!ClientRequestContext.class.equals(parameter.getParameterType())) {
            return false;
        }
        if (argument == null) {
            throw new IllegalArgumentException("ClientRequestContext must not be null");
        }

        ClientRequestContext context = (ClientRequestContext) argument;
        context.headers().asMap().forEach(requestValues::addHeader);
        Boolean detokenize = context.detokenize();
        if (detokenize != null) {
            requestValues.addRequestParameter(ClientRequestContext.DETOKENIZE_QUERY_PARAM, detokenize.toString());
        }
        return true;
    }
}
