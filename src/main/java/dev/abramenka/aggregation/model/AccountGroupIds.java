package dev.abramenka.aggregation.model;

public final class AccountGroupIds {

    public static final String PATTERN = "[A-Za-z]{2}\\d{9}";

    public static final int MAX_PER_REQUEST = 100;

    private AccountGroupIds() {}
}
