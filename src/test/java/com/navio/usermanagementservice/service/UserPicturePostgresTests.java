package com.navio.usermanagementservice.service;

import com.navio.usermanagementservice.media.PictureValidator;
import com.navio.usermanagementservice.repository.UserPictureRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "PROFILE_TEST_DB_URL", matches = ".+")
class UserPicturePostgresTests {
    @Test void migrationsEnforcePictureOwnershipAndReplacement() throws Exception {
        var source = new DriverManagerDataSource(System.getenv("PROFILE_TEST_DB_URL"), "community_test", "");
        Flyway.configure().dataSource(source).schemas("iam").defaultSchema("iam").load().migrate();
        var jdbc = new JdbcTemplate(source);
        var pictures = new UserPictureRepository(jdbc);
        UUID first = UUID.randomUUID(), second = UUID.randomUUID(), pictureId = UUID.randomUUID();
        for (UUID user : new UUID[]{first,second}) jdbc.update("INSERT INTO iam.users(id,auth_subject,email,display_name) VALUES(?,?,?,?)",user,user.toString(),user+"@example.com","User");
        try {
            pictures.save(first,pictureId,new PictureValidator.Picture(new byte[]{1,2},"image/png"));
            jdbc.update("UPDATE iam.users SET avatar_media_id=? WHERE id=?",pictureId,first);
            assertThat(pictures.find(first)).isPresent();
            assertThat(pictures.find(second)).isEmpty();
            assertThatThrownBy(() -> jdbc.update("UPDATE iam.users SET avatar_media_id=? WHERE id=?",pictureId,second))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            // The deferred FK permits an atomic replacement while preventing any committed dangling reference.
            try (var connection = source.getConnection()) {
                connection.setAutoCommit(false);
                UUID next = UUID.randomUUID();
                try (var update = connection.prepareStatement("UPDATE iam.user_pictures SET id=? WHERE user_id=?")) {
                    update.setObject(1,next);update.setObject(2,first);update.executeUpdate();
                }
                try (var update = connection.prepareStatement("UPDATE iam.users SET avatar_media_id=? WHERE id=?")) {
                    update.setObject(1,next);update.setObject(2,first);update.executeUpdate();
                }
                connection.commit();
            }
            assertThat(pictures.find(first)).isPresent();
            jdbc.update("UPDATE iam.users SET avatar_media_id=NULL WHERE id=?",first);
            pictures.delete(first);
            assertThat(pictures.find(first)).isEmpty();
        } finally {
            jdbc.update("UPDATE iam.users SET avatar_media_id=NULL WHERE id IN (?,?)",first,second);
            jdbc.update("DELETE FROM iam.users WHERE id IN (?,?)",first,second);
        }
    }
}
