package com.navio.usermanagementservice.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.Instant;
import java.util.UUID;

/**
 * One color in the admin-managed palette.
 *
 * <p>The hex never changes after creation: trips and guides store only the id,
 * so retiring a color hides it from pickers while every saved reference keeps
 * rendering the same color. Deleting a retired color moves its id to
 * {@link DeletedPaletteColor}, and saved references fall back to the default.
 */
@Entity
@Table(name = "palette_colors", schema = "iam")
@Getter @Setter @NoArgsConstructor
public class PaletteColor {
    @Id @Column(length = 40) private String id;
    @Column(nullable = false, length = 40) private String name;
    @Column(nullable = false, length = 7, updatable = false) private String hex;
    @Column(nullable = false) private Integer sortOrder;
    /** Label color on this color in the light theme: {@code auto}, {@code white} or {@code black}. */
    @Column(nullable = false, length = 5) private String textOnLight = "auto";
    @Column(nullable = false, length = 5) private String textOnDark = "auto";
    private Instant retiredAt;
    private UUID createdBy;
    private UUID updatedBy;
    @Column(nullable = false, updatable = false) private Instant createdAt;
    @Column(nullable = false) private Instant updatedAt;
    @PrePersist void onCreate() { createdAt = updatedAt = Instant.now(); }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }

    public boolean isRetired() { return retiredAt != null; }
}
