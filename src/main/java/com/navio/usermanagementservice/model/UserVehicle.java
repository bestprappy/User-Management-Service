package com.navio.usermanagementservice.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A saved vehicle in the user's garage.
 *
 * <p>Trip Planning copies the selected vehicle into {@code trip.trip_vehicles}
 * rather than referencing this row, so editing a profile later never silently
 * rewrites the assumptions behind an existing trip.
 */
@Entity
@Table(name = "user_vehicles", schema = "iam")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserVehicle {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(length = 100)
    private String nickname;

    @Column(nullable = false, length = 100)
    private String make;

    @Column(nullable = false, length = 100)
    private String model;

    @Column(name = "year")
    private Short year;

    @Column(name = "battery_capacity_kwh", nullable = false, precision = 8, scale = 2)
    private BigDecimal batteryCapacityKwh;

    @Column(name = "range_km", nullable = false, precision = 8, scale = 2)
    private BigDecimal rangeKm;

    @Column(name = "consumption_kwh_per_100km", precision = 8, scale = 3)
    private BigDecimal consumptionKwhPer100km;

    @Column(name = "max_ac_kw", precision = 7, scale = 2)
    private BigDecimal maxAcKw;

    @Column(name = "max_dc_kw", precision = 7, scale = 2)
    private BigDecimal maxDcKw;

    @Builder.Default
    @Column(name = "starting_battery_pct", nullable = false)
    private Integer startingBatteryPct = 80;

    @Column(name = "image_url", length = 2048)
    private String imageUrl;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "connector_types", nullable = false)
    private List<String> connectorTypes = new ArrayList<>();

    @Builder.Default
    @Column(name = "is_default", nullable = false)
    private boolean isDefault = false;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata_jsonb", nullable = false)
    private Map<String, Object> metadata = new HashMap<>();

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
        if (version == null) {
            version = 0L;
        }
        if (connectorTypes == null) {
            connectorTypes = new ArrayList<>();
        }
        if (metadata == null) {
            metadata = new HashMap<>();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }
}
