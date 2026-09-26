package com.navio.usermanagementservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navio.usermanagementservice.dto.VehicleCatalogResponse;
import com.navio.usermanagementservice.model.VehicleModel;
import com.navio.usermanagementservice.repository.VehicleModelRepository;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import java.util.Arrays;
import java.util.List;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Frozen source fixtures for existing garage tests; production no longer reads this file. */
public final class CatalogFixtures {
    public static List<VehicleCatalogResponse> vehicles(ObjectMapper json) throws Exception {
        try (var input = new ClassPathResource("vehicles/thailand.json").getInputStream()) {
            return Arrays.asList(json.readValue(input, VehicleCatalogResponse[].class));
        }
    }
    @SuppressWarnings("unchecked")
    public static VehicleCatalogService service(ObjectMapper json) throws Exception {
        var repository = mock(VehicleModelRepository.class);
        var models = vehicles(json).stream().map(vehicle -> {
            var model = json.convertValue(vehicle, VehicleModel.class);
            model.setVersion(0L); model.setStatus(VehicleModel.Status.PUBLISHED);
            return model;
        }).toList();
        lenient().when(repository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(new PageImpl<>(models));
        lenient().when(repository.findByIdAndStatus(anyString(), eq(VehicleModel.Status.PUBLISHED)))
                .thenAnswer(call -> models.stream().filter(model -> model.getId().equals(call.getArgument(0))).findFirst());
        return new VehicleCatalogService(repository);
    }
}
