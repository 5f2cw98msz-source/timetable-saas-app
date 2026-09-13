package com.chalkline;

import com.chalkline.service.*;
import com.chalkline.web.view.GridView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Whose timetable a person may touch, and what counts as a clash. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:rules;DB_CLOSE_DELAY=-1",
        "app.demo.email="
})
@Import(TestFixtures.class)
@Transactional
class TimetableRulesTest {

    @Autowired TestFixtures fixtures;
    @Autowired TimetableService timetableService;

    TestFixtures.Fixture uni;

    @BeforeEach
    void setUp() {
        uni = fixtures.institution("Test University", "test.edu");
    }

    @Test
    @DisplayName("a lecturer can schedule a class on their own timetable")
    void lecturerSchedulesOwnClass() {
        var saved = timetableService.saveEntry(uni.ada(), uni.ada().getEmail(), 0, 0,
                uni.maths().getId(), uni.lab1().getId());

        assertThat(saved.getLecturer().getEmail()).isEqualTo("ada@test.edu");
        assertThat(timetableService.buildGrid(uni.ada(), null).classCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a lecturer cannot touch somebody else's timetable")
    void lecturerCannotTouchAnother() {
        assertThatThrownBy(() -> timetableService.saveEntry(
                uni.ada(), uni.alan().getEmail(), 0, 0, uni.maths().getId(), uni.lab1().getId()))
                .isInstanceOf(AccessDeniedForLecturerException.class);

        assertThatThrownBy(() -> timetableService.clearEntry(uni.ada(), uni.alan().getEmail(), 0, 0))
                .isInstanceOf(AccessDeniedForLecturerException.class);

        assertThatThrownBy(() -> timetableService.findEntry(uni.ada(), uni.alan().getEmail(), 0, 0))
                .isInstanceOf(AccessDeniedForLecturerException.class);
    }

    @Test
    @DisplayName("viewing falls back to your own grid, but editing never does")
    void viewingFallsBackButEditingDoesNot() {
        assertThat(timetableService.resolveTarget(uni.ada(), uni.alan().getEmail()))
                .isEqualTo("ada@test.edu");

        // An edit keeps the name so the permission check can refuse it, rather
        // than silently writing the class to the wrong lecturer's grid.
        assertThat(timetableService.resolveEditTarget(uni.ada(), uni.alan().getEmail()))
                .isEqualTo("alan@test.edu");
        assertThat(timetableService.resolveEditTarget(uni.ada(), null)).isEqualTo("ada@test.edu");
    }

    @Test
    @DisplayName("two lecturers cannot hold the same room at the same time")
    void roomClashIsRefused() {
        timetableService.saveEntry(uni.admin(), uni.ada().getEmail(), 0, 0,
                uni.maths().getId(), uni.lab1().getId());

        assertThatThrownBy(() -> timetableService.saveEntry(
                uni.admin(), uni.alan().getEmail(), 0, 0, uni.physics().getId(), uni.lab1().getId()))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("already booked by Ada Lovelace");
    }

    @Test
    @DisplayName("the room clash check can be switched off per institution")
    void roomClashCheckIsOptional() {
        uni.organisation().setPreventRoomClashes(false);

        timetableService.saveEntry(uni.admin(), uni.ada().getEmail(), 0, 0,
                uni.maths().getId(), uni.lab1().getId());
        timetableService.saveEntry(uni.admin(), uni.alan().getEmail(), 0, 0,
                uni.physics().getId(), uni.lab1().getId());

        assertThat(timetableService.entriesFor(uni.organisation())).hasSize(2);
    }

    @Test
    @DisplayName("the same time in a different room is fine")
    void differentRoomSameTimeIsAllowed() {
        timetableService.saveEntry(uni.admin(), uni.ada().getEmail(), 0, 0,
                uni.maths().getId(), uni.lab1().getId());
        timetableService.saveEntry(uni.admin(), uni.alan().getEmail(), 0, 0,
                uni.physics().getId(), uni.lab2().getId());

        GridView combined = timetableService.buildGrid(uni.admin(), TimetableService.COMBINED);
        assertThat(combined.editable()).isFalse();
        assertThat(combined.rows().get(0).cells().get(0).entries()).hasSize(2);
    }

    @Test
    @DisplayName("saving over a full slot replaces rather than duplicating")
    void savingTwiceReplaces() {
        timetableService.saveEntry(uni.ada(), uni.ada().getEmail(), 3, 4,
                uni.maths().getId(), uni.lab1().getId());
        timetableService.saveEntry(uni.ada(), uni.ada().getEmail(), 3, 4,
                uni.physics().getId(), uni.lab2().getId());

        GridView grid = timetableService.buildGrid(uni.ada(), null);
        assertThat(grid.classCount()).isEqualTo(1);
        assertThat(grid.rows().get(4).cells().get(3).entries().get(0).courseCode()).isEqualTo("PHY101");
    }

    @Test
    @DisplayName("a class larger than the room raises a capacity warning, but is still allowed")
    void capacityWarningIsAdvisoryOnly() {
        // PHY101 expects 120 students; Lab 1 seats 30.
        timetableService.saveEntry(uni.ada(), uni.ada().getEmail(), 1, 1,
                uni.physics().getId(), uni.lab1().getId());

        var entry = timetableService.buildGrid(uni.ada(), null)
                .rows().get(1).cells().get(1).entries().get(0);

        assertThat(entry.hasCapacityWarning()).isTrue();
        assertThat(entry.capacityWarning()).contains("seats 30").contains("expects 120");
    }

    @Test
    @DisplayName("clearing empties the slot and is harmless when already empty")
    void clearingWorks() {
        timetableService.saveEntry(uni.ada(), uni.ada().getEmail(), 0, 0,
                uni.maths().getId(), uni.lab1().getId());

        assertThat(timetableService.clearEntry(uni.ada(), uni.ada().getEmail(), 0, 0)).isTrue();
        assertThat(timetableService.clearEntry(uni.ada(), uni.ada().getEmail(), 0, 0)).isFalse();
    }

    @Test
    @DisplayName("a day or slot outside the configured grid is refused")
    void outOfRangeCellRefused() {
        assertThatThrownBy(() -> timetableService.saveEntry(
                uni.ada(), uni.ada().getEmail(), 99, 0, uni.maths().getId(), uni.lab1().getId()))
                .isInstanceOf(ValidationException.class);

        assertThatThrownBy(() -> timetableService.saveEntry(
                uni.ada(), uni.ada().getEmail(), 0, -1, uni.maths().getId(), uni.lab1().getId()))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("each course keeps the same colour every time it is drawn")
    void courseColoursAreStable() {
        assertThat(com.chalkline.domain.Course.pickColourFor("CS101"))
                .isEqualTo(com.chalkline.domain.Course.pickColourFor("CS101"));
        assertThat(uni.maths().getColour()).isIn((Object[]) com.chalkline.domain.Course.PALETTE);
    }
}
