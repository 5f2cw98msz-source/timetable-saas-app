package com.chalkline.domain;

import jakarta.persistence.*;
import java.util.Objects;

/** A teaching space. Capacity is optional; 0 means "not recorded". */
@Entity
@Table(
        name = "rooms",
        uniqueConstraints = @UniqueConstraint(name = "uk_room_org_name",
                columnNames = {"organisation_id", "name"}),
        indexes = @Index(name = "ix_room_org", columnList = "organisation_id")
)
public class Room {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "organisation_id", nullable = false, foreignKey = @ForeignKey(name = "fk_room_org"))
    private Organisation organisation;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false)
    private int capacity;

    /** Free text, e.g. "Projector, lab PCs". Shown when picking a room. */
    @Column(length = 200)
    private String facilities;

    protected Room() {
        // required by JPA
    }

    public Room(Organisation organisation, String name, int capacity) {
        this.organisation = organisation;
        this.name = name;
        this.capacity = capacity;
    }

    public Long getId() {
        return id;
    }

    public Organisation getOrganisation() {
        return organisation;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getCapacity() {
        return capacity;
    }

    public void setCapacity(int capacity) {
        this.capacity = capacity;
    }

    public String getFacilities() {
        return facilities;
    }

    public void setFacilities(String facilities) {
        this.facilities = facilities;
    }

    public String getLabel() {
        return capacity > 0 ? name + " (seats " + capacity + ")" : name;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Room other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return getLabel();
    }
}
