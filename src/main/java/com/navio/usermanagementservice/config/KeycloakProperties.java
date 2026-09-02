package com.navio.usermanagementservice.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

/**
 * Keycloak integration settings.
 *
 * <p>Every field that carries a security guarantee is mandatory and validated at
 * startup. A missing issuer or audience must fail the context refresh rather
 * than silently degrade into accepting unvalidated tokens.
 *
 * @param issuerUri        realm issuer, e.g. {@code https://sso.example/realms/navio}.
 *                         Tokens whose {@code iss} differs are rejected.
 * @param audiences        accepted {@code aud} values. Spring Security does not
 *                         validate audience by default, so a token minted for a
 *                         different client in the same realm would otherwise be
 *                         accepted here.
 * @param clockSkew        leeway for {@code exp}/{@code nbf} to absorb clock drift.
 * @param admin            credentials for the Keycloak Admin REST API.
 */
@Validated
@ConfigurationProperties(prefix = "navio.security.keycloak")
public record KeycloakProperties(

        @NotBlank(message = "navio.security.keycloak.issuer-uri is required")
        String issuerUri,

        @NotEmpty(message = "navio.security.keycloak.audiences must list at least one accepted audience")
        List<@NotBlank String> audiences,

        @DefaultValue("30s")
        Duration clockSkew,

        @NotNull
        Admin admin
) {

    /**
     * Service-account credentials used for privileged Keycloak operations
     * (enable/disable an account, grant/revoke a realm role).
     *
     * <p>The service account must be granted only the {@code realm-management}
     * roles it actually needs — {@code manage-users} and {@code manage-realm} are
     * sufficient. Do not grant {@code realm-admin}.
     *
     * @param baseUrl        Keycloak base URL, e.g. {@code https://sso.example}.
     * @param realm          realm that owns Navio users.
     * @param clientId       confidential client backing the service account.
     * @param clientSecret   client secret; injected from the environment only.
     * @param connectTimeout TCP connect timeout for admin calls.
     * @param readTimeout    read timeout for admin calls.
     */
    public record Admin(

            @NotBlank(message = "navio.security.keycloak.admin.base-url is required")
            String baseUrl,

            @NotBlank(message = "navio.security.keycloak.admin.realm is required")
            String realm,

            @NotBlank(message = "navio.security.keycloak.admin.client-id is required")
            String clientId,

            @NotBlank(message = "navio.security.keycloak.admin.client-secret is required")
            String clientSecret,

            @DefaultValue("3s")
            Duration connectTimeout,

            @DefaultValue("8s")
            Duration readTimeout
    ) {

        /**
         * Never let the secret reach a log line, a stack trace, or an actuator
         * dump. Records generate a toString() that would otherwise print it.
         */
        @Override
        public String toString() {
            return "Admin[baseUrl=%s, realm=%s, clientId=%s, clientSecret=***]"
                    .formatted(baseUrl, realm, clientId);
        }

        public String tokenEndpoint() {
            return "%s/realms/%s/protocol/openid-connect/token".formatted(trimmedBaseUrl(), realm);
        }

        public String adminRealmEndpoint() {
            return "%s/admin/realms/%s".formatted(trimmedBaseUrl(), realm);
        }

        private String trimmedBaseUrl() {
            return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        }
    }
}
