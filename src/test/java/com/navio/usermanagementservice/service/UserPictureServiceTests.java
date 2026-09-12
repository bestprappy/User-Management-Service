package com.navio.usermanagementservice.service;

import com.navio.usermanagementservice.dto.UpdateProfileRequest;
import com.navio.usermanagementservice.exception.UserManagementExceptions.BusinessRuleException;
import com.navio.usermanagementservice.media.PictureException;
import com.navio.usermanagementservice.model.User;
import com.navio.usermanagementservice.repository.*;
import com.navio.usermanagementservice.security.AuthenticatedUser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.*;
import javax.imageio.ImageIO;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserPictureServiceTests {
    @Mock UserRepository users;
    @Mock UserPictureRepository pictures;
    @Mock UserMapper mapper;
    @Mock AuditService audit;
    @InjectMocks UserPictureService service;
    private static final UUID USER = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private final AuthenticatedUser caller = new AuthenticatedUser(USER, "subject", "user@example.com", "User", Set.of());

    @Test void uploadsReplaceOnlyTheCallersPictureAfterValidation() throws Exception {
        var user = User.builder().id(USER).build();
        when(users.findByIdAndDeletedAtIsNull(USER)).thenReturn(Optional.of(user));
        var bytes = new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),"png",bytes);
        service.upload(caller, new MockMultipartFile("file", "../../victim.exe", "text/html", bytes.toByteArray()));
        verify(users).lockPicture(USER);
        verify(pictures).save(eq(USER), eq(user.getAvatarMediaId()), argThat(p -> p.contentType().equals("image/png")));
        verify(users).saveAndFlush(user);
        assertThat(user.getAvatarMediaId()).isNotNull();
    }

    @Test void invalidPicturesNeverReachStorage() {
        assertThatThrownBy(() -> service.upload(caller,new MockMultipartFile("file", "<svg/>".getBytes()))).isInstanceOf(PictureException.class);
        verifyNoInteractions(users,pictures,audit);
    }

    @Test void removingAPictureClearsOnlyTheCallersReference() {
        var user = User.builder().id(USER).avatarMediaId(UUID.randomUUID()).build();
        when(users.findByIdAndDeletedAtIsNull(USER)).thenReturn(Optional.of(user));
        service.remove(caller);
        assertThat(user.getAvatarMediaId()).isNull();
        verify(pictures).delete(USER);
        verify(users).lockPicture(USER);
    }

    @Test void arbitraryMediaIdsCannotBeAssignedThroughProfilePatch() {
        var profiles = new UserProfileService(users,mapper,audit,mock(OutboxService.class));
        assertThatThrownBy(() -> profiles.updateMyProfile(caller,new UpdateProfileRequest(null,UUID.randomUUID(),null,null)))
                .isInstanceOf(BusinessRuleException.class);
        verifyNoInteractions(users,pictures,audit);
    }
}
