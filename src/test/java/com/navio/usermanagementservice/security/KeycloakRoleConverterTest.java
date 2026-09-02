package com.navio.usermanagementservice.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Role mapping decides what a caller may do, so the allowlist behaviour is
 * pinned down here — particularly that Keycloak's built-in roles and roles from
 * untrusted clients cannot become Navio authorities.
 */
class KeycloakRoleConverterTest {

    private static final String TRUSTED_CLIENT = "navio-api";

    private final KeycloakRoleConverter converter = new KeycloakRoleConverter(List.of(TRUSTED_CLIENT));

    @Test
    void mapsRealmRolesToAuthorities() {
        Jwt jwt = jwt(Map.of("realm_access", Map.of("roles", List.of("USER", "ADMIN"))));

        assertThat(authorityNames(converter.convert(jwt)))
                .containsExactlyInAnyOrder("ROLE_USER", "ROLE_ADMIN");
    }

    @Test
    void ignoresKeycloakBuiltInRoles() {
        // offline_access, uma_authorization and default-roles-* are present on
        // essentially every Keycloak token. None is a Navio role.
        Jwt jwt = jwt(Map.of("realm_access", Map.of("roles",
                List.of("USER", "offline_access", "uma_authorization", "default-roles-navio"))));

        assertThat(authorityNames(converter.convert(jwt))).containsExactly("ROLE_USER");
    }

    @Test
    void mapsClientRolesFromATrustedClient() {
        Jwt jwt = jwt(Map.of("resource_access",
                Map.of(TRUSTED_CLIENT, Map.of("roles", List.of("MODERATOR")))));

        assertThat(authorityNames(converter.convert(jwt))).containsExactly("ROLE_MODERATOR");
    }

    @Test
    void ignoresClientRolesFromAnUntrustedClient() {
        // A role granted on some other client in the realm must not escalate
        // privilege here — otherwise anyone able to create a client could mint
        // themselves an ADMIN authority.
        Jwt jwt = jwt(Map.of("resource_access",
                Map.of("some-other-app", Map.of("roles", List.of("ADMIN")))));

        assertThat(converter.convert(jwt)).isEmpty();
    }

    @Test
    void normalisesRoleCasing() {
        Jwt jwt = jwt(Map.of("realm_access", Map.of("roles", List.of("admin", "Moderator"))));

        assertThat(authorityNames(converter.convert(jwt)))
                .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_MODERATOR");
    }

    @Test
    void returnsNoAuthoritiesWhenClaimsAreAbsent() {
        assertThat(converter.convert(jwt(Map.of()))).isEmpty();
    }

    @Test
    void toleratesMalformedRoleClaims() {
        // A claim of the wrong shape must not throw: an exception during
        // authority mapping would surface as a 500 rather than a clean 401/403.
        Jwt malformedRoles = jwt(Map.of("realm_access", Map.of("roles", "USER")));
        assertThat(converter.convert(malformedRoles)).isEmpty();

        Jwt nonStringEntries = jwt(Map.of("realm_access", Map.of("roles", List.of(42, true))));
        assertThat(converter.convert(nonStringEntries)).isEmpty();
    }

    @Test
    void deduplicatesRoleGrantedByBothRealmAndClient() {
        Jwt jwt = jwt(Map.of(
                "realm_access", Map.of("roles", List.of("ADMIN")),
                "resource_access", Map.of(TRUSTED_CLIENT, Map.of("roles", List.of("ADMIN")))));

        assertThat(authorityNames(converter.convert(jwt))).containsExactly("ROLE_ADMIN");
    }

    private List<String> authorityNames(Collection<GrantedAuthority> authorities) {
        return authorities.stream().map(GrantedAuthority::getAuthority).toList();
    }

    private Jwt jwt(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("subject-1")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300));
        claims.forEach(builder::claim);
        return builder.build();
    }
}
