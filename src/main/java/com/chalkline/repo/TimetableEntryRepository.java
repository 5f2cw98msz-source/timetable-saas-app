package com.chalkline.repo;

import com.chalkline.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TimetableEntryRepository extends JpaRepository<TimetableEntry, Long> {

    Optional<TimetableEntry> findByDayIndexAndSlotIndexAndLecturer(int dayIndex, int slotIndex, User lecturer);

    List<TimetableEntry> findByLecturerOrderByDayIndexAscSlotIndexAsc(User lecturer);

    /** Every class in one cell, across the organisation. Used for clash checks. */
    List<TimetableEntry> findByOrganisationAndDayIndexAndSlotIndex(
            Organisation organisation, int dayIndex, int slotIndex);

    /** The whole week for one organisation, in a single query. */
    List<TimetableEntry> findByOrganisationOrderByDayIndexAscSlotIndexAsc(Organisation organisation);

    long countByOrganisation(Organisation organisation);

    long countByCourse(Course course);

    long countByRoom(Room room);

    long countByLecturer(User lecturer);

    void deleteByLecturer(User lecturer);
}
