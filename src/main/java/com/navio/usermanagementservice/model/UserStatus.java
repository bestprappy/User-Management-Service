package com.navio.usermanagementservice.model;

/**
 * Lifecycle state of a Navio profile.
 *
 * <p>Persisted lower-case to match the {@code ck_iam_users_status} check
 * constraint in the {@code iam} schema.
 */
public enum UserStatus {

    ACTIVE("active"),
    SUSPENDED("suspended"),
    DELETED("deleted");

    private final String value;

    UserStatus(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static UserStatus fromValue(String value) {
        for (UserStatus status : values()) {
            if (status.value.equalsIgnoreCase(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown user status: " + value);
    }
}
