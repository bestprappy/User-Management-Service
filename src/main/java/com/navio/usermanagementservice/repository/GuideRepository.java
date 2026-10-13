package com.navio.usermanagementservice.repository;

import com.navio.usermanagementservice.model.Guide;
import com.navio.usermanagementservice.model.Guide.Status;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;
import java.util.UUID;

public interface GuideRepository extends JpaRepository<Guide, UUID>, JpaSpecificationExecutor<Guide> {
    Page<Guide> findByStatus(Status status, Pageable pageable);
    Optional<Guide> findByIdAndStatus(UUID id, Status status);
    boolean existsByDestinationProviderAndDestinationPlaceId(String provider, String placeId);
}
