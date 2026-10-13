package com.navio.usermanagementservice.controller;

import com.navio.usermanagementservice.dto.GuideDtos.PublicResponse;
import com.navio.usermanagementservice.service.GuideService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** Published guides, readable by anyone: the planner offers them on matching days. */
@RestController @RequestMapping("/v1/guides") @RequiredArgsConstructor @Validated
public class GuideController {
    private final GuideService service;

    @GetMapping
    public ResponseEntity<Page<PublicResponse>> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok(service.published(PageRequest.of(page, size, Sort.by("country", "name", "id"))));
    }

    @GetMapping("/{id}")
    public ResponseEntity<PublicResponse> get(@PathVariable UUID id) {
        return ResponseEntity.ok(service.publishedDetail(id));
    }
}
