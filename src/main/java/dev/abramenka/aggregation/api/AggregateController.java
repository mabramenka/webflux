package dev.abramenka.aggregation.api;

import dev.abramenka.aggregation.model.ClientRequestContext;
import dev.abramenka.aggregation.service.AggregateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/aggregate")
@RequiredArgsConstructor
public class AggregateController {

    private final AggregateService aggregateService;

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<JsonNode> aggregate(
            @Valid @RequestBody AggregateRequest request, ClientRequestContext clientRequestContext) {
        return aggregateService.aggregate(request, clientRequestContext);
    }
}
