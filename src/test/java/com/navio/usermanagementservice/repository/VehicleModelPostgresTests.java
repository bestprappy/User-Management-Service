package com.navio.usermanagementservice.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navio.usermanagementservice.config.JacksonConfiguration;
import com.navio.usermanagementservice.dto.VehicleModelDtos.*;
import com.navio.usermanagementservice.exception.UserManagementExceptions.BusinessRuleException;
import com.navio.usermanagementservice.exception.UserManagementExceptions.CatalogNotFoundException;
import com.navio.usermanagementservice.model.VehicleModel.Status;
import com.navio.usermanagementservice.security.AuthenticatedUser;
import com.navio.usermanagementservice.security.NavioRole;
import com.navio.usermanagementservice.security.RequestContext;
import com.navio.usermanagementservice.service.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

@DataJpaTest(properties = {
    "spring.datasource.url=${NAVIO_TEST_DB_URL}", "spring.datasource.username=${NAVIO_TEST_DB_USERNAME:tripplanner}",
    "spring.datasource.password=${NAVIO_TEST_DB_PASSWORD:tripplanner}", "spring.datasource.driver-class-name=org.postgresql.Driver",
    "spring.flyway.enabled=true", "spring.flyway.locations=classpath:db/migration", "spring.flyway.schemas=iam",
    "spring.flyway.default-schema=iam", "spring.flyway.create-schemas=true", "spring.jpa.hibernate.ddl-auto=validate"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "NAVIO_TEST_DB_URL", matches = ".+")
@Import({VehicleModelService.class, VehicleCatalogService.class, AuditService.class, RequestContext.class, JacksonConfiguration.class})
class VehicleModelPostgresTests {
    @Autowired VehicleModelService service;
    @Autowired VehicleCatalogService compatibility;
    @Autowired VehicleModelRepository repository;
    @Autowired AuditLogRepository audits;
    @Autowired ObjectMapper json;
    private final AuthenticatedUser admin = new AuthenticatedUser(null, "test", "admin@example.com", "Admin", Set.of(NavioRole.ADMIN));
    private final AuthenticatedUser moderator = new AuthenticatedUser(null, "test", "mod@example.com", "Mod", Set.of(NavioRole.MODERATOR));

    @Test void stableSeedMatchesBundledCatalogAndUsesPublishedStatus() throws Exception {
        var original = CatalogFixtures.vehicles(json);
        var seeded = compatibility.listVehicles();
        assertThat(seeded).hasSize(original.size());
        for (var previous : original) {
            var current = seeded.stream().filter(item -> item.id().equals(previous.id())).findFirst().orElseThrow();
            assertThat(current.make()).isEqualTo(previous.make());
            assertThat(current.model()).isEqualTo(previous.model());
            assertThat(current.trim()).isEqualTo(previous.trim());
            assertThat(current.rangeKm()).isEqualByComparingTo(previous.rangeKm());
            assertThat(current.batteryCapacityKwh()).isEqualByComparingTo(previous.batteryCapacityKwh());
            assertThat(current.connectorTypes()).containsExactlyElementsOf(previous.connectorTypes());
        }
        assertThat(service.published(null, "TH", PageRequest.of(0, 20)).getTotalElements()).isEqualTo(3);
    }

    @Test void draftPublicationEditArchiveAndSnapshotsHaveExpectedVisibilityAndAudit() {
        var draft = service.create(admin, new WriteRequest(null, fields(null, null, null, List.of(), null, null)));
        assertThat(draft.status()).isEqualTo(Status.DRAFT);
        assertThatThrownBy(() -> service.publishedDetail(draft.id())).isInstanceOf(CatalogNotFoundException.class);
        assertThatThrownBy(() -> service.transition(admin, draft.id(), draft.version(), Status.PUBLISHED)).isInstanceOf(BusinessRuleException.class);
        var complete = service.update(admin, draft.id(), new WriteRequest(draft.version(), fields("75", "490", "WLTP", List.of("TYPE2", "CCS2"), "https://example.com/spec", LocalDate.now())));
        var published = service.transition(admin, draft.id(), complete.version(), Status.PUBLISHED);
        var savedSnapshot = service.publishedDetail(draft.id());
        assertThat(savedSnapshot.rangeKm()).isEqualByComparingTo("490");
        assertThat(savedSnapshot.version()).isEqualTo(published.version());
        assertThatThrownBy(() -> service.update(admin, draft.id(), new WriteRequest(draft.version(), fields("80", "510", "WLTP", List.of("CCS2"), "https://example.com/spec", LocalDate.now())))).isInstanceOf(ObjectOptimisticLockingFailureException.class);
        assertThatThrownBy(() -> service.transition(moderator, draft.id(), published.version(), Status.ARCHIVED)).isInstanceOf(com.navio.usermanagementservice.exception.UserManagementExceptions.ForbiddenOperationException.class);
        var archived = service.transition(admin, draft.id(), published.version(), Status.ARCHIVED);
        assertThat(archived.status()).isEqualTo(Status.ARCHIVED);
        assertThatThrownBy(() -> service.publishedDetail(draft.id())).isInstanceOf(CatalogNotFoundException.class);
        assertThat(compatibility.listVehicles()).noneMatch(car -> car.id().equals(draft.id()));
        assertThat(savedSnapshot.rangeKm()).isEqualByComparingTo("490");
        assertThat(audits.findAll()).extracting(a -> a.getAction()).contains("VEHICLE_MODEL_CREATED", "VEHICLE_MODEL_UPDATED", "VEHICLE_MODEL_PUBLISHED", "VEHICLE_MODEL_ARCHIVED");
    }

    @Test void duplicateIdentityIsRejectedCaseInsensitively() {
        service.create(admin, new WriteRequest(null, fields(null, null, null, List.of(), null, null)));
        var duplicate = new Fields("evtest", "CATALOG UNIQUE", "LONG RANGE", (short) 2027, "TH", null, "UNKNOWN", null, null, List.of(), null, null, null, null, null);
        assertThatThrownBy(() -> service.create(admin, new WriteRequest(null, duplicate)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    private Fields fields(String battery, String range, String standard, List<String> connectors, String source, LocalDate verified) {
        return new Fields("EVTEST", "Catalog Unique", "Long Range", (short) 2027, "TH",
                battery == null ? null : new BigDecimal(battery), "UNKNOWN", range == null ? null : new BigDecimal(range),
                standard, connectors, null, null, null, source, verified);
    }
}
