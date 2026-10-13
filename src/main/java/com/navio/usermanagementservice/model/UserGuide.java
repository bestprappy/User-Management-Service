package com.navio.usermanagementservice.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import java.util.UUID;

/** A traveler's guide. Only its owner can access drafts or change publication. */
@Entity @Table(name = "user_guides", schema = "iam") @Getter @Setter
public class UserGuide extends GuideDocument {
    @Column(nullable = false, updatable = false) private UUID ownerId;
}
