package com.chalkline.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;

/**
 * One scheduled class: a course, in a room, taught by one lecturer, in one
 * day/time slot of the weekly grid.
 *
 * Timetables are personal -- at most one entry per (day, slot, lecturer), so
 * two lecturers can teach at the same time without clashing with each other.
 * Whether they may also share a ROOM is an organisation setting.
 *
 * The organisation is stored here as well as on the lecturer. It is
 * redundant, and it is worth it: every tenant-scoped query filters on it
 * directly instead of joining through users, which is the query that runs on
 * every page load.
 */
@Entity
@Table(
        name = "timetable_entries",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_entry_day_slot_lecturer",
                columnNames = {"day_index", "slot_index", "lecturer_id"}
        ),
        indexes = {
                @Index(name = "ix_entry_org_day_slot", columnList = "organisation_id,day_index,slot_index"),
                @Index(name = "ix_entry_lecturer", columnList = "lecturer_id")
        }
)
public class TimetableEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "organisation_id", nullable = false, foreignKey = @ForeignKey(name = "fk_entry_org"))
    private Organisation organisation;

    @Column(name = "day_index", nullable = false)
    private int dayIndex;

    @Column(name = "slot_index", nullable = false)
    private int slotIndex;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "course_id", nullable = false, foreignKey = @ForeignKey(name = "fk_entry_course"))
    private Course course;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "room_id", nullable = false, foreignKey = @ForeignKey(name = "fk_entry_room"))
    private Room room;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "lecturer_id", nullable = false, foreignKey = @ForeignKey(name = "fk_entry_lecturer"))
    private User lecturer;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected TimetableEntry() {
        // required by JPA
    }

    public TimetableEntry(int dayIndex, int slotIndex, Course course, Room room, User lecturer) {
        this.organisation = lecturer.getOrganisation();
        this.dayIndex = dayIndex;
        this.slotIndex = slotIndex;
        this.course = course;
        this.room = room;
        this.lecturer = lecturer;
    }

    public Long getId() {
        return id;
    }

    public Organisation getOrganisation() {
        return organisation;
    }

    public int getDayIndex() {
        return dayIndex;
    }

    public int getSlotIndex() {
        return slotIndex;
    }

    public Course getCourse() {
        return course;
    }

    public void setCourse(Course course) {
        this.course = course;
        this.updatedAt = Instant.now();
    }

    public Room getRoom() {
        return room;
    }

    public void setRoom(Room room) {
        this.room = room;
        this.updatedAt = Instant.now();
    }

    public User getLecturer() {
        return lecturer;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TimetableEntry other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
