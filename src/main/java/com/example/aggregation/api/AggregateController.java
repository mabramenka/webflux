package com.example.aggregation.api;

import com.example.aggregation.client.ClientRequestContext;
import com.example.aggregation.service.AggregateService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

@RestController
@RequestMapping("/api/v1/aggregate")
@RequiredArgsConstructor
public class AggregateController {

    private final AggregateService aggregateService;

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<JsonNode> aggregate(@RequestBody ObjectNode requestBody, ClientRequestContext clientRequestContext) {
        return aggregateService.aggregate(requestBody, clientRequestContext);
    }
}
