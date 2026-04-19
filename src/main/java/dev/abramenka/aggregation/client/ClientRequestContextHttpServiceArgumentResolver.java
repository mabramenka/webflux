package dev.abramenka.aggregation.client;

import dev.abramenka.aggregation.model.ClientRequestContext;
import dev.abramenka.aggregation.model.ForwardedHeaders;
import org.jspecify.annotations.Nullable;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.service.invoker.HttpRequestValues;
import org.springframework.web.service.invoker.HttpServiceArgumentResolver;

@Component
public final class ClientRequestContextHttpServiceArgumentResolver implements HttpServiceArgumentResolver {

    @Override
    public boolean resolve(
            @Nullable Object argument, MethodParameter parameter, HttpRequestValues.Builder requestValues) {
        if (!ClientRequestContext.class.equals(parameter.getParameterType())) {
            return false;
        }

        if (!(argument instanceof ClientRequestContext(ForwardedHeaders headers, Boolean detokenize))) {
            throw new IllegalArgumentException("ClientRequestContext must not be null");
        }

        headers.asMap().forEach(requestValues::addHeader);

        if (detokenize != null) {
            requestValues.addRequestParameter(
                    ClientRequestContext.DETOKENIZE_QUERY_PARAM, Boolean.toString(detokenize));
        }

        return true;
    }
}
