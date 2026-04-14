package com.example.aggregation.controller;

import com.example.aggregation.service.AggregateService;
import com.example.aggregation.web.DownstreamRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
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
    public Mono<ResponseEntity<JsonNode>> aggregate(
        @RequestBody ObjectNode requestBody,
        @RequestHeader HttpHeaders headers,
        @RequestParam MultiValueMap<String, String> queryParams
    ) {
        DownstreamRequest downstreamRequest = DownstreamRequest.from(headers, queryParams);
        return aggregateService.aggregate(requestBody, downstreamRequest)
            .map(ResponseEntity::ok);
    }
}
