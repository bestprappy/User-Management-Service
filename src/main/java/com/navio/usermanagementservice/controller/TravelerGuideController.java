package com.navio.usermanagementservice.controller;

import com.navio.usermanagementservice.dto.UserGuideDtos.*;
import com.navio.usermanagementservice.service.UserGuideService;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController @RequestMapping("/v1/traveler-guides") @RequiredArgsConstructor @Validated
public class TravelerGuideController {
    private final UserGuideService service;
    @GetMapping
    public ResponseEntity<Page<PublishedSummary>> list(
            @RequestParam(required = false) @Size(max = 100) String term,
            @RequestParam(required = false, name = "city") @Size(max = 30) List<@NotBlank @Size(max = 200) String> cities,
            @RequestParam(required = false) @Size(max = 100) String country,
            @RequestParam(required = false, name = "destination") @Size(max = 30) List<@Pattern(regexp = "(GOOGLE|MAPBOX):[A-Za-z0-9_-]{1,255}") String> destinations,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok(service.published(term, cities, country, destinations,
                PageRequest.of(page, size, Sort.by(Sort.Order.desc("publishedAt"), Sort.Order.asc("id")))));
    }
    @GetMapping("/{id}")
    public ResponseEntity<PublishedResponse> get(@PathVariable UUID id) { return ResponseEntity.ok(service.publishedDetail(id)); }
}
