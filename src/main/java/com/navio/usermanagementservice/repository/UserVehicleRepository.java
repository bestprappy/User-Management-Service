package com.navio.usermanagementservice.repository;

import com.navio.usermanagementservice.model.UserVehicle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserVehicleRepository extends JpaRepository<UserVehicle, UUID> {

    /**
     * Every read is scoped by {@code userId} in the query itself.
     *
     * <p>Deliberately there is no {@code findById(vehicleId)} in the service
     * layer: fetching by id alone and checking ownership afterwards is the shape
     * that produces IDOR bugs when someone forgets the second step. Making the
     * scoped query the only available one removes the chance to forget.
     */
    Optional<UserVehicle> findByIdAndUserIdAndDeletedAtIsNull(UUID id, UUID userId);

    List<UserVehicle> findByUserIdAndDeletedAtIsNullOrderByIsDefaultDescCreatedAtAsc(UUID userId);

    Optional<UserVehicle> findByUserIdAndIsDefaultTrueAndDeletedAtIsNull(UUID userId);

    long countByUserIdAndDeletedAtIsNull(UUID userId);

    /**
     * Clears the default flag across a user's garage before a new default is set.
     *
     * <p>Runs as one statement so the partial unique index
     * {@code uq_iam_user_vehicles_user_default} is never transiently violated by
     * a read-modify-write loop under concurrency.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE UserVehicle v
               SET v.isDefault = false, v.updatedAt = CURRENT_TIMESTAMP
             WHERE v.userId = :userId
               AND v.isDefault = true
               AND v.deletedAt IS NULL
               AND (:exceptId IS NULL OR v.id <> :exceptId)
            """)
    int clearDefaultForUser(@Param("userId") UUID userId, @Param("exceptId") UUID exceptId);
}
