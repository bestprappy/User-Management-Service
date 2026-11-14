package com.navio.usermanagementservice.library;

import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.constraints.*;
import org.springframework.validation.annotation.Validated;
import java.util.*;

@Validated @RestController @RequestMapping("/v1/saved-content/favorites") @RequiredArgsConstructor
public class PublicFavoritesController {
 private final PublicFavoritesRepository repository;
 private static final String ID="(?:plan:[A-Za-z0-9_-]{1,128}|guide:(?:curated|traveler):[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})";
 @GetMapping public ResponseEntity<List<PublicFavoritesRepository.Count>> counts(@RequestParam @Size(min=1,max=48) List<@Pattern(regexp=ID) String> identity) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(repository.counts(identity));
 }
}
