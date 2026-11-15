package com.navio.usermanagementservice.service;

import com.navio.usermanagementservice.dto.VehicleModelDtos.ImageResponse;
import com.navio.usermanagementservice.exception.UserManagementExceptions.ForbiddenOperationException;
import com.navio.usermanagementservice.media.PictureException;
import com.navio.usermanagementservice.media.PictureValidator;
import com.navio.usermanagementservice.media.VehicleImagePaths;
import com.navio.usermanagementservice.repository.VehicleModelImageRepository;
import com.navio.usermanagementservice.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

/**
 * Photos for catalog vehicles. Admins upload one, receive its path, and save
 * that path as the vehicle's image like any other image URL. Every upload gets
 * a new id, so a served photo never changes and can be cached indefinitely.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class VehicleModelImageService {

    private final VehicleModelImageRepository images;

    @Transactional
    public ImageResponse upload(AuthenticatedUser caller, MultipartFile file) {
        if (!caller.isAdmin()) throw new ForbiddenOperationException("Only administrators can manage the vehicle catalog");
        var picture = PictureValidator.validate(file);
        UUID id = UUID.randomUUID();
        images.save(id, caller.id(), picture);
        return new ImageResponse(id, VehicleImagePaths.uploaded(id));
    }

    public PictureValidator.Picture read(UUID id) {
        return images.find(id).orElseThrow(() -> new PictureException(HttpStatus.NOT_FOUND, "Vehicle image not found"));
    }
}
