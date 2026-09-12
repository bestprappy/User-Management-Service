package com.navio.usermanagementservice.repository;

import com.navio.usermanagementservice.model.UserSavedPlace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserSavedPlaceRepository extends JpaRepository<UserSavedPlace, UUID> {

    /**
     * Every read is scoped by {@code userId} in the query itself.
     *
     * <p>As with {@link UserVehicleRepository}, there is deliberately no
     * find-by-id-alone: a saved place can hold a home address, so the shape that
     * fetches first and checks ownership afterwards is not offered at all.
     */
    Optional<UserSavedPlace> findByIdAndUserIdAndDeletedAtIsNull(UUID id, UUID userId);

    List<UserSavedPlace> findByUserIdAndDeletedAtIsNullOrderByIsDefaultDescCreatedAtAsc(UUID userId);

    Optional<UserSavedPlace> findByUserIdAndKindAndDeletedAtIsNull(
            UUID userId, UserSavedPlace.SavedPlaceKind kind);

    long countByUserIdAndDeletedAtIsNull(UUID userId);

    /**
     * Clears the default flag before a new default is set, as one statement so
     * {@code uq_iam_user_saved_places_user_default} is never transiently
     * violated by a read-modify-write loop under concurrency.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE UserSavedPlace p
               SET p.isDefault = false, p.updatedAt = CURRENT_TIMESTAMP
             WHERE p.userId = :userId
               AND p.isDefault = true
               AND p.deletedAt IS NULL
               AND (:exceptId IS NULL OR p.id <> :exceptId)
            """)
    int clearDefaultForUser(@Param("userId") UUID userId, @Param("exceptId") UUID exceptId);
}
