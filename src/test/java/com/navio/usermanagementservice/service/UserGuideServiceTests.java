package com.navio.usermanagementservice.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navio.usermanagementservice.config.JacksonConfiguration;
import com.navio.usermanagementservice.dto.GuideDtos.*;
import com.navio.usermanagementservice.exception.UserManagementExceptions.*;
import com.navio.usermanagementservice.model.Guide.Status;
import com.navio.usermanagementservice.model.UserGuide;
import com.navio.usermanagementservice.model.User;
import com.navio.usermanagementservice.repository.*;
import com.navio.usermanagementservice.security.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.jpa.domain.Specification;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserGuideServiceTests {
    static final UUID ID = UUID.randomUUID();
    static final AuthenticatedUser OWNER = new AuthenticatedUser(UUID.randomUUID(), "owner", "owner@example.com", "Writer", Set.of(NavioRole.USER));
    static final String BODY = "<div data-embed=\"placeList\" data-list-id=\"visit\"></div>\n\n<div data-embed=\"guidePlace\" data-place-id=\"temple\"></div>";
    @Mock UserGuideRepository repository;
    @Mock UserRepository users;
    @Mock PaletteColorService palette;
    ObjectMapper json = new JacksonConfiguration().objectMapper();
    UserGuideService service;
    @BeforeEach void setup() {
        service = new UserGuideService(repository, users, json, palette);
        lenient().when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }
    static Fields fields(String body) {
        return new Fields("A local's Bangkok", "Thailand", "TH", new Destination("GOOGLE", "city", "Bangkok", 13.7, 100.5), "Tips", "GOOGLE:city",
                new Content(List.of(new Category("visit", "Sights", "landmark", "blue")), Map.of("visit", new ListHeader("visit", "visit")),
                        Map.of("temple", new Place("GOOGLE:temple", "GOOGLE", "temple", "Temple", "", 13.7, 100.5, null, null, List.of())), body));
    }
    UserGuide guide() {
        UserGuide guide = new UserGuide(); guide.setId(ID); guide.setOwnerId(OWNER.id()); guide.setVersion(3L);
        guide.setName("Bangkok"); guide.setCountry("Thailand"); guide.setDestinationProvider("GOOGLE"); guide.setDestinationPlaceId("city");
        guide.setDestinationName("Bangkok"); guide.setDestinationLat(13.7); guide.setDestinationLng(100.5); guide.setCoverPlaceId("GOOGLE:city");
        guide.setContent(json.convertValue(fields(BODY).content(), new TypeReference<>() {}));
        return guide;
    }
    @Test void everyPrivateOperationRejectsAnotherOwnerIncludingAdministrators() {
        for (NavioRole role : NavioRole.values()) {
            var stranger = new AuthenticatedUser(UUID.randomUUID(), "other", "other@example.com", "Other", Set.of(role));
            assertThatThrownBy(() -> service.detail(stranger, ID)).isInstanceOf(GuideNotFoundException.class);
            assertThatThrownBy(() -> service.update(stranger, ID, new WriteRequest(3L, fields(BODY)))).isInstanceOf(GuideNotFoundException.class);
            assertThatThrownBy(() -> service.publish(stranger, ID, 3)).isInstanceOf(GuideNotFoundException.class);
            assertThatThrownBy(() -> service.unpublish(stranger, ID, 3)).isInstanceOf(GuideNotFoundException.class);
            assertThatThrownBy(() -> service.delete(stranger, ID, 3)).isInstanceOf(GuideNotFoundException.class);
        }
        verify(repository, never()).saveAndFlush(any()); verify(repository, never()).delete(any(UserGuide.class));
    }
    @Test void creationAlwaysTakesOwnershipFromTheCallerAndStartsPrivate() {
        var response = service.create(OWNER, new CreateRequest(fields(BODY)));
        assertThat(response.status()).isEqualTo(Status.DRAFT);
        var captor = org.mockito.ArgumentCaptor.forClass(UserGuide.class); verify(repository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getOwnerId()).isEqualTo(OWNER.id());
        assertThat(captor.getValue().getPublishedAt()).isNull();
    }
    @Test void allMutationKindsRejectAStaleVersion() {
        when(repository.findByIdAndOwnerId(ID, OWNER.id())).thenReturn(Optional.of(guide()));
        assertThatThrownBy(() -> service.update(OWNER, ID, new WriteRequest(2L, fields(BODY)))).isInstanceOf(ObjectOptimisticLockingFailureException.class);
        assertThatThrownBy(() -> service.publish(OWNER, ID, 2)).isInstanceOf(ObjectOptimisticLockingFailureException.class);
        assertThatThrownBy(() -> service.unpublish(OWNER, ID, 2)).isInstanceOf(ObjectOptimisticLockingFailureException.class);
        assertThatThrownBy(() -> service.delete(OWNER, ID, 2)).isInstanceOf(ObjectOptimisticLockingFailureException.class);
        verify(repository, never()).saveAndFlush(any());
    }
    @Test void publicationRequiresAResolvedPlaceCard() {
        UserGuide guide = guide(); guide.setContent(json.convertValue(fields("Just text").content(), new TypeReference<>() {}));
        when(repository.findByIdAndOwnerId(ID, OWNER.id())).thenReturn(Optional.of(guide));
        assertThatThrownBy(() -> service.publish(OWNER, ID, 3)).isInstanceOf(BusinessRuleException.class);
    }
    @Test void aPublishedGuideCannotBeEditedIntoAnEmptyDocument() {
        UserGuide guide = guide(); guide.setStatus(Status.PUBLISHED);
        when(repository.findByIdAndOwnerId(ID, OWNER.id())).thenReturn(Optional.of(guide));
        assertThatThrownBy(() -> service.update(OWNER, ID, new WriteRequest(3L, fields("")))).isInstanceOf(BusinessRuleException.class);
        verify(repository, never()).saveAndFlush(any());
    }
    @Test void unpublishedAndMissingGuidesShareThePublicNotFoundResponse() {
        assertThatThrownBy(() -> service.publishedDetail(ID)).isInstanceOf(GuideNotFoundException.class);
        verify(repository).findByIdAndStatus(ID, Status.PUBLISHED);
    }
    @Test void aMissingAuthenticatedIdentityCannotManageAnyLibrary() {
        assertThatThrownBy(() -> service.mine(null, null, null, PageRequest.of(0, 10))).isInstanceOf(ForbiddenOperationException.class);
        assertThatThrownBy(() -> service.create(null, new CreateRequest(fields(BODY)))).isInstanceOf(ForbiddenOperationException.class);
        verifyNoInteractions(repository);
    }
    @Test void publishedDetailUsesTheCurrentPublicProfileAndDoesNotExposeContactOrLoginFields() throws Exception {
        UserGuide guide = guide();
        User user = new User(); user.setId(OWNER.id()); user.setDisplayName("Updated writer");
        user.setAvatarMediaId(UUID.randomUUID()); user.setEmail("private@example.com"); user.setAuthSubject("private-subject");
        when(repository.findByIdAndStatus(ID, Status.PUBLISHED)).thenReturn(Optional.of(guide));
        when(users.findAllById(Set.of(OWNER.id()))).thenReturn(List.of(user));
        var author = service.publishedDetail(ID).author();
        assertThat(author.id()).isEqualTo(user.getId());
        assertThat(author.displayName()).isEqualTo("Updated writer");
        assertThat(author.avatarMediaId()).isEqualTo(user.getAvatarMediaId());
        assertThat(json.writeValueAsString(author)).doesNotContain("private@example.com", "private-subject", "email", "keycloak");
    }
    @Test void publishedSummariesBatchAuthorsAndIncludeTheirAvatars() {
        User user = new User(); user.setId(OWNER.id()); user.setDisplayName("Writer"); user.setAvatarMediaId(UUID.randomUUID());
        var page = PageRequest.of(0, 10);
        when(repository.findAll(any(Specification.class), eq(page))).thenReturn(new PageImpl<>(List.of(guide(), guide())));
        when(users.findAllById(Set.of(OWNER.id()))).thenReturn(List.of(user));
        var result = service.published(null, null, null, null, page);
        assertThat(result.getContent()).allSatisfy(item -> {
            assertThat(item.author().id()).isEqualTo(user.getId());
            assertThat(item.author().avatarMediaId()).isEqualTo(user.getAvatarMediaId());
        });
        verify(users, times(1)).findAllById(Set.of(OWNER.id()));
    }
    @Test void aMissingPublicProfileFallsBackToTravelerWithoutAPicture() {
        when(repository.findByIdAndStatus(ID, Status.PUBLISHED)).thenReturn(Optional.of(guide()));
        when(users.findAllById(Set.of(OWNER.id()))).thenReturn(List.of());
        var author = service.publishedDetail(ID).author();
        assertThat(author.displayName()).isEqualTo("Traveler");
        assertThat(author.id()).isNull(); assertThat(author.avatarMediaId()).isNull();
    }
}
