package com.navio.usermanagementservice.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A destination guide written by administrators. Every administrator sees and
 * edits every guide; {@link #version} keeps two of them from overwriting each
 * other. Travelers only ever see published guides.
 */
@Entity
@Table(name = "guides", schema = "iam")
@Getter @Setter @NoArgsConstructor
public class Guide {
    public enum Status { DRAFT, PUBLISHED }

    @Id private UUID id;
    @Column(nullable = false, length = 120) private String name;
    @Column(nullable = false, length = 100) private String country = "";
    @Column(length = 2) private String countryCode;
    @Column(nullable = false, length = 16) private String destinationProvider;
    @Column(nullable = false, length = 255) private String destinationPlaceId;
    @Column(nullable = false, length = 200) private String destinationName;
    @Column(nullable = false) private Double destinationLat;
    @Column(nullable = false) private Double destinationLng;
    @Column(nullable = false, columnDefinition = "text") private String description = "";
    @Column(nullable = false, length = 300) private String coverPlaceId;
    /** The editor document, validated as {@code GuideDtos.Content} before it is stored. */
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false)
    private Map<String, Object> content = new HashMap<>();
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16)
    private Status status = Status.DRAFT;
    @Version @Column(nullable = false) private Long version;
    private UUID createdBy;
    private UUID updatedBy;
    private Instant publishedAt;
    @Column(nullable = false, updatable = false) private Instant createdAt;
    @Column(nullable = false) private Instant updatedAt;

    @PrePersist void onCreate() { createdAt = updatedAt = Instant.now(); }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }
}
