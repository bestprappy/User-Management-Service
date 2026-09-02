package com.navio.usermanagementservice.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The audience check is the control that stops a token minted for a different
 * client in the same realm from authenticating here, so each rejection path is
 * asserted explicitly.
 */
class AudienceValidatorTest {

    private static final String EXPECTED_AUDIENCE = "navio-api";

    private final AudienceValidator validator = new AudienceValidator(List.of(EXPECTED_AUDIENCE));

    @Test
    void acceptsTokenWithTheExpectedAudience() {
        OAuth2TokenValidatorResult result = validator.validate(tokenWithAudience(List.of(EXPECTED_AUDIENCE)));

        assertThat(result.hasErrors()).isFalse();
    }

    @Test
    void acceptsTokenWhenExpectedAudienceIsOneOfSeveral() {
        OAuth2TokenValidatorResult result =
                validator.validate(tokenWithAudience(List.of("account", EXPECTED_AUDIENCE)));

        assertThat(result.hasErrors()).isFalse();
    }

    @Test
    void rejectsTokenMintedForAnotherClient() {
        // The realm's default audience when no Audience mapper is configured.
        // Accepting it would mean any client in the realm could call this API.
        OAuth2TokenValidatorResult result = validator.validate(tokenWithAudience(List.of("account")));

        assertThat(result.hasErrors()).isTrue();
    }

    @Test
    void rejectsTokenWithNoAudienceClaim() {
        OAuth2TokenValidatorResult result = validator.validate(tokenWithAudience(null));

        assertThat(result.hasErrors()).isTrue();
    }

    @Test
    void rejectsTokenWithEmptyAudienceList() {
        OAuth2TokenValidatorResult result = validator.validate(tokenWithAudience(List.of()));

        assertThat(result.hasErrors()).isTrue();
    }

    @Test
    void errorMessageDoesNotDiscloseTheExpectedAudience() {
        OAuth2TokenValidatorResult result = validator.validate(tokenWithAudience(List.of("account")));

        // An unauthenticated caller should not be able to learn the realm's
        // client layout from a rejection message.
        assertThat(result.getErrors())
                .allSatisfy(error -> assertThat(error.getDescription()).doesNotContain(EXPECTED_AUDIENCE));
    }

    @Test
    void refusesToBeConstructedWithNoAcceptedAudience() {
        // A validator with an empty allowlist would accept nothing, or worse be
        // read as "no audience requirement". Fail at construction instead.
        assertThatThrownBy(() -> new AudienceValidator(List.of()))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new AudienceValidator(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private Jwt tokenWithAudience(List<String> audience) {
        Jwt.Builder builder = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("subject-1")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .claims(claims -> claims.putAll(Map.of()));

        if (audience != null) {
            builder.audience(audience);
        }
        return builder.build();
    }
}
