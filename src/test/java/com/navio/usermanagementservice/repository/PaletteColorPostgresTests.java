package com.navio.usermanagementservice.repository;

import com.navio.usermanagementservice.config.JacksonConfiguration;
import com.navio.usermanagementservice.dto.PaletteColorDtos.CreateRequest;
import com.navio.usermanagementservice.exception.UserManagementExceptions.BusinessRuleException;
import com.navio.usermanagementservice.security.AuthenticatedUser;
import com.navio.usermanagementservice.security.NavioRole;
import com.navio.usermanagementservice.security.RequestContext;
import com.navio.usermanagementservice.service.AuditService;
import com.navio.usermanagementservice.service.PaletteColorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

/** Runs V9 and the palette service against real Postgres with {@code ddl-auto=validate}. */
@DataJpaTest(properties = {
    "spring.datasource.url=${NAVIO_TEST_DB_URL}", "spring.datasource.username=${NAVIO_TEST_DB_USERNAME:tripplanner}",
    "spring.datasource.password=${NAVIO_TEST_DB_PASSWORD:tripplanner}", "spring.datasource.driver-class-name=org.postgresql.Driver",
    "spring.flyway.enabled=true", "spring.flyway.locations=classpath:db/migration", "spring.flyway.schemas=iam",
    "spring.flyway.default-schema=iam", "spring.flyway.create-schemas=true", "spring.jpa.hibernate.ddl-auto=validate"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "NAVIO_TEST_DB_URL", matches = ".+")
@Import({PaletteColorService.class, AuditService.class, RequestContext.class, JacksonConfiguration.class})
class PaletteColorPostgresTests {
    @Autowired PaletteColorService service;
    @Autowired AuditLogRepository audits;
    private final AuthenticatedUser admin = new AuthenticatedUser(null, "test", "admin@example.com", "Admin", Set.of(NavioRole.ADMIN));

    @Test void theSeedKeepsTheTwentyFourBuiltInIdsInTheirPickerOrder() {
        assertThat(service.all()).extracting(c -> c.id()).startsWith(
                "teal", "cyan", "blue", "indigo", "violet", "rose", "coral", "amber",
                "emerald", "pine", "navy", "slate", "plum", "wine", "brick", "rust",
                "lime", "olive", "gold", "sky", "fuchsia", "mocha", "stone", "graphite");
        assertThat(service.all()).filteredOn(c -> c.id().equals("teal")).singleElement()
                .satisfies(teal -> assertThat(teal.hex()).isEqualTo("#00ae84"));
    }

    @Test void addRetireAndRestorePersistAndAreAudited() {
        var created = service.create(admin, new CreateRequest("Sunset Orange", "#E8590C"));
        assertThat(created.id()).isEqualTo("sunset-orange");
        assertThat(service.all()).last().satisfies(c -> assertThat(c.id()).isEqualTo("sunset-orange"));
        assertThat(service.retire(admin, "sunset-orange").retired()).isTrue();
        assertThat(service.all()).filteredOn(c -> c.id().equals("sunset-orange")).singleElement()
                .satisfies(c -> assertThat(c.retired()).isTrue());
        assertThat(service.restore(admin, "sunset-orange").retired()).isFalse();
        assertThat(audits.findAll()).extracting(a -> a.getAction())
                .contains("PALETTE_COLOR_CREATED", "PALETTE_COLOR_RETIRED", "PALETTE_COLOR_RESTORED");
    }

    @Test void guidesMayUseRetiredColorsButNotUnknownOnes() {
        service.retire(admin, "graphite");
        assertThatCode(() -> service.requireKnown(List.of("teal", "graphite"))).doesNotThrowAnyException();
        assertThatThrownBy(() -> service.requireKnown(List.of("teal", "invented"))).isInstanceOf(BusinessRuleException.class);
    }
}
