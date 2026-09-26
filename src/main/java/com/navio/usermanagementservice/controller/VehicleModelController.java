package com.navio.usermanagementservice.controller;

import com.navio.usermanagementservice.dto.VehicleCatalogResponse;
import com.navio.usermanagementservice.service.VehicleModelService;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/v1/vehicle-models") @RequiredArgsConstructor @Validated
public class VehicleModelController {
    private final VehicleModelService service;
    @GetMapping("/{id}")
    public VehicleCatalogResponse get(@PathVariable String id) { return service.publishedDetail(id); }
    @GetMapping
    public Page<VehicleCatalogResponse> list(
            @RequestParam(required = false) @Size(max = 100) String term,
            @RequestParam(required = false) @Pattern(regexp = "[A-Z]{2}") String market,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.published(term, market, PageRequest.of(page, size, Sort.by("make", "model", "id")));
    }
}
