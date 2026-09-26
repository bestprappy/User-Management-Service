package com.navio.usermanagementservice.repository;

import com.navio.usermanagementservice.model.User;
import com.navio.usermanagementservice.model.UserStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The admin dashboard's aggregate and lock queries against real PostgreSQL.
 *
 * <p>{@code lockForModeration} is native SQL and the counts depend on the
 * status converter; H2 would prove neither. Skipped unless NAVIO_TEST_DB_URL is
 * set, like {@code PostgresSchemaTests}. Runs in a rolled-back transaction.
 */
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
class AdminUserRepositoryPostgresTests {

    @Autowired
    UserRepository userRepository;

    @Test
    void dashboardCountsSeparateStatusesAndIgnoreDeletedProfiles() {
        long totalBefore = userRepository.countByDeletedAtIsNullAndStatusNot(UserStatus.DELETED);
        long activeBefore = userRepository.countByDeletedAtIsNullAndStatus(UserStatus.ACTIVE);
        long suspendedBefore = userRepository.countByDeletedAtIsNullAndStatus(UserStatus.SUSPENDED);

        save(UserStatus.ACTIVE, null);
        save(UserStatus.SUSPENDED, null);
        save(UserStatus.DELETED, Instant.now());
        save(UserStatus.ACTIVE, Instant.now());

        assertThat(userRepository.countByDeletedAtIsNullAndStatusNot(UserStatus.DELETED)).isEqualTo(totalBefore + 2);
        assertThat(userRepository.countByDeletedAtIsNullAndStatus(UserStatus.ACTIVE)).isEqualTo(activeBefore + 1);
        assertThat(userRepository.countByDeletedAtIsNullAndStatus(UserStatus.SUSPENDED)).isEqualTo(suspendedBefore + 1);
        assertThat(userRepository.countByDeletedAtIsNullAndStatusNotAndCreatedAtGreaterThanEqual(
                UserStatus.DELETED, Instant.now().minusSeconds(60))).isGreaterThanOrEqualTo(2);
    }

    @Test
    void moderationLockTakesBothRowsInTheSameOrderWhicheverSideAsks() {
        User first = save(UserStatus.ACTIVE, null);
        User second = save(UserStatus.ACTIVE, null);

        // Admin A acting on B and admin B acting on A must lock in one order, or
        // the two transactions can deadlock. (Postgres orders uuid bytes
        // unsigned, unlike Java's UUID.compareTo, so compare the two calls
        // rather than a Java-sorted list.)
        List<UUID> fromFirst = userRepository.lockForModeration(List.of(first.getId(), second.getId()));
        List<UUID> fromSecond = userRepository.lockForModeration(List.of(second.getId(), first.getId()));

        assertThat(fromFirst).containsExactlyInAnyOrder(first.getId(), second.getId());
        assertThat(fromSecond).containsExactlyElementsOf(fromFirst);
    }

    private User save(UserStatus status, Instant deletedAt) {
        String subject = "test-" + UUID.randomUUID();
        return userRepository.saveAndFlush(User.builder()
                .authSubject(subject)
                .email(subject + "@example.com")
                .displayName("Test")
                .status(status)
                .deletedAt(deletedAt)
                .preferences(new HashMap<>())
                .build());
    }
}
