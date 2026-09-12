package com.navio.usermanagementservice.controller;

import com.navio.usermanagementservice.dto.UserProfileResponse;
import com.navio.usermanagementservice.security.*;
import com.navio.usermanagementservice.service.UserPictureService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.UUID;

@RestController
@RequestMapping("/v1/users")
@RequiredArgsConstructor
public class UserPictureController {
    private final UserPictureService pictures;

    @PostMapping(value = "/me/picture", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UserProfileResponse> upload(@CurrentUser AuthenticatedUser caller, @RequestPart("file") MultipartFile file) {
        return ResponseEntity.status(HttpStatus.CREATED).body(pictures.upload(caller, file));
    }

    @DeleteMapping("/me/picture")
    public ResponseEntity<Void> remove(@CurrentUser AuthenticatedUser caller) {
        pictures.remove(caller);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me/picture")
    public ResponseEntity<byte[]> ownPicture(@CurrentUser AuthenticatedUser caller) { return image(caller.id()); }

    @GetMapping("/{userId}/picture")
    public ResponseEntity<byte[]> read(@CurrentUser AuthenticatedUser caller, @PathVariable UUID userId) { return image(userId); }

    private ResponseEntity<byte[]> image(UUID userId) {
        var picture = pictures.read(userId);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(picture.contentType()))
                .cacheControl(CacheControl.noStore()).header("X-Content-Type-Options", "nosniff")
                .contentLength(picture.bytes().length).body(picture.bytes());
    }
}
