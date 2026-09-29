package com.navio.usermanagementservice.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "vehicle_models", schema = "iam")
@Getter @Setter @NoArgsConstructor
public class VehicleModel {
    public enum Status { DRAFT, PUBLISHED, ARCHIVED }
    @Id @Column(length = 120) private String id;
    @Column(nullable = false, length = 100) private String make;
    @Column(nullable = false, length = 100) private String model;
    @Column(nullable = false, length = 100) private String trim = "";
    private Short year;
    @Column(nullable = false, length = 2) private String market;
    @Column(precision = 8, scale = 2) private BigDecimal batteryCapacityKwh;
    @Column(nullable = false, length = 32) private String batteryCapacityBasis = "UNKNOWN";
    @Column(precision = 8, scale = 2) private BigDecimal rangeKm;
    @Column(length = 16) private String rangeStandard;
    @JdbcTypeCode(SqlTypes.ARRAY) @Column(nullable = false)
    private List<String> connectorTypes = new ArrayList<>();
    @Column(precision = 7, scale = 2) private BigDecimal maxAcKw;
    @Column(precision = 7, scale = 2) private BigDecimal maxDcKw;
    @Column(length = 2048) private String imageUrl;
    @Column(length = 2048) private String sourceUrl;
    private LocalDate verifiedAt;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16)
    private Status status = Status.DRAFT;
    @Version @Column(nullable = false) private Long version;
    private UUID createdBy;
    private UUID updatedBy;
    @Column(nullable = false, updatable = false) private Instant createdAt;
    @Column(nullable = false) private Instant updatedAt;
    @PrePersist void onCreate() { createdAt = updatedAt = Instant.now(); }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }
}
