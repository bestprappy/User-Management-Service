package com.navio.usermanagementservice.repository;

import com.navio.usermanagementservice.media.PictureValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.Optional;
import java.util.UUID;

/** Bounded, normalized avatar pixels. No client filename or external URL is stored. */
@Repository
@RequiredArgsConstructor
public class UserPictureRepository {
    private final JdbcTemplate jdbc;

    public void save(UUID userId, UUID id, PictureValidator.Picture picture) {
        jdbc.update("""
                INSERT INTO iam.user_pictures(user_id,id,content_type,image_bytes) VALUES(?,?,?,?)
                ON CONFLICT(user_id) DO UPDATE SET id=EXCLUDED.id,content_type=EXCLUDED.content_type,image_bytes=EXCLUDED.image_bytes
                """, userId, id, picture.contentType(), picture.bytes());
    }

    public Optional<PictureValidator.Picture> find(UUID userId) {
        return jdbc.query("""
                SELECT p.image_bytes,p.content_type FROM iam.user_pictures p JOIN iam.users u
                ON u.id=p.user_id AND u.avatar_media_id=p.id WHERE p.user_id=? AND u.deleted_at IS NULL
                """, (r, n) -> new PictureValidator.Picture(r.getBytes(1), r.getString(2)), userId).stream().findFirst();
    }

    public void delete(UUID userId) { jdbc.update("DELETE FROM iam.user_pictures WHERE user_id=?", userId); }
}
