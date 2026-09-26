package com.navio.usermanagementservice.service;

import com.navio.usermanagementservice.dto.VehicleCatalogResponse;
import com.navio.usermanagementservice.exception.UserManagementExceptions.BusinessRuleException;
import com.navio.usermanagementservice.model.VehicleModel.Status;
import com.navio.usermanagementservice.repository.VehicleModelRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;

import java.util.List;

@Service @RequiredArgsConstructor @Transactional(readOnly = true)
public class VehicleCatalogService {
    private final VehicleModelRepository repository;

    /** Compatibility array endpoint. New clients use the paginated public collection. */
    public List<VehicleCatalogResponse> listVehicles() {
        return repository.findAll((root, query, cb) -> cb.equal(root.get("status"), Status.PUBLISHED),
                PageRequest.of(0, 1000, Sort.by("make", "model", "id")))
                .map(VehicleModelService::publicResponse).getContent();
    }

    public VehicleCatalogResponse requireVehicle(String catalogId) {
        return repository.findByIdAndStatus(catalogId, Status.PUBLISHED).map(VehicleModelService::publicResponse)
                .orElseThrow(() -> new BusinessRuleException("This vehicle is no longer in the catalog. Refresh and choose again."));
    }
}
