package com.navio.usermanagementservice.service;

/**
 * Canonical audit action names.
 *
 * <p>Constants rather than inline strings so a typo cannot silently create a new
 * action category that no alert or report is watching for.
 *
 * <p>The set covers the events {@code docs/database/Navio Database.md} 8.5 marks
 * as required, plus profile edits.
 */
public final class AuditAction {

    private AuditAction() {
    }

    public static final String USER_PROVISIONED = "USER_PROVISIONED";
    public static final String USER_PROFILE_UPDATED = "USER_PROFILE_UPDATED";
    public static final String USER_PREFERENCES_UPDATED = "USER_PREFERENCES_UPDATED";

    public static final String USER_SUSPENDED = "USER_SUSPENDED";
    public static final String USER_REACTIVATED = "USER_REACTIVATED";

    public static final String USER_ROLE_GRANTED = "USER_ROLE_GRANTED";
    public static final String USER_ROLE_REVOKED = "USER_ROLE_REVOKED";

    public static final String USER_VEHICLE_CREATED = "USER_VEHICLE_CREATED";
    public static final String USER_VEHICLE_UPDATED = "USER_VEHICLE_UPDATED";
    public static final String USER_VEHICLE_DELETED = "USER_VEHICLE_DELETED";

    /** A request was rejected because the account is suspended. */
    public static final String SUSPENDED_ACCESS_ATTEMPT = "SUSPENDED_ACCESS_ATTEMPT";

    public static final String RESOURCE_USER = "USER";
    public static final String RESOURCE_VEHICLE = "USER_VEHICLE";
}
