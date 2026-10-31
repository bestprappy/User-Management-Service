package com.navio.usermanagementservice.model;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Navio profile keyed by the Keycloak subject.
 *
 * <p>Credentials, sessions, and account enablement live in Keycloak — never
 * here. This entity holds only the application-side profile, which is why there
 * is no password, no MFA secret, and no token column to leak.
 */
@Entity
@Table(name = "users", schema = "iam")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Keycloak {@code sub}. Unique; the join key for every authenticated request. */
    @Column(name = "auth_subject", nullable = false, updatable = false, length = 128)
    private String authSubject;

    @Column(nullable = false, length = 255)
    private String email;

    @Column(name = "display_name", nullable = false, length = 120)
    private String displayName;

    @Column(name = "avatar_media_id")
    private UUID avatarMediaId;

    /** Public, unique, lowercase handle. Null only for rows written by an older image. */
    @Column(length = 30)
    private String username;

    @Column(name = "username_changed_at")
    private Instant usernameChangedAt;

    @Column(length = 280)
    private String bio;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "travel_interests", nullable = false)
    private List<String> travelInterests = new ArrayList<>();

    @Column(name = "travel_pace", length = 20)
    private String travelPace;

    /** Whether interests and pace appear on the public profile. */
    @Builder.Default
    @Column(name = "show_travel_preferences", nullable = false)
    private Boolean showTravelPreferences = Boolean.TRUE;

    @Column(name = "banner_media_id")
    private UUID bannerMediaId;

    @Builder.Default
    @Convert(converter = UserStatusConverter.class)
    @Column(nullable = false, length = 30)
    private UserStatus status = UserStatus.ACTIVE;

    @Column(length = 20)
    private String locale;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "country_code", length = 2, columnDefinition = "char(2)")
    private String countryCode;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "preferences_jsonb", nullable = false)
    private Map<String, Object> preferences = new HashMap<>();

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Soft-delete marker. Rows are retained so audit references stay resolvable. */
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
        if (status == null) {
            status = UserStatus.ACTIVE;
        }
        if (preferences == null) {
            preferences = new HashMap<>();
        }
        if (travelInterests == null) {
            travelInterests = new ArrayList<>();
        }
        if (showTravelPreferences == null) {
            showTravelPreferences = Boolean.TRUE;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    public boolean isDeleted() {
        return deletedAt != null || status == UserStatus.DELETED;
    }
}
