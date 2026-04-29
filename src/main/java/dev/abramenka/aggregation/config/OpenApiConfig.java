package dev.abramenka.aggregation.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class OpenApiConfig {

    private static final String AUTHORIZATION_SCHEME = "Authorization";

    @Bean
    OpenAPI aggregationOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Aggregation Facade API")
                        .version("v1")
                        .description("Reactive aggregation facade exposing /api/v1/aggregate."))
                .components(
                        new Components()
                                .addSecuritySchemes(
                                        AUTHORIZATION_SCHEME,
                                        new SecurityScheme()
                                                .type(SecurityScheme.Type.APIKEY)
                                                .in(SecurityScheme.In.HEADER)
                                                .name(AUTHORIZATION_SCHEME)
                                                .description(
                                                        "Raw value forwarded verbatim to downstream services in the Authorization header.")))
                .addSecurityItem(new SecurityRequirement().addList(AUTHORIZATION_SCHEME));
    }
}
