package com.chalkline.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Who changed what, and when.
 *
 * Deliberately stores the actor's NAME as text rather than only a foreign key:
 * an audit trail has to stay readable after the account it refers to has been
 * deleted, which is exactly when someone tends to go looking at it.
 */
@Entity
@Table(name = "audit_events", indexes = @Index(name = "ix_audit_org_at", columnList = "organisation_id,at"))
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "organisation_id", nullable = false, foreignKey = @ForeignKey(name = "fk_audit_org"))
    private Organisation organisation;

    @Column(name = "actor_name", nullable = false, length = 160)
    private String actorName;

    @Column(name = "actor_email", length = 190)
    private String actorEmail;

    @Column(nullable = false, length = 60)
    private String action;

    @Column(nullable = false, length = 500)
    private String detail;

    @Column(nullable = false)
    private Instant at = Instant.now();

    protected AuditEvent() {
    }

    public AuditEvent(Organisation organisation, User actor, String action, String detail) {
        this.organisation = organisation;
        this.actorName = actor == null ? "System" : actor.getDisplayName();
        this.actorEmail = actor == null ? null : actor.getEmail();
        this.action = action;
        this.detail = detail == null ? "" : detail.substring(0, Math.min(detail.length(), 500));
    }

    public Long getId() { return id; }
    public Organisation getOrganisation() { return organisation; }
    public String getActorName() { return actorName; }
    public String getActorEmail() { return actorEmail; }
    public String getAction() { return action; }
    public String getDetail() { return detail; }
    public Instant getAt() { return at; }
}
