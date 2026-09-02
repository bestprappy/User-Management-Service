package com.navio.usermanagementservice.repository;

import com.navio.usermanagementservice.model.UserRoleAssignment;
import com.navio.usermanagementservice.security.NavioRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Access to the {@code iam.user_roles} display snapshot.
 *
 * <p>Reminder for callers: this is not an authorization source. Do not gate an
 * endpoint on these rows — use the authorities derived from the validated JWT.
 */
public interface UserRoleRepository extends JpaRepository<UserRoleAssignment, UserRoleAssignment.UserRoleId> {

    List<UserRoleAssignment> findByUserId(UUID userId);

    void deleteByUserIdAndRole(UUID userId, NavioRole role);

    boolean existsByUserIdAndRole(UUID userId, NavioRole role);
}
