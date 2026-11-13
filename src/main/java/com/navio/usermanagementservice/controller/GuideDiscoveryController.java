package com.navio.usermanagementservice.controller;

import com.navio.usermanagementservice.dto.GuideDiscoveryDtos.Response;
import com.navio.usermanagementservice.service.GuideDiscoveryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

@RestController @RequestMapping("/v1/traveler-guides/discovery") @RequiredArgsConstructor @Validated
public class GuideDiscoveryController {
    private final GuideDiscoveryService service;
    
    public record ViewRequest(UUID readId, UUID visitorId) {
        @AssertTrue(message="A read identifier is required")
        public boolean isIdentified() { return readId != null || visitorId != null; }
        public UUID requestId() { return readId != null ? readId : visitorId; }
    }
    @GetMapping
    public Response discover(@RequestParam(defaultValue="") @Size(max=200) String term,
            @RequestParam(defaultValue="") @Pattern(regexp="[A-Za-z]{2}|^") String country,
            @RequestParam(defaultValue="any") @Size(max=20) String time,
            @RequestParam(defaultValue="") @Size(max=200) String collections,
            @RequestParam(defaultValue="") @Size(max=200) String interests,
            @RequestParam(defaultValue="relevance,trending,recent") @Size(max=100) String sort,
            @RequestParam(defaultValue="0") @Min(0) int page,
            @RequestParam(defaultValue="24") @Min(1) @Max(24) int size) {
        return service.discover(term,country,time,collections,interests,sort,page,size);
    }
    @PostMapping("/{source}/{id}/view")
    public ResponseEntity<Void> view(@PathVariable @Pattern(regexp="CURATED|TRAVELER") String source,
            @PathVariable UUID id, @Valid @RequestBody ViewRequest body,
            HttpServletRequest request, Authentication authentication) {
        boolean authenticated=authentication!=null&&authentication.isAuthenticated()&&!"anonymousUser".equals(authentication.getName());
        String account=authenticated?hash("account:"+authentication.getName()):null;
        // The read UUID is only retry evidence, never identity or authentication.
        service.recordView(source,id,body.requestId(),account,hash("network:"+request.getRemoteAddr()));
        return ResponseEntity.noContent().build();
    }
    private static String hash(String identity) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
