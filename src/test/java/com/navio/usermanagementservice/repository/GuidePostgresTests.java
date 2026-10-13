package com.navio.usermanagementservice.repository;

import com.navio.usermanagementservice.config.JacksonConfiguration;
import com.navio.usermanagementservice.dto.GuideDtos.*;
import com.navio.usermanagementservice.exception.UserManagementExceptions.GuideConflictException;
import com.navio.usermanagementservice.exception.UserManagementExceptions.GuideNotFoundException;
import com.navio.usermanagementservice.model.Guide;
import com.navio.usermanagementservice.model.Guide.Status;
import com.navio.usermanagementservice.security.AuthenticatedUser;
import com.navio.usermanagementservice.security.NavioRole;
import com.navio.usermanagementservice.security.RequestContext;
import com.navio.usermanagementservice.service.AuditService;
import com.navio.usermanagementservice.service.GuideService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.util.*;

import static org.assertj.core.api.Assertions.*;

/** Guides against real PostgreSQL: the V7 table, its seed, jsonb content and the lifecycle. */
@DataJpaTest(properties = {
    "spring.datasource.url=${NAVIO_TEST_DB_URL}", "spring.datasource.username=${NAVIO_TEST_DB_USERNAME:tripplanner}",
    "spring.datasource.password=${NAVIO_TEST_DB_PASSWORD:tripplanner}", "spring.datasource.driver-class-name=org.postgresql.Driver",
    "spring.flyway.enabled=true", "spring.flyway.locations=classpath:db/migration", "spring.flyway.schemas=iam",
    "spring.flyway.default-schema=iam", "spring.flyway.create-schemas=true", "spring.jpa.hibernate.ddl-auto=validate"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "NAVIO_TEST_DB_URL", matches = ".+")
@Import({GuideService.class, AuditService.class, RequestContext.class, JacksonConfiguration.class})
class GuidePostgresTests {
    static final UUID SEED = UUID.fromString("8f2b6c1e-5d3a-4c7b-9e1f-2a6d4b8c0e01");
    @Autowired GuideService service;
    @Autowired GuideRepository repository;
    @Autowired AuditLogRepository audits;
    @Autowired jakarta.persistence.EntityManager entityManager;
    private final AuthenticatedUser admin = new AuthenticatedUser(null, "test", "admin@example.com", "Admin", Set.of(NavioRole.ADMIN));

    static Fields fields(String placeId, String body, Map<String, Place> places) {
        Content content = new Content(List.of(new Category("visit", "Places to visit", "landmark", "blue")),
                Map.of("visit", new ListHeader("visit", "visit")), places, body);
        return new Fields("Test city", "Thailand", "TH", new Destination("GOOGLE", placeId, "Test city", 18.0, 99.0),
                "A test guide.", "GOOGLE:" + placeId, content);
    }

    static final String HEADER = "<div data-embed=\"placeList\" data-list-id=\"visit\"></div>";
    static final String CARD = "<div data-embed=\"guidePlace\" data-place-id=\"ChIJ-test-temple\"></div>";
    static final Place TEMPLE = new Place("GOOGLE:ChIJ-test-temple", "GOOGLE", "ChIJ-test-temple", "Temple", "Old town",
            18.1, 99.1, "buddhist_temple", "Buddhist Temple", List.of("Temple", "Free"));

    @Test void theSeededBangkokGuideIsPublishedWithItsTwentyFourPlaces() {
        PublicResponse bangkok = service.publishedDetail(SEED);
        assertThat(bangkok.guide().name()).isEqualTo("Bangkok");
        assertThat(bangkok.guide().countryCode()).isEqualTo("TH");
        assertThat(bangkok.guide().content().places()).hasSize(24);
        assertThat(bangkok.guide().content().categories()).extracting(Category::id).containsExactly("visit", "eat", "stay");
        assertThat(bangkok.guide().content().places().get("ChIJPzZsMU6Z4jARQUzvk913bCo").tags()).contains("Royal palace");
        assertThat(service.published(PageRequest.of(0, 50)).getContent()).extracting(PublicResponse::id).contains(SEED);
    }

    @Test void contentRoundTripsThroughJsonbUnchanged() {
        Fields written = fields("ChIJ-roundtrip", HEADER + "\n\n" + CARD + "\n\nWrite **here** — with <span data-color=\"red\">colour</span>.",
                Map.of("ChIJ-test-temple", TEMPLE));
        AdminResponse created = service.create(admin, new CreateRequest(written));
        repository.flush();
        entityManager.clear();
        assertThat(service.detail(admin, created.id()).guide()).isEqualTo(written);
    }

    @Test void aGuideMovesFromDraftToPublishedAndBackThenIsDeleted() {
        AdminResponse draft = service.create(admin, new CreateRequest(fields("ChIJ-lifecycle", HEADER, Map.of())));
        assertThat(draft.status()).isEqualTo(Status.DRAFT);
        assertThatThrownBy(() -> service.publishedDetail(draft.id())).isInstanceOf(GuideNotFoundException.class);

        AdminResponse edited = service.update(admin, draft.id(), new WriteRequest(draft.version(),
                fields("ChIJ-lifecycle", HEADER + "\n\n" + CARD, Map.of("ChIJ-test-temple", TEMPLE))));
        assertThatThrownBy(() -> service.update(admin, draft.id(), new WriteRequest(draft.version(), fields("ChIJ-lifecycle", HEADER, Map.of()))))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        AdminResponse published = service.publish(admin, draft.id(), edited.version());
        assertThat(service.publishedDetail(draft.id()).publishedAt()).isNotNull();
        AdminResponse unpublished = service.unpublish(admin, draft.id(), published.version());
        assertThatThrownBy(() -> service.publishedDetail(draft.id())).isInstanceOf(GuideNotFoundException.class);

        service.delete(admin, draft.id(), unpublished.version());
        assertThat(repository.findById(draft.id())).isEmpty();
        assertThat(audits.findAll()).extracting(a -> a.getAction())
                .contains("GUIDE_CREATED", "GUIDE_UPDATED", "GUIDE_PUBLISHED", "GUIDE_UNPUBLISHED", "GUIDE_DELETED");
    }

    @Test void eachDestinationHasOneGuideEvenPastTheServiceCheck() {
        service.create(admin, new CreateRequest(fields("ChIJ-unique", HEADER, Map.of())));
        assertThatThrownBy(() -> service.create(admin, new CreateRequest(fields("ChIJ-unique", HEADER, Map.of()))))
                .isInstanceOf(GuideConflictException.class);
        Guide duplicate = new Guide();
        duplicate.setId(UUID.randomUUID());
        duplicate.setName("Duplicate"); duplicate.setDestinationProvider("GOOGLE"); duplicate.setDestinationPlaceId("ChIJ-unique");
        duplicate.setDestinationName("Duplicate"); duplicate.setDestinationLat(0.0); duplicate.setDestinationLng(0.0);
        duplicate.setCoverPlaceId("GOOGLE:ChIJ-unique"); duplicate.setContent(new HashMap<>(Map.of("body", "")));
        assertThatThrownBy(() -> repository.saveAndFlush(duplicate)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void anotherAdministratorCanReadEditPublishAndDeleteTheSameGuide() {
        var secondAdmin = new AuthenticatedUser(null, "second-admin", "second@example.com", "Second admin", Set.of(NavioRole.ADMIN));
        var created = service.create(admin, new CreateRequest(fields("ChIJ-shared-admins", HEADER, Map.of())));
        entityManager.clear();
        assertThat(service.search(secondAdmin, "Test city", null, PageRequest.of(0, 100)).getContent())
                .extracting(Summary::id).contains(created.id());
        assertThat(service.detail(secondAdmin, created.id()).version()).isEqualTo(created.version());
        var edited = service.update(secondAdmin, created.id(), new WriteRequest(created.version(),
                fields("ChIJ-shared-admins", HEADER + "\n\n" + CARD, Map.of("ChIJ-test-temple", TEMPLE))));
        entityManager.clear();
        assertThat(service.detail(admin, created.id()).guide().content().places()).containsKey("ChIJ-test-temple");
        assertThatThrownBy(() -> service.publish(admin, created.id(), created.version()))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        var published = service.publish(secondAdmin, created.id(), edited.version());
        assertThat(service.publishedDetail(created.id()).id()).isEqualTo(created.id());
        service.delete(secondAdmin, created.id(), published.version());
        assertThatThrownBy(() -> service.detail(admin, created.id())).isInstanceOf(GuideNotFoundException.class);
    }
}
