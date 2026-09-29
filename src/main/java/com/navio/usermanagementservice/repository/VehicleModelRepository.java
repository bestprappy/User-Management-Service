package com.navio.usermanagementservice.repository;

import com.navio.usermanagementservice.model.VehicleModel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import java.util.Optional;

public interface VehicleModelRepository extends JpaRepository<VehicleModel, String>, JpaSpecificationExecutor<VehicleModel> {
    Optional<VehicleModel> findByIdAndStatus(String id, VehicleModel.Status status);
}
