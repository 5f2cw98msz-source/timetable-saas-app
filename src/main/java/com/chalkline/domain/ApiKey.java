package com.chalkline.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * A key for the public API.
 *
 * Only a HASH of the key is stored, exactly as for a password: a leaked
 * database should not hand over working credentials. The plain key is shown
 * once, at creation, and never again. The short prefix is kept in the clear
 * so a key can be recognised in a list without revealing it.
 */
@Entity
@Table(name = "api_keys", indexes = @Index(name = "ix_apikey_org", columnList = "organisation_id"))
public class ApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "organisation_id", nullable = false, foreignKey = @ForeignKey(name = "fk_apikey_org"))
    private Organisation organisation;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(name = "key_hash", nullable = false, length = 100)
    private String keyHash;

    /** e.g. "ck_live_8fa2" -- enough to identify, not enough to use. */
    @Column(name = "key_prefix", nullable = false, length = 24)
    private String keyPrefix;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(nullable = false)
    private boolean revoked = false;

    protected ApiKey() {
    }

    public ApiKey(Organisation organisation, String name, String keyHash, String keyPrefix) {
        this.organisation = organisation;
        this.name = name;
        this.keyHash = keyHash;
        this.keyPrefix = keyPrefix;
    }

    public Long getId() { return id; }
    public Organisation getOrganisation() { return organisation; }
    public String getName() { return name; }
    public String getKeyHash() { return keyHash; }
    public String getKeyPrefix() { return keyPrefix; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(Instant lastUsedAt) { this.lastUsedAt = lastUsedAt; }
    public boolean isRevoked() { return revoked; }
    public void setRevoked(boolean revoked) { this.revoked = revoked; }
}
