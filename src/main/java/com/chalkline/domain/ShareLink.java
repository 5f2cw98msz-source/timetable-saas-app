package com.chalkline.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * A read-only public page, so students can see a timetable without an account.
 *
 * The token is the only thing protecting it, so it is long and random, and a
 * link can be switched off without deleting the timetable behind it.
 */
@Entity
@Table(
        name = "share_links",
        uniqueConstraints = @UniqueConstraint(name = "uk_share_token", columnNames = "token"),
        indexes = @Index(name = "ix_share_org", columnList = "organisation_id")
)
public class ShareLink {

    public enum Scope {
        WHOLE_ORGANISATION("Everyone's timetable"),
        SINGLE_LECTURER("One lecturer's timetable");

        private final String label;

        Scope(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "organisation_id", nullable = false, foreignKey = @ForeignKey(name = "fk_share_org"))
    private Organisation organisation;

    @Column(nullable = false, length = 64)
    private String token;

    @Column(nullable = false, length = 120)
    private String label;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Scope scope = Scope.WHOLE_ORGANISATION;

    /** Only set when the scope is SINGLE_LECTURER. */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "lecturer_id", foreignKey = @ForeignKey(name = "fk_share_lecturer"))
    private User lecturer;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "view_count", nullable = false)
    private long viewCount = 0;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected ShareLink() {
    }

    public ShareLink(Organisation organisation, String token, String label, Scope scope, User lecturer) {
        this.organisation = organisation;
        this.token = token;
        this.label = label;
        this.scope = scope;
        this.lecturer = lecturer;
    }

    public Long getId() { return id; }
    public Organisation getOrganisation() { return organisation; }
    public String getToken() { return token; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public Scope getScope() { return scope; }
    public User getLecturer() { return lecturer; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public long getViewCount() { return viewCount; }
    public void recordView() { this.viewCount++; }
    public Instant getCreatedAt() { return createdAt; }
}
