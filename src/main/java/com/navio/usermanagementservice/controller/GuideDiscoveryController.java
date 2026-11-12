package com.navio.usermanagementservice.controller;

import com.navio.usermanagementservice.dto.GuideDiscoveryDtos.Response;
import com.navio.usermanagementservice.service.GuideDiscoveryService;
import com.navio.usermanagementservice.exception.UserManagementExceptions.BusinessRuleException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.*;
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
    
    public record ViewRequest(UUID visitorId) {}
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
            @PathVariable UUID id, @RequestBody(required=false) ViewRequest body,
            HttpServletRequest request, Authentication authentication) {
        boolean authenticated=authentication!=null&&authentication.isAuthenticated()&&!"anonymousUser".equals(authentication.getName());
        if(!authenticated&&(body==null||body.visitorId()==null))throw new BusinessRuleException("A visitor session is required");
        String identity=authenticated?"account:"+authentication.getName():"visitor:"+body.visitorId();
        // The untrusted session is only dedup evidence, never authentication. A separate
        // network budget bounds anonymous ID rotation; forwarded headers are not trusted.
        service.recordView(source,id,hash(identity),hash("network:"+request.getRemoteAddr()));
        return ResponseEntity.noContent().build();
    }
    private static String hash(String identity) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
