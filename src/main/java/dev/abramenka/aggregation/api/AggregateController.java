package dev.abramenka.aggregation.api;

import dev.abramenka.aggregation.model.AccountGroupIds;
import dev.abramenka.aggregation.model.ClientRequestContext;
import dev.abramenka.aggregation.service.AggregateService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping(path = "/api/{apiVersion:v\\d+}/aggregate", version = "1")
@RequiredArgsConstructor
public class AggregateController {

    private final AggregateService aggregateService;

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<JsonNode> aggregate(
            @Valid @RequestBody AggregateRequest request, ClientRequestContext clientRequestContext) {
        return aggregateService.aggregate(request, clientRequestContext);
    }

    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<JsonNode> aggregateOne(
            @PathVariable @Pattern(regexp = AccountGroupIds.PATTERN) String id,
            @RequestParam(required = false) @Nullable List<@NotBlank String> include,
            ClientRequestContext clientRequestContext) {
        return aggregateService.aggregate(new AggregateRequest(List.of(id), include), clientRequestContext);
    }
}
