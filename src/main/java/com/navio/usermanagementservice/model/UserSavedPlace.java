package com.navio.usermanagementservice.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

import java.time.Instant;
import java.util.UUID;

/**
 * A reusable personal anchor — the user's home, work, or any place they start
 * and end days from often.
 *
 * <p>Trip Planning copies the resolved name and coordinates into the day anchor
 * rather than referencing this row, so correcting "Home" later never rewrites
 * the assumptions behind a trip that was already planned.
 *
 * <p>A row here can hold a real home address. Anything that publishes a trip
 * must drop anchors that came from a saved place rather than serialising them.
 */
@Entity
@Table(name = "user_saved_places", schema = "iam")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserSavedPlace {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** What the user calls it — "Home", "Mum's house", "Office". */
    @Column(nullable = false, length = 80)
    private String label;

    @Builder.Default
    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private SavedPlaceKind kind = SavedPlaceKind.CUSTOM;

    /** The resolved place name, which may differ from the user's label. */
    @Column(nullable = false, length = 255)
    private String name;

    @Column(length = 512)
    private String address;

    @Column(nullable = false)
    private Double lat;

    @Column(nullable = false)
    private Double lng;

    /** The provider id this was resolved from, when it came from a search. */
    @Column(name = "provider_place_id", length = 512)
    private String providerPlaceId;

    @Builder.Default
    @Column(name = "is_default", nullable = false)
    private boolean isDefault = false;

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
        if (kind == null) {
            kind = SavedPlaceKind.CUSTOM;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    /**
     * HOME and WORK are singletons per user so the planner can offer them as
     * one-tap choices; everything else is CUSTOM.
     */
    public enum SavedPlaceKind {
        HOME,
        WORK,
        CUSTOM
    }
}
