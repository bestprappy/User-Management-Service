package com.navio.usermanagementservice.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navio.usermanagementservice.config.JacksonConfiguration;
import com.navio.usermanagementservice.dto.GuideDtos.*;
import com.navio.usermanagementservice.exception.UserManagementExceptions.*;
import com.navio.usermanagementservice.model.Guide;
import com.navio.usermanagementservice.model.Guide.Status;
import com.navio.usermanagementservice.repository.AuditLogRepository;
import com.navio.usermanagementservice.repository.GuideRepository;
import com.navio.usermanagementservice.repository.UserRepository;
import com.navio.usermanagementservice.security.AuthenticatedUser;
import com.navio.usermanagementservice.security.NavioRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GuideServiceTests {
    static final UUID ID = UUID.fromString("00000000-0000-4000-8000-000000000042");
    static final AuthenticatedUser ADMIN = new AuthenticatedUser(UUID.fromString("00000000-0000-4000-8000-000000000001"), "a", "a@example.com", "Admin", Set.of(NavioRole.ADMIN));
    static final AuthenticatedUser MODERATOR = new AuthenticatedUser(UUID.randomUUID(), "m", "m@example.com", "Mod", Set.of(NavioRole.MODERATOR));

    @Mock GuideRepository repository;
    @Mock UserRepository users;
    @Mock AuditService audit;
    @Mock AuditLogRepository auditLogs;
    final ObjectMapper json = new JacksonConfiguration().objectMapper();
    GuideService service;

    @BeforeEach void setUp() {
        service = new GuideService(repository, users, audit, auditLogs, json);
        lenient().when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    static Place place(String key) {
        return new Place("GOOGLE:" + key, "GOOGLE", key, key, "", 13.7, 100.5, null, null, List.of());
    }

    static Content content(String body, Map<String, Place> places) {
        return new Content(List.of(new Category("visit", "Places to visit", "landmark", "blue")),
                Map.of("visit", new ListHeader("visit", "visit")), places, body);
    }

    static Fields fields(Content content) {
        return new Fields("Chiang Mai", "Thailand", "TH", new Destination("GOOGLE", "ChIJ-cm", "Chiang Mai", 18.79, 98.98),
                "", "GOOGLE:ChIJ-cm", content);
    }

    static final String HEADER = "<div data-embed=\"placeList\" data-list-id=\"visit\"></div>";
    static String card(String key) { return "<div data-embed=\"guidePlace\" data-place-id=\"" + key + "\"></div>"; }

    Guide stored(Status status, long version, Content content) {
        Guide guide = new Guide();
        guide.setId(ID);
        guide.setName("Chiang Mai"); guide.setCountry("Thailand");
        guide.setDestinationProvider("GOOGLE"); guide.setDestinationPlaceId("ChIJ-cm"); guide.setDestinationName("Chiang Mai");
        guide.setDestinationLat(18.79); guide.setDestinationLng(98.98); guide.setCoverPlaceId("GOOGLE:ChIJ-cm");
        guide.setContent(json.convertValue(content, new TypeReference<>() {}));
        guide.setStatus(status); guide.setVersion(version);
        return guide;
    }

    @Test void moderatorsCannotReadOrChangeGuides() {
        assertThatThrownBy(() -> service.search(MODERATOR, null, null, org.springframework.data.domain.Pageable.unpaged())).isInstanceOf(ForbiddenOperationException.class);
        assertThatThrownBy(() -> service.create(MODERATOR, new CreateRequest(fields(content(HEADER, Map.of()))))).isInstanceOf(ForbiddenOperationException.class);
        assertThatThrownBy(() -> service.delete(MODERATOR, ID, 0)).isInstanceOf(ForbiddenOperationException.class);
        assertThatThrownBy(() -> service.detail(MODERATOR, ID)).isInstanceOf(ForbiddenOperationException.class);
        assertThatThrownBy(() -> service.update(MODERATOR, ID, new WriteRequest(0L, fields(content(HEADER, Map.of()))))).isInstanceOf(ForbiddenOperationException.class);
        assertThatThrownBy(() -> service.publish(MODERATOR, ID, 0)).isInstanceOf(ForbiddenOperationException.class);
        assertThatThrownBy(() -> service.unpublish(MODERATOR, ID, 0)).isInstanceOf(ForbiddenOperationException.class);
        verifyNoInteractions(repository, audit);
    }

    @Test void aDestinationThatAlreadyHasAGuideCannotGetASecondOne() {
        when(repository.existsByDestinationProviderAndDestinationPlaceId("GOOGLE", "ChIJ-cm")).thenReturn(true);
        assertThatThrownBy(() -> service.create(ADMIN, new CreateRequest(fields(content(HEADER, Map.of())))))
                .isInstanceOf(GuideConflictException.class).hasMessage("Chiang Mai already has a guide");
        verify(repository, never()).saveAndFlush(any());
    }

    @Test void aNewGuideStartsAsADraftCreatedAndAuditedByItsAdmin() {
        AdminResponse created = service.create(ADMIN, new CreateRequest(fields(content(HEADER, Map.of()))));
        assertThat(created.status()).isEqualTo(Status.DRAFT);
        assertThat(created.guide().content().body()).isEqualTo(HEADER);
        verify(audit).record(eq(ADMIN.id()), eq("GUIDE_CREATED"), eq("GUIDE"), any(), isNull(), anyMap(), anyMap());
    }

    @Test void contentWhosePartsDoNotReferToEachOtherIsRefused() {
        Category visit = new Category("visit", "Visit", "landmark", "blue");
        List<Content> broken = List.of(
                new Content(List.of(visit, visit), Map.of(), Map.of(), ""),
                new Content(List.of(visit), Map.of("visit", new ListHeader("visit", "missing")), Map.of(), ""),
                new Content(List.of(visit), Map.of("other", new ListHeader("visit", "visit")), Map.of(), ""),
                new Content(List.of(visit), Map.of(), Map.of("a", place("b")), ""),
                new Content(List.of(visit), Map.of(), Map.of("a", new Place("MAPBOX:a", "GOOGLE", "a", "A", "", 0.0, 0.0, null, null, List.of())), ""));
        for (Content content : broken) {
            assertThatThrownBy(() -> service.validateContent(content)).isInstanceOf(BusinessRuleException.class);
        }
    }

    @Test void aSaveBasedOnAnOlderVersionIsRefused() {
        when(repository.findById(ID)).thenReturn(Optional.of(stored(Status.DRAFT, 3, content(HEADER, Map.of()))));
        assertThatThrownBy(() -> service.update(ADMIN, ID, new WriteRequest(2L, fields(content(HEADER, Map.of())))))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        assertThatThrownBy(() -> service.delete(ADMIN, ID, 2)).isInstanceOf(ObjectOptimisticLockingFailureException.class);
        verify(repository, never()).saveAndFlush(any());
        verify(repository, never()).delete(any(Guide.class));
    }

    @Test void aGuideWithNoPlaceCardsCannotBePublished() {
        Content cardWithoutPlace = content(HEADER + "\n\n" + card("pending-1"), Map.of());
        when(repository.findById(ID)).thenReturn(Optional.of(stored(Status.DRAFT, 0, cardWithoutPlace)));
        assertThatThrownBy(() -> service.publish(ADMIN, ID, 0)).isInstanceOf(BusinessRuleException.class)
                .hasMessage("Add at least one place before publishing");
    }

    @Test void publishingAndUnpublishingSetTheDateAndAreAudited() {
        Guide guide = stored(Status.DRAFT, 0, content(HEADER + "\n\n" + card("a"), Map.of("a", place("a"))));
        when(repository.findById(ID)).thenReturn(Optional.of(guide));
        assertThat(service.publish(ADMIN, ID, 0).publishedAt()).isNotNull();
        assertThat(guide.getStatus()).isEqualTo(Status.PUBLISHED);
        service.unpublish(ADMIN, ID, 0);
        assertThat(guide.getStatus()).isEqualTo(Status.DRAFT);
        assertThat(guide.getPublishedAt()).isNull();
        verify(audit).record(any(), eq("GUIDE_PUBLISHED"), eq("GUIDE"), eq(ID), anyMap(), anyMap(), anyMap());
        verify(audit).record(any(), eq("GUIDE_UNPUBLISHED"), eq("GUIDE"), eq(ID), anyMap(), anyMap(), anyMap());
    }

    @Test void aPublishedGuideCannotBeEditedIntoHavingNoPlaces() {
        when(repository.findById(ID)).thenReturn(Optional.of(stored(Status.PUBLISHED, 0, content(HEADER + "\n\n" + card("a"), Map.of("a", place("a"))))));
        assertThatThrownBy(() -> service.update(ADMIN, ID, new WriteRequest(0L, fields(content(HEADER, Map.of())))))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test void autosavedEditsAreAuditedAtMostOncePerAdminPerWindow() {
        when(repository.findById(ID)).thenReturn(Optional.of(stored(Status.DRAFT, 0, content(HEADER, Map.of()))));
        when(auditLogs.existsByResourceTypeAndResourceIdAndActorUserIdAndActionAndCreatedAtAfter(eq("GUIDE"), eq(ID), eq(ADMIN.id()), eq("GUIDE_UPDATED"), any()))
                .thenReturn(false, true);
        service.update(ADMIN, ID, new WriteRequest(0L, fields(content(HEADER, Map.of()))));
        service.update(ADMIN, ID, new WriteRequest(0L, fields(content(HEADER, Map.of()))));
        verify(audit, times(1)).record(any(), eq("GUIDE_UPDATED"), any(), any(), any(), any(), any());
        verify(repository, times(2)).saveAndFlush(any());
    }

    @Test void deletingAGuideRemovesItAndKeepsAnAuditRecord() {
        Guide guide = stored(Status.PUBLISHED, 4, content(HEADER, Map.of()));
        when(repository.findById(ID)).thenReturn(Optional.of(guide));
        service.delete(ADMIN, ID, 4);
        verify(repository).delete(guide);
        verify(audit).record(eq(ADMIN.id()), eq("GUIDE_DELETED"), eq("GUIDE"), eq(ID), anyMap(), isNull(), anyMap());
    }

    @Test void travelersCannotReadADraft() {
        when(repository.findByIdAndStatus(ID, Status.PUBLISHED)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.publishedDetail(ID)).isInstanceOf(GuideNotFoundException.class);
    }

    @Test void theDashboardCountsOnlyCardsWithAPlaceAndListsCategoriesInUse() {
        Content mixed = new Content(
                List.of(new Category("visit", "Visit", "landmark", "blue"), new Category("eat", "Eat", "utensils", "rose")),
                Map.of("visit", new ListHeader("visit", "visit")), Map.of("a", place("a")),
                HEADER + "\n\n" + card("a") + "\n\n" + card("pending-1") + "\n\n" + card("missing"));
        Guide guide = stored(Status.DRAFT, 0, mixed);
        when(repository.findAll(any(org.springframework.data.jpa.domain.Specification.class), any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(guide)));
        Summary summary = service.search(ADMIN, null, null, org.springframework.data.domain.PageRequest.of(0, 10)).getContent().getFirst();
        assertThat(summary.placeCount()).isEqualTo(1);
        assertThat(summary.categories()).extracting(Category::id).containsExactly("visit");
    }
}
