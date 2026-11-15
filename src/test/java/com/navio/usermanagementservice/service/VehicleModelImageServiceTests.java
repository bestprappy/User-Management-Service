package com.navio.usermanagementservice.service;

import com.navio.usermanagementservice.exception.UserManagementExceptions.ForbiddenOperationException;
import com.navio.usermanagementservice.media.PictureException;
import com.navio.usermanagementservice.media.VehicleImagePaths;
import com.navio.usermanagementservice.repository.VehicleModelImageRepository;
import com.navio.usermanagementservice.security.AuthenticatedUser;
import com.navio.usermanagementservice.security.NavioRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VehicleModelImageServiceTests {
    static final UUID ADMIN_ID = UUID.fromString("0b6a3c1e-3f1d-4c55-9d1a-7a1e2b3c4d5e");

    @Mock VehicleModelImageRepository repository;
    @InjectMocks VehicleModelImageService service;

    static AuthenticatedUser user(NavioRole role) {
        return new AuthenticatedUser(ADMIN_ID, "someone", "someone@example.com", "Someone", Set.of(role));
    }

    static MockMultipartFile png() throws IOException {
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB), "png", output);
        return new MockMultipartFile("file", "car.png", "image/png", output.toByteArray());
    }

    @Test void adminUploadStoresTheReEncodedPhotoAndReturnsItsServedPath() throws IOException {
        var response = service.upload(user(NavioRole.ADMIN), png());
        verify(repository).save(eq(response.id()), eq(ADMIN_ID), any());
        assertThat(response.imageUrl()).isEqualTo("/api/vehicle-models/images/" + response.id());
        assertThat(VehicleImagePaths.isLocal(response.imageUrl())).isTrue();
    }

    @Test void nonAdminsCannotUploadAndNothingIsStored() throws IOException {
        var file = png();
        assertThatThrownBy(() -> service.upload(user(NavioRole.USER), file)).isInstanceOf(ForbiddenOperationException.class);
        verifyNoInteractions(repository);
    }

    @Test void aFileThatIsNotAnImageIsRejectedBeforeStorage() {
        var file = new MockMultipartFile("file", "car.png", "image/png", "not an image".getBytes());
        assertThatThrownBy(() -> service.upload(user(NavioRole.ADMIN), file))
                .isInstanceOfSatisfying(PictureException.class, ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(repository);
    }

    @Test void readingAnUnknownPhotoIsNotFound() {
        UUID id = UUID.fromString("6f1c2f1e-8a54-4a0c-9f7e-1c2d3e4f5a6b");
        when(repository.find(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.read(id))
                .isInstanceOfSatisfying(PictureException.class, ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test void onlyBundledAssetsAndUploadedPhotoPathsCountAsLocalImages() {
        assertThat(VehicleImagePaths.isLocal("/images/vehicles/byd-seal.png")).isTrue();
        assertThat(VehicleImagePaths.isLocal("/api/vehicle-models/images/6f1c2f1e-8a54-4a0c-9f7e-1c2d3e4f5a6b")).isTrue();
        assertThat(VehicleImagePaths.isLocal("/api/vehicle-models/images/../users/me")).isFalse();
        assertThat(VehicleImagePaths.isLocal("/api/vehicle-models/images/6F1C2F1E-8A54-4A0C-9F7E-1C2D3E4F5A6B")).isFalse();
        assertThat(VehicleImagePaths.isLocal("/api/users/me/picture")).isFalse();
    }
}
