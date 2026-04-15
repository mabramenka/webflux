package com.example.aggregation.client;

import org.springframework.http.HttpStatusCode;

public final class DownstreamClientException extends IllegalStateException {

    private final String clientName;
    private final HttpStatusCode statusCode;
    private final String responseBody;

    public DownstreamClientException(String clientName, HttpStatusCode statusCode, String responseBody) {
        super(clientName + " client failed: " + responseBody);
        this.clientName = clientName;
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }

    public String clientName() {
        return clientName;
    }

    public HttpStatusCode statusCode() {
        return statusCode;
    }

    public String responseBody() {
        return responseBody;
    }
}
