package com.navio.usermanagementservice.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navio.usermanagementservice.dto.VehicleCatalogResponse;
import com.navio.usermanagementservice.exception.UserManagementExceptions.BusinessRuleException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

@Service
public class VehicleCatalogService {
    private final List<VehicleCatalogResponse> vehicles;

    public VehicleCatalogService(ObjectMapper objectMapper) throws IOException {
        try (var input = new ClassPathResource("vehicles/thailand.json").getInputStream()) {
            vehicles = List.copyOf(objectMapper.readValue(input, new TypeReference<List<VehicleCatalogResponse>>() {}));
        }
    }

    /** Small curated catalogue; no live scraping or third-party dependency at request time. */
    public List<VehicleCatalogResponse> listVehicles() {
        return vehicles;
    }

    public VehicleCatalogResponse requireVehicle(String catalogId) {
        return vehicles.stream().filter(vehicle -> vehicle.id().equals(catalogId)).findFirst()
                .orElseThrow(() -> new BusinessRuleException("This vehicle is no longer in the catalogue. Refresh and choose again."));
    }
}
