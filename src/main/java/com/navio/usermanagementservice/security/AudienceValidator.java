package com.navio.usermanagementservice.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.Set;

/**
 * Rejects tokens that were not minted for this API.
 *
 * <p>Spring Security validates signature, issuer, {@code exp} and {@code nbf}
 * out of the box but <strong>not</strong> {@code aud}. Without this check, any
 * token from the same realm — including one issued to a low-trust public client
 * for a completely different application — would authenticate successfully here.
 * That turns every other client in the realm into a confused deputy.
 *
 * <p>Requires a Keycloak <em>Audience</em> protocol mapper on the client, since
 * Keycloak otherwise emits only {@code "aud": "account"}.
 */
public class AudienceValidator implements OAuth2TokenValidator<Jwt> {

    private static final String DESCRIPTION =
            "The required audience is missing from the access token";

    private final Set<String> acceptedAudiences;

    public AudienceValidator(List<String> acceptedAudiences) {
        if (acceptedAudiences == null || acceptedAudiences.isEmpty()) {
            throw new IllegalArgumentException("At least one accepted audience must be configured");
        }
        this.acceptedAudiences = Set.copyOf(acceptedAudiences);
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        List<String> tokenAudiences = token.getAudience();
        if (tokenAudiences != null && tokenAudiences.stream().anyMatch(acceptedAudiences::contains)) {
            return OAuth2TokenValidatorResult.success();
        }
        // The error deliberately does not echo the token's actual audience:
        // an unauthenticated caller should not learn the realm's client layout.
        return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                OAuth2ErrorCodes.INVALID_TOKEN,
                DESCRIPTION,
                "https://tools.ietf.org/html/rfc6750#section-3.1"
        ));
    }
}
