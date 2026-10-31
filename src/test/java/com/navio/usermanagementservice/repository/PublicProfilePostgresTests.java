package com.navio.usermanagementservice.repository;

import com.navio.usermanagementservice.media.PictureValidator;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V11 (public profile) against real PostgreSQL, in a throwaway database so the upgrade from V10
 * with existing rows can be replayed: the username backfill, the uniqueness and
 * format rules, the banner ownership key and the handle history.
 *
 * <p>Needs a role that may create databases. Skipped unless NAVIO_TEST_DB_URL is set.
 */
@EnabledIfEnvironmentVariable(named = "NAVIO_TEST_DB_URL", matches = ".+")
class PublicProfilePostgresTests {

    private static final String USER = System.getenv().getOrDefault("NAVIO_TEST_DB_USERNAME", "tripplanner");
    private static final String PASSWORD = System.getenv().getOrDefault("NAVIO_TEST_DB_PASSWORD", "tripplanner");

    @Test
    void upgradingWithExistingProfilesGivesEachAUniqueHandleAndEnforcesTheNewRules() {
        String url = System.getenv("NAVIO_TEST_DB_URL");
        String database = "iam_profile_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        var admin = new JdbcTemplate(new DriverManagerDataSource(url, USER, PASSWORD));
        admin.execute("CREATE DATABASE " + database);
        try {
            var source = new DriverManagerDataSource(url.replaceFirst("/[^/?]+(\\?|$)", "/" + database + "$1"), USER, PASSWORD);
            var jdbc = new JdbcTemplate(source);
            Flyway.configure().dataSource(source).schemas("iam").defaultSchema("iam")
                    .target(MigrationVersion.fromVersion("10")).load().migrate();

            UUID first = insert(jdbc, "Alex Morgan", "2026-01-01T00:00:00Z");
            UUID second = insert(jdbc, "alex  morgan!", "2026-01-02T00:00:00Z");
            UUID numeric = insert(jdbc, "123", "2026-01-03T00:00:00Z");
            UUID thai = insert(jdbc, "นักเดินทาง", "2026-01-04T00:00:00Z");

            Flyway.configure().dataSource(source).schemas("iam").defaultSchema("iam")
                    .ignoreMigrationPatterns("*:missing").load().migrate();

            assertThat(username(jdbc, first)).isEqualTo("alexmorgan");
            assertThat(username(jdbc, second)).isEqualTo("alexmorgan2");
            assertThat(username(jdbc, numeric)).isEqualTo("traveler123");
            assertThat(username(jdbc, thai)).isEqualTo("traveler");

            // Lowercase-only format and uniqueness are enforced by the database itself.
            assertThatThrownBy(() -> jdbc.update("UPDATE iam.users SET username='Alex' WHERE id=?", numeric))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update("UPDATE iam.users SET username='alexmorgan' WHERE id=?", numeric))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update("UPDATE iam.users SET travel_pace='warp' WHERE id=?", numeric))
                    .isInstanceOf(DataIntegrityViolationException.class);

            // An older image inserting without the new columns still works.
            UUID legacy = UUID.randomUUID();
            jdbc.update("INSERT INTO iam.users(id,auth_subject,email,display_name) VALUES(?,?,?,?)",
                    legacy, legacy.toString(), legacy + "@example.com", "Legacy");
            assertThat(username(jdbc, legacy)).isNull();
            assertThat(jdbc.queryForObject("SELECT show_travel_preferences FROM iam.users WHERE id=?", Boolean.class, legacy)).isTrue();

            // A profile can only point at its own banner.
            var banners = new UserBannerRepository(jdbc);
            UUID bannerId = UUID.randomUUID();
            banners.save(first, bannerId, new PictureValidator.Picture(new byte[]{1, 2, 3}, "image/jpeg"));
            jdbc.update("UPDATE iam.users SET banner_media_id=? WHERE id=?", bannerId, first);
            assertThat(banners.find(first)).isPresent();
            assertThat(banners.find(second)).isEmpty();
            assertThatThrownBy(() -> jdbc.update("UPDATE iam.users SET banner_media_id=? WHERE id=?", bannerId, second))
                    .isInstanceOf(DataIntegrityViolationException.class);

            // Released handles stay attributed to their previous owner.
            var history = new UsernameHistoryRepository(jdbc);
            Instant released = Instant.parse("2026-10-01T00:00:00Z");
            history.recordRelease("old_alex", first, released);
            assertThat(history.mostRecentOwner("old_alex")).contains(first);
            assertThat(history.heldByAnotherAccount("old_alex", second, released.minusSeconds(60))).isTrue();
            assertThat(history.heldByAnotherAccount("old_alex", first, released.minusSeconds(60))).isFalse();
            assertThat(history.heldByAnotherAccount("old_alex", second, released.plusSeconds(60))).isFalse();
        } finally {
            admin.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
        }
    }

    private static UUID insert(JdbcTemplate jdbc, String displayName, String createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO iam.users(id,auth_subject,email,display_name,created_at) VALUES(?,?,?,?,?::timestamptz)",
                id, id.toString(), id + "@example.com", displayName, createdAt);
        return id;
    }

    private static String username(JdbcTemplate jdbc, UUID id) {
        return jdbc.queryForObject("SELECT username FROM iam.users WHERE id=?", String.class, id);
    }
}
