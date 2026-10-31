package com.navio.usermanagementservice.repository;

import com.navio.usermanagementservice.model.User;
import com.navio.usermanagementservice.model.UserStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.HashMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The people-search JPQL against real PostgreSQL, including LIKE escaping. Rolled back. */
@DataJpaTest(properties = {
        "spring.datasource.url=${NAVIO_TEST_DB_URL}",
        "spring.datasource.username=${NAVIO_TEST_DB_USERNAME:tripplanner}",
        "spring.datasource.password=${NAVIO_TEST_DB_PASSWORD:tripplanner}",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        "spring.flyway.schemas=iam",
        "spring.flyway.default-schema=iam",
        "spring.flyway.create-schemas=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "NAVIO_TEST_DB_URL", matches = ".+")
class PeopleSearchPostgresTests {

    @Autowired UserRepository users;

    private User save(String username, String displayName, boolean deleted) {
        String subject = UUID.randomUUID().toString();
        return users.saveAndFlush(User.builder().authSubject(subject).email(subject + "@example.com")
                .displayName(displayName).username(username).status(UserStatus.ACTIVE)
                .preferences(new HashMap<>()).deletedAt(deleted ? Instant.now() : null).build());
    }

    @Test
    void searchMatchesHandlePrefixesAndNamesButNeverClosedAccountsOrWildcards() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        save("kyoto_" + suffix, "Mina Tanaka", false);
        save("kyotoxwalker" + suffix, "Someone Else", false);
        save("closed_" + suffix, "Kyoto Closed " + suffix, true);
        save("ren_" + suffix, "Ren Kyoto " + suffix, false);

        var byHandle = users.searchPublicProfiles("kyoto!_" + suffix, "kyoto!_" + suffix, "kyoto_" + suffix, PageRequest.of(0, 10));
        assertThat(byHandle.getContent()).extracting(User::getUsername).containsExactly("kyoto_" + suffix);

        var byName = users.searchPublicProfiles("kyoto " + suffix, "Kyoto " + suffix, "kyoto " + suffix, PageRequest.of(0, 10));
        assertThat(byName.getContent()).extracting(User::getUsername).containsExactly("ren_" + suffix);
    }
}
