package com.navio.usermanagementservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navio.usermanagementservice.dto.UpdateProfileRequest;
import com.navio.usermanagementservice.exception.UserManagementExceptions.BusinessRuleException;
import com.navio.usermanagementservice.exception.UserManagementExceptions.UserGoneException;
import com.navio.usermanagementservice.exception.UserManagementExceptions.UserNotFoundException;
import com.navio.usermanagementservice.model.User;
import com.navio.usermanagementservice.model.UserStatus;
import com.navio.usermanagementservice.repository.UserRepository;
import com.navio.usermanagementservice.repository.UsernameHistoryRepository;
import com.navio.usermanagementservice.security.AuthenticatedUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserProfileServiceTests {

    private static final UUID ME = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID SUBJECT = UUID.fromString("00000000-0000-4000-8000-0000000000aa");

    @Mock UserRepository users;
    @Mock AuditService audit;
    @Mock OutboxService outbox;
    @Mock UsernameService usernames;
    @Mock UsernameHistoryRepository history;
    private UserProfileService service;
    private final AuthenticatedUser caller = new AuthenticatedUser(ME, SUBJECT.toString(), "me@example.com", "Me", Set.of());

    @BeforeEach
    void setUp() {
        service = new UserProfileService(users, new UserMapper(new ObjectMapper().findAndRegisterModules()), audit, outbox, usernames, history);
        lenient().when(users.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private User user() {
        return User.builder().id(ME).authSubject(SUBJECT.toString()).email("me@example.com").displayName("Me")
                .username("me_travels").status(UserStatus.ACTIVE).travelInterests(new ArrayList<>())
                .showTravelPreferences(true).createdAt(Instant.parse("2026-01-01T00:00:00Z")).build();
    }

    private static UpdateProfileRequest request(String bio, List<String> interests, String pace, Boolean show) {
        return new UpdateProfileRequest(null, null, null, null, null, bio, interests, pace, show);
    }

    @Test
    void anEmptyBioOrPaceClearsTheStoredValue() {
        User user = user();
        user.setBio("Old bio");
        user.setTravelPace("packed");
        when(users.findByIdAndDeletedAtIsNull(ME)).thenReturn(Optional.of(user));
        var response = service.updateMyProfile(caller, request("   ", null, "", null));
        assertThat(response.bio()).isNull();
        assertThat(response.travelPace()).isNull();
    }

    @Test
    void travelInterestsOutsideTheVocabularyAreRejectedWithoutSaving() {
        when(users.findByIdAndDeletedAtIsNull(ME)).thenReturn(Optional.of(user()));
        assertThatThrownBy(() -> service.updateMyProfile(caller, request(null, List.of("food", "<script>"), null, null)))
                .isInstanceOf(BusinessRuleException.class);
        verify(users, never()).save(any());
    }

    @Test
    void duplicateInterestsAreStoredOnce() {
        when(users.findByIdAndDeletedAtIsNull(ME)).thenReturn(Optional.of(user()));
        var response = service.updateMyProfile(caller, request(null, List.of("food", "nature", "food"), null, null));
        assertThat(response.travelInterests()).containsExactly("food", "nature");
    }

    @Test
    void hiddenTravelPreferencesNeverReachThePublicProfile() {
        User user = user();
        user.setTravelInterests(new ArrayList<>(List.of("food")));
        user.setTravelPace("relaxed");
        user.setShowTravelPreferences(false);
        when(users.findByUsername("me_travels")).thenReturn(Optional.of(user));
        var profile = service.getPublicProfileByUsername("@Me_Travels");
        assertThat(profile.travelInterests()).isEmpty();
        assertThat(profile.travelPace()).isNull();
        assertThat(profile.accountId()).isEqualTo(SUBJECT);
    }

    @Test
    void aPreviousHandleResolvesToItsOwnersCurrentProfile() {
        when(users.findByUsername("old_name")).thenReturn(Optional.empty());
        when(history.mostRecentOwner("old_name")).thenReturn(Optional.of(ME));
        when(users.findById(ME)).thenReturn(Optional.of(user()));
        assertThat(service.getPublicProfileByUsername("old_name").username()).isEqualTo("me_travels");
    }

    @Test
    void aClosedAccountIsGoneRatherThanMissing() {
        User closed = user();
        closed.setDeletedAt(Instant.parse("2026-05-01T00:00:00Z"));
        when(users.findByUsername("me_travels")).thenReturn(Optional.of(closed));
        assertThatThrownBy(() -> service.getPublicProfileByUsername("me_travels")).isInstanceOf(UserGoneException.class);
    }

    @Test
    void anUnknownHandleIsNotFound() {
        when(users.findByUsername("nobody")).thenReturn(Optional.empty());
        when(history.mostRecentOwner("nobody")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getPublicProfileByUsername("nobody")).isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void peopleSearchNeedsTwoCharactersAndEscapesWildcards() {
        assertThatThrownBy(() -> service.searchPeople("a", 0, 20)).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> service.searchPeople("alex", 0, 51)).isInstanceOf(BusinessRuleException.class);
        when(users.searchPublicProfiles(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), any())).thenReturn(org.springframework.data.domain.Page.empty());
        service.searchPeople("@50%_off", 0, 20);
        verify(users).searchPublicProfiles(org.mockito.ArgumentMatchers.eq("50!%!_off"), org.mockito.ArgumentMatchers.eq("50!%!_off"),
                org.mockito.ArgumentMatchers.eq("50%_off"), any());
    }

    @Test
    void aUsernameChangeIsDelegatedToThePolicyService() {
        User user = user();
        when(users.findByIdAndDeletedAtIsNull(ME)).thenReturn(Optional.of(user));
        when(usernames.change(user, "new_me")).thenReturn(true);
        service.updateMyProfile(caller, new UpdateProfileRequest(null, null, null, null, "new_me", null, null, null, null));
        verify(usernames).change(user, "new_me");
        verify(users).save(user);
    }
}
