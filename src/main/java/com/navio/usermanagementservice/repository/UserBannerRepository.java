package com.navio.usermanagementservice.repository;

import com.navio.usermanagementservice.media.PictureValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/** Bounded, re-encoded profile banner pixels. No client filename or external URL is stored. */
@Repository
@RequiredArgsConstructor
public class UserBannerRepository {

    private final JdbcTemplate jdbc;

    public void save(UUID userId, UUID id, PictureValidator.Picture picture) {
        jdbc.update("""
                INSERT INTO iam.user_banners(user_id, id, content_type, image_bytes) VALUES (?, ?, ?, ?)
                ON CONFLICT (user_id) DO UPDATE
                SET id = EXCLUDED.id, content_type = EXCLUDED.content_type, image_bytes = EXCLUDED.image_bytes
                """, userId, id, picture.contentType(), picture.bytes());
    }

    /** Only the banner the profile currently points at, and never a closed account's. */
    public Optional<PictureValidator.Picture> find(UUID userId) {
        return jdbc.query("""
                SELECT b.image_bytes, b.content_type FROM iam.user_banners b
                JOIN iam.users u ON u.id = b.user_id AND u.banner_media_id = b.id
                WHERE b.user_id = ? AND u.deleted_at IS NULL
                """, (row, index) -> new PictureValidator.Picture(row.getBytes(1), row.getString(2)), userId)
                .stream().findFirst();
    }

    public void delete(UUID userId) {
        jdbc.update("DELETE FROM iam.user_banners WHERE user_id = ?", userId);
    }
}
