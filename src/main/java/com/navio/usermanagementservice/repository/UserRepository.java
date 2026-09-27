package com.navio.usermanagementservice.repository;

import com.navio.usermanagementservice.model.User;
import com.navio.usermanagementservice.model.UserStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    @Query(value = "SELECT id FROM iam.users WHERE id = :userId FOR UPDATE", nativeQuery = true)
    UUID lockPicture(@Param("userId") UUID userId);

    /** Serializes garage writes, including the first insert when no vehicle row exists yet. */
    @Query(value = "SELECT id FROM iam.users WHERE id = :userId FOR UPDATE", nativeQuery = true)
    UUID lockGarage(@Param("userId") UUID userId);

    /**
     * Serializes saved-place writes, including the first insert.
     *
     * <p>Takes the same user row lock as {@link #lockGarage}; kept as a separate
     * method so each call site reads as what it is actually protecting.
     */
    @Query(value = "SELECT id FROM iam.users WHERE id = :userId FOR UPDATE", nativeQuery = true)
    UUID lockSavedPlaces(@Param("userId") UUID userId);

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
    Page<User> findAllBy(Pageable pageable);

    Page<User> findByStatus(UserStatus status, Pageable pageable);

    @Query("""
            SELECT u FROM User u
            WHERE lower(u.email) LIKE lower(concat('%', :term, '%'))
               OR lower(u.displayName) LIKE lower(concat('%', :term, '%'))
            """)
    Page<User> searchByTerm(@Param("term") String term, Pageable pageable);

    @Query("""
            SELECT u FROM User u
            WHERE u.status = :status
              AND (lower(u.email) LIKE lower(concat('%', :term, '%'))
                   OR lower(u.displayName) LIKE lower(concat('%', :term, '%')))
            """)
    Page<User> searchByTermAndStatus(@Param("term") String term,
                                     @Param("status") UserStatus status,
                                     Pageable pageable);

    boolean existsByAuthSubject(String authSubject);

    /**
     * Locks the given profiles in a fixed (id) order for a privileged action.
     *
     * <p>Two administrators acting on each other at the same moment would
     * otherwise both pass their checks and could remove every administrator.
     * Locking both rows, always in the same order, makes the second action wait
     * for the first and then re-check against its committed result — without
     * risking a deadlock from opposite lock orders.
     */
    @Query(value = "SELECT id FROM iam.users WHERE id IN (:userIds) ORDER BY id FOR UPDATE", nativeQuery = true)
    List<UUID> lockForModeration(@Param("userIds") Collection<UUID> userIds);

    long countByDeletedAtIsNullAndStatusNot(UserStatus status);

    long countByDeletedAtIsNullAndStatus(UserStatus status);

    long countByDeletedAtIsNullAndStatusNotAndCreatedAtGreaterThanEqual(UserStatus status, Instant since);
}
