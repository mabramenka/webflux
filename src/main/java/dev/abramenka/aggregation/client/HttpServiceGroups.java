package dev.abramenka.aggregation.client;

import dev.abramenka.aggregation.error.OrchestrationException;

public final class HttpServiceGroups {

    public static final String ACCOUNT_GROUP = "account-group";
    public static final String ACCOUNT = "account";
    public static final String OWNERS = "owners";

    private HttpServiceGroups() {}

    public static String downstreamClientName(String groupName) {
        return Group.byName(groupName).displayName;
    }

    private enum Group {
        ACCOUNT_GROUP(HttpServiceGroups.ACCOUNT_GROUP, "Account group"),
        ACCOUNT(HttpServiceGroups.ACCOUNT, "Account"),
        OWNERS(HttpServiceGroups.OWNERS, "Owners");

        private final String groupName;
        private final String displayName;

        Group(String groupName, String displayName) {
            this.groupName = groupName;
            this.displayName = displayName;
        }

        static Group byName(String groupName) {
            for (Group group : values()) {
                if (group.groupName.equals(groupName)) {
                    return group;
                }
            }
            throw OrchestrationException.configInvalid(
                    new IllegalStateException("Unknown HTTP service group: " + groupName));
        }
    }
}
