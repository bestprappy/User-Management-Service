package com.navio.usermanagementservice.controller;

import com.navio.usermanagementservice.dto.PaletteColorDtos.PublicResponse;
import com.navio.usermanagementservice.service.PaletteColorService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

/**
 * The palette as every reader needs it, including guests opening a shared plan.
 * Retired colors are included and flagged so saved trips keep their colors; the
 * list is bounded by {@code PaletteColorService.MAX_TOTAL}.
 */
@RestController @RequestMapping("/v1/palette-colors") @RequiredArgsConstructor
public class PaletteColorController {
    private final PaletteColorService service;

    @GetMapping
    public ResponseEntity<List<PublicResponse>> list() {
        return ResponseEntity.ok(service.all());
    }
}
