package com.navio.usermanagementservice.service;

import com.navio.usermanagementservice.dto.SavedPlaceRequests.CreateSavedPlaceRequest;
import com.navio.usermanagementservice.dto.SavedPlaceRequests.UpdateSavedPlaceRequest;
import com.navio.usermanagementservice.dto.SavedPlaceResponse;
import com.navio.usermanagementservice.exception.UserManagementExceptions.BusinessRuleException;
import com.navio.usermanagementservice.exception.UserManagementExceptions.SavedPlaceNotFoundException;
import com.navio.usermanagementservice.model.UserSavedPlace;
import com.navio.usermanagementservice.model.UserSavedPlace.SavedPlaceKind;
import com.navio.usermanagementservice.repository.UserRepository;
import com.navio.usermanagementservice.repository.UserSavedPlaceRepository;
import com.navio.usermanagementservice.security.AuthenticatedUser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserSavedPlaceServiceTests {

    private static final UUID OWNER = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID OTHER = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final UUID PLACE = UUID.fromString("20000000-0000-4000-8000-000000000001");

    @Mock UserSavedPlaceRepository savedPlaceRepository;
    @Mock UserRepository userRepository;
    @Mock AuditService auditService;
    @InjectMocks UserSavedPlaceService service;

    private final AuthenticatedUser caller =
            new AuthenticatedUser(OWNER, "subject", "owner@example.com", "Owner", Set.of());

    @Test void theFirstSavedPlaceBecomesTheDefaultStartPoint() {
        when(savedPlaceRepository.countByUserIdAndDeletedAtIsNull(OWNER)).thenReturn(0L);
        when(savedPlaceRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.addSavedPlace(caller, createRequest("Home", "HOME", null));

        ArgumentCaptor<UserSavedPlace> saved = ArgumentCaptor.forClass(UserSavedPlace.class);
        verify(savedPlaceRepository).save(saved.capture());
        assertThat(saved.getValue().isDefault()).isTrue();
        verify(savedPlaceRepository).clearDefaultForUser(eq(OWNER), isNull());
    }

    @Test void aLaterPlaceIsNotMadeDefaultUnlessAsked() {
        when(savedPlaceRepository.countByUserIdAndDeletedAtIsNull(OWNER)).thenReturn(3L);
        when(savedPlaceRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.addSavedPlace(caller, createRequest("Gym", "CUSTOM", null));

        ArgumentCaptor<UserSavedPlace> saved = ArgumentCaptor.forClass(UserSavedPlace.class);
        verify(savedPlaceRepository).save(saved.capture());
        assertThat(saved.getValue().isDefault()).isFalse();
        verify(savedPlaceRepository, never()).clearDefaultForUser(any(), any());
    }

    @Test void aSecondHomeIsRejectedWithAnActionableMessage() {
        when(savedPlaceRepository.countByUserIdAndDeletedAtIsNull(OWNER)).thenReturn(1L);
        when(savedPlaceRepository.findByUserIdAndKindAndDeletedAtIsNull(OWNER, SavedPlaceKind.HOME))
                .thenReturn(Optional.of(place(SavedPlaceKind.HOME)));

        assertThatThrownBy(() -> service.addSavedPlace(caller, createRequest("Home", "HOME", null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Edit it instead");

        verify(savedPlaceRepository, never()).save(any());
    }

    @Test void aSecondCustomPlaceIsAllowed() {
        when(savedPlaceRepository.countByUserIdAndDeletedAtIsNull(OWNER)).thenReturn(1L);
        when(savedPlaceRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.addSavedPlace(caller, createRequest("Gym", "CUSTOM", null));

        verify(savedPlaceRepository).save(any());
        verify(savedPlaceRepository, never()).findByUserIdAndKindAndDeletedAtIsNull(any(), any());
    }

    @Test void theGarageCapRefusesTheFiftyFirstPlace() {
        when(savedPlaceRepository.countByUserIdAndDeletedAtIsNull(OWNER)).thenReturn(50L);

        assertThatThrownBy(() -> service.addSavedPlace(caller, createRequest("Home", "CUSTOM", null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("at most 50");

        verify(savedPlaceRepository, never()).save(any());
    }

    @Test void anUnknownKindIsRejectedBeforeAnythingIsWritten() {
        when(savedPlaceRepository.countByUserIdAndDeletedAtIsNull(OWNER)).thenReturn(0L);

        assertThatThrownBy(() -> service.addSavedPlace(caller, createRequest("Home", "CASTLE", null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("HOME, WORK or CUSTOM");

        verify(savedPlaceRepository, never()).save(any());
    }

    @Test void nonFiniteCoordinatesAreRejectedBeforeTheyReachRouting() {
        when(savedPlaceRepository.countByUserIdAndDeletedAtIsNull(OWNER)).thenReturn(0L);
        var request = new CreateSavedPlaceRequest("Home", "CUSTOM", "Home", null,
                Double.NaN, 100.5, null, null);

        assertThatThrownBy(() -> service.addSavedPlace(caller, request))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("valid coordinates");

        verify(savedPlaceRepository, never()).save(any());
    }

    @Test void anotherUsersPlaceIsIndistinguishableFromOneThatDoesNotExist() {
        when(savedPlaceRepository.findByIdAndUserIdAndDeletedAtIsNull(PLACE, OWNER))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getMySavedPlace(caller, PLACE))
                .isInstanceOf(SavedPlaceNotFoundException.class);
    }

    @Test void aReadIsAlwaysScopedToTheCallerNotThePathId() {
        when(savedPlaceRepository.findByIdAndUserIdAndDeletedAtIsNull(PLACE, OWNER))
                .thenReturn(Optional.of(place(SavedPlaceKind.HOME)));

        SavedPlaceResponse response = service.getMySavedPlace(caller, PLACE);

        assertThat(response.id()).isEqualTo(PLACE);
        verify(savedPlaceRepository).findByIdAndUserIdAndDeletedAtIsNull(PLACE, OWNER);
        verify(savedPlaceRepository, never()).findByIdAndUserIdAndDeletedAtIsNull(PLACE, OTHER);
    }

    @Test void movingAPlaceRequiresBothCoordinatesTogether() {
        when(savedPlaceRepository.findByIdAndUserIdAndDeletedAtIsNull(PLACE, OWNER))
                .thenReturn(Optional.of(place(SavedPlaceKind.HOME)));
        var request = new UpdateSavedPlaceRequest(null, null, null, null, 14.0, null, null, null);

        assertThatThrownBy(() -> service.updateSavedPlace(caller, PLACE, request))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("lat and lng must be updated together");

        verify(savedPlaceRepository, never()).save(any());
    }

    @Test void deletingTheDefaultPromotesTheNextPlace() {
        UserSavedPlace current = place(SavedPlaceKind.HOME);
        current.setDefault(true);
        UserSavedPlace next = place(SavedPlaceKind.CUSTOM);
        next.setId(UUID.fromString("20000000-0000-4000-8000-000000000002"));
        when(savedPlaceRepository.findByIdAndUserIdAndDeletedAtIsNull(PLACE, OWNER))
                .thenReturn(Optional.of(current));
        when(savedPlaceRepository.findByUserIdAndDeletedAtIsNullOrderByIsDefaultDescCreatedAtAsc(OWNER))
                .thenReturn(List.of(next));

        service.deleteSavedPlace(caller, PLACE);

        assertThat(current.getDeletedAt()).isNotNull();
        // The deleted row must release the default slot or the partial unique
        // index keeps it occupied forever.
        assertThat(current.isDefault()).isFalse();
        assertThat(next.isDefault()).isTrue();
        verify(savedPlaceRepository).save(next);
    }

    @Test void deletingANonDefaultPlaceLeavesTheDefaultAlone() {
        UserSavedPlace current = place(SavedPlaceKind.CUSTOM);
        when(savedPlaceRepository.findByIdAndUserIdAndDeletedAtIsNull(PLACE, OWNER))
                .thenReturn(Optional.of(current));

        service.deleteSavedPlace(caller, PLACE);

        assertThat(current.getDeletedAt()).isNotNull();
        verify(savedPlaceRepository, never())
                .findByUserIdAndDeletedAtIsNullOrderByIsDefaultDescCreatedAtAsc(OWNER);
    }

    private CreateSavedPlaceRequest createRequest(String label, String kind, Boolean isDefault) {
        return new CreateSavedPlaceRequest(label, kind, label, "Bangkok", 13.75, 100.5, null, isDefault);
    }

    private UserSavedPlace place(SavedPlaceKind kind) {
        return UserSavedPlace.builder()
                .id(PLACE)
                .userId(OWNER)
                .label("Home")
                .kind(kind)
                .name("Sukhumvit 24")
                .address("Bangkok")
                .lat(13.75)
                .lng(100.5)
                .build();
    }
}
