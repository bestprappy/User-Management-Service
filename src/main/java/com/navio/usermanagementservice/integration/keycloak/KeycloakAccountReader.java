package com.navio.usermanagementservice.integration.keycloak;

import java.util.List;

/**
 * Read-only facts about how an account signs in, for its owner's Security page.
 *
 * <p>Separate from {@link KeycloakAdminClient} so the moderation code and its
 * test fakes do not grow methods they never use.
 */
public interface KeycloakAccountReader {

    /** Credential types the account holds, such as {@code password} or {@code otp}. Never their values. */
    List<String> credentialTypes(String authSubject);

    /** Aliases of linked external identity providers, such as {@code google}. */
    List<String> linkedIdentityProviders(String authSubject);
}
