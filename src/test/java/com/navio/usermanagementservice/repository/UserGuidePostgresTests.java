package com.navio.usermanagementservice.repository;

import com.navio.usermanagementservice.config.JacksonConfiguration;
import com.navio.usermanagementservice.dto.GuideDtos.*;
import com.navio.usermanagementservice.exception.UserManagementExceptions.GuideNotFoundException;
import com.navio.usermanagementservice.model.User;
import com.navio.usermanagementservice.security.*;
import com.navio.usermanagementservice.service.UserGuideService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.*;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@DataJpaTest(properties = {
    "spring.datasource.url=${NAVIO_TEST_DB_URL}", "spring.datasource.username=${NAVIO_TEST_DB_USERNAME:tripplanner}",
    "spring.datasource.password=${NAVIO_TEST_DB_PASSWORD:tripplanner}", "spring.datasource.driver-class-name=org.postgresql.Driver",
    "spring.flyway.enabled=true", "spring.flyway.locations=classpath:db/migration", "spring.flyway.schemas=iam",
    "spring.flyway.default-schema=iam", "spring.flyway.create-schemas=true", "spring.jpa.hibernate.ddl-auto=validate"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "NAVIO_TEST_DB_URL", matches = ".+")
@Import({UserGuideService.class, JacksonConfiguration.class})
class UserGuidePostgresTests {
    @Autowired UserGuideService service;
    @Autowired UserGuideRepository guides;
    @Autowired UserRepository users;
    @Autowired jakarta.persistence.EntityManager entityManager;
    AuthenticatedUser owner;
    AuthenticatedUser other;
    @BeforeEach void setup() { owner = account("First"); other = account("Second"); }
    AuthenticatedUser account(String name) {
        User user = users.saveAndFlush(User.builder().authSubject(UUID.randomUUID().toString()).email(name + "@example.com").displayName(name).build());
        return new AuthenticatedUser(user.getId(), user.getAuthSubject(), user.getEmail(), name, Set.of(NavioRole.USER));
    }
    static Fields fields(String title, String city, String country, String id) {
        return new Fields(title, country, null, new Destination("GOOGLE", id, city, 13.7, 100.5), "Written **tips**", "GOOGLE:" + id,
                new Content(List.of(new Category("visit", "Sights", "landmark", "blue")), Map.of("visit", new ListHeader("visit", "visit")),
                        Map.of("temple", new Place("GOOGLE:temple", "GOOGLE", "temple", "Temple", "Old town", 13.7, 100.5, null, null, List.of("Quiet"))),
                        "Opening words\n\n<div data-embed=\"placeList\" data-list-id=\"visit\"></div>\n\n<div data-embed=\"guidePlace\" data-place-id=\"temple\"></div>\n\nWalk here."));
    }
    @Test void lifecycleRoundTripsJsonbAndSeparatesPrivateAndPublishedReads() {
        Fields written = fields("My favorite corners", "Bangkok", "Thailand", "ChIJ-bkk");
        var draft = service.create(owner, new CreateRequest(written));
        entityManager.clear();
        assertThat(service.detail(owner, draft.id()).guide()).isEqualTo(written);
        assertThatThrownBy(() -> service.detail(other, draft.id())).isInstanceOf(GuideNotFoundException.class);
        assertThatThrownBy(() -> service.publishedDetail(draft.id())).isInstanceOf(GuideNotFoundException.class);
        var published = service.publish(owner, draft.id(), draft.version());
        entityManager.clear();
        assertThat(service.publishedDetail(draft.id()).author().displayName()).isEqualTo("First");
        assertThatThrownBy(() -> service.unpublish(owner, draft.id(), draft.version())).isInstanceOf(ObjectOptimisticLockingFailureException.class);
        var privateAgain = service.unpublish(owner, draft.id(), published.version());
        assertThatThrownBy(() -> service.publishedDetail(draft.id())).isInstanceOf(GuideNotFoundException.class);
        service.delete(owner, draft.id(), privateAgain.version());
        assertThat(guides.findById(draft.id())).isEmpty();
    }
    @Test void multipleAuthorsAndMultipleGuidesCanShareTheAdminSeedDestination() {
        var fields = fields("Bangkok stories", "Bangkok", "Thailand", "ChIJ82ENKDJgHTERIEjiXb5w7QQ");
        var first = service.create(owner, new CreateRequest(fields));
        var second = service.create(other, new CreateRequest(fields));
        var third = service.create(owner, new CreateRequest(fields));
        assertThat(service.mine(owner, null, null, PageRequest.of(0, 20)).getContent()).extracting(Summary::id).containsExactlyInAnyOrder(first.id(), third.id());
        assertThat(service.mine(other, null, null, PageRequest.of(0, 20)).getContent()).extracting(Summary::id).containsExactly(second.id());
        assertThat(users.findById(owner.id())).isPresent();
    }
    @Test void countryDiscoveryIncludesTokyoOsakaAndOlderCountryWideGuides() {
        var japan = service.create(owner, new CreateRequest(fields("Japan gaming guide", "Japan", "", "ChIJ-japan")));
        service.publish(owner, japan.id(), japan.version());
        var tokyo = service.create(owner, new CreateRequest(fields("Tokyo tips", "Tokyo", "Japan", "ChIJ-tokyo")));
        service.publish(owner, tokyo.id(), tokyo.version());
        var osaka = service.create(other, new CreateRequest(fields("Osaka tips", "Osaka", "Japan", "ChIJ-osaka")));
        service.publish(other, osaka.id(), osaka.version());
        var bangkok = service.create(other, new CreateRequest(fields("Bangkok tips", "Bangkok", "Thailand", "ChIJ-bangkok")));
        service.publish(other, bangkok.id(), bangkok.version());
        service.create(owner, new CreateRequest(fields("Private Japan notes", "Japan", "Japan", "ChIJ-japan")));
        for (String country : List.of("Japan", "JP")) {
            assertThat(service.published(null, null, country, null, PageRequest.of(0, 20)).getContent()).extracting(s -> s.id())
                    .containsExactlyInAnyOrder(japan.id(), tokyo.id(), osaka.id());
        }
    }

    @Test void discoverySearchesDestinationAndCountryAfterTheTitleChangesAndExcludesDrafts() {
        var first = service.create(owner, new CreateRequest(fields("Hidden corners", "Bangkok", "Thailand", "ChIJ-bkk")));
        service.publish(owner, first.id(), first.version());
        var second = service.create(other, new CreateRequest(fields("Tokyo after dark", "Tokyo", "Japan", "ChIJ-tokyo")));
        service.publish(other, second.id(), second.version());
        service.create(owner, new CreateRequest(fields("Private Bangkok notes", "Bangkok", "Thailand", "ChIJ-bkk")));
        var page = PageRequest.of(0, 1, Sort.by("id"));
        assertThat(service.published("Thailand", null, null, null, page).getTotalElements()).isEqualTo(1);
        assertThat(service.published("Bangkok", null, null, null, page).getContent()).extracting(s -> s.id()).containsExactly(first.id());
        assertThat(service.published(null, List.of("Bangkok", "Tokyo"), null, null, page).getTotalElements()).isEqualTo(2);
        assertThat(service.published(null, null, null, List.of("GOOGLE:ChIJ-tokyo"), page).getContent()).extracting(s -> s.id()).containsExactly(second.id());
        assertThat(service.published("%", null, null, null, page).getTotalElements()).isZero();
        assertThat(service.published(null, List.of("Bangkok"), "Japan", null, page).getTotalElements()).isZero();
        assertThat(service.published(null, null, null, null, PageRequest.of(1, 1, Sort.by("id"))).getContent()).hasSize(1);
    }
}
