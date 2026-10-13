package com.navio.usermanagementservice.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** The shared Navio team library. Its destination uniqueness is independent of user guides. */
@Entity
@Table(name = "guides", schema = "iam")
public class Guide extends GuideDocument {
    public enum Status { DRAFT, PUBLISHED }
}
