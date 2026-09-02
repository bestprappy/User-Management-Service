package com.navio.usermanagementservice.integration.keycloak;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The narrow slice of Keycloak Admin API representations this service exchanges.
 *
 * <p>Deliberately minimal. Binding the full representation would pull credential
 * arrays and session detail into application memory for no reason, and would
 * risk echoing them back through a mapper or a log line.
 */
public final class KeycloakRepresentations {

    private KeycloakRepresentations() {
    }

    /**
     * A realm user. Unknown properties are ignored so a Keycloak upgrade that
     * adds fields does not break deserialization.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record KeycloakUser(
            String id,
            String username,
            String email,
            Boolean enabled,
            Boolean emailVerified,
            String firstName,
            String lastName
    ) {
    }

    /** Patch body for enabling or disabling an account. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EnabledUpdate(Boolean enabled) {
    }

    /** A realm role, as required by the role-mapping endpoints. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record KeycloakRole(
            String id,
            String name,
            String description,
            Boolean composite,
            Boolean clientRole,
            String containerId
    ) {
    }
}
