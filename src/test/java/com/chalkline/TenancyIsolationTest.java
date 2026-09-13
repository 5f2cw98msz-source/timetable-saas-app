package com.chalkline;

import com.chalkline.domain.Role;
import com.chalkline.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The most important tests in the project.
 *
 * Once this is sold to more than one institution, the thing that would end it
 * is one customer seeing another's data. Every one of these is an attempt to
 * cross that line using an id or an email address from the other tenant.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:tenancy;DB_CLOSE_DELAY=-1",
        "app.demo.email="
})
@Import(TestFixtures.class)
@Transactional
class TenancyIsolationTest {

    @Autowired TestFixtures fixtures;
    @Autowired TimetableService timetableService;
    @Autowired UserService userService;
    @Autowired CatalogService catalogService;

    TestFixtures.Fixture north;
    TestFixtures.Fixture south;

    @BeforeEach
    void setUp() {
        north = fixtures.institution("North College", "north.edu");
        south = fixtures.institution("South College", "south.edu");
    }

    @Test
    @DisplayName("two institutions can use the same room and course names independently")
    void namesDoNotCollideAcrossInstitutions() {
        // Both created "Lab 1" and "MTH110" in setUp, and neither clashed.
        assertThat(catalogService.findRooms(north.organisation())).hasSize(2);
        assertThat(catalogService.findRooms(south.organisation())).hasSize(2);
        assertThat(catalogService.findCourses(north.organisation())).hasSize(2);
        assertThat(catalogService.findCourses(south.organisation())).hasSize(2);
    }

    @Test
    @DisplayName("an admin cannot schedule onto a lecturer in another institution")
    void cannotScheduleAcrossInstitutions() {
        assertThatThrownBy(() -> timetableService.saveEntry(
                north.admin(), south.ada().getEmail(), 0, 0,
                north.maths().getId(), north.lab1().getId()))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("in your organisation");
    }

    @Test
    @DisplayName("an admin cannot use another institution's course or room")
    void cannotUseAnotherInstitutionsCatalogue() {
        assertThatThrownBy(() -> timetableService.saveEntry(
                north.admin(), north.ada().getEmail(), 0, 0,
                south.maths().getId(), north.lab1().getId()))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Pick a course");

        assertThatThrownBy(() -> timetableService.saveEntry(
                north.admin(), north.ada().getEmail(), 0, 0,
                north.maths().getId(), south.lab1().getId()))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Pick a room");
    }

    @Test
    @DisplayName("an admin cannot delete, suspend or reset a member of another institution")
    void cannotManageAnotherInstitutionsPeople() {
        assertThatThrownBy(() ->
                userService.delete(south.ada().getId(), north.organisation(), north.admin()))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("not part of your organisation");

        assertThatThrownBy(() ->
                userService.setEnabled(south.ada().getId(), false, north.organisation(), north.admin()))
                .isInstanceOf(ValidationException.class);

        assertThatThrownBy(() ->
                userService.resetPassword(south.ada().getId(), "newpassword1",
                        north.organisation(), north.admin()))
                .isInstanceOf(ValidationException.class);

        assertThatThrownBy(() ->
                userService.changeRole(south.ada().getId(), Role.ADMIN,
                        north.organisation(), north.admin()))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("an admin cannot delete another institution's rooms or courses")
    void cannotDeleteAnotherInstitutionsCatalogue() {
        assertThatThrownBy(() ->
                catalogService.deleteRoom(south.lab1().getId(), north.organisation(), north.admin()))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("not part of your organisation");

        assertThatThrownBy(() ->
                catalogService.deleteCourse(south.maths().getId(), north.organisation(), north.admin()))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("not part of your organisation");
    }

    @Test
    @DisplayName("the combined view shows only your own institution's classes")
    void combinedViewDoesNotLeak() {
        timetableService.saveEntry(north.admin(), north.ada().getEmail(), 0, 0,
                north.maths().getId(), north.lab1().getId());
        timetableService.saveEntry(south.admin(), south.ada().getEmail(), 0, 0,
                south.maths().getId(), south.lab1().getId());

        var northGrid = timetableService.buildGrid(north.admin(), TimetableService.COMBINED);
        var southGrid = timetableService.buildGrid(south.admin(), TimetableService.COMBINED);

        assertThat(northGrid.classCount()).isEqualTo(1);
        assertThat(southGrid.classCount()).isEqualTo(1);
        assertThat(northGrid.rows().get(0).cells().get(0).entries().get(0).lecturerEmail())
                .isEqualTo("ada@north.edu");
    }

    @Test
    @DisplayName("the lecturer dropdown lists only your own institution")
    void lecturerListDoesNotLeak() {
        var grid = timetableService.buildGrid(north.admin(), TimetableService.COMBINED);

        assertThat(grid.lecturers())
                .extracting(com.chalkline.web.view.GridView.LecturerOption::email)
                .containsExactlyInAnyOrder("ada@north.edu", "alan@north.edu");
    }

    @Test
    @DisplayName("a room booked in one institution does not block the same room name in another")
    void clashesAreScopedToOneInstitution() {
        timetableService.saveEntry(north.admin(), north.ada().getEmail(), 0, 0,
                north.maths().getId(), north.lab1().getId());

        // South's "Lab 1" is a different room entirely, so this must succeed.
        timetableService.saveEntry(south.admin(), south.ada().getEmail(), 0, 0,
                south.maths().getId(), south.lab1().getId());

        assertThat(timetableService.entriesFor(south.organisation())).hasSize(1);
    }
}
