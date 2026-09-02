package com.navio.usermanagementservice.integration.keycloak;

/**
 * Raised when a Keycloak Admin API call cannot be completed.
 *
 * <p>Messages are written for operators, not end users. The global exception
 * handler maps this to a generic 503 so upstream callers never receive Keycloak
 * internals, URLs, or realm structure.
 */
public class KeycloakAdminException extends RuntimeException {

    public KeycloakAdminException(String message) {
        super(message);
    }

    public KeycloakAdminException(String message, Throwable cause) {
        super(message, cause);
    }

    /** The target user does not exist in the realm. */
    public static class UserNotFoundInRealmException extends KeycloakAdminException {
        public UserNotFoundInRealmException(String authSubject) {
            super("Keycloak has no user with subject " + authSubject);
        }
    }

    /** The requested realm role is not defined. */
    public static class RoleNotFoundException extends KeycloakAdminException {
        public RoleNotFoundException(String role) {
            super("Keycloak realm has no role named " + role);
        }
    }
}
