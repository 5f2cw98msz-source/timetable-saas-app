package com.chalkline.domain;

import jakarta.persistence.*;
import java.util.Objects;

/** A module that can be scheduled, e.g. "CS101 - Intro to Programming". */
@Entity
@Table(
        name = "courses",
        uniqueConstraints = @UniqueConstraint(name = "uk_course_org_code",
                columnNames = {"organisation_id", "code"}),
        indexes = @Index(name = "ix_course_org", columnList = "organisation_id")
)
public class Course {

    /**
     * Distinct, readable hues for the grid. Timetables are read at a glance,
     * and colour does that far better than text -- but never colour alone:
     * every cell still shows its course code, so the grid works for a
     * colour-blind reader and in black-and-white print.
     */
    public static final String[] PALETTE = {
            "violet", "teal", "amber", "coral", "sky", "lime", "pink", "indigo"
    };

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "organisation_id", nullable = false, foreignKey = @ForeignKey(name = "fk_course_org"))
    private Organisation organisation;

    @Column(nullable = false, length = 32)
    private String code;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(length = 24)
    private String colour;

    /** Used by the capacity warning when a class is bigger than the room. */
    @Column(name = "expected_students")
    private Integer expectedStudents;

    protected Course() {
        // required by JPA
    }

    public Course(Organisation organisation, String code, String name) {
        this.organisation = organisation;
        this.code = code;
        this.name = name;
        this.colour = pickColourFor(code);
    }

    /**
     * A stable colour derived from the code, so the same course is the same
     * colour every time without anybody having to choose one.
     */
    public static String pickColourFor(String code) {
        int hash = Math.abs(Objects.hashCode(code == null ? "" : code.toLowerCase()));
        return PALETTE[hash % PALETTE.length];
    }

    public Long getId() {
        return id;
    }

    public Organisation getOrganisation() {
        return organisation;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getColour() {
        return colour == null ? pickColourFor(code) : colour;
    }

    public void setColour(String colour) {
        this.colour = colour;
    }

    public Integer getExpectedStudents() {
        return expectedStudents;
    }

    public void setExpectedStudents(Integer expectedStudents) {
        this.expectedStudents = expectedStudents;
    }

    public String getLabel() {
        return code + " - " + name;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Course other)) return false;
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
