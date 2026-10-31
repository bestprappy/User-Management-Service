package com.navio.usermanagementservice.controller;

import com.navio.usermanagementservice.dto.UserProfileResponse;
import com.navio.usermanagementservice.security.AuthenticatedUser;
import com.navio.usermanagementservice.security.CurrentUser;
import com.navio.usermanagementservice.service.UserBannerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

/** Profile banner upload, removal and reads. Writes always target the caller. */
@RestController
@RequestMapping("/v1/users")
@RequiredArgsConstructor
public class UserBannerController {

    private final UserBannerService banners;

    @PostMapping(value = "/me/banner", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UserProfileResponse> upload(@CurrentUser AuthenticatedUser caller,
                                                      @RequestPart("file") MultipartFile file) {
        return ResponseEntity.status(HttpStatus.CREATED).body(banners.upload(caller, file));
    }

    @DeleteMapping("/me/banner")
    public ResponseEntity<Void> remove(@CurrentUser AuthenticatedUser caller) {
        banners.remove(caller);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{userId}/banner")
    public ResponseEntity<byte[]> read(@CurrentUser AuthenticatedUser caller, @PathVariable UUID userId) {
        var picture = banners.read(userId);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(picture.contentType()))
                .cacheControl(CacheControl.noStore()).header("X-Content-Type-Options", "nosniff")
                .contentLength(picture.bytes().length).body(picture.bytes());
    }
}
