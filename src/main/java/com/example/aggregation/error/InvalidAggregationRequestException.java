package com.example.aggregation.error;

public final class InvalidAggregationRequestException extends RuntimeException {

    public InvalidAggregationRequestException(String message) {
        super(message);
    }
}
