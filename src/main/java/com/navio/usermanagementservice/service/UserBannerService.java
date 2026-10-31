package com.navio.usermanagementservice.service;

import com.navio.usermanagementservice.dto.UserProfileResponse;
import com.navio.usermanagementservice.exception.UserManagementExceptions.UserNotFoundException;
import com.navio.usermanagementservice.media.PictureException;
import com.navio.usermanagementservice.media.PictureValidator;
import com.navio.usermanagementservice.repository.UserBannerRepository;
import com.navio.usermanagementservice.repository.UserRepository;
import com.navio.usermanagementservice.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.UUID;

/**
 * The caller's profile banner. Mirrors {@link UserPictureService}: the target is
 * always the provisioned caller, the pixels are re-encoded, and the user row is
 * locked so concurrent replace/remove calls cannot leave a dangling reference.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserBannerService {

    private final UserRepository users;
    private final UserBannerRepository banners;
    private final UserMapper mapper;
    private final AuditService audit;

    @Transactional
    public UserProfileResponse upload(AuthenticatedUser caller, MultipartFile file) {
        var picture = PictureValidator.validate(file);
        users.lockPicture(caller.id());
        var user = users.findByIdAndDeletedAtIsNull(caller.id()).orElseThrow(() -> new UserNotFoundException(caller.id()));
        UUID id = UUID.randomUUID();
        banners.save(caller.id(), id, picture);
        user.setBannerMediaId(id);
        users.saveAndFlush(user);
        audit.record(caller.id(), AuditAction.USER_PROFILE_UPDATED, AuditAction.RESOURCE_USER, caller.id(),
                Map.of("bannerUpdated", true));
        return mapper.toProfile(user, caller.roles().stream().sorted().toList());
    }

    @Transactional
    public UserProfileResponse remove(AuthenticatedUser caller) {
        users.lockPicture(caller.id());
        var user = users.findByIdAndDeletedAtIsNull(caller.id()).orElseThrow(() -> new UserNotFoundException(caller.id()));
        user.setBannerMediaId(null);
        users.saveAndFlush(user);
        banners.delete(caller.id());
        audit.record(caller.id(), AuditAction.USER_PROFILE_UPDATED, AuditAction.RESOURCE_USER, caller.id(),
                Map.of("bannerRemoved", true));
        return mapper.toProfile(user, caller.roles().stream().sorted().toList());
    }

    public PictureValidator.Picture read(UUID userId) {
        return banners.find(userId)
                .orElseThrow(() -> new PictureException(HttpStatus.NOT_FOUND, "Profile banner not found"));
    }
}
