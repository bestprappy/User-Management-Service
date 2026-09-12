package com.navio.usermanagementservice.service;

import com.navio.usermanagementservice.dto.UserProfileResponse;
import com.navio.usermanagementservice.exception.UserManagementExceptions.UserNotFoundException;
import com.navio.usermanagementservice.media.*;
import com.navio.usermanagementservice.repository.*;
import com.navio.usermanagementservice.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserPictureService {
    private final UserRepository users;
    private final UserPictureRepository pictures;
    private final UserMapper mapper;
    private final AuditService audit;

    @Transactional
    public UserProfileResponse upload(AuthenticatedUser caller, MultipartFile file) {
        // The target is always the provisioned caller; request headers and multipart fields cannot override it.
        var picture = PictureValidator.validate(file);
        users.lockPicture(caller.id());
        var user = users.findByIdAndDeletedAtIsNull(caller.id()).orElseThrow(() -> new UserNotFoundException(caller.id()));
        UUID id = UUID.randomUUID();
        pictures.save(caller.id(), id, picture);
        user.setAvatarMediaId(id);
        users.saveAndFlush(user);
        audit.record(caller.id(), AuditAction.USER_PROFILE_UPDATED, AuditAction.RESOURCE_USER, caller.id(), Map.of("pictureUpdated", true));
        return mapper.toProfile(user, caller.roles().stream().sorted().toList());
    }

    @Transactional
    public UserProfileResponse remove(AuthenticatedUser caller) {
        users.lockPicture(caller.id());
        var user = users.findByIdAndDeletedAtIsNull(caller.id()).orElseThrow(() -> new UserNotFoundException(caller.id()));
        user.setAvatarMediaId(null);
        users.saveAndFlush(user);
        pictures.delete(caller.id());
        audit.record(caller.id(), AuditAction.USER_PROFILE_UPDATED, AuditAction.RESOURCE_USER, caller.id(), Map.of("pictureRemoved", true));
        return mapper.toProfile(user, caller.roles().stream().sorted().toList());
    }

    public PictureValidator.Picture read(UUID userId) { return pictures.find(userId).orElseThrow(PictureException::notFound); }
}
