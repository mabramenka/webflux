package dev.abramenka.aggregation.client;

import java.util.Arrays;

public final class HttpServiceGroups {

    public static final String ACCOUNT_GROUP = "account-group";
    public static final String ACCOUNT = "account";
    public static final String OWNERS = "owners";

    private HttpServiceGroups() {}

    public static String downstreamClientName(String groupName) {
        return Group.fromId(groupName).displayName(groupName);
    }

    private enum Group {
        ACCOUNT_GROUP(HttpServiceGroups.ACCOUNT_GROUP, "Account group"),
        ACCOUNT(HttpServiceGroups.ACCOUNT, "Account"),
        OWNERS(HttpServiceGroups.OWNERS, "Owners"),
        UNKNOWN("", "");

        private final String id;
        private final String displayName;

        Group(String id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }

        static Group fromId(String id) {
            return Arrays.stream(values())
                    .filter(group -> group.id.equals(id))
                    .findFirst()
                    .orElse(UNKNOWN);
        }

        String displayName(String fallback) {
            return this == UNKNOWN ? fallback : displayName;
        }
    }
}
