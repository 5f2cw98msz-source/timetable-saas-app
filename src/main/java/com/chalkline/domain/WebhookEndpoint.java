package com.chalkline.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * A URL to notify when a timetable changes -- the general-purpose way to plug
 * this into anything else the institution runs, including Zapier or Power
 * Automate, without writing an integration for each one.
 *
 * Every delivery is signed with the secret so the receiver can prove the call
 * really came from here.
 */
@Entity
@Table(name = "webhook_endpoints", indexes = @Index(name = "ix_webhook_org", columnList = "organisation_id"))
public class WebhookEndpoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "organisation_id", nullable = false, foreignKey = @ForeignKey(name = "fk_webhook_org"))
    private Organisation organisation;

    @Column(nullable = false, length = 500)
    private String url;

    @Column(nullable = false, length = 80)
    private String secret;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "last_status", length = 80)
    private String lastStatus;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected WebhookEndpoint() {
    }

    public WebhookEndpoint(Organisation organisation, String url, String secret) {
        this.organisation = organisation;
        this.url = url;
        this.secret = secret;
    }

    public Long getId() { return id; }
    public Organisation getOrganisation() { return organisation; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getSecret() { return secret; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getLastStatus() { return lastStatus; }
    public void setLastStatus(String lastStatus) { this.lastStatus = lastStatus; }
    public Instant getLastAttemptAt() { return lastAttemptAt; }
    public void setLastAttemptAt(Instant lastAttemptAt) { this.lastAttemptAt = lastAttemptAt; }
    public Instant getCreatedAt() { return createdAt; }
}
