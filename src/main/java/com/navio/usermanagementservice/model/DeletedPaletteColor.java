package com.navio.usermanagementservice.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.Instant;
import java.util.UUID;

/**
 * The id of a palette color an administrator deleted permanently.
 *
 * <p>Kept forever so the id is never handed to a new color: every trip, guide
 * and shared snapshot that stored it renders the default color instead.
 */
@Entity
@Table(name = "deleted_palette_colors", schema = "iam")
@Getter @Setter @NoArgsConstructor
public class DeletedPaletteColor {
    @Id @Column(length = 40) private String id;
    @Column(nullable = false, length = 40) private String name;
    @Column(nullable = false, length = 7) private String hex;
    private UUID deletedBy;
    @Column(nullable = false) private Instant deletedAt;
}
