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
@org.springframework.context.annotation.Import(com.navio.usermanagementservice.library.SavedLibraryRepository.class)
@EnabledIfEnvironmentVariable(named = "NAVIO_TEST_DB_URL", matches = ".+")
class PostgresSchemaTests {
    @Autowired com.navio.usermanagementservice.library.SavedLibraryRepository library;

    @Test void libraryDeduplicatesSourcesSearchesWholeCollectionAndEnforcesTrashOwnership() {
        var user=java.util.UUID.randomUUID();
        jdbc.update("INSERT INTO iam.users(id,auth_subject,email,display_name) VALUES(?,?,?,?)",user,user.toString(),user+"@example.com","Library test");
        var item=new com.navio.usermanagementservice.library.LibraryContracts.Item(java.util.UUID.randomUUID(),"PLACE","google:abc","Café Tokyo","Japan","ChIJabc",1.0,2.0,"Japan","Tokyo","Cafe",null,null,null,null,null,java.util.List.of());
        var a=library.save(user,item,null,null);
        var b=library.save(user,item,new com.navio.usermanagementservice.library.LibraryContracts.Source("token","Trip"),null);
        assertThat(b.id()).isEqualTo(a.id());assertThat(b.sources()).hasSize(1);
        var q=new com.navio.usermanagementservice.library.LibraryContracts.Query("PLACE","cafe","NEWEST",0,24,null,null,null,null,null,null);
        assertThat(library.list(user,q).total()).isEqualTo(1);
        assertThat(library.states(java.util.UUID.randomUUID(),java.util.List.of("google:abc")).getFirst().saved()).isFalse();
        assertThat(org.assertj.core.api.Assertions.catchThrowable(()->library.remove(java.util.UUID.randomUUID(),a.id()))).isInstanceOf(com.navio.usermanagementservice.exception.UserManagementExceptions.SavedPlaceNotFoundException.class);
        library.remove(user,a.id());assertThat(library.list(user,q).total()).isZero();
        assertThat(library.restore(user,a.id(),50).id()).isEqualTo(a.id());
        library.remove(user,a.id());jdbc.update("UPDATE iam.saved_library_items SET deleted_at=now()-interval '8 days' WHERE id=?",a.id());
        assertThat(org.assertj.core.api.Assertions.catchThrowable(()->library.restore(user,a.id(),50))).isInstanceOf(com.navio.usermanagementservice.exception.UserManagementExceptions.SavedPlaceNotFoundException.class);
        library.expire(user);assertThat(jdbc.queryForObject("SELECT count(*) FROM iam.saved_library_sources WHERE item_id=?",Long.class,a.id())).isZero();
    }

    @Test void personalFavoriteIntegrationDoesNotMakeDiscoverySaveAnAnchor() {
        var user=java.util.UUID.randomUUID();var anchor=java.util.UUID.randomUUID();
        jdbc.update("INSERT INTO iam.users(id,auth_subject,email,display_name) VALUES(?,?,?,?)",user,user.toString(),user+"@example.com","Anchor test");
        jdbc.update("INSERT INTO iam.user_saved_places(id,user_id,label,name,lat,lng,is_default) VALUES(?,?,?,?,?,?,?)",anchor,user,"Home","Home",1.0,2.0,true);
        var saved=library.states(user,java.util.List.of("coordinates:1.000000,2.000000")).getFirst();
        assertThat(saved.saved()).isTrue();
        library.remove(user,saved.id());
        assertThat(jdbc.queryForObject("SELECT is_default FROM iam.user_saved_places WHERE id=?",Boolean.class,anchor)).isTrue();
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NULL FROM iam.user_saved_places WHERE id=?",Boolean.class,anchor)).isTrue();
        jdbc.update("UPDATE iam.user_saved_places SET lat=3 WHERE id=?",anchor);
        assertThat(library.states(user,java.util.List.of("coordinates:3.000000,2.000000")).getFirst().saved()).isTrue();
    }

    @Test void planLimitCountsOnlyActiveAndRestoreCannotBypassIt() {
        var user=java.util.UUID.randomUUID();
        jdbc.update("INSERT INTO iam.users(id,auth_subject,email,display_name) VALUES(?,?,?,?)",user,user.toString(),user+"@example.com","Quota test");
        java.util.UUID first=null;
        for(int i=0;i<50;i++) {
            var row=library.save(user,new com.navio.usermanagementservice.library.LibraryContracts.Item(java.util.UUID.randomUUID(),"PLAN","plan:"+i,"Plan "+i,null,null,null,null,"Japan","Tokyo",null,"Owner",2,null,null,null,java.util.List.of()),null,50);
            if(i==0)first=row.id();
        }
        var extra=new com.navio.usermanagementservice.library.LibraryContracts.Item(java.util.UUID.randomUUID(),"PLAN","plan:extra","Extra",null,null,null,null,null,null,null,null,1,null,null,null,java.util.List.of());
        assertThat(org.assertj.core.api.Assertions.catchThrowable(()->library.save(user,extra,null,50))).isInstanceOf(com.navio.usermanagementservice.exception.UserManagementExceptions.BusinessRuleException.class);
        var removed=first;library.remove(user,removed);library.save(user,extra,null,50);
        assertThat(org.assertj.core.api.Assertions.catchThrowable(()->library.restore(user,removed,50))).isInstanceOf(com.navio.usermanagementservice.exception.UserManagementExceptions.BusinessRuleException.class);
        var q=new com.navio.usermanagementservice.library.LibraryContracts.Query("PLAN","","NEWEST",1,24,"Japan,France",null,null,null,null,null);
        assertThat(library.list(user,q).total()).isEqualTo(49);assertThat(library.list(user,q).items()).hasSize(24);
    }

    @Test void favoriteSearchNormalizesAccentsAndProviderUpdatesMoveCanonicalLocation() {
        var user=java.util.UUID.randomUUID();var id=java.util.UUID.randomUUID();
        jdbc.update("INSERT INTO iam.users(id,auth_subject,email,display_name) VALUES(?,?,?,?)",user,user.toString(),user+"@example.com","Search test");
        jdbc.update("INSERT INTO iam.user_saved_places(id,user_id,label,name,address,lat,lng,provider_place_id) VALUES(?,?,?,?,?,?,?,?)",id,user,"Café","Café", "A very long address with many words and distant city information to make document similarity low",1.0,2.0,"ChIJcafe");
        var q=new com.navio.usermanagementservice.library.LibraryContracts.Query("PLACE","cafe","NEWEST",0,24,null,null,null,null,null,null);
        assertThat(library.list(user,q).total()).isEqualTo(1);
        jdbc.update("UPDATE iam.user_saved_places SET lat=4 WHERE id=?",id);
        assertThat(library.list(user,q).items().getFirst().lat()).isEqualTo(4.0);
    }

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
