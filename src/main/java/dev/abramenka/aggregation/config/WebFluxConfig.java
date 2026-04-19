package dev.abramenka.aggregation.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import org.springframework.web.reactive.result.method.annotation.ArgumentResolverConfigurer;

@Configuration(proxyBeanMethods = false)
@RequiredArgsConstructor
public class WebFluxConfig implements WebFluxConfigurer {

    private final ServerClientRequestContextArgumentResolver clientRequestContextArgumentResolver;

    @Override
    public void configureArgumentResolvers(ArgumentResolverConfigurer configurer) {
        configurer.addCustomResolver(clientRequestContextArgumentResolver);
    }
}
