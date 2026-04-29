package dev.abramenka.aggregation.config;

import io.micrometer.context.ContextRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.MDC;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class MdcPropagationConfig {

    public static final String REQUEST_ID_KEY = "requestId";
    public static final String CORRELATION_ID_KEY = "correlationId";

    @PostConstruct
    void registerMdcAccessors() {
        registerMdcKey(REQUEST_ID_KEY);
        registerMdcKey(CORRELATION_ID_KEY);
    }

    private static void registerMdcKey(String key) {
        ContextRegistry.getInstance()
                .registerThreadLocalAccessor(
                        key, () -> MDC.get(key), value -> MDC.put(key, value), () -> MDC.remove(key));
    }
}
