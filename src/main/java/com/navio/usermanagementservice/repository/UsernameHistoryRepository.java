package com.navio.usermanagementservice.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Previously used handles, for redirects and the reservation hold. */
@Repository
@RequiredArgsConstructor
public class UsernameHistoryRepository {

    private final JdbcTemplate jdbc;

    public void recordRelease(String username, UUID userId, Instant releasedAt) {
        jdbc.update("""
                INSERT INTO iam.username_history(username, user_id, released_at) VALUES (?, ?, ?)
                ON CONFLICT (username, user_id) DO UPDATE SET released_at = EXCLUDED.released_at
                """, username, userId, Timestamp.from(releasedAt));
    }

    /** True when another account released this handle after {@code since}. */
    public boolean heldByAnotherAccount(String username, UUID userId, Instant since) {
        Boolean held = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM iam.username_history
                               WHERE username = ? AND user_id <> ? AND released_at > ?)
                """, Boolean.class, username, userId, Timestamp.from(since));
        return Boolean.TRUE.equals(held);
    }

    /** The account that most recently released this handle, if any. */
    public Optional<UUID> mostRecentOwner(String username) {
        return jdbc.query("""
                SELECT user_id FROM iam.username_history WHERE username = ?
                ORDER BY released_at DESC LIMIT 1
                """, (row, index) -> row.getObject(1, UUID.class), username).stream().findFirst();
    }
}
