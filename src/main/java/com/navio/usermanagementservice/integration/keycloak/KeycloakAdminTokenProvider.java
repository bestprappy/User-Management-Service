package com.navio.usermanagementservice.integration.keycloak;

import com.navio.usermanagementservice.config.KeycloakProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Supplies and caches the service-account access token used for Admin API calls.
 *
 * <p>The token is fetched with the {@code client_credentials} grant and reused
 * until shortly before it expires. Caching is not just a performance concern:
 * requesting a fresh token per admin operation multiplies the number of times
 * the client secret crosses the network.
 *
 * <p>The token is held in memory only — never logged, never persisted, never
 * placed in an audit payload or an error response.
 */
@Component
@Slf4j
public class KeycloakAdminTokenProvider {

    /**
     * Renew this long before nominal expiry so an in-flight request cannot be
     * rejected by a token that expires mid-call.
     */
    private static final Duration EXPIRY_MARGIN = Duration.ofSeconds(30);

    private final KeycloakProperties.Admin adminProperties;
    private final RestClient restClient;
    private final ReentrantLock refreshLock = new ReentrantLock();

    private volatile CachedToken cachedToken;

    public KeycloakAdminTokenProvider(KeycloakProperties properties, RestClient.Builder restClientBuilder) {
        this.adminProperties = properties.admin();
        this.restClient = restClientBuilder.clone().build();
    }

    /**
     * @return a currently valid bearer token for the Keycloak Admin API.
     * @throws KeycloakAdminException when a token cannot be obtained.
     */
    public String accessToken() {
        CachedToken current = cachedToken;
        if (current != null && current.isUsableAt(Instant.now())) {
            return current.value();
        }

        refreshLock.lock();
        try {
            // Re-check inside the lock: a concurrent caller may have refreshed
            // while this thread was waiting.
            CachedToken afterLock = cachedToken;
            if (afterLock != null && afterLock.isUsableAt(Instant.now())) {
                return afterLock.value();
            }
            CachedToken refreshed = requestToken();
            cachedToken = refreshed;
            return refreshed.value();
        } finally {
            refreshLock.unlock();
        }
    }

    /** Drops the cached token so the next call re-authenticates. */
    public void invalidate() {
        cachedToken = null;
    }

    private CachedToken requestToken() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", adminProperties.clientId());
        form.add("client_secret", adminProperties.clientSecret());

        try {
            TokenResponse response = restClient.post()
                    .uri(adminProperties.tokenEndpoint())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(TokenResponse.class);

            if (response == null || response.accessToken() == null || response.accessToken().isBlank()) {
                throw new KeycloakAdminException("Keycloak returned an empty service-account token");
            }

            Instant expiresAt = Instant.now().plusSeconds(Math.max(response.expiresIn(), 1));
            log.debug("Refreshed Keycloak admin token for client {}", adminProperties.clientId());
            return new CachedToken(response.accessToken(), expiresAt);
        } catch (RestClientException exception) {
            // The exception message is logged but not propagated verbatim: a
            // Keycloak error body can echo back the client_id and endpoint.
            log.error("Failed to obtain a Keycloak service-account token", exception);
            throw new KeycloakAdminException("Unable to authenticate against Keycloak", exception);
        }
    }

    private record CachedToken(String value, Instant expiresAt) {

        boolean isUsableAt(Instant now) {
            return now.isBefore(expiresAt.minus(EXPIRY_MARGIN));
        }

        @Override
        public String toString() {
            return "CachedToken[value=***, expiresAt=%s]".formatted(expiresAt);
        }
    }

    /** Subset of the OIDC token response this service needs. */
    private record TokenResponse(
            @com.fasterxml.jackson.annotation.JsonProperty("access_token") String accessToken,
            @com.fasterxml.jackson.annotation.JsonProperty("expires_in") long expiresIn
    ) {
        @Override
        public String toString() {
            return "TokenResponse[access_token=***, expires_in=%d]".formatted(expiresIn);
        }
    }
}
