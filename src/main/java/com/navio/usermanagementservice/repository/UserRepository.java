package com.navio.usermanagementservice.repository;

import com.navio.usermanagementservice.model.User;
import com.navio.usermanagementservice.model.UserStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    /** Primary lookup for an authenticated request: Keycloak {@code sub} to profile. */
    Optional<User> findByAuthSubject(String authSubject);

    /**
     * Case-insensitive email lookup, matching the {@code uq_iam_users_email_lower}
     * index so a duplicate cannot slip in through casing.
     */
    @Query("SELECT u FROM User u WHERE lower(u.email) = lower(:email)")
    Optional<User> findByEmailIgnoreCase(@Param("email") String email);

    Optional<User> findByIdAndDeletedAtIsNull(UUID id);

    /**
     * Admin user search.
     *
     * <p>The term is matched with a parameterised LIKE — never string
     * concatenation — so a search box cannot become an injection point. Deleted
     * profiles stay listed for moderators, since suppressing them would hide
     * exactly the accounts an investigation cares about.
     */
    @Query("""
            SELECT u FROM User u
            WHERE (:status IS NULL OR u.status = :status)
              AND (:term IS NULL
                   OR lower(u.email) LIKE lower(concat('%', :term, '%'))
                   OR lower(u.displayName) LIKE lower(concat('%', :term, '%')))
            """)
    Page<User> search(@Param("term") String term,
                      @Param("status") UserStatus status,
                      Pageable pageable);

    boolean existsByAuthSubject(String authSubject);
}
