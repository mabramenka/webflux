package com.example.aggregation.client;

public final class HttpServiceGroups {

    public static final String ACCOUNT_GROUP = "account-group";
    public static final String ACCOUNT = "account";
    public static final String OWNERS = "owners";

    private HttpServiceGroups() {
    }

    public static String downstreamMetricClientName(String groupName) {
        return switch (groupName) {
            case ACCOUNT_GROUP -> "Account group";
            case ACCOUNT -> "Account";
            case OWNERS -> "Owners";
            default -> groupName;
        };
    }
}
