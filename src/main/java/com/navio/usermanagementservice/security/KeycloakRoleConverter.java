package com.navio.usermanagementservice.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Extracts Navio authorities from a validated Keycloak access token.
 *
 * <p>Keycloak splits roles across two claims:
 * <pre>
 *   realm_access.roles                  -> realm-wide roles
 *   resource_access.&lt;client&gt;.roles      -> per-client roles
 * </pre>
 * Both are read, then filtered through {@link NavioRole}'s allowlist. Only the
 * configured audiences are consulted for client roles, so a role granted on an
 * unrelated client in the same realm cannot escalate privilege here.
 *
 * <p>This converter runs only after signature, issuer, expiry, and audience
 * validation have already passed — it is a mapping step, not a trust boundary.
 */
public class KeycloakRoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    private static final String REALM_ACCESS_CLAIM = "realm_access";
    private static final String RESOURCE_ACCESS_CLAIM = "resource_access";
    private static final String ROLES_KEY = "roles";

    private final List<String> trustedClientIds;

    public KeycloakRoleConverter(List<String> trustedClientIds) {
        this.trustedClientIds = List.copyOf(trustedClientIds);
    }

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Set<GrantedAuthority> authorities = new LinkedHashSet<>();

        readRoles(jwt.getClaimAsMap(REALM_ACCESS_CLAIM)).forEach(role ->
                authorities.add(new SimpleGrantedAuthority(role.authority())));

        Map<String, Object> resourceAccess = jwt.getClaimAsMap(RESOURCE_ACCESS_CLAIM);
        if (resourceAccess != null) {
            for (String clientId : trustedClientIds) {
                if (resourceAccess.get(clientId) instanceof Map<?, ?> clientClaims) {
                    readRoles(castToStringKeyedMap(clientClaims)).forEach(role ->
                            authorities.add(new SimpleGrantedAuthority(role.authority())));
                }
            }
        }

        return authorities;
    }

    private Set<NavioRole> readRoles(Map<String, Object> accessClaim) {
        if (accessClaim == null || !(accessClaim.get(ROLES_KEY) instanceof Collection<?> rawRoles)) {
            return Set.of();
        }
        Set<NavioRole> roles = new LinkedHashSet<>();
        for (Object rawRole : rawRoles) {
            if (rawRole instanceof String roleName) {
                NavioRole.fromClaim(roleName).ifPresent(roles::add);
            }
        }
        return roles;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castToStringKeyedMap(Map<?, ?> source) {
        // Jackson always produces String keys for a decoded JWT claim set; the
        // cast is confined here so callers stay generic-safe.
        return (Map<String, Object>) source;
    }
}
