package com.navio.usermanagementservice.controller;

import com.navio.usermanagementservice.dto.GuideFeedback.*;
import com.navio.usermanagementservice.service.GuideFeedbackService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/traveler-guides/discovery/{source}/{guideId}/feedback")
public class GuideFeedbackController {
    private final GuideFeedbackService feedback;
    private final com.navio.usermanagementservice.repository.UserRepository users;
    private UUID viewer(org.springframework.security.core.Authentication auth) {
        if(!(auth instanceof org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken jwt))return null;
        return users.findByAuthSubject(jwt.getName()).map(com.navio.usermanagementservice.model.User::getId).orElse(null);
    }
    private boolean moderator(org.springframework.security.core.Authentication auth) {
        return auth instanceof org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
            && auth.getAuthorities().stream().anyMatch(a -> Set.of("ROLE_ADMIN","ROLE_MODERATOR","ROLE_OWNER").contains(a.getAuthority()));
    }
    private String key(String source,UUID id) {
        if(!Set.of("CURATED","TRAVELER").contains(source))throw new com.navio.usermanagementservice.exception.UserManagementExceptions.GuideNotFoundException();
        return source+":"+id;
    }
    private <T> ResponseEntity<T> read(T value){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);}
    @GetMapping public ResponseEntity<Summary> summary(@PathVariable String source,@PathVariable UUID guideId,org.springframework.security.core.Authentication auth) {
        return read(feedback.summary(key(source,guideId),viewer(auth),moderator(auth)));
    }
    @PutMapping("/rating") public ResponseEntity<Summary> rate(@PathVariable String source,@PathVariable UUID guideId,@com.navio.usermanagementservice.security.CurrentUser com.navio.usermanagementservice.security.AuthenticatedUser caller,@Valid @RequestBody RatingRequest request) {
        return read(feedback.rate(key(source,guideId),caller.id(),request.score()));
    }
    @DeleteMapping("/rating") public ResponseEntity<Summary> unrate(@PathVariable String source,@PathVariable UUID guideId,@com.navio.usermanagementservice.security.CurrentUser com.navio.usermanagementservice.security.AuthenticatedUser caller) {
        return read(feedback.rate(key(source,guideId),caller.id(),null));
    }
    @GetMapping("/comments") public ResponseEntity<Comments> comments(@PathVariable String source,@PathVariable UUID guideId,org.springframework.security.core.Authentication auth,
            @RequestParam(required=false) UUID parentId,@RequestParam(defaultValue="0") int page) {
        return read(feedback.comments(key(source,guideId),viewer(auth),parentId,page,moderator(auth)));
    }
    @PostMapping("/comments") public ResponseEntity<Void> add(@PathVariable String source,@PathVariable UUID guideId,@com.navio.usermanagementservice.security.CurrentUser com.navio.usermanagementservice.security.AuthenticatedUser caller,@Valid @RequestBody CommentRequest request) {
        feedback.add(key(source,guideId),caller.id(),new CommentRequest(request.content(),caller.displayName(),request.parentId()));return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).build();
    }
    @PutMapping("/comments/{id}") public ResponseEntity<Void> edit(@PathVariable String source,@PathVariable UUID guideId,@com.navio.usermanagementservice.security.CurrentUser com.navio.usermanagementservice.security.AuthenticatedUser caller,@PathVariable UUID id,@Valid @RequestBody EditRequest request) {
        feedback.edit(key(source,guideId),caller.id(),id,request);return ResponseEntity.noContent().build();
    }
    @DeleteMapping("/comments/{id}") public ResponseEntity<Void> delete(@PathVariable String source,@PathVariable UUID guideId,@com.navio.usermanagementservice.security.CurrentUser com.navio.usermanagementservice.security.AuthenticatedUser caller,@PathVariable UUID id) {
        feedback.delete(key(source,guideId),caller.id(),id,caller.canModerate());return ResponseEntity.noContent().build();
    }
    @PostMapping("/comments/{id}/reports") public ResponseEntity<Void> report(@PathVariable String source,@PathVariable UUID guideId,@com.navio.usermanagementservice.security.CurrentUser com.navio.usermanagementservice.security.AuthenticatedUser caller,@PathVariable UUID id,@Valid @RequestBody ReportRequest request) {
        feedback.report(key(source,guideId),caller.id(),id,request);return ResponseEntity.noContent().build();
    }
    @GetMapping("/reports") public ResponseEntity<List<Report>> reports(@PathVariable String source,@PathVariable UUID guideId,@com.navio.usermanagementservice.security.CurrentUser com.navio.usermanagementservice.security.AuthenticatedUser caller) {
        return read(feedback.reports(key(source,guideId),caller.id(),caller.canModerate()));
    }
}
