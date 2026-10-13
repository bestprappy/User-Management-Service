package com.navio.usermanagementservice.repository;

import com.navio.usermanagementservice.model.UserGuide;
import com.navio.usermanagementservice.model.Guide.Status;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import java.util.Optional;
import java.util.UUID;

public interface UserGuideRepository extends JpaRepository<UserGuide, UUID>, JpaSpecificationExecutor<UserGuide> {
    Optional<UserGuide> findByIdAndOwnerId(UUID id, UUID ownerId);
    Optional<UserGuide> findByIdAndStatus(UUID id, Status status);
}
