package com.navio.usermanagementservice;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs every migration against a disposable PostgreSQL, then starts JPA with
 * {@code ddl-auto=validate} exactly as production does. Unlike the disabled
 * full-context test this needs no Keycloak. Skipped unless NAVIO_TEST_DB_URL is set;
 * see docs/agents/database-changes.md.
 */
@DataJpaTest(properties = {
        "spring.datasource.url=${NAVIO_TEST_DB_URL}",
        "spring.datasource.username=${NAVIO_TEST_DB_USERNAME:tripplanner}",
        "spring.datasource.password=${NAVIO_TEST_DB_PASSWORD:tripplanner}",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        "spring.flyway.schemas=iam",
        "spring.flyway.default-schema=iam",
        "spring.flyway.create-schemas=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "NAVIO_TEST_DB_URL", matches = ".+")
class PostgresSchemaTests {

    @Autowired
    Flyway flyway;

    @Autowired jakarta.persistence.EntityManager entityManager;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Test
    void vehicleEnergyProvenanceSurvivesJsonbPersistence() {
        var owner = java.util.UUID.randomUUID();
        jdbc.update("INSERT INTO iam.users(id,auth_subject,email,display_name) VALUES(?,?,?,?)",
                owner, owner.toString(), owner + "@example.com", "Energy profile test");
        var json = new com.navio.usermanagementservice.config.JacksonConfiguration().objectMapper();
        var mapper = new com.navio.usermanagementservice.service.UserMapper(json);
        var vehicle = com.navio.usermanagementservice.model.UserVehicle.builder()
                .userId(owner).make("Test").model("EV")
                .batteryCapacityKwh(new java.math.BigDecimal("60.48"))
                .rangeKm(new java.math.BigDecimal("480"))
                .consumptionKwhPer100km(new java.math.BigDecimal("18.125"))
                .connectorTypes(java.util.List.of("CCS2")).build();
        var profile = mapper.toEnergyProfile(vehicle).observed(
                com.navio.usermanagementservice.dto.VehicleEnergyProfile.MeasurementBasis.UNKNOWN);
        vehicle.getMetadata().put("energyProfile", mapper.toEnergyMetadata(profile));
        vehicle.getMetadata().put("otherFeature", java.util.Map.of("enabled", true));
        entityManager.persist(vehicle);
        entityManager.flush();
        var id = vehicle.getId();
        entityManager.clear();
        var reloaded = entityManager.find(com.navio.usermanagementservice.model.UserVehicle.class, id);
        assertThat(mapper.toEnergyProfile(reloaded)).isEqualTo(profile);
        assertThat(reloaded.getConsumptionKwhPer100km()).isEqualByComparingTo("18.125");
        assertThat(reloaded.getMetadata()).containsKey("otherFeature");

        // Phase 2 reset persists a null scalar together with an explicit range profile.
        var fallback = com.navio.usermanagementservice.dto.VehicleEnergyProfile.defaultFor(null, reloaded.getRangeKm());
        reloaded.setConsumptionKwhPer100km(null);
        reloaded.getMetadata().put("energyProfile", mapper.toEnergyMetadata(fallback));
        entityManager.flush();
        entityManager.clear();
        var reset = entityManager.find(com.navio.usermanagementservice.model.UserVehicle.class, id);
        assertThat(reset.getConsumptionKwhPer100km()).isNull();
        assertThat(mapper.toEnergyProfile(reset)).usingRecursiveComparison()
                .withComparatorForType(java.math.BigDecimal::compareTo, java.math.BigDecimal.class).isEqualTo(fallback);
        assertThat(reset.getMetadata()).containsKey("otherFeature");
    }

    @Test
    void migrationsApplyAndEntityMappingsMatchThePostgresSchema() {
        assertThat(flyway.info().pending()).isEmpty();
    }
}
