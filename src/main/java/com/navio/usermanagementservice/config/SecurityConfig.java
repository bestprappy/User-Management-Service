package com.navio.usermanagementservice.config;

import com.navio.usermanagementservice.security.AudienceValidator;
import com.navio.usermanagementservice.security.KeycloakRoleConverter;
import com.navio.usermanagementservice.security.NavioRole;
import com.navio.usermanagementservice.security.RestAccessDeniedHandler;
import com.navio.usermanagementservice.security.RestAuthenticationEntryPoint;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.XXssProtectionHeaderWriter;

import java.time.Duration;

/**
 * Resource-server security for the user management service.
 *
 * <h2>Trust model</h2>
 * The only accepted proof of identity is a Keycloak-issued JWT, verified locally
 * against the realm's JWKS. Nothing else authenticates a caller — in particular
 * the {@code X-User-Id} header used elsewhere in this system carries no trust
 * here and is never read.
 *
 * <h2>What is validated on every request</h2>
 * <ol>
 *   <li>signature, against the realm JWKS (fetched and cached by Nimbus)</li>
 *   <li>{@code iss} matches the configured issuer</li>
 *   <li>{@code exp} / {@code nbf}, with a bounded clock-skew allowance</li>
 *   <li>{@code aud} contains this API — see {@link AudienceValidator}</li>
 * </ol>
 * Suspension is enforced separately, per request, in the service layer.
 */
@Configuration
@EnableConfigurationProperties(KeycloakProperties.class)
@EnableMethodSecurity
public class SecurityConfig {

    private static final String ADMIN_PATTERN = "/v1/admin/**";
    private static final String[] HEALTH_ENDPOINTS = {"/actuator/health", "/actuator/health/**", "/actuator/info"};

    /**
     * Scraped by Prometheus over the internal Docker network with no credentials.
     *
     * <p>Safe only because nginx routes just {@code /v1/} and {@code /health} to
     * the gateway — no actuator path is publicly reachable. If that ever changes,
     * move management endpoints to a separate {@code management.server.port}
     * bound to the internal network.
     */
    private static final String[] METRICS_ENDPOINTS = {"/actuator/prometheus", "/actuator/metrics/**"};

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtDecoder jwtDecoder,
            JwtAuthenticationConverter jwtAuthenticationConverter,
            RestAuthenticationEntryPoint authenticationEntryPoint,
            RestAccessDeniedHandler accessDeniedHandler
    ) throws Exception {

        http
                // No cookies, no server-side session: every request must carry its
                // own bearer token, so there is no session to fixate or ride.
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // CSRF protection defends against the browser attaching ambient
                // credentials (cookies) to a forged request. This API has no
                // ambient credential — the caller must attach a bearer token that
                // a cross-site attacker cannot read — so CSRF tokens would add
                // ceremony without adding protection.
                .csrf(csrf -> csrf.disable())

                // CORS is terminated at the gateway. Defining a permissive policy
                // here as well would silently widen it.
                .cors(cors -> cors.disable())

                // Browsers should never treat an API response as a document.
                .headers(headers -> headers
                        .contentTypeOptions(Customizer.withDefaults())
                        .frameOptions(frame -> frame.deny())
                        .xssProtection(xss -> xss
                                .headerValue(XXssProtectionHeaderWriter.HeaderValue.ENABLED_MODE_BLOCK))
                        .referrerPolicy(referrer -> referrer
                                .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .maxAgeInSeconds(Duration.ofDays(365).toSeconds()))
                        .cacheControl(Customizer.withDefaults()))

                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HEALTH_ENDPOINTS).permitAll()
                        .requestMatchers(METRICS_ENDPOINTS).permitAll()

                        // CORS preflight carries no credentials and must not 401.
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                        // Coarse gate on the admin surface. Individual handlers
                        // narrow this further with @PreAuthorize — role changes,
                        // for instance, require ADMIN rather than MODERATOR.
                        .requestMatchers(ADMIN_PATTERN)
                        .hasAnyRole(NavioRole.MODERATOR.name(), NavioRole.ADMIN.name())

                        // Deny by default: any path not listed above still needs a
                        // valid token, so a newly added endpoint is protected even
                        // if its author forgets to say so.
                        .anyRequest().authenticated())

                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt
                                .decoder(jwtDecoder)
                                .jwtAuthenticationConverter(jwtAuthenticationConverter))
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))

                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler));

        return http.build();
    }

    /**
     * JWT decoder wired to the realm, with the full validator chain.
     *
     * <p>{@link JwtDecoders#fromIssuerLocation} performs OIDC discovery, so the
     * JWKS URI comes from the issuer's own metadata rather than being configured
     * separately and drifting out of sync. That discovery is a network call made
     * while this bean is created, which is why {@code jwk-set-uri} exists as an
     * override: in the deployed stack the public issuer resolves through NGINX,
     * and NGINX cannot start until this service reports healthy.
     *
     * <p>Overriding the key source does not weaken validation. {@code iss} is
     * still checked against the configured public issuer below, so a token minted
     * by any other issuer is rejected regardless of where the keys came from.
     */
    @Bean
    public JwtDecoder jwtDecoder(KeycloakProperties properties) {
        NimbusJwtDecoder decoder = buildDecoder(properties);

        // Validators are assembled explicitly rather than via
        // JwtValidators.createDefaultWithIssuer() so the configured clock skew is
        // actually applied instead of the library's fixed 60-second default.
        OAuth2TokenValidator<Jwt> timestamps = new JwtTimestampValidator(properties.clockSkew());
        OAuth2TokenValidator<Jwt> issuer = new JwtIssuerValidator(properties.issuerUri());
        OAuth2TokenValidator<Jwt> audience = new AudienceValidator(properties.audiences());

        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(timestamps, issuer, audience));
        return decoder;
    }

    private static NimbusJwtDecoder buildDecoder(KeycloakProperties properties) {
        String jwkSetUri = properties.jwkSetUri();
        if (jwkSetUri != null && !jwkSetUri.isBlank()) {
            return NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        }
        return (NimbusJwtDecoder) JwtDecoders.fromIssuerLocation(properties.issuerUri());
    }

    /**
     * Maps Keycloak's role claims onto Spring authorities.
     *
     * <p>The configured audiences double as the trusted client ids for
     * {@code resource_access}: a client role only counts when it was granted on a
     * client this API actually accepts tokens for.
     */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter(KeycloakProperties properties) {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new KeycloakRoleConverter(properties.audiences()));

        // Authentication.getName() becomes the Keycloak subject, so audit and log
        // lines identify the account rather than a display name that can change.
        converter.setPrincipalClaimName("sub");
        return converter;
    }
}
