package com.navio.usermanagementservice;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Full-context startup check.
 *
 * <p>Disabled because starting this context needs real infrastructure, and every
 * dependency is one that must not be stubbed away casually:
 * <ul>
 *   <li>PostgreSQL — the {@code iam} schema uses JSONB, {@code TEXT[]}, partial
 *       unique indexes and a plpgsql trigger. H2 supports none of them, so an
 *       H2-backed context would prove the app starts against a schema that is
 *       not the one it runs on.</li>
 *   <li>Keycloak — {@code JwtDecoders.fromIssuerLocation} performs OIDC
 *       discovery during bean creation. Stubbing the decoder to make this pass
 *       would remove the very thing worth testing.</li>
 *   <li>The configuration server, for {@code spring.config.import}.</li>
 * </ul>
 *
 * <p>To enable it, add Testcontainers ({@code postgres} plus a JWKS stub or a
 * Keycloak container) and drop the {@link Disabled}. The security logic itself
 * is covered without infrastructure by the unit tests under
 * {@code security/} and {@code service/}.
 */
@SpringBootTest
@Disabled("Requires PostgreSQL, Keycloak and the config server. See the class javadoc.")
class UserManagementServiceApplicationTests {

    @Test
    void contextLoads() {
    }
}
