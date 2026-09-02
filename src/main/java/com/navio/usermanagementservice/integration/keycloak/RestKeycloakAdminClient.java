package com.navio.usermanagementservice.integration.keycloak;

import com.navio.usermanagementservice.config.KeycloakProperties;
import com.navio.usermanagementservice.integration.keycloak.KeycloakRepresentations.EnabledUpdate;
import com.navio.usermanagementservice.integration.keycloak.KeycloakRepresentations.KeycloakRole;
import com.navio.usermanagementservice.integration.keycloak.KeycloakRepresentations.KeycloakUser;
import com.navio.usermanagementservice.security.NavioRole;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Keycloak Admin API client built on {@link RestClient}.
 *
 * <p>Chosen over {@code keycloak-admin-client} to keep the dependency surface —
 * and therefore the transitive CVE surface — small, and to avoid pinning a
 * RESTEasy stack against this project's Java 25 / Spring Boot 4 baseline. It
 * follows the same integration pattern as the trip service's mobility client.
 *
 * <p>A 401 from Keycloak triggers exactly one token refresh and retry, which
 * covers the case where a cached token was revoked server-side. Retrying more
 * than once would risk hammering the token endpoint with a bad secret.
 */
@Component
@Slf4j
public class RestKeycloakAdminClient implements KeycloakAdminClient {

    private final KeycloakProperties.Admin adminProperties;
    private final KeycloakAdminTokenProvider tokenProvider;
    private final RestClient restClient;

    public RestKeycloakAdminClient(
            KeycloakProperties properties,
            KeycloakAdminTokenProvider tokenProvider,
            RestClient.Builder restClientBuilder
    ) {
        this.adminProperties = properties.admin();
        this.tokenProvider = tokenProvider;
        this.restClient = restClientBuilder.clone()
                .baseUrl(properties.admin().adminRealmEndpoint())
                .build();
    }

    @Override
    public Optional<KeycloakUser> findUserBySubject(String authSubject) {
        try {
            KeycloakUser user = withAuthRetry(token -> restClient.get()
                    .uri("/users/{id}", authSubject)
                    .header(HttpHeaders.AUTHORIZATION, bearer(token))
                    .retrieve()
                    .body(KeycloakUser.class));
            return Optional.ofNullable(user);
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 404) {
                return Optional.empty();
            }
            throw translate("look up user", exception);
        } catch (RestClientException exception) {
            throw translate("look up user", exception);
        }
    }

    @Override
    public void setUserEnabled(String authSubject, boolean enabled) {
        try {
            withAuthRetry(token -> restClient.put()
                    .uri("/users/{id}", authSubject)
                    .header(HttpHeaders.AUTHORIZATION, bearer(token))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new EnabledUpdate(enabled))
                    .retrieve()
                    .toBodilessEntity());
            log.info("Keycloak account enabled={} for subject {}", enabled, authSubject);
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 404) {
                throw new KeycloakAdminException.UserNotFoundInRealmException(authSubject);
            }
            throw translate("update account state", exception);
        } catch (RestClientException exception) {
            throw translate("update account state", exception);
        }
    }

    @Override
    public void logoutUser(String authSubject) {
        try {
            withAuthRetry(token -> restClient.post()
                    .uri("/users/{id}/logout", authSubject)
                    .header(HttpHeaders.AUTHORIZATION, bearer(token))
                    .retrieve()
                    .toBodilessEntity());
            log.info("Revoked all Keycloak sessions for subject {}", authSubject);
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 404) {
                throw new KeycloakAdminException.UserNotFoundInRealmException(authSubject);
            }
            throw translate("revoke sessions", exception);
        } catch (RestClientException exception) {
            throw translate("revoke sessions", exception);
        }
    }

    @Override
    public List<NavioRole> realmRolesOf(String authSubject) {
        try {
            KeycloakRole[] roles = withAuthRetry(token -> restClient.get()
                    .uri("/users/{id}/role-mappings/realm", authSubject)
                    .header(HttpHeaders.AUTHORIZATION, bearer(token))
                    .retrieve()
                    .body(KeycloakRole[].class));
            if (roles == null) {
                return List.of();
            }
            return Arrays.stream(roles)
                    .map(KeycloakRole::name)
                    .map(NavioRole::fromClaim)
                    .flatMap(Optional::stream)
                    .toList();
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 404) {
                throw new KeycloakAdminException.UserNotFoundInRealmException(authSubject);
            }
            throw translate("read role mappings", exception);
        } catch (RestClientException exception) {
            throw translate("read role mappings", exception);
        }
    }

    @Override
    public void grantRealmRole(String authSubject, NavioRole role) {
        KeycloakRole realmRole = requireRealmRole(role);
        try {
            withAuthRetry(token -> restClient.post()
                    .uri("/users/{id}/role-mappings/realm", authSubject)
                    .header(HttpHeaders.AUTHORIZATION, bearer(token))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(List.of(realmRole))
                    .retrieve()
                    .toBodilessEntity());
            log.info("Granted realm role {} to subject {}", role, authSubject);
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 404) {
                throw new KeycloakAdminException.UserNotFoundInRealmException(authSubject);
            }
            throw translate("grant role", exception);
        } catch (RestClientException exception) {
            throw translate("grant role", exception);
        }
    }

    @Override
    public void revokeRealmRole(String authSubject, NavioRole role) {
        KeycloakRole realmRole = requireRealmRole(role);
        try {
            withAuthRetry(token -> restClient.method(org.springframework.http.HttpMethod.DELETE)
                    .uri("/users/{id}/role-mappings/realm", authSubject)
                    .header(HttpHeaders.AUTHORIZATION, bearer(token))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(List.of(realmRole))
                    .retrieve()
                    .toBodilessEntity());
            log.info("Revoked realm role {} from subject {}", role, authSubject);
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 404) {
                throw new KeycloakAdminException.UserNotFoundInRealmException(authSubject);
            }
            throw translate("revoke role", exception);
        } catch (RestClientException exception) {
            throw translate("revoke role", exception);
        }
    }

    /**
     * Keycloak's role-mapping endpoints need the role's realm id, not just its
     * name, so the definition is resolved first.
     */
    private KeycloakRole requireRealmRole(NavioRole role) {
        try {
            KeycloakRole found = withAuthRetry(token -> restClient.get()
                    .uri("/roles/{roleName}", role.name())
                    .header(HttpHeaders.AUTHORIZATION, bearer(token))
                    .retrieve()
                    .body(KeycloakRole.class));
            if (found == null || found.id() == null) {
                throw new KeycloakAdminException.RoleNotFoundException(role.name());
            }
            return found;
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 404) {
                throw new KeycloakAdminException.RoleNotFoundException(role.name());
            }
            throw translate("resolve realm role", exception);
        } catch (RestClientException exception) {
            throw translate("resolve realm role", exception);
        }
    }

    /**
     * Runs a call with the cached token; on 401 refreshes once and retries.
     */
    private <T> T withAuthRetry(AdminCall<T> call) {
        try {
            return call.execute(tokenProvider.accessToken());
        } catch (RestClientResponseException exception) {
            HttpStatusCode status = exception.getStatusCode();
            if (status.value() != 401 && status.value() != 403) {
                throw exception;
            }
            log.warn("Keycloak admin call rejected with {}; refreshing service-account token", status.value());
            tokenProvider.invalidate();
            return call.execute(tokenProvider.accessToken());
        }
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    /**
     * Converts a transport failure into a domain exception.
     *
     * <p>The Keycloak response body is logged for operators but never attached to
     * the thrown message: it can contain realm and client identifiers that should
     * not reach an API consumer.
     */
    private KeycloakAdminException translate(String operation, Exception exception) {
        log.error("Keycloak admin operation '{}' failed against realm {}",
                operation, adminProperties.realm(), exception);
        return new KeycloakAdminException("Keycloak is unavailable or rejected the request", exception);
    }

    @FunctionalInterface
    private interface AdminCall<T> {
        T execute(String accessToken);
    }
}
